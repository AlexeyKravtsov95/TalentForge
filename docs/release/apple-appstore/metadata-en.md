# M5.IOS.1 — App Store Connect: черновик метаданных (английский, en-US)

| | |
|---|---|
| Статус | **ЗАМЕНЁН в M5.IOS.3.** Окончательные тексты для App Store Connect (сборка OTA ON) — по файлу на поле в [`submission/metadata/en-US/`](submission/metadata/en-US/) и [`review_information/notes.txt`](submission/metadata/review_information/notes.txt), изменения — [submission §2.1](submission/README.md#21-что-изменилось-относительно-черновиков). Этот файл — история черновика M5.IOS.1/M5.IOS.2; в App Store Connect его не копировать |
| Сборка | тексты верны для сборки **OTA OFF** (эта readiness-сборка). Для OTA ON заменить строки по §8 после M5.IOS.2 |
| Позиционирование | неофициальное приложение; лицензия Blizzard **не заявляется**. Использование названия игры — `RISK ACCEPTED — OWNER DECISION` ([readiness §5](readiness.md#5-app-review-compliance)) |
| Лимиты Apple | Name ≤ 30, Subtitle ≤ 30, Promotional Text ≤ 170, Description ≤ 4000, Keywords ≤ 100 байт/символов (через запятую), What's New ≤ 4000 ([App information](https://developer.apple.com/help/app-store-connect/reference/app-information/app-information/), [Platform version information](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/)) |
| Счёт символов | `len()` Python точного текста в блоке (для кириллицы лимит Keywords считает байты UTF-8 — см. §5) |
| Язык | English (U.S.) — основная локализация |

Связанные документы: [русский черновик](metadata-ru.md) · [readiness](readiness.md) · [App Privacy](app-privacy.md)

## 1. Name

```text
TalentForge
```

11 символов. Совпадает с `CFBundleDisplayName` и названием в Google Play.

## 2. Subtitle

```text
Talent builds for WoW: Forever
```

30 символов. Назначение приложения и название игры (World of Warcraft: Forever в сокращении WoW: Forever).

## 3. Promotional Text (необязательно)

```text
Plan talent builds for all nine classes of World of Warcraft: Forever. Save them on your device and share them as codes. No account, no ads.
```

140 символов. Меняется без новой версии.

## 4. Description

```text
TalentForge is an unofficial talent build planner for World of Warcraft: Forever. Pick a class, spend points on its talent trees and keep every build on your iPhone or iPad.

Plan
• All nine classes, each with its three talent trees.
• Tap a talent to select it. Spending and refunding use clear buttons, never hidden gestures. Max out a talent in one step.
• Read each talent's description for its current and next rank, what it requires and how many points its tier needs.
• Jump to the talent that unlocks another one.
• Undo, redo and reset talents.
• Drag to move around the tree and pinch to zoom. Works in portrait and landscape, on iPhone and iPad.

Keep
• Builds are saved automatically on your device.
• Continue editing where you left off from the home screen.
• Rename, duplicate and delete builds.

Share
• Share a build as a short text code through any app, or copy the code.
• Import a code and preview the build before it is saved. An imported build never overwrites your other builds.

Private
• No account, no ads, no analytics, no tracking.
• Your builds stay on your device.
• This version works entirely offline: talent data is included with the app.

Languages
• English and Russian interface and talent texts, following your device language.

TalentForge is an independent, unofficial project. It is not affiliated with, endorsed by or sponsored by Blizzard Entertainment. World of Warcraft is a trademark or registered trademark of Blizzard Entertainment, Inc. in the U.S. and/or other countries.
```

1520 символов. Каждое утверждение сверено со сборкой M5.IOS.1 (smoke на симуляторах, [readiness §4](readiness.md#4-устройства-и-smoke)): девять классов, три дерева, кнопки +/−/Max, «Показать», отмена/повтор/сброс, жесты, портрет и ландшафт, автосохранение, переименование/копирование/удаление, код и импорт с предпросмотром, RU/EN. Не упоминаются функции, которых нет (облако, аккаунт, экипировка, цены).

## 5. Keywords

```text
talent,planner,build,calculator,forever,classic,tree,spec,warrior,mage,priest,druid,rogue,hunter
```

96 символов, 96 байт UTF-8. Без названий чужих приложений и без товарного знака (он уже в Subtitle). 

## 6. What's New (первая версия)

```text
First release for iPhone and iPad.
• Plan talent builds for all nine classes.
• Builds save automatically: rename, duplicate and delete them.
• Share a build as a code or import one with a preview.
• English and Russian.
```

220 символов.

## 7. App Review Notes

```text
TalentForge is a talent build planner for World of Warcraft: Forever. It is an independent, unofficial companion tool: it does not need the game, a Blizzard account or any other account, and it has no sign-in, no in-app purchases, no ads and no tracking.

How to review:
1. Tap "New build", choose a class (for example Mage).
2. Tap a talent to select it; use "+" / "−" / "Max" in the panel to spend or refund points. Dragging moves the tree, pinching zooms. Tapping a locked talent shows what it requires; "Show" jumps to that talent.
3. Use the ⋯ menu to rename or share the build. "Share" opens the iOS share sheet with a text code; "Copy code" copies it.
4. On the home screen, "Import build" → "Paste" → "Continue" previews the build from the code before "Import" saves it as a new build.
5. The ⓘ button opens About: version, talent data version, privacy summary and open-source licenses.

This build makes no network requests: all talent data is bundled. The interface follows the device language (English or Russian).
```

1025 символов. Демо-аккаунт не нужен (входа нет): в App Store Connect оставить «Sign-in required» выключенным.

## 8. Замены для сборки OTA ON (только после M5.IOS.2)

В Description заменить строку

```text
• This version works entirely offline: talent data is included with the app.
```

на

```text
• Talent data is included with the app. When a talent data update is published, the app downloads it over HTTPS and verifies its signature; without a connection it keeps working with the data it has.
```

а в App Review Notes заменить последний абзац («This build makes no network requests…») на (M5.IOS.2, сверено со сборкой OTA ON):

```text
Talent data updates (App Review Guideline 2.5.2): after launch, when returning to the app, from About → "Check for updates", and when a saved build needs a game-data version that is not on the device, the app downloads a signed public catalog and talent data files over HTTPS (GET only) from https://ota.mythweld.com. It verifies the catalog's ECDSA P-256 signature against keys built into the app and the SHA-256 of every file before using them. The files are data and images only — talent trees and texts (JSON) and talent icons (PNG) — never executable code; the app's behaviour and features do not change. Requests carry no account, device or advertising identifier and no build contents. Without a network connection the app keeps working with its bundled data. The interface follows the device language (English or Russian).
```

Длина Review Notes с этим абзацем ≈ 1 600 символов (лимит App Store Connect — 4 000).

Одновременно обновить App Privacy ([app-privacy.md §3](app-privacy.md#3-сборка-ota-on-m5ios2)) и опубликовать iOS-политику ([docs/legal/ios](../../legal/ios/README.md)).

## 9. URL и категория (общие для всех языков)

| Поле | Значение | Статус |
|---|---|---|
| Privacy Policy URL | `https://legal.mythweld.com/privacy-ios-en.html` (EN) и `https://legal.mythweld.com/privacy-ios.html` (RU, в локализации ru) | **PASS**: опубликованы владельцем 2026-10-24, `verify-live --date 2026-10-24` → PASS для обеих страниц на 2026-10-25 ([docs/legal/ios](../../legal/ios/README.md)) |
| Support URL | `https://legal.mythweld.com/` | PASS: страница отвечает 200 и содержит контакт `alexey.kravtsov95@gmail.com` (проверено 2026-10-23) |
| Marketing URL | не заполнять | — |
| Primary category | **Reference** | рекомендация; владелец решает |
| Secondary category | **Utilities** | рекомендация. Games не подходит: TalentForge — инструмент, а не игра |
| Copyright | `2026 Alexey Kravtsov` | владелец подтверждает |
| Age rating | см. [readiness §8.4](readiness.md#84-age-rating-черновик-ответов) | черновик |
