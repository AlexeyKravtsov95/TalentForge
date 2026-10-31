# TalentForge — project instructions for ZCode Agent

TalentForge is a native Kotlin Multiplatform mobile talent/build planner for World of Warcraft.

The repository has two deliberately separated areas:

- the Python data platform/reference allocation engine at the repository root;
- the KMP mobile client under `mobile/`.

Treat the repository and its committed documentation as the source of truth.

## Read these sources when relevant

Data contracts:

- `docs/schema/talent-data.v1.schema.json`
- `docs/schema/dataset-manifest.v1.schema.json`
- `docs/schema/allocation-build.v1.schema.json`
- `docs/schema/allocation-conformance.v1.schema.json`

Data and semantics:

- `data/normalized/`
- `docs/talent-allocation-semantics.md`
- `docs/mobile-architecture.md`

Design:

- `docs/design/TalentForge_UX_UI_Spec_v1.pdf`
- `docs/design/TalentForge_TalentTree_B3.pdf`
- `docs/design/README.md`

Design precedence:

1. UX/UI Specification V1 pages 2–3 wins on conflict.
2. Otherwise B3 is authoritative for Talent Tree behaviour and interaction.

## Architecture boundaries

`mobile/shared` is pure KMP domain/data code and must not depend on Compose UI or Android/iOS framework types.

`mobile/composeApp` owns Compose UI, resources, theme/design tokens, and future navigation/screens.

`mobile/iosApp` remains a thin native host; do not create a duplicate Swift business/domain layer.

The root Python project remains independent from Gradle/mobile code.

## Engineering rules

TalentForge is fail-closed:

- do not ignore unknown contract fields or values;
- do not silently coerce wrong JSON wire types;
- required nullable fields are still required;
- defaults are allowed only when the public schema allows them;
- do not change public schemas for implementation convenience;
- do not hand-edit derived mobile datasets;
- do not guess game semantics;
- preserve typed errors.

`docs/talent-allocation-semantics.md` owns allocation evidence and confidence. Do not upgrade hypothesis/unresolved rules to Proven without repository-recorded client evidence. In particular, preserve the documented status of `EDGE-ACTIVE` and `EDGE-SUF`.

The product is offline-first. Do not introduce backend/networking, persistence, DI, analytics, or other infrastructure unless the active milestone explicitly requires it.

Implement only the requested milestone. Do not begin the next milestone in the same PR. Avoid speculative abstractions and placeholders.

## Frozen Talent Tree rules

Unless the specification is explicitly being changed:

- node tap selects only and never spends;
- spending/refunding uses visible controls;
- drag pans and pinch zooms;
- critical actions are not hidden gestures;
- `Show` navigates one prerequisite hop and never spends;
- selection styling is independent from allocation/lock state;
- portrait uses the B3 bottom inspector;
- landscape uses the B3 side inspector.

Active/passive node-shape semantics remain provisional unless backed by a proven dataset field.

## Skills

When installed/enabled for this workspace, use the relevant project skills:

- `$talentforge-engineering`
- `$talentforge-contract-safety`
- `$talentforge-ui`
- `$talentforge-pr-workflow`

## Validation

Run only commands you can actually execute, and report exact results.

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

For iOS/Kotlin Native changes, run the relevant iOS compilation/framework and simulator build tasks documented in `docs/mobile-architecture.md`.

Never merge a PR unless the user explicitly instructs you to merge it.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- Never traverse, read, or index `ceremony/`; it contains private NDA material and must remain excluded from Git and graphify.
- When graphify-out/graph.json exists, run `graphify query "<question>"` before your first grep, search or source-file read for a codebase question, and put this rule in every subagent prompt that explores code. Read raw files only for lines the graph or an earlier read already located. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
- A committed graphify-out/graph.json is rewritten whole on every build, so a large diff to it in a pull request is expected. Do not flag it as churn or suggest skipping the graph rebuild or its hooks.
