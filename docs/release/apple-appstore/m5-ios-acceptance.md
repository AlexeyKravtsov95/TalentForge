# M5.IOS — приёмка iOS перед TestFlight: отчёт

| | |
|---|---|
| Дата | 2026-10-25 (прогон 2026-10-24T20:30Z…20:57Z) |
| База | `main` @ `c21be4f` (после PR #82, #83, #84) |
| Сборка | **1.0.0 (1), Release, OTA ON** (production `https://ota.mythweld.com`), симулятор, без подписи |
| Итог | Все пользовательские сценарии, которые можно выполнить на симуляторе, — **PASS**. Первый запуск без сети на симуляторе — **NOT RUN** (§2.1). Дефектов не найдено; два наблюдения (§4). Код приложения, `PrivacyInfo.xcprivacy`, bundle id и скриншоты App Store не менялись |
| Следующий шаг владельца | §5 |
| Evidence | [`evidence/m5-ios-acceptance/`](evidence/m5-ios-acceptance/) — журнал прогона и 10 скриншотов |

Статусы: **PASS** — выполнено здесь и проверено; **NOT RUN** — не выполнялось (причина указана); **BLOCKED** — нужно
действие владельца.

## 1. Исходное состояние

### 1.1 CI фактического `main`

| Workflow | Run | Commit | Результат |
|---|---|---|---|
| `mobile-ci` | [40127417895](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/40127417895) | `c21be4f` | **success**: «Mobile (Linux) — contracts, drift, Android», «Mobile (macOS) — iOS, Xcode 27.1», «iOS OTA staging E2E (Simulator, Xcode 27.1)» |
| `CI` | [40127418065](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/40127418065) | `c21be4f` | **success** (`checks`) |

Xcode 27.1 — только эти CI-прогоны. Всё в §1.2–§2 выполнено локально на **Xcode 26.4.1** и за проверку на 27.1 не
выдаётся.

### 1.2 Окружение и сборка (локально)

| | Факт |
|---|---|
| macOS / Xcode / SDK | 26.3.1 / **26.4.1 (17E202)** / `iphonesimulator26.4` |
| Runtime | iOS 26.4.1 (23E254a) |
| Устройства | новые симуляторы «TF-Accept iPhone 17 Pro» и «TF-Accept iPad Pro 13» (iPad Pro 13-inch M5): чистая установка, данные прежних прогонов и съёмки App Store не затронуты |
| Команда | `TALENTFORGE_OTA_IOS_PRODUCTION_ENABLED=true` + `xcodebuild … -configuration Release -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build` → `** BUILD SUCCEEDED **` |
| `Info.plist` | `io.github.alexeykravtsov95.talentforge`, `1.0.0` / `1`, `DTSDKName = iphonesimulator26.4`, `DTXcodeBuild = 17E202` |
| Флаг OTA в бинарнике | **PASS**: OTA-проверки `verify_ios_archive.py` по исполняемому файлу — `ota-on` (origin `https://ota.mythweld.com`, ровно ключи `tf-prod-2026a`, `tf-prod-2026b`, SPKI SHA-256 сверены), `ota-keyring`, `ota-no-staging-origin` |
| Production-каталог | `ota_production_preflight.py --expect published` (только чтение): первый прогон 20:30Z — FAIL из-за таймаута и DNS-ошибок локальной сети во время сборки; повтор 20:37Z — **OK**: sequence 1, `1.60.1.70450-r1`, 397 файлов перехешированы, `expiresAt` 2026-12-05T08:31:31Z (41 день) |

Подробности и SHA-256 исполняемого файла — [журнал](evidence/m5-ios-acceptance/acceptance-log.txt).

## 2. Функциональная приёмка

Каждый сценарий выполнен нажатиями в запущенном приложении (iPhone 17 Pro — 402 × 874 pt; iPad Pro 13" — 1032 × 1376 pt).

### 2.1 Данные и OTA

| Сценарий | iPhone | iPad | Доказательство |
|---|---|---|---|
| Первый запуск **без сети** на встроенных данных | **NOT RUN** | **NOT RUN** | у симулятора нет авиарежима и своей сети: он использует сеть Mac. Отключить её можно только системными настройками Mac (нужны права администратора, и это обрывает сессию агента). Проверяется на устройстве: [runbook §6](submission/testflight-runbook.md#6-проверка-на-физическом-iphone) п. 1 (P-1, P-8), до TestFlight — [§8](submission/testflight-runbook.md#8-необязательно-личный-iphone-через-personal-team-до-активации-членства). Косвенно: CI «iOS OTA staging E2E» на `c21be4f` (staging-канал, не production) включает перезапуск без сети |
| Первый запуск **с сетью**: приложение само проверяет production | **PASS** | **PASS** | после первого кадра `state.json`: канал `production`, каталог sequence 1, active `1.60.1.70450-r1`, `rejected: []`. About: «Downloaded update 1.60.1.70450-r1», «Game build 1.60.1.70450» — [iphone-01](evidence/m5-ios-acceptance/iphone-01-about-ota-first-launch-en.png) |
| «Check for updates» | **PASS** | **PASS** | iPhone: «Talent data is up to date.»; iPad (сразу после первой установки): «Talent data updated to 1.60.1 (1.60.1.70450)…» |

### 2.2 Редактор

| Сценарий | iPhone | iPad | Доказательство |
|---|---|---|---|
| Выбор класса: девять классов, RU/EN | **PASS** | **PASS** (EN) | RU: Воин, Паладин, Охотник, Разбойник, Жрец, Шаман, Маг, Чернокнижник, Друид |
| Три специализации, RU/EN | **PASS** | **PASS** (EN) | Воин: Arms / Fury / Protection ↔ «Оружие / Неистовст… / Защита» (обрезка «Неистовство» на 402 pt — по дизайну, как в [M5.IOS.3 §2](m5-ios-3-readiness.md#2-задача-a--скриншоты-app-store)); Маг на iPad: Arcane / Fire / Frost. Очки распределены во все три дерева |
| Tap выбирает, очки не тратит | **PASS** | **PASS** | 0/51 после выбора; инспектор открылся |
| `+` / `−` / `Max` | **PASS** | **PASS** | iPhone: + + → 2/3, − → 1/3, Max → 3/3 «Maxed»; iPad: + + − → 1/5, Max → 5/5 |
| Отмена / повтор | **PASS** | **PASS** | отмена после Max → прежний ранг; повтор → снова Max |
| «Показать» переходит к пререквизиту, очки не меняются | **PASS** | **PASS** | Deep Wounds → Improved Rend (3/51 до и после) — [iphone-03](evidence/m5-ios-acceptance/iphone-03-show-prerequisite-en.png); Arcane Meditation → Arcane Concentration (5/51) |
| Pan / pinch | **PASS** | **PASS** | pinch увеличивает дерево, drag панорамирует (на iPad — Arcane → Fire → Frost); очки не меняются — [ipad-03](evidence/m5-ios-acceptance/ipad-03-portrait-inspector-pinch-en.png) |
| Поворот и сохранность распределения | **PASS** | **PASS** | телефон landscape → боковой инспектор, обратно → нижний; iPad landscape → дерево + боковая панель ([ipad-04](evidence/m5-ios-acceptance/ipad-04-landscape-side-inspector-en.png), кадровый буфер `simctl` повёрнут); выбор и счёт очков те же |

### 2.3 Билды и обмен

| Сценарий | Результат | Доказательство |
|---|---|---|
| Создание | **PASS** | автосохранение «Warrior build» (iPhone), «Mage build» (iPad) |
| Переименование | **PASS** | экранной клавиатурой: «тест», 4/60, Save → имя в шапке и на Home. См. наблюдение O-1 |
| Восстановление после перезапуска | **PASS** | `simctl terminate` + запуск: iPhone — оба билда 5/51, iPad — «Mage build» 7/51; открытый билд — то же распределение |
| «Копировать код» | **PASS** | «Code copied»; буфер симулятора (`simctl pbpaste`) = код в листе |
| Импорт с предпросмотром | **PASS** | «Paste» → «Continue» → «Import this build?» (Warrior, 1.60.1.70450, 5/51) до сохранения — [iphone-07](evidence/m5-ios-acceptance/iphone-07-import-preview-en.png); «Import» → новый независимый билд. См. наблюдение O-2 |
| Системное меню «Поделиться» | **PASS** | «Share» открывает share sheet iOS с кодом билда — [iphone-06](evidence/m5-ios-acceptance/iphone-06-ios-share-sheet-en.png); закрыт без отправки, приложение на месте |

### 2.4 About: политика конфиденциальности

URL открытой страницы взят из истории Safari симулятора (`History.db`, читалась копия), а не из HTTP-запроса агента.

| Локаль | Устройство | Ссылка видна | Нажатие → Safari | Возврат в TalentForge |
|---|---|---|---|---|
| EN | iPhone | **PASS** «Privacy policy» | **PASS** `https://legal.mythweld.com/privacy-ios-en.html` (20:40:31Z), «Privacy Policy — TalentForge for iPhone and iPad» — [iphone-02](evidence/m5-ios-acceptance/iphone-02-safari-privacy-ios-en.png) | **PASS**: системная ссылка «◀ TalentForge», тот же процесс (PID 34411), тот же экран About |
| RU | iPhone | **PASS** «Политика конфиденциальности» — [iphone-10](evidence/m5-ios-acceptance/iphone-10-about-privacy-link-ru.png) | **PASS** `https://legal.mythweld.com/privacy-ios.html` (20:52:15Z), «Политика конфиденциальности — TalentForge для iPhone и iPad» — [iphone-11](evidence/m5-ios-acceptance/iphone-11-safari-privacy-ios-ru.png) | **PASS**: PID 84485 не изменился, экран About и прокрутка те же |
| EN | iPad | **PASS** | **PASS** `…/privacy-ios-en.html` (20:53:32Z) — [ipad-02](evidence/m5-ios-acceptance/ipad-02-safari-privacy-ios-en.png) | **PASS**: PID 92861, тот же экран |
| RU | iPad | — | **NOT RUN**: RU проверен на iPhone; страница выбирается по языку интерфейса, а не по устройству | — |

Язык переключался языком **устройства** (`AppleLanguages ru-RU`, перезагрузка симулятора), не аргументом запуска.
Страница открывается в Safari, не во встроенном WebView.

## 3. Не проверялось на симуляторе

| Пункт | Статус | Где проверяется |
|---|---|---|
| Авиарежим, сотовые данные, IPv6/NAT64, перезагрузка устройства (P-1…P-13) | **NOT RUN** | устройство: runbook §6 п. 1 или §8 (Personal Team) |
| VoiceOver | **NOT RUN** (в симуляторе нет) | runbook §6 п. 3 |
| Xcode 27.1 / SDK `iphoneos27.1`, подписанный архив, TestFlight | **BLOCKED** (нет Apple Developer Program) | runbook §1–§5 |

## 4. Наблюдения (не дефекты; проверить на устройстве)

| # | Наблюдение | Оценка |
|---|---|---|
| O-1 | Переименование: текст, введённый **инъекцией клавиш с Mac** (несколько символов подряд через инструмент симулятора), закрыл диалог без сохранения. Один символ и пробел — диалог оставался. Экранной клавиатурой ввод и сохранение работают | похоже на артефакт инъекции событий симулятора; на устройстве не проверялось. Проверить на iPad с аппаратной клавиатурой ([runbook §6](submission/testflight-runbook.md#6-проверка-на-физическом-iphone) п. 4) |
| O-2 | Первое нажатие «Paste» → системный запрос «…would like to paste from “CoreSimulator-Bridge”» → «Allow Paste» → «Couldn't read the clipboard. Paste into the field instead.» Повторное «Paste» вставило содержимое буфера. После «Copy code» внутри приложения вставка прошла без запроса | источник буфера — мост Mac ↔ симулятор (синхронизация буфера хоста). На устройстве: «Paste» после копирования кода в другом приложении (runbook §6 п. 2) |

Во время O-2 в поле импорта попал текст из буфера обмена Mac (не код билда); импорт не выполнялся, поле очищено.

## 5. Следующий шаг владельца после одобрения Apple

1. **До одобрения (необязательно):** личный iPhone через Personal Team — [runbook §8](submission/testflight-runbook.md#8-необязательно-личный-iphone-через-personal-team-до-активации-членства):
   закрывает «первый запуск без сети» и Safari на устройстве. Готовность подписи для TestFlight это не подтверждает.
2. **Решить App Privacy** (A или B, [app-privacy §5.2](app-privacy.md#8-история-варианты-a-и-b-заменены)). Сообщение владельца от
   2026-10-25 о журналах VK Cloud записано ([OWNER_ACTION_REQUIRED §1.1](../../legal/ota-on/OWNER_ACTION_REQUIRED.md#11-сообщение-владельца-2026-10-25));
   журналы CDN МегаФон и внутренние журналы провайдера им не проверены. Для B — сначала отдельный PR с
   `PrivacyInfo.xcprivacy`, до архива.
3. **После одобрения:** [runbook](submission/testflight-runbook.md) §1 → §6 по порядку: запись в App Store Connect,
   подпись, архив на Xcode 27.1, `verify_ios_archive.py --require-signed`, TestFlight, проверка на iPhone.
4. **Срок:** production-каталог действует до 2026-12-05; по правилу ≥ 30 дней загрузить сборку не позже **2026-11-05**
   или сначала переподписать каталог ([owner-guide §3](m5-ios-2-owner-guide.md#3-перед-сборкой-ota-on)).
5. Результаты устройства — в [M5.IOS.3 §8](m5-ios-3-readiness.md#8-физическое-устройство-и-testflight).

## 6. Изменения в этом PR

| Файл | Что |
|---|---|
| `docs/release/apple-appstore/m5-ios-acceptance.md` | этот отчёт |
| `docs/release/apple-appstore/evidence/m5-ios-acceptance/` | журнал прогона и 10 скриншотов |
| `docs/release/apple-appstore/submission/testflight-runbook.md` | §8 — необязательный маршрут Personal Team и ссылка в шапке; §6 п. 2, 4 — вставка из другого приложения и аппаратная клавиатура (O-1, O-2) |
| `docs/release/apple-appstore/submission/README.md` | §4 — строки C2, C3 и новая E |
| `docs/legal/ota-on/OWNER_ACTION_REQUIRED.md` | §1.1 — сообщение владельца 2026-10-25 |
| `docs/release/apple-appstore/app-privacy.md` | §5.1 — строка о журналах: сообщение владельца, статус прежний |
| `docs/release/apple-appstore/m5-ios-3-readiness.md` | §8 — ссылка на приёмку на симуляторе |

Код, ресурсы, Gradle-скрипты, проект Xcode, `PrivacyInfo.xcprivacy`, датасеты, OTA-каталог и материалы App Store не
менялись.

## 7. Проверки

| Команда | Результат |
|---|---|
| Release OTA ON для симулятора (Xcode 26.4.1) | **PASS** (`** BUILD SUCCEEDED **`) |
| OTA-проверки `verify_ios_archive.py` по исполняемому файлу | **PASS** 3/3 |
| `ota_production_preflight.py --expect published` | **OK** (второй прогон; первый — FAIL сети, §1.2) |
| `python3 scripts/verify_ios_project.py --expected-version 1.0.0 --expected-build 1` | **PASS** (после правок runbook; проект не менялся) |
| `python3 scripts/verify_appstore_metadata.py` / `verify_appstore_screenshots.py` | **PASS** / **PASS** (пакет не менялся) |
| `pytest tests/unit/test_verify_appstore_metadata.py tests/unit/test_verify_appstore_screenshots.py tests/unit/test_verify_ios_project.py` | **PASS**: 86 passed |
| Проверка относительных ссылок и якорей в изменённых документах | **PASS** (0 ошибок) |
| CI `c21be4f` | **success** (§1.1) |
| Gradle, полный `pytest`, `ruff`, `mypy` | **NOT RUN**: изменены только документы и evidence; Python и Kotlin не менялись |
