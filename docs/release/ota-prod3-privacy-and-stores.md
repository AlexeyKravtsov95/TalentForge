# M5.OTA.PROD.3 — privacy и магазины для Android-сборки с OTA

| | |
|---|---|
| Статус | **Предложения для владельца и юриста.** Ничего не подано в консоли и не опубликовано. Публичная политика на `legal.mythweld.com` **не изменена**. Декларации магазинов **не подтверждены** владельцем |
| Когда применяется | только к Android-сборке, собранной с `talentforge.ota.android.production.enabled=true` ([android-production-enablement](../ota/android-production-enablement.md)) |
| Не юридическая консультация | LR-1…LR-5 остаются **OPEN** ([privacy-and-compliance §7.1](appgallery/privacy-and-compliance.md#71-status-of-lr-1--lr-5-none-is-closed-by-this-milestone)). Этот PR их статус не меняет |
| Источники правил | Google Play Data safety ([справка](https://support.google.com/googleplay/android-developer/answer/10787469), перечитано 2026-10-21) · правила RuStore и AppGallery, как в [store1 audit §6](store1-release-audit.md#6-store-compliance-official-sources-re-read-2026-10-19) (2026-10-19, этим PR не перечитывались) |

## 1. Две конфигурации

| | OTA OFF (по умолчанию, как 1.0.0) | OTA ON |
|---|---|---|
| `INTERNET` в release APK / Play AAB | нет (проверено, [evidence](ota-prod3-evidence/README.md)) | есть (проверено, там же) |
| Сетевые запросы | нет: без `INTERNET` Android не даёт открыть сокет | HTTPS GET к `https://ota.mythweld.com` (§2) |
| Тексты About в приложении | прежние: «works offline», «works without the internet» | новые (§3), выбираются автоматически |
| Политика, store listing, декларации | прежние тексты верны, их **не менять** | нужны изменения из §4–§8 **до** загрузки OTA ON сборки в магазин |

Правило: каждая сборка, загружаемая в магазин, должна сопровождаться текстами для **её** конфигурации. OTA ON сборку нельзя загружать с текстами «без интернета».

## 2. Что фактически делает OTA ON сборка (проверено по коду этой ветки)

| Факт | Источник |
|---|---|
| Запросы только двух видов: `GET https://ota.mythweld.com/v1/channels/production/catalog.json` и `GET https://ota.mythweld.com/v1/blobs/sha256/<hex>` (манифесты и файлы данных по их SHA-256). URL одинаковы для всех пользователей и не содержат данных пользователя | `OtaConfig.catalogUrl` / `blobUrl` |
| Origin зашит в сборку. Сервер не может перенаправить клиент: редиректы не выполняются, URL из ответов не используются, допускается только `https://` | `build.gradle.kts` (pinned origin), `PlatformBlobFetcher` |
| Когда: после первого кадра при запуске; при возвращении в приложение, если последняя успешная проверка старше 6 ч; по кнопке «Check for updates»; при открытии сохранённого или импортированного билда, данных которого нет на устройстве (загрузка по требованию). После ошибок автоматические проверки откладываются: 1 ч, 2 ч … 24 ч | `OtaUpdateCoordinator` (`CHECK_INTERVAL_SECONDS = 6 h`), `HistoricalDatasetResolver` |
| Заголовки: фиксированный `User-Agent: TalentForge/<versionName>`. Нет cookies, HTTP-кэша, учётной записи, рекламного или иного идентификатора устройства | `JvmOtaPorts.kt` (`PlatformBlobFetcher`), `OtaRuntime.android.kt` |
| Что получает сервер/CDN: IP-адрес устройства, время запроса, запрошенный URL, `User-Agent`, TLS-метаданные. Хранит ли CDN журналы и как долго, зависит от настроек VK Cloud у владельца (**не проверено агентом**, owner-only) | транспорт HTTPS |
| Что **не** отправляется: билды, их названия, содержимое буфера обмена, сведения об устройстве сверх перечисленного. Список версий игры из библиотеки билдов читается **локально**, чтобы не удалить нужные данные (retention) | `OtaUpdateCoordinator.libraryBuilds`, `RetentionPolicy` |
| Проверка целостности: подпись каталога ECDSA P-256 закреплёнными ключами `tf-prod-2026a` / `tf-prod-2026b`, SHA-256 каждого файла. При любой ошибке ничего не меняется, приложение остаётся на прежних данных | `CatalogVerifier`, `ReleaseVerifier`, `DatasetDownloadManager` |
| Хранение загруженного: `noBackupFilesDir/ota` (закрытое хранилище, не попадает в резервные копии) | `OtaRuntime.android.kt` |
| Аналитики, рекламы, crash reporting, Firebase и новых зависимостей **нет**: этот PR не добавляет ни одной зависимости | `gradle/libs.versions.toml` не изменён |
| В приложении **нет переключателя**, отключающего автоматические проверки (OD-4 не реализован). Пользователь может только отключить сеть для устройства или приложения средствами системы | [open-decisions OD-4](../ota/open-decisions.md) |

## 3. Тексты About в приложении (реализовано в этом PR)

Показываются **только** в OTA-сборке (когда у About есть статус OTA и «Check for updates»). OTA OFF сборка показывает прежние тексты без изменений (проверено `AboutScreenUiTest`).

| Ключ | EN | RU |
|---|---|---|
| `about_privacy_online` (вместо `about_privacy_offline`) | TalentForge works without an account, ads or analytics. Your builds stay on this device; only talent data updates are downloaded from the internet. | TalentForge работает без учётной записи, без рекламы и без аналитики. Ваши билды остаются на этом устройстве; из интернета загружаются только обновления данных талантов. |
| `about_privacy_updates` (новый, в «Подробнее») | описывает: HTTPS, `ota.mythweld.com`, моменты загрузки, проверку подписи, обработку IP-адреса и времени запроса сервером/CDN, отсутствие идентификаторов, работу без сети | то же |
| `about_privacy_permissions_online` (вместо `about_privacy_permissions`) | The app uses the internet only to download talent data updates and does not ask for access to your location, contacts, camera, microphone, files or notifications. | Приложение использует интернет только для загрузки обновлений данных талантов и не запрашивает доступ к местоположению, контактам, камере, микрофону, файлам или уведомлениям. |

Полный текст: `mobile/composeApp/src/commonMain/composeResources/values{,-ru}/strings.xml`. Тексты, как и остальная сводка, ждут проверки владельцем и юристом (LR-5).

## 4. Политика конфиденциальности: редакция для OTA ON (черновик, не опубликован)

Основа: черновик [privacy-and-compliance §8](appgallery/privacy-and-compliance.md#8-draft-privacy-policy). Для OTA ON сборки замените пункты 1 и 6 и вставьте новый пункт 7 (последующие пункты сдвигаются на один). Поля `⟨…⟩` заполняет владелец. Публикует политику только владелец (P-6).

### 4.1 Русская версия

> **1. Коротко.** TalentForge работает без учётной записи, без рекламы и без аналитики. Созданные вами билды хранятся только на вашем устройстве; мы их не получаем. Приложение подключается к интернету только для загрузки обновлений данных талантов (п. 7).
>
> **6. Разрешения.** Приложение запрашивает стандартное разрешение Android на доступ к сети (INTERNET) только для загрузки обновлений данных талантов. Оно не запрашивает доступ к местоположению, контактам, камере, микрофону, файлам или уведомлениям.
>
> **7. Обновления данных талантов.** Чтобы данные талантов оставались актуальными, приложение по защищённому соединению (HTTPS) загружает с сервера `ota.mythweld.com` общедоступный список обновлений и файлы игровых данных: после запуска, время от времени при возвращении в приложение, по кнопке «Проверить обновления» и при открытии билда, данных которого ещё нет на устройстве. Запросы одинаковы для всех пользователей и содержат только версию приложения. В них нет учётной записи, идентификаторов устройства или рекламы, а также сведений о ваших билдах. При обработке запросов сервер и сеть доставки контента ⟨наименование провайдера, например VK Cloud — подтвердить⟩ получают IP-адрес вашего устройства и время запроса; эти сведения используются только для доставки файлов и защиты сервера ⟨и хранятся в журналах не дольше … — заполнить по настройкам хостинга⟩. Мы не используем их, чтобы определять ваше местоположение или личность. Загруженные файлы проверяются на устройстве по цифровой подписи и хранятся в закрытом хранилище приложения. Без подключения к интернету приложение работает на уже имеющихся данных. ⟨Если будет реализован переключатель автоматических обновлений (OD-4), описать его здесь.⟩

### 4.2 English version

> **1. In short.** TalentForge works without an account, ads or analytics. The builds you create are stored only on your device; we do not receive them. The app connects to the internet only to download talent data updates (section 7).
>
> **6. Permissions.** The app requests Android's standard network-access permission (INTERNET) only to download talent data updates. It does not request access to your location, contacts, camera, microphone, files or notifications.
>
> **7. Talent data updates.** To keep talent data current, the app downloads a public list of updates and game-data files over a secure connection (HTTPS) from `ota.mythweld.com`: after it starts, from time to time when you return to it, when you tap "Check for updates", and when you open a build whose data is not on the device yet. The requests are the same for every user and contain only the app version. They contain no account, device or advertising identifier and nothing about your builds. To answer them, the server and its content delivery network ⟨provider name, e.g. VK Cloud — confirm⟩ receive your device's IP address and the time of the request; this is used only to deliver the files and protect the server ⟨and kept in logs for no longer than … — fill in from the hosting settings⟩. We do not use it to determine your location or identity. Downloaded files are verified on the device against a digital signature and kept in the app's private storage. Without an internet connection the app keeps working with the data it already has. ⟨If an automatic-update switch is implemented (OD-4), describe it here.⟩

Пункты 2–5 и 8–11 черновика остаются верными для OTA ON сборки (проверено по §2). Пункт 2 («приложение никуда их не отправляет» о билдах) остаётся верным.

## 5. Google Play: Data safety (OTA ON)

Что говорит справка Google (2026-10-21):

- IP-адрес **не является отдельным типом данных**. Его указывают по назначению: например, как *Approximate location*, если по IP определяется местоположение;
- передача данных поставщику услуг (service provider), который обрабатывает их от имени разработчика, **не** считается sharing;
- справка противоречива в отношении данных, обрабатываемых «эфемерно». Раздел *Data collection* требует включать их в форму, FAQ — нет.

| Вопрос | Предложение | Условие / кто решает |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No** (предварительно) | Верно, пока владелец **не** определяет местоположение по IP и не использует журналы CDN ни для чего, кроме доставки и защиты. Если журналы с IP хранятся и анализируются, юрист решает, нужен ли тип (например, *Device or other IDs* или *Approximate location*). **Owner/legal** 🔑 |
| Is all user data encrypted in transit? | (появится, если выше «Yes») **Yes**: только HTTPS | факт §2 |
| Data deletion | (если выше «Yes») данные билдов не покидают устройство. Журналы CDN по сроку хранения хостинга | owner |
| App access (комментарий для проверки) | «No account or sign-in. All features work without network access; the network is used only to download talent data updates.» | заменить текст из [store-listing-en](google-play/store-listing-en.md) (строка «All features work without network access») |
| Privacy policy URL | та же страница, но с редакцией §4 до загрузки OTA ON bundle | owner (P-6) |

Если владелец оставит «No data collected», ярлык в магазине не изменится. Форму всё равно нужно **пересмотреть и сохранить заново** при выпуске OTA ON версии.

## 6. RuStore (OTA ON)

| Пункт | Предложение | Кто |
|---|---|---|
| Блок Data Safety | указать, что приложение передаёт технические данные подключения (IP-адрес) на сервер разработчика для загрузки обновлений данных; персональные данные пользователя (билды, названия) не передаются. Выбрать категории так, как их предлагает консоль 🔑 ([store1 §5.6](store1-release-audit.md#56-store-declarations-proposed-answers-all-owner-confirmed-in-each-console), вариант C) | owner 🔑 |
| Обоснование `INTERNET` (правило 5.3) | «Загрузка обновлений игровых данных (данные талантов) с сервера разработчика по HTTPS. Без сети приложение работает на встроенных данных.» | owner |
| Политика на русском (правило 5.1) | редакция §4.1, опубликованная по тому же адресу, доступная из карточки и из приложения (ссылка в About уже есть) | owner (P-6) |
| Комментарий модератору | заменить «Все функции доступны без сети» на: «Учётная запись и вход не нужны. Все функции работают без сети; интернет используется только для загрузки обновлений данных талантов.» | owner |

## 7. Huawei AppGallery (OTA ON)

| Пункт | Предложение | Кто |
|---|---|---|
| Privacy tags: «Collect personal data» | **решение owner/legal**: считается ли обработка IP-адреса хостингом сбором по определениям AGC ([store1 §5.6](store1-release-audit.md#56-store-declarations-proposed-answers-all-owner-confirmed-in-each-console)). Агент ответ не выбирает | owner/legal 🔑 |
| Privacy policy URL | обязательно, с редакцией §4 | owner (P-6) |
| Permission descriptions | `INTERNET` — normal permission, описание не требуется. Если консоль спросит: текст из §6 | owner |
| Reviewer notes | как в §6 (EN-вариант из §5) | owner |

## 8. Тексты карточек (RuStore, AppGallery, Google Play): замены для OTA ON

Длины проверены (`len()` в Python): RuStore и Google Play ≤ 80 символов для краткого описания.

| Где | Сейчас | OTA ON |
|---|---|---|
| Краткое описание EN (RuStore, AppGallery, Play) | Offline talent build planner: plan, save and share builds as codes. | Talent build planner: plan, save and share builds as codes. Works offline. (74) |
| Краткое описание RU | Планировщик билдов талантов без интернета: создавайте, храните, делитесь | Планировщик билдов талантов: работает и без сети, хранит и делится (66) |
| AppGallery brief introduction (≤ 25) | Offline talent planner | Talent build planner (20) |
| Полное описание, 1-я фраза EN / RU | …an offline planner… / …который работает без интернета… | …a talent build planner that works offline… / …который работает и без сети… |
| Блок приватности EN | PRIVATE AND OFFLINE · «The app does not request internet access. Your builds stay on your device.» | PRIVATE · «Your builds stay on your device. The app uses the internet only to download talent data updates.» |
| Блок приватности RU | ПРИВАТНО И БЕЗ ИНТЕРНЕТА · «Приложение не запрашивает доступ к интернету…» | ПРИВАТНО · «Ваши билды остаются на вашем устройстве. Интернет используется только для загрузки обновлений данных талантов.» |
| Данные талантов EN | New talent data arrives with app updates. | New talent data is downloaded in the app when it is published; without a connection the app uses the data it has. |
| Данные талантов RU | Новые данные приходят с обновлениями приложения. | Новые данные талантов загружаются в приложении после публикации; без сети приложение использует уже имеющиеся. |
| «fully offline» / «без интернета» в списке возможностей | Plan talent builds for nine classes, fully offline. | Plan talent builds for nine classes, even offline. / …для девяти классов, в том числе без сети. |
| Комментарий модератору / App access | «All features work without network access» | §5 / §6 |

## 9. Действия владельца (owner-only)

| # | Действие | Блокирует |
|---|---|---|
| O-1 | Решить, выпускать ли первую публичную версию с OTA ON (NET-1 → вариант C) или оставить OTA OFF | всё ниже |
| O-2 | Проверить настройки журналов CDN/хранилища VK Cloud (включены ли access logs, срок хранения, кто имеет доступ) и заполнить `⟨…⟩` в §4 | политика, Data safety |
| O-3 | Юридическая проверка §4 и решения по Data safety / AGC «Collect personal data» (LR-5 и связанные) | публикация |
| O-4 | Опубликовать обновлённую политику RU/EN на `legal.mythweld.com` **до** загрузки OTA ON сборки (P-6) | загрузка |
| O-5 | Заполнить Data safety (Google Play), блок Data Safety (RuStore), privacy tags (AppGallery) по §5–§7 🔑 | загрузка |
| O-6 | Обновить тексты карточек по §8 в консолях 🔑 | загрузка |
| O-7 | Решить OD-4: нужен ли переключатель «Загружать обновления данных талантов». Сейчас его нет, и тексты §3–§4 это учитывают | возможно, модерация RuStore 5.3 |
| O-8 | Выполнить ручные проверки M-1…M-8 ([android-production-enablement §4](../ota/android-production-enablement.md#4-ручные-проверки-владельца-не-выполнены-не-отмечать-pass-без-запуска)) | загрузка |
| O-9 | Поднять `versionCode`/`versionName` отдельным PR, собрать и подписать OTA ON артефакты (§3 того же документа) 🔑 | загрузка |

## 10. Что этот PR не меняет

- Тексты и декларации для OTA OFF сборки: они остаются верными.
- Статусы LR-1…LR-5 (OPEN), решения OD-4/OD-5.
- iOS: production OTA выключен, политика для iOS не публикуется (`PublishedPrivacyPolicy.NotPublished`).
- Опубликованные страницы политики и консоли магазинов.
