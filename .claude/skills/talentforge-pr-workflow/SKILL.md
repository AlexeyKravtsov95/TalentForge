---
name: talentforge-pr-workflow
description: Use when preparing, implementing, validating, reviewing, pushing, or handing off a TalentForge change intended for GitHub. Enforces milestone-scoped branches, small logical commits, required validation, precise PR descriptions, explicit limitations, no unrelated work, and a hard stop before merge unless the user explicitly orders a merge.
---

# TalentForge PR Workflow

Use this workflow for repository changes delivered through GitHub.

## 1. Start from current main

```bash
git checkout main
git pull
git status
```

Confirm the working tree is understood before creating a branch.

Do not build a milestone on an unreviewed local branch unless the task explicitly says so.

## 2. Use a milestone-scoped branch

Examples:

```text
feat/m3b-kotlin-allocation-engine
fix/mobile-contract-wire-types
docs/live-client-evidence
```

Do not work directly on `main`.

Keep one PR focused on one milestone or one correction set.

## 3. Read before editing

Inspect relevant previous-milestone files, schemas/docs, tests, CI, and source-of-truth artifacts.

Do not implement from memory when repository evidence exists.

If the prompt conflicts with current repository state, prefer the current repository and report the discrepancy.

## 4. Keep the diff focused

Do not mix unrelated cleanup, dependency upgrades, formatting churn, future milestone work, speculative refactors, or unrelated design changes.

Out-of-scope improvements belong in follow-up notes.

## 5. Commit logically

Prefer a small sequence of coherent commits.

Typical prefixes:

- `build:`
- `feat:`
- `fix:`
- `test:`
- `docs:`
- `ci:`
- `data:`

Do not commit local build outputs, IDE state, credentials, or irrelevant generated artifacts.

## 6. Validation before push

Python/data baseline:

```bash
ruff check .
ruff format --check .
mypy
pytest
```

Dataset/mobile bundle:

```bash
python3 scripts/sync_mobile_datasets.py --check
```

KMP shared:

```bash
cd mobile
./gradlew :shared:jvmTest
```

Run Kotlin/Native/iOS tests or compilation when shared changes can affect iOS.

Compose/mobile app when relevant:

```bash
./gradlew :composeApp:desktopTest
./gradlew :composeApp:assembleDebug
```

For iOS-facing changes, run the framework link and simulator Xcode build documented in `docs/mobile-architecture.md`.

For docs-only changes, run the smallest relevant verification and state what was not run.

Never report a check as passing unless it actually passed.

## 7. Push and open the PR

Push the branch and open a PR to `main`.

Do not merge it.

Use PR sections appropriate to the task:

### Summary
What changed and why.

### Scope
What is included and excluded.

### Architecture / behaviour
Important decisions and boundaries.

### Data/contracts
Schema/model/dataset/manifest/conformance effects.

### Tests
Exact commands and exact results.

### Manual verification
Devices/simulators/states actually checked for UI/runtime work.

### Known limitations
Intentional unresolved items.

### Next milestone
Recommendation only. Do not implement it in this PR.

## 8. UI PR evidence

For production UI changes include enough evidence for review: relevant portrait screenshots/previews, landscape/adaptive state when touched, important locked/error/empty states, and accessibility notes when affected.

Do not use screenshots as a substitute for domain tests.

## 9. Contract PR evidence

For contract/semantics changes identify source of truth, compatibility impact, affected fixtures, affected conformance scenarios, confidence/evidence changes, regenerated data, and drift checks.

Never hide semantic changes inside serializer/refactor wording.

## 10. Stop condition

After opening the PR:

1. report branch name;
2. report commit SHA(s);
3. provide PR URL;
4. summarize validation;
5. summarize known limitations;
6. stop.

Do not merge the PR, start the next milestone, continue unrelated cleanup, rewrite accepted design, or declare unresolved semantics proven.

Only merge when the user explicitly instructs you to merge.
