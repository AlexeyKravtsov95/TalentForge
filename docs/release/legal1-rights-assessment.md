# M5.LEGAL.1 — Rights assessment: sources, risk matrix and open questions

| | |
|---|---|
| Milestone | M5.LEGAL.1 (research, **not a legal opinion**). Nothing here replaces advice from a qualified lawyer |
| Basis | inventory [legal1-content-inventory.md](legal1-content-inventory.md) on `main` @ `aaf4b60` |
| Sources read | 2026-10-19, live pages (§2). Two key passages were re-read independently: the Blizzard EULA §2 and §1.C/§1.D, and RuStore rules 2.13, 4.1, 4.2, 4.5, 4.6 and 6.4 |
| Rule of this document | a use is called permitted only where an applicable primary source grants it. Where no grant was found, the status is **NOT ESTABLISHED**. Neither "free", nor "available for download", nor a disclaimer, nor a redraw is treated as a permission |

## 1. Findings in one page

1. **No Blizzard document read for this audit grants permission to redistribute client-extracted icons
   or game text in a standalone third-party mobile app.** The EULA restricts copying, derivative works,
   data mining and transferring components (§3.1). The Legal FAQ, the trademark guidelines, the Video
   Policy, the Fan Art terms and the UI Add-On policy each cover only their own activity, and **none
   mentions mobile apps** (§3.2–§3.5). Permission for categories 1–4 is therefore **NOT ESTABLISHED**.
2. **The Developer API Terms are the only Blizzard licence that covers distributing game data to end
   users through a third-party application**, and only for data retrieved through the API by a
   registered application, under conditions that conflict with TalentForge's offline bundling, for
   example a 30-day refresh (§3.4). They do not cover the data TalentForge has now, which came from
   wago.tools.
3. **wago.tools publishes no terms, licence or ownership statement** (§3.6). It is a third-party
   mirror. It cannot grant rights in Blizzard content, and it does not claim to.
4. **Both stores require a lawful basis for third-party IP and can demand proof**: RuStore 4.1/4.2
   with the Developer Agreement §4.2–4.3 and §9; AppGallery 9.1–9.5, §10 and the complaints
   procedure (§4). With no document of rights, the only answer the project could give a moderator
   or a complaint today is an argument, not a licence.
5. **The source is a beta product.** The data is from `wow_classic_beta` 1.60.1. Blizzard's official
   page announces the "World of Warcraft: Forever" beta (2026-09-17, running through October 21) and
   mentions no confidentiality. The EULA's beta clause imposes confidentiality only when Blizzard
   announces a confidential test (§3.7). That 1.60 *is* the Forever beta rests on wago.tools' build
   table, not on a Blizzard statement.
6. **Distribution is already happening outside the stores**: through the public repository and its
   history, 31 public CI APK artifacts, and a publicly readable OTA staging bucket (inventory §5).
   Store release is not the first distribution act.
7. **The nine class paintings have no recorded author or licence** (LR-3). The audit found no evidence
   either way. They are the only bundled artwork that is not a client file, yet they cannot be
   called original until their provenance is recorded.

## 2. Sources

All read on 2026-10-19. "Date" is what the page itself shows.

| # | Source | URL | Date shown |
|---|---|---|---|
| B1 | Blizzard End User License Agreement | https://www.blizzard.com/en-us/legal/fba4d00f-c7e4-4883-b8b9-1b4500a402ea/blizzard-end-user-license-agreement | last revised 2024-03-21 |
| B2 | Blizzard Legal FAQ | https://www.blizzard.com/en-us/legal/c1ae32ac-7ff9-4ac3-a03b-fc04b8697010/blizzard-legal-faq | not shown |
| B3 | Blizzard Copyright Notices | https://www.blizzard.com/legal/9c9cb70b-d1ed-4e17-998a-16c6df46be7b/copyright-notices | not shown |
| B4 | Blizzard Entertainment Trademark Usage Guidelines (also published as "Logo and Trademark Guidelines" with the same text) | https://www.blizzard.com/en-us/legal/38fd0408-8431-469a-99bc-2cd9eb9462c8/blizzard-entertainment-trademark-usage-guidelines · https://www.blizzard.com/en-us/legal/8bcb0794-6641-4ce3-a573-8eb243bab342/blizzard-entertainment-logo-and-trademark-guidelines | not shown; not linked from the current legal index |
| B5 | Blizzard Developer API Terms of Use | https://www.blizzard.com/en-us/legal/a2989b50-5f16-43b1-abec-2ae17cc09dd6/blizzard-developer-api-terms-of-use | last updated 2019-10-01 |
| B6 | WoW Game Data API documentation (media endpoints) | https://community.developer.battle.net/documentation/world-of-warcraft/game-data-apis | not shown |
| B7 | Blizzard Video Policy | https://www.blizzard.com/en-us/legal/dd76b654-f2c4-4aaa-ba49-ca3122de2376/blizzard-video-policy | not shown |
| B8 | Terms and Conditions of Fan Art Submissions | https://www.blizzard.com/en-us/legal/a6def1da-48b1-41ba-8e8c-8b849efa1f24/terms-and-conditions-of-fan-art-submissions-to-blizzard-entertainment | not shown |
| B9 | WoW UI Add-On Development Policy (forum post) | https://us.forums.blizzard.com/en/wow/t/ui-add-on-development-policy/24534 | thread 2018-11-19; policy from 2009 |
| B10 | "The World of Warcraft: Forever Beta Now Live" | https://news.blizzard.com/en-us/article/24304160/the-world-of-warcraft-forever-beta-now-live | published 2026-09-17 |
| B11 | Blizzard legal index | https://www.blizzard.com/en-us/legal | — |
| W1 | wago.tools (home, `/db2`, `/apis`, `/branding`) | https://wago.tools/ | footer "© 2021 - 2026 Wago Tools"; `/terms`, `/tos`, `/legal`, `/about`, `/privacy-policy` return 404 |
| W2 | wago.io Terms of Service (the sister site; does not mention wago.tools) | https://wago.io/terms-of-service | last updated 2021-03-16 |
| R1 | RuStore moderation rules, "requirements for apps" | https://www.rustore.ru/help/developers/publishing-and-verifying-apps/requirement-apps | no edition date shown; it cites a 2026-03-01 law, so the edition is 2026 or later. M5.STORE.1 recorded HTTP `Last-Modified` 2026-10-15 |
| R2 | RuStore Developer Agreement | https://www.rustore.ru/help/legal/devs/developers-agreement | edition of 2026-08-03 |
| H1 | AppGallery Review Guidelines (index; version history) | https://developer.huawei.com/consumer/en/doc/app/50104 · https://developer.huawei.com/consumer/en/doc/50141 | last updated 2023-11-09; current major revision valid from 2022-10-01 |
| H2 | AppGallery Review Guidelines §9 Intellectual Property Rights | https://developer.huawei.com/consumer/en/doc/app/50104-09 | last updated 2023-08-23 |
| H3 | AppGallery Review Guidelines §1 App Information | https://developer.huawei.com/consumer/en/doc/app/50104-01 | last updated 2026-01-14 |
| H4 | AppGallery Review Guidelines §10 App Qualifications, §11 Developers' Behavior | https://developer.huawei.com/consumer/en/doc/app/50104-10 · https://developer.huawei.com/consumer/en/doc/app/50104-11 | 2023-08-23 · 2025-05-21 |
| H5 | Guidelines for Handling AppGallery Infringement Complaints | https://developer.huawei.com/consumer/en/doc/app/50120 | last updated 2024-09-09 |

**Not found or not verified:** a current Blizzard "Fan Content", "Fan Site" or "Fan Site Kit"
policy (the EULA refers to "Blizzard's Fan Policies" but defines and links none; the old
`classic.battle.net/fsp.shtml` Fan Site Program page was seen only in search results and not
read); a separate Blizzard beta test agreement or Forever NDA (none on the legal index B11 or in
B10); any Blizzard statement that maps the client branch 1.60.1 to "Forever".

## 3. Blizzard sources: what applies to TalentForge

### 3.1 EULA (B1)

| Clause | What the text says (paraphrase) | Relevance |
|---|---|---|
| §1.B Grant of License | a limited, revocable, non-exclusive licence to use the Platform for personal, non-commercial entertainment, except where the Agreement specifically allows more | distributing client content to the public is outside this grant unless another clause allows it |
| §1.C.i Derivative Works | no copying or reproducing (beyond §1.B), translating, reverse engineering, modifying, disassembling or decompiling, and no derivative works based on the Platform | icons are pixel-exact copies of client textures; descriptions are client text; the dataset is a structured copy of client tables |
| §1.C.vi Data Mining | no unauthorised process or software that intercepts, collects, reads or "mines" information generated or stored by the Platform; Blizzard may allow certain third-party interfaces | describes how client data is obtained (here by wago.tools, then fetched by the project's pipeline). Whether the clause binds the project, which did not mine the client itself, is a legal question (§6) |
| §1.C, transfers | no transferring of any copy of the Platform or "component thereof" in a way not expressly authorised | redistribution of client files (icons, BLP fixtures) |
| §2 Blizzard's Ownership | Blizzard owns or licenses the Platform's content, including artwork, names, and "all data and communications generated by" it. Users may not create works based on the Platform except as the Agreement, contest rules, **"Blizzard's Fan Policies"** or addenda allow | the only exception route the EULA names is "Fan Policies", and no current document by that name was found |
| §1.D.ii.3 Beta testing | testers are designated by Blizzard. Confidentiality applies **only if Blizzard labels the test confidential**, and then covers game details such as classes, combat and items. Access to a beta may not be commercially exploited. Testers must delete pre-release materials when the test ends | see §3.7 |

**Q1 answer for the EULA: EXPRESSLY RESTRICTED** for copying and transferring client components
and for works based on the Platform; the only named exception route (Fan Policies) is **NOT
ESTABLISHED**.

### 3.2 Legal FAQ and copyright notices (B2, B3)

- The fansite answer grants a personal, **non-commercial, home** licence to one copy of material
  downloaded *from Blizzard's site*. The licence is non-transferable and revocable, keeps copyright
  notices, and bars decompiling or reverse engineering. It does not cover client files and does not
  mention apps.
- Sites must carry copyright and trademark notices. B3 gives the World of Warcraft notice line.
- The FAQ says Blizzard does not enter into licensing agreements with individuals (stated for
  merchandise; the FAQ gives no route for other individual licence requests).
- **Apps are not mentioned.** Answer: **NO EXPRESS PERMISSION FOUND.**

### 3.3 Trademark guidelines (B4)

- The marks may be used only for activities listed in Blizzard's User Generated Content section
  whose own policy references these guidelines. Today that section lists the Video Policy, Fan Art
  terms, Warcraft III Art Tools EULA, DOTA usage and the Custom Game AUP. **No app activity is
  listed.**
- Non-commercial use only, unless the activity policy says otherwise. Use ™/® on first use plus the
  credit line. Do not combine a mark with your own name, do not put it in a domain or a trademark
  application, and do not imply sponsorship without written authorisation. Consent can be
  withdrawn at any time.
- **Relevance:** the nominative listing (Variant B) and any "World of Warcraft" in the name or icon.
  The guidelines contain no "not affiliated" disclaimer wording, so the project's drafted disclaimer
  is a project choice, not a Blizzard-prescribed safe harbour. Answer: **NO EXPRESS PERMISSION
  FOUND** for an app.

### 3.4 Developer API Terms (B5, B6)

- **Licence:** a revocable, non-transferable licence to implement the APIs in a **registered**
  application and to distribute **Data retrieved through the APIs** to end users for their personal
  use. The listed application types are "client libraries, desktop applications, services … scripts
  and applications and/or utilities"; mobile apps are not named but not excluded.
- **Conditions:** no premium versions, download fees, donation interstitials or forced ads for
  API-based features. Do not sell or license the Data. Identify Blizzard as the source without
  implying endorsement; **no Blizzard trademark in the application's title or URL**. A privacy
  policy is required. Refresh or delete Data **at least every 30 days**. Blizzard may terminate at
  any time, after which all Data must be deleted. Outside authorised API use, the terms prohibit
  scraping or extracting data from Blizzard games.
- **Media:** the terms have no clause on images or icons, but the Game Data API exposes media
  endpoints (spell, class, specialisation, talent media; B6). Whether media URLs are "Data" is not
  stated; treating them as Data is a reading, not a grant.
- **Applicability to TalentForge today:** **NOT APPLICABLE**. The project has no registered
  application, its data was not retrieved through the API, and the terms do not extend to data
  obtained elsewhere. Whether the API serves Forever / Classic 1.60 data at all is **NOT ESTABLISHED**.
- **As a future route:** the only licence found that addresses third-party apps distributing game
  data, but the 30-day refresh and the "dynamically available" expectation conflict with
  TalentForge's bundled-offline design and with fixed historical datasets for saved builds.

### 3.5 Video, fan art, add-on policies (B7, B8, B9)

Each is limited to its own activity: community videos (non-commercial), art made **for submission
to Blizzard's Fan Art Program**, and in-game UI add-ons (free, open code, no ads). **None addresses
standalone or mobile apps or the redistribution of client assets.** Answer: **NO EXPRESS PERMISSION
FOUND**. The fan-art terms matter for the class paintings only if they depict Blizzard characters
or art, and then they do not help, because the licence is for submissions to Blizzard.

### 3.6 wago.tools (W1, W2)

wago.tools has no terms, licence or ownership statement (relevant pages 404). Its footer claims
copyright only in general terms. The wago.io terms cover only wago.io, require users to follow
Activision Blizzard's policies and offer a DMCA address. **Using wago.tools gives TalentForge no
rights in the data**; it is only the transport. The project's own reliance on wago.tools (automated
CSV and file requests) is not governed by any published wago.tools terms, so it is **NOT ESTABLISHED**
either way.

### 3.7 The beta source

- Repository facts: `GameConfig.products = ("wow_classic_beta",)`; build `1.60.1.70450` of that
  product ([forever-dataset.md](../forever-dataset.md)).
- B10 (2026-09-17) announces the World of Warcraft: Forever beta, open through October 21, with
  invitations including fansites. It contains no confidentiality statement. wago.tools lists a
  separate `wowf` branch at `1.60.1.70245`.
- EULA §1.D.ii.3 imposes confidentiality only for tests Blizzard labels confidential. **Whether
  any invitation carried such a label: NOT ESTABLISHED.**
- What this adds: the bundled content may describe **pre-release** game content that can still
  change or be withdrawn. Shipping beta content as "the game" also has an accuracy dimension already
  recorded in `forever-dataset.md` (no claim that the realms run these builds).

## 4. Store rules

### 4.1 RuStore (R1, R2)

| Rule | What it requires (paraphrase) | Effect on TalentForge |
|---|---|---|
| 2.13 | no apps that download, distribute or aggregate third-party media (films, music, books, software, **games, mods**) without licences | TalentForge distributes game *assets and text*, not the game. Whether a moderator reads 2.13 that broadly is unknown; it reinforces 4.1 |
| 4.1 | no third-party trademarks, logos or copyright objects in the app, its metadata or store materials **without a lawful basis**; nothing implying affiliation; the developer must hold the rights and **provide proof on request**; RuStore may restrict publication if proof is missing or on a confirmed rightsholder complaint | **central rule** for categories 1–5 and for screenshots |
| 4.2 | licensed or partner use must be documented on request; spot checks possible | no document exists |
| 4.5 | use without rights → rejection or blocking; repeat → account blocking | consequence |
| 4.6 | no presenting an app as official without documented authorisation | the disclaimer helps here, but only here |
| 6.4 / 6.6 | no third-party trademarks in the name or descriptions; icon without third-party marks | Variant A listing complies in form; **Variant B conflicts with 6.4 as written** |
| Agreement §4.2–4.3, §7.4, §9.2–9.5 | the developer warrants its rights and must provide certified rights documents on request; liability rests with the developer; on a claim RuStore may suspend the app; sanctions up to blocking and a **100 000 RUB fine** | financial and account exposure for the owner |

### 4.2 Huawei AppGallery (H1–H5)

| Rule | What it requires (paraphrase) | Effect |
|---|---|---|
| §9 preamble, 9.1 | include only content you created or are licensed to use; third-party copyrighted content needs authorisation | **central rule** for categories 1–5 |
| 9.3 | no unauthorised use of the same or similar trademarks | LR-2 |
| 9.5, 1.15 | no misleading users into believing the app comes from or is related to a third party, by appearance, name or topic; no unauthorised use of a well-known entity's logo, name or content | the "topic" wording means that even a trademark-free name does not remove the question if the app's content is the third party's |
| 1.2, 1.14 | no trademarks in the app name; no "official" | Variant A complies in form |
| §10 | qualification documents must be authentic | only real rights documents can be supplied |
| §11 | on violations: removal, termination, account freeze; the developer bears Huawei's costs, **including compensation paid to rightsholders** | financial exposure |
| Complaints guideline (H5) | complaint forwarded to the developer; **counter-notice within 3 working days** with proof of authorisation or non-infringement; AppGallery may act on preliminary evidence | without a rights document, a counter-notice could only argue non-infringement |

**Q4 answer:** both stores accept third-party IP only on a lawful basis that the developer can
document on request, and both act on rightsholder complaints. Neither store's rules provide a
fan-project, free-app or disclaimer exception.

## 5. Risk matrix

Statuses: **DOCUMENTED**, **NEEDS PERMISSION**, **NEEDS PROVENANCE**, **LEGAL REVIEW**,
**REPLACEMENT CANDIDATE** (several may apply). Risk is the audit's qualitative estimate of
the consequence if a rightsholder or moderator objects (High: likely rejection or removal of
the app, or a takedown; Medium: depends on a legal question; Low: unlikely to be decisive).
**No Blizzard-derived item is DOCUMENTED.**

| Material | Origin | Rights evidence | Status | Risk | Possible action |
|---|---|---|---|---|---|
| Talent icons (367 distinct, 1 790 files, in APK) | client BLP2 textures via wago.tools, pixel-exact PNG | none; EULA §1.C.i/§2 restrict; no fan or app policy found; repository already states Blizzard owns them | NEEDS PERMISSION · REPLACEMENT CANDIDATE | **High** | permission from Blizzard, or replace with original icons (Option B) |
| Verbatim BLP files (2, `tests/fixtures/casc`) | client files via wago.tools | none | NEEDS PERMISSION · REPLACEMENT CANDIDATE | Medium (repository only) | replace with a synthetic BLP2 test texture |
| English description templates and rendered tooltips (469 / 1 328 ranks) | client `Spell.Description_lang` with project-computed numbers | none | NEEDS PERMISSION · LEGAL REVIEW (protectability of short functional rule text) · partial REPLACEMENT CANDIDATE | **High** | permission, or own-worded descriptions generated from mechanics (Option B), with a legal view on what remains |
| Russian names and descriptions (469 / 383) | client ruRU text (Blizzard's translation) | none | NEEDS PERMISSION · LEGAL REVIEW · partial REPLACEMENT CANDIDATE | **High** | as above, in Russian |
| Talent names, English (451 distinct) | client `SpellName` | none | LEGAL REVIEW (short names / titles; trademark questions for distinctive ones) | Medium | keep with a legal view, or replace; replacing breaks recognition of the planner |
| Class names (9, EN/RU) | common words used by the game | none needed if common words; not verified | LEGAL REVIEW | Low | keep unless legal review says otherwise |
| Tree structure and numbers (positions, ranks, gates, edges, values) | client trait tables | none | LEGAL REVIEW (facts and game rules vs. protection of a complete structured dataset, e.g. database-maker rights; contractual EULA questions) | Medium | needed for the product's function; cannot be replaced without losing accuracy |
| Raw client-table CSV fixtures (1 381 at `HEAD`, more in history) | wago.tools exports | none | NEEDS PERMISSION · LEGAL REVIEW · REPLACEMENT CANDIDATE (reduce to minimal rows or synthetic) | Medium (repository only; they contain descriptions) | trim or synthesise; history needs a separate decision (§7) |
| "Forever" label in the UI | project label equal to Blizzard's product name "World of Warcraft: Forever" (B10) | none | LEGAL REVIEW (LR-2) · REPLACEMENT CANDIDATE | Medium | rename the display label (e.g. a neutral data-version label) if legal review asks |
| "World of Warcraft" in README / listing Variant B | project text | B4 permits no app use; RuStore 6.4 bans marks in descriptions | LEGAL REVIEW (LR-2) | Medium (Variant B: **High** on RuStore) | keep Variant A; README wording is a repository question |
| Nine class paintings (in APK) | **unknown** | none recorded (LR-3) | **NEEDS PROVENANCE** · REPLACEMENT CANDIDATE | **High** until resolved | owner records author, tool and licence; if not provable, replace |
| App icon / store icon | AI-assisted concept, owner-approved package | package checksums; the required rights review (tool terms, output rights) is not recorded | NEEDS PROVENANCE | Low–Medium | record the tool, its terms and the prompt/iteration trail |
| Screenshots in `docs/` (412) | captures of the app | inherit icons, text, paintings | REPLACEMENT CANDIDATE | Medium (repository only) | none of them are store assets; decide with §7 |
| Store screenshots (not yet made) | will show categories 1, 3, 5 | — | NEEDS PERMISSION (inherits) | **High** | make them after the content decision |
| Store texts, Variant A | project-written | no third-party material found | — (no status needed) | Low | keep |
| OTA staging bucket (public, 2 releases) | project upload | none | NEEDS PERMISSION | **High** (active public distribution today) | owner decides whether to make it private or empty (§7) |
| Public CI APK artifacts (31) | CI upload | none | NEEDS PERMISSION | Medium | owner decides on deletion and on artifact visibility/retention (§7) |
| Future OTA production releases | same content as the APK | none | NEEDS PERMISSION | **High** | follows the content decision; never publish unresolved content |
| Third-party code licences (LR-4) | open-source components | licences in `files/legal` | DOCUMENTED (separate sign-off, LR-4) | Low | out of scope here |

## 6. Questions only a lawyer can answer (LEGAL REVIEW)

These are listed so that a specialist can be briefed; the audit takes no position on them, and
the statutory references are pointers that were **not** re-read for this audit.

1. **Protectability of the text.** Are short functional rule descriptions ("Increases the damage …
   by 12%") and short talent names protected works under Russian law (Civil Code part IV,
   art. 1259) and in the other jurisdictions where the stores distribute? Does the quotation or
   informational free-use exception (art. 1274) cover a whole-corpus reproduction (it plausibly does
   not, but this is for the lawyer)?
2. **Facts and data.** Art. 1259(5) excludes ideas, methods and systems; are the tree numbers and
   layout "facts of the game rules"? Does the complete structured copy engage a database maker's
   right (art. 1333–1336) or an equivalent elsewhere?
3. **Icons.** Is any exception arguable for pixel-exact copies of artwork? The audit assumes none.
4. **Contract.** Does the EULA (§1.C.vi data mining, §2) bind the project when the data was
   extracted by a third party (wago.tools) and fetched from it? Does it bind the owner as a
   Battle.net account holder?
5. **Beta.** Did any Forever beta invitation carry a confidentiality notice, and does that matter
   for content now public?
6. **Trademarks.** "Forever" as a label, talent names, class names and "World of Warcraft" in the
   README: what is nominative use, and in which jurisdictions?
7. **Liability.** What is the owner's exposure for the repository, the CI artifacts and the staging
   bucket, which are public today, separately from the store release?

## 7. The public GitHub repository

| Fact (inventory §5.1) | Assessment |
|---|---|
| The repository is public and holds categories 1–4 twice, 1 381 raw table CSVs, 2 BLP files and 412 screenshots | this is publication of the same material, independent of any store. The NEEDS PERMISSION statuses above apply here too |
| Git history keeps all of it since 2026-09-26, plus 42 history-only CSV paths | removing files at `HEAD` leaves the history public; clones and forks (0 today) cannot be recalled |
| 31 public debug-APK artifacts (until 2027-01-17) | each is a full app with categories 1–6 |
| No `LICENSE` | the project grants no rights in its own code; this does not affect the game material, which the project could not license anyway |

**Options for the owner** (none was taken by this audit):

| # | Option | Effect | Cost and caveats |
|---|---|---|---|
| G1 | keep as is | none | exposure continues; inconsistent with any store-side caution |
| G2 | make the repository **private** | stops new public access to `HEAD`, history and artifacts | existing clones and caches remain; private-repository CI minutes and artifact storage limits apply; reversible |
| G3 | remove game material from `HEAD` (data pipeline fetches into a git-ignored cache; CI uses synthetic fixtures) | stops adding new copies | history still public; large engineering change (tests are built on real fixtures by design) |
| G4 | rewrite history (`git filter-repo`) and force-push | removes game material from the repository's history | destructive, breaks every SHA referenced in the docs and PRs; GitHub may keep cached views until support purges them; needs explicit owner approval |
| G5 | delete the public CI artifacts and lower retention or stop uploading the debug APK | removes 31 public APKs | reversible for the future only; small change |
| G6 | make the staging bucket private or empty it | stops the current public distribution of releases A/B | breaks the staging E2E (M5.OTA.4b) until re-enabled with access control; the bucket's owner action |

Whatever release option is chosen ([legal1-release-options.md](legal1-release-options.md)), the
audit suggests G5 + G6 (cheap, reversible, and they stop public copies nobody needs) and G2 while
LR-1 is open; G3 belongs to Option B; G4 only on legal advice. These are owner decisions.

## 8. What this audit did not establish

- No permission and no prohibition by a court or authority. Every NOT ESTABLISHED above is a gap in
  the sources found, not a finding of infringement.
- Blizzard's enforcement practice towards fan tools (websites, add-ons, apps) was not researched;
  practice is not permission.
- Laws outside Russia were not analysed.
- No contact with Blizzard, wago.tools, RuStore or Huawei was made.
