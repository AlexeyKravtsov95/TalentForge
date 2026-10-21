# OTA client architecture (KMP) — design for M5.OTA.3

Status: **proposed** (M5.OTA.1). Nothing here is implemented yet.
Contract: [ota-contract-v1.md](ota-contract-v1.md). Decision: [ADR-0001](ADR-0001-remote-dataset-delivery.md).

## 1. What exists and what is reused

Audit of `main` after PR #41 (paths: `S` = `mobile/shared/src/commonMain/kotlin/io/github/alexeykravtsov95/talentforge`,
`C` = `mobile/composeApp/src/commonMain/kotlin/io/github/alexeykravtsov95/talentforge/ui`).

| Existing piece | Today | OTA verdict |
| --- | --- | --- |
| `DatasetAssetSource` (`S/data/DatasetAssetSource.kt`) | `suspend fun read(path): ByteArray`; typed `AssetNotFoundException` / `AssetReadException` | **Reused unchanged.** The single injection point: every repository reads through it with paths `dataset\|visuals\|tooltips\|localization/<game>/<build>/…`. OTA adds implementations, not a new read API. |
| `ComposeResourceDatasetAssetSource` (`C/assets/…`) | `Res.readBytes("files/$path")` | **Reused unchanged** as the bundled source. |
| `BundledTalentDatasetRepository`, `BundledTalentVisualsRepository`, `BundledTalentTooltipsRepository`, `BundledTalentLocalizationRepository` (`S/data/…`) | strict decoding, typed outcomes, identity checks; keyed by the document's own (game, clientBuild) | **Reused unchanged** to read OTA releases (same paths, same contracts) and to validate a staged release (contract §6.4, last paragraph). The "Bundled" prefix becomes historical; no rename needed. |
| `DatasetBundleId`, `DatasetId`, `ContractKind`, `DatasetLoadError` (`S/data/…`) | identity and typed failures | **Reused.** OTA failures get their own sealed type (`OtaFailure`), never folded into `DatasetLoadError` or strings. |
| `sha256Hex` (`S/data/localization/LocalizationContentHash.kt`) | pure-Kotlin SHA-256, tested against `MessageDigest` | **Reused** for blob and manifest hashes (moved to a neutral file such as `S/data/Sha256.kt`, plus a streaming variant). |
| `PRODUCT_BUNDLE` / `REFERENCE_BUNDLE` (`C/library/BundledDatasets.kt`) | compile-time product | **Extended:** `PRODUCT_BUNDLE` stays the *bundled* product and becomes the rollback floor; the *effective* product comes from `ProductDatasetResolver` (§4.4). `REFERENCE_BUNDLE` is unchanged (conformance pin). |
| Bundled build list | none at runtime (Python `BUNDLED_BUILDS`; Kotlin test-only `ALL_BUNDLES`) | **Extended:** a `BUNDLED_CLIENT_BUILDS` constant in composeApp, drift-checked against `scripts/mobile_bundles.py`, so the resolver knows what is bundled without probing. |
| `loadEditorSession` / `createClassDraft` (`C/library/BuildLoaders.kt`) | new drafts on `PRODUCT_BUNDLE`; saved builds on their own `clientBuild` | **Extended:** takes a `DatasetSnapshot` (§4.5) instead of the global source; new drafts use `snapshot.product`; a build whose dataset is missing gets the typed resolution of §5.2 instead of only `AssetMissing`. No allocation logic changes. |
| `BuildLibraryStorage`, `LocalBuildRepository` (`S/data/builds/…`) | one JSON value in SharedPreferences / NSUserDefaults / `java.util.prefs`; records carry `game` + `clientBuild` | **Unchanged.** Read-only use: the retention policy asks for the set of referenced `(game, clientBuild)`. The OTA cache is a different storage area, so cleaning it cannot touch the library. |
| `talent_data` pipeline, `scripts/mobile_bundles.py`, sync scripts | canonical data → bundled copies, drift checks | **Unchanged.** The publisher (M5.OTA.2) reads the same committed `data/`. |
| `scripts/check_product_freshness.py`, `docs/dataset-freshness-watch.md` | detects newer publishable builds | **Unchanged.** Still a signal for a human, never a publication trigger. Which build it compares against once OTA exists: [OD-10](open-decisions.md). |

## 2. Module placement

```
mobile/shared (pure KMP, no platform types, no new dependency)
  data/ota/
    JsonDuplicateNames.kt   raw-byte duplicate member-name scanner (contract §2.1), run before decoding
    OtaContract.kt          strict models: SignedEnvelope, OtaCatalog, OtaReleaseManifest, FileRole
    OtaFailure.kt           sealed failures: one per contract code + client-only conditions
    CatalogVerifier.kt      contract §6.1, size limit first (signature math delegated to SignatureVerifier)
    ActivationPolicy.kt     contract §6.2 (pure)
    ReleaseVerifier.kt      contract §6.3 and §6.4 #1–10
    ReleaseValidator.kt     §6.4 tail: runs the existing repositories over a staged release
    RetentionPolicy.kt      pure: (state, library refs, leases, bundled builds) -> releases to delete
    StagingPolicy.kt        pure: staging listing + attempt.json + catalog entry + free space -> keep/resume/delete (§4.7)
    OtaState.kt             persisted state model + strict codec
    DatasetSnapshot.kt      immutable build -> source map for one session
  data/ota/ports (interfaces implemented in composeApp)
    SignatureVerifier       verify(publicKeySpki, message, derSignature): Boolean
    BlobFetcher             suspend fetch(url, maxBytes): FetchResult (Ok bytes | NotFound | HttpError | Network)
    OtaFileStore            files under the OTA root: read, write, atomicReplace, rename, list, delete, freeSpace
    OtaClock                now(): Instant

mobile/composeApp (platform adapters + wiring)
  androidMain / iosMain / desktopMain
    PlatformSignatureVerifier   java.security (Android, desktop) / Security.framework (iOS)
    PlatformBlobFetcher         HttpURLConnection / NSURLSession / java.net.http
    PlatformOtaFileStore        java.io + java.nio / NSFileManager
  commonMain/ota
    OtaConfig                   channel, base URL, keyring, data level, enabled (per build type)
    RemoteDatasetCatalog        fetch + verify the catalog, persist the acceptance
    DatasetDownloadManager      plan, fetch, verify and stage one release
    DatasetCache                on-disk layout, installed releases, quarantine, GC execution
    DatasetActivationManager    install -> activate, state transitions, startup recovery
    OtaUpdateCoordinator        when to check (start, foreground, backoff); one attempt at a time
    ReleaseAssetSource          DatasetAssetSource over one installed release (hash-checked reads)
    RoutingDatasetAssetSource   DatasetAssetSource that routes each path by its <game>/<build>
```

The names from the task are kept where they carry meaning (`RemoteDatasetCatalog`,
`DatasetDownloadManager`, `DatasetCache`, `DatasetActivationManager`). Everything that
*decides* — verification, activation, retention — is pure code in `shared`, tested on the JVM
against the shared fixtures. composeApp only moves bytes and calls platform crypto.

No new library: no Ktor, no okio, no DI framework, no WorkManager. If the owner prefers Ktor
for HTTP, it stays confined to the `BlobFetcher` adapter ([OD-11](open-decisions.md)).

## 3. On-disk layout

```
<otaRoot>/v1/
  state.json                     persisted OtaState (atomic replace)
  state.json.tmp                 transient; deleted on start
  catalog/<channel>.payload      bytes of the last accepted catalog payload
  releases/<releaseId>/          one installed, verified release (never modified)
    release.json                 the release manifest (bytes pinned by the catalog)
    dataset/<game>/<build>/…     files at their release paths
    visuals/<game>/<build>/…
    tooltips/<game>/<build>/…
    localization/<game>/<build>/…
  staging/<releaseId>/           in-progress install of one release (at most one per id); never
                                 read as a release, never made active
    attempt.json                 releaseId, manifestSha256, manifestByteLength, startedAt, updatedAt
    release.json                 the release manifest, once verified (§6.3)
    <release paths>              complete files only, each renamed into place after its check
    <release path>.part          a download in flight; deleted on startup, never reused
  quarantine/<releaseId>.<n>/    a release that failed a read-time check (diagnostics; removed by GC)
  trash/<releaseId>.<n>/         a release being deleted (rename first, then recursive delete)
```

### Android

* `otaRoot` = `Context.getNoBackupFilesDir()/ota`: app-private internal storage, never
  cleared by the system (unlike `cacheDir`, which Android may purge and which would silently
  drop datasets of saved builds), excluded from backups. The app already sets
  `allowBackup="false"`; `noBackupFilesDir` keeps the data out even if backups are enabled
  later.
* Install: `File.renameTo` within `otaRoot` (one filesystem).
* State: write `state.json.tmp`, `FileOutputStream.fd.sync()`, then
  `Files.move(tmp, state, ATOMIC_MOVE, REPLACE_EXISTING)` (available from minSdk 26).
* Free space: `StatFs(otaRoot).availableBytes`.
* Network: add `android.permission.INTERNET`. HTTPS only (cleartext is already refused for
  targetSdk 36 by the default network security configuration).
* User action "Clear storage" in system settings deletes the OTA store **and** the library
  (both are app data); "Clear cache" deletes neither.

### iOS

* `otaRoot` = `<Application Support>/TalentForge/ota` inside the app container, created on
  first use. **Not** `Caches` (the OS may purge it under storage pressure) and **not**
  `Documents` (user-visible through Files when file sharing is enabled).
* Set `NSURLIsExcludedFromBackupKey = true` on `otaRoot`: the data is re-downloadable and
  must not grow iCloud backups.
* Install: `NSFileManager.moveItemAtURL` (rename(2) on one volume).
* State: `NSData.writeToURL(url, atomically = true)` (temp file + rename).
* Free space: `NSURLVolumeAvailableCapacityForImportantUsageKey`.
* File protection: the container default (`CompleteUntilFirstUserAuthentication`), so a check
  started after a reboot and before the first unlock fails cleanly instead of reading
  partial data.
* Network: `NSURLSession` with an ephemeral configuration (no cookies, no URL cache — the
  verifier decides freshness). App Transport Security defaults stay (HTTPS only).

### Desktop (tests and development only)

A directory injected by tests (or `~/.talentforge/ota`), `java.nio.file.Files.move(…,
ATOMIC_MOVE)`.

## 4. Behaviour

### 4.1 Startup (never waits for the network)

1. `DatasetActivationManager.recover()` — local only, before the first screen needs data
   (contract §7.4):
   * delete `state.json.tmp`, `trash/*`, and every `*.part` file under `staging/`;
   * **keep** every staging directory for a later resume (§4.7), except safely identifiable
     garbage: a name that is not a valid release id, a missing or unreadable `attempt.json`, an
     `attempt.json` naming another release id, or a release id already installed with the same
     manifest SHA-256. Recovery does not hash staged files (the resume does) and never reads
     data from staging for the UI;
   * never delete an installed release;
   * read `state.json` strictly. Unreadable → `OtaState.EMPTY` (bundled product only), and
     **no deletion** of any release directory;
   * active release: `releases/<id>/release.json` exists and its SHA-256 equals the hash the
     state recorded; otherwise fall back to the previous release, otherwise the bundle.
     A broken release directory moves to `quarantine/`.
2. Build the first `DatasetSnapshot` (§4.5) and show the UI.
3. Start `OtaUpdateCoordinator.check()` in the background (§4.2).

Cost at startup: one small JSON file and one ≈ 75 KB SHA-256. Files are verified when they are
read (§4.6), so there is no 5 MB re-hash on every start.

### 4.2 Background check

Triggers: app start (after the first frame); return to the foreground when the last
successful check is older than 6 h; a manual "check for talent data updates" action
([OD-5](open-decisions.md)). One attempt at a time. Leaving the app cancels the attempt
cooperatively; its staging directory remains for the next attempt (§4.7).

```
fetch catalog (read at most 1 MiB + 1 byte) ──(network / HTTP error)──▶ end: NETWORK  (backoff 1 h, 2 h, 4 h … max 24 h)
      │
verify catalog (§6.1) ──(reject)──▶ end: <code>  (nothing changes; same backoff)
      │
persist {sequence, payloadSha256, payload} atomically
      │
decide (§6.2) ──NO_CHANGE / KEEP_*──▶ end
      │ ACTIVATE(release)
installed and intact? ──yes──▶ activate (§4.3)
      │ no
install(release) ──(any failure)──▶ end: <code>; staging kept for resume
      │
activate (§4.3) ──▶ publish a new DatasetSnapshot for future sessions ──▶ GC (§5.3)
```

**One request inside an attempt.** Every GET of an attempt — the catalog, the release manifest
and each file blob — is repeated **twice at most, 0.5 s and 2 s after a transfer that did not
complete** (`FetchResult.NetworkError`: no connection, TLS, timeout, reset), on a new connection
(`fetchRetrying`, `OtaConfig.fetchRetryDelaysMillis`). Only after the third failed transfer does
the attempt end with `NETWORK` (and the backoff above applies). An answer of the origin is never
repeated inside the attempt: an HTTP status, an over-limit body, or bytes that miss their pinned
length or SHA-256 end it at once, as before. The URL, the byte limit and every check of contract
v1 are the same on each try. A transfer failure no repeat can change is not repeated either: a
refused or malformed URL, or a rejected TLS identity (untrusted, expired or not-yet-valid
certificate, a certificate for another host) — the platform fetcher marks it
`retryable = false` from the failure's type (JVM/Android) or `NSURLErrorDomain` code (iOS), never
from its message; a reset or stalled handshake stays retryable. The pauses are cancellable:
leaving the app ends a pausing attempt at once, and so does a transfer blocked in the platform's
socket I/O — the Android/JVM fetcher awaits its blocking transfer cancellably and closes the
connection (a body read already blocked keeps its platform thread until the read timeout, holding
only bytes nothing reads; iOS cancels the `NSURLSession` task). On Android/JVM the connect timeout is 10 s (read timeout 30 s), so a connection the
origin never accepts is replaced quickly. Origin: M5.OTA.4b measured 7–11 % of new connections
to the VK staging origin never accepted, which ended whole attempts.

### 4.3 Install and activate

`DatasetDownloadManager.install(entry)`:

1. Open or create `staging/<id>/` and reconcile it with the verified catalog entry (§4.7):
   a staging directory for other manifest bytes is deleted first.
2. Manifest: if `staging/<id>/release.json` exists and still matches the catalog pin
   (length + SHA-256), use it; otherwise fetch the blob with a limit of `manifestByteLength`
   bytes (more is `MANIFEST_HASH_MISMATCH`). Verify it (§6.3).
3. Free space ≥ (sum of `byteLength` of the files not yet validly staged) + 10 MiB, else
   `INSUFFICIENT_STORAGE` (§4.7 for what is kept).
4. For each file, cheapest source first, **always re-checking length and SHA-256 of the bytes
   actually read** against this manifest: the file already in `staging/<id>/<path>` → keep;
   the same SHA-256 in an installed release, or the bundled file at the same path of the same
   build → copy; otherwise download `blobs/sha256/<hex>` to `<path>.part` with a byte limit of
   `byteLength`, verify (§6.4 #1–2) and rename to `<path>`. A staged file that fails the check
   is deleted and acquired again. At most 4 downloads in parallel (tuned by the measurements of
   test-plan §7).
5. Snapshot checks (§6.4 #3–10), including same-build identity against the bundle or an
   installed release of the same build.
6. `ReleaseValidator`: a `ReleaseAssetSource` over the staging directory; for every class in
   the dataset manifest load the document, visuals, tooltips and ruRU localization through the
   existing repositories. Any typed failure → `CONTENT_INVALID`.
7. Ensure `release.json` is written, sync the files, rename `staging/<id>` → `releases/<id>`.

`DatasetActivationManager.activate(id)` atomically writes a new `state.json`
(`previous = active; active = id; installed += id`). Only then do **new** snapshots see the
new product. If the app dies between the rename and the state write, the release is installed
but not active; the next check decides `ACTIVATE` again, finds it installed and activates it
without downloading.

### 4.4 Product selection

`ProductDatasetResolver.product(state)`:

* the active OTA release `R`, if intact and `R.clientBuild ≥ PRODUCT_BUNDLE.clientBuild`;
* otherwise the bundled `PRODUCT_BUNDLE`.

After an app update whose bundled product is newer than the active OTA release, the bundled
product wins at once; the older release stays installed while saved builds reference it. A
catalog never moves a device below its bundled product (`KEEP_BUNDLED_FLOOR`,
[OD-3](open-decisions.md)).

### 4.5 Sessions are pinned: `DatasetSnapshot`

```kotlin
class DatasetSnapshot(
    val product: DatasetBundleId,                                   // where new drafts are created
    private val sources: Map<DatasetBundleId, DatasetAssetSource>,  // installed OTA (newest revision) + bundled
)
```

* A snapshot is immutable. `loadEditorSession` receives one and uses it for the whole
  session: the document, icons, descriptions and localization of an open editor all come from
  the same release, even if a new release is activated meanwhile.
* The app holds a `StateFlow<DatasetSnapshot>`; activation publishes a new one. Open screens
  keep theirs; New Build, All Builds and newly opened editors take the latest.
* Every release in a live snapshot is **leased** (in-memory reference count released when the
  screen closes). GC never deletes a leased release.
* `RoutingDatasetAssetSource` implements `DatasetAssetSource` for a snapshot: it reads
  `<root>/<game>/<build>/…` from the path and delegates to that build's source, so the
  existing repositories work unchanged. An unknown build → `AssetNotFoundException`, exactly as
  today.

For one client build the snapshot prefers the newest installed OTA revision, then the bundle.
Their talent data is identical by contract (`BUILD_IDENTITY_CONFLICT`); only presentation can
differ.

### 4.6 Reads from an installed release

`ReleaseAssetSource.read(path)`: the path must be listed in the release manifest (otherwise
`AssetNotFoundException`); the bytes must match the listed length and SHA-256, otherwise it
throws `AssetReadException` **and** reports the release as corrupt. The activation manager then
quarantines the release and points **new** snapshots at the previous release or the bundle; the
next check re-installs it. The open session shows the existing typed "unavailable" state for
that asset (icons and descriptions already fail soft).

### 4.7 Staging, resume and recovery (one policy)

The staging directory of a release is **progress**, not a release: it is kept across process
death so verified files are not downloaded again, and it is never read for the UI or made
active. The rules, in the order they apply:

| Moment | Rule |
| --- | --- |
| Startup (`recover()`) | delete `*.part`, `state.json.tmp`, `trash/`; keep staging directories except garbage (bad name, missing/unreadable/mismatched `attempt.json`, already installed with the same manifest hash); never delete an installed release; never hash or use staged files |
| Attempt starts for release X | `staging/X/attempt.json` exists and pins the same `manifestSha256` and `manifestByteLength` as the verified catalog → resume; pins different bytes → delete `staging/X` and start fresh; absent → create `staging/X` and write `attempt.json` atomically |
| Each needed file | reuse a staged, installed or bundled copy only after re-reading it and matching length + SHA-256 against X's manifest; a mismatching staged file is deleted; new downloads go to `.part` and are renamed only after their check |
| Not enough space | before downloading, or `ENOSPC` while writing: end with `INSUFFICIENT_STORAGE`, delete the `.part` in flight, keep verified staged files — unless free space is below the 10 MiB margin, then delete `staging/X` |
| Transient failure or cancellation (network, HTTP, `MANIFEST_HASH_MISMATCH`, `BLOB_SIZE_MISMATCH`, `BLOB_HASH_MISMATCH`, storage, I/O, cancelled — the bytes did not match their pin, so nothing is known about the release itself) | end the attempt; the bad blob or `.part` is discarded; `staging/X` stays; retried with backoff (a transfer that did not complete is first repeated twice inside the attempt, §4.2); the active dataset and the state are untouched |
| Release rejected for what it is — a deterministic verdict on bytes that matched their pins (`MANIFEST_INVALID`, `UNSUPPORTED_SCHEMA_VERSION`, `RELEASE_IDENTITY_MISMATCH`, `INCOMPATIBLE_DATA_LEVEL`, `LAYOUT_INVALID`, `CONTENT_*`, `RELEASE_INCOMPLETE`, `BUILD_IDENTITY_CONFLICT`) | delete `staging/X`; record `{X, manifestSha256, code}` in the state; never retry the same manifest bytes (the publisher must publish a new revision); the active dataset is untouched |
| Process death at any point | the next start runs `recover()`; the next attempt for X resumes from the reconciled staging |
| Install complete | `staging/X` is renamed to `releases/X` (it no longer exists as staging); activation, if wanted, is the separate state write (§4.3) |
| Retention after a successful check (§5.3) | delete `staging/X` when X is no longer listed in the verified catalog, when X is neither the active release, nor needed by a saved build, nor requested on demand, or when `attempt.json.updatedAt` is older than 30 days |

Acceptance (test plan S5/S10): after the process is killed in the middle of a download, the
next start keeps the verified files, the next attempt does not request them again (asserted on
the fetch log), and the active dataset changes only after the whole release has been verified
and installed.

## 5. Saved builds and historical datasets

### 5.1 Invariant

A saved build opens **only** on its own `(game, clientBuild)`. No migration, no node-id
remapping, no re-binding to the product, no fallback to "a dataset that looks identical", no
rewrite of the stored record. `allocation-build.v1` and the library format do not change.

### 5.2 Resolving the dataset of a saved or imported build

1. An intact installed OTA release of that build (newest revision).
2. The bundled dataset of that build (`BUNDLED_CLIENT_BUILDS`).
3. A release of that build listed in the **last accepted catalog** (stored on the device, so no
   network is needed to know it exists): download it on demand (§4.3, install only, no
   activation), with visible progress and cancel.
4. Otherwise a typed failure `DatasetUnavailable(game, clientBuild, reason)`, `reason` ∈
   {`NOT_PUBLISHED`, `OFFLINE`, `DOWNLOAD_FAILED(code)`, `NEEDS_NEWER_APP(minDataLevel)`,
   `INSUFFICIENT_STORAGE`}, with a Retry action. The build stays in the library untouched; its
   card shows the same reason.

`NOT_PUBLISHED` (no accepted catalog lists the build) stays until a catalog lists it; the app
never substitutes another build. Share-code import (`prepareBuildImport`) follows the same
resolution and keeps its `ClientBuildUnavailable` failure for case 4.

### 5.3 Retention (`RetentionPolicy`, pure)

Inputs: `OtaState`, the `(game, clientBuild)` set referenced by the library, the leased
releases, the bundled build list.

Keep:

* the active release and the previous release;
* for every client build referenced by a saved build and **not bundled**: its newest
  installed revision;
* every leased release.

Delete: every other installed release; every stale staging directory per §4.7 (never the one
of a running attempt); every `quarantine/*` and `trash/*`.

Rules:

* GC runs only after a successful check, or on an explicit "free up space" action — never at
  startup before the first snapshot, never during an install.
* If the library cannot be read (read exception or decoding failure), GC deletes **only**
  staging, quarantine and trash.
* A release is deleted by renaming it into `trash/` (atomic) and then deleting recursively; a
  crash leaves `trash/`, cleaned next time.
* Deleting a build from the library frees its release on the next GC; opening a build of that
  client build later downloads it again (§5.2 step 3).

Steady-state footprint: ≈ 5 MB × (2 + number of distinct unbundled client builds with saved
builds). Bundled datasets cost nothing extra.

## 6. Failure behaviour (all fail-safe)

| Situation | What the user sees | State change |
| --- | --- | --- |
| No internet at start or during a check | nothing (optional "offline" hint in settings) | none |
| Catalog rejected (any §6.1 code) | nothing | none |
| Active release needs a newer app | optional "update the app for newer talent data" | none |
| Partial download; app killed mid-download | nothing | `.part` deleted at next start; verified staged files re-checked and reused by the next attempt (§4.7) |
| Wrong SHA-256 / size of a blob | nothing | blob discarded, attempt ends; verified staged files kept; retried with backoff |
| Invalid signature / untrusted signer | nothing | none |
| Unknown schema / closed-enum value / duplicate member name / mechanic above the data level | nothing | release recorded as rejected (not retried for the same bytes); staging deleted |
| Insufficient storage | optional settings hint | verified staged files kept for resume; staging deleted only if free space is below the 10 MiB margin |
| Crash between install and activation | nothing | release installed; activated on the next check |
| Crash while writing the state | nothing | the old `state.json` remains (atomic replace) |
| Corrupt file of the active release at read time | the existing "unavailable" fallback for that asset | release quarantined; new sessions use the previous release or the bundle |
| Catalog rolls back to an older release | new drafts use the older release | open editors unchanged; newer release kept while referenced |
| State file unreadable | app runs on bundled data | nothing deleted; the next catalog rebuilds the state |
| Saved build's dataset not available | typed "unavailable" with reason and Retry | build untouched |

No row deletes or rewrites a saved build. The library and the OTA store are separate storage
areas, and no OTA code path has a write handle to the library.

## 7. Privacy and network surface

* Requests: `GET <base>/v1/channels/<channel>/catalog.json` and
  `GET <base>/v1/blobs/sha256/<hex>`. No query parameters, identifiers, cookies or custom
  headers beyond a fixed `User-Agent: TalentForge/<appVersion>` ([OD-4](open-decisions.md)).
* The hosting provider sees IP addresses and request times. The privacy policy and the store
  data-safety forms must say so.
* No analytics or crash reporting is added. Failures are visible locally only (non-release
  builds may show a debug list of the last attempts and their codes).

## 8. Configuration per build type

| Build | Channel | Base URL | Keyring | Data level |
| --- | --- | --- | --- | --- |
| Android release, iOS App Store | `production` | production base | production primary + backup | 1 |
| Android debug, iOS development | `staging` | staging base | staging key(s) | 1 |
| Desktop / JVM tests | injected | fake `BlobFetcher` | `docs/ota/fixtures/v1/keyring.json` (TEST keys) | 1 |

`OtaConfig` is compile-time (Gradle-generated constants), never read from a downloaded
document. `enabled` is set **per platform** and build type, so Android can ship OTA while iOS
builds keep it off until the iOS release gate passes (test-plan §6, [OD-17](open-decisions.md)).
With `enabled = false` the binary behaves exactly as today. The first OTA-enabled store release needs working production hosting
and keys first ([OD-1](open-decisions.md), [OD-2](open-decisions.md)).

**M5.OTA.PROD.3 (2026-10-21).** Для Android production-строка таблицы закреплена в репозитории: base URL `https://ota.mythweld.com`, keyring `docs/ota/keys/production-keyring.json` (`tf-prod-2026a` + резервный `tf-prod-2026b`). Владелец задаёт только `talentforge.ota.android.production.enabled=true`, любая другая настройка origin или keyring останавливает сборку. Release/play manifest содержит `INTERNET` ровно при включённом канале ([android-production-enablement](android-production-enablement.md)). iOS production по-прежнему выключен.
