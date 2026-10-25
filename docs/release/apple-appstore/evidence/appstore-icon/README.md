# Замена iOS-иконки на утверждённую владельцем — проверка (2026-10-25)

## Источник

| | Факт |
|---|---|
| Файл | [`TalentForge-AppIcon-source-1254.png`](../../assets/TalentForge-AppIcon-source-1254.png) — передан владельцем 2026-10-25 как утверждённая иконка (вариант, подготовленный для Google Play) |
| Формат | PNG, **1254 × 1254**, RGB 8 бит, без альфа-канала, без ICC-профиля (sRGB по умолчанию) |
| SHA-256 | `1a3924b43669f18a63ab7114d410ad4d70cd4204ad64547fb7637c11dd7fa32c` |
| Не апскейл | пересэмплирование через 512 px теряет в 4,5 раза больше деталей (средняя разница 3,15), чем у bicubic-увеличенного 512-px файла (0,70): исходник содержит детали выше 512 px |
| Артефакты | при 100 % на кристалле, сферах и рамке дефектов нет; серого пятна на кристалле прежней иконки нет ([до/после](before-after.png)) |

Прежде вложенный в чат вариант 512 × 512 px — тот же рисунок, но меньше требуемых Apple 1024 × 1024 px — не использовался.

## Вариант без плашки (решение владельца 2026-10-25)

В исходник встроены собственная скруглённая плашка с золотой рамкой и тёмное поле (~10 % по краям); под маской iOS
это давало «иконку в иконке». По решению владельца («вариант A») иконка — **обрезка исходника по внутренней границе
рамки**, без перерисовки и без маски:

| | |
|---|---|
| Область | квадрат 928 × 928 px, `crop((163, 128, 1091, 1056))` в координатах исходника ([рамка на сравнении](before-after.png)) |
| iOS AppIcon | Lanczos 928 → **1024** — увеличение **×1,10**: единственный шаг вверх, детали исходника выше 512 px сохранены, мелкие линии чуть мягче, чем при уменьшении |
| About | Lanczos 928 → 192 |
| Что изменилось в композиции | нет плашки, рамки и внешнего поля; рисунок занимает всю иконку. Верхняя сфера и кончик кристалла близко к краям, но не обрезаны. Углы обрезки (внутренние скругления рамки) скрыты маской iOS |

Воспроизведение (Pillow):

```python
from PIL import Image
inner = Image.open("docs/release/apple-appstore/assets/TalentForge-AppIcon-source-1254.png").crop((163, 128, 1091, 1056))
inner.resize((1024, 1024), Image.LANCZOS).save("AppIcon-1024.png", optimize=True)
inner.resize((192, 192), Image.LANCZOS).save("app_icon.png", optimize=True)
```

## Изменённые ресурсы

| Ресурс | Было | Стало |
|---|---|---|
| `mobile/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` (единственный размер, `universal`, `ios`, 1024 × 1024 — актуальный формат Xcode) | прежний рисунок с дефектом | вариант A, RGB без альфа, SHA-256 `da4d634cdaef29410636b5735aafc915c70a810697f500637cb5b3da5134a0e5` |
| `mobile/composeApp/src/commonMain/composeResources/drawable/app_icon.png` (иконка в About, общий ресурс iOS и Android) | побайтная копия Android `mipmap-xxxhdpi/ic_launcher.png` (тот же дефект) | вариант A 192 × 192, RGB, SHA-256 `daa84fd83d66be5abaca337cc4012f692a82c6317aeb84a71f2b26ecd0d0a8c0` |

`Contents.json` не менялся. **Android:** About на Android теперь тоже показывает новый рисунок; launcher-иконка Android
(`mipmap-*`, adaptive `drawable-nodpi/ic_launcher_*`) не менялась. `AppIconResourceTest` сверяет About-иконку с iOS
AppIcon (средняя разница каналов < 6; для новой иконки 2,4, для прежней 27,0 — тест падает), а не с Android launcher.

## Сборка и окружение

| | Факт |
|---|---|
| Xcode / SDK | **26.4.1 (17E202)** / `iphonesimulator26.4` |
| Runtime | iOS 26.4.1 (23E254a) |
| Команда | `env TALENTFORGE_OTA_IOS_PRODUCTION_ENABLED=true` `xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS Simulator' -derivedDataPath <scratch> CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build` → `** BUILD SUCCEEDED **` |
| `Info.plist` | `io.github.alexeykravtsov95.talentforge`, `1.0.0` / `1`, `CFBundleDisplayName = TalentForge`, `CFBundleIconName = AppIcon` (iPhone и iPad) |
| `Assets.car` (`assetutil --info`) | `AppIcon-1024.png` 1024 × 1024, `Opaque: true`, `srgb`, idiom `phone` и `pad` |
| About-ресурс в бандле | `compose-resources/…/app_icon.png` — SHA-256 совпадает с файлом варианта A |
| Исполняемый файл | SHA-256 `76560040060e2fa50f5cc1d3dffea5c734bb36f89c4668f111b43763da564a5d` (иконки — ресурсы, код не менялся) |
| OTA ON (`verify_ios_archive.py` `_ota_checks`, режим `on`) | PASS `ota-keyring`, PASS `ota-no-staging-origin`, PASS `ota-on` (origin `https://ota.mythweld.com`, ключи `tf-prod-2026a`, `tf-prod-2026b`) |

## Визуальная проверка

Симуляторы созданы только для этой проверки (прежние «TF-Accept» и «TF-Shots» не тронуты):
«TF-IconA iPhone 17 Pro» и «TF-IconA iPad Pro 13» (iPad Pro 13-inch M5) — чистая установка варианта A;
«TF-Icon iPhone 17 Pro» и «TF-Icon iPad Pro 13» — до этого стояла сборка с плашкой (первая версия PR), вариант A
установлен поверх; «TF-Icon-Upgrade iPhone 17 Pro» — проверка обновления со старой иконки main.

| Проверка | Результат | Кадр |
|---|---|---|
| Домашний экран iPhone 17 Pro, чистая установка | **PASS**: вариант A, без плашки, искажений и обрезанных элементов | [iphone17pro-home](iphone17pro-home.png) |
| Домашний экран iPad Pro 13, чистая установка | **PASS** | [ipadpro13-home](ipadpro13-home.png) |
| About, iPhone (EN) | **PASS**: вариант A в шапке | [iphone17pro-about-en](iphone17pro-about-en.png) |
| About, iPad (EN) | **PASS** | [ipadpro13-about-en](ipadpro13-about-en.png) |
| Кеш: старая иконка main → новая | **PASS**: на «TF-Icon-Upgrade» копия сборки со старой иконкой (`actool` из прежнего `AppIcon-1024.png`), затем поверх новая сборка — домашний экран сразу показал новую иконку | — |
| Кеш: сборка с плашкой → вариант A | **PASS**: на «TF-Icon» iPhone и iPad вариант A поставлен поверх; домашние экраны и About сразу показали вариант A | [icons-1to1](icons-1to1.png): 3-й кадр — до (с плашкой), 4-й — после |

`icons-1to1.png` (пиксели симулятора без масштабирования): iPhone home, iPad home, iPhone до обновления, iPhone после
обновления, About iPhone, About iPad. Остальные кадры уменьшены в 2 раза.

## Скриншоты App Store

Набор `submission/screenshots` (редактор, детали таланта, выбор класса, библиотека, код, импорт) не содержит ни экрана
About, ни иконки приложения: переснимать нечего, набор не менялся. Исторические evidence не переписывались.
