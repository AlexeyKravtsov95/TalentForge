# M7.IOS.REVIEW.2 — individual screenshot and icon audit

**Hold every asset: NO-GO.** Date: 2026-11-17, Asia/Novosibirsk. This adds individual
findings to REVIEW.1; it does not repeat extraction/provenance research or close rights.

All **42 PNGs** in the unchanged [manifest](submission/screenshots/manifest.json) were
visually inspected using eight temporary, labeled contact sheets (one per device/locale
folder). The Inner EN library was also opened at full size to resolve a bottom-button
legibility question; its Import button is present. The actual iOS icon was opened at
1024 pixels. Contact sheets only scaled copies for inspection; they are temporary audit
views, not new screenshots or proposed store assets. No source/delivered PNG was altered.
Hashes below bind every row to its actual input; full digests remain in the manifest.

The ASC UI shows 18 iPhone/iPad filenames but no Duo uploads. This audit covers **local
repository assets**, not a claim that the remote files were downloaded or match by hash.

## Classification

- **REC**: recapture from the chosen actual candidate after content decisions. All 18
  iPhone/iPad images are older Release OTA OFF **1.0.0 (1)** captures at `d51cbed`.
  Age/configuration mismatch alone does not prove a feature was removed or broken.
  **Eight additional Duo library/favorites images are REC for uploaded (4)**: their
  search/filter/favorites/sort UI was added November 10–14, after its November 9 review
  (commit evidence in the assessment). They are ahead of this candidate, not obsolete
  functions in current main. Rights clearance alone cannot make them accurate for (4).
- **KEEP?**: 16 Duo editor/details/class/share images may remain visually unchanged **only after** applicable rights,
  actual-candidate parity and native acceptance are established. They are native Debug
  OTA OFF captures with app label **1.0.0 (4)**; that label does not bind them to ASC's
  uploaded build. Recapture if source/configuration/parity cannot be established or the
  content decision changes visible UI. KEEP? is not upload approval.
- **G**: client-derived talent/spec icons; **T**: client-derived talent description prose;
  **C**: class artwork without documented author/license; **L**: game-identifying labels,
  class/talent names/data arrangement requiring the existing rights assessment.
  Every row contains unconfirmed material; labels show the directly visible categories,
  not a determination that every short label is copyrightable.
- **OLD COVERAGE**: the two old iPhone `04-build-library` files show Home/Continue/Recent,
  not current main's searchable/filterable/sortable library. This is stale coverage for
  that newer UI. The final build-(4) copy removes those newer-library claims;
  Home/Continue itself is not proved obsolete in (4). No image is asserted to depict a
  proven removed function.

Relevant risks: **2.3 / 2.3.3** (actual current experience), **2.3.9 / 5.2.1** (rights),
with **4.1(a)** in the context of Apple's explicit third-party metadata concern. See
[Apple's guidelines](https://developer.apple.com/app-store/review/guidelines/).
A genuine capture is not permission to publish protected content inside it.

## All 42 images

Paths are relative to `submission/screenshots/`; each is linked to the unchanged PNG.

| # | File | Visible observation / obsolescence finding | Unconfirmed content | Decision | SHA-256 prefix |
|---|---|---|---|---|---|
| 01 | [en-US/ipad-13/01-editor.png](submission/screenshots/en-US/ipad-13/01-editor.png) | Holy Power 2/5, 33/51, tablet side inspector; small prose at store scale; candidate parity unverified. | G, T, L | REC | `f8fbd629889f` |
| 02 | [en-US/ipad-13/02-share-code.png](submission/screenshots/en-US/ipad-13/02-share-code.png) | Mage / Arcane Mind 3/5 in dimmed side inspector, code/share modal; small controls amid empty canvas; parity unverified. | G, T, L | REC | `e9531a31206f` |
| 03 | [en-US/ipad-13/03-import-preview.png](submission/screenshots/en-US/ipad-13/03-import-preview.png) | Mage import confirmation 30/51 with class emblem and Forever data version, small centered modal; parity unverified. | C, L | REC | `7b7e781018ed` |
| 04 | [en-US/iphone-6.3/01-editor.png](submission/screenshots/en-US/iphone-6.3/01-editor.png) | Paladin 33/51, Holy Power 2/5 in compact bottom inspector; game/spec icons; parity unverified. | G, L | REC | `4568543aef66` |
| 05 | [en-US/iphone-6.3/02-talent-details.png](submission/screenshots/en-US/iphone-6.3/02-talent-details.png) | Holy Power 2/5, current/next-rank prose and visible rank controls; parity unverified. | G, T, L | REC | `956a71535101` |
| 06 | [en-US/iphone-6.3/03-class-picker.png](submission/screenshots/en-US/iphone-6.3/03-class-picker.png) | Nine class emblems, Forever data 1.60.1 label, no full game title; parity unverified. | C, L | REC | `2e83ca7d27f3` |
| 07 | [en-US/iphone-6.3/04-build-library.png](submission/screenshots/en-US/iphone-6.3/04-build-library.png) | OLD COVERAGE: TalentForge Home, Continue/Recent, four builds; no library search/sort/favorite controls. Replace with actual current Library for that claim. | C, L | REC | `346300eb537b` |
| 08 | [en-US/iphone-6.3/05-share-code.png](submission/screenshots/en-US/iphone-6.3/05-share-code.png) | Code/share sheet over dimmed Paladin talent tree; Share and Copy code visible; parity unverified. | G, L | REC | `aa7d056333f9` |
| 09 | [en-US/iphone-6.3/06-import-preview.png](submission/screenshots/en-US/iphone-6.3/06-import-preview.png) | Paladin import confirmation 33/51, class emblem, Forever data 1.60.1.70450; Import/Cancel visible; parity unverified. | C, L | REC | `71ea4be64b8f` |
| 10 | [ru/ipad-13/01-editor.png](submission/screenshots/ru/ipad-13/01-editor.png) | Священная сила 2/5, 33/51, tablet side inspector; small prose at store scale; candidate parity unverified. | G, T, L | REC | `305da8ab905d` |
| 11 | [ru/ipad-13/02-share-code.png](submission/screenshots/ru/ipad-13/02-share-code.png) | Маг / Чародейский ум 3/5 in dimmed side inspector, code/share modal; small controls amid empty canvas; parity unverified. | G, T, L | REC | `1b166525aab4` |
| 12 | [ru/ipad-13/03-import-preview.png](submission/screenshots/ru/ipad-13/03-import-preview.png) | Mage import confirmation 30/51 with class emblem and Forever data version, small centered modal; parity unverified. | C, L | REC | `15ca6da832a0` |
| 13 | [ru/iphone-6.3/01-editor.png](submission/screenshots/ru/iphone-6.3/01-editor.png) | Paladin 33/51, Holy Power 2/5 in compact bottom inspector; game/spec icons; parity unverified. | G, L | REC | `de99192c7624` |
| 14 | [ru/iphone-6.3/02-talent-details.png](submission/screenshots/ru/iphone-6.3/02-talent-details.png) | Священная сила 2/5, current/next-rank prose and visible rank controls; parity unverified. | G, T, L | REC | `a6f971242b95` |
| 15 | [ru/iphone-6.3/03-class-picker.png](submission/screenshots/ru/iphone-6.3/03-class-picker.png) | Nine class emblems, Forever data 1.60.1 label, no full game title; parity unverified. | C, L | REC | `e362938d4c54` |
| 16 | [ru/iphone-6.3/04-build-library.png](submission/screenshots/ru/iphone-6.3/04-build-library.png) | OLD COVERAGE: TalentForge Home, Continue/Recent, four builds; no library search/sort/favorite controls. Replace with actual current Library for that claim. | C, L | REC | `3920d270ce90` |
| 17 | [ru/iphone-6.3/05-share-code.png](submission/screenshots/ru/iphone-6.3/05-share-code.png) | Code/share sheet over dimmed Paladin talent tree; Share and Copy code visible; parity unverified. | G, L | REC | `08af74324edf` |
| 18 | [ru/iphone-6.3/06-import-preview.png](submission/screenshots/ru/iphone-6.3/06-import-preview.png) | Paladin import confirmation 33/51, class emblem, Forever data 1.60.1.70450; Import/Cancel visible; parity unverified. | C, L | REC | `1d3be3c1ceca` |
| 19 | [iphone-duo/inner/en-US/01-editor.png](submission/screenshots/iphone-duo/inner/en-US/01-editor.png) | Paladin build; Holy/Свет 5/51, locked nodes; no removed function established. | G, L | KEEP? | `249e5f7f84fb` |
| 20 | [iphone-duo/inner/en-US/02-talent-details.png](submission/screenshots/iphone-duo/inner/en-US/02-talent-details.png) | Divine Strength 5/5, strength +10%, expanded inspector; no obsolete function established. | G, T, L | KEEP? | `afd42906d332` |
| 21 | [iphone-duo/inner/en-US/03-class-selection.png](submission/screenshots/iphone-duo/inner/en-US/03-class-selection.png) | Nine class emblems in three-column grid; Forever/data 1.60.1 label; no obsolete function established. | C, L | KEEP? | `1598b07b1495` |
| 22 | [iphone-duo/inner/en-US/04-build-library.png](submission/screenshots/iphone-duo/inner/en-US/04-build-library.png) | Four Paladin library results, empty query, favorite stars, class/sort controls; list continues by scrolling. Newer than reviewed (4): incompatible candidate feature claim. | C, L | REC | `394a97398ee0` |
| 23 | [iphone-duo/inner/en-US/05-search-favorites.png](submission/screenshots/iphone-duo/inner/en-US/05-search-favorites.png) | Favorites active, 1 of 4 results; search field empty (not evidence of a typed search); Import visible. Newer than reviewed (4): incompatible candidate feature claim. | C, L | REC | `169de469f18d` |
| 24 | [iphone-duo/inner/en-US/06-build-sharing.png](submission/screenshots/iphone-duo/inner/en-US/06-build-sharing.png) | TF1 code, Share/Copy controls over dimmed 5/51 tree; no actual send shown, no obsolete function established. | G, L | KEEP? | `eefd8493fa5e` |
| 25 | [iphone-duo/inner/ru/01-editor.png](submission/screenshots/iphone-duo/inner/ru/01-editor.png) | User-named holy / Паладин; Holy/Свет 5/51, locked nodes; no removed function established. | G, L | KEEP? | `dcbd722dd684` |
| 26 | [iphone-duo/inner/ru/02-talent-details.png](submission/screenshots/iphone-duo/inner/ru/02-talent-details.png) | Божественная сила 5/5, strength +10%, expanded inspector; no obsolete function established. | G, T, L | KEEP? | `8bb0589aa2f3` |
| 27 | [iphone-duo/inner/ru/03-class-selection.png](submission/screenshots/iphone-duo/inner/ru/03-class-selection.png) | Nine class emblems in three-column grid; Forever/data 1.60.1 label; no obsolete function established. | C, L | KEEP? | `2a8035b30e12` |
| 28 | [iphone-duo/inner/ru/04-build-library.png](submission/screenshots/iphone-duo/inner/ru/04-build-library.png) | Four Paladin library results, empty query, favorite stars, class/sort controls; list continues by scrolling. Newer than reviewed (4): incompatible candidate feature claim. | C, L | REC | `a325ecd0bcff` |
| 29 | [iphone-duo/inner/ru/05-search-favorites.png](submission/screenshots/iphone-duo/inner/ru/05-search-favorites.png) | Favorites active, 1 of 4 results; search field empty (not evidence of a typed search); Import visible. Newer than reviewed (4): incompatible candidate feature claim. | C, L | REC | `611f35fd8e6f` |
| 30 | [iphone-duo/inner/ru/06-build-sharing.png](submission/screenshots/iphone-duo/inner/ru/06-build-sharing.png) | TF1 code, Share/Copy controls over dimmed 5/51 tree; no actual send shown, no obsolete function established. | G, L | KEEP? | `3abc93834a70` |
| 31 | [iphone-duo/outer/en-US/01-editor.png](submission/screenshots/iphone-duo/outer/en-US/01-editor.png) | Paladin build; Holy/Свет 5/51, locked nodes; no removed function established. Asymmetric right system-indicator area. | G, L | KEEP? | `a4b7754599b8` |
| 32 | [iphone-duo/outer/en-US/02-talent-details.png](submission/screenshots/iphone-duo/outer/en-US/02-talent-details.png) | Divine Strength 5/5, strength +10%, expanded inspector; no obsolete function established. Asymmetric right system-indicator area. | G, T, L | KEEP? | `5deccf80416d` |
| 33 | [iphone-duo/outer/en-US/03-class-selection.png](submission/screenshots/iphone-duo/outer/en-US/03-class-selection.png) | Nine class emblems in three-column grid; Forever/data 1.60.1 label; no obsolete function established. Asymmetric right system-indicator area. | C, L | KEEP? | `95d8afbee7b3` |
| 34 | [iphone-duo/outer/en-US/04-build-library.png](submission/screenshots/iphone-duo/outer/en-US/04-build-library.png) | Four Paladin library results, empty query, favorite stars, class/sort controls; list continues by scrolling. Newer than reviewed (4): incompatible candidate feature claim. Asymmetric right system-indicator area. | C, L | REC | `0e8c92b1be8f` |
| 35 | [iphone-duo/outer/en-US/05-search-favorites.png](submission/screenshots/iphone-duo/outer/en-US/05-search-favorites.png) | Favorites active, 1 of 4 results; search field empty (not evidence of a typed search); Import visible. Newer than reviewed (4): incompatible candidate feature claim. Asymmetric right system-indicator area. | C, L | REC | `66a611cf2440` |
| 36 | [iphone-duo/outer/en-US/06-build-sharing.png](submission/screenshots/iphone-duo/outer/en-US/06-build-sharing.png) | TF1 code, Share/Copy controls over dimmed 5/51 tree; no actual send shown, no obsolete function established. Asymmetric right system-indicator area. | G, L | KEEP? | `9f833a3ff934` |
| 37 | [iphone-duo/outer/ru/01-editor.png](submission/screenshots/iphone-duo/outer/ru/01-editor.png) | User-named holy / Паладин; Holy/Свет 5/51, locked nodes; no removed function established. Asymmetric right system-indicator area. | G, L | KEEP? | `909b52faf2ec` |
| 38 | [iphone-duo/outer/ru/02-talent-details.png](submission/screenshots/iphone-duo/outer/ru/02-talent-details.png) | Божественная сила 5/5, strength +10%, expanded inspector; no obsolete function established. Asymmetric right system-indicator area. | G, T, L | KEEP? | `aa1caa6e089c` |
| 39 | [iphone-duo/outer/ru/03-class-selection.png](submission/screenshots/iphone-duo/outer/ru/03-class-selection.png) | Nine class emblems in two-column grid; Forever/data 1.60.1 label; no obsolete function established. Asymmetric right system-indicator area. | C, L | KEEP? | `ad60ac45e00b` |
| 40 | [iphone-duo/outer/ru/04-build-library.png](submission/screenshots/iphone-duo/outer/ru/04-build-library.png) | Four Paladin library results, empty query, favorite stars, class/sort controls; list continues by scrolling. Newer than reviewed (4): incompatible candidate feature claim. Asymmetric right system-indicator area. | C, L | REC | `38278513115b` |
| 41 | [iphone-duo/outer/ru/05-search-favorites.png](submission/screenshots/iphone-duo/outer/ru/05-search-favorites.png) | Favorites active, 1 of 4 results; search field empty (not evidence of a typed search); Import visible. Newer than reviewed (4): incompatible candidate feature claim. Asymmetric right system-indicator area. | C, L | REC | `fa9e1c70c8fb` |
| 42 | [iphone-duo/outer/ru/06-build-sharing.png](submission/screenshots/iphone-duo/outer/ru/06-build-sharing.png) | TF1 code, Share/Copy controls over dimmed 5/51 tree; no actual send shown, no obsolete function established. Asymmetric right system-indicator area. | G, L | KEEP? | `268d6f6154e2` |

**Totals: 42/42 visually reviewed; 26 REC for uploaded (4); 16 KEEP?; 42 with unconfirmed content;
2 explicitly stale library-claim coverage images; 0 rights-cleared/unconditionally
approved images.** Current images contain Forever labels; no full `World of Warcraft:
Forever` title or Blizzard wordmark was observed in these captures. That does not remove
recognizable game-image risks. The blank query in all four Duo `05-search-favorites`
images proves only the shown favorites state, not native text-search acceptance.

## App Icon

File: [AppIcon-1024.png](../../../mobile/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png).
SHA-256: `da4d634cdaef29410636b5735aafc915c70a810697f500637cb5b3da5134a0e5`.

- **Observation:** gold branching lines, circular/orb forms and a lower crystal on
  a textured dark field. No visible text or Blizzard/WoW wordmark. The ASC thumbnail
  looks consistent; remote bytes/embedded archive icon were not independently extracted.
- **Decision: KEEP?** after documented independent source/input and tool/output rights,
  plus comparison with any specific image Apple identifies. Fantasy styling alone proves
  neither copying nor originality. Rights remain **unconfirmed** (REVIEW.1 R2).
- [Existing provenance](evidence/appstore-icon/README.md) records owner visual approval,
  512px source → AI enlargement → 1254px → crop/resize to 1024px. It does not record a
  complete creator/input/license chain. Technical size and visual approval do not cure it.
- No icon replacement or screenshot paint-over here. If the existing binary-owned icon
  needs changing, use the separate binary plan in the [assessment](m7-ios-review-2-assessment.md).

## Concrete recapture plan — execution belongs to a later authorized step

1. Resolve content rights/provenance and Apple's field/image scope first. If rights cannot
   be established, keep NO-GO and complete a separately scoped legitimate content solution
   in the app. Do not substitute neutral pictures only in store images while the app still
   shows the original assets. Preserve third-party origin and ownership disclosures.
2. Bind the existing uploaded **1.0.0 (4)** to its delivery archive/source/configuration,
   icon, OTA mode and data versions. Verify features in an existing install/TestFlight build.
   A recent local app with the same numeric build is insufficient. If a binary change is
   required, choose that new candidate in the separate milestone before recapturing.
3. Prepare fictional local builds (no personal data): an editable 33/51 build; a locked
   node with one-hop prerequisite; four library entries with distinct neutral names; a
   genuine exported TF1 code with a successful import preview. Use available
   local data offline; separately check any advertised online-only version download.
4. Capture on actual supported iPhone **1206×2622** and iPad **2064×2752** simulator/device
   slots matching the manifest. Capture all six stories in **both EN and RU**: editor,
   expanded current/next-rank requirements, class chooser, the candidate’s actual All
   Builds, share code and import preview. Do not stage search/favorites/sort controls in
   uploaded (4). This means 12 iPhone and 12 iPad images;
   the latter replaces the old three-per-locale set. If using additional library scenes,
   remain within Apple's then-current ten-per-slot maximum. Prefer a readable real app
   viewport/zoom and compact modal content; no crop/enlargement to fake readability.
5. For Duo, first close native two-finger pinch/pan, text entry/search, remaining sort/
   favorites/delete/transition acceptance from [DUO.1](../../qa/m7-ios-duo-1/README.md).
   These checks cover the newer-library candidate only where it actually contains those
   functions; they are not promises for uploaded (4). On official Outer **1398×2034** and Inner **2007×2853** displays, verify EN/RU portrait
   and landscape controls/safe areas. For uploaded (4), replace the eight 04/05 images
   with genuine All Builds / import preview captures; the other 16 are conditional. For
   a separately approved newer binary, reassess all 24 and use a genuine entered query
   if claiming search. Do not label the existing share screen an import preview. The
   current screenshot verifier hardcodes the Duo six-scene set including search-favorites;
   revising scene names/manifest/verifier together belongs to that real recapture follow-up,
   with regression coverage. Do not put another screen under a misleading required filename.
6. Record source SHA, selected archive identity/hash, app/store versions, build, OTA mode,
   bundled/downloaded data version, device/display, Xcode/SDK/runtime, locale, appearance,
   Dynamic Type, raw/delivered hashes and timestamp. Use native capture; only fully opaque
   alpha removal with unchanged decoded RGB is allowed and recorded. Preserve raw captures.
7. Review every full image and store-size preview for readable EN/RU, actual function,
   unobscured controls, and content rights; update manifest and run
   `python3 scripts/verify_appstore_screenshots.py --require-duo`. Owner approval of the
   coherent candidate pack is separate from technical PASS. No captures/uploads in this PR.
