# M6.LIB.2 — favorites and safe local-library migration

## Storage and compatibility

The private library envelope changes independently from `allocation-build.v1` and share codes. Each v2 record adds the **required JSON boolean** `isFavorite`. The v1 and v2 wire types are separate and both reject unknown fields, quoted numbers, wrong primitive types, duplicate/blank IDs and incompatible embedded build versions. The v1 model retains the defaults the original writer/reader allowed. Reading v1 maps favorites to false **in memory only**.

```text
v1 JSON → strict v1 decode → records with isFavorite=false
                            ↓ load / existing-value no-op: no write
                            ↓ successful mutation
                         encode entire v2 envelope → synchronous storage replacement
```

Every record retains its stable localId, name, createdAt, updatedAt, lastOpenedAt and embedded BuildFile, except the field explicitly changed by the requested operation. A favorite change only changes isFavorite. Encoding retains canonical localId order. Repository display ordering remains updatedAt descending, createdAt descending, localId ascending. A duplicate has isFavorite=false; the source is untouched. Accepted legacy blank names still appear as Draft (`name == null`) but their exact original blank string is retained privately for serialization, including later v2 writes. Explicit rename replaces it; newly created/duplicated Drafts write null. No extra JSON field is introduced. No game data, allocation semantics, public schemas, share codes, OTA settings or application versions change.

| Reader | v1 storage | v2 storage | Unknown future storage |
|---|---|---|---|
| Old application (v1 only) | Supported | May refuse with UnsupportedStorageVersion | Refused |
| M6.LIB.2 (v1 + v2) | Read-only migration in memory; first successful mutation writes v2 | Supported | Typed UnsupportedStorageVersion; no write |

**Downgrade risk:** after any successful mutation writes v2, an older v1-only application may refuse to read the local library. There is no automatic backward conversion. Downgrading the application does not undo the storage migration. Preserve the original app data before downgrading; never clear it as a recovery step.

## Failure and concurrency behavior

The repository reads and decodes before encoding/writing. Failed reads, malformed JSON, unsupported versions and failed writes never trigger an empty replacement library. Existing-value favorite assignments do not write or migrate. UI changes only after storage confirms success; an error shows Retry and leaves the prior star state visible.

Favorites run through the app's existing BuildAutosaveCoordinator: flush submissions made before the action, then take the same write mutex as autosave and rename. A failed pending save of the targeted build blocks its favorite change with a typed outcome. Later autosaves re-read the record and preserve favorite metadata. While a management write is in flight, All Builds disables opening rows, row actions, Back/New/Import and consumes system Back; this prevents a concurrently opened editor from writing a stale open timestamp over a favorite. A delayed-storage app test pins that guard. Gated-write tests exercise both interleavings, another build's failed save, retry and unchanged timestamps.

Android uses the existing SharedPreferences string cell and synchronous commit. commit can change its in-memory value even when it reports failure: the adapter now snapshots and restores the previous cell on false/exception. Instrumented tests reproduce this against real preferences and inspect both the reread value and XML file for byte-exact restoration. A failure of the restore itself is reported explicitly; no preference API can promise durable writes during persistent OS/filesystem failure. iOS continues using NSUserDefaults. Native iOS visual acceptance remains backlog.

## Evidence

`mobile/shared/src/jvmTest/resources/builds/local-library-v1.json` is a frozen three-record fixture verified byte-for-byte against the **original v1 production writer before changing it**. It includes named/unnamed records, Cyrillic, nullable open time, allocations and three Forever versions. It is a storage fixture, not evidence of allocation validity. Common JVM/iOS tests use the identical frozen text; the JVM test loads the JSON and pins their equality; Android tests load that same JSON as a test asset. No test generates v1 with the new v2 encoder.

The migration test compares every original record JSON object against its migrated object after removing only isFavorite. It also compares complete domain records before/after, IDs and order, and retries a failed first migration after checking byte-exact original payload preservation. Synthetic 0/1/10/100-record v1 libraries cover scale and empty storage. A dedicated regression covers blank names on the favorite target and untouched neighbors, subsequent v2 writes, explicit rename and Draft duplication.

The UI matrix covers EN/RU, widths 320/360/393/412 dp, and font scales 100/130/175/200%. It checks the star's 48 dp target, checkbox state, favorite filtering and empty state, reset, persistence, unchanged bytes after favorite/unfavorite, class/search controls and the New Build action. App-level tests cover rename/delete under all filters, duplicate defaults, write failure/retry and recreation. Home and its Continue Editing selection logic are unchanged.

Android instrumented checks use only the `.lib2check` test application and refuse to run against another package:

```sh
cd mobile
./gradlew :composeApp:connectedDebugAndroidTest -Ptalentforge.applicationIdSuffix=.lib2check --console=plain
```

The mobile CI adds an API 35 emulator job for these tests; existing shared JVM, Compose Desktop, Android build/signing and Xcode 27.1/iOS jobs remain required.

The offline Python baseline uses `pytest -o tmp_path_retention_policy=failed --basetemp=/tmp/tf-lib2-pytest`: the same full suite, with passed tests' temporary clones deleted promptly. A first local run was interrupted because default retention accumulated 15 GB of disposable clones; those task-created clones were removed and the complete suite restarted with this retention option. This changes test-output retention, not test selection or product behavior.

## Validation and evidence

Exact whole-suite counts, platform build results and final per-commit CI links are maintained in [PR #137](https://github.com/AlexeyKravtsov95/TalentForge/pull/137). This report keeps the migration contract, reproducible checks and preservation/screenshot evidence together without freezing an intermediate CI snapshot.

```sh
ruff check .
ruff format --check .
mypy
pytest -o tmp_path_retention_policy=failed --basetemp=/tmp/tf-lib2-pytest
python3 scripts/sync_mobile_datasets.py --check
cd mobile
./gradlew :shared:jvmTest :composeApp:desktopTest :composeApp:assembleDebug
./gradlew :shared:iosSimulatorArm64Test :composeApp:iosSimulatorArm64Test
./gradlew :composeApp:linkDebugFrameworkIosArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64
```

The iOS host simulator build follows `docs/mobile-architecture.md`. `actionlint` validates the added Android job; the installed version's existing unknown `xcode-27` runner-label diagnostic is ignored. The committed graph was rebuilt with `graphify update .` (AST only).

Android screenshots are actual emulator captures from `.lib2check`, not desktop renders:

| State | English | Russian |
|---|---|---|
| All Builds, 393 dp / 100% | [EN](../screenshots/m6-lib2/android-en-393dp-100.png) | [RU](../screenshots/m6-lib2/android-ru-393dp-100.png) |
| Favorites filter, 393 dp / 100% | [EN](../screenshots/m6-lib2/android-en-favorites-393dp-100.png) | [RU](../screenshots/m6-lib2/android-ru-favorites-393dp-100.png) |
| All Builds, 320 dp / 200% | [EN](../screenshots/m6-lib2/android-en-320dp-200.png) | [RU](../screenshots/m6-lib2/android-ru-320dp-200.png) |

[Android preservation report](../screenshots/m6-lib2/preservation.json): three original records/IDs retained with every original JSON field equal; v1 load did not write; cold process restart retained the confirmed v2 payload byte for byte. The fixture covers storage compatibility, not allocation/gameplay acceptance. Native iOS visual acceptance remains backlog.
