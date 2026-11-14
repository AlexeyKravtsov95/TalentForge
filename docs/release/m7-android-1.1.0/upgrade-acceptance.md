# M7.RELEASE.1 — Owner acceptance: upgrade installed Android 1.0.0 (5) → 1.1.0 (6)

**BLOCKING, NOT YET EXECUTED.** This is a non-destructive *installed-app upgrade*, not a fresh install, new isolated `.lib2check` fixture, or only a JSON codec unit test. Carry out only on an authorized owner device/test profile with installed user data. **Never uninstall, run `pm clear`, clear app data, factory reset or use a forced downgrade to make a failing scenario pass.** Do not publish/upload/re-sign APKs or retrieve production keys as part of this checklist.

## A. Preconditions and provenance

- [ ] PR #146 has Android `versionName=1.1.0`, `versionCode=6`, unchanged `applicationId=io.github.alexeykravtsov95.talentforge`, and CI + mobile-ci are successful at the *same exact source SHA*. Record SHA and Actions run URLs.
- [ ] Owner's previous app really reports `1.0.0 (5)` **before** the update, with matching production applicationId. Record installation provenance (**Google Play / RuStore / AppGallery / compatible owner build**) and Android OS/device; **do not assume code 5 was published in every store**. If code 5 cannot be installed or shown as previously installed, mark this owner gate **BLOCKED**, never claim PASS from another version.
- [ ] Record which signing lineage applies. A universal APK may upgrade a same-certificate APK-store installation; a Play-delivered application may use the Google Play **app-signing certificate**, which is distinct from the Play AAB **upload certificate**. Confirm upgrade signature compatibility using approved certificate fingerprints. A failure to update because of different signatures is **NO-GO**, never a reason to uninstall/clear data. If Play can only deliver a compatible upgrade through a store track, **do not upload now**; leave this gate pending a separately authorized test-track update.
- [ ] Owner supplies existing signing settings in a protected environment. Do not print keystore paths, credentials, private keys or sensitive logs. Run the existing `:composeApp:assemblePublishableRelease` and `:composeApp:bundlePublishablePlay` with `TALENTFORGE_OTA_ANDROID_PRODUCTION_ENABLED=true` and **without staging variables**. Require validator PASS for both signed artifacts; record their SHA-256 only after files exist.
- [ ] Verify the APK and AAB contain `1.1.0 (6)`, unchanged applicationId, expected independent app-signing and upload cert SHA-256, no debuggable/test-only flag, production HTTPS OTA endpoint and production public keys only. Confirm the APK and play bundle manifest `INTERNET` permission is consistent with OTA ON.
- [ ] Ensure device has sufficient charge/storage and can be taken offline. Store only redacted, non-personal evidence; don't publish real saved builds or share codes.

## B. Pre-upgrade state (running installed v1.0.0 (5))

Record a table privately with at least **three representative builds**, e.g. one favorite, one non-favorite, builds of different classes, and one build originally created under the legacy v1 storage format if available. For each store: record build name, class, specialization, total points, talent ranks per tree, favorite flag and stable ID (if exposed). Include a build with prerequisites and one with enough talents for sorting/class grouping. Confirm all are readable/reopenable before update.

- [ ] Capture count, names, favorites and talent distributions before upgrade; restart v1.0.0 once and confirm persistence.
- [ ] Record any available evidence that library storage is still **v1** vs already **v2**. If cannot safely obtain a legacy v1 instance on the installed production app, explicitly note it; the CI SharedPreferences v1→v2 fixture is supporting automated evidence **not** proof of an installed v1 data migration.
- [ ] Note current language (RU/EN), display size/font scale and cached game data version. Capture private screenshots with sensitive user-generated data masked.

## C. Non-destructive installation

- [ ] Record candidate APK SHA-256 and exact source commit. Verify that the candidate certificate matches the installed app **before** replacing it.
- [ ] Where direct sideload is compatible, run **`adb install -r <path-to-new-approved-TalentForge-1.1.0-6.apk>`**; do not use `adb uninstall`, `pm clear`, `-d` or any reset. The output must report `Success`. Where signed Play lineage differs, use only a separately authorized compatible Play update flow; no store delivery is authorized by this PR.
- [ ] Immediately confirm installed app version `1.1.0 (6)`, unchanged package, and normal launch. If install fails or signed lineage differs, STOP, keep previous data and capture the redacted reason. Do not work around it by deleting the app.
- [ ] Restart/cold-launch 1.1.0 at least once after update, again after a deliberate library mutation and once offline; record any crash/ANR or data loss.

## D. Preservation & migration (compare with section B)

- [ ] Same library count, stable identifiers where observable, names, class/spec, talents/point allocations and favorite flags. No missing, duplicated, reset or renamed builds.
- [ ] Open each previously recorded build in the editor; compare exact rank allocation and prerequisites. Close/reopen it and repeat after app restart.
- [ ] Confirm v1 read does not modify legacy bytes until first permitted write (automated golden test); toggle favorite/rename a test build and verify v1→v2 conversion preserves **every** other record. Compare with the pre-upgrade snapshot and restart to validate persistence. If real installed v1 cannot be staged, clearly label the **owner device v1 case PENDING**.
- [ ] Verify already-migrated v2 records reopen without a second destructive migration. Toggle favorite twice and restore its original state, confirming other fields are unchanged.
- [ ] Search by name (positive and negative cases), class filters and Favorites-only; switch sorting (Last edited / Name), toggle class grouping and restore default. None should mutate build payload merely by changing sort, group or filter.
- [ ] Create a new test build, allocate ranks, rename, duplicate, favorite, close/relaunch and delete only the test copy. Verify old builds unchanged.

## E. Functionality / localization / live OTA

- [ ] Requirements and prerequisite navigation: blocked talent state, tier unlock, prerequisite focus and Show navigation from both old and new builds. Verify rank controls, inspector, back/return navigation and class specialization tabs.
- [ ] RU and EN independently: all library controls, guides, Requirements, Show, sorting/grouping, dialogs and labels. Check widths **320, 360 and 393 dp**, normal and **2× font scale**; visual inspection and accessibility touch targets. Attach relevant sanitized EN/RU small-screen screenshots.
- [ ] With network available: verify **production** `https://ota.mythweld.com` is selected and game data loads/updates with the approved production keyring; check app version vs data version labels, no debug/staging URL or trust anchors. Record production catalog/data version readback and result (not just a successful HTTP call).
- [ ] Disable network, cold-start, and verify the saved library and already installed talent data remain accessible. Re-enable network; confirm normal recovery. Do not reset OTA cache.
- [ ] Confirm no crash, ANR or unexpected permission/signing change.

## F. Owner acceptance record (complete only after evidence)

| Item | Owner result / link |
|---|---|
| Test date (UTC), device model, Android API / OS | **PENDING** |
| Before: v1.0.0 (5) distribution + signing certificate identity | **PENDING** |
| After: v1.1.0 (6), package/version/cert validated | **PENDING** |
| Final PR SHA and passing CI/mobile-ci run links | **PENDING** |
| Signed APK SHA-256 + AAB SHA-256 + validator reports | **PENDING** |
| v1 legacy data case / v2 already migrated case | **PENDING / PENDING** |
| Saved builds/names/allocations/favorites after restarts | **PENDING** |
| Search/filters/sort/group; editor/Requirements/Show | **PENDING** |
| RU/EN small-screen and 2× fonts with screenshots | **PENDING** |
| Production OTA online/offline and data-version readback | **PENDING** |
| Crash / ANR / migration failure observations | **PENDING** |
| Owner final verdict | **NO-GO / NOT YET ACCEPTED** |

**Acceptance rule:** mark PASS only with real installed-previous-version evidence and actual updated signed artifact. Temporary-key self-tests and fresh test installations do not substitute for the upgrade. Any data loss, signature mismatch, wrong OTA channel, missing CI or unverifiable code occupancy is **NO-GO**. Before **any** later upload, re-read occupied versions in all three stores and reconcile `android-store-5` separately.
