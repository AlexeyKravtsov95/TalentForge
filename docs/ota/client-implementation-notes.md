# OTA client — implementation notes (M5.OTA.3)

Status: **implemented, disabled in every app build** (no hosting, no production keys yet —
[OD-1](open-decisions.md), [OD-2](open-decisions.md)). Contract: [ota-contract-v1.md](ota-contract-v1.md)
(unchanged). Design: [client-architecture.md](client-architecture.md). This note records what the
code does where the design leaves a choice, and every deliberate deviation from the design sketch.

## Where the code is

| Piece | File | Notes |
| --- | --- | --- |
| Strict JSON + duplicate names (contract §2.1) | `mobile/shared/…/data/ota/JsonDuplicateNames.kt` | A byte-level lexer: duplicate names after unescaping at any depth, **and** the full RFC 8259 grammar, UTF-8 validity, no BOM, no `NaN`/`Infinity`, bounded depth (256), iterative. No new library: kotlinx.serialization exposes no token stream, and its tree reader accepts unquoted literals (`{"a": abc}`), so the scanner is the single strictness gate. |
| OTA documents | `…/data/ota/OtaContract.kt` | kotlinx models, decoded after the scanner, then **the payload tree must equal the canonical re-encoding of the decoded model** — any coercion (quoted number, `1e3` for an integer, a number for a string) is rejected; then the field constraints of the Python models. |
| Failures | `…/data/ota/OtaFailure.kt` | One member per `OtaErrorCode` + `Network`, `Http`, `InsufficientStorage`, `Io`, `Cancelled`; `installClass()` is the §4.7 transient vs. rejected split. |
| Verifiers and policies | `CatalogVerifier`, `ActivationPolicy`, `ReleaseVerifier`, `ReleaseValidator`, `StagingPolicy`, `RetentionPolicy`, `OtaState`, `DatasetSnapshot` | Pure `shared` code; all decisions are here. |
| Ports | `…/data/ota/OtaPorts.kt`, `OtaKeyring.kt` | `SignatureVerifier`, `BlobFetcher` (hard `maxBytes`), `OtaFileStore`, `OtaClock`. P-256 SPKI and strict-DER checks run in common code before the platform is asked, so the three platforms cannot disagree on malformed input. |
| Engine | `mobile/composeApp/…/ui/ota/` | `RemoteDatasetCatalog`, `DatasetDownloadManager`, `DatasetCache`, `DatasetActivationManager`, `OtaUpdateCoordinator`, `HistoricalDatasetResolver`, `ReleaseAssetSource`, `RoutingDatasetAssetSource`, `OtaRuntime`, `SessionDatasets`, `OtaConfig`. |
| Android + desktop adapters | `composeApp/src/jvmCommonMain/…/ui/ota/JvmOtaPorts.kt` | One source set for both JVM targets (`applyDefaultHierarchyTemplate { group("jvmCommon") }`): `java.security` ECDSA, `HttpURLConnection` (byte limit while reading, no redirects, HTTPS only), `java.nio` (fsync, `ATOMIC_MOVE`). Desktop uses `HttpURLConnection` too rather than `java.net.http`: one implementation, the one Android ships, is what the L4 tests exercise. |
| iOS adapters | `composeApp/src/iosMain/…/ui/ota/IosOtaPorts.kt` | `SecKeyCreateWithData` (X9.63 point = last 65 bytes of the SPKI) + `SecKeyVerifySignature(ECDSASignatureMessageX962SHA256)`; ephemeral `NSURLSession` with a data delegate that cancels past the limit and refuses redirects; Application Support store (`NSURLIsExcludedFromBackupKey`), `open`/`write`/`fsync`, `rename(2)`. |
| Hosts | `OtaRuntime.android.kt`, `OtaRuntime.ios.kt`, `MainActivity`, `MainViewController` | One runtime per process; `null` unless the platform's generated settings are enabled. |
| Configuration | `composeApp/build.gradle.kts` → `GeneratedOtaSettings.kt` | Per platform (android, ios) × channel (production for release builds, staging for debug builds) from Gradle properties / environment only; all disabled by default. Each keyring pins only its own channel's keys — `tf-prod-*` in production, `tf-staging-*` in staging ([publisher-and-security.md §5.1](publisher-and-security.md)) — checked on that keyring alone by the build (`verifyOtaSettingsRules` proves the rules) and again by `OtaConfig.resolve` (`KEY_NOT_FOR_CHANNEL`). |

## Decisions and deviations from the design sketch

1. **Catalog payload and `state.json` (PR #42 review).** The sketch's single mutable
   `catalog/<channel>.payload` cannot be written atomically together with `state.json`. The client
   writes accepted payloads **content-addressed**, `v1/catalog/<channel>/<sha256-hex>.payload`
   (atomic replace, synced), *before* the state that names it; payloads no committed state names
   are deleted only *after* the state write (and by `recover()`). A crash between the two writes
   leaves the old state with its own payload on disk, never a state naming a missing payload or
   other bytes, and the releases the last accepted catalog listed stay known offline. Tested by
   `OtaCrashRecoveryTest` (kill between the payload and the state write, and after it).
2. **`state.json` is the single commit point** of every transition (catalog accepted, release
   installed, activated, rejected, retention). Format (client-private, contract §7.2):
   `{format: "talentforge-ota-state", formatVersion: 1, game, channel, catalog: {sequence, payloadSha256} | null,
   active, previous, installed: [{releaseId, clientBuild, manifestSha256}], rejected: [{releaseId, manifestSha256, code}]}`
   (rejections capped at 64). Read strictly; unreadable → "no OTA state", nothing deleted.
3. **Install records.** A release renamed into `releases/` but not yet recorded (crash between
   the rename and the state write) is adopted by `recover()` — and by the next accepted catalog —
   when the last accepted catalog pins exactly its `release.json` bytes; the next check then
   activates it without downloading. A release directory nothing pins is left untouched.
4. **Retention order and leases.** The state forgets releases first (one atomic write), then
   their directories go through `trash/`. A lease covers every release id of the snapshot an
   editor **actually reads** (conservative: it protects every release of that snapshot): the one
   it opened with, or the one its historical build was downloaded into, which then takes the
   lease over (`EditorDatasetAccess`); an import preview holds the lease of the snapshot it
   downloaded into while its screen is open. Leases and retention share one atomic table
   (`SnapshotLeases`): under the activation lock retention *claims* the releases of its plan
   that no lease holds, and a claimed release cannot be leased; a snapshot is leased only if all
   its releases are still intact. So the plan's view of the leases can be stale without harm,
   and a release downloaded on demand is leased before the resolver returns, with no suspension
   in between (PR #44 review P1-1).
5. **Product source.** The snapshot reads the product's build from the **active** release (so a
   rollback to an older revision of the same build also rolls back presentation), every other
   build from its newest installed revision, then the bundle.
6. **Strictness vs. the reference.** The Kotlin readers may only be *stricter* than
   `talent_data.ota.verify`, never more lenient: timestamps accept `YYYY-MM-DDTHH:MM:SS[.ffffff](Z|±00:00)`
   only (pydantic accepts more spellings); integers are 64-bit (`sequence` beyond 2^63 is
   rejected); `-0` is not accepted as an integer spelling. The publisher writes none of these.
7. **ENOSPC on the JVM.** `java.io` does not expose `ENOSPC` as a type; a failed write is
   reported as "no space" when the volume's usable space is below 1 MiB afterwards. iOS uses
   `errno == ENOSPC`.
8. **Placement.** `RoutingDatasetAssetSource` and the managers are in composeApp as designed;
   `DatasetSnapshot` (in shared) carries the per-build sources.
9. **Kill switch.** With a disabled configuration the hosts pass `ota = null` and `App` builds
   exactly today's bundled wiring (`SessionDatasets.bundled`); every pre-existing test passes
   unchanged.

## Verification evidence

* L1: `OtaConformanceReplayTest` — 64/64 fixture cases reach the reference verdict with
  `java.security`; every code and decision exercised; the 8 duplicate-name cases; 1 MiB + 1 byte
  rejected before parsing; exactly 1 MiB verified normally. `JsonDuplicateNamesTest` — the
  scanner equals `strict_json` on 474 generated vectors (`tests/fixtures/ota_client/`).
* L2: `OtaScenarioTest` (S1, S4–S9, S11–S14, test-plan §3), `OtaSessionTest` (S2, S3, S3b,
  leases), `OtaCrashRecoveryTest` (60 kill points over the 805 mutating operations of a full
  install, plus the catalog/state crash), `OtaUiTest`. Negative scenarios assert the library
  payload is unchanged.
* L4: `OtaPlatformAdapterTest` (JVM/Android code: 39 catalogs, HTTP limits/redirects against a
  local server, atomic store), `IosOtaAdapterTest` on the iOS simulator (38 catalogs with
  Security.framework, store).
* L6: [client-performance-report.md](client-performance-report.md).

## Not done here (owner / later milestones)

* Hosting, production and staging keys, enabling OTA in any build (OD-1, OD-2, OD-17).
* L5 end-to-end on real hosting and devices; Android emulator network throttling and the
  physical-device smoke (test-plan §4, §6B/C) — they need an OTA-enabled build, which no
  configuration can produce before keys and hosting exist (test keys are refused by design).
* Privacy copy and store data-safety answers for the network access (OD-4): the About privacy
  text ("works without the internet") is accurate while OTA is disabled and must be revised
  before it is enabled (test-plan §6B.6).
* Build cards of an unavailable historical build show the existing "unresolved" card (no
  reason); the reason and Retry are in the editor.
