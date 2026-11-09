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
such a store before any production promotion. The automated part of it for a candidate -
both releases built from one commit on `main`, staged on an isolated local stand, the real client
(JVM) as the app before the update - is the **OTA staging verification** workflow
([staging-verification.md](staging-verification.md)); it never replaces the approval record or
the owner's exact release approval, and devices stay a manual step.

## 1. Manual first-publication / owner recovery reference (production)

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

The data of a new build is prepared automatically (M5.OTA.AUTO.1,
[auto-prepare.md](auto-prepare.md)): a `PREPARED` artifact carries the normalized data,
icons, descriptions, translations, the diff, the gate results and a git bundle of the
candidate. It is input for the refresh PR, never a publication: the release is built here
from the merge commit on `main`.

For production the routine path is the guarded GitHub release workflow described in
[production-release.md](production-release.md). It binds the exact package/staging bytes,
opens a draft release PR, and only after owner review/merge plus successful exact main CI
runs the protected writer. No extra manual signing command is part of an ordinary release.
The legacy local helper below remains available only for owner recovery after the stop/drain
procedure in [GitHub setup/recovery](#github-production-automation-owner-setup).

`promote` appends the release and preserves every earlier entry; review the semantic diff,
G1–G8, G5/G7 evidence and download volume in the release PR.

## 3. Manual rollback (after CI stop/drain)

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

## 4. Re-sign and expired-catalog recovery

Catalogs expire (`issuedAt + 45 days`, OD-13). Re-issue at ~30 days, or with any change:

```bash
talent-data ota resign --store <store> --keyring <public keyring> --out artifacts/ota --execute
# sign + attach-signature + verify-live as in §3
```

The enabled daily workflow renews at <=15 days remaining with 45-day validity. It never
needs a historical package or new G7. The local commands above are the owner recovery path
after CI stop/drain, not a second routine writer.

**If the window has already lapsed**, automated renewal refuses it. Owner-only local
`resign` (and `rollback`), after CI stop/drain, are still the recovery
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

The generic `ota sign` / staging inline signer still refuse production key ids. The separate
protected `CiPemSigner` deliberately reads only the existing pinned primary in CI (OD-2);
backup signing remains the offline owner recovery route. Never store private keys in git.

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

1. Only one operator publishes to staging at a time. Production has a separate protected CI
   writer and separate credentials; revoke ordinary local production write before enabling it.
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

## GitHub production automation: owner setup

This is a post-review/merge owner procedure. Implementation validation does not prove
production delivery. No App, environment, secret, IAM policy, variable, remote state branch or
catalog was configured by this change. Do not reissue `1.60.1.70532-r1` or request a new G7 for it.

1. Keep repository variable `OTA_PRODUCTION_AUTOMATION_ENABLED` unset or `false`. Protect
   main and review the workflow/source and [validation record](2026-11-01-github-publication-validation.md).
2. Create/install the GitHub App only in TalentForge, contents and pull requests read/write.
   In main-only environment `ota-preparation`, set variable `OTA_APP_CLIENT_ID` and secret
   `OTA_APP_PRIVATE_KEY`. Its short-lived installation token proposes release PRs so normal
   CI can trigger; no close/reopen workaround. No App credential belongs in production.
3. Create environment `ota-production`, Deployment branches/tags restricted to `main`, no
   second required reviewer (the exact release PR is the approval). Set repository variable
   `OTA_PRODUCTION_KEY_ID=tf-prod-2026a` (preflight also needs it). Import the owner's existing
   primary as environment secret `OTA_PRODUCTION_PRIVATE_KEY_PEM`; optional
   `OTA_PRODUCTION_KEY_PASSWORD`. Never generate/request a replacement key for setup; verify
   P-256 and the pinned public key. `tf-prod-2026b` remains offline. Do not put keys in chat,
   shell arguments, artifacts, logs, caches or exports.
4. **Withhold both CI S3 secrets during bootstrap.** Temporarily set enable flag to literal
   `true`; manually dispatch `init-journal` on main with `execute=true`, then `signer-smoke`
   with `execute=true`. Init uses GitHub token only to initialize `ota-publication-state`;
   normal missing/corrupt journal is a refusal, never auto-reinitialized. Smoke signs a
   synthetic domain-separated PAE and verifies the pinned public key, never a catalog PUT;
   its step receives PEM/password only. Require explicit successful status. Set flag back to
   `false` while completing setup. Even init/smoke require flag true **and** explicit execute;
   there is no disabled maintenance exception. Withheld S3 prevents automatic runs obtaining
   catalog-write capability during this temporary enable window.
5. Issue dedicated production S3 credentials: read/get/head/list limited to production bucket
   `talentforge-ota-production` / `v1/`, PUT only `v1/blobs/sha256/*` and
   `v1/channels/production/catalog.json`. Scope list to the needed prefixes. No DeleteObject,
   ACL/policy changes, staging access or any other bucket. Endpoint/origin/region/profile and
   keyring are committed (`s3_channels.PRODUCTION_PROFILE`); there are no CLI overrides.
   Store only `OTA_PRODUCTION_S3_ACCESS_KEY_ID` and `OTA_PRODUCTION_S3_SECRET_ACCESS_KEY`
   in `ota-production`. PEM/AWS profile exist only in restricted temporary directories,
   cleaned on success/failure. These four production secrets never enter preflight/notifications.
6. Revoke ordinary local publisher **write** credentials and confirm no local publisher is
   active before final enabling. VK ignores conditional PUT, and GitHub concurrency cannot
   lock a local process. A local file lock does not solve inter-machine races.
7. Keep only `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` in main-only `notifications` environment;
   no production/App secrets. The hourly observer and direct outcome notification share one
   lock/state marker. Missing Telegram secrets are explicit SKIPPED, never delivery evidence.
8. Only after these checks, final-enable literal `true`. The next genuinely new release follows
   data merge → exact staging/package → draft release PR → owner G5/G7 review/merge → exact
   main/push CI → secret-free preflight → protected writer → full public proof → Telegram.
   First real delivery remains to be observed; do not exercise it by reissuing current 70532.

### Controlled runs, statuses and failure diagnosis

Actions → **OTA production publication** on main: choose `publish`, `reconcile`, `resign`,
`verify`, `init-journal` or `signer-smoke`. Manual `execute` defaults false; reads/dry runs
never load production secrets or mutate journal. Explicit `execute=true` still requires the
literal flag. Optional CI id/attempt must be paired and match event inputs; optional release id
only narrows already-authorized committed requests. No free ref, endpoint, keyring or clock input.

The corresponding concrete CLI is:

```bash
python scripts/ota_release_automation.py preflight --operation reconcile --status-file out/preflight.json
python scripts/ota_release_automation.py reconcile --status-file out/status.json --execute
```

Outside the guarded workflow this is not authorization for a normal local writer. Every
journal-changing preflight and writer mode uses `ota-production-writer`, cancel false,
with job dependencies to serialize within a daily run. Daily reconciliation discovers complete
committed requests despite lost webhook/pending jobs; a separate independent resign path runs
even after BLOCKED candidates. Failed/expired artifacts stay BLOCKED. A signed public
catalog can lag private S3: a public-only base mismatch or already-listed release stays
retryable BLOCKED until convergence, without loading production secrets just to diagnose
that mismatch. Only authoritative private publication checks may classify live-dependent
work STALE. Current 70532 exclusion and a request removed from trusted main are independent
terminal proofs. Never clear an existing terminal receipt to retry after rollback.
Terminal receipts stay retained across a legitimate higher-sequence rollback and never reactivate.

| Strict status / exit | Meaning and next action |
| --- | --- |
| DISABLED / 0 | flag is not exactly true; no dependency/secret loading or mutation |
| DRY_RUN, READY / 0 | plan only; READY asks for a guarded writer, not publication proof |
| NO_ACTION, VERIFIED / 0 | no due work, or full applicable proof; inspect stage (smoke/init are not releases) |
| BLOCKED / 1 | refusal with safe stage/reason; correct request/config/evidence through review |
| PUBLISHED_NOT_VERIFIED / 3 | S3 target written but public expected payload/files not yet proven; retry verification only, no PUT/sequence bump |
| AMBIGUOUS / 3 | possible write unknown; freeze new writes and diagnose retained exact journal/live state |

Preflight refusal artifacts survive skipped writer jobs. Candidate and renewal statuses use
separate immutable V4 artifacts; renewal success never replaces candidate failure. If a writer
is interrupted before complete status, treat it as unknown/AMBIGUOUS, not 'nothing published' or success.
A cleanup failure after a durable successful PUT remains PUBLISHED_NOT_VERIFIED with
safe `CLEANUP` / `CLEANUP_FAILED`, never downgraded to BLOCKED; subsequent verification
loads no writer credentials and performs no PUT. The next daily run diagnoses retained
WRITE_STARTED/AMBIGUOUS exact work without fetching a
historical package. PUBLISHED_NOT_VERIFIED retries expected full public proof independently
of removed requests/expired artifacts. Telegram re-verifies public bytes; it cannot promote a
status artifact to proof or change receipts. First baseline and same-active resign stay quiet.

After durable WRITE_STARTED and the final full assessment, the publisher verifies references
and appended releases, then fetches main again and requires the same SHA that bound the exact
request, approval and provenance. A moved snapshot refuses before catalog PUT and records
BLOCKED, rather than claiming a lost/ambiguous write. The remaining main fetch → short live
sequence read → S3 PUT spans two services and is not an atomic transaction. Approval/request
removal alone cannot cancel an already in-flight PUT in that minimal window; use the stop/drain
and credential-revocation procedure below when immediate cessation is required.

### Break-glass recovery and rollback

1. Set enable flag false, disable workflow triggers, cancel queued jobs and stop/drain active
   writers. A flag change alone cannot stop credentials already loaded in an in-flight process.
2. Revoke CI S3 write credentials; verify no job/local process can still write. Preserve the
   durable `ota-publication-state` branch, exact prepared bytes/signature and terminal receipts.
3. Diagnose the exact signed live S3/catalog and public payload against retained journal work.
   Determine target/base/other/unreadable before any write. Do not blindly retry, reset journal,
   auto-rollback, delete receipts, manufacture approvals or re-sign around ambiguity.
4. Only after isolation, grant temporary owner recovery credentials. Use the existing manual
   helper/rollback/resign procedures (§1–§5 and production-deployment §9) with fresh live proof.
   A real rollback is a new higher sequence retaining every listed release, never replaying an
   old envelope. Retain terminal release receipts so recovery cannot re-execute old requests.
5. Verify every public manifest/file and stable catalog, revoke temporary owner write, restore
   dedicated CI credentials, inspect journal/config and re-enable only when the single-writer
   condition holds. Compromised primary/main/runner additionally requires custody response;
   backup stays offline and revoking a pinned key on installed apps requires an app update.
### Approval-to-public-proof timing evidence

For scoped, nonterminal release commands, `ota_release_automation.py` accepts
`--timings-file <path>` together with `--release-id <releaseId>`. The optional
`delivery-timings.v1` artifact binds the frozen request, approval epoch, main and
exact trigger/request CI pins to phase durations and bounded object-I/O counters.
It is diagnostic data, never publication authorization or a substitute for public proof.

The 600-second target starts at GitHub's server `merged_at` for the first valid
approval in the current authorization epoch. Retries and code-only fixes keep that
start; revocation ends the epoch. If server-time evidence is unavailable, delivery
may still verify, while latency is `UNKNOWN`. `PASS` requires the expected payload,
all retained public files, a stable signed catalog and a confirmed terminal receipt.
An elapsed time above 600 seconds is `MISS`; it never cancels or retries catalog PUT.

Aggregate health/renewal and already-terminal idempotent commands have no new
per-release timing artifact. The normal delivery routing supplies a release scope.
Optional report-writing/upload failures do not erase a durable publication outcome.
I/O counters identify incomplete measurement explicitly rather than inventing bytes
or SDK retry counts. Unknown external error text, credentials, keys and signed URLs
are excluded from these artifacts. The CI token needs read-only pull-request metadata
access to resolve the server approval time; it gains no PR write permission.

### Event delivery and lightweight watchdog

A completed, independently verified current-main CI triggers the read-only probe.
The `3-58/5 * * * *` watchdog runs the same metadata selection; empty work and
pending CI/approval skip package downloads, deep preflight, writer and notification.
GitHub can delay scheduled starts, so five minutes is a polling interval, not a
promise about delivery time. The separate `41 3 * * *` run retains independent
renewal/global health even if a candidate fails.

Probe artifacts contain data only. The trusted router validates the closed schema,
passes release IDs as argv (no eval), checks the journal again under the existing
`ota-production-writer` job lock and executes candidates sequentially. Each target
keeps its own status/timing files. New publication precedes older CDN verification;
unknown writes force a single recovery turn and prohibit subsequent fresh writes.
Every publication still repeats exact invocation/CI, committed approval/request,
provenance, G1–G8, signing, journal and reference barriers inside the guarded CLI.

Automatic pre-write failures with unchanged request/approval/code/artifact identity
wait at least 300 seconds and stop after three heavy failures in 24 hours. The probe
reconstructs that budget from exact API attempts and bounded, independently hashed
status/timing archives. Missing/expired/corrupt evidence blocks automatic retries.
Input changes allow re-evaluation but never reset the original approval clock.
Manual owner-controlled modes retain their explicit execute flag and default dry run.

### Delivery read benchmark and next-write cache policy

The next legitimate catalog publication/renewal uses `public, max-age=60`;
immutable blobs keep their one-year cache policy. The already-served catalog may
keep its old 300-second header until that normal write; this change does not reissue it.

Run the standalone read-only benchmark with the project environment:

```bash
PYTHONPATH=src python scripts/benchmark_ota_delivery.py --mode fixture --read-workers 1 --latency-ms 1 --output /tmp/ota-serial.json
PYTHONPATH=src python scripts/benchmark_ota_delivery.py --mode fixture --read-workers 8 --latency-ms 1 --output /tmp/ota-parallel.json
PYTHONPATH=src python scripts/benchmark_ota_delivery.py --mode public --read-workers 8 --output /tmp/ota-public.json
```

Measured locally during implementation (fixed TEST fixture clock, 1 ms simulated
private GET latency; public runs anonymous, sequential run before parallel run):

| Measurement | Serial | Bounded parallel |
| --- | ---: | ---: |
| Private GETs, three reference barriers | 6,003 | 2,772 |
| Private reference time, TEST graph | 9.178 s | 0.651 s |
| Peak private reads in flight | 1 | 8 |
| Public GETs, all retained releases | 926 | 926 |
| Anonymous public full proof time | 147.106 s | 33.920 s |

The TEST graph has five valid manifests, 1,996 file references and 919 unique file
hashes; both variants also pass the unchanged complete semantic public verifier.
Both public measurements proved the same stable signed catalog payload
`sha256:069b0b3d53f25ff958efe0736c6ed89a075cd59378b52d3921e1a56865085e21`.
No private production access, signer or PUT was used. Network/CDN conditions affect
elapsed times and the second public run can benefit from warming. These are read
measurements, not a GitHub approval-to-delivery test: the JSON always records
`endToEndSloProven=false`. The 600-second target must still be measured on the next
independently approved new frozen release through its actual final public proof.

### Recovery details and workflow validation

Successful producer jobs export their immutable artifact IDs. A failed-consumer-only
rerun downloads those retained IDs, rather than constructing a new attempt's name;
the pinned download action explicitly flattens ID downloads. CLI authorization still
rechecks the current main, CI, approval, artifact metadata and live state.

Each routed batch binds an atomic `execution.json` progress file to the exact input
plan. The target is marked started before invocation. An untouched target is not a
failed retry; a started target without its outcome remains unknown. Partial preflight
failures retain their own statuses and an independent failure-audit job, while other
ready targets can publish. Thus a successful workflow cannot hide a failed target.

The four protected jobs retain one running writer and use GitHub's `queue: max`
policy (up to 100 pending jobs), preserving queued daily renewal when a watchdog
arrives. This is documented [GitHub concurrency behavior](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency).
History reads are shared across candidates within one probe and capped at 48
worst-case API requests, including pagination/download costs. Overflow or incomplete
history blocks automatic retries; it never silently resets their count. Successful
runs of this audited code SHA can be skipped using their exact repository/run metadata.

The installed actionlint 1.7.12 has the known [upstream queue-schema gap](https://github.com/rhysd/actionlint/issues/657).
Until upstream supports this documented field, validate the four literal `max` values
and `cancel-in-progress: false` via the workflow regression tests, then run actionlint
with only that exact obsolete-schema diagnostic excluded; every other diagnostic stays fatal:

```bash
actionlint -ignore '^unexpected key "queue" for "concurrency" section[.] expected one of "cancel-in-progress", "group"$' .github/workflows/ci.yml .github/workflows/ota-production-publish.yml
```
