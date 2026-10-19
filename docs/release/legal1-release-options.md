# M5.LEGAL.1 — Release options A / B / C

| | |
|---|---|
| Milestone | M5.LEGAL.1 (research, **not legal advice**) |
| Inputs | [legal1-content-inventory.md](legal1-content-inventory.md) · [legal1-rights-assessment.md](legal1-rights-assessment.md) · [store1-release-audit.md](store1-release-audit.md) |
| Status of the blockers | LR-1…LR-5 stay **OPEN**. No option below closes them by itself |

None of the options is "safe" by construction. A free app, a disclaimer, the absence of
monetisation, or redrawing an icon each reduce one kind of exposure; none of them is a licence,
and none is treated as one below.

## 1. Comparison

| | **A — Existing content** | **B — Reduced IP exposure** | **C — Permission first** |
|---|---|---|---|
| Idea | publish the app as built, once a sufficient right or applicable permission exists | replace the Blizzard material the planner does not strictly need, keep the mechanics exact | publish nothing until a legal opinion or the rightsholder's written permission exists |
| Materials affected | none changed; all of categories 1–5 ship | icons (cat. 1), description text EN/RU (cat. 3), "Forever" label, class paintings if not provable (cat. 5), repository fixtures and screenshots; names (cat. 2) and structure (cat. 4) stay | none changed; distribution channels paused |
| Main work | obtain a document of rights; assemble the proof package for RuStore 4.1/4.2 and AppGallery 9/§10; LR-2 copy; screenshots | original icon set; a description strategy (see §3); contract and pipeline changes in their own milestones; LR-3 record or replacement; new screenshots | legal brief (rights assessment §6); a permission request to Blizzard; G5/G6/G2 decisions; keep engineering on non-content work |
| Remaining risks | permission scope (territories, mobile, stores, OTA, revocation); without a document, A means publishing with **NOT ESTABLISHED** rights | names, structure and the "topic" (AppGallery 9.5/1.15) remain Blizzard-derived → **LEGAL REVIEW** stays open; EULA contract questions; replacement quality and accuracy; a reviewer may still ask for proof | time; the answer may be "no" or never come (the Legal FAQ says Blizzard does not license individuals, stated for merchandise); the public repository, artifacts and bucket remain exposed unless G2/G5/G6 are taken |
| UX impact | none | **significant**: unfamiliar icons; descriptions shorter, numeric or absent; players cannot match in-game tooltips word for word | none (no release) |
| Owner decisions | whether to seek permission; who signs requests; acceptance of residual risk | icon budget and style; description strategy; whether talent names stay; LR-3 outcome; label wording | engage a lawyer; send or not send a permission request; G2/G5/G6 |
| Moderation-ready after the conditions? | **Yes, if** written permission covers mobile distribution through these stores in their territories (and OTA); a legal opinion alone is an argument, not the "license, consent or contract" RuStore 4.1 lists, so acceptance would be at the moderator's discretion | **Not automatically.** It removes the most visible copies (artwork, long text), but the remaining names and mechanics still need a LEGAL REVIEW answer before the proof question can be answered | not applicable until the outcome; then it becomes A (permission granted) or B (refused or no answer) |

## 2. Option A: existing content

**Conditions (all needed):**

1. A rights basis that covers: copies of client icons; client text EN/RU; the dataset; distribution
   through RuStore and AppGallery in their territories; OTA updates from the project's origin; the
   public repository, if it stays public. Only a written permission or licence from the
   rightsholder is evidenced as acceptable by both stores' texts. None of the Blizzard documents
   read (rights assessment §3) is such a basis.
2. LR-3 closed (class paintings), LR-2 copy approved (Variant A listing, disclaimer, "Forever"
   label), LR-4 signed off.
3. A proof package prepared in advance: RuStore asks within a deadline; AppGallery allows
   3 working days for a counter-notice.

**If A is chosen without condition 1** (publishing on an argument), the owner accepts these
risks as written in the store rules: rejection, removal, account sanctions, a 100 000 RUB fine
under the RuStore Developer Agreement §9.5, and Huawei's compensation costs under §11. The audit
records this as a possible owner decision, not as a recommendation.

## 3. Option B: reduced IP exposure

| Material | Replacement | Feasibility notes | Remains Blizzard-derived |
|---|---|---|---|
| 367 talent icons | original icons: per-talent originals, or a smaller set of category glyphs (damage type, school, role) | large art task; category glyphs lose identity; new contract/pipeline work (the visuals contract records `sourceFormat: BLP2` and client hashes, so an original set needs its own contract milestone); OTA and saved builds unaffected (icons are presentation-only) | nothing, if originals are documented (author, tool, licence) |
| English descriptions | (a) show **mechanics only**: per-rank numbers with neutral labels from the effect data; (b) own-worded descriptions written per talent; (c) none, name + numbers | (a) loses meaning for many talents; (b) ≈ 469 texts × 2 languages, written by people who must not copy the game's wording, and with an accuracy review, since the project forbids guessing semantics; (c) worst UX | numbers are game facts (LEGAL REVIEW) |
| Russian names/descriptions | as above, in Russian; names may need to stay recognisable | translation quality vs. recognisability; diverging from in-game Russian terms confuses players | — |
| Talent names | **kept** (a planner cannot work without matching the game) | — | **yes**: LEGAL REVIEW (short names) |
| Tree structure and values | **kept** (the product's function) | — | **yes**: LEGAL REVIEW (facts / dataset) |
| "Forever" label | neutral label, e.g. data version or "Classic 1.60" style descriptor approved by legal | small UI change | depends on wording |
| Class paintings | keep if LR-3 proves authorship; otherwise replace with documented originals or the existing monogram fallback | `ClassArtworkMark` already falls back to a monogram for unknown tokens | — |
| Repository material | synthetic test fixtures; drop BLP fixtures; trim CSVs; consider G2/G4 (rights assessment §7) | tests are built on real rows by design (byte-for-byte regeneration); replacing them is a large test-strategy change | history unless G4 |

**What B does not do:** it does not make the app "cleared". It removes copies of artwork and
long text, the categories where the rights question is most direct. It leaves the app's subject
matter and the data that makes it work. A moderator may still ask for proof of rights for the
remaining material (RuStore 4.1, AppGallery 9.5 "topic"). B needs its own LEGAL REVIEW sign-off
before submission.

**Engineering shape (for planning only, not started):** B is several milestones (icon contract and
assets; description strategy and contract; label; fixtures). Each changes public contracts or
bundled data and needs the contract discipline in `CLAUDE.md`, so none of it belongs in this
audit PR.

## 4. Option C: permission first

- **Work:** brief a lawyer with rights assessment §6; draft a permission request to Blizzard
  describing exactly what is used (inventory §1, §4) and where it would be distributed. Sending it
  is an owner action. Meanwhile, decide G2/G5/G6 for the public channels that exist today.
- **Engineering:** continue only on work that does not depend on the content question (privacy
  policy, signing, versioning, device runs: M5.STORE.1 OA-1…OA-12), so that a "yes" can be shipped
  quickly.
- **Exit:** permission → Option A with the document in the proof package. Refusal or silence by a
  date the owner sets → Option B, or no release.
- **Risk:** delay; and C does nothing about the existing public copies unless G2/G5/G6 are taken.

## 5. Owner decisions

| # | Decision | Needed for |
|---|---|---|
| L-1 | Choose the path: A (with a document), B, C, or C → B with a deadline | everything below |
| L-2 | G6: make the OTA staging bucket private or empty it (it serves client icons and text to anyone today) | stopping current public distribution |
| L-3 | G5: delete the 31 public debug-APK artifacts and stop uploading or shorten retention | idem |
| L-4 | G2: repository visibility while LR-1 is open; G4 only on legal advice | idem |
| L-5 | LR-3: record the author, method/tool, date and licence of the 9 class paintings (or decide to replace them) | any release |
| L-6 | Record the rights review of the AI-assisted app icon (tool, terms, output rights) | any release; AppGallery AI declaration |
| L-7 | Engage a lawyer with the §6 questions; decide whether to contact Blizzard | A, C |
| L-8 | For B: icon approach, description strategy, whether names stay, label wording | B |

## 6. Recommended order of work

1. **Now, independent of the option:** L-2 and L-3 (cheap and reversible, and they stop public copies
   that serve no current purpose); L-5 and L-6 (records only the owner has).
2. **Treat C as the current state.** The app is not published, and nothing in this audit
   establishes a right to publish categories 1–4. Brief the lawyer (L-7) with rights assessment §6
   and this document.
3. **In parallel, scope B** (a planning document, no implementation) so that the cost of B is known
   when the legal answer arrives: icon count and approach, the description strategy and its
   accuracy review, contract changes.
4. **Decide L-1** on the legal answer:
   - permission in writing → A;
   - a legal opinion that names and mechanics are usable but artwork and text are not → B;
   - neither → no release, or B with an explicit acceptance of the residual LEGAL REVIEW risk by
     the owner.
5. Only then: screenshots, final listings and the remaining M5.STORE.1 steps (its §11 order still
   applies after this step).

## 7. Not decided here

- No option is chosen; L-1 belongs to the owner.
- No material was replaced, deleted or unpublished; no history was rewritten; repository and
  bucket visibility are unchanged.
- Nothing was sent to Blizzard, wago.tools, RuStore or Huawei.
