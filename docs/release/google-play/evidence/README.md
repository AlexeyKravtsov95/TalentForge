# M5.PLAY.1 — Evidence

Machine: owner's Mac (Apple silicon), JDK 17, Android build-tools 37.0.0, bundletool 1.18.3 (only
used by scenario `play-apks-16kb` and for the fixture cross-check), branch
`release/m5-play-1-google-play`. All keys used were **temporary self-test keys**, deleted at the end
of each run. Their certificate digests in these files are public and belong to keys that no longer
exist. Logs are written as `.txt` (`*.log` is git-ignored), with the temporary directory shown as
`<tmp>` and the home directory as `~`.

| Directory | Produced by | Result |
|---|---|---|
| [`play-signing-selftest/`](play-signing-selftest/) | `scripts/play_signing_selftest.sh --evidence …` (two temporary keys, 20 scenarios) | see `summary.txt` |
| [`release-signing-selftest/`](release-signing-selftest/) | `scripts/release_signing_selftest.sh --evidence …` (RuStore/AppGallery path, one temporary key, 14 scenarios), re-run to show the APK path still works after M5.PLAY.1 | see `summary.txt` |

Key files in `play-signing-selftest/`:

- `00-test-certificates.txt`: the two temporary certificates (app signing, upload);
- `01-apk.report.json` and `01-aab.report.json`: the two `SELFTEST-PASS` reports of one build;
- `01-apksigner.txt` and `01-keytool-printcert.txt`: the certificates read back with the SDK/JDK tools;
- `03-metadata.txt`: the release metadata both reports agree on;
- `18-zipalign.txt`: `zipalign -c -P 16 -v 4` on the arm64-v8a and x86_64 split APKs that bundletool
  generated from the staged bundle;
- `NN-*.txt`: the Gradle and validator log of each scenario.

Native library check, read with the NDK's `llvm-readelf -Wl` on the `bundlePlay` bundle
(`androidx.graphics:graphics-path` 1.0.1, `libandroidx.graphics.path.so`):

| ABI | LOAD alignment | GNU_RELRO (VirtAddr + MemSiz) | Writable LOAD after RELRO | 16 KB verdict |
|---|---|---|---|---|
| arm64-v8a | 0x4000 (×3) | 0x5c40 + 0x3c0 = 0x6000 (not a 16 KB multiple) | RW LOAD 0x5c40–0x5fd0 lies inside RELRO; next RW LOAD at 0x9fd0 (≥ 0x8000) | compliant: RELRO is alone in its LOAD segment, the case Google's page-size guide calls compliant |
| x86_64 | 0x4000 (×3) | 0x5ec0 + 0x1140 = 0x7000 | RW LOAD 0x5ec0–0x6268 inside RELRO; next RW LOAD at 0xa268 | compliant, same reason |

`verify_release_apk.py` / `verify_play_bundle.py` encode this rule (`relro_page_problem`): no
writable byte outside RELRO may lie in RELRO's range rounded out to 16 KB pages. The unit tests pin
it on these exact program headers. They also check Google's own non-compliant example, and that the
same library is reported as unsafe with 64 KB pages.

`tests/fixtures/play_bundle/AndroidManifest.pb` and `BundleConfig.pb` are
`base/manifest/AndroidManifest.xml` and `BundleConfig.pb` of the unsigned `bundlePlay` bundle. When
they were extracted, `bundletool dump manifest` and `bundletool dump config` (1.18.3) showed:
- `package io.github.alexeykravtsov95.talentforge`, `versionCode 1`, `versionName 0.1.0`;
- `minSdkVersion 26`, `targetSdkVersion 36`;
- the AndroidX receiver permission as the only `uses-permission`;
- `uncompressNativeLibraries: enabled, PAGE_ALIGNMENT_16K`.

The validator's decoder gives the same values (`test_real_bundle_manifest_decodes_like_bundletool`).
