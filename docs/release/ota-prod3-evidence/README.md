# M5.OTA.PROD.3 — evidence: Android production OTA ON / OFF

Снято 2026-10-21 в ветке `feature/m5-ota-android-production` на Mac владельца (Apple silicon, JDK 17, Android build-tools 36.0.0). Все проверки локальные:

- к `https://ota.mythweld.com`, VK Cloud и другой live-инфраструктуре не было ни одного обращения;
- ключи владельца (signing и OTA) не использовались;
- Gradle запускался с изолированным `GRADLE_USER_HOME`: кэши подключены через symlink, `~/.gradle/gradle.properties` не читался;
- переменные `TALENTFORGE_*` были сброшены.

Локальные пути заменены на `~`, `<worktree>`, `<scratch>`. APK и AAB не коммитятся.

## 1. Матрица сборок и guards (`run-matrix.sh`, HEAD `ad251a8`)

Скрипт собирает **неподписанные** release APK и Play AAB с OTA OFF и OTA ON. Permissions читаются из итоговых артефактов: `aapt2 dump permissions` для APK и разбор proto-манифеста `scripts/verify_play_bundle.py` для AAB. В dex проверяются закреплённые ключи и origin. Затем скрипт пытается собрать запрещённые конфигурации. Итог: [matrix.txt](matrix.txt), хвосты логов Gradle: [logs/](logs/).

| Артефакт | `INTERNET` | Ключи и origin в dex | Результат |
|---|---|---|---|
| release APK, OTA OFF (не задано) | нет | нет `tf-*`, нет `ota.mythweld.com` | PASS |
| release APK, OTA OFF (`=false`) | нет | то же | PASS |
| Play AAB, OTA OFF | нет | то же | PASS |
| release APK, OTA ON | **есть** | `https://ota.mythweld.com`, `tf-prod-2026a`, `tf-prod-2026b`; **нет** `tf-staging-*` и `test-*` | PASS |
| Play AAB, OTA ON | **есть** | то же | PASS |

Во всех артефактах есть `https://ota.invalid`: это значение по умолчанию тестовой фабрики `OtaConfig.forTesting`, приложение её не вызывает.

Отклонённые конфигурации, каждая со своей ошибкой (все PASS):
- другой origin;
- `http://` origin;
- fixture-keyring с `test-` ключами;
- Android staging в release и в play;
- iOS staging в release (новый guard);
- `enabled=yes`;
- сломанный OTA overlay без `INTERNET` в release и в play (проверка **merged manifest**);
- `talentforge.ota.*` в закоммиченном `gradle.properties`.

Повтор: `docs/release/ota-prod3-evidence/run-matrix.sh` из корня репозитория.

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
