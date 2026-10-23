# M5.IOS.1 — evidence

Файлы в корне и `simulator/` получены 2026-10-23 на машине разработчика: macOS 26.3.1, **Xcode 26.4.1 (17E202), iOS 26.4 SDK**.
Это **не** доказательство сборки на Xcode 27.1 (её выполняет `mobile-ci`, job «Mobile (macOS) — iOS, Xcode 27.1»).
Секретов, ключей, профилей и Team ID нет.

## Архив

| Файл | Что это |
|---|---|
| `ios-archive-verify-xcode26.4.1-local.txt` | вывод `scripts/verify_ios_archive.py` с ожиданиями локального тулчейна (`iphoneos26.4`, `17E202`): **PASS** по всем 18 проверкам |
| `ios-archive-report-xcode26.4.1-local.json` | JSON-отчёт той же проверки (Info.plist, SHA-256 исполняемого файла, использованные required-reason API) |
| `ios-archive-verify-against-xcode27.1-local.txt` | тот же архив с ожиданиями Xcode 27.1 (`iphoneos27.1`, `27A9275`): **FAIL** только по `toolchain` — доказательство, что сборка старым Xcode не проходит за 27.1 |

## Скриншоты симуляторов (`simulator/`)

Только из запущенной Release-сборки TalentForge (unsigned, симулятор, OTA OFF). Уменьшены до 1200 px по длинной стороне
для репозитория; это доказательства smoke, **не** маркетинговые скриншоты App Store.

| Префикс | Устройство | Runtime |
|---|---|---|
| `iphone17pro-*` | iPhone 17 Pro (6.3", Dynamic Island) | iOS 26.4 |
| `iphone17promax-*` | iPhone 17 Pro Max (6.9") — `scripts/ios_simulator_smoke.sh` | iOS 26.4 |
| `ipadpro13-*` | iPad Pro 13-inch (M5) | iOS 26.4 |
| `iphone12mini-ios26.0-*` | iPhone 12 mini (компактный 5.4", поддерживает iOS 17) | iOS 26.0 |

Сценарии по файлам — в [readiness §4](../readiness.md#4-устройства-и-smoke).

## CI, Xcode 27.1 (`ci-xcode27.1/`)

Из артефакта `ios-xcode-27.1-evidence` [прогона mobile-ci 39917498331](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/39917498331) (образ `xcode-27`, macOS 27.0.1,
Xcode 27.1 build 27A9275, симуляторы runtime iOS 27.0). Скриншоты уменьшены до 1200 px.

| Файл | Что это |
|---|---|
| `ios-archive-report-xcode27.1-ci.json` | `verify_ios_archive.py` по unsigned-архиву: **PASS 18/18**, `DTSDKName iphoneos27.1`, `DTXcodeBuild 27A9275` |
| `iphone-18-pro-launch-smoke.png` | iPhone 18 Pro, приложение работает через 15 с |
| `iphone-18-pro-max-launch-smoke.png` | iPhone 18 Pro Max, то же |
| `ipad-pro-13-inch-m5-launch-smoke.png` | iPad Pro 13-inch (M5), то же |
