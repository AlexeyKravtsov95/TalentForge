"""The Google Play app-bundle validator, offline (M5.PLAY.1).

``scripts/verify_play_bundle.py`` decides whether an ``.aab`` may be uploaded to Google Play:
signed with the owner's **upload key**, never with the app signing key, a debug key or a
self-test key. These tests pin its protobuf decoding against the real manifest and
``BundleConfig.pb`` of a ``bundlePlay`` build (``tests/fixtures/play_bundle``, read back with
``bundletool dump`` 1.18.3 when they were extracted), every fail-closed check, and the CLI
end to end against fake ``keytool``/``jarsigner`` executables, including that the report
carries no secret. The real tools, two real temporary keys and the Gradle path are exercised
by ``scripts/play_signing_selftest.sh``.
"""

from __future__ import annotations

import dataclasses
import importlib.util
import json
import sys
import zipfile
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

from tests.android_artifacts import elf, proto_bundle_config, proto_manifest

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "tests/fixtures/play_bundle"

UPLOAD = "a1" * 32  # synthetic digest of the owner's upload key
APP_SIGNING = "b2" * 32  # synthetic digest of the owner's app signing key
DEBUG = "c3" * 32  # synthetic digest of a local debug keystore
OWN_PERMISSION = "io.github.alexeykravtsov95.talentforge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"


def load_validator() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "verify_play_bundle", ROOT / "scripts" / "verify_play_bundle.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


v = load_validator()


def keytool_form(digest: str) -> str:
    return ":".join(digest[i : i + 2].upper() for i in range(0, len(digest), 2))


def printcert_output(
    *,
    dn: str = "CN=TalentForge Upload, O=TalentForge, C=RU",
    digest: str = UPLOAD,
    bits: int = 4096,
) -> str:
    """``keytool -printcert -jarfile`` in the format of JDK 17 (synthetic digests)."""
    return "\n".join(
        [
            "Signer #1:",
            "",
            "Certificate #1:",
            f"Owner: {dn}",
            f"Issuer: {dn}",
            "Serial number: 1a2b3c",
            "Certificate fingerprints:",
            f"\t SHA1: {keytool_form('d4' * 20)}",
            f"\t SHA256: {keytool_form(digest)}",
            "Signature algorithm name: SHA256withRSA",
            f"Subject Public Key Algorithm: {bits}-bit RSA key",
            "Version: 3",
        ]
    )


def signature(**overrides: Any) -> Any:
    printed = v.parse_printcert(printcert_output())
    values: dict[str, Any] = {
        "verifies": True,
        "unsigned_entries": (),
        "signature_files": 1,
        "signer_count": printed.signers,
        "certificate_dns": printed.dns,
        "certificate_sha256s": printed.sha256s,
        "key_algorithms": printed.algorithms,
        "key_sizes": printed.sizes,
    }
    values.update(overrides)
    return v.BundleSignature(**values)


def libraries(*abis: str, alignment: int = 16384) -> tuple[Any, ...]:
    return tuple(
        v.apk.NativeLibrary(f"base/lib/{abi}/libx.so", abi, (alignment, alignment), True, 0)
        for abi in abis
    )


ALL_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
REAL_MANIFEST = (FIXTURES / "AndroidManifest.pb").read_bytes()
REAL_CONFIG = (FIXTURES / "BundleConfig.pb").read_bytes()


def evaluate(**overrides: Any) -> Any:
    arguments: dict[str, Any] = {
        "signature": signature(),
        "manifest": v.parse_bundle_manifest(REAL_MANIFEST),
        "config": v.parse_bundle_config(REAL_CONFIG),
        "libraries": libraries(*ALL_ABIS),
        "expected_upload_cert_sha256": UPLOAD,
        "app_signing_cert_sha256": APP_SIGNING,
        "debug_cert_sha256": DEBUG,
        "expected_version_code": 1,
        "expected_version_name": "0.1.0",
    }
    arguments.update(overrides)
    return v.evaluate(**arguments)


def failed(evaluation: Any) -> set[str]:
    return {check.id for check in evaluation.checks if not check.passed}


# ------------------------------------------------------------------ real bundle parts


def test_real_bundle_manifest_decodes_like_bundletool() -> None:
    manifest = v.parse_bundle_manifest(REAL_MANIFEST)
    assert manifest == v.BundleManifest(
        application_id="io.github.alexeykravtsov95.talentforge",
        version_code=1,
        version_name="0.1.0",
        min_sdk=26,
        target_sdk=36,
        permissions=(OWN_PERMISSION,),
        debuggable=False,
        test_only=False,
    )


def test_real_bundle_config_stores_native_libraries_16kb_aligned() -> None:
    assert v.parse_bundle_config(REAL_CONFIG) == v.NativeLibraryConfig(True, "16K")


def test_a_correct_upload_signed_bundle_passes_every_check() -> None:
    evaluation = evaluate()
    assert failed(evaluation) == set()
    assert evaluation.result(selftest=False) == "PASS"


# ----------------------------------------------------------------- protobuf decoding


def test_synthetic_manifest_round_trips() -> None:
    manifest = v.parse_bundle_manifest(proto_manifest(version_code="7", target_sdk="35"))
    assert (manifest.version_code, manifest.target_sdk) == (7, 35)
    assert manifest.permissions == (OWN_PERMISSION,)


@pytest.mark.parametrize(
    ("attributes", "debuggable", "test_only"),
    [
        ((("debuggable", "true"),), True, False),
        ((("testOnly", "true"),), False, True),
        ((("debuggable", "false"), ("testOnly", "false")), False, False),
        ((("debuggable", "@bool/flag"),), True, False),  # unresolvable: fail closed
    ],
)
def test_debuggable_and_test_only_flags(
    attributes: tuple[tuple[str, str], ...], debuggable: bool, test_only: bool
) -> None:
    manifest = v.parse_bundle_manifest(proto_manifest(application_attributes=attributes))
    assert (manifest.debuggable, manifest.test_only) == (debuggable, test_only)


def test_malformed_protobuf_is_an_error() -> None:
    with pytest.raises(v.ProtoError):
        v.parse_bundle_manifest(b"\x0a\xff")
    with pytest.raises(v.ProtoError):
        v.parse_bundle_manifest(b"")


def test_bundle_config_alignments() -> None:
    assert v.parse_bundle_config(proto_bundle_config(alignment=1)).alignment == "4K"
    assert v.parse_bundle_config(proto_bundle_config(alignment=3)).alignment == "64K"
    assert not v.parse_bundle_config(proto_bundle_config(uncompress_native=False)).uncompressed
    assert v.parse_bundle_config(b"") == v.NativeLibraryConfig(False, "UNSPECIFIED")


# ------------------------------------------------------------------------ signature


def test_parse_printcert_reads_only_each_signers_leaf_certificate() -> None:
    chain = "\n\nCertificate #2:\nOwner: CN=Some CA\n\t SHA256: " + keytool_form("ee" * 32)
    printed = v.parse_printcert(printcert_output() + chain)
    assert printed.signers == 1
    assert printed.sha256s == (UPLOAD,)
    assert printed.dns == ("CN=TalentForge Upload, O=TalentForge, C=RU",)
    assert (printed.algorithms, printed.sizes) == (("RSA",), (4096,))


def test_unsigned_entries_follow_manifest_continuation_lines(tmp_path: Path) -> None:
    long_name = "base/assets/" + "x" * 90 + ".json"
    folded = f"Name: {long_name[:66]}\r\n {long_name[66:]}\r\nSHA-256-Digest: AA==\r\n\r\n"
    path = tmp_path / "b.aab"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n" + folded)
        archive.writestr("META-INF/UPLOAD.SF", "")
        archive.writestr("META-INF/UPLOAD.RSA", "")
        archive.writestr(long_name, "{}")
        archive.writestr("BundleConfig.pb", b"")
    with zipfile.ZipFile(path) as archive:
        assert v.unsigned_entries(archive) == (("BundleConfig.pb",), 1)


# ------------------------------------------------------------- key roles never mixed


def test_a_bundle_signed_with_the_app_signing_key_fails() -> None:
    printed = v.parse_printcert(printcert_output(digest=APP_SIGNING))
    evaluation = evaluate(signature=signature(certificate_sha256s=printed.sha256s))
    assert failed(evaluation) == {
        "upload-certificate-sha256-matches",
        "certificate-differs-from-other-role",
    }


def test_an_unexpected_key_fails_the_fingerprint() -> None:
    other = v.parse_printcert(printcert_output(digest="dd" * 32)).sha256s
    evaluation = evaluate(signature=signature(certificate_sha256s=other))
    assert failed(evaluation) == {"upload-certificate-sha256-matches"}


def test_an_unsigned_bundle_fails() -> None:
    unsigned = signature(
        verifies=False,
        unsigned_entries=("BundleConfig.pb",),
        signature_files=0,
        signer_count=0,
        certificate_dns=(),
        certificate_sha256s=(),
        key_algorithms=(),
        key_sizes=(),
    )
    assert {
        "signature-verifies",
        "all-entries-signed",
        "single-signer",
        "upload-certificate-sha256-matches",
        "not-selftest-certificate",
    } <= failed(evaluate(signature=unsigned))


def test_partially_signed_or_double_signed_bundles_fail() -> None:
    partial = signature(unsigned_entries=("base/dex/classes.dex",))
    assert failed(evaluate(signature=partial)) == {"all-entries-signed"}
    double = signature(signature_files=2, signer_count=2)
    assert "single-signer" in failed(evaluate(signature=double))


def test_debug_certificate_fails() -> None:
    dn = ("C=US, O=Android, CN=Android Debug",)
    debug = signature(certificate_dns=dn, certificate_sha256s=(DEBUG,))
    assert {"not-debug-certificate-dn", "not-local-debug-certificate"} <= failed(
        evaluate(signature=debug, expected_upload_cert_sha256=DEBUG)
    )


def test_a_small_or_non_rsa_key_fails() -> None:
    assert failed(evaluate(signature=signature(key_sizes=(1024,)))) == {"rsa-key-size"}
    assert failed(evaluate(signature=signature(key_algorithms=("EC",)))) == {"rsa-key-size"}
    assert failed(evaluate(signature=signature(key_sizes=(2048,)))) == set()


SELFTEST_DN = ("CN=TalentForge signing self-test - temporary - upload test key",)


def test_a_selftest_key_is_never_a_release_and_selftest_never_passes_as_release() -> None:
    marked = signature(certificate_dns=SELFTEST_DN)
    assert failed(evaluate(signature=marked)) == {"not-selftest-certificate"}
    selftest = evaluate(signature=marked, selftest=True)
    assert failed(selftest) == set()
    assert selftest.result(selftest=True) == "SELFTEST-PASS"
    assert failed(evaluate(selftest=True)) == {"selftest-certificate"}


# ------------------------------------------------------------------ release metadata


def manifest_with(**fields: Any) -> Any:
    return dataclasses.replace(v.parse_bundle_manifest(REAL_MANIFEST), **fields)


def test_package_and_versions_must_match() -> None:
    other = manifest_with(application_id="io.github.alexeykravtsov95.other")
    assert failed(evaluate(manifest=other)) == {"application-id"}
    assert failed(evaluate(expected_version_code=2)) == {"version-code"}
    assert failed(evaluate(expected_version_name="1.0.0")) == {"version-name"}
    unread = manifest_with(version_code=None, version_name=None)
    assert {"version-code", "version-name"} <= failed(
        evaluate(manifest=unread, expected_version_code=None, expected_version_name=None)
    )


def test_target_sdk_below_the_play_minimum_fails() -> None:
    assert failed(evaluate(manifest=manifest_with(target_sdk=35))) == {"target-sdk"}
    assert failed(evaluate(manifest=manifest_with(target_sdk=None))) == {"target-sdk"}


def test_debuggable_or_test_only_fails() -> None:
    assert failed(evaluate(manifest=manifest_with(debuggable=True))) == {"not-debuggable"}
    assert failed(evaluate(manifest=manifest_with(test_only=True))) == {"not-test-only"}


def test_internet_needs_an_explicit_allowance() -> None:
    internet = "android.permission.INTERNET"
    manifest = manifest_with(permissions=(OWN_PERMISSION, internet))
    assert failed(evaluate(manifest=manifest)) == {"permissions-allowed"}
    assert failed(evaluate(manifest=manifest, allowed_permissions=(internet,))) == set()


# ------------------------------------------------------------ 64-bit and 16 KB pages


def test_32_bit_without_64_bit_fails() -> None:
    assert {"abi-arm64-v8a", "abi-64-bit-coverage", "native-16kb-page-size"} <= failed(
        evaluate(libraries=libraries("armeabi-v7a", "x86"))
    )
    assert failed(evaluate(libraries=libraries("arm64-v8a", "x86"))) == {"abi-64-bit-coverage"}


def test_4kb_aligned_64_bit_library_fails() -> None:
    misaligned = (
        *libraries("armeabi-v7a", "x86", "x86_64"),
        *libraries("arm64-v8a", alignment=4096),
    )
    assert failed(evaluate(libraries=misaligned)) == {"native-16kb-page-size"}


def test_bundle_without_16kb_page_alignment_fails() -> None:
    four_k = v.NativeLibraryConfig(True, "4K")
    assert failed(evaluate(config=four_k)) == {"bundle-native-page-alignment"}
    compressed = v.NativeLibraryConfig(False, "16K")
    assert failed(evaluate(config=compressed)) == {"bundle-native-page-alignment"}


# ------------------------------------------------------------------------------- CLI


def fake_tools(tmp_path: Path, printcert: str) -> tuple[Path, Path]:
    bin_dir = tmp_path / "jdk-bin"
    bin_dir.mkdir()
    (bin_dir / "printcert.out").write_text(printcert, encoding="utf-8")
    jarsigner = bin_dir / "jarsigner"
    jarsigner.write_text('#!/bin/sh\necho "jar verified."\n', encoding="utf-8")
    keytool = bin_dir / "keytool"
    keytool.write_text(f'#!/bin/sh\ncat "{bin_dir / "printcert.out"}"\n', encoding="utf-8")
    for tool in (jarsigner, keytool):
        tool.chmod(0o755)
    return keytool, jarsigner


def fake_bundle(tmp_path: Path) -> Path:
    """A bundle whose every entry is listed in its JAR manifest (signature files faked)."""
    bundle = tmp_path / "TalentForge-0.1.0-1.aab"
    entries = {
        "BundleConfig.pb": REAL_CONFIG,
        "base/manifest/AndroidManifest.xml": REAL_MANIFEST,
        **{
            f"base/lib/{abi}/libandroidx.graphics.path.so": elf(wide=abi in ("arm64-v8a", "x86_64"))
            for abi in ALL_ABIS
        },
    }
    jar_manifest = "Manifest-Version: 1.0\r\n\r\n" + "".join(
        f"Name: {name}\r\nSHA-256-Digest: AA==\r\n\r\n" for name in entries
    )
    with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/MANIFEST.MF", jar_manifest)
        archive.writestr("META-INF/UPLOAD.SF", "")
        archive.writestr("META-INF/UPLOAD.RSA", "")
        for name, data in entries.items():
            archive.writestr(name, data)
    return bundle


def run_cli(tmp_path: Path, *extra: str, printcert: str | None = None) -> tuple[int, Path]:
    keytool, jarsigner = fake_tools(tmp_path, printcert or printcert_output())
    report = tmp_path / "report.json"
    code = v.main(
        [
            "--aab",
            str(fake_bundle(tmp_path)),
            "--keytool",
            str(keytool),
            "--jarsigner",
            str(jarsigner),
            "--expected-version-code",
            "1",
            "--expected-version-name",
            "0.1.0",
            "--report",
            str(report),
            *extra,
        ]
    )
    return code, report


def test_cli_pass_writes_a_report_without_secrets(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    keystore = tmp_path / "owner-upload.p12"
    monkeypatch.setenv("TALENTFORGE_PLAY_UPLOAD_STORE_FILE", str(keystore))
    monkeypatch.setenv("TALENTFORGE_PLAY_UPLOAD_STORE_PASSWORD", "hunter2-store-secret")
    monkeypatch.setenv("TALENTFORGE_PLAY_UPLOAD_CERT_SHA256", keytool_form(UPLOAD))
    monkeypatch.setenv("TALENTFORGE_RELEASE_CERT_SHA256", APP_SIGNING)

    code, report = run_cli(tmp_path)

    assert code == v.EXIT_PASS
    text = report.read_text(encoding="utf-8")
    data = json.loads(text)
    bundle = tmp_path / "TalentForge-0.1.0-1.aab"
    assert data["schema"] == "talentforge.play-bundle-report.v1"
    assert (data["result"], data["purpose"]) == ("PASS", "release")
    assert data["bundle"]["sha256"] == v.apk.file_sha256(bundle)
    assert data["signature"]["role"] == "google-play-upload-key"
    assert data["signature"]["certificateSha256"] == [UPLOAD]
    assert data["package"]["permissions"] == [OWN_PERMISSION]
    assert data["package"]["nativePageAlignment"] == "16K"
    assert {check["status"] for check in data["checks"]} == {"PASS"}
    for secret in ("hunter2", str(keystore), "TALENTFORGE_", str(tmp_path)):
        assert secret not in text


def test_cli_refuses_one_key_in_both_roles(tmp_path: Path) -> None:
    code, report = run_cli(
        tmp_path,
        "--expected-upload-cert-sha256",
        UPLOAD,
        "--app-signing-cert-sha256",
        keytool_form(UPLOAD),
    )
    assert code == v.EXIT_ERROR
    assert not report.exists()


def test_cli_needs_both_fingerprints(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("TALENTFORGE_PLAY_UPLOAD_CERT_SHA256", raising=False)
    monkeypatch.delenv("TALENTFORGE_RELEASE_CERT_SHA256", raising=False)
    assert run_cli(tmp_path, "--expected-upload-cert-sha256", UPLOAD)[0] == v.EXIT_ERROR


def test_cli_fails_a_bundle_signed_with_the_app_signing_key(tmp_path: Path) -> None:
    code, report = run_cli(
        tmp_path,
        "--expected-upload-cert-sha256",
        UPLOAD,
        "--app-signing-cert-sha256",
        APP_SIGNING,
        printcert=printcert_output(digest=APP_SIGNING),
    )
    assert code == v.EXIT_FAIL
    data = json.loads(report.read_text(encoding="utf-8"))
    assert data["result"] == "FAIL"
    failing = {check["id"] for check in data["checks"] if check["status"] == "FAIL"}
    assert failing == {"upload-certificate-sha256-matches", "certificate-differs-from-other-role"}


def test_cli_rejects_files_that_are_not_bundles(tmp_path: Path) -> None:
    keytool, jarsigner = fake_tools(tmp_path, printcert_output())
    tools = ["--keytool", str(keytool), "--jarsigner", str(jarsigner)]
    keys = ["--expected-upload-cert-sha256", UPLOAD, "--app-signing-cert-sha256", APP_SIGNING]
    apk_like = tmp_path / "app.aab"
    with zipfile.ZipFile(apk_like, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"\x03\x00")
    text_file = tmp_path / "notes.aab"
    text_file.write_text("not a zip", encoding="utf-8")
    for path in (apk_like, text_file, tmp_path / "absent.aab"):
        assert v.main(["--aab", str(path), *tools, *keys]) == v.EXIT_ERROR
