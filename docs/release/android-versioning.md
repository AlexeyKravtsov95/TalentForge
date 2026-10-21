# Android versioning — first release 1.0.0 (M5.RELEASE.VERSION)

| | |
|---|---|
| Milestone | M5.RELEASE.VERSION: Android release versioning. No new features |
| Stores | RuStore and Huawei AppGallery (one signed universal APK); Google Play (AAB with a separate upload key). iOS is not released and its version is not changed here |
| Source of truth | `mobile/composeApp/build.gradle.kts`, `android.defaultConfig { versionCode, versionName }`. Nothing else sets or derives the Android version (no CI override, no git-derived code) |
| Related | [rc1 checklist §1, §5, §8](rc1-release-checklist.md), [multi-store signing](appgallery/multi-store-signing.md), [AppGallery §6.1](appgallery/testing-and-publication.md#61-open-testing--formal-production-release), [Play signing §6.4](google-play/signing.md#64-versions-across-the-three-stores), [Play closed testing §6](google-play/closed-testing.md) |

## 1. Owner decision (2026-10-20, revised 2026-10-21)

- First test build: `versionName = "1.0.0"`, `versionCode = 1`.
- `versionCode = 2` (`versionName = "1.0.0"`) is used for the **local production OTA smoke**
  ([OTA enablement §4](../ota/android-production-enablement.md#4-ручные-проверки-владельца-не-выполнены-не-отмечать-pass-без-запуска)):
  an update over an installed `1` build on the owner's own devices. It is **not** uploaded to any
  store console (§3).
- The first public `versionCode` is **not decided**. The owner decides it after testing is
  finished, under the rules in §2. This revision supersedes the 2026-10-20 plan of public
  `2` / `1.0.0`.
- All three stores share **one** `versionCode` sequence.
- RuStore and AppGallery receive **the same signed universal APK** file.
- Google Play receives an AAB signed with a **separate upload key**.
- iOS is not released now; `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` stay `0.1.0` / `1`.

Applied by this milestone: `versionName` `0.1.0` → `1.0.0`; `versionCode` stays `1`. `applicationId`,
`minSdk`, `targetSdk`, signing, OTA, the manifest and the on-device storage format are unchanged.

Applied by M5.OTA.SMOKE.VERSION (2026-10-21): `versionCode` `1` → `2`; `versionName` stays `1.0.0`.
Nothing else changes: OTA client, game data, signing, keys and the manifest sources are untouched.

## 2. Rules

1. **One code per release build.** The publishable APK and the Play bundle of one release are built
   from **one commit** with **one** `versionCode`/`versionName`. Their reports must agree (the
   self-test scenario `metadata` checks this).
2. **Strictly increasing, never reused.** A code is never lowered and never given to different
   bytes. Build each code once and keep the files; do not upload a rebuild of a code that was
   already uploaded.
3. **Test uploads consume codes.** A code is consumed as soon as an artifact carrying it is uploaded
   to any console (a Play track, including internal/closed tests and discarded drafts; AppGallery
   Open Testing; any RuStore upload), or is handed to anyone outside the release machine. It stays
   consumed even if that release is never published.
4. **The public APK is a new build.** The APK for the AppGallery formal version and the RuStore
   release is a **new** build with a **higher** `versionCode` than every APK or bundle uploaded
   before it, even if the code is unchanged since the test build ([AppGallery
   §6.1](appgallery/testing-and-publication.md#61-open-testing--formal-production-release)). The
   same rule applies to the Play production bundle ([closed testing
   §7](google-play/closed-testing.md#7-applying-for-production-access-)).
5. **One file, both stores.** RuStore and AppGallery get the byte-identical APK (same SHA-256, same
   `versionCode`), published in the same release window.
6. **`versionName` is not unique.** Test and public builds of the first release are all `1.0.0`. A
   different `versionName` (for example `1.0.1`) is a separate owner decision.
7. **Self-test artifacts consume nothing.** Files named `*-SELFTEST-NOT-FOR-UPLOAD*` are signed with
   temporary keys, are never uploaded and do not consume a code.

## 3. Planned sequence and ledger

| `versionCode` | `versionName` | Build | Destination |
|---|---|---|---|
| 1 | 1.0.0 | test: `TalentForge-1.0.0-1.apk` + `TalentForge-1.0.0-1.aab`, one commit | APK → AppGallery Open Testing (and any RuStore test upload); AAB → Play closed testing |
| 2 | 1.0.0 | local production OTA smoke: OTA ON APK signed by the owner, installed over `1` on the owner's devices | **No store upload.** Not a store test build and not the public build |
| not decided | 1.0.0 | public: `TalentForge-1.0.0-<N>.apk` + `TalentForge-1.0.0-<N>.aab`, one commit | APK → RuStore release **and** AppGallery formal version (same file); AAB → Play production |

The owner decides the public `N` after testing is finished (§1), by the rules in §2 and the
procedure in §5.

Ledger. The owner fills it in when an artifact is built or uploaded. Only public facts go here
(no paths, passwords or keystore details). An empty row means nothing has been built or uploaded
with that code yet.

| `versionCode` | Commit / tag | Purpose | APK SHA-256 | AAB SHA-256 | Uploaded to (date) |
|---|---|---|---|---|---|
| 1 | | test | | | |
| 2 | | local production OTA smoke (no store upload) | | | none (local only) |

## 4. Test build (`versionCode 1`) and OTA smoke build (`versionCode 2`)

`versionCode 1` builds come from commits before M5.OTA.SMOKE.VERSION (up to `3d8df68`, the merge of
PR #66). The owner builds `assemblePublishableRelease` and `bundlePublishablePlay` from the reviewed
release commit
([rc3 readiness §7](rc3-readiness.md#7-exact-build-and-verification-commands-owner-machine-release-commit))
and records both SHA-256 values in §3 before the first upload.

From M5.OTA.SMOKE.VERSION on, `main` builds `2` / `1.0.0`. The owner builds the OTA ON APK as in
[OTA enablement §3](../ota/android-production-enablement.md#3-сборка-ota-on-для-магазинов-выполняет-только-владелец-),
installs it locally over the `1` build for the §4 checks there, and records its SHA-256 in the §3
ledger. It is **not** uploaded to RuStore, AppGallery or Google Play.

## 5. Raising `versionCode` before the public release

Preconditions: the test round is finished, the owner has given a go decision, and every fix is
merged to `main`.

1. **Find the next code.** Take the highest code in the §3 ledger and check it against every
   console: Play Console (*App bundle explorer* and every track, including drafts),
   AppGallery Connect (*Version information*, including Open Testing), RuStore Console (versions).
   `N` = highest + 1. If the ledger and a console disagree, stop and reconcile first.
2. **Make one reviewed change.** Branch from current `main` (for example
   `release/m5-version-code-N`). Change only `versionCode = N` in
   `mobile/composeApp/build.gradle.kts` (`defaultConfig`), keep `versionName = "1.0.0"`, and update
   the §3 ledger row for `N` as "public (planned)". Nothing else goes in this PR.
3. **Verify without production keys.** Run the engineering checks with a Gradle user home that has
   no `talentforge.release.*` / `talentforge.play.*` settings (§6). Expected:
   - `aapt2 dump badging` of the unsigned release APK: `versionCode='N' versionName='1.0.0'`,
     `package: name='io.github.alexeykravtsov95.talentforge'`;
   - `bundletool dump manifest` of the `play` bundle: `android:versionCode="N"`,
     `android:versionName="1.0.0"`;
   - `scripts/release_signing_selftest.sh` and `scripts/play_signing_selftest.sh` pass (staged
     names `TalentForge-1.0.0-N-SELFTEST-NOT-FOR-UPLOAD.*`);
   - `:shared:jvmTest`, `:composeApp:desktopTest` pass.
4. **Merge, then tag.** After review and merge, the owner tags the merge commit only once the
   release checklist is complete ([rc1 checklist §8](rc1-release-checklist.md#8-release-candidate-acceptance-gates-m5rc3), item 10).
5. 🔑 **Build once on the release machine** from the tag:
   `./gradlew :composeApp:assemblePublishableRelease :composeApp:bundlePublishablePlay`. Expect
   `TalentForge-1.0.0-N.apk` and `TalentForge-1.0.0-N.aab`, both reports `"result": "PASS"`
   (never `SELFTEST-PASS`). Re-check the exact files with the expected version:

   ```bash
   python3 scripts/verify_release_apk.py --apk TalentForge-1.0.0-N.apk \
     --expected-cert-sha256 <APP_SIGNING_SHA256> \
     --expected-version-code N --expected-version-name 1.0.0 --report /tmp/apk-report.json
   python3 scripts/verify_play_bundle.py --aab TalentForge-1.0.0-N.aab \
     --expected-upload-cert-sha256 <UPLOAD_SHA256> --app-signing-cert-sha256 <APP_SIGNING_SHA256> \
     --expected-version-code N --expected-version-name 1.0.0 --report /tmp/play-report.json
   shasum -a 256 TalentForge-1.0.0-N.apk TalentForge-1.0.0-N.aab
   ```

6. 🔑 **Record** the commit, tag and both SHA-256 values in §3.
7. 🔑 **Upload.** The **same** APK file goes to RuStore and to AppGallery (an AGC *Update* with
   *Version for open testing* = **No**), in the same release window. The AAB goes to Play
   production once production access is granted.
8. **If a fix is needed after upload,** go back to step 1 with `N + 1`. The new APK replaces the
   pending submission in **both** RuStore and AppGallery, and the new bundle replaces it on Play.

## 6. Engineering checks without production keys

On a machine whose `~/.gradle/gradle.properties` holds the owner's `talentforge.release.*` or
`talentforge.play.*` settings, a plain `assembleRelease` signs with the production key, and both
self-test scripts refuse to run. Do engineering checks (and any agent run) with a separate Gradle
user home that has no such file. Do not edit the owner's file:

```bash
export GRADLE_USER_HOME=<scratch dir without gradle.properties>   # may clone ~/.gradle/caches
cd mobile
./gradlew :composeApp:assembleRelease :composeApp:bundlePlay      # unsigned technical artifacts
cd ..
bash scripts/release_signing_selftest.sh
bash scripts/play_signing_selftest.sh
```

Without signing settings, `assembleRelease` and `bundlePlay` produce **unsigned** technical
artifacts only. They are never uploaded.

## 7. Where the version shows

- About screen: "Version 1.0.0 (2)" / "Версия 1.0.0 (2)", read from the installed package.
- OTA HTTP User-Agent `TalentForge/1.0.0`. Production OTA is off in release builds unless the owner
  enables it at build time ([OTA enablement](../ota/android-production-enablement.md)).
- The UI tests and screenshot tests use their own fixed `AppVersion("0.1.0", 1)` fixture, which is
  independent of the build. It is not changed here.
