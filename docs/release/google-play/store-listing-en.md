# M5.PLAY.1 — Google Play store listing draft (English, en-US)

| | |
|---|---|
| Status | **DRAFT.** Owner approval and legal review (LR-1, LR-2) are needed before use. Nothing was entered in Play Console |
| Based on | the RuStore/AppGallery drafts ([EN](../appgallery/store-listing-en.md), [RU](../appgallery/store-listing-ru.md)), with the NET-1 = B wording, adapted to Google Play's limits and metadata policy |
| Scope rule | Describes only what this branch does. No Legacy, gear, auction prices, quests, cloud sync or web links |
| Positioning | TalentForge is independent. The text claims no affiliation with, endorsement by or licence from the game's developer or publisher |
| Related | [Russian draft](store-listing-ru.md) · [assets checklist](assets-checklist.md) · [data safety](data-safety.md) · [readiness](readiness.md) |

## 1. Google Play limits and rules this copy is written against

| Field | Google Play | This draft |
|---|---|---|
| App name | ≤ 30 characters | `TalentForge` (11), identical to the installed label |
| Short description | ≤ 80 characters | §3 |
| Full description | ≤ 4000 characters | §4 |
| Release notes ("What's new") | ≤ 500 characters | §5 |
| Metadata policy | no emojis, emoticons or repeated special characters in the title, icon or developer name; no ALL CAPS unless part of the brand; no ranking, performance, price or promotional claims ("#1", "best", "free download"); no unattributed testimonials; no keyword stuffing; no brand names or logos without permission; no "official" without rights | none used; section headings in sentence case, not capitals |
| IP and impersonation policy | do not "imply that your app is related to or authorized by someone that it isn't"; no third-party trademarks without permission | **Variant A (default)** names no game, publisher, class or trademark. Variant B only after LR-2 |

Sources (read 2026-10-19): [Store listing text](https://support.google.com/googleplay/android-developer/answer/9859152),
[Metadata policy](https://support.google.com/googleplay/android-developer/answer/9898842),
[Impersonation](https://support.google.com/googleplay/android-developer/answer/9888374),
[Intellectual property](https://support.google.com/googleplay/android-developer/answer/9888072).

Character counts below are Python `len()` of the exact text inside each block.

## 2. App name

`TalentForge` (11 characters). The same as `android:label` in every language.

## 3. Short description

```text
Offline talent build planner: plan, save and share builds as codes.
```

67 characters.

## 4. Full description

### 4.1 Variant A — trademark-free (default for submission)

```text
TalentForge is an offline planner for class talent builds. Pick a class, spend points on its talent tree and keep every build on your device.

Plan
• Nine classes, each with a full talent tree divided into three sections.
• Tap a talent to select it. Spending and refunding use clear buttons, never hidden gestures. Max out a talent in one step.
• See each talent's description for its current and next rank, what it requires and how many points its tier needs.
• Jump to the talent that unlocks another one.
• Undo, redo and reset talents.
• Drag to move around the tree and pinch to zoom. Works in portrait and landscape, on phones and tablets.

Keep
• Builds are saved automatically on your device.
• Continue editing where you left off from the home screen.
• Rename, duplicate and delete builds.

Share
• Share a build as a short text code through any app on your device, or copy the code.
• Import a code and preview the build before it is saved. An imported build never overwrites your other builds.

Private and offline
• No account, no ads, no analytics.
• The app does not request internet access. Your builds stay on your device.

Languages
• English and Russian interface and talent texts.

Talent data is bundled with the app, and the data version is shown in the app. New talent data arrives with app updates.

TalentForge is an independent, unofficial project. It is not affiliated with, endorsed by or sponsored by the game's developer or publisher.
```

1 465 characters.

Changes from the RuStore/AppGallery text: the section headings are in sentence case instead of
capitals (Play metadata policy). The feature claims are unchanged and were re-checked against this
branch:
- nine classes with three sections each;
- explicit spend and refund buttons (B3 contract);
- undo, redo and reset;
- share and copy;
- import with a preview;
- EN/RU;
- no `INTERNET` permission (NET-1 = B, verified on the Play bundle).

### 4.2 Variant B — nominative (use **only** if legal review LR-2 approves)

Replace the first paragraph and the last line of Variant A with:

```text
TalentForge is an unofficial talent build planner for World of Warcraft. Pick a class, spend points on its talent tree and keep every build on your device.
```

```text
TalentForge is an independent, unofficial project and is not affiliated with Blizzard Entertainment. World of Warcraft and Blizzard Entertainment are trademarks or registered trademarks of Blizzard Entertainment, Inc. in the U.S. and/or other countries.
```

Naming the game helps people find the app in search. Without permission it is also the clearest
case for Play's trademark and impersonation policies. The owner decides only after legal review.
The word "unofficial" does not grant any right: Google documents no safe harbour for fan apps.

## 5. Release notes (first closed-test and production version)

```text
First release.
• Plan talent builds for nine classes, fully offline.
• Builds save automatically: rename, duplicate and delete them.
• Share a build as a code or import one with a preview.
• English and Russian.
```

211 characters.

## 6. Category and tags (owner decision)

| Field | Proposal | Note |
|---|---|---|
| App or game | **App** | TalentForge is a planning tool, not a game. The choice decides the questionnaire paths |
| Category | **Tools** | alternatives: *Books & Reference*. Owner's choice |
| Tags | up to 5 from Play's list, chosen in the console | not checked here |
| Contact e-mail | ⟨owner⟩, required | public on the listing |
| Website | optional | — |
| Privacy policy | ⟨public URL⟩, required ([data safety §3](data-safety.md#3-privacy-policy)) | owner |

## 7. Screenshots and graphics

Requirements and status: [assets checklist](assets-checklist.md). The phone screenshot sequence is the
same as for RuStore/AppGallery
([AppGallery listing §7](../appgallery/store-listing-en.md#7-screenshot-sequence-and-captions)):
1. editor with a talent selected;
2. rank descriptions with **Show**;
3. class selection;
4. Home;
5. Share build;
6. import preview;
7. optionally, the Builds actions.

Each screenshot is a capture of the **running release build**, unmodified. Screenshots 1–3 show
game icons, talent text and class artwork (LR-1, LR-3). Google Play's IP policy lists "Marketing
images from movies, television, or video games" among violations, so these screenshots carry the
same legal risk as the app content ([readiness §6](readiness.md#6-legal-constraints-m5legal1)).

## 8. Reviewer notes (App access)

```text
Offline app. No account or sign-in. All features work without network access.
```
