# M5.OTA.PROD.3 — включение production OTA в Android-сборке (owner-only)

| | |
|---|---|
| Статус | Код и guards готовы (PR M5.OTA.PROD.3). **Ни одна OTA ON сборка не опубликована.** Публикация, подпись и live-проверки выполняет только владелец |
| Платформа | Android: `release` (RuStore, AppGallery) и `play` (Google Play). iOS production OTA **остаётся выключенным**, этот документ его не касается |
| Связанные документы | [production-deployment](production-deployment.md) · [client-architecture §8](client-architecture.md) · [test-plan §6](test-plan.md) · [privacy и магазины](../release/ota-prod3-privacy-and-stores.md) · [evidence](../release/ota-prod3-evidence/README.md) · [android-release-signing](../release/android-release-signing.md) · [google-play/signing](../release/google-play/signing.md) |

## 1. Что закреплено в репозитории

Production-канал Android **не настраивается**. Все значения ниже зашиты в `mobile/composeApp/build.gradle.kts`, и сборка завершается ошибкой при попытке их подменить.

| Параметр | Значение | Где проверяется |
|---|---|---|
| Origin | `https://ota.mythweld.com` | `androidProductionOtaOrigin`. Любой другой `talentforge.ota.android.production.baseUrl` останавливает сборку: «baseUrl is pinned to https://ota.mythweld.com» |
| Канал | `production`, путь `/v1/channels/production/catalog.json` | `OtaConfig.catalogUrl` (контракт §1) |
| Keyring | [`docs/ota/keys/production-keyring.json`](keys/production-keyring.json): `tf-prod-2026a` (основной) + `tf-prod-2026b` (резервный) | `androidProductionOtaKeyringFile`. Любой другой keyring (даже с `tf-prod-*`) останавливает сборку. `verifyOtaSettingsRules` проверяет и сам закоммиченный файл |
| Запрещённые ключи | `test-*`, `tf-staging-*` и любые ключи без префикса `tf-prod-` | `otaSettingsProblems` (Gradle) **и** `OtaConfig.resolve` (в приложении, fail-closed: канал выключается) |
| Data level | приложение поддерживает `minDataLevel ≤ 1` (`OtaContract.SUPPORTED_DATA_LEVEL = 1`). Релиз с уровнем выше не активируется (`KEEP_CURRENT_INCOMPATIBLE`) | `ActivationPolicy`, `ReleaseVerifier` |
| Транспорт | только `https://`, редиректы не выполняются, cleartext запрещён платформой | `PlatformBlobFetcher` (`instanceFollowRedirects = false`), `OtaConfig.BASE_URL` |
| Trust anchors | в release/play только системные корни. Staging trust anchor допустим только в канале Android staging, а в release/play запрещены **любые** staging-настройки (Android и iOS) | `checkReleaseOtaSettings`, `AndroidOta` (trust anchor только при `FLAG_DEBUGGABLE`) |
| Подмена origin данными сервера | невозможна: URL каталога и blob-ов строятся только из скомпилированного origin и SHA-256 | `OtaConfig.catalogUrl` / `blobUrl`. В каталоге нет URL-полей (контракт §1) |

## 2. Как включить и выключить

Единственная настройка владельца:

```text
talentforge.ota.android.production.enabled=true
```

Где её задать (по убыванию приоритета):

1. переменная окружения `TALENTFORGE_OTA_ANDROID_PRODUCTION_ENABLED=true`, рекомендуется для одной сборки;
2. `-Ptalentforge.ota.android.production.enabled=true` в командной строке Gradle;
3. `~/.gradle/gradle.properties` владельца, вне репозитория.

**Нельзя** коммитить её в `mobile/gradle.properties` или `mobile/composeApp/gradle.properties`. Сборка завершится ошибкой «… must not be committed».

| Значение | Результат |
|---|---|
| не задано / `false` | OTA OFF: `GeneratedOtaSettings.androidProduction` = `enabled = false`, origin и ключей нет. Release/play manifest **без** `INTERNET` (overlay `src/androidRelease`). Поведение совпадает с прежней release-сборкой 1.0.0 |
| `true` | OTA ON: origin и keyring подставляются из §1. Release/play manifest **с** `INTERNET` (overlay `src/androidReleaseOta`) |
| любое другое | сборка завершается ошибкой («enabled must be true or false») |

Guards на каждой release/play сборке:

| Задача | Что проверяет |
|---|---|
| `checkReleaseInternetPermission` / `checkPlayInternetPermission` | **фактический merged manifest** варианта содержит `INTERNET` ровно тогда, когда `enabled=true`. Выполняется перед `packageRelease` / `packagePlayBundle` |
| `checkReleaseOtaSettings` | в release/play не задана ни одна staging-настройка (Android или iOS) |
| `generateOtaSettings` | все правила `otaSettingsProblems`, закрепление origin и keyring, отсутствие закоммиченных `talentforge.ota.*` |
| `verifyOtaSettingsRules` / `verifyReleaseInternetRules` | самопроверка правил на фиксированных входах (запускаются перед `desktopTest`) |
| `assemblePublishableRelease` / `bundlePublishablePlay` | валидаторы `verify_release_apk.py` / `verify_play_bundle.py` разрешают `INTERNET` **только** при `enabled=true` |

## 3. Сборка OTA ON для магазинов (выполняет только владелец 🔑)

Перед сборкой:

- [ ] `versionCode` / `versionName` подняты отдельным PR. Этот PR их **не меняет**. Обновление поверх 1.0.0 (`versionCode = 1`) требует большего `versionCode`. Для локального production OTA smoke (§4) `main` собирает `versionCode = 2`, `versionName = 1.0.0` (M5.OTA.SMOKE.VERSION); такую сборку в магазины не загружают. Публичный `versionCode` определяется после тестирования ([android-versioning](../release/android-versioning.md)).
- [ ] `ota_production_preflight.py --expect published --keyring docs/ota/keys/production-keyring.json` → OK, `verify-live` → PASS ([production-deployment §10](production-deployment.md)).
- [ ] До `expiresAt` каталога остаётся не меньше 30 дней.
- [ ] Privacy policy (RU/EN) обновлена и опубликована по тексту из [privacy и магазины §4](../release/ota-prod3-privacy-and-stores.md), декларации магазинов подготовлены (там же, §5–§7).

RuStore и AppGallery (подписанный ключом приложения APK):

```bash
export TALENTFORGE_OTA_ANDROID_PRODUCTION_ENABLED=true
# TALENTFORGE_RELEASE_* и TALENTFORGE_RELEASE_CERT_SHA256 — как в docs/release/android-release-signing.md
cd mobile && ./gradlew :composeApp:assemblePublishableRelease
```

Google Play (bundle, подписанный upload-ключом):

```bash
export TALENTFORGE_OTA_ANDROID_PRODUCTION_ENABLED=true
# TALENTFORGE_PLAY_UPLOAD_* и оба отпечатка — как в docs/release/google-play/signing.md
cd mobile && ./gradlew :composeApp:bundlePublishablePlay
```

После сборки проверьте отчёт рядом с артефактом (`*.report.json`): в `permissions` есть `android.permission.INTERNET`, все проверки `pass`. Дополнительно можно проверить, что в dex нет staging- и test-ключей:

```bash
unzip -p mobile/composeApp/build/outputs/publishable/TalentForge-*.apk '*.dex' | strings -n 6 | grep -oE 'tf-prod-[0-9a-z]+|tf-staging-[0-9a-z]+|https://ota\.[a-z.]+' | sort -u
```

Ожидаемый результат: `https://ota.invalid` (значение по умолчанию тестовой фабрики `OtaConfig.forTesting`, приложение его не вызывает), `https://ota.mythweld.com`, `tf-prod-2026a`, `tf-prod-2026b`, и **ничего** с `tf-staging-`.

## 4. Ручные проверки владельца (не выполнены, не отмечать PASS без запуска)

Эти проверки требуют настоящего production HTTPS или физического устройства, поэтому агент их не выполнял. Используйте OTA ON сборку из §3, подписанную ключом владельца (на устройство с установленным 1.0.0 её можно поставить поверх, если подпись совпадает). Записывайте результат и время (UTC) в [ota-prod3-evidence/README.md §4](../release/ota-prod3-evidence/README.md).

| # | Устройство | Шаги | Ожидаемый результат |
|---|---|---|---|
| M-1 | TECNO Spark 40 | Установить OTA ON сборку. Включить режим полёта, запустить | Приложение открывается сразу, About: «Included with the app», данные `1.60.1.70450` |
| M-2 | TECNO Spark 40 | Выключить режим полёта, About → «Check for updates» | Загружается и активируется `1.60.1.70450-r1` (по `ActivationPolicy`: встроенная версия игры та же, но активного OTA-релиза ещё нет), строка «Talent data updated to …», About: «Downloaded update 1.60.1.70450-r1». Повторная проверка: «up to date» |
| M-3 | TECNO Spark 40 | Во время загрузки M-2 (на чистой установке) включить режим полёта, затем выключить и снова «Check for updates» | Загрузка продолжается с уже скачанных файлов, приложение работает без сбоев |
| M-4 | TECNO Spark 40 | Открыть сохранённый билд, пока идёт проверка/активация | Открытый редактор не переключает dataset. Билд и его название не меняются |
| M-5 | TECNO Spark 40 | Обновление с 1.0.0 (OTA OFF) на OTA ON сборку с бо́льшим `versionCode` при наличии сохранённых билдов | Все билды на месте и открываются |
| M-6 | Эмулятор API 26 (Android 8.0) | M-1 и M-2 по реальному `https://ota.mythweld.com` | TLS-цепочка CDN принимается на API 26, загрузка и активация проходят |
| M-7 | Любое | `adb shell dumpsys package io.github.alexeykravtsov95.talentforge \| grep -A3 "requested permissions"` | `android.permission.INTERNET` есть в OTA ON сборке и отсутствует в OTA OFF |
| M-8 | Компьютер владельца | `verify-live` и preflight непосредственно перед загрузкой в магазин | PASS / OK |

## 5. Возврат к OTA OFF без потери билдов

1. Соберите следующую версию **без** настройки из §2 (`versionCode` больше, чем у OTA ON версии) и опубликуйте её.
2. В ней release/play manifest снова **без** `INTERNET` (guards это проверяют), `AndroidOta.runtime()` возвращает `null`, приложение работает на встроенных данных.
3. Билды пользователя хранятся отдельно (`SharedPreferences` `talentforge_build_library`). OTA-клиент их только читает, а OTA OFF версия их не трогает. Загруженные OTA-файлы в `noBackupFilesDir/ota` остаются на устройстве неиспользованными. Если OTA снова включить, клиент их восстановит.
4. Ограничение: билд, сохранённый на версии игры, которая пришла **только** через OTA и не встроена в APK, в OTA OFF версии не откроется. Он остаётся сохранённым, запись не изменяется. Сейчас активный production-релиз (`1.60.1.70450-r1`) имеет ту же версию игры, что и встроенные данные (`1.60.1.70450`), поэтому таких билдов пока нет. Перед откатом после публикации новой версии игры встройте её в APK отката.
5. Каталог и blob-ы на CDN при откате трогать не нужно: OTA OFF версия к ним не обращается.

Для отката **на сервере** (без новой версии приложения) используйте `rollback` из [production-deployment](production-deployment.md). Клиент не активирует релиз ниже встроенного floor (`KEEP_BUNDLED_FLOOR`).
