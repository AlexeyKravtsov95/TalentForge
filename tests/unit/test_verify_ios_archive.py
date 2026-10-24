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


def test_a_distribution_archive_must_be_signed_when_required(tmp_path: Path, keyring: Path) -> None:
    unsigned = make_archive(tmp_path / "unsigned")
    evaluation, report = run(unsigned, keyring)
    assert "signed" not in {check.id for check in evaluation.checks}
    assert report["signed"] is False
    evaluation, _ = run(unsigned, keyring, require_signed=True)
    assert "_CodeSignature/CodeResources missing" in failed(evaluation)["signed"]

    signature_only = make_archive(
        tmp_path / "no-profile", extra_files={"_CodeSignature/CodeResources": b"<plist/>"}
    )
    evaluation, _ = run(signature_only, keyring, require_signed=True)
    assert "embedded.mobileprovision missing" in failed(evaluation)["signed"]

    signed = make_archive(
        tmp_path / "signed",
        extra_files={
            "_CodeSignature/CodeResources": b"<plist/>",
            "embedded.mobileprovision": b"profile",
        },
    )
    evaluation, report = run(signed, keyring, require_signed=True)
    assert failed(evaluation) == {}
    assert report["signed"] is True
    common = ["--archive", str(signed), "--ota", "off", "--production-keyring", str(keyring)]
    assert v.main([*common, "--require-signed"]) == v.EXIT_PASS
    unsigned_cli = ["--archive", str(unsigned), "--ota", "off", "--require-signed"]
    assert v.main(unsigned_cli) == v.EXIT_FAIL


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
