# Build Library Search & Filters implementation plan

**Goal:** M6.LIB.1: find existing local builds by their displayed title/class and an optional class filter.

**Architecture:** All Builds resolves localized presentation strings, then passes immutable entries to a pure stable filter in composeApp commonMain. Query/class selection are saveable UI state. Existing repository loading, management, navigation and sorting remain authoritative.

**Tech stack:** Existing Compose Foundation, Compose resources, Kotlin tests; no new dependencies.

**Spec:** User's M6.LIB.1 request; UX/UI Specification V1 pp. 2–3, 7 and existing LibraryText.

## Constraints

Storage version, LocalBuildRecord, allocation-build.v1, OTA, game data, talent rules and app versions stay unchanged. iOS host/submission stays untouched. Search performs no repository reads/writes and editor actions use localId.

## UX decisions

A full-width search input with one-tap clear, followed by a compact class dropdown (All classes + the existing nine localized class names). Use existing neutral surface/border/focus tokens and 48 dp targets; scroll the dropdown at large text. Keep New build in the header. Resolve Draft with buildCardTitle. Keep true-empty, search-empty, class-empty, loading and failed storage states distinct; no-results offers Reset filters. Display result count and preserve repository order.

## Review focus

- Russian Draft title inflection must match the actual card; search class names separately.
- Rename/delete reload while retaining query/class selection and updating membership.
- Duplicate titles/results never redirect open or management actions by index.
- A failed storage read stays visible with Retry, including after a management reload.
- At 320/360/393/412 dp and 100/130/175/200% font, controls remain reachable and menu scrolls.

## Tasks

- [x] Add failing Compose coverage for combined search/filter, immutable storage, real-ID opening, active-search rename/delete and RU Draft/class lookup.
- [x] Add pure filtering coverage for empty/trimmed/case-insensitive queries, EN/RU strings, class intersection, stable order and 1/10/100 entries.
- [x] Implement pure filter, localized controls and AllBuilds integration; run focused tests.
- [ ] Add layout/semantics matrix and error/empty-state coverage; validate full desktop/shared suites and Android build.
- [ ] Capture Android EN/RU screenshots, run iOS compatibility builds, update graph, review bounded diff and prepare one PR without merge.
