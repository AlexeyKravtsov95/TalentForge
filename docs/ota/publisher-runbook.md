# OTA publisher runbook (M5.OTA.2)

Operator procedures for `talent-data ota …` — the commands that turn a committed build
into a published OTA release. Design: [publisher-and-security.md](publisher-and-security.md).
Contract: [ota-contract-v1.md](ota-contract-v1.md). Everything below assumes the `ota`
extra installed (`pip install -e '.[ota]'`).

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

## 6. Hosting change (OD-1)

The origin is object storage + CDN serving `<base>/v1/…` (contract §1). The app's
`<base>` is compiled in per channel, on a hostname the project controls — the provider
can change without an app update only through that hostname's DNS.

What M5.OTA.2 ships today: the `BlobStore` protocol and `LocalDirectoryStore` (a local
directory as the bucket — tests, rehearsals, and this runbook's examples). **Not
shipped: the S3-compatible remote adapter.** AWS SigV4 request signing is a large
surface that cannot be honestly tested from this repository against no account, so the
brief's fallback applies: protocol + local store, gap documented. Writing the adapter
means implementing `BlobStore.get/put/exists/list/delete` over httpx with SigV4,
setting the §1 cache headers (`public, max-age=31536000, immutable` for blobs,
`public, max-age=300` for the catalog) and conditional writes (`If-Match` on the
catalog ETag) where the store supports them; every ordering rule already lives in
`talent_data.ota.store`, so the adapter changes one implementation and no rule. Until
then, publishing to a real origin is out of scope (and no production upload has ever
run).

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
