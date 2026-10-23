# OTA test plan and release readiness (M5.OTA.1)

Contract: [ota-contract-v1.md](ota-contract-v1.md) · Client: [client-architecture.md](client-architecture.md) ·
Publisher: [publisher-and-security.md](publisher-and-security.md).

Overriding rule for every negative scenario: **the saved-build library is byte-identical before
and after**, and the dataset that was active stays usable.

## 1. Test layers

| Layer | Where | Status | What it proves |
| --- | --- | --- | --- |
| L1 Contract conformance | `docs/ota/fixtures/v1/cases.json` (64 cases), replayed by `tests/unit/ota/test_ota_contract.py` (Python, **this PR**) and by a Kotlin replay in `mobile/shared` jvmTest (M5.OTA.3) | Python: done | publisher and client agree on every accept/reject verdict and error code |
| L1b Publisher policy | `tests/unit/ota/test_ota_mechanics_gate.py` (**this PR**) | done | gate G6: unsupported mechanics are a STOP with no override; the level-1 baseline equals the bundled data's known anomaly |
| L2 Client integration (JVM) | `mobile/shared` jvmTest + `mobile/composeApp` desktopTest with a fake `BlobFetcher` (in-memory blob store built from the fixtures and `data/`, with a request log), a temp-dir `OtaFileStore`, a controllable clock and fault injection | M5.OTA.3 | download, staging, resume, activation, recovery, retention, sessions, saved builds |
| L3 Publisher (Python) | `tests/unit/ota/` with a fake object store (local directory or `httpx.MockTransport`) | M5.OTA.2 | gates, upload order, atomic pointer, rollback, re-sign, verify-live |
| L4 Platform adapters | Android (instrumented or Robolectric) and iOS (simulator) tests: ECDSA verification of the fixture signatures with platform crypto, atomic rename/replace, free-space queries, streaming byte limits | M5.OTA.3 | java.security / Security.framework / file and HTTP APIs behave as designed |
| L5 End to end | staging channel on real hosting, real devices | before each platform's public release (§6) | the whole chain, CDN behaviour, real network failures |
| L6 Performance | §7 | M5.OTA.3 | download cost of the per-file design on slow, high-latency links |

## 2. Required scenarios

Each scenario lists its L1 fixture cases (already executable) and the L2/L3 test M5.OTA.3 /
M5.OTA.2 must add. Every L2 test asserts "library unchanged" by comparing the
`BuildLibraryStorage` payload before and after.

| # | Scenario | L1 fixture cases | L2/L3 test (to implement) | Expected |
| --- | --- | --- | --- | --- |
| S1 | A → B without an app update | `catalog-b-active`, `release-b-valid` | App bundling A with no OTA state; serve catalog seq 2 and B's blobs; run a check | B installed and active; a **new** draft is created on B (its document's `clientBuild` is B); bundled A untouched |
| S2 | A → B with an editor open | `catalog-b-active` | Open an editor on product A (snapshot S_A); activate B; keep editing; load icons/descriptions after activation; save | every asset the open editor reads comes from A (recording asset source); the saved record keeps `clientBuild` A; a New Build screen opened afterwards lists B |
| S3 | Open saved build A after B is active | `catalog-b-active`, `release-a-valid` | Library holds an A build; activate B; open the A build | opens on A's document, icons and descriptions; allocations identical; record unchanged; no B file read |
| S3b | Saved build of an unbundled, published build | `catalog-rollback-to-a` (A listed) | Library holds a build of a build that is not bundled but is listed; open it offline, then online | offline → `DatasetUnavailable(OFFLINE)` with Retry, record unchanged; online → the release is downloaded (not activated) and the build opens on its own build |
| S4 | No internet | — (client-only) | The fetcher fails every request; cold start; check; open builds | app starts on the last active release (or the bundle); nothing changes; backoff scheduled |
| S5 | Partially downloaded package, resumed | `catalog-truncated-download`, `release-manifest-truncated`, `release-blob-truncated` | (a) The fetcher cuts a blob after N bytes. (b) The attempt is cancelled after K of M files and the process "dies": the coroutine is cancelled, in-memory objects are dropped, a `.part` file is left behind; then `recover()` runs on the same directory and a new check starts. (c) Between the two attempts one staged file is corrupted on disk. | (a) `BLOB_SIZE_MISMATCH`, `.part` discarded. (b) `recover()` deletes only the `.part`; the second attempt re-hashes the K staged files and requests **only** the M−K missing blobs (asserted on the request log); nothing is activated before the last file is verified and the validators pass. (c) the corrupted staged file fails its re-check, is deleted and requested again — no other staged file is requested |
| S5b | Catalog pins different bytes for a staged release | — (client-only) | Stage X half-way; serve a catalog whose entry for X has another `manifestSha256` | `staging/X` deleted, the attempt restarts from nothing; active unchanged |
| S6 | Wrong SHA-256 | `release-blob-hash-mismatch`, `release-manifest-hash-mismatch` | The fetcher serves a bit-flipped blob | `BLOB_HASH_MISMATCH`; blob discarded; staged verified files kept; retried with backoff; active unchanged |
| S7 | Invalid signature | `catalog-tampered-payload`, `catalog-signature-by-wrong-key`, `catalog-untrusted-signer`, `catalog-wrong-payload-type` | Serve each envelope | rejected with the fixture's code; `lastSeen` unchanged; no blob requested |
| S8 | Unknown schema or new mechanic | `catalog-schema-version-2`, `release-schema-version-2`, `catalog-unknown-field`, `release-unknown-field`, `release-unknown-role`, `release-new-mechanic`, `release-needs-data-level-2`, `catalog-active-needs-newer-app`, `release-open-union-node-type` | Serve each | closed-contract violations rejected and recorded as rejected releases (not retried); a level-2 active release → `KEEP_CURRENT_INCOMPATIBLE`, nothing downloaded. `release-open-union-node-type` is wire-valid by design and must **never reach a catalog**: L1b/L3 prove gate G6 stops it; the client test only checks that, if it did arrive, the engine reports the node unsupported and nothing else breaks |
| S9 | Not enough free space | — (client-only) | Free space < remaining bytes + margin; separately, `ENOSPC` half-way; separately, free space below the margin | `INSUFFICIENT_STORAGE` before any download; `ENOSPC` deletes the `.part` and keeps verified staged files; below the margin `staging/X` is deleted; active unchanged in all three |
| S10 | Crash during installation or activation | `catalog-b-active-refetched` (idempotence) | Kill points: during a blob write (`.part` present), after each blob rename, before/after writing `release.json`, before/after the staging rename, before/after the state replace — each followed by `recover()` on the same directory and a new check | after every kill point the app starts on the old or the new release, never a mix; before the staging rename the next attempt resumes (no verified file downloaded again); after the rename but before the state write the next check activates the installed release without downloading |
| S11 | Catalog rollback | `catalog-rollback-to-a`, `catalog-rollback-below-bundled-floor`, `catalog-sequence-regression`, `catalog-sequence-reused` | B active; serve seq 3 (active A); then serve the old seq 1 | A active (or `KEEP_BUNDLED_FLOOR` when the app bundles B); B kept while a saved build references it; seq 1 → `SEQUENCE_REGRESSION`, no change |
| S12 | Re-download of a package already present | `catalog-b-active-refetched` | B active; the same catalog again; also a release whose files equal bundled files | `NO_CHANGE`, zero blob requests; for the bundled-equal release, files copied from the bundle after re-hashing, zero blob requests |
| S13 | Duplicate JSON member names | `catalog-envelope-duplicate-key`, `catalog-envelope-nested-duplicate-key`, `catalog-duplicate-json-key`, `catalog-duplicate-key-same-value`, `catalog-duplicate-nested-key`, `catalog-duplicate-escaped-key`, `release-duplicate-nested-key`, `release-content-duplicate-key` | Kotlin replay (L1) plus unit tests of the duplicate-name scanner (§3) | every document with a repeated name is rejected with its stage's code (`ENVELOPE_INVALID`, `PAYLOAD_INVALID`, `MANIFEST_INVALID`, `CONTENT_INVALID`), whatever the values and order, nested or escaped |
| S14 | Oversized catalog | `catalog-over-size-limit` | A fetcher that streams an endless response | the client stops reading at 1 MiB + 1 byte and reports `CATALOG_TOO_LARGE`; nothing is parsed; a response of exactly 1 MiB is verified normally |

The suite also covers, and the Kotlin replay must keep: game/channel mismatch, expiry, quoted
numbers, BOM, naive timestamps, non-canonical base64, duplicate key ids, path traversal,
foreign-build paths, role/path mismatch, duplicate or unordered files, wrong totals, missing
roles, mixed builds, missing classes, same-build conflict, backup-key and multi-signature
acceptance.

## 3. Additional L2/L4 tests (M5.OTA.3)

* **Duplicate-name scanner** (contract §2.1): duplicates at the top level, in nested objects, in
  objects inside arrays, with equal and different values, in both orders; names equal only after
  unescaping (`\uXXXX`, surrogate pairs, `\/`); names that look alike but differ (`"a"` vs
  `"a "`); strings containing `{`, `}`, `:`, `,` and escaped quotes; deep nesting (bounded, no
  stack overflow). Differential test: on generated documents the scanner's verdict equals the
  Python reference's `strict_json`. A separate test records what kotlinx.serialization alone does
  with duplicates, so a library update that changes it is noticed — the client depends on it in
  neither direction.
* **Read-time corruption:** flip a byte of an installed icon and of a talent document; the open
  session gets the typed failure for that asset; the release is quarantined; the next snapshot
  uses the previous release or the bundle; the next check re-installs it.
* **Unreadable state file:** garbage `state.json` → app on bundled data; no release directory
  deleted; staging directories kept; the next catalog rebuilds the state.
* **Recovery garbage rules:** staging directories with a bad name, a missing or mismatched
  `attempt.json`, or an already-installed id are deleted at startup; every other staging
  directory survives `recover()`.
* **Rejected releases:** a release rejected with `CONTENT_INVALID` is not downloaded again for the
  same manifest SHA-256; a transient `BLOB_HASH_MISMATCH` is retried.
* **Retention:** library references X (unbundled) and Y (bundled); installed {X, Z, active B,
  previous A}; GC deletes only Z; the stale staging of a no-longer-listed release is deleted, the
  staging of the release being installed is not. With an unreadable library GC deletes no
  installed release.
* **Leases:** an editor open on Z while GC runs → Z kept until the editor closes.
* **App update floor:** active OTA release A, new app bundles B → product B at once; A kept.
* **Concurrency:** two triggers at once → one attempt.
* **Fixture keys never shipped:** production and staging keyrings contain no `test-` key id; no
  staging key is in the production keyring.
* **Platform crypto equivalence (L4):** every fixture signature verifies (or fails) identically
  with the platform verifier on Android/JVM and iOS.
* **Streaming limits (L4):** the platform fetchers stop at the byte limit (catalog 1 MiB, manifest
  `manifestByteLength`, blob `byteLength`) without buffering the rest.
* **No network on the startup path:** a fetcher that fails the test if called before the first
  snapshot is published.

## 4. Manual staging smoke (before every production promotion)

On a staging-channel build, for each platform that has OTA enabled:

1. Fresh install, airplane mode: the app opens on the bundled product; saved builds open.
2. Network on: the staging release downloads; a new draft uses it; the diagnostics view shows the
   release id.
3. An editor open during activation does not change.
4. A saved build of the previous product opens on its own build.
5. Kill the app during a download and relaunch: the download resumes (diagnostics show the number
   of reused files) and completes.
6. Promote a rollback on staging: the device follows on its next check.

Results go into the approval record (publisher design §2.2).

## 5. Publisher tests (M5.OTA.2, L3)

* `build-release` is deterministic (two runs → identical bytes) and reproduces the fixture
  release manifests for 70374 and 70450 when given the fixture's commit, `createdAt` and
  validation note.
* Every gate G1–G8 has a failing test. A STOP gate (G1–G4, G6, G8) cannot be passed by any
  approval record, flag or environment variable: a test tries each such mechanism for G6 with the
  content of `release-open-union-node-type` and asserts that `stage` and `promote` refuse.
* A flagged gate (G5, G7) without an approval record blocks `promote`; G5 never accepts a finding
  that G6 reports.
* Upload order: the catalog is written last; a failed blob upload leaves the live catalog
  untouched.
* Optimistic concurrency: a changed live `sequence` aborts `promote`.
* Rollback and re-sign keep every release listed and increase `sequence`.
* The produced catalog is at most 1 MiB; `promote` refuses to write a larger one.
* `verify-live` detects a missing blob, a wrong blob, a bad signature, an expired catalog and an
  oversized catalog.
* No command deletes a referenced blob; blob GC lists before deleting and requires `--confirm`.
* The production signing step never reads a private key from the repository or a CI environment.

## 6. Release readiness criteria (OTA public release gates)

OTA is enabled **per platform** (`OtaConfig.enabled` per platform and build type, client design
§8). Android can ship OTA before iOS: **Android ships with OTA when gates A and B pass**,
whatever the status of gate C. An iOS build keeps OTA disabled — and behaves exactly as today,
on bundled datasets — until gate C passes.

### A. Common OTA gate (before OTA is enabled on any platform)

1. M5.OTA.2 and M5.OTA.3 merged.
2. Kotlin L1 replay of all 64 fixture cases green on the JVM (the publisher ↔ client contract).
3. L2 suites green: S1–S14 and §3 — signatures, SHA-256, staging, resume, activation, recovery,
   saved-build preservation, rollback, retention, negative scenarios, `minDataLevel`
   compatibility.
4. L1b and L3 green: gate G6 cannot be overridden; every gate has a failing test.
5. The shared KMP code still compiles for iOS (framework link tasks) and the existing iOS
   simulator tests pass — an engineering check of common code, **not** the iOS release gate.
6. Production keys generated and stored per publisher design §5; backup restore rehearsed.
7. Hosting decided ([OD-1](open-decisions.md)); staging and production channels live;
   `verify-live` green; HTTPS on the project's own hostname.
8. The production catalog lists the active product and every client build that saved builds of
   OTA-enabled app versions can reference beyond what those versions bundle.
9. Re-signing before expiry scheduled (a reminder at half the validity window) and documented.
10. Rollback rehearsed end to end on staging, with an open editor and saved builds on both
    releases.
11. No negative scenario in L2/L5 changed the saved-build library (asserted by tests).
12. Performance measured per §7, and no trigger of §7.3 fired (or its follow-up decision taken).

### B. Android release gate (RuStore, AppGallery and any other Android store)

1. Gate A passed.
2. Android production adapters — `java.security` ECDSA verifier, `HttpURLConnection` fetcher with
   streaming limits, `noBackupFilesDir` file store — with L4 tests green.
3. Real HTTPS downloads from the production origin on an **Android emulator at API 26** (minSdk)
   and on a current API level.
4. At least one **physical Android device**: staging smoke (§4) with the exact store candidate,
   including background/foreground transitions during a download, process death and resume,
   offline start and recovery, and saved builds of earlier products.
5. Kill switch: an Android build with OTA disabled behaves exactly like today's app (existing
   tests unchanged and green).
6. Android privacy and store compliance: the `INTERNET` permission reviewed in the release
   checklist (`docs/release/`); privacy policy; RuStore and AppGallery (and Google Play, if used)
   data-safety answers updated ([OD-4](open-decisions.md)).

**M5.OTA.PROD.3 status (2026-10-21, Android).** B.1/A: not re-run here (owner gates A.6–A.10 are
recorded in production-deployment). B.2: L4 adapters — the existing desktop/JVM suites ran green in this PR.
B.3 (real HTTPS on API 26 and current) and B.4 (physical device with the exact store candidate):
**owner-only, NOT run** — checks M-1…M-8 in [android-production-enablement §4](android-production-enablement.md).
B.5 (kill switch / OTA OFF): verified locally — OTA OFF release APK and Play AAB have no `INTERNET`
and generated settings `enabled = false` ([ota-prod3-evidence](../release/ota-prod3-evidence/README.md));
existing tests unchanged and green. B.6: texts and declarations prepared, **not confirmed** by the owner
([ota-prod3-privacy-and-stores](../release/ota-prod3-privacy-and-stores.md)).

### C. iOS release gate (only before a public iOS release with OTA)

1. Gate A passed.
2. iOS adapters — Security.framework verifier, `NSURLSession` fetcher with streaming limits,
   Application Support file store excluded from backup — with L4 tests green on the
   **iOS Simulator**.
3. At least one **physical iOS device**: staging smoke (§4), including the iOS lifecycle
   (suspension during a download, termination, relaunch and resume, launch before the first
   unlock after a reboot).
4. Kill switch: an iOS build with OTA disabled behaves exactly like today's app.
5. App Store privacy answers and privacy policy updated; App Transport Security defaults
   unchanged.

**M5.IOS.2 status (2026-10-23, iOS).** Results and evidence:
[m5-ios-2-ota-readiness](../release/apple-appstore/m5-ios-2-ota-readiness.md).
C.1/A: not re-run here (gate A was passed for Android; the production origin was re-checked
read-only, preflight `OK`). C.2: **PASS on the iOS Simulator** — the existing L4 adapter tests,
plus the new `IosOtaStagingE2ETest`: the production iOS stack (`NSURLSession` with ATS defaults
and the system trust store, Security.framework, the Application Support store, `NSUserDefaults`
builds, the app's Compose resources) against the real publisher's staging releases over verified
HTTPS, 14 phases run by `scripts/ios_ota_e2e.py` one simulator process at a time (S1–S12, S3b,
SIGKILL mid-download and cold-start resume, background/foreground, rejected certificates), and
the real app on the simulator (`ios_ota_e2e.py app`). C.3 (physical device, including
suspension and relaunch on hardware): **NOT RUN** — owner plan P-1…P-13 in
[m5-ios-2-owner-guide §5](../release/apple-appstore/m5-ios-2-owner-guide.md). C.4 (kill switch):
**PASS** — the OTA OFF archive carries no origin and no key (`verify_ios_archive.py --ota off`),
the OTA OFF app opens no Internet socket, and a binary without settings has no OTA runtime. C.5:
ATS defaults verified in both archives; App Privacy answers and the published policy:
**BLOCKED** (owner).

## 7. Performance measurements (M5.OTA.3, L6)

The per-file design (ADR-0001 D2) costs about 400 requests for a first install. M5.OTA.3 measures
it before any public release; the results go into its PR and into `docs/ota/`, and decide whether
a batching follow-up is needed. No archive format is introduced by M5.OTA.1.

### 7.1 What to measure

| Metric | Cases |
| --- | --- |
| Cold download of a new dataset | release B on an empty OTA store with nothing reusable from the bundle |
| Warm download, matching SHA-256 | release B with A installed or bundled (icons reused): files reused vs. downloaded |
| HTTP requests actually made | every case, from the fetcher's request log |
| Bytes transferred | on the wire (compressed) and decoded, every case |
| Install time | from catalog accepted to `releases/<id>` renamed, per network profile |
| Parallelism | 1, 4 and 8 concurrent downloads |
| Resume after an interruption | interrupt at 50 %, then resume: requests and bytes of the second attempt |

### 7.2 How

* A JVM benchmark against a local HTTP server that injects latency, bandwidth limits and loss
  (repeatable; may run in CI as a non-gating job).
* Device runs: Android emulator network throttling and a physical Android device on mobile data;
  the iOS Network Link Conditioner when gate C is run.
* Profiles, at least: good (20 ms RTT, 20 Mbit/s), mobile (100 ms RTT, 5 Mbit/s), poor (300 ms
  RTT, 1 Mbit/s, 1 % loss).
* The median of 5 runs, with the 90th percentile reported as well.

### 7.3 When a batch/archive decision is required

Open a separate decision (a new ADR, outside M5.OTA.1 and M5.OTA.3) if, at parallelism 4:

* a cold install on the **poor** profile takes more than **90 s** (median), or
* a warm install on the **mobile** profile takes more than **15 s** (median), or
* request overhead (time not spent transferring payload bytes) exceeds **50 %** of the cold
  install time on the **mobile** profile.

Below these thresholds the per-file design stays. The follow-up chooses among options such as
batching the small JSON files of a release into one blob, or HTTP/2 tuning, and must keep the
contract's integrity chain (every byte still pinned by the signed catalog).
