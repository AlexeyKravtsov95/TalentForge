# M5.RC.1 — Release checklist

How to read this checklist:
- It is a living checklist for M5.RC.2a → M5.RC.2b → M5.RC.3.
- `[x]` = verified in M5.RC.1 with evidence.
- `[ ]` = open.
- Items marked **(gate)** must be checked before M5.RC.3 can pass.
- Defect IDs refer to [rc1-defects.md](rc1-defects.md).

> **M5.OTA.PROD.3 (2026-10-21).** Утверждения этого документа об отсутствии `INTERNET` и сетевых соединений относятся к сборке **OTA OFF** (по умолчанию) и остаются для неё верными. Матрица OTA ON/OFF: [ota-prod3-evidence](ota-prod3-evidence/README.md). Сборка с production OTA (`talentforge.ota.android.production.enabled=true`) запрашивает `INTERNET` и загружает данные талантов с `https://ota.mythweld.com`: для неё действуют [ota-prod3-privacy-and-stores](ota-prod3-privacy-and-stores.md) (тексты, политика, декларации, действия владельца) и [android-production-enablement](../ota/android-production-enablement.md). Ничего не подано и не опубликовано; LR-1…LR-5 без изменений.

## 1. Android release checklist (RuStore first)

**Identity and versioning**
- [x] `applicationId` = `io.github.alexeykravtsov95.talentforge` (aapt2 badging, E1-60)
- [x] `minSdk 26`, `targetSdk 36`, `compileSdk 36` (`libs.versions.toml`, badging)
- [x] **(gate)** `versionCode` / `versionName` decided for the first public release. Owner decision (2026-10-20, M5.RELEASE.VERSION): test build `1` / `1.0.0` (applied), public build `2` / `1.0.0`, unless testing consumes more codes. One code sequence for all three stores ([android-versioning.md](android-versioning.md)).
- [ ] **(gate)** `versionCode` raised to the public value (highest consumed code + 1; codes `2` (Google Play upload with OTA ON) and `3` (Google Play `alpha` track) are consumed, so the next code is `4`, [ledger](android-versioning.md#3-planned-sequence-and-ledger)) in a dedicated, reviewed change before the public build; test uploads consume codes, and the AppGallery/RuStore public APK is a new build with a higher code ([procedure](android-versioning.md#5-raising-versioncode-before-the-public-release)).
- [x] Display name `TalentForge` (`android:label`)

**Manifest and permissions**
- [x] Only the launcher `MainActivity` is exported.
- [x] No dangerous or user-visible permissions.
- [x] **(gate) `INTERNET` decided (NET-1).** The owner chose **B** (2026-10-19): release builds remove `android.permission.INTERNET` (`src/androidRelease/AndroidManifest.xml`) while the production OTA channel is off; debug builds keep it for staging OTA. `:composeApp:checkReleaseInternetPermission` stops packaging of any release build whose merged manifest requests `INTERNET` while production OTA is off, or lacks it while production OTA is on (rule self-test: `verifyReleaseInternetRules`, run before `desktopTest`). Re-verified with `aapt2`: the release and temporary-key publishable APKs request only the AndroidX signature permission `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` ([NET-1 evidence](net1-evidence/README.md)). Re-check `aapt2 dump permissions` on the owner-signed APK (§9). *(M5.STORE.1 at `b242cd6`: `INTERNET` declared, no connection measured, [store1 audit §5](store1-release-audit.md#5-privacy-and-ota-what-the-production-build-actually-does); M5.RC.1 at its commit: no INTERNET.)*
- [x] `android:allowBackup` decided explicitly and reflected in the privacy text (TF-RC1-ANDROID-CONFIG). M5.RC.2b: `allowBackup="false"` + `dataExtractionRules` (Android 12+) + `fullBackupContent` (API 26–30), every domain excluded; verified in the merged release manifest ([rc2b-evidence](rc2b-evidence/README.md#backup-policy)); privacy text updated. Owner confirms the policy choice.
- [x] **(gate)** `android:icon` set to the approved adaptive icon (TF-RC1-ICON). M5.RC.2a: `android:icon="@mipmap/ic_launcher"`; packaging and rendering checks in §4 ([rc2a-evidence](rc2a-evidence/README.md)).

**Build and packaging**
- [x] `assembleDebug`, `assembleRelease`, `bundleRelease` succeed (E1-43/44/45).
- [x] Localization resources packaged: `values` + `values-ru` compose resources (desktop tests and EMU en-US run).
- [ ] **(gate)** Release artifact signed with the owner's long-lived key. Keystore and passwords stay outside git (TF-RC1-SIGNING).
  - M5.RC.2b: the mechanism is implemented and verified with a temporary test key (`assemblePublishableRelease`, [owner guide](android-release-signing.md)). Still open: the owner's key and the first verified signed APK.
- [ ] **(gate)** `apksigner verify --print-certs` passes on the exact artifact to upload. M5.RC.2b: `scripts/verify_release_apk.py` performs this and every identity check automatically; to be run on the owner-signed APK.
- [x] `lintRelease` exits 0, or a justified baseline is committed (TF-RC1-LINT). M5.RC.2b: **0 errors, 9 warnings**, root-cause fix, no baseline or suppression.
  - M5.RC.2a: still fails only on the pre-existing `RememberReturnType` error. `MissingApplicationIcon` is gone. The icon integration **adds 6 warnings**: `ObsoleteSdkInt` (the `mipmap-anydpi-v26` folder could be `mipmap-anydpi`) and `IconLauncherShape` ×5 (the full-bleed raster fallbacks). The owner decides whether to fix them or baseline them ([lint](rc2a-evidence/mac/lint/lint-results-release.txt)).
- [ ] R8/minify decision. If enabled, run a full device regression (TF-RC1-ANDROID-CONFIG).
- [ ] Optional: `localeFilters` (en, ru) and `localeConfig` (TF-RC1-ANDROID-CONFIG).
- [x] Artifact sizes recorded: debug 20.1 MB, release APK 16.9 MB (unsigned), AAB 16.8 MB.

## 2. iOS release checklist

**Identity and versioning**
- [x] Bundle ID `io.github.alexeykravtsov95.talentforge`; deployment target iOS 17.2; `TARGETED_DEVICE_FAMILY 1,2` (E1-50)
- [ ] **(gate)** `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` decided. They are currently `0.1.0` / `1`.
- [x] Display name `TalentForge`; `CFBundleLocalizations` en, ru
- [x] Orientations: iPhone portrait + both landscapes; iPad all four
- [ ] `CFBundleName` resolves to `iosApp` (`PRODUCT_NAME`). Confirm this is acceptable; it is not user-visible when `CFBundleDisplayName` is set.

**Build**
- [x] Kotlin frameworks link: debug device + simulator, release device (E1-47/48)
- [x] `xcodebuild` simulator Debug, simulator Release, device Release with `CODE_SIGNING_ALLOWED=NO` (E1-51/52/53)
- [x] Built with Xcode 26.4.1 / iOS 26.4 SDK. This meets Apple's "Xcode 26 / iOS 26 SDK" upload minimum (in effect since 2026-04-28).
- [ ] **(gate)** `PrivacyInfo.xcprivacy` added. It must declare User Defaults plus the Compose/Kotlin categories, with reason codes confirmed against Apple's documentation (TF-RC1-IOS-PRIVACY).
- [x] **(gate)** AppIcon 1024×1024 integrated (TF-RC1-ICON). M5.RC.2a: approved master in `AppIcon.appiconset`; build and rendering checks in §4 ([rc2a-evidence](rc2a-evidence/README.md)).
- [ ] **(gate)** iPad multi-scene (TF-RC1-IOS-SCENES): **either** multiple scenes disabled for the first release **and** `UIApplicationSupportsMultipleScenes = false` confirmed in the **built** release `Info.plist`, **or** cross-scene-safe persistence implemented and tested with two iPad windows in a separately reviewed change. iPad multi-window must not be approved without one of these.
- [ ] `ITSAppUsesNonExemptEncryption` set (the app uses no encryption), so each upload skips the export-compliance question.
- [ ] Launch screen: the generated empty `UILaunchScreen` shows the system background before Compose paints dark. Accept or style it.

**Requires an Apple Developer account and signing**
- [ ] **(gate)** Development team, distribution certificate and App Store provisioning profile
- [ ] **(gate)** `Product → Archive` succeeds; Organizer "Validate App" passes with no ITMS-91053 or icon errors
- [ ] TestFlight internal build installed on a physical iPhone and iPad

What can be verified without an account: simulator builds and tests, unsigned device builds, and the simulator smoke run. A free Apple ID can install on a personal device for 7 days.

## 3. Dataset freshness

- [x] Freshness check executed: 2026-10-16T19:37:52Z → **OUTDATED**. Product `1.60.1.70374`; latest publishable `1.60.1.70425` (`wow_classic_beta`, `isBgdl=false`).
- [x] **(gate)** TF-RC1-DATA closed:
  - **either** a dataset-refresh milestone adds `70425` (normalize, diff, sync, conformance, keep all older bundled builds);
  - **or** the owner records why `70374` stays the product build.
  - **M5.DATA.4 (2026-10-17):** refreshed to `1.60.1.70450`, which superseded `70425` (same talent data); freshness `CURRENT` at 2026-10-17T19:10:13Z. Effective on merge of the M5.DATA.4 PR ([data4-evidence](data4-evidence/README.md)).
- [ ] **(gate)** Freshness re-run within 24 h before the RC.3 tag, with the result recorded (CURRENT, or the documented decision).
- [ ] Never remove `1.60.1.70009`, `70245`, `70338`, `70374` or `70450` while saved builds may reference them.
- [ ] Never change `PRODUCT_BUNDLE` outside a dedicated, reviewed milestone.

## 4. App icon readiness (TF-RC1-ICON)

**Status: OPEN, P1, Android + iOS. Owner: Product/Design. Planned: M5.RC.2a.**

**M5.RC.2a (integration):** the owner-approved concept #3 (amber/gold talent tree on dark charcoal) is integrated byte-for-byte from the approved asset package on both platforms. Source, checksums, offline asset QA and the build/rendering scripts: [rc2a-evidence](rc2a-evidence/README.md). `TF-RC1-ICON` stays **OPEN** until platform rendering is verified (see *Validation in release builds* below).

**Icon policy (applies to M5.RC.1 → M5.RC.2a).**
1. **M5.RC.1 generates and integrates no icon.** This milestone only records the gap; `TF-RC1-ICON` stays **OPEN / P1**.
2. **M5.RC.2a requires original TalentForge artwork that the owner has approved** before it is integrated.
3. **AI-assisted concept creation is allowed**, provided the result is approved by the owner and passes a rights review (originality, the tool's terms and output rights, no resemblance to third-party marks) before integration.
4. **Not allowed:** Blizzard or World of Warcraft logos or artwork (class/talent icons included), any other borrowed or unauthorized artwork, downloaded stock icons without a cleared license, and placeholder icons shipped in a release build.

**Android launcher, adaptive icon** ([developer.android.com](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive)):
- [x] Foreground and background layers, 108×108 dp each. The inner 66×66 dp safe zone holds the logo, which is at least 48 dp and at most 66 dp. The outer 18 dp on each side is reserved for masking. Vector preferred.
  - M5.RC.2a: 432×432 px PNG layers (108 dp at 4 px/dp). Foreground 57.5 × 61.8 dp, farthest pixel 32.9 dp from the centre: inside the 33 dp safe radius. **Not vector:** the layers are bitmap-derived from the approved raster; a vector redraw is optional and only needed if device QA fails.
- [x] Monochrome layer for Android 13+ themed icons. M5.RC.2a: alpha-only silhouette, farthest pixel 32.6 dp from the centre.
- [x] `res/mipmap-anydpi-v26/ic_launcher.xml` with `<adaptive-icon>` (background, foreground, monochrome).
- [x] Legacy raster `mipmap-*dpi/ic_launcher.png` for API < 26 is not needed (`minSdk` is 26), but keep a fallback if the design team wants one for non-adaptive launchers. M5.RC.2a: the package's fallbacks are kept (mdpi…xxxhdpi, 48–192 px, opaque).
- [x] `android:icon="@mipmap/ic_launcher"` in the manifest. `android:roundIcon` is optional. M5.RC.2a: no `roundIcon`; the adaptive icon is masked by the launcher.

**RuStore store icon** ([rustore.ru](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication)):
- [ ] 512×512 px, PNG or JPG, ≤ 3 MB, with a background that fills the whole area: no transparent or empty edges.
  - M5.RC.2a: the approved package's `TalentForge-RuStore-512.png` meets this (512×512, opaque RGB, 181 KB; kept in `rc2a-evidence/source/`). Open until it is uploaded and previewed in the RuStore console (publication, out of scope).

**iOS / iPadOS** ([Apple HIG — App icons](https://developer.apple.com/design/human-interface-guidelines/app-icons)):
- [x] 1024×1024 px square master, unmasked (the system applies the rounded mask). Colour space sRGB or Display P3. M5.RC.2a: opaque RGB, no alpha channel, no embedded profile (treated as sRGB).
- [ ] Appearances: default, dark, clear and tinted. The system auto-generates variants that are not provided; providing designed dark/tinted variants is recommended.
  - M5.RC.2a: the approved package has **no** dark or tinted variant, and none was invented. The default icon is integrated; designed variants are an owner/design decision.
- [x] Integrated as `AppIcon.appiconset` (single size 1024), or as an Icon Composer `.icon` (Xcode 26), with `ASSETCATALOG_COMPILER_APPICON_NAME` matching. M5.RC.2a: `AppIcon.appiconset`, `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` (Debug and Release).

**Validation in release builds:**
- [ ] Android: `aapt2 dump badging <signed release apk>` shows a non-empty `icon=`. Lint reports no `MissingApplicationIcon`.
  - M5.RC.2a, **unsigned** builds: debug `icon='res/mipmap-anydpi-v26/ic_launcher.xml'`, release `icon='res/BW.xml'` (resource paths shortened by the release build). The adaptive XML references the background, foreground and monochrome drawables. Every packaged layer and fallback matches the source pixel-for-pixel: alpha and visible RGB are identical. The release build re-encodes the monochrome PNG as a palette PNG, which changes only the invisible RGB under fully transparent pixels. `MissingApplicationIcon` is gone. **Still open:** the same check on the signed artifact (TF-RC1-SIGNING).
- [ ] Android: visual check on API 26 and 33+ (themed on/off) and 36, on a circle-mask launcher and on a vendor launcher (TECNO HiOS).
  - [x] M5.RC.2a: emulator API 35, Pixel Launcher (circle mask), 420 dpi: the app drawer, the Home screen and the hotseat suggestion show the approved icon unclipped. With themed icons **on**, the monochrome silhouette is tinted correctly; the setting was restored to off afterwards ([screenshots](rc2a-evidence/mac/android/)). The isolated `.devcheck` build was used.
  - [ ] Still open: API 26, API 36, the TECNO HiOS launcher, any physical device.
- [ ] iOS: Archive → Validate App passes with no icon errors. Check the Home Screen in default, dark and tinted appearances on a device or simulator, plus Spotlight, Settings and the share sheet.
  - [x] M5.RC.2a: simulator Debug and Release builds succeed (Xcode, `CODE_SIGNING_ALLOWED=NO`) with no asset-catalog or icon warnings. The built `Info.plist` has `CFBundleIconName = AppIcon`. `Assets.car` holds the 1024 rendition for phone and pad, and the bundle holds `AppIcon60x60@2x` (120 px) and `AppIcon76x76@2x~ipad` (152 px). Both match the master: mean difference 3.1 and 2.6 out of 255. On the iPhone 17e simulator (iOS 26.4), the Home screen shows the icon in light and dark appearance with the system mask ([screenshots](rc2a-evidence/mac/ios/)).
  - [ ] Still open: Archive → Validate App (needs signing), tinted and clear appearances, Spotlight, Settings, the share sheet, iPad, a physical device.
- [ ] Store listings preview the icon correctly (RuStore console, App Store Connect).

## 5. Signing and versioning

- [ ] **(gate)** Android release keystore created **by the owner**, backed up, never committed. Use one key for the app's lifetime: a RuStore key change requires support and archives all versions.
- [x] Gradle `signingConfigs.release` reads from environment variables or `~/.gradle/gradle.properties` (not `local.properties`, which lives inside the checkout). M5.RC.2b; CI holds no production secret.
- [ ] APK vs AAB decided for RuStore. For AAB, the signing key is uploaded separately during upload. (M5.GALLERY.1 recommends APK; owner approval pending, S-1.)
- [ ] **(gate)** iOS: team, certificates and profiles (Apple account).
- [x] **(gate)** Android version numbers for the first release decided and the test values applied in a dedicated, reviewed change (M5.RELEASE.VERSION: `1` / `1.0.0`). The public `versionCode` raise is the §1 gate ([android-versioning.md](android-versioning.md)).
- [ ] iOS `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION`: not decided. iOS is not part of the first release; they stay `0.1.0` / `1`.

## 6. Store requirements

**RuStore.** Confirmed from the official publication and signing pages:
- [ ] Signed APK, or AAB plus signing key. Package name unique.
- [ ] Icon 512×512 (see §4).
- [ ] Phone screenshots (mandatory): at least 3, PNG/JPG, **up to 3 MB each**, max 2160×3840.
- [ ] Tablet screenshots (optional): if supplied, at least 3, PNG/JPG, **up to 5 MB each**.
- [ ] App name ≤ 30 characters; short description ≤ 80; full description ≤ 4000.
- [ ] Category, age rating, at least one developer contact.
- [ ] **Data Safety** declaration: no data collected or shared; local storage only; Android auto-backup per the `allowBackup` decision.
- [ ] Not verified: RuStore moderation rules on third-party IP or trademarks. The page could not be fetched; the owner must review it.

**App Store (iOS, later).** Confirmed from the Apple upcoming-requirements page:
- [x] Xcode 26 / iOS 26 SDK build (met by the toolchain used).
- [ ] Privacy manifest (§2), App Privacy label ("Data Not Collected"), new age-rating questionnaire answers, privacy-policy URL. The URL requirement is an assumption to confirm.

**Both stores:**
- [ ] **(gate)** Owner/legal review LR-1…LR-5: Blizzard artwork and text, trademarks and a non-affiliation disclaimer, class-artwork provenance, OSS notices, privacy policy. M5.RC.2b ships the in-app About surface (draft disclaimer, privacy summary, Android and iOS OSS notices); none of LR-1…LR-5 is closed by it. The iOS notices leave legal points open ([ios-oss-notices §5](rc2b-evidence/ios-oss-notices.md#5-open-items-not-resolved-here-ownerlegal-review)).
- [ ] Screenshots must show only approved branding. Do not generate marketing artwork in engineering milestones.

## 7. Manual device verification (to be executed before M5.RC.3)

Run on: **a physical Android phone in ru-RU** (e.g. the TECNO KM5n), **a physical Android phone or emulator in en-US**, **an Android tablet**, **an iPhone** and **an iPad** (physical or TestFlight).

- [ ] `python3 scripts/android_section_check.py --serial <phone> --runs 2`: section navigation, rotation, Back, relaunch
- [ ] RC1 device scenarios S1–S3 on the physical phone (procedure: [rc1-evidence/README.md](rc1-evidence/README.md#reproduction))
- [ ] Create a build for each of the nine classes. Check the artwork, names and descriptions in EN and RU.
- [ ] Rename, Duplicate, Share and Delete from Home Recent, All Builds and the editor, including cancel paths
- [ ] Share: Android chooser (send to a messenger), Copy and Paste into Import, cancelling the chooser, rotating while the sheet is open, backgrounding and returning
- [ ] iOS: share sheet, Copy, Paste with the system paste-permission prompt (allow and deny), iPad popover anchoring, cancel
- [ ] Import: valid code, damaged code, code from an older bundled build, oversized paste, cancel, confirm once (repeated taps)
- [ ] Lifecycle: background/foreground mid-edit; `adb shell am kill` while backgrounded; reopen the build
- [ ] Font scale 1.0, 1.5 and 2.0 on Home, All Builds, editor, import and dialogs (re-check TF-RC1-BADGE-200)
- [ ] TalkBack and VoiceOver: create a build, spend a point, open ⋯, rename, share, import
- [ ] Tablet layouts at 800×1280 and 1280×800 (re-check TF-RC1-TABLET-WIDTH)
- [ ] Performance: cold start ×10 (release build), pan/zoom frame stats, large library (≈300 builds)

## 8. Release candidate acceptance gates (M5.RC.3)

M5.RC.3 can pass only when **all** of these hold:

1. [ ] TF-RC1-ICON closed with the approved artwork, validated in signed release builds (§4).
2. [ ] TF-RC1-SIGNING closed; the signed artifact verifies.
3. [ ] TF-RC1-DATA closed by refresh or a documented owner decision; the freshness check was run within 24 h. (M5.DATA.4 closed it by refresh to `1.60.1.70450`; the 24 h re-check before the RC.3 tag is still open.)
4. [ ] For an iOS release, TF-RC1-IOS-PRIVACY closed and the archive validated in App Store Connect. For a RuStore-only RC.3 this gate is deferred, explicitly.
   - [ ] **4a.** For an iOS release, the iPad multi-scene gate (§2) is closed: multiple scenes disabled and verified in the built `Info.plist`, **or** cross-scene-safe persistence implemented and two-window tested in a separately reviewed change. M5.RC.3 must not approve iPad multi-window support without one of these.
5. [ ] No open P0 or P1. Each open P2 is accepted in writing or fixed.
6. [ ] Every UNVERIFIED critical scenario in [rc1-readiness §6](rc1-readiness.md#6-limitations-and-unverified-critical-scenarios) executed, with results recorded (§7 of this checklist).
7. [ ] Full validation on the RC commit: Python baseline, four sync checks, `jvmTest`, `desktopTest`, `iosSimulatorArm64Test` (forced, not from cache), `assembleRelease`/`bundleRelease`, `lintRelease`, iOS links and `xcodebuild`.
8. [ ] GitHub Actions restored and green on the RC commit, or its unavailability documented with item 7 as the substitute.
9. [ ] Owner/legal sign-off on LR-1…LR-5 and the store metadata.
10. [ ] Version numbers decided; the release commit is tagged only after this checklist is complete. (Android decided in M5.RELEASE.VERSION; the public `versionCode` raise (§1) and the tag are still open.)

## 9. Huawei AppGallery publication (M5.GALLERY.1)

Added by M5.GALLERY.1 ([appgallery/readiness.md](appgallery/readiness.md)). Sections 1–8 above stay
valid unchanged: RuStore (§1, §6) and iOS (§2) requirements are not replaced. AppGallery receives the
**same** Android product: the same `applicationId`, the same signed universal APK, the same
`versionCode` and the same certificate as RuStore ([multi-store-signing.md](appgallery/multi-store-signing.md)).
Google Play is deferred (#35). 🔑 = needs the owner's Huawei Developer account.

**Package and signing** (shared with §1 and §5; nothing extra for AppGallery)
- [x] No Google Mobile Services, Firebase, Play Billing or HMS dependency; `arm64-v8a` packaged (64-bit requirement); `minSdk 26` (static evidence: [appgallery/evidence/mac](appgallery/evidence/mac/), re-checked on `b242cd6` in [store1-evidence](store1-evidence/mac/))
- [x] No `INTERNET` permission in the release APK (NET-1 = B, §1; re-verified on the temporary-key publishable APK, [NET-1 evidence](net1-evidence/README.md)). Re-check on the owner-signed APK before upload. *(At `b242cd6` the release APK declared `INTERNET`.)*
- [ ] **(gate)** Owner approves the signing strategy: one owner-held RSA key, signed universal APK for both stores, no AGC App Signing, no RuStore AAB key upload ([multi-store-signing §1](appgallery/multi-store-signing.md#1-recommendation))
- [ ] **(gate)** Release keystore created, backed up twice offline, recovery drill done (§5 above and [multi-store-signing §5](appgallery/multi-store-signing.md#5-keystore-creation-offline-backup-recovery-owner-procedure--not-executed-here))
- [ ] **(gate)** The APK uploaded to AppGallery is byte-identical to the RuStore APK; `apksigner` SHA-256 equals the owner's recorded fingerprint; not the debug certificate; no `application-debuggable`
- [ ] After publication: the AppGallery-delivered APK's SHA-256 equals the owner's fingerprint (H-13)

**Account and app record** ([testing-and-publication §2](appgallery/testing-and-publication.md#2-appgallery-publication-requirements-official-sources))
- [x] Huawei developer account identity verification: **owner reports COMPLETE** (2026-10-17; not independently verified)
- [ ] 🔑 **VERIFY IN CONSOLE**: the account can create the Android app in AppGallery Connect and submit it for release
- [ ] 🔑 Android app created in AppGallery Connect: APK, *Mobile phone*, name `TalentForge`, category **App** (irreversible), no project, package `io.github.alexeykravtsov95.talentforge`
- [ ] 🔑 Release countries chosen explicitly; Chinese mainland excluded (or its copyright certificates obtained)

**Listing and assets**
- [ ] Owner approves the EN/RU copy ([store-listing-en](appgallery/store-listing-en.md), [store-listing-ru](appgallery/store-listing-ru.md)); Variant B (trademark names) only after LR-2 review
- [x] Store icon file meets AppGallery (216 or 512 px PNG ≤ 2 MB) and RuStore (512 px ≤ 3 MB, opaque) ([assets-checklist §2](appgallery/assets-checklist.md#2-icon))
- [ ] EN and RU portrait screenshot sets (3–8 each, 1080×1920, ≤ 2 MB, no system UI) captured from the signed release build ([assets-checklist §3](appgallery/assets-checklist.md#3-screenshots))
- [ ] 🔑 Icon and screenshots previewed in AGC for both localizations

**Privacy, rating, declarations** ([privacy-and-compliance](appgallery/privacy-and-compliance.md))
- [ ] **(gate)** Privacy policy (RU + EN) reviewed, owner fields filled, hosted at a public URL; backup paragraph matches the `allowBackup` decision (TF-RC1-ANDROID-CONFIG)
- [ ] Owner/legal decision on an in-app privacy link (AppGallery Review Guidelines 7.1, RuStore moderation rule 5.1)
- [ ] 🔑 Privacy tags: *Collect personal data = No* (after owner confirmation)
- [ ] 🔑 Age-rating questionnaire answered from the content facts; AI function declaration answered (icon concept was AI-assisted)
- [ ] **(gate)** LR-1…LR-5 signed off (same gate as §6 "Both stores"; AppGallery Review Guidelines §9 and RuStore rules 4.1/6.4 make it explicit)

**Testing and release** ([testing-and-publication §5–§6](appgallery/testing-and-publication.md#5-device-test-plan))
- [ ] **(gate)** Huawei device scenarios H-1…H-14 executed on the signed release APK (UNVERIFIED in M5.GALLERY.1: no Huawei device)
- [ ] 🔑 First AppGallery release as an Open Testing version; then the formal version as an AGC update with a **new APK and a higher `versionCode`**, the same APK released in RuStore in the same window ([testing-and-publication §6.1](appgallery/testing-and-publication.md#61-open-testing--formal-production-release))
- [ ] Phased release is not available for the first version (updates only)

**Observation recorded by M5.GALLERY.1:** the freshness check on 2026-10-17T15:30:54Z reported
**OUTDATED** with `1.60.1.70450` as the latest publishable build (product `1.60.1.70374`); §3's
TF-RC1-DATA gate is unchanged and still open. *(M5.DATA.4 later refreshed the product to
`1.60.1.70450`; see §3.)*

## 10. Android release hardening (M5.RC.2b)

Added by M5.RC.2b ([evidence](rc2b-evidence/README.md)). Nothing here was signed with a production
key, uploaded or published.

**Done in engineering**
- [x] Fail-closed release signing in Gradle: settings only from the environment or `~/.gradle`; partial or invalid settings, a keystore inside the checkout, the debug keystore or a committed `talentforge.release.*` key fail every release build; no debug fallback ([owner guide](android-release-signing.md)).
- [x] `:composeApp:assemblePublishableRelease` is the only upload path: complete signing settings + expected certificate SHA-256 required; the APK is staged only when `scripts/verify_release_apk.py` passes; report with the APK SHA-256 and no secrets.
- [x] Signing self-test with a temporary key (14/14 scenarios, incl. no stale APK after a failed publishable attempt), run in `mobile-ci`.
- [x] TF-RC1-LINT resolved (0 errors).
- [x] Backup off (`allowBackup="false"` + data-extraction / full-backup rules), privacy text updated.
- [x] About / legal screen (RU/EN): version, data version, independent-project statement (draft), privacy summary, Android OSS notices + Apache-2.0 text, privacy-policy link slot (empty until approved).
- [x] Android OSS notices generated from `releaseRuntimeClasspath` (122 modules, all Apache-2.0 by POM; no NOTICE file shipped) plus the stdlib jar's ThreeTen-derived `kotlin.time` attributed with the bundled BSD-3-Clause text (M5.LEGAL.OSS.1); full-content drift check (coordinates, component mapping, licences re-read from POMs) in `mobile-ci`.
- [x] iOS OSS notices (TF-RC2B-IOS-NOTICES) generated from `iosArm64CompileKlibraries` (80 modules) plus the code linked into Skiko (Skia, HarfBuzz, ICU, libjpeg-turbo, libpng, libwebp, Wuffs, zlib, Expat, Adobe DNG SDK, Piex) and the stdlib's third-party code; 16 verbatim licence texts; archive-level drift check in `mobile-ci` (macOS). Evidence: [ios-oss-notices](rc2b-evidence/ios-oss-notices.md).

**Owner actions before M5.RC.3** (🔑 = owner only)
- [ ] 🔑 Approve the signing strategy (S-1) and the APK format.
- [ ] 🔑 Create the RSA 4096 release key outside every checkout; record the SHA-256 ([guide §1](android-release-signing.md#1-create-the-key-once-)).
- [ ] 🔑 Two offline encrypted backups + recovery drill ([guide §2](android-release-signing.md#2-back-it-up-before-the-first-upload-)).
- [ ] 🔑 Configure the four signing settings and `cert_sha256` on the release machine ([guide §3](android-release-signing.md#3-configure-the-build-)).
- [x] 🔑 Decide the first public `versionCode` / `versionName` (applied in a reviewed change). Decided 2026-10-20: test `1` / `1.0.0` (applied in M5.RELEASE.VERSION), public `2` / `1.0.0` ([android-versioning.md](android-versioning.md)).
- [ ] Raise `versionCode` for the public build in a reviewed change and record every consumed code in the ledger ([procedure](android-versioning.md#5-raising-versioncode-before-the-public-release)).
- [ ] 🔑 Build `assemblePublishableRelease` from the release commit; record the report's APK SHA-256; the same file goes to both stores.
- [ ] Legal/owner: LR-1…LR-5 sign-off, including the in-app draft disclaimer (LR-2), the OSS-notice mechanism (LR-4) and the privacy policy text, hosting and URL (LR-5). M5.LEGAL.LINK wired the owner-published RU/EN Android policy URLs into the Android About screen (`privacyPolicyUrlOf`, `legal.mythweld.com`); iOS shows no link until an iOS policy is published; the sign-off of the policy text and the developer contacts remains.
- [ ] Owner: the remaining TF-RC1-ANDROID-CONFIG decisions (R8, ABI filtering, `localeConfig`, `targetSdk`).
- [ ] Device runs of the signed APK (M5.RC.3): install/update, About screen, backup behaviour (`adb shell bmgr` / a vendor transfer on a Huawei and a TECNO device). **Not run in M5.RC.2b.**

**Still blocking an iOS release:** TF-RC1-IOS-PRIVACY and TF-RC1-IOS-SCENES. **TF-RC2B-IOS-NOTICES** is resolved: the iOS About
screen lists the framework's notices. Its legal points stay with LR-4, and the screen was checked on the iOS simulator; a physical-device check is part of the M5.RC.3 iOS runs.
