# OTA Android device smoke (M5.OTA.4a)

A reproducible manual check of the OTA client on Android: a **debug** build of the staging channel
against the local staging stand ([staging-setup.md](staging-setup.md)) over HTTPS. It covers what the
JVM tests cannot: Android's own TLS stack and `HttpURLConnection`, the `noBackupFilesDir` store, the
Activity/process lifecycle and the real UI.

What was actually run, per target, is in
[M5.OTA.4a-integration-report.md](M5.OTA.4a-integration-report.md) §4. A target that was not run is
reported as NOT RUN — never inferred from another one.

| Target | Image / device | Origin URL in the build |
| --- | --- | --- |
| Emulator, API 26 (the app's `minSdk`) | a `system-images;android-26;google_apis;<abi>` AVD | `https://10.0.2.2:8443` (the emulator's alias of the host's loopback) |
| Emulator, current API | an API 35/36 Google APIs AVD | `https://10.0.2.2:8443` |
| TECNO Spark 40 (physical) | the owner's device, USB debugging on | `https://localhost:8443` through `adb reverse` (§1.3) |

## 1. Setup

### 1.1 The stand

```bash
python scripts/ota_staging_stand.py prepare ~/tf-ota-stand            # outside the repository
python scripts/ota_staging_stand.py activate ~/tf-ota-stand 1-a-staged
python scripts/ota_staging_stand.py serve ~/tf-ota-stand --port 8443   # keep it running
```

### 1.2 The debug build

```bash
source ~/tf-ota-stand/android-staging.env      # staging channel, the stand's keyring and CA
cd mobile
./gradlew :composeApp:assembleDebug -Ptalentforge.applicationIdSuffix=.otastaging
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

* `.otastaging` installs a separate app (`io.github.alexeykravtsov95.talentforge.otastaging`) with its
  own data: the user's installed TalentForge and its saved builds are never touched.
* While these variables are set, every release variant stops in `checkReleaseOtaSettings`: the test
  configuration cannot reach a store build. Unset them (`unset ${!TALENTFORGE_OTA_@}`) afterwards.
* The stand's CA is the **only** TLS root of this debug build's OTA client; certificate validity and
  the host name (`localhost`, `127.0.0.1`, `10.0.2.2` are in the certificate) are verified as usual.

### 1.3 Physical device (TECNO Spark 40)

The stand binds `127.0.0.1` only and its certificate names `localhost`, so reverse the port and build
for that URL:

```bash
adb -s <serial> reverse tcp:8443 tcp:8443
export TALENTFORGE_OTA_ANDROID_STAGING_BASEURL=https://localhost:8443
```

## 2. What a device can and cannot show today

The app bundles five builds, including both stand releases (70374 = A; 70450 = B, which is also the
bundled product). On a device, therefore:

* **A active** (`1-a-staged`): A is older than the bundled product → `KEEP_BUNDLED_FLOOR`; nothing is
  downloaded and the app stays on its bundle ("Talent data is up to date").
* **B active** (`2-b-staged`): B is the bundled build → `ACTIVATE`. The manifest is downloaded over
  HTTPS and checked against the signed catalog; every file is copied from the bundle after its SHA-256
  is re-checked against the manifest. About → Talent data then shows "Downloaded update
  1.60.1.70450-r1".
* **Rollback** (`3-rollback-a`): A is below the floor → `KEEP_BUNDLED_FLOOR`; B stays active.
* **Level 2** (`4-level-2`): "Newer talent data needs an app update"; nothing downloaded.

A multi-megabyte download on a device needs a release of a build the app does not bundle; no such
build exists in `data/` today (every published build is bundled). That path — the same
`jvmCommonMain` code Android runs — is covered on the JVM by `OtaStagingE2ETest`; on a device it
becomes testable with the next data refresh (M5.OTA.4b).

## 3. Procedure

Read the client's state at any point (debuggable build only):

```bash
PKG=io.github.alexeykravtsov95.talentforge.otastaging
adb shell run-as $PKG cat no_backup/ota/v1/state.json
adb shell run-as $PKG ls no_backup/ota/v1/releases no_backup/ota/v1/staging
```

The stand's console logs every request (`GET /v1/...`).

| # | Step | Expected |
| --- | --- | --- |
| 1 | Install, launch (stand on `1-a-staged`) | the first screen appears without waiting for the network; catalog GETs only (see step 6); About → Talent data: game build 1.60.1.70450, "Included with the app"; `state.json` with sequence 1 and no active release |
| 2 | Create and save a build | it is listed in the library (opening it later from Home → Continue sets its `lastOpenedAt`; that is the only library change a run may show) |
| 3 | `activate 2-b-staged`; About → Check for updates | the catalog GET and one blob GET (the B manifest); "Talent data updated to …"; About: "Downloaded update 1.60.1.70450-r1"; `state.json` active `1.60.1.70450-r1`; the saved build opens unchanged |
| 4 | Check again | "Talent data is up to date"; the catalog GET only |
| 5 | Home, reopen (background → foreground) | no crash, state unchanged; the foreground check is skipped inside the 6 h interval (OD-5), so no request |
| 6 | `adb shell am force-stop $PKG`; relaunch (process death) | the cold start shows the release at once; catalog GETs only (the start and the foreground trigger both fire on a cold start — two GETs observed on API 35, a harmless duplicate noted for M5.OTA.4b); `state.json` unchanged |
| 7 | Stop the stand; Check for updates | "Couldn't check for updates: no connection"; nothing changes; the saved build opens |
| 8 | Serve again; `activate 3-rollback-a`; check | "Talent data is up to date" (`KEEP_BUNDLED_FLOOR`: A is below the bundled product); `state.json` sequence 3, B stays active; no blob GET |
| 9 | `activate 4-level-2`; check | "Newer talent data needs an app update"; `state.json` sequence 4; no blob GET |
| 10 | Replay: `activate 3-rollback-a` again; check | "Couldn't check for updates (SEQUENCE_REGRESSION)": an older catalog is never accepted again; nothing changes |
| 11 | Interruptions: `pm clear $PKG`; serve `2-b-staged` with `--blob-delay-ms 8000`; launch; while the manifest GET is pending press Home (the attempt is cancelled), relaunch, check; then repeat with `am force-stop` instead of Home | no `.part` left and no active release after either interruption, the staging directory kept; the next check completes and activates B |
| 12 | A build without `TRUSTANCHOR` against the same stand | every check fails as "no connection"; the stand logs no HTTP request (TLS refused first) |

Record per target: device and API level, the stand's key id (`report.json`), the observed result of
every step, and screenshots of steps 1, 3 and 9.

## 4. Cleanup

```bash
adb uninstall io.github.alexeykravtsov95.talentforge.otastaging
unset ${!TALENTFORGE_OTA_@}
rm -rf ~/tf-ota-stand            # the ephemeral staging key and the TLS key go with it
```
