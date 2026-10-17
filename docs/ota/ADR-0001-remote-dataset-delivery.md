# ADR-0001: Remote dataset delivery (OTA) for TalentForge

* Status: **proposed** — M5.OTA.1 (architecture only; no client, no publication)
* Date: 2026-10-18
* Deciders: repository owner (open points: [open-decisions.md](open-decisions.md))
* Contract: [ota-contract-v1.md](ota-contract-v1.md) · Client: [client-architecture.md](client-architecture.md) ·
  Publisher & security: [publisher-and-security.md](publisher-and-security.md) · Tests: [test-plan.md](test-plan.md)

## Context

TalentForge ships its game data inside the app binary. Today (main after PR #41):

* Five complete Forever datasets are bundled side by side (70009, 70245, 70338, 70374,
  70450). Each one has talent documents, a dataset manifest, an icon registry and 357–359 PNG
  icons, visuals, tooltips and ruRU localization: 395–397 files, ≈ 5.0 MB raw per build
  (3.1 MB JSON, 2.0 MB PNG).
* The product dataset is a **compile-time constant** (`PRODUCT_BUNDLE` in
  `composeApp/…/ui/library/BundledDatasets.kt`). New drafts are created on it. The app has
  no runtime list of the other bundled builds; they are found by path.
* Saved builds (`LocalBuildRepository`: one JSON value in SharedPreferences / NSUserDefaults /
  `java.util.prefs`) store `game` + `clientBuild` + class + tree + allocations, never a
  content hash. `loadEditorSession` opens each build on its **own** client build; a build of
  an unbundled client build fails with a typed `DatasetLoadError` and never falls back.
* All reads go through one `DatasetAssetSource` (`suspend fun read(path): ByteArray`) over
  Compose resources; paths are `dataset|visuals|tooltips|localization/<game>/<build>/…`.
* Integrity today: strict closed parsing, manifest ↔ document identity (game, build, class,
  `contentHash` string, tree ids); localization recomputes its own SHA-256. Icon `sha256`
  values are published but not checked against the PNG bytes.
* The app has no network code, no Android `INTERNET` permission, no background work.
* A daily watcher (M5.DATA.WATCH) detects a newer publishable build; a developer then makes
  a refresh PR and a new app release.

Every Forever client build therefore needs a new APK/AAB and iOS binary, store review and a
user update before users can plan on it. OTA is a release gate for TalentForge: after one
app update, new talent trees, icons, descriptions and translations must reach users without
another binary.

Constraints (CLAUDE.md and the task): offline-first; fail-closed; no executable payload; no
always-on backend; no paid infrastructure without approval; saved builds are never migrated
or silently re-bound; unproven game semantics stay unproven.

## Decision

### D1 — Static, content-addressed delivery with one signed catalog per channel

Two kinds of objects on a static HTTPS origin:

* `v1/channels/<channel>/catalog.json` — the **only** mutable object: a signed list of every
  retained release, the active release, and the SHA-256 of each release manifest.
* `v1/blobs/sha256/<hex>` — everything else (release manifests, JSON documents, PNGs),
  immutable, named by the SHA-256 of its bytes.

Integrity is one chain: pinned public key → catalog signature → `manifestSha256` → each
file's `sha256`. Publication is atomic because it is one object write (the catalog), made
after every blob is uploaded. Rollback is a new catalog. There is no server logic, so any
object store + CDN works, and promotion between channels copies nothing.

### D2 — A manifest of individual files, not a snapshot archive

Measured on the committed data (sizes on the wire assume the CDN gzips JSON; PNG does not
compress):

| | Per-file manifest (chosen) | Archive per snapshot (zip/tar.gz) |
| --- | --- | --- |
| First download of a build | ≈ 2.2 MB (0.23 MB gzipped JSON + 2.0 MB PNG) in ≈ 400 requests | ≈ 2.2 MB in 1 request |
| Next build, icons re-used | ≈ 0.23 MB: every JSON document changes (it embeds `clientBuild`), icons are shared — 70338 → 70374 adds 0 new PNGs, 70245 → 70338 adds 3 | ≈ 2.2 MB again |
| Next build, icons re-encoded | ≈ 2.2 MB (70374 → 70450: 353 PNGs changed bytes, see below) | ≈ 2.2 MB |
| Reuse of files the app already bundles | copied locally, not downloaded | impossible without unpacking |
| Interrupted download | resumes per file; verified files are kept | restarts, or needs HTTP Range plus partial state |
| Verification | each file before it is stored | only after the whole archive |
| Client code | HTTP GET + SHA-256 (already in `shared`: `sha256Hex`) | an archive reader in KMP common code (new dependency or hand-written) plus zip-slip, zip-bomb, duplicate-entry and symlink defenses |
| Publisher | upload new blobs + one manifest | build, compress and upload one archive per build |
| CDN caching | immutable blobs cached forever, shared by builds and channels | one large object per build |

70450's PNGs differ from 70374's although the client files they were converted from are
identical (`sourceSha256` equal): Pillow's PNG encoder output depends on the platform build
it ran on ([forever-dataset.md](../forever-dataset.md)). Content addressing therefore saves
2 MB per build when the publisher encodes reproducibly and costs nothing when it does not.
The publisher brief requires encoding in one pinned environment ([OD-6](open-decisions.md)).

The price of the choice — about 400 small requests on a first install — is bounded (HTTP/2
multiplexing, at most 4 parallel requests) and paid once per build.

### D3 — Authenticity: ECDSA P-256/SHA-256 over DSSE PAE, two pinned keys

* Only the catalog is signed; every other object is hash-pinned from it.
* Envelope: `payload` = the exact catalog bytes (base64); the signature covers the DSSE v1
  pre-authentication encoding of `payloadType` + bytes. No JSON canonicalization anywhere.
* Algorithm: **ECDSA P-256 / SHA-256**, DER signatures. Chosen over Ed25519 because both
  target platforms verify it with **built-in** APIs reachable from Kotlin without a new
  dependency: `java.security.Signature("SHA256withECDSA")` on every Android API level and
  on the JVM (desktop tests), and `SecKeyVerifySignature` (Security.framework, a C API that
  Kotlin/Native calls directly) on iOS. Ed25519 is in Android's platform crypto only from
  API 33, and on iOS only in CryptoKit, a Swift-only API that Kotlin/Native cannot call
  without a Swift bridge in `iosApp` (which the project forbids).
* The app pins two production keys (primary + offline backup). Threshold 1.
* Replay protection: a strictly increasing `sequence` persisted on the device. Freeze
  protection: `expiresAt` (≤ 180 days; 45 recommended). This is a deliberately reduced TUF:
  one role, one signed document, no delegation.

### D4 — Client: install atomically, activate atomically, never re-bind a saved build

* The app always starts from local state: the last activated release, else the bundled
  product. The network is consulted only in the background, after the UI is up.
* A release is downloaded into a staging directory, verified (hashes, strict contracts,
  cross-document identity, then the app's own repositories), renamed into `releases/<id>/`
  (atomic), and activated by an atomic replacement of the state file.
* Open editor sessions keep the dataset snapshot they started with. New drafts use the
  current product. Each saved build opens on its own `clientBuild`: installed OTA release →
  bundled → on-demand download of that build's release → typed "unavailable" with retry.
  Never another build.
* Retention keeps the active and previous releases and every release a saved build
  references. Cache cleanup never touches the build library, which lives in a different
  store.

### D5 — The publisher publishes only reviewed, committed data

Publication input is the committed `data/` of a commit on `main` (merged after review with
green CI), never a working tree and never the freshness watcher's output. New or changed
mechanics and new warning codes stop the pipeline until the owner approves. Staging and
production are separate channels with separate keys; promotion to production is a manual,
signed step.

### D6 — Data levels instead of app-version gates

Each release carries `minDataLevel`; each app declares the level it implements. A release
needing more is never downloaded. This is platform-neutral (no Android `versionCode` vs. iOS
build-number mapping) and is the one switch the publisher needs to keep new mechanics away
from old apps.

## Alternatives considered

| Alternative | Why not |
| --- | --- |
| Keep shipping data only in the binary | Fails the goal: every client build needs store releases. |
| A REST backend (dynamic "latest" endpoint, server-side signing) | Always-on infrastructure, operations and cost the project does not have; a static catalog answers the same question. |
| Unsigned catalog over HTTPS only | TLS authenticates the host, not the publisher: a compromised bucket, CDN account or DNS could push arbitrary data. A signature keeps the root of trust offline. |
| Sign every release manifest too | Adds nothing while the catalog pins its hash, and promotion between channels would have to re-sign immutable objects. |
| Detached `catalog.json` + `catalog.json.sig` | Two mutable objects can be observed out of sync through a CDN; the envelope makes the signed unit one object. |
| Canonical JSON with an embedded signature | Canonicalization differs between libraries (number formatting, Unicode escapes); signing the exact bytes removes the problem. |
| Ed25519 | Platform availability (D3). Revisit if `shared` ever gains a vetted multiplatform crypto library. |
| Full TUF (root/targets/snapshot/timestamp roles) | Correct but heavy for one publisher and one data type. The reduced design keeps TUF's essential properties (offline root, hash pinning, rollback and freeze protection) and leaves a migration path: the envelope supports several signatures and payload types. |
| Client picks "the newest compatible release" | Takes the decision away from the publisher, breaks rollback, and makes behaviour depend on the app version. The client activates only `activeReleaseId`. |
| Re-bind saved builds to the new dataset when trees "look the same" | Forbidden: node ids change meaning between builds (Druid 104951, Warrior 105953, Priest 105823 in `docs/mobile-architecture.md`). |
| Re-issue different talent data for an existing client build | Would silently re-interpret saved builds. Forbidden by the contract (`BUILD_IDENTITY_CONFLICT`); a corrected normalization of an old build is an owner decision ([OD-8](open-decisions.md)). |
| Background scheduler (WorkManager, BGTaskScheduler) | Not needed for correctness; adds platform surface. v1 checks at app start and on return to the foreground ([OD-5](open-decisions.md)). |

## Consequences

Positive:

* New Forever builds reach users within minutes of a production promotion, without store
  review.
* No server to run. Hosting is a bucket + CDN and can move between providers by copying
  files, because no document names a host.
* With no network, or when every check fails, the app behaves exactly as today, on the last
  activated or bundled data.
* Every rejection has a typed code shared by Python and Kotlin, pinned by 56 fixture cases.

Negative / costs:

* The app gains network access: Android `INTERNET` permission and updated privacy
  disclosures in Google Play, AppGallery and App Store forms ([OD-4](open-decisions.md)).
* Key custody becomes a release-critical duty of the owner. Lost keys stop updates; a stolen
  key plus control of the hosting can push wrong data — never code.
* Storage grows by ≈ 5 MB per retained release.
* A build can be defined both by the bundle and by OTA; the same-build identity rule keeps
  the two consistent and the client enforces it.

## Compliance with project rules

* **Offline-first:** startup and every editor operation read only local files.
* **Fail-closed:** unknown fields, versions, roles, enum values and signers → rejection; a
  rejection never changes active data.
* **No executable payload:** the role set is closed and contains only data contracts the app
  already parses strictly.
* **No guessed semantics:** OTA carries the contracts the app already validates. Allocation
  semantics and their confidence levels are untouched (no change to
  `docs/talent-allocation-semantics.md`, `EDGE-ACTIVE`, `EDGE-SUF`).
* **No allocation-engine change and no change to existing schemas.** Three new contracts are
  added: `ota-envelope.v1`, `ota-catalog.v1`, `ota-release.v1`.
