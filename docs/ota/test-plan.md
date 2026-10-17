# OTA test plan and release readiness (M5.OTA.1)

Contract: [ota-contract-v1.md](ota-contract-v1.md) · Client: [client-architecture.md](client-architecture.md) ·
Publisher: [publisher-and-security.md](publisher-and-security.md).

Overriding rule for every negative scenario: **the saved-build library is byte-identical before
and after**, and the dataset that was active stays usable.

## 1. Test layers

| Layer | Where | Status | What it proves |
| --- | --- | --- | --- |
| L1 Contract conformance | `docs/ota/fixtures/v1/cases.json` (56 cases), replayed by `tests/unit/ota/test_ota_contract.py` (Python, **this PR**) and by a Kotlin replay in `mobile/shared` jvmTest (M5.OTA.3) | Python: done | publisher and client agree on every accept/reject verdict and error code |
| L2 Client integration (JVM) | `mobile/shared` jvmTest + `mobile/composeApp` desktopTest with a fake `BlobFetcher` (an in-memory blob store built from the fixtures and `data/`), a temp-dir `OtaFileStore`, a controllable clock and fault injection | M5.OTA.3 | download, staging, activation, recovery, retention, sessions, saved builds |
| L3 Publisher (Python) | `tests/unit/ota/` with a fake object store (local directory or `httpx.MockTransport`) | M5.OTA.2 | gates, upload order, atomic pointer, rollback, re-sign, verify-live |
| L4 Platform adapters | Android instrumented (or Robolectric) and iOS simulator tests: ECDSA verification of the fixture signatures with platform crypto, atomic rename/replace, free-space queries | M5.OTA.3 | java.security / Security.framework / file APIs behave as designed |
| L5 End to end | staging channel on real hosting, real devices (Android phone, iOS simulator + device) | before the public release | the whole chain, CDN behaviour, real network failures |

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
| S5 | Partially downloaded package | `catalog-truncated-download`, `release-manifest-truncated`, `release-blob-truncated` | The fetcher cuts the connection after N bytes / the attempt is killed half-way; restart; run again | the attempt fails with the size/hash code; staging kept; the next attempt resumes (verified files are not fetched again) and completes; nothing activated in between |
| S6 | Wrong SHA-256 | `release-blob-hash-mismatch`, `release-manifest-hash-mismatch` | The fetcher serves a bit-flipped blob | `BLOB_HASH_MISMATCH`; blob discarded; release not installed; active unchanged |
| S7 | Invalid signature | `catalog-tampered-payload`, `catalog-signature-by-wrong-key`, `catalog-untrusted-signer`, `catalog-wrong-payload-type` | Serve each envelope | rejected with the fixture's code; `lastSeen` unchanged; no blob requested |
| S8 | Unknown schema or new mechanic | `catalog-schema-version-2`, `release-schema-version-2`, `catalog-unknown-field`, `release-unknown-field`, `release-unknown-role`, `release-new-mechanic`, `release-needs-data-level-2`, `catalog-active-needs-newer-app`, `release-open-union-node-type` | Serve each | closed-contract violations rejected; a level-2 active release → `KEEP_CURRENT_INCOMPATIBLE`, nothing downloaded; an open-union node type accepted and reported unsupported by the engine (and blocked by publisher gate G6 in L3) |
| S9 | Not enough free space | — (client-only) | `freeSpace` < `totalByteLength` + margin; separately, a write failure (ENOSPC) half-way | `INSUFFICIENT_STORAGE` before any download; a failure half-way removes staging; active unchanged |
| S10 | Crash during activation | `catalog-b-active-refetched` (idempotence) | Kill points after each blob write, before/after the staging rename, before/after the state replace (abort the coroutine, then run `recover()` on the same directory) | after every kill point the app starts on the old or the new release, never a mix; a release installed but not active is activated by the next check without downloading |
| S11 | Catalog rollback | `catalog-rollback-to-a`, `catalog-rollback-below-bundled-floor`, `catalog-sequence-regression`, `catalog-sequence-reused` | B active; serve seq 3 (active A); then serve the old seq 1 | A active (or `KEEP_BUNDLED_FLOOR` when the app bundles B); B kept while a saved build references it; seq 1 → `SEQUENCE_REGRESSION`, no change |
| S12 | Re-download of a package already present | `catalog-b-active-refetched` | B active; the same catalog again; also a release whose files equal bundled files | `NO_CHANGE`, zero blob requests; for the bundled-equal release, files copied from the bundle, zero blob requests |

The suite also covers, and the Kotlin replay must keep: game/channel mismatch, expiry, duplicate
JSON keys, quoted numbers, BOM, naive timestamps, non-canonical base64, duplicate key ids, path
traversal, foreign-build paths, role/path mismatch, duplicate or unordered files, wrong totals,
missing roles, mixed builds, missing classes, same-build conflict, backup-key and multi-signature
acceptance.

## 3. Additional L2/L4 tests (M5.OTA.3)

* **Read-time corruption:** flip a byte of an installed icon and of a talent document; the open
  session gets the typed failure for that asset; the release is quarantined; the next snapshot
  uses the previous release or the bundle; the next check re-installs it.
* **Unreadable state file:** garbage `state.json` → app on bundled data; no release directory
  deleted; the next catalog rebuilds the state.
* **Retention:** library references X (unbundled) and Y (bundled); installed {X, Z, active B,
  previous A}; GC deletes only Z. With an unreadable library GC deletes only staging, quarantine
  and trash.
* **Leases:** an editor open on Z while GC runs → Z kept until the editor closes.
* **App update floor:** active OTA release A, new app bundles B → product B at once; A kept.
* **Concurrency:** two triggers at once → one attempt.
* **Fixture keys never shipped:** production and staging keyrings contain no `test-` key id; no
  staging key is in the production keyring.
* **Platform crypto equivalence (L4):** every fixture signature verifies (or fails) identically
  with the platform verifier on Android/JVM and iOS.
* **No network on the startup path:** a fetcher that fails the test if called before the first
  snapshot is published.

## 4. Manual staging smoke (before every production promotion)

On a staging-channel build, Android and iOS:

1. Fresh install, airplane mode: the app opens on the bundled product; saved builds open.
2. Network on: the staging release downloads; a new draft uses it; the diagnostics view shows
   the release id.
3. An editor open during activation does not change.
4. A saved build of the previous product opens on its own build.
5. Kill the app during a download, relaunch: the download completes.
6. Promote a rollback on staging: the device follows on its next check.

Results go into the approval record (publisher design §2.2).

## 5. Publisher tests (M5.OTA.2, L3)

* `build-release` is deterministic (two runs → identical bytes) and reproduces the fixture
  release manifests for 70374 and 70450 when given the fixture's commit, `createdAt` and
  validation note.
* Every gate G1–G8 has a failing test; a flagged gate without an approval record blocks
  `promote`.
* Upload order: the catalog is written last; a failed blob upload leaves the live catalog
  untouched.
* Optimistic concurrency: a changed live `sequence` aborts `promote`.
* Rollback and re-sign keep every release listed and increase `sequence`.
* `verify-live` detects a missing blob, a wrong blob, a bad signature and an expired catalog.
* No command deletes a referenced blob; blob GC lists before deleting and requires `--confirm`.
* The production signing step never reads a private key from the repository or a CI
  environment.

## 6. Release readiness criteria (OTA public release gate)

OTA may be enabled in a store build only when **all** of these hold:

1. M5.OTA.2 and M5.OTA.3 merged; the Kotlin L1 replay green on the JVM **and** in iOS simulator
   tests; L2 suites green, including S1–S12 and §3.
2. L4 platform tests green on Android (an API 26 emulator and a current device) and iOS
   (simulator and one device).
3. Production keys generated and stored per publisher design §5; backup restore rehearsed (a
   test catalog signed with the backup key from its offline medium verifies).
4. Hosting decided ([OD-1](open-decisions.md)); staging and production channels live;
   `verify-live` green; HTTPS on the project's own hostname.
5. The production catalog lists a release for the active product and for every client build that
   saved builds of OTA-enabled app versions can reference beyond what those versions bundle; a
   staging smoke (§4) passed on both platforms with the exact store candidate.
6. Re-signing before expiry is scheduled (a reminder at half the validity window) and documented.
7. Privacy policy, Google Play data safety, AppGallery and App Store privacy answers updated for
   network access ([OD-4](open-decisions.md)); the `INTERNET` permission reviewed in the release
   checklist (`docs/release/`).
8. Kill switch verified: an app built with OTA disabled behaves exactly like today's app (the
   existing desktop tests unchanged and green).
9. Rollback rehearsed end to end on staging, with an open editor and saved builds on both
   releases.
10. No negative scenario in L2/L5 changed the saved-build library (asserted by tests, not just
    observed).
