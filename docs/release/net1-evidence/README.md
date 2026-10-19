# NET-1 = B: release builds without `INTERNET` (evidence)

Owner decision NET-1 ([store1 audit §5.4](../store1-release-audit.md#54-options-for-the-first-release-owner-decision-net-1)),
2026-10-19: **B**. While the production OTA channel is off, release builds request no network access; debug builds keep
`android.permission.INTERNET` for the staging OTA channel and its device tests.

Taken on 2026-10-19 on branch `fix/m5-net-1-release-no-internet` (stacked on `docs/m5-store-1-release-audit` @ `d2d5c3a`,
which is `main` @ `b242cd6` plus the M5.STORE.1 audit), on the owner's Mac with Android build-tools 37.0.0. Local
paths are shown as `~`, `<worktree>` or `<scratch>`. No production key was involved; the signed APKs used here were
signed with temporary self-test keys that were deleted afterwards. The APKs themselves are not committed.

## What changed

| Piece | Effect |
|---|---|
| `mobile/composeApp/src/androidRelease/AndroidManifest.xml` | `<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>` for the `release` build type |
| `android.sourceSets.release.manifest.srcFile(...)` in `composeApp/build.gradle.kts` | `:composeApp:sourceSets` already reported this path for `release`, but the manifest merger ignored the file until it was set explicitly (first build of this change: the guard below failed because `INTERNET` was still merged) |
| `:composeApp:checkReleaseInternetPermission` | reads the release variant's **merged** manifest. Fails unless it requests `INTERNET` exactly when `talentforge.ota.android.production.enabled` is `true` (the channel a release build reads). Every `packageRelease`/`packageReleaseBundle` depends on it, so `assembleRelease` and `assemblePublishableRelease` cannot produce an APK that breaks the rule |
| `:composeApp:verifyReleaseInternetRules` | self-test of the rule on fixed manifests (8 cases + a malformed manifest that must fail), run before `desktopTest` like `verifyOtaSettingsRules` |

## Files

| File | What it shows |
|---|---|
| [`aapt2-release-unsigned.txt`](aapt2-release-unsigned.txt) | `assembleRelease` (unsigned technical APK): only the AndroidX signature permission `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; `INTERNET` occurs 0 times in the binary manifest |
| [`aapt2-publishable-tempkey.txt`](aapt2-publishable-tempkey.txt) | the APK staged by `assemblePublishableRelease` (temporary key, [`publishable-tempkey.sh`](../store1-evidence/publishable-tempkey.sh)): the same, `INTERNET` 0 times. Its generated OTA settings have `androidProduction.enabled = false` |
| [`publishable-tempkey-report.json`](publishable-tempkey-report.json), [`publishable-tempkey-cert.txt`](publishable-tempkey-cert.txt) | the validator's PASS report for that APK and the deleted temporary certificate's digest |
| [`aapt2-debug.txt`](aapt2-debug.txt) | `assembleDebug`: `android.permission.INTERNET` is requested (staging OTA) |
| [`manifest-merger-release-INTERNET.txt`](manifest-merger-release-INTERNET.txt) | the release merger decision: `INTERNET` ADDED from `androidMain`, REJECTED by `tools:node` from `androidRelease` |
| [`guard-scenarios.txt`](guard-scenarios.txt) | the guard on real builds: production OTA enabled with the removal → FAIL; removal gone with OTA off → FAIL before `packageRelease`; production OTA enabled without the removal (the option-C shape) → PASS |
| [`signing-selftest-summary.txt`](signing-selftest-summary.txt) | `scripts/release_signing_selftest.sh`: 14/14 PASS with the guard in the release path |

## Commands

```bash
cd mobile
./gradlew :composeApp:verifyReleaseInternetRules :composeApp:assembleDebug :composeApp:assembleRelease
"$ANDROID_HOME/build-tools/37.0.0/aapt2" dump permissions composeApp/build/outputs/apk/release/composeApp-release-unsigned.apk
"$ANDROID_HOME/build-tools/37.0.0/aapt2" dump permissions composeApp/build/outputs/apk/debug/composeApp-debug.apk
cd .. && scripts/release_signing_selftest.sh
bash docs/release/store1-evidence/publishable-tempkey.sh "$PWD" <scratch-out>   # then aapt2 on the staged APK
```

## Limits

- The owner-signed APK does not exist yet. `scripts/verify_release_apk.py` does not check permissions, so run
  `aapt2 dump permissions` on the owner-signed APK before upload (rc1-release-checklist §9).
- No new emulator network run was made. The M5.STORE.1 run measured no traffic with `INTERNET` declared. Without the
  permission, Android refuses the app's network sockets.
- The disabled OTA client code (`HttpURLConnection`) is still in the release package. Enabling production OTA
  (option C) must delete the removal and change the privacy policy, listings, in-app privacy texts and store
  declarations in the same release; the guard fails the build until the manifest is changed.
