# M5.RC.3 — Release candidate for RuStore, Huawei AppGallery and Google Play

| | |
|---|---|
| Milestone | M5.RC.3, release-candidate audit and verification. No new features |
| Audited commit | `main` @ `7efc93d` (merge of PR #57, M5.LEGAL.LINK), 2026-10-20 |
| Stores | RuStore and Huawei AppGallery (one signed universal APK); Google Play (AAB + Play App Signing). iOS is not in scope |
| Environment | owner's Mac (Apple silicon), JDK 17, Python 3.12 (fresh venv, `pip install -e ".[dev]"`), Android build-tools 37.0.0, bundletool 1.18.3, Xcode; Android emulator `Pixel_9a` (API 35, `google_apis_playstore`, arm64) |
| Evidence | [`rc3-evidence/`](rc3-evidence/) |
| Safety | The agent created, requested, read and used no production key. The owner reports a locally created production app signing keystore (§2.1); the agent had no access to it. Every signed artifact here was signed with **temporary self-test keys** (subject `TalentForge signing self-test …`, deleted after the run) and is named `*-SELFTEST-NOT-FOR-UPLOAD*`. Nothing was uploaded, submitted or published |

> **M5.OTA.PROD.3 (2026-10-21).** Утверждения этого документа об отсутствии `INTERNET` и сетевых соединений относятся к сборке **OTA OFF** (по умолчанию) и остаются для неё верными. Сборка с production OTA (`talentforge.ota.android.production.enabled=true`) запрашивает `INTERNET` и загружает данные талантов с `https://ota.mythweld.com`: для неё действуют [ota-prod3-privacy-and-stores](ota-prod3-privacy-and-stores.md) (тексты, политика, декларации, действия владельца) и [android-production-enablement](../ota/android-production-enablement.md). Ничего не подано и не опубликовано; LR-1…LR-5 без изменений.

## 1. Decision

| Store | Verdict | Engineering | What blocks publication |
|---|---|---|---|
| **RuStore** | **NOT READY** | GO | production signing gate (§2.1): app signing keystore created by the owner, but backups, restore, certificate fingerprint and the first signed APK are not verified (TF-RC1-SIGNING); the public `versionCode` raise (S-5 decided in M5.RELEASE.VERSION: test `1`, public `2`, [android-versioning](android-versioning.md)); store screenshots from the release build; LR-1…LR-5 sign-off; console declarations (age rating, data safety); written decision on the open P2 defects (§5) |
| **Huawei AppGallery** | **NOT READY** | GO | everything RuStore needs, plus the Huawei device pass H-1…H-14 (§4.3), which needs a physical Huawei device |
| **Google Play** | **NOT READY** | GO | production signing gate (§2.1), plus the separate upload key (not confirmed); Play App Signing set to the owner's key **before** the first upload (U-1…U-3); App content (Data safety and the other declarations); feature graphic and phone screenshots; closed test (12+ testers, 14 days); production access; P-12 written risk acceptance |

"Engineering GO" means: the build, both signing paths and both validators pass on `7efc93d`, the
release build behaves as the store texts and the privacy policy say, and the manual run on the
emulator found no crash and no P0/P1 defect. It does not replace the owner and legal gates.

## 2. Release audit

| Area | Result | Evidence |
|---|---|---|
| Open blockers carried over | TF-RC1-SIGNING (P1, production signing; OPEN, see §2.1), LR-1…LR-5 (legal), STORE1-SHOTS, VERSION, CONSOLE, M5.RC.3 Huawei/physical-device runs. TF-RC1-ICON stays OPEN until the owner-signed APK and a physical device are checked. P2: TF-RC1-COPY-NAME, TF-RC1-DRAFT-TITLE, TF-RC1-BADGE-200 reproduced (§5); TF-RC1-ANDROID-CONFIG unchanged | [rc1-defects](rc1-defects.md), [store1 audit §9](store1-release-audit.md) |
| Versioning | At the audited commit: `versionCode 1` / `versionName 0.1.0` (placeholders). Since M5.RELEASE.VERSION: `1` / `1.0.0` for the test build; since M5.OTA.SMOKE.VERSION `main` builds `2` / `1.0.0` for the local production OTA smoke (no store upload); the public code is decided after testing, in a separate reviewed change ([android-versioning](android-versioning.md)). The APK and the AAB of one commit carry the same values; the code sequence is shared by all three stores ([google-play/signing §6.4](google-play/signing.md#64-versions-across-the-three-stores)) | validator reports |
| Localization | RU/EN UI and talent texts switch with the app locale; Compose string resources (`values-ru`) are in the **base** APK assets, so Play language splits cannot drop them. The launcher label stays `TalentForge`. No `localeConfig`: the APK advertises AndroidX locales (TF-RC1-ANDROID-CONFIG, cosmetic) | emulator run (§4.3); `unzip -l` of the Play-like `base-master.apk` |
| Legal documents | Published privacy policy RU/EN checked (§3). Store listings: Variant A of all four drafts contains none of *World of Warcraft*, *Warcraft*, *Blizzard*, *WoW*, *Forever*; Play short/full text 67/1465 (EN) and 72/1626 (RU) characters, within 80/4000 | listing drafts |
| Rights to assets | **Not cleared.** LR-1…LR-3 (game data, icons, texts, class artwork, trademarks) remain OPEN. The owner's 2026-10-19 decision to proceed covers Google Play preparation only and does not establish any right ([legal1-release-options](legal1-release-options.md)) | [rights assessment](legal1-rights-assessment.md) |
| Android permissions | Release APK, Play AAB and the installed app request only `io.github.alexeykravtsov95.talentforge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. No `INTERNET`, no debug permission | `aapt2 dump badging`; validators `permissions-allowed`; `dumpsys package` |
| Compatibility | `minSdk 26`, `targetSdk 36`, `compileSdk 36` (Play requires 36 since 2026-08-31); ABIs `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`; the only native library is 16 KB aligned (validators `native-16kb-page-size`, `bundle-native-page-alignment`; `zipalign -c -P 16` on the generated arm64 split) | reports in [`rc3-evidence/signing/`](rc3-evidence/signing/) |
| Dataset freshness | **CURRENT**: product `1.60.1.70450` is the latest publishable build, checked 2026-10-20T06:25:17Z (gate 3 of [rc1 checklist §8](rc1-release-checklist.md#8-release-candidate-acceptance-gates-m5rc3); re-run within 24 h of the tag) | [`freshness.json`](rc3-evidence/freshness.json) |
| CI | green on `7efc93d`: [CI](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/39349091718) and [mobile-ci](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/39349091706) | `gh run list --branch main` |

### 2.1 Production signing status (release gate: OPEN)

State as reported by the owner on 2026-10-20. The agent did not receive, read or use the keystore,
its password or its certificate; nothing below was checked by the agent unless it says so.

| Item | Status |
|---|---|
| Production app signing keystore `talentforge-release.p12` | **Created locally by the owner** (owner's report). Not seen by the agent; no part of it is in the repository or in CI |
| Two offline backups and the restore drill ([signing guide §2](android-release-signing.md#2-back-it-up-before-the-first-upload-)) | **Not confirmed** |
| Production certificate SHA-256 (`APP_SIGNING_SHA256`) recorded and checked against the keystore | **Not verified** |
| Key type and size (RSA 4096) and the public DN | **Not verified** |
| First `assemblePublishableRelease` with the production key, report `"result": "PASS"` | **Not run** |
| Separate Google Play upload key (`talentforge-upload.p12`), its SHA-256 different from the app signing certificate | **Not confirmed** |
| Play App Signing set to the owner's key | **Not done** (owner, before the first upload) |

**Gate:** TF-RC1-SIGNING and the production-signing items of every store stay **OPEN** until the
owner confirms the backups and restore drill, records both fingerprints, and a production-signed
APK (and, for Google Play, an upload-key-signed AAB) passes the validators in §7.

## 3. Privacy policy and the three stores

The app opens `https://legal.mythweld.com/privacy.html` (Russian UI) or `…/privacy-en.html` (other
languages) on Android. Both pages answered `200 text/html` on 2026-10-20 (`last-modified` 2026-10-19).

| Requirement | Result |
|---|---|
| Names the stores | **Yes.** §5 of both pages names **Google Play, RuStore and AppGallery** and separates store-side processing from the app |
| Matches the release build | **Yes.** No account, ads or analytics; "does not request internet access" (true: no `INTERNET`); local build library with the listed fields (class, allocation, data version, optional name, created/modified/last-opened times: `LocalBuildRecord`); Copy/Paste/Share only on a tap; the code has no build name (seen in the share sheet); Android backup disabled (`allowBackup=false`, both rule files exclude every domain; `bmgr backupnow` → "Backup is not allowed"); vendor migration tools mentioned |
| Operator and contact | named, with an e-mail address |
| Children | "not designed specifically for children": consistent with the proposed Play target audience (16+/18+) |
| Reachable from the app | About → *Privacy policy* starts `ACTION_VIEW https://legal.mythweld.com/…` in the browser (emulator, EN UI). The per-language URL is covered by unit tests (`881be89`) |
| Google Play form | public HTTPS page, not a PDF, labelled *Privacy Policy*: fits the Play User Data policy. Also entered in Play Console by the owner |

Open for the owner: LR-5 legal review of the text; confirm the page is reachable without
geoblocking from outside Russia (VK Cloud hosting; not testable from here); keep the name in the
policy equal to the developer name shown in each store. The policy covers Android only, which is
correct while iOS is not released.

## 4. Test results

### 4.1 Automated (all run on `7efc93d`, 2026-10-20)

| Command | Result |
|---|---|
| `ruff check .` · `ruff format --check .` · `mypy` · `pytest` (clean worktree, fresh venv) | all checks passed · 275 files formatted · no issues in 85 files · **1455 passed**, 11 deselected |
| `sync_mobile_datasets.py --check`, `sync_mobile_localization.py --check`, `sync_mobile_tooltips.py --check`, `sync_mobile_visuals.py --check` | all in sync |
| `scripts/check_product_freshness.py` | CURRENT |
| `./gradlew :shared:jvmTest --rerun` | 371 tests, 0 failures |
| `./gradlew :composeApp:desktopTest --rerun` | 812 tests, 0 failures, 54 skipped (opt-in screenshot/remote checks) |
| `./gradlew :composeApp:iosSimulatorArm64Test :shared:iosSimulatorArm64Test` | 7 + 188 tests, 0 failures |
| `./gradlew :composeApp:assembleDebug` | BUILD SUCCESSFUL |
| `./gradlew :composeApp:assembleRelease :composeApp:bundlePlay` (no keys) | BUILD SUCCESSFUL (unsigned technical outputs; INTERNET guards passed) |
| `./gradlew :composeApp:assemblePublishableRelease` / `:bundlePublishablePlay` (no keys) | **fail closed** as designed (`checkPublishableSigning` names the missing settings, prints no value) |
| `./gradlew :composeApp:lintRelease` | 0 errors, 9 warnings (the known set, [rc2b evidence](rc2b-evidence/README.md#lint)) |
| `./gradlew :composeApp:linkDebugFrameworkIosArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64` + `xcodebuild … -destination 'generic/platform=iOS Simulator'` | BUILD SUCCEEDED |
| `scripts/release_signing_selftest.sh` | **PASS 14/14** ([summary](rc3-evidence/signing/release-selftest-summary.txt)) |
| `scripts/play_signing_selftest.sh` | **PASS 20/20**, including `play-apks-16kb` ([summary](rc3-evidence/signing/play-selftest-summary.txt)) |

### 4.2 RC artifacts with temporary keys

One temporary **app signing** key and one temporary **upload** key (both RSA 4096,
`TALENTFORGE_SIGNING_SELFTEST=true`) drove the real tasks:

| Artifact | Task | Validator | Certificate |
|---|---|---|---|
| `TalentForge-0.1.0-1-SELFTEST-NOT-FOR-UPLOAD.apk` (RuStore/AppGallery path) | `assemblePublishableRelease` | `verify_release_apk.py`: SELFTEST-PASS, 18 checks | app key `b149e7c6…5735d7` |
| `TalentForge-0.1.0-1-SELFTEST-NOT-FOR-UPLOAD.aab` (Play path) | `bundlePublishablePlay` | `verify_play_bundle.py`: SELFTEST-PASS, 20 checks | upload key `ba7d2002…ce1e0c` (differs from the app key) |
| Play-like device APKs | `bundletool build-apks` from that AAB, signed with the **app** key (what Play App Signing does) | `apksigner`: v3 signer = app key | app key `b149e7c6…5735d7` |
| `TalentForge-0.1.0-2-SELFTEST-NOT-FOR-UPLOAD.apk` | as v1, `versionCode 2` in a scratch worktree only | SELFTEST-PASS | app key |

So the scheme holds end to end: the APK and the Play-delivered APKs carry the **same** app signing
certificate, the bundle carries only the upload certificate.

### 4.3 Manual run (emulator `Pixel_9a`, API 35, 1080 × 2424, airplane mode on)

| # | Scenario | Result |
|---|---|---|
| T-1 / H-1 | Install the Play-like split APKs (`bundletool install-apks`), first launch offline | PASS: Home opens, "No builds yet", data `1.60.1` |
| T-3 / H-2 | New build for **all nine classes** | PASS: every class opens three specs with icons; a point spent in each |
| T-4 / H-3 | Tap selects without spending; + / − / **Max** in the inspector; Undo / Redo; Reset talents + Undo | PASS |
| T-7 / H-4 | Force-stop and relaunch | PASS: 10 builds and their points unchanged |
| H-5a–c / T-8 | Menus: All Builds ⋯ = *Rename · Duplicate · Share · Delete*; editor ⋯ = *Rename · Share · Reset talents*. Rename, Duplicate, Delete with Cancel and with confirm | PASS |
| T-9 / H-6 | Share → system Sharesheet; Copy code | PASS: the shared text is exactly the `TF1…` code, without the build name; cancelling shows nothing |
| T-10 / H-7, H-8 | Import: Paste → preview → Import; damaged code | PASS: preview before saving, new independent build; damaged code → "This code is damaged or incomplete…", nothing saved. One early Paste tap had no effect; three repeats all pasted (automation timing, not reproduced) |
| T-11 / H-9 | RU ↔ EN (per-app locale) | PASS: UI and talent texts in Russian |
| T-12 | Portrait (bottom inspector), landscape (side inspector), rotation keeps state | PASS |
| T-12 | Tablet layout (`wm size 1600x2560`, 320 dpi; emulation, not a tablet device) | PASS: no clipping |
| T-13 / H-10 | Font scale 200 % | **TF-RC1-BADGE-200 reproduced**: rank badges `0/3` clipped. Tabs switch to the documented number-only mode. Functions stay usable |
| T-15 | Update keeps data: Play-like install v1 → universal APK v1 (same code, same cert) → APK v2 | PASS: library identical before and after ([before](rc3-evidence/library-before-update.txt), [after](rc3-evidence/library-after-update.txt)); `firstInstallTime` kept. Downgrade refused |
| T-16 / H-14 (simulated) | Cross-store: Play-delivered install updated by the store APK | PASS for that direction with temporary keys. Real stores not involved |
| T-19 | About: version, data version, privacy summary, *Privacy policy* link | PASS |
| — | Release flags | `run-as` → "package not debuggable"; flags `HAS_CODE ALLOW_CLEAR_USER_DATA` (no `DEBUGGABLE`, no `TEST_ONLY`); only the AndroidX signature permission; backup refused |
| — | Crashes | none in the `crash` log buffer |

**Not run here** (they stay owner/device items):

| Scenario | Why |
|---|---|
| H-1…H-14 on a Huawei device (no GMS: launcher mask H-11, Phone Clone H-12, AppGallery delivery H-13, store client updates H-14) | **needs a physical Huawei device** or AGC Cloud Testing; emulators carry Google services |
| T-18 / R-7: runtime on a 16 KB-page device | a `ps16k` emulator could not be started from this tool environment (hypervisor blocked); static checks pass |
| Physical Android phone and tablet; API 26/30 runtime; TalkBack; 3-button vs gesture navigation; predictive back | no device / not automated |
| T-2, T-16 real: certificate of a Play-delivered install; the reverse direction (store APK → Play) | needs Play Console and the owner's keys |
| T-17: uninstall deletes builds | by design (no backup); not re-run |
| Loading the policy page inside the emulator browser | the browser's first-run consent screen was not accepted; the page itself was fetched and read over HTTPS (§3) |
| Store screenshots | must come from the owner-signed release build ([assets](google-play/assets-checklist.md)) |

## 5. Defects seen in this run

| ID | Status | Note |
|---|---|---|
| TF-RC1-COPY-NAME (P2) | **reproduced** | Russian UI: duplicating "Билд мага" creates "Draft (copy)" in English (screenshot `18-tablet-editor-ru.png`, title) |
| TF-RC1-DRAFT-TITLE (P2) | **reproduced** | the same unnamed build is "Warrior build" / "Билд воина" on Home and "Draft" / "Черновик" in the editor |
| TF-RC1-BADGE-200 (P2) | **reproduced** | `15-editor-font200.png` |

Gate 5 of [rc1 checklist §8](rc1-release-checklist.md#8-release-candidate-acceptance-gates-m5rc3)
needs each open P2 fixed or accepted in writing. The two naming defects are visible in Russian store
screenshots and to every Russian user; fixing them before the store screenshots is recommended (a
separate change, not part of this audit).

## 6. Owner actions, in order

1. 🔑 Finish production signing (§2.1): the app signing keystore exists; make and verify two offline backups with a restore drill, record its SHA-256 and check it is RSA 4096; create the **separate upload key** and record its SHA-256 (the two must differ) — [signing guide §1–§2](android-release-signing.md), [Play signing §4](google-play/signing.md#4-creating-the-keys-).
2. Versions (S-5) decided and the test values applied in M5.RELEASE.VERSION (test `1` / `1.0.0`, public `2` / `1.0.0`). Before the public build, raise `versionCode` in a reviewed change ([procedure](android-versioning.md#5-raising-versioncode-before-the-public-release)); tag the release commit after the checklist.
3. Decide the open P2 defects (§5): fix, or accept in writing.
4. Legal: LR-1…LR-5 sign-off for RuStore/AppGallery; P-12 for Google Play.
5. Build and verify the artifacts (§7); capture the store screenshots from the owner-signed APK; make the Play feature graphic.
6. RuStore and AppGallery: upload **the same APK file**; fill the age rating and data-safety forms; AppGallery without App Signing ([appgallery runbook](appgallery/testing-and-publication.md#6-publication-runbook-for-the-owner-not-executed)). Run H-1…H-14 on a Huawei device.
7. Google Play: the Play Console steps in §8.

## 7. Exact build and verification commands (owner machine, release commit)

Signing settings come from the environment or `~/.gradle/gradle.properties` only
([signing guide §3](android-release-signing.md#3-configure-the-build-), [Play signing §6.1](google-play/signing.md#61-settings)).

```bash
git checkout <release tag>
cd mobile

# RuStore + AppGallery: one APK, signed with the app signing key
./gradlew :composeApp:assemblePublishableRelease
# -> composeApp/build/outputs/publishable/TalentForge-<v>-<code>.apk (+ .report.json, "result": "PASS")

# Google Play: AAB signed with the upload key (APP_SIGNING_SHA256 is only checked, never used to sign)
./gradlew :composeApp:bundlePublishablePlay
# -> composeApp/build/outputs/publishable-google-play/TalentForge-<v>-<code>.aab (+ .report.json)
cd ..
```

Re-check the exact files right before each upload:

```bash
python3 scripts/verify_release_apk.py --apk TalentForge-<v>-<code>.apk \
  --expected-cert-sha256 <APP_SIGNING_SHA256> --report /tmp/apk-report.json
python3 scripts/verify_play_bundle.py --aab TalentForge-<v>-<code>.aab \
  --expected-upload-cert-sha256 <UPLOAD_SHA256> --app-signing-cert-sha256 <APP_SIGNING_SHA256> \
  --report /tmp/play-report.json
shasum -a 256 TalentForge-<v>-<code>.apk TalentForge-<v>-<code>.aab
apksigner verify --print-certs TalentForge-<v>-<code>.apk      # SHA-256 = APP_SIGNING_SHA256
keytool -printcert -jarfile TalentForge-<v>-<code>.aab         # SHA256 = UPLOAD_SHA256
```

Optional local check of what Play will deliver, before uploading (the app signing keystore never
leaves the machine):

```bash
bundletool build-apks --bundle=TalentForge-<v>-<code>.aab --output=/tmp/tf.apks \
  --ks=<app signing .p12> --ks-key-alias=talentforge
bundletool install-apks --apks=/tmp/tf.apks     # then install the store APK over it: must update in place
```

Expected for both reports: `"result": "PASS"` (never `SELFTEST-PASS`), the right certificate,
`applicationId io.github.alexeykravtsov95.talentforge`, the decided version, not debuggable,
only the AndroidX signature permission.

## 8. Google Play Console: create the app and start closed testing 🔑

Nothing here was executed. Details: [closed-testing.md](google-play/closed-testing.md),
[signing §5](google-play/signing.md#5-handing-the-app-signing-key-to-google-play-),
[data-safety.md](google-play/data-safety.md), [listing EN](google-play/store-listing-en.md) /
[RU](google-play/store-listing-ru.md), [assets](google-play/assets-checklist.md).

1. **Account.** Personal developer account: identity and device verification complete in the Play Console app; check the developer name matches the privacy policy.
2. **Create the app.** *Home → Create app*: name `TalentForge`, default language, **App**, **Free** (cannot become paid later), accept the declarations after reading them.
3. **Before any upload: Play App Signing with your key.** *Protected with Play → Play Store distribution → Go to Play app signing → Change the app signing key* → "Export and upload a key from Java keystore". Download PEPK and the encryption key **from that page**, run the command it shows on your app signing keystore, upload the `.zip`, register `talentforge-upload-certificate.pem`. If the screen says Play will still generate a classical key of its own (U-1…U-3), **stop**: that breaks the one-certificate scheme for three stores. Then check on *App signing*: app signing certificate = `APP_SIGNING_SHA256`, upload certificate = `UPLOAD_SHA256`; screenshot it.
4. **App content** (*Policy and programs → App content*): privacy policy URL `https://legal.mythweld.com/privacy-en.html`; Ads: none; App access: all functionality without special access; Target audience: 16+/18+ (not under 13); Content rating (IARC) questionnaire as utility; Data safety: **no data collected or shared**; financial, health, government, news: none.
5. **Store listing** (EN + RU, Variant A): name, short and full description, icon `TalentForge-GooglePlay-512.png`, feature graphic 1024 × 500, 2–8 phone screenshots from the owner-signed build.
6. **Internal testing (optional, a quick check):** *Testing → Internal testing → Create release*, upload the publishable `.aab`, add yourself as tester, install via the opt-in link, then pull the installed APK and confirm its certificate is `APP_SIGNING_SHA256` (T-2) and that the RuStore APK updates it in place (T-16).
7. **Closed testing:** *Testing → Closed testing → Create track*; testers via a Google Group (18–20 people to keep 12+ opted in); countries; upload the same `.aab` (or a higher `versionCode`); release notes; *Review release → Start rollout*. Keep 12+ testers opted in for **14 consecutive days**; log feedback; run the test plan T-1…T-19.
8. **Production access:** *Dashboard → Apply for production* after the 14 days; then production release. **Do not** request a key upgrade without analysing cross-store updates (R-3).

Upload, rollout and publication stay with the owner.
