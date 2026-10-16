# Build sharing: `talentforge-share.v1` (M5.SHARE.1)

A **share code** is the portable text form of one talent build. A player exports
a build on one device, sends the code through any messenger or the clipboard,
and imports the same allocation on another device — Android or iOS, any display
language, no network.

This document is the public specification of the wire format and of the import
validation that follows decoding. M5.SHARE.1 implements the shared domain layer
(`mobile/shared`, package `io.github.alexeykravtsov95.talentforge.share`);
M5.SHARE.2 adds the UI, the platform share sheets and the localized error
messages (section 9, and "Build sharing UI" in
[mobile-architecture.md](mobile-architecture.md)).

| | |
|---|---|
| Format name | `talentforge-share.v1` |
| Prefix | `TF1` |
| Alphabet | Crockford Base32 digits `0123456789ABCDEFGHJKMNPQRSTVWXYZ` (upper case only) |
| Integrity | CRC-32 (IEEE 802.3, the zlib/PNG checksum), big-endian, over `"TF1" ‖ payload` |
| Payload model | [`allocation-build.v1`](schema/allocation-build.v1.schema.json) (`BuildFile`) |
| Typical length | 58 characters (empty build) – 170 characters (largest full build of the bundled datasets) |
| Hard limits | 4096 raw input characters, 1024 code characters, 128 allocations |

TalentForge does not claim compatibility with any third-party calculator or
website format, and there is no TalentForge website or deep-link domain: a
share code is plain text and nothing else.

## 1. What a code carries

The payload is exactly the portable `allocation-build.v1` [`BuildFile`] — the
model saved builds already use — and nothing more:

| Carried | Never carried |
|---|---|
| `schemaVersion` of `allocation-build` (must be `1`) | local record id (`localId`) |
| `game` (e.g. `forever`) | build name, Draft state |
| `clientBuild`, exactly (e.g. `1.60.1.70009`) | `createdAt` / `updatedAt` / `lastOpenedAt` |
| `class` token (e.g. `DRUID`) | undo/redo history, selection, viewport |
| `treeId` | device identifiers, user information |
| every allocation: `nodeId`, selected `entryId`, `rank` | any other library metadata |

The library envelope (`LocalBuildRecord`) is app-private and stays on the
device. Display strings (class name, talent names) are not in the code: they
are resolved from the dataset on the importing device, in its own language.

## 2. Wire format

```
code       = "TF1" base32( payload ‖ crc32be( "TF1" ‖ payload ) )

payload    = varint(schemaVersion)          ; 1
             string(game)
             string(clientBuild)
             string(class)
             varint(treeId)
             varint(count)                  ; 0..128
             allocation × count             ; ascending nodeId

string     = varint(length) byte × length   ; 1..32 bytes of [A-Za-z0-9._-],
                                            ; first byte a letter or digit
allocation = varint(nodeDelta)              ; nodeId − previous nodeId (≥ 1); previous of the first is 0
             varint(zigzag(offsetDelta) << 3 | inlineRank)
             [ varint(rank − 8) ]           ; present iff inlineRank = 0
```

where, per allocation, `offset = entryId − nodeId` and
`offsetDelta = offset − previous offset` (previous of the first is 0), and
`inlineRank = rank` when `rank ≤ 7`, else `0` (escape; ranks 8..255 follow
explicitly).

- **varint**: unsigned LEB128 (7 bits per byte, least significant group first,
  high bit = "more follows"), **minimal length**, at most 8 bytes.
- **zigzag**: `(n << 1) ^ (n >> 63)` — maps 0, −1, 1, −2 … to 0, 1, 2, 3 …
- **base32**: RFC 4648 bit order (most significant bit first) over the
  Crockford digit set, no padding characters; the unused low bits of the last
  character are zero.
- **crc32be**: the 4 checksum bytes are appended to the payload before Base32,
  most significant byte first. The prefix is part of the checksummed data, so a
  body copied under another version prefix fails the checksum.

### Why this encoding

- Pure ASCII, case-exact, no `+`, `/`, `=`, `-` or spaces — survives
  messengers and double-click selection as one word; Crockford's set has no
  `I L O U`, so `1`/`I`/`l` and `0`/`O` cannot be confused in a code.
- No JSON, no compression library, no new dependency: a few dozen lines of
  common Kotlin, identical on JVM, Android and iOS (the codec uses no platform
  API and no locale-sensitive formatting).
- Small: node ids of one tree are close together and an entry id is usually
  its node id plus a constant, so a typical allocation costs 2 bytes. The
  identity strings dominate a short code; they are kept as exact strings on
  purpose (a client build is identity, never a number to normalize).
- Dataset-independent: decoding needs no dataset, so a code of a build the
  device does not ship still decodes and is rejected *by name*
  (`ClientBuildUnavailable`) instead of being misread.

### Canonical form

Encoding is deterministic: allocations are written in ascending `nodeId`
order whatever order the `BuildFile` lists them in, every varint is minimal,
padding bits are zero. Therefore one build has exactly one code, and the
decoder accepts only canonical codes — **every accepted code re-encodes to
itself** (a property test checks this on thousands of random and mutated
inputs). Decoding returns allocations in that canonical order, which is also
the order `BuildState` keeps them in.

The encoder never modifies its input and never normalizes invalid data: a
duplicate node, a duplicate entry, a rank outside 1..255, an id outside
1..2³¹−1, a malformed identity string or more than 128 allocations is a typed
`ShareCodeEncodeError`, not a silently "fixed" code.

## 3. Examples

All three are real codes of builds the app ships datasets for. The JVM test
`BuildImportBundledDatasetsTest.theDocumentedExamplesAreRealImports` imports
them through the real repository and engine; the Python test
`tests/unit/test_build_share_code_spec.py` decodes them with an independent
implementation and checks every node and rank against `data/normalized/`.

**Empty Mage build, `1.60.1.70374`** (58 characters):

```
TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1K6WT08KA18X2XG200AYM31R0
```

```json
{ "schemaVersion": 1, "game": "forever", "clientBuild": "1.60.1.70374",
  "class": "MAGE", "treeId": 1112, "allocations": [] }
```

**Warrior, 13 of 51 points, `1.60.1.70374`** (99 characters):

```
TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1K6WT0ENT1A994JKTJVM40HMNV0TH96601088G40820411008206N3QGF90ABEE1AA
```

```json
{ "schemaVersion": 1, "game": "forever", "clientBuild": "1.60.1.70374",
  "class": "WARRIOR", "treeId": 1117,
  "allocations": [
    { "nodeId": 105938, "entryId": 130668, "rank": 2 },
    { "nodeId": 105939, "entryId": 130669, "rank": 2 },
    { "nodeId": 105956, "entryId": 130686, "rank": 2 },
    { "nodeId": 105957, "entryId": 130687, "rank": 2 },
    { "nodeId": 105958, "entryId": 130688, "rank": 2 },
    { "nodeId": 105974, "entryId": 130704, "rank": 1 },
    { "nodeId": 105976, "entryId": 130706, "rank": 1 },
    { "nodeId": 113570, "entryId": 141192, "rank": 1 }
  ] }
```

Its 60 bytes before Base32:

```
01                                       schemaVersion 1
07 66 6f 72 65 76 65 72                  "forever"
0c 31 2e 36 30 2e 31 2e 37 30 33 37 34   "1.60.1.70374"
07 57 41 52 52 49 4f 52                  "WARRIOR"
dd 08                                    treeId 1117
08                                       8 allocations
d2 bb 06  a2 93 18                       node 105938; offset Δ +24730, rank 2
01  02                                   node +1;   offset Δ 0, rank 2
11  02                                   node +17;  offset Δ 0, rank 2
01  02 · 01  02                          node +1, +1; offset Δ 0, rank 2
10  01 · 02  01                          node +16, +2; offset Δ 0, rank 1
aa 3b  c1 e9 02                          node +7594 (113570); offset Δ +2892, rank 1
96 e7 05 4a                              CRC-32 of "TF1" ‖ the bytes above
```

**Full Druid build, `1.60.1.70009`, King of the Jungle 3/3** (138 characters) —
valid only on `1.60.1.70009`: from `1.60.1.70245` on, node 104951 is the 1-rank
"Shifting Power", so the same allocation on any later build is
`RANK_OUT_OF_BOUNDS`:

```
TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1G60WGAH2JAN4M9G882VDV61P3H8C020RE0M0GA083041020R1080G208204102081080G2082041G20G2080G2081041G20HYGMB54
```

```json
{ "schemaVersion": 1, "game": "forever", "clientBuild": "1.60.1.70009",
  "class": "DRUID", "treeId": 1089,
  "allocations": [
    { "nodeId": 104923, "entryId": 129583, "rank": 3 }, { "nodeId": 104924, "entryId": 129584, "rank": 3 },
    { "nodeId": 104938, "entryId": 129598, "rank": 5 }, { "nodeId": 104939, "entryId": 129599, "rank": 5 },
    { "nodeId": 104940, "entryId": 129600, "rank": 3 }, { "nodeId": 104941, "entryId": 129601, "rank": 2 },
    { "nodeId": 104942, "entryId": 129602, "rank": 3 }, { "nodeId": 104943, "entryId": 129603, "rank": 2 },
    { "nodeId": 104944, "entryId": 129604, "rank": 1 }, { "nodeId": 104945, "entryId": 129605, "rank": 2 },
    { "nodeId": 104946, "entryId": 129606, "rank": 2 }, { "nodeId": 104947, "entryId": 129607, "rank": 1 },
    { "nodeId": 104948, "entryId": 129608, "rank": 2 }, { "nodeId": 104949, "entryId": 129609, "rank": 1 },
    { "nodeId": 104950, "entryId": 129610, "rank": 2 }, { "nodeId": 104951, "entryId": 129611, "rank": 3 },
    { "nodeId": 104952, "entryId": 129612, "rank": 2 }, { "nodeId": 104954, "entryId": 129614, "rank": 2 },
    { "nodeId": 104955, "entryId": 129615, "rank": 1 }, { "nodeId": 104956, "entryId": 129616, "rank": 1 },
    { "nodeId": 104957, "entryId": 129617, "rank": 3 }, { "nodeId": 104958, "entryId": 129618, "rank": 2 }
  ] }
```

## 4. Decoding (structural, dataset-independent)

`BuildShareCode.decode(text)` returns `ShareCodeDecoding.Decoded(BuildFile)` or
`ShareCodeDecoding.Failed(ShareCodeDecodeError)`. It never throws, whatever the
input. In order:

| Check | Typed failure |
|---|---|
| raw input ≤ 4096 characters (checked before anything is scanned or copied) | `TooLong(length, 4096)` |
| leading/trailing space, tab, CR, LF are removed; nothing else is | — |
| something is left | `Empty` |
| code ≤ 1024 characters | `TooLong(length, 1024)` |
| starts with `TF` | `NotAShareCode` |
| at least `TF` + one version character | `Truncated` |
| version character is `1` (`TF2…`, `TF0…`, `TFZ…` are other versions) | `UnsupportedFormatVersion(char)` |
| every body character is in the alphabet (lower case is rejected) | `InvalidCharacter(index, char)` |
| the body is canonical Base32 (possible length, zero padding bits) | `InvalidEncoding` |
| more than the 4 checksum bytes | `Truncated` |
| CRC-32 matches | `ChecksumMismatch(stored, computed)` |
| every varint minimal and ≤ 8 bytes | `MalformedNumber` |
| no read past the end | `Truncated` |
| `schemaVersion` = 1 | `UnsupportedBuildSchemaVersion(actual, 1)` |
| identity strings present, 1..32 bytes, `[A-Za-z0-9._-]`, letter/digit first | `InvalidIdentity(field)` |
| `treeId`, `nodeId`, `entryId` in 1..2³¹−1; `rank` in 1..255 | `NumberOutOfRange(field, value)` |
| ≤ 128 allocations | `TooManyAllocations(count, 128)` |
| node ids strictly ascending (a zero delta is a repeated node) | `DuplicateNode(nodeId)` |
| no entry id used twice | `DuplicateEntry(entryId)` |
| nothing after the last allocation | `TrailingData(byteCount)` |

`ShareCodeViolation` (the last group) is shared by encoder and decoder: both
enforce exactly the same structural rules, so everything the encoder produces
decodes and nothing the decoder rejects can be produced.

CRC-32 detects every error burst of up to 32 bits — so every single-character
substitution and every swap of two adjacent characters — and all but about 1
in 4 billion random corruptions. It is an integrity check against
**accidental** damage, not authentication: anyone can compute a valid code for
any build, which is why decoding is followed by full validation (section 5).

## 5. Import preparation (dataset validation)

A valid share code is not automatically a valid talent build.
`prepareBuildImport(code, datasets)` is read-only and returns
`BuildImportPreparation.Ready(BuildImportPreview)` or
`BuildImportPreparation.Rejected(BuildImportFailure)`:

1. decode the code (section 4) — failure: `InvalidCode(decodeError)`; no
   dataset is touched;
2. resolve the code's **exact** `game / clientBuild / class` through
   `TalentDatasetRepository` (the bundled repository verifies manifest,
   identity and content hash):
   - no bundle for that game/build → `ClientBuildUnavailable(game, clientBuild)`;
   - bundle without that class → `ClassUnavailable(game, clientBuild, class)`;
   - any integrity or parsing failure of that exact dataset → `DatasetRejected(datasetLoadError)`;
3. the code's `treeId` must be a tree of that document → `TreeMismatch(buildTreeId, availableTreeIds)`;
4. the real `TalentAllocationEngine` must accept the dataset
   (→ `EngineUnavailable(reason)`) and **validate the allocation**
   (→ `InvalidAllocation(issues)` with the engine's typed issues and
   `reasonCodes`): unknown nodes (`UNKNOWN_NODE`) and entries
   (`INVALID_ENTRY`), invalid ranks (`RANK_OUT_OF_BOUNDS`), unmet
   prerequisites (`REQUIRED_/SUFFICIENT_PREREQUISITE_NOT_MET`), unmet tier gates
   (`TIER_GATE_NOT_MET`), overspending (`POINT_BUDGET_EXCEEDED`), unsupported
   mechanics (`UNSUPPORTED_*`, `UNRESOLVED_CONDITION`, `NODE_NEVER_PURCHASABLE`).
   Validity is reachability (purchasable rank by rank), exactly as when a saved
   build is reopened;
5. `Ready` carries a `BuildImportPreview`: game, client build, class token,
   class name (dataset data, not localized UI copy), tree id, spent points,
   point budget, and the verified portable `BuildFile`.

`prepareBuildImport` has no access to the build library: it performs no
storage write of any kind (tested with a recording storage that holds saved
builds before and after).

### Supported client-build policy

- A code is validated against **its own** client build, never another.
  `PRODUCT_BUNDLE` (the build new drafts are created on) is never substituted;
  a build is never migrated, re-mapped or "repaired". A code made on
  `1.60.1.70009` resolves against `1.60.1.70009`.
- The importable builds are exactly the bundled ones — today Forever
  `1.60.1.70009` (reference baseline), `1.60.1.70245`, `1.60.1.70338` and
  `1.60.1.70374` (product). A code of any other build (for example
  `1.60.1.69977`, which the repository keeps but the app does not ship) is
  rejected as `ClientBuildUnavailable`, typed, so the UI can say "made with a
  game version this app doesn't include".
- Identical talent data does not merge identities: `1.60.1.70338` and
  `1.60.1.70374` have the same rows for every class, yet the same allocation
  yields two different codes, each importing as its own build.
- When the app stops shipping a build, its codes stop importing (typed
  `ClientBuildUnavailable`); they never silently start meaning something on
  another build.

| Code made on | 70009 | 70245 | 70338 | 70374 | 69977 / other |
|---|---|---|---|---|---|
| Imports as | 70009 | 70245 | 70338 | 70374 | `ClientBuildUnavailable` |
| Validated against | 70009 | 70245 | 70338 | 70374 | — |

Data differences the tests pin: Druid 104951 (King of the Jungle, 3 ranks →
Shifting Power, 1 rank) and the new 113563 (Improved Shifting Power) between
70009 and 70245; Warrior Precision, Improved Cleave and Toughness removed and
Gore Drinker and Iron Will added between 70245 and 70338.

## 6. Security and input limits

- Input is untrusted text. The decoder bounds work before doing any: raw input
  over 4096 characters is refused without scanning, a code over 1024 too; the
  allocation count is checked (≤ 128) before any list is allocated; varints are
  limited to 8 bytes, so no arithmetic overflows; identity strings are limited
  to 32 bytes of a fixed ASCII set.
- Identity strings start with a letter or digit and cannot contain `/`, so a
  code can never turn the dataset lookup path into `.`/`..` segments.
- Nothing in a code is executed, fetched or used as a file name beyond that
  exact-identity dataset lookup; there is no network access, no reflection and
  no JSON parsing of untrusted input.
- A code is not private: it carries no personal data, but whoever has it can
  see the build. It is not signed; trust comes from validation, not from origin.

## 7. Versioning and compatibility

- The prefix names the share format version: `TF1` = `talentforge-share.v1`.
  Any change to the byte layout, the alphabet, the checksum or the canonical
  rules is a **new format version** (`TF2…`). v1 decoding never changes
  meaning: a v1 code made today decodes to the same build in every future
  version of the app.
- A v1 client receiving a `TF2…` code answers
  `UnsupportedFormatVersion('2')` (not "corrupted"), so the UI can say "update
  the app".
- The payload carries the `allocation-build` schema version (currently 1). A
  future `allocation-build.v2` needs its own share format version; v1 rejects
  anything but 1 (`UnsupportedBuildSchemaVersion`).
- Whether a code *imports* also depends on which datasets the app ships (see the
  client-build policy): format compatibility is permanent, dataset
  availability is not.
- The local library format, saved-build migration rules and talent datasets
  are untouched by this contract.

## 8. Implementation map

| Piece | Location |
|---|---|
| Codec, typed errors, limits | `mobile/shared/.../share/BuildShareCode.kt` |
| Base32, CRC-32, varint reader/writer | `mobile/shared/.../share/ShareCodePrimitives.kt` (internal) |
| Import preparation and preview | `mobile/shared/.../share/BuildImport.kt` |
| Codec tests, golden vectors (JVM + iOS simulator) | `mobile/shared/src/commonTest/.../share/BuildShareCodeTest.kt` |
| Import tests, synthetic datasets (JVM + iOS simulator) | `mobile/shared/src/commonTest/.../share/BuildImportTest.kt` |
| Import tests, every shipped build and class (JVM) | `mobile/shared/src/jvmTest/.../share/BuildImportBundledDatasetsTest.kt` |
| Independent reference + doc-example check (Python) | `tests/unit/test_build_share_code_spec.py` |

The golden vectors in `BuildShareCodeTest` are pinned from the independent
Python implementation, so the same text is required byte for byte on the JVM
and on Kotlin/Native (iOS).

## 9. UI integration (M5.SHARE.2)

- **Export from the editor**: ⋯ → *Share* encodes the editing session's
  current in-memory build (`buildFileOf` over the session's `BuildState`, bound
  to the record's game / client build / class) at the moment of the tap —
  never the persisted record, which a pending or failed autosave may lag
  behind, and never a cached code. Sharing changes no history and saves nothing.
- **Export from All Builds and Home**: the All Builds row ⋯ → *Share* and the
  Home *Recent* row ⋯ → *Share* read the latest persisted build of exactly
  that record with `LocalBuildRepository.load` (not `open`, which would touch
  `lastOpenedAt`): no timestamp, name or order changes.
- **Rename next to Share**: the same three places offer *Rename* (Home Recent
  ⋯: Rename · Share · Delete; editor ⋯: Rename · Share · Reset talents — never
  Delete; All Builds ⋯: Rename · Duplicate · Share · Delete). Delete is always
  confirmed first (the same dialog in Home and All Builds). One shared dialog and one write path
  (`BuildAutosaveCoordinator.rename` → `LocalBuildRepository.renameBuild`):
  the repository's trimming and 60-character rule, metadata only (no
  allocation, `updatedAt` or order change), serialized with autosave, and
  refused while the build's newest talent change failed to save. Renaming
  does not touch the share code: a code never carries a name.
- **Export surface**: the exact code (monospace, selectable), *Share* (Android
  `ACTION_SEND` chooser, iOS `UIActivityViewController`, desktop: copies) and
  *Copy code*. Only the canonical code is ever handed to the platform — no
  label, line break or decoration. Opening a chooser is not reported as a
  delivery; cancellation is silent; platform failures are typed messages.
- **Import**: *Import build* on Home and All Builds opens one screen. The
  field's exact text goes to `prepareBuildImport(text, datasets)`; every
  `BuildImportFailure` / `ShareCodeDecodeError` maps to one localized category
  (not a code, newer version, damaged, unshipped client build — named, class
  unavailable, invalid talents, dataset problem). A valid code opens a
  confirmation built from `BuildImportPreview` (localized class, `Forever ·
  talent data <exact client build>`, spent / budget points).
- **Confirm**: *Import* calls `LocalBuildRepository.createDraft(preview.build)`
  once; the new Draft opens in the editor (which re-validates it against the
  same exact dataset) and Back returns to Home or All Builds, wherever the
  import started. Nothing is written before the confirmation; editing the code
  invalidates the preview; a failed write keeps the preview for a retry.

[`BuildFile`]: ../mobile/shared/src/commonMain/kotlin/io/github/alexeykravtsov95/talentforge/data/allocation/BuildFile.kt
