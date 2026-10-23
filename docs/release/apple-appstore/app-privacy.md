# App Privacy (App Store Connect): черновик ответов (M5.IOS.1, обновлено в M5.IOS.2)

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
| Что уходит в запросе | — | только `GET` на пути контракта (каталог, файлы по SHA-256). Технически: IP, время, URL, параметры TLS. Заголовки, **измерено** на симуляторе (M5.IOS.2): `Host`, `User-Agent: TalentForge/<версия>`, `Accept: */*`, `Accept-Encoding: gzip, deflate, br`, `Accept-Language: *`, `Connection`. Без cookies (ephemeral session, `HTTPCookieStorage = nil`), без идентификаторов, без содержимого билдов. `Accept-Language` фиксирован в M5.IOS.2: по умолчанию `NSURLSession` отправлял языки устройства (`en-US,en;q=0.9`) | `PlatformBlobFetcher`; [отчёт приложения](evidence/m5-ios-2/app-e2e-local-xcode-26.4.1/ios-ota-app-report.json) |
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

Статус: **BLOCKED** — нужен факт от владельца (OA-1: ведутся ли и сколько хранятся журналы запросов у CDN `ota.mythweld.com` (MegaFon CDN, CNAME `cl-541e19d9.mf.cdnmgf.megafon.ru`) и у бакета VK Cloud). В репозитории этого факта нет, агент его не проверял. Пока его нет, **«Data Not Collected» для OTA ON не заявлять**.

По правилам Apple «collect» — передача данных с устройства так, что разработчик или его провайдеры имеют к ним доступ дольше, чем нужно для обслуживания запроса в реальном времени. Приложение само ничего не собирает (нет аналитики, идентификаторов, cookies, содержимого билдов). Вопрос только в журналах доставки: IP, время, URL, `User-Agent`.

### 3.1 Вариант A: журналы не хранятся

Применим, только если владелец подтверждает: провайдеры не хранят журналы запросов дольше обслуживания запроса, или ни владелец, ни провайдер не имеют к ним доступа после обслуживания.

| Вопрос App Store Connect | Ответ |
|---|---|
| Do you or your third-party partners collect data from this app? | **No** |
| Итоговая карточка | **Data Not Collected** |
| `PrivacyInfo.xcprivacy` → `NSPrivacyCollectedDataTypes` | пустой массив (как сейчас); проверка архива: `--privacy-collected none` (по умолчанию) |

### 3.2 Вариант B: журналы хранятся

Рекомендуется, если журналы хранятся или факт неизвестен на момент отправки.

| Вопрос App Store Connect | Ответ |
|---|---|
| Data types | **Diagnostics → Other Diagnostic Data** |
| Linked to the user's identity | **No** |
| Used for tracking | **No** |
| Purposes | **App Functionality** |
| Итоговая карточка | Data Not Linked to You: Diagnostics |

Apple не выделяет IP-адрес в отдельный тип; журналы доставки ближе всего к диагностике. Разработчик их не связывает с личностью и не использует для отслеживания.

Изменение `mobile/iosApp/iosApp/PrivacyInfo.xcprivacy` для этого варианта (делается отдельным маленьким PR после решения владельца, вместе со сборкой OTA ON):

```xml
<key>NSPrivacyCollectedDataTypes</key>
<array>
	<dict>
		<key>NSPrivacyCollectedDataType</key>
		<string>NSPrivacyCollectedDataTypeOtherDiagnosticData</string>
		<key>NSPrivacyCollectedDataTypeLinked</key>
		<false/>
		<key>NSPrivacyCollectedDataTypeTracking</key>
		<false/>
		<key>NSPrivacyCollectedDataTypePurposes</key>
		<array>
			<string>NSPrivacyCollectedDataTypePurposeAppFunctionality</string>
		</array>
	</dict>
</array>
```

Проверка архива: `--ota on --privacy-collected other-diagnostic`. Проверка требует ровно эту запись, поэтому манифест и выбранный ответ не могут разойтись незаметно. Сборка OTA OFF с такой записью проверку не пройдёт: она не выполняет запросов (`--privacy-collected` для `--ota off` допускает только `none`).

### 3.3 Required Reason API и прочее (одинаково для A и B)

- `PrivacyInfo.xcprivacy` не меняется для Required Reason API. OTA ON не добавляет новых API: `DiskSpace E174.1` (проверка места перед записью) уже объявлен. Проверка `privacy-required-reasons` сравнивает объявления с импортами бинарника — PASS в архивах OTA ON и OFF ([readiness §6](m5-ios-2-ota-readiness.md#6-app-store-privacy-и-app-review)).
- Tracking: `NSPrivacyTracking = false`, доменов отслеживания нет — для обоих режимов.
- Политика: текст iOS-редакции уже описывает OTA ON (раздел 2) и верен с фиксированным `Accept-Language`. Публикация — у владельца ([docs/legal/ios](../../legal/ios/README.md)).

## 4. Связанные декларации

- **Export compliance:** `ITSAppUsesNonExemptEncryption = false` в `Info.plist`. Приложение использует только шифрование ОС (HTTPS через `NSURLSession`) и проверку подписи (аутентификация данных). Владелец подтверждает этот ответ в App Store Connect при первой загрузке; при сомнении — ответ по [Complying with Encryption Export Regulations](https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations).
- **Privacy Policy URL:** обязателен; страница iOS-редакции **не опубликована** → BLOCKED ([docs/legal/ios](../../legal/ios/README.md)).
- **Privacy manifest:** [readiness §6.1](readiness.md#61-privacyinfoxcprivacy-и-required-reason-api).
