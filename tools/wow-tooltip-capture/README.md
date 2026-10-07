# TalentForge tooltip capture (WoW addon)

A read-only development utility for the TalentForge data pipeline. It asks the exact
Forever client for the rendered tooltip of every talent entry and rank
(`C_TooltipInfo.GetTraitEntry(entryID, rank)`) and stores the raw answers in one
SavedVariables file that `python -m talent_data tooltips import-client` validates.

It is not part of the mobile app and not part of any other addon. Background, format and
rules: [docs/research/forever-client-tooltip-capture.md](../../docs/research/forever-client-tooltip-capture.md).

**It never changes your character.** It does not purchase, refund, reset, stage or
activate anything; your class, level and current talents do not need to be anything in
particular, and no talent reset is needed.

## Requirements

- Forever client build **1.60.1.70009**, exactly. Any other build refuses to capture.
- Client language **English (enUS)**. Any other locale refuses to capture: the TalentForge
  dataset is English and localized text is never mixed into it.

## Capture (macOS)

`WOW` below is the Forever client folder inside your World of Warcraft installation - the
one that contains `Interface/` and `WTF/` (for the `wow_classic_beta` product this is
expected to be `_classic_beta_`; check what is actually on disk).

1. Copy the addon folder into the client:

   ```bash
   WOW="/Applications/World of Warcraft/_classic_beta_"
   cp -R tools/wow-tooltip-capture/TalentForgeTooltipCapture "$WOW/Interface/AddOns/"
   ```

2. Start the client (or `/reload`) and log in with any character. If the AddOns list shows
   the addon as out of date, tick **Load out of date AddOns** - see the note below.
3. `/tftooltips status` - must end with `ready: /tftooltips capture`.
4. `/tftooltips capture` - leave the game alone for the few seconds it takes.
5. Wait for:

   ```text
   TalentForge tooltips: 1333 / 1333
   TalentForge tooltips: TalentForge tooltip capture complete.
   TalentForge tooltips: Reload or exit the game to flush SavedVariables.
   ```

6. `/reload` (or exit the game). SavedVariables are only written to disk then.
7. The capture is this one file:

   ```text
   $WOW/WTF/Account/<ACCOUNT>/SavedVariables/TalentForgeTooltipCapture.lua
   ```

8. Import it from the repository root:

   ```bash
   python -m talent_data tooltips import-client --build 1.60.1.70009 \
     --saved-variables "$WOW/WTF/Account/<ACCOUNT>/SavedVariables/TalentForgeTooltipCapture.lua"
   python -m talent_data tooltips report --build 1.60.1.70009
   ```

Recommended before the full capture - one tooltip, three ranks, printed to chat:

```text
/tftooltips probe 130686 1
/tftooltips probe 130686 2
/tftooltips probe 130686 3
```

(`130686` is Warrior · Improved Rend, max rank 3.) The three outputs should differ in the
rendered value. If they do not, or if the probe prints `returned nil` on a character of
another class, stop and report that instead of working around it.

## Commands

| command | effect |
| --- | --- |
| `/tftooltips status` | client build, locale, manifest, stored record, readiness. Requests nothing. |
| `/tftooltips capture` | captures all 1333 entry/rank tooltips of the 469 talent entries |
| `/tftooltips clear` | stops a running capture and removes the stored record |
| `/tftooltips probe <entryId> <rank>` | prints one tooltip to chat. Stores nothing. |

## When it does not complete

`capture INCOMPLETE` means at least one tooltip never became final (the client returned
`nil`, a tooltip without text, or data that kept changing). The addon prints how many, by
status and by class. The stored record is marked incomplete and the importer rejects it -
it can still be handed over as a diagnostic, because it contains what the client did
return. Running `/tftooltips capture` again is always safe; a capture interrupted by a
logout leaves a record without payload that the importer also rejects.

## Notes

- `## Interface: 16001` in the `.toc` is derived from the version `1.60.1`; it has not been
  confirmed against the client. `/tftooltips status` prints the client's real interface
  number - if it differs, report it so the `.toc` can be corrected.
- `GeneratedTalentManifest.lua` is generated from `data/normalized/forever/1.60.1.70009/`.
  Never edit it; regenerate with
  `python -m talent_data tooltips manifest --build 1.60.1.70009` (`--check` verifies it).
  A capture taken with an outdated manifest is rejected by the importer.
- Capturing twice and importing both should print the same `semantic hash`. A different
  hash means the client's answers are not stable (or depend on the character) - report it.
