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
