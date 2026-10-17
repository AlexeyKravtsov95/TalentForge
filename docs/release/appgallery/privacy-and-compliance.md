# M5.GALLERY.1 — Privacy, intellectual property and compliance

| | |
|---|---|
| Milestone | M5.GALLERY.1 (preparation; no product change) |
| Evidence base | `main` @ `f7494e1`, static inspection by [`evidence/run-gallery1-checks.sh`](evidence/README.md) |
| Related | [rc1-defects — owner/legal items LR-1…LR-5](../rc1-defects.md#owner--legal-review-items-not-defects-must-be-decided-before-publication) · [testing-and-publication](testing-and-publication.md) · [listing EN](store-listing-en.md) / [RU](store-listing-ru.md) |

> **The legal gate is NOT passed.** This document separates what the repository and the built
> package **prove** from what needs an **owner or legal decision**. It is not legal advice and
> grants no clearance. Publicly accessible game files are not assumed to be licensed for
> redistribution.

## 1. Summary

| Question | Answer | Basis |
|---|---|---|
| Does the app use the network? | **No.** The package requests no `INTERNET` permission, so Android does not let it open network sockets. No HTTP client library is a dependency. | Verified (static), §2 |
| Analytics, ads, crash reporting, tracking? | **None found.** No analytics/ads/crash SDK among the release runtime modules; no Google Play services, Firebase, Play Billing, Play Core or Huawei HMS/AGConnect code or configuration files. | Verified (static), §2 |
| What is stored, and where? | The build library (talent builds, optional user-typed names, timestamps) in app-private `SharedPreferences` on the device. | Verified (source), §3 |
| What leaves the device? | Only what the user sends: Share (system share sheet) or Copy (clipboard). Android backup or device transfer may copy app data if the user enabled them. | Verified (source/manifest); backup on Huawei devices UNVERIFIED |
| Personal data collected by the developer? | **None.** The developer receives nothing from the app. | Verified (static) |
| Privacy policy needed? | Depends on the store and on owner decisions; drafts are in §8 | §5 |
| Rights to Blizzard-derived content? | **Not established.** LR-1…LR-3 remain open. | §7 |
| OSS attribution? | Not present in the app or the repository. LR-4 remains open. | §7 |

## 2. Verified: network, analytics and Google/Huawei services (static)

From `run-gallery1-checks.sh` on `f7494e1` (results: [readiness §7](readiness.md#7-validation-results);
raw files: [`evidence/mac/`](evidence/mac/)):

| Check | Result |
|---|---|
| `uses-permission` in the release manifest | only `io.github.alexeykravtsov95.talentforge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, the app's own signature-level permission added by AndroidX. **No `INTERNET`**; no location, contacts, storage, camera, microphone, phone or notification permission |
| `usesCleartextTraffic` / `networkSecurityConfig` | unset / none (irrelevant without `INTERNET`) |
| Release runtime dependencies (`releaseRuntimeClasspath`) | AndroidX (activity, compose, core, emoji2, lifecycle, profileinstaller, savedstate, startup, window, …), JetBrains Compose Multiplatform, Kotlin stdlib, kotlinx-coroutines, kotlinx-serialization, `com.google.guava:listenablefuture` (a compatibility artifact, not Google Play services), annotations. **No** `com.google.android.gms`, `com.google.firebase`, `com.android.billingclient`, `com.google.android.play`, `com.huawei.*`, OkHttp, Ktor or Retrofit |
| Class-name references in the dex files | none for `com/google/android/gms`, `com/google/firebase`, `com/android/billingclient`, `com/google/android/play/core`, `com/huawei/hms`, `com/huawei/agconnect`, OkHttp, Ktor or `HttpURLConnection`. `java.net.Socket` and `android.webkit.WebView` are **referenced by library code** (AndroidX/Compose); a reference is not a use, and without `INTERNET` the OS refuses network sockets |
| `google-services.json` / `agconnect-services.json` | absent |
| Exported components | the launcher `MainActivity`; AndroidX `ProfileInstallReceiver` (standard component, guarded by the `android.permission.DUMP` permission) |
| Start-up initializers (`androidx.startup`) | `EmojiCompatInitializer` (emoji2), `ProcessLifecycleInitializer`, `ProfileInstallerInitializer`; plus the Compose resources `AndroidContextProvider`. All are local; none requires Google services. Emoji2 looks for an on-device font provider; its behaviour on a device without Google services is **UNVERIFIED** (no Huawei device) |
| Optional libraries | `androidx.window.extensions`, `androidx.window.sidecar`, both `required=false` |

**Conclusion for Huawei compatibility:** no mandatory Google Mobile Services dependency, no Firebase,
no Google Play Billing, no HMS. Core functionality (offline data, local storage, clipboard,
`ACTION_SEND`) uses only Android framework APIs. Runtime confirmation on a Huawei device is
**UNVERIFIED** ([testing-and-publication §5](testing-and-publication.md#5-device-test-plan)).

## 3. Verified: local storage

| Item | Detail | Source |
|---|---|---|
| Store | Android `SharedPreferences` file `talentforge_build_library`, `MODE_PRIVATE`, one string value | `AndroidBuildLibraryStorage.kt` |
| Content | per build: a local id, an optional **name typed by the user**, `createdAt` / `updatedAt` / `lastOpenedAt` (epoch ms), and the build itself (game, client build, class, talent allocation) | `LocalBuildLibrary.kt` (`LocalBuildRecord`) |
| Other app data | the bundled, read-only talent datasets, icons and texts inside the APK | `composeResources/files` |
| Accounts, identifiers | none: no account, no advertising ID, no device identifier read | static inspection |
| Deletion | delete a build in the app (All Builds → ⋯ → Delete, Home → ⋯ → Delete); clear the app's storage or uninstall it to delete everything | app behaviour |
| Network copy | none made by the app | §2 |

The user-typed build name is free text. It stays on the device and is never put into a share code
([build-sharing.md](../../build-sharing.md): a code carries no personal data and never a name).

## 4. Verified: backup, clipboard, sharing

| Topic | Fact | Status |
|---|---|---|
| Android backup | `android:allowBackup` is **unset**, so the platform default (`true`) applies, with no `fullBackupContent` / `dataExtractionRules`. Android Auto Backup and device-to-device transfer can therefore include the `SharedPreferences` library when the user has backup enabled. The backup is handled by the device's backup service provider, never by the developer. The explicit setting is the open owner decision **TF-RC1-ANDROID-CONFIG**; the privacy text must match whatever is decided. | Verified (manifest); behaviour on Huawei devices (Huawei backup / Phone Clone) **UNVERIFIED** (H-12) |
| Clipboard: write | only when the user taps **Copy code**: one plain-text clip, label "TalentForge build code", content = the share code | Verified (source) |
| Clipboard: read | only when the user taps **Paste** on the Import screen; reads the first item's plain text | Verified (source: `ImportBuildScreen.kt`) |
| Share | only when the user taps **Share**: `Intent.ACTION_SEND`, `text/plain`, wrapped in the system chooser. The app does not learn which app is chosen or whether anything was sent. | Verified (source) |
| Import | the pasted text is decoded locally, limited to 4 096 characters, never executed or fetched | Verified (source/docs) |

## 5. Store privacy declarations

| Store | Requirement (official) | Proposed answer | Decision |
|---|---|---|---|
| AppGallery: privacy tags | "If your app does not collect user data, set Collect personal data to No." | **No** | owner confirms |
| AppGallery: privacy policy URL | Labelled mandatory; described as required "if your app needs to collect and process user information"; shown on the details page | Publish the §8 policy at a public URL anyway (avoids a rejection over an ambiguous field) | **owner** (hosting, identity, contact) |
| AppGallery: Review Guidelines 7.1 | Apps that process personal data need an easy-to-find **in-app** link to the policy as well as the AGC link | TalentForge's developer processes no personal data; whether reviewers still expect an in-app link is uncertain. An in-app About/legal surface is already proposed for M5.RC.2b (LR-2, LR-4) and could carry the link. | **owner/legal**; implementation would be a separate UI change |
| AppGallery: permission descriptions | Only for sensitive permissions | none needed | — |
| RuStore: moderation rule 5.1 | Apps that collect or process personal data need a privacy policy **in Russian**, available from the store card **and inside the app** | as above; the Russian draft is §8.1 | **owner/legal** |
| RuStore: Data Safety form | (rc1 checklist §6) | no data collected or shared; local storage only; backup per the `allowBackup` decision | owner fills it in 🔑 |

## 6. Content facts for age-rating questionnaires

Facts only; the answers and the resulting ratings are the owner's decision in each console.

- Content: talent trees of a fantasy role-playing game. Talent names and descriptions are game text
  that refers to combat abilities (damage, weapons, spells, healing); small ability icons; one
  artwork per class.
- No user-to-user communication inside the app; nothing user-generated is visible to others (a build
  name is visible only on the device that stores it; share codes contain no free text).
- No ads, no purchases, no gambling or simulated gambling, no location, no web content, no links.
- No account and no data collection by the developer.

## 7. Intellectual property and trademarks

### 7.1 Status of LR-1 … LR-5 (none is closed by this milestone)

| ID | Item (from M5.RC.1) | What M5.GALLERY.1 adds | Status |
|---|---|---|---|
| LR-1 | Talent icons are converted Blizzard client textures; talent names and descriptions are Blizzard game text ([talent-visuals.md](../../talent-visuals.md) states the icons "remain the intellectual property of Blizzard Entertainment") | Both stores require rights to third-party copyrighted content: AppGallery Review Guidelines 9.1 ("authorized to use any patented or copyrighted content of others"); RuStore rule 4.1 (the developer must hold the rights and can be asked for documents). Store **screenshots** of the editor show this content too. | **OPEN: legal review** |
| LR-2 | "World of Warcraft", "Forever" and class names; no non-affiliation disclaimer; no in-app legal surface | AppGallery 1.15 / 9.5 (no implied relationship with a third party); RuStore 6.4 forbids third-party trademarks and names in the app name **and descriptions**. The listing drafts therefore use a trademark-free variant by default and a nominative variant gated on this review. The in-app UI shows "Forever" in the data-version line. | **OPEN: legal review + owner decision** |
| LR-3 | Provenance and licence of the 9 class artwork masters (`composeResources/drawable/class_*.png`) not recorded | Still not recorded. Store screenshots of class selection show them. | **OPEN: owner** |
| LR-4 | No OSS notices; no `LICENSE` in the repository | Release dependencies are Apache-2.0 licensed (AndroidX, Compose, Kotlin, kotlinx, `listenablefuture`, annotations). Apache-2.0 §4 asks redistributors to give recipients a copy of the licence and to keep applicable NOTICE texts; how TalentForge does this (in-app notices screen, bundled text) is not decided. Neither store prescribes a form. | **OPEN: owner/legal** |
| LR-5 | Privacy forms and policy URL | Verified facts (§2–§4), the store mapping (§5) and RU/EN drafts (§8) are prepared | **OPEN: owner** — identity, contact, hosting URL, legal review of the text |

### 7.2 What is *not* claimed

- That talent icons, names, descriptions or class artwork may be redistributed because they are
  accessible in the game client or on community sites. They are not assumed to be licensed.
- That a disclaimer makes the use lawful. A disclaimer only addresses confusion about affiliation
  (LR-2); it grants no copyright permission (LR-1).
- That store reviewers will or will not accept the current content.

### 7.3 Non-affiliation disclaimer (draft wording for legal review)

Trademark-free form (compatible with RuStore rule 6.4; usable in the listing and later in the app):

- RU: «TalentForge — независимый неофициальный проект. Он не связан с разработчиком и издателем игры, не одобрен и не поддерживается ими.»
- EN: "TalentForge is an independent, unofficial project. It is not affiliated with, endorsed by or sponsored by the game's developer or publisher."

Nominative form (only if legal review approves naming the marks):

- RU: «TalentForge — независимый неофициальный проект, не связанный с Blizzard Entertainment. World of Warcraft и Blizzard Entertainment являются товарными знаками или зарегистрированными товарными знаками Blizzard Entertainment, Inc. в США и/или других странах.»
- EN: "TalentForge is an independent, unofficial project and is not affiliated with Blizzard Entertainment. World of Warcraft and Blizzard Entertainment are trademarks or registered trademarks of Blizzard Entertainment, Inc. in the U.S. and/or other countries."

### 7.4 Brand assets

The app icon and the 512×512 store icon come from the owner-approved M5.RC.2a package
([rc2a-evidence](../rc2a-evidence/README.md)): original TalentForge artwork with no Blizzard logos.
The concept was AI-assisted; the M5.RC.1 icon policy required a rights review (originality, tool
terms) before integration. Whether that review was recorded is an owner item; it also feeds the
AppGallery AI declaration ([testing-and-publication §2.7](testing-and-publication.md#27-content-rating-ai-declaration-copyright-payment)).

## 8. Draft privacy policy

Status: **DRAFT for owner and legal review.** Fields in `⟨…⟩` are owner decisions. Before
publication, align the backup paragraph with the final `allowBackup` decision and re-check the text
against the shipped build.

### 8.1 Русская версия

> **Политика конфиденциальности приложения TalentForge**
>
> Дата вступления в силу: ⟨дата⟩. Правообладатель приложения: ⟨имя или наименование владельца⟩. Контакт: ⟨адрес электронной почты⟩.
>
> **1. Коротко.** TalentForge работает без интернета, без учётной записи, без рекламы и без аналитики. Мы не собираем, не получаем, не храним на своих серверах и не передаём третьим лицам ваши персональные данные. У нас нет серверов, на которые приложение что-либо отправляет.
>
> **2. Какие данные хранятся на устройстве.** Приложение сохраняет на вашем устройстве созданные вами билды: класс, распределение талантов, версию игровых данных, название билда (если вы его ввели), а также время создания, изменения и последнего открытия. Эти данные находятся в закрытом хранилище приложения, и приложение никуда их не отправляет. Не указывайте в названии билда сведения о себе или о других людях.
>
> **3. Резервные копии.** Если на устройстве включено резервное копирование или перенос данных на новое устройство, операционная система или сервис резервного копирования производителя устройства может включить данные приложения в резервную копию. Такие копии создаёт и хранит этот сервис по своим правилам; у нас нет к ним доступа. ⟨Абзац уточняется после решения TF-RC1-ANDROID-CONFIG об `allowBackup`.⟩
>
> **4. Буфер обмена.** Приложение помещает код билда в буфер обмена только после нажатия «Копировать код» и читает буфер обмена только после нажатия «Вставить» на экране импорта.
>
> **5. Отправка кода билда.** Когда вы нажимаете «Поделиться», приложение передаёт текст кода билда в системное меню «Поделиться». Получателя выбираете вы, а дальнейшая обработка зависит от выбранного приложения и его правил. Код содержит только билд (класс, версию данных и таланты) — без названия билда и без сведений о вас.
>
> **6. Разрешения.** Приложение не запрашивает доступ к интернету, местоположению, контактам, камере, микрофону, файлам или уведомлениям.
>
> **7. Магазины приложений.** Магазин, из которого вы установили приложение, может обрабатывать данные о загрузке и установке по своим правилам. Мы не получаем от магазинов персональные данные пользователей ⟨уточнить по консолям магазинов⟩.
>
> **8. Удаление данных.** Удалите билд в приложении либо удалите само приложение (или очистите его данные в настройках устройства) — тогда все сохранённые билды будут удалены с устройства.
>
> **9. Дети.** Приложение не предназначено специально для детей и не собирает данные детей.
>
> **10. Изменения.** Новая редакция политики публикуется по этому адресу с указанием даты вступления в силу.
>
> **11. Контакты.** Вопросы о конфиденциальности: ⟨адрес электронной почты⟩.

### 8.2 English version

> **TalentForge Privacy Policy**
>
> Effective date: ⟨date⟩. App owner: ⟨owner name⟩. Contact: ⟨e-mail address⟩.
>
> **1. In short.** TalentForge works offline, without an account, ads or analytics. We do not collect, receive, store on our servers or share your personal data. We have no servers that the app sends anything to.
>
> **2. Data stored on your device.** The app stores the builds you create on your device: the class, the talent allocation, the game-data version, the build name (if you entered one) and when the build was created, changed and last opened. This data stays in the app's private storage, and the app does not send it anywhere. Please do not put information about yourself or other people in a build name.
>
> **3. Backups.** If device backup or transfer to a new device is enabled, the operating system or your device manufacturer's backup service may include the app's data in a backup. That service creates and keeps such backups under its own terms; we have no access to them. ⟨Paragraph to be finalised after the TF-RC1-ANDROID-CONFIG `allowBackup` decision.⟩
>
> **4. Clipboard.** The app puts a build code on the clipboard only when you tap "Copy code", and reads the clipboard only when you tap "Paste" on the import screen.
>
> **5. Sharing a build code.** When you tap "Share", the app hands the build code text to the system share menu. You choose the recipient; what happens next depends on the app you pick and its terms. A code contains only the build (class, data version and talents) — no build name and no information about you.
>
> **6. Permissions.** The app does not request access to the internet, location, contacts, camera, microphone, files or notifications.
>
> **7. App stores.** The store you installed the app from may process download and installation data under its own policy. We do not receive users' personal data from the stores ⟨confirm against both consoles⟩.
>
> **8. Deleting data.** Delete a build in the app, or uninstall the app (or clear its storage in the device settings), to delete all saved builds from the device.
>
> **9. Children.** The app is not directed at children and does not collect children's data.
>
> **10. Changes.** A new version of this policy is published at this address with its effective date.
>
> **11. Contact.** Privacy questions: ⟨e-mail address⟩.

## 9. Owner and legal actions

| # | Action | Blocks |
|---|---|---|
| P-1 | Legal review of LR-1 (game icons and text) for distribution through RuStore and AppGallery, including store screenshots | publication |
| P-2 | Decide the trademark and disclaimer approach (LR-2): trademark-free listing vs nominative mention; in-app disclaimer | listing copy, publication |
| P-3 | Record the provenance and licence of the class artwork (LR-3) | publication |
| P-4 | Decide the OSS-notice mechanism (LR-4) | publication |
| P-5 | Decide `allowBackup` (TF-RC1-ANDROID-CONFIG) and finalise §8 accordingly | privacy text |
| P-6 | Fill in the ⟨…⟩ fields, review §8 and host it at a public URL (RU and EN) | AppGallery and RuStore forms |
| P-7 | Decide whether an in-app privacy/legal link is needed now (AppGallery 7.1, RuStore 5.1); if yes, schedule it as its own UI change | possibly publication |
| P-8 | Answer the age-rating questionnaires and the AppGallery AI declaration from the facts in §6 and §7.4 🔑 | submission |
