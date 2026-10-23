# M5.IOS.2 — iOS OTA ON: сборка, подпись и проверка (только владелец 🔑)

| | |
|---|---|
| Для кого | владелец: у агента нет Apple Developer-команды, сертификатов подписи, физического iPhone и доступа к App Store Connect |
| Что готово | закрепление production-канала в сборке, проверка архива в режимах OTA ON/OFF, iOS E2E на симуляторе ([readiness](m5-ios-2-ota-readiness.md)) |
| Чего здесь нет | загрузка в App Store, публикация, подпись production-каталога, promote/rollback на production: этот документ их **не** выполняет и не требует |
| Связанные документы | [readiness](m5-ios-2-ota-readiness.md) · [app-privacy](app-privacy.md) · [metadata-en](metadata-en.md) / [metadata-ru](metadata-ru.md) · [iOS-политика](../../legal/ios/README.md) · [production-deployment](../../ota/production-deployment.md) · [Android-аналог](../../ota/android-production-enablement.md) |

## 1. Что закреплено в репозитории

iOS production-канал **не настраивается**. Значения ниже зашиты в `mobile/composeApp/build.gradle.kts`, и сборка завершается ошибкой при попытке их изменить. Правила те же, что у Android с M5.OTA.PROD.3.

| Параметр | Значение | Где проверяется |
|---|---|---|
| Origin | `https://ota.mythweld.com` | `productionOtaOrigin`. Любой другой `talentforge.ota.ios.production.baseUrl` (даже `https://ota.mythweld.com/v2` или staging-хост) останавливает сборку: «baseUrl is pinned to https://ota.mythweld.com» |
| Канал и путь каталога | `production`: `GET https://ota.mythweld.com/v1/channels/production/catalog.json`, файлы — `…/v1/blobs/sha256/<hex>` | `OtaConfig.catalogUrl` / `blobUrl` (контракт v1 §1). URL из каталога не читаются: в каталоге нет URL-полей |
| Keyring | [`docs/ota/keys/production-keyring.json`](../../ota/keys/production-keyring.json): `tf-prod-2026a` (основной) + `tf-prod-2026b` (резервный), ECDSA P-256 / SHA-256 | `productionOtaKeyringFile`. Любой другой keyring (даже из `tf-prod-*` ключей) останавливает сборку |
| Отпечатки SPKI | `tf-prod-2026a` → `1fac1b0d…71239278`, `tf-prod-2026b` → `6997b3d4…78532b26` (SHA-256 DER SPKI) | `productionOtaSpkiSha256` в Gradle (каждая сборка) **и** `PRODUCTION_KEY_SPKI_SHA256` в `scripts/verify_ios_archive.py`; тест проверяет, что значения совпадают |
| Запрещено | `test-*`, `tf-staging-*`, ключ другого алгоритма, keyring без резервного ключа, trust anchor | `otaSettingsProblems` (Gradle) и `OtaConfig.resolve` (в приложении, fail-closed: канал выключается) |
| Staging в release | любая staging-настройка (Android **или** iOS) останавливает `linkReleaseFrameworkIos*` | `checkReleaseOtaSettings` теперь стоит и перед iOS release-линковкой |
| Транспорт | только `https://`, редиректы не выполняются, App Transport Security по умолчанию (в `Info.plist` нет `NSAppTransportSecurity`) | `PlatformBlobFetcher`, проверка архива `ats-default` |

## 2. Как включить и выключить

Единственная настройка владельца:

```text
talentforge.ota.ios.production.enabled=true
```

| Значение | Результат |
|---|---|
| не задано / `false` | **OTA OFF** (по умолчанию): `GeneratedOtaSettings.iosProduction` = `enabled = false`, в бинарнике нет origin и ключей, `IosOta.runtime()` = `null`, сетевых запросов нет |
| `true` | **OTA ON**: origin и keyring из §1 подставляются сборкой |
| любое другое | сборка завершается ошибкой («enabled must be true or false») |

Где её задать:

1. Переменная окружения `TALENTFORGE_OTA_IOS_PRODUCTION_ENABLED=true` в терминале, из которого запускается `xcodebuild`. Рекомендуется: действует ровно на одну сборку.
2. `~/.gradle/gradle.properties` владельца — только на время сборки OTA ON. Xcode, запущенный из Dock, не видит переменных терминала, поэтому для сборки из Xcode GUI нужен этот вариант. После сборки строку удалите.

**Нельзя** коммитить её в `mobile/gradle.properties` или `mobile/composeApp/gradle.properties`: сборка завершится ошибкой «… must not be committed».

Флаги платформ независимы, но сгенерированные настройки общие. Поэтому при `talentforge.ota.android.production.enabled=true` в окружении в iOS-бинарник тоже попадут origin и ключи, и проверка iOS-архива в режиме `--ota off` завершится FAIL. Это намеренно: собирайте iOS OTA OFF без Android-флага.

## 3. Перед сборкой OTA ON

- [ ] `CFBundleShortVersionString` / `CFBundleVersion` подняты отдельным PR, если в App Store Connect уже есть загрузка с этим номером. Этот PR версии **не** меняет (`1.0.0` / `1`).
- [ ] Production preflight (только чтение) → `OK`:

  ```bash
  .venv/bin/python scripts/ota_production_preflight.py --expect published --keyring docs/ota/keys/production-keyring.json
  ```

- [ ] До `expiresAt` production-каталога не меньше 30 дней. На 2026-10-23 осталось 42 дня (до 2026-12-05). При загрузке позже 2026-11-05 сначала выполните re-sign по [production-deployment](../../ota/production-deployment.md): `resign` → внешняя подпись → `attach-signature` → `verify-live`.
- [ ] iOS-политика опубликована ([docs/legal/ios](../../legal/ios/README.md)), App Privacy выбран ([app-privacy §3](app-privacy.md#3-сборка-ota-on-m5ios2)).

## 4. Сборка и проверка архива

Подписанный архив для App Store (Xcode 27.1, ваша команда):

```bash
export TALENTFORGE_OTA_IOS_PRODUCTION_ENABLED=true
cd mobile/iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS' -archivePath build/TalentForge-ota-on.xcarchive -allowProvisioningUpdates archive
```

Проверка архива **до** загрузки (тот же архив затем откроется в Organizer):

```bash
python3 scripts/verify_ios_archive.py --archive mobile/iosApp/build/TalentForge-ota-on.xcarchive --ota on --expected-version 1.0.0 --expected-build 1 --expected-sdk iphoneos27.1 --expected-xcode-build 27A9275 --report mobile/iosApp/build/ios-archive-ota-on-report.json
```

Ожидаемый результат: `RESULT: PASS`, в отчёте `"signed": true`, `ota.embeddedKeys` = `["tf-prod-2026a", "tf-prod-2026b"]`, `ota.productionOrigin` = `true`. Если в App Privacy выбран вариант «Data Collected → Other Diagnostic Data», сначала измените `PrivacyInfo.xcprivacy` ([app-privacy §3.2](app-privacy.md#32-вариант-b-журналы-хранятся)) и добавьте к проверке `--privacy-collected other-diagnostic`.

Затем Xcode → Window → Organizer → архив → **Distribute App** → App Store Connect. Загрузку выполняете только вы.

## 5. Проверка на физическом iPhone (TestFlight) — план

Агент эти шаги **не выполнял**: нет устройства и подписи. Результат каждого шага — `PASS` / `FAIL` / `NOT RUN`, с временем (UTC), моделью и версией iOS. Записывайте в [readiness §8](m5-ios-2-ota-readiness.md#8-физическое-устройство-и-testflight). Используйте сборку из §4, установленную через TestFlight. iPhone с iOS ≥ 17.2; лучше два — со старейшей поддерживаемой и с текущей версией iOS.

| # | Шаги | Ожидаемый результат |
|---|---|---|
| P-1 | Установить, включить авиарежим, запустить | Приложение сразу открывается на встроенных данных (`1.60.1.70450`); About: данные встроены в приложение; зависаний нет |
| P-2 | Выключить авиарежим, вернуться в приложение (или About → «Проверить обновления») | Загружается и активируется `1.60.1.70450-r1` (тот же build игры — первый OTA-релиз), About: «Загруженное обновление 1.60.1.70450-r1». Повторная проверка: данные актуальны |
| P-3 | На чистой установке во время P-2 свернуть приложение (Home) на 30 с, вернуться | Попытка отменяется при уходе в фон и продолжается при возврате; итог как в P-2, без сбоев |
| P-4 | Во время P-2 закрыть приложение из переключателя приложений, запустить снова | Холодный старт на прежних данных, затем загрузка завершается; «половинчатых» данных нет |
| P-5 | Создать и сохранить 2 билда до P-2, после P-2 открыть оба | Билды на месте, названия и распределения не изменились |
| P-6 | Открыть билд, затем дождаться активации (P-2) | Открытый редактор не меняет данные до закрытия; новый билд создаётся на новых данных |
| P-7 | Перезагрузить iPhone, разблокировать, открыть приложение | Приложение открывается, загруженные данные доступны. До первой разблокировки iOS приложение не запускает: фоновых режимов (`UIBackgroundModes`) у него нет |
| P-8 | Настройки iOS → TalentForge → выключить «Сотовые данные», отключить Wi-Fi, открыть приложение | Нет зависаний; встроенные или загруженные данные работают; в About проверка завершается ошибкой сети без потери данных |
| P-9 | Сеть IPv6-only с NAT64 (macOS: Системные настройки → Общий доступ → Общий интернет, «Create NAT64 Network» с зажатым Option), About → «Проверить обновления» | Проверка проходит (App Review 2.5.5) |
| P-10 | Удалить приложение и установить заново | Загруженные данные удалены вместе с приложением; P-1…P-2 повторяются |
| P-11 | Настройки → Основные → Хранилище iPhone → TalentForge | Размер «Документы и данные» разумен (≈ 5 МБ после P-2) |
| P-12 | Обновление поверх ранее установленной OTA OFF сборки (если была в TestFlight) с сохранёнными билдами | Все билды на месте и открываются |
| P-13 | По желанию: прокси (Charles) или `rvictl` + Wireshark | Запросы только к `ota.mythweld.com`, только `GET` на пути контракта, без cookies; содержимое билдов не передаётся |

App Review 2.5.2: загружаются только JSON-данные и PNG-иконки контракта v1, проверяемые подписью и SHA-256; исполняемого кода нет. На устройстве это видно косвенно (P-13); основное доказательство — код и E2E ([readiness §6](m5-ios-2-ota-readiness.md#6-app-store-privacy-и-app-review)).

## 6. Возврат к OTA OFF без потери билдов

1. Соберите следующую версию **без** настройки из §2 (с бо́льшим `CFBundleVersion`), проверьте архив с `--ota off` и загрузите.
2. В ней `IosOta.runtime()` = `null`: приложение работает на встроенных данных и не обращается к сети.
3. Билды хранятся отдельно (`NSUserDefaults`, ключ `talentforge.localBuildLibrary`). OTA-клиент их только читает, OTA OFF версия их не трогает. Загруженные файлы в `Application Support/TalentForge/ota` остаются неиспользованными; при повторном включении OTA клиент их восстановит.
4. Ограничение: билд, сохранённый на версии игры, которая пришла **только** через OTA и не встроена в приложение, в OTA OFF версии не откроется (запись сохранится). Сейчас production-релиз `1.60.1.70450-r1` совпадает со встроенной версией `1.60.1.70450`, поэтому таких билдов нет.
5. Каталог и файлы на CDN при этом не трогаются. Откат **на сервере** без новой версии приложения — `rollback` из [production-deployment](../../ota/production-deployment.md); клиент не активирует релиз ниже встроенного (`KEEP_BUNDLED_FLOOR`).

## 7. Только владелец

| # | Действие | Статус |
|---|---|---|
| OW-1 | Решить App Privacy для OTA ON по фактам о журналах CDN (OA-1) — [app-privacy §3](app-privacy.md#3-сборка-ota-on-m5ios2) | **BLOCKED** (владелец) |
| OW-2 | Опубликовать iOS-политику RU/EN и сообщить агенту для PR со ссылкой в About | **BLOCKED** (владелец; на 2026-10-23 страницы отвечают 403) |
| OW-3 | Подписанный архив OTA ON (§4), проверка `--ota on` | NOT RUN |
| OW-4 | TestFlight и физический iPhone, P-1…P-13 (§5) | NOT RUN |
| OW-5 | Метаданные OTA ON в App Store Connect, App Review Notes ([metadata-en §8](metadata-en.md#8-замены-для-сборки-ota-on-только-после-m5ios2)), Privacy Policy URL | NOT RUN |
| OW-6 | Re-sign production-каталога до истечения: для загрузки нужен запас ≥ 30 дней | по [production-deployment](../../ota/production-deployment.md) |
| OW-7 | Загрузка в App Store и отправка на ревью | NOT RUN |
