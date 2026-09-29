# TalentForge — project instructions

## Project

TalentForge is a native Kotlin Multiplatform mobile talent/build planner for World of Warcraft.

The current repository contains two major areas:

- the Python data platform and reference allocation engine at the repository root;
- the Kotlin Multiplatform mobile client under `mobile/`.

The product is offline-first. Do not introduce a backend, account system, network client, database, dependency-injection framework, analytics stack, or other infrastructure unless the current milestone explicitly requires it.

## Authoritative sources

Use repository artifacts as the source of truth instead of assumptions.

### Data contracts

Public JSON contracts:

- `docs/schema/talent-data.v1.schema.json`
- `docs/schema/dataset-manifest.v1.schema.json`
- `docs/schema/allocation-build.v1.schema.json`
- `docs/schema/allocation-conformance.v1.schema.json`

Canonical normalized data:

- `data/normalized/`

Allocation semantics and evidence:

- `docs/talent-allocation-semantics.md`

Mobile architecture:

- `docs/mobile-architecture.md`

### Design

Authoritative design files:

- `docs/design/TalentForge_UX_UI_Spec_v1.pdf`
- `docs/design/TalentForge_TalentTree_B3.pdf`
- `docs/design/README.md`

Precedence:

1. `TalentForge_UX_UI_Spec_v1.pdf`, pages 2–3, wins when the two design documents conflict.
2. Otherwise `TalentForge_TalentTree_B3.pdf` is authoritative for Talent Tree behaviour and interaction.

Do not reinterpret a frozen interaction rule merely because another implementation would be easier.

## Architecture boundaries

### Python root

The root Python project owns:

- DB2/source adapters;
- normalization;
- validation;
- manifests and diffs;
- the Python reference allocation engine;
- conformance generation/replay.

Do not make the Python package depend on the mobile project.

### `mobile/shared`

`mobile/shared` is a pure Kotlin Multiplatform library.

It owns:

- public contract models;
- strict JSON parsing;
- typed dataset identity;
- dataset repository abstractions and integrity checks;
- allocation-domain logic when that milestone is implemented.

It must not depend on Compose UI or Android/iOS framework types.

### `mobile/composeApp`

`mobile/composeApp` owns:

- Compose Multiplatform UI;
- resources;
- design tokens;
- future application navigation and screens;
- platform-neutral UI adapters.

It may depend on `shared`.

### `mobile/iosApp`

`mobile/iosApp` is a thin native host for the shared Compose application.

Do not create a parallel Swift business/domain layer unless explicitly required.

## Contract discipline

TalentForge is fail-closed.

- Do not silently ignore unknown fields or enum values.
- Do not silently coerce wrong JSON wire types.
- A nullable required field is still required on the wire.
- A schema-defaulted/optional field may use the matching Kotlin default.
- Do not change a public schema merely to simplify Kotlin/Python implementation.
- Do not hand-edit derived mobile dataset copies.
- Do not guess unknown game semantics.
- Preserve typed failure modes; application logic must not parse exception-message strings.

If source evidence is incomplete or contradictory, surface the uncertainty instead of inventing behaviour.

## Allocation semantics status

`docs/talent-allocation-semantics.md` is the authoritative allocation evidence document.

Some rules may be explicitly marked as hypotheses or unresolved. Preserve those confidence levels. Do not upgrade a rule to Proven without repository-recorded client evidence.

In particular, do not silently treat `EDGE-ACTIVE` or `EDGE-SUF` as client-proven until the repository documentation says they are.

## Design discipline

The Talent Tree B3 interaction contract is frozen unless a task explicitly changes the specification.

Core rules include:

- tap a node selects it; tap does not spend;
- spend/refund use explicit visible controls;
- drag pans the canvas;
- pinch zooms;
- important actions must not depend on hidden gestures;
- `Show` navigates to a prerequisite and never spends;
- selected state is independent of purchased/locked state;
- portrait uses the B3 bottom-inspector model;
- landscape uses the B3 side-inspector model.

Node shape meaning for active/passive classification is provisional unless the dataset exposes a proven classification.

## Scope discipline

Implement only the requested milestone.

Do not start the next milestone in the same PR.

Do not add speculative abstractions, frameworks, persistence, networking, UI, or “future-proofing” that has no current consumer.

No placeholder implementations, fake repositories, `assertTrue(true)` tests, ignored acceptance tests, or TODOs in place of required behaviour.

## Reusable project skills

Use the matching project skill when the task fits:

- `talentforge-engineering`
- `talentforge-contract-safety`
- `talentforge-ui`
- `talentforge-pr-workflow`

## Validation

Run checks relevant to the touched area.

Python baseline:

```bash
ruff check .
ruff format --check .
mypy
pytest
```

Mobile baseline:

```bash
python3 scripts/sync_mobile_datasets.py --check
cd mobile
./gradlew :shared:jvmTest
./gradlew :composeApp:desktopTest
./gradlew :composeApp:assembleDebug
```

For iOS/Kotlin Native changes, also run the relevant iOS compilation/framework tasks and, when practical for the milestone, the simulator Xcode build documented in `docs/mobile-architecture.md`.

Never claim a command passed if it was not actually run.
