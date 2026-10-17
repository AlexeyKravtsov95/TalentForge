# TF-RC2B-IOS-NOTICES — evidence for the iOS open-source notices

Established on 2026-10-18 on the M5.RC.2b branch (Kotlin 2.4.20, Compose Multiplatform 1.10.3,
`org.jetbrains.skiko:skiko-iosarm64:0.9.37.4`) with a local Mac run. Every statement below comes
from a build artifact, the Gradle cache or a pinned upstream revision. The generator
`scripts/generate_oss_notices.py` re-checks the archive-level facts on every `--check`.

## 1. What the iOS framework links

- **Gradle modules.** `:composeApp:iosArm64CompileKlibraries` is the klib set that
  `linkReleaseFrameworkIosArm64` links into the device framework. It resolves 80 external modules
  (BOMs excluded) plus `:shared`.
  - New compared to Android: `org.jetbrains.skiko:skiko[-iosarm64]:0.9.37.4` and
    `org.jetbrains.kotlinx:atomicfu[-iosarm64]:0.27.0`.
  - Every POM declares Apache-2.0, verified on regeneration.
- **Native archives inside the 39 resolved klib files** (`targets/ios_arm64/included/*.a`):
  - `skiko.klib` (skiko-iosarm64 0.9.37.4) bundles 21 archives: `libskia.a`, `libskottie.a`,
    `libskparagraph.a`, `libskresources.a`, `libsksg.a`, `libskshaper.a`, `libskunicode_core.a`,
    `libskunicode_icu.a`, `libsvg.a`, `libjsonreader.a`, `libharfbuzz.a`, `libicu.a`, `libjpeg.a`,
    `libpng.a`, `libwebp.a`, `libwebp_sse41.a`, `libzlib.a`, `libexpat.a`, `libdng_sdk.a`,
    `libpiex.a`, and `skiko-native-bridges-ios-arm64.a` (Skiko's own native bridges).
  - `ui-uikit-uikitarm64` 1.10.3 bundles `libCMPUIKitUtils.a` (Compose's own UIKit helpers).
  - No other klib carries an archive. The only other native entries are compiler-generated
    `cstubs.bc` (atomicfu, ui-uikit).
- **Object-file families** (member names in the arm64 slice, the prefix before the first `.`).
  `libskia.a` contains copies of the third-party objects as well:
  - Skia: `libskia gpu gpu_shared pdf xml fontmgr_mac_ct jpeg_decode jpeg_encode
    png_decode_libpng png_encode raw webp_decode webp_encode wuffs libskcms`, plus the module
    archives' families (`libskottie libskresources libsksg libskshaper libskparagraph
    libskunicode_core libskunicode_icu libsvg libjsonreader`);
  - third party: `libharfbuzz libicu libjpeg libjpeg12 libjpeg16 libpng libwebp libwebp_sse41
    libwuffs libzlib zlib_adler32_simd zlib_arm_crc32 zlib_inflate_chunk_simd libexpat libdng_sdk
    libpiex`.
- **Not linked** (no defined symbol in any archive):
  - FreeType (iOS uses CoreText; `FT_*` = 0);
  - Brotli, Highway, libavif, libjxl, libyuv, ICU4X, Vulkan Memory Allocator, SPIR-V, ANGLE and
    SwiftShader.
- **The release framework.** `linkReleaseFrameworkIosArm64` builds a static framework. It contains
  all of the archives above as members, next to `ComposeApp.framework.o` and the `CMP*` objects.
  - The notices therefore list everything the framework carries.
  - The app's final link may drop unreferenced objects, so the list is a superset of the shipped
    code, never a subset.
- **The iOS host app** (`mobile/iosApp`) imports only `SwiftUI` and `ComposeApp`, and has no Swift
  package or other dependency.

## 2. Versions and upstream revisions

skiko 0.9.37.4 pins `dependencies.skia=m138-80d088a-1` (`skiko/gradle.properties`, tag
`v0.9.37.4`). That is JetBrains/skia-pack release `m138-80d088a-1`, built from JetBrains/skia
`80d088ad6ee869e6b511763b5238cff5de72684d`.

- skia-pack's `script/build.py` sets `skia_use_system_{expat,libjpeg_turbo,libpng,libwebp,zlib,
  harfbuzz,icu,freetype2}=false`, so each library is built from the revision Skia's DEPS pins.
- Versions come from the archives' own strings, where they carry one.

| Component | Version (evidence) | Pinned revision (Skia DEPS at 80d088a) |
|---|---|---|
| Skia | m138 | JetBrains/skia 80d088a |
| HarfBuzz | 11.2.1 (string in `libharfbuzz.a`) | harfbuzz 08b52ae2 |
| ICU | 74.2 (`README.chromium`; data `icudt74l`, symbol `icudt_skiko74_dat`) | chromium/deps/icu 364118a1 |
| libjpeg-turbo | 3.1.0 (`libjpeg-turbo version 3.1.0`) | chromium/deps/libjpeg_turbo e14cbfaa |
| libpng | 1.6.43 (`libpng version 1.6.43`) | skia third_party/libpng ed217e3e |
| libwebp | 1.4.0 (`NEWS` at the pin) | webm/libwebp 845d5476 |
| Wuffs | 0.3 (`libwuffs.wuffs-v0.3.o`) | wuffs-mirror-release-c e3f919cc |
| zlib (Chromium) | 1.3.0.1 (`deflate 1.3.0.1`, `Cr_z_` prefix) | chromium/src/third_party/zlib 646b7f56 |
| Expat | 2.7.1 (`expat_2.7.1`) | libexpat 8e49998f |
| Adobe DNG SDK | 1.4 (`README.version`) | platform/external/dng_sdk dbe0a676 |
| Piex | (no version string) | platform/external/piex bb217acd |

## 3. Licences and the bundled texts

Every text in `composeResources/files/legal/` is the verbatim upstream file at the pinned revision.
Its SHA-256 is pinned in the generator, in `OpenSourceNoticesTest` and in
`tests/unit/test_generate_oss_notices.py`.

| Component | Licence(s) | Bundled file(s) | Upstream file |
|---|---|---|---|
| Skiko | Apache-2.0 + NOTICE | `LICENSE-Apache-2.0.txt`, `NOTICE-Skiko.txt` | POM; `NOTICE` at v0.9.37.4 |
| Skia (incl. skcms) | BSD-3-Clause | `LICENSE-Skia.txt` | `LICENSE` |
| HarfBuzz | Old MIT | `LICENSE-HarfBuzz.txt` | `COPYING` |
| ICU | Unicode License V3 + third-party notices (dictionaries, tz, double-conversion…) | `LICENSE-ICU.txt` | `LICENSE` |
| libjpeg-turbo | IJG + BSD-3-Clause + zlib | `LICENSE-libjpeg-turbo.md`, `README-libjpeg-turbo-IJG.txt` | `LICENSE.md`, `README.ijg` |
| libpng | PNG Reference Library License v2 (+ v1 text) | `LICENSE-libpng.txt` | `LICENSE` |
| libwebp | BSD-3-Clause + patent grant | `LICENSE-libwebp.txt`, `PATENTS-libwebp.txt` | `COPYING`, `PATENTS` |
| Wuffs, Piex | Apache-2.0 | `LICENSE-Apache-2.0.txt` | `LICENSE` (Wuffs byte-identical; Piex the same text, whitespace differs) |
| zlib (Chromium) | zlib + Chromium BSD-3-Clause | `LICENSE-zlib.txt`, `LICENSE-Chromium.txt` | `LICENSE`; chromium/src `LICENSE` at 24976593 |
| Expat | MIT | `LICENSE-Expat.txt` | `expat/COPYING` |
| Adobe DNG SDK | DNG SDK License Agreement + DNG patent licence | `LICENSE-DNG-SDK.txt`, `PATENTS-DNG-SDK.txt` | `LICENSE`, `PATENTS` |
| Kotlin stdlib third-party code | ThreeTen BSD-3-Clause; GWT and Guava Apache-2.0 | `LICENSE-ThreeTen.txt` | JetBrains/kotlin v2.4.20 `license/third_party/threetenbp_license.txt` (identical to the K/N 2.4.20 distribution's copy) |

Notes on the evidence:

- **Chromium zlib.** The SIMD objects (`adler32_simd.c`, `crc32_simd.c`, `cpu_features.c`,
  `chunkcopy.h`) say "Copyright … The Chromium Authors" and point to the "Chromium source
  repository LICENSE file". That is why the Chromium text is listed next to the zlib licence.
- **Required statements** are shown verbatim on the component card:
  - "This software is based in part on the work of the Independent JPEG Group." (IJG licence,
    `README.ijg` clause 2);
  - "This product includes DNG technology under license by Adobe Systems Incorporated." (Adobe DNG
    patent licence, which requires it "in a prominent manner" in the documentation).
- **Kotlin standard library.** JetBrains' `license/README.md` lists third-party code in it:
  - `kotlin/time` (ThreeTen, BSD-3-Clause);
  - `kotlin/collections` (GWT, Apache-2.0);
  - `Unsigned.kt` (Guava, Apache-2.0).

  The release framework contains all three (`kotlin.time.Duration`, `kotlin.collections.HashMap`,
  `kotlin#ulongToString`).
- **Apache-2.0 NOTICE files.** No resolved klib contains a NOTICE file; the 14 AndroidX klibs
  carry only the Apache licence. Skiko's repository has a `NOTICE` (AOSP-derived code), which is
  shown even though the klib does not carry it.

## 4. On the iOS simulator

- **Build.** `xcodebuild` of `iosApp` (Debug, iOS Simulator arm64) succeeded. The app bundle carries
  `compose-resources/…/files/legal/oss-notices-ios.json` and all 16 licence texts.
- **About screen.** On an iPhone 17 Pro simulator (iOS 26.4) it showed:
  - the iOS list (AndroidX 28 modules and Compose Multiplatform 41, the iOS counts);
  - the linked components with their versions and "Statically linked into
    org.jetbrains.skiko:skiko-iosarm64:0.9.37.4";
  - the verbatim Adobe DNG statement;
  - a licence text opened through its toggle.
- **Screenshots:**
  - [linked components](screenshots/ios-notices/ios-about-linked-components.png);
  - [licence text](screenshots/ios-notices/ios-about-licence-text.png).

## 5. Open items (not resolved here; owner/legal review)

1. **SpiderSymbol probe font in Skia.**
   - `src/utils/mac/SkCTFont.cpp` (Skia BSD-3-Clause, "Copyright 2020 Google Inc.") embeds a tiny
     TrueType font, `kSpiderSymbol_ttf`, compiled into `libskia.SkCTFont.o`.
   - The font's `name` table says "Copyright (c) 2015, Google." and gives the licence URL
     `http://scripts.sil.org/OFL`, with no OFL version.
   - It is listed under Skia's licence (the header of the source file). Whether the OFL must also
     be reproduced is **not established**.
2. **Adobe DNG SDK terms beyond attribution.**
   - The agreement's §5 (indemnification when distributing in a commercial product) and Google's
     `METADATA` (`license_type: BY_EXCEPTION_ONLY`) need a legal decision.
   - `dng_color_space.o` also carries ICC-profile copyright strings ("Hewlett-Packard Company
     1998", "Eastman Kodak Company, 1999", "Adobe Systems Incorporated 1999"), shipped as part of
     the DNG SDK.
3. **Kotlin/Native runtime C++.**
   - The K/N 2.4.20 distribution's `licenses/third_party` has `mpmc_queue_LICENSE.txt`
     (BSD-2-Clause, Dmitry Vyukov). `licenses/README.md` maps it to no path, and no symbol in the
     release framework identifies it. Its use is **not established**, so it is not listed.
   - libbacktrace is not linked (no `backtrace_*` definitions).
   - The Boost-derived `Utils.cpp` is under BSL-1.0, which requires no notice for object code; it
     is not listed.
4. **Android follow-up (outside this change).** The Android `kotlin-stdlib` jar contains the same
   ThreeTen-derived `kotlin.time` code. The Android notices list kotlin-stdlib under Apache-2.0
   only (its POM), without the ThreeTen text.
5. **Scope.** The notices describe the device framework (`iosArm64`). The simulator framework is
   not distributed.
