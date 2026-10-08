# Talent descriptions (M5.V2): sources, rules and evidence

TalentForge shows each talent's description at every rank. The text is rendered **at
build time** from the client tooltip template and the DB2 tables of the **same pinned
build**, validated, and bundled offline as a sidecar contract. There is no client
capture step, no addon, no runtime network and no hand-written text.

**Build scope.** The renderer is build-generic (`--build` takes any exact client build).
Its first canonical output — the one committed and bundled — is for the project's
**verified reference baseline** `forever 1.60.1.70009`, the build the allocation evidence,
conformance vectors, dataset and icons are pinned to. That build is not claimed to be the
current Forever build; publishing descriptions for a newer build belongs to the M5.DATA
data refresh. The table and column facts below are observed on the reference baseline.

```
exact Forever build
  -> DB2 tables (wago.tools CSV, build-pinned cache)
  -> talent-data.v1 descriptionTemplate + effects[].perRank   (committed dataset)
  -> parse template (closed grammar, no eval)
  -> evaluate every token from the same build's rows
  -> per-rank segments: resolved / partial (explicit gaps) / unresolved
  -> coverage report (every entry, rank, token, gap)
  -> data/tooltips/<game>/<build>/<class>.json  (talent-tooltips.v1)
  -> mirrored byte-for-byte into the Compose resources
  -> Talent Inspector
```

```bash
# verified reference baseline
python -m talent_data tooltips fetch --build 1.60.1.70009                # online, on demand
python -m talent_data tooltips normalize --build 1.60.1.70009 --offline  # sidecars + report
python -m talent_data tooltips check --build 1.60.1.70009                # offline, CI
python scripts/sync_mobile_tooltips.py --check                           # bundle == canonical
```

The coverage report is `artifacts/tooltips/forever-1.60.1.70009-tooltip-coverage.json`,
summarised (with every gap) in
[`research/forever-1.60.1.70009-tooltip-coverage.md`](research/forever-1.60.1.70009-tooltip-coverage.md).
Result for the reference baseline 1.60.1.70009: **469 entries, 1333 rank descriptions: 1290 resolved
(96.77 %), 43 partial, 0 unresolved.**

## Contract: `talent-tooltips.v1`

One closed document per class and build (`docs/schema/talent-tooltips.v1.schema.json`),
next to - never inside - `talent-data.v1`:

* identity: `game`, `clientBuild`, `classToken`, and `talentDataContentHash` - the
  sidecar is only valid next to exactly that dataset revision;
* `entries[]` ordered by `entryId`, each with `definitionId`, `spellId`, `maxRank` and
  exactly ranks `1..maxRank`;
* each rank: `status` (`resolved` / `partial` / `unresolved`), `segments[]` and `gaps[]`;
* a segment is `text` (literal template text; `\n` line breaks), `value` (one evaluated
  number, duration or plural word) or `gap` (empty text, a construct that is not shown);
* a gap explains itself: `category` (inventory category), `classification`
  (`contextDependent` / `unsupported` / `missingData`), the template `fragment`, the
  root-cause `token`, and a `reason`;
* `contentHash` over everything except `contentHash`/`provenance`; no timestamp, so
  regeneration is byte-identical.

Every field is required; strict parsing in Python (pydantic, `strict`, `extra="forbid"`)
and Kotlin (`ignoreUnknownKeys = false`, numeric wire-form check, closed enums). Rules the
schema cannot express are enforced by both readers: a text/value segment is never empty
and never contains `$`, `|` or `\r`; `resolved` has no gaps; `partial` has one gap entry
per gap segment and some text; `unresolved` has no segments.

**Accuracy policy: no raw template is ever presented as resolved.** A rank is `resolved`
only when every construct was evaluated from the build's data under a rule recorded below.

## Value sources (1.60.1.70009 tables and columns)

Columns are the exact columns of the wago.tools exports of 1.60.1.70009 (pinned by
`tests/unit/tooltips/test_db2_tables.py`). Only `DifficultyID = 0` rows are read (the rule
the normalizer already uses for talents); several default rows for one key are refused.

| token | source |
| --- | --- |
| `$sN` `$mN` `$SN` (own spell) | `talent-data.v1` `effects[index = N-1].perRank` at the rank (the existing, proven rank-curve values - no second algorithm); `basePoints` when the effect has no rank curve (`perRankStatus: none`); `incomplete`/`unresolved` -> gap |
| `$<spell>sN` `$<spell>mN` | `SpellEffect.EffectBasePointsF` of that spell, `EffectIndex = N-1` |
| `$oN` | effect value x (`SpellDuration.Duration` / `SpellEffect.EffectAuraPeriod`); a duration that is not a whole number of ticks -> gap |
| `$aN` `$<spell>aN` | `SpellEffect.EffectRadiusIndex_0` -> `SpellRadius.Radius` (only when `EffectRadiusIndex_1` is 0 or equal, `RadiusPerLevel = 0`, `Radius = RadiusMax`, `RadiusMin = 0`) |
| `$tN` `$<spell>tN` | `SpellEffect.EffectAuraPeriod` / 1000 |
| `$d` `$<spell>d` | `SpellMisc.DurationIndex` -> `SpellDuration.Duration` (only when `Duration = MaxDuration`, `DurationPerResource = 0`, `Duration > 0`) |
| `$h` | `SpellAuraOptions.ProcChance` (1..100; not for `SpellProcsPerMinuteID != 0`) |
| `$n` `$<spell>n` | `SpellAuraOptions.ProcCharges` (> 0) |
| `$u` `$<spell>u` | `SpellAuraOptions.CumulativeAura` (> 0) |
| `$@spelltooltip<spell>` | `Spell.Description_lang` of that spell, rendered recursively in its own context |
| duration units | `GlobalStrings` of the same build: `INT_SPELL_DURATION_SEC` `%d sec`, `INT_SPELL_DURATION_MIN` `%d min`, `INT_SPELL_DURATION_HOURS` `%d \|4hour:hrs;`, `SPELL_DURATION_SEC` `%.1f sec` |
| `$<name>` (classification only) | `SpellXDescriptionVariables` -> `SpellDescriptionVariables.Variables` |

New typed records (`src/talent_data/db2/tables.py`, `TOOLTIP_TABLES`, never part of
`TRAIT_TABLES`): `SpellEffectValues` (a second, wider view of `SpellEffect`; the allocation
view is unchanged), `SpellMiscDuration`, `SpellDuration`, `SpellRadius`,
`SpellAuraOptions`, `SpellXDescriptionVariables`, `SpellDescriptionVariables`,
`GlobalString`. `Spell` is read for descriptions; its record is unchanged.

Every referenced spell is looked up in the same build's tables. A spell or row that does
not exist is a `missingData` gap - never a value from another build, difficulty or
default.

## Evidence for the rules

What is **proven from the build's data**: which row and column each value comes from (the
joins above resolve uniquely in 1.60.1.70009), and the integrity checks (the dataset's
templates equal `Spell.Description_lang` of the same tables, and dataset `basePoints`
equal `SpellEffect` - a mismatch stops the pipeline). The client's C++ tooltip formatter
itself is not available, so the *display* rules are conventions supported by evidence
inside the build. Each one is listed with that evidence; anything weaker is a gap.

**Effect index (`$sN` = `EffectIndex N-1`).** Improved Slam: `$m1`/`$m3` with rank curves
on indices 0 and 2 (-250/-500 and -1500/-3000 ms) while `EffectIndex 3` holds a spell id
(1310196) - a one-based reading would print a spell id as seconds. Anger Management:
`$m2 Rage every $m3 sec … by $s4%` reads indices 1/2/3 (1, 3, 30); a one-based reading
needs an `EffectIndex 4` the spell does not have. Weaponmaster's three per-rank values
(1/3/1 per rank) land on its three lines. `talent-data.v1` already documents
"`$s1` in tooltips is index 0".

**Stand-alone values are written without sign.** All 113 negative stand-alone `$s`/`$m`/
`$/N;` occurrences of the 469 templates sit in "reduces … by", "decreases", "lowers",
"less", "reduced" contexts (Iron Will "Reduces the duration … by $s1%" with -3..-15;
Demonic Brand "generates $s1% less threat" with -17..-50) - a signed reading would print
"reduces by -3 %". Inside `${…}` values stay signed: authors divide by negative constants
(`${$m1/-1000}`, `${$m2/-10}`), which only makes sense on signed values. A negative
*expression* result is refused (gap).

**`$S` = `$s`.** `$/1000;S1` and `$/1000;s1` are used in identical sentences ("Reduces the
casting time … by $/1000;S1 sec" / "Reduces the cooldown … by $/1000;s1 sec").

**Proc values.** Every `$h`/`$n`/`$u` occurrence matches the talent's well-known value:
Redoubt 10 % chance / 5 blocks, Holy Shield 4 charges, Flurry 3 swings, Sweeping Strikes
5 attacks, Vengeance 3 stacks, Shadow Weaving / Improved Scorch 5 stacks, Elemental Focus
10 %.

**Numbers.** Values are exact decimals (`Fraction` of the source text, never binary
floats). A bare value prints as an integer, or as the shortest exact decimal with at most
two places (`$/1000;S1` with -100 -> `0.1`; ranks 1-4 of such talents would read "0 sec"
otherwise). Anything longer (a float32 artefact, a third) is a gap. `${…}.N` prints exactly
N decimals (printf semantics: `${$m1/-1000}.2` -> `0.25`, `0.50`); a value exactly between
two N-decimal numbers is a gap, because the client's tie rounding is not proven. `${…}`
without a suffix must be integral, otherwise it is a gap (Mutilate: 17.25).

**Durations.** The largest exact unit of the build's own duration strings: whole hours
`2 hrs`, whole minutes `30 min`, whole seconds `10 sec`, tenths `1.5 sec`. The sec/min/hour
thresholds are a convention (e.g. 60 000 ms -> `1 min`), not client-verified.

**Plural.** `$lsingular:plural;` (and `$L…`, written interchangeably for the same
"level:levels" construct in Feral Instinct/Master of Deception vs Improved Distract) takes
the singular exactly when the **preceding evaluated number in the same template** is 1:
"refunds $m2 Combo $LPoint:Points;" -> "refunds 1 Combo Point" / "2 Combo Points". The
words come verbatim from the template; no English grammar is applied. No preceding number,
or an unresolved one -> gap.

**Colour and line breaks.** `|cAARRGGBB` / `|r` are dropped: the colours are white
emphasis on Weaponmaster/Hack and Slash weapon labels and the white/red "Requires … Form"
line of two Druid talents (red = requirement not met - character state). `\r\n` becomes
`\n` and is preserved exactly - including the in-game hard wraps of Weaponmaster and Hack
and Slash, which cannot be told apart from intended lists (Venom) without a heuristic.
Runs of spaces collapse, spaces around line breaks and at the ends are removed - whitespace
only, never words or values.

**Conditionals (`$?a5487|a9634[…][…]`).** The condition is client state (the character's
form), which TalentForge does not have and never assumes. When every branch renders to the
same text, that text is shown and no branch is chosen: Feral Charge's two branches are both
"Requires Bear Form, Dire Bear Form" and differ only in colour. Berserk's branches differ
("Reuires …" / "Requires …"), so its conditional is a `contextDependent` gap.

**Nested tooltips.** `$@spelltooltip414924` (Feral Charge) inlines spell 414924's
description, rendered in spell 414924's own context, from the same build. Cycles and
nesting deeper than 3 levels fail closed; any gap inside a nested tooltip makes the whole
nested tooltip one gap.

## Values that are deliberately not shown (gaps)

**Character-dependent amounts** (`contextDependent`). An effect value is refused when the
client scales it by the caster or a roll: `EffectRealPointsPerLevel != 0` (level),
`ScalingClass`/`Coefficient` (level scaling), `BonusCoefficientFromAP != 0` (attack power),
`EffectPointsPerResource`/`ResourceCoefficient` (combo points), and a non-zero
`EffectBonusCoefficient` (spell power) on a damage/healing/absorb amount (`Effect` 2
SCHOOL_DAMAGE, 10 HEAL; aura 3 PERIODIC_DAMAGE, 8 PERIODIC_HEAL, 53 PERIODIC_LEECH, 69
SCHOOL_ABSORB - WoWDBDefs/TrinityCore enums) or any fractional coefficient. A coefficient
of exactly 1 on a non-amount effect is accepted: 320 of the 677 effect-value references
of the templates carry it, almost all on aura modifiers (e.g. Deflection "+1 % parry"),
where a spell-power share would make every such tooltip meaningless. `Variance != 0` (a rolled range) is `unsupported`. Non-neutral
group-size/PvP/chain multipliers are refused.

**Named variables** (`$rap`, `$bh`, `$bc`, `$proccooldown`; `unsupported`). They have no
source in the build's tables. `$proccooldown` (Paladin Shield Specialization) has a
plausible candidate, `SpellAuraOptions.ProcCategoryRecovery` = 3000 ms, but nothing in the
build proves the client substitutes that column, so it is reported, not used.

**Description variables** (`$<mult>`, `$<minDam>`, `$<maxDam>`; `contextDependent`). Their
definitions (`SpellDescriptionVariables` 1015, 1016) read the caster's level (`$pl`), spell
power (`$SPS`, `$SPFI`) and known-spell/aura conditions (`$?s…`, `$?a…`).

**Other `unsupported` cases.** `$d` inside an expression (Mana Tide Totem `${$d-1}`:
seconds or milliseconds is not proven); a radius only in `EffectRadiusIndex_1` (Holy Nova
`$a1`, Wild Growth `$a1`); a bare `$t`/`$m` is accepted only when the spell has exactly one
candidate effect and it is index 0 (Wrack `$t`, Survivalist's Discipline `$m`).

**`missingData`.** Riposte `$19718d` (spell 19718 has no `SpellMisc` row in this build);
Resourcefulness `$m3` (a rank curve for effect 2, but the spell has no `EffectIndex 2`).

**Unknown syntax** (anything outside the grammar) makes the whole rank `unresolved` with an
`unclassified` gap: the extent of an unknown construct is unknown, so the text around it is
not trusted either. 1.60.1.70009 has none.

## Mobile

`mobile/shared` decodes the sidecar strictly (`TalentTooltipsDocument`) and
`BundledTalentTooltipsRepository` verifies game, client build, class token, dataset
`contentHash`, every entry (no unknown, missing or duplicate entry; definition/spell/
maxRank equal to the dataset), ranks exactly `1..maxRank`, status consistency and the
no-markup rule, with typed `TooltipsLoadError`s. The app loads the descriptions with the
icons when an editor session opens; a failure never affects allocation or whether a build
opens - the inspector then shows "Talent description unavailable for this client data."
Layout and behaviour: [mobile-architecture.md](mobile-architecture.md#talent-descriptions-m5v2).
