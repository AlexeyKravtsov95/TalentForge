# Замена iOS-иконки на утверждённую владельцем — проверка (2026-10-25)

## Источник

| | Факт |
|---|---|
| Файл | [`TalentForge-AppIcon-source-1254.png`](../../assets/TalentForge-AppIcon-source-1254.png) — передан владельцем 2026-10-25 как утверждённая иконка (вариант, подготовленный для Google Play) |
| Формат | PNG, **1254 × 1254**, RGB 8 бит, без альфа-канала, без ICC-профиля (sRGB по умолчанию) |
| SHA-256 | `1a3924b43669f18a63ab7114d410ad4d70cd4204ad64547fb7637c11dd7fa32c` |
| Не апскейл | пересэмплирование через 512 px теряет в 4,5 раза больше деталей (средняя разница 3,15), чем у bicubic-увеличенного 512-px файла (0,70): исходник содержит детали выше 512 px |
| Артефакты | при 100 % на кристалле, сферах и рамке дефектов нет; серого пятна на кристалле прежней иконки нет ([до/после](before-after.png)) |
| Композиция | в рисунок встроены собственная скруглённая плашка с рамкой и тёмное поле (~10 % по краям). Рисунок не менялся: ни обрезки, ни маски. Под маской iOS плашка видна внутри системного скругления — это часть утверждённого изображения |

Прежде вложенный в чат вариант 512 × 512 px — тот же рисунок, но меньше требуемых Apple 1024 × 1024 px — не использовался.

## Изменённые ресурсы

| Ресурс | Было | Стало |
|---|---|---|
| `mobile/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` (единственный размер, `universal`, `ios`, 1024 × 1024 — актуальный формат Xcode) | прежний рисунок с дефектом | Lanczos 1254 → 1024, RGB без альфа, SHA-256 `42a17911f2e0d63493cdec5247f2948bd37e27bfb5dab12789c248d5c584cc9c` |
| `mobile/composeApp/src/commonMain/composeResources/drawable/app_icon.png` (иконка в About, общий ресурс iOS и Android) | побайтная копия Android `mipmap-xxxhdpi/ic_launcher.png` (тот же дефект) | Lanczos 1254 → 192, RGB, SHA-256 `828b028ffbb6ff220f200bc7ce651eb226efbbb5bd20267668ae9bafad8371b6` |

`Contents.json` не менялся. **Android:** About на Android теперь тоже показывает новый рисунок; launcher-иконка Android
(`mipmap-*`, adaptive `drawable-nodpi/ic_launcher_*`) не менялась. `AppIconResourceTest` сверяет About-иконку с iOS
AppIcon (средняя разница каналов < 6; для новой иконки 1,9, для прежней 27,0 — тест падает), а не с Android launcher.

## Сборка и окружение

| | Факт |
|---|---|
| Xcode / SDK | **26.4.1 (17E202)** / `iphonesimulator26.4` |
| Runtime | iOS 26.4.1 (23E254a) |
| Команда | `env TALENTFORGE_OTA_IOS_PRODUCTION_ENABLED=true` `xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS Simulator' -derivedDataPath <scratch> CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build` → `** BUILD SUCCEEDED **` |
| `Info.plist` | `io.github.alexeykravtsov95.talentforge`, `1.0.0` / `1`, `CFBundleDisplayName = TalentForge`, `CFBundleIconName = AppIcon` (iPhone и iPad) |
| `Assets.car` (`assetutil --info`) | `AppIcon-1024.png` 1024 × 1024, `Opaque: true`, `srgb`, idiom `phone` и `pad` |
| Исполняемый файл | SHA-256 `76560040060e2fa50f5cc1d3dffea5c734bb36f89c4668f111b43763da564a5d` |
| OTA ON (`verify_ios_archive.py` `_ota_checks`, режим `on`) | PASS `ota-keyring`, PASS `ota-no-staging-origin`, PASS `ota-on` (origin `https://ota.mythweld.com`, ключи `tf-prod-2026a`, `tf-prod-2026b`) |

## Визуальная проверка

Новые симуляторы, созданные только для этой проверки (прежние «TF-Accept» и «TF-Shots» не тронуты):
«TF-Icon iPhone 17 Pro», «TF-Icon iPad Pro 13» (iPad Pro 13-inch M5), «TF-Icon-Upgrade iPhone 17 Pro».

| Проверка | Результат | Кадр |
|---|---|---|
| Домашний экран iPhone 17 Pro (чистая установка) | **PASS**: новый рисунок, без искажений и обрезки элементов | [iphone17pro-home](iphone17pro-home.png) |
| Домашний экран iPad Pro 13 (чистая установка) | **PASS** | [ipadpro13-home](ipadpro13-home.png) |
| About, iPhone (EN) | **PASS**: новый рисунок в шапке | [iphone17pro-about-en](iphone17pro-about-en.png) |
| About, iPad (EN) | **PASS** | [ipadpro13-about-en](ipadpro13-about-en.png) |
| Кеш при обновлении | **PASS**: на «TF-Icon-Upgrade» установлена копия этой же сборки со старой иконкой (`actool` из прежнего `AppIcon-1024.png`), затем поверх — новая сборка; домашний экран сразу показал новую иконку | [icons-1to1](icons-1to1.png): 3-й кадр — до, 4-й — после |
| Масштаб 1:1 | все кадры иконки и About | [icons-1to1](icons-1to1.png) |

Кадры домашнего экрана и About уменьшены в 2 раза; `icons-1to1.png` — пиксели симулятора без масштабирования.
Наблюдение: плашка рисунка видна внутри системного скругления iOS с тёмным полем по краям — так задумано в
утверждённом рисунке, не искажение.

## Скриншоты App Store

Набор `submission/screenshots` (редактор, детали таланта, выбор класса, библиотека, код, импорт) не содержит ни экрана
About, ни иконки приложения: переснимать нечего, набор не менялся. Исторические evidence не переписывались.
