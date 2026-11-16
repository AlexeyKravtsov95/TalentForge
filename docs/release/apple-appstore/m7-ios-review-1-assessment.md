# M7.IOS.REVIEW.1 — App Review remediation assessment

**Release gate: NO-GO.** Metadata is prepared for owner review, not authorized for
upload or resubmission. Renaming does not establish rights to the bundled content.
This PR can be Ready for Review while the release remains blocked.

Date: **2026-11-16, Asia/Novosibirsk**. Audited base: `main` at
`6dc98e15158ae9961ea2d5abf2878673862d8d67`. Scope: iOS metadata, review drafts,
asset/provenance audit, validator, regression tests and CI coverage.

## 1. Rejection evidence and limits

The owner's task reports rejection under **4.1(a), Copycats**. The repository materials
reviewed here do not contain the complete Apple rejection message, Apple's annotated
screenshots or an identified allegedly copied app. Consequently, the findings below
are risk analysis, **not a claim to know Apple's exact comparison or full rationale**.
The owner should preserve the original Resolution Center message and attachments
with the eventual submission evidence. No App Store Connect session was accessed.

Sources inspected:

- All 18 text fields in [submission/metadata](submission/metadata/), before and after edits.
- All **42** delivered screenshots in [submission/screenshots](submission/screenshots/):
  12 iPhone 6.3-inch, 6 iPad 13-inch, 24 Duo Outer/Inner, both locales. Visual audit
  used temporary contact sheets; original files were not changed. Technical verification
  is separate from visual, functional and legal acceptance.
- The 1024px iOS [AppIcon](../../../mobile/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png)
  and its [recorded processing/provenance](evidence/appstore-icon/README.md).
- `mobile/iosApp/iosApp/Info.plist`, EN/RU `values*/strings.xml`, library sort code,
  existing share/import and library UI test evidence, [Duo QA](../../qa/m7-ios-duo-1/README.md).
- [Content inventory](../legal1-content-inventory.md), [rights assessment](../legal1-rights-assessment.md)
  and [release options](../legal1-release-options.md). Their 2026-10-19 asset counts,
  public artifact counts and infrastructure state are historical, not new live measurements.

Apple's current [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
were read on 2026-11-16. Applicable requirements, paraphrased:

| Classification | Requirement relevant here |
|---|---|
| 4.1(a), with 4.1(b)/(c) | Deliver an independent product; minor name/UI changes do not cure copying. Avoid impersonation and unapproved use of another developer's identity in name/icon. |
| 2.3.7 | Use a distinct name and accurate keywords; avoid trademark stuffing and unsupported subtitle claims. |
| 5.2.1 | Protected third-party content needs permission; names and metadata must not mislead. |
| 2.3 / 2.3.3 / 2.3.9 | Present the actual app experience and secure rights for screenshot/icon materials. |
| 2.5.2 | Do not download executable code that changes app features. Data delivery must remain data delivery. |

Blizzard EULA and trademark-guideline URLs from the earlier assessment were attempted
again; both returned **HTTP 403** through the research tool. Their current text was not
independently revalidated. The earlier recorded assessment is used as historical evidence;
no new license or permission is inferred from it. No rightsholder was contacted.

## 2. Risk register

Severity is qualitative release impact, not a legal finding. **OPEN** means evidence or
owner action is still needed; neither free distribution nor a disclaimer grants rights.

| ID / severity / class | Source and affected files | Finding | Proposed remedy / present status | Owner agreement needed |
|---|---|---|---|---|
| R1 High — 4.1(a), 2.3.7 | Previous `metadata/{en-US,ru}/{name,subtitle,promotional_text}.txt`; old validator `APP_NAME`, `GAME_SHORT` | Rejected listing used `TalentForge: WoW Forever`, game-branded subtitle and repeated game title in promotion. This could make the planner look like a Blizzard product. Acceptance of a name by ASC is not App Review acceptance. | Prepared `TalentForge`, neutral localized planner subtitles and feature-led promotion. Regression tests reject restoration of old names/subtitles and game branding in promo/keywords. **Editorially remediated; Apple outcome unknown.** | Yes: approve EN/RU copy and later verify name availability in the existing ASC record. Never automatically fall back to the rejected title. |
| R2 High — 4.1(a), 5.2.1 | `mobile/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`; `composeResources/drawable/app_icon.png`; icon provenance README | Golden branching paths, orbs and crystal on dark background. No text or recognizable Blizzard wordmark observed; fantasy visual language alone proves neither copying nor originality. 512px owner-approved source was AI-upscaled then cropped/resized; tool/output-rights review remains unrecorded. | Retain existing icon in this PR. Record original author/tool, source inputs, terms and output rights, and compare against any icon Apple identifies. Replace with documented original work if evidence fails. **OPEN.** | Yes: supply provenance and decide retain/replace. Visual approval is not rights evidence. |
| R3 Critical — 5.2.1; contributes to 4.1(a) | `data/visuals/forever/*/icons.json`, `icons/*.png`; mobile `composeResources/files/visuals/forever/**`; editor/inspector/screenshots | Earlier inventory traces icons to client BLP2 textures via wago.tools, converted pixel-exactly to PNG. No applicable app redistribution permission is recorded. Mirror access and conversion do not establish rights. | Document permission covering app, screenshots, territories and OTA, or separately implement original visual replacements under [replacement plan](m7-ios-review-1-content-plan.md). **OPEN, NO-GO.** No images/data changed here. | Yes: rights evidence or replacement scope/budget; owner cannot close this merely by accepting risk. |
| R4 Critical — 5.2.1 | `data/normalized/forever/**`, `data/tooltips/forever/**`, `data/localization/forever/**`; corresponding mobile `files/{dataset,tooltips,localization}/**`; talent-details images | EN templates and RU localized text originate in client tables; substitution of calculated values does not turn them into independently written prose. Existing rights assessment found no grant for current sources. | Permission or a separately approved mechanics-based/original-text strategy, with legal and accuracy review. Short names and complete dataset arrangement need separate evaluation. **OPEN, NO-GO.** | Yes: counsel/permission route and description strategy; do not guess mechanics or silently discard text. |
| R5 High — 5.2.1 | `composeResources/drawable/class_{druid,hunter,mage,paladin,priest,rogue,shaman,warlock,warrior}.png`; class picker/library/home images | Nine class emblems/paintings have no author/tool/license in the inventory. They visibly reinforce game association. "Approved" does not establish independent creation. | Supply creation/license records or plan documented originals/fallback in separate scope. **OPEN (LR-3).** | Yes: provenance or replacement decision. |
| R6 High — 2.3.7, 5.2.1; 4.1(a) context | New descriptions; `values/strings.xml` and `values-ru/strings.xml`: `home_subtitle`, `help_step_class`, `about_disclaimer`; visible `Forever` labels | One full game-title mention remains in each description for supported-data identification. UI still says WoW Forever in Home/Help and Forever in editor/library/chooser. Non-affiliation reduces confusion but grants no use right. | Consider the nominative-use question separately (§3). No claim that description/UI use is permitted. The validator no longer mandates a game title. **OPEN.** UI label changes, if required, belong to follow-up; shared resources would also affect Android. | Yes: approve wording on documented legal basis or choose alternative accurate wording and separate UI work. |
| R7 High — 2.3, 2.3.9, 5.2.1 | All 42 submission PNGs and `manifest.json` | Captures inherit icon/text/class-art rights. Native captures do not remove underlying IP risk. Older iPhone/iPad set predates new library features; all captures are OTA OFF, so they do not demonstrate OTA ON behavior. | Hold upload. After content decision, recapture a coherent set from the actual candidate app, including search/sort. Preserve provenance and hashes, no painted-over content. **OPEN.** | Yes: approve content and candidate/pack. |
| R8 High — 2.3, 4.1(a) differentiation evidence | `docs/qa/m7-ios-duo-1/README.md`; PR #150; 24 Duo PNGs | Screenshots and Compose resize tests pass, but native two-finger pinch/pan acceptance and parts of the native functional matrix remain blocked. PR merge is not functional acceptance. | Close native multi-touch and remaining matrix with genuine device/simulator input and recorded results before Duo optimization or complete-support claims. **OPEN.** No claim added. | Yes: native test evidence and acceptance. |
| R9 High — 4.1(a), 2.3, 5.2.1 | `metadata/review_information/notes.txt`; historical readiness/runbook wording | Earlier notes positioned mainly as a companion and overstated "every feature" offline; old documents recorded owner risk acceptance. Neither states a right to redistribute content or demonstrates uniqueness against a named comparator. | New notes demonstrate concrete editing/library/import workflows, qualify offline limits, explicitly leave rights unresolved. Historical docs marked superseded for current submission. No universal uniqueness claim. **Draft prepared; rights OPEN.** | Yes: approve notes and attach actual rejection/comparator evidence if available. |
| R10 Medium — 2.5.2, 5.2.1 | Notes/description OTA paragraphs; existing `files/visuals`, dataset/text payloads | Existing implementation/evidence describe signed JSON/PNG delivery, not executable code. Developer signatures prove integrity/origin, not IP permission. Data can still redistribute protected content. | Preserve accurate OTA explanation and privacy logging disclosure. Do not change catalog/keys or fetch/publish new data. Verify the eventually selected archive's OTA mode before sending notes. **Technical description retained; rights OPEN.** | Yes: confirm the submission candidate and rights scope covers OTA. |

LR-1…LR-5 are not closed by this PR. OSS notices do not license game assets. This audit
adds the Apple-specific analysis; it does not rewrite the original legal documents or
select release option A/B/C for the owner.

## 3. EN/RU positioning and the game-title decision

| Field | EN | RU |
|---|---|---|
| Name | TalentForge | TalentForge |
| Subtitle | Talent Build Planner | Планировщик талантов |
| Description scope | Independent unofficial planner for the named game's included data versions; nine classes / three trees | Same |
| Feature scope | Edit/autosave; search/filter/favorites/sort/group; code export/import preview; offline available data; language fallback | Same |
| Promotion/keywords | Features and class/common terms; no WoW/Warcraft/Blizzard/Forever | Same |
| Relationship | No Blizzard affiliation/endorsement/sponsorship | Same |

The description uses **World of Warcraft: Forever once**, to identify the specific data
supported. It does not advertise compatibility with arbitrary games, a live realm or a
particular current game build. A generic description would hide the actual product.
The omission of the title from name/subtitle/promotion/keywords is an editorial mitigation,
not a conclusion that keeping it in the description is permitted.

2.3.7 is not a blanket license for nominative use. The earlier rights assessment found
no app-specific grant in the trademark guidelines. The owner needs a documented legal
assessment for the chosen territories/use and any necessary permission. If that basis
is unavailable, approve accurate alternative wording and any corresponding UI changes
before submission; do not simply conceal the game's identity while retaining its assets.
The validator permits title-free descriptions while retaining specialized planner facts.

The reviewed UI already names the product **TalentForge**, and `CFBundleDisplayName`
is **TalentForge**. No binary rename is needed for this proposal. `CFBundleName` expands
the internal product name and does not replace the user-visible display name. Home/Help
and the data labels remain the separate R6 issue. The iPad portrait captures use the existing width-driven tablet side inspector (`TalentTreeLayout.kt`); the descriptions therefore promise adaptive inspectors without an incorrect universal portrait-bottom claim. No mobile source/resources were edited.

## 4. Screenshot findings and recapture decision

| Existing group / both locales | Visual observations | Submission status |
|---|---|---|
| iPhone 6.3-inch, 6 each | Paladin editor/expanded Holy Power text, class chooser, Home/continue library, share code, import confirmation. TalentForge is visible on Home; game icons, class emblems and Forever remain. Old Home image is not the newer searchable library screen. | Technical files can pass; content rights unresolved; refreshed library coverage needed. |
| iPad 13-inch, 3 each | Portrait-dimension (2064×2752) tablet editor with side inspector, share and import modal. Game textures/text remain; large empty canvas and small modal dominate at store thumbnail size. | Recapture from selected candidate, with readable real UI and current library coverage; no simulated zoom/crop in an editor. |
| Duo Outer/Inner, 6 per locale/display | Editor, Divine Strength text, nine classes, searchable/sortable library, favorites and code share. No fabricated branding overlay. System indicator area is asymmetric on Outer. | Layout captures only; native gesture/remaining QA gate **BLOCKED**. |
| App icon | Golden paths/orbs/crystal; no visible game wordmark. | Provenance/originality review remains OPEN. |

No screenshot, manifest or icon was edited in this PR. No new runtime capture or native
manual gesture test is claimed. The earlier iPhone/iPad captures are 1.0.0 (1), Xcode
26.4.1; Duo captures are 1.0.0 (4), Xcode 27.1. They must not be represented as a newly
captured, uniform OTA ON set. Their exact source SHAs/configurations remain in the manifest.

Recapture order after R2–R6 decisions:

1. Select the real iOS candidate, confirm installed version/build, source SHA, OTA mode,
   locale and data versions. This milestone does not bump **1.0.0 (4)**.
2. Capture editor, selected/locked talent requirements, class selection, **current library
   search/filter/favorites/sort**, share and import preview in EN/RU on iPhone and iPad.
   Capture portrait bottom and landscape side inspector as appropriate. Use fictional local builds.
3. Use actual native screenshots at required display sizes; no repainting, hiding assets,
   substituting another device's resolution or compositing fake controls. Any opaque-alpha
   removal must preserve RGB pixels and be recorded.
4. Complete PR #150's native Duo pinch/pan, transitions and remaining functional matrix;
   only then approve Duo material/claims. Existing screenshots cannot prove these gestures.
5. Record source/configuration/device/runtime, raw and delivered SHA-256, dimensions and
   capture method; run screenshot verifier and review every locale. Have owner approve
   both content rights and visual accuracy before any separate ASC action.

## 5. Concrete differentiation and review drafts

The new [App Review Notes](submission/metadata/review_information/notes.txt) describe
observable contributions: selection separated from spending, visible rank controls,
one-hop prerequisite navigation, undo/redo, adaptive inspectors, an offline local library
with search/sort/favorites/grouping, and preview-before-import preserving existing builds.
Source support includes `BuildLibrarySorting.kt`, `LocalBuildRepository.kt`, the tree,
share/import and library UI tests located by graphify, and the existing native QA record.

These explain utility beyond reproducing a game screen. They **do not prove** originality
of borrowed content or claim exclusivity versus every other planner. If Apple identifies
a comparator, the owner must assess the actual alleged overlap before responding.

[Resolution Center response](submission/resolution-center-response.txt) reports only
repository work performed. It does not say ASC metadata has already been changed, assets
replaced, rights cleared, gestures accepted or a new binary submitted. Both texts are
**drafts held under NO-GO**. The response is outside the field-copy metadata directory.
Before later resubmission, reconcile the notes with the chosen archive and actual rights
outcome; the current unresolved-rights paragraph must not be silently removed.

## 6. Owner decisions and release exit criteria

| Decision / evidence | State |
|---|---|
| Full original Apple message/attachments and any named comparator preserved | Needed to finish exact-cause analysis |
| EN/RU listing and notes approved; TalentForge availability checked in both locales | Owner review pending; ASC untouched |
| R3/R4 rights basis or completed, legally assessed replacement strategy | **OPEN, NO-GO** |
| R2/R5 icon/class-art authorship, source inputs and output/license rights | **OPEN, NO-GO** |
| R6 title/labels/short names/dataset arrangement assessment, territories and OTA scope | **OPEN, NO-GO** |
| Fresh coherent screenshot pack approved after content decisions | Pending |
| Native Duo gesture and remaining matrix from PR #150 closed | **BLOCKED** |
| Exact PR head CI and Mobile CI evidence | Recorded in PR Checks and linked validation document |

Owner acceptance of commercial/legal risk does not manufacture permission and cannot be
reported as closure of 5.2.1. GO requires documented resolution of the content questions,
accurate candidate/metadata/screenshots and completed required acceptance checks. Apple
retains its review decision. **Present outcome: NO-GO for release/resubmission.**

## 7. Boundaries and verification

See [validation record](m7-ios-review-1-validation.md) and PR checks for exact results.
Historical M5 validation reports remain intact and are not evidence for this PR head.

No game data/trees, public schemas, OTA catalog/keys, runtime UI, Android 1.1.0 (6), iOS
version/build or bundle identifier changed. No signed archive, ASC upload, Apple response,
review submission, publication, rightsholder message or merge was performed. CI exercises
existing tests; it does not authorize release. Follow-up content work is planned separately.
