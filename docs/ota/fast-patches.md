# Fast data-only patches — M8.OTA.FAST.1

A data patch changes canonical Forever datasets, sidecars and icons. It does not change
`PRODUCT_BUNDLE`, Android versionCode, iOS build number or an installed application binary.
`PRODUCT_BUNDLE` is the offline installation baseline; signed Production OTA is the delivery
baseline, including when it is older than the bundle. Detection compares against active OTA
independently of the bundle. The first publication still uses the bundle as its diff baseline.

## Short path

1. The existing freshness/OTA preparation workflow discovers a publishable Wago build and
   prepares a candidate data PR, with complete nine-class manifests, diffs and checks. That
   data PR still needs normal code/data review; this policy never merges a PR. The current
   owner-merge path remains available for candidates already carrying its exact binding.
2. For data reviewed onto `main` without a release request, successful main CI triggers
   **OTA release preparation**. It reuses the existing exact staging/client verification and
   immutable release package. `propose-request --auto-data-only` evaluates the committed
   [standing policy](data-only-policy.json) and G1–G8. It freezes the exact request, source
   commit, package/staging attempts, artifact digest, manifest and policy hash in an annotated
   `ota-data-only/<releaseId>` tag. It never writes `main`, opens a release-approval PR, merges
   anything, signs a catalog or uploads production data.
3. The existing production probe discovers those tags (at most a five-minute scheduled
   interval, subject to GitHub queue delays). Its protected writer independently re-fetches
   the immutable tag, proves successful CI at the exact data source on `main`, checks the
   current policy, package provenance, staged production base and all G1–G8 gates. The existing
   signer produces an ECDSA P-256 signature, blobs undergo SHA-256 readback, and the catalog
   changes only through the existing durable journal and final authorization barrier.
   Public readback verifies the signed catalog and active content.

No extra per-release owner approval, offline signature ceremony or store build is required
for that permitted path. Existing one-time production automation enablement, signer secrets,
App scope and main-only environment bindings remain required. Both current `ota-preparation`
and `ota-production` environments have branch policies and no required-reviewer rule (read-only
inspection on 2026-11-18). Main branch protections are preserved; authorization uses tags,
not a direct push around required PR/CI protections.

This is a deliberately narrow standing authorization: the game/branch/data level are fixed;
the source commit may change only the candidate's canonical data and allowlisted reports; all nine classes retain their allocation shape (tree/node/entry/spell identities,
rank limits, costs, conditions, prerequisites, flags, layout and budgets). Effect values and
presentation may change. G5 warning growth and every STOP block automatic publication.
G7 acceptance is explicitly a standing policy decision, **not evidence that live realms run
the build**; it does not upgrade allocation hypotheses or invent client observations.
Missing images, unknown mechanics, code/config changes, a changed policy or production base,
invalid signatures/hashes and expired/unverifiable artifacts block the path with a reason.
The legacy manual publisher cannot use a policy receipt as an unverified manual override.

To revoke future automatic publications, set policy `enabled` to `false` through normal review.
Existing in-flight writers recheck the current policy before activation. Do not move/delete
request tags to retry: exact requests and ambiguous writes use the existing durable recovery.
Changed policy bytes invalidate their old authorization; reassessment needs a new reviewed
request, never mutation of an immutable tag.

## Incremental preparation and network recovery

`visuals fetch --build B --reuse-from A` resolves every target FileDataID from **B's** tables.
It obtains Wago's complete `/builds-diff?from=A&to=B` CASC diff, validates build identities,
filters, pagination, unique IDs, action counts and total inventory. An absent/truncated/error
page is never evidence of equality. Every reused baseline PNG passes the baseline contract,
SHA-256, byte-length and dimension checks. Only baseline IDs absent from the complete changed
set may be reused. Modified IDs (even with the same FileDataID) and new IDs require an exact
B-pinned CASC source. No stale-image fallback exists.

`artifacts/visuals/<game>-<build>-icon-reuse.json` records baseline registry SHA-256, source page
URLs/hashes, changed IDs, every reused PNG/source hash, and required fetches. The target registry's
existing provenance fields pin that evidence without changing public schemas. The target
release manifest pins the PNG bytes and registry as usual.

The orchestrator negotiates `--reuse-from` support with both visuals commands in the selected
checkout. Historical tooling without both options uses the complete download path with the
same integrity checks. Broken capability inspection blocks preparation; it does not silently
fall back after a failed pipeline. The two CLI help logs are retained in preparation evidence.

Successful source files and validated diff pages are resource-level checkpoints. Downloads
remain atomic; interrupted `.part` files never become cache hits. A rerun fetches only missing
or invalid resources. `TALENT_DATA_CACHE_ROOT` lets the disposable preparation workspace reuse
its checkout's `.cache`; Actions saves `.cache/wago` even after a failed preparation. The cache
is an optimization, not authority: hashes, URL/build identity and diff completeness are checked
again after restore. This resumes completed resources, not unchecked HTTP byte ranges.

Transport failures retain bounded HTTP retries and `UNKNOWN` diagnostics. Definitively missing
resources and structural failures are `BLOCKED`: the same preparation inputs are not scheduled
again indefinitely. A changed input fingerprint or explicit `force` retries after the cause is
resolved. A kept BLOCKED artifact is never routed into the PREPARED proposal path.

## Store delivery and archive monitoring

All store preflights explicitly use `--release-scope active --expect published`, the committed
production keyring and the existing 30-day validity requirement. DNS, TLS, catalog signature,
expiry, contract headers, active manifest and every active file's integrity remain mandatory.
The default scope remains `all` for existing callers. `--release-scope all` verifies every
historical release in the independent daily **OTA archive health** workflow and retains a JSON
report for 30 days. A failure there does not gate the APK/IPA workflow.

HTTP reads with TLS EOF/transport failures retry at most three times; a failing initial TLS
handshake still fails the mandatory TLS gate. Exhausted transport errors are reported
as transport errors before parsing/hashing an empty response; they are not reported as corrupt
blob evidence. A successful body with a wrong hash still fails immediately.

Rollback retains the existing protocol: select a retained verified release, issue a higher
catalog sequence and sign it through the protected writer. Never restore old catalog bytes
or reduce the sequence. See [publisher/security](publisher-and-security.md) and
[production deployment/recovery](production-deployment.md). Request-tag authorization does
not change the catalog, client rollback floor, signature protocol or blob retention.

## 70959 after infrastructure merge

These commands are a publication runbook, **not authorization to merge or publish**. Obtain
the owner's separate production authorization before merging the data PR: successful main CI
starts the standing-policy automation. No additional release-approval PR is needed.

1. Merge the reviewed infrastructure PR #155 only when the owner authorizes it. Fetch main,
   move the prepared data branch onto that merged infrastructure, and review the diff:

   ```bash
   export PYTHONPATH=src
   git fetch origin main
   git switch codex/ota-70959-data
   git rebase --onto origin/main codex/m8-ota-fast-1
   git diff --stat origin/main...HEAD
   python - <<'PY'
   import subprocess
   from talent_data.prepare.orchestrate import candidate_path_allowed
   files = subprocess.check_output(['git', 'diff', '--name-only', 'origin/main...HEAD']).decode().splitlines()
   assert files and all(candidate_path_allowed(p, game='forever', build='1.60.1.70959', base='1.60.1.70938') for p in files)
   PY
   ```

2. Regenerate the manifest from that data-only commit, outside the commit itself (committing
   a manifest that names its own source SHA would create a self-reference). Verify its source
   pin and reproducibility; the fixed date below is for the review preview only:

   ```bash
   export PYTHONPATH=src
   data_source=$(git rev-parse HEAD)
   python -m talent_data ota build-release --game forever --build 1.60.1.70959 \
     --revision 1 --commit "$data_source" --created-at 2026-11-18T00:00:00Z \
     --out .cache/ota-review
   python scripts/ota_patch_diff.py --from-build 1.60.1.70938 --to-build 1.60.1.70959 \
     --out .cache/ota-review/70959-presentation.json
   ```

   The manifest is `.cache/ota-review/1.60.1.70959-r1/release.json`.
   Run Python CI and the dataset/sidecar checks, push the data branch, then open the separate
   data-only PR against `main`. No `src/`, workflow, policy, graph or `docs/ota/candidates/`
   changes belong in it. Rebuild the preview after every source-changing rebase. Review the
   real diff, retained `$mws` gap and source/reuse evidence with the owner.

3. After the owner authorizes publication and merges **that** data-only PR, observe successful
   main/push CI, then **OTA release preparation**. It reserves the exact source, dispatches
   staging (including Kotlin client verification), packages the immutable release, checks the
   current base/policy and G1–G8, and writes `ota-data-only/1.60.1.70959-r1`. Manifest provenance
   is the actual main source SHA selected by that run, not `ecad66c` or a preview SHA. A failed
   preparation can be retried with its exact successful CI run/attempt:

   ```bash
   gh workflow run ota-release-prepare.yml --ref main \
     -f ci_run_id=ACTUAL_SUCCESSFUL_MAIN_CI_RUN_ID -f ci_attempt=ACTUAL_ATTEMPT
   ```

4. The scheduled **OTA production publish** probe selects the immutable request tag. The
   protected writer rechecks source CI, base, hashes, policy, staging/package provenance and
   gates, signs through the configured ECDSA signer, activates through the durable journal,
   and performs public readback. Retain both run reports: release ID, source SHA, manifest
   SHA-256, catalog sequence, signature verification and active-file readback. Rollback uses
   a retained verified release with a higher signed catalog sequence. Never mutate request
   tags, reduce a catalog sequence or bypass a failed gate to retry.
