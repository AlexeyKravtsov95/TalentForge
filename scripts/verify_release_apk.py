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
  under ``lib/``) that packages ``arm64-v8a``;
- it requests no permission outside the allow-list (the app's own AndroidX
  ``DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`` plus every ``--allow-permission``; NET-1 = B);
- every native library of a 64-bit ABI is ready for 16 KB memory pages: each ELF ``LOAD``
  segment is aligned to at least 16 KB, the read-only range the loader derives from
  ``GNU_RELRO`` (rounded out to 16 KB pages) covers no writable data outside RELRO, and, when
  stored uncompressed, the library starts at a 16 KB boundary of the archive (M5.PLAY.1);
- the certificate is none of the ``--must-differ-from-cert-sha256`` fingerprints (the Google
  Play upload key, M5.PLAY.1);
- the certificate is not a signing self-test certificate (its subject names
  ``TalentForge signing self-test``); with ``--selftest`` the reverse is required and the
  result is ``SELFTEST-PASS``, never ``PASS``, so a test artifact is never a release artifact.

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
from typing import Literal

FROZEN_APPLICATION_ID = "io.github.alexeykravtsov95.talentforge"
REQUIRED_ABI = "arm64-v8a"
MIN_RSA_KEY_BITS = 4096
EXPECTED_CERT_ENV = "TALENTFORGE_RELEASE_CERT_SHA256"
REPORT_SCHEMA = "talentforge.release-apk-report.v1"
# The subject of every key made by scripts/*signing_selftest.sh; never in an owner key.
SELFTEST_DN_MARKER = "TalentForge signing self-test"
# 64-bit ABIs whose native libraries must be ready for 16 KB memory pages.
SIXTY_FOUR_BIT_ABIS = frozenset({"arm64-v8a", "x86_64"})
PAGE_16K = 16384

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
    permissions: tuple[str, ...] = ()


@dataclass(frozen=True)
class NativeLibrary:
    """One ``lib/<abi>/*.so`` entry: its ELF ``LOAD`` alignments and archive placement."""

    path: str
    abi: str
    load_alignments: tuple[int, ...]  # empty: not a readable ELF file
    stored: bool  # uncompressed in the archive
    data_offset: int  # offset of the entry's data in the archive
    relro_problem: str | None = None  # why GNU_RELRO is unsafe with 16 KB pages, or None


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

    def result(self, *, selftest: bool) -> str:
        """``PASS`` or ``FAIL``; a passing self-test artifact is ``SELFTEST-PASS``, not ``PASS``."""
        if not self.passed:
            return "FAIL"
        return "SELFTEST-PASS" if selftest else "PASS"


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
    permissions: list[str] = []
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
        elif line.startswith("uses-permission:") and (name := _PACKAGE_ATTR_RE.findall(line)):
            permissions.extend(value for key, value in name if key == "name")
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
        permissions=tuple(dict.fromkeys(permissions)),
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


def is_selftest_dn(dn: str) -> bool:
    return SELFTEST_DN_MARKER in dn


PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
PF_W = 2


@dataclass(frozen=True)
class Segment:
    type: int
    flags: int
    vaddr: int
    memsz: int
    align: int


def elf_segments(data: bytes) -> tuple[Segment, ...]:
    """The program headers of an ELF file; empty when ``data`` is no readable ELF file."""
    if len(data) < 52 or data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] not in (1, 2):
        return ()
    wide = data[4] == 2
    order: Literal["little", "big"] = "little" if data[5] == 1 else "big"

    def number(offset: int, size: int) -> int:
        return int.from_bytes(data[offset : offset + size], order)

    phoff, phentsize, phnum = (
        (number(32, 8), number(54, 2), number(56, 2))
        if wide
        else (number(28, 4), number(42, 2), number(44, 2))
    )
    if phnum == 0 or phentsize < (56 if wide else 32) or phoff + phnum * phentsize > len(data):
        return ()
    segments = []
    for index in range(phnum):
        h = phoff + index * phentsize
        if wide:  # p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align
            fields = (number(h, 4), number(h + 4, 4), number(h + 16, 8), number(h + 40, 8))
            align = number(h + 48, 8)
        else:  # p_type, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_flags, p_align
            fields = (number(h, 4), number(h + 24, 4), number(h + 8, 4), number(h + 20, 4))
            align = number(h + 28, 4)
        segments.append(Segment(fields[0], fields[1], fields[2], fields[3], align))
    return tuple(segments)


def elf_load_alignments(data: bytes) -> tuple[int, ...]:
    """The ``p_align`` of every ``PT_LOAD`` segment; empty when ``data`` is no readable ELF file."""
    return tuple(segment.align for segment in elf_segments(data) if segment.type == PT_LOAD)


def relro_page_problem(segments: tuple[Segment, ...], page: int = PAGE_16K) -> str | None:
    """Why the loader's RELRO protection would cover writable data with ``page``-sized pages.

    The loader makes ``GNU_RELRO`` read-only rounded out to whole pages. Google's page-size
    guide: an end that is not 16 KB aligned "crashes the app at runtime with a segmentation
    fault" when RELRO shares a page with later writable data; a RELRO that fills its writable
    ``LOAD`` segment, with the next writable segment on a later page, is compliant. So the rule
    is: no writable byte outside RELRO may lie inside the rounded-out range.
    """
    for relro in (s for s in segments if s.type == PT_GNU_RELRO):
        start, end = relro.vaddr, relro.vaddr + relro.memsz
        low, high = start - start % page, -(-end // page) * page
        for load in (s for s in segments if s.type == PT_LOAD and s.flags & PF_W):
            load_start, load_end = load.vaddr, load.vaddr + load.memsz
            for part_start, part_end in (
                (load_start, min(load_end, start)),
                (max(load_start, end), load_end),
            ):
                if part_start < part_end and part_start < high and part_end > low:
                    return (
                        f"GNU_RELRO ends at {end:#x}; writable data at {max(part_start, low):#x} "
                        f"would become read-only with {page // 1024} KB pages"
                    )
    return None


def native_libraries(archive_path: Path, prefix: str = "lib/") -> tuple[NativeLibrary, ...]:
    """Every ``<prefix><abi>/*.so`` entry (``lib/`` in an APK, ``base/lib/`` in a bundle)."""
    libraries = []
    with zipfile.ZipFile(archive_path) as archive, archive_path.open("rb") as raw:
        for info in archive.infolist():
            rest = info.filename.removeprefix(prefix)
            parts = rest.split("/")
            if rest == info.filename or len(parts) != 2 or not parts[1].endswith(".so"):
                continue
            raw.seek(info.header_offset + 26)
            name_length = int.from_bytes(raw.read(2), "little")
            extra_length = int.from_bytes(raw.read(2), "little")
            data = archive.read(info)
            libraries.append(
                NativeLibrary(
                    path=info.filename,
                    abi=parts[0],
                    load_alignments=elf_load_alignments(data),
                    stored=info.compress_type == zipfile.ZIP_STORED,
                    data_offset=info.header_offset + 30 + name_length + extra_length,
                    relro_problem=relro_page_problem(elf_segments(data)),
                )
            )
    return tuple(libraries)


def page_size_problems(libraries: tuple[NativeLibrary, ...], *, check_zip: bool) -> list[str]:
    """Why a 64-bit native library is not ready for 16 KB pages (empty: every one is)."""
    problems = []
    for library in libraries:
        if library.abi not in SIXTY_FOUR_BIT_ABIS:
            continue
        if not library.load_alignments:
            problems.append(f"{library.path}: not a readable ELF file")
        elif min(library.load_alignments) < PAGE_16K:
            problems.append(f"{library.path}: LOAD alignment {min(library.load_alignments)}")
        if library.relro_problem:
            problems.append(f"{library.path}: {library.relro_problem}")
        if check_zip and library.stored and library.data_offset % PAGE_16K:
            problems.append(f"{library.path}: stored at offset {library.data_offset}, not 16 KB")
    return problems


def permission_problems(requested: tuple[str, ...], allowed: tuple[str, ...]) -> list[str]:
    """The requested permissions outside the allow-list (NET-1 = B: no INTERNET by default)."""
    own = f"{FROZEN_APPLICATION_ID}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    return [name for name in requested if name != own and name not in allowed]


def add_certificate_role_checks(
    result: Evaluation,
    *,
    certificate: str | None,
    dn: str | None,
    must_differ_from: tuple[str, ...],
    selftest: bool,
) -> None:
    """The checks shared by the APK and the bundle validator: key role and test-key marker."""
    distinct = certificate is not None and certificate not in must_differ_from
    result.add(
        "certificate-differs-from-other-role",
        distinct,
        f"certificate is none of the {len(must_differ_from)} fingerprint(s) of the other key role"
        if distinct
        else "certificate IS the other key role's (app signing vs upload key), or none was read",
    )
    marked = dn is not None and is_selftest_dn(dn)
    if selftest:
        result.add(
            "selftest-certificate",
            marked,
            "self-test run: the signer is a temporary self-test key"
            if marked
            else "self-test run, but the signer is not a self-test key",
        )
    else:
        result.add(
            "not-selftest-certificate",
            dn is not None and not marked,
            "signer is not a self-test key"
            if dn is not None and not marked
            else "signer is a temporary self-test key, or no DN was read: never a release",
        )


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
    libraries: tuple[NativeLibrary, ...] | None = None,
    allowed_permissions: tuple[str, ...] = (),
    must_differ_from: tuple[str, ...] = (),
    selftest: bool = False,
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
    extra = permission_problems(badging.permissions, allowed_permissions)
    result.add(
        "permissions-allowed",
        not extra,
        f"requested: {list(badging.permissions)}"
        + (f"; not allowed: {extra}" if extra else "; all allowed"),
    )
    if libraries is not None:
        problems = page_size_problems(libraries, check_zip=True)
        sixty_four = [lib for lib in libraries if lib.abi in SIXTY_FOUR_BIT_ABIS]
        result.add(
            "native-16kb-page-size",
            bool(sixty_four) and not problems,
            f"{len(sixty_four)} 64-bit libraries; "
            + ("; ".join(problems) if problems else "ELF LOAD, RELRO and zip alignment fit 16 KB")
            if sixty_four
            else "no 64-bit native library found",
        )
    add_certificate_role_checks(
        result, certificate=certificate, dn=dn, must_differ_from=must_differ_from, selftest=selftest
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
    selftest: bool = False,
) -> dict[str, object]:
    return {
        "schema": REPORT_SCHEMA,
        "generatedAt": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "result": evaluation.result(selftest=selftest),
        "purpose": "signing-selftest" if selftest else "release",
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
            "permissions": list(badging.permissions),
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


def format_summary(
    evaluation: Evaluation, apk: Path, apk_sha256: str, *, selftest: bool = False, kind: str = "APK"
) -> str:
    lines = [f"{'PASS' if c.passed else 'FAIL'}  {c.id}: {c.detail}" for c in evaluation.checks]
    lines.append(f"{kind:<5} {apk.name}  {apk.stat().st_size} bytes  sha256 {apk_sha256}")
    lines.append(f"RESULT {evaluation.result(selftest=selftest)}")
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
    parser.add_argument(
        "--allow-permission",
        action="append",
        default=[],
        help="a permission the APK may request (repeatable; INTERNET only with production OTA)",
    )
    parser.add_argument(
        "--must-differ-from-cert-sha256",
        action="append",
        default=[],
        help="a fingerprint the signer must NOT have, e.g. the Google Play upload key (repeatable)",
    )
    parser.add_argument(
        "--selftest",
        action="store_true",
        help="signing self-test: require a self-test key; the result is SELFTEST-PASS, not PASS",
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
        libraries=native_libraries(apk),
        allowed_permissions=tuple(args.allow_permission),
        must_differ_from=tuple(normalize_sha256(d) for d in args.must_differ_from_cert_sha256),
        selftest=args.selftest,
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
        selftest=args.selftest,
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

    print(format_summary(evaluation, Path(args.apk), apk_sha256, selftest=args.selftest))
    if args.report:
        Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return EXIT_PASS if evaluation.passed else EXIT_FAIL


if __name__ == "__main__":
    sys.exit(main())
