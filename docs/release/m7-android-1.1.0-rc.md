# M7.RELEASE.1 — Android 1.1.0 (6) release candidate

**Status: Android RC source version configured; NO-GO for distribution until signed artifacts and in-place upgrade acceptance are verified.** Release preparation only; no merge, APK/AAB upload, Google Play production publication or OTA publication is authorized by this report.

| Field | Value / evidence |
|---|---|
| Source examined | `main` at `f0a17c006c62d7ae0e5f818908c5e29390514056` (2026-11-14) |
| PR base | `main`; release candidate branch `release/m7-android-1.1.0-rc` |
| Android version in PR #146 | **`1.1.0 (6)` configured** in Android `defaultConfig`, after owner console confirmation |
| Previous `main` version | `1.0.0 (5)` at baseline; PR updates Android to `1.1.0 (6)` |
| Android applicationId | `io.github.alexeykravtsov95.talentforge` (read-only source inspection) |
| Android signing | Existing app-signing key for universal APK; distinct existing Play upload key for AAB; production certificate matches **not yet verified on RC** |
| iOS | Out of scope; no iOS version, sources, keys or signing changes |
| CI baseline | [CI run 43581893182](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/43581893182) PASS; [mobile-ci run 43581893164](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/43581893164) PASS, both on the source SHA |
| Store delivery history | GitHub release `android-store-5`, `docs/release/android-versioning.md`, `docs/release/android-public-stores.md` |
| APK SHA-256 / AAB SHA-256 (RC) | **NOT BUILT / NOT RECORDED**. Never copy SHA values from v1.0.0 (5) |
| Owner version allocation | **CONFIRMED by owner 2026-11-14**: highest used code 5 in Play, RuStore and AppGallery; not independent API verification |
| Remaining owner acceptance | **OPEN** — signed APK/AAB, install-over-installed-5, final pre-upload collision recheck |

## 1. Version allocation — owner-confirmed, release not published

**On 2026-11-14, the owner checked all three consoles (Google Play, RuStore, Huawei AppGallery) and explicitly confirmed the maximum consumed `versionCode` in each is 5.** This is a user-supplied owner readback, **not independent API evidence**. This confirmation resolves the previous version allocation blocker; PR #146 now sets Android `versionCode = 6`, `versionName = "1.1.0"`. The application ID and Android signing configuration are unchanged.

**Code 6 is allocated in source but not uploaded, submitted or published.** Before the **first** upload, owner must repeat version occupancy checks including Play's all tracks and drafts, RuStore drafts/versions and AppGallery Open Testing/pending submissions. If code 6 or higher was subsequently consumed, stop; do not reuse a code or upload either artifact.

Version 5 is a separate historical artifact: source `9e9d9e188c5a0a10c2e573640b0e0d171f9eebf5`, approved APK SHA-256 `ec2d62fe461d688cfc2c7aa674bfc44d4ef6deac4d6c35480984170d45fa0526`. That hash identifies the **old** APK only, not this candidate. The `android-store-5` GitHub journal, RuStore draft `2064929703` and the dated 2026-11-04 AppGallery readback remain unresolved and must be reconciled separately. Neither store is newly marked published, and old APK bytes must not be reuploaded or reused.

## 2. QA evidence at baseline (not equivalent to RC device acceptance)

| Area | Result | Evidence / limitation |
|---|---|---|
| Shared repository migration v1 → v2 | **PASS — automated baseline** | `LocalBuildMigrationGoldenTest`; first mutation migrates, read alone does not rewrite; `shared:jvmTest` in mobile-ci |
| Real Android SharedPreferences migration | **PASS — isolated emulator baseline** | `AndroidBuildMigrationTest` under suffix `.lib2check`; three legacy records, favorite writes, reopen, commit-failure rollback, corrupt/future payload safety; Android library job PASS in mobile-ci |
| Existing builds, names, talents, favorites | **PARTIAL — regression tests** | Golden migration compares pre-/post- records; test scope does not prove in-place upgrade of a *user-installed* signed 1.0.0 app |
| Search, favorites, filtering | **PASS — automated baseline** | `BuildLibrarySearchTest`, `BuildLibraryFiltersUiTest`, repository favorites migration tests; Linux mobile-ci green |
| Sorting and grouping | **PASS — automated baseline** | `BuildLibrarySortingTest`, `BuildLibrarySortingUiTest` and Android screenshot test; read-only sorting/grouping behavior; Linux and Android library jobs green |
| Editor, talent Requirements, Show | **PASS — automated baseline / device smoke pending** | `TalentRequirementsUiTest`, `TalentRequirementsAuditTest`, `TalentRequirementConnectorUiTest`, `TalentTreeShowFocusTest`; live user navigation still needed |
| RU/EN, small screens, accessibility | **PASS — regression tests / manual gate pending** | Sorting/grouping UI test runs RU/EN at widths 320, 360, 393 and 412 dp with font scale 1× and 2×. CI uploads EN/RU 393-dp Android screenshots; manually inspect entire application at small width and large font |
| OTA policy and staging exclusion | **PASS — configuration rules on baseline; actual RC APK unverified** | Pinned production origin/keyring in Gradle; `checkReleaseOtaSettings` fails on non-disabled Android/iOS staging settings, `checkReleaseInternetPermission` couples INTERNET permission to OTA enablement |
| Production OTA live installation/download | **OPEN — RC device acceptance** | Baseline OTA workflows passed; need signed 1.1.0 install and readback of production catalog/data. Staging must not be used |
| applicationId | **PASS — static source** | Android `defaultConfig` remains `io.github.alexeykravtsov95.talentforge` |
| Signing identity (APK and Play upload key) | **OPEN — signed artifact validation** | Verify signed APK against existing app signing fingerprint and AAB against existing upload certificate (without revealing secret material) |
| In-place upgrade 1.0.0 → 1.1.0 | **OPEN — BLOCKING owner gate** | Real installed previous production build, upgrade without uninstall/clear data, verify persisted records and same package/signing identity |

The baseline **CI** and **mobile-ci** were successful on `f0a17c0`. The final RC SHA requires new CI and mobile-ci, including Android and iOS compatibility. Successful CI does **not** imply signed 1.1.0 deliverables or an in-place owner upgrade have been verified. No new app feature, migration, data-store or OTA-signature implementation is included in this PR.

## 3. Exact RC build and technical verification (owner environment only)

Use the existing Gradle build tasks; **do not introduce a delivery pipeline**. Keep production key material solely in the owner's protected signing environment. Avoid printing properties, aliases, passwords, private keys, credential files or secret values. On the owner machine/protected signing environment, **after exact-source CI is green**, and only using the existing credentials without exposing them:

```bash
cd mobile
# Existing signing settings supplied privately, NOT in the repository or command arguments.
# All staging variables (Android AND iOS) must be unset.
export TALENTFORGE_OTA_ANDROID_PRODUCTION_ENABLED=true
./gradlew :composeApp:assemblePublishableRelease :composeApp:bundlePublishablePlay --console=plain
cd ..
shasum -a 256 mobile/composeApp/build/outputs/publishable/TalentForge-1.1.0-6.apk
shasum -a 256 mobile/composeApp/build/outputs/publishable-google-play/TalentForge-1.1.0-6.aab
git rev-parse HEAD
```

- [ ] Both tasks finish PASS. A failed task **never** counts as a publishable artifact.
- [ ] Validator reports PASS for `versionCode=6`, `versionName=1.1.0`, expected applicationId, non-debuggable universal APK and Play AAB, correct signing certificate chain and app-vs-upload-key identities.
- [ ] Compare generated OTA configuration and packaged release artifacts: Android production OTA enabled, pinned `https://ota.mythweld.com` and production public keyring only; **no staging URL/key/CA, debug or test signing**, even from iOS staging environment variables.
- [ ] Check merged release and Play manifests for INTERNET permission with OTA ON. No staged or old artifact reused.
- [ ] Record **source SHA**, artifact paths/file names, **APK SHA-256**, **AAB SHA-256**, app signing certificate SHA-256, Play upload certificate SHA-256, and UTC timestamp in the acceptance report. No private key data.
- [ ] Verify the build source SHA equals the **final reviewed PR head**; after any future owner-approved merge, verify the intended release source and store-delivery gate against `main`. Do not claim the PR head already equals `main`.

### 3.1 Owner acceptance: non-destructive upgrade

A new isolated `.lib2check` test install is **not** enough. Use an actual app with package `io.github.alexeykravtsov95.talentforge` installed from the previously distributed 1.0.0 version and signed compatibly with the new APK (or update through Play testing/Production with matching App Signing identity). Before installing, record in a privacy-safe checklist at least two build names, class/spec/talent allocation, one favorite and one non-favorite. Install the new signed build **without uninstalling or clearing app data**, restart and check:

- [ ] Existing build IDs/count, names, classes, allocated talent ranks and favorites retained after v1 → v2 migration and app restart.
- [ ] Search, filters, favorite toggle, sort and group change only intended state; editor/Requirements/Show work on legacy and new builds.
- [ ] Repeat in RU and EN; narrow screen and enlarged font; inspect 393-dp EN/RU screenshots and spot-check 320-dp if possible.
- [ ] Network connected: About shows OTA status and production game data loads/updates using HTTPS. Reopen offline to verify cached builds remain accessible.
- [ ] Capture non-sensitive build identity / before-after evidence, tested device/API, test date and explicit owner **PASS** or **FAIL**.

If APK install reports signature mismatch, do **not** uninstall existing data to make the test pass. Resolve the certificate lineage, test through the correct Play channel or mark release **NO-GO**. The detailed [upgrade acceptance checklist](m7-android-1.1.0/upgrade-acceptance.md) is mandatory: device provenance, explicit test steps for both v1 and v2 stored data, artifact fingerprint, pre/post invariants, OTA, RU/EN and sign-off.

## 4. Store readiness / separation of delivery

**Never use the existing `store-delivery.yml` Play test-track route to publish Play Production.** `include_play` in that workflow selects the existing test track, not a production release.

| Channel | Planned artifact | Required separate owner action | Status |
|---|---|---|---|
| Google Play Production | `TalentForge-1.1.0-6.aab`, Play upload-signed | Inspect Play app signing/upload certificates and version occupancy; separately create/update Production release in Play Console, select rollout, upload this AAB, verify Console readback/status | **NOT SUBMITTED** |
| RuStore | `TalentForge-1.1.0-6.apk`, app signing key | Reconcile version 5 journal first; read current console state and available code; only then start **new** approved release under the existing APK-store flow after merge and owner authorization | **NOT SUBMITTED** |
| Huawei AppGallery | **same exact APK bytes/SHA-256** as RuStore | Reconcile version 5 journal; check published/draft/test packages and current status; use the existing approved APK-store flow after merge and owner authorization | **NOT SUBMITTED** |

The historical `android-store-5` release is **not** proof of code-5 publication in either store. Its last documented provider inspection is dated 2026-11-04; current status requires fresh readback. Never reuse its `approved.apk` for 1.1.0 or re-upload that old file. A prepared or accepted upload is not `PUBLISHED`; require store confirmation of exact version and package identity.

## 5. Decision / exit criteria

**NO-GO for distribution** until every item is satisfied: Android 1.1.0 (6) source committed and CI/mobile-ci green on the final PR SHA, including Android and iOS compatibility; production OTA-on signed APK/AAB validated with both SHA-256 values recorded; Android installed-version migration explicitly accepted by owner; RU/EN small-screen and editor flows accepted; no staging endpoints/keys; source and signing identity matched; versionCode 6 rechecked in all consoles immediately before upload; previous APK-store history reconciled separately; independent Play Production plan acknowledged. **Version allocation was owner-approved on 2026-11-14; it does not prove current store availability at a later upload time.**

Release notes (ready to copy only after acceptance): [RU](m7-android-1.1.0/notes-ru.txt) and [EN](m7-android-1.1.0/notes-en.txt). Publication and merge require separate explicit owner authorization.

## 6. RC final SHA — evidence to attach after GitHub checks

- [ ] CI success on **final PR #146 head SHA**; cite run URL and UTC completion time (baseline-only PASS does not satisfy this gate).
- [ ] Mobile CI success on **the same final SHA**, including `Mobile (Linux)`, `Android — library v1 migration on SharedPreferences`, macOS/iOS compatibility and iOS OTA staging test jobs in the existing workflow. Staging **test** jobs do not mean staging keys are allowed inside the release APK.
- [ ] Verify Android `1.1.0 (6)` and applicationId via source and (after signed build) APK/AAB validator reports. iOS versions untouched by PR diff.
- [ ] Signed APK/AAB with production OTA ON: **OWNER GATE — not built here; no protected signing environment was accessed**. No APK/AAB SHA-256 is asserted for the RC until the files actually exist.
- [ ] `android-store-5` remains a separate unresolved reconciliation task; no old release asset was reused.
