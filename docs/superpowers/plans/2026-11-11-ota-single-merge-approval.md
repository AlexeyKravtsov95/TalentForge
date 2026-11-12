# One-merge OTA Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One generated data MR prepares the exact package before merge; owner merge is the sole authorization for automatic production publication.

**Architecture:** Keep data source D, trusted producer tooling T, final PR head H and merge M separate. Add a strict candidate proof and conditional merge record; reuse existing package/journal/publisher machinery, with actual main G1 and verified owner authorization enforced at production barriers. Add a main CI validated-merge path only above exact successful final PR CI/tree equality.

**Tech Stack:** Existing Python 3.12/Pydantic/httpx/Git, GitHub Actions, existing Kotlin staging scenarios; no dependency or public OTA schema changes.

**Spec:** `docs/superpowers/specs/2026-11-11-ota-single-merge-approval-design.md` (approved in chat, 2026-11-11).

## Global Constraints

- ONE initial generated data MR; staging and frozen package BEFORE merge; no second release/approval MR for new-policy candidates.
- Policy `owner-merge-v1`; owner `AlexeyKravtsov95`, GitHub user ID 5190861; candidate App `talentforge-ota-alexeykravtsov95[bot]`, user ID 351368232.
- Ordinary merge only, exact same repository/head-repository `AlexeyKravtsov95/TalentForge`; fork/other actor/squash/rebase never authorize.
- Tooling is trusted main T, source is committed candidate D; never execute MR code with credentials. Production G1 always checks actual main ancestry.
- Public schemas/data semantics/keys/mobile code/dependencies untouched. Frozen 70888/70862/70755 requests/approvals/packages/tags/journals unchanged; no live dispatch/sign/publish/retry benchmark.
- G1–G4/G6/G8 STOP never overridable; G5 only already displayed frozen FLAG; G7 records owner merge acceptance, not fabricated live observation.
- Source/createdAt/manifest/archive/request identity frozen; no blind repeat PUT, terminal immutability, read-only UNKNOWN recovery, independent barriers preserved.
- New mode only explicit prospective policy marker; legacy remains supported. No bot merge or direct main push/protection bypass.
- NEVER traverse/read/index `ceremony/`; graphify query before source exploration; graphify update AST-only after code edits.
- Venv `/Users/alexeykravtsov/Desktop/workspace/TalentForge/.venv`; always `PYTHONPATH=src`. Dedicated temporary tests, preserve foreign files.
- Native execution as previously chosen; owner explicitly authorized implementation. No additional approval checkpoints for implementation details; final PR never merged by agent.

## Review Focus

- Cyclic pins: an archive cannot contain its own artifact/hash or final H; bind preparation head D inside, artifact ID/hash and final H outside.
- App fast-forward after package completion racing with owner/foreign edits must never overwrite/rebuild or silently authorize changed data.
- PR CI at synthetic merge SHA, missing job pages, retained producer artifacts after consumer retry: actual head/tree/run/attempt must be proven, not guessed.
- Conditional record accidentally accepted by legacy manual tooling or trusted without merged_by must remain unpublishable.
- Base/tooling advancement between staging, readiness and PUT must revalidate or refuse; no stale READY shortcut or new implicit G5 acceptance.

## File Map

New focused modules under `src/talent_data/ota/automation/`: `candidate.py` (candidate/binding), `merge_approval.py` (conditional record and owner merge), `premerge.py` (readiness/coordinator). Thin `scripts/ota_premerge.py` entry point.
Adapt existing `prepare/propose.py`, `prepare/staging_verify.py`, staging/package scripts and workflows; package/provenance helpers; authorization/probe/publisher/cli/timings; CI classifier and notifications/runbooks. No unrelated refactor.

---

### Task 1: Strict candidate and conditional authorization contracts

**Files:** Create `automation/candidate.py`, `automation/merge_approval.py`; tests `tests/unit/ota/test_merge_contracts.py`.
**Interfaces:** Produce strict `CandidateBinding` (`schemaVersion=1`, `kind=ota-candidate-binding`, policy, repository, pullNumber, game/build/revision, sourceSha/tree, preparationHeadSha, baseSha, toolingSha, preparationCi:RunPin, stagingRun:RunPin, packageRun:RunPin, candidateArtifact:ArtifactPin, baseReleaseId/baseManifestSha256). No archive-own hash or final H. Produce `OwnerMergeRecord` (schemaVersion1, policy, repository, pullNumber, releaseId, sourceSha, bindingSha256, requestDigest, acceptedFlags tuple G5/G7, revoked bool). Produce `parse_merge_record(text:str)->OwnerMergeRecord|None`, `render_merge_record(record)->str`, `binding_digest(binding)->str`.

- [ ] **Step 1:** Tests round-trip, strict unknown/wire types, positive IDs/full SHAs, forbidden accepted STOP, source!=preparationHead rejection, duplicated flags, revoked required bool. Legacy text -> None. Conditional Markdown uses one `owner-merge-v1` fenced JSON block and no fake Approved by/Live-build evidence fields.
- [ ] **Step 2:** `PYTHONPATH=src VENV/bin/pytest tests/unit/ota/test_merge_contracts.py -q`; expected RED missing modules/interfaces.
- [ ] **Step 3:** Implement models with existing strict aliases/parser; canonical digest via canonical JSON + sha256_ref. No self-referential package pins.
- [ ] **Step 4:** Same tests GREEN; Ruff/mypy touched modules; AST update.
- [ ] **Step 5:** Commit `feat(ota): define strict candidate and merge approval contracts`.

### Task 2: Prove a generated candidate and exact full PR CI

**Files:** `candidate.py`; create `tests/unit/ota/single_merge_testkit.py`, `test_candidate_authorization.py`.
**Interfaces:** `VerifiedCandidate` frozen value (binding context plus source/base/pull metadata), `verify_candidate(api:GitHub,repo:Path,pull_number:int,*,source_sha:str,ci_pin:RunPin|None=None)->VerifiedCandidate`; `verify_full_pr_ci(api,pull_number,head_sha,pin=None)->RunPin`.

- [ ] **Step 1:** Real minimal Git + fake GitHub fixtures: bot data-only D parent B accepted; fork/wrong actor/multiple PR/head changed/forged preparation artifact/changed path-mode/delete/rename/unknown CI ancestry fail. Test sourceD != toolingT, synthetic PR merge SHA handling using exact PR head/tree binding, truncated jobs and incorrect run attempt fail.
- [ ] **Step 2:** Run `test_candidate_authorization.py`, expected RED.
- [ ] **Step 3:** Reuse existing candidate artifact/report/bundle/path validation and GitHub bounded pages. Independently hash/extract artifact before source trust. Require app identity, branch/policy marker, allowed regular data paths, base binding and successful `full-validation`/`checks`. Never trust MR body alone.
- [ ] **Step 4:** Tests GREEN and existing proposal regressions; AST update.
- [ ] **Step 5:** Commit `feat(ota): verify generated candidate origin and PR validation`.

### Task 3: Preparation-only source context in staging/package

**Files:** `prepare/staging_verify.py`, `scripts/ota_staging_verify.py`, `scripts/ota_release_package.py`, staging/package workflows; `test_candidate_preparation.py`.
**Interfaces:** `resolve_subject(...,candidate_context:VerifiedCandidate|None=None)->Subject` retains default main-only. `Subject` exposes trusted preparation ref; `ci_gates(...,main_ref:str='origin/main')` uses that explicit context. Candidate CLI inputs are PR number/full source SHA/exact preparation CI, verified by API before ref creation; no unchecked generic allow-non-main switch.

- [ ] **Step 1:** Tests normal source-not-main still fails; valid generated D can stage/package with trusted tooling T without checkout D; wrong source/actor/code diff/expired evidence fail. G1 detail names verified candidate ref, never false main PASS; production default still STOP until D on actual main.
- [ ] **Step 2:** Run candidate tests RED; existing staging/package baseline tests preserved.
- [ ] **Step 3:** Add candidate context and explicit local verified ref only after Task2 proof; thread it through committed-data readers and G1. Workflows dispatch on main, no production environment/secrets, validate source via trusted main code.
- [ ] **Step 4:** New+existing source/staging/package tests GREEN, actionlint, AST.
- [ ] **Step 5:** Commit `feat(ota): prepare verified candidate packages before merge`.

### Task 4: Bind candidate proof inside exact package and provenance

**Files:** `release_package.py`, package script, `automation/provenance.py`, `prepare.py`; `test_candidate_package.py`.
**Interfaces:** `CANDIDATE_BINDING_NAME='report/candidate-binding.json'`; `candidate_binding(package:LoadedPackage)->CandidateBinding|None`; candidate-aware origin validation consumes exact indexed attachment + request external artifact pin. PackageIndex stays v1; its existing files map hashes new attachment. Final H and own archive ID/hash remain external.

- [ ] **Step 1:** Tests package indexed proof round-trip; sourceD/toolingT distinct valid; wrong/absent/extra/duplicate-key binding, archive substitution, changed staging source, same input but alternate package run rejected. Legacy package round-trip unchanged; supplied package vs archive contents still independently compared.
- [ ] **Step 2:** Run `test_candidate_package.py` RED.
- [ ] **Step 3:** Add attachment using existing indexed files machinery; new explicit provenance branch validates T API run identities and D artifact/source proof; legacy branch unchanged. Freeze correlation/reservation before dispatch; re-read metadata and refuse expired artifacts even cached.
- [ ] **Step 4:** New+legacy package/provenance tests GREEN, AST.
- [ ] **Step 5:** Commit `feat(ota): bind premerge candidate packages to trusted producers`.

### Task 5: One draft MR and idempotent metadata append/readiness

**Files:** `prepare/propose.py`, `automation/premerge.py`, new `scripts/ota_premerge.py`; `test_premerge_proposal.py`.
**Interfaces:** `bind_candidate_request(reader:GitHub,writer:GitHub,repo:Path,candidate:VerifiedCandidate,request:ReleaseRequest,binding:CandidateBinding)->str` returns final metadata H; `mark_candidate_ready(reader,writer,repo,pull_number,*,ci_pin:RunPin)->None`.

- [ ] **Step 1:** Tests initial policy MR draft + clear merge notice; metadata fast-forward P only expected request/record paths on D, no data mutation; repeat exact H no write; foreign/racing head refuses with zero ref update. Final successful full CI H + proof -> ready once; failed/pending/mismatched CI and manual undraft do not authorize. Request digest is canonical exact committed bytes; no circular H pin.
- [ ] **Step 2:** New tests RED; existing legacy proposal fixture unchanged unless explicitly new policy enabled.
- [ ] **Step 3:** New-policy generated MR includes marker and candidate artifact identity. App writes only its branch through expected-head/CAS semantics and re-reads after write; never main or merge endpoint. Keep legacy handling for existing proposals; no new legacy-mode candidate generation after rollout.
- [ ] **Step 4:** Tests GREEN; source/metadata allowed paths checked independently; AST.
- [ ] **Step 5:** Commit `feat(ota): keep candidate preparation in one reviewable MR`.

### Task 6: Workflow orchestration for premerge prepare then ready

**Files:** `premerge.py`, `ota-release-prepare.yml`, `ota-prepare.yml`, CLI entry; `test_premerge_workflows.py`.
**Interfaces:** `prepare_candidate(client,*,reader:GitHub,repo:Path,ci_pin:RunPin,destination:Path,now:datetime)->PreparedProposal|None`; CLI phases return strict data-only `prepare|bind|ready|wait|blocked` results; no artifact-provided executable argv.

- [ ] **Step 1:** Real-shell/workflow tests initial PR full CI D -> trusted staging/package dispatch -> bind; subsequent H CI -> validate+ready without second staging/package/tag/PR. Workflow_run from fork/wrong path/event/attempt denied. Consumer-only retries use producer artifact IDs. Main legacy request still works but new-policy main never opens second release MR.
- [ ] **Step 2:** Tests RED.
- [ ] **Step 3:** Add trusted workflow_run route for policy candidate PRs; credentials separated (read/package dispatch vs App branch metadata). Reuse existing completed_run/reservation/frozen tag and artifacts; retain successful producer IDs. Mark ready in separate post-CI phase to avoid CI depending on itself.
- [ ] **Step 4:** New/old workflow tests GREEN, actionlint (existing exact queue diagnostic exclusion only), AST.
- [ ] **Step 5:** Commit `feat(ota): orchestrate premerge package readiness without a release MR`.

### Task 7: Verify owner merge at production barriers and enforce revocation

**Files:** `merge_approval.py`, `release_package.py`, `gates.py`, `automation/authorization.py`, `publisher.py`, `cli.py`, `probe.py`; `test_owner_merge_authorization.py`.
**Interfaces:** `VerifiedOwnerMerge` frozen value (record, pullNumber, finalHeadSha/tree, mergeSha, baseSha, owner login/id, mergedAt, PR CI pin); `verify_owner_merge(api:GitHub,repo:Path,request:ReleaseRequest,record:OwnerMergeRecord,*,binding:CandidateBinding)->VerifiedOwnerMerge`. Required `record.revoked` is committed per-release revocation. `as_gate_approval(verified)->ApprovalRecord` yields only displayed accepted flags and audit evidence, never claimed play observation.

- [ ] **Step 1:** Owner exact ordinary merge M with parents B,H, equal H tree and D ancestry succeeds. Other merger/closed-unmerged/squash/rebase/old-policy/source/base/tree/bytes/flag mismatch and revoked/missing record fail. Conditional record rejected by legacy parser/manual path. Withdrawal between preflight/secret/PUT produces zero unauthorized writes; old terminal and UNKNOWN recovery unchanged.
- [ ] **Step 2:** Tests RED.
- [ ] **Step 3:** Dispatch approval parsing by explicit record kind; CI structural validation is not authorization. Production builds ephemeral gate record only after fresh API/main proof; revalidates before secrets and PUT using provenance callback. Enforce production main_ref; no premerge G1 proof shortcut. Do not let old helper interpret conditional G7 as ordinary manual evidence.
- [ ] **Step 4:** New+withdrawal/legacy e2e tests GREEN, AST.
- [ ] **Step 5:** Commit `feat(ota): authorize publication from the owner's exact candidate merge`.

### Task 8: Reuse final full PR validation in main CI safely

**Files:** `ci_validation.py`, `.github/workflows/ci.yml`, `authorization.py`; `test_validated_merge_ci.py`, existing CI/workflow tests.
**Interfaces:** `verify_validated_merge(api,repo,main_sha)->VerifiedOwnerMerge|None`; CiDecision adds explicit `validated-merge` mode and exact proof pins, preserving full/metadata. `verify_main_ci_evidence` checks new-mode proof chain when applicable; no acceptance of PR CI as a main/push run.

- [ ] **Step 1:** Main M with B,H exact final successful PR CI/tree/candidate binding selects validated-merge; code/dependency/workflow edits, synthetic CI mismatch, partial history, moving base, failed/pending attempt, metadata baseline substituted for full -> full/refusal. Stable checks accepts exactly one successful validation branch; other two skipped. Publisher rechecks reuse proof.
- [ ] **Step 2:** New tests RED plus real-shell selected-branch checks.
- [ ] **Step 3:** Add separate merge-validation job consuming exact retained classify artifact ID, always stable checks aggregation; short path creates its own main success after API proof. Keep PR always full and legacy metadata path. Narrow eligibility to new policy, not every owner code merge.
- [ ] **Step 4:** New+existing CI/workflow tests GREEN, actionlint, AST.
- [ ] **Step 5:** Commit `feat(ci): validate exact approved data merges without redundant full CI`.

### Task 9: Timings, readiness messages and runbooks

**Files:** `timings.py`, `notify/automation.py`, proposal/CI reporting, `docs/ota/{auto-prepare,publisher-runbook}.md`; `test_owner_merge_timings.py`, notification tests.
**Interfaces:** Merge approval epoch derives server M merged_at; readiness timestamps are server MR created/full-CI/staging/package/ready times, never fabricated. Add strict optional/new-version internal timeline representation if existing model cannot carry premerge fields; old records remain parseable. All visible user actions use plain release terms.

- [ ] **Step 1:** Tests D->READY/READY->merge/merge->verified separate, code-fix/retry preserves t0, no premerge approval epoch, MISS still delivers, no fake personal evidence. Ready/completed/actionable-error notify once; healthy/pending quiet; old release MR notifications absent for new mode.
- [ ] **Step 2:** Tests RED.
- [ ] **Step 3:** Integrate verified epoch with existing timing counters; report exact 600 s PASS/MISS; document only normal Merge and no second PR/G7 action. Profile recorded 70888 timing; preserve independent barriers, no unmeasured performance bypass. Next-release SLO remains unproven until real ordinary release.
- [ ] **Step 4:** Timing/notification regressions GREEN; AST.
- [ ] **Step 5:** Commit `feat(ota): report one-merge readiness and approval-to-proof timing`.

### Task 10: Full one-MR integration, final review and reviewable PR

**Files:** `tests/unit/ota/test_single_merge_e2e.py`, shared test kit, final workflow/docs/graph outputs.
**Interfaces:** All previous tasks, with distinct D/T/H/M and real TEST publisher/public proof.

- [ ] **Step 1:** Integration asserts one MR only, draft->ready after frozen package/full final CI, allowed merge, own main validated-merge CI, target VERIFIED/all files/one catalog PUT; request/manifest bytes fixed. Negative base/tooling movement and revoked-before-PUT/UNKNOWN recovery plus mixed-batch scoped statuses. First integration may already pass; report honestly, no invented RED.
- [ ] **Step 2:** Targeted integration/regressions GREEN; run full Ruff check/format, mypy, pytest once on final source tree, actionlint and diff. Dedicated test scratch to avoid retained multi-GB interrupted directories; never delete others' files.
- [ ] **Step 3:** AST graph update; frozen/data/mobile/key/schema/dependency diff scope check. One fresh whole-branch reviewer per Native skill. One TDD Important/Critical fix pass and green full suite; no re-review; minors/rulings recorded.
- [ ] **Step 4:** Push isolated codex branch, create reviewable PR, verify exact required PR CI; update body/evidence. Never merge without explicit owner instruction. No production replay or new monitor for completed 70888.
- [ ] **Step 5:** Report actual implemented behavior, tests, remaining rollout/SLO limits; export ledger rulings and delete only this plan's scratch workspace after completion.

## Self-review / coverage

One connected release pipeline, no independent product milestone. Candidate source1-3, package freeze4, one MR5-6, owner authorization7, exact CI reuse8, timings/UX9, final integration10. Review Focus: cyclic pins1/4/5, CAS races5/6, CI/artifact identity2/6/8, legacy conditional rejection7, changing base2/7/8/10. No new public schema or production write is needed to test the plan.
