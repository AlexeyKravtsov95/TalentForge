# App Privacy (App Store Connect): согласованная декларация

| | |
|---|---|
| Статус | **СОГЛАСОВАНО для первой отправки** (2026-10-26) по консервативной модели владельца (§1). В App Store Connect агент ничего не вносил: ответы вводит владелец по §5 |
| Сборка | TalentForge 1.0.0 (3), **OTA ON** (production `https://ota.mythweld.com`) |
| Итог | **Data Linked to You: Diagnostics** (Other Diagnostic Data; App Functionality). **Data Used to Track You** — нет |
| Правила Apple (прочитаны 2026-10-26) | [App privacy details on the App Store](https://developer.apple.com/app-store/app-privacy-details/) · [Manage app privacy](https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy/) · [Describing data use in privacy manifests](https://developer.apple.com/documentation/bundleresources/describing-data-use-in-privacy-manifests) |
| Согласованы с этим документом | `mobile/iosApp/iosApp/PrivacyInfo.xcprivacy` · `scripts/verify_ios_archive.py` (`DECLARED_COLLECTED_DATA`) · iOS-политика RU/EN, раздел 2 ([docs/legal/ios](../../legal/ios/README.md)) · iOS-абзац «Данные талантов» в About (`about_privacy_updates_ios`) · [runbook](submission/testflight-runbook.md) · [пакет отправки](submission/README.md) |
| Юридический статус | не юридическая консультация; OA-5 (IP-адрес как персональные данные) остаётся OPEN |

## 1. Модель и факты

**Допущение владельца (2026-10-26):** инфраструктура OTA (CDN МегаФон перед бакетом VK Cloud Object Storage) **может
сохранять технические журналы запросов после ответа**. Это допущение владельца, а не подтверждение провайдера;
ответа поддержки не ждём. Срок хранения, состав полей и настройки журналов провайдера не известны и нигде в
декларации, политике и приложении не утверждаются. OA-1…OA-4 ([OWNER_ACTION_REQUIRED](../../legal/ota-on/OWNER_ACTION_REQUIRED.md))
остаются без ответа; декларация от них больше не зависит (когда её пересматривать — §6).

Факты приложения (с M5.IOS.2 сетевой код не менялся; этот PR поведение OTA не меняет):

| Поток | Факт | Доказательство |
|---|---|---|
| Сетевые запросы | только `GET https://ota.mythweld.com/v1/channels/production/catalog.json` и `/v1/blobs/sha256/<hex>`; только HTTPS, ATS по умолчанию, редиректы не выполняются | `PlatformBlobFetcher`, `verify_ios_archive.py` (`ota-on`, `ats-default`) |
| Когда | после запуска; время от времени при возвращении в приложение; «Проверить обновления»; открытие билда, данных для версии игры которого нет на устройстве | `App.kt` (`onAppStarted`, `ON_START` → `onForeground`, `checkNow`, `resolveDataset`) |
| Заголовки | `Host`, `User-Agent: TalentForge/<версия>`, `Accept: */*`, `Accept-Encoding`, `Accept-Language: *`, `Connection`; без cookies (ephemeral-сессия), без идентификаторов | измерено в M5.IOS.2 ([отчёт](evidence/m5-ios-2/app-e2e-local-xcode-26.4.1/ios-ota-app-report.json)); `IosOtaPorts.kt` |
| Что получает инфраструктура | IP-адрес, время, URL (может указывать нужную версию игровых данных), `User-Agent`, параметры TLS | транспорт HTTPS; цепочка — `scripts/ota_production_preflight.py` |
| Что **не** уходит с устройства | билды, их названия и распределения, буфер обмена, рекламные идентификаторы, идентификаторы устройства или учётной записи | `IosBuildLibraryStorage` (`NSUserDefaults`), `OtaUpdateCoordinator`, `PlatformBlobFetcher` |
| Буфер обмена / «Поделиться» | только по нажатию; получатель — система и выбранное пользователем приложение, не разработчик | `IosBuildCodeSharing` |
| Аналитика, реклама, сторонние SDK | нет | `otool -L`: только системные фреймворки; `NSPrivacyTracking = false` |

## 2. Декларация

| Вопрос App Store Connect | Ответ |
|---|---|
| Do you or your third-party partners collect data from this app? | **Yes, we collect data from this app** |
| Data types | **Diagnostics → Other Diagnostic Data** — единственный тип |
| Purposes (Other Diagnostic Data) | **App Functionality** — единственная цель |
| Linked to the user's identity | **Yes** |
| Used for tracking | **No** |
| Карточка (Product Page Preview) | «Data Linked to You: Diagnostics»; раздела «Data Used to Track You» нет |

`PrivacyInfo.xcprivacy` (`NSPrivacyCollectedDataTypes`) содержит ровно ту же запись — §4.

## 3. Обоснование по документации Apple

Цитаты — из [App privacy details](https://developer.apple.com/app-store/app-privacy-details/), прочитано 2026-10-26.

### 3.1 Есть ли «сбор»

Apple: «"Collect" refers to transmitting data off the device in a way that allows you and/or your third-party
partners to access it for a period longer than what is necessary to service the transmitted request in real time».
Там же: IP-адрес, который отправлен с запросом к серверу и **не сохраняется**, раскрывать не нужно; если IP-адрес
сохраняется — «Declare the relevant data types based on how you use IP address, such as precise location, coarse
location, device ID, or diagnostics».

Вывод: по модели §1 журналы с IP-адресом могут храниться дольше обслуживания запроса. Бакет и CDN-ресурс — сервис
доставки самого разработчика (арендованный им), поэтому журналы этой инфраструктуры рассматриваются как доступные
разработчику. Более узкое прочтение («журналы есть только у провайдера, а он не "third-party partner" по определению
Apple — код в приложении») не используется: оно требует подтверждённых фактов о доступе, которых нет.
**Ответ: «Yes, we collect data from this app».**

### 3.2 Необязательное раскрытие не применяется

Apple разрешает не раскрывать данные, только если выполнены **все** условия, в том числе «Collection of the data
occurs only in infrequent cases that are not part of your app's primary functionality, and which are optional for
the user» и данные вводит сам пользователь в форме. Запросы OTA идут при каждом запуске и возвращении в приложение,
это часть основной функции, и пользователь ничего не вводит. **Раскрывать обязательно.**

### 3.3 Тип данных: Other Diagnostic Data

Apple классифицирует сохранённый IP-адрес **по использованию** (цитата в §3.1). Журналы запросов — технические записи
об обслуживании запросов доставки; разработчик использует их (если имеет доступ) только для работы и защиты доставки.
Определение Apple: Other Diagnostic Data — «Any other data collected for the purposes of measuring technical
diagnostics related to the app».

| Кандидат (определение Apple) | Решение | Почему |
|---|---|---|
| **Diagnostics → Other Diagnostic Data** | **выбран** | технические журналы запросов; «diagnostics» прямо назван Apple для сохранённого IP |
| Diagnostics → Crash Data («Such as crash logs»), Performance Data («Such as launch time, hang rate, or energy use») | нет | отчёты о сбоях и метрики производительности не собираются |
| Location → Precise / Coarse Location | нет | IP-адрес не используется для определения местоположения; разрешение на геолокацию не запрашивается |
| Identifiers → Device ID («the device's advertising identifier, or other device-level ID»), User ID | нет | идентификаторы не передаются; учётных записей нет; IP не используется как идентификатор устройства |
| Usage Data → Product Interaction («app launches, taps…»), Other Usage Data | нет | время запроса каталога совпадает с запуском или возвращением, а URL может указывать версию данных, но журналы не используются, чтобы узнать, как человек пользуется приложением; Apple классифицирует IP по использованию |
| Other Data → Other Data Types («Any other data types not mentioned») | нет | тип подходит, только если ни один из перечисленных не подходит; Diagnostics подходит |

Неопределённость: Apple не называет журналы доступа сервера отдельным примером. Выбор Other Diagnostic Data —
суждение по приведённым определениям и указанию Apple про IP-адрес, а не подтверждение Apple. Условия, при которых
тип нужно добавить или заменить, — §6.

### 3.4 Связано с пользователем: Yes

Apple: «Data collected from an app is often linked to the user's identity, unless specific privacy protections are
put in place before collection to de-identify or anonymize it, such as: Stripping data of any direct identifiers…
Manipulating data to break the linkage…» и «"Personal Information" and "Personal Data", as defined under relevant
privacy laws, are considered linked to the user».

- Защиту **до** сбора приложение обеспечить не может: исходный IP-адрес попадает в журналы инфраструктуры; удаление
  или обезличивание IP до записи журнала не подтверждено.
- IP-адрес может быть персональными данными по применимому праву (OA-5 открыт; консервативная модель берёт худший
  случай).

Отсутствие учётных записей и то, что разработчик сам не связывает журналы с личностью, условия Apple не снимает: оно
требует защиты до сбора. «Not linked» обоснованно заявить нельзя. **Ответ: Yes.**

### 3.5 Отслеживание: No

Apple: «"Tracking" refers to linking data collected from your app about a particular end-user or device… with
Third-Party Data for targeted advertising or advertising measurement purposes, or sharing data collected from your
app… with a data broker».

Журналы не объединяются с данными третьих сторон для рекламы или измерения рекламы и не передаются брокерам данных;
в приложении нет рекламы и рекламных SDK. `NSPrivacyTracking = false`, `NSPrivacyTrackingDomains` пуст; App Tracking
Transparency не требуется. **Ответ: No.**

### 3.6 Цель: только App Functionality

Apple: App Functionality — «Such as to authenticate the user, enable features, prevent fraud, implement security
measures, ensure server up-time, minimize app crashes, improve scalability and performance, or perform customer
support».

| Цель Apple | Решение | Почему |
|---|---|---|
| **App Functionality** | **да** | доставка обновлений данных, работа и защита сервиса |
| Analytics («evaluate user behavior… measure audience size or characteristics») | нет | разработчик не анализирует журналы: не считает аудиторию, запуски или версии |
| Product Personalization | нет | каталог одинаков для всех; загрузка файлов по требованию — функция (данные для открытого билда), а не подбор содержимого |
| Third-Party Advertising, Developer's Advertising or Marketing | нет | рекламы и маркетинга нет |
| Other Purposes | нет | других целей нет |

Это обязательство владельца, и оно повторено в iOS-политике и в About: журналы используются только для доставки,
работы и защиты сервиса, никогда — для рекламы, аналитики или отслеживания. Обработку провайдером по его правилам
политика описывает отдельно; если владелец узнает, что журналы используются для другой цели, декларацию пересматривают (§6).

## 4. PrivacyInfo.xcprivacy и проверка архива

```xml
<key>NSPrivacyCollectedDataTypes</key>
<array>
	<dict>
		<key>NSPrivacyCollectedDataType</key>
		<string>NSPrivacyCollectedDataTypeOtherDiagnosticData</string>
		<key>NSPrivacyCollectedDataTypeLinked</key>
		<true/>
		<key>NSPrivacyCollectedDataTypeTracking</key>
		<false/>
		<key>NSPrivacyCollectedDataTypePurposes</key>
		<array>
			<string>NSPrivacyCollectedDataTypePurposeAppFunctionality</string>
		</array>
	</dict>
</array>
```

Константы — из справочника Apple [NSPrivacyCollectedDataType](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacycollecteddatatypes/nsprivacycollecteddatatype)
и [NSPrivacyCollectedDataTypePurposes](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacycollecteddatatypes/nsprivacycollecteddatatypepurposes)
(проверены 2026-10-26). Apple: Xcode строит privacy report из манифестов, и к нему обращаются при заполнении App
Privacy; собственные значения типов и целей ломают этот отчёт.

- `scripts/verify_ios_archive.py` требует в собранном архиве **ровно** эту запись (`privacy-collected-data`,
  константа `DECLARED_COLLECTED_DATA`). Пустой массив, `Linked = false`, `Tracking = true`, другой тип, другая или
  лишняя цель, лишний тип — FAIL. Выбора в командной строке нет (флаг `--privacy-collected` удалён), поэтому архив,
  манифест и ответы в App Store Connect не могут разойтись незаметно. Unit-тест сверяет закоммиченный манифест с той
  же константой.
- Манифест — один закоммиченный файл, он попадает в архивы обоих режимов. Поэтому та же запись требуется и для
  `--ota off`: архив OTA OFF (CI) объявляет больше, чем собирает, но в App Store отправляется только OTA ON. Если
  когда-нибудь отправлять OTA OFF, декларацию нужно пересмотреть до загрузки.
- Required Reason API без изменений: `CA92.1`, `C617.1`, `E174.1`; проверка `privacy-required-reasons` по импортам
  бинарника. `NSPrivacyTracking = false`, доменов нет.

## 5. Заполнение App Store Connect (владелец 🔑)

Порядок — по [Manage app privacy](https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy/).
Справка Apple не приводит дословно вопросы по каждому типу данных; ниже они даны по смыслу. Если экран App Store
Connect спрашивает что-то, чего здесь нет, — остановитесь и ничего не публикуйте.

1. До этого шага iOS-политика новой редакции опубликована, и `python3 scripts/ios_privacy_pages.py verify-live --date <дата публикации>`
   → `RESULT: PASS` ([docs/legal/ios §3](../../legal/ios/README.md#3-публикация-выполняет-только-владелец)).
2. Apps → TalentForge → **App Privacy** → **Get Started** (или Edit, если ответы уже начаты).
3. «Do you or your third-party partners collect data from this app?» → **Yes, we collect data from this app** → Next.
4. Отметьте **только** **Diagnostics → Other Diagnostic Data** → Save. Ничего больше не отмечайте (Location,
   Identifiers, Usage Data, Crash Data, Performance Data — нет).
5. В Data Types нажмите **Other Diagnostic Data** и ответьте:
   - цели — **только App Functionality**;
   - связаны ли данные с личностью пользователя — **Yes**;
   - используются ли для отслеживания — **No**.

   Save.
6. **Product Page Preview**: «Data Linked to You» → Diagnostics; разделов «Data Used to Track You» и «Data Not
   Linked to You» нет.
7. Privacy Policy: EN — `https://legal.mythweld.com/privacy-ios-en.html`, RU — `https://legal.mythweld.com/privacy-ios.html`
   ([privacy_url.txt](submission/metadata/en-US/privacy_url.txt), [ru](submission/metadata/ru/privacy_url.txt)).
8. **Publish** → в диалоге «Publish Your App Privacy Responses?» вы подтверждаете точность ответов и обязуетесь
   обновлять их при изменении практики → Publish.

Проверка: архив, загруженный в TestFlight, прошёл `verify_ios_archive.py --ota on` с `privacy-collected-data` →
PASS ([runbook §4](submission/testflight-runbook.md#4-проверка-архива-verify_ios_archivepy---ota-on)), то есть
манифест сборки совпадает с ответами шагов 3–5.

## 6. Когда пересматривать

Изменение декларации — отдельный PR: манифест, `DECLARED_COLLECTED_DATA`, iOS-политика, About, этот документ, затем
ответы в App Store Connect (Publish).

| Событие | Что меняется |
|---|---|
| Провайдер письменно подтверждает: журналы с IP не хранятся дольше обслуживания запроса или ни разработчик, ни провайдер не имеют к ним доступа | возможен «Data Not Collected» (§3.1) |
| Подтверждено обезличивание IP **до** записи журналов, и IP по применимому праву не персональные данные (OA-5) | можно пересмотреть «Linked» (§3.4) |
| Журналы используются для подсчёта аудитории, запусков, версий | + Analytics; вероятно, Product Interaction |
| Из IP определяется местоположение для разработчика | + Coarse Location |
| Новый сетевой адрес, SDK, учётные записи, отчёты о сбоях | полный пересмотр |
| Google Play, RuStore, AppGallery | та же инфраструктура; их декларации (OA-7) в этом PR **не** менялись — согласовать отдельно |

## 7. Связанные декларации

- **Export compliance:** `ITSAppUsesNonExemptEncryption = false` в `Info.plist`. Приложение использует только
  шифрование ОС (HTTPS через `NSURLSession`) и проверку подписи (аутентификация данных). При сомнении — ответ по
  [Complying with Encryption Export Regulations](https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations).
- **Privacy Policy URL:** обязателен. Опубликованная 2026-10-24 редакция **не** описывает хранение журналов: перед
  отправкой владелец публикует новую редакцию с новой датой ([docs/legal/ios §3](../../legal/ios/README.md#3-публикация-выполняет-только-владелец)).
- **Privacy manifest и Required Reason API:** §4 и [readiness §6.1](readiness.md#61-privacyinfoxcprivacy-и-required-reason-api).

## 8. История: варианты A и B (заменены)

- M5.IOS.1: сборка OTA OFF не выполняла сетевых запросов → «Data Not Collected». В App Store она не отправляется.
- M5.IOS.2–M5.IOS.3: для OTA ON выбор оставался за владельцем: A — «Data Not Collected» (только при подтверждённом
  отсутствии хранения журналов), B — Other Diagnostic Data, **Linked: No**. Вариант B назначал «Not linked» без
  проверки по критериям Apple; с учётом §3.4 это не обосновано. Оба варианта заменены декларацией §2.
- Выводы аудита M5.IOS.3 о сетевом коде, заголовках и цепочке доставки остаются в силе (§1); историческая запись —
  [readiness M5.IOS.3](m5-ios-3-readiness.md).
