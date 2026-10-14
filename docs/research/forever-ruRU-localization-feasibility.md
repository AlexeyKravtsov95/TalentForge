# Russian talent text for Forever: source feasibility (M5.REL.3a)

**Question.** Can authentic Russian talent names and descriptions be obtained for the exact
Forever client build the product uses (`1.60.1.70338`), without translating anything
ourselves?

**Answer.** Yes, for names and for most descriptions. wago.tools serves the client's own
`ruRU` strings for the exact build through the same DB2 CSV endpoint the pipeline already uses.
Three properties of the source must be handled explicitly, and the pipeline does: an
unsupported locale value silently returns English, untranslated rows come back as English
text, and a localized export can lack a row. Some Russian description constructs (three-form
plurals, decimal values) cannot be rendered with recorded evidence yet; those ranks fall back
to English.

All probes ran against the live service on **2026-10-14**. Mechanical facts are pinned by
`tests/unit/localization/` (offline, real rows) and `tests/integration/test_wago_localization_live.py`
(live, `pytest -m integration`).

## 1. Endpoint and locale behaviour

`GET https://wago.tools/db2/<Table>/csv?build=<build>&locale=<locale>`

Probe: `SpellName`, build `1.60.1.70338` (31 863 rows in every answer below):

| Query | HTTP | Bytes | sha256 (prefix) | Rows with Cyrillic | Reading |
| --- | --- | --- | --- | --- | --- |
| `build=1.60.1.70338` | 200 | 835 757 | `679a769bcfa4` | 0 | default = enUS |
| `…&locale=enUS` | 200 | 835 757 | `679a769bcfa4` | 0 | enUS |
| `…&locale=ruRU` | 200 | 1 438 928 | `b8658c2e4b93` | 29 302 | **Russian** |
| `…&locale=ruru` | 200 | 835 757 | `679a769bcfa4` | 0 | **enUS, silently** |
| `…&locale=xxXX` | 200 | 835 757 | `679a769bcfa4` | 0 | **enUS, silently** |
| `…&locale=deDE` | 200 | 909 637 | `59560770fb98` | 0 | German |
| `build=9.9.9.99999&locale=ruRU` | 400 | 1 890 (HTML) | — | — | unknown build |

Consequences, implemented in `talent_data.localization.source`:

* **HTTP 200 proves nothing.** The locale value is case-sensitive and an unknown value returns
  the enUS export byte for byte. Only `ruRU` is accepted (`require_supported_locale`), and every
  localized table is compared with the enUS export of the same build
  (`verify_localized_table`): same columns, every localized row present in enUS, every non-text
  column equal row by row, and — for `SpellName`, `Spell`, `GlobalStrings` — at least one row
  with different, Cyrillic text. The enUS body served for `ruru` fails as `localeNotApplied`.
* Cache entries live in `.cache/wago/forever/<build>/locale/ruRU/` and record the exact
  build- and locale-pinned URL; a file recorded for another URL is never reused.

## 2. Build pinning

The `build` parameter is honoured together with `locale`:

| Export | 1.60.1.70009 | 1.60.1.70245 | 1.60.1.70338 |
| --- | --- | --- | --- |
| `SpellName` / `Spell` rows enUS | 31 716 | 31 739 | 31 863 |
| `SpellName` / `Spell` rows ruRU | 31 716 | **31 738** | 31 863 |
| ruRU has `1323963` Furious Precision (new in the 70338 Warrior rework) | no | no | yes |

* Each ruRU export has the row set of the enUS export of **its own** build — except 70245,
  where `1318286` ("Summon Azurky", a pet summon, not a talent) exists only in enUS. So a
  localized export *can* lack a row: the generator reports such a text as `missing` and never
  substitutes another build or locale.
* **No stale translations.** For every spell the committed datasets use: wherever the English
  description or name changed between two builds, the Russian one changed too
  (70009 → 70245: 27 descriptions and 2 names changed in enUS, all 29 changed in ruRU;
  70245 → 70338: 12 and 1, all changed in ruRU). Several "changes" are the Russian string
  becoming the new English one (untranslated), never an old Russian text left behind.

**Limit.** The text-only tables (`SpellName`, `Spell`: `ID` + `_lang` columns) cannot prove
by themselves that wago.tools read the ruRU files of exactly this build; the evidence is
indirect (row sets per build, the Forever-only rows, the change tracking above, and the
non-text columns of `TraitDefinition`/`ChrClasses`/`GlobalStrings` being identical to enUS).
This is the same trust we already place in wago.tools for enUS.

## 3. Tables and what they contain (1.60.1.70338, whole exports)

`python -m talent_data localization report --build 1.60.1.70338 --locale ruRU`:

| Table | Column(s) used | Rows | Translated | Identical to enUS | Missing in ruRU |
| --- | --- | --- | --- | --- | --- |
| `SpellName` | `Name_lang` — talent names (dataset rule) | 31 863 | 29 302 | 2 560 | 0 |
| `Spell` | `Description_lang` — templates, also nested | 31 863 | 19 553 | 12 302 | 0 |
| `TraitDefinition` | `OverrideName_lang`, `OverrideDescription_lang` | 658 | 0 | 658 (byte-identical file) | 0 |
| `GlobalStrings` | duration unit formats | 27 372 | 25 428 | 1 751 | 0 |

* `TraitDefinition` carries no translatable text in this build: one row (134246) has a numeric
  override `16972` in both locales, and no talent entry of the dataset uses an override. The
  generator still applies the override rule (an override equal to English is `untranslated`).
* `GlobalStrings` ruRU duration formats: `INT_SPELL_DURATION_SEC` = `%d сек`,
  `INT_SPELL_DURATION_MIN` = `%d мин`, `INT_SPELL_DURATION_HOURS` = `%d ч`,
  `SPELL_DURATION_SEC` = `%.1f сек` — all in the shapes the existing renderer accepts.
* `GlobalStrings.DECIMAL_SEPERATOR` (sic) is `.` in enUS and **`,` in ruRU**. Which separator
  the client's tooltip formatter uses for `$`-values in ruRU is not recorded evidence.
* `ChrClasses.Name_lang` ruRU — Воин, Паладин, Охотник, Разбойник, Жрец, Шаман, Маг,
  Чернокнижник, Друид — equals the app's hand-written `values-ru` class names (M5.REL.2a) for
  all nine classes.

## 4. Distinguishing authentic Russian from fallback

| Case | How it shows in the export | Overlay status |
| --- | --- | --- |
| Authentic Russian from the pinned build | text differs from enUS and contains Cyrillic | `translated` / candidate for `rendered` |
| English served as fallback | text identical to enUS (untranslated client row, or a source fallback — indistinguishable) | `untranslated`, English shown |
| Missing / unsupported | row absent, or empty string | `missing`, English shown |
| Other client version | not detectable from text alone; prevented by build-pinned URLs and cache identity, the per-build row sets and the change tracking above | — |
| Unsupported locale value | whole export identical to enUS | refused (`localeNotApplied`) |
| Different and not Cyrillic | never observed | generation **stops** (investigate, never publish) |

The ten talent names served in English for ruRU are exactly the talents introduced or renamed
by Forever: Warrior Furious Precision, Lingering Rage, Gore Drinker; Mage Heating Up; Warlock
Soul Harvest; Druid Blood Frenzy, Primal Bite, Natural Instinct, Shifting Power, Improved
Shifting Power. 41 descriptions are untranslated for the same reason (Forever reworked their
text, the Russian string is the new English one) — e.g. Warrior Dual Wield Specialization,
Blood Craze, Booming Voice.

## 5. Description templates in Russian

* Russian templates use the **same value constructs** as English (`$s1`, `$/10;s1`,
  `${…}`, `$@spelltooltip…`, `$?a…[…][…]`) in a different word order: the Flurry template
  moves `$m1%` to the front. A value-construct multiset comparison
  (`value_signature`) found no mismatch among the entries that reach that gate.
* Russian templates select plural forms with **three** words: 596 three-form and 52 two-form
  `$l…;` constructs in the whole `Spell` export (`$lудар:удара:ударов;`,
  `$lмагический эффект:магических эффекта:магических эффектов;`, with spaces inside a form).
  The English renderer knows two forms and a space-free singular; applying it would print
  `удара:ударов` or refuse the syntax. The forms are ordered one/few/many, but the client's
  selection rule is not recorded evidence → `pluralRuleUnverified` (12 entries), English shown.
* Decimal values (`0.5`, `1.5 сек`) → `decimalFormatUnverified` (25 entries): see the separator
  finding above.

## 6. Coverage of the product build

[`forever-1.60.1.70338-ruRU-coverage.md`](forever-1.60.1.70338-ruRU-coverage.md) (generated):

* **Names: 459 / 469 translated (97.9 %)**, 10 untranslated, 0 missing.
* **Descriptions: 355 / 469 entries rendered, 1 058 / 1 329 ranks** — the remaining entries fall
  back to English: 41 untranslated, 33 English not fully resolved, 25 decimal values,
  12 plural forms, 3 missing (Russian text empty), 0 structure mismatches, 0 render gaps.
* Every rendered rank shows exactly the numbers of the English sidecar (checked rank by rank).

## 7. Alternatives considered

Only needed for what wago.tools cannot give:

* **A ruRU client of the exact build** (CASC `dbfilesclient/*.db2` of the ruRU locale) would
  prove the build identity of the strings directly and is the trustworthy way to observe the
  tooltip formatter (plural selection, decimal separator) in game. It needs a Forever client
  install or a CASC reader; not available in this repository.
* **Live in-game observation** (screenshots of ruRU tooltips for chosen ranks) can promote the
  plural and decimal rules to recorded evidence, the way `talent-allocation-semantics.md` §15
  recorded live allocation evidence.
* **Third-party databases/calculators** (wowhead-style ruRU pages) are not build-pinned for
  Forever and are not acceptable as a source; at most as a hint for which ranks to observe.
* **Machine or manual translation** of the ten untranslated names / 41 descriptions: rejected.
  The client itself shows English for them; inventing Russian would be presenting
  non-game text as game data.
