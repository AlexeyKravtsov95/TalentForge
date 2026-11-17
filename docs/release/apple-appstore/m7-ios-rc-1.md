# M7.IOS.RC.1 — iOS 1.0.0 (5)

**Technical RC prepared; App Review/publication: NO-GO.** The signed archive and
App Store IPA pass their technical checks. Rights clearance and the owner's separate
submission decision remain required. Native Duo multi-touch and its submission images
are incomplete; `--require-duo` deliberately fails for this candidate.

This continues PR #152 without reopening its content audit. The independent brand,
third-party disclosures, privacy declarations, public schemas, game semantics and
Android **1.1.0 (6)** remain unchanged. Nothing was uploaded to ASC, submitted to Apple,
published or merged.

## Candidate and artifacts

| Fact | Observed value |
|---|---|
| Base | `5baa6388214c330e3f5264c5154148e098daf382`, main after PR #152 |
| Runtime source SHA | `ee4e9d028ffce5c1df1eec7a643827f69111709c` |
| Version / build | **1.0.0 (5)**, both Debug and Release |
| Bundle / deployment | `io.github.alexeykravtsov95.talentforge`, **17.2**, iPhone + iPad |
| Toolchain | Xcode **27.1 (27A9275)**, `iphoneos27.1` / `iphonesimulator27.1` |
| Compilation | Xcode **Release**, Kotlin `linkReleaseFrameworkIosArm64`; simulator equivalent Release arm64 |
| OTA | `TALENTFORGE_OTA_IOS_PRODUCTION_ENABLED=true`, `https://ota.mythweld.com` |
| Keys | Exactly `tf-prod-2026a` + `tf-prod-2026b`; DER SPKI SHA-256 matches independent verifier pins |
| Archive signing | Apple Development, matching valid profile; permitted archive configuration |
| IPA signing | **Apple Distribution, App Store profile, no device list**; strict signature/certificate/profile/entitlement checks PASS |
| Signing environment | Owner explicitly authorized this Mac in this chat before signing |

Later PR changes affect release validators/tests, metadata, documentation, evidence and
graph only; tracked application runtime sources match the source SHA above. The owner
already had an uncommitted `org.gradle.tooling.parallel=true` sync setting in
`mobile/gradle.properties`; it was present during builds, recorded in
[artifact provenance](evidence/m7-ios-rc-1/artifacts.json), and excluded from this PR.
No generated dataset was edited. Other pre-existing user files were excluded from commits.

Local artifacts live under ignored `.cache/m7-ios-rc-1/`; binaries, profiles,
certificates and private keys are **not committed or published**. The full native
verification reports remain there. Committed reports omit personal signing identity,
team and absolute local paths while retaining check results and artifact hashes.

| Artifact | SHA-256 |
|---|---|
| `TalentForge-1.0.0-5-ota-on.xcarchive.zip` | `25631e06395c89c1b55645e365b77f2417524837f74e63024c8d64cbefd28ac7` |
| `export/iosApp.ipa` (29,154,754 bytes) | `80661c1fd6236e763f9c9d5cb871a4c1de5df24429d0e506c20f862575155f15` |
| Installed development archive executable | `a137492b59dff34a29dc4900226cb33df72870e96bc97708e6cfddecdbc8df04` |
| Distribution IPA executable (re-signed at export) | `d5b95b1d27cadef7fc8963ca8f1e14a7110bc8f9a3f00bd8e3702f09382a1fc7` |
| Screenshot simulator executable | `3fc9aa51e79f18a86c8154c48dab7ad864caaeee52b31cca1d55c7d34ff17449` |

The simulator is a separate native build of the same runtime source/configuration,
not the App Store IPA running in a simulator. Native iPhone acceptance uses the
installed development-signed Release archive. Distribution export changes signatures;
its resulting executable hash is recorded separately.

## Technical validation, 17 November 2026 (Asia/Novosibirsk)

- [Project](evidence/m7-ios-rc-1/project-report.json): **15/15 PASS**.
- [Signed archive](evidence/m7-ios-rc-1/archive-report.json): **27/27 PASS**.
- [Exported IPA](evidence/m7-ios-rc-1/ipa-report.json): **27/27 PASS**.
- Both artifacts: arm64, frozen identity/version, minimum OS, iPhone/iPad families,
  compiled icon/resources, Russian resources, Privacy Manifest and required-reason
  API declarations, agreed Diagnostics declaration, default ATS, production keys/origin,
  no staging/loopback/development origin, no bundled secret/key files.
- [Simulator binary](evidence/m7-ios-rc-1/simulator-binary.json): production origin
  and exactly the two production SPKIs independently scanned from the installed binary.
- [Production preflight](evidence/m7-ios-rc-1/production-preflight.txt): **OK**;
  signed catalog sequence **7**, active **1.60.1.70938-r1**, all **7/7** releases
  re-hashed. Catalog expires **2026-12-29T05:50:30Z**, 41 days at check time.
  HTTP warning: origin also serves plain HTTP; the app accepts HTTPS only.
  Anonymous write/ACL verification remains a manual infrastructure check, as before.
- [Metadata](evidence/m7-ios-rc-1/metadata-report.json): **44/44 PASS**. The build-4
  date-specific prohibition was removed; brand, OTA, required disclaimers/product facts,
  limits, URLs and platform checks remain. Editorial PASS does not prove binary behavior.
- [Candidate screenshots](evidence/m7-ios-rc-1/screenshots-report.json): **148/148
  PASS**, **28 PNGs**. With `--require-duo`: **FAIL**, eight missing-Duo count/screen
  checks; [full report](evidence/m7-ios-rc-1/screenshots-require-duo-report.json).

Build commands (root unless stated otherwise; signing team supplied only locally):

```bash
python3 scripts/verify_ios_project.py --expected-version 1.0.0 --expected-build 5
# cwd mobile/iosApp; owner's Automatic signing, team passed via DEVELOPMENT_TEAM
TALENTFORGE_OTA_IOS_PRODUCTION_ENABLED=true xcodebuild \
  -project iosApp.xcodeproj -scheme iosApp -configuration Release \
  -destination 'generic/platform=iOS' \
  -derivedDataPath ../../.cache/m7-ios-rc-1/device-derived \
  -archivePath ../../.cache/m7-ios-rc-1/TalentForge-1.0.0-5-ota-on.xcarchive \
  -allowProvisioningUpdates archive
# local export options: method=app-store-connect, destination=export,
# signingStyle=automatic, manageAppVersionAndBuildNumber=false; no upload
xcodebuild -exportArchive \
  -archivePath .cache/m7-ios-rc-1/TalentForge-1.0.0-5-ota-on.xcarchive \
  -exportPath .cache/m7-ios-rc-1/export \
  -exportOptionsPlist .cache/m7-ios-rc-1/export-options.plist -allowProvisioningUpdates
python3 scripts/verify_ios_archive.py \
  --archive .cache/m7-ios-rc-1/TalentForge-1.0.0-5-ota-on.xcarchive \
  --ota on --require-signed --expected-version 1.0.0 --expected-build 5 \
  --expected-sdk iphoneos27.1 --expected-xcode-build 27A9275
python3 scripts/verify_ios_archive.py --ipa .cache/m7-ios-rc-1/export/iosApp.ipa \
  --ota on --expected-version 1.0.0 --expected-build 5 \
  --expected-sdk iphoneos27.1 --expected-xcode-build 27A9275
```

The executed verification commands also passed the owner's expected team locally.
Initial Manual signing failed because the old managed Store profile did not contain
its Distribution certificate. Automatic archive and local export succeeded; export
obtained a matching Store profile. No certificate/key material was printed or committed.

## Functional acceptance

Native Device Hub UI, fresh iPhone 17 Pro and iPad Pro 13-inch (M5) simulators,
**iOS/iPadOS 26.4.1**, same Release build 5. Language selected with per-process
`-AppleLanguages` / `-AppleLocale` launch arguments; screenshots show actual native
UI, not desktop Compose or a resized substitute. Four saved builds survived native
terminate/relaunch on each simulator, including original allocations and favorites.

| Scenario | iPhone simulator | iPad simulator | Evidence / scope |
|---|---|---|---|
| Create/edit; selection never spends | PASS | PASS | Paladin 0/51 → visible + → 1/51 |
| Undo/Redo | PASS | PASS | 1 → 0 → 1 |
| Requirements / Show | PASS RU/EN | PASS EN, RU details | Illumination → direct Reverence; total stays 1/51 |
| Autosave / reopen / restart | PASS | PASS | Four records and invested ranks retained |
| Rename / duplicate | PASS / PASS | NOT RUN / PASS | Name `light` persisted on iPhone; duplicates retain allocation |
| Search | PASS RU/EN | PASS RU/EN | `light`, `pal`, `пал`; visible results 1 of 3/4 |
| Class + favorites filters | PASS | PASS | Paladin + favorite returns one record |
| Sorting / grouping | PASS | PASS | Class order, separate Mage/Paladin headers; exhaustive option permutations covered by tests, not native replay |
| Import / export | PASS RU/EN | PASS EN, RU preview | Actual exported code → Paste → preview → separate new record; native share sheet/popover opened; no recipient selected |
| Production OTA | PASS | PASS | App reports update to 1.60.1.70938; prior build's data retained |
| Portrait / landscape | PASS | PASS | Selected rank 1/5 survives rotation; side inspector observed |
| Largest Dynamic Type | Home RU checked | Home RU checked | [iPhone](evidence/m7-ios-rc-1/qa/iphone-ru-large-text-home.png), [iPad](evidence/m7-ios-rc-1/qa/ipad-ru-large-text-home.png); not exhaustive screen acceptance |
| Native pan / pinch | Owner checked physical iPhone | Unconfirmed | Device Hub pointer drags did not establish iPad gesture acceptance; no synthetic pinch presented as evidence |
| Offline | Owner checked physical iPhone | Unconfirmed | OTA fault/offline adapter tests also pass; no status-bar override used to pretend connectivity was disabled |

**Physical iPhone 16 Pro, iOS 27.0.1:** installed the verified development-signed
Release archive over the existing bundle without uninstalling. The owner was asked
to check two-finger pinch, free pan, native swipe-back, open/edit with Wi-Fi and
cellular off, restart and preservation of old builds/new changes. The owner replied
**«все ок»** on 17 November in this chat. This is **owner-reported PASS for those
specific checks**, not an agent-observed recording or a TestFlight/App Store IPA test.
No physical iPad was available in the connected-device inventory.

**Duo, iOS 27.1:** fresh official simulator `DFF5B9CB-F2F2-4ADB-B315-B08091D42924`.
Native Closed → Open → Closed controls retained Paladin/Holy, selected Divine
Strength 1/5 and total 1/51 in the same process. Undo afterwards produced 0/51;
Redo restored 1/51. [Outer QA](evidence/m7-ios-rc-1/qa/duo-outer-en-editor.png) and
[Inner QA](evidence/m7-ios-rc-1/qa/duo-inner-en-editor.png) document only this subset.
Native multi-touch, swipe-back, complete RU/EN functional matrix and library-state
resize acceptance remain **NOT VERIFIED**. No Duo optimization claim or submission
screenshot pack is approved. Build-4 Duo images are historical, not substitute evidence.

## Metadata and screenshot decisions

Name **TalentForge** and both agreed subtitles remain unchanged. New library
claims were added to the two descriptions and Review Notes **after** native iPhone
and iPad checks above. Promotion and keywords retain PR #152's neutral wording.
Notes identify build 5, Release production OTA and the limited simulator provenance,
retain the game's third-party origin and unresolved rights statement, and do not
claim full Duo optimization or a license.

The current candidate is [screenshots-rc1/manifest.json](submission/screenshots-rc1/manifest.json).
The old `submission/screenshots/` pack stays intact for historical review links;
its successful structural validation cannot count toward this candidate's release gate.
Each candidate entry records build/version, Release, production mode/origin, runtime,
Xcode/SDK, source commit, installed simulator binary SHA-256, original capture SHA-256,
PNG dimensions/size/hash, locale, device and capture time. Only opaque alpha was removed;
decoded RGB bytes were asserted identical. No generated/retouched UI, crops or resizing.
Provenance fields are recorded evidence, not a script's ability to infer authenticity
or rights from pixels; human capture/review remains necessary.

```bash
python3 scripts/verify_appstore_screenshots.py \
  --screenshots docs/release/apple-appstore/submission/screenshots-rc1 \
  --expected-version 1.0.0 --expected-build 5 --require-production-ota
# Deliberate release blocker until accepted Duo recapture exists:
python3 scripts/verify_appstore_screenshots.py \
  --screenshots docs/release/apple-appstore/submission/screenshots-rc1 \
  --require-duo --expected-version 1.0.0 --expected-build 5 --require-production-ota
```

## Remaining gates and submission

1. **Rights:** the unresolved game icons/texts, class art and App Icon basis from
   PR #152 remains a separate blocker. Existing affirmative Content Rights in ASC
   is not substantiated by this RC. Owner must establish sufficient legal basis
   and a truthful declaration before authorizing submission.
2. **Duo:** complete native acceptance and then RU/EN Outer/Inner recapture;
   `--require-duo` must pass for the requested complete pack.
3. **iPad:** native multi-touch/offline and full large-text acceptance remain
   unconfirmed. Physical-device/TestFlight checks beyond the owner's stated iPhone
   subset are not implied by simulator or unit-test passes.
4. **Final HEAD CI:** both CI and Mobile CI must pass on the final pushed commit;
   the PR's exact-head Checks/description records hosted results (avoids a document
   commit claiming to contain its own commit hash). Earlier green runs are not final-head proof.
5. **ASC owner actions:** upload/processing and actual selection of build 5,
   application of approved metadata/screenshots, truthful Content Rights, Manual
   Release and resubmission each require the owner's separate decision. The current
   ASC setting is still Automatic Release and build 4 remains selected.

Use [the existing-app submission checklist](submission/rc1-checklist.md), not the
historical New App runbook. [Read-only ASC observation](evidence/m7-ios-rc-1/asc-read-only-observation.json)
confirms store version 1.0 already associates binary 1.0.0 (4). Build 5 association
can only be checked after separately authorized upload/processing; no store version
or application record was changed.

## Local baseline results and execution limits

- `python3 scripts/sync_mobile_datasets.py --check`: PASS, 50 files / 5 builds.
- `./gradlew :shared:jvmTest :composeApp:desktopTest :composeApp:assembleDebug
  :shared:iosSimulatorArm64Test :composeApp:iosSimulatorArm64Test --console=plain`:
  **PASS**, 7m22s. Shared JVM: 383; desktop: 951 tests, 64 opt-in skipped;
  shared iOS: 199; Compose iOS: 8. Zero failures/errors.
- Initial baseline with the RC's OTA ON environment: FAIL, because
  `OtaConfigTest.everyGeneratedPlatformSettingIsDisabledWithoutHostingOrKeys` requires
  default OTA OFF. Repeated with ordinary settings above, PASS. Release candidates
  were built and binary-scanned separately with OTA ON.
- Full local `.venv/bin/python -m pytest -q`: **INTERRUPTED**, 358 passed,
  11 deselected, one failure in
  `test_code_only_trigger_recovers_older_approved_request` (PACKAGE_PROVENANCE_FAILED),
  848.84s before stopping. Scratch usage reached 16 GB; only this task's test scratch
  was removed. Full-suite success is not claimed locally; clean hosted CI is required.
- Local `ruff check .`: FAIL, two pre-existing errors in untracked
  `.tf-transfer/rc1/rc1_android_device_check.py`; that user file was not edited.
  Tracked-file lint, final focused tests, format and mypy results are recorded in
  [validation](evidence/m7-ios-rc-1/validation.json).
