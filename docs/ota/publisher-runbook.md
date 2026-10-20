# OTA publisher runbook (M5.OTA.2, remote staging M5.OTA.4b, production store M5.OTA.PROD.1)

Operator procedures for `talent-data ota …` — the commands that turn a committed build
into a published OTA release. Design: [publisher-and-security.md](publisher-and-security.md).
Contract: [ota-contract-v1.md](ota-contract-v1.md). Everything below assumes the `ota`
extra installed (`pip install -e '.[ota]'`); the remote store (§6) also needs `s3`.

Every mutating command is a **dry run by default**: nothing is written without
`--execute`, and `gc` additionally needs `--confirm`. Nothing ever deletes a listed
release or a blob a listed release references.

## 0. Rehearse offline first

The whole pipeline runs against a local directory standing in for the bucket — the same
way the test suite (`tests/unit/ota/test_ota_publisher_e2e.py`) does:

```bash
KEYRING=/tmp/ota-rehearsal/keyring.json   # any TEST keyring (see §7)
PEM=/tmp/ota-rehearsal/test-primary.pem
STORE=/tmp/ota-rehearsal/store

talent-data ota build-release --game forever --build 1.60.1.70450 \
    --commit <sha> --created-at 2026-10-17T23:00:00Z --out /tmp/ota-rehearsal/artifacts
talent-data ota validate  --manifest .../1.60.1.70450-r1/release.json \
    --store $STORE --keyring $KEYRING --releases-docs /tmp/ota-rehearsal/approvals
talent-data ota report    --manifest .../release.json --store $STORE --keyring $KEYRING
talent-data ota stage     --manifest .../release.json --store $STORE --keyring $KEYRING \
    --key $PEM --key-id test-primary --execute
talent-data ota verify-live --store $STORE --keyring $KEYRING --channel staging
```

The staging smoke (test plan §4) happens on staging-channel app builds against exactly
such a store before any production promotion.

## 1. First publication (production)

Preconditions: merged `main` commit with the build's data (G1); approval record not
needed unless G5/G7 flagged; production keys generated per publisher design §5 and the
**public** keyring committed.

```bash
# 1. build the release from the committed tree of a commit on origin/main
talent-data ota build-release --game forever --build <build> --revision 1 \
    --commit <40-hex sha> --out artifacts/ota

# 2. gates G1–G8; exit 0 = no STOP (a FLAG needs an approval record for promote)
talent-data ota validate --manifest artifacts/ota/<build>-r1/release.json \
    --store <store> --keyring <public keyring> --releases-docs docs/ota/releases

# 3. the human review diff vs. the active production release
talent-data ota report --manifest artifacts/ota/<build>-r1/release.json \
    --store <store> --keyring <public keyring>

# 4. upload blobs (verified by read-back); writes the unsigned payload + prints
#    the SHA-256 of the PAE to sign offline
talent-data ota promote --manifest artifacts/ota/<build>-r1/release.json \
    --store <store> --keyring <public keyring> --releases-docs docs/ota/releases \
    --out artifacts/ota --execute

# 5. ON THE OFFLINE SIGNER: sign the printed PAE digest with tf-prod-<yyyy>a
#    (hardware token / keychain / offline media) and export the DER signature.

# 6. verify the external signature against the committed public key and write
#    catalog.json — the last write of the pipeline
talent-data ota attach-signature --payload artifacts/ota/pending-production.payload.json \
    --signature <der file> --key-id tf-prod-2026a \
    --store <store> --keyring <public keyring> --execute

# 7. prove the live state
talent-data ota verify-live --store <store> --keyring <public keyring>
```

`attach-signature` never trusts the earlier steps alone: before writing it re-proves
the whole publication from the signed bytes — the payload must hash to the pin the
pending meta recorded (a cheap tripwire against accidental edits), be a catalog of the
exact `--channel`/`--game`, be a valid successor of the live catalog (sequence exactly
live + 1, every live release still listed with identical members), reference only
blobs that exist and hash to their pins, and the live `sequence` is re-read one final
time inside the write. If anything was published between steps 4 and 6, it aborts and
the promotion restarts at step 4.

**Every release the signed catalog appends is re-proven from the store and the
repository before the write:** its content passes `verify_release_manifest` /
`verify_release_files` over the actual store blobs, and gates G1–G8 run again with
its approval records — rebuilt from the pinned manifest bytes and the provenance
commit. The pending payload and its meta are local, editable files; the gate re-run
is what makes them impossible to use as a bypass (a correctly signed, structurally
valid catalog whose appended release never passed `promote` is stopped here — G6
above all). A pure rollback/re-sign appends no release and needs no gate re-run:
signature, integrity and succession are its whole proof.

## 2. Routine refresh (a new build)

Same as §1 with the new build's commit. `promote` appends the release to the catalog
(append-only: every earlier release stays listed with identical members) and makes it
active. Re-read `ota report` first — it shows the semantic diff, warning moves, G6
findings and the bytes a device will download.

## 3. Rollback

```bash
talent-data ota rollback --active <older releaseId> \
    --store <store> --keyring <public keyring> --out artifacts/ota --execute
# sign the printed PAE offline, then:
talent-data ota attach-signature --payload artifacts/ota/pending-production.payload.json \
    --signature <der> --key-id <key id> --store <store> --keyring <public keyring> --execute
talent-data ota verify-live --store <store> --keyring <public keyring>
```

A rollback is a **new** catalog with a higher `sequence` whose `activeReleaseId` names
an older listed release. Releases are never unlisted (OD-7); the bundled product is the
client-side floor (`KEEP_BUNDLED_FLOOR`).

## 4. Re-sign (before expiry — and after it)

Catalogs expire (`issuedAt + 45 days`, OD-13). Re-issue at ~30 days, or with any change:

```bash
talent-data ota resign --store <store> --keyring <public keyring> --out artifacts/ota --execute
# sign + attach-signature + verify-live as in §3
```

Schedule the reminder at half the validity window (test plan §6A.9).

**If the window has already lapsed**, `resign` (and `rollback`) are still the recovery
path: the live catalog's signature, structure, identity and integrity are verified as
always, and only the expired window is forgiven *for the issuer of the next catalog* —
which then ships a fresh `sequence` and validity window. Clients keep rejecting the
lapsed catalog until it is replaced, exactly as the contract requires. `promote`
deliberately stays strict: recover with `resign` first, then promote the new release.

## 5. Key loss

* **Primary lost:** sign with the backup key (`--key-id tf-prod-<yyyy>b`); apps pin it.
  Generate a new backup per publisher design §5.2 and ship an app update re-pinning the
  pair; then retire the lost key id.
* **Primary compromised:** sign every new catalog with the backup only; ship an app
  update dropping the compromised id; tell users if it happened.
* **Both lost:** apps keep working on their last activated data and stop updating.
  Recovery is an app update with new keys.

The tool itself can neither load a production private key (`ota sign` refuses every
non-`test-`/`tf-staging-` key id) nor read one from the repository or CI: production
signing is the external steps 5–6 of §1, always.

## 6. Remote store: VK Cloud staging (OD-1, M5.OTA.4b)

The origin is object storage serving `<base>/v1/…` (contract §1). Staging is **VK Cloud
Object Storage** (OD-1): bucket `talentforge-ota-staging` in `ru-msk`, S3 API
`https://hb.ru-msk.vkcloud-storage.ru`, public origin
`https://talentforge-ota-staging.hb.ru-msk.vkcloud-storage.ru`. Production has its own bucket
(`talentforge-ota-production`, M5.OTA.PROD.1 - prepared, created by the owner): its setup,
publication, rollback, re-sign and recovery are
[production-deployment.md](production-deployment.md). The bucket names the channel (committed
profiles, `talent_data.ota.s3_channels`): a staging command against the production bucket, or a
production command against the staging bucket, is refused before connecting.

### 6.1 Setup (once per operator machine)

```bash
pip install -e '.[ota,s3]'                     # boto3 is the s3 extra
aws configure --profile talentforge-staging    # the S3 access key (prefix v1/), region ru-msk
python scripts/ota_vkcloud_staging.py keygen ~/.talentforge/ota-staging-vkcloud \
    --key-id tf-staging-2026 \
    --origin https://talentforge-ota-staging.hb.ru-msk.vkcloud-storage.ru
```

Two different secrets, never in git and never mixed: the **S3 access key** lives only in the
AWS CLI credential store (`~/.aws/credentials`, profile `talentforge-staging`); the
**staging signing key** `tf-staging-2026` is a PEM (mode 0600) outside any checkout
(`keygen` refuses a directory inside one). Only the public keyring goes into a debug build.

### 6.2 Publish to staging

```bash
S3="--backend s3 --s3-bucket talentforge-ota-staging \
    --s3-endpoint https://hb.ru-msk.vkcloud-storage.ru --s3-region ru-msk \
    --aws-profile talentforge-staging"
K=~/.talentforge/ota-staging-vkcloud

talent-data ota build-release --game forever --build 1.60.1.70450 --commit <sha on origin/main> \
    --created-at <UTC instant> --out artifacts/ota
talent-data ota validate --channel staging --manifest artifacts/ota/1.60.1.70450-r1/release.json \
    $S3 --keyring $K/staging-keyring.json
talent-data ota stage --manifest artifacts/ota/1.60.1.70450-r1/release.json $S3 \
    --keyring $K/staging-keyring.json --key $K/tf-staging-2026.pem --key-id tf-staging-2026   # dry run
talent-data ota stage … --execute
talent-data ota verify-live --channel staging $S3 --keyring $K/staging-keyring.json
```

Rollback / re-sign of staging are the §3/§4 commands with `--channel staging $S3`, the
`sign` convenience signer with the staging PEM, and `attach-signature --channel staging $S3
--execute`. `gc` lists with `$S3`; it deletes only with `--execute --confirm`.

What the adapter guarantees (`talent_data.ota.s3_store`):

* **Credentials per channel.** A write needs exactly the channel's AWS CLI profile
  (`--aws-profile talentforge-staging` / `talentforge-production`) and no `AWS_ACCESS_KEY_ID` /
  `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN` in the environment; the other channel's profile is
  refused even for reads. Checked before any request (`check_credentials`); reads may use the
  standard credential chain.
* **Dry run is read-only by construction.** The store is opened writable only by `stage` /
  `promote` (blobs) / `attach-signature` under `--execute`, and able to delete only by
  `gc --execute --confirm` - on staging; a production store never deletes.
* **Keys.** Writes: `v1/blobs/sha256/<64 hex>` and the store's own channel catalog only. The
  other channel's catalog key is refused before any request (it may be read, so gates and `gc` fail
  closed if one ever appears there). Anything else is refused.
* **Blobs are immutable.** A blob must hash to its key; an existing blob is never rewritten;
  every put is checked by `HEAD` (length, `ETag` = MD5, `Content-Type`, `Cache-Control`, no
  `Content-Encoding`) and then read back and re-hashed by the publisher (`upload_blobs`).
* **Catalog last**, after every referenced object was re-read and re-hashed; read back after
  the put.
* **Failure kinds.** 404 / 403 / precondition / network / credentials are distinct
  (`S3Failure`); a 403 is never "absent".
* **HTTP metadata.** Blobs `Cache-Control: public, max-age=31536000, immutable`, the catalog
  `public, max-age=300`; `Content-Type: application/json` (catalog and JSON blobs) or
  `image/png`. Uploads carry `Content-MD5` and no streaming checksum: botocore's default
  `aws-chunked` upload makes VK Cloud serve the object with `Content-Encoding: aws-chunked`
  (observed on the owner's first test object), which the put verification would reject.

### 6.3 One publisher at a time

VK Cloud **ignores conditional writes** (`If-None-Match: *` and `If-Match` both answer 200 —
`scripts/ota_vkcloud_staging.py probe-conditional`, 2026-10-18). The adapter still sends them
(harmless, and effective if VK adds support), but on VK the concurrency guards are: the live
`sequence` re-read immediately before the catalog put (`write_catalog`), the read-back of the
catalog right after it, and this procedure:

1. Only one operator publishes to staging at a time; no CI job holds the S3 key.
2. Before a publication, `verify-live --channel staging` and note the sequence.
3. After it, `verify-live` again: the sequence must be exactly the one `stage`/`attach-signature`
   printed. If not, stop: two publications raced. Clients never accept a lower sequence and reject
   a re-used sequence with other content (`SEQUENCE_REGRESSION`), so a lost race leaves clients on
   their last accepted catalog; recover by publishing the next sequence from the verified live
   state (`resign --channel staging`).

### 6.4 Origin behaviour to expect (measured, M5.OTA.4b report)

* About 1.4 s per uploaded blob (five sequential requests at ~140 ms RTT): a full release
  (≈ 397 objects) takes ≈ 8–9 minutes; the catalog re-verification another ≈ 3 minutes.
* A minority of new TCP connections to the public endpoint are not accepted (11 of 100 from the
  test network). The publisher's botocore retries absorb them; an interrupted `stage` is re-run
  as is (`upload_blobs` skips present blobs; the catalog is written only at the end).
* `Accept-Encoding: gzip` gets `application/json` compressed on the fly (ADR-0002 O2), PNG not.
  No HTTP/2. No redirects. Plain `http://` is also served — HTTPS-only is enforced by the client.
* Responses carry `X-Amz-Version-Id`: the bucket appears versioned, so a replaced catalog keeps
  its previous versions and a `gc` deletion leaves a delete marker (not verified — the key may not
  read the bucket's versioning configuration).

### 6.5 Production store (M5.OTA.PROD.1)

The production remote store keeps every rule above and adds, for the production channel only:

* **Its own committed profile**: bucket `talentforge-ota-production`, AWS CLI profile
  `talentforge-production` (a separate prefix key), public origin `https://ota.mythweld.com`.
* **A catalog guard in the adapter** (`ProductionCatalogGuard`): a writable production store
  needs the production keyring (`tf-prod-*` keys only), and every catalog put must verify on the
  wall clock, be signed by `tf-prod-*` keys only, keep at least 7 days of validity, not be
  future-dated and be the exact successor of the live catalog. No fault injection, test key or
  replay can reach the production path, whoever calls the store.
* **No rehearsal clock, no deletion**: production writes refuse `--now`; `gc` lists only.
* **Offline signing input**: `promote` / `rollback` / `resign` also write
  `pending-<channel>.pae.bin`, the exact bytes `openssl dgst -sha256 -sign` signs offline.

Both channels re-read the live catalog's `ETag` immediately before the catalog put (VK ignores
`If-Match`) and read the catalog back after it. The hostname, CDN and certificate are the
owner's setup: [production-deployment.md](production-deployment.md) §1–§3; the read-only
`scripts/ota_production_preflight.py` checks them.

## 7. Keyrings, TEST keys and the approval record

* `--keyring` is a JSON file `{"keys": [{"keyId", "algorithm": "ecdsa-p256-sha256",
  "publicKeySpki": "<base64 DER SPKI>"}]}` — same shape as
  `docs/ota/fixtures/v1/keyring.json`. Production apps pin `tf-prod-*` ids only; a
  `test-*` key must never appear in a shipped keyring (contract §8).
* TEST keys derive from public seeds (`scripts/generate_ota_fixtures.py`), so anyone
  can sign with them — that is the point: they exist for fixtures, rehearsals and the
  offline test suite, never for production.
* The **approval record** (OD-12) is `docs/ota/releases/<releaseId>.md`, merged by PR
  before the promotion. The publisher parses three lines:

  ```markdown
  - Release: 1.60.1.70450-r1
  - Manifest SHA-256: sha256:<the exact manifest bytes this record approves>
  - Accepted gates: G5, G7
  - Live-build evidence: <client screenshot link + date + region, or a patch note>
  ```

  Only G5 and G7 can be accepted; a record naming a STOP gate (G1–G4, G6, G8) is itself
  an error and the promotion refuses. G7 acceptance requires the evidence line. When
  the `Manifest SHA-256` line is present, `promote` refuses a record whose pin does not
  match the release being promoted — pin it, so the record approves exact bytes, not a
  release id. (The record is merged by PR before the promotion: being on `main` is the
  review mechanism, and the promotion PR references it.)

## 8. Garbage collection

`gc` lists blobs no listed release of either channel references; it deletes only with
`--execute --confirm`, and never touches the catalogs:

```bash
talent-data ota gc --store <store> --keyring <public keyring>            # list only
talent-data ota gc --store <store> --keyring <public keyring> --execute --confirm
```

Both channels' live catalogs are signature-verified first, and every release manifest
is verified against its catalog pin (hash and length) before its file list is trusted:
on any mismatch gc aborts without deleting anything (fail closed — an unverifiable or
corrupted catalog must never drive deletions).
