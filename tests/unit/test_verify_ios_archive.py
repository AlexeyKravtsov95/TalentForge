"""The iOS archive validator, offline (M5.IOS.1).

``scripts/verify_ios_archive.py`` decides whether an ``.xcarchive`` is a TalentForge App Store
candidate. These tests build synthetic archives (plists, a minimal arm64 Mach-O with a symbol
table, Compose resources) and pin every fail-closed check: identity, version, deployment
target, toolchain, export compliance, single scene, icon, embedded code, the privacy manifest
against the executable's imports, the bundled datasets, production OTA off and secrets. The
real archive is checked by ``mobile-ci`` (macOS) with Xcode 27.1.
"""

from __future__ import annotations

import datetime as dt
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
COMMITTED_KEYRING = ROOT / "docs/ota/keys/production-keyring.json"
_KEYS = {
    key["keyId"]: key["publicKeySpki"] for key in json.loads(COMMITTED_KEYRING.read_text())["keys"]
}
SPKI_A, SPKI_B = _KEYS["tf-prod-2026a"], _KEYS["tf-prod-2026b"]
FIXTURE_SPKI = json.loads((ROOT / "docs/ota/fixtures/v1/keyring.json").read_text())["keys"][0][
    "publicKeySpki"
]


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
def keyring() -> Path:
    return COMMITTED_KEYRING


def utf16(text: str) -> bytes:
    """A Kotlin/Native string literal: UTF-16LE, here between NULs like the literal pool."""
    return b"\0\0" + text.encode("utf-16-le") + b"\0\0"


def ota_on_tail(
    *,
    origin: str = "https://ota.mythweld.com",
    spkis: tuple[str, ...] = (SPKI_A, SPKI_B),
    ids: tuple[str, ...] = ("tf-prod-2026a", "tf-prod-2026b"),
) -> bytes:
    """The literals GeneratedOtaSettings.iosProduction compiles into an OTA ON executable."""
    return b"".join(utf16(text) for text in (origin, *ids, *spkis))


def run(archive: Path, keyring: Path, **expected: Any) -> tuple[Any, dict[str, Any]]:
    arguments: dict[str, Any] = {
        "expected_version": "1.0.0",
        "expected_build": "1",
        "expected_min_os": "17.2",
        "expected_sdk": "iphoneos27.1",
        "expected_xcode_build": "27A9275",
        "ota": "off",
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
    executable = macho(undefined=DEFAULT_SYMBOLS, tail=b"\0" + SPKI_B.encode(encoding) + b"\0")
    evaluation, _ = run(make_archive(tmp_path, executable=executable), keyring)
    assert "keys=['tf-prod-2026b']" in failed(evaluation)["ota-off"]
    executable = macho(undefined=DEFAULT_SYMBOLS, tail="https://ota.mythweld.com".encode(encoding))
    evaluation, _ = run(make_archive(tmp_path / "origin", executable=executable), keyring)
    assert "production origin=True" in failed(evaluation)["ota-off"]


def test_any_embedded_public_key_fails_an_ota_off_build(tmp_path: Path, keyring: Path) -> None:
    executable = macho(undefined=DEFAULT_SYMBOLS, tail=utf16(FIXTURE_SPKI))
    evaluation, report = run(make_archive(tmp_path, executable=executable), keyring)
    assert "keys=['unknown:" in failed(evaluation)["ota-off"]
    assert report["ota"]["embeddedKeys"][0].startswith("unknown:")


def test_an_ota_on_archive_passes_in_on_mode_only(tmp_path: Path, keyring: Path) -> None:
    executable = macho(undefined=DEFAULT_SYMBOLS, tail=ota_on_tail())
    archive = make_archive(tmp_path, executable=executable)
    evaluation, report = run(archive, keyring, ota="on")
    assert failed(evaluation) == {}
    assert report["ota"] == {
        "mode": "on",
        "productionOrigin": True,
        "embeddedKeys": ["tf-prod-2026a", "tf-prod-2026b"],
        "embeddedKeySpkiSha256": sorted(v.PRODUCTION_KEY_SPKI_SHA256.values()),
        "httpsOrigins": ["https://ota.mythweld.com"],
    }
    # the same archive claimed as OTA OFF never passes
    evaluation, _ = run(archive, keyring, ota="off")
    assert set(failed(evaluation)) == {"ota-off"}


def test_an_ota_off_archive_never_passes_as_ota_on(tmp_path: Path, keyring: Path) -> None:
    evaluation, _ = run(make_archive(tmp_path), keyring, ota="on")
    assert "origin missing, keys [], key ids missing" in failed(evaluation)["ota-on"]


@pytest.mark.parametrize(
    ("tail", "reason"),
    [
        (ota_on_tail(origin="https://ota.example.org"), "origin missing"),
        (ota_on_tail(spkis=(SPKI_A,)), "keys ['tf-prod-2026a']"),
        (
            ota_on_tail(spkis=(SPKI_A, SPKI_B, FIXTURE_SPKI)),
            "keys ['tf-prod-2026a', 'tf-prod-2026b', 'unknown:",
        ),
        (ota_on_tail(spkis=(FIXTURE_SPKI, SPKI_B)), "keys ['tf-prod-2026b', 'unknown:"),
        (ota_on_tail(ids=("tf-prod-2026a", "tf-staging-2026")), "key ids missing"),
    ],
    ids=[
        "another origin",
        "backup key missing",
        "an extra test key",
        "a substituted primary key",
        "a substituted key id",
    ],
)
def test_ota_on_requires_exactly_the_pinned_origin_and_keys(
    tmp_path: Path, keyring: Path, tail: bytes, reason: str
) -> None:
    executable = macho(undefined=DEFAULT_SYMBOLS, tail=tail)
    evaluation, _ = run(make_archive(tmp_path, executable=executable), keyring, ota="on")
    assert reason in failed(evaluation)["ota-on"]


@pytest.mark.parametrize(
    "origin",
    [
        "https://talentforge-ota-staging.hb.ru-msk.vkcloud-storage.ru",
        "https://localhost:8443",
        "https://127.0.0.1:8443",
        "https://10.0.2.2:8443",
        "https://staging.mythweld.com",
    ],
)
@pytest.mark.parametrize("ota", ["on", "off"])
def test_a_staging_or_development_origin_fails_either_mode(
    tmp_path: Path, keyring: Path, origin: str, ota: str
) -> None:
    tail = (ota_on_tail() if ota == "on" else b"") + utf16(origin)
    executable = macho(undefined=DEFAULT_SYMBOLS, tail=tail)
    evaluation, _ = run(make_archive(tmp_path, executable=executable), keyring, ota=ota)
    assert origin in failed(evaluation)["ota-no-staging-origin"]


def test_the_test_factory_origin_is_not_a_staging_origin(tmp_path: Path, keyring: Path) -> None:
    # OtaConfig.forTesting's default base URL is compiled into every build and names no host
    tail = ota_on_tail() + utf16("https://ota.invalid")
    executable = macho(undefined=DEFAULT_SYMBOLS, tail=tail)
    evaluation, _ = run(make_archive(tmp_path, executable=executable), keyring, ota="on")
    assert failed(evaluation) == {}


def test_a_substituted_production_keyring_never_passes(tmp_path: Path) -> None:
    substituted = tmp_path / "keyring.json"
    keys = [
        {"keyId": "tf-prod-2026a", "publicKeySpki": FIXTURE_SPKI},
        {"keyId": "tf-prod-2026b", "publicKeySpki": SPKI_B},
    ]
    substituted.write_text(json.dumps({"keys": keys}))
    tail = ota_on_tail(spkis=(FIXTURE_SPKI, SPKI_B))
    executable = macho(undefined=DEFAULT_SYMBOLS, tail=tail)
    evaluation, _ = run(make_archive(tmp_path, executable=executable), substituted, ota="on")
    assert "is not the pinned one" in failed(evaluation)["ota-keyring"]
    assert "ota-on" in failed(evaluation)
    evaluation, _ = run(make_archive(tmp_path / "off"), substituted)
    assert "ota-keyring" in failed(evaluation)


@pytest.mark.parametrize(
    "ats",
    [{"NSAllowsArbitraryLoads": True}, {"NSExceptionDomains": {"ota.mythweld.com": {}}}, {}],
)
def test_app_transport_security_must_keep_the_defaults(
    tmp_path: Path, keyring: Path, ats: dict[str, Any]
) -> None:
    evaluation, _ = run(make_archive(tmp_path, info=app_info(NSAppTransportSecurity=ats)), keyring)
    assert "App Transport Security is changed" in failed(evaluation)["ats-default"]


def test_collected_data_must_be_what_the_caller_declares(tmp_path: Path, keyring: Path) -> None:
    diagnostic = v.PRIVACY_COLLECTED["other-diagnostic"]
    on = macho(undefined=DEFAULT_SYMBOLS, tail=ota_on_tail())
    archive = make_archive(
        tmp_path, executable=on, privacy=manifest(NSPrivacyCollectedDataTypes=diagnostic)
    )
    evaluation, _ = run(archive, keyring, ota="on", privacy_collected="other-diagnostic")
    assert failed(evaluation) == {}
    evaluation, _ = run(archive, keyring, ota="on")
    assert "privacy-collected-data" in failed(evaluation)
    evaluation, _ = run(
        make_archive(tmp_path / "none", executable=on),
        keyring,
        ota="on",
        privacy_collected="other-diagnostic",
    )
    assert "privacy-collected-data" in failed(evaluation)
    with pytest.raises(v.ToolError, match="declares no collected data"):
        run(make_archive(tmp_path / "off"), keyring, privacy_collected="other-diagnostic")


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
        "--ota",
        "off",
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
    assert (
        v.main(["--archive", str(tmp_path / "missing.xcarchive"), "--ota", "off"]) == v.EXIT_ERROR
    )


# --require-signed: the real signature, checked with codesign / security (mocked here).
TEAM = "ABCDE12345"
LEAF_CERT = b"0\x82leaf-certificate-der"
NOW = dt.datetime(2026, 10, 24, 12, 0, tzinfo=dt.UTC)
DEVELOPMENT = "Apple Development: Owner Name (QWERTY1234)"
DISTRIBUTION = "Apple Distribution: Owner Name (ABCDE12345)"


def codesign_display(
    identity: str | None = DEVELOPMENT,
    team: str = TEAM,
    identifier: str = "io.github.alexeykravtsov95.talentforge",
    adhoc: bool = False,
) -> str:
    """What ``codesign -d --verbose=4`` prints (on stderr) for a signed or ad-hoc app."""
    lines = [
        "Executable=/archive/Products/Applications/iosApp.app/iosApp",
        f"Identifier={identifier}",
        "Format=app bundle with Mach-O thin (arm64)",
        f"CodeDirectory v=20500 size=90911 flags={'0x2(adhoc)' if adhoc else '0x0(none)'} "
        "hashes=2834+7 location=embedded",
    ]
    if adhoc:
        lines.append("Signature=adhoc")
    elif identity:
        lines += [
            f"Authority={identity}",
            "Authority=Apple Worldwide Developer Relations Certification Authority",
            "Authority=Apple Root CA",
        ]
    lines.append(f"TeamIdentifier={'not set' if adhoc else team}")
    return "\n".join(lines) + "\n"


def profile(**overrides: Any) -> dict[str, Any]:
    """A decoded development provisioning profile for the frozen bundle id."""
    value: dict[str, Any] = {
        "Name": "iOS Team Provisioning Profile: io.github.alexeykravtsov95.talentforge",
        "TeamIdentifier": [TEAM],
        "ApplicationIdentifierPrefix": [TEAM],
        "CreationDate": dt.datetime(2026, 10, 1),
        "ExpirationDate": dt.datetime(2027, 10, 1),
        "ProvisionedDevices": ["00008110-000A1B2C3D4E5F60"],
        "DeveloperCertificates": [LEAF_CERT],
        "Entitlements": {
            "application-identifier": f"{TEAM}.io.github.alexeykravtsov95.talentforge",
            "com.apple.developer.team-identifier": TEAM,
            "get-task-allow": True,
        },
    }
    value.update(overrides)
    return value


def app_store_profile(**overrides: Any) -> dict[str, Any]:
    value = profile(Name="TalentForge App Store", ProvisionedDevices=None)
    del value["ProvisionedDevices"]
    value["Entitlements"] = {**value["Entitlements"], "get-task-allow": False}
    value.update(overrides)
    return value


def tools(
    *,
    verify: tuple[int, str] = (0, "iosApp.app: valid on disk\niosApp.app: satisfies its DR\n"),
    display: tuple[int, str] | None = None,
    decoded: tuple[int, bytes] | None = None,
    leaf: bytes | None = LEAF_CERT,
    entitlements: dict[str, Any] | None = None,
) -> tuple[Any, list[list[str]]]:
    """A fake codesign/security runner and the commands it was asked to run."""
    calls: list[list[str]] = []
    decoded = decoded if decoded is not None else (0, plistlib.dumps(profile()))
    display = display if display is not None else (0, codesign_display())
    signed = entitlements if entitlements is not None else profile()["Entitlements"]

    def run(args: list[str]) -> Any:
        calls.append(args)
        if args[:2] == ["codesign", "--verify"]:
            return v.ToolResult(verify[0], b"", verify[1])
        if args[:3] == ["codesign", "-d", "--verbose=4"]:
            return v.ToolResult(display[0], b"", display[1])
        if args[:3] == ["security", "cms", "-D"]:
            return v.ToolResult(decoded[0], decoded[1], "")
        if args[:2] == ["codesign", "-d"] and args[2].startswith("--extract-certificates="):
            if leaf is None:
                return v.ToolResult(1, b"", "code object is not signed at all")
            Path(args[2].split("=", 1)[1] + "0").write_bytes(leaf)
            return v.ToolResult(0, b"", "")
        if args[:3] == ["codesign", "-d", "--entitlements"]:
            return v.ToolResult(0, plistlib.dumps(signed), "")
        raise AssertionError(f"unexpected command {args}")

    return run, calls


def signed_archive(tmp_path: Path) -> Path:
    """An archive whose app holds a signature directory and a profile file (bytes are dummies:
    every fact about them comes from the mocked tools, exactly as the real check reads them)."""
    return make_archive(
        tmp_path,
        extra_files={
            "_CodeSignature/CodeResources": b"<plist/>",
            "embedded.mobileprovision": b"cms-envelope",
        },
    )


def run_signed(archive: Path, keyring: Path, runner: Any, **extra: Any) -> tuple[Any, Any]:
    return run(archive, keyring, require_signed=True, runner=runner, now=NOW, **extra)


SIGNING_CHECKS = (
    "signature-valid",
    "signature-identity",
    "provisioning-profile",
    "profile-certificate",
    "testflight-signing",
)


def test_a_development_signed_archive_passes(tmp_path: Path, keyring: Path) -> None:
    runner, calls = tools()
    evaluation, report = run_signed(signed_archive(tmp_path), keyring, runner)
    assert failed(evaluation) == {}
    assert {c.id for c in evaluation.checks} >= set(SIGNING_CHECKS)
    assert report["signing"]["identity"] == DEVELOPMENT
    assert report["signing"]["teamId"] == TEAM
    assert "re-signs it with Apple Distribution" in report["signing"]["configuration"]
    assert calls[0][:4] == ["codesign", "--verify", "--strict", "--verbose=2"]


def test_an_app_store_signed_archive_passes(tmp_path: Path, keyring: Path) -> None:
    runner, _ = tools(
        display=(0, codesign_display(DISTRIBUTION)),
        decoded=(0, plistlib.dumps(app_store_profile())),
        entitlements=app_store_profile()["Entitlements"],
    )
    evaluation, report = run_signed(
        signed_archive(tmp_path), keyring, runner, expected_team_id=TEAM
    )
    assert failed(evaluation) == {}
    assert report["signing"]["configuration"].startswith("App Store")


def test_dummy_signature_files_never_pass(tmp_path: Path, keyring: Path) -> None:
    # What the real tools answer for an app that only has the two files, with dummy bytes.
    runner, _ = tools(
        verify=(1, "iosApp.app: code object is not signed at all\nIn architecture: arm64\n"),
        display=(1, "iosApp.app: code object is not signed at all\n"),
        decoded=(0, b""),
        leaf=None,
        entitlements={},
    )
    problems = failed(run_signed(signed_archive(tmp_path), keyring, runner)[0])
    assert set(SIGNING_CHECKS) <= set(problems)
    assert "not signed at all" in problems["signature-valid"]


def test_a_damaged_signature_fails(tmp_path: Path, keyring: Path) -> None:
    runner, _ = tools(verify=(1, "iosApp.app: a sealed resource is missing or invalid\n"))
    problems = failed(run_signed(signed_archive(tmp_path), keyring, runner)[0])
    assert list(problems) == ["signature-valid"]
    assert "sealed resource" in problems["signature-valid"]


def test_an_adhoc_signature_fails_although_codesign_verify_accepts_it(
    tmp_path: Path, keyring: Path
) -> None:
    runner, _ = tools(display=(0, codesign_display(adhoc=True)))
    problems = failed(run_signed(signed_archive(tmp_path), keyring, runner)[0])
    assert "signature-valid" not in problems  # codesign --verify accepts ad-hoc signatures
    assert "ad-hoc signature" in problems["signature-identity"]


@pytest.mark.parametrize(
    ("display", "problem"),
    [
        (codesign_display(identity="Developer ID Application: Owner (ABCDE12345)"), "is not Apple"),
        (codesign_display(team="SHORT"), "TeamIdentifier='SHORT'"),
        (codesign_display(identifier="com.example.other"), "Identifier='com.example.other'"),
    ],
)
def test_the_signature_must_name_a_team_identity_for_the_app(
    tmp_path: Path, keyring: Path, display: str, problem: str
) -> None:
    runner, _ = tools(display=(0, display))
    problems = failed(run_signed(signed_archive(tmp_path), keyring, runner)[0])
    assert problem in problems["signature-identity"]


def test_another_team_than_expected_fails(tmp_path: Path, keyring: Path) -> None:
    runner, _ = tools()
    evaluation, _ = run_signed(
        signed_archive(tmp_path), keyring, runner, expected_team_id="ZZZZZ99999"
    )
    assert "not the expected ZZZZZ99999" in failed(evaluation)["signature-identity"]


def test_a_profile_of_another_team_fails(tmp_path: Path, keyring: Path) -> None:
    other = profile(TeamIdentifier=["ZZZZZ99999"])
    runner, _ = tools(decoded=(0, plistlib.dumps(other)))
    problems = failed(run_signed(signed_archive(tmp_path), keyring, runner)[0])
    assert "do not include the signature's ABCDE12345" in problems["provisioning-profile"]


@pytest.mark.parametrize(
    ("overrides", "problem"),
    [
        ({"ExpirationDate": dt.datetime(2026, 10, 20)}, "not in force"),
        ({"CreationDate": dt.datetime(2026, 11, 1)}, "not in force"),
        ({"ExpirationDate": "never"}, "not in force"),
        ({"ProvisionsAllDevices": True}, "enterprise"),
    ],
)
def test_an_invalid_profile_fails(
    tmp_path: Path, keyring: Path, overrides: dict[str, Any], problem: str
) -> None:
    runner, _ = tools(decoded=(0, plistlib.dumps(profile(**overrides))))
    problems = failed(run_signed(signed_archive(tmp_path), keyring, runner)[0])
    assert problem in problems["provisioning-profile"]


@pytest.mark.parametrize(
    "app_id",
    [f"{TEAM}.*", f"{TEAM}.com.example.other", "ZZZZZ99999.io.github.alexeykravtsov95.talentforge"],
)
def test_the_profile_must_name_exactly_this_app(tmp_path: Path, keyring: Path, app_id: str) -> None:
    entitlements = {**profile()["Entitlements"], "application-identifier": app_id}
    runner, _ = tools(decoded=(0, plistlib.dumps(profile(Entitlements=entitlements))))
    problems = failed(run_signed(signed_archive(tmp_path), keyring, runner)[0])
    assert "application-identifier" in problems["provisioning-profile"]


@pytest.mark.parametrize(
    "decoded", [(1, b""), (0, b"security: failed to decode message"), (0, b"")]
)
def test_an_undecodable_profile_fails(
    tmp_path: Path, keyring: Path, decoded: tuple[int, bytes]
) -> None:
    runner, _ = tools(decoded=decoded)
    problems = failed(run_signed(signed_archive(tmp_path), keyring, runner)[0])
    assert "not a CMS-signed provisioning profile" in problems["provisioning-profile"]


def test_a_missing_profile_fails(tmp_path: Path, keyring: Path) -> None:
    archive = make_archive(tmp_path, extra_files={"_CodeSignature/CodeResources": b"<plist/>"})
    runner, calls = tools()
    problems = failed(run_signed(archive, keyring, runner)[0])
    assert "provisioning-profile" in problems
    assert not any(call[:2] == ["security", "cms"] for call in calls)


def test_the_signing_certificate_must_be_in_the_profile(tmp_path: Path, keyring: Path) -> None:
    runner, _ = tools(leaf=b"0\x82another-certificate")
    problems = failed(run_signed(signed_archive(tmp_path), keyring, runner)[0])
    assert list(problems) == ["profile-certificate"]


def test_an_ad_hoc_distribution_profile_is_not_for_testflight(
    tmp_path: Path, keyring: Path
) -> None:
    adhoc_profile = profile(Name="TalentForge Ad Hoc")
    adhoc_profile["Entitlements"] = {**adhoc_profile["Entitlements"], "get-task-allow": False}
    runner, _ = tools(
        display=(0, codesign_display(DISTRIBUTION)),
        decoded=(0, plistlib.dumps(adhoc_profile)),
        entitlements=adhoc_profile["Entitlements"],
    )
    problems = failed(run_signed(signed_archive(tmp_path), keyring, runner)[0])
    assert "Ad Hoc" in problems["testflight-signing"]


def test_a_distribution_identity_with_get_task_allow_fails(tmp_path: Path, keyring: Path) -> None:
    debuggable = app_store_profile()
    debuggable["Entitlements"] = {**debuggable["Entitlements"], "get-task-allow": True}
    runner, _ = tools(
        display=(0, codesign_display(DISTRIBUTION)),
        decoded=(0, plistlib.dumps(debuggable)),
        entitlements=debuggable["Entitlements"],
    )
    problems = failed(run_signed(signed_archive(tmp_path), keyring, runner)[0])
    assert "get-task-allow" in problems["testflight-signing"]


def test_signed_entitlements_must_match_the_profile(tmp_path: Path, keyring: Path) -> None:
    entitlements = {
        **profile()["Entitlements"],
        "com.apple.developer.team-identifier": "ZZZZZ99999",
    }
    runner, _ = tools(entitlements=entitlements)
    problems = failed(run_signed(signed_archive(tmp_path), keyring, runner)[0])
    assert "com.apple.developer.team-identifier" in problems["testflight-signing"]


def test_without_the_signing_tools_the_check_is_blocked(
    tmp_path: Path, keyring: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(v.shutil, "which", lambda tool: None)
    with pytest.raises(v.ToolError, match="BLOCKED"):
        run(signed_archive(tmp_path), keyring, require_signed=True)
    arguments = ["--archive", str(signed_archive(tmp_path / "cli")), "--ota", "off"]
    assert (
        v.main([*arguments, "--production-keyring", str(keyring), "--require-signed"])
        == v.EXIT_ERROR
    )


def test_unsigned_ci_archives_run_no_signing_tool(tmp_path: Path, keyring: Path) -> None:
    def no_tools(args: list[str]) -> Any:
        raise AssertionError("no signing tool may run without --require-signed")

    evaluation, report = run(make_archive(tmp_path), keyring, runner=no_tools)
    assert failed(evaluation) == {}
    assert report["signed"] is False
    assert report["signing"] is None
    assert not {c.id for c in evaluation.checks} & set(SIGNING_CHECKS)


def test_the_cli_requires_the_ota_mode(tmp_path: Path) -> None:
    with pytest.raises(SystemExit):
        v.main(["--archive", str(make_archive(tmp_path))])
    with pytest.raises(SystemExit):
        v.main(["--archive", str(make_archive(tmp_path / "x")), "--ota", "maybe"])


def test_the_committed_production_keyring_is_the_default_and_pinned() -> None:
    keys = v.production_keys(v.DEFAULT_PRODUCTION_KEYRING)
    assert {
        key_id: v.spki_sha256(spki) for key_id, spki in keys.items()
    } == v.PRODUCTION_KEY_SPKI_SHA256


def test_the_verifier_pins_the_same_fingerprints_as_the_gradle_build() -> None:
    gradle = (ROOT / "mobile/composeApp/build.gradle.kts").read_text()
    for key_id, sha in v.PRODUCTION_KEY_SPKI_SHA256.items():
        assert f'"{key_id}" to "{sha}"' in gradle
