# CI/CD доставки сборок в тестовые каналы (issue #90) 🔑

| | |
|---|---|
| Workflow | [`.github/workflows/store-delivery.yml`](../../.github/workflows/store-delivery.yml) («Store delivery»), только `workflow_dispatch` |
| Решения и API магазинов | [`scripts/store_delivery.py`](../../scripts/store_delivery.py); временная iOS-подпись — [`scripts/ios_delivery_signing.sh`](../../scripts/ios_delivery_signing.sh); Python 3.12 — [`.github/actions/delivery-python`](../../.github/actions/delivery-python/action.yml) |
| Что доставляется | Android: подписанный upload-ключом AAB → **существующий закрытый тест Google Play**; подписанный ключом приложения универсальный APK → проверенный артефакт запуска (загрузка в RuStore/AppGallery — следующий этап). iOS: подписанный archive → App Store export (`.ipa`) → App Store Connect → **существующая внутренняя группа TestFlight** |
| Что не делается никогда | публичный выпуск (production-трек Play, отправка на App Review, «Release»), открытое тестирование, загрузка в RuStore/AppGallery, сборка без подписи, с debug-ключом или с OTA OFF, изменение версий |
| Статус | Инженерная часть готова и проверена **только с фейковыми API** (`tests/unit/test_store_delivery.py`). Настоящая доставка не запускалась: владелец добавил credentials в GitHub Environments, реальной доставкой они ещё не проверены. Первые реальные загрузки и появление сборок в Play Console / TestFlight — финальная проверка владельца (§9) |

Шаги с 🔑 выполняет только владелец. Значения секретов никогда не пересылаются в чат, issue или PR.

## 1. Как это работает

Один запуск = одна ревизия `main` (или релизный тег на `main`) = один полный SHA для обеих платформ.

```text
gate ─┬─ android-store-check ── android-build ─┐
      │                                        ├─ android-deliver ──────────────┐
      └─ ios-store-check ────── ios-build ─────┴─ ios-upload ── ios-testflight ─┴─ summary
```

| Job | Runner | Environment | Что делает |
|---|---|---|---|
| `gate` | ubuntu | — | Проверяет, что workflow запущен с `main`; разрешает ревизию в полный SHA (пусто — голова `main`; полный 40-символьный SHA на `main`; тег `vX.Y.Z[-…]` на `main`; короткие SHA и имена веток отклоняются); требует **успешные** `CI` и `mobile-ci` именно этого SHA (push/dispatch на `main`, не форк); читает `versionCode`/`versionName` и `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` этого SHA |
| `android-store-check`, `ios-store-check` | ubuntu | `google-play`, `app-store-connect` | Только чтение: есть ли этот номер в магазине. Решение — `UPLOAD`, `ALREADY_DELIVERED`/`ALREADY_UPLOADED` (сборка уже доставлена этим workflow из того же SHA) или отказ `CONFLICT` |
| `android-build` | ubuntu | `android-signing` | Production OTA preflight (каталог опубликован, подпись проверена, **≥ 30 дней** до `expiresAt`); `assemblePublishableRelease` + `bundlePublishablePlay` с `TALENTFORGE_OTA_ANDROID_PRODUCTION_ENABLED=true` (Gradle сам проверяет и выкладывает файлы валидаторами исходного коммита); повторная проверка тех же файлов валидаторами workflow; `android-check`: APK и AAB — одна версия исходного коммита, `PASS` (не `SELFTEST-PASS`), есть `INTERNET`, в dex есть `https://ota.mythweld.com`, `tf-prod-2026a`, `tf-prod-2026b` и нет ни одного идентификатора `tf-staging-<id>` |
| `ios-build` | `xcode-27` (Xcode 27.1, 27A9275) | `ios-signing` | OTA preflight; `verify_ios_project.py`; временная ключница и App Store-профиль; Release archive с `TALENTFORGE_OTA_IOS_PRODUCTION_ENABLED=true` (ручная подпись Apple Distribution существует только в командной строке, в проекте остаётся Automatic без команды); `verify_ios_archive.py --archive --ota on --require-signed`; App Store export (`manageAppVersionAndBuildNumber = false`); **`verify_ios_archive.py --ipa`** — проверка именно загружаемого пакета: Apple Distribution + App Store-профиль, development-подпись не принимается; `ios-check`: archive и `.ipa` — одна версия, OTA ON |
| `android-deliver` | ubuntu | `google-play` | Только `mode=deliver` и только если **все** выбранные платформы собраны и проверены. Сверяет SHA-256, повторно читает Play прямо перед загрузкой, в одном edit загружает AAB, назначает релиз на трек `PLAY_TRACK` и коммитит, затем читает Play заново и требует тот же SHA-256 на этом треке |
| `ios-upload` | `xcode-27` | `app-store-connect` | То же условие. Сверяет SHA-256, повторно читает App Store Connect, **до** загрузки сохраняет запись `upload-started`, затем `xcrun altool --validate-app` и `--upload-package` |
| `ios-testflight` | ubuntu | `app-store-connect` | Ждёт обработки (`VALID`, до 100 минут), проверяет export compliance = false, добавляет сборку во внутреннюю группу `TESTFLIGHT_INTERNAL_GROUP` |
| `summary` | ubuntu | — | Таблица по платформам в summary запуска и `delivery-report.json`; job красный, если хоть одна выбранная платформа не дошла |

`mode=verify` (по умолчанию) выполняет сборку, подпись и все проверки, но ничего не загружает и не хранит подписанные бинарники — только отчёты и SHA-256, поэтому номер версии не расходуется. `mode=deliver` загружает.

Параллельные запуски исключены: `concurrency: store-delivery` без отмены идущего запуска. GitHub держит в очереди один ожидающий запуск: новый старт заменяет ожидающий, но не прерывает идущий.

Исполняемые инструменты доставки (`store_delivery.py`, валидаторы повторной проверки) берутся из коммита, с которого запущен workflow (голова `main`); собирается выбранный SHA, а Gradle-задачи проверяют артефакты валидаторами этого SHA. Выбранный SHA всегда предок головы `main`, поэтому инструменты не старше сборки. Обе проверки должны пройти.

## 2. Выбор инструмента

Требования: минимальная поддерживаемая поверхность, без новых зависимостей и стороннего кода рядом с production-секретами, тестируемость отказов без магазинов.

| Вариант | Решение |
|---|---|
| fastlane (`supply`, `pilot`, `match`) | Нет: Ruby-стек с десятками gem-зависимостей и своим lockfile ради нескольких HTTP-вызовов; `match` навязывает свою схему хранения сертификатов вместо существующей; поведение при повторе скрыто в плагинах — труднее доказать «не загрузить повторно» |
| Сторонние GitHub Actions (`r0adkll/upload-google-play`, `apple-actions/*`) | Нет: сторонний код получает service account и ключ App Store Connect; версия action — ещё одна цепочка поставки; нет проверки «тот же SHA-256 уже в магазине» |
| **Официальные HTTP API магазинов + штатные инструменты Apple** | **Выбрано.** Google Play Developer API v3 (edits) и App Store Connect API v1 вызываются из `scripts/store_delivery.py` через `httpx` и `cryptography` — обе уже зависимости репозитория. `.ipa` загружает `xcrun altool` из Xcode 27.1 (тот же toolchain, что и сборка). Подписывают существующие Gradle-задачи проекта и `xcodebuild`. Все решения — функции, отказы покрыты тестами с фейковыми API |

## 3. Настройка GitHub 🔑

### 3.1 Environments

Settings → Environments → **New environment**, четыре окружения. Для каждого: *Deployment branches and tags* → **Selected branches and tags** → правило `main`. Тогда секреты не видны pull request-проверкам, другим веткам и workflow, изменённым вне `main`; `gate` дополнительно отказывает, если запуск не с `refs/heads/main`.

| Environment | Кто использует | Защита |
|---|---|---|
| `google-play` | `android-store-check`, `android-deliver` | правило ветки `main` |
| `android-signing` | `android-build` | правило ветки `main`; по желанию *Required reviewers* = владелец (каждый запуск будет ждать подтверждения) |
| `app-store-connect` | `ios-store-check`, `ios-upload`, `ios-testflight` | правило ветки `main` |
| `ios-signing` | `ios-build` | правило ветки `main`; по желанию *Required reviewers* = владелец |

Создайте все четыре окружения **до** первого запуска: GitHub создаёт отсутствующее окружение сам, но без правила ветки и без секретов (job тогда остановится на `CONFIG`); у такого окружения сразу добавьте правило `main`.

Репозиторий публичный: включите *Secret scanning* и *Push protection* (Settings → Code security), если ещё не включены ([M5.CI.1](M5.CI.1-public-repo-secret-audit.md)). Для тегов `v*` заведите ruleset (Settings → Rules → New tag ruleset: target `v*`, *Restrict creations*, *Restrict updates*, *Restrict deletions*, bypass — только владелец), чтобы «утверждённый релизный тег» мог создать только владелец.

### 3.2 Secrets и Variables

Секреты (Environment secrets) скрыты в логах. Переменные (Environment variables) — публичные значения (отпечатки, имена).

**`android-signing`**

| Имя | Тип | Значение |
|---|---|---|
| `ANDROID_RELEASE_KEYSTORE_BASE64` | secret | `talentforge-release.p12` (ключ приложения: тот же для RuStore/AppGallery и Play App Signing) в base64 |
| `ANDROID_RELEASE_KEYSTORE_PASSWORD` | secret | пароль PKCS12 (для PKCS12 он же пароль ключа) |
| `ANDROID_RELEASE_KEY_ALIAS` | variable | `talentforge` |
| `ANDROID_RELEASE_CERT_SHA256` | variable | `APP_SIGNING_SHA256` ([android-release-signing §1](android-release-signing.md#1-create-the-key-once-)) |
| `ANDROID_UPLOAD_KEYSTORE_BASE64` | secret | `talentforge-upload.p12` (upload-ключ Play) в base64 |
| `ANDROID_UPLOAD_KEYSTORE_PASSWORD` | secret | его пароль |
| `ANDROID_UPLOAD_KEY_ALIAS` | variable | `talentforge-upload` |
| `ANDROID_UPLOAD_CERT_SHA256` | variable | `UPLOAD_SHA256` ([google-play/signing §4](google-play/signing.md#4-creating-the-keys-)) |

Gradle получает их под прежними именами `TALENTFORGE_RELEASE_*` / `TALENTFORGE_PLAY_UPLOAD_*`; схема ключей не меняется: APK — ключом приложения, AAB — только upload-ключом, валидаторы требуют разные сертификаты.

**`google-play`**

| Имя | Тип | Значение |
|---|---|---|
| `PLAY_SERVICE_ACCOUNT_JSON` | secret | JSON-ключ service account (§4.1) целиком |
| `PLAY_TRACK` | variable | имя существующего закрытого трека: `alpha` (Closed testing — Alpha) или имя своего закрытого трека. `production` и `beta` (открытое тестирование) отклоняются, несуществующий трек — тоже |
| `PLAY_RELEASE_STATUS` | variable, необязательно | `completed` (по умолчанию: сборка сразу доступна тестировщикам трека) или `draft` (черновик; раскатка в консоли) |
| `PLAY_CHANGES_NOT_SENT_FOR_REVIEW` | variable, необязательно | `true` только если Play при коммите отвечает «Changes cannot be sent for review automatically» (Managed publishing): изменения будут ждать отправки на проверку в консоли |

**`ios-signing`**

| Имя | Тип | Значение |
|---|---|---|
| `IOS_DISTRIBUTION_CERT_P12_BASE64` | secret | сертификат **Apple Distribution** с закрытым ключом (`.p12`, §4.3) в base64 |
| `IOS_DISTRIBUTION_CERT_PASSWORD` | secret | пароль экспорта `.p12` |
| `IOS_APPSTORE_PROFILE_BASE64` | secret | профиль **App Store Connect** для `io.github.alexeykravtsov95.talentforge` (`.mobileprovision`) в base64 |
| `APPLE_TEAM_ID` | secret | Team ID (10 символов); в git его нет, в логах он маскируется |

**`app-store-connect`**

| Имя | Тип | Значение |
|---|---|---|
| `ASC_API_KEY_ID` | secret | Key ID ключа App Store Connect API (10 символов) |
| `ASC_API_ISSUER_ID` | secret | Issuer ID (UUID) |
| `ASC_API_PRIVATE_KEY` | secret | содержимое `AuthKey_<KeyID>.p8` целиком (с `BEGIN/END PRIVATE KEY`) |
| `TESTFLIGHT_INTERNAL_GROUP` | variable | точное имя существующей **внутренней** группы TestFlight |

base64 без переносов строк (macOS), сразу в буфер обмена, без вывода в терминал:

```bash
base64 -i talentforge-release.p12 | tr -d '\n' | pbcopy
```

Вставьте в поле секрета, затем очистите буфер. Файлы ключей по-прежнему хранятся вне любых checkout.

Отсутствующая или пустая настройка останавливает job ошибкой `Delivery refused (CONFIG)` со списком **имён** недостающих настроек; значения не печатаются. Подмены на unsigned/debug/OTA OFF нет.

## 4. Настройка аккаунтов магазинов 🔑

### 4.1 Google Play: service account

1. Google Cloud Console → ваш проект → *APIs & Services* → включить **Google Play Android Developer API**.
2. *IAM & Admin → Service Accounts* → создать аккаунт (например `talentforge-delivery`), ролей проекта не давать → *Keys* → *Add key* → JSON. Содержимое — в `PLAY_SERVICE_ACCOUNT_JSON`, файл удалите.
3. Play Console → *Users and permissions* → *Invite new users* → e-mail service account → *App permissions* → TalentForge: **View app information (read-only)** и **Release apps to testing tracks**; без production-прав и без *Manage store presence*.
4. Закрытый трек уже существует (код `2` загружен вручную). Его имя — `PLAY_TRACK`.

### 4.2 App Store Connect: API key

App Store Connect → *Users and Access* → *Integrations* → *App Store Connect API* → *Team Keys* → «+», роль **App Manager** (нужна для назначения сборок группам TestFlight; Admin не нужен). Скачайте `.p8` (скачивается один раз), запишите Key ID и Issuer ID. Внутренняя группа TestFlight уже существует; её имя — `TESTFLIGHT_INTERNAL_GROUP`. Если у группы включён доступ ко всем сборкам, workflow ничего не назначает и пишет об этом.

### 4.3 Apple Distribution certificate и App Store-профиль

Xcode-managed (облачный) сертификат распространения экспортировать нельзя, поэтому для CI нужен сертификат с закрытым ключом у владельца:

1. Keychain Access → *Certificate Assistant* → *Request a Certificate From a Certificate Authority* → «Saved to disk».
2. [Certificates](https://developer.apple.com/account/resources/certificates/list) → «+» → **Apple Distribution** → загрузить CSR → скачать `.cer` → открыть (сертификат ляжет в login keychain рядом с ключом).
3. Keychain Access → *My Certificates* → «Apple Distribution: … (TEAMID)» → *Export* → `.p12` с паролем → `IOS_DISTRIBUTION_CERT_P12_BASE64`, `IOS_DISTRIBUTION_CERT_PASSWORD`.
4. [Profiles](https://developer.apple.com/account/resources/profiles/list) → «+» → **App Store Connect** → App ID `io.github.alexeykravtsov95.talentforge` → этот сертификат → скачать → `IOS_APPSTORE_PROFILE_BASE64`.

`ios_delivery_signing.sh` отклоняет профиль другой команды или другого bundle id, Ad Hoc/development/enterprise-профиль и `.p12` без ровно одной идентичности Apple Distribution этой команды. Bundle ID, Team и Automatic signing в проекте не меняются.

## 5. Запуск 🔑

Предусловия:

- Номера версий подняты **отдельным PR** и влиты в `main`; workflow версии не меняет. Для первой доставки: Android `1.0.0` / `versionCode 3` (код `2` израсходован загрузкой в Play, `3` — следующий); iOS `1.0.0 (4)` — сборка для внутреннего TestFlight. `1.0.0 (3)` уже загружена в App Store Connect и отправлена на App Review: этот номер не загружается повторно, а запуск SHA с `CURRENT_PROJECT_VERSION` ≤ `3` получит в iOS-ветке `CONFLICT` ([readiness §2](apple-appstore/readiness.md#2-конфигурация-release-сборки)). Доставка сборки `4` не затрагивает версию на App Review: workflow не отправляет на ревью и не меняет выбранную там сборку.
- `CI` и `mobile-ci` выбранного SHA зелёные. Если `mobile-ci` этого SHA был отменён следующим push (`cancel-in-progress`), перезапустите его: Actions → тот запуск → *Re-run all jobs*.
- Каталог production OTA действителен ещё ≥ 30 дней; иначе preflight остановит сборку — переподпишите каталог ([production-deployment](../ota/production-deployment.md)).
- iOS-политика и декларации магазинов — по [app-privacy](apple-appstore/app-privacy.md) и [ota-prod3-privacy-and-stores](ota-prod3-privacy-and-stores.md); workflow их не меняет.

Запуск: Actions → **Store delivery** → *Run workflow* → ветка **main**:

| Поле | Значение |
|---|---|
| `revision` | пусто (голова `main`), полный SHA коммита на `main` или тег `vX.Y.Z` на `main` |
| `platforms` | `both` / `android` / `ios` |
| `mode` | сначала **`verify`** (credentials, подпись и все валидаторы без загрузки), затем `deliver` |

### 5.1 Runners: стоимость и ограничения

- iOS-сборка и загрузка идут на образе `xcode-27` (Xcode 27.1, macOS 27) — тот же образ, что в `mobile-ci`. Образ в public preview: при его отсутствии или другом билде Xcode job падает (`BLOCKED`), перехода на `macos-latest` или другую версию Xcode нет.
- Репозиторий публичный: стандартные GitHub-hosted runners, включая macOS, для публичных репозиториев бесплатны. Для приватного репозитория стандартный macOS runner тарифицируется поминутно (с 1 января 2026 — $0.062/мин, Linux значительно дешевле; актуальные цены — [GitHub Actions runner pricing](https://docs.github.com/en/billing/reference/actions-runner-pricing)). Ожидание обработки TestFlight поэтому вынесено на ubuntu.
- Оценка длительности одной доставки (по `mobile-ci`, на реальной доставке не измерялась): ubuntu ≈ 15–25 мин сборки Android + до 100 мин ожидания обработки TestFlight; macOS ≈ 40–70 мин (Kotlin/Native release-линковка, archive, export) + 5–15 мин загрузки.
- Одновременно выполняется только одна доставка (concurrency).

## 6. Результат и артефакты

Summary запуска показывает таблицу «Platform / Status / Version / Artifact SHA-256 / Detail».

| Статус | Значение |
|---|---|
| `DELIVERED` | этот запуск загрузил сборку; Android: SHA-256 в Play совпал с проверенным AAB, релиз на `PLAY_TRACK`; iOS: обработка `VALID`, сборка во внутренней группе |
| `ALREADY_DELIVERED` | номер уже доставлен **этим workflow из того же исходного SHA** (Android — с тем же SHA-256, что сообщает Play, и код стоит на `PLAY_TRACK`; иначе `MISMATCH`); повторно ничего не загружено. Detail называет запуск-источник. iOS: App Store Connect не отдаёт хэш файла, идентичность подтверждается записью запуска, сохранённой не позже загрузки сборки (§7) |
| `ALREADY_UPLOADED` | только iOS, промежуточный: сборка этого SHA уже в App Store Connect, но `ios-testflight` ещё не подтвердил обработку и группу (например, его остановила ошибка другой платформы). В `deliver` это **не** успех; после `ios-testflight` становится `ALREADY_DELIVERED` |
| `VERIFIED` | `mode=verify`: собрано, подписано, проверено, ничего не загружено |
| `FAILED` | назван упавший job; последний записанный статус, если был, показан отдельно и не маскирует ошибку |
| `NOT_DELIVERED` | job платформы не дошёл до результата; detail называет упавший job другой платформы (одна ошибка останавливает все загрузки) |
| `BLOCKED` | `gate` не пропустил запуск |
| `NOT_SELECTED` | платформа не выбрана |

Артефакты (Summary запуска → Artifacts):

| Артефакт | Содержимое | Хранение |
|---|---|---|
| `tf-gate`, `delivery-report` | исходный SHA, тип ревизии, ссылки на зелёные CI, версии; итог по платформам | 90 дней |
| `android-release` (deliver) | `TalentForge-<v>-<code>.apk` (файл для RuStore/AppGallery), `.aab`, отчёты Gradle и повторной проверки, `release-check.json`, OTA preflight, `SHA256SUMS`, `gate.json` | 90 дней |
| `ios-release` (deliver) | `TalentForge-<v>-<build>.ipa`, отчёты project/archive/ipa, `release-check.json`, OTA preflight, `SHA256SUMS`, `gate.json` | 90 дней |
| `android-verify-reports`, `ios-verify-reports` (verify) | только отчёты и `SHA256SUMS` | 30 дней |
| `tf-delivery-…` | записи доставки (имя: платформа, версия, номер, фаза, исходный SHA, SHA-256 артефакта); по ним повторный запуск узнаёт свою сборку. Учитываются только записи запусков этого workflow с `main` этого репозитория | 90 дней |

**Публичный репозиторий:** артефакты Actions может скачать любой вошедший пользователь GitHub. Подписанные APK/AAB/IPA не содержат секретов (валидаторы проверяют `no-secrets`), но это предрелизные сборки, а в подписи `.ipa` и в отчётах виден Team ID (он виден в любой подписанной сборке и не является credential). Если это неприемлемо до публичного выпуска — используйте `mode=verify`, а для `deliver` сделайте репозиторий приватным или выделите шифрование артефактов отдельным этапом.

APK для RuStore/AppGallery: скачайте `android-release`, сверьте `shasum -a 256 -c SHA256SUMS`, загрузите **этот же** файл в обе консоли вручную и внесите SHA-256 в [журнал версий](android-versioning.md#3-planned-sequence-and-ledger).

## 7. Повторный запуск и восстановление

| Ситуация | Что делать |
|---|---|
| Сетевой сбой или 5xx при загрузке, таймаут обработки TestFlight | В том же запуске: *Re-run failed jobs*. Успешные сборки не пересобираются, используется тот же проверенный артефакт; перед загрузкой магазин читается заново, уже принятая сборка второй раз не загружается |
| Android доставлен, iOS упал (частичный успех) | *Re-run failed jobs* того же запуска; либо новый запуск того же SHA: Android получит `ALREADY_DELIVERED` без пересборки, iOS пройдёт заново |
| `CONFLICT` | номер уже израсходован: в магазине есть сборка с этим номером не из этого SHA или не этими байтами, либо номер выше. Поднимите `versionCode` / `CURRENT_PROJECT_VERSION` отдельным PR и запускайте доставку с новым SHA |
| `MISMATCH` | файл, отчёт или ответ магазина не совпадает с проверенным артефактом. Доставка остановлена, в Play ничего не закоммичено. Разберите отчёт; не перезапускайте вслепую |
| Запись старше 90 дней истекла | повторный запуск того же номера получит `CONFLICT` (fail-closed). Сверьте вручную в консоли и поднимите номер |
| Play: «Changes cannot be sent for review automatically» | `PLAY_CHANGES_NOT_SENT_FOR_REVIEW=true` в `google-play`, повторить; затем отправьте изменения на проверку в консоли |
| ITMS-письмо, обработка `INVALID` | номер израсходован; исправление — новым PR с новым номером |
| Ограничение iOS-идентичности | App Store Connect не отдаёт хэш загруженного файла. Сборка с номером в ASC считается «своей», только если запуск этого SHA сохранил запись `upload-started` **не позже**, чем ASC зафиксировал загрузку. Если запуск упал до загрузки, а затем кто-то загрузил тот же номер вручную, workflow примет её за свою: не загружайте вручную номер, который уже пробовал загрузить workflow |
| `mode=verify` для уже доставленной версии | если номер уже доставлен этим workflow из того же SHA, verify сообщает `ALREADY_DELIVERED`/`ALREADY_UPLOADED` и не пересобирает: проверка подписи и credentials на такой версии ничего не докажет. Для проверки credentials используйте ещё не загруженный номер |
| Запуск отменён во время `ios-upload` | запись `upload-started` уже сохранена: следующий запуск того же SHA и номера распознает сборку, если App Store Connect её принял; иначе загрузит заново |

Signing-материал удаляется шагами `if: always()` и при ошибке: ключи Android, временная ключница и профиль iOS, `.p8`. GitHub-hosted runner к тому же одноразовый. `security import` получает пароль `.p12` аргументом командной строки (другого способа у `security` нет): на одноразовом runner он виден только процессам этого job, в логах маскируется.

Actions (`actions/*`, `gradle/actions`) закреплены по тегам мажорной версии, как и в остальных workflow репозитория; зависимости Python ставятся по диапазонам из `pyproject.toml`. Закрепление по SHA коммита и lock-файл для jobs с секретами — рекомендуемый следующий шаг усиления.

## 8. Ротация и отзыв credentials 🔑

| Credential | Ротация | Утечка |
|---|---|---|
| `PLAY_SERVICE_ACCOUNT_JSON` | новый ключ service account → обновить секрет → удалить старый ключ в Cloud Console | удалить ключ; при необходимости убрать аккаунт из Play Console |
| Upload-ключ Play | Play Console → *Request upload key reset* ([google-play/signing §7](google-play/signing.md#7-losing-or-leaking-a-key)) → обновить `ANDROID_UPLOAD_*` | то же |
| Ключ приложения (`talentforge-release.p12`) | **не ротируется** для RuStore/AppGallery: потеря или утечка прекращает обновления на месте ([multi-store-signing §10](appgallery/multi-store-signing.md#10-consequences-of-losing-or-leaking-the-signing-key)). Это главный риск подписи APK в CI: держите `android-signing` под правилом `main`, по желанию — с required reviewer | удалить секрет, действовать по multi-store-signing §10 |
| `ASC_API_*` | новый Team key → обновить три секрета → *Revoke* старого | *Revoke* немедленно |
| Apple Distribution `.p12` и профиль | сертификат действует год: новый сертификат (§4.3) → новый профиль → обновить три секрета → после успешного `verify` отозвать старый | *Revoke* сертификата (уже загруженные сборки не затрагиваются) |

После любой ротации — запуск `mode=verify`.

## 9. Что проверено и что остаётся владельцу

Проверено в PR без магазинов: тесты оркестрации с фейковыми GitHub/Play/App Store Connect API (отказ gate, CI не того SHA, отсутствие credentials, конфликт версии, повторный запуск, частичный успех, расхождение артефактов, итоговая таблица); тесты `verify_ios_archive.py --ipa` и `ota_production_preflight.py --min-validity-days`; actionlint и shellcheck; проверка OTA ON маркеров `android-check` на настоящих unsigned `assembleRelease`/`bundlePlay` (OTA ON проходит, OTA OFF отклоняется). Это **не** доказательство реальной доставки.

Android Lint: `lintVitalAnalyzeRelease`/`lintVitalAnalyzePlay` внутри release-сборки печатают «Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.4.0, expected version is 2.2.0» — встроенный в AGP 8.13.2 анализатор lint старше Kotlin 2.4.20 проекта и видит типы KMP-модулей неполностью (так же, как в [TF-RC1-LINT](rc1-defects.md#tf-rc1-lint--composeapplintrelease-fails-on-rememberreturntype)). Задачи `lintVital*` при этом проходят, сборка успешна; lint не отключён и не подавлен, ошибка lint остановит доставку. Полное устранение — обновление AGP/lint отдельной задачей.

Остаётся владельцу:

- [ ] Environments, secrets, variables (§3), service account и права в Play (§4.1), ключ App Store Connect (§4.2), сертификат и профиль (§4.3), ruleset для тегов `v*`.
- [x] PR с `CURRENT_PROJECT_VERSION ≥ 4` (iOS) до первой iOS-доставки: `1.0.0 (4)`, ветка `build/ios-build-4-testflight`.
- [ ] Первый запуск `mode=verify` → summary `OK`.
- [ ] Первый запуск `mode=deliver` → сборка видна в Play Console (закрытый трек, тот же SHA-256) и в TestFlight (внутренняя группа) → установка тестерами.
- [ ] Записать коммит и SHA-256 в [журнал версий](android-versioning.md#3-planned-sequence-and-ledger).
- [ ] Только после этого закрывать issue #90. RuStore/AppGallery — следующий этап.
