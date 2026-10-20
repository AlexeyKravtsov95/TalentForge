# M5.PLAY.1 — Google Play release readiness

| | |
|---|---|
| Milestone | M5.PLAY.1, Google Play release preparation (stores: Google Play, RuStore, Huawei AppGallery) |
| Branch / base | `release/m5-play-1-google-play` on `main` @ `f0d0fcb`, with the NET.1 commits carried as the first commits (`5b5d9a0`, `bfefde1`; PR #53 had been merged into the M5.STORE.1 branch, not into `main`) |
| Date | 2026-10-19; Google requirements read the same day ([§5](#5-google-play-console-requirements)) |
| Safety | No production key was created, read or used. Nothing was uploaded to Play Console, submitted, posted or published. Every signed artifact was made with **temporary self-test keys**, deleted after each run |
| Documents | [signing](signing.md) · [data safety](data-safety.md) · [closed testing](closed-testing.md) · [listing EN](store-listing-en.md) · [listing RU](store-listing-ru.md) · [assets](assets-checklist.md) · [evidence](evidence/README.md) |

## 1. Decision

| Gate | Verdict | Why |
|---|---|---|
| **Engineering** (pipeline, signing separation, Play technical requirements) | **GO** | Every check in §2 passes. The Google Play bundle path is reproducible and fail-closed, and the RuStore/AppGallery APK path is unchanged and re-verified |
| **Closed testing on Google Play** | **NO-GO** until the owner actions P-1…P-9 (§3) are done | app signing keystore created by the owner but unverified, upload key not confirmed ([RC.3 §2.1](../rc3-readiness.md#21-production-signing-status-release-gate-open)); Play App Signing must be set to the owner's key before the first upload; privacy policy hosted since M5.LEGAL.LINK but its legal review (LR-5) is open ([RC.3 §3](../rc3-readiness.md#3-privacy-policy-and-the-three-stores)); App content not filled; feature graphic and screenshots missing; device verification |
| **Production on Google Play** | **NO-GO** | needs the closed test (12+ testers for 14 days), production access, and the owner's explicit acceptance of the open legal risk (§6). LR-1…LR-5 stay **OPEN** |

## 2. Engineering PASS/FAIL

| # | Item | Result | Evidence |
|---|---|---|---|
| E-1 | Publishable Google Play bundle: `:composeApp:bundlePublishablePlay` builds, verifies (`scripts/verify_play_bundle.py`) and stages `outputs/publishable-google-play/TalentForge-<v>-<code>.aab` + report | **PASS** | self-test `two-keys` ([evidence](evidence/play-signing-selftest/)) |
| E-2 | RuStore/AppGallery APK path not broken: `assemblePublishableRelease` | **PASS** | release self-test 14/14 ([evidence](evidence/release-signing-selftest/)); `assembleRelease` unsigned build unchanged |
| E-3 | App signing key and upload key never confused | **PASS** | self-test scenarios `two-keys`, `distinct`, `same-cert`, `same-key`, `aab-app-key`, `apk-upload-key`, `release-aab`, `play-apk` |
| E-4 | Bundle signature, upload certificate SHA-256, `applicationId`, `versionCode`, `versionName` checked | **PASS** | `verify_play_bundle.py` checks; scenarios `two-keys`, `wrong-cert`, `metadata` |
| E-5 | Debug, testOnly, staging OTA and wrong configurations excluded | **PASS** | scenarios `debug-aab`, `play-none`, `play-partial`, `no-app-cert`; `checkReleaseOtaSettings` runs before every `play` build; validator `not-debuggable`/`not-test-only` |
| E-6 | Unsigned `bundleRelease`/`bundlePlay` never counts as publishable | **PASS** | scenarios `unsigned-aab`, `release-aab` |
| E-7 | A failed attempt leaves no publishable artifact behind | **PASS** | scenario `stale`; the release self-test's `stale-*` scenarios |
| E-8 | A self-test artifact can never pass as a release | **PASS** | scenario `not-selftest`; reports say `SELFTEST-PASS`, files are named `*-SELFTEST-NOT-FOR-UPLOAD` |
| E-9 | Test keys and outputs deleted; no secret in logs, reports or `build/` | **PASS** | scenarios `cleanup`, `no-secrets` (both self-tests) |
| E-10 | `targetSdk ≥ 36` (Google Play since 2026-08-31) | **PASS** | bundle manifest `targetSdkVersion 36`; validator `target-sdk` |
| E-11 | 64-bit | **PASS** | `arm64-v8a` + `x86_64` for `armeabi-v7a` + `x86`; validator `abi-64-bit-coverage` |
| E-12 | 16 KB pages, including the packaged native library | **PASS (static)** | §4 |
| E-13 | Release permissions: no `INTERNET` (NET-1 = B) | **PASS** | bundle and APK request only `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; validator `permissions-allowed`; `checkPlayInternetPermission` |
| E-14 | Production signing secrets kept out of CI | **PASS** | `mobile-ci` runs both self-tests with temporary keys only; no secret is configured or read |
| E-15 | Play icon format | **PASS** | 32-bit RGBA PNG, 512 × 512, 206 KB, pixels identical to the approved icon ([assets §2](assets-checklist.md#2-app-icon-format-check-and-conversion)) |
| E-16 | Runtime of a Play-delivered install (signing on device, 16 KB device, accessibility, edge-to-edge) | **NOT RUN** | needs the closed test ([test plan T-2, T-13, T-14, T-18](closed-testing.md#5-test-plan-for-talentforge)) |
| E-17 | Play Console pre-launch report and App Bundle Explorer checks | **NOT RUN** | appear only after an upload (owner) |

Validation commands on this branch (all run on 2026-10-19):

| Command | Result |
|---|---|
| `ruff check .` / `ruff format --check .` / `mypy` / `pytest` (clean venv of this worktree, `pip install -e ".[dev]"`) | all checks passed / 274 files formatted / no issues in 85 files / **1455 passed**, 11 deselected |
| `mypy --strict scripts/verify_release_apk.py scripts/verify_play_bundle.py` (not in the baseline) | no issues |
| `python3 scripts/sync_mobile_datasets.py --check` | in sync (50 files of 5 builds) |
| `./gradlew :shared:jvmTest --rerun` · `:composeApp:desktopTest --rerun` (`verifyOtaSettingsRules`, `verifyReleaseInternetRules`) | BUILD SUCCESSFUL, both tasks executed (not from cache): `jvmTest` 371 tests, 0 failures; `desktopTest` 785 tests, 0 failures, 53 skipped (the opt-in screenshot/remote checks that skip by default) |
| `./gradlew :composeApp:assembleDebug` | BUILD SUCCESSFUL |
| `./gradlew :composeApp:assembleRelease :composeApp:bundlePlay` (no keys) | BUILD SUCCESSFUL; unsigned technical APK and bundle; `checkReleaseInternetPermission` and `checkPlayInternetPermission` passed |
| `scripts/release_signing_selftest.sh` | **PASS, 14/14** |
| `scripts/play_signing_selftest.sh` | **PASS, 20/20** (including `play-apks-16kb` with bundletool 1.18.3; on CI without bundletool that one scenario is SKIP) |

Release facts (unchanged by this milestone; the owner decides the first public version):

| Fact | Value |
|---|---|
| `applicationId` | `io.github.alexeykravtsov95.talentforge` (frozen; the same in all three stores) |
| `versionCode` / `versionName` | `1` / `0.1.0` at M5.PLAY.1 (placeholders). Since M5.RELEASE.VERSION: `1` / `1.0.0` for the closed-test bundle; the production bundle gets `2` / `1.0.0` or the next unused code ([android-versioning](../android-versioning.md)) |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 36 / 36 |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| Native libraries | only `libandroidx.graphics.path.so` (`androidx.graphics:graphics-path` 1.0.1, pulled in by Compose) |
| Permissions (release APK and Play bundle) | `io.github.alexeykravtsov95.talentforge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` only |
| AGP / Gradle / Kotlin | 8.13.2 / 9.5.1 / 2.4.20 |
| Sizes (self-test build) | APK 19 814 734 bytes; AAB 19 823 740 bytes |

Known noise, not caused by this milestone: `lintVitalAnalyzeRelease` (and `…Play`) prints
"Module was compiled with an incompatible version of Kotlin … 2.4.0, expected 2.2.0". Lint's
embedded Kotlin is older than the project's 2.4.20. The task succeeds; it is a warning from lint's
analyser, and the same lines appear on `release` without any M5.PLAY.1 change.

## 3. Owner actions

| # | Action | Blocks | Reference |
|---|---|---|---|
| P-1 | Decide: Play App Signing with **the owner's own key** (not Google-generated, not hybrid), before the first bundle upload | closed testing | [signing §3](signing.md#3-google-plays-default-for-a-new-app-and-the-owners-choice) |
| P-2 | Create the app signing key (RSA 4096), back it up twice, run the recovery drill (existing S-2, S-3) | everything | [signing guide §1–§2](../android-release-signing.md) |
| P-3 | Create the **separate** upload key; record both fingerprints (they must differ) | closed testing | [signing §4](signing.md#4-creating-the-keys-) |
| P-4 | Verify the physical device in the Play Console app; check account identity details | making the app available | [closed testing §1](closed-testing.md#1-account-checks-) |
| P-5 | Create the app; set Play App Signing with PEPK; check U-1…U-3 on screen; verify both fingerprints on *App signing* | first upload | [signing §5.2](signing.md#52-steps-owner-in-play-console-nothing-here-was-executed) |
| P-6 | Publish the privacy policy at a public URL (legal review LR-5) — the owner-published `legal.mythweld.com` pages (RU/EN) are wired into the app since M5.LEGAL.LINK; the owner confirms the hosting and the sign-off | closed testing | [data safety §3](data-safety.md#3-privacy-policy) |
| P-7 | Fill *App content* (Data safety, ads, app access, target audience, IARC, financial features, health, government) | closed testing | [data safety §2–§4](data-safety.md#2-proposed-data-safety-answers) |
| P-8 | Store listing EN/RU; feature graphic; phone screenshots from the release build; icon rights record (L-6) | closed testing | [listing](store-listing-en.md), [assets](assets-checklist.md) |
| P-9 | Configure the upload settings; run `bundlePublishablePlay` from the tagged release commit. Versions decided (M5.RELEASE.VERSION): closed test `1` / `1.0.0`; the production bundle needs a raised `versionCode` | first upload | [signing §6](signing.md#6-building-the-google-play-bundle), [android-versioning](../android-versioning.md) |
| P-10 | Closed test: Google Group, 18–20 testers, 14 days, test plan, log | production access | [closed testing §3–§6](closed-testing.md#3-closed-testing-track-) |
| P-11 | Apply for production access | production | [closed testing §7](closed-testing.md#7-applying-for-production-access-) |
| P-12 | Accept or reduce the legal risk in writing for Google Play (§6) | production | [LEGAL.1 options](../legal1-release-options.md) |

Irreversible steps, all left to the owner:
- the choice of the app signing key, which locks once an open-testing or production release
  rolls out;
- the package name, set by the first upload;
- Free, which can never become paid;
- policy acceptances.

## 4. 16 KB memory page size

Google's rule ([page sizes guide](https://developer.android.com/guide/practices/page-sizes), updated
2026-10-16):
- apps targeting Android 15+ must support 16 KB pages on 64-bit devices;
- from **1 Feb 2027**, updates that do not support them cannot be released.

| Check | Result |
|---|---|
| ELF `LOAD` alignment of `libandroidx.graphics.path.so`, all four ABIs | `2**14` (0x4000) for every segment (`llvm-objdump -p`, `llvm-readelf -Wl`) |
| `GNU_RELRO` | (VirtAddr + MemSiz) is 0x6000 (arm64-v8a) / 0x7000 (x86_64), not a 16 KB multiple. RELRO is alone in its writable `LOAD` segment and the next writable segment starts at or after 0x8000, which the guide explicitly calls **compliant**. The validators now check this rule ([evidence](evidence/README.md)) |
| Bundle configuration | `uncompressNativeLibraries: enabled`, `PAGE_ALIGNMENT_16K` (AGP 8.13.2 ≥ 8.5.1) |
| APKs generated from the staged bundle | `zipalign -c -P 16 -v 4` "Verification successful" for the `arm64-v8a` and `x86_64` splits; libraries stored uncompressed (bundletool 1.18.3, self-test `play-apks-16kb`) |
| Dependency | `androidx.graphics:graphics-path` **1.0.1**. Google's release notes do not mention 16 KB for any version, so this verdict rests on the inspection above, not on a release note. 1.1.0 exists (2026-05-06), but there is no reason to upgrade in this milestone |
| Runtime on a 16 KB device | **NOT RUN** (closed-test T-18) |

## 5. Google Play Console requirements

| Requirement | Status for TalentForge |
|---|---|
| Android App Bundle for a new app; signed with the upload key (`jarsigner`) | ready (§2) |
| Play App Signing; new apps default to Google-generated hybrid keys | owner provides own key before the first upload (P-1, P-5); U-1…U-3 unverified |
| Target API 36 (since 2026-08-31; extensions to 2026-11-01 on request) | 36 |
| 64-bit for every 32-bit ABI | yes |
| 16 KB pages (blocking for updates from 2027-02-01) | statically compliant (§4) |
| Privacy policy (also for apps that collect nothing), in Console and in the app | hosted at `legal.mythweld.com` (RU/EN), names Google Play, RuStore and AppGallery, linked from About on Android ([RC.3 §3](../rc3-readiness.md#3-privacy-policy-and-the-three-stores)); legal review LR-5 and the Console entry remain (P-6) |
| Data safety form (closed testing included; only internal testing exempt) | proposed answer: no data collected or shared ([data safety §2](data-safety.md#2-proposed-data-safety-answers)) |
| Ads, app access, target audience, IARC content rating, financial features, health, government declarations | proposed ([data safety §4](data-safety.md#4-other-app-content-declarations-proposed)) |
| Store listing: name ≤ 30, short ≤ 80, full ≤ 4000; icon 512 32-bit PNG ≤ 1024 KB; feature graphic 1024 × 500; 2–8 phone screenshots | texts ready (11 / 67 / 1 465 EN; 11 / 72 / 1 626 RU); icon ready; feature graphic and screenshots missing |
| Personal account: device verification; closed test 12 testers × 14 days; production access application | plan ready ([closed testing](closed-testing.md)) |
| Permissions declarations; accessibility API declaration | none needed (no sensitive permission, no `AccessibilityService`) |
| Android 16 behaviour for targetSdk 36 (edge-to-edge enforced, predictive back, resizability on large screens) | covered by the test plan T-12, T-14; not a Console form |

## 6. Legal constraints (M5.LEGAL.1)

- **Owner decision recorded here (2026-10-19, M5.PLAY.1 task):** release preparation continues with
  the current game content, and the owner understands the risk of moderation action and of claims.
- This **does not** establish any right in Blizzard content. **LR-1…LR-5 remain OPEN**; none is
  marked CLEARED. Permission for categories 1–4 of the
  [content inventory](../legal1-content-inventory.md) is **NOT ESTABLISHED**
  ([rights assessment §1](../legal1-rights-assessment.md#1-findings-in-one-page)). L-1 (choice of
  path A/B/C) remains the owner's.
- Google Play rules that apply (read 2026-10-19):
  - the [Intellectual property policy](https://support.google.com/googleplay/android-developer/answer/9888072)
    lists "Marketing images from movies, television, or video games" and fan art "indistinguishable
    from the original" among violations, and says "Developers may be required to provide evidence of
    their rights to use copyrighted content";
  - the [Impersonation policy](https://support.google.com/googleplay/android-developer/answer/9888374)
    says "Don't imply that your app is related to or authorized by someone that it isn't".
  - Google documents no safe harbour for fan apps; a disclaimer is not a licence.
- What the drafts do about it:
  - the listing uses the trademark-free Variant A;
  - the disclaimer states independence;
  - no "official" wording anywhere;
  - the feature graphic must be original art.

  None of this removes the risk carried by the bundled icons, texts and class artwork, or by the
  screenshots that show them.
- Consequence for the plan: a policy rejection is possible at closed-test review, at the
  production-access review or later after a complaint. It could come with account-level
  consequences, which also affect the owner's other Play apps. P-12 is the owner's written acceptance
  of that risk for Google Play, or the move to option B/C.

## 7. Residual risks

| # | Risk | Severity | Mitigation / owner |
|---|---|---|---|
| R-1 | IP/impersonation enforcement on Google Play (game icons, texts, class art, screenshots) | **high** | §6; P-12; LR-1…LR-5 |
| R-2 | Play's hybrid-signing default and the own-key path for a **new** app are not fully documented (U-1…U-3). If Play insists on a Google-held classical key, the three-store, one-certificate scheme fails | **high** | P-5 step 4: stop and decide before the first upload; never let the first bundle go up with a Google-generated key |
| R-3 | A future Play **key upgrade** (rotation lineage, annual for Android 17+) would give Play installs a certificate history that RuStore/AppGallery installs lack; cross-store updates could break | medium | do not request or accept a key upgrade without analysis (signing §3) |
| R-4 | Losing the app signing key ends RuStore/AppGallery updates (Play keeps working with its copy) | medium | backups and recovery drill (P-2) |
| R-5 | If fewer than 12 testers stay opted in on any day of the window, the 14-day requirement is not met and production access is delayed; Google may also require more testing | medium | 18–20 testers, daily count, log (closed testing §4) |
| R-6 | Android developer verification (enforced in four countries from 2026-09-30, worldwide in 2027) also concerns the RuStore/AppGallery APK installs | medium | register the package and the app signing key in Play Console before 2027 (closed testing §1) |
| R-7 | 16 KB compliance verified statically only | low | T-18 on a 16 KB device/emulator |
| R-8 | The APK and the bundle are two builds of one commit (`release` and `play` build types), not one file; their code is identical by construction, not by hash | low | same commit, same `versionCode`; self-test `metadata` compares the reports |
| R-9 | The validators decode the bundle's protobufs themselves | low | decoding pinned against the real manifest that `bundletool dump` read; Play Console re-checks every upload |
| R-10 | Play policy pages change without dates; the facts here are from 2026-10-19 | low | re-read §5 before each submission |

## 8. Signing scheme for the three stores

[signing §1](signing.md#1-the-scheme-in-one-table), in short:

| | RuStore | AppGallery | Google Play |
|---|---|---|---|
| Upload | signed universal APK | the same APK, byte-identical | AAB signed with the **upload key** |
| Signed for users with | app signing key (owner) | app signing key (owner) | app signing key (owner's copy at Play App Signing) |
| Store-managed signing | none | none (no AGC App Signing) | Play App Signing with the owner's key, never Google-generated |
| Build task | `assemblePublishableRelease` | `assemblePublishableRelease` | `bundlePublishablePlay` |
| Validator | `verify_release_apk.py` | `verify_release_apk.py` | `verify_play_bundle.py` |
