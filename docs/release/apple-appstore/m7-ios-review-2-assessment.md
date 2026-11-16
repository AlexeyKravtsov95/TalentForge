# M7.IOS.REVIEW.2 — final metadata decision

**Resubmission: NO-GO.** Text remediation is ready for review in this repository;
neither the existing images nor bundled third-party content have a documented rights
basis. The current ASC Content Rights answer is inconsistent with that evidence.
Build **1.0.0 (4)** remains selected, but resubmission is **not currently enabled**.

Date: **2026-11-17, Asia/Novosibirsk**. Base: `main` at
`502facedf853ec48f79bd763a04c5e03f76d1e24`, merged [PR #151](https://github.com/AlexeyKravtsov95/TalentForge/pull/151).
This is a delta to [REVIEW.1](m7-ios-review-1-assessment.md), not a replacement legal
investigation. Its R2–R8 rights/provenance and native acceptance findings remain open.

## Evidence added since REVIEW.1

The existing authenticated Chrome ASC session was inspected **read-only**, approximately
01:25–01:31 local time (18:25–18:31 UTC on November 16). No fields were filled or saved,
no review-update/resubmit controls used, and no messages or assets sent. The
[observation record](evidence/m7-ios-review-2/asc-read-only-observation.json) separates
observed values from inference. It is a selective UI transcription, not an API export
or a cryptographically authenticated store snapshot. Review contact details are excluded.

- App **6826780044**, submission **0a3acce7-75fb-4f82-9f93-54edf5310eb6**:
  submission has unresolved issues, the sole version item is rejected under **4.1.0**.
- Original message: review date November 9, 2026; iPad Air 11-inch (M3);
  version reviewed **1.0 (4)**. Apple identifies potentially misleading third-party
  references in metadata resembling WoW without demonstrated authorization. It requests
  documentary evidence if rights exist, otherwise removal of third-party metadata content.
  Individual offending fields/images and any in-app scope are **not enumerated**.
- Latest Apple follow-up asks for adjustment and resubmission; it does **not** accept the
  app, grant permission, identify specific images, or require a new binary.
- ASC still has the rejected branded texts, **not** PR #151's neutral proposed copy.
  EN/RU iPhone slots each have 6 screenshots; iPad each 3; Duo each **0**. The repository's
  24 Duo images are prepared assets, not uploaded assets. Filename/count agreement for
  iPhone/iPad is not byte identity with local files; remote originals were not downloaded.
- App Information says the app has necessary third-party rights. This is an **existing
  developer declaration**, not documentary proof, and cannot close the repository's gates.
- Store version is **1.0**; the selected binary is **1.0.0 (4)**. Preserve that distinction.
  A shared build number on recent simulator captures does not identify the uploaded binary.

## Final comparative risk matrix

“Current ASC” below means the rejected listing observed above. “PR #151” means the
repository proposal already reviewed; no store application of it is assumed. Full proposed
copy lives in [field files](submission/metadata/). Risks are review judgments, not legal
determinations. All rows remain subject to Apple's decision.

| Field/material | Current ASC / PR #151 baseline | Specific Apple risk | Final change prepared here | Effect on understanding | Rights / additional confirmation |
|---|---|---|---|---|---|
| Name EN | ASC `TalentForge: WoW Forever`; #151 `TalentForge` | 4.1(a)/(c), 2.3.7: another product's identity used in the name | Keep #151 `TalentForge` | Planner purpose moves to subtitle | Check availability when separately authorized to edit ASC; no automatic fallback to old name |
| Name RU | Same branded ASC name; #151 `TalentForge` | Same | Keep #151 `TalentForge` | Same independent identity in both locales | Same |
| Subtitle EN | ASC `Talent builds for WoW: Forever`; #151 `Talent Build Planner` | 4.1(a), 2.3.7: branded discovery positioning | Keep #151 neutral subtitle | Explicit talent planning, no universal-game claim | Owner editorial acceptance and ASC reconciliation |
| Subtitle RU | ASC `Билды талантов WoW: Forever`; #151 `Планировщик талантов` | Same | Keep #151 neutral subtitle | Same function in Russian | Same |
| Promotion EN | ASC starts `Plan talent builds for all nine classes of World of Warcraft: Forever.`; #151 starts `Edit talent builds, search and sort your library` | 4.1(a), 2.3/2.3.7: branding and features added after this binary's review | 146-character feature text limited to included data, local saves, codes/import preview and offline available data | Removes unsupported newer-library promises while retaining concrete utility | Verify remaining functions against retained archive/existing install |
| Promotion RU | ASC starts `Планируйте билды талантов для всех девяти классов World of Warcraft: Forever.`; #151 starts `Редактируйте билды талантов, ищите и сортируйте их` | Same | Equivalent 157-character text; remove newer-library claims | Equivalent functions and limits | Same |
| Description EN | ASC repeats full title in opening/data section and trademark attribution; #151 retains it once in opening | 4.1(a), 2.3, 5.2.1: promotional use vs accurate data identity; borrowed content remains | Lead with functions; move the one identifying title to a separate supported-data notice; explicitly state only included datasets, no other games or all-version promise; retain non-affiliation/ownership and identify icons/texts as third-party | Specialized scope and content origin stay visible | **OPEN**: informational placement is not permission. If Apple requires all third-party references/content removed, these texts alone cannot satisfy both truthfulness and rights requirements |
| Description RU | Same title pattern; #151 one title in opening | Same | Equivalent supported-data notice and disclosures in RU | Same restrictions and origin | Same **OPEN** conflict |
| Keywords EN | ASC `planner,wow,calculator,tree,classic,warrior,mage,priest,druid,rogue,hunter,paladin,shaman,warlock`; #151 removes `wow`/`classic` but keeps nine classes | 2.3.7, 4.1(a): branded discovery and unnecessary game-association cluster | `calculator,tree,spec,points,ranks,offline,import,export,code,undo,redo` | Feature discovery without game classes or newer-library claims; game identity still disclosed in description | Common class words are not declared unlawful; conservative editorial removal. 70 UTF-8 bytes |
| Keywords RU | ASC `таланты,планировщик,калькулятор,дерево,маг,жрец,друид`; #151 `билды,калькулятор,дерево,избранное,поиск` | Same discovery concern; #151 keywords also imply newer features | `билды,калькулятор,дерево,импорт,экспорт,очки` | Function-led, no class/brand promotion or newer-library claims | 83 UTF-8 bytes |
| App Icon | Golden branching paths/orbs/crystal on black; no observed game wordmark; ASC thumbnail is visually consistent | 4.1(a)/(c), 2.3.9, 5.2.1: unclear source/input/output rights, originality not proved by appearance | No image change; conditional retention only | Conveys a talent tree, without a text game brand | **OPEN** original author/tool/input/terms/output evidence; see [individual icon assessment](m7-ios-review-2-screenshot-audit.md#app-icon). Changing this binary-owned icon would require a separately planned binary change |
| 12 iPhone PNGs | Game icons/texts, nine class emblems, Forever labels; older Home/continue image named library | 2.3/2.3.3/2.3.9, 5.2.1; 4.1(a) context | Hold; recapture coherent candidate set and actual All Builds | Genuine selected-binary experience; do not add current-main search UI to build (4)'s listing | Rights + selected-binary parity; [12 individual rows](m7-ios-review-2-screenshot-audit.md) |
| 6 iPad PNGs | Same assets, small tablet share/import views; OTA OFF build (1) | Same | Hold; recapture readable real UI from selected candidate, add library coverage | Better legibility without fabricated UI | Rights + candidate parity; six individual rows in audit |
| 24 Duo PNGs | Real Outer/Inner captures with game assets and newer library UI; Debug OTA OFF numeric build (4), captured after the binary's review | Same + 2.3 accuracy of optimization/support claims | **8 library/favorites images require replacement for uploaded (4)**; other 16 conditional retain only after rights/candidate/native acceptance | Avoid presenting post-review library features as part of uploaded (4); no optimization claim | Rights, native acceptance and uploaded-build binding; 24 individual rows in audit |
| Content Rights declaration | ASC says necessary third-party rights exist; REVIEW.1 has no applicable grant | 5.2.1, 2.3: declaration exceeds recorded evidence | Flag contradiction; no ASC edit here. Reconcile truthfully with documented rights outcome before any submission | Disclosure remains honest | **NO-GO**; changing wording or accepting commercial risk does not supply rights |

The relevant current [Apple guidelines](https://developer.apple.com/app-store/review/guidelines/)
require accurate metadata/screenshots, an independent identity, and permission for protected
third-party materials. Apple's [4.1 guidance](https://developer.apple.com/help/app-review/guideline-reference/4-1-prevent-copycat-rejection/)
applies across public metadata, including images. This audit uses those requirements as
risk criteria; it does not infer authorization from a disclaimer or another app's listing.
Sources checked November 17 local time. No new Blizzard policy interpretation or contact.

## Final EN/RU copy and technical review

The complete field files are the single copy source. **Six public files** change
relative to #151: EN/RU descriptions, promotion and keywords, plus the shared Review Notes.
Non-affiliation and ownership
sentences are preserved verbatim; the source disclosure is strengthened, not hidden.
Both locales make the same claims: nine classes/three trees, explicit allocation controls,
local saved builds, import preview/export, offline available data, data-only OTA with signature
checks, and RU-to-EN fallback. Neither promises generic game support or live realm access.

| Field | EN | RU | Limit / result |
|---|---:|---:|---|
| Name | 11 characters | 11 characters | 30 / PASS |
| Subtitle | 20 characters | 20 characters | 30 / PASS |
| Promotion | 146 characters | 157 characters | 170 / PASS |
| Description | 2093 characters | 2237 characters | 4000 / PASS |
| Keywords | 70 bytes | 83 bytes | 100-byte conservative policy / PASS |

[Machine report](evidence/m7-ios-review-2/appstore-metadata-report.json): 18 fields,
45 checks. The validator now rejects EN/RU library search/filter/favorites/sort/grouping
claims for the selected reviewed candidate, with 35 new negative cases across public
fields and Review Notes; positive note requirements use existing rename/duplicate/delete
functions. This change is necessary because #151's required search/sort claims conflict
with the actual review chronology below. No CI configuration changes are needed. These checks
are editorial guardrails, not a semantic proof, Apple name reservation or rights checker.
EN/RU editorial review here is not represented as the owner's approval or ASC acceptance.

The strongest feasible text mitigation retains one factual title reference outside
promotion to identify the actual dataset. **There is no demonstrated metadata-only
solution to the rights conflict.** Deleting the source notice while leaving borrowed
assets would conceal provenance; asserting generic game support would be inaccurate.
If this factual notice or the assets are unacceptable without permission, retain NO-GO
and resolve content/binary scope separately under [REVIEW.1's plan](m7-ios-review-1-content-plan.md).

## Existing build and resubmission route

Apple [allows reuse of a build after a metadata rejection](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/reply-to-app-review-messages).
In this actual record the rejected item still selects **1.0.0 (4)**, its metadata is
editable, and Apple explicitly requests adjusted resubmission. Thus **retaining build (4)
is a supported conditional route** if every required fix is genuinely outside the binary.
Apple has not explicitly classified the entire outstanding scope as metadata-only.

The **Resubmit** button was disabled and the version offered **Update Review Information**.
No change/update was made to test enabling it. **Immediate resubmission availability is
not confirmed; current answer is no.** Do not report that a live resubmit can now succeed.
The [unresolved-issues workflow](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/manage-a-submission-with-unresolved-issues)
requires corrected items before resubmission. In a separately authorized store session,
after all NO-GO gates close: save accurate metadata/approved screenshots, update the rejected
item for review using the then-current UI, verify (4) remains selected and the action is
enabled, and reconcile release settings. Current release setting is **automatic**; that
is an observation, not authorization to publish. Do not cancel the submission, remove
the build or create another app record as a workaround.

**Candidate chronology exposes a concrete metadata mismatch.** The actual Apple review
was November 9. Repository history first adds library search/class filtering on November 10
([`f8f0ccea`](https://github.com/AlexeyKravtsov95/TalentForge/commit/f8f0ccea92ea4c057f39fd9bd457dbb1fc7f7e47)),
favorites on November 11
([`230bbe0a`](https://github.com/AlexeyKravtsov95/TalentForge/commit/230bbe0a1c162cca00c8bf42be5be532a5795aba)),
and UI sorting/grouping on November 14
([`78bcb2db`](https://github.com/AlexeyKravtsov95/TalentForge/commit/78bcb2db5bc54d4725a3c9f88fbcc6dcfb0db1fc)).
These are changes to `AllBuildsScreen.kt`; the build-number bump to 4 dates to October 27
(`b4aa431b`). Thus current-main library features cannot be advertised as part of the
November 9 reviewed binary on the available evidence. Their claims are removed from
both public locales and Review Notes in this PR. Duo library/favorites PNGs show those
newer features despite reusing numeric build 4; they require recapture for uploaded (4).

The uploaded archive's exact source, system-appearance fix, data/OTA mode and icon bytes
still need retained delivery provenance / existing TestFlight install verification; no
new build is authorized here. If retaining those newer library features is required,
use a separately approved new binary milestone. Never screenshot main and label it (4).

Separate new-build plan, only if required:

1. Record which required change affects compiled resources/UI: app icon, embedded game
   artwork/texts/labels, or a feature absent in uploaded (4). Secure the rights/content
   decision before implementation; do not paint over screenshot evidence.
2. Scope an iOS-only follow-up with a verified source-to-archive binding, truthful listing,
   EN/RU runtime checks and native Duo acceptance if claiming support. Preserve Android,
   bundle identity and signing; shared-content changes need their own explicitly approved
   cross-platform scope. REVIEW.1's content plan is not permission to implement it here.
3. Choose a new unique build number and appropriate store/binary version in that follow-up;
   **do not bump anything here**. Run the documented iOS/Native/framework/archive checks,
   verify OTA mode and privacy statements, then capture the actual candidate screens.
4. Owner reviews the evidence, Content Rights answer, final response and release mode
   before any separate upload/resubmission. No archive, upload or contact was made here.

## Two English responses — separate, unsent

- **A — [Clarification request](submission/clarification-request.txt)** targets the
  uncertainty left by the actual message: which fields/images, whether in-app content
  is included, and whether build (4) can be retained. It does not deny the already stated
  WoW/authorization concern or cite other apps as precedent.
- **B — [Resubmission response](submission/resubmission-response.txt)** is a **held draft**
  for the metadata-only route, describing text changes actually made in this package.
  It deliberately makes no claim of ASC upload, content replacement, Blizzard license,
  rights clearance, native gesture acceptance or newly added features. It is **not
  ready to send while NO-GO**. After actual remediation and ASC application, recheck every
  statement and supply the factual rights disposition/supporting evidence separately.
  If the final route changes images/binary, rewrite B to describe those completed changes;
  this draft's unchanged-assets/build statements would no longer apply.

The [REVIEW.1 response](submission/resolution-center-response.txt) is historical and is
superseded for this decision. No canned response can honestly certify future rights
work that has not happened. Sending either message requires a separate owner instruction.

## Decision and remaining blockers

| Gate | Result / evidence needed to close |
|---|---|
| Full rejection available | **Improved**: original and follow-up read; specific field/image and binary scope still unclear |
| EN/RU copy | Technically checked, aligned; owner acceptance/name availability and actual binary parity pending |
| Game icons/texts, class art, icon | **OPEN**, REVIEW.1 R2–R6; rights/provenance evidence or separately completed content remediation |
| Truthful Content Rights declaration | **OPEN**, observed affirmative answer not supported by repository evidence |
| Screenshots | 42 individually reviewed; **26 recapture for uploaded (4), 16 conditional retain**; all held for rights/candidate gates |
| Duo acceptance | **BLOCKED**, existing native multi-touch and remaining functional checks unchanged |
| Same-build route | Conditionally supported by observed selected (4) + Apple workflow; **not executable now**, resubmit disabled |
| Tests / CI | See [fresh validation](m7-ios-review-2-validation.md); never closes rights or store gates |

**NO-GO for resubmission, even with green CI.** This PR is reviewable remediation and
evidence, not a claim that 4.1(a) or IP issues are resolved. No Android, game data/icon/
localization/OTA, app icon, screenshot, signing, bundle ID, version/build or runtime code
changes; no new build, store mutation, Apple/Blizzard message, publication or merge.
