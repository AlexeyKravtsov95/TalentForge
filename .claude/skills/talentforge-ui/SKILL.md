---
name: talentforge-ui
description: Use when designing, implementing, reviewing, or refactoring TalentForge Compose Multiplatform UI, design tokens, responsive layouts, Talent Tree canvas/nodes/connections/inspector, Home, New Build, Saved Builds, dialogs, navigation, accessibility, or interaction behaviour. Treat the committed UX/UI PDFs as authoritative and preserve the frozen B3 Talent Tree contract.
---

# TalentForge UI

Use this skill for production UI and design-system work.

## 1. Read the design sources

Before changing product UI, consult:

- `docs/design/TalentForge_UX_UI_Spec_v1.pdf`
- `docs/design/TalentForge_TalentTree_B3.pdf`
- `docs/design/README.md`

Precedence:

1. UX/UI Specification V1 pages 2–3 is authoritative when the PDFs conflict.
2. Otherwise B3 is authoritative for Talent Tree behaviour and interaction.

Do not redesign a frozen rule while implementing it.

If platform constraints make the spec impossible, report the conflict and propose the smallest spec adjustment rather than silently substituting a different UX.

## 2. Frozen Talent Tree interaction contract

Unless the task explicitly revises the specification:

- tap a TalentNode = select only;
- tap never spends a point;
- tap empty canvas = deselect;
- drag canvas = pan;
- pinch = zoom;
- spend/refund use explicit visible controls;
- critical operations must not depend only on hidden gestures;
- `Show` navigates to a prerequisite and never spends;
- `Show` advances one prerequisite hop at a time;
- selected/focus state is independent of purchased/maxed/locked/no-points state;
- Undo/Redo remains reachable;
- global point counter and per-spec totals keep distinct meanings.

Do not introduce tap-to-spend or floating +/- controls attached to nodes.

## 3. Gesture arbitration

Dense nodes must not create pan dead zones:

- pointer down on node + movement below touch slop -> select node;
- movement beyond touch slop -> cancel node tap and pan canvas.

Do not require long press or double tap for a critical action.

## 4. Inspector behaviour

Portrait:

- inspector hidden with no selection;
- compact bottom inspector after selection;
- available node exposes explicit `+`;
- partial node exposes `-` and `+`;
- maxed node retains refund and shows max state;
- prerequisite lock exposes reason + `Show`;
- tier gate shows gate progress and no misleading `Show`;
- expanded inspector shows full detail/actions.

The ~448dp expanded-sheet size is a preferred reference, not an absolute. Constrain to usable height and scroll internally when necessary, especially with accessibility text scaling.

Landscape uses the B3 side-inspector model and preserves canvas priority.

Tablet extends the same model; a persistent inspector is allowed. Do not make it a stretched phone layout.

## 5. Node and state language

Keep semantic state separate from class identity/accent.

States must remain distinguishable without color alone:

- available;
- purchased partial;
- maxed;
- locked by tier;
- locked by prerequisite;
- no points;
- selected/focus;
- Show origin/path.

Do not use class accent as an error/lock semantic.

Active/passive circle-versus-square meaning is provisional. Product correctness must not depend on it until a proven dataset field exists. Rounded-square nodes are the implementation-safe fallback.

## 6. Touch/accessibility requirements

- Keep primary touch targets around 48dp or larger where possible.
- Node visual size and hit target are different concepts.
- Do not clip essential inspector content under large text.
- Allow controlled wrapping/growth.
- Preserve visible selected/focus treatment.
- Never rely only on color.
- Critical actions must remain explicit.
- Respect reduced-motion/accessibility semantics when implementing motion/haptics.

## 7. Application shell rules

The Talent Tree remains the product hero.

Do not turn TalentForge into a dashboard, guide/wiki portal, social feed, or news surface.

Use the approved shell/navigation model from the UX/UI spec.

Important lifecycle concepts:

- Draft means unnamed, not unsaved.
- Naming a draft is distinct from persistence.
- Reset Talents is recoverable and different from deleting a saved build.
- Delete is the true destructive action.
- Do not show a fake version chooser when only one version is selectable.

Do not add persistent navigation chrome that reduces Talent Tree canvas unless the spec is explicitly revised.

## 8. Design tokens

Use committed theme tokens where they exist.

Do not invent final numeric per-class accent colors that are not frozen.

If a token is specified in the PDF but missing from code, derive it from the authoritative spec, document its source, and add a regression test when appropriate.

Avoid one-off magic colors/spacings where a semantic token should exist.

## 9. UI implementation discipline

Production UI must use real repository/domain data, not hardcoded talent trees.

Keep domain rules out of composables. Prefer state-hoisted/testable UI APIs.

Do not duplicate allocation logic in the UI or infer availability from visuals when the domain layer owns the rule.

For UI milestone PRs include screenshots/previews for relevant phone/adaptive states and describe manual verification.

## 10. Review checklist

Verify:

- no tap-to-spend regression;
- canvas still pans from node-origin gestures;
- explicit spend/refund controls remain visible;
- `Show` never spends;
- lock reason matches domain state;
- no-points is distinct from locked;
- selected state is independent;
- touch targets remain usable;
- portrait/landscape behaviour matches spec;
- large text does not hide required actions;
- UI is driven by real models;
- no undocumented design reinterpretation was introduced.
