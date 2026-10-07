# Talent visuals (M5.V1: real, build-pinned, offline talent icons)

This document describes how TalentForge obtains the real WoW icon of every talent, the
two sidecar contracts that carry them, and how the mobile app renders them without any
network access. Everything below was verified against the exact client build
**Forever `1.60.1.70009`** on 2026-10-07.

```
data/normalized/forever/<build>/<class>.json      (talent-data.v1, unchanged)
        │  TalentEntry.definitionId
        ▼
TraitDefinition ── displaySpellId = VisibleSpellID or SpellID
        │
        ▼
SpellMisc.SpellIconFileDataID ──► data/visuals/forever/<build>/<class>.json   (talent-visuals.v1)
        │ FileDataID
        ▼
client file (BLP2, by FileDataID + build) ──► data/visuals/forever/<build>/icons/<id>.png
                                              data/visuals/forever/<build>/icons.json (talent-icons.v1)
        │ scripts/sync_mobile_visuals.py (byte-identical mirror)
        ▼
mobile/composeApp/src/commonMain/composeResources/files/visuals/forever/<build>/
```

## 1. Why a sidecar and not `talent-data.v1`

Icons are presentation/media data, not allocation semantics. `talent-data.v1` is a
public, frozen contract consumed by the allocation engine and the conformance suite; it
was **not** changed, no field was added, and it stays loadable without any visual data.
The visuals live in two separate, versioned, closed contracts:

| contract | file | schema |
| --- | --- | --- |
| `talent-visuals.v1` | `data/visuals/<game>/<build>/<class>.json` (one per class) | `docs/schema/talent-visuals.v1.schema.json` |
| `talent-icons.v1` | `data/visuals/<game>/<build>/icons.json` (one per build) | `docs/schema/talent-icons.v1.schema.json` |

Both are strict in every implementation: unknown fields, missing fields, an unsupported
`schemaVersion`, quoted numbers, fractional or boolean numbers, non-positive ids and
malformed hashes are rejected. Neither has a timestamp, so regenerating from the same
inputs is byte-identical.

### `talent-visuals.v1`

```json
{
  "schemaVersion": 1,
  "game": "forever",
  "clientBuild": "1.60.1.70009",
  "classToken": "WARRIOR",
  "talentDataContentHash": "sha256:cab5b510…",
  "entries": [
    { "entryId": 130686, "definitionId": 135487, "spellId": 12286,
      "displaySpellId": 12286, "iconFileDataId": 132155 }
  ],
  "contentHash": "sha256:…",
  "provenance": { "source": "wago.tools", "tables": ["TraitDefinition", "SpellMisc"],
                  "iconColumn": "SpellMisc.SpellIconFileDataID" }
}
```

* `entries` is ordered by `entryId` and has **exactly one entry per talent entry** of the
  class: no entry without an icon, no icon for an unknown entry. The contract has no
  "missing icon" shape on purpose — a class with an unresolved icon publishes nothing.
* `talentDataContentHash` is the `contentHash` of the `talent-data.v1` document the icons
  were resolved for. A sidecar is valid only next to exactly that document, which is
  stricter than matching `game` / `clientBuild` / `classToken` (all three are checked too).
  Consumers compare the published strings; nobody recomputes the dataset hash.
* `contentHash` is `sha256` over the canonical JSON (sorted keys, compact separators,
  UTF-8) of every field except `contentHash` and `provenance`.
* No `assetKey` field: the asset is identified by `iconFileDataId`, and the registry is the
  single place that maps a FileDataID to a file. No URL is stored anywhere.

### `talent-icons.v1`

```json
{
  "schemaVersion": 1,
  "game": "forever",
  "clientBuild": "1.60.1.70009",
  "imageFormat": "png",
  "icons": [
    { "fileDataId": 132155, "file": "icons/132155.png", "width": 64, "height": 64,
      "byteLength": 4665, "sha256": "sha256:48c22a4e…",
      "sourceFormat": "BLP2", "sourceByteLength": 3916, "sourceSha256": "sha256:6abba572…" }
  ],
  "contentHash": "sha256:…",
  "provenance": { "source": "wago.tools",
                  "retrieval": "client file by FileDataID, pinned to clientBuild",
                  "converter": "Pillow 12.3.0 BLP decoder -> PNG (compress_level=9)" }
}
```

* One entry per FileDataID, ordered by `fileDataId`; `file` is always
  `icons/<fileDataId>.png` relative to the registry. Class names are not part of a file
  name: 469 talent entries share 358 files.
* `sha256` / `byteLength` describe the bundled image; `sourceSha256` /
  `sourceByteLength` describe the client file it was converted from, so the provenance of
  every committed image is checkable against the client.

## 2. Source truth for build 1.60.1.70009

### `SpellMisc`

`GET https://wago.tools/db2/SpellMisc/csv?build=1.60.1.70009` → 31 698 rows, 34 columns:

```
ID, Attributes_0 … Attributes_16, DifficultyID, CastingTimeIndex, DurationIndex,
PvPDurationIndex, RangeIndex, SchoolMask, Speed, LaunchDelay, MinDuration,
SpellIconFileDataID, ActiveIconFileDataID, ContentTuningID,
ShowFutureSpellPlayerConditionID, SpellVisualScript, ActiveSpellVisualScript, SpellID
```

`DifficultyID` distribution over the whole table: `0` × 31 587, `186` × 88, `226` × 19,
`9` × 2, `184` × 1, `215` × 1; 104 spells have more than one row. The typed layer
(`db2/tables.py::SpellMisc`) reads exactly `ID`, `SpellID`, `DifficultyID`,
`SpellIconFileDataID`, `ActiveIconFileDataID` and fails loudly when one is missing.
`SpellMisc` is in `VISUAL_TABLES`, **not** in `TRAIT_TABLES`: allocation normalization
never depends on it.

### Facts about the 469 bundled talent entries

| fact | value |
| --- | --- |
| `TraitDefinition.VisibleSpellID` non-zero | 0 of 469 (0 of all 654 definitions in the build) |
| `TraitDefinition.OverrideIcon` non-zero | 0 of 469 (2 of 654 definitions, none in a class tree) |
| display spells with more than one `SpellMisc` row | 0 |
| display spells with a non-zero `DifficultyID` row | 0 |
| display spells with `SpellIconFileDataID = 0` or no row | 0 |
| display spells that also have an `ActiveIconFileDataID` | 4 (Druid 24858, Mage 12042 / 11958, Priest 15473) |

### Icon resolution rule

For every talent entry of the published `talent-data.v1` document
(`visuals/resolver.py`):

1. look up its `TraitDefinition`; the definition's `SpellID` must equal the entry's
   `spellId` (otherwise the two inputs are not the same dataset → `spell-mismatch`);
2. `displaySpellId = VisibleSpellID if VisibleSpellID != 0 else SpellID` — the same
   preference the normalizer already uses for the displayed name and description;
3. take **all** `SpellMisc` rows of `displaySpellId` and use `SpellIconFileDataID` (the
   normal icon, never `ActiveIconFileDataID`);
4. the rows must agree on one non-zero FileDataID.

Everything else is *unresolved* with a stable reason code, and nothing is guessed:

| reason | when |
| --- | --- |
| `definition-missing` | the entry's `TraitDefinition` is not in the build's table |
| `spell-mismatch` | dataset and DB2 disagree about the entry's spell |
| `override-icon-unproven` | `TraitDefinition.OverrideIcon` is set |
| `spell-misc-missing` | no `SpellMisc` row for the display spell |
| `icon-zero` | `SpellIconFileDataID` is 0 |
| `icon-conflict` | several rows name different icons |

Deliberate non-rules:

* **No `DifficultyID` preference.** Every bundled display spell has a single row, so
  "prefer difficulty 0" would be a rule this build gives no evidence for. Several rows
  are accepted only when they all name the same icon; a `0` next to a real icon is a
  conflict, not something to skip.
* **No fallback to the underlying spell** when a `VisibleSpellID` resolves but has no
  icon.
* **`OverrideIcon` is not applied.** It does not occur in the bundled trees, and whether
  the Forever client lets it replace the spell icon is not recorded evidence — so an
  entry that has it is reported unresolved instead of silently picking either icon.

### Coverage (the gate)

`python -m talent_data visuals report --build 1.60.1.70009` →
`artifacts/visuals/forever-1.60.1.70009-icon-coverage.json`:

| class | entries | resolved | unresolved | distinct icons |
| --- | ---: | ---: | ---: | ---: |
| Warrior | 53 | 53 | 0 | 53 |
| Paladin | 50 | 50 | 0 | 50 |
| Hunter | 52 | 52 | 0 | 51 |
| Rogue | 53 | 53 | 0 | 53 |
| Priest | 54 | 54 | 0 | 53 |
| Shaman | 50 | 50 | 0 | 49 |
| Mage | 54 | 54 | 0 | 54 |
| Warlock | 52 | 52 | 0 | 52 |
| Druid | 51 | 51 | 0 | 51 |
| **total** | **469** | **469** | **0** | **358** |

358 distinct FileDataIDs; 86 of them are used by more than one entry (197 entries in
total; 85 of the 86 are shared across classes). The report lists every shared icon
with its entry ids and would list every unresolved entry with class, node, entry, spell,
display spell and reason.

`report` exits non-zero below 100 %, and `fetch` / `normalize` refuse to fetch or
publish anything for an incomplete build.

## 3. Icon files

### Retrieval

`GET https://wago.tools/api/casc/<FileDataID>?version=<build>` returns the raw client
file (`application/octet-stream`). Verified behaviour the code relies on
(`sources/wago/casc.py`, pinned by tests with the verbatim payloads):

* **the `version` parameter matters** — without it wago.tools serves its latest copy,
  which for FileDataID 132269 differs byte-for-byte from the 1.60.1.70009 file. The
  build is always sent and is part of the cache identity;
* unknown build → HTTP 400 `{"error":"Version not found"}`;
* unknown FileDataID → HTTP **200** `{"error":"File not found","0":400}` — so a 200 is not
  proof of a file, and every payload is checked before it becomes a cache entry.

`CascFileSource.fetch_file(version, file_data_id)` is a separate, blob-shaped boundary
(the table-shaped `Db2Source` is untouched). The wago implementation downloads atomically
(`.part` → rename), writes the size + sha256 sidecar, caches at
`.cache/wago/<game>/<build>/casc/<FileDataID>.bin`, and treats a cache entry whose
recorded URL names another build as absent — a file copied in from another build's
directory is never reused. Failures are typed (`CascFileError.kind`: `unknown-build`,
`file-not-found`, `not-cached`, `invalid-payload`, `transport`).

### Format and conversion

All 358 files are **BLP2**, 64 × 64, DXT-compressed with mipmaps; 273 have a real alpha
channel (the rounded icon corners are transparent), 85 have none. They are converted at
build time only (`visuals/icons.py`):

* decoded with Pillow's maintained BLP plugin — no hand-written DXT decoder;
* written as 8-bit PNG, RGBA when the texture has alpha and RGB when it has none;
* native 64 × 64, no resampling, no upscaling, no effects, no metadata chunks
  (`IHDR` / `IDAT` / `IEND` only). Lock dimming, state rings and rank chips are drawn by
  the app at render time, never baked into a file;
* pixel-exact: tests decode the PNG and the BLP2 texture and compare the raw pixels.

Pillow is an optional dependency (`pip install -e ".[visuals]"`, also part of `dev`),
pinned to `12.3.0` so the PNG bytes are reproducible; it is needed only to *generate*
assets. Checking committed assets parses the PNG header with the standard library.

### Size

| | value |
| --- | ---: |
| talent entries | 469 |
| distinct FileDataIDs = bundled image files | 358 |
| client files (BLP2) | 2 087 808 bytes |
| bundled images (PNG) | 1 996 859 bytes |
| nine sidecars + `icons.json` | 216 640 bytes |

Individual deduplicated files; no atlas. At ~2 MB for 358 files an atlas would add a
build step and a coordinate contract without a measured need.

## 4. Commands: online generation vs. offline everything else

```bash
pip install -e ".[dev]"                                   # includes the pinned Pillow

# ONLINE, on demand (only when the build or the dataset changes):
python -m talent_data visuals fetch --build 1.60.1.70009  # SpellMisc + 358 client files

# OFFLINE, from the local cache:
python -m talent_data visuals report    --build 1.60.1.70009 --offline   # coverage gate
python -m talent_data visuals normalize --build 1.60.1.70009 --offline   # data/visuals/…
python scripts/sync_mobile_visuals.py                                    # mobile bundle

# OFFLINE, from the repository only (no cache, no Pillow) — what CI runs:
python -m talent_data visuals check --build 1.60.1.70009
python scripts/sync_mobile_visuals.py --check
```

* `visuals` commands take an exact build; `latest` is refused.
* `normalize` is all-or-nothing: every sidecar and image is produced in memory first; one
  unresolved or unavailable icon publishes nothing. It also removes files a previous run
  published and this one did not, so the directory is exactly the published set.
* `check` verifies the committed visuals against the committed dataset: sidecar identity
  (game / build / class / dataset hash), exact entry coverage, content hashes, registry
  ordering and uniqueness, and for every image: present, non-empty, recorded size and
  sha256, a supported PNG of the recorded dimensions. It also reports registry entries no
  talent uses, icons a talent uses that are not registered, orphan image files and stray
  files.
* `sync_mobile_visuals.py --check` is the drift check: the mobile bundle must be a
  byte-identical mirror — nothing missing, nothing extra, nothing different.

Normal CI and the mobile build never contact wago.tools. The live behaviour is pinned
by `pytest -m integration` (manual workflow), separately.

## 5. Mobile bundle and consumer obligations

`mobile/composeApp/src/commonMain/composeResources/files/visuals/forever/1.60.1.70009/`
is a derived, byte-identical copy of the canonical directory; never edit it by hand.

A consumer of the sidecars must, as part of loading them:

* parse both documents strictly (closed objects, exact `schemaVersion`, numbers as JSON
  numbers);
* refuse a `talent-visuals.v1` document whose `game`, `clientBuild`, `classToken` or
  `talentDataContentHash` does not match the `talent-data.v1` document it is used with —
  visuals are never substituted across builds or dataset revisions;
* refuse duplicate `entryId`s and entries that reference no talent entry of that document;
* read images only from the bundle (no network at runtime), decode each image once, and
  keep the monogram tile as the fallback for a talent whose icon cannot be loaded —
  a broken icon must not take the tree down.

## 6. Provenance and ownership of the artwork

* Source table: `SpellMisc` (`SpellIconFileDataID`), joined through `TraitDefinition`.
* Source build: Forever `1.60.1.70009` (product `wow_classic_beta`).
* Icon identity: the 358 FileDataIDs listed in `data/visuals/forever/1.60.1.70009/icons.json`.
* Binary retrieval: wago.tools, client file by FileDataID pinned to that build.
* Generated mobile format: 64 × 64 PNG, converted from the client's BLP2 textures.

The icon artwork is World of Warcraft game art and **remains the intellectual property
of Blizzard Entertainment**. TalentForge does not own it and claims no rights in it; the
files are unmodified conversions of client textures, identified by the client's own
FileDataIDs. No Blizzard logos are included. This document records where the files come from; it does not state a legal
position on any use of them.
