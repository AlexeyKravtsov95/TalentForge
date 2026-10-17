# M5.RC.1 — Release checklist

How to read this checklist:
- It is a living checklist for M5.RC.2a → M5.RC.2b → M5.RC.3.
- `[x]` = verified in M5.RC.1 with evidence.
- `[ ]` = open.
- Items marked **(gate)** must be checked before M5.RC.3 can pass.
- Defect IDs refer to [rc1-defects.md](rc1-defects.md).

## 1. Android release checklist (RuStore first)

**Identity and versioning**
- [x] `applicationId` = `io.github.alexeykravtsov95.talentforge` (aapt2 badging, E1-60)
- [x] `minSdk 26`, `targetSdk 36`, `compileSdk 36` (`libs.versions.toml`, badging)
- [ ] **(gate)** `versionCode` / `versionName` decided for the first public release. They are currently `1` / `0.1.0`, which is an owner decision. Do not change them without a release decision.
- [x] Display name `TalentForge` (`android:label`)

**Manifest and permissions**
- [x] Only the launcher `MainActivity` is exported.
- [x] No dangerous or user-visible permissions. Only the AndroidX signature permission `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. No INTERNET, consistent with offline-first.
- [ ] `android:allowBackup` decided explicitly and reflected in the privacy text (TF-RC1-ANDROID-CONFIG).
- [x] **(gate)** `android:icon` set to the approved adaptive icon (TF-RC1-ICON). M5.RC.2a: `android:icon="@mipmap/ic_launcher"`; packaging and rendering checks in §4 ([rc2a-evidence](rc2a-evidence/README.md)).

**Build and packaging**
- [x] `assembleDebug`, `assembleRelease`, `bundleRelease` succeed (E1-43/44/45).
- [x] Localization resources packaged: `values` + `values-ru` compose resources (desktop tests and EMU en-US run).
- [ ] **(gate)** Release artifact signed with the owner's long-lived key. Keystore and passwords stay outside git (TF-RC1-SIGNING).
- [ ] **(gate)** `apksigner verify --print-certs` passes on the exact artifact to upload.
- [ ] `lintRelease` exits 0, or a justified baseline is committed (TF-RC1-LINT).
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
- [ ] **(gate)** TF-RC1-DATA closed:
  - **either** a dataset-refresh milestone adds `70425` (normalize, diff, sync, conformance, keep all older bundled builds);
  - **or** the owner records why `70374` stays the product build.
- [ ] **(gate)** Freshness re-run within 24 h before the RC.3 tag, with the result recorded (CURRENT, or the documented decision).
- [ ] Never remove `1.60.1.70009`, `70245`, `70338` or `70374` while saved builds may reference them.
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
- [ ] Android: visual check on API 26 and 33+ (themed on/off) and 36, on a circle-mask launcher and on a vendor launcher (TECNO HiOS).
- [ ] iOS: Archive → Validate App passes with no icon errors. Check the Home Screen in default, dark and tinted appearances on a device or simulator, plus Spotlight, Settings and the share sheet.
- [ ] Store listings preview the icon correctly (RuStore console, App Store Connect).

## 5. Signing and versioning

- [ ] **(gate)** Android release keystore created **by the owner**, backed up, never committed. Use one key for the app's lifetime: a RuStore key change requires support and archives all versions.
- [ ] Gradle `signingConfigs.release` reads from `local.properties` or environment variables. CI secrets come later, once CI works.
- [ ] APK vs AAB decided for RuStore. For AAB, the signing key is uploaded separately during upload.
- [ ] **(gate)** iOS: team, certificates and profiles (Apple account).
- [ ] **(gate)** Version numbers for the first release decided and applied in a dedicated, reviewed change (Android `versionCode`/`versionName`; iOS `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION`).

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
- [ ] **(gate)** Owner/legal review LR-1…LR-5: Blizzard artwork and text, trademarks and a non-affiliation disclaimer, class-artwork provenance, OSS notices, privacy policy.
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
3. [ ] TF-RC1-DATA closed by refresh or a documented owner decision; the freshness check was run within 24 h.
4. [ ] For an iOS release, TF-RC1-IOS-PRIVACY closed and the archive validated in App Store Connect. For a RuStore-only RC.3 this gate is deferred, explicitly.
   - [ ] **4a.** For an iOS release, the iPad multi-scene gate (§2) is closed: multiple scenes disabled and verified in the built `Info.plist`, **or** cross-scene-safe persistence implemented and two-window tested in a separately reviewed change. M5.RC.3 must not approve iPad multi-window support without one of these.
5. [ ] No open P0 or P1. Each open P2 is accepted in writing or fixed.
6. [ ] Every UNVERIFIED critical scenario in [rc1-readiness §6](rc1-readiness.md#6-limitations-and-unverified-critical-scenarios) executed, with results recorded (§7 of this checklist).
7. [ ] Full validation on the RC commit: Python baseline, four sync checks, `jvmTest`, `desktopTest`, `iosSimulatorArm64Test` (forced, not from cache), `assembleRelease`/`bundleRelease`, `lintRelease`, iOS links and `xcodebuild`.
8. [ ] GitHub Actions restored and green on the RC commit, or its unavailability documented with item 7 as the substitute.
9. [ ] Owner/legal sign-off on LR-1…LR-5 and the store metadata.
10. [ ] Version numbers decided; the release commit is tagged only after this checklist is complete.
