"""The iOS project checker (M5.IOS.3).

``scripts/verify_ios_project.py`` reads the committed ``iosApp.xcodeproj`` before the owner
signs an archive. The committed project must pass; each rule is then broken once on a copy of
the project and must fail closed. The parser is checked against the committed project file.
"""

from __future__ import annotations

import importlib.util
import json
import plistlib
import shutil
import sys
from pathlib import Path
from types import ModuleType

import pytest

ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "mobile/iosApp/iosApp.xcodeproj"
SOURCES = ROOT / "mobile/iosApp/iosApp"


def load_validator() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "verify_ios_project", ROOT / "scripts" / "verify_ios_project.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


v = load_validator()


@pytest.fixture
def project(tmp_path: Path) -> Path:
    copy = tmp_path / "iosApp" / "iosApp.xcodeproj"
    copy.mkdir(parents=True)
    shutil.copy(PROJECT / "project.pbxproj", copy / "project.pbxproj")
    shutil.copytree(PROJECT / "xcshareddata", copy / "xcshareddata")
    (tmp_path / "iosApp" / "iosApp").mkdir()
    shutil.copy(SOURCES / "Info.plist", tmp_path / "iosApp" / "iosApp" / "Info.plist")
    return copy


def failed(project: Path, **expected: str | None) -> dict[str, str]:
    arguments = {"expected_version": "1.0.0", "expected_build": "4", **expected}
    evaluation, _ = v.evaluate(project, **arguments)
    return {check.id: check.detail for check in evaluation.checks if not check.passed}


def edit_pbxproj(project: Path, old: str, new: str, count: int = -1) -> None:
    path = project / "project.pbxproj"
    text = path.read_text(encoding="utf-8")
    assert old in text
    path.write_text(text.replace(old, new, count), encoding="utf-8")


def edit_info(project: Path, changes: dict[str, object]) -> None:
    path = project.parent / "iosApp" / "Info.plist"
    info = plistlib.loads(path.read_bytes())
    for key, value in changes.items():
        if value is None:
            info.pop(key, None)
        else:
            info[key] = value
    path.write_bytes(plistlib.dumps(info))


def test_the_committed_project_passes() -> None:
    evaluation, report = v.evaluate(PROJECT, expected_version="1.0.0", expected_build="4")
    assert {c.id: c.detail for c in evaluation.checks if not c.passed} == {}
    assert report["settings"]["PRODUCT_BUNDLE_IDENTIFIER"] == v.FROZEN_BUNDLE_ID
    assert report["settings"]["archiveConfiguration"] == "Release"


def test_the_parser_reads_the_committed_project() -> None:
    root = v.parse_openstep((PROJECT / "project.pbxproj").read_text(encoding="utf-8"))
    configurations = [
        obj["buildSettings"]
        for obj in root["objects"].values()
        if obj["isa"] == "XCBuildConfiguration"
    ]
    assert len(configurations) == 4
    families = [
        s["TARGETED_DEVICE_FAMILY"] for s in configurations if "TARGETED_DEVICE_FAMILY" in s
    ]
    assert families == ["1,2", "1,2"]


def test_the_parser_rejects_malformed_text() -> None:
    with pytest.raises(v.ToolError):
        v.parse_openstep("{ a = b }")
    with pytest.raises(v.ToolError):
        v.parse_openstep("{ a = b; } trailing;")
    assert v.parse_openstep('{ a = "x \\"y\\""; b = (1, 2,); /* c */ }') == {
        "a": 'x "y"',
        "b": ["1", "2"],
    }


def test_another_bundle_id_fails(project: Path) -> None:
    edit_pbxproj(project, "io.github.alexeykravtsov95.talentforge;", "com.example.app;", 1)
    assert "bundle-id" in failed(project)


@pytest.mark.parametrize(
    ("old", "new"),
    [
        ("MARKETING_VERSION = 1.0.0;", "MARKETING_VERSION = 1.0.1;"),
        ("CURRENT_PROJECT_VERSION = 4;", "CURRENT_PROJECT_VERSION = 5;"),
    ],
)
def test_debug_and_release_must_agree_on_the_version(project: Path, old: str, new: str) -> None:
    edit_pbxproj(project, old, new, 1)
    assert "version" in failed(project, expected_version=None, expected_build=None)


def test_the_expected_version_and_build_are_enforced(project: Path) -> None:
    assert "version" in failed(project, expected_build="5")
    assert "version" in failed(project, expected_version="1.0.1")
    edit_pbxproj(project, "CURRENT_PROJECT_VERSION = 4;", "CURRENT_PROJECT_VERSION = 5;")
    assert "version" not in failed(project, expected_build="5")
    edit_pbxproj(project, "CURRENT_PROJECT_VERSION = 5;", "CURRENT_PROJECT_VERSION = 0;")
    assert "version" in failed(project, expected_build=None)


@pytest.mark.parametrize(
    ("old", "new"),
    [
        ("IPHONEOS_DEPLOYMENT_TARGET = 17.2;", "IPHONEOS_DEPLOYMENT_TARGET = 16.0;"),
        ('TARGETED_DEVICE_FAMILY = "1,2";', "TARGETED_DEVICE_FAMILY = 1;"),
        ("SUPPORTS_MACCATALYST = NO;", "SUPPORTS_MACCATALYST = YES;"),
    ],
)
def test_platform_rules(project: Path, old: str, new: str) -> None:
    edit_pbxproj(project, old, new)
    assert "platform" in failed(project)


@pytest.mark.parametrize(
    "setting",
    [
        "DEVELOPMENT_TEAM = ABCDE12345;",
        'PROVISIONING_PROFILE_SPECIFIER = "TalentForge App Store";',
        '"CODE_SIGN_IDENTITY[sdk=iphoneos*]" = "Apple Distribution";',
    ],
)
def test_committed_signing_settings_fail(project: Path, setting: str) -> None:
    edit_pbxproj(
        project, "CODE_SIGN_STYLE = Automatic;", f"CODE_SIGN_STYLE = Automatic;\n{setting}", 1
    )
    assert "committed signing settings" in failed(project)["signing"]


def test_manual_signing_fails(project: Path) -> None:
    edit_pbxproj(project, "CODE_SIGN_STYLE = Automatic;", "CODE_SIGN_STYLE = Manual;")
    assert "signing" in failed(project)


def test_the_scheme_must_archive_release(project: Path) -> None:
    scheme = project / "xcshareddata/xcschemes/iosApp.xcscheme"
    text = scheme.read_text(encoding="utf-8")
    archive = text.index("<ArchiveAction")
    scheme.write_text(
        text[:archive] + text[archive:].replace('"Release"', '"Debug"', 1), encoding="utf-8"
    )
    assert "archive-configuration" in failed(project)


def test_the_privacy_manifest_must_be_copied(project: Path) -> None:
    edit_pbxproj(project, "A1A100000000000000000006 /* PrivacyInfo.xcprivacy in Resources */,", "")
    assert "privacy-manifest" in failed(project)


@pytest.mark.parametrize(
    ("changes", "check"),
    [
        ({"ITSAppUsesNonExemptEncryption": None}, "export-compliance"),
        ({"NSAppTransportSecurity": {"NSAllowsArbitraryLoads": True}}, "ats-default"),
        ({"NSCameraUsageDescription": "Scan a code"}, "no-permissions"),
        ({"UIBackgroundModes": ["fetch"]}, "no-permissions"),
        (
            {"UIApplicationSceneManifest": {"UIApplicationSupportsMultipleScenes": True}},
            "single-scene",
        ),
        ({"CFBundleLocalizations": ["en"]}, "localizations"),
        (
            {"UISupportedInterfaceOrientations~ipad": ["UIInterfaceOrientationPortrait"]},
            "ipad-orientations",
        ),
    ],
)
def test_info_plist_rules(project: Path, changes: dict[str, object], check: str) -> None:
    edit_info(project, changes)
    assert check in failed(project)


def test_full_screen_ipad_may_limit_orientations(project: Path) -> None:
    edit_info(
        project,
        {
            "UISupportedInterfaceOrientations~ipad": ["UIInterfaceOrientationPortrait"],
            "UIRequiresFullScreen": True,
        },
    )
    assert "ipad-orientations" not in failed(project)


def test_cli_writes_a_report_and_exits_by_result(project: Path, tmp_path: Path) -> None:
    report = tmp_path / "report.json"
    arguments = ["--project", str(project), "--expected-version", "1.0.0", "--report", str(report)]
    assert v.main(arguments) == v.EXIT_PASS
    assert json.loads(report.read_text(encoding="utf-8"))["schema"] == v.REPORT_SCHEMA
    assert v.main([*arguments, "--expected-build", "7"]) == v.EXIT_FAIL
    assert v.main(["--project", str(tmp_path / "missing.xcodeproj")]) == v.EXIT_ERROR
