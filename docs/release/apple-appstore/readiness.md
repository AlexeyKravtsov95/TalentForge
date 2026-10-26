# M5.IOS.1 — TalentForge: готовность к App Store (iOS 27)

| | |
|---|---|
| Дата | 2026-10-23 |
| Ветка | `release/m5-ios-1-appstore-readiness` от `main` @ `7f91b40` (после PR #74) |
| Итог | **Не готово к отправке.** Код, проект, CI и документы подготовлены; unsigned-сборка и архив на **Xcode 27.1 / iOS 27.1 SDK — PASS в CI** ([прогон](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/39917498331)). Блокеры: подписанный архив (Xcode 27.1 у владельца), публикация iOS-политики, интерактивная проверка iPhone 18 / iPhone Duo / физическое устройство (§11) |
| Production OTA | **выключен** на iOS (проверено по бинарнику, §7). Включение — отдельный M5.IOS.2 ([план](m5-ios-2-ota-plan.md)) |
| Владельческие решения | игровые материалы WoW: Forever — `RISK ACCEPTED — OWNER DECISION`, повторный юридический аудит не проводился; лицензия Blizzard не заявляется; название TalentForge + маркировка неофициального приложения; целевая архитектура iOS — OTA ON |
| Продолжение | M5.IOS.2 — [OTA ON](m5-ios-2-ota-readiness.md); **M5.IOS.3 — [пакет отправки в App Store](submission/README.md), [отчёт](m5-ios-3-readiness.md)** |
| Связанные документы | [metadata-en](metadata-en.md) · [metadata-ru](metadata-ru.md) · [app-privacy](app-privacy.md) · [m5-ios-2-ota-plan](m5-ios-2-ota-plan.md) · [iOS-политика](../../legal/ios/README.md) · [evidence](evidence/README.md) |

Статусы: **PASS** — выполнено и проверено; **FAIL** — выполнено, требование не выполнено; **BLOCKED** — нельзя
выполнить без владельца/среды; **NOT RUN** — не выполнялось (с причиной).

## 0. Definition of Done

| Критерий | Статус | Комментарий |
|---|---|---|
| Сборка с Xcode 27.1 и iOS 27.1 SDK | **PASS в CI**; BLOCKED локально | [mobile-ci 39917498331](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/39917498331) (образ `xcode-27`, macOS 27.0.1): Xcode 27.1 build 27A9275, release-фреймворки, Release-сборка для симулятора, unsigned archive, `verify_ios_archive.py` 18/18 с `iphoneos27.1`/`27A9275` ([отчёт](evidence/ci-xcode27.1/ios-archive-report-xcode27.1-ci.json)). Локально Xcode 26.4.1 на macOS 26.3.1 (Xcode 27.1 требует 26.6+, §1) |
| Минимальная iOS 17.2 | **PASS** | `MinimumOSVersion = 17.2` в собранном архиве |
| Release framework собирается | **PASS** (Xcode 26.4.1) | `linkReleaseFrameworkIosArm64` + `…IosSimulatorArm64` |
| Unsigned Release archive создаётся и проверяется | **PASS** (Xcode 26.4.1) | 18/18 проверок `verify_ios_archive.py`; с ожиданиями Xcode 27.1 — FAIL по `toolchain` (так и должно быть) |
| Запуск на доступных симуляторах | **PASS** | локально: iPhone 17 Pro, iPhone 17 Pro Max, iPad Pro 13" (M5) на iOS 26.4; iPhone 12 mini на iOS 26.0. CI (сборка Xcode 27.1, runtime iOS 27.0): iPhone 18 Pro, iPhone 18 Pro Max, iPad Pro 13" (M5) — launch-smoke PASS |
| Smoke основных сценариев | **PASS** на доступных устройствах | §4 |
| Существенные ошибки UI исправлены | **PASS** | 4 исправления (§3.3); оставшиеся наблюдения — §11 |
| Privacy и App Review checklist | **PASS** (подготовлены) | §5, §6, [app-privacy](app-privacy.md) |
| Production OTA не включён | **PASS** | §7 |
| Android и KMP regression | **PASS** | §3.2 |
| Нет секретов в PR | **PASS** | §10 |
| Нет ложных заявлений о физическом тестировании, TestFlight, публикации | **PASS** | физическое устройство, TestFlight, App Store Connect — **NOT RUN / BLOCKED** (§11) |

## 1. Окружение

| Компонент | Требование | Фактически (машина агента) | Статус |
|---|---|---|---|
| macOS | Tahoe 26.6+ | **26.3.1** (25D771280a) | **BLOCKED**: ниже минимума Xcode 27.1; обновление ОС — действие владельца |
| Xcode | 27.1 stable | **26.4.1 (17E202)** — единственный установленный | **BLOCKED** |
| iOS SDK | 27.1 | **26.4** (`iphoneos26.4`, `iphonesimulator26.4`) | **BLOCKED** |
| Симуляторы | iPhone 18 Pro / Pro Max, iPhone Duo, iPad Pro 13", компактный iPhone | runtime iOS 26.0, 26.1, 26.4; iPhone 17/17 Pro/17 Pro Max/17e/Air/16e/12 mini, iPad Pro 13"/11" (M5), iPad Air, iPad mini | частично (§4) |
| Kotlin / Compose Multiplatform | 2.4.20 / 1.10.3 | 2.4.20 / 1.10.3 — без изменений | — |
| JDK / Gradle | — | OpenJDK 17.0.19 / Gradle 9.5.1 | — |
| CI (GitHub Actions) | Xcode 27.1 явно | образ `xcode-27` (preview, macOS 27.0.1): `Xcode_27.1.app`, build **27A9275**, SDK `iphoneos27.1`; симуляторы iOS 27.0 с iPhone 18 Pro / Pro Max / iPad Pro 13" (M5); **iPhone Duo нет** ([readme образа](https://github.com/actions/runner-images/blob/main/images/macos/xcode-27-arm64-Readme.md), 2026-10-12) | подготовлено (§9) |

Проверено командами `sw_vers`, `xcodebuild -version`, `xcodebuild -showsdks`, `xcrun simctl list runtimes`.

**Требования Apple на 2026-10-23** ([Upcoming requirements](https://developer.apple.com/news/upcoming-requirements/),
[Xcode system requirements](https://developer.apple.com/xcode/system-requirements/)):

- с 28 апреля 2026 загрузки в App Store Connect должны собираться **Xcode 26+ с SDK iOS 26+**. Xcode 26.4.1 этому
  минимуму Apple соответствует, но **не** требованию владельца (Xcode 27.1), поэтому локальная сборка в этом отчёте не
  выдаётся за проверенную на актуальном SDK;
- Xcode 27.1 и 27.0 требуют macOS Tahoe 26.6+; на странице уже есть **Xcode 27.2 RC** — не используется (RC/beta для
  финального архива запрещены без решения владельца);
- с 9 сентября 2026 приложения должны таргетировать iOS 13+ — выполнено (17.2);
- скриншоты iPhone Duo станут обязательными **с апреля 2027** для приложений на iOS 27.1 SDK и новее (§8.2).

**Совместимость Kotlin 2.4.20 / CMP 1.10.3 с Xcode 27.1.** Таблица совместимости Kotlin
([multiplatform compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html))
для Kotlin 2.4.20–2.4.21 называет **Xcode 26.4** — Xcode 27.x официально не заявлен (ни в одной стабильной версии Kotlin
на эту дату). Несовместимость не выявлена; эмпирическая проверка — CI-job на Xcode 27.1 этого PR. **Зависимости не
обновлялись**: обновление без выявленной несовместимости запрещено условиями задачи, и стабильной версии Kotlin с
заявленным Xcode 27 нет. Если CI-job на 27.1 упадёт на Kotlin/Native, это станет основанием для отдельного решения.

## 2. Конфигурация Release-сборки

| Параметр | Значение | Статус |
|---|---|---|
| `PRODUCT_BUNDLE_IDENTIFIER` | `io.github.alexeykravtsov95.talentforge` | PASS (без изменений) |
| `MARKETING_VERSION` | **1.0.0** (было 0.1.0) | PASS |
| `CURRENT_PROJECT_VERSION` | **2** (было 1: `1.0.0 (1)` загружена в App Store Connect; поднят вместе со свайпом назад) | PASS |
| `CODE_SIGN_STYLE` | Automatic; `DEVELOPMENT_TEAM` в репозитории **не задан** (владелец выбирает команду в Xcode) | PASS |
| `IPHONEOS_DEPLOYMENT_TARGET` | 17.2 | PASS |
| `TARGETED_DEVICE_FAMILY` | 1,2 (iPhone, iPad) | PASS |
| Локализации | `CFBundleLocalizations` en, ru; `knownRegions` + `ru` | PASS |
| Ориентации | iPhone: portrait, landscape L/R; iPad: все четыре | PASS |
| `ITSAppUsesNonExemptEncryption` | `false` (добавлено) | PASS (владелец подтверждает в ASC, [app-privacy §4](app-privacy.md#7-связанные-декларации)) |
| Сцены | `UIApplicationSupportsMultipleScenes = false` в **собранном** plist (исправлено, §3.3) | PASS |
| Launch screen | цвет `LaunchBackground` #0B0D11 (исправлено, §3.3) | PASS |
| KMP framework | статический (`isStatic = true`), Release-конфигурация Xcode → `linkReleaseFramework*`, только arm64 (device) / arm64 (simulator) | PASS |
| Встраивание framework | статический framework **линкуется, не встраивается** (исправлено, §3.3); подписывать в бандле нечего | PASS |
| Запрещённые зависимости | `otool -L`: только системные фреймворки и Swift runtime; нет CocoaPods/SPM/сторонних SDK, нет PrivateFrameworks | PASS |
| AppIcon | один слот 1024×1024 universal; файл байт-в-байт = утверждённый пакет (§8.1) | PASS |

**Правило номера сборки (`CURRENT_PROJECT_VERSION`).**

1. Первый App Store/TestFlight-билд: `1.0.0 (1)` — загружен; следующая загрузка — `1.0.0 (2)`.
2. Каждая загрузка в App Store Connect (TestFlight, в том числе отклонённая или отозванная) потребляет номер: следующий
   билд — строго больше (`2`, `3`, …), номер никогда не переиспользуется и не уменьшается.
3. `MARKETING_VERSION` меняется только по решению о новой версии (`1.0.1`, `1.1.0`); `CURRENT_PROJECT_VERSION` не
   сбрасывается при смене версии (одна возрастающая последовательность — проще аудит).
4. Номер меняется в `project.pbxproj` (обе конфигурации) отдельным коммитом до сборки архива; CI берёт ожидаемые значения
   из `-showBuildSettings`, поэтому архив с другим номером не пройдёт проверку `version`.
5. Последовательность iOS независима от Android `versionCode` ([android-versioning](../android-versioning.md)).

## 3. Выполненные команды и результаты

### 3.1 Сборка (Xcode 26.4.1, не 27.1)

| # | Команда | Результат |
|---|---|---|
| 1 | `./gradlew :composeApp:linkReleaseFrameworkIosArm64 :composeApp:linkReleaseFrameworkIosSimulatorArm64` | **PASS**, 5 мин 19 с; оба `ComposeApp.framework` — thin arm64 |
| 2 | `xcodebuild … -configuration Release -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build` | **FAIL** без `ARCHS=arm64`: generic-назначение собирает и x86_64, которого нет среди KMP-таргетов (`syncComposeResourcesForIos`: «Cannot query the value of this provider»). С `ARCHS=arm64` (как в существующем CI) — **PASS** |
| 3 | `xcodebuild … -configuration Release -destination 'generic/platform=iOS' -archivePath …/TalentForge.xcarchive CODE_SIGNING_ALLOWED=NO archive` | **PASS** (`** ARCHIVE SUCCEEDED **`) |
| 4 | `python3 scripts/verify_ios_archive.py --archive … --expected-version 1.0.0 --expected-build 1 --expected-sdk iphoneos26.4 --expected-xcode-build 17E202` | **PASS** 18/18 ([evidence](evidence/ios-archive-verify-xcode26.4.1-local.txt)) |
| 5 | то же с `--expected-sdk iphoneos27.1 --expected-xcode-build 27A9275` | **FAIL** только `toolchain` ([evidence](evidence/ios-archive-verify-against-xcode27.1-local.txt)) — ожидаемо |
| 6 | Подписанный дистрибутивный archive (Team ID, Distribution Certificate) | **BLOCKED**: данных владельца нет; подписание не имитировалось |

Содержимое архива (проверено скриптом и вручную): `Info.plist` (bundle id, 1.0.0 (1), MinimumOSVersion 17.2,
UIDeviceFamily [1, 2], en/ru, arm64, `DTSDKName iphoneos26.4`, `DTXcodeBuild 17E202`); исполняемый файл thin arm64
(33.9 МБ, SHA-256 в [отчёте](evidence/ios-archive-report-xcode26.4.1-local.json)); `Assets.car` + иконки 120/152 px;
`PrivacyInfo.xcprivacy`; `compose-resources/` — 29 МБ, 2010 файлов, 50 JSON-датасетов, `values-ru`; папки `Frameworks/`
нет; `_CodeSignature` нет (unsigned). Размер `.app` — 61 МБ.

### 3.2 Регрессия (2026-10-23, все — exit 0)

| Команда | Результат |
|---|---|
| `python3 scripts/sync_mobile_datasets.py --check` | PASS |
| `python3 scripts/generate_oss_notices.py --check --platform ios` / `--platform android` | PASS / PASS |
| `./gradlew :shared:jvmTest --rerun` | PASS, 373 теста, 0 падений |
| `./gradlew :composeApp:desktopTest` | PASS, 834 теста, 0 падений, 58 opt-in пропущено (скриншот-тесты по флагу) |
| `./gradlew :composeApp:assembleDebug` | PASS |
| `./gradlew :shared:iosSimulatorArm64Test --rerun` | PASS, 190 тестов |
| `./gradlew :composeApp:iosSimulatorArm64Test --rerun` | PASS, 7 тестов (`IosOtaAdapterTest`, `IosAboutFactsTest`) |
| `./gradlew :composeApp:linkDebugFrameworkIosArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64` | PASS |
| `ruff check` / `ruff format --check` (файлы репозитория) | PASS / PASS. `ruff check .` по всей папке падает только на неотслеживаемой папке владельца `.tf-transfer/` (не в git) |
| `mypy` | PASS |
| `pytest -q` | PASS, 1711 passed, 11 deselected (в т.ч. 30 новых тестов `test_verify_ios_archive.py`) |

### 3.3 Исправления в этом PR

| # | Проблема | Доказательство до | Исправление | Проверка после |
|---|---|---|---|---|
| 1 | **TF-RC1-IOS-SCENES**: собранный plist включал несколько сцен (iPad: два окна → два `App` пишут одну библиотеку билдов) | `UIApplicationSupportsMultipleScenes => true` в архиве | убран `INFOPLIST_KEY_UIApplicationSceneManifest_Generation` | `false` в архиве; проверка `single-scene` в CI |
| 2 | Статический `ComposeApp.framework` встраивался: в `Frameworks/` лежала 33 КБ dylib-заглушка с install name вида `/private/var/folders/…/arm64-apple` (риск отказа валидации App Store) | `otool -L` заглушки | убрана фаза Embed Frameworks (framework линкуется) | в архиве нет `Frameworks/`; приложение запускается; проверка `embedded-code` |
| 3 | **TF-RC1-IOS-PRIVACY**: нет `PrivacyInfo.xcprivacy` | — | добавлен манифест (§6.1) | проверки `privacy-*` |
| 4 | Белая вспышка при каждом холодном старте тёмного приложения (пустой сгенерированный launch screen) | [снимок до](evidence/simulator/iphone17pro-00-launch-screen-white-before-fix.png) | `UILaunchScreen.UIColorName = LaunchBackground` (#0B0D11 — токен `background` спеки G), без генерации | [снимок после](evidence/simulator/iphone12mini-ios26.0-00-launch-screen-dark-after-fix.png) |
| 5 | About на iOS советовал «очистите его данные» — на iOS нет очистки данных приложения (неверная инструкция) | RU/EN строка `about_privacy_delete` | отдельная iOS-строка `about_privacy_delete_ios`, выбор по `BackupStatement` | `AboutScreenUiTest` (EN/RU); [снимок RU](evidence/simulator/iphone17pro-13-about-delete-ios-ru.png) |

Также: `MARKETING_VERSION` 1.0.0, регион `ru`, `ITSAppUsesNonExemptEncryption`, верификатор архива
(`scripts/verify_ios_archive.py` + тесты), smoke-скрипт (`scripts/ios_simulator_smoke.sh`), CI на Xcode 27.1.

## 4. Устройства и smoke

### 4.1 Покрытие устройств

| Устройство | Требование | Статус | Причина / доказательство |
|---|---|---|---|
| iPhone 18 Pro | да | **PASS (launch-smoke, CI)**; интерактивно **NOT RUN** | CI, сборка Xcode 27.1, runtime iOS 27.0: приложение работает через 15 с (pid в логе), [скриншот](evidence/ci-xcode27.1/iphone-18-pro-launch-smoke.png). Сценарии §4.2 на нём не выполнялись: локального симулятора нет |
| iPhone 18 Pro Max | да | **PASS (launch-smoke, CI)**; интерактивно **NOT RUN** | то же ([скриншот](evidence/ci-xcode27.1/iphone-18-pro-max-launch-smoke.png)). Первый прогон дал FAIL из-за ошибки smoke-скрипта (SIGPIPE, §9.1), не приложения |
| iPhone Duo (внешний и внутренний дисплей) | да | **NOT RUN** | симулятора нет ни локально, ни на CI-образе `xcode-27`; переходы между дисплеями, ресайз окна и сохранение editor state **не проверены**. Скриншоты другого устройства не подставлялись |
| iPad Pro 13-inch | да | **PASS** (iPad Pro 13-inch (M5), iOS 26.4) | §4.2 |
| Компактный iPhone, совместимый с 17.2 | да | **PASS** (iPhone 12 mini, 5.4", 360×780 pt, iOS 26.0) | запуск, тёмная заставка, выбор класса, дерево Druid целиком |
| Дополнительно | — | **PASS** iPhone 17 Pro (6.3", iOS 26.4) — полный сценарий; iPhone 17 Pro Max (6.9", iOS 26.4) — launch-smoke скриптом | §4.2 |
| Минимальная iOS 17.2 (runtime) | желательно | **NOT RUN** | runtime iOS 17.x не установлен |
| Физический iPhone / iPad | — | **NOT RUN** | устройства и подписи нет |

### 4.2 Сценарии (Release-сборка, unsigned, симулятор, OTA OFF)

| Сценарий | Результат | Доказательство |
|---|---|---|
| Запуск, Home, safe areas (Dynamic Island, статус-бар, home indicator) | PASS: контент не заходит под вырез и индикатор | `iphone17pro-01`, `iphone12mini-ios26.0-*` |
| Выбор класса, создание билда | PASS (9 классов) | `iphone17pro-02`, `ipadpro13-01` |
| Tap выбирает, не тратит; +/−/Max — явные кнопки | PASS (B3) | `iphone17pro-03`, `ipadpro13-02` |
| «Показать» переходит к пререквизиту, очки не меняются | PASS (3/51 до и после) | `iphone17pro-04` |
| Pinch-zoom, pan | PASS | `iphone17pro-05`, `ipadpro13-*` (pan до Fury) |
| Ориентация: телефон landscape → боковой инспектор; обратно в portrait | PASS | `iphone17pro-06` |
| iPad portrait/landscape: дерево + постоянная панель инспектора | PASS, с наблюдением §11 (O-2) | `ipadpro13-02`, `ipadpro13-03` |
| Заголовки специализаций (вкладки телефона, заголовки деревьев планшета) | PASS; на 360 pt «Feral Co…» ellipsized по дизайну (полное имя — в доступности) | `iphone12mini-ios26.0-01`, `ipadpro13-*` |
| Переименование (клавиатура, диалог над клавиатурой) | PASS | `iphone17pro-07` (имя в шапке) |
| Дублирование, RU-суффикс «(копия)» | PASS | `iphone17pro-10`, `iphone17pro-12` |
| Экспорт: лист кода, системный share sheet iOS, «Копировать код» | PASS | `iphone17pro-07`, `-08` |
| Импорт: «Вставить», предпросмотр до сохранения, импорт новым билдом | PASS | `iphone17pro-09` |
| About: версия 1.0.0, данные «Встроены в приложение», без «Проверить обновления», без ссылки на политику (iOS-политика не опубликована) | PASS | `iphone17pro-11`, `-13` |
| RU/EN по языку устройства | PASS (смена `AppleLanguages`) | `*-ru.png` |
| Dynamic Type (максимальный accessibility XXXL) | PASS smoke: Home и редактор читаемы, вкладки переходят в «номер + очки» по дизайну (`NUMBER_ONLY_FONT_SCALE`), инспектор в двухстрочном виде | `iphone17pro-14`, `-15` |
| Reduce Motion | PASS smoke: с включённым Reduce Motion выбор, «Показать» и переходы работают; отсутствие анимации по скриншоту не измеряется (поведение с уменьшенной анимацией покрыто `TalentTreeMotionUiTest`) | `iphone17pro-16` |
| VoiceOver | **NOT RUN**: в симуляторе нет VoiceOver; семантика (роли, contentDescription, stateDescription) покрыта desktop UI-тестами |
| Обрезанный текст / элементы вне экрана | PASS на проверенных экранах; известное: Home на iPad растянут на всю ширину (TF-RC1-TABLET-WIDTH, §11 O-1) |
| Отсутствие сети | PASS по построению: сборка не делает запросов (§7) |

## 5. App Review compliance

Источник: [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/) (прочитано 2026-10-23).

| Пункт | Требование (кратко) | Оценка TalentForge | Статус |
|---|---|---|---|
| 2.1 App Completeness | финальная сборка, без заглушек и неработающих функций, демо-доступ если нужен вход | все функции работают офлайн, входа нет; нет «Coming soon», ложных кнопок и тестовых экранов (smoke §4); Review Notes подготовлены | PASS |
| 2.3 Accurate Metadata | описание и скриншоты = реальная функциональность | тексты сверены со сборкой ([metadata](metadata-en.md#4-description)); скриншоты только из запущенного приложения (§8.2); RU/EN; OTA-строки — только для OTA ON сборки | PASS (черновик) |
| 2.5.1 Public APIs | только публичные API | `otool -L` — только системные фреймворки; `dlopen`/`dlsym` — Skia (CoreText-символы, публичный `OpenGLES.framework`), `sysctlbyname` — zlib (`hw.optional.*`); путей PrivateFrameworks нет | PASS (статический анализ; окончательно — валидация ASC) |
| 2.5.2 Self-contained / no downloaded code | не загружать и не исполнять код | OTA (выключен) доставляет только JSON-данные contract v1, подписанные ECDSA; интерпретатора/движка скриптов нет | PASS |
| 2.5.5 IPv6-only | работать в IPv6-only сетях | OTA OFF: сетевых запросов нет. OTA ON: `NSURLSession` по имени хоста, без IPv4-литералов — совместимо; проверка NAT64 — шаг M5.IOS.2 | PASS (OTA OFF) |
| 4.1 Copycats | не копировать чужие приложения/бренд | своё название и иконка (утверждены владельцем); маркировка «независимый неофициальный проект» в приложении и метаданных | PASS |
| 4.2 Minimum Functionality | полезность сверх «сайта в обёртке» | нативный интерактивный редактор деревьев, библиотека билдов, обмен кодами, офлайн | PASS |
| 4.2.3 Downloaded resources | работать без установки других приложений; не требовать загрузки на первом запуске | WoW не нужна, аккаунт Blizzard не нужен; все данные в бинарнике, работает с первого запуска без сети | PASS |
| 4.2.6 App generation services | шаблонные/сгенерированные сервисом приложения отклоняются | собственный код проекта, не шаблон сервиса генерации. Использование ИИ-агента при разработке само по себе не запрещено; деклараций об ИИ не требуется | PASS |
| 5.1.1 Privacy | политика конфиденциальности (ссылка в ASC и в приложении), минимум данных, согласия | данных не собирается (OTA OFF); `PrivacyInfo.xcprivacy`; **iOS-политика не опубликована**, ссылка в About отсутствует до публикации | **BLOCKED** (публикация владельцем) |
| 5.2 Intellectual property | не использовать чужую ИС без разрешения | игровые иконки, тексты, данные и название WoW: Forever — **`RISK ACCEPTED — OWNER DECISION`**; лицензия Blizzard не заявляется; повторный аудит не проводился. Риск отказа по 5.2.1 Apple остаётся | ACCEPTED RISK (документировано) |

## 6. Privacy и требования Apple к SDK

### 6.1 `PrivacyInfo.xcprivacy` и Required Reason API

Метод: категории выведены из **импортов собранного исполняемого файла** (undefined symbols Mach-O + имена селекторов), а
не по памяти; источник каждого символа атрибутирован по объектным файлам статического framework (`nm -A`).

| Категория | Код | Откуда в бинарнике | Обоснование кода |
|---|---|---|---|
| User Defaults | `CA92.1` | `_OBJC_CLASS_$_NSUserDefaults` — `IosBuildLibraryStorage` (библиотека билдов) | чтение/запись данных, доступных только самому приложению |
| File Timestamp | `C617.1` | `_stat`, `_fstat` — Skia `SkOSFile_posix/stdio`, ICU `umapfile` (размер/наличие файлов шрифтов и данных) | метаданные файлов внутри контейнера/бандла приложения |
| Disk Space | `E174.1` | `_NSURLVolumeAvailableCapacityForImportantUsageKey` + `resourceValuesForKeys:` — `PlatformOtaFileStore.freeSpaceBytes` | проверка места перед записью файлов (OTA; код слинкован, хотя OTA выключен) |
| System Boot Time | — | `mach_absolute_time`, `systemUptime` **не импортируются** | не объявлено |
| Active Keyboards | — | `activeInputModes` нет | не объявлено |

`NSPrivacyTracking = false`, `NSPrivacyTrackingDomains = []`. `NSPrivacyCollectedDataTypes` — с 2026-10-26 одна запись,
Other Diagnostic Data (linked, без отслеживания, App Functionality): декларация App Privacy, [app-privacy §4](app-privacy.md#4-privacyinfoxcprivacy-и-проверка-архива). Сторонних SDK с
собственными манифестами/подписями ([third-party SDK requirements](https://developer.apple.com/support/third-party-SDK-requirements/))
нет: Compose Multiplatform/Skiko/Kotlin слинкованы статически в приложение, поэтому их API декларирует манифест
приложения. Скрипт `verify_ios_archive.py` повторяет анализ на каждом CI-архиве и падает, если появится
необъявленная категория. Окончательное подтверждение (отсутствие ITMS-91053) — загрузка подписанного архива: **BLOCKED**.

### 6.2 Хранение данных

| Данные | Где | Бэкап | Статус |
|---|---|---|---|
| Библиотека билдов | `NSUserDefaults.standardUserDefaults` (один ключ, JSON-конверт) | входит в бэкап iCloud/компьютера — так и сказано в About и iOS-политике | PASS |
| OTA-файлы (при OTA ON) | `Application Support/TalentForge/ota` | исключены (`NSURLIsExcludedFromBackupKey`) | PASS (код) |
| Буфер обмена | только по нажатию «Копировать»/«Вставить»; `hasStrings` перед чтением (без лишнего запроса вставки) | — | PASS |
| Разрешения | ни одного `NS*UsageDescription` | — | PASS |

### 6.3 Сеть

Сборка M5.IOS.1 не выполняет сетевых запросов: `GeneratedOtaSettings.iosProduction/iosStaging` — `enabled = false`,
`baseUrl = null`; в бинарнике нет `ota.mythweld.com` и SPKI production-ключей (проверка `ota-off`, UTF-8 и UTF-16LE).
Поток OTA ON (`NSURLSession`, HTTPS, `User-Agent: TalentForge/<версия>`, без cookies) и сведения, которые технически
получает CDN, описаны в [app-privacy §1](app-privacy.md#1-модель-и-факты).
Журналирование CDN/сервера не проверялось (нет доступа; OA-1…OA-4): в политике нет утверждений о журналах, кроме того,
что верно при любых настройках. Утверждений об аналитике, рекламе или трекинге нет нигде.

### 6.4 Политика конфиденциальности

| Пункт | Статус |
|---|---|
| iOS-редакция RU/EN подготовлена ([docs/legal/ios](../../legal/ios/README.md)) | PASS |
| Опубликована на `legal.mythweld.com` | **PASS** (обновлено 2026-10-25): опубликована владельцем 2026-10-24, `verify-live --date 2026-10-24` → PASS для обеих страниц ([evidence](evidence/m5-ios-3/privacy-pages-verify-live-2026-10-25.txt)). На 2026-10-23 страницы отвечали 403 |
| Ссылка в About (iOS) | **PASS** (код и тесты): `platformPrivacyPolicy = Ios`, RU/EN-страница по языку интерфейса (`IosAboutFactsTest`, `AboutScreenUiTest`). Открытие браузера на физическом iPhone — [runbook §6](submission/testflight-runbook.md#6-проверка-на-физическом-iphone) |
| Ссылка в App Store Connect | разблокирована публикацией; вносит владелец ([metadata §9](metadata-en.md#9-url-и-категория-общие-для-всех-языков), [runbook §7](submission/testflight-runbook.md#7-карточка-app-store-и-отправка-на-ревью)) |
| App Privacy answers | согласовано 2026-10-26 для OTA ON: **Data Linked to You: Diagnostics** ([app-privacy §2](app-privacy.md#2-декларация)); вводит владелец |

## 7. Готовность iOS OTA

| Компонент | Проверено | Статус |
|---|---|---|
| `OtaConfig` / `GeneratedOtaSettings` | генерация из Gradle-свойств; в репозитории/CI ничего не задано; все каналы `enabled = false` | PASS |
| Production OTA в бинарнике | нет origin и production-ключей (`ota-off`) | PASS (выключен) |
| `PlatformBlobFetcher` | NSURLSession, ephemeral, HTTPS-only, без редиректов, лимит размера, ATS по умолчанию (`NSAppTransportSecurity` нет) | PASS (код + `IosOtaAdapterTest`) |
| Подпись Security.framework | ECDSA P-256/SHA-256, векторы контракта, некорректные ключи/подписи → `false` | PASS (`IosOtaAdapterTest`) |
| Хранилище / атомарная установка | `fsync` + `rename(2)`, небезопасные пути отклоняются | PASS (`IosOtaAdapterTest`) |
| Ретеншн, восстановление, сохранность билдов | общая логика с Android (`OtaRuntime.open` → `recover()`) | PASS (общие тесты); на iOS end-to-end — PASS в M5.IOS.2 ([§4](m5-ios-2-ota-readiness.md#4-ios-staging-e2e-на-симуляторе)) |
| Совместимость с contract v1 и `https://ota.mythweld.com` | тот же клиент и контракт, что на Android | PASS (код) |
| Закрепление iOS production-канала за origin/keyring | M5.IOS.1: не закреплён. **Закрыто в M5.IOS.2**: origin и keyring закреплены так же, как у Android ([m5-ios-2-ota-readiness §2](m5-ios-2-ota-readiness.md#2-закрепление-production-канала-gradle)) | PASS (M5.IOS.2) |
| Staging/test ключи в production | правила `verifyOtaSettingsRules` запрещают | PASS |

План включения — [m5-ios-2-ota-plan.md](m5-ios-2-ota-plan.md) (14 шагов, включая все 12 обязательных пунктов задачи).

## 8. Материалы App Store

### 8.1 Иконка

| Проверка | Результат |
|---|---|
| `AppIcon-1024.png` — 1024×1024, RGB, без альфа-канала | PASS (`sips`) |
| Совпадает с утверждённым пакетом | PASS: SHA-256 `19596266…9b0c` = `rc2a-evidence/source/PACKAGE-SHA256SUMS.txt` |
| Asset catalog | PASS: один universal-слот 1024; в бандле `Assets.car`, `CFBundleIconName = AppIcon`, 120/152 px |
| Новый логотип | не создавался |

### 8.2 План скриншотов

Источник: [Screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/).
Все скриншоты — только из запущенного TalentForge (Release-сборка той же версии), без фиктивного UI и без изменений
интерфейса ради маркетинга; RU-набор — на устройстве с языком ru.

| Набор | Размер | Обязательность | Устройство для съёмки | Статус |
|---|---|---|---|---|
| iPhone с Dynamic Island (medium) | 1206×2622 или 1179×2556 | обязателен (минимум 1) | iPhone 17 Pro / iPhone 18 Pro | доказательства smoke уже 1206×2622 (оригиналы вне репозитория); финальные — после сборки на Xcode 27.1 |
| iPhone с Dynamic Island (large, 6.9") | 1320×2868 / 1290×2796 / 1260×2736 | рекомендуется (иначе масштабируются из large Face ID) | iPhone 18 Pro Max / 17 Pro Max | NOT RUN |
| iPad 13" | 2064×2752 (или 2048×2732) | обязателен (приложение поддерживает iPad) | iPad Pro 13-inch (M5) | доказательства smoke 2064×2752; финальные — NOT RUN |
| iPhone Duo — внешний | 1398×2034 | обязателен **с апреля 2027** для iOS 27.1 SDK+ (даёт бейдж «optimized for iPhone Duo») | iPhone Duo | **BLOCKED** (нет симулятора/устройства) |
| iPhone Duo — внутренний | 2007×2853 | то же | iPhone Duo | **BLOCKED** |

Последовательность (как в Google Play): 1) редактор с выбранным талантом; 2) описание рангов и «Показать»;
3) выбор класса; 4) Home; 5) «Поделиться»; 6) предпросмотр импорта; 7) iPad: дерево + панель. Без альфа-канала,
PNG/JPEG, до 10 на размер.

### 8.3 Метаданные

| Поле | EN | RU | Статус |
|---|---|---|---|
| Name | TalentForge (11) | TalentForge (11) | черновик |
| Subtitle | Talent builds for WoW: Forever (30) | Билды талантов WoW: Forever (27) | черновик |
| Description, Keywords, Promotional Text, What's New, Review Notes | [metadata-en](metadata-en.md) | [metadata-ru](metadata-ru.md) | черновик |
| Privacy Policy URL | `…/privacy-ios-en.html` | `…/privacy-ios.html` | PASS (опубликованы 2026-10-24, `verify-live` → PASS) |
| Support URL | `https://legal.mythweld.com/` | то же | PASS (200, контакт) |
| Категория | Reference (+ Utilities) | — | рекомендация |
| Дисклеймер | в Description, About и на сайте | то же | PASS |

Название игры (World of Warcraft: Forever / WoW: Forever) присутствует в Subtitle, Promotional Text и Description;
приложение не описывается как планировщик для произвольных игр.

### 8.4 Age rating (черновик ответов)

Анкета App Store Connect (обновлена Apple в 2026). Рекомендация — **ответы «None» по всем пунктам, кроме**:

| Вопрос | Предлагаемый ответ | Причина |
|---|---|---|
| Cartoon or Fantasy Violence | **Infrequent / Mild** | иконки и тексты талантов фэнтези-игры (оружие, заклинания, урон), без изображения насилия |
| Horror/Fear themes, Realistic Violence, Profanity, Sexual content, Alcohol/Tobacco/Drugs, Gambling, Contests, Medical | None | нет такого содержимого |
| Unrestricted web access, User-generated content, Messaging/Chat, Advertising | No | нет браузера, чата, рекламы; коды билдов — данные, не публикуемый контент |
| Parental controls / age assurance | No | нет учётных записей |

Ожидаемый результат — младший рейтинг с пометкой о мягком фэнтези-насилии (Apple вычисляет его сама). Решение — за
владельцем.

## 9. CI

`.github/workflows/mobile-ci.yml`, job **«Mobile (macOS) — iOS, Xcode 27.1»** (`runs-on: xcode-27`):

| Проверка | Как | Статус |
|---|---|---|
| Явный выбор Xcode 27.1 и SDK | `xcode-select --switch /Applications/Xcode_27.1.app`; build = `27A9275`; `iphoneos27.1` и `iphonesimulator27.1` в `-showsdks`; иначе `::error::BLOCKED` и job красный | подготовлено |
| iOS release framework link | `linkRelease…IosArm64/IosSimulatorArm64` (+ debug) | подготовлено |
| iOS simulator tests | `:shared:iosSimulatorArm64Test` + новый `:composeApp:iosSimulatorArm64Test` | подготовлено |
| iOS simulator app build | Release, `ARCHS=arm64` | подготовлено |
| Unsigned Release archive + проверка | `xcodebuild archive` + `verify_ios_archive.py` (ожидаемые версия/build из `-showBuildSettings`, SDK 27.1, Xcode 27A9275) | подготовлено |
| Launch-smoke | `scripts/ios_simulator_smoke.sh` на iPhone 18 Pro, iPhone 18 Pro Max, iPad Pro 13-inch (M5); отсутствие устройства = FAIL | подготовлено |
| Android regression, shared tests, resource drift, OSS licences | job `mobile-linux` — **не изменён**; OSS iOS — в macOS-job | без изменений |
| Python (`ci.yml`) | `test_verify_ios_archive.py` запускается в `pytest` | автоматически |

Ограничения: образ `xcode-27` — **preview** GitHub (возможны очереди; actionlint пока не знает эту метку). Xcode 27.1 на
нём лежит как `Xcode_27.1_Release_Candidate.app` с алиасом `Xcode_27.1.app`; его build `27A9275` совпадает с
релизным Xcode 27.1 ([xcodereleases](https://xcodereleases.com): 27.1 Release = 27A9275, 2026-10-15), поэтому job
проверяет именно номер сборки. Если образ уберут или поменяют, job упадёт (BLOCKED), а не позеленеет на старом Xcode.
Результат: первый прогон (39910279665) — сборка, архив и проверка 18/18 PASS, smoke FAIL на iPhone 18 Pro Max из-за
ошибки скрипта (§9.1); после исправления [прогон 39917498331](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/39917498331) — **оба job зелёные**: `mobile-linux` (Android,
контракты, дрейф) за 12 мин, `mobile-macos` (Xcode 27.1) за 44 мин, smoke PASS на iPhone 18 Pro, iPhone 18 Pro Max и
iPad Pro 13-inch (M5).

### 9.1 Падение smoke в первом прогоне — ошибка инфраструктуры, не приложения

- Симптом: `Child process terminated with signal 13: Broken pipe`, затем `FAIL [iPhone 18 Pro Max]: … is not running`.
- Первопричина (подтверждена): проверка `xcrun simctl spawn … launchctl list | grep -q <bundle>` под `set -o pipefail`.
  `grep -q` закрывает канал на **первом совпадении**; `launchctl` (строка приложения — в начале вывода из сотен строк)
  ещё пишет и получает SIGPIPE; `simctl spawn` сообщает о сигнале и возвращает ненулевой код; `pipefail` делает весь
  конвейер ложным. Сообщение о SIGPIPE само доказывает, что строка приложения **была найдена**: без совпадения `grep`
  читает поток до конца и SIGPIPE не возникает. Гонка зависит от объёма вывода и планирования, поэтому на iPhone 18 Pro и
  iPad проверка прошла.
- Исправление (`scripts/ios_simulator_smoke.sh`): полный вывод `launchctl` сначала сохраняется в переменную, проверяется
  код завершения самого `simctl spawn`, строка приложения ищется без конвейера и должна содержать числовой PID (строка с
  `-` = не запущено); до 3 проверок с интервалом 2 с повторяют только **неудавшийся запрос** — отсутствие приложения
  окончательно; при FAIL сохраняются полный `launchctl list`, лог приложения из симулятора, `listapps` и новые crash
  reports; приложение и симулятор завершаются при любом исходе (`trap`); `pipefail` не убирался.
- Регрессия: `tests/unit/test_ios_simulator_smoke.py` (10 тестов) — поддельный `xcrun` с выводом ~10 МБ, строка приложения
  в начале, процесс умирает от SIGPIPE как настоящий: старый конвейер детерминированно даёт 141 (при том что `grep` нашёл
  строку), новая проверка находит PID; отсутствующее/мёртвое/чужое приложение, ошибка `simctl`, ограниченные повторы.

## 10. Только владелец

| # | Действие | Блокирует |
|---|---|---|
| OW-1 | Обновить macOS до Tahoe 26.6+ и установить **Xcode 27.1** (stable, не 27.2 RC); проверить `xcodebuild -version` = 27.1 (27A9275) | локальную сборку/архив на 27.1 |
| OW-2 | В Xcode выбрать Team (Signing & Capabilities, Automatic), собрать подписанный архив Product → Archive на Xcode 27.1, Validate App в Organizer. Team ID, сертификаты, профили, ключи API **не коммитить** | подписанный архив, ITMS-проверки |
| OW-3 | Создать приложение в App Store Connect (bundle id `io.github.alexeykravtsov95.talentforge`, SKU, основной язык EN, + RU) | всё в ASC |
| OW-4 | Опубликовать iOS-политику ([инструкция](../../legal/ios/README.md#3-публикация-выполняет-только-владелец)) и сообщить агенту | 5.1.1, ссылка в About и ASC |
| OW-5 | Ответить на App Privacy ([черновик](app-privacy.md)), export compliance, Age rating (§8.4), категорию | отправку |
| OW-6 | Утвердить тексты [metadata-en](metadata-en.md) / [metadata-ru](metadata-ru.md) | отправку |
| OW-7 | Загрузить билд в TestFlight, проверить на **физическом iPhone и iPad** (§4 сценарии) | 2.1, реальное устройство |
| OW-8 | Снять финальные скриншоты (§8.2), включая iPhone Duo до апреля 2027 | отправку (Duo — с апреля 2027) |
| OW-9 | Решить по журналам CDN (OA-1) перед M5.IOS.2 | App Privacy для OTA ON |
| OW-10 | Отправить на App Review — только после всех пунктов выше и code review этого PR | — |

## 11. Известные проблемы и блокеры

### Блокеры

| ID | Описание | Статус |
|---|---|---|
| B-1 | Нет Xcode 27.1 / iOS 27.1 SDK на машине (macOS 26.3.1 < 26.6) | unsigned-сборка на 27.1 — PASS в CI; локально и для подписанного архива — BLOCKED (OW-1) |
| B-2 | Нет подписанного дистрибутивного архива | BLOCKED (OW-2) |
| B-3 | iOS-политика не опубликована | BLOCKED (OW-4) |
| B-4 | iPhone 18 Pro / Pro Max проверены только launch-smoke в CI (PASS), интерактивно — нет; iPhone Duo — совсем | NOT RUN (нет симуляторов локально; Duo нет и в CI) |
| B-5 | Нет проверки на физическом устройстве и TestFlight | NOT RUN (OW-7) |

### Наблюдения (не исправлялись в этом PR)

| ID | Наблюдение | Почему не исправлено |
|---|---|---|
| O-1 | iPad: Home растягивает контент и «Новый билд» на всю ширину (TF-RC1-TABLET-WIDTH, открыт с RC1) | косметика P2; нужна дизайн-величина max-width (B+), решение дизайна |
| O-2 | iPad 13": на стартовой камере видны 1,5–2 дерева из трёх, третье — за краем или под панелью инспектора; остальное — панорамированием | так же выглядит утверждённый эталон M5.UI.TABLET.1 (`docs/screenshots/m5-ui-tablet-1/after/tablet1-1024x1366-portrait-paladin-en.png`): минимальный размер узла 68 dp. Изменение — вопрос к дизайну, не регрессия iOS |
| O-3 | На Home в меню «⋯» недавнего билда нет «Создать копию» (есть в «Все билды») | поведение общего кода, одинаково на Android; описание в сторе не обещает дублирование с Home |
| O-4 | `platformPrefersReducedMotion()` на iOS читает Reduce Motion один раз (`remember`); переключение в настройках без перезапуска экрана не проверялось. Dynamic Type проверен только при запуске с уже выбранным размером | smoke пройден; живое обновление — возможное улучшение, не требование |
| O-5 | iOS production OTA-канал не закреплён за origin/keyring в сборке (Android закреплён) | **закрыто в M5.IOS.2** ([readiness](m5-ios-2-ota-readiness.md)) |
| O-6 | Kotlin 2.4.20 официально заявляет Xcode 26.4; Xcode 27.x не заявлен | несовместимость не выявлена; эмпирика — CI 27.1 |
| O-7 | Bundle/executable называются `iosApp` (`CFBundleName = iosApp`); пользователю показывается `CFBundleDisplayName = TalentForge` | косметика, не влияет на Store; переименование таргета — отдельное решение |

## 12. Evidence

- [evidence/README.md](evidence/README.md) — отчёты верификатора архива и 23 скриншота симуляторов.
- [evidence/ci-xcode27.1/](evidence/ci-xcode27.1/) — отчёт проверки архива Xcode 27.1 и скриншоты launch-smoke iPhone 18 Pro /
  Pro Max / iPad Pro 13" из [прогона 39917498331](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/39917498331) (артефакт `ios-xcode-27.1-evidence`).
- Код проверок: [`scripts/verify_ios_archive.py`](../../../scripts/verify_ios_archive.py),
  [`scripts/ios_simulator_smoke.sh`](../../../scripts/ios_simulator_smoke.sh),
  [`tests/unit/test_verify_ios_archive.py`](../../../tests/unit/test_verify_ios_archive.py).
