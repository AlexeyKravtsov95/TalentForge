# M5.PLAY.1 — Data safety, privacy policy and App content (Google Play)

| | |
|---|---|
| Status | **Proposed answers**, not submitted. The owner fills in Play Console 🔑 |
| Evidence base | this branch (`main` @ `f0d0fcb` + NET.1 `5b5d9a0`, `bfefde1`), re-checked 2026-10-19: source, merged manifests, the `play` bundle manifest, generated OTA settings |
| Earlier evidence | [AppGallery privacy and compliance §1–§6](../appgallery/privacy-and-compliance.md) (M5.GALLERY.1, updated by M5.STORE.1 and NET.1) · [store1 audit §5](../store1-release-audit.md#5-privacy-and-ota-what-the-production-build-actually-does) |
| Not legal advice | The wording of the privacy policy and the IP questions are LR-5 and LR-1…LR-3 (owner/legal) |

## 1. What the app does with data (verified)

| Area | Fact | How it was checked |
|---|---|---|
| Network | The Play bundle requests **no `INTERNET` permission** (NET-1 = B): the only requested permission is the AndroidX signature permission `io.github.alexeykravtsov95.talentforge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Without `INTERNET`, Android lets the app open no network socket. | `verify_play_bundle.py` `permissions-allowed` on the self-test bundle ([evidence](evidence/play-signing-selftest/)); `bundletool dump manifest` of `bundlePlay`; `checkPlayInternetPermission` guards every `play` build |
| OTA | Every OTA channel is generated with `enabled = false` (no hosting or keys configured), and release/play builds refuse any staging OTA setting (`checkReleaseOtaSettings`). The disabled OTA fetcher's code (`HttpURLConnection`) is still in the package. | `build/generated/ota/.../GeneratedOtaSettings.kt`; build guard |
| Local storage | The build library: per build a local id, an optional **name the user types**, timestamps and the talent allocation. Stored in the app-private `SharedPreferences` file `talentforge_build_library` (`MODE_PRIVATE`). | `AndroidBuildLibraryStorage.kt`, `LocalBuildLibrary.kt` |
| Backup | `allowBackup="false"`; `dataExtractionRules` and `fullBackupContent` exclude every data domain. Nothing goes to Google Drive backup. | merged manifest; packaged rule files (M5.RC.2b) |
| Export: share | Only on a tap on **Share**: `Intent.ACTION_SEND`, `text/plain`, in the system chooser (Android Sharesheet). The text is the build code: no name, no personal data ([build-sharing.md](../../build-sharing.md)). The app does not learn the target app. | `BuildCodeSharing.android.kt` |
| Export: clipboard | Only on a tap on **Copy code**: one plain-text clip with the build code. | `BuildCodeSharing.android.kt` |
| Import: clipboard | Only on a tap on **Paste** on the Import screen: the first clip's plain text is read and decoded locally (max 4 096 characters). | `BuildCodeSharing.android.kt`, `ImportBuildScreen.kt` |
| Analytics, ads, crash reporting | **None.** No analytics, advertising, crash-reporting, Firebase, Google Play services, Play Billing or HMS module is declared in the version catalog or the app's build files, and none was found among the release runtime modules or the dex class references. | `gradle/libs.versions.toml`, `composeApp/build.gradle.kts`; dex scan ([AppGallery privacy §2](../appgallery/privacy-and-compliance.md#2-verified-network-analytics-and-googlehuawei-services-static)) |
| Identifiers, account | No account, no sign-in, no advertising ID, no device identifier read. | static inspection |
| Developer receives | **Nothing.** | follows from the rows above |

## 2. Proposed Data safety answers

Google's definitions ([Data safety help](https://support.google.com/googleplay/android-developer/answer/10787469), read 2026-10-19):

- **Collect** means "transmitting data from your app off a user's device". Data processed only on
  the device is not collected.
- Transfers based "on a specific user-initiated action, where the user reasonably expects the data
  to be shared" are not **sharing**.
- The form is required for every published app. **Only apps active solely on internal testing
  are exempt**, so the form is needed **before closed testing**.

| Question (Play Console) | Proposed answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No** | The app transmits nothing off the device. Build codes leave the device only through Share or Copy, which the user starts and directs at an app of their choice: on-device handling, and a user-initiated transfer that is excluded from "sharing". The user-typed build name never leaves the device |
| Is all of the user data collected by your app encrypted in transit? | not asked when the answer above is No | — |
| Do you provide a way for users to request that their data is deleted? | not asked when the answer above is No | Users can delete builds in the app, or uninstall to delete everything |
| Data types: location, personal info, financial info, health, messages, photos/videos, audio, files/docs, calendar, contacts, app activity, web browsing, app info and performance, device or other IDs | **none** | The clipboard is read only on the user's Paste tap and handled locally; it is not collected |

The resulting store label would read "No data collected" and "No data shared with third parties".
The owner must still confirm every answer in the console 🔑. If production OTA is ever enabled
(NET-1 changes), redo this form: OTA requests would then send device data (at least the IP address)
to the hosting origin.

## 3. Privacy policy

- **Required even though nothing is collected.** Google's
  [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311):
  "Apps that do not access any personal and sensitive user data must still submit a privacy policy".
- **Where:** a public, active, non-geofenced URL (no PDF), labelled as a privacy policy, entered in
  Play Console (*App content* → *Privacy policy*) **and** reachable inside the app.
- **When:** before closed testing. The *App content* declarations, including the privacy policy,
  must be complete before a release can be sent to review on any track other than internal testing.
  Google's help does not state this as one sentence; it follows from the page above, from the Data
  safety rule in §2 and from the *Target audience* section, which opens only after the privacy-policy
  declaration. Treat a hosted policy as a hard prerequisite of [closed testing](closed-testing.md).
- **Text:** the RU/EN drafts in [AppGallery privacy §8](../appgallery/privacy-and-compliance.md#8-draft-privacy-policy)
  describe this exact behaviour (no internet access, local storage, Share/Copy only on a tap, no
  backup). They need a Google Play mention where they list stores, the owner's identity and contact,
  and legal review (LR-5).
- **In the app:** Home → About shows the privacy summary and, since M5.LEGAL.LINK, the link that
  opens the published policy of the UI language (`privacyPolicyUrlOf`: `legal.mythweld.com/privacy.html`
  for Russian, `…/privacy-en.html` otherwise) in the system browser. The owner action that remains
  is the policy text's legal review (LR-5, P-6).

## 4. Other App content declarations (proposed)

| Declaration | Proposed answer | Note |
|---|---|---|
| Ads | **No ads** | no ads SDK |
| App access | **All functionality is available without special access** | no account, no sign-in |
| Target audience and content | **18+** (or 16–17 and 18+); **not** under 13 | The app is a planner for a game rated for teens and adults. Choosing under-13 groups brings the Families policy. The listing must not look child-directed |
| Content rating (IARC) | Reference app category: utility/productivity, not a game. Expected answers: no violence shown (game text names combat abilities in talent descriptions), no sexuality, no profanity, no drugs, no gambling, no user interaction or sharing **between users**, no location sharing, no digital purchases | The answers decide the rating. The questionnaire is the owner's. Facts: [AppGallery privacy §6](../appgallery/privacy-and-compliance.md#6-content-facts-for-age-rating-questionnaires) |
| News app | No | — |
| COVID-19 apps | Not a COVID-19 app | — |
| Data safety | §2 | — |
| Government apps | Not developed by or for a government | — |
| Financial features | **My app doesn't provide any financial features** | required for every app, closed testing included |
| Health | **My app doesn't provide any health features** | required for every app, closed testing included |
| Permissions declarations | none: no sensitive or restricted permission | — |
| Accessibility API | not used (no `AccessibilityService`) | standard semantics only |

## 5. Not established here

- How the reviewer will treat the game content (talent icons, texts, class artwork) under Google
  Play's intellectual-property and impersonation policies: see [readiness §6](readiness.md#6-legal-constraints-m5legal1).
- Behaviour of the emoji2 initializer on devices without a font provider: not a data-safety
  question; no network access exists to use either way.
