# M5.OTA.PROD.3 — evidence: Android production OTA ON / OFF

Снято 2026-10-21 в ветке `feature/m5-ota-android-production` на Mac владельца (Apple silicon, JDK 17, Android build-tools 36.0.0). Все проверки локальные:

- к `https://ota.mythweld.com`, VK Cloud и другой live-инфраструктуре не было ни одного обращения;
- ключи владельца (signing и OTA) не использовались;
- Gradle запускался с изолированным `GRADLE_USER_HOME`: кэши подключены через symlink, `~/.gradle/gradle.properties` не читался;
- переменные `TALENTFORGE_*` были сброшены.

Локальные пути заменены на `~`, `<worktree>`, `<scratch>`. APK и AAB не коммитятся.

## 1. Матрица сборок и guards (`run-matrix.sh`, HEAD `300da91`)

**Как выполняется.**

- Матрица собирается во **временном `git worktree` HEAD**, который удаляется по `trap`.
- Отрицательные кейсы меняют файлы только в этой копии; рабочее дерево, из которого запущен скрипт, не изменяется. Меняются только `matrix.txt` и `logs/`.
- Скрипт отказывается работать (exit 2), если в `mobile/`, `scripts/`, `docs/ota/keys/` или в самих `run-matrix.sh` / `check_ota_artifact.py` есть локальные изменения. Так результат всегда относится к названному коммиту.
- Итог: [matrix.txt](matrix.txt), хвосты логов Gradle и результаты по каждому артефакту: [logs/](logs/). Любой `FAIL` даёт exit 1.

**Каждый артефакт** (неподписанные release APK и Play AAB) проверяется дважды:

- `INTERNET` в итоговом манифесте: `aapt2` для APK, разбор proto-манифеста `scripts/verify_play_bundle.py` для AAB;
- [`check_ota_artifact.py`](check_ota_artifact.py): точная таблица строк **всех dex** (`string_ids`, без `strings`), `GeneratedOtaSettings`, из которого собран артефакт, и закоммиченный keyring против закреплённых **SHA-256 SPKI** (`tf-prod-2026a` `1fac1b0d…`, `tf-prod-2026b` `6997b3d4…`, [production-deployment §4.4](../../ota/production-deployment.md)).

| Артефакт | `INTERNET` | Утверждения `check_ota_artifact.py` | Результат |
|---|---|---|---|
| release APK, OTA OFF (не задано) | нет | все 4 канала выключены, без URL и ключей, без trust anchor; в dex нет `ota.mythweld.com`, ни одного key id и ни одного P-256 SPKI; keyring = отпечатки (10) | PASS |
| release APK, OTA OFF (`=false`) | нет | то же (10) | PASS |
| Play AAB, OTA OFF | нет | то же (10) | PASS |
| release APK, OTA ON | **есть** | `androidProduction`: enabled, baseUrl ровно `https://ota.mythweld.com`, ключи ровно `tf-prod-2026a/b` с SPKI из keyring и закреплёнными SHA-256; остальные каналы выключены; в dex ровно один origin, ровно два key id, ровно два SPKI с закреплёнными SHA-256, нет `tf-staging-*` и `test-*` (14) | PASS |
| Play AAB, OTA ON | **есть** | то же (14) | PASS |

**Самопроверка checker.** Он обязан отклонить подменённые входы, и все кейсы дают PASS:

- OFF-артефакт с ожиданиями ON;
- ON-артефакт с ожиданиями OFF;
- staging-ключ в середине и в конце списка ключей `GeneratedOtaSettings`;
- удалённый резервный ключ;
- другой origin;
- keyring с подменённым SPKI.

**Отклонённые конфигурации.** Каждая останавливается своей ошибкой, все PASS:

- другой origin и `http://` origin;
- fixture-keyring с `test-` ключами;
- Android staging в release и в play;
- iOS staging в release;
- `enabled=yes`;
- сломанный OTA overlay без `INTERNET` в release и в play (проверка **merged manifest**);
- `talentforge.ota.*` в закоммиченном `gradle.properties`;
- **изменённый закоммиченный keyring** (резервный ключ подменён SPKI основного): сборка останавливается и с OTA OFF, и с OTA ON;
- **изменённый закреплённый отпечаток** в `build.gradle.kts`: падает `verifyOtaSettingsRules`.

Итого 31 PASS, 0 FAIL.

**Первый прогон новой матрицы (`663b254`)** закончился `RESULT: FAIL`, exit 1. Причина — ошибка в самом checker: из `GeneratedOtaSettings` читался только первый ключ канала. Ошибка исправлена в `300da91`, туда же добавлены два кейса самопроверки, которые её ловят. Проверка dex того же прогона уже видела оба ключа.

Во всех артефактах есть строка `https://ota.invalid`: это значение по умолчанию тестовой фабрики `OtaConfig.forTesting`, приложение её не вызывает. Checker её не учитывает, потому что ищет только `ota.mythweld.com`.

Повтор: `docs/release/ota-prod3-evidence/run-matrix.sh` из корня репозитория (нужен `ANDROID_HOME`).

## 2. Эмуляторы (`emulator-check.sh`)

Отдельные AVD `tf-prod3-api35` (Android 15, API 35) и `tf-prod3-api30` (Android 11, API 30), Google APIs arm64. Запуск: `emulator -dns-server 127.0.0.1`. `ota.mythweld.com` на них не резолвится, и скрипт отказывается работать, если имя резолвится. Оба release APK подписаны одноразовым локальным ключом, созданным и удалённым в рамках проверки (не для публикации).

| Шаг | API 35 | API 30 |
|---|---|---|
| OTA OFF APK: нет `INTERNET` | PASS | PASS |
| OTA OFF: импорт реального кода билда (Warrior 13/51, `docs/build-sharing.md` §3) | PASS | PASS |
| OTA OFF About: «works offline», нет «Check for updates» | PASS | PASS |
| Обновление поверх (`install -r`) на OTA ON: `INTERNET: granted=true` | PASS | PASS |
| Холодный старт без сети: первый экран сразу, импортированный билд на месте | PASS | PASS |
| OTA ON About: новые тексты, нет «works offline», «Included with the app» | PASS | PASS |
| «Check for updates» без сети: «Couldn't check for updates: no connection», приложение работает | PASS | PASS |
| «Show details»: `ota.mythweld.com` в тексте | PASS | PASS |
| Сохранённый билд открывается в редакторе (вкладка Arms), без «not available» | PASS | PASS |
| Процесс жив, крашей приложения в crash buffer нет | PASS | PASS |

Подробности: [emulator-api35/steps.txt](emulator-api35/steps.txt) и [emulator-api30/steps.txt](emulator-api30/steps.txt), там же скриншоты.

**API 26 (minSdk) на эмуляторе НЕ запускался**: образ `system-images;android-26;…` на машине не установлен, а его скачивание требует согласия владельца. Проверено статически: `minSdkVersion=26` в манифесте APK (`aapt2`), `:composeApp:lintRelease` без ошибок. Самый старый реально запущенный API — 30.

## 2a. Физический телефон TECNO Spark 40 (`device-smoke.sh`, 2026-10-21)

TECNO KM5n (Spark 40), Android 15 (API 35), русский интерфейс. Это шаги 1–10 из [android-device-smoke §3](../../ota/android-device-smoke.md), выполненные через adb.

- **Что за приложение.** Debug-сборка `.otastaging`: отдельный пакет с отдельными данными. Она настроена только на локальный стенд `https://localhost:8443` через `adb reverse`, с эфемерным ключом `tf-staging-local-2210e2bb` и собственным CA стенда. Production-канал в этой сборке выключен (`enabled = false`), поэтому обращений к `ota.mythweld.com` не было.
- **Ваше приложение не затронуто.** Установленная release-сборка 1.0.0 и её данные остались как были (`lastUpdateTime` не изменился). `.otastaging` после прогона удалён, `adb reverse` снят, стенд и его ключи удалены.
- **Запросы.** Сервер стенда получил только `GET /v1/channels/staging/catalog.json` и `GET /v1/blobs/sha256/…` (манифест B), за два прогона 15 + 2 запроса.

| # | Шаг | Результат |
|---|---|---|
| 1 | Установка и запуск, стенд на `1-a-staged`: первый экран сразу, About «Сборка игры 1.60.1.70450», «Встроены в приложение» | PASS |
| 2 | Импорт реального кода билда (Воин 13/51, `1.60.1.70374`) | PASS |
| 3 | `2-b-staged`, «Проверить обновления» | PASS: «Данные талантов обновлены до…», «Загруженное обновление 1.60.1.70450-r1», `state.json` sequence 2, active r1 |
| 4 | Повторная проверка | PASS: «Данные талантов актуальны.» |
| 5 | Home → возврат в приложение | PASS: процесс жив |
| 6 | `force-stop` и холодный старт | PASS: r1 сразу активен |
| 7 | Нет соединения (`adb reverse` снят) | PASS: «Не удалось проверить обновления: нет соединения.», данные не изменились |
| 8 | Rollback на A (ниже встроенного floor) | PASS: «актуальны», sequence 3, r1 остаётся активным |
| 9 | `minDataLevel 2` | PASS: «Для новых данных талантов нужно обновить приложение.», sequence 4 |
| 10 | Повтор старого каталога | PASS: `SEQUENCE_REGRESSION`, sequence 4 остаётся |
| — | Библиотека билдов при всех проверках | PASS: SHA-256 файла не изменился |
| — | Сохранённый билд открывается в редакторе (вкладка «Оружие») | PASS |
| — | Крашей приложения нет | PASS |

Подробности: [device-tecno/steps.txt](device-tecno/steps.txt) и скриншоты. Первый прогон того же дня упал на шагах 3–4: скрипт не вернулся на Home после импорта, потому что импорт открывает редактор. Скрипт исправлен, второй прогон полностью PASS. На скриншоте 03 виден и новый текст приватности OTA-сборки на русском.

Этот прогон **не заменяет** M-1…M-5. Там нужна **release** OTA ON сборка, подписанная ключом владельца, и **production** origin.

## 3. Автоматизированные тесты (HEAD `ad251a8`)

| Команда | Результат |
|---|---|
| `ruff check .` · `ruff format --check .` · `mypy` | OK |
| `pytest` | 1565 passed, 11 deselected |
| `python3 scripts/sync_mobile_datasets.py --check` | OK |
| `./gradlew :shared:jvmTest --rerun` | 373 tests, 0 failed |
| `./gradlew :composeApp:desktopTest` | 819 tests, 0 failed, 54 skipped (opt-in: скриншоты, бенчмарк, remote; два локальных набора прогнаны отдельно, см. ниже) |
| `./gradlew :composeApp:assembleDebug` | OK |
| `:shared:compileKotlinIos{Arm64,SimulatorArm64}`, `:composeApp:compileKotlinIos{Arm64,SimulatorArm64}`, `:composeApp:linkDebugFramework{IosSimulatorArm64,IosArm64}` | OK |
| `:shared:iosSimulatorArm64Test` · `:composeApp:iosSimulatorArm64Test` | 190 / 7 tests, 0 failed |
| `:composeApp:lintRelease` | OK (9 warnings, 0 errors) |
| `scripts/ota_integration_check.sh --android` (локальный HTTPS-стенд с эфемерным ключом, Python publisher ↔ Kotlin client) | PASS: `OtaStagingE2ETest` 10/10 и `OtaPublisherInteropTest` 2/2, 0 skipped. Проверено: атомарная активация, открытый редактор остаётся на прежнем dataset, process death и resume, порча подписи/манифеста/blob, rollback floor, `minDataLevel 2`, офлайн-старт, транспорт (404/500/302, http, недоверенный сертификат) |

## 4. Ручные проверки владельца (не выполнены)

Процедура: [android-production-enablement §4](../../ota/android-production-enablement.md). Заполняет только владелец. Пока строка не заполнена, проверка считается **не выполненной**.

| # | Проверка | Дата (UTC) | Сборка (`versionCode`, SHA-256 APK) | Результат |
|---|---|---|---|---|
| M-1 | TECNO Spark 40, офлайн-старт | | | не выполнено |
| M-2 | TECNO Spark 40, загрузка `1.60.1.70450-r1` с production | | | не выполнено |
| M-3 | TECNO Spark 40, прерывание и продолжение загрузки | | | не выполнено |
| M-4 | TECNO Spark 40, открытый редактор во время активации | | | не выполнено |
| M-5 | TECNO Spark 40, обновление с 1.0.0 при сохранённых билдах | | | не выполнено |
| M-6 | Эмулятор API 26, реальный production HTTPS | | | не выполнено |
| M-7 | `dumpsys` permissions OTA ON / OFF | | | не выполнено |
| M-8 | `verify-live` и preflight перед загрузкой | | | не выполнено |
