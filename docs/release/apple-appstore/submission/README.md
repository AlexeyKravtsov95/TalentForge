# M7.IOS.RC.1 — App Store candidate package

**App Review/publication: NO-GO.** Technical RC **1.0.0 (5)** is prepared;
[RC evidence and remaining gates](../m7-ios-rc-1.md) distinguish signed artifacts,
native simulator checks, owner-reported physical iPhone acceptance and incomplete
Duo acceptance. Rights clearance and the owner's separate submission decision remain
required. This repository package has not been applied in ASC.

Use [the existing-app checklist](rc1-checklist.md) for app **6826780044**, store
version **1.0**, rejected submission **0a3acce7-75fb-4f82-9f93-54edf5310eb6**.
Read-only ASC inspection still finds **1.0.0 (4)** selected, Unresolved Issues,
Resubmit disabled, the old branded listing and **Automatic Release**.
The checklist requires **Manual Release**; no ASC setting was changed.
Do not create another app or run the historical New App workflow.

| Item | Current proposal / boundary |
|---|---|
| Name, both locales | **TalentForge** |
| Subtitle EN / RU | **Talent Build Planner** / **Планировщик талантов** |
| Positioning | Independent unofficial planner for included game data; no rightsholder authorization claimed |
| Game-title mention | Supported-data/content notice in descriptions; absent from promotional text and keywords |
| iOS / Android | iOS **1.0.0 (5)**; Android **1.1.0 (6)** untouched |
| Candidate configuration | Release **production OTA ON**, verified archive + exported App Store IPA |
| New library copy | Restored after build-5 native iPhone/iPad simulator acceptance |
| Rights | Game icons/text, class art and App Icon basis remain unresolved; RC is not rights evidence |
| Duo | Build-5 resize/Undo evidence is partial; native multi-touch and new submission pack remain blocked |
| External actions | Local signing/export/device install only; no ASC edits, upload, message, review or publication |

## Field files

Each field is UTF-8 with one final newline, without copy/paste headings. The current
[machine report](../evidence/m7-ios-rc-1/metadata-report.json) records exact lengths.
The existing general editorial checks remain: independent branding, required product/
OTA facts and disclosures, field/keyword limits, iOS URLs and no other-store references.
Only the build-4 date-specific library prohibition was removed.

| ASC field | EN / shared | RU |
|---|---|---|
| Name | [name](metadata/en-US/name.txt) | [name](metadata/ru/name.txt) |
| Subtitle | [subtitle](metadata/en-US/subtitle.txt) | [subtitle](metadata/ru/subtitle.txt) |
| Promotional text | [promotion](metadata/en-US/promotional_text.txt) | [promotion](metadata/ru/promotional_text.txt) |
| Description | [description](metadata/en-US/description.txt) | [description](metadata/ru/description.txt) |
| Keywords | [keywords](metadata/en-US/keywords.txt) | [keywords](metadata/ru/keywords.txt) |
| Support URL | [support](metadata/en-US/support_url.txt) | [support](metadata/ru/support_url.txt) |
| Privacy URL | [privacy](metadata/en-US/privacy_url.txt) | [privacy](metadata/ru/privacy_url.txt) |
| Categories | [Reference](metadata/primary_category.txt) / [Utilities](metadata/secondary_category.txt) | shared |
| Copyright | [copyright](metadata/copyright.txt) | shared |
| Review Notes | [English notes](metadata/review_information/notes.txt) | one shared field |

## Candidate screenshots and checks

[Build-5 manifest](screenshots-rc1/manifest.json): **28 new native captures**,
seven screens for each iPhone Dynamic Island/iPad 13-inch and EN/RU combination.
Source commit, simulator binary, raw capture and delivered PNG hashes are recorded.
No generated/retouched UI, crops or resizing; opaque alpha removal preserves RGB bytes.

```bash
python3 scripts/verify_appstore_metadata.py
python3 scripts/verify_appstore_screenshots.py \
  --screenshots docs/release/apple-appstore/submission/screenshots-rc1 \
  --expected-version 1.0.0 --expected-build 5 --require-production-ota
# Required complete-pack gate: currently FAIL because Duo recapture is held.
python3 scripts/verify_appstore_screenshots.py \
  --screenshots docs/release/apple-appstore/submission/screenshots-rc1 \
  --require-duo --expected-version 1.0.0 --expected-build 5 --require-production-ota
```

Mobile CI checks both historical integrity and the new candidate's explicit provenance.
Its success cannot replace the separate, currently failing complete Duo release gate.
PNG/provenance validation is not authentication, rights clearance or native gesture approval.

The old [42-image pack](screenshots/manifest.json), [PR #152 assessment](../m7-ios-review-2-assessment.md),
[individual image audit](../m7-ios-review-2-screenshot-audit.md) and
[validation record](../m7-ios-review-2-validation.md) remain historical evidence.
They must not be presented as build-5 acceptance. Existing unsent
[clarification](clarification-request.txt) and [resubmission](resubmission-response.txt)
drafts describe the earlier metadata remediation; reconcile them with actual final
remediation and owner authorization before any use. They are not sent by this RC task.
