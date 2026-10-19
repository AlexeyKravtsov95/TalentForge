# M5.GALLERY.1 — Store listing draft (English)

| | |
|---|---|
| Status | **DRAFT** — owner approval and legal review (LR-1, LR-2) required before use |
| Stores | Huawei AppGallery (EN localization) · RuStore (EN is secondary; the RuStore card description must be Russian, see the [RU draft](store-listing-ru.md)) |
| Scope rule | Describes only what `main` @ `f7494e1` does. No Legacy, gear, auction prices, quests, cloud sync or web share links. Feature claims re-checked against `b242cd6` by M5.STORE.1. |
| ⚠ M5.STORE.1 | Since M5.OTA.3 the APK **declares `INTERNET`** (OTA disabled; no connection measured). The sentence "The app does not request internet access." was therefore removed from §4.1 and replaced by ⟨NET-1⟩. Choose the wording from [store1 audit §5.5](../store1-release-audit.md#55-replacement-texts-drafts-for-ownerlegal-review) after the owner's NET-1 decision; it is true again only if `INTERNET` is removed from the release build and re-verified (option B). |
| Related | [assets-checklist](assets-checklist.md) · [privacy-and-compliance §7](privacy-and-compliance.md#7-intellectual-property-and-trademarks) · [testing-and-publication §2.4](testing-and-publication.md#24-store-listing-fields) |

## 1. Limits this copy is written against

| Field | AppGallery | RuStore | This draft |
|---|---|---|---|
| App name | ≤ 30 characters; no `-`, `&`, `*`, brackets or emoji; equal to the installed label | ≤ 30 characters; the same on the store card and on the device | `TalentForge` (11) |
| Short text | "Brief introduction" ≤ 80 (≤ 25 advised) | short description ≤ 80 | §3 |
| Full text | ≤ 8000 | ≤ 4000 | §4, kept ≤ 4000 so one text fits both |
| Release notes | "New features" ≤ 500 | "What's new" | §5, ≤ 500 |
| Other app platforms | must not be named (AppGallery 1.16) | no links to other stores (RuStore 6.4) | none named |
| Third-party marks | must not imply a relationship (AppGallery 1.15, 9.5) | not in the name or descriptions (RuStore 6.4) | **Variant A (default) names no game, publisher or class**; Variant B only after legal review |

Character counts were measured with Python `len()` on the exact text.

## 2. App name

`TalentForge` — identical to the APK label (`android:label="TalentForge"`) in every language.

## 3. Short description / brief introduction

| Use | Text | Characters |
|---|---|---|
| RuStore short description, AppGallery brief introduction | "Offline talent build planner: plan, save and share builds as codes." | 67 |
| AppGallery brief introduction (≤ 25 advised) | "Offline talent planner" | 22 |

## 4. Full description

### 4.1 Variant A — trademark-free (default for submission)

```text
TalentForge is an offline planner for class talent builds. Pick a class, spend points on its talent tree and keep every build on your device.

PLAN
• Nine classes, each with a full talent tree divided into three sections.
• Tap a talent to select it. Spending and refunding use clear buttons, never hidden gestures. Max out a talent in one step.
• See each talent's description for its current and next rank, what it requires and how many points its tier needs.
• Jump to the talent that unlocks another one.
• Undo, redo and reset talents.
• Drag to move around the tree and pinch to zoom. Works in portrait and landscape, on phones and tablets.

KEEP
• Builds are saved automatically on your device.
• Continue editing where you left off from the home screen.
• Rename, duplicate and delete builds.

SHARE
• Share a build as a short text code through any app on your device, or copy the code.
• Import a code and preview the build before it is saved. An imported build never overwrites your other builds.

PRIVATE AND OFFLINE
• No account, no ads, no analytics.
• ⟨NET-1: network sentence per the owner's decision, see the M5.STORE.1 note at the top⟩ Your builds stay on your device.

LANGUAGES
• English and Russian interface and talent texts.

Talent data is bundled with the app, and the data version is shown in the app. New talent data arrives with app updates.

TalentForge is an independent, unofficial project. It is not affiliated with, endorsed by or sponsored by the game's developer or publisher.
```

### 4.2 Variant B — nominative (use **only** if legal review LR-2 approves)

Replace the first paragraph and the last line of Variant A with:

```text
TalentForge is an unofficial talent build planner for World of Warcraft. Pick a class, spend points on its talent tree and keep every build on your device.
```

```text
TalentForge is an independent, unofficial project and is not affiliated with Blizzard Entertainment. World of Warcraft and Blizzard Entertainment are trademarks or registered trademarks of Blizzard Entertainment, Inc. in the U.S. and/or other countries.
```

and, optionally, the classes bullet with: "• Nine classes: Warrior, Paladin, Hunter, Rogue, Priest, Shaman, Mage, Warlock and Druid, each with a full talent tree divided into three sections."

RuStore rule 6.4 forbids third-party trademarks and names in descriptions, so Variant B is **not**
suitable for RuStore unless the owner holds documented rights (rules 4.1 and 4.2).

## 5. Release notes (first public version)

```text
First release.
• Plan talent builds for nine classes, fully offline.
• Builds save automatically: rename, duplicate and delete them.
• Share a build as a code or import one with a preview.
• English and Russian.
```

## 6. Categories (owner decision)

| Store | Proposal | Note |
|---|---|---|
| AppGallery | category type **App** → *Tools* → *Tools* | The App/Game choice cannot be changed later; Huawei may re-categorise levels 2/3 |
| RuStore | a tools/utilities category — pick the exact name in the console | RuStore's category list was not checked in this milestone (UNVERIFIED) |

## 7. Screenshot sequence and captions

Portrait phone set only (RuStore allows one orientation per set). Every screenshot is a capture of
the **running signed release build**. Captions are optional overlay text placed **outside** the app
capture, never drawn onto the app UI. Capture rules and sizes: [assets-checklist §3](assets-checklist.md#3-screenshots).

| # | Screen and state | Caption (EN) |
|---|---|---|
| 1 | Talent editor, portrait, a talent selected, inspector visible with its + / − buttons | "Plan every talent point" |
| 2 | Inspector expanded: current and next rank description, a requirement with **Show** | "Every rank explained" |
| 3 | "Choose a class" (New build) | "Nine classes" |
| 4 | Home with the CONTINUE and RECENT sections | "Builds save automatically" |
| 5 | "Share build" sheet with the code and the Share / Copy code buttons | "Share a build as a code" |
| 6 | Import: build preview before saving | "Preview before you import" |
| 7 (optional) | "Builds" with the ⋯ action sheet (Rename · Duplicate · Share · Delete) | "Rename, duplicate, delete" |

Screenshots 1–3 show game icons, talent text and class artwork, so they fall under LR-1 and LR-3.

## 8. App icon

Use the approved M5.RC.2a store icon `docs/release/rc2a-evidence/source/TalentForge-RuStore-512.png`
(512×512, opaque RGB PNG, 181 KB) for both stores. Details: [assets-checklist §2](assets-checklist.md#2-icon).

## 9. Other listing fields

| Field | Value | Owner |
|---|---|---|
| Price | Free, no in-app purchases | — |
| Contains ads | No | — |
| Developer contact (RuStore: at least one; AppGallery: service information) | ⟨e-mail / website⟩ | owner |
| Privacy policy URL | ⟨public URL of the [§8 draft](privacy-and-compliance.md#8-draft-privacy-policy)⟩ | owner |
| Reviewer notes | "Offline app. No account or sign-in. All features work without network access." | — |
