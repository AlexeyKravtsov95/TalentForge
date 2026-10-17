#!/usr/bin/env python3
"""Validate a TalentForge release APK before it is uploaded to a store (M5.RC.2b).

One owner-signed universal APK is uploaded byte-identical to RuStore and Huawei
AppGallery (docs/release/appgallery/multi-store-signing.md). This script checks the
exact file to upload and writes a JSON report. Every check must pass; anything it
cannot establish is a failure, never a pass:

- the file exists and is an APK (zip) archive;
- ``apksigner verify`` succeeds, with exactly one signer certificate;
- the certificate is not the Android debug certificate (subject DN, and the local
  debug keystore's certificate when one is given);
- the certificate SHA-256 equals the owner's recorded fingerprint;
- the key is RSA with at least 4096 bits (the multi-store strategy);
- ``applicationId`` is the frozen ``io.github.alexeykravtsov95.talentforge``;
- ``versionCode`` and ``versionName`` read correctly (and equal the expected values
  when given);
- the package is not debuggable and not test-only;
- it is a universal APK (not a split; the ``native-code`` list equals the ABIs packaged
  under ``lib/``) that packages ``arm64-v8a``.

The report holds public facts only: file name, size and SHA-256, package metadata,
signature schemes, signer DN and certificate digest. It never holds a keystore path,
a password, an environment variable or the local debug certificate's digest.

Usage::

    python3 scripts/verify_release_apk.py --apk TalentForge-0.1.0-1.apk \\
        --expected-cert-sha256 <owner fingerprint> --report report.json

``--expected-cert-sha256`` falls back to ``TALENTFORGE_RELEASE_CERT_SHA256``. The
tools come from ``--build-tools`` or the newest ``build-tools/*`` of ``ANDROID_HOME``
/ ``ANDROID_SDK_ROOT``. Exit status: 0 = all checks pass, 1 = a check failed,
2 = the check could not run (usage or tool error).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path

FROZEN_APPLICATION_ID = "io.github.alexeykravtsov95.talentforge"
REQUIRED_ABI = "arm64-v8a"
MIN_RSA_KEY_BITS = 4096
EXPECTED_CERT_ENV = "TALENTFORGE_RELEASE_CERT_SHA256"
REPORT_SCHEMA = "talentforge.release-apk-report.v1"

# The subject every Android SDK debug keystore uses (any RDN order).
DEBUG_DN_PARTS = frozenset({"C=US", "O=Android", "CN=Android Debug"})
DEBUG_KEY_ALIAS = "androiddebugkey"
DEBUG_STORE_PASSWORD = "android"  # the SDK's documented, public debug-keystore password

EXIT_PASS, EXIT_FAIL, EXIT_ERROR = 0, 1, 2


class ToolError(Exception):
    """A tool could not be run, or its output could not be understood."""


@dataclass(frozen=True)
class SignatureInfo:
    verifies: bool
    schemes: dict[str, bool]
    signer_count: int | None
    certificate_dns: tuple[str, ...]
    certificate_sha256s: tuple[str, ...]
    key_algorithms: tuple[str, ...]
    key_sizes: tuple[int, ...]
    errors: tuple[str, ...]
    warnings: tuple[str, ...]


@dataclass(frozen=True)
class Badging:
    application_id: str | None
    version_code: int | None
    version_name: str | None
    min_sdk: int | None
    target_sdk: int | None
    native_code: tuple[str, ...]
    split: str | None
    debuggable: bool
    test_only: bool


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


# ----------------------------------------------------------------------------- parsing

_SCHEME_RE = re.compile(r"^Verified using (v[0-9.]+) scheme \([^)]*\): (true|false)$")
# "Signer #1 certificate DN: …" (older apksigner / v1) or "V2 Signer: certificate DN: …"
_SIGNER_RE = re.compile(
    r"^(?:Signer #\d+|V[0-9.]+ Signer(?: #\d+)?):? "
    r"(certificate DN|certificate SHA-256 digest|key algorithm|key size \(bits\)): (.*)$"
)
_PACKAGE_ATTR_RE = re.compile(r"(\w+)='([^']*)'")


def normalize_sha256(value: str) -> str:
    """``AB:CD:…`` (keytool) or ``abcd…`` (apksigner) → 64 lower-case hex characters."""
    digest = re.sub(r"[\s:]", "", value).lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("a SHA-256 fingerprint must be 64 hexadecimal characters")
    return digest


def parse_apksigner(output: str, exit_code: int) -> SignatureInfo:
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    schemes: dict[str, bool] = {}
    dns: list[str] = []
    digests: list[str] = []
    algorithms: list[str] = []
    sizes: list[int] = []
    signer_count: int | None = None
    for line in lines:
        if scheme := _SCHEME_RE.match(line):
            schemes[scheme.group(1)] = scheme.group(2) == "true"
        elif line.startswith("Number of signers:"):
            signer_count = int(line.split(":", 1)[1].strip())
        elif signer := _SIGNER_RE.match(line):
            key, value = signer.group(1), signer.group(2).strip()
            if key == "certificate DN":
                dns.append(value)
            elif key == "certificate SHA-256 digest":
                digests.append(normalize_sha256(value))
            elif key == "key algorithm":
                algorithms.append(value)
            else:
                sizes.append(int(value))
    return SignatureInfo(
        verifies=exit_code == 0 and bool(lines) and lines[0] == "Verifies",
        schemes=schemes,
        signer_count=signer_count,
        certificate_dns=tuple(dict.fromkeys(dns)),
        certificate_sha256s=tuple(dict.fromkeys(digests)),
        key_algorithms=tuple(dict.fromkeys(algorithms)),
        key_sizes=tuple(dict.fromkeys(sizes)),
        errors=tuple(line for line in lines if line.startswith(("ERROR", "DOES NOT VERIFY"))),
        warnings=tuple(line for line in lines if line.startswith("WARNING")),
    )


def parse_badging(output: str) -> Badging:
    package: dict[str, str] = {}
    values: dict[str, str] = {}
    native_code: tuple[str, ...] = ()
    debuggable = test_only = False
    for line in output.splitlines():
        if line.startswith("package:"):
            package = dict(_PACKAGE_ATTR_RE.findall(line))
        elif line.startswith(("minSdkVersion:", "targetSdkVersion:")):
            key, _, value = line.partition(":")
            values[key] = value.strip().strip("'")
        elif line.startswith("native-code:"):
            native_code = tuple(re.findall(r"'([^']*)'", line))
        elif line.strip() == "application-debuggable":
            debuggable = True
        elif line.startswith("testOnly="):
            test_only = True
    return Badging(
        application_id=package.get("name"),
        version_code=_int_or_none(package.get("versionCode")),
        version_name=package.get("versionName") or None,
        min_sdk=_int_or_none(values.get("minSdkVersion")),
        target_sdk=_int_or_none(values.get("targetSdkVersion")),
        native_code=native_code,
        split=package.get("split"),
        debuggable=debuggable,
        test_only=test_only,
    )


def parse_keytool_sha256(output: str) -> str:
    for line in output.splitlines():
        stripped = line.strip()
        if stripped.startswith("SHA256:"):
            return normalize_sha256(stripped.split(":", 1)[1])
    raise ToolError("keytool printed no SHA256 fingerprint")


def _int_or_none(value: str | None) -> int | None:
    return int(value) if value is not None and value.isdigit() else None


def is_debug_dn(dn: str) -> bool:
    return {part.strip() for part in dn.split(",")} == DEBUG_DN_PARTS


def packaged_abis(apk: Path) -> tuple[str, ...]:
    """The ABIs that carry native libraries in the archive (``lib/<abi>/*.so``)."""
    with zipfile.ZipFile(apk) as archive:
        abis = {
            parts[1]
            for name in archive.namelist()
            if len(parts := name.split("/")) == 3 and parts[0] == "lib" and parts[2].endswith(".so")
        }
    return tuple(sorted(abis))


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


# -------------------------------------------------------------------------- evaluation


def evaluate(
    *,
    signature: SignatureInfo,
    badging: Badging,
    abis: tuple[str, ...],
    expected_cert_sha256: str,
    debug_cert_sha256: str | None,
    expected_version_code: int | None,
    expected_version_name: str | None,
) -> Evaluation:
    result = Evaluation()
    result.add(
        "signature-verifies",
        signature.verifies,
        "apksigner verify succeeded"
        if signature.verifies
        else "apksigner verify failed: " + ("; ".join(signature.errors) or "no 'Verifies' line"),
    )
    single = signature.signer_count == 1 and len(signature.certificate_sha256s) == 1
    result.add(
        "single-signer",
        single,
        f"signers={signature.signer_count}, certificates={len(signature.certificate_sha256s)}",
    )
    certificate = signature.certificate_sha256s[0] if single else None
    dn = signature.certificate_dns[0] if len(signature.certificate_dns) == 1 else None

    debug_dn = dn is None or is_debug_dn(dn)
    result.add(
        "not-debug-certificate-dn",
        not debug_dn,
        "signer DN is not the Android debug subject"
        if not debug_dn
        else "signer DN is the Android debug subject, or no single DN was read",
    )
    if debug_cert_sha256 is None:
        result.add(
            "not-local-debug-certificate",
            certificate is not None,
            "no local debug keystore given; the DN and fingerprint checks decide",
        )
    else:
        differs = certificate is not None and certificate != debug_cert_sha256
        result.add(
            "not-local-debug-certificate",
            differs,
            "certificate differs from the local debug keystore's"
            if differs
            else "certificate IS the local debug keystore's certificate, or none was read",
        )
    matches = certificate is not None and certificate == expected_cert_sha256
    result.add(
        "certificate-sha256-matches",
        matches,
        "certificate SHA-256 equals the expected fingerprint"
        if matches
        else "certificate SHA-256 does not equal the expected fingerprint",
    )
    rsa_ok = (
        signature.key_algorithms == ("RSA",)
        and len(signature.key_sizes) == 1
        and signature.key_sizes[0] >= MIN_RSA_KEY_BITS
    )
    result.add(
        "rsa-key-size",
        rsa_ok,
        f"key {'/'.join(signature.key_algorithms) or '?'} "
        f"{'/'.join(map(str, signature.key_sizes)) or '?'} bits "
        f"(required: RSA, at least {MIN_RSA_KEY_BITS})",
    )
    result.add(
        "signature-scheme-v2-or-v3",
        signature.schemes.get("v2", False) or signature.schemes.get("v3", False),
        "schemes: " + (", ".join(f"{k}={v}" for k, v in sorted(signature.schemes.items())) or "?"),
    )

    result.add(
        "application-id",
        badging.application_id == FROZEN_APPLICATION_ID,
        f"applicationId={badging.application_id!r} (frozen: {FROZEN_APPLICATION_ID!r})",
    )
    version_code_ok = badging.version_code is not None and badging.version_code > 0
    if expected_version_code is not None:
        version_code_ok = version_code_ok and badging.version_code == expected_version_code
    result.add(
        "version-code",
        version_code_ok,
        f"versionCode={badging.version_code} (expected: {expected_version_code or 'any > 0'})",
    )
    version_name_ok = badging.version_name is not None
    if expected_version_name is not None:
        version_name_ok = version_name_ok and badging.version_name == expected_version_name
    result.add(
        "version-name",
        version_name_ok,
        f"versionName={badging.version_name!r} (expected: {expected_version_name or 'non-empty'})",
    )
    result.add("not-debuggable", not badging.debuggable, f"debuggable={badging.debuggable}")
    result.add("not-test-only", not badging.test_only, f"testOnly={badging.test_only}")
    result.add(
        "universal-apk",
        badging.split is None and bool(abis) and set(abis) == set(badging.native_code),
        f"split={badging.split!r}; native-code={list(badging.native_code)}; lib/ ABIs={list(abis)}",
    )
    result.add(
        "abi-arm64-v8a",
        REQUIRED_ABI in abis and REQUIRED_ABI in badging.native_code,
        f"{REQUIRED_ABI} packaged: {REQUIRED_ABI in abis and REQUIRED_ABI in badging.native_code}",
    )
    return result


# ------------------------------------------------------------------------------- tools


def _run(command: list[str]) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(command, capture_output=True, text=True, check=False)
    except OSError as error:
        raise ToolError(f"cannot run {Path(command[0]).name}: {error.strerror}") from error


def _version_key(path: Path) -> list[int]:
    return [int(part) if part.isdigit() else -1 for part in path.name.split(".")]


def find_build_tools(explicit: str | None) -> Path:
    if explicit:
        path = Path(explicit)
        if not path.is_dir():
            raise ToolError("--build-tools is not a directory")
        return path
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(variable)
        root = Path(sdk) / "build-tools" if sdk else None
        if root is not None and root.is_dir():
            versions = sorted((p for p in root.iterdir() if p.is_dir()), key=_version_key)
            if versions:
                return versions[-1]
    raise ToolError("Android build-tools not found: pass --build-tools or set ANDROID_HOME")


def debug_certificate_sha256(keytool: str, keystore: Path) -> str:
    result = _run(
        [
            keytool,
            "-list",
            "-v",
            "-keystore",
            str(keystore),
            "-alias",
            DEBUG_KEY_ALIAS,
            "-storepass",
            DEBUG_STORE_PASSWORD,
        ]
    )
    if result.returncode != 0:
        raise ToolError("keytool could not read the debug keystore")
    return parse_keytool_sha256(result.stdout)


def tool_version(executable: str) -> str:
    result = _run([executable, "version"])
    lines = (result.stdout or result.stderr).strip().splitlines()
    return lines[0] if lines else "unknown"


# ------------------------------------------------------------------------------ report


def build_report(
    *,
    apk: Path,
    apk_sha256: str,
    signature: SignatureInfo,
    badging: Badging,
    evaluation: Evaluation,
    tool_versions: dict[str, str],
) -> dict[str, object]:
    return {
        "schema": REPORT_SCHEMA,
        "generatedAt": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "result": "PASS" if evaluation.passed else "FAIL",
        "apk": {"fileName": apk.name, "sizeBytes": apk.stat().st_size, "sha256": apk_sha256},
        "package": {
            "applicationId": badging.application_id,
            "versionCode": badging.version_code,
            "versionName": badging.version_name,
            "minSdk": badging.min_sdk,
            "targetSdk": badging.target_sdk,
            "nativeCode": list(badging.native_code),
            "split": badging.split,
            "debuggable": badging.debuggable,
            "testOnly": badging.test_only,
        },
        "signature": {
            "verifies": signature.verifies,
            "schemes": signature.schemes,
            "signers": signature.signer_count,
            "certificateDn": list(signature.certificate_dns),
            "certificateSha256": list(signature.certificate_sha256s),
            "keyAlgorithm": list(signature.key_algorithms),
            "keySizeBits": list(signature.key_sizes),
            "warnings": list(signature.warnings),
        },
        "checks": [
            {"id": c.id, "status": "PASS" if c.passed else "FAIL", "detail": c.detail}
            for c in evaluation.checks
        ],
        "tools": tool_versions,
    }


def format_summary(evaluation: Evaluation, apk: Path, apk_sha256: str) -> str:
    lines = [f"{'PASS' if c.passed else 'FAIL'}  {c.id}: {c.detail}" for c in evaluation.checks]
    lines.append(f"APK   {apk.name}  {apk.stat().st_size} bytes  sha256 {apk_sha256}")
    lines.append(f"RESULT {'PASS' if evaluation.passed else 'FAIL'}")
    return "\n".join(lines)


# -------------------------------------------------------------------------------- main


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate a TalentForge release APK before it is uploaded to a store."
    )
    parser.add_argument("--apk", required=True, help="the exact APK file to upload")
    parser.add_argument(
        "--expected-cert-sha256",
        help=f"the owner's recorded certificate fingerprint (default: ${EXPECTED_CERT_ENV})",
    )
    parser.add_argument("--expected-version-code", type=int)
    parser.add_argument("--expected-version-name")
    parser.add_argument("--build-tools", help="Android build-tools directory (apksigner, aapt2)")
    parser.add_argument("--keytool", default="keytool", help="keytool executable")
    parser.add_argument(
        "--debug-keystore",
        help="the local Android debug keystore; the APK certificate must differ from its",
    )
    parser.add_argument("--report", help="write the JSON report to this file")
    return parser.parse_args(argv)


def run_checks(
    args: argparse.Namespace, expected: str
) -> tuple[Evaluation, dict[str, object], str]:
    """Run every check on ``args.apk``; raises ToolError when a check cannot run."""
    apk = Path(args.apk)
    if not apk.is_file():
        raise ToolError("the APK file does not exist")
    if not zipfile.is_zipfile(apk):
        raise ToolError("the file is not an APK (zip) archive")
    tools = find_build_tools(args.build_tools)
    apksigner, aapt2 = str(tools / "apksigner"), str(tools / "aapt2")

    signed = _run([apksigner, "verify", "--verbose", "--print-certs", str(apk)])
    signature = parse_apksigner(signed.stdout + "\n" + signed.stderr, signed.returncode)
    dumped = _run([aapt2, "dump", "badging", str(apk)])
    if dumped.returncode != 0:
        raise ToolError("aapt2 could not read the APK")
    badging = parse_badging(dumped.stdout)

    debug_sha = None
    if args.debug_keystore and Path(args.debug_keystore).is_file():
        debug_sha = debug_certificate_sha256(args.keytool, Path(args.debug_keystore))

    evaluation = evaluate(
        signature=signature,
        badging=badging,
        abis=packaged_abis(apk),
        expected_cert_sha256=expected,
        debug_cert_sha256=debug_sha,
        expected_version_code=args.expected_version_code,
        expected_version_name=args.expected_version_name,
    )
    apk_sha256 = file_sha256(apk)
    report = build_report(
        apk=apk,
        apk_sha256=apk_sha256,
        signature=signature,
        badging=badging,
        evaluation=evaluation,
        tool_versions={
            "buildTools": tools.name,
            "apksigner": tool_version(apksigner),
            "aapt2": tool_version(aapt2),
        },
    )
    return evaluation, report, apk_sha256


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    expected_raw = args.expected_cert_sha256 or os.environ.get(EXPECTED_CERT_ENV)
    if not expected_raw:
        print(
            f"ERROR: no expected certificate fingerprint: pass --expected-cert-sha256 "
            f"or set {EXPECTED_CERT_ENV}",
            file=sys.stderr,
        )
        return EXIT_ERROR
    try:
        evaluation, report, apk_sha256 = run_checks(args, normalize_sha256(expected_raw))
    except (ToolError, ValueError, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return EXIT_ERROR

    print(format_summary(evaluation, Path(args.apk), apk_sha256))
    if args.report:
        Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return EXIT_PASS if evaluation.passed else EXIT_FAIL


if __name__ == "__main__":
    sys.exit(main())
