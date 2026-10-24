# M5.IOS.3 — App Store Submission Preparation: отчёт

| | |
|---|---|
| Дата | 2026-10-24 |
| База | `main` @ `d51cbed` (после PR #80) |
| Итог | Пакет отправки готов к code review: тексты EN/RU, ответы анкет, runbook TestFlight, пять проверок. **В App Store Connect ничего не вносилось, сборка не загружалась, приложение не публиковалось.** Отправка заблокирована действиями владельца (§9) |
| Пакет | [submission/README.md](submission/README.md) · [runbook](submission/testflight-runbook.md) · [App Privacy §5](app-privacy.md#5-аудит-m5ios3-ota-on) · [iOS-политика §3](../../legal/ios/README.md#3-публикация-выполняет-только-владелец) · [evidence](evidence/m5-ios-3/) |
| Не изменялись | Android, игровой датасет, OTA production-каталог, `PRODUCT_BUNDLE`, код приложения, ссылка на политику в About |

Статусы: **PASS** — выполнено здесь и проверено; **BLOCKED** — нужно действие владельца; **NOT RUN** — не выполнялось
(причина указана).

## 1. Окружение

| | Локально (машина агента) | Требование |
|---|---|---|
| Xcode / SDK | **26.4.1 (17E202)**, `iphoneos26.4` / `iphonesimulator26.4` | Xcode 27.1 (27A9275) — **недоступен** (macOS 26.3.1 < 26.6, [readiness §1](readiness.md#1-окружение)) |
| Симуляторы | iPhone 17 Pro (6.3", Dynamic Island) и iPad Pro 13-inch (M5), runtime iOS 26.4.1 (23E254a) | iPhone 18 Pro или эквивалент с Dynamic Island (medium); iPad Pro 13" |

iPhone 17 Pro и iPhone 18 Pro относятся к одному классу дисплея App Store Connect («iPhone with Dynamic Island (medium)»,
1206 × 2622), поэтому снимки iPhone 17 Pro подходят без масштабирования. Сборка на Xcode 27.1 — задача CI
(`mobile-macos`) и подписанного архива владельца. Ничего из выполненного локально не выдаётся за проверку на Xcode 27.1.

## 2. Задача A — скриншоты App Store

| Пункт | Статус | Подробности |
|---|---|---|
| Release-сборка для съёмки | **PASS** | `xcodebuild -configuration Release` для симулятора, `ARCHS=arm64`, коммит `d51cbed`, OTA OFF (`** BUILD SUCCEEDED **`); установлена на два новых симулятора с языком устройства и чистой строкой статуса (`simctl status_bar override`: 9:41, полный заряд) |
| Съёмка экранов (iPhone 6.3" и iPad 13", EN и RU) | **BLOCKED** | для перехода между экранами нужны нажатия в симуляторе; инструмент управления симулятором требует разрешения пользователя на устройство («Let Claude use it»), запросы остались без ответа. Обходить это разрешение (AppleScript, клики по окну Simulator) агент не стал. **Ни одного скриншота не создано**; сгенерированные, смонтированные или взятые из старых доказательств изображения не используются |
| Валидатор скриншотов | **PASS** | `scripts/verify_appstore_screenshots.py` + 27 тестов (§7) |
| Отчёт о соответствии | требования и проверки — ниже; результат валидатора появится после съёмки | — |

Требования App Store Connect ([Screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/), прочитано 2026-10-24) и как пакет их проверяет:

| Требование Apple | Значение | Проверка |
|---|---|---|
| iPhone with Dynamic Island (medium) — **обязателен** («at least one screenshot») | 1206 × 2622 или 1179 × 2556 (портрет; ландшафт — транспонировано) | `png:*` (размер по классу дисплея) |
| iPad 13-inch — **обязателен**, если приложение работает на iPad | 2064 × 2752 или 2048 × 2732 | `png:*` |
| Формат | `.png` / `.jpeg` / `.jpg`; «Images can't include alpha channels or transparencies» | `png:*`: IHDR 8-bit RGB (тип 2), без `tRNS`, CRC чанков, полная распаковка IDAT |
| Количество | 1–10 на размер | `count:*`, нумерация `01…N` без пропусков |
| Локализации | English (U.S.) и Russian — одинаковый набор экранов | `locales-match:*` |
| Неизменность после съёмки | — | `manifest:*` (размеры, байты, SHA-256) |
| Реальный UI, верный язык, без уведомлений и отладочных элементов, без обрезанного интерфейса | — | визуальная проверка каждого файла; скрипт это не решает |
| iPhone Duo (1398 × 2034 / 2007 × 2853) | обязателен **с апреля 2027** для сборок на iOS 27.1 SDK+ | NOT RUN: для этой отправки не требуется; симулятора нет |

План съёмки (после разрешения доступа; файлы — `submission/screenshots/<locale>/<display>/`, `<display>` =
`iphone-6.3` / `ipad-13`):

| № | Файл | Экран | Состояние |
|---|---|---|---|
| 01 | `01-editor` | редактор | билд с распределёнными очками, выбран талант, инспектор (iPhone — нижний, B3) |
| 02 | `02-talent-details` | описание таланта | текущий и следующий ранг, требование, «Показать» |
| 03 | `03-class-picker` | выбор класса | девять классов |
| 04 | `04-build-library` | библиотека билдов | Home с «Продолжить» и недавними билдами или «Все билды» |
| 05 | `05-share-code` | обмен кодом | лист приложения «Поделиться билдом» с кодом и «Копировать код» |
| 06 | `06-import-preview` | импорт | «Импортировать этот билд?» — предпросмотр до сохранения |

На iPad экран 01 снимается в ландшафте (дерево + постоянная боковая панель). Билды для съёмки создаются в самом
приложении (выбор класса, `+`/`Max`), код для импорта — его же кнопкой «Копировать код». Набор `ru` снимается на
устройстве с языком ru, `en-US` — с английским. `simctl io screenshot` сохраняет PNG; если в нём окажется альфа-канал,
он удаляется без изменения пикселей (RGBA → RGB полностью непрозрачного изображения), без кадрирования и
масштабирования. Затем `verify_appstore_screenshots.py` и визуальная проверка каждого файла.

## 3. Задача B — метаданные

| Пункт | Статус | Доказательство |
|---|---|---|
| Name, Subtitle, Keywords, Description, Promotional Text EN/RU, App Review Notes, категории, Copyright, URL | **PASS** | [submission §2](submission/README.md#2-поля-app-store-connect--файлы); `verify_appstore_metadata.py` → PASS ([вывод](evidence/m5-ios-3/appstore-metadata.txt)) |
| Лимиты Apple (символы / байты) | **PASS** | Name/Subtitle ≤ 30, Promotional ≤ 170, Description ≤ 4000 символов; Keywords ≤ 100 байт, Review Notes ≤ 4000 байт ([Platform version information](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/), 2026-10-24) |
| Позиционирование: неофициальный планировщик талантов World of Warcraft: Forever | **PASS** | Subtitle и Description называют игру; Description и Review Notes — «unofficial / неофициальный», дисклеймер Blizzard; проверки `positioning:*` |
| OTA ON: загружаются только игровые данные JSON/PNG, не код; офлайн на встроенных и загруженных данных | **PASS** | раздел «Works offline / Работает без сети»; проверка `ota-on-claims` не пропускает утверждения сборки OTA OFF |
| What's New | — | у первой версии этого поля нет; файл из пакета удалён |
| Поддержка iPhone и iPad | **PASS** | `TARGETED_DEVICE_FAMILY = 1,2` (`verify_ios_project.py`), `UIDeviceFamily [1, 2]` в архиве; в Description — «on iPhone and iPad» |
| Утверждение текстов владельцем | **BLOCKED** | владелец |

## 4. Задача C — privacy и App Review

| Пункт | Статус | Доказательство |
|---|---|---|
| `privacy-ios.html` / `privacy-ios-en.html` соответствуют OTA ON | **PASS** | [app-privacy §5.1](app-privacy.md#51-что-проверено): моменты запросов, их состав, IP у CDN без утверждений о хранении, без идентификаторов и cookies, подпись, бэкап, разрешений нет. Тексты не изменены |
| Инструкция по публикации (дата, `Content-Type`, HTTP 200) | **PASS** | [legal/ios §3](../../legal/ios/README.md#3-публикация-выполняет-только-владелец) + `scripts/ios_privacy_pages.py render / verify-live` (14 тестов) |
| Страницы опубликованы | **BLOCKED** | `verify-live` 2026-10-24: обе — **HTTP 403** ([вывод](evidence/m5-ios-3/privacy-pages-verify-live.txt)) |
| Ссылка в About | **BLOCKED** (не меняется) | меняется отдельным PR только после `verify-live` → PASS |
| Сетевые запросы, IP-адреса, CDN | **PASS** (факты) / **BLOCKED** (журналы) | `GET` каталога и blobs; заголовки измерены в M5.IOS.2, код с тех пор не менялся; цепочка CDN МегаФон → VK Cloud подтверждена preflight-ом ([вывод](evidence/m5-ios-3/production-preflight.txt)); хранение журналов (OA-1…OA-4) неизвестно |
| `PrivacyInfo.xcprivacy` | **PASS** | в архиве OTA ON текущего `main`: tracking off, Required Reason API = импорты бинарника, `NSPrivacyCollectedDataTypes = []` |
| Ответ App Privacy | **BLOCKED** | варианты A/B и их условия — [app-privacy §5.2](app-privacy.md#52-варианты-декларации); агент не выбирает; «Data Not Collected» не заявлен |
| App Review Notes (неофициальный статус, OTA, 2.5.2) | **PASS** (текст) | [`notes.txt`](submission/metadata/review_information/notes.txt) |

## 5. Задача D — готовность к TestFlight

| Пункт | Статус | Доказательство |
|---|---|---|
| `iosApp.xcodeproj`: bundle id, 1.0.0 (1), iOS 17.2, iPhone + iPad, Automatic signing без закоммиченной команды/профиля/identity, Release-архив, privacy manifest, `Info.plist` | **PASS** | `verify_ios_project.py` → 14/14 ([вывод](evidence/m5-ios-3/ios-project.txt)) |
| Build number `1` | **PASS** (проект) / **BLOCKED** (ASC) | в проекте `1`; не использовался ли он в App Store Connect, может проверить только владелец (записи приложения ещё нет) |
| Unsigned-архив OTA ON (`TALENTFORGE_OTA_IOS_PRODUCTION_ENABLED=true`) | **PASS** локально (Xcode 26.4.1) | `** ARCHIVE SUCCEEDED **`; `verify_ios_archive.py --ota on` → **22/22 PASS**: origin `https://ota.mythweld.com`, ровно `tf-prod-2026a/b`, ATS по умолчанию, privacy ([вывод](evidence/m5-ios-3/archive-ota-on-unsigned-xcode-26.4.1.txt), [json](evidence/m5-ios-3/archive-ota-on-unsigned-xcode-26.4.1.json)) |
| `--require-signed` отличает подписанный архив | **PASS** | тот же unsigned-архив → `signed` FAIL ([вывод](evidence/m5-ios-3/archive-ota-on-unsigned-require-signed.txt)); подписанный — unit-тест |
| Production-каталог к загрузке | **PASS** (только чтение) | preflight `result: OK`, `expiresAt` 2026-12-05 (41 день). По правилу проекта (≥ 30 дней) — загрузка до 2026-11-05 или сначала re-sign |
| Owner-runbook (7 шагов) | **PASS** (документ) | [testflight-runbook.md](submission/testflight-runbook.md) |
| Подписанный архив на Xcode 27.1, загрузка, TestFlight, физический iPhone | **BLOCKED** | только владелец |

## 6. Изменения в этом PR

| Файл | Что |
|---|---|
| `docs/release/apple-appstore/submission/` | пакет: `metadata/` (18 файлов), `README.md`, `testflight-runbook.md` |
| `scripts/verify_appstore_metadata.py` | новый: тексты пакета против лимитов и правил Apple |
| `scripts/verify_appstore_screenshots.py` | новый: скриншоты пакета против спецификаций Apple |
| `scripts/verify_ios_project.py` | новый: проект Xcode до архива (собственный разбор OpenStep plist, без зависимостей) |
| `scripts/ios_privacy_pages.py` | новый: дата в iOS-политике и проверка публикации |
| `scripts/verify_ios_archive.py` | флаг `--require-signed`; без флага поведение не изменилось |
| `tests/unit/` | 4 новых файла + 1 тест в `test_verify_ios_archive.py` |
| `docs/release/apple-appstore/app-privacy.md` | §5 — аудит OTA ON |
| `docs/legal/ios/README.md` | §3 — публикация через `ios_privacy_pages.py`, `Content-Type`, HTTP 200 |
| `metadata-en.md`, `metadata-ru.md`, `readiness.md` | отметка «заменено» и ссылки на пакет |
| `evidence/m5-ios-3/` | выводы проверок (без путей машины и секретов) |

## 7. Проверки

Все — локально, 2026-10-24.

| Команда | Результат |
|---|---|
| `ruff check .` / `ruff format --check .` (без неотслеживаемых каталогов владельца `.tf-transfer/`, `ceremony/`, `legal-publish/`, `artifacts/`, как в M5.IOS.2) | **PASS** / **PASS** (337 файлов) |
| `mypy` (`src`) / `mypy --strict` по 5 изменённым скриптам | **PASS** (94 файла) / **PASS** |
| `pytest` | **PASS**: 1857 passed, 11 deselected (в т.ч. 100 новых тестов M5.IOS.3) |
| `python3 scripts/sync_mobile_datasets.py --check` | **PASS**: 50 файлов 5 сборок, bundle совпадает с `data/normalized/forever` |
| `python3 scripts/verify_appstore_metadata.py` | **PASS** |
| `python3 scripts/verify_ios_project.py --expected-version 1.0.0 --expected-build 1` | **PASS** 14/14 |
| Release-сборка для симулятора (Xcode 26.4.1) | **PASS** |
| Unsigned-архив OTA ON + `verify_ios_archive.py --ota on` (Xcode 26.4.1) | **PASS** 22/22 |
| `ota_production_preflight.py --expect published` (только чтение) | **OK** (WARN `http.plain`, как в M5.IOS.2) |
| `ios_privacy_pages.py verify-live --date 2026-10-25` | **FAIL**, ожидаемо: 403, страницы не опубликованы |
| `verify_appstore_screenshots.py` по пакету | **NOT RUN**: скриншотов нет (§2) |
| Gradle `:shared:jvmTest`, `:composeApp:desktopTest`, `:composeApp:assembleDebug` | **NOT RUN**: Kotlin-код, ресурсы и Gradle-скрипты не менялись (PR — Python-скрипты и документы); iOS-фреймворк собран заново в составе Release-сборки и архива выше |

## 8. Физическое устройство и TestFlight

Заполняет владелец по [runbook §6](submission/testflight-runbook.md#6-проверка-на-физическом-iphone): `PASS` / `FAIL` /
`NOT RUN`, время (UTC), модель, версия iOS.

| Шаг | Результат |
|---|---|
| P-1…P-13 (OTA ON на устройстве) | NOT RUN: у агента нет устройства и подписи |
| Основные сценарии | NOT RUN |
| iPad | NOT RUN |

## 9. Только владелец

| # | Действие | Блокирует |
|---|---|---|
| OW-1 | Разрешить агенту доступ к симуляторам «TF-Shots iPhone 17 Pro» и «TF-Shots iPad Pro 13» (панель симулятора → «Let Claude use it») — или снять скриншоты самостоятельно по плану §2 | задачу A |
| OW-2 | Утвердить тексты пакета | B |
| OW-3 | Опубликовать iOS-политику ([§3](../../legal/ios/README.md#3-публикация-выполняет-только-владелец)) и сообщить дату | C, Privacy Policy URL |
| OW-4 | Выяснить журналы CDN и бакета (OA-1…OA-4) и выбрать вариант App Privacy — одинаково с Google Play | App Privacy, `PrivacyInfo.xcprivacy` |
| OW-5 | Content Rights, Age Rating, DSA, цена и страны ([submission §3](submission/README.md#3-ответы-анкет-app-store-connect-решает-владелец)) | отправку |
| OW-6 | Runbook, шаги 1–6: запись в ASC, подпись, архив на Xcode 27.1, `--require-signed`, TestFlight, iPhone | TestFlight |
| OW-7 | Runbook, шаг 7: карточка и отправка на ревью — после OW-1…OW-6 и code review этого PR | публикацию |
