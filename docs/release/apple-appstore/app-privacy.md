# M5.IOS.1 — App Privacy (App Store Connect): черновик ответов

| | |
|---|---|
| Статус | **ЧЕРНОВИК.** В App Store Connect ничего не вносилось. Ответы даёт владелец |
| Основа | фактические сетевые потоки и хранилища сборки M5.IOS.1, а не предположения ([readiness §6](readiness.md#6-privacy-и-требования-apple-к-sdk)) |
| Источник правил Apple | [App privacy details](https://developer.apple.com/app-store/app-privacy-details/): «collect» — передача данных с устройства так, что разработчик или его партнёры (включая провайдеров) имеют к ним доступ дольше, чем нужно для обслуживания запроса в реальном времени |
| Юридический статус | не юридическая консультация; OA-5 (IP-адрес как персональные данные) остаётся OPEN |

## 1. Фактические потоки данных (проверено по коду и бинарнику)

| Поток | Сборка OTA OFF (эта) | Сборка OTA ON (M5.IOS.2) | Доказательство |
|---|---|---|---|
| Сетевые запросы приложения | **нет**: в бинарнике нет production-origin и ключей, `IosOta.runtime()` = `null` | HTTPS GET к `https://ota.mythweld.com` (каталог, данные талантов) | `verify_ios_archive.py` проверка `ota-off`; `GeneratedOtaSettings.iosProduction.enabled = false`; `IosOta`/`PlatformBlobFetcher` |
| Что уходит в запросе | — | IP (технически), время, URL файла, `User-Agent: TalentForge/<версия>`, параметры TLS. Без cookies (ephemeral session, `HTTPCookieStorage = nil`), без идентификаторов, без содержимого билдов | `PlatformBlobFetcher` |
| Билды | только `NSUserDefaults` на устройстве | то же; не передаются | `IosBuildLibraryStorage` |
| Буфер обмена / меню «Поделиться» | только по нажатию пользователя; передаётся системе, а не разработчику | то же | `IosBuildCodeSharing` |
| Аналитика, реклама, трекинг, SDK третьих сторон | нет | нет | `otool -L`: только системные фреймворки; нет сторонних SDK; `NSPrivacyTracking = false` |
| Журналы CDN/сервера | — | **неизвестно** (OA-1…OA-4): ведутся ли и сколько хранятся | [OWNER_ACTION_REQUIRED](../../legal/ota-on/OWNER_ACTION_REQUIRED.md) |

## 2. Сборка OTA OFF (M5.IOS.1, первая отправка без OTA)

| Вопрос App Store Connect | Ответ |
|---|---|
| Do you or your third-party partners collect data from this app? | **No, we do not collect data from this app** |
| Итоговая карточка | **Data Not Collected** |
| Tracking | No |

Обоснование: приложение не выполняет сетевых запросов; данные не покидают устройство, кроме действий пользователя через системные буфер обмена и меню «Поделиться», где получатель — выбранное пользователем приложение.

## 3. Сборка OTA ON (M5.IOS.2)

Ответ зависит от фактов, которых в репозитории нет (OA-1: журналы доступа CDN `ota.mythweld.com` и бакета). Два варианта:

| Факт о журналах | Ответ | Почему |
|---|---|---|
| Провайдер не хранит журналы запросов дольше обслуживания запроса (или разработчик подтверждает, что ни он, ни провайдер не имеют к ним доступа после обслуживания) | **Data Not Collected** | IP и User-Agent используются только для доставки ответа в реальном времени |
| Журналы доступа хранятся (у CDN или в бакете) | **Data Collected → Diagnostics / Other Diagnostic Data** (или **Other Data Types**), **Not Linked to You**, **Not Used for Tracking**, purpose: **App Functionality** | Apple не выделяет «IP-адрес» в отдельный тип; журналы доставки ближе всего к диагностике. Разработчик не связывает их с личностью и не использует для отслеживания |

Решение принимает владелец после ответа на OA-1. До решения OTA ON сборка не отправляется ([m5-ios-2-ota-plan.md](m5-ios-2-ota-plan.md), шаг 13). `NSPrivacyCollectedDataTypes` в `PrivacyInfo.xcprivacy` приводится к выбранному варианту в том же PR, что и включение OTA.

## 4. Связанные декларации

- **Export compliance:** `ITSAppUsesNonExemptEncryption = false` в `Info.plist`. Приложение использует только шифрование ОС (HTTPS через `NSURLSession`) и проверку подписи (аутентификация данных). Владелец подтверждает этот ответ в App Store Connect при первой загрузке; при сомнении — ответ по [Complying with Encryption Export Regulations](https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations).
- **Privacy Policy URL:** обязателен; страница iOS-редакции **не опубликована** → BLOCKED ([docs/legal/ios](../../legal/ios/README.md)).
- **Privacy manifest:** [readiness §6.1](readiness.md#61-privacyinfoxcprivacy-и-required-reason-api).
