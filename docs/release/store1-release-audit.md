# M5.STORE.1 — Android release readiness audit (RuStore + Huawei AppGallery)

| | |
|---|---|
| Milestone | M5.STORE.1, independent pre-release audit of the first public Android release |
| Audited commit | `main` @ `b242cd61f1e9ca400e5c652cc6db61c48c7178f5` (merge of PR #51), 2026-10-19 |
| Stores | RuStore and Huawei AppGallery (Android APK). Google Play deferred (#35); iOS not in scope |
| Environment | owner's Mac (Apple silicon, macOS 26.3.1), JDK 17.0.19, Python 3.12.13, Android build-tools 37.0.0, bundletool 1.18.3; Android emulator API 35 (`compact`, `google_apis` arm64, userdebug, started `-read-only`) |
| Evidence | [`store1-evidence/`](store1-evidence/) (curated; scratch logs are not committed) |
| Safety | No production key was created, read or used. Nothing was uploaded, submitted or published. The two signed APKs used here were signed with **temporary self-test keys** that were deleted after each run |

> **Update 2026-10-19: NET-1 = B.** The owner chose option B ([§5.4](#54-options-for-the-first-release-owner-decision-net-1)).
> The NET-1 engineering change (branch `fix/m5-net-1-release-no-internet`) removes `INTERNET` from release builds while
> the production OTA channel is off, keeps it in debug builds, and adds a build guard with a self-test. It re-verifies
> the release and temporary-key publishable APKs with `aapt2` ([NET-1 evidence](net1-evidence/README.md)) and restores the
> "does not request internet access" wording in the texts listed in §5.3. Once that change is merged, STORE1-NET is
> resolved. The decision stays **NO-GO** for the other blockers below. Statuses changed by the update are marked
> *NET-1 = B*; the rest of the audit is the record for `b242cd6`.

## 1. Decision

**NO-GO for publication.**

The engineering build is in good shape. On `b242cd6`:

- every build, test, lint and drift check passes;
- the fail-closed publishable path works with a temporary key;
- the release build makes **no network connection at all**.

Publication is still blocked by:

1. **A new P0 discrepancy (STORE1-NET).** *NET-1 = B: resolved by the NET-1 change (update above).* Since M5.OTA.3 (commit `ea77dec`, PR #44, 2026-10-18), the
   Android manifest declares `android.permission.INTERNET`. The privacy-policy drafts, both store
   listings, the release checklist and the AppGallery readiness documents still say that the app
   requests no internet access. As written, they would turn into false store declarations. The
   correct wording depends on an owner decision ([§5.4](#54-options-for-the-first-release-owner-decision-net-1)).
2. **The existing gates that this audit could not close**:
   - no owner release key and no production-signed APK (TF-RC1-SIGNING);
   - LR-1…LR-5 legal review;
   - no hosted privacy policy or developer contact;
   - no store screenshots;
   - no physical-device or Huawei run (M5.RC.3);
   - the first public `versionCode`/`versionName` is not decided;
   - the console-only declarations (age rating, data safety, AI declaration).

None of the earlier PASS statuses was carried over without a new check. §2–§8 show what was re-run.

## 2. Project state on `main`

| Fact | Value | How it was checked |
|---|---|---|
| HEAD | `b242cd6` "Merge pull request #51" (2026-10-19T14:10:10+07:00) | `git fetch`; `git log -1 origin/main` |
| PR #49 (M5.UI.ABOUT.1, About cleanup) | MERGED 2026-10-19T07:09Z as `27102e8`; head `38bb553` is an ancestor of `b242cd6` | `gh pr view 49`; `git merge-base --is-ancestor` |
| PR #50 (OTA S5b resume flake) | MERGED 2026-10-18T19:34Z as `2aa47e3`; head `5847d3e` in `main` | idem |
| PR #51 (Linux CI label wrap) | MERGED 2026-10-19T07:10Z as `b242cd6`; head `5873e1f` in `main` | idem |
| CI on `b242cd6` | **green**: [CI #39214258265](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/39214258265) `checks` success; [mobile-ci #39214258206](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/39214258206) Linux (drift, JVM, desktop, `assembleDebug`, signing self-test) and macOS (iOS) jobs success | `gh run list --branch main`, `gh run view` |
| Earlier `main` CI | mobile-ci failed on `2aa47e3`, `634b1f3`, `3e9922d` and `5a7ea07`. The run on `27102e8` was cancelled (superseded). PR #51 fixed the Linux label-wrap failure; the first fully green mobile-ci run is on `b242cd6` | idem |
| `applicationId` | `io.github.alexeykravtsov95.talentforge` | `aapt2 dump badging` ([40-badging-release](store1-evidence/mac/40-badging-release.txt)) |
| `minSdk` / `targetSdk` / `compileSdk` | 26 (Android 8.0) / 36 / 36 | badging, `libs.versions.toml` |
| `versionCode` / `versionName` | `1` / `0.1.0` (placeholder; first public values are an owner decision) | badging, `build.gradle.kts` |
| Debuggable / testOnly | no / no (release and publishable APKs) | badging; validator report |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`. The only native library is `libandroidx.graphics.path.so` (37 KB in total) | badging `native-code`; `unzip -l` |
| Permissions (release) | `android.permission.INTERNET` and the AndroidX signature permission `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Nothing else | [41-manifest-release](store1-evidence/mac/41-manifest-release.txt); device `dumpsys package` ([package.txt](store1-evidence/emulator/package.txt)) |
| Backup | `allowBackup=false` plus data-extraction and full-backup rules | manifest dump |
| Exported components | `MainActivity`, `ProfileInstallReceiver` (guarded by `android.permission.DUMP`) | inspection |
| Bundled talent data | product `1.60.1.70450`; the freshness check reports **CURRENT** (2026-10-19T07:28:37Z) | [15-freshness-rerun-venv](store1-evidence/mac/15-freshness-rerun-venv.txt) |

## 3. Android release build

### 3.1 Commands and results

The existing runner
[`run-gallery1-checks.sh`](appgallery/evidence/run-gallery1-checks.sh) ran once on a detached scratch
worktree of `b242cd6`:

```bash
bash docs/release/appgallery/evidence/run-gallery1-checks.sh \
  --commit origin/main --expect-sha b242cd61f1e9ca400e5c652cc6db61c48c7178f5 \
  --work-dir <scratch> --python python3 --bundletool /opt/homebrew/bin/bundletool
```

Results are in [`store1-evidence/mac/00-summary.tsv`](store1-evidence/mac/00-summary.tsv), with the
full SHA on every row.

| Check | Result |
|---|---|
| `sync_mobile_{datasets,visuals,tooltips,localization}.py --check` | **PASS** ×4 |
| `check_product_freshness.py` | runner step INFO (exit 1). `--python python3` lacks the `talent_data` package, so this is an audit-invocation error, not a product failure. Re-run with the project venv: **CURRENT**, `1.60.1.70450` |
| `:shared:jvmTest --rerun --no-build-cache` | **PASS**: 371 tests, 0 failures (32 suites) |
| `:composeApp:desktopTest --rerun --no-build-cache` | **PASS**: 785 tests, 0 failures, 53 skipped (opt-in screenshot tests; 106 suites) |
| `:composeApp:assembleDebug` | **PASS**: 23 189 832 bytes |
| `:composeApp:assembleRelease` | **PASS**: `composeApp-release-unsigned.apk`, 19 806 566 bytes, unsigned |
| `:composeApp:bundleRelease` | **PASS**: AAB 19 720 150 bytes |
| `:composeApp:lintRelease` | **PASS: 0 errors, 9 warnings** (`ApplySharedPref`, `UseKtx`, `OldTargetApi`, `ObsoleteSdkInt`, `IconLauncherShape` ×5). The same set as M5.RC.2b; none suppressed. The runner still labels this step "expected to fail", which is outdated (§9) |
| release runtime dependencies | **PASS**: 124 modules; no GMS, Firebase, Play Billing, Play Core, HMS/AGConnect, OkHttp, Ktor or Retrofit |
| `apksigner` (unsigned release) | FAIL-EXPECTED: `Missing META-INF/MANIFEST.MF`. The unsigned technical APK is not an upload artifact |
| bundletool | splits PASS; per-device download estimate **18 788 132 – 18 832 648 bytes**, against **19 814 758 bytes** for the signed universal APK (≈ 1 MB or 5 % saved by AAB). The APK recommendation of [multi-store-signing §3](appgallery/multi-store-signing.md#3-apk-vs-aab-for-the-first-public-release) still holds |
| dex scan | no GMS/Firebase/Billing/Play Core/HMS class names. `java.net.HttpURLConnection` is **now referenced** by the M5.OTA.3 fetcher (`JvmOtaPorts.kt`), plus library references to `java.net.Socket` and `android.webkit.WebView`. The M5.GALLERY.1 statement "no `HttpURLConnection`" is out of date |

Test counts grew since M5.RC.2b (338 → 371 shared, 681 → 785 desktop), because M5.OTA.3/4 and M5.UI.ABOUT.1
added tests.

### 3.2 Publishable path and signing self-test (temporary keys only)

| Check | Result | Evidence |
|---|---|---|
| `scripts/release_signing_selftest.sh` on `b242cd6` | **PASS 14/14** (unsigned, publish-none, partial, debug-key, in-repo-key, signed, publish-nocert, publish-wrong, publish-ok, stale-wrong, stale-none, stale-partial, debug-apk, no-secrets). The test key and every test-signed output were deleted on exit | [signing-selftest/summary.txt](store1-evidence/signing-selftest/summary.txt) |
| `assemblePublishableRelease` with a second temporary key (for the device check, §5) | **PASS**. `TalentForge-0.1.0-1.apk`, 19 814 758 bytes. Validator PASS: v2 signature verifies, 1 signer, not the debug DN, not the local debug certificate, fingerprint matches, RSA 4096, not debuggable, not testOnly. The key's DN reads "NOT A PRODUCTION KEY"; the key was deleted after the build, and the secret scan of logs, report and `build/` was clean | [publishable-tempkey/](store1-evidence/publishable-tempkey/), [script](store1-evidence/publishable-tempkey.sh) |
| No staging OTA in the publishable build | **PASS**. The generated `GeneratedOtaSettings` used by that build has every channel `enabled = false`, `baseUrl = null`, no keys and no staging trust anchor. `checkReleaseOtaSettings` guards release variants. The dex contains no OTA host (the only URL string is the test default `https://ota.invalid`) | [GeneratedOtaSettings.kt.txt](store1-evidence/publishable-tempkey/GeneratedOtaSettings.kt.txt) |
| No debug configuration or test material | **PASS**. Not debuggable; signer is not the debug certificate (validator). No OTA fixtures, keyrings, PEM files or `test-` keys in the APK (`unzip -Z1` and dex string scan). Bundled files: `dataset` 50, `localization` 45, `tooltips` 45, `visuals` 1 840, `legal` 18 | this audit |
| Signature schemes | v2 only (v1/v3 absent). `minSdk 26` needs no v1. AppGallery rejects an APK without a v2 signature (parsing error 33), so v2 is required and present | validator report |

### 3.3 APK size and structure (signed universal APK)

| Part | Compressed | Uncompressed |
|---|---|---|
| `assets/composeResources` (datasets, icons, texts, legal) | 11.6 MB | 25.7 MB |
| dex (2 files; R8/minify **off**) | 7.0 MB | 20.4 MB |
| `res` + `resources.arsc` | 0.37 MB | 0.38 MB |
| native libraries (4 ABIs) | 37 KB | 37 KB |
| **Total file** | **19.81 MB** (2 145 entries) | 46.7 MB |

Both stores accept this size (RuStore APK ≤ 5 GB; AppGallery < 4 GB). Enabling R8 would shrink the dex,
but it is a TF-RC1-ANDROID-CONFIG decision that needs a full device regression. It does not block release.

### 3.4 One certificate for both stores

The current build supports the recommended strategy in [multi-store-signing](appgallery/multi-store-signing.md):

- one owner-held key;
- one signed universal APK uploaded byte-identical to RuStore and AppGallery;
- the same `applicationId` and `versionCode`;
- no flavors and no store-managed signing.

The store rules re-read on 2026-10-19 (§6) agree:

- RuStore takes an APK signed with the developer's own certificate. Updates must keep the same
  signature and package and a higher version code, and a key change goes through support and archives
  every version.
- AppGallery requires the same signature for updates.

The strategy was not changed. **It is still not approved by the owner (S-1), and the owner key does not exist (TF-RC1-SIGNING).**

## 4. Device support

| Item | Status | Detail |
|---|---|---|
| Emulator smoke, release APK (temporary key) | **PASS** | API 35 `compact` emulator. Steps: cold launch (`Status: ok`); two package-restricted monkey runs of 1 500 events (no crash, no ANR); a scripted walk (New build → Mage → select a talent → `+` ×2 → expand inspector → back → About → Show details → Third-party licenses → back → Import → back; Home shows the saved "Mage build 2/51"); background/foreground; force-stop and relaunch; airplane-mode relaunch reopening the saved build. Screenshots: [emulator/](store1-evidence/emulator/) |
| Emulator smoke on Pixel_9a / tablet AVDs | NOT RUN | `emulator-5554` (Pixel_9a) holds the owner's debug install and data and was left untouched |
| Physical Android device | **NOT VERIFIED** | No physical device was connected during this audit. Earlier TECNO Spark 40 runs used **debug/staging** builds ([OTA device smoke](../ota/android-device-smoke.md)), not this release candidate. Manual runs are possible with the signed APK (`adb install`) once the owner key exists (rc1-release-checklist §7) |
| Huawei without Google Mobile Services | **NOT VERIFIED** | No Huawei device was available, so H-1…H-14 ([testing-and-publication §5](appgallery/testing-and-publication.md#5-device-test-plan)) are still open. Static evidence (no GMS/HMS dependency or class reference; offline data; framework APIs only) is unchanged. The emulator used here **has** Google Play services, so it proves nothing about GMS-free behaviour |

## 5. Privacy and OTA: what the production build actually does

### 5.1 Code path with OTA off

`MainActivity` calls `AndroidOta.runtime()`. A release build reads `GeneratedOtaSettings.androidProduction`
(a debuggable build reads `androidStaging`). With no owner settings, `OtaConfig.resolve` returns
`Disabled(NOT_ENABLED)` and `runtime()` returns `null`. The app then uses the bundled data only
(`SessionDatasets.bundled`), and no `BlobFetcher` (the only `HttpURLConnection` user) is ever constructed.

Nothing in the repository enables either channel. Release builds stop on any staging setting
(`checkReleaseOtaSettings`). Enabling production needs a base URL plus two `tf-prod-` keys (`PRODUCTION_NEEDS_BACKUP_KEY`).

The About screen shows no "Check for updates" control when OTA is off (seen on the emulator).

### 5.2 Measured network activity (release APK, OTA off)

Method ([`netcheck.sh`](store1-evidence/netcheck.sh)): install the temporary-key publishable APK on the rooted,
read-only emulator with Wi-Fi **online**. Before any app activity, add owner-match accounting rules for the app's
UID (10227) to `iptables` and `ip6tables` `OUTPUT` and start a full `tcpdump`. Then run the flows in §4.

| Measurement | Result |
|---|---|
| iptables `OUTPUT`, owner UID 10227 (IPv4) | **0 packets, 0 bytes** over the whole session ([file](store1-evidence/emulator/iptables-output.txt)) |
| ip6tables `OUTPUT`, owner UID 10227 (IPv6) | **0 packets, 0 bytes** ([file](store1-evidence/emulator/ip6tables-output.txt)) |
| Positive control: the same rule type for UID 2000 (shell) during `ping -c 3` | 3 packets, 252 bytes ([file](store1-evidence/emulator/control-counter.txt)): the method does detect traffic |
| Kernel per-UID byte map (`dumpsys netstats`, `mAppUidStatsMap`) | **no entry for UID 10227** (zero bytes since boot), while the shell UID shows its ping bytes ([file](store1-evidence/emulator/netstats-kernel-uid-bytes.txt)) |
| Sockets owned by UID 10227 in `/proc/net/{tcp,tcp6,udp,udp6}` at four sample points (after launch, after each monkey run, after the idle period) | **none** |
| DNS in the full capture | one query, `android.googleapis.com` (system), and no other name ([file](store1-evidence/emulator/pcap-dns-queries.txt)). DNS on Android is resolved by the system resolver, so this list covers names the app could have asked for. The raw capture is not committed because it contains unrelated system traffic |

**Finding:** on `b242cd6` the production build with OTA off **declares** `INTERNET` but **opened no network connection**
during launch, editing, About/Licenses, import, background/foreground, restart, idle (30 s online) and airplane
mode. Two limits apply:

- This is a dynamic test of the exercised flows plus the static code-path argument in §5.1. It cannot cover
  every possible future code path.
- Third-party library code (`Socket`/`WebView` references) was not exercised by any visible feature and showed
  no traffic.

### 5.3 Statements that are now wrong

Each one was true for `f7494e1` (M5.GALLERY.1) and became stale with `ea77dec` (M5.OTA.3). `docs/ota/test-plan.md` §6B item 6
already required "the `INTERNET` permission reviewed in the release checklist"; that review had not been done.

| Document | Stale statement | Fixed in this PR? |
|---|---|---|
| [privacy-and-compliance](appgallery/privacy-and-compliance.md) §1, §2 | "No `INTERNET` permission, so Android does not let it open network sockets"; "No HTTP client library"; "No `HttpURLConnection`" | **Yes**: factual correction plus a pointer here |
| privacy policy draft §8.1 item 6 / §8.2 item 6 | "does not request access to the internet" | **Yes**: replaced by the owner placeholder ⟨NET-1⟩ with the option texts from §5.5 |
| [store-listing-en](appgallery/store-listing-en.md) §4.1 / [store-listing-ru](appgallery/store-listing-ru.md) §4.1 | "The app does not request internet access." / «Приложение не запрашивает доступ к интернету.» | **Yes**: placeholder ⟨NET-1⟩ |
| [readiness](appgallery/readiness.md) §1, §4, §9 | "no `INTERNET` permission … the decisive network fact" | **Yes**: correction note (the historical record for `f7494e1` is kept) |
| [testing-and-publication](appgallery/testing-and-publication.md) §2.6 | "requests none" (permission descriptions); "no in-app legal/about surface today" (stale since M5.RC.2b) | **Yes**: correction note |
| [rc1-release-checklist](rc1-release-checklist.md) §1, §9 | `[x]` "No INTERNET" | **Yes**: unchecked, with the current fact |
| [rc1-defects](rc1-defects.md) LR-5 | "no network" | **Yes**: note added |
| In-app About text (`about_privacy_permissions`, `about_privacy_offline`) | "works without the internet …", "works offline …" | **No change needed for options A and B**: both are true on `b242cd6` (verified). They **become false under option C** and must change in the same PR that enables OTA |
| Listing sentence "New talent data arrives with app updates." | true with OTA off | must change under option C |

### 5.4 Options for the first release (owner decision NET-1)

| | A. Keep `INTERNET`, OTA off | B. Remove `INTERNET` from release builds while OTA is off | C. Enable production OTA for the first release |
|---|---|---|---|
| Engineering | none | a reviewed change: a release-variant manifest removal (`tools:node="remove"`) **plus** a build-time guard that fails any build with an enabled production OTA channel and no `INTERNET`; re-verify the merged release manifest; debug/staging keep it | the OTA release gate in [test-plan §6A–B](../ota/test-plan.md); a production origin and project-owned hostname (OD-1); production keys and custody (OD-2); a device run of real HTTPS downloads (API 26 and current) |
| Store risk | **RuStore 5.3 (re-read 2026-10-19): every permission must be justified by the app's functionality.** A permission for a disabled feature is hard to justify to a moderator. `INTERNET` is a "normal" permission (no form field), but the data-safety block is pre-filled from manifest permissions (exact effect UNVERIFIED without the console) | none from the permission; the existing "no internet access" texts become true again (after re-verification) | the network use must be declared truthfully in both consoles |
| Privacy texts | §5.5 variant A | the original wording, re-verified on the built APK | a new policy section (§5.5 variant C), new in-app texts, the OD-4 opt-out (**not implemented**: there is no "Download talent data updates" switch) |
| Later OTA update | no permission change | the update adds `INTERNET`. It is a normal permission, granted on install/update without a prompt; stores may show the change. The policy must be updated anyway | — |
| Time to release | shortest | one small engineering PR | longest; most open decisions |

**Recommendation (owner decides): B for the first public release**, as a separate, reviewed engineering
change. It keeps the strongest and simplest privacy position and avoids the RuStore 5.3 question. The cost
is one small manifest-plus-guard change. Choose A only if the owner accepts the RuStore 5.3 moderation risk
and uses the variant-A texts everywhere. Defer C until the OTA gate (OD-1, OD-2, OD-4, OD-5, test-plan §6)
is passed. This audit does **not** implement any option.

**Decision (owner, 2026-10-19): B.** Implemented by the NET-1 change: `src/androidRelease/AndroidManifest.xml`
removes `INTERNET`, and `:composeApp:checkReleaseInternetPermission` stops packaging of any release variant whose merged
manifest requests `INTERNET` while `talentforge.ota.android.production.enabled` is not `true`, or lacks it while it
is. The change that enables production OTA (option C) deletes the removal and changes the texts in the same
release ([NET-1 evidence](net1-evidence/README.md)).

### 5.5 Replacement texts (drafts for owner/legal review)

**Variant A** (keep the permission, OTA off) — privacy policy item 6:

- RU: «6. Разрешения. Приложение не запрашивает доступ к местоположению, контактам, камере, микрофону, файлам или уведомлениям. В пакете приложения объявлено стандартное разрешение Android на доступ к сети (INTERNET) для необязательной функции обновления данных талантов; в этой версии функция выключена, и приложение не устанавливает сетевых соединений.»
- EN: "6. Permissions. The app does not request access to your location, contacts, camera, microphone, files or notifications. Its package declares Android's standard network-access permission (INTERNET) for an optional talent-data update feature; in this version that feature is turned off and the app makes no network connections."
- Listing bullet RU: «• Приложение не подключается к интернету: данные талантов встроены, билды остаются на вашем устройстве.» EN: "• The app does not connect to the internet: talent data is built in and your builds stay on your device."

**Variant B** (permission removed and re-verified): keep the original sentences ("does not request access to the internet …" / «не запрашивает доступ к интернету …»).

**Variant C** (OTA enabled), facts to disclose. The wording is drafted only after OD-1/OD-4:

- the app sends HTTPS GET requests for signed talent-data files to ⟨production host⟩ at start and on return to the foreground (OD-5);
- each request carries the fixed `User-Agent: TalentForge/<version>` and no account, advertising or device identifier;
- the hosting provider ⟨name⟩ processes the device's IP address to deliver the files and may keep access logs under its own terms;
- the user can turn updates off (requires the OD-4 switch).

The in-app `about_privacy_offline` / `about_privacy_permissions`, the RuStore data-safety answers and the AppGallery
privacy tags must change in the same release.

### 5.6 Store declarations (proposed answers, all owner-confirmed in each console)

| Declaration | A / B | C |
|---|---|---|
| AppGallery "Collect personal data" | No | owner/legal decision: whether IP processing by the hosting provider counts as collection under the AGC definitions |
| AppGallery privacy-policy URL | provide anyway (the field is labelled mandatory) | required |
| RuStore data-safety block (data categories) | no categories collected or transmitted; check what the console pre-fills from the manifest 🔑 | declare what is transmitted (technical data/IP to the host) per the console's categories 🔑 |
| RuStore permission justification | A: be ready to explain `INTERNET` under 5.3; B: nothing | justify `INTERNET` by the update feature |

## 6. Store compliance (official sources re-read 2026-10-19)

The sources were re-read today; the earlier statuses were not copied over. RuStore help pages show no editorial
date. The moderation rules page's HTTP `Last-Modified` is 2026-10-15 12:03 GMT, so its text was compared line
by line. Huawei pages show "Last updated".

### 6.1 Changes and additions since M5.GALLERY.1

| Store | Item | Finding | Effect |
|---|---|---|---|
| RuStore | **5.2 / 5.3** (not in the baseline) | 5.2: disclose collected data categories and purposes on the card. 5.3: every permission must be justified by the app's functionality | NET-1 (§5.4) |
| RuStore | data-safety block in the upload form | always present; data categories must cover everything the app or its SDKs collect or transmit; pre-filled from manifest permissions | owner fills it in 🔑 |
| RuStore | screenshots | at least 3 per device type uploaded; 1–10 per batch | the 6–7 planned are fine |
| RuStore | package | APK ≤ 5 GB; updates need the same signature and package and a higher version code | met by design |
| RuStore | 8.5 | delisting if the RuStore version lags other stores, **or** no update for over 12 months while not meeting current platform requirements | release-cadence note for the owner |
| RuStore | 8.6 | individuals may submit up to 10 apps per day | no effect |
| RuStore | age rating | 0+/6+/12+/16+/18+ under FZ-436, chosen in the upload form; the moderator may correct it | owner 🔑 |
| RuStore | account | individual (VK ID, no e-signature) or legal entity/ИП (qualified e-signature). Since 2026-02-01 only ИП and legal entities can use RuStore payments; this does not affect a free app | owner 🔑 |
| AppGallery | Review Guidelines 7.1 | the literal text requires an in-app privacy-policy link and the same link in AGC without a condition; the section preamble frames it for apps that process personal data | an in-app link becomes **necessary in practice** once a URL exists (`LegalLinks.privacyPolicyUrl`) |
| AppGallery | Website field | available only to enterprise developers outside the Chinese mainland | an individual account supplies another contact |
| AppGallery | "Privacy rights URL" | the page contradicts itself (required in the "Before You Start" table, optional in the form) | owner checks in the console 🔑 |
| AppGallery | Review Guidelines 2.5 / 3.9 | no downloading or executing code that changes features | the OTA design is **data-only** (signed JSON/PNG, no code), which matches. Keep it that way |
| AppGallery | parsing error 33 | an APK without a v2 signature is rejected | v2 present ✅ |
| Both | minimum `targetSdk` | neither store documents one | `targetSdk 36` accepted; `OldTargetApi` lint warning only |

### 6.2 Unchanged and re-confirmed

RuStore:

- 2.14 (debug certificate / `debuggable` treated as malware signs);
- 4.1/4.2 (third-party IP needs legal grounds; proof may be requested);
- 5.1 (Russian privacy policy linked from the card and in-app, for apps processing personal data);
- 6.2 (Russian or English UI; Russian card description);
- 6.4 (no third-party marks or names, no other stores' links; a Russian name recommended unless the name is a registered trademark, FZ-168, in force since 2026-03-01);
- 6.6 (icon 512×512 ≤ 3 MB filled to the edges and identical to the installed icon; screenshots ≤ 3 MB, max 2160×3840, no system UI, one orientation, RU/EN text);
- 6.7 (at least one contact);
- 8.1 (Android 8.0 minimum).

AppGallery:

- one 64-bit APK < 4 GB;
- privacy-policy URL field;
- privacy tags;
- permission descriptions for sensitive permissions only;
- mandatory content rating;
- AI declaration;
- 9.1 / 9.5 / 1.15 / 1.16;
- icon 216 or 512 PNG ≤ 2 MB;
- 3–8 screenshots 800×450 / 450×800, sides 320–3840, ≤ 2 MB.

### 6.3 Compliance status of this build

| Item | Status | Note |
|---|---|---|
| Package name, unique and stable | PASS | `io.github.alexeykravtsov95.talentforge` |
| Versioning | **BLOCKED** (owner) | `1` / `0.1.0` are placeholders; the first public values need a reviewed change (S-5) |
| Signing | **BLOCKED** (owner key) | mechanism PASS (§3.2) |
| Not debuggable, no debug certificate | PASS (temporary-key APK) | re-check on the owner-signed APK (the validator does it) |
| 64-bit, Android 8.0+ | PASS | `arm64-v8a`; `minSdk 26` |
| Icon | PASS (file), NOT VERIFIED (consoles, Huawei launcher) | 512×512 opaque PNG, 185 254 bytes, identical to the RC.2a package; launcher icon present in the APK |
| Screenshots | **BLOCKED** | none exist from the signed release build (§7) |
| Metadata (name, short/full description) | *NET-1 = B:* accurate | the ⟨NET-1⟩ placeholders are replaced by the original sentences; otherwise accurate for `b242cd6` |
| Permissions | *NET-1 = B:* **PASS** (temporary-key APK) | release requests only the AndroidX signature permission ([NET-1 evidence](net1-evidence/README.md)); re-check on the owner-signed APK |
| Privacy policy, public URL, in-app link | **BLOCKED** (owner/legal) | draft only; `LegalLinks.privacyPolicyUrl = null` |
| Age rating | **BLOCKED** (consoles) | facts in [privacy §6](appgallery/privacy-and-compliance.md#6-content-facts-for-age-rating-questionnaires) unchanged |
| Third-party content | **BLOCKED** (legal) | LR-1…LR-3 (§8) |

## 7. Release materials

| Artifact | Status | Why |
|---|---|---|
| Store icon `rc2a-evidence/source/TalentForge-RuStore-512.png` | **READY** (after owner approval) | meets both stores' specifications; the artwork is unchanged since RC.2a |
| Listing EN [store-listing-en](appgallery/store-listing-en.md) | *NET-1 = B:* **READY** (after owner approval) | the permission sentence is restored (it was ⟨NET-1⟩). The "Scope rule" names `f7494e1`; the described features were re-checked against `b242cd6` (Undo/Redo/Reset, Max, Show, share/import, RU/EN all present). Variant B stays gated on LR-2 |
| Listing RU [store-listing-ru](appgallery/store-listing-ru.md) | *NET-1 = B:* **READY** (after owner approval) | as EN; the Russian app-name question (O-10) is open |
| Release notes (EN/RU §5) | **READY** (after owner approval) | true for options A/B. Under C, "fully offline" / «без интернета» must change |
| Reviewer notes ("Offline app … All features work without network access") | **READY** for A/B | verified in airplane mode (§4). Under A, add one line explaining `INTERNET` (RuStore 5.3) |
| Privacy policy RU/EN (privacy §8) | **BLOCKED** | *NET-1 = B:* item 6 restored to "does not request access to the internet"; still open: the owner fields, hosting, URL, contact |
| Phone screenshots EN/RU | **BLOCKED** | must come from the **owner-signed release build**, with no system UI, gated by LR-1/LR-3. Everything in `docs/screenshots/*`, `docs/release/*/screenshots/*` and `store1-evidence/emulator/*.png` is debug, desktop-rendered or test evidence (the store1 captures include the status bar and were taken with a temporary key) and **must not** be uploaded |
| Data-safety / privacy-tag answers | **BLOCKED** | consoles 🔑 (*NET-1 = B:* §5.6 column A/B; no permission to justify under RuStore 5.3) |
| Age rating, AI declaration | **BLOCKED** | consoles 🔑 |
| Developer contact | **BLOCKED** | owner |
| Signed APK | **BLOCKED** | owner key |

## 8. Legal blockers (none closed by this audit; a passing build does not close them)

| ID | Item | Status on 2026-10-19 | What changed |
|---|---|---|---|
| LR-1 | Rights to the game data, talent icons and talent text (client-extracted Blizzard content) | **OPEN: legal review** | The rules re-read today make it explicit in both stores: RuStore 4.1/4.2 (proof may be requested), AppGallery 9.1. Store screenshots 1–3 show this content |
| LR-2 | Trademarks and positioning ("World of Warcraft", "Forever", class names; disclaimer) | **OPEN: legal review + owner decision** | The in-app About shows the trademark-free draft disclaimer (checked on the emulator); the listings default to the trademark-free variant A. RuStore 6.4 and AppGallery 1.2/1.15/9.5 unchanged |
| LR-3 | Provenance and licence of the 9 class artwork files (`composeResources/drawable/class_*.png`) | **OPEN: owner** | Still not recorded anywhere in the repository (the only trace is commit `2a2ada2` "approved TalentForge class paintings") |
| LR-4 | Third-party licences and notices | **OPEN: owner/legal sign-off** | Mechanism present: Third-party licenses screen (M5.UI.ABOUT.1), Android notices drift-checked in CI on `b242cd6`. The repository has no `LICENSE` (an owner choice, not a store requirement) |
| LR-5 | Privacy policy, public URL, developer contacts | **OPEN: owner** | Depended on NET-1 (§5); *NET-1 = B* settles the network wording. Policy not hosted; `LegalLinks.privacyPolicyUrl = null`; no contact chosen; an AppGallery individual account gets no Website field |

**M5.LEGAL.1 follow-up (2026-10-19).** The game-content rights and provenance audit documents the
evidence behind LR-1…LR-3 and does **not** close any of LR-1…LR-5:

- [legal1-content-inventory.md](legal1-content-inventory.md): what game-derived material exists, its
  provenance chain (client → wago.tools → pipeline → `data/` → `composeResources` → APK/OTA) and every
  place it is distributed today (APK, public repository and its history, public CI artifacts, the
  OTA staging origin);
- [legal1-rights-assessment.md](legal1-rights-assessment.md): the Blizzard and store sources re-read
  for this audit, the risk matrix and what remains NOT ESTABLISHED;
- [legal1-release-options.md](legal1-release-options.md): release options A (existing content),
  B (reduced IP exposure) and C (permission first), the owner decisions they need and the
  recommended order of work.

## 9. Issues

### 9.1 Blocking

| # | Issue | Severity | Owner/next step |
|---|---|---|---|
| STORE1-NET | `INTERNET` declared, texts say otherwise (§5) | **P0** for listings and privacy | *NET-1 = B:* resolved by the NET-1 change once merged (manifest removal, build guard, re-verification, texts) |
| TF-RC1-SIGNING | no owner key, no production-signed APK | P0 | owner (O-1, O-2) |
| LR-1…LR-5 | legal review | P0 | owner/legal |
| STORE1-SHOTS | no store screenshots from a signed release build | P0 for submission | after signing and LR-1/LR-3 |
| VERSION | first public `versionCode`/`versionName` | P0 for submission | owner, reviewed change |
| M5.RC.3 | physical-device and Huawei runs (H-1…H-14) not done | P1 gate | owner device / Cloud Testing |
| CONSOLE | account checks, age rating, data safety, AI declaration, countries | P0 for submission | owner 🔑 |

### 9.2 Not blocking

| # | Issue | Note |
|---|---|---|
| STORE1-CI-RELEASE | CI does not run `assembleRelease`/`bundleRelease`/`lintRelease` as their own steps (the signing self-test builds the release variant) | a lint regression would surface only in local audits; consider adding `lintRelease` to mobile-ci |
| STORE1-RUNNER | `run-gallery1-checks.sh` still marks `lintRelease` EXPECT=fail; the freshness step needs the project venv (`--python .venv/bin/python`) | runner maintenance; today's results are unaffected (§3.1) |
| TF-RC1-ANDROID-CONFIG | R8 off (dex 20 MB uncompressed); 4 ABIs for a 37 KB native lib; no `localeConfig`; `targetSdk 36` (`OldTargetApi`) | owner decisions; each needs a device regression |
| Lint warnings | 9 pre-existing | unchanged since RC.2b |
| OD-4 switch | no user opt-out for OTA | only matters for option C |
| Freshness re-check | must be re-run within 24 h of the RC tag (rc1-release-checklist §8.3) | CURRENT today |

## 10. Owner actions

| # | Action | Blocks | Needs |
|---|---|---|---|
| OA-1 | ~~Decide NET-1~~ **Done 2026-10-19: B** | listings, policy, declarations | — |
| OA-2 | ~~For B: request the engineering change~~ **Done:** the NET-1 change (branch `fix/m5-net-1-release-no-internet`); review and merge it | NET-1 = B | — |
| OA-3 | Approve the signing strategy (S-1); create the RSA 4096 key outside every checkout; two offline backups and a restore drill; record the SHA-256 ([guide](android-release-signing.md)) | signed APK | owner machine |
| OA-4 | Decide the first public `versionCode`/`versionName` (reviewed change) | signed APK | — |
| OA-5 | Legal review LR-1, LR-2 (incl. the in-app disclaimer and listing variant), LR-3 (record class-art provenance), LR-4 (notice mechanism) | publication | legal |
| OA-6 | Finalise the privacy policy (RU + EN, owner fields), host it at a public URL, then set `LegalLinks.privacyPolicyUrl` in a reviewed change (AppGallery 7.1 in-app link) | both stores | hosting |
| OA-7 | Choose the developer contact (RuStore 6.7; AppGallery service info) and the Russian-name question (O-10) | listings | — |
| OA-8 | Build `assemblePublishableRelease` with the owner key on the release commit; keep the report | upload | key |
| OA-9 | Capture EN/RU screenshot sets from that APK per [assets §3](appgallery/assets-checklist.md#3-screenshots) | listings | device/emulator |
| OA-10 | Device runs: rc1-release-checklist §7 on a physical phone; Huawei H-1…H-14 without GMS (or AGC Cloud Testing as a supplement) | M5.RC.3 | devices |
| OA-11 | 🔑 Consoles: verify AGC can create and submit the Android app; RuStore account type; age ratings; data safety/privacy tags; AI declaration; countries | submission | accounts |
| OA-12 | Re-run the freshness check within 24 h of the RC tag | RC tag | — |

## 11. Recommended order

1. **NET-1** (OA-1; *done: B*). If B, do the small engineering PR first, so that every text and screenshot is made once, against the final manifest.
2. **Legal**: LR-1…LR-3 first (they can stop publication outright), then LR-2 copy and the LR-4 sign-off.
3. **Privacy policy** and contact (OA-6, OA-7), then the in-app link change.
4. **Version numbers and the owner key** (OA-3, OA-4), then the first publishable APK (OA-8).
5. **Screenshots** from that APK (OA-9) and the final EN/RU listings.
6. **Device runs**: M5.RC.3, Huawei included (OA-10), on that exact APK.
7. **Consoles** (OA-11): AppGallery Open Testing first, then the formal release and RuStore in the same window ([testing-and-publication §6.1](appgallery/testing-and-publication.md#61-open-testing--formal-production-release)).

## 12. Reproduction

```bash
# 1. Build/test/inspect an explicit commit (scratch worktree; nothing written to the repo)
bash docs/release/appgallery/evidence/run-gallery1-checks.sh --fetch --commit origin/main \
  --expect-sha <sha> --python "$PWD/.venv/bin/python" --bundletool "$(command -v bundletool)"
# 2. Signing self-test (temporary key, deleted on exit)
scripts/release_signing_selftest.sh
# 3. Publishable APK with a temporary key, for device checks only (never upload it)
bash docs/release/store1-evidence/publishable-tempkey.sh "$PWD" <scratch-out>
# 4. Network behaviour on a rooted emulator started with -read-only, package NOT installed
emulator -avd <google_apis AVD> -read-only -no-snapshot -no-window -port 5560 &
bash docs/release/store1-evidence/netcheck.sh emulator-5560 <scratch-out>/TalentForge-<v>-<code>.apk <scratch-net>
```

`tap.sh` (tap by visible text or content description) drove the scripted walk in §4 interactively. The walk's steps are listed there.

## 13. Limitations

- Every store statement comes from official pages read on 2026-10-19; the rules can change before submission.
  Console behaviour (pre-filled data-safety answers, the exact questionnaires, country lists) is **UNVERIFIED** without the owner's accounts.
- The network result covers the release build of `b242cd6` with OTA off, on one emulator, for the exercised flows.
- No physical device, no Huawei device, no owner key.
- iOS was not audited.
