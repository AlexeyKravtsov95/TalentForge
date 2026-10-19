# M5.LEGAL.1 — Game content inventory and provenance

| | |
|---|---|
| Milestone | M5.LEGAL.1, game content rights and provenance audit (research, **not legal advice**) |
| Audited commit | `main` @ `aaf4b60` (merge of PR #52), 2026-10-19. The app and data are identical to `b242cd6`, the commit M5.STORE.1 audited; only `docs/` changed in between |
| Companion documents | [legal1-rights-assessment.md](legal1-rights-assessment.md) (sources, risk matrix) · [legal1-release-options.md](legal1-release-options.md) (options A/B/C) |
| Safety | Read-only audit. No game data, image, app code, store console, bucket or repository setting was changed. The only network requests to project infrastructure were anonymous read-only `HEAD`/`GET` requests to the project's own OTA staging origin (§5.2) |

This document records **what** game-derived material TalentForge holds, **where** it comes from and
**where it ends up**. Whether any of it may be distributed is assessed separately in the
[rights assessment](legal1-rights-assessment.md). Nothing here is a statement that a use is
permitted or forbidden.

## 1. Summary

| # | Category | Count (product build `1.60.1.70450`) | In the release APK | Origin | Rights recorded in the repository |
|---|---|---|---|---|---|
| 1 | Talent icons (client textures) | 359 icons per build; **367 distinct** across the 5 bundled builds; 1 790 PNG files | **Yes**, all 1 790 | Blizzard client files (BLP2), fetched from wago.tools by FileDataID, converted to PNG | **None.** [talent-visuals §6](../talent-visuals.md#6-provenance-and-ownership-of-the-artwork) states they remain Blizzard's property and the project claims no rights |
| 2 | Names: talents, classes, game label | 469 talent entries, 451 distinct English names, 469 Russian names; 9 class names (EN/RU); the label "Forever" | **Yes** | talent names: client DB2 (`SpellName`, `TraitDefinition`); class names: UI string resources; "Forever": project label for the product | None |
| 3 | Descriptions and localised strings | 469 English description templates; 1 328 rendered English ranks (≈ 132 k characters); 383 of 469 Russian descriptions rendered (≈ 116 k characters) | **Yes** | client DB2 text (`Spell.Description_lang`, `SpellName.Name_lang`, `GlobalStrings`), numbers substituted by the project's renderer | None |
| 4 | Mechanics: numbers, requirements, edges, layout | 9 trees, 469 nodes, rank values, point gates, prerequisites, positions | **Yes** | client DB2 trait tables | None |
| 5 | Nine class paintings `class_*.png` | 9 files, 118 × 118 RGBA | **Yes** | **not recorded** (commit `2a2ada2` calls them "approved TalentForge class paintings") | **None** (LR-3) |
| 6 | TalentForge icon, design, UI | launcher icon (adaptive, 3 layers + fallbacks), `app_icon.png`, 512 px store icon, UI code and tokens, 2 design PDFs | **Yes** (icon, UI) | owner-approved M5.RC.2a package (AI-assisted concept), project code | Package recorded with checksums; **rights review of the AI-assisted concept not recorded** |
| 7 | Screenshots and store texts | 412 PNGs under `docs/`; EN/RU listing drafts | No (repository only) | captures of the app (show categories 1–5) and project-written copy | Store texts are project-written; screenshots inherit categories 1–5 |

Distribution channels found (all detailed below):

- the **release APK** (and the debug APK) — categories 1–6;
- the **public GitHub repository**, including its history — categories 1–7 plus raw client tables
  (CSV) and two verbatim client texture files (BLP);
- **31 unexpired debug-APK artifacts** of the public repository's CI (categories 1–6);
- the **OTA staging origin on VK Cloud**, publicly readable today, which holds two complete data
  releases (categories 1–4).

## 2. Provenance chain

```
Blizzard client build (product wow_classic_beta, branch 1.60, "Forever")
  │  DB2 tables + texture files, addressed by build version and FileDataID
  ▼
wago.tools  (third-party mirror; not Blizzard)
  │  GET https://wago.tools/db2/<Table>/csv?build=<build>[&locale=ruRU]        DB2 → CSV
  │  GET https://wago.tools/api/casc/<FileDataID>?version=<build>                client file, verbatim
  ▼
.cache/wago/<game>/<build>/…       (local, git-ignored, build-pinned)
  │  python -m talent_data fetch | normalize | visuals | tooltips | localization
  ▼
data/normalized/forever/<build>/   talent-data.v1  (structure, names, description templates, rank values)
data/visuals/forever/<build>/      icon registry + PNG icons (BLP2 → PNG, Pillow 12.3.0)
data/tooltips/forever/<build>/     rendered English descriptions per rank
data/localization/forever/<build>/ruRU/  Russian names and rendered descriptions
  │  scripts/sync_mobile_{datasets,visuals,tooltips,localization}.py   byte-identical copies, drift-checked in CI
  ▼
mobile/composeApp/src/commonMain/composeResources/files/{dataset,visuals,tooltips,localization}/…
  │  Compose Multiplatform resources
  ▼
APK: assets/composeResources/io.github.alexeykravtsov95.talentforge.composeapp.generated.resources/files/…
  │  (and the iOS app bundle, out of scope here)
  ▼
OTA (optional, production disabled): the same files as content-addressed blobs on an S3 origin
```

Evidence for each arrow:

| Step | Evidence |
|---|---|
| Product and build | [forever-dataset.md](../forever-dataset.md) "Build selection": `1.60.1.70450` is product `wow_classic_beta`, branch `1.60`; `GameConfig.products = ("wow_classic_beta",)`. The repository does not claim it is the build the live realms run |
| DB2 via wago.tools | every dataset's `provenance.source = "wago.tools"` and its table list; the localization sidecars record the eight exact CSV URLs (e.g. `https://wago.tools/db2/SpellName/csv?build=1.60.1.70450&locale=ruRU`) |
| Client files via wago.tools | `icons.json` `provenance`: `source: wago.tools`, `retrieval: client file by FileDataID, pinned to clientBuild`; [tests/fixtures/README.md](../../tests/fixtures/README.md): `GET https://wago.tools/api/casc/<FileDataID>?version=…` |
| Local CSV alternative | `src/talent_data/sources/local.py` accepts locally exported client tables; no committed dataset was produced that way (all record `wago.tools`) |
| Mobile copies | `scripts/sync_mobile_*.py --check` (CI drift check) |
| APK contents | release APK built for this audit (§4) |

## 3. Category details

### 3.1 Original talent icons from the game client

| Fact | Value | Source |
|---|---|---|
| Source format | BLP2, 64 × 64, DXT-compressed with mipmaps; 273 with a real alpha channel, 85 without (70009 figures) | [talent-visuals §Format](../talent-visuals.md) |
| Conversion | Pillow 12.3.0 BLP decoder → 8-bit PNG (RGBA or RGB), **native 64 × 64, no resampling, no effects, no metadata**; tests compare decoded pixels with the BLP2 texture | `icons.json` `provenance.converter`; `talent-visuals.md` |
| Character of the PNG | a **pixel-exact format conversion** of the client texture, not a new work. Lock dimming, rings and rank chips are drawn by the app at render time | idem |
| Identity | each icon is named by the client's FileDataID (e.g. `132089.png`); every registry entry records `sourceSha256` and `sourceByteLength` of the client file | `icons.json` |
| Count | 359 (70450), 358 (70009, 70338, 70374), 357 (70245); **367 distinct FileDataIDs** across the five bundled builds; 1 790 PNG files in `data/visuals` and the same 1 790 in `composeResources` | `ls`, `icons.json` |
| Size | ≈ 14 MB on disk (`data/visuals`, all builds); ≈ 2.0 MB per build | `du` |
| Verbatim client files | `tests/fixtures/casc/forever/1.60.1.70009/{132269,132090}.blp`, described as "Blizzard artwork, used to test the BLP2 → PNG conversion" | [tests/fixtures/README.md](../../tests/fixtures/README.md) |
| Git | 717 distinct PNG blobs in history (the 367 icons, re-encoded byte-differently by different Pillow builds); first committed `2909dc2` (2026-10-07) | `git log --raw` |

### 3.2 Names of talents, specialisations and classes

| Item | Count | Where | Origin |
|---|---|---|---|
| Talent names, English | 469 entries, 451 distinct | `data/normalized/.../<class>.json` (`nodes[].name`, `entries[].name`) | DB2 `SpellName` / `TraitDefinition` |
| Talent names, Russian | 469 translated (`status: translated`) | `data/localization/.../ruRU/<class>.json` | DB2 `SpellName.Name_lang` (ruRU export); "no substitution": a missing row is `missing`, never invented ([talent-localization.md](../talent-localization.md)) |
| Specialisation names | **none bundled**: every tree has three sections with `name: null` | normalized data | — |
| Class names | 9 (Warrior, Paladin, Hunter, Rogue, Priest, Shaman, Mage, Warlock, Druid) + Russian equivalents; also in `class.name` of the datasets | `values/strings.xml`, `values-ru/strings.xml`; DB2 `ChrClasses` | common English words / Russian equivalents used by the game |
| Game label | "Forever", shown in the UI as `Warrior · Forever` | `BundledDatasets.kt` (`forever` → `Forever`) | the project's game id; not found in the committed DB2 fixture rows (`tests/fixtures`) |
| "World of Warcraft" | `README.md` line 3; Variant B of the store listing (gated on LR-2); **not** in the app's UI strings | repository text | — |

### 3.3 Descriptions and localised strings

| Item | Count (70450) | Nature |
|---|---|---|
| English description templates (`descriptionTemplate`) | 469 | **verbatim client text** with `$s1`-style tokens, e.g. "Increases your damage with Wands by $s1%." |
| English rendered tooltips | 1 328 ranks: 1 272 resolved, 55 partial, 1 unresolved; ≈ 132 k characters of text segments | client template text with the project's numbers substituted. [talent-tooltips.md](../talent-tooltips.md): "no hand-written text" |
| Russian rendered descriptions | 383 of 469 entries rendered, 86 fall back to English; ≈ 116 k characters | client ruRU text (`Spell.Description_lang`) rendered with the same grammar |
| `GlobalStrings` rows | four duration rows (EN/RU) | client UI strings |

What is **original to TalentForge** in this category: none of the words. The project authors only
the renderer (code), the per-rank numbers it computes from client tables, and the gap markers.
The renderer output is a mechanical rendering of the client's own template text, so for rights
purposes it must be treated as the game's text, not as an independent description.

### 3.4 Numeric parameters, requirements, edges, layout

`data/normalized/forever/<build>/<class>.json` (talent-data.v1): tree ids, point budgets, node
positions (`x`, `y`, row, column), node type, max ranks, prerequisites, unlock requirements
(point gates), edges, per-rank effect values (from `Curve`/`CurvePoint`), spell ids. All are
taken from the client's trait tables (`provenance.tables`, 23 tables). Conformance vectors
(`data/conformance/`) and allocation fixtures are derived from them.

These are the facts of the game's rules. The rights assessment discusses whether facts and
their arrangement are protected; the inventory only records that they are a **complete,
structured copy** of the trees, not a selection.

### 3.5 The nine class paintings

| Fact | Value |
|---|---|
| Files | `mobile/composeApp/src/commonMain/composeResources/drawable/class_{druid,hunter,mage,paladin,priest,rogue,shaman,warlock,warrior}.png` |
| Format | 118 × 118, RGBA (colour type 6), 34–41 KB each; **no** `tEXt`/`iTXt`/`zTXt`/`eXIf`/`iCCP` chunks, so no embedded author, tool or licence metadata |
| Git history | **one commit**: `2a2ada2` (2026-10-13, author `AlexeyKravtsov95`): "The nine approved TalentForge class paintings bundled byte-exact at their native 118x118 px". Never modified since; no source files, layered masters, prompts, sketches or licence files in history |
| Documentation | [mobile-architecture.md](../mobile-architecture.md) ("nine approved TalentForge class paintings"); [rc1-defects LR-3](rc1-defects.md); [store1 §8 LR-3](store1-release-audit.md#8-legal-blockers-none-closed-by-this-audit-a-passing-build-does-not-close-them). No document names the author, the tool, the date of creation or a licence |
| Evidence of independent creation | **None recorded.** "Approved" records an owner decision, not authorship. Whether they were hand-painted, AI-generated, commissioned, or derived from game or third-party art cannot be determined from the repository |
| Shipped | Yes (`drawable/class_*.png` in the APK); shown on Home, All Builds and class selection |

### 3.6 TalentForge icon, design and UI

| Item | Provenance in the repository |
|---|---|
| Launcher icon, `app_icon.png`, 512 px store icon | M5.RC.2a package, integrated byte-for-byte; checksums in [rc2a-evidence/source](rc2a-evidence/source/PACKAGE-SHA256SUMS.txt). Concept #3 was **AI-assisted** ([privacy-and-compliance §7.4](appgallery/privacy-and-compliance.md#74-brand-assets)); the icon policy required a rights review (originality, tool terms, output rights) before integration, and that review is **not recorded** |
| UI code, layout, tokens | written in this repository |
| Design PDFs (`docs/design/*.pdf`) | the project's UX/UI specification; repository only, not in the APK. Their embedded images were not inspected |
| Fonts | platform default (no bundled font files found under `composeResources`) |
| Third-party code licences | covered by LR-4 and the Third-party licenses screen; out of scope here |

### 3.7 Screenshots and store texts

| Item | Count / location | Note |
|---|---|---|
| Screenshots | 412 PNG files under `docs/` (349 in `docs/screenshots/*`, the rest in release evidence) | captures of debug or test builds; most show talent icons, talent text and class paintings |
| Store icon | `docs/release/rc2a-evidence/source/TalentForge-RuStore-512.png` | category 6 |
| Listing texts | [store-listing-en](appgallery/store-listing-en.md), [store-listing-ru](appgallery/store-listing-ru.md) | project-written. Variant A names no game, publisher or class; Variant B names World of Warcraft and Blizzard and is gated on LR-2 |
| Store screenshots | **none exist** (M5.STORE.1 STORE1-SHOTS) | planned screenshots 1–3 would show categories 1, 3 and 5 |

## 4. What the release APK contains

Built for this audit on `aaf4b60` with `./gradlew :composeApp:assembleRelease` (unsigned, not
uploaded, not kept): `composeApp-release-unsigned.apk`, 19 806 566 bytes, 2 145 entries.
Paths below are relative to
`assets/composeResources/io.github.alexeykravtsov95.talentforge.composeapp.generated.resources/`.

| APK path | Files | Category |
|---|---|---|
| `files/visuals/forever/<build>/icons/<FileDataID>.png` | **1 790** | 1 |
| `files/visuals/forever/<build>/{icons.json,<class>.json}` | 5 + 45 | 1 (registry), 4 |
| `files/dataset/forever/<build>/{manifest.json,<class>.json}` | 5 + 45 | 2, 3 (templates), 4 |
| `files/tooltips/forever/<build>/<class>.json` | 45 | 3 |
| `files/localization/forever/<build>/ruRU/<class>.json` | 45 | 2, 3 |
| `drawable/class_*.png` | 9 | 5 |
| `drawable/app_icon.png` + Android launcher resources | 1 + adaptive icon | 6 |
| `values/`, `values-ru/` strings | 2 | 2 (class names), 6 |
| `files/legal/*` | 20 | third-party licences (LR-4) |

The five bundled builds are `1.60.1.70009`, `70245`, `70338`, `70374` and `70450`: older builds
stay bundled for saved builds. Spot check: the APK's `…/70450/icons/132089.png` and
`data/visuals/forever/1.60.1.70450/icons/132089.png` have the same SHA-256 (`941724bb…`), as the
CI drift check requires. The debug APK has the same `composeResources`.

## 5. Distribution outside the APK

### 5.1 Public GitHub repository

| Fact | Value |
|---|---|
| Visibility | **PUBLIC** (created 2026-09-26); no `LICENSE`; 0 forks, 0 stars on 2026-10-19 (`gh repo view`) |
| Game material at `HEAD` | categories 1–4 in `data/` **and** in `composeResources/` (two copies), 1 381 raw client-table CSV files under `tests/fixtures/{db2,tooltips,localization}`, 2 verbatim BLP files, `docs/research/*` coverage reports quoting templates, `artifacts/diff`, 412 screenshots |
| Git history | everything above since 2026-09-26 (first DB2 data `1941826`, first icons `2909dc2`); 52 paths exist **only** in history, among them 42 earlier raw CSV fixture paths (`tests/fixtures/db2/forever/{1.60.1.69977,1.60.1.70009}/*.csv`). Deleting files at `HEAD` would not remove them from history, clones or GitHub's caches |
| CI artifacts | **31 unexpired `composeApp-debug-apk` artifacts** (≈ 22 MB each, 90-day retention, expiring 2026-12-27 … 2027-01-17); for a public repository, GitHub lets any signed-in user download artifacts. Each contains categories 1–6 |
| Releases | none |

### 5.2 OTA staging origin (VK Cloud)

The M5.OTA.4b report records a public-read staging bucket
`talentforge-ota-staging` (`https://talentforge-ota-staging.hb.ru-msk.vkcloud-storage.ru`) holding
releases `1.60.1.70374-r1` and `1.60.1.70450-r1` (≈ 790 objects: dataset, visuals, tooltips,
localization JSON and the PNG icons), with a catalog valid until 2026-12-02. Re-checked for this
audit on **2026-10-19 09:56 GMT** with anonymous requests:

| Request | Result |
|---|---|
| `HEAD /v1/channels/staging/catalog.json` | `200`, `Last-Modified: Sun, 18 Oct 2026 13:43:39 GMT` |
| `HEAD /v1/blobs/sha256/<sha256 of 70450 icons/132089.png>` | `200`, `image/png`, 6 482 bytes |

So **client-derived icons and text are publicly downloadable today from a server the project
controls**, independent of any store release. The production channel is not written and
production OTA is disabled in every build (M5.OTA.4b, store1 §5).

### 5.3 Future OTA production artifacts

A production release would contain the same file set per build (ADR-0001: 395–397 files,
≈ 5.0 MB per build: talent documents, manifest, icon registry, 357–359 PNG icons, tooltips, ruRU
localization). Rights-wise, OTA adds **no new category**, but it adds a **new distribution act**
(the project publishing game content from its own origin, outside the stores' review), and it lets
content change after a store has reviewed the app.

## 6. Gaps in the record

| Gap | Effect |
|---|---|
| Authorship and licence of the 9 class paintings | category 5 cannot be assessed (LR-3) |
| Rights review of the AI-assisted icon concept (tool, terms, output rights) | category 6 is owner-approved but not rights-reviewed |
| Whether `wow_classic_beta` content is covered by beta/test terms | see the rights assessment |
| Which in-game product "Forever" is, and whether "Forever" is used by Blizzard as a product name | the repository records only the product id; the label's trademark status is unknown |
| iOS bundle | not inspected; it is built from the same `composeResources` |

## 7. Reproduction

```bash
# counts and provenance (from the repository root)
python3 -c "import json;d=json.load(open('data/visuals/forever/1.60.1.70450/icons.json'));print(len(d['icons']),d['provenance'])"
ls data/visuals/forever/*/icons | grep png | sort -u | wc -l          # 367
git log --format='%h %ad %s' --date=short -- 'mobile/composeApp/src/commonMain/composeResources/drawable/class_*.png'
# APK contents
(cd mobile && ./gradlew :composeApp:assembleRelease)
unzip -l mobile/composeApp/build/outputs/apk/release/composeApp-release-unsigned.apk | grep -c 'icons/.*png'   # 1790
# public CI artifacts
gh api repos/AlexeyKravtsov95/TalentForge/actions/artifacts --paginate \
  --jq '[.artifacts[]|select(.name=="composeApp-debug-apk" and .expired==false)]|length'
# OTA staging origin (read-only)
curl -sI https://talentforge-ota-staging.hb.ru-msk.vkcloud-storage.ru/v1/channels/staging/catalog.json
```
