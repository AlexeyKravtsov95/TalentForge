"""The iOS archive validator, offline (M5.IOS.1).

``scripts/verify_ios_archive.py`` decides whether an ``.xcarchive`` is a TalentForge App Store
candidate. These tests build synthetic archives (plists, a minimal arm64 Mach-O with a symbol
table, Compose resources) and pin every fail-closed check: identity, version, deployment
target, toolchain, export compliance, single scene, icon, embedded code, the privacy manifest
against the executable's imports, the bundled datasets, production OTA off and secrets. The
real archive is checked by ``mobile-ci`` (macOS) with Xcode 27.1.
"""

from __future__ import annotations

import importlib.util
import json
import plistlib
import struct
import sys
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[2]
SPKI_A = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsyntheticKeyA"
SPKI_B = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsyntheticKeyB"


def load_validator() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "verify_ios_archive", ROOT / "scripts" / "verify_ios_archive.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


v = load_validator()

ARM64, X86_64 = 0x0100000C, 0x01000007


def macho(
    *,
    cputype: int = ARM64,
    filetype: int = 0x2,
    undefined: tuple[str, ...] = (),
    install_name: str | None = None,
    tail: bytes = b"",
) -> bytes:
    """A minimal 64-bit Mach-O: LC_SYMTAB with ``undefined`` external symbols, an optional
    LC_ID_DYLIB, and ``tail`` (stands for __objc_methname / string literals)."""
    strings = b"\0" + b"".join(name.encode() + b"\0" for name in undefined)
    commands = []
    if install_name is not None:
        name = install_name.encode() + b"\0"
        size = (24 + len(name) + 7) // 8 * 8
        commands.append(
            struct.pack("<IIIIII", 0xD, size, 24, 0, 0, 0) + name.ljust(size - 24, b"\0")
        )
    header_size = 32
    symtab_size = 24
    commands_size = sum(len(c) for c in commands) + symtab_size
    symoff = header_size + commands_size
    stroff = symoff + 16 * len(undefined)
    symtab = struct.pack("<IIIIII", 0x2, symtab_size, symoff, len(undefined), stroff, len(strings))
    header = struct.pack(
        "<IiiIIIII", 0xFEEDFACF, cputype, 0, filetype, len(commands) + 1, commands_size, 0, 0
    )
    symbols = b""
    strx = 1
    for name in undefined:
        symbols += struct.pack("<IBBHQ", strx, 0x01, 0, 0, 0)  # N_UNDF | N_EXT
        strx += len(name) + 1
    return header + b"".join(commands) + symtab + symbols + strings + tail


def manifest(**overrides: Any) -> dict[str, Any]:
    value: dict[str, Any] = {
        "NSPrivacyTracking": False,
        "NSPrivacyTrackingDomains": [],
        "NSPrivacyCollectedDataTypes": [],
        "NSPrivacyAccessedAPITypes": [
            {
                "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryUserDefaults",
                "NSPrivacyAccessedAPITypeReasons": ["CA92.1"],
            },
            {
                "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryFileTimestamp",
                "NSPrivacyAccessedAPITypeReasons": ["C617.1"],
            },
            {
                "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryDiskSpace",
                "NSPrivacyAccessedAPITypeReasons": ["E174.1"],
            },
        ],
    }
    value.update(overrides)
    return value


def app_info(**overrides: Any) -> dict[str, Any]:
    value: dict[str, Any] = {
        "CFBundleExecutable": "iosApp",
        "CFBundleIdentifier": "io.github.alexeykravtsov95.talentforge",
        "CFBundleShortVersionString": "1.0.0",
        "CFBundleVersion": "1",
        "MinimumOSVersion": "17.2",
        "UIDeviceFamily": [1, 2],
        "CFBundleLocalizations": ["en", "ru"],
        "UIRequiredDeviceCapabilities": ["arm64"],
        "DTSDKName": "iphoneos27.1",
        "DTXcode": "2710",
        "DTXcodeBuild": "27A9275",
        "ITSAppUsesNonExemptEncryption": False,
        "UIApplicationSceneManifest": {"UIApplicationSupportsMultipleScenes": False},
        "CFBundleIcons": {"CFBundlePrimaryIcon": {"CFBundleIconName": "AppIcon"}},
    }
    value.update(overrides)
    return value


DEFAULT_SYMBOLS = (
    "_stat",
    "_fstat",
    "_OBJC_CLASS_$_NSUserDefaults",
    "_NSURLVolumeAvailableCapacityForImportantUsageKey",
)


def make_archive(
    tmp_path: Path,
    *,
    info: dict[str, Any] | None = None,
    executable: bytes | None = None,
    privacy: dict[str, Any] | None = None,
    with_privacy: bool = True,
    datasets: bool = True,
    extra_files: dict[str, bytes] | None = None,
) -> Path:
    archive = tmp_path / "TalentForge.xcarchive"
    app = archive / "Products" / "Applications" / "iosApp.app"
    app.mkdir(parents=True)
    record = {
        "ApplicationProperties": {
            "ApplicationPath": "Applications/iosApp.app",
            "Architectures": ["arm64"],
        }
    }
    (archive / "Info.plist").write_bytes(plistlib.dumps(record))
    (app / "Info.plist").write_bytes(plistlib.dumps(info if info is not None else app_info()))
    (app / "iosApp").write_bytes(
        executable if executable is not None else macho(undefined=DEFAULT_SYMBOLS)
    )
    (app / "Assets.car").write_bytes(b"car")
    if with_privacy:
        (app / "PrivacyInfo.xcprivacy").write_bytes(
            plistlib.dumps(privacy if privacy is not None else manifest())
        )
    package = app / v.COMPOSE_RESOURCES / "io.github.alexeykravtsov95.talentforge.generated"
    (package / "values-ru").mkdir(parents=True)
    (package / "values-ru/strings.commonMain.cvr").write_text("ru")
    if datasets:
        (package / "files/dataset/1.60.1.70450").mkdir(parents=True)
        (package / "files/dataset/1.60.1.70450/warrior.json").write_text("{}")
    for relative, content in (extra_files or {}).items():
        path = app / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    return archive


@pytest.fixture
def keyring(tmp_path: Path) -> Path:
    path = tmp_path / "production-keyring.json"
    keys = [
        {"keyId": "tf-prod-2026a", "publicKeySpki": SPKI_A},
        {"keyId": "tf-prod-2026b", "publicKeySpki": SPKI_B},
    ]
    path.write_text(json.dumps({"keys": keys}))
    return path


def run(archive: Path, keyring: Path, **expected: str | None) -> tuple[Any, dict[str, Any]]:
    arguments: dict[str, Any] = {
        "expected_version": "1.0.0",
        "expected_build": "1",
        "expected_min_os": "17.2",
        "expected_sdk": "iphoneos27.1",
        "expected_xcode_build": "27A9275",
    }
    arguments.update(expected)
    return v.evaluate(archive, keyring=keyring, **arguments)


def failed(evaluation: Any) -> dict[str, str]:
    return {check.id: check.detail for check in evaluation.checks if not check.passed}


def test_a_complete_archive_passes(tmp_path: Path, keyring: Path) -> None:
    evaluation, report = run(make_archive(tmp_path), keyring)
    assert failed(evaluation) == {}
    assert report["result"] == "PASS"
    assert report["signed"] is False
    assert report["executable"]["architectures"] == ["arm64"]
    assert set(report["requiredReasonApisUsed"]) == {
        "NSPrivacyAccessedAPICategoryFileTimestamp",
        "NSPrivacyAccessedAPICategoryDiskSpace",
        "NSPrivacyAccessedAPICategoryUserDefaults",
    }


@pytest.mark.parametrize(
    ("overrides", "check"),
    [
        ({"CFBundleIdentifier": "com.example.other"}, "bundle-id"),
        ({"CFBundleShortVersionString": "0.1.0"}, "version"),
        ({"CFBundleVersion": "0"}, "version"),
        ({"CFBundleVersion": "1.0"}, "version"),
        ({"MinimumOSVersion": "18.0"}, "minimum-os"),
        ({"UIDeviceFamily": [1]}, "device-family"),
        ({"CFBundleLocalizations": ["en"]}, "localizations"),
        ({"UIRequiredDeviceCapabilities": []}, "device-capabilities"),
        ({"DTSDKName": "iphoneos26.4"}, "toolchain"),
        ({"DTXcodeBuild": "17E202"}, "toolchain"),
        ({"ITSAppUsesNonExemptEncryption": True}, "export-compliance"),
        (
            {"UIApplicationSceneManifest": {"UIApplicationSupportsMultipleScenes": True}},
            "single-scene",
        ),
        ({"CFBundleIcons": {}}, "app-icon"),
    ],
)
def test_each_plist_rule_fails_closed(
    tmp_path: Path, keyring: Path, overrides: dict[str, Any], check: str
) -> None:
    evaluation, report = run(make_archive(tmp_path, info=app_info(**overrides)), keyring)
    assert check in failed(evaluation)
    assert report["result"] == "FAIL"


def test_an_older_toolchain_never_passes_for_the_required_one(
    tmp_path: Path, keyring: Path
) -> None:
    info = app_info(DTSDKName="iphoneos26.4", DTXcode="2641", DTXcodeBuild="17E202")
    evaluation, _ = run(make_archive(tmp_path, info=info), keyring)
    assert "iphoneos26.4" in failed(evaluation)["toolchain"]
    evaluation, _ = run(
        make_archive(tmp_path / "local", info=info),
        keyring,
        expected_sdk="iphoneos26.4",
        expected_xcode_build="17E202",
    )
    assert failed(evaluation) == {}


@pytest.mark.parametrize(
    ("executable", "reason"),
    [
        (macho(cputype=X86_64, undefined=DEFAULT_SYMBOLS), "x86_64"),
        (macho(filetype=0x6, undefined=DEFAULT_SYMBOLS), "file types=[6]"),
        (b"!<arch>\nstatic", "static-library"),
    ],
)
def test_the_executable_must_be_a_thin_arm64_executable(
    tmp_path: Path, keyring: Path, executable: bytes, reason: str
) -> None:
    evaluation, _ = run(make_archive(tmp_path, executable=executable), keyring)
    assert reason in failed(evaluation)["executable"]


def test_a_fat_executable_is_read_slice_by_slice(tmp_path: Path, keyring: Path) -> None:
    slice_ = macho(undefined=DEFAULT_SYMBOLS)
    fat = struct.pack(">II", 0xCAFEBABE, 1) + struct.pack(">iiIII", ARM64, 0, 4096, len(slice_), 12)
    fat = fat.ljust(4096, b"\0") + slice_
    parsed = v.read_macho(fat)
    assert parsed.architectures == ("arm64",)
    assert "_stat" in parsed.undefined_symbols


def test_an_embedded_static_or_misnamed_framework_fails(tmp_path: Path, keyring: Path) -> None:
    files = {
        "Frameworks/Stub.framework/Stub": macho(
            filetype=0x6, install_name="/private/var/folders/x/arm64-apple"
        ),
        "Frameworks/Good.framework/Good": macho(
            filetype=0x6, install_name="@rpath/Good.framework/Good"
        ),
        "Frameworks/libfoo.a": b"!<arch>\n",
    }
    evaluation, _ = run(make_archive(tmp_path, extra_files=files), keyring)
    detail = failed(evaluation)["embedded-code"]
    assert "Stub.framework/Stub has install name" in detail
    assert "libfoo.a is a static library" in detail
    assert "Good.framework" not in detail


def test_a_missing_privacy_manifest_fails(tmp_path: Path, keyring: Path) -> None:
    evaluation, _ = run(make_archive(tmp_path, with_privacy=False), keyring)
    assert "TF-RC1-IOS-PRIVACY" in failed(evaluation)["privacy-manifest"]


def test_an_undeclared_used_category_fails(tmp_path: Path, keyring: Path) -> None:
    executable = macho(
        undefined=(*DEFAULT_SYMBOLS, "_mach_absolute_time"), tail=b"\0activeInputModes\0"
    )
    evaluation, _ = run(make_archive(tmp_path, executable=executable), keyring)
    detail = failed(evaluation)["privacy-required-reasons"]
    assert "SystemBootTime is used (_mach_absolute_time)" in detail
    assert "ActiveKeyboards is used (activeInputModes)" in detail


def test_a_reason_code_must_belong_to_its_category(tmp_path: Path, keyring: Path) -> None:
    types = manifest()["NSPrivacyAccessedAPITypes"]
    types[0] = {
        "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryUserDefaults",
        "NSPrivacyAccessedAPITypeReasons": ["C617.1"],
    }
    evaluation, _ = run(
        make_archive(tmp_path, privacy=manifest(NSPrivacyAccessedAPITypes=types)), keyring
    )
    assert "are not Apple's codes" in failed(evaluation)["privacy-required-reasons"]


def test_tracking_must_be_off(tmp_path: Path, keyring: Path) -> None:
    evaluation, _ = run(make_archive(tmp_path, privacy=manifest(NSPrivacyTracking=True)), keyring)
    assert "privacy-tracking" in failed(evaluation)


def test_the_datasets_must_be_bundled(tmp_path: Path, keyring: Path) -> None:
    evaluation, _ = run(make_archive(tmp_path, datasets=False), keyring)
    assert "0 dataset JSON files" in failed(evaluation)["compose-resources"]


@pytest.mark.parametrize("encoding", ["utf-8", "utf-16-le"])
def test_production_ota_compiled_in_fails(tmp_path: Path, keyring: Path, encoding: str) -> None:
    executable = macho(undefined=DEFAULT_SYMBOLS, tail=SPKI_B.encode(encoding))
    evaluation, _ = run(make_archive(tmp_path, executable=executable), keyring)
    assert "production OTA compiled in" in failed(evaluation)["ota-off"]
    executable = macho(undefined=DEFAULT_SYMBOLS, tail="https://ota.mythweld.com".encode(encoding))
    evaluation, _ = run(make_archive(tmp_path / "origin", executable=executable), keyring)
    assert "origin=True" in failed(evaluation)["ota-off"]


def test_key_material_in_the_bundle_fails(tmp_path: Path, keyring: Path) -> None:
    files = {"dist.p12": b"x", "notes.txt": b"-----BEGIN EC PRIVATE KEY-----\n"}
    evaluation, _ = run(make_archive(tmp_path, extra_files=files), keyring)
    detail = failed(evaluation)["no-secrets"]
    assert "dist.p12" in detail and "notes.txt (contains a private key)" in detail


def test_cli_writes_a_report_and_exits_by_result(tmp_path: Path, keyring: Path) -> None:
    archive = make_archive(tmp_path)
    report_path = tmp_path / "report.json"
    common = [
        "--archive",
        str(archive),
        "--production-keyring",
        str(keyring),
        "--report",
        str(report_path),
    ]
    assert (
        v.main([*common, "--expected-sdk", "iphoneos27.1", "--expected-xcode-build", "27A9275"])
        == v.EXIT_PASS
    )
    assert json.loads(report_path.read_text())["schema"] == "talentforge.ios-archive-report.v1"
    assert v.main([*common, "--expected-sdk", "iphoneos27.2"]) == v.EXIT_FAIL
    assert v.main(["--archive", str(tmp_path / "missing.xcarchive")]) == v.EXIT_ERROR


def test_the_committed_production_keyring_is_the_default() -> None:
    spkis = v.production_spkis(v.DEFAULT_PRODUCTION_KEYRING)
    assert len(spkis) == 2
