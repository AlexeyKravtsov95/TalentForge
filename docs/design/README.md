# Design source of truth

This directory holds the authoritative design sources for the TalentForge client.

| File | Scope |
|---|---|
| `TalentForge_TalentTree_B3.pdf` | Talent Tree (B3) behaviour and interaction spec |
| `TalentForge_UX_UI_Spec_v1.pdf` | Overall UX/UI specification, design system (section G) |

## Precedence rule

When the two documents conflict:

1. **`TalentForge_UX_UI_Spec_v1.pdf`, pages 2–3** (binding rules) is authoritative.
2. Otherwise **`TalentForge_TalentTree_B3.pdf`** is authoritative for Talent Tree
   behaviour and interaction.

The PDFs are binary design sources committed here verbatim; they are never edited.
Machine-readable derivatives (for example the colour/spacing/radius/motion tokens in
`mobile/composeApp/src/commonMain/kotlin/io/github/alexeykravtsov95/talentforge/ui/theme/`)
cite the section and OKLCH values they were derived from, and a test pins every
colour constant back to its spec value.

## Recorded refinements

Narrow behaviour decisions on points the PDFs leave open. They do not override
either document or the precedence rule above; every other B3 rule keeps its
priority.

- **Talent inspector — reading in sequence (portrait).** The first selection
  opens the compact inspector, as in B3 03. Once the reader expands it, selecting
  another talent keeps the sheet expanded and shows that talent's description
  from its top; a collapse by the reader is kept by the next selection. Rank
  changes, undo and redo never collapse it; no selection hides it; Show still
  returns to the compact inspector (B3 07). Tapping a node still only selects.
  The choice is view state only and is not stored between launches. Landscape
  keeps the B3 05 side panel and the tablet its persistent panel. Implementation
  notes: `docs/mobile-architecture.md`, "M5.UX.2a adaptive inspector and header".
