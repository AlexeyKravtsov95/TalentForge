# M5.RC.1 — Release readiness assessment

| | |
|---|---|
| Milestone | M5.RC.1 — Pre-release QA and readiness audit (audit only; no product changes) |
| Audited commit | `main` @ `5648941849a8ac7b9a5b291423e95f533e6292ca` (merge of PR #32, M5.SHARE.2) |
| Audit dates | 2026-10-16 19:37 UTC (pass 1) and 2026-10-17 03:10 UTC (pass 2) |
| Environment | Apple M4 Pro Mac, macOS 26.3.1, Xcode 26.4.1, JDK 17.0.19; Android emulator API 35; iOS 26.4 simulators. Full list: [test matrix](rc1-test-matrix.md#execution-environments-actually-used) |
| Companion documents | [Defects](rc1-defects.md) · [Test matrix](rc1-test-matrix.md) · [Release checklist](rc1-release-checklist.md) · [Evidence](rc1-evidence/README.md) |

## 1. Executive summary

**Overall decision: NO-GO.** TalentForge is not ready for a public release on any platform.

The engineering core is in good shape:
- Every automated suite passes when it is actually executed:
  - Python: 1048 tests
  - shared JVM: 338 tests
  - Compose desktop UI: 663 tests (36 opt-in screenshot tests skipped)
  - Kotlin/Native on the iOS simulator: 188 tests
- All four dataset sync checks pass.
- Debug and release builds compile for Android (APK + AAB) and iOS (simulator and device, unsigned).
- On an Android emulator, the highest-risk data-integrity scenarios were driven through the real UI and verified after process death. These were rapid spends then immediate Back, spend + Undo then Back, and spend then immediate Activity recreation. All persisted exactly.
- No P0 defect was found.

The release is blocked by packaging, data and store prerequisites, not by core logic.

**Confirmed P1 blockers:**
1. **TF-RC1-ICON**: no application icon on Android or iOS. Known and pre-existing; stays OPEN; resolution planned in M5.RC.2a.
2. **TF-RC1-SIGNING**: the Android release artifact is unsigned and no release signing exists.
3. **TF-RC1-DATA**: the dataset freshness check reports **OUTDATED**. `1.60.1.70425` is publishable and the product bundle is `1.60.1.70374`.
4. **TF-RC1-IOS-PRIVACY**: there is no `PrivacyInfo.xcprivacy`. This applies to the App Store only.

Several critical scenarios remain **UNVERIFIED** because no physical device was available during pass 2 and no TalkBack/VoiceOver session was run. The highest of these are native share/clipboard on both platforms, a physical Android phone, Russian on a device, and screen readers. They must be executed before M5.RC.3.

**The missing application icon alone prevents an unconditional GO.**

## 2. Platform readiness

| Area | Android (RuStore, first) | iOS (subsequent) |
|---|---|---|
| Builds | ✅ debug, release APK, release AAB build | ✅ simulator Debug/Release, device Release (unsigned) build |
| Automated tests | ✅ all pass (common + JVM) | ✅ 188 Kotlin/Native tests on simulator pass |
| App icon | ❌ P1 TF-RC1-ICON | ❌ P1 TF-RC1-ICON |
| Signing | ❌ P1 TF-RC1-SIGNING (no key, unsigned output) | ⛔ BLOCKED: no Apple Developer team or signing identity |
| Store compliance | ⚠️ RuStore Data Safety, 512 px icon, ≥3 screenshots, descriptions to prepare | ❌ P1 privacy manifest; App Store metadata, privacy label and age rating to prepare |
| Runtime evidence | ⚠️ emulator only (en-US): core flows + autosave PASS; physical phone BLOCKED | ⚠️ launch smoke only (iPhone + iPad simulators); no interaction flows driven |
| Native sharing | ❓ UNVERIFIED | ❓ UNVERIFIED |
| Config | ⚠️ P2 lint failure, no R8, `allowBackup` default, `0.1.0`/`1` | ⚠️ P2 multi-scene enabled in the built plist |
| **Assessment** | **Not ready.** Closest to release: after the icon, signing, the dataset decision, the legal review and a physical-device pass, it can become an RC. | **Not ready.** Same blockers, plus the privacy manifest, Apple account/signing, archive validation and physical-device testing. |

What iOS can do without Apple Developer membership:
- simulator builds and tests (done);
- unsigned device builds (done);
- running on a personal device with a free Apple ID (7-day provisioning). This was not done.

What requires membership:
- distribution signing;
- archive upload;
- TestFlight;
- privacy-manifest validation by App Store Connect;
- submission.

## 3. Highest risks

1. **No physical-device evidence for RC1.** Every on-device result comes from an emulator. Physical-device behaviour (vendor ROM, Russian locale, performance) is unverified, and so is the full iOS interaction.
2. **Native share and clipboard are untested at runtime** on both platforms (EX-06, EX-07). This is the newest user-facing surface (M5.SHARE.2).
3. **Dataset currency.** A newer publishable client build exists. If it changes talents, shipped builds could mislead players. See TF-RC1-DATA.
4. **Rights to game content.** Talent icons and text are extracted from the Blizzard client. Trademarks appear without a disclaimer. See LR-1…LR-4. Nobody can sign this off but the owner or legal counsel.
5. **Write-serialization invariant.** Library writes other than autosave and rename are not serialized by the autosave lock. They are safe today only because leaving the editor flushes first (SR-1). On iPad multi-window that invariant does not hold (SR-2, TF-RC1-IOS-SCENES).
6. **No CI.** GitHub Actions executes zero steps (TF-RC1-CI). Regressions are caught only by manual Mac runs.

## 4. Blocking findings

| ID | Priority | Platforms | Owner | Resolution milestone |
|---|---|---|---|---|
| TF-RC1-ICON | P1 | Android + iOS | Product/Design | **M5.RC.2a** |
| TF-RC1-SIGNING | P1 | Android | Owner / Release eng. | M5.RC.2b |
| TF-RC1-DATA | P1 gate | both | Data / Product | dataset-refresh milestone (M5.DATA.4) or owner decision, before M5.RC.3 |
| TF-RC1-IOS-PRIVACY | P1 (iOS only) | iOS | Mobile eng. | M5.RC.2b (before any App Store upload) |

There are also non-defect gates that must be closed before publication:
- legal and owner review LR-1…LR-5;
- store metadata;
- the UNVERIFIED critical scenarios in §6.

P2 findings (8) are listed in [rc1-defects.md](rc1-defects.md#p2--non-blocking).

## 5. Evidence summary

| Area | Result |
|---|---|
| Python (ruff, format, mypy, pytest) | PASS. pytest 1048 passed, 11 deselected (`integration`) |
| Dataset / visuals / tooltips / localization sync | PASS (4/4) |
| `:shared:jvmTest` (forced rerun) | 338 / 0 failed |
| `:composeApp:desktopTest` (forced rerun) | 663 / 0 failed / 36 skipped (opt-in) |
| `:shared:iosSimulatorArm64Test` (forced rerun) | 188 / 0 failed |
| Android `assembleDebug` / `assembleRelease` / `bundleRelease` | PASS; 20.1 / 16.9 / 16.8 MB |
| Android `lintRelease` | FAIL (P2, likely false positive) |
| iOS framework links + `xcodebuild` ×3 | PASS |
| Emulator autosave scenarios S1–S3 | PASS (3/3) |
| Emulator cold start (debug) | 542–662 ms over 5 runs |
| iOS simulator launch (iPhone 17e, iPad Air 11) | PASS (launch smoke only) |
| Dataset freshness | **OUTDATED** (2026-10-16T19:37:52Z) |
| GitHub Actions | infrastructure-blocked (0 steps) |

## 6. Limitations and unverified critical scenarios

- **Build cache.** Pass 1 Gradle test tasks were restored `FROM-CACHE`. They were discarded and re-executed with `--rerun --no-build-cache` in pass 2. Only pass 2 counts are reported.
- **Physical Android phone.** The TECNO KM5n (ru-RU) was attached in pass 1 but not in pass 2. `android_section_check.py` and the RC1 scenarios could not run on it (**BLOCKED**).
- **Unverified critical scenarios:**
  - native share sheet, clipboard and paste permission (Android and iOS);
  - Russian UI on a device;
  - TalkBack and VoiceOver;
  - background/foreground and process death during a pending write;
  - storage-failure on a device;
  - all nine classes on a device;
  - iPad and Android tablet interaction;
  - large-library performance;
  - physical-device performance.
- **iOS.** Only launch was verified on simulators. There is no signed build, no TestFlight and no physical device.
- **Store requirements.** RuStore requirements were checked against the official publication and signing pages. The RuStore moderation-rules page could not be fetched, so moderation criteria are **not verified**. Apple's required-reason codes for User Defaults could not be rendered from Apple's site and must be confirmed at implementation time.
- **Legal.** This audit identifies legal *risks*. It does not give, or imply, any legal clearance.

## 7. Recommendation and roadmap

1. **Overall:** NO-GO for public release. Do not publish.
2. **Android:** not ready. It is the nearest platform; its path to an RC is short once the P1s are closed and a physical-device pass is done.
3. **iOS:** not ready. It needs Apple membership, signing and a privacy manifest in addition to the shared blockers.
4. **Confirmed defects:** P0 = 0, P1 = 4 (one iOS-only), P2 = 8. Suspected risks: 5. Owner/legal items: 5.
5. **Unverified critical scenarios:** see §6.
6. **Sequence:**
   - **M5.RC.2a:** branding and icon (TF-RC1-ICON). Design approval comes first, integration second.
   - **M5.DATA.4** (proposed, can run in parallel): refresh to `1.60.1.70425` or record the decision not to (TF-RC1-DATA).
   - **M5.RC.2b:** engineering blockers and selected P2s (see checklist §8).
   - **M5.RC.3:** final RC verification on physical devices, with CI restored.
7. **M5.RC.2a icon and branding requirements:** see [checklist §4](rc1-release-checklist.md#4-app-icon-readiness-tf-rc1-icon). The icon must be original TalentForge artwork with no Blizzard/WoW artwork. It needs an Android adaptive icon (108 dp layers, 66 dp safe zone, monochrome layer), a RuStore 512×512 opaque PNG/JPG, and an iOS 1024×1024 master with default, dark and tinted appearances.
8. **Proposed M5.RC.2b tasks:**
   - release signing config read from local or env secrets (TF-RC1-SIGNING);
   - iOS `PrivacyInfo.xcprivacy` (TF-RC1-IOS-PRIVACY);
   - single-scene decision for iPad (TF-RC1-IOS-SCENES);
   - fix or justify the lint error and add a lint baseline (TF-RC1-LINT);
   - Android release hardening decisions: R8, `allowBackup`, `localeFilters`, `localeConfig`, version numbers (TF-RC1-ANDROID-CONFIG);
   - an in-app About/legal screen with a non-affiliation disclaimer and OSS notices, if the owner decides on one (LR-2, LR-4);
   - optionally TF-RC1-DRAFT-TITLE, TF-RC1-BADGE-200, TF-RC1-TABLET-WIDTH, TF-RC1-COPY-NAME;
   - optionally route all library writes through one serialized writer (SR-1).
9. **Conditions before M5.RC.3:**
   - all P1s closed (TF-RC1-DATA closed by refresh or recorded decision);
   - legal and owner review signed off;
   - CI green, or the infrastructure block documented with a full local run on the RC commit;
   - every UNVERIFIED critical scenario in §6 executed on a physical Android phone (ru and en) and, for iOS, on a physical device or TestFlight;
   - a freshness check run within 24 h of the RC.
