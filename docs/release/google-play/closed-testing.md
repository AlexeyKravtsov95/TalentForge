# M5.PLAY.1 — Personal account: closed testing and production access

| | |
|---|---|
| Status | **Plan.** Nothing was created, uploaded, sent or posted. Every step is an owner action 🔑 |
| Applies to | A **personal** Play Console developer account created after 13 November 2023 |
| Sources | Google help pages read 2026-10-19 ([§10](#10-sources)) |
| Prerequisites | [signing](signing.md) (keys, Play App Signing with the owner's key), [data safety](data-safety.md) (privacy policy and App content), [listing EN](store-listing-en.md)/[RU](store-listing-ru.md), [assets](assets-checklist.md) |

## 1. Account checks 🔑

| Check | Rule (Google) | Owner |
|---|---|---|
| Testing requirement | Personal accounts created after 13 Nov 2023 must run a **closed test with at least 12 testers opted in for the last 14 consecutive days** before they can apply for production access | applies to this account |
| Physical device verification | New personal accounts must verify access to a **physical, non-rooted Android 10+ phone** in the **Play Console mobile app** (scan the QR code from the Home task) before they can make an app available on Google Play. It takes under a minute, and Google may ask again later | install the Play Console app on the owner's phone; complete the Home task |
| Identity and contact details | completed during account setup; the developer name and the contact e-mail are public on the listing | not re-checked in this milestone (UNVERIFIED wording) |
| Android developer verification | Google's new verification programme: enforcement on certified devices in Brazil, Indonesia, Singapore and Thailand from **30 Sep 2026**, worldwide in **2027**. Google Play registers most Play apps automatically; others are registered in Play Console | after the first upload, check in Play Console that the package name is registered. Apps distributed **outside** Play (RuStore, AppGallery APKs) can be registered there too, by proving the signing key. **Decide before 2027** (readiness R-6) |

## 2. Create the app in Play Console 🔑

1. *Create app*: app name `TalentForge`; default language (proposal: **English (United States)**,
   with Russian added as a translation); **App** (not game); **Free**.
   - A free app can never be made paid.
   - Accepting the Developer Program Policies and the US export-law declaration is the owner's act.
2. **Before any upload:** set Play App Signing to the owner's key
   ([signing §5](signing.md#5-handing-the-app-signing-key-to-google-play-)) and record both
   fingerprints from the *App signing* page.
3. *App content*: privacy policy URL, ads, app access, target audience, content rating, data
   safety, government apps, financial features, health. Proposed answers:
   [data safety §2–§4](data-safety.md#2-proposed-data-safety-answers).
4. *Main store listing*: EN and RU texts, icon, feature graphic, screenshots.
5. *Countries/regions* for the closed track: owner's choice. Testers must be in a selected country.

## 3. Closed testing track 🔑

1. *Test and release* → *Testing* → *Closed testing*: use the default track or create one
   (e.g. `closed-beta`).
2. **Testers: Google Group** (recommended). Create a group such as
   `talentforge-testers@googlegroups.com` (the name is the owner's choice) and add it to the track.
   Testers join the group with the Google account they use on their phone. Alternative: an e-mail
   list. Lists hold up to 2 000 addresses; a CSV saved as UTF-8 with a BOM is rejected.
3. *Feedback channel* on the track: an e-mail address or URL shown to testers (§6).
4. Create the release:
   - upload the bundle from `bundlePublishablePlay`
     (`TalentForge-<versionName>-<versionCode>.aab`, after the standalone validator check in
     [signing §6.3](signing.md#63-what-the-publishable-bundle-must-pass-scriptsverify_play_bundlepy));
   - set the release notes from the listing §5;
   - review and roll out.
5. After review, when the track shows **Published**, the **opt-in link** appears (it can take
   hours). Every tester must open it and accept **individually**, then install from Play. A
   closed-test app is not findable by search.
6. Check the delivered certificate on the owner's own test install
   ([signing §5.2](signing.md#52-steps-owner-in-play-console-nothing-here-was-executed), step 7).

## 4. Recruiting testers

- **Minimum:** 12 testers opted in on each of the last 14 consecutive days. A tester who opts out
  and opts back in starts their 14 days again.
- **Recommended: 18–20 testers.** People drop out, change phones or opt out by mistake. A buffer
  keeps the count at 12 or more every day without restarting the 14 days.
- Count opted-in testers daily (Play Console shows the testers on the track). Write the date and the
  count in the testing log (§6). Ask quiet testers to keep the app installed, not to leave the group.
- Ask testers to **use** the app: Google's production-access form asks how testers engaged and
  whether they used the features. A list of installs alone is weak evidence.
- Do not offer money, rewards or reviews in exchange for testing.
- Invitation template: §9.

## 5. Test plan for TalentForge

Run on at least: one recent phone on Android 16 (the target API), one older phone (Android 8–10,
`minSdk 26`), one tablet or a large-screen emulator, and, if available, a device or emulator
with **16 KB pages** (Android 15+ "16 KB page size" developer option or a 16 KB emulator image).
Every tester gets the core list. The owner runs the full list on every new `versionCode`.

| # | Area | Steps | Expected |
|---|---|---|---|
| T-1 | Install and first launch | Install from the Play opt-in link with **flight mode on** after the download | The app opens and works fully offline; no network prompt |
| T-2 | Signing identity | (owner) `adb shell pm path …`, pull the base APK, `apksigner verify --print-certs` | SHA-256 = the owner's app signing fingerprint, the same as the RuStore/AppGallery APK |
| T-3 | Class selection | New build → each of the nine classes | Each class opens its talent tree with three sections |
| T-4 | Select vs spend (B3) | Tap a talent; then use **+** / **−** in the inspector; try max-out | A tap only selects; points change only through the visible buttons; requirements and tier points update |
| T-5 | Navigation | Drag to pan, pinch to zoom; use **Show** on a requirement | Canvas moves and zooms; **Show** jumps to the prerequisite and never spends |
| T-6 | Undo / redo / reset | Spend several points, undo, redo, reset a section | State matches each step; counters are correct |
| T-7 | Persistence | Leave the app, force-stop it, reopen; rotate during editing | The build is saved automatically; Home → Continue opens it |
| T-8 | Library | Rename, duplicate, delete builds (Home and All Builds) | Changes stay after restart; delete asks for confirmation |
| T-9 | Share | Share build → Share (Android Sharesheet) to a messenger; Copy code | The text is the build code only (no build name); the clipboard holds the code |
| T-10 | Import | Paste a code from another device; a corrupted code; an empty clipboard | Valid code: preview before saving, never overwrites another build. Invalid code: clear error, nothing saved |
| T-11 | Languages | Device language RU, then EN | UI and talent texts in the device language |
| T-12 | Orientation and screens | Portrait and landscape on a phone; a tablet | Portrait uses the bottom inspector, landscape the side inspector; nothing cut off |
| T-13 | Accessibility | Font size at maximum; display size large; TalkBack on Home, class selection and the inspector buttons | Text readable, controls reachable, buttons announced with their action |
| T-14 | Edge-to-edge (API 36) | Gesture and 3-button navigation | No content under the status or navigation bars; back works (predictive back on Android 16) |
| T-15 | Update keeps data | Install build N, create builds, update to N+1 through the track | All builds are still there |
| T-16 | Cross-store update (owner, after RuStore/AppGallery APKs exist) | Install from Play, then install the same-or-higher-`versionCode` RuStore APK over it (and the reverse) | Installs as an update, builds kept (same certificate). A failure means Play is not using the owner's key: stop and check [signing §5](signing.md#5-handing-the-app-signing-key-to-google-play-) |
| T-17 | Uninstall | Uninstall and reinstall | Builds are deleted (no backup, by design). Users are told to share codes before uninstalling |
| T-18 | 16 KB pages | On a 16 KB device or emulator: launch, open every class, share and import | No crash (the app's only native library is `libandroidx.graphics.path.so`) |
| T-19 | Data version | Home → About | Shows the bundled data version and the in-app privacy summary |

## 6. Feedback and fixes

- **Channels:**
  - the track's feedback e-mail or URL;
  - testers' private feedback on the Play listing, which only the developer sees;
  - replies in the Google Group.

  Do not collect device logs or personal data through a form; ask for the device model, the
  Android version, the steps taken and a screenshot.
- **Testing log** (keep it private, outside the public repository: it names testers):

  | Date | Opted-in testers | Tester (alias) | Device / Android | Area (T-#) | Report | Severity | Fixed in commit / PR | Released in `versionCode` |
  |---|---|---|---|---|---|---|---|---|

- **Fixes** follow the normal milestone workflow (branch, PR, review, merge). Each fix release is a
  new bundle from `bundlePublishablePlay` with a **higher `versionCode`** on the same closed track. A
  new release does not reset the testers' 14 days; opting out does.
- Keep the log's summary for the production-access form (§7): how many testers took part, what they
  used, what was reported and what changed.

## 7. Applying for production access 🔑

When the dashboard shows that the requirement is met (12+ testers for the last 14 days), use
*Dashboard* → **Apply for production**. Google asks three groups of questions. Prepare the answers
from the log:

| Group | Questions (paraphrased from Google's help) | Where the answer comes from |
|---|---|---|
| About your closed test | How did you recruit testers? How easy was it? Did testers use all features? How did their behaviour compare with expected production use? Summarise the feedback and how you collected it | §4, §5 coverage per T-#, §6 log |
| About your app | Who is the target audience? What value does the app give users? How many installs do you expect in the first year? | listing text; target audience 18+ ([data safety §4](data-safety.md#4-other-app-content-declarations-proposed)); an honest estimate |
| About your production readiness | What changes did you make based on the test? How did you decide the app is ready? | fixed issues with commits and `versionCode`s; the readiness gates |

Review usually takes **7 days or less**. Possible outcomes:

- **approved**: production (and open testing) become available;
- **more testing required**: Google "may require" more testing when fewer than 12 testers stayed
  opted in or engagement was low. Continue the same track, recruit more (keep the buffer), keep the
  log, apply again;
- **rejected over policy** (for example IP; see [readiness §6](readiness.md#6-legal-constraints-m5legal1)):
  read the reason and decide with legal input.

The production release itself is a new bundle with a **higher `versionCode`** than every
closed-test bundle.

## 8. Not done here

- No Play Console account action, no group, no upload, no policy acceptance, no invitation sent, no
  post published.

## 9. Invitation template (English, for Reddit; not posted)

Before posting anywhere:
- read the community's rules (many forbid self-promotion or require a flair or a weekly thread);
- post only where tester recruitment is allowed;
- never offer payment or reviews;
- do not imply any affiliation with the game's developer or publisher.

The owner fills in the ⟨…⟩ fields.

```text
Title: Looking for Android testers: TalentForge, an offline talent build planner (closed test, 14 days)

Hi! I'm building TalentForge, a free, offline talent build planner for Android. You pick a class, spend points on its talent tree, save builds on your device and share them as short text codes.

Google Play requires new developer accounts to run a closed test for 14 days before release, so I'm looking for testers who will keep the app installed for two weeks and try it out.

What you get: early access, and your feedback shapes the first public release.
What it needs: an Android phone (Android 8 or newer) and a Google account.
No account in the app, no ads, no analytics, and it does not request internet access.

How to join:
1. Join the testers group: ⟨Google Group link⟩
2. Accept the test invitation: ⟨Play opt-in link⟩
3. Install TalentForge from Google Play and keep it installed for 14 days.

Feedback: ⟨feedback e-mail or link⟩, or private feedback on the Play listing.

TalentForge is an independent, unofficial project and is not affiliated with the game's developer or publisher. Thanks!
```

## 10. Sources

Read 2026-10-19; the help pages show no revision date.

- [App testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Set up an open, closed or internal test](https://support.google.com/googleplay/android-developer/answer/9845334)
- [Verify access to a physical Android device](https://support.google.com/googleplay/android-developer/answer/14316361)
- [Android developer verification](https://developer.android.com/developer-verification), [announcement (2026-06-18)](https://android-developers.googleblog.com/2026/06/android-developer-verification.html), [Play Console help](https://support.google.com/googleplay/android-developer/answer/16761053)
- The change of the tester minimum from 20 to 12 (Dec 2024) is stated in a Play developer community guide, not on a help page; the help page above states 12, which this plan uses
