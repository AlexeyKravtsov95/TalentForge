# Localized talent text (M5.REL.3a / M5.REL.3b.1): source, contract, pipeline and plan

TalentForge's talent names and descriptions are the client's own English strings
(`talent-data.v1`, `talent-tooltips.v1`). M5.REL.3a established **where authentic Russian
strings come from, how they are bound to exactly one dataset revision, and when a Russian
description may be shown at all**, with the **Warrior pilot of `1.60.1.70338`**. M5.REL.3b.1
(§12) completed the data: **36 overlays — all nine classes of every supported build
(`1.60.1.70009`, `1.60.1.70245`, `1.60.1.70338`, `1.60.1.70374`), each generated from its
own build's exports — are committed and bundled** as Compose Multiplatform resources;
M5.DATA.4 (§14) added the nine overlays of the product build `1.60.1.70450`, **45 overlays**
in total. M5.REL.3b.2 (§13) wired them into the editor: on a Russian device the talent
editor shows the client's Russian names and the descriptions that passed every gate, English
everywhere else. No milestone translates anything or changes allocation, persistence or
navigation.

* Source evidence: [research/forever-ruRU-localization-feasibility.md](research/forever-ruRU-localization-feasibility.md)
* Coverage per build (generated, every class):
  [70009](research/forever-1.60.1.70009-ruRU-coverage.md) ·
  [70245](research/forever-1.60.1.70245-ruRU-coverage.md) ·
  [70338](research/forever-1.60.1.70338-ruRU-coverage.md) ·
  [70374](research/forever-1.60.1.70374-ruRU-coverage.md) ·
  [70450](research/forever-1.60.1.70450-ruRU-coverage.md)
* Contract: [`schema/talent-localization.v1.schema.json`](schema/talent-localization.v1.schema.json)
  (generated from `talent_data.models.localization`)

```
exact Forever build
  -> wago.tools DB2 CSV, enUS export  +  the same table with &locale=ruRU     (build-pinned cache)
  -> verify: same columns, same row ids (subset), same non-text columns, Cyrillic present
  -> committed talent-data.v1 (identity, English name/template) + talent-tooltips.v1 (English ranks)
  -> per entry: name status; description gates 1-8 (unchanged M5.V2 renderer, ruRU strings)
  -> data/localization/<game>/<build>/ruRU/<class>.json          (talent-localization.v1)
  -> artifacts/localization/<game>-<build>-ruRU-coverage.json    (+ research/*.md summary)
  -> localization check (offline, CI)
  -> scripts/sync_mobile_localization.py -> composeResources/files/localization/… (M5.REL.3b.1)
```

```bash
python -m talent_data localization fetch     --build 1.60.1.70338 --locale ruRU            # online
python -m talent_data localization report    --build 1.60.1.70338 --locale ruRU --offline  # print coverage
python -m talent_data localization normalize --build 1.60.1.70338 --locale ruRU --classes warrior --offline
python -m talent_data localization check     --build 1.60.1.70338 --locale ruRU            # offline, CI
python scripts/extract_fixtures.py --build 1.60.1.70338 --localization ruRU                # test rows
```

## 1. Result

| | Product build 1.60.1.70338, all nine classes |
| --- | --- |
| Source | wago.tools `…/db2/<Table>/csv?build=1.60.1.70338&locale=ruRU` — the client's own ruRU strings of this build |
| Names | **459 / 469 translated (97.9 %)**, 10 served in English (talents introduced/renamed by Forever), 0 missing |
| Descriptions | **355 / 469 entries rendered (1 058 / 1 329 ranks)**; 114 entries fall back to English with a reason |
| Warrior pilot (committed) | 52 entries: 49 names translated, 3 English; 36 descriptions rendered (103 / 150 ranks) |
| Numbers | every rendered rank shows exactly the numbers of the English sidecar (checked rank by rank, at generation and in `check`) |
| Reproducibility | a fresh download of the four ruRU tables (2026-10-14) was byte-identical to the cache, and `normalize` reproduced the committed overlay, report and Markdown byte for byte |

The pilot is **not** a finished localization and is not shown by the app. It proves the
mapping, the provenance, the fallback rules and the gates on real data.

## 2. Source rules (enforced in `talent_data.localization.source`)

1. **A 200 response proves nothing.** wago.tools answers an unknown or wrongly cased locale
   (`ruru`, `xxXX`) with the enUS export byte for byte. Only the investigated, case-exact
   locale `ruRU` is accepted (`require_supported_locale` → `unsupportedLocale`).
2. **Every localized table is verified against the enUS table of the same build**
   (`verify_localized_table`): identical columns; every localized row id exists in enUS
   (`unexpectedRow`); no duplicate id (`duplicateRow`); every non-`_lang` column equal row by
   row (`nonTextMismatch` — another build snapshot); and for `SpellName`, `Spell`,
   `GlobalStrings` at least one row with different, Cyrillic text (`localeNotApplied`).
   `TraitDefinition` is exempt: its override columns carry no translatable text in Forever
   (both exports are byte-identical).
3. **Pinned cache.** `.cache/wago/<game>/<build>/locale/ruRU/<Table>.csv` + `.meta.json`
   recording the exact build- and locale-pinned URL; an entry recorded for any other URL
   (an enUS file copied in, another build) is treated as absent and re-downloaded.
4. **No substitution.** A row absent from the localized export is `missing`; text equal to
   English is `untranslated`; neither is ever filled from another build, locale or source.
   Text that differs from English but has no Cyrillic letter **stops generation** (never
   observed; would need investigation).

Tables and columns: `SpellName.Name_lang` (names), `TraitDefinition.OverrideName_lang` /
`OverrideDescription_lang` (overrides), `Spell.Description_lang` (templates, including
nested `$@spelltooltip` / `$@spelldesc` targets), `GlobalStrings` (duration unit formats).
All numbers come from the build's locale-independent tooltip tables (`SpellEffect`,
`SpellDuration`, `SpellRadius`, …) exactly as for English.

## 3. Contract: `talent-localization.v1`

One closed, strict JSON document per **game × client build × class × locale**:
`data/localization/<game>/<build>/<locale>/<class>.json`.

| Field | Meaning |
| --- | --- |
| `schemaVersion` | `1` |
| `game`, `clientBuild`, `classToken` | identity of the matching `talent-data.v1` (`classToken` is locale-independent, e.g. `WARRIOR`) |
| `locale` | closed enum, currently only `ruRU` (English is the canonical dataset, never an overlay) |
| `talentDataContentHash` | `contentHash` of the exact `talent-data.v1` revision |
| `tooltipsContentHash` | `contentHash` of the exact English `talent-tooltips.v1` the descriptions were checked against |
| `entries[]` | exactly one per talent entry of the class, ordered by `entryId` |
| `entries[].entryId` / `nodeId` / `definitionId` / `spellId` / `maxRank` | copied from the dataset; the key is `entryId` |
| `entries[].textSpellId` | the spell whose strings name/describe the entry: `VisibleSpellID` when set, else `SpellID` (the dataset's own rule) |
| `entries[].name` | `{status: translated\|untranslated\|missing, text, source}` — `text` is present **exactly** when `translated` (and must contain Cyrillic) |
| `entries[].description` | `{status, source, ranks}` — `ranks` is exactly `1..maxRank` when `rendered`, empty otherwise |
| `ranks[].segments[]` | `{kind: text\|value, text}`; no template markup may leak |
| `contentHash` | sha256 of the canonical JSON of everything except `contentHash` and `provenance` |
| `provenance` | `source`, `renderer` (`talent_data.localization gates-v1 + talent_data.tooltips grammar-v1`), and the eight build- and locale-pinned table URLs |

Properties:

* **Presentation only.** The overlay never carries English text and is never an input to
  allocation, validation, conformance or saved builds. `allocation-build.v1` and
  `talent-data.v1` are unchanged (no `locale` anywhere); a saved build stays valid in every
  language because it references node/entry ids only.
* **Deterministic.** No timestamp, sorted entries, canonical hash; regenerating from the
  same inputs yields byte-identical files.
* **Fail-closed parsing.** Unknown fields, missing fields, wrong wire types, a `text` on a
  non-translated name, ranks on a non-rendered description, incomplete ranks — all rejected.

### Mapping (identity first)

* An overlay entry is keyed by `TalentEntry.id`; its node, definition, spell and max rank are
  copied, never inferred from text.
* Localized strings are read with **the dataset's own English rule**, applied to the ruRU
  export of the same build. The English string obtained the same way must equal the
  dataset's `name` / `descriptionTemplate`, otherwise generation stops (dataset and tables
  are not one build).
* **`spellId` is not an identity.** Hunter *Lightning Reflexes* (spell 19168, entries 129646
  and 137851) and Priest *Holy Specialization* (spell 14889, entries 130594 and 137847) sit
  on two nodes each (`DUPLICATE_SPELL_IDENTITY`). Each entry gets its own overlay entry; the
  shared spell yields the same text for both by identity, never by choosing a node.

## 4. Description gates (`talent_data.localization.generate`)

Russian text is never assembled from English fragments. The **Russian template** is
rendered by the unchanged M5.V2 renderer with the ruRU template, ruRU nested templates and
ruRU duration formats, and the build's numeric tables. The first gate that fails is the
status; only `rendered` publishes ranks:

| # | Gate | Status when it fails | 70338, all classes |
| --- | --- | --- | --- |
| 1 | the ruRU template exists and is not the English string | `missing` / `untranslated` | 3 / 41 |
| 2 | the English sidecar is `resolved` at every rank (Russian never claims more than verified English) | `sourceNotResolved` | 33 |
| 3 | no plural selection `$l…;` / `$L…;` in the template or a nested one | `pluralRuleUnverified` | 12 |
| 4 | every nested template present and translated, everything parses | `missing` / `untranslated` / `structureMismatch` | — |
| 5 | the multiset of value constructs (`$s1`, `${…}`, `$@spelltooltip…`, condition shapes) equals English, template and nested | `structureMismatch` | 0 |
| 6 | every rank renders completely | `renderGap` | 0 |
| 7 | at every rank the numbers shown equal the English sidecar's | `structureMismatch` | 0 |
| 8 | no shown value has a decimal part | `decimalFormatUnverified` | 25 |
| | all passed | `rendered` | 355 |

What is **deterministic from client data** (shown): Russian word order and wording of the
template, every integer value, percentages, `сек`/`мин`/`ч` durations from the ruRU
`GlobalStrings`, nested Russian tooltips, rank-specific values (`$s1` per rank).

What **needs further verification** (falls back to English until recorded evidence exists):

* **Plural forms.** Russian templates select among three forms (`$lудар:удара:ударов;`;
  596 three-form and 52 two-form constructs in the whole `Spell` export). The order is
  one/few/many, but the client's selection rule (and two-form behaviour) is not recorded
  evidence — no guessing from CLDR.
* **Decimal separator.** ruRU `GlobalStrings.DECIMAL_SEPERATOR` is `,`, enUS `.`; which one
  the tooltip formatter uses for `$`-values is unobserved.
* **English partial ranks** (character-dependent values, `$c`, …) stay English with their
  missing-value markers; a Russian rank with `—` gaps would need its own gap model.

## 5. Consumer rule: English fallback

The reference implementation is `talent_data.localization.display.resolve_class_text`
(tests: `tests/unit/localization/test_display.py`); M5.REL.3b mirrors it in Kotlin.

1. Load the overlay for `(game, clientBuild, classToken, locale)` of the **display
   language**; for English, or when no file exists, show English.
2. Apply it **as a whole or not at all**: if `check_localization_document` reports any
   problem (game/build/class, `talentDataContentHash`, `tooltipsContentHash`, `contentHash`,
   entry set, entry identity, numbers), show English for every entry and surface the
   typed reason in diagnostics — never a partially applied stale overlay.
3. Name: overlay `text` when `translated`, else the dataset's English name (and the
   existing `Talent <id>` / `Талант <id>` fallback when the dataset has none).
4. Description: overlay ranks when `rendered`, else the English sidecar ranks **unchanged**
   (status, gaps and `—` markers). A single rank is never mixed from two languages.
5. Rank comparison runs on whichever language is shown; the Russian ranks of one entry
   compare exactly where the English ones do and change the same numbers
   (`test_rank_comparison_behaves_as_in_english`) — only their order follows Russian word order.

## 6. Retrieval and validation strategy

| Step | Command / code | Guarantees |
| --- | --- | --- |
| fetch | `localization fetch` → `WagoLocalizedDb2Source` | enUS + ruRU of the four tables, pinned URL in the cache metadata |
| verify | `verify_localized_table` (runs on every load) | same build snapshot, locale actually applied |
| generate | `localization normalize --classes …` | overlays of the given classes + coverage of **every** class; in memory first, then written |
| write boundary | `publish` | writes only `data/localization/…`, `artifacts/localization/…`, the coverage Markdown; canonical `data/normalized` and `data/tooltips` are inputs only (digest-checked in a test) |
| offline check (CI) | `localization check` / `check_canonical` | overlays strict-parse, match dataset and tooltip revisions, report rows/exceptions recomputed from the overlays, Markdown matches the report |
| drift | hashes + regeneration test | a new dataset or tooltip revision invalidates the overlay; a changed ruRU row changes the overlay hash (`test_source_data_drift_changes_the_overlay`) |
| live | `pytest -m integration tests/integration/test_wago_localization_live.py` | ruRU served for the exact build, build-pinned, unsupported locale = silent enUS |

## 7. Test map

| Requirement | Tests |
| --- | --- |
| EN/RU separation | `test_english_content_stays_english_and_separate`, `test_english_tooltip_text_is_not_changed_by_localization`, `test_publishing_writes_only_localization_outputs` |
| exact build identity | `test_request_and_cache_are_pinned_to_build_and_locale`, `test_a_table_recorded_for_another_url_is_never_reused`, `test_tables_of_another_build_are_refused`, `test_a_foreign_overlay_is_detected`, live `test_the_locale_is_build_pinned` |
| dataset revision linkage | `test_overlays_are_linked_to_the_exact_dataset_and_tooltip_revisions`, `test_a_stale_dataset_revision_is_detected`, `test_a_stale_tooltips_revision_is_detected` |
| node / entry identity | `test_russian_names_are_mapped_to_the_real_talent_identities`, `test_entry_identity_drift_is_detected` |
| duplicate spell identity | `test_entries_sharing_a_spell_keep_separate_identities`, `test_entries_sharing_a_spell_resolve_by_their_own_identity` |
| missing translations | `test_a_missing_russian_row_or_empty_string_is_missing`, `test_a_missing_template_is_missing`, `test_forever_only_talents_have_no_russian_name_and_fall_back`, `test_a_row_missing_from_the_locale_is_counted_not_fatal` |
| invalid / mismatched data | `test_strict_parsing`, `test_the_english_export_served_for_rurU_is_detected`, `test_rows_that_cannot_belong_to_the_same_build_fail`, `test_text_that_is_neither_english_nor_russian_stops_generation`, `test_tampered_content_without_a_new_hash_is_detected`, `test_report_and_files_must_agree` |
| English fallback | `test_display.py` (no overlay, untranslated, unrendered, mismatched or tampered overlay) |
| reproducible generation | `test_regenerating_from_the_build_rows_reproduces_the_committed_files`, `test_generation_is_deterministic` |
| source-data drift | `test_source_data_drift_changes_the_overlay`, `test_the_real_exports_verify_as_one_build_in_two_locales` |
| numeric preservation | `test_every_rendered_rank_shows_exactly_the_english_numbers`, `test_numbers_that_change_in_rendering_are_a_structure_mismatch`, `test_a_changed_number_in_a_rendered_rank_is_detected`, `test_localized_ranks_carry_the_english_numbers` |
| comparison behaviour | `test_rank_comparison_behaves_as_in_english` |
| no allocation / persistence change | `test_allocation_and_dataset_code_never_depend_on_localization`, `test_saved_build_and_allocation_contracts_are_untouched`; no Kotlin source under `mobile/` changes in this milestone |
| every bundled build covered (3b.1) | `test_all_builds.py`: nine overlays per build, `check` + CLI check for all four builds, measured totals, per-build identity and provenance, offline regeneration of 70009/70245, unchanged Warrior pilots, unchanged canonical English trees |

Fixtures are real rows: `tests/fixtures/localization/forever/<build>/{enUS,ruRU}/` for
every bundled build (identical row ids in both locales — the definitions of the dataset,
their text spells, the closure of every spell the English **and** Russian templates
reference or nest, and the duration rows of `GlobalStrings`).

## 8. Integration points (Kotlin runtime — implemented in M5.REL.3b.2, §13)

M5.REL.3b.1 delivered the data half of this list (the first bullet); M5.REL.3b.2
implemented the runtime half as instructed below (details and deviations in §13):

* **Data (done).** `scripts/sync_mobile_localization.py` mirrors
  `data/localization/<game>/<build>/ruRU/` for every `BUNDLED_BUILDS` entry into Compose
  resources (`composeResources/files/localization/<game>/<build>/<locale>/<class>.json`),
  with `--check` in CI (mobile-ci workflow) — the same pattern as `sync_mobile_tooltips.py`
  and through the same `scripts/mobile_bundles.py` module (byte-identical mirror, missing/
  extra-file detection, all-or-nothing preflight). No runtime network.
* **`mobile/shared`.** A strict `TalentLocalizationDocument` decoder and a
  `BundledTalentLocalizationRepository` that performs the §5 checks with typed
  `LocalizationLoadError`s (unknown/missing/duplicate entry, identity drift, hash mismatch).
  Locale-independent: it takes the locale code as input. The resource path convention is
  `files/localization/forever/<build>/ruRU/<class>.json` (lower-case class token, exactly
  the bundled layout); mirror `TooltipsDriftTest` as a JVM `LocalizationDriftTest` over
  `data/localization` vs the bundle, and port `test_display.py` as Kotlin tests against
  the same committed files.
* **`mobile/composeApp`.** `TalentName.published` and `DescriptionBody` resolution in the
  editor (`TalentInspectorContent.talentName`, `TalentInspectorDescription.bodyOf`) choose
  overlay text by `AppLanguage`; the allocation engine, `TalentTreeNodeFacts` identity, persistence,
  history and navigation are not touched. A failed or absent overlay shows English, never
  blocks the editor. The §9 step 5 mixed-inspector decision (Russian name with an English
  description) must be settled before the editor labels anything "localized".
* **Kotlin oracle.** Port `test_display.py` cases as Kotlin tests against the same committed files.

## 9. Plan: M5.REL.3b

1. **All nine classes, product build — done in M5.REL.3b.1 (§12).** `localization normalize
   --build 1.60.1.70374 --locale ruRU --classes all --offline` after `localization fetch`;
   all nine overlays committed and bundled; `check` passes. Coverage as measured in 3a:
   459/469 names, 355/469 descriptions.
2. **Retained builds — done in M5.REL.3b.1 (§12).** The same commands for `1.60.1.70245`
   and `1.60.1.70009`; overlays are per build and never reuse one build's text for another.
   Measured coverage (committed, matches the 3a report-day figures): 70245 — 464/470 names,
   365/470 descriptions (1 097/1 333 ranks), `Spell`/`SpellName` ruRU lack one row
   (1318286, a pet summon, not a talent); 70009 — 467/469 names, 386/469 descriptions
   (1 143/1 333 ranks). Fixtures exist for every build
   (`tests/fixtures/localization/forever/<build>/{enUS,ruRU}/`).
3. **Evidence to lift gates** (each needs recorded client evidence, like
   `talent-allocation-semantics.md` §15): ruRU in-game tooltip screenshots of chosen ranks for
   (a) a three-form plural with counts 1/2/5/11/21, (b) a two-form plural, (c) a decimal value
   (e.g. Warrior Improved Slam `0.5`). Only then add a ruRU plural selector and decimal
   formatter to the renderer (a new renderer revision → all overlays regenerate).
4. **Mobile integration — done in M5.REL.3b.2 (§13)** per §8, behind the existing
   `AppLanguage` rule, with EN/RU UI tests for translated, untranslated, unrendered and
   mismatched overlays and the layout matrix for longer Russian text.
5. **Release gate — decided (product, M5.REL.3b.2).** A Russian name with an English
   description is acceptable and shown as is: it is the honest client state for 114 entries
   today, preferable to an invented translation or to hiding gameplay information. Nothing
   labels the editor or a talent "localized"; coverage targets remain open.
6. **Refresh.** When the product build moves, regenerate overlays after the dataset and
   tooltips; the hashes make a forgotten regeneration fail `check`.

## 10. Known gaps and risks

* **Indirect build proof for text-only tables.** `SpellName`/`Spell` exports have only `ID`
  and `_lang` columns, so their build identity rests on wago.tools honouring `build=` (row
  sets per build, Forever-only rows, change tracking; see the feasibility report §2). A ruRU
  client of the exact build would make it direct.
* **Untranslated is indistinguishable from source fallback.** An English string in the
  ruRU export may be an untranslated client row or a source fallback; both show English,
  which is correct either way.
* **Client typos are preserved.** E.g. Priest *Early Demise* ruRU template
  `…не выше$s2%` renders as `не выше20%` (no space) — that is the client's string; the
  pipeline never edits text.
* **Coverage limited by the gates.** 114 of 469 entries show English descriptions until the
  plural and decimal rules are evidenced and the English partial ranks improve.
* **Source availability.** wago.tools is a third party; the committed overlays and fixtures
  keep everything offline, but regeneration needs the service (or a CASC reader).
* **Renderer coupling.** The overlay embeds the renderer revision; any renderer change must
  regenerate English tooltips and overlays together (the `tooltipsContentHash` link enforces it).

## 11. M5.DATA.3: the product build 1.60.1.70374

The product build moved to `1.60.1.70374` (`docs/forever-dataset.md`). The same pipeline was
run on it, unchanged, from its own exports; nothing of 70338 was reused and the 70338 overlay,
report and Markdown are untouched.

| | 1.60.1.70374 (2026-10-14) |
| --- | --- |
| Source support | `localization fetch --build 1.60.1.70374 --locale ruRU`: all four tables served for the exact build and verified against its enUS exports (same columns, ids and non-text columns; Cyrillic present; `TraitDefinition` text-free as before). `SpellName` 31863 rows (29302 translated), `Spell` 31863 (19553), `GlobalStrings` 27372 (25428), 0 missing |
| Raw identity | the four ruRU and four enUS exports are byte-identical to 70338's |
| Coverage (every class) | names **459 / 469** translated (10 English, 0 missing); descriptions **355 / 469** rendered (1058 / 1329 ranks) — identical to 70338, gate by gate: [research/forever-1.60.1.70374-ruRU-coverage.md](research/forever-1.60.1.70374-ruRU-coverage.md) |
| Committed | `data/localization/forever/1.60.1.70374/ruRU/warrior.json` (pilot, as for 70338), `artifacts/localization/forever-1.60.1.70374-ruRU-coverage.json` |
| Linkage | `talentDataContentHash` = the 70374 Warrior document (`sha256:e47bd92d…`), `tooltipsContentHash` = the 70374 Warrior sidecar (`sha256:43ac9654…`); `localization check --build 1.60.1.70374` passes |
| Tests | `tests/unit/test_product_build.py` regenerates overlay, report and Markdown from `tests/fixtures/localization/forever/1.60.1.70374/` byte for byte and checks the linkage; `tests/unit/localization/` stays pinned to the 3a pilot build 70338 (`PILOT_BUILD`) |

**For M5.REL.3b.1.** Generate the product-build overlays with
`localization normalize --build 1.60.1.70374 --locale ruRU --classes all --offline` (after
`localization fetch`); every overlay is then bound to the hashes above, so the dataset and
tooltip revisions it was produced for are explicit. 70338 keeps its own overlay; bundling
overlays for retained builds stays per build (§9 step 2), never by copying text across
builds — even where, as here, the strings are identical.

## 12. M5.REL.3b.1: all nine classes of every supported build, published and bundled

Executed on 2026-10-15, per §9 steps 1–2, with the unchanged 3a pipeline (fetch → verify →
normalize → check) and no gate, rule or schema change:

```bash
for b in 1.60.1.70009 1.60.1.70245 1.60.1.70338 1.60.1.70374; do
    python -m talent_data localization fetch    --build $b --locale ruRU   # online, build-pinned
    python -m talent_data localization normalize --build $b --locale ruRU --classes all --offline
    python -m talent_data localization check     --build $b --locale ruRU   # offline, CI
done
python scripts/extract_fixtures.py --build 1.60.1.70009 --localization ruRU  # + 1.60.1.70245
python scripts/sync_mobile_localization.py            # bundle 36 overlays
python scripts/sync_mobile_localization.py --check    # CI drift gate
```

| | 1.60.1.70009 | 1.60.1.70245 | 1.60.1.70338 | 1.60.1.70374 |
| --- | --- | --- | --- | --- |
| names translated | 467 / 469 | 464 / 470 | 459 / 469 | 459 / 469 |
| names missing | 0 | 0 | 0 | 0 |
| descriptions rendered | 386 / 469 | 365 / 470 | 355 / 469 | 355 / 469 |
| ranks rendered | 1 143 / 1 333 | 1 097 / 1 333 | 1 058 / 1 329 | 1 058 / 1 329 |
| source quirks | — | `Spell`/`SpellName` ruRU lack row 1318286 (pet summon, not a talent) | — | ruRU exports byte-identical to 70338's |

Coverage reports: [70009](research/forever-1.60.1.70009-ruRU-coverage.md) ·
[70245](research/forever-1.60.1.70245-ruRU-coverage.md) ·
[70338](research/forever-1.60.1.70338-ruRU-coverage.md) ·
[70374](research/forever-1.60.1.70374-ruRU-coverage.md).

Facts of this milestone:

* **36 committed overlays** (`data/localization/forever/<build>/ruRU/`, nine classes each),
  four coverage reports + Markdown, four fixture pairs
  (`tests/fixtures/localization/forever/<build>/{enUS,ruRU}/` — the 70338/70374 fixtures
  predate it and are unchanged).
* **The Warrior pilots are byte-identical** to the 3a / M5.DATA.3 commits: regenerating all
  nine classes reproduces the pilot file exactly (pinned by
  `test_the_warrior_pilots_are_unchanged`).
* **Canonical English data untouched**: `data/normalized/**` and `data/tooltips/**` of all
  four builds hash to their pre-milestone digests
  (`test_canonical_english_data_is_unchanged`), and `publish` never writes outside
  `data/localization`, `artifacts/localization`, `docs/research` (3a boundary test).
* **Bundled**: `composeResources/files/localization/forever/<build>/ruRU/<class>.json` —
  byte-identical mirror via `scripts/sync_mobile_localization.py` (the fourth
  `mobile_bundles.py` kind), `--check` in `mobile-ci` and in
  `test_mobile_bundles.py`. Not yet read by any Kotlin code (§8).
* **One pipeline fix.** Publishing more than one class exposed a latent ordering bug in
  `check_canonical`: the recomputed exception lists followed alphabetical file order while
  the committed report follows dataset order, so every build with ≥2 classes failed with
  "the name/description exceptions do not match the overlays". The check now recomputes in
  dataset order — the pilot-era single-class state had simply never exercised it.
* **Tests**: `tests/unit/localization/test_all_builds.py` (new) plus updated pilot
  assumptions in `test_contract_and_pipeline.py` and `test_product_build.py`. Full offline
  suite: 1038 passed, 11 integration tests deselected.
* **Known limits** (unchanged): plural forms, decimal separator and partial English ranks
  keep their §4/§10 fallbacks; the app still shows English only until the §8 runtime
  milestone.

## 13. M5.REL.3b.2: Russian talent text in the editor

Executed on 2026-10-15 per §8 and §9 steps 4–5. No data, schema, renderer or gate changed;
the 36 overlays of §12 are read as committed.

**Runtime check** (`mobile/shared`, `BundledTalentLocalizationRepository`) — the Kotlin form
of `check_localization_document`, applied whole or not at all: schema patterns and ranges;
the case-exact locale (`ruRU`; anything else is `UnsupportedLocale` and nothing is read);
game, client build and class; the English tooltips and visuals handed in belong to the
document; `talentDataContentHash`; `tooltipsContentHash`; the `contentHash` **recomputed** with
the same canonical JSON + SHA-256 as `content_hash` (pinned by cross-language vectors:
`tests/fixtures/localization/content-hash-vectors.json`, generated and checked by
`tests/unit/localization/test_content_hash_vectors.py`, recomputed by the Kotlin
`LocalizationContentHashTest` together with all 36 published hashes — 45 since M5.DATA.4,
§14); the complete, unique, `entryId`-ordered entry set; node/definition/spell/maxRank identity; the §3 field rules
(text ⇔ `translated` with Cyrillic, ranks exactly `1..maxRank` ⇔ `rendered`, no empty segment,
no markup); and every rendered rank over a resolved English rank with exactly its numbers and
no decimal value. Beyond the Python check, `textSpellId` is verified too: it must equal the
`talent-visuals.v1` `displaySpellId` of the entry (the same VisibleSpellID-else-SpellID rule;
all 1 877 entries agree), because `talent-data.v1` does not carry VisibleSpellID. Every failure
is a typed `LocalizationLoadError` (diagnostic only, never user-facing).

**Consumer rule** (`resolveClassTalentText`, a port of `display.resolve_class_text` with the
`test_display.py` cases ported to `TalentClassTextTest`): by `entryId` only; translated name
or the English name (and the localized `Talent <id>` fallback when neither exists); rendered
ranks or the English ranks unchanged (partial/unresolved status, gaps and `—` markers kept);
a rejected overlay shows English for the whole class. Multi-entry nodes stay English (the
dataset defines no representative entry; none exist in the bundled data today).

**Editor** (`mobile/composeApp`): the overlay is loaded with the session, after every
allocation gate, against the tooltips and visuals the session already read; a missing or
rejected overlay — or missing English inputs — opens the editor in English. Names reach the
inspector heading, the prerequisite / Show / "needed for" / blocked-action copy and the node
accessibility labels; Russian ranks feed the existing description, CURRENT / NEXT RANK
comparison and value highlighting. A language change re-projects the text only.

**Known limits.** The §4 fallbacks are unchanged (plural forms, the decimal separator and
partial English ranks stay English). At 200 % text the single word «жертвоприношение»
(Warlock *Демоническое жертвоприношение*) is wider than the 320 dp sheet and the 300 dp
landscape panel and wraps inside the word — nothing is cut, and every other name and
description keeps its words whole (`EditorLocalizationLayoutUiTest`). The node monogram (shown
only when an icon fails) keeps the English initials.

## 14. M5.DATA.4: the product build 1.60.1.70450

The product build moved to `1.60.1.70450` (`docs/forever-dataset.md`). The same pipeline was
run on it, unchanged, from its own exports, for all nine classes at once; nothing of 70374
was reused and the overlays, reports and Markdown of the four earlier builds are untouched.

| | 1.60.1.70450 (2026-10-17) |
| --- | --- |
| Source support | `localization fetch --build 1.60.1.70450 --locale ruRU`: the exports pass every source check of `talent_data.localization.source` (§2) |
| Coverage (every class) | names **469 / 469** translated (0 English, 0 missing; 70374: 459); descriptions **383 / 469** rendered (1130 / 1328 ranks; 70374: 355, 1058 / 1329): [research/forever-1.60.1.70450-ruRU-coverage.md](research/forever-1.60.1.70450-ruRU-coverage.md) |
| Committed | all nine overlays `data/localization/forever/1.60.1.70450/ruRU/<class>.json`, `artifacts/localization/forever-1.60.1.70450-ruRU-coverage.json`, fixtures `tests/fixtures/localization/forever/1.60.1.70450/{enUS,ruRU}/` |
| Linkage | each overlay's `talentDataContentHash` / `tooltipsContentHash` = its 70450 class document / tooltip sidecar (Warrior: `sha256:f346086e…` / `sha256:484488b1…`); `localization check --build 1.60.1.70450` passes |
| Bundled | mirrored to `composeResources/files/localization/forever/1.60.1.70450/ruRU/` by `scripts/sync_mobile_localization.py`; 45 overlays, 5 builds × 9 classes (`LocalizationDriftTest`) |
| Tests | `tests/unit/test_product_build.py` regenerates the nine overlays, report and Markdown from the fixtures byte for byte and checks the linkage and coverage; the former 70374 tests moved unchanged to `tests/unit/test_build_70374.py`; `test_all_builds.py` covers five builds |
| Content-hash vectors | `tests/fixtures/localization/content-hash-vectors.json` regenerated: 45 overlays (`test_content_hash_vectors.py`; recomputed by the Kotlin `LocalizationContentHashTest`) |

No gate, rule, schema or renderer changed; the §4/§10 fallbacks (plural forms, decimal
separator, partial English ranks) apply unchanged.
