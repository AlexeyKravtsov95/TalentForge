---
name: talentforge-contract-safety
description: Use whenever changing or reviewing TalentForge JSON schemas, normalized datasets, manifests, Kotlin/Python serializers, dataset repositories, conformance vectors/loaders, allocation models/rules, DB2 normalization, or any code that interprets game-data semantics. Enforces strict closed-contract parsing, fail-closed behaviour, dataset identity/integrity, evidence confidence, and conformance safety.
---

# TalentForge Contract Safety

Use this skill for any change that crosses a data-contract or game-semantics boundary.

## 1. Source-of-truth hierarchy

Authoritative wire contracts:

- `docs/schema/talent-data.v1.schema.json`
- `docs/schema/dataset-manifest.v1.schema.json`
- `docs/schema/allocation-build.v1.schema.json`
- `docs/schema/allocation-conformance.v1.schema.json`

Use committed documents under `data/normalized/` as real examples.

Do not mechanically mirror Python internals when the public schema says something different.

Allocation semantics/confidence live in `docs/talent-allocation-semantics.md`.

## 2. Closed-contract parsing

Treat the contracts as fail-closed:

- unknown object fields fail;
- unknown closed-enum values fail;
- unsupported `schemaVersion` fails explicitly;
- wrong JSON wire types fail;
- quoted numbers do not satisfy numeric schema fields;
- missing required properties fail;
- required nullable fields must still appear on the wire;
- optional/defaulted fields may be absent only when the schema permits it.

A Kotlin default is valid only when its absence semantics match the schema.

Do not set `ignoreUnknownKeys = true`, coerce types, or invent enum fallbacks for closed unions.

Open unions explicitly allowed by schema may preserve unknown raw values if the contract says to do so.

## 3. Public schema changes

A consumer adapts to the public contract, not the reverse.

Do not edit a schema merely because Kotlin/Python serialization is inconvenient.

If a genuine schema defect is discovered:

1. stop the dependent implementation;
2. document the mismatch;
3. explain compatibility impact;
4. change the schema only when the task explicitly authorizes a contract revision;
5. update producers, consumers, fixtures, conformance data, and versioning together.

Never change a public contract silently.

## 4. Dataset identity and integrity

Keep dataset identity explicit: game, client build, class token, tree identity, and content hash where defined.

The mobile repository must verify manifest/document consistency rather than trusting filenames alone.

Preserve checks for game, client build, class token, published `contentHash`, and tree IDs.

Do not recompute the Python canonical semantic hash in Kotlin unless that algorithm becomes a public contract.

## 5. Canonical versus bundled data

Canonical normalized datasets live under `data/normalized/`.

Bundled mobile resources are derived copies. Never hand-edit them.

Use:

```bash
python3 scripts/sync_mobile_datasets.py
python3 scripts/sync_mobile_datasets.py --check
```

CI must fail when canonical data changes without re-sync.

Changes under `data/conformance/**` must continue to trigger mobile contract/conformance tests.

## 6. Game semantics: evidence, not guesses

Preserve confidence labels from `docs/talent-allocation-semantics.md`.

If a rule is hypothesis/unresolved, preserve that status, fail closed where the contract says to fail closed, and do not present it as client-proven.

Repository-recorded evidence is required to raise confidence.

Chat recollections, third-party calculators, or inferred UI behaviour are not substitutes for recorded reproducible evidence.

## 7. EDGE-ACTIVE / EDGE-SUF guardrail

Until the semantics document records client observations that upgrade them:

- `EDGE-ACTIVE` remains a reference hypothesis;
- `EDGE-SUF` remains a reference hypothesis.

Conformance vectors encoding a hypothesis do not make it proven.

If live evidence contradicts either rule:

1. do not patch Kotlin just to match existing vectors;
2. update evidence and the Python reference rule first;
3. regenerate/review affected conformance vectors;
4. only then update the Kotlin executor.

## 8. Conformance safety

Conformance expectations are executable product contracts, not generated echoes of the implementation under test.

Do not create a runner that simply returns expected values.

When implementing an executor, parse the existing suite strictly, execute actual domain operations, compare complete structured outcomes, and preserve canonical issue/reason ordering from the reference semantics.

When changing semantics, explain which scenarios change and why.

## 9. DB2/normalization safety

Unknown DB2 mechanics must not be silently flattened into supported semantics.

Examples include unknown condition shapes, unexpected cost link tables, unsupported node/entry shapes, mixed/unknown edge semantics, and unknown table availability where absence is part of a proof.

“Not observed” is not equivalent to “absent.”

## 10. Review checklist

Verify:

- schema/model required/optional semantics match;
- nullable required fields are still required;
- numeric/string wire forms are correct;
- unknown values fail or are preserved only where schema permits;
- real committed documents still parse;
- manifest/document identity is checked;
- bundle drift protection still works;
- conformance counts/identity/hash checks remain intact;
- no hypothesis was upgraded without evidence;
- no public schema changed incidentally.
