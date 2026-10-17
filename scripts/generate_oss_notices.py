#!/usr/bin/env python3
"""Open-source notices of the Android release APK (M5.RC.2b, LR-4).

The About screen lists every third-party module the Android release APK ships, grouped
into components, with each component's licence. This script derives that list from
Gradle — the resolved ``:composeApp:releaseRuntimeClasspath`` — never by hand:

    python3 scripts/generate_oss_notices.py          # regenerate the committed notices
    python3 scripts/generate_oss_notices.py --check  # fail when they drift from Gradle

Regeneration reads every module's POM (``<licenses>``, following ``<parent>``) from the
Gradle cache or, when Gradle did not download it, from Google Maven / Maven Central, and
fails unless each module's declared licence is the curated licence of its component.
A module whose group no component claims also fails: a new dependency is never listed
silently or under a guessed licence. ``--check`` compares only the module coordinates
(no network); the licence check happens whenever the file is regenerated.

Bills of materials (``*-bom``) are version constraints with no code and are not listed.
``:shared`` is TalentForge's own module. The iOS framework ships a different set (it
statically links Skiko and the Skia graphics library); its notices are NOT produced
here — see the release checklist (TF-RC2B-IOS-NOTICES).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MOBILE = ROOT / "mobile"
NOTICES = MOBILE / "composeApp/src/commonMain/composeResources/files/legal/oss-notices-android.json"
SCHEMA = "talentforge.oss-notices.v1"
CONFIGURATION = "releaseRuntimeClasspath"
GRADLE_CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / (
    "caches/modules-2/files-2.1"
)
REPOSITORIES = ("https://dl.google.com/dl/android/maven2", "https://repo1.maven.org/maven2")

APACHE_2 = "Apache-2.0"
LICENSES = {APACHE_2: {"name": "Apache License 2.0", "file": "files/legal/LICENSE-Apache-2.0.txt"}}
# POM <license><name> spellings that mean the Apache License 2.0
APACHE_2_NAMES = frozenset(
    {
        "The Apache Software License, Version 2.0",
        "The Apache License, Version 2.0",
        "Apache License, Version 2.0",
        "Apache-2.0",
    }
)


@dataclass(frozen=True)
class Component:
    """A curated component: the modules it claims, its project URL (as the POMs declare
    it) and its licence. Exactly one component must claim every shipped module."""

    name: str
    url: str
    license: str
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
        APACHE_2,
        group_prefixes=("androidx",),
    ),
    Component(
        "Compose Multiplatform",
        "https://github.com/JetBrains/compose-jb",
        APACHE_2,
        group_prefixes=("org.jetbrains.compose", "org.jetbrains.androidx"),
    ),
    Component(
        "Kotlin Standard Library",
        "https://kotlinlang.org/",
        APACHE_2,
        group="org.jetbrains.kotlin",
        artifact_prefix="kotlin-stdlib",
    ),
    Component(
        "kotlinx.coroutines",
        "https://github.com/Kotlin/kotlinx.coroutines",
        APACHE_2,
        group="org.jetbrains.kotlinx",
        artifact_prefix="kotlinx-coroutines-",
    ),
    Component(
        "kotlinx.serialization",
        "https://github.com/Kotlin/kotlinx.serialization",
        APACHE_2,
        group="org.jetbrains.kotlinx",
        artifact_prefix="kotlinx-serialization-",
    ),
    Component(
        "JetBrains Java Annotations",
        "https://github.com/JetBrains/java-annotations",
        APACHE_2,
        group="org.jetbrains",
        artifact_prefix="annotations",
    ),
    Component(
        "JSpecify",
        "http://jspecify.org/",
        APACHE_2,
        group="org.jspecify",
        artifact_prefix="jspecify",
    ),
    Component(
        "Guava ListenableFuture",
        "https://github.com/google/guava",
        APACHE_2,
        group="com.google.guava",
        artifact_prefix="listenablefuture",
    ),
)

INIT_SCRIPT = """
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

allprojects {
    if (path == ':composeApp') {
        tasks.register('talentforgeReleaseModules') {
            def configuration = configurations.named('CONFIGURATION')
            doLast {
                configuration.get().incoming.resolutionResult.allComponents.each { component ->
                    def id = component.id
                    if (id instanceof ModuleComponentIdentifier) {
                        println "TF-MODULE ${id.group}:${id.module}:${id.version}"
                    }
                }
            }
        }
    }
}
""".replace("CONFIGURATION", CONFIGURATION)


class NoticesError(Exception):
    """The notices cannot be derived, or do not match the build."""


def resolved_modules() -> list[str]:
    """The external modules of :composeApp's release runtime classpath, from Gradle."""
    with tempfile.TemporaryDirectory() as directory:
        init = Path(directory) / "talentforge-modules.gradle"
        init.write_text(INIT_SCRIPT, encoding="utf-8")
        result = subprocess.run(
            [
                "./gradlew",
                "--init-script",
                str(init),
                ":composeApp:talentforgeReleaseModules",
                "--console=plain",
                "-q",
            ],
            cwd=MOBILE,
            capture_output=True,
            text=True,
            check=False,
        )
    if result.returncode != 0:
        raise NoticesError(
            "Gradle could not resolve the release runtime classpath:\n" + result.stderr
        )
    modules = {
        line.removeprefix("TF-MODULE ").strip()
        for line in result.stdout.splitlines()
        if line.startswith("TF-MODULE ")
    }
    if not modules:
        raise NoticesError("Gradle reported no release runtime modules")
    return sorted(m for m in modules if not m.split(":")[1].endswith("-bom"))


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
    if component.license != APACHE_2 or not names or not set(names) <= APACHE_2_NAMES:
        raise NoticesError(
            f"{module}: POM declares {names or 'no licence'}, curated licence of "
            f"{component.name!r} is {component.license}"
        )


def build_notices(modules: list[str]) -> dict[str, object]:
    grouped: dict[str, list[str]] = {}
    for module in modules:
        component = component_of(module)
        verify_license(module, component)
        grouped.setdefault(component.name, []).append(module)
    return {
        "schema": SCHEMA,
        "platform": "android",
        "source": f":composeApp:{CONFIGURATION}",
        "licenses": LICENSES,
        "components": [
            {"name": c.name, "url": c.url, "license": c.license, "modules": grouped[c.name]}
            for c in COMPONENTS
            if c.name in grouped
        ],
    }


def committed_modules() -> list[str]:
    data = json.loads(NOTICES.read_text(encoding="utf-8"))
    return sorted(m for component in data["components"] for m in component["modules"])


def check(modules: list[str]) -> None:
    if not NOTICES.is_file():
        raise NoticesError(f"{NOTICES.relative_to(ROOT)} is missing; regenerate it")
    listed = committed_modules()
    missing, stale = sorted(set(modules) - set(listed)), sorted(set(listed) - set(modules))
    if missing or stale:
        raise NoticesError(
            "the OSS notices drift from the release runtime classpath; regenerate them\n"
            f"  not listed: {missing}\n  no longer shipped: {stale}"
        )


def write(modules: list[str]) -> None:
    notices = build_notices(modules)
    NOTICES.parent.mkdir(parents=True, exist_ok=True)
    NOTICES.write_text(json.dumps(notices, indent=2) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate or check the Android OSS notices.")
    parser.add_argument("--check", action="store_true", help="fail when the notices drift")
    args = parser.parse_args(argv)
    try:
        modules = resolved_modules()
        if args.check:
            check(modules)
        else:
            write(modules)
    except NoticesError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    if args.check:
        print(f"OSS notices: OK ({len(modules)} modules)")
    else:
        print(f"wrote {NOTICES.relative_to(ROOT)} ({len(modules)} modules)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
