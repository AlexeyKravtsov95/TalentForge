# App Privacy (App Store Connect): черновик ответов (M5.IOS.1, обновлено в M5.IOS.2 и M5.IOS.3)

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

## 5. Аудит M5.IOS.3 (OTA ON)

Дата: 2026-10-24, `main` @ `d51cbed`. Ответ в App Store Connect по-прежнему **не выбран** — его даёт владелец.

### 5.1 Что проверено

| Пункт | Факт | Как проверено | Статус |
|---|---|---|---|
| Код сети с M5.IOS.2 | не менялся: `git diff 2b807a1 d51cbed -- mobile/` пуст (после M5.IOS.2 менялись только Python-инструменты OTA, документы и данные вне мобильного bundle) | git | **PASS** |
| Сетевые запросы | только `GET https://ota.mythweld.com/v1/channels/production/catalog.json` и `/v1/blobs/sha256/<hex>`; редиректы не выполняются, только HTTPS, ATS по умолчанию | M5.IOS.2 (код + E2E на симуляторе); код с тех пор тот же | **PASS** |
| Заголовки | `Host`, `User-Agent: TalentForge/1.0.0`, `Accept: */*`, `Accept-Encoding`, `Accept-Language: *` (фиксирован в `IosOtaPorts.kt`), `Connection`; без cookies (ephemeral-сессия) и идентификаторов | измерено в M5.IOS.2 (`app-request-headers`); `IosOtaPorts.kt` прочитан заново | **PASS** |
| Что получает инфраструктура | IP-адрес, время, URL (может указывать нужную версию игровых данных), `User-Agent`, параметры TLS. Цепочка: `ota.mythweld.com` → CNAME `cl-541e19d9.mf.cdnmgf.megafon.ru` (CDN МегаФон) → бакет VK Cloud Object Storage | `scripts/ota_production_preflight.py` (только чтение, 2026-10-24T08:32:27Z, `result: OK`) | **PASS** (факт доставки) |
| Хранение журналов CDN и бакета (OA-1…OA-4) | **неизвестно**: в репозитории сведений нет; у агента нет доступа к консолям провайдеров | — | **BLOCKED** (владелец) |
| `PrivacyInfo.xcprivacy` | `NSPrivacyTracking = false`, доменов нет, `NSPrivacyCollectedDataTypes = []`; Required Reason API `CA92.1`, `C617.1`, `E174.1` совпадают с импортами бинарника | `verify_ios_archive.py --ota on` на unsigned-архиве OTA ON текущего `main` (локально, Xcode 26.4.1): 22/22 PASS ([evidence](evidence/m5-ios-3/)) | **PASS** для варианта A; для B манифест меняется отдельным PR (§3.2) |
| iOS-политика RU/EN | верно описывает OTA ON: моменты проверок (запуск, возврат в приложение, «Проверить обновления», открытие билда без нужных данных), состав запроса, IP у инфраструктуры без утверждений о сроках хранения, отсутствие идентификаторов и cookies, проверку подписи, исключение из бэкапа, отсутствие разрешений. Изменения не нужны | сверено с кодом: `App.kt` (`onAppStarted`, `ON_START` → `onForeground`, `checkNow`, `resolveDataset`), `AboutScreen.kt` (кнопка «Проверить обновления» только при OTA ON) | **PASS** (текст); публикация — **BLOCKED** |
| Согласованность с другими магазинами | Google Play Data safety, RuStore и AppGallery для OTA ON зависят от того же факта (OA-1…OA-5, [store-declarations](../../legal/ota-on/store-declarations.md)). При одной и той же инфраструктуре ответ «не собирается» в одном магазине и «собирается» в другом противоречат друг другу | документы | к сведению владельца |

### 5.2 Варианты декларации

| | A — Data Not Collected | B — Diagnostics (Other Diagnostic Data) |
|---|---|---|
| Условие | владелец **подтвердил**: ни CDN МегаФон, ни VK Cloud не хранят журналы запросов дольше обслуживания запроса, **или** ни владелец, ни провайдеры не имеют к ним доступа после обслуживания (определение «collect» Apple) | журналы хранятся, **или** факт на дату отправки не установлен |
| Ответ в ASC | «No, we do not collect data from this app» | Diagnostics → Other Diagnostic Data; Linked to the user: **No**; Tracking: **No**; Purpose: **App Functionality** |
| `PrivacyInfo.xcprivacy` | без изменений | `NSPrivacyCollectedDataTypeOtherDiagnosticData` (§3.2), отдельный PR **до** архива |
| Проверка архива | `--ota on` | `--ota on --privacy-collected other-diagnostic` |
| Риск | если журналы на самом деле хранятся, карточка расходится с фактом (Guideline 5.1.1/5.1.2); исправляется обновлением ответа и, при необходимости, новой версией | карточка показывает «Data Not Linked to You: Diagnostics»; связь с личностью и трекинг не заявляются, потому что их нет |

Apple не выделяет IP-адрес в отдельный тип данных; «Other Diagnostic Data» — ближайший тип для журналов доставки.
Условие варианта A — подтверждённый факт; без него условие не выполнено. Выбор остаётся за владельцем, агент его не
делает. Юридическая квалификация IP-адреса (OA-5, 152-ФЗ) остаётся открытой.

### 5.3 Что выяснить для выбора (OA-1…OA-4)

1. **CDN МегаФон** (ресурс `ota.mythweld.com`): в личном кабинете или у менеджера — ведутся ли журналы доступа (access
   logs), какие поля (IP, URL, User-Agent), срок хранения, кто имеет к ним доступ (только провайдер или и клиент).
2. **VK Cloud Object Storage** (бакет `talentforge-ota-production`): включено ли журналирование запросов к бакету и
   куда пишутся журналы; срок хранения.
3. Анализируются ли IP-адреса сверх доставки и защиты (OA-4).
4. Записать ответы с датой в [OWNER_ACTION_REQUIRED](../../legal/ota-on/OWNER_ACTION_REQUIRED.md) и выбрать вариант
   **одинаково** для App Store и Google Play.
