# M5.IOS.2 — iOS Production OTA Enablement: готовность

| | |
|---|---|
| Дата | 2026-10-23 |
| База | `main` @ `eb9fc46` (merge PR #75, M5.IOS.1) |
| Итог | Код, проверки и симуляторный E2E готовы к code review. **Публичный iOS release gate с OTA ON не пройден**: нет проверки на физическом iPhone, iOS-политика не опубликована, App Privacy для OTA ON не выбран (§10) |
| Конфигурация по умолчанию | **OTA OFF**. OTA ON включает только владелец одним флагом `talentforge.ota.ios.production.enabled=true` ([руководство владельца](m5-ios-2-owner-guide.md)) |
| Production | только чтение: preflight до и после работы, состояние не изменилось (§7). Ничего не подписывалось и не публиковалось, promote и rollback не выполнялись |
| Merge | только после независимого code review |

Статусы: **PASS** — выполнено здесь и прошло; **FAIL** — выполнено и не прошло; **BLOCKED** — нельзя выполнить без внешнего условия (владелец, устройство, инструмент); **NOT RUN** — можно было, но не выполнено. Ничего не помечено PASS по выводу из другой проверки.

Инструменты. Сборка и среда выполнения указываются **раздельно**:

| | Локально | CI (`mobile-ci`, образ `xcode-27`) |
|---|---|---|
| Xcode | 26.4.1 (17E202) | **27.1 (27A9275)** |
| SDK сборки | iphoneos26.4 / iphonesimulator26.4 | **iphoneos27.1 / iphonesimulator27.1** |
| iOS Simulator Runtime | iOS 26.4.1 (23E254a), iPhone 17 Pro | выбирается под устройство: на образе есть iOS 27.1 (24A94425) и iOS 27.0 (24A434), **iPhone 18 Pro поддерживает только iOS 27.0** → E2E идёт на **iOS 27.0 runtime**; фактические версия и сборка runtime записываются в отчёт (`simulator.runtime`, `runtimeBuild`, `runtimeIdentifier`) |

Всё, что выполнено локально, помечено «локально, Xcode 26.4.1» и **не** выдаётся за проверку на Xcode 27.1. Бинарники CI собираются Xcode 27.1 с iOS 27.1 SDK (минимальная iOS приложения — 17.2), поэтому выполнение на runtime 27.0 не меняет SDK сборки (§9).

## 1. Сводная матрица

| # | Требование | Статус | Доказательство |
|---|---|---|---|
| 1 | iOS production origin и keyring закреплены в сборке; подмена origin, ключей или канала, staging/test-ключи → ошибка сборки | **PASS** (локально) | §2 |
| 2 | OTA OFF — конфигурация по умолчанию; флаг нельзя закоммитить | **PASS** (локально) | §2 |
| 3 | Позитивные и негативные правила в `verifyOtaSettingsRules`, мутационная проверка | **PASS** (локально) | §2 |
| 4 | Verifier архива: режимы `--ota off` / `--ota on`, ATS, privacy, без ложного PASS | **PASS** (unit 56/56; реальные архивы локально) | §3 |
| 5 | Архивы OTA OFF и OTA ON на Xcode 27.1 проходят verifier | **PASS** (CI) | §9 |
| 6 | iOS staging E2E на симуляторе (`NSURLSession`, Security.framework, 70374 → 70450) | **PASS**: CI — Xcode 27.1 / SDK 27.1, runtime iOS 27.0, iPhone 18 Pro, 14/14; локально — 14/14 | §4, §9 |
| 7 | Негативные и восстановительные сценарии | **PASS** (локально) | §4, §5 |
| 8 | Сохранность билдов и их версий данных | **PASS** (локально) | §4 |
| 9 | Настоящее приложение на симуляторе: OTA ON (staging) и OTA OFF | **PASS** (локально, 6/6) | §5 |
| 10 | Production preflight только на чтение, состояние не изменено | **PASS** | §7 |
| 11 | Privacy manifest, Required Reason API, ATS, фактические запросы | **PASS** | §6 |
| 12 | App Privacy answers для OTA ON | **BLOCKED** (владелец, OA-1) | §6, [app-privacy §3](app-privacy.md#3-сборка-ota-on-m5ios2) |
| 13 | iOS-политика опубликована, ссылка в About | **BLOCKED** (владелец; страницы отвечают 403) | §6 |
| 14 | App Review Notes и метаданные OTA ON RU/EN | **PASS** (текст готов) | [metadata-en §8](metadata-en.md#8-замены-для-сборки-ota-on-только-после-m5ios2), [metadata-ru §8](metadata-ru.md#8-замены-для-сборки-ota-on-только-после-m5ios2) |
| 15 | Android regression, shared-тесты, Python | **PASS** | §9 |
| 16 | Исправление SIGPIPE из PR #75 сохранено | **PASS** | §9 |
| 17 | Физический iPhone / TestFlight | **BLOCKED** (нет устройства и подписи) | §8, [план владельца](m5-ios-2-owner-guide.md#5-проверка-на-физическом-iphone-testflight--план) |

## 2. Закрепление production-канала (Gradle)

В `mobile/composeApp/build.gradle.kts` production-канал **обеих** платформ закреплён одинаково (раньше — только Android).

| Проверка | Как | Результат |
|---|---|---|
| `enabled=true` без других настроек | `TALENTFORGE_OTA_IOS_PRODUCTION_ENABLED=true ./gradlew :composeApp:generateOtaSettings` | **PASS**: `iosProduction` = `enabled = true`, `OtaChannel.PRODUCTION`, `https://ota.mythweld.com`, ровно `tf-prod-2026a` и `tf-prod-2026b` ([02](evidence/m5-ios-2/gradle-pinning/02-ios-on-generate.txt)) |
| По умолчанию | без настроек | **PASS**: `enabled = false`, `baseUrl = null`, ключей нет, `mythweld` в файле не встречается ([03](evidence/m5-ios-2/gradle-pinning/03-ios-off-generate.txt)) |
| Другой origin | `…_BASEURL=https://ota.example.org` | **PASS** (сборка остановлена): «baseUrl is pinned to https://ota.mythweld.com» ([04](evidence/m5-ios-2/gradle-pinning/04-ios-other-origin.txt)) |
| Staging-keyring вместо production | `…_KEYRING=<staging-keyring.json>` | **PASS** (остановлена): «not a production key», «must pin a primary and a backup key», «must pin exactly the committed production keyring» ([05](evidence/m5-ios-2/gradle-pinning/05-ios-staging-keyring.txt)) |
| Staging-настройка в iOS release | `TALENTFORGE_OTA_IOS_STAGING_ENABLED=true`: `checkReleaseOtaSettings` останавливает сборку, и `linkReleaseFrameworkIosArm64` от неё зависит | **PASS** ([06](evidence/m5-ios-2/gradle-pinning/06-ios-release-link-staging-dryrun.txt), [07](evidence/m5-ios-2/gradle-pinning/07-ios-staging-release-guard.txt)) |
| Флаг в закоммиченном `gradle.properties` | временная строка в `mobile/gradle.properties` | **PASS** (остановлена): «must not be committed» ([08](evidence/m5-ios-2/gradle-pinning/08-committed-flag.txt)); файл восстановлен |
| `verifyOtaSettingsRules` | 16 новых iOS-отказов: другой, staging- или http-origin, origin с путём, origin при выключенном канале, staging-, test-, чужой или единственный ключ, ключи `tf-prod` с чужими SPKI, другой алгоритм, keyring не файл, trust anchor, `enabled=yes`, production-ключ в iOS staging. Рендер ON (ровно origin, канал и 2 ключа; остальные каналы выключены) и OFF (ни origin, ни SPKI). Изменённый keyring — для обеих платформ | **PASS** ([01](evidence/m5-ios-2/gradle-pinning/01-verifyOtaSettingsRules.txt)) |
| Мутация: закрепление «только Android» | `pinned = platform == "android" && …` | **PASS**: `verifyOtaSettingsRules` падает с 8 iOS-нарушениями ([09](evidence/m5-ios-2/gradle-pinning/09-mutation-android-only-pinning.txt)); код восстановлен |

ECDSA P-256 / SHA-256 и оба SPKI закреплены отпечатками (`productionOtaSpkiSha256`). Тот же набор зашит в verifier архива, а тест `test_the_verifier_pins_the_same_fingerprints_as_the_gradle_build` не даёт им разойтись.

## 3. Verifier архива

`scripts/verify_ios_archive.py` теперь требует `--ota off|on`. Все проверки M5.IOS.1 сохранены: идентичность, версия, toolchain, `arm64`, privacy manifest и Required Reason API, локализации, иконка, встроенный код, секреты. Добавлены:

| Проверка | OFF | ON |
|---|---|---|
| `ota-off` / `ota-on` | нет production-origin и **ни одного** P-256 SPKI-литерала (UTF-8 и UTF-16LE) | есть `https://ota.mythweld.com`; встроены **ровно** два ключа, опознанные по SHA-256 SPKI и названные `tf-prod-2026a/b` |
| `ota-keyring` | переданный `--production-keyring` имеет закреплённые отпечатки: подменённый keyring не даёт PASS | то же |
| `ota-no-staging-origin` | нет staging-, loopback- или эмулятор-origin (VK staging-хост, `localhost`, `127.*`, `10.0.2.2`, `*staging*`) | то же |
| `ats-default` | в `Info.plist` нет `NSAppTransportSecurity` | то же |
| `privacy-collected-data` | `NSPrivacyCollectedDataTypes` = `[]` (для OFF другое не принимается) | равно `--privacy-collected` (`none` или `other-diagnostic`) |

| Проверка | Статус | Доказательство |
|---|---|---|
| Unit-тесты: подмена origin, keyring и отдельных ключей, лишний `test-` ключ, подменённый key id, staging-origin в обоих режимах, архив OFF в режиме ON и наоборот, ATS, privacy, CLI без `--ota` | **PASS** 56/56 | `tests/unit/test_verify_ios_archive.py` |
| Реальные unsigned-архивы, **локально Xcode 26.4.1** (SDK `iphoneos26.4`): OFF как `off`, ON как `on` | **PASS** / **PASS** | [ota-off](evidence/m5-ios-2/archive-local-xcode-26.4.1/ota-off.txt), [ota-on](evidence/m5-ios-2/archive-local-xcode-26.4.1/ota-on.txt) |
| Те же архивы в чужом режиме | ожидаемый **FAIL**: OFF как `on` → «origin missing, keys []»; ON как `off` → «production origin=True, keys=[tf-prod-2026a, tf-prod-2026b]» | [negative](evidence/m5-ios-2/archive-local-xcode-26.4.1/) |
| Xcode 27.1 (CI), SDK `iphoneos27.1` | **PASS** OFF и ON, перекрёстно — FAIL как и должно | [archive-ci-xcode-27.1](evidence/m5-ios-2/archive-ci-xcode-27.1/) |

HTTPS-литералы в реальном OTA ON бинарнике: `https://ota.mythweld.com`, `https://legal.mythweld.com` (ссылка на политику), `https://goo.gle`, `https://youtrack.jetbrains.com` (тексты ошибок библиотек). В OFF — те же, кроме `ota.mythweld.com`.

## 4. iOS staging E2E на симуляторе

`IosOtaStagingE2ETest` (`mobile/composeApp/src/iosTest`) выполняется **в iOS Simulator**, а не в JVM `desktopTest`. Он использует production-стек iOS-клиента:

- `PlatformBlobFetcher` поверх `NSURLSession` (ATS по умолчанию, системное хранилище доверия);
- `PlatformSignatureVerifier` поверх Security.framework;
- `PlatformOtaFileStore` (`fsync`, `rename(2)`);
- `IosBuildLibraryStorage` поверх `NSUserDefaults`;
- встроенные Compose-ресурсы приложения как bundle.

`scripts/ios_ota_e2e.py run` выполняет такие шаги:

1. Публикует релизы настоящим publisher на эфемерном ключе `tf-staging-local-*` (`ota_staging_stand.py prepare`: A = `1.60.1.70374-r1`, B = `1.60.1.70450-r1`, rollback, data level 2).
2. Поднимает HTTPS-origin на `127.0.0.1`. Сертификат выпущен эфемерным CA, который добавлен **корнем доверия только в созданный для прогона симулятор** (`simctl keychain add-root-cert`). Проверка сертификата и имени хоста не отключается.
3. Создаёт отдельный симулятор, запускает каждую фазу отдельным процессом (`simctl spawn`) и в конце удаляет симулятор.

В bundle только `70374`, как у версии приложения до 70450. Поэтому A → B — **настоящее** обновление по сети, а не повторная активация той же версии.

Прогон: **локально** — Xcode 26.4.1, SDK iphonesimulator26.4; runtime **iOS 26.4.1 (23E254a)**, iPhone 17 Pro, выбран `choose_runtime`; стенд на `eb9fc46`, ключ `tf-staging-local-1c72b193`. Итог — 14/14 фаз **PASS** ([отчёт](evidence/m5-ios-2/simulator-e2e-local-xcode-26.4.1/ios-ota-e2e-report.json), [строки результатов](evidence/m5-ios-2/simulator-e2e-local-xcode-26.4.1/results.txt), [журналы фаз](evidence/m5-ios-2/simulator-e2e-local-xcode-26.4.1/phase-logs/)). Xcode 27.1 / iPhone 18 Pro — задача `ios-ota-e2e` в CI, её отчёт и журналы фаз — артефакт `ios-ota-e2e-xcode-27.1` (§9).

| Фаза | Что проверено | Результат |
|---|---|---|
| key-classes | бинарник без настроек = kill switch: `iosProduction` и `iosStaging` → `NOT_ENABLED`, `IosOta.runtime()` = `null`; staging-ключ отвергнут в production, `test-` — везде; `http://` → `BAD_BASE_URL`, fetcher отказывает до соединения | **PASS** |
| update-70374-to-70450 | старт на встроенных данных без единого запроса; A из bundle (скачан только манифест); B: **391 файл / 5 042 736 байт по HTTPS**, байты манифеста совпадают с publisher, атомарная установка, активация; новый редактор и новый билд — на 70450; открытый ранее редактор читает только 70374; сохранённые билды 70374 открываются на 70374; обновление не меняет библиотеку; повторный каталог — `NO_CHANGE`, 1 запрос | **PASS** |
| relaunch-1 / relaunch-2 | процесс 1 устанавливает B и сохраняет билды на 70374 и 70450. **Новый процесс** без сети: B активен с диска, 0 запросов при старте, библиотека совпадает байт в байт, оба билда открываются на своих версиях, проверка → `Network`, ничего не меняется | **PASS** / **PASS** |
| terminate-1 / terminate-2 | процесс убит `SIGKILL` (выход 137) перед 150-м файлом — так iOS завершает приостановленное приложение. **Новый процесс**: `recover()` удалил частичные файлы и сохранил проверенные, ничего не активно, библиотека та же; продолжение скачивает только недостающие файлы и активирует B | **PASS** / **PASS** |
| suspension | `onBackground()` (его вызывает `ON_STOP`) посреди загрузки отменяет попытку: 2 с после — ни одного запроса, `.part` нет. `onForeground()` продолжает без повторной загрузки проверенных файлов. Продолжение работы фоновой корутины **не** предполагается | **PASS** |
| transport | каталог 404/500/503; редирект 302 не выполняется (цель редиректа получила 0 запросов); обрыв соединения; манифест 404 и файл 404 (нет объекта на CDN); обрыв посреди файлов (проверенные сохранены, продолжение без повторов); origin недоступен (connection refused); DNS не отвечает | **PASS** |
| tls | недоверенный CA, просроченный сертификат, сертификат другого хоста → `NSURLErrorDomain`, `retryable = false`, 1 попытка; на этих портах 0 HTTP-запросов | **PASS** |
| integrity | неверная подпись, изменённый payload, подпись чужим ключом и `test-` ключом фикстур → каталог не принят, 0 файлов; испорченный манифест → `ManifestHashMismatch`; SHA-256 файла → `BlobHashMismatch`; неполный файл → `BlobSizeMismatch`; каталог после `expiresAt` → `CatalogExpired`. После каждого случая чистая проверка ставит B | **PASS** |
| disk-space | мало места до первого файла и `ENOSPC` посреди загрузки → `InsufficientStorage`, без `.part`, ничего не меняется; с местом — установка | **PASS** |
| rollback-and-data-level | rollback B → A (sequence 3): A из bundle, B сохранён (на него ссылается билд) и открывается; `minDataLevel 2` → `NeedsNewerApp`, 1 запрос; старый sequence 2 → `SequenceRegression` | **PASS** |
| corrupted-store | мусор в `state.json` → старт на bundle, релизы не удалены, следующая проверка возвращает B без загрузки файлов. Изменённый байт установленного файла → типизированная ошибка чтения, карантин, новые сессии на 70374, следующая проверка переустанавливает B | **PASS** |
| historical-on-demand | приложение со встроенным только 70450 и сохранённым билдом 70374: без сети → `Unavailable(Offline)`; с сетью 70374 скачивается, не активируется, билд открывается на 70374 | **PASS** |

Во всех негативных случаях проверяется одно и то же:

- библиотека билдов не изменилась (полезная нагрузка `NSUserDefaults` совпадает байт в байт);
- активный релиз и продукт новых билдов прежние;
- `.part` нет.

Открытие билда в редакторе меняет `lastOpenedAt` — это действие пользователя, поэтому эталон снимается после открытия.

## 5. Настоящее приложение на симуляторе

`scripts/ios_ota_e2e.py app` запускает две сборки:

- `TalentForge.app` Debug с iOS staging-каналом на тот же HTTPS-стенд (настройки из `app-env`; release-сборка с ними невозможна, §2);
- Release-сборку без настроек (OTA OFF).

Это покрывает то, чего нет в компонентном тесте: проверку после первого кадра (`App.kt`), lifecycle, ATS с `Info.plist` приложения и завершение процесса системой (`simctl terminate`). Встроенный продукт приложения — 70450, поэтому активируется `1.60.1.70450-r1` с файлами из bundle. Это проверка проводки приложения, **не** доказательство обновления версии — обновление доказано в §4.

Прогон: **локально, Xcode 26.4.1, iOS 26.4.1, iPhone 17 Pro** — 6/6 **PASS** ([отчёт](evidence/m5-ios-2/app-e2e-local-xcode-26.4.1/ios-ota-app-report.json), снимки экрана [после активации](evidence/m5-ios-2/app-e2e-local-xcode-26.4.1/app-1-after-activation.png), [после возврата из фона](evidence/m5-ios-2/app-e2e-local-xcode-26.4.1/app-2-after-foreground.png), [OTA OFF](evidence/m5-ios-2/app-e2e-local-xcode-26.4.1/app-3-ota-off.png)).

| Проверка | Результат |
|---|---|
| app-first-launch-activates | Первый запуск: приложение само (после первого кадра) делает 2 HTTPS-запроса — каталог и манифест, файлы берутся из bundle. `state.json`: active `1.60.1.70450-r1`; приложение работает. **PASS** |
| app-request-headers | `User-Agent: TalentForge/1.0.0`, `Accept-Language: *`; заголовки только `Accept`, `Accept-Encoding`, `Accept-Language`, `Connection`, `Host`, `User-Agent`; cookies нет. **PASS** |
| app-relaunch-offline-keeps-b | Перезапуск, все соединения обрываются: приложение работает, B остаётся активным. **PASS** |
| app-terminated-mid-install-recovers | `simctl terminate`, пока манифест в пути: ничего не активировано. Холодный старт активирует B. **PASS** |
| app-background-then-foreground | Переход в Настройки на 6 с во время проверки: ничего не активировано. Возврат в приложение: проверка возобновляется, B активен. **PASS** |
| app-ota-off-no-network | Release без OTA: 15 с после запуска нет Internet-сокетов (`lsof`), 0 запросов, каталога OTA нет. **PASS** |

## 6. App Store, privacy и App Review

| Пункт | Факт | Статус |
|---|---|---|
| `PrivacyInfo.xcprivacy` | `NSPrivacyTracking = false`, доменов нет. Required Reason API: UserDefaults `CA92.1`, FileTimestamp `C617.1`, DiskSpace `E174.1` — совпадают с импортами бинарника в архивах OFF и ON (`privacy-required-reasons`) | **PASS** |
| App Transport Security | `NSAppTransportSecurity` отсутствует в обоих архивах (`ats-default`); исключений нет; TLS проверяет система (§4, фаза tls) | **PASS** |
| Фактические запросы OTA ON | только `GET` к `https://<origin>/v1/channels/<channel>/catalog.json` и `/v1/blobs/sha256/<hex>`. Заголовки измерены (§5): `Host`, `User-Agent: TalentForge/<версия>`, `Accept: */*`, `Accept-Encoding`, `Accept-Language: *`, `Connection`. Без cookies, идентификаторов и содержимого билдов | **PASS** |
| Найдено и исправлено | `NSURLSession` по умолчанию отправлял `Accept-Language: en-US,en;q=0.9` — языки устройства. Этого нет в политике, а серверу язык не нужен. `PlatformBlobFetcher` теперь отправляет фиксированное `Accept-Language: *`; проверка `app-request-headers` это требует | **PASS** |
| OTA OFF: сеть | в архиве нет origin и ключей; приложение 15 с работает без Internet-сокетов (`lsof`) и без запросов | **PASS** (§5) |
| App Privacy answers | варианты A и B и точное изменение манифеста для B — в [app-privacy §3](app-privacy.md#3-сборка-ota-on-m5ios2). «Data Not Collected» для OTA ON **не** заявляется без факта о журналах CDN (OA-1) | **BLOCKED** (владелец) |
| iOS-политика RU/EN | текст уже описывает OTA ON и сверен с измеренным поведением: моменты проверок, `User-Agent`, отсутствие cookies и идентификаторов, проверка подписи, исключение из бэкапа. Изменения не нужны. На 2026-10-23 `https://legal.mythweld.com/privacy-ios.html` и `…/privacy-ios-en.html` отвечают **403** | **BLOCKED** (публикует владелец) |
| Ссылка на политику в About | не включена: страницы нет. Включается отдельным маленьким PR после публикации ([legal/ios §3, шаг 5](../../legal/ios/README.md)) | **BLOCKED** |
| App Review Notes и описание OTA ON (RU/EN) | готовы и сверены со сборкой: моменты загрузки, только `GET`, подпись ECDSA P-256 и SHA-256, JSON + PNG, без кода, без идентификаторов | **PASS** (текст) |
| Guideline 2.5.2 | OTA доставляет только данные контракта v1: JSON и PNG по SHA-256 из подписанного каталога. Интерпретатора, загрузки кода, `dlopen` и изменения логики нет. Содержимое проходит строгие валидаторы данных (`ReleaseVerifier`, валидация датасетов); узел, который движок не поддерживает, останавливает gate G6 у publisher | **PASS** |
| Guideline 2.5.5 (IPv6) | запросы идут по имени хоста через `NSURLSession`; проверка NAT64 возможна только на устройстве или с NAT64-сетью Mac | **NOT RUN** (P-9 владельца) |
| Export compliance | `ITSAppUsesNonExemptEncryption = false` (только шифрование ОС и проверка подписи), без изменений | **PASS** |

## 7. Production HTTPS preflight (только чтение)

`scripts/ota_production_preflight.py --expect published --keyring docs/ota/keys/production-keyring.json` выполняет только анонимные `GET`/`HEAD` и ничего не записывает.

| | До работы (2026-10-23T09:23:42Z) | После работы |
|---|---|---|
| Результат | **OK** | **OK** (2026-10-23T10:07:12Z): подпись, sequence 1, активный релиз, payload `sha256:cd4e25b9…`, 397 файлов — **без изменений** |
| DNS / TLS | `ota.mythweld.com` → CNAME `cl-541e19d9.mf.cdnmgf.megafon.ru`; TLSv1.3, Let's Encrypt, имя проверено, до 2027-01-18; TLS 1.0/1.1 отвергнуты | |
| Каталог | 200, заголовки контракта, 1031 Б; подпись верна (production keyring); sequence 1; активен `1.60.1.70450-r1`; payload `sha256:cd4e25b9…51e18e8c` | |
| Срок | `expiresAt` 2026-12-05T08:31:31Z — 42 дня (порог re-sign — 15 дней) | |
| Релиз | 397 файлов, 5 077 048 байт перехешированы; 398 объектов с заголовками контракта; бакет отдаёт то же | |
| Приватные пути | 13 путей origin и 13 путей бакета → 403/404 | |

Отчёты: [до](evidence/m5-ios-2/production-preflight-before.txt) ([json](evidence/m5-ios-2/production-preflight-before.json)), [после](evidence/m5-ios-2/production-preflight-after.txt). Совместимость: data level релиза — 1, равен `OtaContract.SUPPORTED_DATA_LEVEL`. iOS-клиент с bundle 70450 активирует `1.60.1.70450-r1` из bundle-файлов, как Android в M-2.

Серверные наблюдения. Изменений не делалось; hotfix для этого PR не нужен.

- `WARN http.plain`: `http://ota.mythweld.com` отдаёт 200. Клиенты отказываются от `http://` сами (§4, фаза key-classes), поэтому это не дефект клиента. Рекомендация владельцу прежняя: редирект `http://` → `https://` на CDN.
- До истечения каталога 42 дня. Для загрузки в App Store нужен запас не меньше 30 дней, поэтому при загрузке позже 2026-11-05 сначала выполните re-sign ([руководство §3](m5-ios-2-owner-guide.md#3-перед-сборкой-ota-on)).

## 8. Физическое устройство и TestFlight

| Пункт | Статус | Причина / план |
|---|---|---|
| OTA ON на физическом iPhone, P-1…P-13: офлайн-старт, загрузка, сворачивание и закрытие во время загрузки, перезапуск после перезагрузки, сохранность билдов, выключенные сотовые данные, IPv6/NAT64, переустановка, хранилище, обновление поверх OTA OFF, наблюдение запросов | **BLOCKED** | у агента нет устройства и Apple Developer-подписи. План с ожидаемыми результатами — [owner-guide §5](m5-ios-2-owner-guide.md#5-проверка-на-физическом-iphone-testflight--план) |
| TestFlight-сборка OTA ON | **BLOCKED** | подпись и загрузку выполняет только владелец ([owner-guide §4](m5-ios-2-owner-guide.md#4-сборка-и-проверка-архива)) |

## 9. CI и проверки

Локально (Xcode 26.4.1, macOS; журналы — [validation](evidence/m5-ios-2/validation/)):

| Проверка | Команда | Статус |
|---|---|---|
| Python: lint, формат, типы, тесты | `ruff check .`, `ruff format --check .`, `mypy`, `pytest` (неотслеживаемые локальные каталоги `.tf-transfer/`, `ceremony/`, `legal-publish/`, `artifacts/` исключены: их нет в git) | **PASS**: 1747 passed, 11 deselected |
| Синхронизация датасетов | `python3 scripts/sync_mobile_datasets.py --check` | **PASS** |
| Shared Kotlin (JVM) | `./gradlew :shared:jvmTest --rerun-tasks` | **PASS**: 373 теста |
| Compose desktop (JVM, вкл. OTA L2 и `verifyOtaSettingsRules`) | `./gradlew :composeApp:desktopTest --rerun-tasks` | **PASS**: 834 теста, 58 opt-in пропущены (как в `main`). В первом принудительном прогоне под нагрузкой один раз упал `OtaScenarioTest.s5b_…` («exactly the k answered files stay staged»: 205 вместо 198). Это существующая гонка теста: тест и общий код в ветке не менялись; отдельно 5/5 PASS, повторный полный прогон PASS ([журнал](evidence/m5-ios-2/validation/desktop-tests-rerun-2.txt)). Вынесено в follow-up |
| Android regression | `./gradlew :composeApp:assembleDebug :composeApp:assembleRelease` | **PASS** (Android OTA-настройки и guards не менялись, кроме общих имён констант) |
| iOS Simulator tests (обычные) | `./gradlew :shared:iosSimulatorArm64Test :composeApp:iosSimulatorArm64Test` | **PASS**: `IosOtaAdapterTest` 5, `IosAboutFactsTest` 2; E2E-класс исключён из этого запуска |
| iOS Release framework | `./gradlew :composeApp:linkReleaseFrameworkIosArm64 :composeApp:linkReleaseFrameworkIosSimulatorArm64` (с `checkReleaseOtaSettings`) | **PASS** |
| Архивы OFF/ON + verifier | §3 | **PASS** |
| iOS OTA staging E2E | `python3 scripts/ios_ota_e2e.py run <dir>` | **PASS** 14/14 (§4) |
| Приложение OTA ON (staging) / OFF | `python3 scripts/ios_ota_e2e.py app …` | **PASS** 6/6 (§5) |
| Секреты в артефактах | поиск `PRIVATE KEY`, `.pem/.p12/.key` в коммите и доказательствах; проверка `no-secrets` в архивах | **PASS**: в репозиторий не попали ни приватные ключи стенда, ни TLS-ключи (они остаются во временном каталоге вне git), ни пути машины |
| SIGPIPE-исправление PR #75 | `git diff main -- scripts/ios_simulator_smoke.sh tests/unit/test_ios_simulator_smoke.py` пуст; тест `test_ios_simulator_smoke.py` в составе `pytest` | **PASS** |

На Xcode 27.1 (`mobile-ci`, образ `xcode-27`):

| Задача / шаг | Статус |
|---|---|
| `mobile-macos`: iOS simulator tests, release frameworks, Release для симулятора | **PASS** ([run 39954106784](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/39954106784), коммит `7b8cd95`) |
| `mobile-macos`: архив OTA OFF + `--ota off`; архив OTA ON + `--ota on`; перекрёстная проверка режимов | **PASS**: оба архива SDK `iphoneos27.1`, Xcode 27A9275; ON — ровно `tf-prod-2026a/b`, OFF — ни одного ключа; ни один архив не проходит в чужом режиме ([отчёты](evidence/m5-ios-2/archive-ci-xcode-27.1/)) |
| `mobile-macos`: launch smoke iPhone 18 Pro, iPhone 18 Pro Max, iPad Pro 13-inch (M5) | **PASS**: все три на runtime iOS 27.0 (сборка Xcode 27.1 / SDK 27.1) |
| `ios-ota-e2e`: 14 фаз на iPhone 18 Pro (Xcode 27.1, iOS 27.1 SDK; runtime — таблица «Инструменты» в начале) | **PASS 14/14**: runtime **iOS 27.0 (24A434)**, `com.apple.CoreSimulator.SimRuntime.iOS-27-0` ([отчёт](evidence/m5-ios-2/simulator-e2e-ci-xcode-27.1/ios-ota-e2e-report.json), [строки](evidence/m5-ios-2/simulator-e2e-ci-xcode-27.1/results.txt), [журналы](evidence/m5-ios-2/simulator-e2e-ci-xcode-27.1/phase-logs/)) |
| `mobile-linux`: shared, desktop, Android | **PASS** |

Инцидент CI (исправлен в этом PR). Первые два запуска `ios-ota-e2e` (runs 39938806401 и 39939931594) завершились `ERROR: iPhone 18 Pro is not supported by iOS 27.1` **до** первой фазы: `pick_device()` сначала брал самый новый runtime (27.1) и только потом проверял устройство. Теперь `choose_runtime()` для запрошенной модели берёт самый новый *доступный* runtime, который её поддерживает. Модель, указанная явно, никогда не заменяется другой; если подходящего runtime нет, выводятся все runtime и их устройства. Покрыто `tests/unit/test_ios_ota_e2e.py`: только 27.0 поддерживает; обе версии поддерживают; устройства нет; runtime недоступен; нет iOS runtime; выбор по умолчанию; числовое сравнение версий.

Затем на iOS 27.0 (run 39946215591) 9 из 14 фаз упали на полной установке B с `CheckOutcome.Failed`; тип ошибки тест тогда не печатал, локально (под полной нагрузкой CPU) не воспроизводилось. Исправлено в стенде, а не в клиенте: HTTPS-origin делал каждое TLS-рукопожатие внутри `accept()` единственного потока с очередью 5 — одно зависшее рукопожатие останавливало приём всех новых соединений клиента (8 параллельных, новое соединение на каждый файл). Теперь рукопожатие идёт в потоке соединения, очередь 128, неудачные рукопожатия записываются в отчёт, а сервер пишет `origin-log.json`; тест печатает фактический исход. С этим run 39954106784 — 14/14. Причина названа как **наиболее вероятная**: прямого воспроизведения нет. 21 неудачное рукопожатие на доверенном origin в зелёном прогоне приходится только на фазы, где клиент сам обрывает соединения (отмена при сбое, фон, `SIGKILL`): 17 `ConnectionResetError`, 4 `SSLEOFError`.

## 10. Только владелец

| # | Действие | Статус |
|---|---|---|
| OW-1 | App Privacy для OTA ON по журналам CDN (OA-1); при варианте B — PR с `PrivacyInfo.xcprivacy` ([app-privacy §3](app-privacy.md#3-сборка-ota-on-m5ios2)) | **BLOCKED** |
| OW-2 | Публикация iOS-политики RU/EN, затем PR со ссылкой в About | **BLOCKED** |
| OW-3 | Подписанный архив OTA ON и `verify_ios_archive.py --ota on` ([owner-guide §4](m5-ios-2-owner-guide.md#4-сборка-и-проверка-архива)) | NOT RUN |
| OW-4 | TestFlight и физический iPhone, P-1…P-13 | NOT RUN |
| OW-5 | Метаданные и App Review Notes OTA ON в App Store Connect, Privacy Policy URL | NOT RUN |
| OW-6 | Re-sign production-каталога, если на момент загрузки до `expiresAt` меньше 30 дней | по [production-deployment](../../ota/production-deployment.md) |
| OW-7 | Загрузка в App Store и отправка на ревью; merge этого PR после code review | NOT RUN |

Публичный iOS release gate с OTA ON (test-plan §6 C) **не пройден**, пока не закрыты OW-1, OW-2 и OW-4.

## 11. Ограничения

- Все локальные прогоны выполнены на Xcode 26.4.1 / iOS 26.4.1 (iPhone 17 Pro): Xcode 27.1 на этой машине не установлен. Доказательство на 27.1 — CI (§9).
- «Нет интернета» на симуляторе моделируется отказом соединения, DNS-ошибкой и обрывом соединений. Авиарежима на симуляторе нет — он в плане для устройства (P-1, P-8).
- Нехватка места моделируется обёрткой хранилища (свободное место и `ENOSPC`); настоящий заполненный диск не проверялся.
- Уход в фон в компонентном тесте — вызов `onBackground()`, тот же, что делает `ON_STOP`. В приложении — настоящий переход в другое приложение (§5). Приостановка iOS на устройстве — шаг P-3.
- Запуск до первой разблокировки неприменим: у приложения нет `UIBackgroundModes`, и iOS не запускает его до разблокировки. Шаг P-7 проверяет первый запуск после перезагрузки.
- Production-клиент против `https://ota.mythweld.com` на iOS не запускался: это была бы реальная загрузка с production. Production проверен preflight-ом только на чтение; путь клиента — тот же код с другой базой URL и ключами.
