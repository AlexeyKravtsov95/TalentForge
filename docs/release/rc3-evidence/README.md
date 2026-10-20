# M5.RC.3 — Evidence

Curated evidence for [rc3-readiness.md](../rc3-readiness.md), taken from `main` @ `7efc93d` on
2026-10-20. All keys used were **temporary self-test keys**, deleted at the end of the run. Their
certificate digests below are public and belong to keys that no longer exist. Every artifact they
signed is named `*-SELFTEST-NOT-FOR-UPLOAD*` and was not committed.

| Path | What it is |
|---|---|
| `signing/00-test-certificates.txt` | SHA-256 of the temporary app signing and upload certificates (they differ) |
| `signing/apk-v1.report.json`, `signing/apk-v2.report.json` | `verify_release_apk.py` reports of the `assemblePublishableRelease` APKs (`versionCode` 1 and 2, same app key): `SELFTEST-PASS` |
| `signing/aab.report.json` | `verify_play_bundle.py` report of the `bundlePublishablePlay` AAB (upload key): `SELFTEST-PASS` |
| `signing/release-selftest-summary.txt`, `signing/play-selftest-summary.txt` | scenario results of `scripts/release_signing_selftest.sh` (14/14) and `scripts/play_signing_selftest.sh` (20/20) |
| `freshness.json` | `scripts/check_product_freshness.py`: `CURRENT` |
| `library-before-update.txt`, `library-after-update.txt` | All Builds as read from the screen (uiautomator) before and after installing the store APK over the Play-like install and updating to `versionCode` 2; identical |
| `screenshots/` | emulator `Pixel_9a` (API 35), release build with a temporary key, airplane mode. Test evidence only, **not** store material (status bar shown, temporary key) |

Screenshots:

- `01-home-empty.png`: first launch offline;
- `03-warrior-spent.png`: points spent through the inspector, Undo enabled;
- `06-sharesheet.png`: the system share sheet receives only the `TF1…` code;
- `10-import-damaged.png`: a damaged code is refused with a typed message;
- `13-editor-ru-inspector.png`: Russian UI and talent text, inspector with − / + Rank / Max;
- `14-editor-landscape-ru.png`: landscape side inspector;
- `15-editor-font200.png`: 200 % font scale, TF-RC1-BADGE-200 (clipped rank badges);
- `18-tablet-editor-ru.png`: tablet-size emulation; the title shows TF-RC1-COPY-NAME ("Draft (copy)" in the Russian UI).

Not committed: the APKs, the AAB, the Play-like `.apks`, full Gradle logs and the remaining
screenshots.
