# Forever client tooltip capture (M5.V2A)

How TalentForge obtains the real, rendered description of every talent: by asking the
exact game client, recording its raw answer, and importing that record as a build-pinned
audit artifact. This document describes the pipeline and its rules.

> **REAL CLIENT CAPTURE PENDING.** M5.V2A delivers the tooling. No capture of a real
> client is committed, and nothing below describes what the Forever client actually
> returns - that is exactly what the capture is for. Everything in "Status of the
> evidence" marked *unverified* stays unverified until a real capture is imported.

```
data/normalized/forever/1.60.1.70009/*.json        talent-data.v1 (unchanged)
        │  python -m talent_data tooltips manifest
        ▼
tools/wow-tooltip-capture/TalentForgeTooltipCapture/GeneratedTalentManifest.lua
        │  469 entries, 1333 entry/rank pairs, manifestHash
        ▼
exact Forever client 1.60.1.70009, enUS            (manual step)
        │  C_TooltipInfo.GetTraitEntry(entryID, rank), read-only
        ▼
WTF/Account/<account>/SavedVariables/TalentForgeTooltipCapture.lua
        │  base64(JSON, capture format v1) in one narrow record
        │  python -m talent_data tooltips import-client
        ▼
artifacts/tooltips/forever/1.60.1.70009/enUS/client-capture.json     raw evidence
        │  python -m talent_data tooltips report
        ▼
artifacts/tooltips/forever/1.60.1.70009/enUS/client-capture-report.md
        ┆
        ▼  M5.V2B (not in this milestone)
talent-tooltips.v1 -> offline mobile Inspector
```

The mobile app never talks to the game. The capture is a one-off, manual evidence step
per client build; its result is a repository file.

## 1. Why capture instead of rendering templates

Every talent entry carries a raw client template in `descriptionTemplate`. The committed
inventory ([forever-tooltip-template-inventory.md](forever-tooltip-template-inventory.md))
counts 469 entries, of which 461 need evaluation: effect values, cross-spell references,
durations, radii, proc values, scaled references, `${...}` arithmetic with precision
suffixes, pluralisation, client-side conditionals, a nested spell tooltip, colour markup
and named variables. A renderer for that language would be an approximation of the
client, and every disagreement would be a wrong description shown to a player.

The client already renders these tooltips. `C_TooltipInfo.GetTraitEntry(entryID [, rank])`
returns `TooltipData` with the rendered lines. So for M5.V2 the **client-rendered output
is authoritative**; the raw templates stay in `talent-data.v1` as evidence and as a
validation input, and are not interpreted into user-facing text.

## 2. Exact identity: build, locale, manifest

| fact | required | enforced by |
| --- | --- | --- |
| game | `forever` | importer |
| client build | `1.60.1.70009` - `GetBuildInfo()` version `.` build, compared as a whole string | addon (refuses to start), importer |
| locale | `enUS` - `GetLocale()` | addon (refuses to start), importer |
| capture manifest | `manifestHash` of the repository's current manifest | importer |
| dataset | `contentHash` of each of the nine `talent-data.v1` documents | importer (and part of `manifestHash`) |

There is no "close enough" build. Another build, or another locale, does not produce a
capture at all: the addon prints the actual value and stops, and nothing marked complete
is written. The locale rule exists because the canonical dataset is English; a localized
capture would silently mix languages. The capture format records the locale and the
artifact path contains it, so other locales can be added later, but M5.V2A builds no
localization system and accepts exactly one capture: enUS.

### Capture manifest

`GeneratedTalentManifest.lua` is the addon's only talent input. It is rendered from the
committed dataset and never edited by hand:

```bash
python -m talent_data tooltips manifest --build 1.60.1.70009           # write
python -m talent_data tooltips manifest --build 1.60.1.70009 --check   # verify
```

For every talent entry it lists `classToken`, `treeId`, `nodeId`, `entryId`, `spellId`,
`name` and `maxRank`; for the dataset it lists `game`, `clientBuild`, the target `locale`,
the format version and every class document's `contentHash`. Order is deterministic:
classes in dataset-manifest (class id) order, entries by entry id.

`manifestHash` is the sha256 of the canonical JSON of all of the above. A capture carries
the hash of the manifest it was taken with. If the dataset changes in any way, the hash
changes, `--check` (and the test suite) fails until the Lua is regenerated, and captures
taken with the old manifest are rejected. A capture cannot be reused across builds or
dataset revisions.

Current manifest: **469 entries, 1333 entry/rank pairs**.

## 3. The capture addon

`tools/wow-tooltip-capture/TalentForgeTooltipCapture/` - installation and the exact macOS
steps are in [tools/wow-tooltip-capture/README.md](../../tools/wow-tooltip-capture/README.md).

### Read-only by construction

The addon calls `C_TooltipInfo.GetTraitEntry`, `GetBuildInfo`, `GetLocale` and, for
diagnostics, `UnitClass("player")` / `UnitLevel("player")`. It calls nothing else from the
game API: nothing that purchases, refunds, resets, stages or activates talents, and no
`C_Traits` / `C_ClassTalents` function at all. The character's class, level and current
talents are not inputs. The tests run the real addon in a sandbox that records every
global it reads or writes and assert that list.

### Requests

For every entry, ranks `1..maxRank` are requested separately; one rank is never assumed to
describe the others. Rank 0 is not requested.

### Raw data, verbatim

The returned `TooltipData` is dumped as it is, not picked apart:

- every field of the table and of every line is kept, including fields this pipeline has
  no name for; nothing is selected as "the description";
- strings are stored byte for byte: no trimming, no punctuation or whitespace
  normalisation, no stripping of `|c…|r` markup or line breaks, no translation;
- a table with keys exactly `1..n` becomes a JSON array, any other table a JSON object
  with sorted keys (numeric keys as decimal strings);
- function-valued fields (methods mixed into colour tables) are not data and are left out;
- anything that cannot be represented - userdata, a non-finite number, nesting deeper than
  8, ambiguous keys - fails that tooltip with an error instead of being dropped.

### Dynamic data and completeness

A `GetTraitEntry` result is not assumed to be final. Every (entry, rank) is a record with
a state:

| status | meaning |
| --- | --- |
| `pending` | not requested yet |
| `unverified` | the client returned text, not yet confirmed |
| `ok` | final |
| `nil` | the client returned `nil` |
| `empty` | the client returned data without any text line |
| `error` | the call or the raw dump failed |

A record becomes `ok` only when a later read, at least one second after the previous one,
returns exactly the same data (ignoring `dataInstanceID`). `TOOLTIP_DATA_UPDATE` is
correlated by `dataInstanceID`: an update for a record's instance makes that record
non-final again, and an update without an id does so for every record. An update arriving
while the finished payload is being encoded discards the payload and re-verifies.

Requests run in batches of 20 per frame, in passes with a one-second pause between them,
so the client stays responsive. After at most 10 passes any record that is still not `ok`
makes the capture incomplete.

`complete = true` is stored only when every expected entry/rank pair is `ok` and the
number of records equals the manifest's rank count - not merely because the queue was
traversed. An incomplete capture is still stored, marked `complete = false`, with each
record's status, error and whatever the client did return, so it can be diagnosed; the
importer rejects it. A capture interrupted before it finished leaves a record without any
payload, which is also rejected. Starting again is always safe.

Known limit: a placeholder that the client returns identically twice, a second apart,
without ever sending `TOOLTIP_DATA_UPDATE` before the run ends would be recorded as final.
The check for that is to capture twice and compare the semantic hashes (section 5).

## 4. Handoff: SavedVariables -> repository

The addon builds one JSON document (capture format v1) itself, with a deterministic key
order, base64-encodes it and stores it in chunks in a single saved variable:

```lua
TalentForgeTooltipCaptureDB = {
    ["formatVersion"] = 1,
    ["payloadBytes"] = 841135,
    ["payloadBase64"] = {
        "eyJjYXB0dXJlRm9ybWF0VmVyc2lvbiI6MSwi...", -- [1]
        "...", -- [2]
    },
}
```

One file is handed over; nothing is copied out of chat. Base64 means no tooltip text -
quotes, backslashes, `|` markup, CR LF, non-ASCII - ever passes through Lua string
escaping. The importer reads this wrapper with a tokenizer and a fixed grammar: it accepts
exactly this shape, evaluates nothing, processes no escapes and rejects any other content.
There is no Lua interpreter in the importer and no `eval`.

### Capture format v1 (payload)

| field | meaning |
| --- | --- |
| `captureFormatVersion` | `1` |
| `game`, `clientBuild`, `locale` | identity of the client that answered |
| `manifestHash`, `datasets` | identity of the manifest and dataset the requests came from |
| `complete`, `expectedRecords` | completeness as decided by the addon |
| `capturedAt` | UTC timestamp. Diagnostic only |
| `diagnostics` | raw `GetBuildInfo` values, interface version, the capturing character's class token and level, pass and update-event counts. Diagnostic only |
| `records[]` | `classToken`, `treeId`, `nodeId`, `entryId`, `spellId`, `rank`, `status`, `reads`, `error`, `tooltip` |

`tooltip` is the raw `TooltipData` dump described above. The envelope and the record
identity are a closed contract: unknown fields, missing fields (including nullable ones)
and wrong wire types are rejected. `tooltip` is deliberately open - it is raw evidence, so
unknown fields are preserved verbatim, never dropped, never interpreted. The importer
requires only the minimum that makes it a tooltip: `lines` is a non-empty list of
objects, at least one line has non-empty `leftText`, and `type`, `id`, `dataInstanceID`,
`hasDynamicData`, and a line's `type`, `leftText`, `rightText`, `wrapText` have their
documented wire type when present.

## 5. Importer, audit artifact and semantic hash

```bash
python -m talent_data tooltips import-client --build 1.60.1.70009 \
  --saved-variables /path/to/TalentForgeTooltipCapture.lua
```

The importer validates and never repairs. Each rejection has its own exception type
(`talent_data.tooltips.errors`), so callers never parse messages:

| condition | type |
| --- | --- |
| not the addon's wrapper | `MalformedSavedVariablesError` |
| variable is `nil` | `NoStoredCaptureError` |
| bad base64, byte count, UTF-8, JSON (duplicate keys, NaN), or payload shape | `MalformedPayloadError` |
| unsupported wrapper or capture format version | `UnsupportedCaptureFormatError` |
| other game / build / locale | `GameMismatchError`, `ClientBuildMismatchError`, `LocaleMismatchError` |
| other manifest or dataset | `ManifestHashMismatchError`, `DatasetHashMismatchError` |
| not complete, or any record not final | `IncompleteCaptureError` (counts by status and class) |
| entry not in the manifest | `UnknownEntryError` |
| class / tree / node / spell differs from the manifest | `RecordIdentityError` |
| rank outside `1..maxRank` | `RankOutOfRangeError` |
| entry/rank captured twice / not captured | `DuplicateRecordError` / `MissingRecordError` |
| tooltip without the minimal shape | `MalformedTooltipError` |

On success it writes the **audit artifact**

```
artifacts/tooltips/forever/1.60.1.70009/enUS/client-capture.json
```

which is the decoded, validated raw capture (not the SavedVariables wrapper): the
identity, `semanticHash`, entry and record counts, the diagnostic fields, and every
record in class / entry / rank order with its raw tooltip.

`semanticHash` is the sha256 of the canonical JSON of the identity (`game`, `clientBuild`,
`locale`, `manifestHash`, `datasets`, format version) and, for every record in that order,
its identity and its tooltip. Explicitly excluded as non-semantic: `capturedAt`,
`diagnostics`, each record's `reads`, and each tooltip's top-level `dataInstanceID` (a
per-session handle the client issues to correlate updates; it is kept in the artifact).
Two captures of the same client, locale and manifest that received the same `TooltipData`
have the same hash, whenever and on whichever character they were taken; any difference
in text, colour, line structure or flags changes it. Reading the artifact back re-verifies
identity, coverage and the hash, so a hand-edited artifact is detected.

## 6. Analysis report

```bash
python -m talent_data tooltips report --build 1.60.1.70009
```

writes `client-capture-report.md` next to the artifact. It is observational - counts and
verbatim quotes - and answers: total entries and requests; `hasDynamicData`; the distinct
`TooltipDataLine` types; lines per tooltip and line-type sequences; which fields occur;
whether rendered lines differ between ranks; whether the name and rank are separate
lines; whether prerequisite / gate text, leftover `$` template tokens or colour markup
appear; how many reads each tooltip needed; and the full raw output of Improved Rend 1-3,
Improved Slam 1-2, Mortal Strike, Weaponmaster 1-5, one Mage talent, one Rogue talent and
the first Druid talent whose template has a conditional or nested tooltip.

## 7. Raw evidence is not the mobile contract

`client-capture.json` is an audit artifact. It is not `talent-tooltips.v1`, it is not
bundled into the app, and no mobile code reads it. M5.V2A intentionally defines no
user-facing tooltip fields: which line is the description, how ranks are presented, what
happens to markup and requirement lines are M5.V2B decisions, to be made from the report
of a real capture rather than from assumptions about line order. The mobile Inspector is
unchanged in this milestone and shows no `descriptionTemplate`, no `$` tokens and no
synthetic text.

## 8. Status of the evidence

| statement | status |
| --- | --- |
| The manifest equals the committed dataset (469 entries, 1333 pairs) | verified offline (tests, `--check`) |
| The addon loads and runs under Lua 5.1 and its output imports | verified offline against a stand-in client (`tests/unit/tooltips/`) |
| The importer rejects every listed failure | verified offline on synthetic captures |
| `C_TooltipInfo.GetTraitEntry(entryID, rank)` exists in Forever `1.60.1` and returns rendered lines | stated in the M5.V2A brief; **unverified in this repository** until a capture is imported |
| The `TooltipData` / `TooltipDataLine` field names (`lines`, `leftText`, …) | taken from the brief; **unverified** - which is why the dump is generic and the importer requires only a minimum |
| `TOOLTIP_DATA_UPDATE` exists and carries `dataInstanceID` in Forever | **unverified**; the addon also works without the event and records whether it could register it |
| The API answers for entries of classes other than the character's | **unverified**. If it does not, the capture is incomplete by class - stop and report, do not work around it |
| The rendered text is independent of the capturing character | **unverified, and doubtful for some entries**: two Druid templates choose their text by a client-side condition (`$?a5487\|a9634[…][…]`, conventionally an aura check) and three entries use named variables such as `$rap`. The capture records the character's class and level; compare the semantic hashes of captures from different characters |
| TOC `## Interface: 16001` | derived from the version number, **unverified** |

## 9. Tests

`tests/unit/tooltips/`:

- `test_manifest.py` - coverage, identity, ordering, hash, drift detection, the `--check`
  command, no hand-authored ids.
- `test_importer.py` - every rejection above on synthetic SavedVariables, strict wire
  types, verbatim round trip of quotes / Unicode / `|` / CR LF, semantic-hash rules, the
  artifact round trip, the CLI.
- `test_addon.py` - the real addon under Lua 5.1 (`lupa`, a test-only dependency) against
  `wow_client_harness.lua`: a full 1333-tooltip capture imported by the real importer,
  the self-checks, nil / changing / never-final answers, `TOOLTIP_DATA_UPDATE`, interrupted
  runs, `probe`, the encoders, and the list of globals the addon touches.
- `test_report.py` - the report on a synthetic capture.

The stand-in client is test code. Its tooltips are invented and prove only that the
tooling is consistent with itself; they say nothing about the real client. The game
client is never a CI dependency.
