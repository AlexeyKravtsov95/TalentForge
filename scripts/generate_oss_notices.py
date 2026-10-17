#!/usr/bin/env python3
"""Open-source notices of the Android release APK and the iOS framework (LR-4).

The About screen lists every third-party component the platform's distributed binary
ships, with each component's licences and their full texts. This script derives the
lists from Gradle — never by hand:

    python3 scripts/generate_oss_notices.py                     # regenerate both platforms
    python3 scripts/generate_oss_notices.py --check             # fail when either drifts
    python3 scripts/generate_oss_notices.py --check --platform ios

Android (M5.RC.2b) lists the external modules of ``:composeApp:releaseRuntimeClasspath``.
iOS (TF-RC2B-IOS-NOTICES) lists the external modules of
``:composeApp:iosArm64CompileKlibraries`` — the klibs ``linkReleaseFrameworkIosArm64``
links into the device framework — plus native code that is not a Gradle module: Skiko's
klib bundles static archives of Skia and of the third-party libraries Skia links
(``targets/ios_arm64/included/*.a``), and the Kotlin standard library incorporates
third-party code. Each is a curated *linked* component, established from the archives
(object files, symbols, version strings) and from the pinned upstream sources — the
evidence is in docs/release/rc1-defects.md (TF-RC2B-IOS-NOTICES).

Fail-closed rules:

- every module must be claimed by exactly one curated component, and regeneration reads
  its POM (``<licenses>``, following ``<parent>``) and fails unless the declared licence
  is the component's curated licence;
- every static archive inside every resolved iOS klib must be either the module's own
  code (curated) or claimed — object-file family by family — by the curated native
  inventory of that exact module version; an unknown archive, family or version fails;
- every bundled licence text must match its curated SHA-256: the verbatim upstream file
  at a pinned revision. Regeneration downloads a missing text from that revision.

``--check`` rebuilds each document offline (no POM or text download) and fails when it
differs from the committed file. Bills of materials (``*-bom``) are version constraints
with no code and are not listed; ``:shared`` is TalentForge's own module.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MOBILE = ROOT / "mobile"
RESOURCES = MOBILE / "composeApp/src/commonMain/composeResources"
SCHEMA = "talentforge.oss-notices.v2"
GRADLE_CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / (
    "caches/modules-2/files-2.1"
)
REPOSITORIES = ("https://dl.google.com/dl/android/maven2", "https://repo1.maven.org/maven2")

APACHE_2 = "Apache-2.0"
# POM <license><name> spellings that mean the Apache License 2.0
APACHE_2_NAMES = frozenset(
    {
        "The Apache Software License, Version 2.0",
        "The Apache License, Version 2.0",
        "Apache License, Version 2.0",
        "Apache-2.0",
    }
)


def _googlesource(host: str, repo: str, revision: str, path: str) -> str:
    return f"https://{host}.googlesource.com/{repo}/+/{revision}/{path}?format=TEXT"


@dataclass(frozen=True)
class LicenseText:
    """A bundled licence or notice text: the verbatim upstream file at a pinned revision."""

    name: str
    file: str
    sha256: str
    origin: str


_SKIA = "80d088ad6ee869e6b511763b5238cff5de72684d"  # JetBrains/skia, skia-pack m138-80d088a-1
_JPEG = ("chromium", "chromium/deps/libjpeg_turbo", "e14cbfaa85529d47f9f55b0f104a579c1061f9ad")
_WEBP = ("chromium", "webm/libwebp", "845d5476a866141ba35ac133f856fa62f0b7445f")
_DNG = ("android", "platform/external/dng_sdk", "dbe0a676450d9b8c71bf00688bb306409b779e90")

LICENSES: dict[str, LicenseText] = {
    APACHE_2: LicenseText(
        "Apache License 2.0",
        "files/legal/LICENSE-Apache-2.0.txt",
        "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
        "https://www.apache.org/licenses/LICENSE-2.0.txt",
    ),
    "Skiko-NOTICE": LicenseText(
        "Skiko NOTICE",
        "files/legal/NOTICE-Skiko.txt",
        "35dd077c24768b53625101cfcc0ad853ae7e2abc08bd2a111a0267760d1406d8",
        "https://raw.githubusercontent.com/JetBrains/skiko/v0.9.37.4/NOTICE",
    ),
    "Skia-BSD-3-Clause": LicenseText(
        "BSD 3-Clause License (Skia)",
        "files/legal/LICENSE-Skia.txt",
        "5f787c1dee3c56547f09ccc2906ab5f5293c4d8dd6c8654e573216c38e908dbd",
        f"https://raw.githubusercontent.com/JetBrains/skia/{_SKIA}/LICENSE",
    ),
    "HarfBuzz-Old-MIT": LicenseText(
        "HarfBuzz License (Old MIT)",
        "files/legal/LICENSE-HarfBuzz.txt",
        "ba8f810f2455c2f08e2d56bb49b72f37fcf68f1f4fade38977cfd7372050ad64",
        _googlesource(
            "chromium",
            "external/github.com/harfbuzz/harfbuzz",
            "08b52ae2e44931eef163dbad71697f911fadc323",
            "COPYING",
        ),
    ),
    "ICU": LicenseText(
        "ICU License (Unicode License V3 and third-party notices)",
        "files/legal/LICENSE-ICU.txt",
        "17510cf7a58b4879b887ec05a45d72cf1b73544dd9ec7e72f20110ed104229ee",
        _googlesource(
            "chromium", "chromium/deps/icu", "364118a1d9da24bb5b770ac3d762ac144d6da5a4", "LICENSE"
        ),
    ),
    "libjpeg-turbo": LicenseText(
        "libjpeg-turbo Licenses (IJG, BSD 3-Clause, zlib)",
        "files/legal/LICENSE-libjpeg-turbo.md",
        "96f5b328adbb78eeaaec6980d73fd558cb1e4d62560ed615646bc3cf5e532430",
        _googlesource(*_JPEG, "LICENSE.md"),
    ),
    "IJG": LicenseText(
        "Independent JPEG Group README (IJG License)",
        "files/legal/README-libjpeg-turbo-IJG.txt",
        "75815e3bf6484201a3c3d17a1bbf10f2e8e3237f84df10a2357ea896db2a81d6",
        _googlesource(*_JPEG, "README.ijg"),
    ),
    "libpng-2.0": LicenseText(
        "PNG Reference Library License version 2",
        "files/legal/LICENSE-libpng.txt",
        "7317e078e2d3b5d7ba5a6159e650945153262b44b76f6700f8e9edb261c5143e",
        _googlesource(
            "skia", "third_party/libpng", "ed217e3e601d8e462f7fd1e04bed43ac42212429", "LICENSE"
        ),
    ),
    "libwebp-BSD-3-Clause": LicenseText(
        "BSD 3-Clause License (WebM Project)",
        "files/legal/LICENSE-libwebp.txt",
        "5aec868f669e384a22372a4e8a1a6cd7d44c64cd451f960ca69cc170d1e13acf",
        _googlesource(*_WEBP, "COPYING"),
    ),
    "libwebp-PATENTS": LicenseText(
        "WebM Additional IP Rights Grant (Patents)",
        "files/legal/PATENTS-libwebp.txt",
        "cc3273e0694ea5896145e0677699b53471b03ea43021ddc50e7923fbb9f5023c",
        _googlesource(*_WEBP, "PATENTS"),
    ),
    "Zlib": LicenseText(
        "zlib License",
        "files/legal/LICENSE-zlib.txt",
        "e1cfcc55c325b3f78cf55df9664abaa066e2271dffe8213347d9fccdfbac8f2c",
        _googlesource(
            "chromium",
            "chromium/src/third_party/zlib",
            "646b7f569718921d7d4b5b8e22572ff6c76f2596",
            "LICENSE",
        ),
    ),
    "Chromium-BSD-3-Clause": LicenseText(
        "BSD 3-Clause License (The Chromium Authors)",
        "files/legal/LICENSE-Chromium.txt",
        "368cca1106be99d39ecd32a38d8305585d802a475effb66380b91ffc9bcf709b",
        _googlesource(
            "chromium", "chromium/src", "249765936393e6222039dfbfc05f8ace9e561f32", "LICENSE"
        ),
    ),
    "Expat-MIT": LicenseText(
        "MIT License (Expat)",
        "files/legal/LICENSE-Expat.txt",
        "31b15de82aa19a845156169a17a5488bf597e561b2c318d159ed583139b25e87",
        _googlesource(
            "chromium",
            "external/github.com/libexpat/libexpat",
            "8e49998f003d693213b538ef765814c7d21abada",
            "expat/COPYING",
        ),
    ),
    "DNG-SDK": LicenseText(
        "Adobe DNG SDK License Agreement",
        "files/legal/LICENSE-DNG-SDK.txt",
        "012e875468ac2f9a2070ee25a950588afa2735b4b2d961f491e3d72c8f572306",
        _googlesource(*_DNG, "LICENSE"),
    ),
    "DNG-PATENTS": LicenseText(
        "Adobe DNG Specification Patent License",
        "files/legal/PATENTS-DNG-SDK.txt",
        "bd958835863f42d8479457503a7ed716424d35b6e76ef76db06b72b109696ec4",
        _googlesource(*_DNG, "PATENTS"),
    ),
    "ThreeTen-BSD-3-Clause": LicenseText(
        "BSD 3-Clause License (ThreeTen)",
        "files/legal/LICENSE-ThreeTen.txt",
        "d1bc53b493a3ab387b42717ed5c4b1976a5048996f81154278100bff86d39331",
        "https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.20/license/third_party/"
        "threetenbp_license.txt",
    ),
}


@dataclass(frozen=True)
class Component:
    """A curated component: the modules it claims, its project URL (as the POMs declare
    it) and its licences. Exactly one component must claim every shipped module. The
    first licence is the one every claimed POM must declare; any further ones are notice
    texts the component also requires."""

    name: str
    url: str
    licenses: tuple[str, ...]
    group_prefixes: tuple[str, ...] = ()
    group: str = ""
    artifact_prefix: str = ""

    def claims(self, group: str, artifact: str) -> bool:
        if self.group_prefixes:
            return any(group == p or group.startswith(p + ".") for p in self.group_prefixes)
        return group == self.group and artifact.startswith(self.artifact_prefix)


COMPONENTS: tuple[Component, ...] = (
    Component(
        "AndroidX (Android Jetpack)",
        "https://developer.android.com/jetpack/androidx",
        (APACHE_2,),
        group_prefixes=("androidx",),
    ),
    Component(
        "Compose Multiplatform",
        "https://github.com/JetBrains/compose-jb",
        (APACHE_2,),
        group_prefixes=("org.jetbrains.compose", "org.jetbrains.androidx"),
    ),
    Component(
        "Kotlin Standard Library",
        "https://kotlinlang.org/",
        (APACHE_2,),
        group="org.jetbrains.kotlin",
        artifact_prefix="kotlin-stdlib",
    ),
    Component(
        "kotlinx.coroutines",
        "https://github.com/Kotlin/kotlinx.coroutines",
        (APACHE_2,),
        group="org.jetbrains.kotlinx",
        artifact_prefix="kotlinx-coroutines-",
    ),
    Component(
        "kotlinx.serialization",
        "https://github.com/Kotlin/kotlinx.serialization",
        (APACHE_2,),
        group="org.jetbrains.kotlinx",
        artifact_prefix="kotlinx-serialization-",
    ),
    Component(
        "kotlinx.atomicfu",
        "https://github.com/Kotlin/kotlinx.atomicfu",
        (APACHE_2,),
        group="org.jetbrains.kotlinx",
        artifact_prefix="atomicfu",
    ),
    Component(
        "Skiko",
        "https://www.github.com/JetBrains/skiko",
        (APACHE_2, "Skiko-NOTICE"),
        group="org.jetbrains.skiko",
        artifact_prefix="skiko",
    ),
    Component(
        "JetBrains Java Annotations",
        "https://github.com/JetBrains/java-annotations",
        (APACHE_2,),
        group="org.jetbrains",
        artifact_prefix="annotations",
    ),
    Component(
        "JSpecify",
        "http://jspecify.org/",
        (APACHE_2,),
        group="org.jspecify",
        artifact_prefix="jspecify",
    ),
    Component(
        "Guava ListenableFuture",
        "https://github.com/google/guava",
        (APACHE_2,),
        group="com.google.guava",
        artifact_prefix="listenablefuture",
    ),
)


@dataclass(frozen=True)
class LinkedComponent:
    """Third-party code statically linked into a module's binary; not a Gradle module.

    ``families`` are the object-file families (member name up to its first ``.``) it
    contributes to the module's static archives. ``attribution`` is a statement its
    licence requires in the product documentation, verbatim."""

    name: str
    url: str
    licenses: tuple[str, ...]
    version: str
    families: tuple[str, ...] = ()
    attribution: str | None = None


@dataclass(frozen=True)
class NativeInventory:
    """The established native content of one exact module version: archives of the
    module's own code, and the third-party components linked into it."""

    own_archives: frozenset[str] = frozenset()
    components: tuple[LinkedComponent, ...] = ()


SKIA_FAMILIES = (
    "libskia",
    "gpu",
    "gpu_shared",
    "pdf",
    "xml",
    "fontmgr_mac_ct",
    "jpeg_decode",
    "jpeg_encode",
    "png_decode_libpng",
    "png_encode",
    "raw",
    "webp_decode",
    "webp_encode",
    "wuffs",
    "libskcms",
    "libskottie",
    "libskresources",
    "libsksg",
    "libskshaper",
    "libskparagraph",
    "libskunicode_core",
    "libskunicode_icu",
    "libsvg",
    "libjsonreader",
)

# Established for skiko 0.9.37.4: Skia m138 (skia-pack m138-80d088a-1), with the
# third-party revisions JetBrains/skia's DEPS pins at 80d088a and the versions the
# archives' own strings report (rc1-defects.md, TF-RC2B-IOS-NOTICES).
SKIKO_0_9_37_4 = NativeInventory(
    own_archives=frozenset({"skiko-native-bridges-ios-arm64.a"}),
    components=(
        LinkedComponent(
            "Skia",
            "https://skia.org/",
            ("Skia-BSD-3-Clause",),
            "m138 (JetBrains/skia 80d088a)",
            SKIA_FAMILIES,
        ),
        LinkedComponent(
            "HarfBuzz",
            "https://harfbuzz.github.io/",
            ("HarfBuzz-Old-MIT",),
            "11.2.1",
            ("libharfbuzz",),
        ),
        LinkedComponent(
            "ICU (International Components for Unicode)",
            "https://icu.unicode.org/",
            ("ICU",),
            "74.2",
            ("libicu",),
        ),
        LinkedComponent(
            "libjpeg-turbo",
            "https://libjpeg-turbo.org/",
            ("libjpeg-turbo", "IJG"),
            "3.1.0",
            ("libjpeg", "libjpeg12", "libjpeg16"),
            attribution="This software is based in part on the work of the Independent JPEG Group.",
        ),
        LinkedComponent(
            "libpng",
            "http://www.libpng.org/pub/png/libpng.html",
            ("libpng-2.0",),
            "1.6.43",
            ("libpng",),
        ),
        LinkedComponent(
            "libwebp",
            "https://developers.google.com/speed/webp",
            ("libwebp-BSD-3-Clause", "libwebp-PATENTS"),
            "1.4.0",
            ("libwebp", "libwebp_sse41"),
        ),
        LinkedComponent(
            "Wuffs",
            "https://github.com/google/wuffs",
            (APACHE_2,),
            "0.3",
            ("libwuffs",),
        ),
        LinkedComponent(
            "zlib (Chromium)",
            "https://chromium.googlesource.com/chromium/src/third_party/zlib",
            ("Zlib", "Chromium-BSD-3-Clause"),
            "1.3.0.1",
            ("libzlib", "zlib_adler32_simd", "zlib_arm_crc32", "zlib_inflate_chunk_simd"),
        ),
        LinkedComponent(
            "Expat",
            "https://libexpat.github.io/",
            ("Expat-MIT",),
            "2.7.1",
            ("libexpat",),
        ),
        LinkedComponent(
            "Adobe DNG SDK",
            "https://helpx.adobe.com/camera-raw/digital-negative.html",
            ("DNG-SDK", "DNG-PATENTS"),
            "1.4",
            ("libdng_sdk",),
            attribution="This product includes DNG technology under license by Adobe Systems "
            "Incorporated.",
        ),
        LinkedComponent(
            "Piex",
            "https://github.com/google/piex",
            (APACHE_2,),
            "bb217ac",
            ("libpiex",),
        ),
    ),
)

# Kotlin/Native compiles the standard library into the framework. JetBrains'
# license/README.md lists third-party code in it — kotlin.time (ThreeTen, BSD-3-Clause),
# collections (GWT, Apache-2.0), unsigned conversions (Guava, Apache-2.0) — and all three
# are in the release framework (kotlin.time.Duration, kotlin.collections.HashMap,
# kotlin#ulongToString).
KOTLIN_STDLIB_2_4_20 = NativeInventory(
    components=(
        LinkedComponent(
            "Kotlin Standard Library: third-party code (ThreeTen, GWT, Guava)",
            "https://github.com/JetBrains/kotlin/blob/v2.4.20/license/README.md",
            ("ThreeTen-BSD-3-Clause", APACHE_2),
            "2.4.20",
        ),
    ),
)

NATIVE_INVENTORIES: dict[str, NativeInventory] = {
    "org.jetbrains.skiko:skiko-iosarm64:0.9.37.4": SKIKO_0_9_37_4,
    "org.jetbrains.kotlin:kotlin-stdlib:2.4.20": KOTLIN_STDLIB_2_4_20,
    # Compose's UIKit helpers (CMPViewController and others): Compose Multiplatform's code
    "org.jetbrains.compose.ui:ui-uikit-uikitarm64:1.10.3": NativeInventory(
        own_archives=frozenset({"libCMPUIKitUtils.a"})
    ),
}
# Modules whose native content is known to exist: each needs an inventory for its exact
# resolved version, even when its klib carries no archive (the stdlib's third-party code
# is compiled Kotlin, not an archive).
NATIVE_MODULES = frozenset(
    {
        "org.jetbrains.skiko:skiko-iosarm64",
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains.compose.ui:ui-uikit-uikitarm64",
    }
)
KLIB_TARGET = "ios_arm64"
# Compiler-generated cinterop stubs of the module's own definitions
KLIB_NATIVE_OWN = frozenset({"cstubs.bc"})


@dataclass(frozen=True)
class Platform:
    name: str
    configuration: str
    notices: Path
    native: bool


PLATFORMS = {
    "android": Platform(
        "android",
        "releaseRuntimeClasspath",
        RESOURCES / "files/legal/oss-notices-android.json",
        native=False,
    ),
    "ios": Platform(
        "ios",
        "iosArm64CompileKlibraries",
        RESOURCES / "files/legal/oss-notices-ios.json",
        native=True,
    ),
}

INIT_SCRIPT = """
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

allprojects {
    if (path == ':composeApp') {
        tasks.register('talentforgeResolvedModules') {
            def configuration = configurations.named(
                providers.gradleProperty('talentforgeConfiguration').get())
            def withFiles = providers.gradleProperty('talentforgeFiles').get() == 'true'
            doLast {
                def resolved = configuration.get().incoming
                resolved.resolutionResult.allComponents.each { component ->
                    def id = component.id
                    if (id instanceof ModuleComponentIdentifier) {
                        println "TF-MODULE ${id.group}:${id.module}:${id.version}"
                    }
                }
                if (withFiles) {
                    resolved.artifacts.artifacts.each { artifact ->
                        def id = artifact.id.componentIdentifier
                        if (id instanceof ModuleComponentIdentifier) {
                            def coordinate = "${id.group}:${id.module}:${id.version}"
                            println "TF-FILE ${coordinate} ${artifact.file}"
                        }
                    }
                }
            }
        }
    }
}
"""


class NoticesError(Exception):
    """The notices cannot be derived, or do not match the build."""


@dataclass(frozen=True)
class Resolution:
    """The external modules of a configuration and, for a native platform, their files."""

    modules: list[str]
    files: dict[str, list[Path]]


def resolve(platform: Platform) -> Resolution:
    with tempfile.TemporaryDirectory() as directory:
        init = Path(directory) / "talentforge-modules.gradle"
        init.write_text(INIT_SCRIPT, encoding="utf-8")
        result = subprocess.run(
            [
                "./gradlew",
                "--init-script",
                str(init),
                ":composeApp:talentforgeResolvedModules",
                f"-PtalentforgeConfiguration={platform.configuration}",
                f"-PtalentforgeFiles={'true' if platform.native else 'false'}",
                "--console=plain",
                "-q",
            ],
            cwd=MOBILE,
            capture_output=True,
            text=True,
            check=False,
        )
    if result.returncode != 0:
        raise NoticesError(f"Gradle could not resolve {platform.configuration}:\n{result.stderr}")
    return parse_resolution(result.stdout, platform.configuration)


def parse_resolution(stdout: str, configuration: str) -> Resolution:
    modules: set[str] = set()
    files: dict[str, list[Path]] = {}
    for line in stdout.splitlines():
        if line.startswith("TF-MODULE "):
            modules.add(line.removeprefix("TF-MODULE ").strip())
        elif line.startswith("TF-FILE "):
            module, path = line.removeprefix("TF-FILE ").split(" ", 1)
            files.setdefault(module, []).append(Path(path.strip()))
    if not modules:
        raise NoticesError(f"Gradle reported no modules for {configuration}")
    return Resolution(
        modules=sorted(m for m in modules if not m.split(":")[1].endswith("-bom")),
        files=files,
    )


def component_of(module: str) -> Component:
    group, artifact, _ = module.split(":")
    claiming = [c for c in COMPONENTS if c.claims(group, artifact)]
    if len(claiming) != 1:
        raise NoticesError(
            f"{module}: claimed by {len(claiming)} components; add or fix a curated component"
        )
    return claiming[0]


def _pom_text(group: str, artifact: str, version: str) -> str:
    cached = sorted(GRADLE_CACHE.glob(f"{group}/{artifact}/{version}/*/{artifact}-{version}.pom"))
    if cached:
        return cached[0].read_text(encoding="utf-8")
    path = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
    for repository in REPOSITORIES:
        try:
            with urllib.request.urlopen(f"{repository}/{path}", timeout=30) as response:
                return str(response.read().decode("utf-8"))
        except OSError:
            continue
    raise NoticesError(f"{group}:{artifact}:{version}: POM not found in the cache or repositories")


def declared_licenses(group: str, artifact: str, version: str, depth: int = 0) -> list[str]:
    """The POM's <license><name> values, inherited from the parent POM when absent."""
    text = re.sub(r'\sxmlns="[^"]+"', "", _pom_text(group, artifact, version), count=1)
    pom = ET.fromstring(text)
    names = [(n.text or "").strip() for n in pom.findall("./licenses/license/name")]
    if names:
        return names
    parent = pom.find("parent")
    if parent is not None and depth < 5:
        return declared_licenses(
            parent.findtext("groupId", ""),
            parent.findtext("artifactId", ""),
            parent.findtext("version", ""),
            depth + 1,
        )
    return []


def verify_license(module: str, component: Component) -> None:
    group, artifact, version = module.split(":")
    names = declared_licenses(group, artifact, version)
    if component.licenses[0] != APACHE_2 or not names or not set(names) <= APACHE_2_NAMES:
        raise NoticesError(
            f"{module}: POM declares {names or 'no licence'}, curated licence of "
            f"{component.name!r} is {component.licenses[0]}"
        )


# --- static archives (Mach-O universal file / BSD ar) --------------------------------------

FAT_MAGIC = 0xCAFEBABE
CPU_TYPE_ARM64 = 0x0100000C
CPU_SUBTYPE_MASK = 0x00FFFFFF
AR_MAGIC = b"!<arch>\n"


def arm64_slice(data: bytes) -> bytes:
    """The arm64 (not arm64e) slice of a universal file, or the file itself when thin."""
    if len(data) < 8 or struct.unpack(">I", data[:4])[0] != FAT_MAGIC:
        return data
    (count,) = struct.unpack(">I", data[4:8])
    for index in range(count):
        cpu, subtype, offset, size, _ = struct.unpack(">5I", data[8 + 20 * index : 28 + 20 * index])
        if cpu == CPU_TYPE_ARM64 and subtype & CPU_SUBTYPE_MASK == 0:
            return data[offset : offset + size]
    raise NoticesError("universal file has no arm64 slice")


def archive_members(data: bytes) -> list[str]:
    """The member names of a BSD ``ar`` static archive (the arm64 slice of a universal one)."""
    archive = arm64_slice(data)
    if not archive.startswith(AR_MAGIC):
        raise NoticesError("not an ar archive")
    members: list[str] = []
    position = len(AR_MAGIC)
    while position < len(archive):
        header = archive[position : position + 60]
        if len(header) < 60 or header[58:60] != b"`\n":
            raise NoticesError("malformed ar member header")
        raw_name = header[:16].decode("ascii").strip()
        size = int(header[48:58].decode("ascii").strip())
        body = position + 60
        if raw_name.startswith("#1/"):
            name = archive[body : body + int(raw_name[3:])].rstrip(b"\0").decode("utf-8")
        else:
            name = raw_name.rstrip("/")
        if not name.startswith("__.SYMDEF"):
            members.append(name)
        position = body + size + (body + size) % 2
    return members


def families(members: Iterable[str]) -> set[str]:
    """Object-file families: each member's name up to its first ``.``."""
    return {member.split(".", 1)[0] for member in members}


_KLIB_ENTRY = re.compile(rf"^[^/]+/targets/{KLIB_TARGET}/(included|native)/([^/]+)$")


def klib_native_content(klib: Path) -> dict[str, set[str]]:
    """``{archive: object families}`` of a klib's included static archives. Any other
    native entry than the compiler's cinterop stubs fails."""
    content: dict[str, set[str]] = {}
    with zipfile.ZipFile(klib) as archive:
        for entry in archive.namelist():
            match = _KLIB_ENTRY.match(entry)
            if match is None:
                continue
            kind, name = match.groups()
            if kind == "native":
                if name not in KLIB_NATIVE_OWN:
                    raise NoticesError(f"{klib.name}: unexpected native entry {entry}")
                continue
            content[name] = families(archive_members(archive.read(entry)))
    return content


def verify_native(module: str, content: dict[str, set[str]]) -> None:
    """The module's archives are its own code or fully claimed by its exact inventory,
    and the inventory claims nothing the archives do not contain."""
    group, artifact, _ = module.split(":")
    if not content and f"{group}:{artifact}" not in NATIVE_MODULES:
        return
    inventory = NATIVE_INVENTORIES.get(module)
    if inventory is None:
        raise NoticesError(
            f"{module}: has native content (archives {sorted(content)}) but no established "
            "inventory for this version; establish its linked components and licences"
        )
    claimed: dict[str, str] = {}
    for linked in inventory.components:
        for family in linked.families:
            if family in claimed:
                raise NoticesError(f"{module}: object family {family} claimed twice")
            claimed[family] = linked.name
    seen: set[str] = set()
    for name, archive_families in sorted(content.items()):
        if name in inventory.own_archives:
            continue
        unclaimed = sorted(archive_families - claimed.keys())
        if unclaimed:
            raise NoticesError(f"{module}: {name} has unclaimed object families {unclaimed}")
        seen |= archive_families
    absent_archives = sorted(inventory.own_archives - content.keys())
    absent_families = sorted(claimed.keys() - seen)
    if absent_archives or absent_families:
        raise NoticesError(
            f"{module}: the native inventory drifts from the klib; absent archives "
            f"{absent_archives}, absent object families {absent_families}"
        )


# --- licence texts -------------------------------------------------------------------------


def _download(origin: str) -> bytes:
    with urllib.request.urlopen(origin, timeout=60) as response:
        data = bytes(response.read())
    return base64.b64decode(data) if origin.endswith("?format=TEXT") else data


def verify_text(license_id: str, fetch: bool) -> None:
    """The bundled text is the pinned upstream file, byte for byte."""
    text = LICENSES[license_id]
    path = RESOURCES / text.file
    if not path.is_file():
        if not fetch:
            raise NoticesError(f"{text.file} is missing; regenerate the notices")
        path.write_bytes(_download(text.origin))
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != text.sha256:
        raise NoticesError(
            f"{text.file}: SHA-256 {digest} is not the curated {text.sha256} of {text.origin}"
        )


# --- documents -----------------------------------------------------------------------------


def native_content(resolution: Resolution) -> dict[str, dict[str, set[str]]]:
    content: dict[str, dict[str, set[str]]] = {}
    for module in resolution.modules:
        archives: dict[str, set[str]] = {}
        for file in resolution.files.get(module, []):
            if file.suffix == ".klib":
                archives.update(klib_native_content(file))
        content[module] = archives
    return content


def build_notices(
    platform: Platform,
    modules: list[str],
    native: dict[str, dict[str, set[str]]] | None,
    online: bool,
) -> dict[str, object]:
    """The notices document. ``native`` is the klibs' archive content (iOS) or ``None``."""
    grouped: dict[str, list[str]] = {}
    for module in modules:
        component = component_of(module)
        if online:
            verify_license(module, component)
        grouped.setdefault(component.name, []).append(module)
    components: list[dict[str, object]] = [
        {"name": c.name, "url": c.url, "licenses": list(c.licenses), "modules": grouped[c.name]}
        for c in COMPONENTS
        if c.name in grouped
    ]
    if native is not None:
        for module in modules:
            verify_native(module, native.get(module, {}))
            inventory = NATIVE_INVENTORIES.get(module)
            components += [
                {
                    "name": linked.name,
                    "url": linked.url,
                    "licenses": list(linked.licenses),
                    "version": linked.version,
                    "linkedInto": module,
                    "attribution": linked.attribution,
                }
                for linked in (inventory.components if inventory else ())
            ]
    used = [
        license_id
        for license_id in LICENSES
        if any(license_id in component["licenses"] for component in components)  # type: ignore[operator]
    ]
    for license_id in used:
        verify_text(license_id, fetch=online)
    return {
        "schema": SCHEMA,
        "platform": platform.name,
        "source": f":composeApp:{platform.configuration}",
        "licenses": {
            license_id: {"name": LICENSES[license_id].name, "file": LICENSES[license_id].file}
            for license_id in used
        },
        "components": components,
    }


def render(notices: dict[str, object]) -> str:
    return json.dumps(notices, indent=2, ensure_ascii=False) + "\n"


def listed_modules(notices: dict[str, object]) -> list[str]:
    components: list[dict[str, list[str]]] = notices.get("components", [])  # type: ignore[assignment]
    return sorted(m for component in components for m in component.get("modules", []))


def check(platform: Platform, notices: dict[str, object]) -> None:
    if not platform.notices.is_file():
        raise NoticesError(f"{platform.notices.relative_to(ROOT)} is missing; regenerate it")
    committed = platform.notices.read_text(encoding="utf-8")
    if committed != render(notices):
        listed, built = set(listed_modules(json.loads(committed))), set(listed_modules(notices))
        raise NoticesError(
            f"the {platform.name} OSS notices drift from :composeApp:{platform.configuration}; "
            "regenerate them\n"
            f"  not listed: {sorted(built - listed)}\n"
            f"  no longer shipped: {sorted(listed - built)}"
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate or check the OSS notices.")
    parser.add_argument("--check", action="store_true", help="fail when the notices drift")
    parser.add_argument("--platform", choices=("android", "ios", "all"), default="all")
    args = parser.parse_args(argv)
    names = ("android", "ios") if args.platform == "all" else (args.platform,)
    try:
        for name in names:
            platform = PLATFORMS[name]
            resolution = resolve(platform)
            native = native_content(resolution) if platform.native else None
            notices = build_notices(platform, resolution.modules, native, online=not args.check)
            if args.check:
                check(platform, notices)
                print(f"OSS notices ({name}): OK ({len(resolution.modules)} modules)")
            else:
                platform.notices.write_text(render(notices), encoding="utf-8")
                print(
                    f"wrote {platform.notices.relative_to(ROOT)} "
                    f"({len(resolution.modules)} modules)"
                )
    except NoticesError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
