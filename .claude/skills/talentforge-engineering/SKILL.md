---
name: talentforge-engineering
description: Use for implementation, refactoring, architecture, dependency, build-system, testing, or documentation changes in the TalentForge repository. Enforces Python/KMP module boundaries, offline-first scope, minimal dependencies, milestone discipline, and reproducible validation. Do not use as a substitute for the contract-safety or UI skill when those domains are the primary task.
---

# TalentForge Engineering

Apply this workflow to engineering work in the TalentForge repository.

## 1. Establish the task boundary

Before editing:

1. identify the active milestone and requested deliverable;
2. inspect the current branch and latest relevant repository files;
3. read the authoritative docs for the touched subsystem;
4. distinguish required work from future work.

Do not implement the next milestone early.

If a requested change conflicts with an authoritative contract or design source, stop and report the conflict instead of silently resolving it.

## 2. Preserve module ownership

### Root Python project

Owns external DB2/source acquisition, normalization, validation, dataset manifests/diffs, Python reference allocation semantics, and conformance generation/replay.

Do not add a dependency from Python into `mobile/`.

### `mobile/shared`

Must remain a pure Kotlin Multiplatform library.

Allowed responsibilities include domain models, JSON contract parsing, typed identity/value objects, repository interfaces/data integrity, and allocation-domain logic when explicitly in scope.

Forbidden here: Compose UI, Android `Context`/`AssetManager`, platform-specific product logic, and screen/navigation state.

### `mobile/composeApp`

Owns Compose Multiplatform UI, packaged resources, design-system implementation, screen/navigation code, and cross-platform UI adapters.

It may depend on `shared`; `shared` must not depend on it.

### `mobile/iosApp`

Keep as a thin host. Do not duplicate shared domain/business behaviour in Swift.

## 3. Keep the dependency surface small

Do not add frameworks because they might be useful later.

Before adding a dependency, answer:

- What current requirement needs it?
- Why can the standard library/current stack not satisfy it?
- Does it leak into `shared`?
- What testing/maintenance cost does it create?

Do not add networking, persistence, DI, analytics, or navigation frameworks unless the current milestone explicitly requires them.

Prefer stable dependency versions already centralized in `mobile/gradle/libs.versions.toml`.

## 4. Offline-first means offline-first

Runtime correctness must not require a backend or internet connection unless a later milestone explicitly changes the architecture.

Canonical datasets remain repository artifacts. A mobile runtime copy must be deterministically derived, never manually maintained.

Do not build hidden online fallback behaviour around missing or invalid local data.

## 5. Make abstractions earn their existence

Create an abstraction only when it has a current consumer, isolates a real boundary, or is required by the milestone.

Avoid empty interfaces for hypothetical future providers, generic manager/service layers with no domain meaning, speculative caching/persistence, duplicate model hierarchies, and wrappers that only rename an existing API.

Prefer small typed domain boundaries.

## 6. Failure behaviour

Use explicit typed failures for recoverable domain/data conditions.

Do not use `null` as a generic failure result, broad `Exception("failed")` as domain API, message-string parsing for program logic, or silent fallback to guessed data.

Preserve coroutine cancellation when translating errors.

## 7. Testing strategy

Test externally meaningful behaviour and boundaries.

Good tests include parsing real committed contracts, repository identity/integrity failures, deterministic resource drift checks, allocation conformance when active, and UI state transitions/semantics when production UI exists.

Avoid tests that mirror implementation line by line, `assertTrue(true)`, fake acceptance tests that echo expected fixtures, and ignored tests standing in for milestone requirements.

Use real committed fixtures where practical.

## 8. Validation matrix

Python/data changes:

```bash
ruff check .
ruff format --check .
mypy
pytest
```

Dataset/mobile-resource changes:

```bash
python3 scripts/sync_mobile_datasets.py --check
```

KMP shared changes from `mobile/`:

```bash
./gradlew :shared:jvmTest
```

Run Kotlin/Native compilation/tests when common/shared code can affect iOS.

Compose/resource changes:

```bash
./gradlew :composeApp:desktopTest
./gradlew :composeApp:assembleDebug
```

For iOS-facing Compose changes, run the relevant framework link and simulator build documented in `docs/mobile-architecture.md`.

Never invent validation results.

## 9. Scope completion

Before finishing:

- remove accidental debug code;
- ensure no generated/local build outputs are committed;
- ensure docs match behaviour;
- ensure no public contract changed incidentally;
- stop at the requested milestone boundary.

If the task is intended for a PR, also use `$talentforge-pr-workflow`.
