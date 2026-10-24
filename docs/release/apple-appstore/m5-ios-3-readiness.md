# M5.IOS.3 — App Store Submission Preparation: отчёт

| | |
|---|---|
| Дата | 2026-10-24 |
| База | `main` @ `d51cbed` (после PR #80) |
| Итог | Пакет отправки готов к code review: 18 скриншотов EN/RU, тексты EN/RU, ответы анкет, runbook TestFlight, пять проверок. **В App Store Connect ничего не вносилось, сборка не загружалась, приложение не публиковалось.** Отправка заблокирована действиями владельца (§9) |
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

**PASS.** 18 скриншотов: iPhone 6.3" (6 экранов) и iPad 13" (3 экрана), English (U.S.) и Russian —
[`submission/screenshots/`](submission/screenshots/), [manifest](submission/screenshots/manifest.json).

| Пункт | Статус | Подробности |
|---|---|---|
| Источник | **PASS** | Release-сборка для симулятора (`-configuration Release`, `ARCHS=arm64`, коммит `d51cbed`, OTA OFF, 1.0.0 (1)), Xcode 26.4.1, runtime iOS 26.4.1 (23E254a); устройства — iPhone 17 Pro (класс Dynamic Island medium, как iPhone 18 Pro) и iPad Pro 13-inch (M5) |
| Реальный UI | **PASS** | каждое состояние получено нажатиями в запущенном приложении: выбор класса, `+`/«+ Rank», выбор таланта, развёрнутый инспектор, меню ⋯ → Share, «Copy code» → импорт этого кода через «Paste» → «Continue». Код приложения не менялся; ничего не дорисовано, не смонтировано, не кадрировано и не масштабировано |
| Язык | **PASS** | язык **устройства** (`AppleLanguages` `en-US` / `ru-RU`, перезагрузка симулятора, переустановка приложения), а не аргумент запуска; RU-набор проверен визуально — интерфейс, названия классов и деревьев, тексты талантов на русском |
| Строка статуса и система | **PASS** | `simctl status_bar override`: 9:41, Wi-Fi, сотовая связь, батарея 100 %; уведомлений, баннеров, клавиатуры и отладочных элементов нет; iPad — режим Full Screen Apps, без маркера окна |
| Формат | **PASS** | `simctl io screenshot` даёт RGBA; альфа-канал полностью непрозрачный (проверено: A = 255 во всех пикселях) и удалён без изменения пикселей (RGB-байты исходника и результата совпадают); итог — 8-bit RGB PNG |
| `verify_appstore_screenshots.py` | **PASS** | все проверки ([вывод](evidence/m5-ios-3/appstore-screenshots.txt)): размеры 1206 × 2622 и 2064 × 2752, RGB без alpha/`tRNS`, CRC и данные изображения, 1–10 на размер, нумерация, одинаковые наборы EN/RU, SHA-256 в manifest |
| Xcode 27.1 / iPhone 18 Pro | **NOT RUN** | локально Xcode 26.4.1 и iPhone 17 Pro; UI — тот же Compose-код, класс дисплея и размер в пикселях совпадают с iPhone 18 Pro |

Набор (одинаковый порядок в обеих локалях):

| № | iPhone 6.3" (`iphone-6.3/`) | iPad 13" (`ipad-13/`, портрет) |
|---|---|---|
| 01 | `editor` — Paladin 33/51 (Holy), всё дерево в кадре, выбран талант, нижний инспектор (B3) | `editor` — Paladin 33/51: дерево Holy в максимальном масштабе приложения, постоянная панель инспектора с описанием текущего и следующего ранга |
| 02 | `talent-details` — развёрнутый инспектор «Holy Power» 2/5: текущий и следующий ранг, `−` / «+ Rank» / `Max` | `share-code` — лист «Share build» с кодом |
| 03 | `class-picker` — девять классов | `import-preview` — «Import this build?» до сохранения |
| 04 | `build-library` — Home: «Continue» (Paladin) и недавние Priest, Druid, Mage, «All builds 4» | — |
| 05 | `share-code` — лист «Share build» (Paladin): код, «Share», «Copy code» | — |
| 06 | `import-preview` — «Import this build?» (Paladin 33/51): класс, данные, очки | — |

Соответствие требованиям App Store Connect ([Screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/), прочитано 2026-10-24):

| Требование Apple | Значение | Факт |
|---|---|---|
| iPhone with Dynamic Island (medium) — **обязателен** | 1206 × 2622 или 1179 × 2556 | 6 × 1206 × 2622 на локаль — **PASS** |
| iPad 13-inch — **обязателен**, если приложение работает на iPad | 2064 × 2752 или 2048 × 2732 | 3 × 2064 × 2752 на локаль — **PASS** |
| Формат | `.png`/`.jpeg`; «Images can't include alpha channels or transparencies» | PNG 8-bit RGB, без `tRNS` — **PASS** |
| Количество | 1–10 на размер | 6 и 3 — **PASS** |
| Остальные классы iPhone/iPad (6.9", 6.5", 11" и др.) | не обязательны: App Store Connect масштабирует из обязательных | не снимались |
| iPhone Duo | обязателен **с апреля 2027** для сборок на iOS 27.1 SDK+ | NOT RUN: сейчас не требуется; симулятора нет |

Наблюдения (не дефекты скриншотов, решение — за владельцем):

- iPad-набор (EN и RU) переснят в режиме iPadOS «Приложения в полноэкранном режиме» / Full Screen Apps (Настройки →
  Многозадачность и жесты): системного маркера изменения размера окна в правом нижнем углу больше нет (проверено:
  максимальная яркость угла 80 × 80 px — 12–21 против 128 у прежнего снимка; на кадрах 03 угол занимает кнопка
  «Continue»). Сценарий, билд и положение камеры — те же, что в первой съёмке.
- На iPad видны два дерева из трёх (третье — панорамированием): так выглядит утверждённая раскладка ([readiness O-2](readiness.md#наблюдения-не-исправлялись-в-этом-pr)).
- Во время съёмки замечено: в RU-локали описание таланта «Чародейская вспышка» (Arcane Blast) показывается
  по-английски — в наборе этот кадр не используется; это вопрос локализации данных, вне M5.IOS.3.

Исправления после ревью (FIX-2):

- **iPhone EN/RU (01, 02, 04, 05, 06)** пересняты на билде **Паладина** (Holy 33/51). Вкладки специализаций
  «Свет / Защита / Воздаяние» и «Holy / Protection / Retribution» на 6.3" видны **полностью**; у Мага на русском
  первая вкладка обрезается до «Тайная м…» (длинное название при ширине 402 pt — поведение интерфейса по дизайну, не
  менялось). Проверены и другие классы: у Жреца обрезается «Послуша…», у Друида — нет, но выбран Паладин: все три
  вкладки полные в обоих языках и всё дерево Holy помещается в кадр без прокрутки. В основных материалах
  обрезанных названий нет. Кадр 03 (выбор класса) переснят и побайтно совпал с прежним.
- **iPad 01 EN/RU** переснят: дерево Holy увеличено жестом pinch до **максимального масштаба приложения** (дальнейший
  жест не увеличивает) и сдвинуто перетаскиванием в пределах границ камеры; оно занимает около 70 % ширины и высоты
  области дерева вместо ~30 % раньше, справа — описание выбранного таланта «Holy Power» / «Священная сила» 2/5.
  Горизонтально дерево прижато к левому краю: камера приложения не сдвигает раздел дальше его границы, интерфейс ради
  скриншота не менялся. Пропорции изображения не искажались, ничего не дорисовано.
- iPad 02 и 03 не менялись. Библиотека на iPhone — четыре класса (Paladin, Priest, Druid, Mage): билды созданы в
  приложении, затем перенесены на другое устройство и язык тем же кодом через «Paste».


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
| `--require-signed` проверяет настоящую подпись | **PASS** (инструменты) / **NOT RUN** (подписанный архив) | нативные `codesign --verify --strict --verbose=2`, `codesign -d` (identity, Team ID), `security cms -D` (профиль: команда, bundle id, срок, enterprise), сертификат в профиле, entitlements, запрет ad-hoc и Ad Hoc. На **настоящих** инструментах: unsigned-архив → FAIL ([вывод](evidence/m5-ios-3/archive-ota-on-unsigned-require-signed.txt)); его копия с ad-hoc подписью проходит `codesign --verify`, но получает FAIL по identity/профилю ([вывод](evidence/m5-ios-3/archive-ota-on-adhoc-require-signed.txt)). Ветви development/App Store/Ad Hoc/истёкший профиль/чужая команда/повреждённая подпись/фиктивные файлы/нет инструментов — 27 unit-тестов с мокированными командами. Архив, подписанный командой владельца, агент не проверял: нет Apple credentials |
| Production-каталог к загрузке | **PASS** (только чтение) | preflight `result: OK`, `expiresAt` 2026-12-05 (41 день). По правилу проекта (≥ 30 дней) — загрузка до 2026-11-05 или сначала re-sign |
| Owner-runbook (7 шагов) | **PASS** (документ) | [testflight-runbook.md](submission/testflight-runbook.md) |
| Подписанный архив на Xcode 27.1, загрузка, TestFlight, физический iPhone | **BLOCKED** | только владелец |

## 6. Изменения в этом PR

| Файл | Что |
|---|---|
| `docs/release/apple-appstore/submission/` | пакет: `metadata/` (18 файлов), `screenshots/` (18 PNG + manifest), `README.md`, `testflight-runbook.md` |
| `scripts/verify_appstore_metadata.py` | новый: тексты пакета против лимитов и правил Apple |
| `scripts/verify_appstore_screenshots.py` | новый: скриншоты пакета против спецификаций Apple |
| `scripts/verify_ios_project.py` | новый: проект Xcode до архива (собственный разбор OpenStep plist, без зависимостей) |
| `scripts/ios_privacy_pages.py` | новый: дата в iOS-политике и проверка публикации |
| `scripts/verify_ios_archive.py` | `--require-signed` (нативная проверка подписи и профиля) и `--expected-team-id`; без флага поведение не изменилось, системные инструменты не вызываются |
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
| `pytest` | **PASS**: 1884 passed, 11 deselected (в т.ч. 127 новых тестов M5.IOS.3, из них 27 — проверка подписи) |
| `python3 scripts/sync_mobile_datasets.py --check` | **PASS**: 50 файлов 5 сборок, bundle совпадает с `data/normalized/forever` |
| `python3 scripts/verify_appstore_metadata.py` | **PASS** |
| `python3 scripts/verify_ios_project.py --expected-version 1.0.0 --expected-build 1` | **PASS** 14/14 |
| Release-сборка для симулятора (Xcode 26.4.1) | **PASS** |
| Unsigned-архив OTA ON + `verify_ios_archive.py --ota on` (Xcode 26.4.1) | **PASS** 22/22 |
| `ota_production_preflight.py --expect published` (только чтение) | **OK** (WARN `http.plain`, как в M5.IOS.2) |
| `ios_privacy_pages.py verify-live --date 2026-10-25` | **FAIL**, ожидаемо: 403, страницы не опубликованы |
| `verify_appstore_screenshots.py` по пакету | **PASS** (18 файлов) |
| Gradle `:shared:jvmTest`, `:composeApp:desktopTest`, `:composeApp:assembleDebug` | **NOT RUN**: Kotlin-код, ресурсы и Gradle-скрипты не менялись (PR — Python-скрипты и документы); iOS-фреймворк собран заново в составе Release-сборки и архива выше |

## 8. Физическое устройство и TestFlight

Заполняет владелец по [runbook §6](submission/testflight-runbook.md#6-проверка-на-физическом-iphone): `PASS` / `FAIL` /
`NOT RUN`, время (UTC), модель, версия iOS. Те же сценарии на симуляторах (Release OTA ON, `c21be4f`, Xcode 26.4.1) —
[приёмка перед TestFlight](m5-ios-acceptance.md); устройство она не заменяет.

| Шаг | Результат |
|---|---|
| P-1…P-13 (OTA ON на устройстве) | NOT RUN: у агента нет устройства и подписи |
| Основные сценарии | NOT RUN |
| iPad | NOT RUN |

## 9. Только владелец

| # | Действие | Блокирует |
|---|---|---|
| OW-1 | Просмотреть скриншоты (§2) | загрузку скриншотов |
| OW-2 | Утвердить тексты пакета | B |
| OW-3 | Опубликовать iOS-политику ([§3](../../legal/ios/README.md#3-публикация-выполняет-только-владелец)) и сообщить дату | C, Privacy Policy URL |
| OW-4 | Выяснить журналы CDN и бакета (OA-1…OA-4) и выбрать вариант App Privacy — одинаково с Google Play | App Privacy, `PrivacyInfo.xcprivacy` |
| OW-5 | Content Rights, Age Rating, DSA, цена и страны ([submission §3](submission/README.md#3-ответы-анкет-app-store-connect-решает-владелец)) | отправку |
| OW-6 | Runbook, шаги 1–6: запись в ASC, подпись, архив на Xcode 27.1, `--require-signed`, TestFlight, iPhone | TestFlight |
| OW-7 | Runbook, шаг 7: карточка и отправка на ревью — после OW-1…OW-6 и code review этого PR | публикацию |
