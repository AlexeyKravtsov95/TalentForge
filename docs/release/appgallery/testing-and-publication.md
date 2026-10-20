# M5.GALLERY.1 — AppGallery requirements, device testing and publication runbook

| | |
|---|---|
| Milestone | M5.GALLERY.1 (preparation only: **nothing was uploaded, submitted or published**) |
| Package | Android APK `io.github.alexeykravtsov95.talentforge` for Huawei AppGallery (and RuStore) |
| Not in scope | HarmonyOS NEXT / HarmonyOS 5+ native apps, ArkTS, DevEco Studio, HAP/APP packaging, HMS Core SDKs, Google Play (#35) |
| Sources checked | 2026-10-17 ([§7](#7-sources)) |
| Related | [readiness](readiness.md) · [signing](multi-store-signing.md) · [assets](assets-checklist.md) · [privacy](privacy-and-compliance.md) · [listing EN](store-listing-en.md) / [RU](store-listing-ru.md) |

Legend: 🔑 = needs the owner's **Huawei Developer** account (or RuStore Console account) ·
**UNVERIFIED** = not executed or not observable without a device or account.

## 1. Android app vs HarmonyOS app — which rules apply

AppGallery Connect (AGC) documents several package types side by side. TalentForge is an
**Android APK**; only the APK/Android rules below apply. Rules for other platforms were not used to
infer anything.

| Topic | Applies to TalentForge (Android APK) | Does **not** apply (other platforms) |
|---|---|---|
| Release guide | [Releasing an App (APK)](https://developer.huawei.com/consumer/en/doc/app/agc-help-releaseapkrpk-0000001106463276) | "Releasing an App (APP)" — HarmonyOS apps/FAs; RPK — quick apps |
| Visual assets | "APKs → Specifications for mobile app visual assets" | "HarmonyOS Apps" (icon PNG ≤ 500 KB, 3–5 screenshots 1280×720 / 720×1280), atomic services, watch, Vision, router, PC, quick apps |
| Package | APK (or AAB with App Signing) | APP/HAP built with DevEco Studio |
| Signing | Android APK signature; App Signing optional for APK | HarmonyOS release certificates and profiles |
| Devices | "Mobile phone" = phones and tablets | Watch, Vision, telematics, PC/2-in-1 |

Whether an Android APK reaches devices running HarmonyOS NEXT / HarmonyOS 5+ is **not claimed**
anywhere in these documents and is out of scope.

## 2. AppGallery publication requirements (official sources)

### 2.1 Developer account and identity verification 🔑

> **Status for TalentForge (2026-10-17):** the owner reports that Huawei developer account identity
> verification is **COMPLETE**. This was not independently verified in this milestone. Whether the
> account can create the Android app in AppGallery Connect and submit it for release is
> **VERIFY IN CONSOLE** (§6 step 1). The registration steps below are kept as reference only; they do
> not need to be repeated.

From [Getting Started](https://developer.huawei.com/consumer/en/doc/app/agc-help-getstarted-0000001100316670)
(updated 2026-03-13) and the identity-verification pages:

1. Register a **HUAWEI ID** on HUAWEI Developers.
2. Complete **identity verification** to turn it into a developer account, as an **individual** or an
   **enterprise** developer ("An enterprise developer can have access to more services").
   - Individual ([page](https://developer.huawei.com/consumer/en/doc/start/ibca-0000001062388135),
     updated 2025-07-30): be of legal age; upload an identity document (ID card, passport or another
     document such as a driver's licence) **plus a bank document**, each showing the full name in
     English and issued in the country selected for verification; the bank document must be valid for
     at least 6 months. Review takes 1–2 working days; the result arrives by e-mail.
   - Enterprise ([DUNS](https://developer.huawei.com/consumer/en/doc/start/atpopb-0000001062836624),
     updated 2026-04-22, or a business licence): company data must match DUNS; some regions also ask
     for the contact person's identity and bank documents.
3. **Merchant Service** is needed only for paid apps or in-app purchases. TalentForge is free and has
   no purchases, so it is not needed.

Fees, if any, are not stated on these pages and were not verified.

### 2.2 Creating the app in AGC 🔑

From [Creating an Android App](https://developer.huawei.com/consumer/en/doc/app/agc-help-createapk-0000001912873100)
(updated 2025-11-06): *Apps and atomic services → Android → Release*, then:

| Field | Value for TalentForge | Note |
|---|---|---|
| Package type | APK (Android app) | |
| Device | Mobile phone | "Mobile phone includes both mobile phones and tablets" |
| App name | `TalentForge` | Must not contain prohibited words and **must be the same as in the app package** (the APK label is `TalentForge`) |
| App category | **App** (not Game) | "This app category cannot be changed once set" |
| Default language | English or Russian (owner) | Further languages via *Manage languages* |
| Add to project | **No** | Needed only for Huawei services (Account Kit, IAP, Auth Service); TalentForge uses none |
| Package name | `io.github.alexeykravtsov95.talentforge` | If not entered, AGC reads it from the first upload; every later package must match |

### 2.3 Package requirements

From [Releasing an App (APK)](https://developer.huawei.com/consumer/en/doc/app/agc-help-releaseapkrpk-0000001106463276)
(updated 2026-06-01):

- APK smaller than 4 GB; package name not used by a released app and equal to the one in AGC.
- "To release a common app, only one APK can be uploaded."
- "When you distribute an APK to mobile phones, ensure that the uploaded package is a 64-bit one."
  TalentForge packages `arm64-v8a` (see [readiness §4](readiness.md#4-huawei-compatibility-audit)).
- AAB instead ([App Bundle guide](https://developer.huawei.com/consumer/en/doc/app/agc-help-releasebundle-0000001100316672)):
  App Signing mandatory, `fusing` set to `true` for the universal APK, Play Asset Delivery disabled,
  download size ≤ 150 MB. **Not recommended** for the first release ([signing §3](multi-store-signing.md#3-apk-vs-aab-for-the-first-public-release)).

### 2.4 Store listing fields

Same guide (*Configuring Basic Localization Information*) and [Review Guidelines §1](https://developer.huawei.com/consumer/en/doc/app/50104-01):

| Field | Limit (official) | Draft |
|---|---|---|
| App name | mandatory; ≤ 30 characters outside Chinese (guideline 1.1); no `*`, `&`, `-`, brackets or emoji (1.4); unique, not generic (1.2, 1.3); the same as the installed app (1.20) | `TalentForge` |
| Brief introduction | mandatory; ≤ 80 characters; ≤ 25 advised | [EN](store-listing-en.md) / [RU](store-listing-ru.md) |
| Full introduction | mandatory; ≤ 8000 characters | idem |
| New features | optional; ≤ 500 characters | idem |
| Category | level-2 and level-3; Huawei may re-categorise | proposal: *Apps → Tools → Tools* ([Categorization](https://developer.huawei.com/consumer/en/doc/app/50103)), owner decides |
| Localization | per language; visual assets fall back to the default language | EN + RU |

Guideline points that shape the copy: information must match the app's real functions and must not
claim features that are not provided (1.14); it must not suggest a relationship with a third party
(1.15); it must not contain names, icons or imagery of other device brands or **app platforms**
(1.16), so the AppGallery listing must not mention RuStore or Google Play; information must be
localised into each selected language (1.13).

### 2.5 Icon and screenshots

[Specifications for App Visual Assets → APKs → mobile](https://developer.huawei.com/consumer/en/doc/app/agc-help-app-material-requirement-0000001146534651)
(updated 2026-04-01):

| Asset | Quantity | Resolution | Format and max size |
|---|---|---|---|
| Icon (mandatory) | 1 | 216×216 **or** 512×512, square | PNG ≤ 2 MB; WebP ≤ 100 KB (not animated) |
| Screenshots (mandatory) | 3–8 | 800×450 or 450×800; minimum side 320 px, maximum side 3840 px | PNG/JPG/JPEG ≤ 2 MB; WebP ≤ 100 KB (not animated) |
| Introduction video (optional) | 1–3 | 1280×720 / 720×1280 | MOV/MP4 ≤ 500 MB, 15 s–2 min |
| Promotional video (optional) | 1 | 1600×1200 or 1200×900 | MOV/MP4 ≤ 500 MB |

Guidelines: at least three different screenshots, correct orientation, not blurred or deformed, no
irrelevant content (1.9); the icon must not imitate a system icon or show fake badges (1.6, 1.7);
icon, name and content must match the installed app (1.20). Asset plan: [assets-checklist](assets-checklist.md).

### 2.6 Privacy information

[Releasing an App (APK) → Configuring Privacy Permissions / Providing a Privacy Statement / Adding Privacy Tags](https://developer.huawei.com/consumer/en/doc/app/agc-help-releaseapkrpk-0000001106463276):

- The **privacy policy URL** is labelled *mandatory* and described as required "If your app needs to
  collect and process user information"; the URL is shown on the app details page and must be
  reachable from the Internet. The *data subject right URL* is optional.
- **Privacy tags:** "If your app does not collect user data, set Collect personal data to No."
- **Permission descriptions** appear only if the APK requests sensitive permissions. TalentForge
  requests none (static evidence in [readiness §4](readiness.md#4-huawei-compatibility-audit)).
- [Review Guidelines §7.1](https://developer.huawei.com/consumer/en/doc/app/50104-07): apps that process
  personal data must have "an easy-to-find in-app link" to the privacy policy **and** submit the link
  in AGC. *(M5.STORE.1: since M5.RC.2b the app has an About screen with a privacy summary and an empty
  policy-link slot, `LegalLinks.privacyPolicyUrl`; the 2026-10-19 re-read found the literal §7.1 sentence
  unconditional, so set the in-app link once the URL exists.)* Whether §7.1 applies to an app that
  processes no personal data is an **owner/legal decision**; see [privacy §5](privacy-and-compliance.md#5-store-privacy-declarations).

The privacy policy draft (RU + EN) and the facts behind it are in [privacy-and-compliance](privacy-and-compliance.md).

### 2.7 Content rating, AI declaration, copyright, payment

Same guide:

- **Content rating** is mandatory: an age-rating questionnaire whose answers determine the youngest
  applicable age group. "False statements about your app content may lead to app removal or freezing."
  The questionnaire changes over time and may have to be answered again. The owner answers it 🔑;
  the content facts are in [privacy §6](privacy-and-compliance.md#6-content-facts-for-age-rating-questionnaires).
- **AI function declaration** is required on submission. TalentForge provides no generative-AI
  service. The launcher-icon concept was AI-assisted (M5.RC.1 icon policy); whether that counts as
  "AI-generated content" under Huawei's labelling FAQ is an **owner decision** 🔑.
- **Copyright information** is optional for release outside the Chinese mainland and mandatory
  (plus an electronic copyright certificate) for the Chinese mainland.
- **Payment**: Free. "The payment type cannot be changed after a formal version is released."

### 2.8 Review

- The app must comply with the [AppGallery Review Guidelines](https://developer.huawei.com/consumer/en/doc/app/50104).
  The sections that matter most here are 1 (App Information), 7 (User Privacy) and 9 (Intellectual
  Property Rights). §9.1: the app must be "authorized to use any patented or copyrighted content of
  others"; §9.5: it must not mislead users into believing that it is provided by, or related to, a
  third party. See [privacy §7](privacy-and-compliance.md#7-intellectual-property-and-trademarks) (LR-1…LR-3).
- "App review usually takes 1 to 3 working days." Rejections arrive by e-mail. A version under
  review cannot be edited without cancelling the review first.
- *For reviewer* notes: no sign-in exists; state that the app works offline and needs no account.

### 2.9 Release countries/regions

- *All countries/regions* (200, plus future ones) or *Selected countries/regions*. After submission,
  Huawei checks "whether your app complies with local policies and religious/cultural practices of
  each release country/region" and may remove countries; the final list is visible after approval.
- The Chinese mainland adds requirements this project cannot meet today (copyright certificates).
  **Recommendation:** select countries explicitly and exclude the Chinese mainland. The concrete list
  is an owner decision 🔑; which countries are offered to this account is visible only in the console.

### 2.10 Testing and staged release options

| Option | Official facts | Use for TalentForge |
|---|---|---|
| [Open Testing](https://developer.huawei.com/consumer/en/doc/AppGallery-connect-Guides/agc-betatest-introduction-0000001071477284) | Invite test users with HUAWEI IDs; Android APK/AAB; phones and tablets only; test period ≤ 90 days; ≤ 100 test users when manual review is skipped, ≤ 5000 with manual review ([Restrictions](https://developer.huawei.com/consumer/en/doc/AppGallery-connect-Guides/agc-betatest-restriction-0000001071399071)). The formal version is released "by updating the released version" with *Version for open testing* = No ([Releasing a Formal Version](https://developer.huawei.com/consumer/en/doc/AppGallery-connect-Guides/agc-betatest-officialrelease-0000001071159046)) | **Recommended first step** 🔑: release the signed RC APK as an open-testing version to the owner's own HUAWEI ID and a few testers, run §5 on the delivered build, then follow §6.1 |
| Scheduled release | Release immediately after approval or at a set time; *Release now* is possible before that time | Align the AppGallery go-live with RuStore |
| [Phased release](https://developer.huawei.com/consumer/en/doc/app/agc-help-phased-release-0000001099836872) | Only for **updates** of an app that already has a full release; APK on phones; ≤ 30 days; the percentage only increases; **no rollback** | Not available for the first release; consider for later updates |
| Cloud Testing / Cloud Debugging | Huawei remote real devices, optional | Optional supplement when no physical Huawei phone is available; does not replace §5 |

## 3. RuStore requirements re-checked in this milestone

M5.RC.1 could not fetch the RuStore moderation rules. They were read on 2026-10-17
([Правила модерации RuStore](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/requirement-apps)).
Points that change the preparation:

| Rule | Consequence |
|---|---|
| 2.14: a debug certificate and `android:debuggable="true"` are listed among signs of malicious code | release key only; checks in [signing §7](multi-store-signing.md#7-preventing-accidental-debug-key-signing) |
| 4.1: no third-party trademarks, names, logos or copyrighted works without legal grounds; RuStore may request proof of rights | LR-1/LR-2 gate RuStore as well |
| 5.1: an app that collects or processes personal data needs a privacy policy in Russian, linked from the store card **and inside the app** | [privacy §5](privacy-and-compliance.md#5-store-privacy-declarations) |
| 6.2: a Latin-only name is allowed; the card description must be in Russian | the RU listing is primary for RuStore |
| 6.4: no third-party trademarks or names, no links to other stores, no clickbait, no unavailable features; a **Russian-language name is recommended** unless the name is a registered trademark (Federal Law 168-FZ, in force since 2026-03-01) | trademark-free copy variant; the Russian-name question goes to the owner |
| 6.6: icon 512×512 PNG/JPG ≤ 3 MB, filled to the edges and **the same as the installed app**; screenshots ≤ 3 MB (phone), max 2160×3840, **no Android system UI**, one orientation per set, text in RU/EN, real functionality only | [assets-checklist](assets-checklist.md) |
| 6.7: at least one developer contact (e-mail, website, VK community or MAX channel) | owner decision |
| 8.1: tested on current Android versions; minimum supported version Android 8.0 | `minSdk 26` = Android 8.0 ✅ |
| 8.5: an app may be delisted if its RuStore version lags the version in other stores | same `versionCode` in both stores |

The RuStore items already in [rc1-release-checklist §6](../rc1-release-checklist.md#6-store-requirements) stay valid.

## 4. What this milestone verified, and what it did not

| Item | Status |
|---|---|
| Official AppGallery requirements for Android APKs (§2) | Documented from official pages (dates in §7) |
| RuStore moderation rules (§3) | Documented (previously unverified) |
| Static Huawei compatibility of the package (no GMS/Firebase/billing, permissions, ABIs, SDK levels) | [readiness §4](readiness.md#4-huawei-compatibility-audit) and §7 there |
| Runtime on a Huawei device | **UNVERIFIED**: no Huawei device was available |
| AGC console behaviour (fields, country list, questionnaire wording) | **UNVERIFIED**: needs the owner's account 🔑 |
| Upload, review, publication | **Not done** (out of scope) |

## 5. Device test plan

**Status: UNVERIFIED.** No physical Huawei device was available for M5.GALLERY.1. These scenarios
belong to the M5.RC.3 device pass and must run on the **signed release APK** (after TF-RC1-SIGNING).

Target devices (subject to the owner's availability): at least one Huawei phone **without Google
Mobile Services** running EMUI or HarmonyOS 2–4 (Android-compatible), in **ru-RU** and **en-US**; a
Huawei tablet if available. Cloud Testing (§2.10) is an optional supplement.

| # | Scenario | Expected |
|---|---|---|
| H-1 | `adb install` the signed release APK; launch in airplane mode | App opens on Home; no Google Play services prompt or error |
| H-2 | Empty library; create a build for each of the 9 classes | Class art, talent trees, icons and descriptions load offline |
| H-3 | Spend/refund with the inspector buttons, Max, Undo/Redo, Reset; pan and pinch | Matches the B3 contract; no crash |
| H-4 | Force-stop and relaunch; reboot the device | Builds persist exactly |
| H-5a | **Home → Recent → ⋯** offers exactly *Rename · Share · Delete*. Rename (confirm and Cancel), Share (then close the sheet), Delete (confirm and Cancel) | Rename: new name shown, row order unchanged; Cancel changes nothing. Delete: the row disappears only after confirming; Cancel keeps it. Force-stop + relaunch: the results persist |
| H-5b | **All Builds → ⋯** offers exactly *Rename · Duplicate · Share · Delete*. Each action, with Cancel where a dialog exists (Rename, Delete) | Duplicate adds one independent copy; Rename/Delete as in H-5a; force-stop + relaunch: the list is exactly as left |
| H-5c | **Talent editor → ⋯** offers exactly *Rename · Share · Reset talents* (no Delete). Rename (confirm and Cancel); Reset talents, then its Undo; Reset without Undo | The title updates in place; Undo restores the allocation; after force-stop + relaunch the build reopens with the last saved state |
| H-6 | **Share**: editor ⋯ → Share → system chooser → a messenger; cancel the chooser; rotate with the sheet open | Chooser opens; the received text is the exact `TF1…` code; cancelling shows nothing |
| H-7 | **Copy code**, then Import → Paste | The clipboard holds the exact code; Paste reads it; record any vendor clipboard-access notice |
| H-8 | Import a valid, a damaged and an older-build code | Preview or the typed error described in [build-sharing.md](../../build-sharing.md) |
| H-9 | Device language ru-RU ↔ en-US | UI and talent text switch; the launcher label stays `TalentForge` |
| H-10 | Font scale 100 % / 200 %, portrait / landscape, phone / tablet | Same layout behaviour as on other Android devices (re-check TF-RC1-BADGE-200) |
| H-11 | Launcher icon on the Huawei launcher and its mask | Approved icon, not clipped |
| H-12 | Huawei backup / Phone Clone with the current `allowBackup` default | Record whether builds are copied (feeds the privacy text) |
| H-13 | Install from AppGallery Open Testing; `adb pull` the delivered APK | SHA-256 equals the owner's certificate ([signing §8](multi-store-signing.md#8-certificate-sha-256-verification)) |
| H-14 | Cross-store: RuStore install → AppGallery APK with the same or a higher `versionCode`, and the reverse | Updates in place, builds kept; record whether each store client offers the update |

## 6. Publication runbook (for the owner; not executed)

Pre-conditions: every gate in [readiness §8](readiness.md#8-release-blockers) is closed, the signed
release APK passed §5, and the owner approved the store copy and assets.

1. 🔑 Identity verification is reported **complete** by the owner (§2.1); do not register again.
   **VERIFY IN CONSOLE** that the account can open *Apps and atomic services → Android* and create an
   app, and record the result. AGC publication privileges were not independently verified.
2. 🔑 AGC → *Apps and atomic services* → *Android* → *Release*: APK, Mobile phone, `TalentForge`,
   category **App**, default language (§2.2).
3. 🔑 *App information*: EN + RU localization (name, brief and full introduction from the listing
   drafts), icon, screenshots per language, category, contact information.
4. 🔑 *Version information → Draft*: release countries (explicit list, no Chinese mainland), Open
   testing = **Yes** for the first round, upload the signed APK (do **not** enable App Signing),
   Payment = Free, the content-rating questionnaire, the privacy-policy URL, privacy tags
   (*Collect personal data = No* once the owner confirms [privacy §5](privacy-and-compliance.md#5-store-privacy-declarations)),
   the AI declaration, and reviewer notes ("offline app, no account, no sign-in").
5. 🔑 Submit. After approval, run §5 (H-13, H-14) on the Open-Testing build.
6. 🔑 Formal release: follow §6.1. It is an **update** of the open-testing version with a **new APK
   and a higher `versionCode`**; the same APK goes to RuStore in the same release window.
7. Record the delivered APK's SHA-256 and the final release countries in the owner's release record.

### 6.1 Open Testing → formal (production) release

What the official documentation says:

- [Releasing a Formal Version](https://developer.huawei.com/consumer/en/doc/AppGallery-connect-Guides/agc-betatest-officialrelease-0000001071159046)
  (updated 2024-12-10): after open testing, "release the formal version of the app by updating the
  released version" (see *Releasing an App Update*), selecting **No** for *Version for open testing*.
- [Releasing an App Update](https://developer.huawei.com/consumer/en/doc/app/agc-help-update-version-0000001146436549)
  (updated 2025-11-06): *Update* creates *New version – Draft*; for an APK, "the version code of the
  uploaded app package is not earlier than that of the released version" and the signature must be
  the same as the released package. If the code is the **same**, AGC warns that "users who have
  already installed this version will not be able to update to the new one". The page also notes that
  the *Update* button may be absent while the last version has not been released.
- Whether AGC accepts re-submitting the **identical** open-testing APK as the formal version is not
  documented and is **UNVERIFIED**. This runbook does not rely on it.

Procedure (owner 🔑 = console step):

| # | Step | Who |
|---|---|---|
| 1 | Open-testing version *N* (signed release APK, `versionCode` *N*) is approved and released to testers; §5 passes on the delivered APK | 🔑 owner + QA |
| 2 | Decide go/no-go for production; fix anything found and go back to step 1 if code changed | owner |
| 3 | Build **one** new signed release APK from the release commit with `versionCode` *N + k* (k ≥ 1) and the intended `versionName`; verify it (`apksigner` SHA-256 = owner fingerprint, not debuggable; [signing §7–§8](multi-store-signing.md#7-preventing-accidental-debug-key-signing)) | engineering + owner (key) |
| 4 | 🔑 AGC → the app → *Update* → *New version – Draft*: *Version for open testing* = **No**; *Manage packages* → upload the APK from step 3; Release type = *Full release* (phased release is only for updates of an app that already has a full release); check countries, privacy, rating and reviewer notes; Submit | owner |
| 5 | 🔑 RuStore Console: upload the **same** APK file from step 3 (same SHA-256, same `versionCode`) as the RuStore release; submit for moderation | owner |
| 6 | 🔑 Schedule both releases for the same window (AGC: specified release time or *Release now*; RuStore: its publication settings) so that neither store lags | owner |
| 7 | After publication: install from each store, `adb pull` the delivered APK, compare SHA-256 (H-13); run H-14 | owner + QA |

`versionCode` policy:

- Every built APK gets a **unique, strictly increasing** `versionCode`; a number is never reused for
  different bytes.
- Open-testing builds **consume** their numbers. The production APK always has a higher
  `versionCode` than every APK uploaded to either store before it.
- The production APK is **byte-identical** in AppGallery and RuStore (one file, one `versionCode`).
- If a store rejects the build and a fix is needed, the fixed APK gets a new, higher `versionCode` and
  replaces the pending submission in **both** stores.
- First release (owner decision 2026-10-20, M5.RELEASE.VERSION): Open Testing APK `1` / `1.0.0`; the
  formal-version APK, which is also the RuStore release, is a new build with `2` / `1.0.0`, or the
  next unused code if testing consumed more ([android-versioning](../android-versioning.md)).

Is a new APK needed? **Yes**, by this policy: the formal release uses a newly built APK with a higher
`versionCode`, even if the code is unchanged since the open-testing build. This avoids the documented
same-`versionCode` update warning and the undocumented question of re-submitting an identical APK.

## 7. Sources

Accessed 2026-10-17 (Huawei pages show their own "Last updated" date).

- [Releasing an App (APK)](https://developer.huawei.com/consumer/en/doc/app/agc-help-releaseapkrpk-0000001106463276) — 2026-06-01
- [Releasing an App (App Bundle)](https://developer.huawei.com/consumer/en/doc/app/agc-help-releasebundle-0000001100316672) — 2026-06-01
- [Specifications for App Visual Assets](https://developer.huawei.com/consumer/en/doc/app/agc-help-app-material-requirement-0000001146534651) — 2026-04-01
- [Getting Started (AGC)](https://developer.huawei.com/consumer/en/doc/app/agc-help-getstarted-0000001100316670) — 2026-03-13
- [Identity verification: individual developers](https://developer.huawei.com/consumer/en/doc/start/ibca-0000001062388135) — 2025-07-30
- [Identity verification: enterprise developers with a DUNS number](https://developer.huawei.com/consumer/en/doc/start/atpopb-0000001062836624) — 2026-04-22
- [Creating an Android App](https://developer.huawei.com/consumer/en/doc/app/agc-help-createapk-0000001912873100) — 2025-11-06
- [AppGallery Review Guidelines](https://developer.huawei.com/consumer/en/doc/app/50104): [1. App Information](https://developer.huawei.com/consumer/en/doc/app/50104-01) (2026-01-14), [7. User Privacy](https://developer.huawei.com/consumer/en/doc/app/50104-07) (2026-01-14), [9. Intellectual Property Rights](https://developer.huawei.com/consumer/en/doc/app/50104-09) (2023-08-23)
- [Categorization](https://developer.huawei.com/consumer/en/doc/app/50103) — 2024-09-09
- [Open Testing: Service Introduction](https://developer.huawei.com/consumer/en/doc/AppGallery-connect-Guides/agc-betatest-introduction-0000001071477284) — 2025-01-08
- [Releasing an App by Phase](https://developer.huawei.com/consumer/en/doc/app/agc-help-phased-release-0000001099836872) — 2026-01-07
- [Releasing an App Update](https://developer.huawei.com/consumer/en/doc/app/agc-help-update-version-0000001146436549) — 2025-11-06
- [Open Testing: Releasing a Formal Version](https://developer.huawei.com/consumer/en/doc/AppGallery-connect-Guides/agc-betatest-officialrelease-0000001071159046) — 2024-12-10; [Restrictions](https://developer.huawei.com/consumer/en/doc/AppGallery-connect-Guides/agc-betatest-restriction-0000001071399071) — 2024-11-11
- [App Signing: Service Introduction](https://developer.huawei.com/consumer/en/doc/AppGallery-connect-Guides/agc-appsigning-introduction-0000001051379577) — 2025-07-16
- RuStore — [Правила модерации RuStore](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/requirement-apps)
