# M5.IOS.3 — App Store submission pack: TalentForge 1.0.0

| | |
|---|---|
| Что это | всё, что владелец вводит в App Store Connect для первой версии: тексты (по файлу на поле), скриншоты, ответы анкет, runbook TestFlight |
| Сборка | **1.0.0 (1), OTA ON** (production `https://ota.mythweld.com`). Тексты описывают именно OTA ON сборку |
| Позиционирование | TalentForge — **неофициальный** планировщик талантов **World of Warcraft: Forever**. Лицензия Blizzard не заявляется; использование названия и материалов игры — `RISK ACCEPTED — OWNER DECISION` ([readiness §5](../readiness.md#5-app-review-compliance)) |
| Что здесь не делается | создание записи в App Store Connect, загрузка сборки, отправка на ревью, публикация — только владелец ([runbook](testflight-runbook.md)) |
| Требования Apple | [Screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/), [Platform version information](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/), [App information](https://developer.apple.com/help/app-store-connect/reference/app-information/app-information/) — прочитаны 2026-10-24 |

## 1. Содержимое

| Путь | Что |
|---|---|
| [`metadata/`](metadata/) | по одному UTF-8 файлу на поле App Store Connect (§2). Файл = точный текст поля, без кавычек и комментариев |
| [`screenshots/`](screenshots/) | 18 PNG: `<locale>/<display>/<NN>-<screen>.png` + `manifest.json`. В App Store Connect загружать по возрастанию `NN`: `iphone-6.3/` — в «iPhone 6.3" Display», `ipad-13/` — в «iPad 13" Display» ([readiness §2](../m5-ios-3-readiness.md#2-задача-a--скриншоты-app-store)) |
| [`testflight-runbook.md`](testflight-runbook.md) | шаги владельца: Apple Developer → подпись → архив → проверка → TestFlight → iPhone → карточка и ревью |
| `scripts/verify_appstore_metadata.py` | проверка текстов: лимиты Apple, формат файлов, ключевые слова, позиционирование, утверждения OTA ON, URL |
| `scripts/verify_appstore_screenshots.py` | проверка скриншотов: размеры по классу дисплея, PNG RGB без альфа-канала, целостность, нумерация, паритет локалей, manifest |
| `scripts/verify_ios_project.py` | проверка `iosApp.xcodeproj` до архива: bundle id, версия, подпись без закоммиченной команды, Release-архив, `Info.plist` |
| `scripts/verify_ios_archive.py --require-signed` | новый флаг: настоящая проверка подписи архива владельца на macOS — `codesign --verify --strict`, identity и Team ID из подписи, профиль через `security cms` (команда, bundle id, срок, сертификат, entitlements), запрет ad-hoc и Ad Hoc/enterprise-конфигураций; без инструментов — BLOCKED (код 2) |
| `scripts/ios_privacy_pages.py` | подстановка даты в iOS-политику и проверка опубликованных страниц (HTTP 200, `Content-Type`, побайтовое совпадение) |

Проверка всего пакета:

```bash
python3 scripts/verify_appstore_metadata.py
```

```bash
python3 scripts/verify_appstore_screenshots.py
```

## 2. Поля App Store Connect → файлы

Тексты основаны на черновиках M5.IOS.1/M5.IOS.2 ([metadata-en](../metadata-en.md), [metadata-ru](../metadata-ru.md)) с
изменениями из §2.1. Лимиты Apple: в символах — Name/Subtitle 30, Promotional Text 170, Description 4000; **в байтах** —
Keywords 100, App Review Notes 4000. Счёт — `verify_appstore_metadata.py` (2026-10-24):

| Раздел ASC | Поле | Файл EN (en-US) | Файл RU (ru) | EN | RU |
|---|---|---|---|---|---|
| App Information | Name | [name](metadata/en-US/name.txt) | [name](metadata/ru/name.txt) | 11 / 30 | 11 / 30 |
| App Information | Subtitle | [subtitle](metadata/en-US/subtitle.txt) | [subtitle](metadata/ru/subtitle.txt) | 30 / 30 | 27 / 30 |
| App Information | Primary / Secondary Category | [primary](metadata/primary_category.txt) · [secondary](metadata/secondary_category.txt) | — | Reference / Utilities | — |
| Version 1.0 | Promotional Text | [promotional_text](metadata/en-US/promotional_text.txt) | [promotional_text](metadata/ru/promotional_text.txt) | 163 / 170 | 170 / 170 |
| Version 1.0 | Description | [description](metadata/en-US/description.txt) | [description](metadata/ru/description.txt) | 1907 / 4000 | 2073 / 4000 |
| Version 1.0 | Keywords | [keywords](metadata/en-US/keywords.txt) | [keywords](metadata/ru/keywords.txt) | 98 / 100 байт | 100 / 100 байт |
| Version 1.0 | Support URL | [support_url](metadata/en-US/support_url.txt) | [support_url](metadata/ru/support_url.txt) | `https://legal.mythweld.com/` | то же |
| Version 1.0 | Marketing URL | — | — | не заполнять | — |
| Version 1.0 | What's New | — | — | **поля нет** у первой версии | — |
| Version 1.0 | Copyright | [copyright](metadata/copyright.txt) | — | `2026 Alexey Kravtsov` (знак © App Store Connect добавит сам) | — |
| App Privacy | Privacy Policy URL | [privacy_url](metadata/en-US/privacy_url.txt) | [privacy_url](metadata/ru/privacy_url.txt) | `…/privacy-ios-en.html` | `…/privacy-ios.html` |
| App Review Information | Notes (одно поле на версию, не локализуется) | [notes](metadata/review_information/notes.txt) | — | 2032 / 4000 байт | — |

### 2.1 Что изменилось относительно черновиков

| Поле | Изменение | Почему |
|---|---|---|
| Description EN/RU | из «Private»/«Приватно» убрано «This version works entirely offline…» / «Эта версия полностью работает без интернета…». Добавлен раздел **Works offline / Работает без сети**: данные встроены, приложение работает без сети с первого запуска; обновления — **только игровые данные** (деревья, тексты, иконки талантов) по HTTPS с проверкой подписи разработчика, **код никогда не загружается**; без сети — встроенные и ранее загруженные данные. «Your builds … are never uploaded» | сборка OTA ON. Формулировки сверены с кодом и измерениями: только `GET` по HTTPS (`PlatformBlobFetcher`), ECDSA P-256 + SHA-256, контракт v1 (JSON + PNG) — [readiness M5.IOS.2 §6](../m5-ios-2-ota-readiness.md#6-app-store-privacy-и-app-review) |
| Promotional Text EN/RU | + «keep planning offline» / «работайте без сети» | то же |
| Keywords EN | убраны `talent`, `build`, `forever` (уже в Subtitle); добавлены `paladin`, `shaman`, `warlock` — теперь в ключевых словах все девять классов | Name и Subtitle App Store индексирует сам; ключевые слова не должны повторять название ([Platform version information](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/)) |
| Keywords RU | убраны `билд`, `forever` (в Subtitle) и `класс`, `ветка`; добавлены `калькулятор`, `маг`, `жрец`, `друид` | то же; лимит — 100 **байт** (кириллица — 2 байта на символ) |
| What's New | удалён из пакета | у первой версии этого поля нет («Not available for the first version») |
| App Review Notes | одно поле (EN) вместо двух: в App Store Connect Notes не локализуется. Явно: неофициальный статус и отсутствие лицензии Blizzard, «no demo account is needed», механизм OTA (2.5.2), «every feature can be reviewed offline» | один текст для ревьюера; лимит в байтах |

Утверждения Description сверены со сборкой: девять классов, три дерева, tap выбирает, `+`/`−`/`Max`, «Показать»,
отмена/повтор/сброс, pan/pinch, портрет/ландшафт, автосохранение, переименование/копия/удаление, код и импорт с
предпросмотром, RU/EN ([readiness M5.IOS.1 §4](../readiness.md#4-устройства-и-smoke); подписи кнопок —
`mobile/composeApp/src/commonMain/composeResources/values*/strings.xml`).

## 3. Ответы анкет App Store Connect (решает владелец)

Агент **не выбирает** ответы за владельца: ниже — факты и рекомендация.

| Анкета | Рекомендация | Факт / основание | Статус |
|---|---|---|---|
| Category | Primary **Reference**, Secondary **Utilities** | инструмент-справочник, не игра (Games не подходит) | рекомендация |
| Age Rating | все ответы «None»/«No», кроме **Cartoon or Fantasy Violence → Infrequent/Mild** | тексты и иконки талантов фэнтези-игры (заклинания, урон) без изображения насилия; нет UGC, чата, браузера, рекламы, покупок, учётных записей ([readiness §8.4](../readiness.md#84-age-rating-черновик-ответов)). Итоговый рейтинг вычисляет Apple | решение владельца |
| Content Rights («contains, shows, or accesses third-party content») | **Yes**: приложение показывает данные, тексты и иконки World of Warcraft: Forever | следующий вопрос («Do you have all necessary rights…») — юридическое заявление владельца. В репозитории: лицензии Blizzard нет, решение `RISK ACCEPTED — OWNER DECISION`, юридические пункты LR открыты ([legal1-release-options](../../legal1-release-options.md)) | **BLOCKED** (решение владельца) |
| App Privacy | вариант **A** или **B** ([app-privacy §3](../app-privacy.md#3-сборка-ota-on-m5ios2), аудит M5.IOS.3 — [§5](../app-privacy.md#5-аудит-m5ios3-ota-on)) | «Data Not Collected» для OTA ON **не** заявлять без факта о журналах CDN (OA-1) | **BLOCKED** (OA-1) |
| Export Compliance | `ITSAppUsesNonExemptEncryption = false` (в `Info.plist`) | только HTTPS ОС и проверка подписи | PASS (ответ встроен в сборку) |
| Sign-in required | **No** | входа нет | рекомендация |
| Pricing | Free, без встроенных покупок | — | решение владельца |
| DSA trader status (ЕС) | — | trader / non-trader — юридическое решение | решение владельца |
| Version Release | Manually release this version | выпуск после одобрения — отдельное решение | рекомендация |
| iPhone Duo screenshots | для этой отправки не требуются | обязательны **с апреля 2027** для сборок на iOS 27.1 SDK+; симулятора iPhone Duo нет ни локально, ни в CI | NOT RUN (сейчас не требуется) |

## 4. Статус

**PASS** — проверено здесь реально; **BLOCKED** — нужно действие владельца; **NOT RUN** — не выполнялось (причина указана).
Полный отчёт — [readiness M5.IOS.3](../m5-ios-3-readiness.md).

| # | Пункт | Статус | Доказательство / что нужно |
|---|---|---|---|
| A | Скриншоты iPhone 6.3" (6) и iPad 13" (3), EN и RU, из Release-сборки | **PASS** | `verify_appstore_screenshots.py` → PASS; [readiness §2](../m5-ios-3-readiness.md#2-задача-a--скриншоты-app-store) |
| B1 | Метаданные EN/RU: лимиты Apple, позиционирование, OTA ON, URL | **PASS** | `verify_appstore_metadata.py` → PASS (§2) |
| B2 | Утверждение текстов владельцем | **BLOCKED** | владелец |
| C1 | iOS-политика RU/EN опубликована (HTTP 200, HTML с декларацией UTF-8, байты = файл с датой) | **PASS** | опубликовано 2026-10-24; `verify-live --date 2026-10-24` → PASS для обеих страниц на 2026-10-25; [evidence](../evidence/m5-ios-3/privacy-pages-verify-live-2026-10-25.txt) |
| C2 | Ссылка на политику в About | **PASS** (код, тесты, симулятор) | iOS открывает отдельную RU/EN-страницу по языку интерфейса; native-маппинг — `IosAboutFactsTest` (iOS CI), нажатие — desktop `AboutScreenUiTest`; нажатие в Release OTA ON на симуляторе iPhone (RU и EN) и iPad (EN) открывает Safari на нужной iOS-странице, возврат сохраняет состояние — [приёмка §2.4](../m5-ios-acceptance.md#24-about-политика-конфиденциальности); физический iPhone — шаг D4, [runbook §6](testflight-runbook.md#6-проверка-на-физическом-iphone) п. 2 |
| C3 | App Privacy для OTA ON | **BLOCKED** (OA-1, решение владельца) | [app-privacy §5](../app-privacy.md#5-аудит-m5ios3-ota-on); сообщение владельца 2026-10-25 о журналах VK Cloud — [OWNER_ACTION_REQUIRED §1.1](../../../legal/ota-on/OWNER_ACTION_REQUIRED.md#11-сообщение-владельца-2026-10-25) |
| C4 | App Review Notes | **PASS** (текст) | `review_information/notes.txt` |
| D1 | `iosApp.xcodeproj` пригоден для App Store Connect | **PASS** | `verify_ios_project.py` → 14/14 |
| D2 | Unsigned-архив OTA ON на текущем `main` | **PASS** (локально, Xcode 26.4.1 / SDK `iphoneos26.4`) | 22/22 ([evidence](../evidence/m5-ios-3/)); Xcode 27.1 — CI `mobile-macos` |
| D3 | Подписанный архив на Xcode 27.1 + `--require-signed` | **BLOCKED** | владелец, [runbook §3–4](testflight-runbook.md#3-подписанный-archive-ota-on) |
| D4 | TestFlight, физический iPhone | **BLOCKED** | владелец, [runbook §5–6](testflight-runbook.md#5-загрузка-в-testflight) |
| D5 | Content Rights, Age Rating, DSA, цена | **BLOCKED** (решения владельца) | §3 |
| D6 | Загрузка, отправка на ревью, публикация | **NOT RUN** | запрещено условиями задачи; только владелец |
| E | Пользовательские сценарии Release OTA ON (`c21be4f`) на симуляторах iPhone и iPad | **PASS** (локально, Xcode 26.4.1); первый запуск без сети — **NOT RUN** на симуляторе | [приёмка](../m5-ios-acceptance.md); без сети — на устройстве, [runbook §6](testflight-runbook.md#6-проверка-на-физическом-iphone) или [§8](testflight-runbook.md#8-необязательно-личный-iphone-через-personal-team-до-активации-членства) |
