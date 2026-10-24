# M5.IOS.3 — TestFlight и отправка в App Store: runbook владельца 🔑

| | |
|---|---|
| Для кого | только владелец. У агента нет Apple Developer-команды, сертификатов, физического iPhone и доступа к App Store Connect: ни один шаг ниже агент **не выполнял** |
| Сборка | TalentForge **1.0.0 (1)**, bundle id `io.github.alexeykravtsov95.talentforge`, iOS 17.2+, iPhone и iPad, **OTA ON** (production `https://ota.mythweld.com`) |
| Инструменты | macOS Tahoe 26.6+, **Xcode 27.1 (27A9275)** stable — не 27.2 RC/beta |
| Нельзя | коммитить Team ID, сертификаты, профили, ключи App Store Connect API, пароли; загружать сборку, не прошедшую шаг 4; отправлять на ревью до закрытия BLOCKED-пунктов [пакета](README.md#4-статус) |
| Связанные документы | [пакет отправки](README.md) · [owner-guide OTA ON](../m5-ios-2-owner-guide.md) · [App Privacy](../app-privacy.md) · [iOS-политика](../../../legal/ios/README.md) |

Каждый шаг заканчивается проверкой. Если проверка не прошла — остановитесь, не переходите к следующему шагу.

## 1. Apple Developer Program и App Store Connect

1. Членство в [Apple Developer Program](https://developer.apple.com/programs/) активно (Account → Membership). Для
   бесплатного приложения отдельный договор Paid Apps не нужен, но Account Holder должен принять актуальное Apple
   Developer Program License Agreement (баннер в Account и в App Store Connect → Business).
2. В App Store Connect → Apps → «+» → **New App**:

   | Поле | Значение |
   |---|---|
   | Platforms | iOS |
   | Name | `TalentForge` ([name.txt](metadata/en-US/name.txt)). Если имя занято, App Store Connect не даст создать запись: это решение владельца, без нового имени не продолжать |
   | Primary Language | English (U.S.) |
   | Bundle ID | `io.github.alexeykravtsov95.talentforge` (появится в списке после шага 2.1) |
   | SKU | любой внутренний идентификатор, например `talentforge-ios` |
   | User Access | Full Access |

3. Добавьте локализацию **Russian** (App Information → язык рядом с English (U.S.) → Russian).

Проверка: запись приложения есть, обе локализации (English (U.S.), Russian) видны.

## 2. Certificates, Identifiers & Profiles и Team

Приложение не использует ни одной capability (push, iCloud, App Groups, Sign in with Apple, Associated Domains):
дополнительные entitlements не нужны.

1. [Identifiers](https://developer.apple.com/account/resources/identifiers/list) → «+» → App IDs → App → Explicit
   Bundle ID `io.github.alexeykravtsov95.talentforge`, Description `TalentForge`, capabilities не включать. (Xcode с
   Automatic signing создаст его сам при первом архиве — тогда проверьте, что capabilities пусты.)
2. Сертификаты и профили создаёт Xcode (Automatic signing): Apple Development для архива, Apple Distribution и
   App Store-профиль при выгрузке. Вручную ничего не скачивайте и ничего из этого не кладите в репозиторий.
3. Team ID (10 символов, Account → Membership details) передавайте **только в командной строке** (`DEVELOPMENT_TEAM=…`,
   шаг 3). Если выбрать Team в Xcode → Signing & Capabilities, Xcode запишет `DEVELOPMENT_TEAM` в
   `project.pbxproj`; такое изменение **не коммитьте**:

   ```bash
   git checkout -- mobile/iosApp/iosApp.xcodeproj/project.pbxproj
   ```

Проверка — конфигурация проекта перед сборкой (без сети, только чтение):

```bash
python3 scripts/verify_ios_project.py --expected-version 1.0.0 --expected-build 1
```

Ожидается `RESULT: PASS` (14 проверок): bundle id, `1.0.0 (1)` в Debug и Release, iOS 17.2, iPhone + iPad, Automatic
signing **без** закоммиченных team/profile/identity, архив — Release, `PrivacyInfo.xcprivacy` в ресурсах,
`ITSAppUsesNonExemptEncryption = false`, ATS по умолчанию, ни одного разрешения, одна сцена, en/ru, 4 ориентации iPad.
Если вы выбрали Team в Xcode и не откатили `project.pbxproj`, проверка `signing` даст FAIL — так и задумано.

**Build number.** `1` — если в App Store Connect ещё **не было ни одной** загрузки. Любая загрузка (даже отклонённая
при обработке) расходует номер: следующая сборка — `2`, отдельным PR ([readiness §2](../readiness.md#2-конфигурация-release-сборки)).

## 3. Подписанный Archive (OTA ON)

Перед сборкой:

- [ ] `xcodebuild -version` → `Xcode 27.1`, `Build version 27A9275`.
- [ ] Рабочая копия — `main` после merge этого PR, `git status` чистый.
- [ ] Production preflight (только чтение) → `result: OK`, и до `expiresAt` **не меньше 30 дней** (правило проекта,
      [owner-guide §3](../m5-ios-2-owner-guide.md#3-перед-сборкой-ota-on)):

  ```bash
  .venv/bin/python scripts/ota_production_preflight.py --expect published --keyring docs/ota/keys/production-keyring.json
  ```

  На 2026-10-24 каталог действует до **2026-12-05T08:31:31Z** (41 день): без re-sign загружать не позже **2026-11-05**.
- [ ] App Privacy выбран (вариант A или B, [app-privacy §3](../app-privacy.md#3-сборка-ota-on-m5ios2)). Для варианта B
      сначала нужен отдельный PR с `PrivacyInfo.xcprivacy` — иначе архив не пройдёт шаг 4 с `other-diagnostic`.
- [ ] В окружении нет `TALENTFORGE_OTA_ANDROID_*` и staging-настроек ([owner-guide §2](../m5-ios-2-owner-guide.md#2-как-включить-и-выключить)).

Сборка (замените `ABCDE12345` своим Team ID; значение остаётся только в истории терминала):

```bash
export TALENTFORGE_OTA_IOS_PRODUCTION_ENABLED=true
```

```bash
cd mobile/iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS' -archivePath build/TalentForge-1.0.0-1-ota-on.xcarchive -allowProvisioningUpdates DEVELOPMENT_TEAM=ABCDE12345 archive
```

Ожидается `** ARCHIVE SUCCEEDED **`. Каталог `mobile/iosApp/build/` в `.gitignore`.

## 4. Проверка архива: `verify_ios_archive.py --ota on`

Из корня репозитория, тем же архивом:

```bash
python3 scripts/verify_ios_archive.py --archive mobile/iosApp/build/TalentForge-1.0.0-1-ota-on.xcarchive --ota on --require-signed --expected-team-id ABCDE12345 --expected-version 1.0.0 --expected-build 1 --expected-sdk iphoneos27.1 --expected-xcode-build 27A9275 --report mobile/iosApp/build/ios-archive-1.0.0-1-report.json
```

Для варианта App Privacy B добавьте `--privacy-collected other-diagnostic`.

Ожидается `RESULT: PASS`, в том числе:

| Проверка | Значение |
|---|---|
| `signature-valid` | `codesign --verify --strict --verbose=2` принимает `.app` (подпись не повреждена) |
| `signature-identity` | по фактической подписи (`codesign -d --verbose=4`): identity — Apple Development или Apple Distribution, Team ID = `--expected-team-id`, идентификатор — `io.github.alexeykravtsov95.talentforge`; ad-hoc подпись — FAIL |
| `provisioning-profile` | `security cms -D` декодирует `embedded.mobileprovision`: профиль действует сейчас, той же команды, ровно `<Team ID>.io.github.alexeykravtsov95.talentforge` (без `*`), не enterprise |
| `profile-certificate` | сертификат подписи (`codesign --extract-certificates`) входит в `DeveloperCertificates` профиля |
| `testflight-signing` | конфигурация годится для TestFlight: development-подпись архива (Organizer переподпишет её Apple Distribution при Distribute App → App Store Connect) или App Store-профиль с Apple Distribution. Ad Hoc-профиль и `get-task-allow` при distribution — FAIL; entitlements подписи совпадают с профилем |
| `version`, `toolchain` | `1.0.0` / `1`, `iphoneos27.1`, `27A9275` |
| `ota-on` | origin `https://ota.mythweld.com`, ровно ключи `tf-prod-2026a`, `tf-prod-2026b` |
| `ota-no-staging-origin`, `ats-default`, `privacy-*`, `no-secrets` | PASS |

`--expected-team-id` — ваш Team ID (10 символов), только в командной строке; в git его нет. Проверка подписи
работает только на macOS: без `codesign` или `security` скрипт завершается с кодом 2 (`ERROR: BLOCKED …`), а не PASS.

Любой FAIL — архив не загружать. Отчёт (JSON) сохраните: в `signing` записаны identity, Team ID, профиль и срок его
действия, а SHA-256 исполняемого файла связывает проверенный архив с загруженной сборкой.

## 5. Загрузка в TestFlight

1. Xcode → Window → **Organizer** → Archives → архив из шага 3 (тот же, что проверен в шаге 4) → **Distribute App** →
   **App Store Connect** → Upload. Подпись — автоматическая; «Upload your app's symbols» оставить включённым.
2. Шифрование: `ITSAppUsesNonExemptEncryption = false` уже в `Info.plist`, поэтому вопрос «Missing Compliance» не
   появится. Если появится — ответ «None of the algorithms mentioned above» ([app-privacy §4](../app-privacy.md#4-связанные-декларации)).
3. Дождитесь обработки (App Store Connect → TestFlight → iOS Builds). Если пришло письмо ITMS-9xxxx — остановитесь,
   перешлите текст письма в issue, сборку на ревью не отправляйте.
4. TestFlight → **Internal Testing** → группа с вашим Apple ID → добавить сборку 1.0.0 (1). Внутреннее тестирование не
   требует Beta App Review. Внешних тестировщиков в этом релизе не добавлять.

Проверка: сборка `1.0.0 (1)` есть в TestFlight, предупреждений о privacy manifest и Required Reason API
(ITMS-91053/91061) нет.

## 6. Проверка на физическом iPhone

Установите TestFlight на iPhone (iOS 17.2+), примите приглашение, установите 1.0.0 (1). Для каждого шага записывайте
`PASS` / `FAIL` / `NOT RUN`, время (UTC), модель и версию iOS.

1. OTA ON на устройстве: **P-1…P-13** из [owner-guide §5](../m5-ios-2-owner-guide.md#5-проверка-на-физическом-iphone-testflight--план)
   (авиарежим, первая загрузка, фон и закрытие во время загрузки, перезагрузка, сохранность билдов, сотовые данные,
   IPv6/NAT64, переустановка, хранилище).
2. Основные сценарии [readiness §4.2](../readiness.md#42-сценарии-release-сборка-unsigned-симулятор-ota-off): выбор
   класса, tap выбирает и не тратит, `+`/`−`/`Max`, «Показать», pinch/pan, поворот, переименование, «Поделиться»
   (системное меню), «Копировать код», импорт с предпросмотром, About (версия 1.0.0, «Проверить обновления»,
   «Политика конфиденциальности» открывает Safari на `https://legal.mythweld.com/privacy-ios.html` в RU-интерфейсе
   и на `https://legal.mythweld.com/privacy-ios-en.html` в EN; приложение не открывает WebView).
3. RU и EN (Настройки → TalentForge → Язык); VoiceOver — короткий проход по Home и редактору (в симуляторе не
   проверялся).
4. Если есть iPad — запуск, дерево и панель инспектора, портрет и ландшафт.

Любой `FAIL` в P-1…P-8 или в основных сценариях — сборку на ревью не отправлять. Результаты занесите в
[readiness M5.IOS.3 §8](../m5-ios-3-readiness.md#8-физическое-устройство-и-testflight).

## 7. Карточка App Store и отправка на ревью

Все тексты берутся из [пакета](README.md#2-поля-app-store-connect--файлы): каждый файл копируется в своё поле целиком.
Перед вводом:

```bash
python3 scripts/verify_appstore_metadata.py
```

```bash
python3 scripts/verify_appstore_screenshots.py
```

Оба → `RESULT: PASS`.

1. **App Information**: Subtitle (EN/RU), Category (Primary/Secondary), Content Rights, Age Rating — [README §3](README.md#3-ответы-анкет-app-store-connect-решает-владелец).
2. **Pricing and Availability**: Free; страны — решение владельца. Для ЕС App Store Connect требует указать статус по
   DSA (trader / non-trader) — решение владельца.
3. **App Privacy**: Privacy Policy URL (EN — `…/privacy-ios-en.html`, RU — `…/privacy-ios.html`) **только после**
   `python3 scripts/ios_privacy_pages.py verify-live --date <дата публикации>` → PASS; ответы — вариант A или B
   ([app-privacy §3](../app-privacy.md#3-сборка-ota-on-m5ios2)), тот же, что в `PrivacyInfo.xcprivacy` архива.
4. **Версия 1.0** (iOS App → 1.0 Prepare for Submission), для каждой локализации: скриншоты iPhone 6.3" и iPad 13"
   в порядке номеров файлов, Promotional Text, Description, Keywords, Support URL. Поля What's New у первой версии нет.
   Build → 1.0.0 (1) из шага 5. Copyright — `copyright.txt`.
5. **App Review Information**: Sign-in required — **выключено**; Notes — `review_information/notes.txt`; контакт
   (имя, телефон, e-mail) — ваши данные, они вводятся только в App Store Connect.
6. **Version Release**: «Manually release this version» (рекомендуется: выпуск после одобрения — отдельное решение).
7. **Add for Review** → **Submit to App Review** — только когда в [README §4](README.md#4-статус) не осталось BLOCKED.

Агент этот шаг не выполняет и приложение не публикует.
