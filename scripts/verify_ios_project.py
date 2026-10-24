#!/usr/bin/env python3
"""Check the committed iOS project before a TestFlight / App Store archive (M5.IOS.3).

``verify_ios_archive.py`` checks what a build produced; this script checks the sources that
build reads, so a wrong setting is caught before the owner signs and uploads anything. It reads
``mobile/iosApp/iosApp.xcodeproj/project.pbxproj`` (an OpenStep property list), the app's
``Info.plist`` and the shared scheme. Every check must pass:

- the ``iosApp`` target's Debug and Release configurations agree on the frozen bundle id
  ``io.github.alexeykravtsov95.talentforge``, the marketing version and the build number (equal
  to ``--expected-version`` / ``--expected-build`` when given; the build number is a positive
  integer) and the display name ``TalentForge``;
- iOS only: ``SDKROOT = iphoneos``, iPhone and iPad (``TARGETED_DEVICE_FAMILY = 1,2``), no Mac
  Catalyst, and the deployment target 17.2 in every configuration that sets one;
- signing is left to the owner: automatic signing and no team, provisioning profile or signing
  identity committed (``DEVELOPMENT_TEAM``, ``PROVISIONING_PROFILE*``, ``CODE_SIGN_IDENTITY``);
- the scheme archives the Release configuration;
- ``Info.plist`` declares export compliance (``ITSAppUsesNonExemptEncryption`` = false), keeps
  App Transport Security at Apple's defaults, requests no permission (no
  ``NS…UsageDescription``) and no background mode, keeps one scene, declares ``en`` and ``ru``,
  and on iPad supports all four orientations (required for iPad multitasking unless the app
  opts out with ``UIRequiresFullScreen``);
- ``PrivacyInfo.xcprivacy`` is copied by the target's Resources build phase.

Usage::

    python3 scripts/verify_ios_project.py --expected-version 1.0.0 --expected-build 1

Exit status: 0 = all checks pass, 1 = a check failed, 2 = the check could not run.
"""

from __future__ import annotations

import argparse
import json
import plistlib
import re
import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PROJECT = ROOT / "mobile/iosApp/iosApp.xcodeproj"
REPORT_SCHEMA = "talentforge.ios-project-report.v1"
TARGET = "iosApp"
FROZEN_BUNDLE_ID = "io.github.alexeykravtsov95.talentforge"
DISPLAY_NAME = "TalentForge"
DEPLOYMENT_TARGET = "17.2"
CONFIGURATIONS = ("Debug", "Release")
# Committing any of these would pin the build to one person's team, profile or certificate.
SIGNING_OVERRIDES = re.compile(
    r"^(DEVELOPMENT_TEAM|PROVISIONING_PROFILE(_SPECIFIER)?|CODE_SIGN_IDENTITY)(\[.*\])?$"
)
IPAD_ORIENTATIONS = frozenset(
    {
        "UIInterfaceOrientationPortrait",
        "UIInterfaceOrientationPortraitUpsideDown",
        "UIInterfaceOrientationLandscapeLeft",
        "UIInterfaceOrientationLandscapeRight",
    }
)
REQUIRED_LOCALIZATIONS = frozenset({"en", "ru"})

EXIT_PASS, EXIT_FAIL, EXIT_ERROR = 0, 1, 2

PlistValue = str | list["PlistValue"] | dict[str, "PlistValue"]


class ToolError(Exception):
    """The project could not be read or parsed."""


@dataclass(frozen=True)
class Check:
    id: str
    passed: bool
    detail: str


@dataclass
class Evaluation:
    checks: list[Check] = field(default_factory=list)

    def add(self, check_id: str, passed: bool, detail: str) -> None:
        self.checks.append(Check(check_id, passed, detail))

    @property
    def passed(self) -> bool:
        return bool(self.checks) and all(check.passed for check in self.checks)


# ----------------------------------------------------------------------------- OpenStep plist

_TOKEN = re.compile(
    r"""
    (?P<space>\s+|//[^\n]*|/\*.*?\*/)
    |(?P<quoted>"(?:[^"\\]|\\.)*")
    |(?P<bare>[A-Za-z0-9_$+/:.\-]+)
    |(?P<punct>[{}();=,])
    """,
    re.VERBOSE | re.DOTALL,
)
_ESCAPES = {"n": "\n", "t": "\t", '"': '"', "\\": "\\"}
# A string token is kept with this prefix so that it is never mistaken for punctuation.
_STRING = "\0"


def _tokens(text: str) -> list[str]:
    tokens: list[str] = []
    position = 0
    while position < len(text):
        match = _TOKEN.match(text, position)
        if match is None:
            raise ToolError(f"project.pbxproj: unexpected text at offset {position}")
        position = match.end()
        if match.lastgroup == "quoted":
            body = re.sub(r"\\(.)", lambda m: _ESCAPES.get(m[1], m[1]), match.group()[1:-1])
            tokens.append(_STRING + body)
        elif match.lastgroup == "bare":
            tokens.append(_STRING + match.group())
        elif match.lastgroup == "punct":
            tokens.append(match.group())
    return tokens


def parse_openstep(text: str) -> dict[str, PlistValue]:
    """Parse an OpenStep property list (the format of ``project.pbxproj``)."""
    tokens = _tokens(text)
    position = 0

    def value() -> PlistValue:
        nonlocal position
        token = tokens[position]
        position += 1
        if token.startswith(_STRING):
            return token[1:]
        if token == "{":
            result: dict[str, PlistValue] = {}
            while tokens[position] != "}":
                key = value()
                if not isinstance(key, str) or tokens[position] != "=":
                    raise ToolError("project.pbxproj: malformed dictionary")
                position += 1
                result[key] = value()
                if tokens[position] != ";":
                    raise ToolError(f"project.pbxproj: expected ';' after {key!r}")
                position += 1
            position += 1
            return result
        if token == "(":
            items: list[PlistValue] = []
            while tokens[position] != ")":
                items.append(value())
                if tokens[position] == ",":
                    position += 1
            position += 1
            return items
        raise ToolError(f"project.pbxproj: unexpected {token!r}")

    try:
        root = value()
    except IndexError as error:
        raise ToolError("project.pbxproj: unexpected end of file") from error
    if not isinstance(root, dict) or position != len(tokens):
        raise ToolError("project.pbxproj: the root is not one dictionary")
    return root


# ----------------------------------------------------------------------------- the checks


def _dict(value: PlistValue | None) -> dict[str, PlistValue]:
    return value if isinstance(value, dict) else {}


def _list(value: PlistValue | None) -> list[PlistValue]:
    return value if isinstance(value, list) else []


def _configurations(
    objects: dict[str, PlistValue], owner: dict[str, PlistValue]
) -> dict[str, dict[str, PlistValue]]:
    """The build settings of the configurations of a target or project, by name."""
    configuration_list = _dict(objects.get(str(owner.get("buildConfigurationList"))))
    configurations = {}
    for identifier in _list(configuration_list.get("buildConfigurations")):
        configuration = _dict(objects.get(str(identifier)))
        configurations[str(configuration.get("name"))] = _dict(configuration.get("buildSettings"))
    return configurations


def evaluate(
    project: Path, *, expected_version: str | None, expected_build: str | None
) -> tuple[Evaluation, dict[str, Any]]:
    """Run every check on ``project`` (the ``.xcodeproj``); raises ToolError when unreadable."""
    try:
        pbxproj = parse_openstep((project / "project.pbxproj").read_text(encoding="utf-8"))
    except OSError as error:
        raise ToolError(f"cannot read project.pbxproj: {error}") from error
    objects = _dict(pbxproj.get("objects"))
    by_isa: dict[str, list[dict[str, PlistValue]]] = {}
    for obj in objects.values():
        by_isa.setdefault(str(_dict(obj).get("isa")), []).append(_dict(obj))
    targets = [t for t in by_isa.get("PBXNativeTarget", []) if t.get("name") == TARGET]
    projects = by_isa.get("PBXProject", [])
    if len(targets) != 1 or len(projects) != 1:
        raise ToolError(f"expected one project and one native target named {TARGET!r}")
    target = targets[0]
    configurations = _configurations(objects, target)
    evaluation = Evaluation()
    evaluation.add(
        "configurations",
        sorted(configurations) == sorted(CONFIGURATIONS),
        f"target configurations {sorted(configurations)} (expected {list(CONFIGURATIONS)})",
    )
    settings = {name: configurations.get(name, {}) for name in CONFIGURATIONS}

    def agreed(key: str) -> PlistValue | None:
        """The value of ``key`` when Debug and Release set it equally, else None."""
        values = {json.dumps(settings[name].get(key)) for name in CONFIGURATIONS}
        return settings["Release"].get(key) if len(values) == 1 else None

    bundle_id = agreed("PRODUCT_BUNDLE_IDENTIFIER")
    evaluation.add(
        "bundle-id",
        bundle_id == FROZEN_BUNDLE_ID,
        f"PRODUCT_BUNDLE_IDENTIFIER={bundle_id!r} in Debug and Release "
        f"(expected {FROZEN_BUNDLE_ID})",
    )
    version, build = agreed("MARKETING_VERSION"), agreed("CURRENT_PROJECT_VERSION")
    evaluation.add(
        "version",
        isinstance(version, str)
        and re.fullmatch(r"\d+\.\d+\.\d+", version) is not None
        and (expected_version is None or version == expected_version)
        and isinstance(build, str)
        and build.isdigit()
        and int(build) > 0
        and (expected_build is None or build == expected_build),
        f"MARKETING_VERSION={version!r} (expected {expected_version or 'x.y.z'}), "
        f"CURRENT_PROJECT_VERSION={build!r} (expected {expected_build or 'a positive integer'}), "
        "equal in Debug and Release",
    )
    display_name = agreed("INFOPLIST_KEY_CFBundleDisplayName")
    evaluation.add(
        "display-name",
        display_name == DISPLAY_NAME,
        f"INFOPLIST_KEY_CFBundleDisplayName={display_name!r} (expected {DISPLAY_NAME})",
    )

    every_settings = [*_configurations(objects, projects[0]).values(), *settings.values()]
    deployment = sorted(
        {
            str(s["IPHONEOS_DEPLOYMENT_TARGET"])
            for s in every_settings
            if "IPHONEOS_DEPLOYMENT_TARGET" in s
        }
    )
    sdk_roots = sorted({str(s["SDKROOT"]) for s in every_settings if "SDKROOT" in s})
    family, catalyst = agreed("TARGETED_DEVICE_FAMILY"), agreed("SUPPORTS_MACCATALYST")
    evaluation.add(
        "platform",
        deployment == [DEPLOYMENT_TARGET]
        and sdk_roots == ["iphoneos"]
        and family == "1,2"
        and catalyst in (None, "NO"),
        f"IPHONEOS_DEPLOYMENT_TARGET={deployment} (expected {DEPLOYMENT_TARGET}), "
        f"SDKROOT={sdk_roots}, TARGETED_DEVICE_FAMILY={family!r} (expected '1,2'), "
        f"SUPPORTS_MACCATALYST={catalyst!r}",
    )
    overrides = sorted(
        {key for s in every_settings for key in s if SIGNING_OVERRIDES.match(key) and s[key]}
    )
    sign_style = agreed("CODE_SIGN_STYLE")
    evaluation.add(
        "signing",
        sign_style == "Automatic" and not overrides,
        f"CODE_SIGN_STYLE={sign_style!r} (expected Automatic); "
        + (
            f"committed signing settings: {overrides}"
            if overrides
            else "no team, profile or identity committed (the owner selects the team in Xcode)"
        ),
    )

    copied = set()
    for phase_id in _list(target.get("buildPhases")):
        phase = _dict(objects.get(str(phase_id)))
        if phase.get("isa") != "PBXResourcesBuildPhase":
            continue
        for build_file_id in _list(phase.get("files")):
            reference = _dict(
                objects.get(str(_dict(objects.get(str(build_file_id))).get("fileRef")))
            )
            copied.add(str(reference.get("path")))
    evaluation.add(
        "privacy-manifest",
        "PrivacyInfo.xcprivacy" in copied,
        "PrivacyInfo.xcprivacy is in the Resources build phase"
        if "PrivacyInfo.xcprivacy" in copied
        else f"PrivacyInfo.xcprivacy is not copied (Resources: {sorted(copied)})",
    )

    scheme_path = project / "xcshareddata" / "xcschemes" / f"{TARGET}.xcscheme"
    try:
        archive = ElementTree.parse(scheme_path).getroot().find("ArchiveAction")
    except (OSError, ElementTree.ParseError) as error:
        raise ToolError(f"cannot read the shared scheme: {error}") from error
    archive_configuration = None if archive is None else archive.get("buildConfiguration")
    evaluation.add(
        "archive-configuration",
        archive_configuration == "Release",
        f"the {TARGET} scheme archives {archive_configuration!r} (expected Release)",
    )

    info_path = project.parent / str(settings["Release"].get("INFOPLIST_FILE"))
    try:
        info = plistlib.loads(info_path.read_bytes())
    except (OSError, plistlib.InvalidFileException) as error:
        raise ToolError(f"cannot read Info.plist {info_path}: {error}") from error
    _info_checks(evaluation, info)

    report: dict[str, Any] = {
        "schema": REPORT_SCHEMA,
        "project": project.name,
        "settings": {
            "PRODUCT_BUNDLE_IDENTIFIER": bundle_id,
            "MARKETING_VERSION": version,
            "CURRENT_PROJECT_VERSION": build,
            "IPHONEOS_DEPLOYMENT_TARGET": deployment,
            "TARGETED_DEVICE_FAMILY": family,
            "CODE_SIGN_STYLE": sign_style,
            "archiveConfiguration": archive_configuration,
        },
        "checks": [{"id": c.id, "passed": c.passed, "detail": c.detail} for c in evaluation.checks],
        "result": "PASS" if evaluation.passed else "FAIL",
    }
    return evaluation, report


def _info_checks(evaluation: Evaluation, info: dict[str, Any]) -> None:
    encryption = info.get("ITSAppUsesNonExemptEncryption")
    evaluation.add(
        "export-compliance",
        encryption is False,
        f"ITSAppUsesNonExemptEncryption={encryption!r} (expected false)",
    )
    ats = info.get("NSAppTransportSecurity")
    evaluation.add(
        "ats-default",
        ats is None,
        "no NSAppTransportSecurity: Apple's defaults"
        if ats is None
        else f"NSAppTransportSecurity={ats!r}",
    )
    permissions = sorted(key for key in info if re.fullmatch(r"NS\w+UsageDescription", key))
    background = info.get("UIBackgroundModes")
    evaluation.add(
        "no-permissions",
        not permissions and not background,
        f"usage descriptions {permissions}, UIBackgroundModes={background!r} "
        "(expected none: the privacy policy says the app requests no permission)",
    )
    scenes = info.get("UIApplicationSceneManifest", {}).get("UIApplicationSupportsMultipleScenes")
    evaluation.add(
        "single-scene",
        scenes is False,
        f"UIApplicationSupportsMultipleScenes={scenes!r} (expected false, TF-RC1-IOS-SCENES)",
    )
    localizations = set(info.get("CFBundleLocalizations", []))
    evaluation.add(
        "localizations",
        localizations >= REQUIRED_LOCALIZATIONS,
        f"CFBundleLocalizations={sorted(localizations)} "
        f"(required {sorted(REQUIRED_LOCALIZATIONS)})",
    )
    ipad = set(info.get("UISupportedInterfaceOrientations~ipad", []))
    full_screen = info.get("UIRequiresFullScreen") is True
    evaluation.add(
        "ipad-orientations",
        ipad == IPAD_ORIENTATIONS or full_screen,
        f"iPad orientations {sorted(ipad)}, UIRequiresFullScreen={full_screen} "
        "(iPad multitasking needs all four orientations)",
    )


def format_summary(evaluation: Evaluation, project: Path) -> str:
    lines = [f"iOS project: {project.name}"]
    lines += [f"  [{'PASS' if c.passed else 'FAIL'}] {c.id}: {c.detail}" for c in evaluation.checks]
    lines.append(f"RESULT: {'PASS' if evaluation.passed else 'FAIL'}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0] if __doc__ else None)
    parser.add_argument("--project", default=str(DEFAULT_PROJECT), help="the .xcodeproj to check")
    parser.add_argument("--expected-version", help="MARKETING_VERSION, e.g. 1.0.0")
    parser.add_argument("--expected-build", help="CURRENT_PROJECT_VERSION, e.g. 1")
    parser.add_argument("--report", help="write the JSON report to this file")
    args = parser.parse_args(argv)
    project = Path(args.project)
    try:
        evaluation, report = evaluate(
            project, expected_version=args.expected_version, expected_build=args.expected_build
        )
    except ToolError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return EXIT_ERROR
    print(format_summary(evaluation, project))
    if args.report:
        Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return EXIT_PASS if evaluation.passed else EXIT_FAIL


if __name__ == "__main__":
    sys.exit(main())
