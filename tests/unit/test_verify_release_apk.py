"""The release-APK validator, offline (M5.RC.2b, TF-RC1-SIGNING).

``scripts/verify_release_apk.py`` decides whether an APK may be uploaded to RuStore and
AppGallery. These tests pin its parsing of real tool output (the committed M5.GALLERY.1
``aapt2 dump badging`` and unsigned-``apksigner`` evidence, and signed ``apksigner``
output in the format of build-tools 37.0.0 with synthetic digests), every fail-closed
check, and the CLI end to end against fake ``apksigner``/``aapt2`` executables —
including that the report carries no secret. The real tools, a real temporary key and
the Gradle signing path are exercised by ``scripts/release_signing_selftest.sh``.
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

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "docs/release/appgallery/evidence/mac"

CERT = "a1" * 32  # synthetic certificate digest of an owner key
OTHER = "b2" * 32  # synthetic digest of some other key
DEBUG = "c3" * 32  # synthetic digest of a local debug keystore


def load_validator() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "verify_release_apk", ROOT / "scripts" / "verify_release_apk.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


v = load_validator()


def apksigner_output(
    *,
    dn: str = "CN=TalentForge Owner, O=TalentForge, C=RU",
    digest: str = CERT,
    bits: int = 4096,
    signers: int = 1,
) -> str:
    return "\n".join(
        [
            "Verifies",
            "Verified using v1 scheme (JAR signing): false",
            "Verified using v2 scheme (APK Signature Scheme v2): true",
            "Verified using v3 scheme (APK Signature Scheme v3): false",
            "Verified using v3.1 scheme (APK Signature Scheme v3.1): false",
            "Verified using v4 scheme (APK Signature Scheme v4): false",
            "Verified for SourceStamp: false",
            f"Number of signers: {signers}",
            f"V2 Signer: certificate DN: {dn}",
            f"V2 Signer: certificate SHA-256 digest: {digest}",
            f"V2 Signer: certificate SHA-1 digest: {'d4' * 20}",
            "V2 Signer: key algorithm: RSA",
            f"V2 Signer: key size (bits): {bits}",
            f"V2 Signer: public key SHA-256 digest: {'e5' * 32}",
        ]
    )


RELEASE_BADGING = (EVIDENCE / "40-badging-release.txt").read_text(encoding="utf-8")
DEBUG_BADGING = (EVIDENCE / "40-badging-debug.txt").read_text(encoding="utf-8")
UNSIGNED_APKSIGNER = (EVIDENCE / "42-apksigner-release.txt").read_text(encoding="utf-8")
ALL_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")


def evaluate(**overrides: Any) -> Any:
    arguments: dict[str, Any] = {
        "signature": v.parse_apksigner(apksigner_output(), 0),
        "badging": v.parse_badging(RELEASE_BADGING),
        "abis": ALL_ABIS,
        "expected_cert_sha256": CERT,
        "debug_cert_sha256": DEBUG,
        "expected_version_code": 1,
        "expected_version_name": "0.1.0",
    }
    arguments.update(overrides)
    return v.evaluate(**arguments)


def failed(evaluation: Any) -> set[str]:
    return {check.id for check in evaluation.checks if not check.passed}


def keytool_form(digest: str) -> str:
    return ":".join(digest[i : i + 2].upper() for i in range(0, 64, 2))


# --------------------------------------------------------------------------- parsing


def test_normalize_accepts_keytool_and_apksigner_forms() -> None:
    assert v.normalize_sha256(keytool_form(CERT)) == CERT
    assert v.normalize_sha256(CERT.upper()) == CERT


@pytest.mark.parametrize("bad", ["", "abc", "g" * 64, CERT[:-2], CERT + "00"])
def test_normalize_rejects_anything_but_64_hex(bad: str) -> None:
    with pytest.raises(ValueError, match="64 hexadecimal"):
        v.normalize_sha256(bad)


def test_parse_signed_apksigner_output() -> None:
    info = v.parse_apksigner(apksigner_output(), 0)
    assert info.verifies
    assert info.signer_count == 1
    assert info.certificate_sha256s == (CERT,)
    assert info.certificate_dns == ("CN=TalentForge Owner, O=TalentForge, C=RU",)
    assert info.key_algorithms == ("RSA",)
    assert info.key_sizes == (4096,)
    assert info.schemes["v2"] is True
    assert info.schemes["v1"] is False


def test_parse_older_signer_format() -> None:
    output = "\n".join(
        [
            "Verifies",
            "Verified using v2 scheme (APK Signature Scheme v2): true",
            "Number of signers: 1",
            "Signer #1 certificate DN: CN=Owner",
            f"Signer #1 certificate SHA-256 digest: {CERT}",
            "Signer #1 key algorithm: RSA",
            "Signer #1 key size (bits): 4096",
        ]
    )
    info = v.parse_apksigner(output, 0)
    assert info.certificate_sha256s == (CERT,)
    assert info.key_sizes == (4096,)


def test_unsigned_apk_never_verifies() -> None:
    # the M5.GALLERY.1 evidence of the unsigned release APK (TF-RC1-SIGNING)
    info = v.parse_apksigner(UNSIGNED_APKSIGNER, 1)
    assert not info.verifies
    assert info.certificate_sha256s == ()
    assert "ERROR: Missing META-INF/MANIFEST.MF" in info.errors


def test_a_verifies_line_with_a_failing_exit_code_does_not_verify() -> None:
    assert not v.parse_apksigner(apksigner_output(), 1).verifies


def test_parse_release_badging_evidence() -> None:
    badging = v.parse_badging(RELEASE_BADGING)
    assert badging.application_id == "io.github.alexeykravtsov95.talentforge"
    assert badging.version_code == 1
    assert badging.version_name == "0.1.0"
    assert badging.min_sdk == 26
    assert badging.target_sdk == 36
    assert badging.native_code == ALL_ABIS
    assert badging.split is None
    assert not badging.debuggable
    assert not badging.test_only


def test_parse_debug_badging_evidence_is_debuggable() -> None:
    assert v.parse_badging(DEBUG_BADGING).debuggable


def test_parse_keytool_fingerprint() -> None:
    output = (
        "Alias name: selftest\nCertificate fingerprints:\n"
        f"\t SHA1: AA:BB\n\t SHA256: {keytool_form(CERT)}\n"
    )
    assert v.parse_keytool_sha256(output) == CERT
    with pytest.raises(v.ToolError):
        v.parse_keytool_sha256("Alias name: selftest\n")


@pytest.mark.parametrize(
    "dn", ["C=US, O=Android, CN=Android Debug", "CN=Android Debug, O=Android, C=US"]
)
def test_debug_dn_in_any_order(dn: str) -> None:
    assert v.is_debug_dn(dn)


def test_owner_dn_is_not_debug() -> None:
    assert not v.is_debug_dn("CN=Android Debug Helper, O=TalentForge, C=US")


def test_packaged_abis_reads_lib_entries(tmp_path: Path) -> None:
    apk = tmp_path / "app.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"")
        archive.writestr("lib/arm64-v8a/libandroidx.graphics.path.so", b"")
        archive.writestr("lib/x86_64/libandroidx.graphics.path.so", b"")
        archive.writestr("lib/README.txt", b"")
        archive.writestr("assets/lib/x86/not-a-lib.so", b"")
    assert v.packaged_abis(apk) == ("arm64-v8a", "x86_64")


# ------------------------------------------------------------------------ evaluation


def test_a_correct_owner_signed_release_passes_every_check() -> None:
    evaluation = evaluate()
    assert failed(evaluation) == set()
    assert evaluation.passed


def test_unsigned_apk_fails() -> None:
    evaluation = evaluate(signature=v.parse_apksigner(UNSIGNED_APKSIGNER, 1))
    expected = {"signature-verifies", "single-signer", "certificate-sha256-matches"}
    assert expected <= failed(evaluation)


def test_android_debug_certificate_fails_even_when_it_is_expected() -> None:
    signature = v.parse_apksigner(apksigner_output(dn="C=US, O=Android, CN=Android Debug"), 0)
    assert failed(evaluate(signature=signature)) == {"not-debug-certificate-dn"}


def test_the_local_debug_keystore_certificate_fails() -> None:
    signature = v.parse_apksigner(apksigner_output(dn="CN=Renamed", digest=DEBUG), 0)
    evaluation = evaluate(signature=signature, expected_cert_sha256=DEBUG)
    assert failed(evaluation) == {"not-local-debug-certificate"}


def test_without_a_local_debug_keystore_the_fingerprint_still_decides() -> None:
    assert failed(evaluate(debug_cert_sha256=None)) == set()
    signature = v.parse_apksigner(apksigner_output(digest=OTHER), 0)
    assert "certificate-sha256-matches" in failed(
        evaluate(signature=signature, debug_cert_sha256=None)
    )


def test_a_different_key_fails_the_fingerprint() -> None:
    signature = v.parse_apksigner(apksigner_output(digest=OTHER), 0)
    assert failed(evaluate(signature=signature)) == {"certificate-sha256-matches"}


def test_two_signers_fail() -> None:
    output = apksigner_output(signers=2) + f"\nV2 Signer #2: certificate SHA-256 digest: {OTHER}"
    evaluation = evaluate(signature=v.parse_apksigner(output, 0))
    assert {"single-signer", "certificate-sha256-matches"} <= failed(evaluation)


def test_a_2048_bit_key_fails_the_strategy() -> None:
    signature = v.parse_apksigner(apksigner_output(bits=2048), 0)
    assert failed(evaluate(signature=signature)) == {"rsa-key-size"}


def test_a_changed_application_id_fails() -> None:
    badging = dataclasses.replace(
        v.parse_badging(RELEASE_BADGING), application_id="io.github.alexeykravtsov95.other"
    )
    assert failed(evaluate(badging=badging)) == {"application-id"}


def test_version_mismatch_fails() -> None:
    assert failed(evaluate(expected_version_code=2)) == {"version-code"}
    assert failed(evaluate(expected_version_name="1.0.0")) == {"version-name"}


def test_unreadable_versions_fail_even_without_expectations() -> None:
    badging = dataclasses.replace(
        v.parse_badging(RELEASE_BADGING), version_code=None, version_name=None
    )
    evaluation = evaluate(badging=badging, expected_version_code=None, expected_version_name=None)
    assert failed(evaluation) == {"version-code", "version-name"}


def test_debuggable_or_test_only_fails() -> None:
    assert "not-debuggable" in failed(evaluate(badging=v.parse_badging(DEBUG_BADGING)))
    test_only = dataclasses.replace(v.parse_badging(RELEASE_BADGING), test_only=True)
    assert failed(evaluate(badging=test_only)) == {"not-test-only"}


def test_a_split_apk_is_not_universal() -> None:
    badging = dataclasses.replace(v.parse_badging(RELEASE_BADGING), split="config.arm64_v8a")
    assert failed(evaluate(badging=badging)) == {"universal-apk"}


def test_missing_arm64_fails() -> None:
    badging = dataclasses.replace(
        v.parse_badging(RELEASE_BADGING), native_code=("armeabi-v7a", "x86")
    )
    assert failed(evaluate(badging=badging, abis=("armeabi-v7a", "x86"))) == {"abi-arm64-v8a"}


def test_native_code_must_match_the_packaged_libraries() -> None:
    assert failed(evaluate(abis=("arm64-v8a",))) == {"universal-apk"}


def test_no_checks_is_not_a_pass() -> None:
    assert not v.Evaluation().passed


# ------------------------------------------------------------------------------- CLI


def fake_build_tools(tmp_path: Path, apksigner: str, apksigner_exit: int = 0) -> Path:
    tools = tmp_path / "build-tools" / "37.0.0"
    tools.mkdir(parents=True)
    (tools / "apksigner.out").write_text(apksigner, encoding="utf-8")
    (tools / "aapt2.out").write_text(RELEASE_BADGING, encoding="utf-8")
    for name, version, code in (("apksigner", "0.9", apksigner_exit), ("aapt2", "2.20", 0)):
        script = tools / name
        script.write_text(
            "#!/bin/sh\n"
            f'if [ "$1" = version ]; then echo {version}; exit 0; fi\n'
            f'cat "{tools / (name + ".out")}"\n'
            f"exit {code}\n",
            encoding="utf-8",
        )
        script.chmod(0o755)
    return tools


def fake_apk(tmp_path: Path) -> Path:
    apk = tmp_path / "TalentForge-0.1.0-1.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"\x03\x00")
        for abi in ALL_ABIS:
            archive.writestr(f"lib/{abi}/libandroidx.graphics.path.so", b"\x7fELF")
    return apk


def run_cli(tmp_path: Path, *extra: str) -> tuple[int, Path]:
    tools = fake_build_tools(tmp_path, apksigner_output())
    report = tmp_path / "report.json"
    code = v.main(
        [
            "--apk",
            str(fake_apk(tmp_path)),
            "--build-tools",
            str(tools),
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
    keystore = tmp_path / "owner-release.p12"
    monkeypatch.setenv("TALENTFORGE_RELEASE_STORE_FILE", str(keystore))
    monkeypatch.setenv("TALENTFORGE_RELEASE_STORE_PASSWORD", "hunter2-store-secret")
    monkeypatch.setenv("TALENTFORGE_RELEASE_KEY_PASSWORD", "hunter2-key-secret")
    monkeypatch.setenv("TALENTFORGE_RELEASE_CERT_SHA256", keytool_form(CERT))

    code, report = run_cli(tmp_path)

    assert code == v.EXIT_PASS
    text = report.read_text(encoding="utf-8")
    data = json.loads(text)
    apk = tmp_path / "TalentForge-0.1.0-1.apk"
    assert data["schema"] == "talentforge.release-apk-report.v1"
    assert data["result"] == "PASS"
    assert data["apk"] == {
        "fileName": apk.name,
        "sizeBytes": apk.stat().st_size,
        "sha256": v.file_sha256(apk),
    }
    assert data["signature"]["certificateSha256"] == [CERT]
    assert data["package"]["applicationId"] == "io.github.alexeykravtsov95.talentforge"
    assert {check["status"] for check in data["checks"]} == {"PASS"}
    for secret in ("hunter2", str(keystore), "TALENTFORGE_RELEASE", str(tmp_path)):
        assert secret not in text


def test_cli_fails_on_a_different_certificate(tmp_path: Path) -> None:
    code, report = run_cli(tmp_path, "--expected-cert-sha256", OTHER)
    assert code == v.EXIT_FAIL
    data = json.loads(report.read_text(encoding="utf-8"))
    assert data["result"] == "FAIL"
    failing = {check["id"] for check in data["checks"] if check["status"] == "FAIL"}
    assert failing == {"certificate-sha256-matches"}


def test_cli_fails_on_an_unverifiable_apk(tmp_path: Path) -> None:
    tools = fake_build_tools(tmp_path, UNSIGNED_APKSIGNER, apksigner_exit=1)
    arguments = ["--apk", str(fake_apk(tmp_path)), "--build-tools", str(tools)]
    assert v.main([*arguments, "--expected-cert-sha256", CERT]) == v.EXIT_FAIL


def test_cli_needs_an_expected_fingerprint(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("TALENTFORGE_RELEASE_CERT_SHA256", raising=False)
    code, report = run_cli(tmp_path)
    assert code == v.EXIT_ERROR
    assert not report.exists()


def test_cli_rejects_a_malformed_fingerprint(tmp_path: Path) -> None:
    code, report = run_cli(tmp_path, "--expected-cert-sha256", "not-a-digest")
    assert code == v.EXIT_ERROR
    assert not report.exists()


def test_cli_missing_or_non_apk_file_is_an_error(tmp_path: Path) -> None:
    tools = fake_build_tools(tmp_path, apksigner_output())
    missing = ["--apk", str(tmp_path / "absent.apk"), "--build-tools", str(tools)]
    assert v.main([*missing, "--expected-cert-sha256", CERT]) == v.EXIT_ERROR
    text_file = tmp_path / "notes.apk"
    text_file.write_text("not a zip", encoding="utf-8")
    not_zip = ["--apk", str(text_file), "--build-tools", str(tools)]
    assert v.main([*not_zip, "--expected-cert-sha256", CERT]) == v.EXIT_ERROR


def test_find_build_tools_picks_the_newest_version(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    for version in ("34.0.0", "37.0.0", "36.1.0", "9.0.0"):
        (tmp_path / "build-tools" / version).mkdir(parents=True)
    monkeypatch.setenv("ANDROID_HOME", str(tmp_path))
    assert v.find_build_tools(None).name == "37.0.0"
    monkeypatch.delenv("ANDROID_HOME")
    monkeypatch.delenv("ANDROID_SDK_ROOT", raising=False)
    with pytest.raises(v.ToolError):
        v.find_build_tools(None)
