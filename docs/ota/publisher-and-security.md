# OTA publisher and security — design for M5.OTA.2

Status: **proposed** (M5.OTA.1). Contract: [ota-contract-v1.md](ota-contract-v1.md).
Implementation brief: [M5.OTA.2-publisher-brief.md](M5.OTA.2-publisher-brief.md).

## 1. Principles

1. **Only reviewed data is published.** Input is the committed `data/` at a commit on `main`
   (merged by PR, CI green). Never a working tree, never an unmerged branch, never the output
   of the freshness watcher.
2. **Humans decide; tools prepare.** The publisher builds, validates, diffs and signs; every
   change that reaches production users is started by the owner by hand. Nothing publishes on a
   schedule or a webhook.
3. **Immutable artifacts, one mutable pointer.** Blobs are written once. The catalog is the only
   object ever replaced, and it is replaced last.
4. **Nothing is deleted** that a listed release references. Rollback changes a pointer.
5. **Static hosting only.** Object storage + CDN. No server code, no database, no accounts.

## 2. Pipeline

```
  main@<commit> (merged, CI green)
          │
  1. build-release   --game forever --build <b> --revision <n> --commit <sha>
          │   reads data/{normalized,visuals,tooltips,localization}/forever/<b>/ at <commit>
          │   → out/<releaseId>/release.json + the blob list (deterministic)
          │
  2. validate        gates G1–G8 below; any "stop" ends here
          │
  3. review-report   human-readable diff vs. the active production release (classes, trees,
          │          nodes added/removed/changed, warnings by code, allocation inventory,
          │          icons/tooltips/locales changed, bytes a device will download)
          │
  4. approve         owner writes the approval record (§2.2) — required when a gate flags
          │
  5. stage           upload missing blobs (each verified by GET + SHA-256 after upload), then a
          │          new staging catalog signed with the STAGING key
          │
  6. smoke           owner runs a staging-channel app build: A→B, saved builds, offline start
          │          (test plan §4)
          │
  7. promote         new production catalog: sequence+1, release appended, active = release,
          │          signed offline by the owner with the PRODUCTION key, uploaded last
          │
  8. verify-live     fetch the live production catalog through the CDN, verify it with the
                     committed production public keys, fetch and verify every blob of every
                     listed release; print a report
```

Rollback is steps 7–8 with `--active <older releaseId>` and no new release. Re-signing before
expiry is steps 7–8 changing only `sequence`, `issuedAt` and `expiresAt`.

### 2.1 Gates

| Gate | Check | On failure |
| --- | --- | --- |
| G1 Source | the commit is on `origin/main`; the release is built from `data/` at that commit (e.g. `git archive`/`git show`), not from the working tree | stop |
| G2 Pipeline validation | the build's dataset manifest lists every class of the game with `validation.errors == 0`; `talent-data manifest` passes; every document parses with the public models; visuals/tooltips/localization are complete | stop |
| G3 Contract | the produced release passes the reference verifier (`talent_data.ota.verify`): contract §6.3 and §6.4 end to end | stop |
| G4 Same-build identity | when the build already has a published release (or is bundled in a shipped app): identical talent-data classes (token, file, treeIds, contentHash) | stop — owner decision [OD-8](open-decisions.md) |
| G5 New warnings | `warningsByCode` has a code absent from the active production release, or a higher count | needs approval |
| G6 New or changed mechanics | allocation inventory (`scripts/allocation_inventory.py`) vs. the active release: any new unsupported node type, entry type, prerequisite shape or gate behaviour; any node whose support status changes | needs approval **and** a recorded `minDataLevel` decision |
| G7 Live-build evidence | the client build is confirmed live by recorded evidence (§4) | production needs approval with evidence; staging allowed |
| G8 Reproducibility | rebuilding the release from the same commit yields identical bytes; PNGs encoded in the pinned environment ([OD-6](open-decisions.md)) | stop |

### 2.2 Approval record

One file per promotion, e.g. `docs/ota/releases/<releaseId>.md` (or the promotion PR description,
[OD-12](open-decisions.md)): release id and manifest SHA-256; source commit; flagged gates and why
they are accepted; chosen `minDataLevel` and why; live-build evidence; approver and date. The
publisher refuses `promote` without a record when a gate flagged.

## 3. Channels, storage and atomicity

* Layout per contract §1: `v1/channels/{production,staging}/catalog.json`, `v1/blobs/sha256/<hex>`.
* **Upload order:** blobs first (skip those already present; verify new ones by reading them
  back), the catalog last. A catalog is never uploaded while one of its blobs is missing or
  unverified.
* **Atomic pointer update:** one single-object `PUT` of `catalog.json`. S3-compatible stores give
  readers the old or the new object, never a mix; CDN caches converge within the catalog's
  `max-age` (≤ 300 s). Clients handle both: an older copy is still a valid catalog or a harmless
  `SEQUENCE_REGRESSION` (they keep what they have).
* **One publisher at a time:** before writing, the publisher reads the live catalog and refuses to
  write unless its `sequence` is the one the new catalog was built on. Where the store supports
  conditional writes (`If-Match` on the ETag), use them.
* **Staging vs. production:** separate catalogs, separate keys, shared blobs. A staging catalog is
  signed only by staging keys, which production apps do not pin, and says `channel: "staging"`,
  which production apps reject anyway.
* **Server-side retention:** a blob referenced by a listed release of either channel is never
  deleted. Removing unreferenced blobs (e.g. from an abandoned staging) is a separate manual
  command that lists what it would delete and needs `--confirm`.

The hosting provider is open ([OD-1](open-decisions.md)). Requirements: HTTPS on a hostname the
project controls (so the provider can change without an app update), per-object
`Cache-Control`, atomic single-object overwrite, no cost without the owner's approval.

## 4. Why `is_bgdl=false` does not prove a build is live

The freshness watcher treats a build as *publishable* when wago.tools lists it with
`is_bgdl: false`. That is a necessary condition for TalentForge data, **not** evidence that the
game servers run the build:

* `is_bgdl` reflects Blizzard's patch configuration as mirrored by wago.tools: whether a build is
  offered as a **background download** (pre-staged before a patch day) or as the regular install.
  It describes what the **launcher/CDN** distributes, not what the **realms** accept.
* The realms' accepted client builds are not public. Clients are routinely distributed before
  realm maintenance ends, and realms may keep accepting an older build for a while after a new
  one is distributed.
* Distribution is per product and region. wago.tools lists products (`wow_classic_beta`, `wowf`)
  without saying which one the live Forever realms belong to; on 2026-10-17 `wowf` listed 70245
  while the beta listed 70450 (`docs/dataset-freshness-watch.md`, "Known gap").
* Two builds can share one version string (70245 with two `build_config`s), and wago.tools
  exports tables by version string alone.
* wago.tools is a third-party mirror: a flag can be missing, late or wrong, and can change after
  the fact.

So OTA publication to production requires **recorded live-build evidence** (gate G7) in the
approval record — e.g. the client build shown by a game client connected to a live realm (login
screen or version display), with date, region and a screenshot under `docs/release/…`, or an
official patch note naming the build. Without evidence a release may go to **staging** only.
The pipeline never automates this judgement and is never triggered by the freshness watcher.

## 5. Keys

### 5.1 Inventory

| Key | Purpose | Private key location | Pinned by |
| --- | --- | --- | --- |
| `tf-prod-<yyyy>a` (primary) | signs production catalogs | offline, under the owner's control: a hardware token / non-exportable OS keychain key, or an encrypted file on offline media ([OD-2](open-decisions.md)) | production apps |
| `tf-prod-<yyyy>b` (backup) | recovery when the primary is lost | generated with the primary; kept on two separate offline media (e.g. encrypted USB in a safe + a paper/QR copy); never used day to day | production apps |
| `tf-staging-<yyyy>` | signs staging catalogs | CI secret or the owner's machine | staging app builds only |
| `test-*` | fixtures | public seeds in this repository | never an app |

Public keys are committed and compiled into the app. Private keys are **never** committed,
logged, uploaded as CI artifacts or sent to any service.

### 5.2 Procedures

* **Generation:** on an offline machine. Record in the repository the key id, base64 SPKI,
  SHA-256 fingerprint of the SPKI and creation date. Test-sign and verify a dummy catalog with
  both production keys before any app pins them.
* **Signing:** `promote` writes the unsigned payload and prints the SHA-256 of the PAE it expects
  to be signed; the owner signs offline; the tool verifies the signature with the committed public
  key before uploading.
* **Primary key lost:** sign with the backup key (apps already pin it). Generate a new backup;
  ship an app update pinning {backup as the new primary, the new backup}; then retire the lost key
  id. No user loses data at any point.
* **Primary key compromised** (someone else can sign): sign every new catalog with the backup key
  only; ship an app update that drops the compromised key id. Until users update, an attacker who
  **also** controls the hosting or DNS could serve a signed catalog with a higher sequence. The
  worst outcome is wrong **data** (strictly parsed, never executed) or a blocked update. Saved
  builds are never touched (each opens on its own build). Tell users if it happened.
* **Both keys lost:** apps stop receiving updates and keep working on their last activated data.
  Recovery is an app update with new keys (and refreshed bundled data, if possible).
* **Rotation:** yearly or on any custody change. The new key ships in an app update first and is
  used to sign only after most installs pin it.
* **Hosting access recovery:** the hosting account uses 2FA with recovery codes stored with the
  backup key. Write credentials for the bucket are separate from the signing keys and can be
  rotated independently; read access needs no credentials.

## 6. Threat model

| Threat | Mitigation | Residual risk |
| --- | --- | --- |
| Corrupt or truncated download | per-file size and SHA-256; strict envelope parsing | none (attempt fails, retried) |
| Compromised CDN / bucket / DNS | offline-key signature over the catalog; all else hash-pinned; base URL compiled in | denial of updates (fail-safe) |
| Replay of an older catalog | persisted `sequence`; an equal sequence must be byte-identical | a device that never saw a newer catalog can be held on an older valid one until it expires |
| Freeze (same old catalog forever) | `expiresAt` ≤ 180 days | up to the validity window |
| Staging data reaching production | signed `channel`; separate keys | none |
| Path traversal / overwriting files | closed role/path grammar; writes only under the staging root | none |
| Size or decompression bombs | `byteLength` limits enforced while reading; 16 MiB per file, 256 MiB per release; no archives | none |
| Executable or behaviour-changing payload | no executable role; only existing strict data contracts | none in v1; every future contract must keep this property |
| Data the app cannot interpret | strict parsing; `minDataLevel`; gates G5/G6 | open-union values (node types) shown as unsupported if G6 is overridden |
| Documents of different builds mixed | cross-document identity; same-build identity | none |
| Silent re-interpretation of saved builds | builds open on their own `clientBuild`; one talent dataset per build | none |
| Wrong device clock | only expiry uses the clock; a wrong clock can only refuse a catalog | delayed updates |
| Key loss / compromise | backup key pinned from day one; §5.2 | compromise + hosting control → wrong data until an app update |
| Publisher bug | reference verifier as gate G3; `verify-live`; staging smoke | caught before production in normal operation |

## 7. Release readiness of OTA itself

OTA may be enabled in a public store release only when every item of
[test-plan.md §6](test-plan.md#6-release-readiness-criteria-ota-public-release-gate) is checked.
