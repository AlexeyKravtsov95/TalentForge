# TalentForge knowledge graph

The committed graph is available to new agent sessions and fresh clones without
rebuilding it. `AGENTS.md` explains when to query and update it; the project-scoped
Codex skill is under `.codex/skills/graphify/`.

## Use

Install the graphify version matching the committed skill:

```bash
uv tool install graphifyy==0.9.99
```

From the repository root:

```bash
graphify query "allocation conformance engine" --budget 1500
graphify update .
```

Queries use the existing graph. `update` refreshes code relationships through AST
extraction without an API key. Changed documentation needs a semantic refresh
through the graphify skill; an AST-only update does not establish new documented
facts. Open `graph.html` for the aggregated community map.

Single-symbol queries may report ambiguous matches across Python and Kotlin;
add context to the question when that happens.

## Scope and privacy

`.graphifyignore` owns the selected scope: source, tests, contracts, and selected
documentation/design PDFs. Canonical datasets, binary assets, screenshots,
release evidence, and generated build output are outside this graph's scope.

**Never traverse, read, or index `ceremony/`.** It contains private NDA material
and is excluded by both `.graphifyignore` and `.gitignore`.

## Shared outputs

- `graph.json`: nodes, relationships, provenance, and community names.
- `GRAPH_REPORT.md`: navigation, connected symbols, and interpretation limits.
- `graph.html`: interactive community overview.
- `diagnostics.json`: extraction integrity diagnostics, not application defects.
- `benchmark.json`: synthetic context-size comparison, not an accuracy benchmark.
- `scope.json`: corpus counts and semantic coverage.

Git ignores local caches, manifests, temporary extraction files, token-accounting
state, and machine-specific `.graphify_python` / `.graphify_root` paths. Those
files are unnecessary for querying the committed graph.

## Interpretation

The default graph is undirected; repeated relationships between a pair of nodes
may collapse, and unresolved references may become reference-only nodes. Verify
`INFERRED` links against source before relying on them. `EXTRACTED` describes a
structural or documented relationship, not proof of game semantics. The recorded
confidence of `EDGE-ACTIVE` and `EDGE-SUF` remains unchanged.

AST extraction uses no LLM/API tokens. Actual token usage of host-agent semantic
extraction is unavailable through this tool interface and is not reported as zero.
