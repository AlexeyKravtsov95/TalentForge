# M7.IOS.REVIEW.2 — final App Store metadata remediation pack

**Release gate: NO-GO.** These files are prepared for owner review after the reported
4.1(a) rejection. A validator PASS is an editorial/technical result, not rights clearance
or permission to upload/respond/resubmit. [Final assessment and open decisions](../m7-ios-review-2-assessment.md).
Read-only ASC inspection found the old rejected branded listing still present, build
**1.0.0 (4)** selected, and resubmission disabled. This repository pack has not been
applied in ASC. The existing affirmative Content Rights declaration has no supporting
grant in the reviewed repository evidence and must be reconciled before submission.

| Item | Current proposal / boundary |
|---|---|
| Name, both locales | **TalentForge** |
| Subtitle EN / RU | **Talent Build Planner** / **Планировщик талантов** |
| Positioning | Independent unofficial planner for the specified game's included data; no Blizzard authorization claimed |
| Game-title mention | Once per description in a separate supported-data/content notice; not in the promotional opening; rights basis remains open |
| iOS / Android | iOS stays **1.0.0 (4)**; Android **1.1.0 (6)** untouched |
| Candidate configuration | Descriptions/notes explain **OTA ON**; actual selected archive must be verified separately. Screenshots are older OTA OFF captures. |
| Rights | Game icons/text, class art and app-icon provenance remain unresolved; [separate plan](../m7-ios-review-1-content-plan.md) |
| Duo | 24 PNGs are not complete acceptance: native pinch/pan and remaining PR #150 matrix are blocked |
| External actions | No ASC edits, upload, Apple message, submission or publication performed |

The previous M5 name `TalentForge: WoW Forever` and game-branded subtitles are superseded.
ASC accepting a name when a record was created was not App Review approval. Do not
restore that title as a fallback if TalentForge is unavailable; the owner must review an
alternative accurate independent name. No availability claim is made by this pack.

## Field files

Each field is UTF-8 with one final newline, without comments or copy/paste headings.
The current [machine report](../evidence/m7-ios-review-2/appstore-metadata-report.json)
records all lengths. Limits are the existing conservative validator policy: name/subtitle
30 characters; promotion 170; description 4000; keywords 100 bytes; review notes 4000 bytes.

| ASC field | EN / shared | RU | EN / RU size |
|---|---|---|---|
| Name | [name](metadata/en-US/name.txt) | [name](metadata/ru/name.txt) | 11 / 11 characters |
| Subtitle | [subtitle](metadata/en-US/subtitle.txt) | [subtitle](metadata/ru/subtitle.txt) | 20 / 20 characters |
| Promotional text | [promotion](metadata/en-US/promotional_text.txt) | [promotion](metadata/ru/promotional_text.txt) | 146 / 157 characters |
| Description | [description](metadata/en-US/description.txt) | [description](metadata/ru/description.txt) | 2093 / 2237 characters |
| Keywords | [keywords](metadata/en-US/keywords.txt) | [keywords](metadata/ru/keywords.txt) | 70 / 83 bytes |
| Support URL | [support](metadata/en-US/support_url.txt) | [support](metadata/ru/support_url.txt) | unchanged iOS support page |
| Privacy URL | [privacy](metadata/en-US/privacy_url.txt) | [privacy](metadata/ru/privacy_url.txt) | unchanged iOS-specific pages |
| Categories | [primary](metadata/primary_category.txt) / [secondary](metadata/secondary_category.txt) | shared | Reference / Utilities |
| Copyright | [copyright](metadata/copyright.txt) | shared | 2026 Alexey Kravtsov |
| Review Notes | [English notes](metadata/review_information/notes.txt) | single shared field | 2955 bytes |

[Clarification request A](clarification-request.txt) and [resubmission response B](resubmission-response.txt)
are separate **unsent drafts**, not ASC fields. B is held under NO-GO and describes only
the text changes made in this package. Reconcile it with actual remediation and store
application before any separately authorized use. The earlier
[Resolution Center response](resolution-center-response.txt) is historical.
[Old metadata-en](../metadata-en.md), [metadata-ru](../metadata-ru.md), M5 readiness
and first-submission evidence are historical. These field files take precedence for copy;
the current assessment takes precedence for the release decision.

## Validation and screenshots

```bash
python3 scripts/verify_appstore_metadata.py
python3 scripts/verify_appstore_screenshots.py --require-duo
```

[Validation record](../m7-ios-review-2-validation.md) distinguishes this PR's checks from
historical evidence. Mobile CI now runs on submission-pack/validator changes and checks
these editorial rules and screenshot integrity as well as its existing mobile checks.

[screenshots/manifest.json](screenshots/manifest.json) records the **42 unchanged PNGs**:
12 iPhone 6.3-inch, 6 iPad 13-inch and 24 Duo Outer/Inner, across EN/RU. No existing image
was retouched. Technical PNG acceptance is not an IP or native gesture sign-off.
[Individual audit and recapture plan](../m7-ios-review-2-screenshot-audit.md) classifies
all 42 files and the App Icon: **26 recapture for uploaded (4), 16 conditional retain**,
none rights-cleared. Eight Duo library/favorites images show UI introduced after the
November 9 review. Public copy and Review Notes omit those newer features; the validator
now guards that candidate-specific restriction.
ASC has 18 iPhone/iPad filenames and no Duo uploads; remote byte identity is unverified.
No new screenshot or icon has been generated or edited here.

## Owner gates

Do not execute the historical [TestFlight runbook](testflight-runbook.md) while NO-GO.
The existing ASC record should eventually be edited, not duplicated. Content Rights
answers must reflect the actual third-party content; this pack supplies no basis to
assert that all necessary rights exist. Owner risk acceptance is not a license.

Before a separate resubmission: resolve documented rights/provenance/title decisions,
close required native acceptance (including Duo), select and verify the real archive,
approve fresh accurate screenshots and reconcile review notes with that candidate.
Review existing App Privacy and iOS privacy-page declarations against its OTA mode.
No version/build increase or store action is authorized by this metadata PR.
