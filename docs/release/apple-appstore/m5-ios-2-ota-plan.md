# M5.IOS.2 — iOS OTA ON: план (не реализуется в M5.IOS.1)

| | |
|---|---|
| Статус | **ПЛАН.** В M5.IOS.1 production OTA на iOS **выключен** и не включается |
| Цель | включить на iOS тот же production OTA, что на Android (contract v1, `https://ota.mythweld.com`, ключи `tf-prod-2026a` + резерв `tf-prod-2026b`) после отдельного iOS release gate |
| Основа | [ota-contract-v1](../../ota/ota-contract-v1.md), [client-architecture](../../ota/client-architecture.md), [test-plan §6 C](../../ota/test-plan.md#c-ios-release-gate-only-before-a-public-ios-release-with-ota) (iOS release gate), [android-production-enablement](../../ota/android-production-enablement.md), [production-deployment](../../ota/production-deployment.md) |
| Merge | только после code review; самостоятельный merge запрещён |

## 0. Что уже готово (проверено в M5.IOS.1)

| Компонент | Состояние | Доказательство |
|---|---|---|
| `OtaConfig` / `GeneratedOtaSettings.iosProduction` / `.iosStaging` | генерируются из Gradle-свойств; в репозитории и CI ничего не задано → iOS OTA выключен | `composeApp/build/generated/ota/…/GeneratedOtaSettings.kt` (все каналы `enabled = false`); проверка `ota-off` архива |
| Выбор канала | release-бинарник → `iosProduction`, debug → `iosStaging` (`Platform.isDebugBinary`) | `OtaRuntime.ios.kt` |
| `PlatformBlobFetcher` (NSURLSession) | ephemeral session, без URL-кэша и cookies, только `https://`, редиректы запрещены, ограничение размера с отменой задачи, ATS по умолчанию | `IosOtaPorts.kt`, `IosOtaAdapterTest` |
| Подпись | Security.framework, ECDSA P-256 / SHA-256, `SecKeyVerifySignature`; любая ошибка = `false` | `PlatformSignatureVerifier`, `IosOtaAdapterTest` (векторы контракта) |
| Хранилище | `Application Support/TalentForge/ota`, исключено из бэкапа (`NSURLIsExcludedFromBackupKey`), `open`/`write`/`fsync`, атомарная замена через `rename(2)` | `PlatformOtaFileStore` |
| Установка, ретеншн, восстановление, сохранность билдов | общая логика `shared`/`composeApp` (та же, что на Android) | `OtaRuntime.open(... recover())`, jvm/iOS тесты |
| Privacy manifest | `DiskSpace E174.1` уже объявлен (проверка свободного места OTA) | `PrivacyInfo.xcprivacy`, `verify_ios_archive.py` |

## 1. Шаги M5.IOS.2

| # | Шаг | Критерий PASS |
|---|---|---|
| 1 | **Закрепить iOS production-канал в сборке** (как Android в M5.OTA.PROD.3): `talentforge.ota.ios.production.baseUrl` и `.keyring` по умолчанию = `https://ota.mythweld.com` и `docs/ota/keys/production-keyring.json`, любое другое значение ломает сборку; проверка SPKI-отпечатков; правила в `verifyOtaSettingsRules` | новые негативные кейсы проходят; без `ios.production.enabled=true` сгенерированные настройки как сейчас |
| 2 | **Staging E2E** на iOS-симуляторе со staging-стендом (debug, `iosStaging`, `tf-staging-*`) | каталог → релиз → установка → About показывает загруженные данные |
| 3 | **HTTPS и сертификаты** | неверный/просроченный/чужой сертификат → `NetworkError(retryable=false)`, данные не применяются; ATS не ослаблен (`NSAppTransportSecurity` отсутствует в `Info.plist`) |
| 4 | **Подписи и SHA-256** | подмена байта в каталоге/файле, чужой ключ, `test-` ключ → отказ, прежние данные |
| 5 | **Отключение сети** (авиарежим, Wi-Fi off) | приложение работает со встроенными/установленными данными; нет зависаний UI |
| 6 | **Прерывание загрузки** (убить приложение, отключить сеть посреди файла) | при следующем запуске `recover()` убирает частичные файлы, установка не «половинчатая» |
| 7 | **Повторный запуск** | после установки и холодного старта используется новый набор; повторная загрузка не выполняется без нужды |
| 8 | **Коррупция загруженных файлов** (испортить файл в `Application Support/TalentForge/ota`) | проверка целостности → откат к встроенным данным, без падения |
| 9 | **Сохранение редактора и билдов** | билды и открытый редактор переживают установку обновления; билд на старой версии данных открывается |
| 10 | **Rollback** (production-откат каталога по runbook) | клиент переходит на предыдущий релиз по правилам контракта |
| 11 | **Физический iPhone** (TestFlight, owner-signed) | шаги 2–9 на устройстве; нет ATS/сертификатных проблем вне симулятора |
| 12 | **Production keyring validation** | сборка с `ios.production.enabled=true` пиннит ровно `tf-prod-2026a` + `tf-prod-2026b`; `verify_ios_archive.py` получает режим OTA ON и требует оба SPKI и origin в бинарнике |
| 13 | **Privacy и магазин** | опубликована iOS-политика; App Privacy выбран по OA-1 ([app-privacy §3](app-privacy.md#3-сборка-ota-on-m5ios2)); `NSPrivacyCollectedDataTypes` соответствует; метаданные заменены ([metadata-en §8](metadata-en.md#8-замены-для-сборки-ota-on-только-после-m5ios2)) |
| 14 | **OTA OFF как kill switch** | сборка без `ios.production.enabled` (или с `false`) не делает ни одного запроса (проверено прокси/Network Link Conditioner), About без «Проверить обновления»; установленные ранее данные не ломают запуск |

## 2. Чего не делать

- Не копировать Android-специфику (`INTERNET`-разрешение, `noBackupFilesDir`, trust anchor staging для Android) в iOS.
- Не использовать staging или `test-` ключи в production; не добавлять исключения ATS.
- Не загружать исполняемый код: OTA доставляет только JSON-данные контракта v1 (App Review 2.5.2).
