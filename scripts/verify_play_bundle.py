#!/usr/bin/env python3
"""Validate a TalentForge Android App Bundle before it is uploaded to Google Play (M5.PLAY.1).

Google Play takes an app bundle signed with the **upload key**; Play App Signing then signs
the APKs it delivers with the **app signing key**, which is the owner's RSA 4096 key that also
signs the RuStore/AppGallery APK (docs/release/google-play/signing.md). This script checks the
exact ``.aab`` to upload and writes a JSON report. Every check must pass; anything it cannot
establish is a failure, never a pass:

- the file is an app bundle (a zip with ``BundleConfig.pb`` and the base manifest);
- ``jarsigner -verify`` succeeds, every entry is covered by the signature, and there is
  exactly one signer with one certificate (``keytool -printcert -jarfile``);
- the certificate is not the Android debug certificate (DN, and the local debug keystore's
  certificate when one is given) and not a signing self-test certificate (unless ``--selftest``);
- the certificate SHA-256 equals the owner's recorded **upload** certificate fingerprint and
  differs from the **app signing** certificate fingerprint: the two keys are never confused;
- the key is RSA with at least 2048 bits (Google's minimum for an upload key);
- ``package`` is the frozen ``io.github.alexeykravtsov95.talentforge``; ``versionCode`` /
  ``versionName`` read correctly (and equal the expected values when given);
- ``targetSdkVersion`` meets Google Play's current minimum (36);
- the manifest is not debuggable and not test-only;
- it requests no permission outside the allow-list (NET-1 = B: no ``INTERNET`` unless
  production OTA is enabled and the caller allows it);
- it packages ``arm64-v8a``, and a 64-bit ABI for every 32-bit ABI it packages;
- every 64-bit native library has ELF ``LOAD`` segments aligned to at least 16 KB, and the
  bundle tells bundletool to store native libraries uncompressed at a 16 KB (or 64 KB) page
  alignment in the APKs Google Play generates.

The bundle manifest and ``BundleConfig.pb`` are protocol buffers (aapt2 ``XmlNode``,
bundletool ``BundleConfig``); they are decoded here so that no bundletool installation is
needed. The report holds public facts only: file name, size and SHA-256, package metadata,
signer DN and certificate digest. It never holds a keystore path, a password, an environment
variable or the local debug certificate's digest.

Usage::

    python3 scripts/verify_play_bundle.py --aab TalentForge-0.1.0-1.aab \\
        --expected-upload-cert-sha256 <upload fingerprint> \\
        --app-signing-cert-sha256 <app signing fingerprint> --report report.json

The fingerprints fall back to ``TALENTFORGE_PLAY_UPLOAD_CERT_SHA256`` and
``TALENTFORGE_RELEASE_CERT_SHA256``. Exit status: 0 = all checks pass, 1 = a check failed,
2 = the check could not run (usage or tool error).
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import sys
import zipfile
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from types import ModuleType
from typing import Any


def _load_apk_checks() -> ModuleType:
    """scripts/verify_release_apk.py: the checks and helpers both validators share."""
    path = Path(__file__).resolve().with_name("verify_release_apk.py")
    spec = importlib.util.spec_from_file_location("verify_release_apk", path)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {path.name}")
    module = importlib.util.module_from_spec(spec)
    sys.modules.setdefault(spec.name, module)
    spec.loader.exec_module(module)
    return module


apk: Any = _load_apk_checks()

FROZEN_APPLICATION_ID: str = apk.FROZEN_APPLICATION_ID
MIN_UPLOAD_RSA_KEY_BITS = 2048  # Google Play: "RSA 2048 bits or more" for an upload key
PLAY_MIN_TARGET_SDK = 36  # new apps and updates since 2026-08-31 (Android 16)
UPLOAD_CERT_ENV = "TALENTFORGE_PLAY_UPLOAD_CERT_SHA256"
APP_SIGNING_CERT_ENV = "TALENTFORGE_RELEASE_CERT_SHA256"
REPORT_SCHEMA = "talentforge.play-bundle-report.v1"
MANIFEST_ENTRY = "base/manifest/AndroidManifest.xml"
BUNDLE_CONFIG_ENTRY = "BundleConfig.pb"
ANDROID_NS = "http://schemas.android.com/apk/res/android"
# bundletool UncompressNativeLibraries.PageAlignment
PAGE_ALIGNMENTS = {0: "UNSPECIFIED", 1: "4K", 2: "16K", 3: "64K"}
SIXTY_FOUR_BIT_FOR = {"armeabi-v7a": "arm64-v8a", "armeabi": "arm64-v8a", "x86": "x86_64"}

EXIT_PASS: int = apk.EXIT_PASS
EXIT_FAIL: int = apk.EXIT_FAIL
EXIT_ERROR: int = apk.EXIT_ERROR
ToolError: type[Exception] = apk.ToolError

ProtoFields = list[tuple[int, int | bytes]]


# ------------------------------------------------------------------------ protobuf


class ProtoError(ValueError):
    """The bytes are not a well-formed protocol buffer message."""


def _varint(data: bytes, offset: int) -> tuple[int, int]:
    value = shift = 0
    while True:
        if offset >= len(data) or shift > 63:
            raise ProtoError("truncated varint")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7


def proto_fields(data: bytes) -> ProtoFields:
    """The (field number, value) pairs of one message: varints as int, the rest as bytes."""
    fields: ProtoFields = []
    offset = 0
    while offset < len(data):
        key, offset = _varint(data, offset)
        number, wire = key >> 3, key & 7
        if number == 0:
            raise ProtoError("field number 0")
        if wire == 0:
            value, offset = _varint(data, offset)
            fields.append((number, value))
        elif wire == 2:
            length, offset = _varint(data, offset)
            if offset + length > len(data):
                raise ProtoError("truncated field")
            fields.append((number, data[offset : offset + length]))
            offset += length
        elif wire in (1, 5):
            size = 8 if wire == 1 else 4
            if offset + size > len(data):
                raise ProtoError("truncated fixed field")
            fields.append((number, data[offset : offset + size]))
            offset += size
        else:
            raise ProtoError(f"unsupported wire type {wire}")
    return fields


def _messages(fields: ProtoFields, number: int) -> list[bytes]:
    return [value for n, value in fields if n == number and isinstance(value, bytes)]


def _text(fields: ProtoFields, number: int) -> str:
    values = _messages(fields, number)
    return values[-1].decode("utf-8") if values else ""


def _varint_field(fields: ProtoFields, number: int) -> int:
    values = [value for n, value in fields if n == number and isinstance(value, int)]
    return values[-1] if values else 0


@dataclass(frozen=True)
class XmlElement:
    namespace: str
    name: str
    attributes: tuple[tuple[str, str, str], ...]  # (namespace, name, value)
    children: tuple[XmlElement, ...]

    def attribute(self, name: str, namespace: str = ANDROID_NS) -> str | None:
        for attr_namespace, attr_name, value in self.attributes:
            if attr_namespace == namespace and attr_name == name:
                return value
        return None

    def descendants(self, name: str) -> list[XmlElement]:
        found = [child for child in self.children if child.name == name]
        for child in self.children:
            found.extend(child.descendants(name))
        return found


def _attribute_value(fields: ProtoFields) -> str:
    """aapt2 XmlAttribute: the source text (value = 3), else the compiled primitive."""
    text = _text(fields, 3)
    if text:
        return text
    for item in _messages(fields, 6):  # compiled_item: Item
        for primitive in _messages(proto_fields(item), 7):  # Item.prim: Primitive
            for number, value in proto_fields(primitive):
                if number == 8 and isinstance(value, int):  # boolean_value
                    return "true" if value else "false"
                if number in (6, 7) and isinstance(value, int):  # int decimal / hexadecimal
                    return str(value)
    return ""


def parse_xml_node(data: bytes) -> XmlElement:
    """aapt2 ``XmlNode`` (element = 1) → its element; text nodes are dropped."""
    elements = _messages(proto_fields(data), 1)
    if not elements:
        raise ProtoError("the XML node holds no element")
    fields = proto_fields(elements[-1])
    attributes = []
    for raw in _messages(fields, 4):
        attribute = proto_fields(raw)
        attributes.append((_text(attribute, 1), _text(attribute, 2), _attribute_value(attribute)))
    children = [
        parse_xml_node(raw) for raw in _messages(fields, 5) if _messages(proto_fields(raw), 1)
    ]
    return XmlElement(_text(fields, 2), _text(fields, 3), tuple(attributes), tuple(children))


@dataclass(frozen=True)
class BundleManifest:
    application_id: str | None
    version_code: int | None
    version_name: str | None
    min_sdk: int | None
    target_sdk: int | None
    permissions: tuple[str, ...]
    debuggable: bool  # true, or present with a value that is not "false"
    test_only: bool


def _flag(element: XmlElement | None, name: str) -> bool:
    value = element.attribute(name) if element is not None else None
    return value is not None and value != "false"


def _int_or_none(value: str | None) -> int | None:
    return int(value) if value is not None and value.isdigit() else None


def parse_bundle_manifest(data: bytes) -> BundleManifest:
    root = parse_xml_node(data)
    if root.name != "manifest":
        raise ProtoError(f"the root element is <{root.name}>, not <manifest>")
    sdk = next(iter(root.descendants("uses-sdk")), None)
    applications = [child for child in root.children if child.name == "application"]
    application = applications[0] if len(applications) == 1 else None
    permissions = [
        name
        for tag in ("uses-permission", "uses-permission-sdk-23")
        for element in root.descendants(tag)
        if (name := element.attribute("name"))
    ]
    return BundleManifest(
        application_id=root.attribute("package", "") or None,
        version_code=_int_or_none(root.attribute("versionCode")),
        version_name=root.attribute("versionName") or None,
        min_sdk=_int_or_none(sdk.attribute("minSdkVersion") if sdk else None),
        target_sdk=_int_or_none(sdk.attribute("targetSdkVersion") if sdk else None),
        permissions=tuple(dict.fromkeys(permissions)),
        # no single <application>: report as debuggable, so the bundle fails closed
        debuggable=application is None or _flag(application, "debuggable"),
        test_only=_flag(application, "testOnly"),
    )


@dataclass(frozen=True)
class NativeLibraryConfig:
    uncompressed: bool
    alignment: str


def parse_bundle_config(data: bytes) -> NativeLibraryConfig:
    """BundleConfig.optimizations (2) → uncompress_native_libraries (2) → enabled, alignment."""
    optimizations = _messages(proto_fields(data), 2)
    native = _messages(proto_fields(optimizations[-1]), 2) if optimizations else []
    fields = proto_fields(native[-1]) if native else []
    return NativeLibraryConfig(
        uncompressed=_varint_field(fields, 1) == 1,
        alignment=PAGE_ALIGNMENTS.get(_varint_field(fields, 2), "UNKNOWN"),
    )


# ----------------------------------------------------------------------- signature


@dataclass(frozen=True)
class BundleSignature:
    verifies: bool
    unsigned_entries: tuple[str, ...]
    signature_files: int  # META-INF/*.SF
    signer_count: int
    certificate_dns: tuple[str, ...]
    certificate_sha256s: tuple[str, ...]
    key_algorithms: tuple[str, ...]
    key_sizes: tuple[int, ...]


_KEY_RE = re.compile(r"^Subject Public Key Algorithm:\s*(\d+)-bit\s+(\w+)\s+key", re.IGNORECASE)


@dataclass(frozen=True)
class PrintedCertificates:
    signers: int
    dns: tuple[str, ...]
    sha256s: tuple[str, ...]
    algorithms: tuple[str, ...]
    sizes: tuple[int, ...]


def parse_printcert(output: str) -> PrintedCertificates:
    """``keytool -printcert -jarfile``: signers, and each signer's leaf certificate facts."""
    signers = 0
    dns: list[str] = []
    digests: list[str] = []
    algorithms: list[str] = []
    sizes: list[int] = []
    leaf = False
    for raw in output.splitlines():
        line = raw.strip()
        if re.fullmatch(r"Signer #\d+:", line):
            signers += 1
        elif re.fullmatch(r"Certificate #\d+:", line):
            leaf = line == "Certificate #1:"
        elif leaf and line.startswith("Owner:"):
            dns.append(line.split(":", 1)[1].strip())
        elif leaf and line.startswith("SHA256:"):
            digests.append(apk.normalize_sha256(line.split(":", 1)[1]))
        elif leaf and (key := _KEY_RE.match(line)):
            sizes.append(int(key.group(1)))
            algorithms.append(key.group(2).upper())
    return PrintedCertificates(signers, tuple(dns), tuple(digests), tuple(algorithms), tuple(sizes))


def _manifest_names(manifest: str) -> set[str]:
    """``Name:`` entries of a JAR manifest (continuation lines start with one space)."""
    names: set[str] = set()
    current: str | None = None
    for line in [*manifest.replace("\r\n", "\n").split("\n"), ""]:
        if line.startswith(" ") and current is not None:
            current += line[1:]
            continue
        if current is not None:
            names.add(current)
            current = None
        if line.startswith("Name: "):
            current = line[len("Name: ") :]
    return names


_SIGNATURE_FILE_RE = re.compile(r"^META-INF/[^/]+\.(SF|RSA|DSA|EC)$")


def unsigned_entries(archive: zipfile.ZipFile) -> tuple[tuple[str, ...], int]:
    """Entries the JAR manifest does not cover, and the number of ``META-INF/*.SF`` files."""
    names = archive.namelist()
    signature_files = sum(1 for name in names if re.fullmatch(r"META-INF/[^/]+\.SF", name))
    if "META-INF/MANIFEST.MF" not in names:
        return tuple(n for n in names if not n.endswith("/")), signature_files
    covered = _manifest_names(archive.read("META-INF/MANIFEST.MF").decode("utf-8"))
    missing = [
        name
        for name in names
        if not name.endswith("/")
        and name != "META-INF/MANIFEST.MF"
        and not _SIGNATURE_FILE_RE.match(name)
        and name not in covered
    ]
    return tuple(missing), signature_files


# ---------------------------------------------------------------------- evaluation


def evaluate(
    *,
    signature: BundleSignature,
    manifest: BundleManifest,
    config: NativeLibraryConfig,
    libraries: tuple[Any, ...],
    expected_upload_cert_sha256: str,
    app_signing_cert_sha256: str,
    debug_cert_sha256: str | None,
    expected_version_code: int | None,
    expected_version_name: str | None,
    allowed_permissions: tuple[str, ...] = (),
    selftest: bool = False,
) -> Any:
    result = apk.Evaluation()
    result.add(
        "signature-verifies",
        signature.verifies,
        "jarsigner -verify: jar verified" if signature.verifies else "jarsigner -verify failed",
    )
    result.add(
        "all-entries-signed",
        not signature.unsigned_entries,
        "every entry is in the signed JAR manifest"
        if not signature.unsigned_entries
        else f"{len(signature.unsigned_entries)} unsigned entries, "
        f"e.g. {signature.unsigned_entries[0]!r}",
    )
    single = (
        signature.signer_count == 1
        and signature.signature_files == 1
        and len(signature.certificate_sha256s) == 1
    )
    result.add(
        "single-signer",
        single,
        f"signers={signature.signer_count}, signature files={signature.signature_files}, "
        f"certificates={len(signature.certificate_sha256s)}",
    )
    certificate = signature.certificate_sha256s[0] if single else None
    dn = signature.certificate_dns[0] if len(signature.certificate_dns) == 1 else None
    debug_dn = dn is None or apk.is_debug_dn(dn)
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
    matches = certificate is not None and certificate == expected_upload_cert_sha256
    result.add(
        "upload-certificate-sha256-matches",
        matches,
        "certificate SHA-256 equals the expected upload-key fingerprint"
        if matches
        else "certificate SHA-256 does not equal the expected upload-key fingerprint",
    )
    rsa_ok = (
        signature.key_algorithms == ("RSA",)
        and len(signature.key_sizes) == 1
        and signature.key_sizes[0] >= MIN_UPLOAD_RSA_KEY_BITS
    )
    result.add(
        "rsa-key-size",
        rsa_ok,
        f"key {'/'.join(signature.key_algorithms) or '?'} "
        f"{'/'.join(map(str, signature.key_sizes)) or '?'} bits "
        f"(required: RSA, at least {MIN_UPLOAD_RSA_KEY_BITS})",
    )
    apk.add_certificate_role_checks(
        result,
        certificate=certificate,
        dn=dn,
        must_differ_from=(app_signing_cert_sha256,),
        selftest=selftest,
    )

    result.add(
        "application-id",
        manifest.application_id == FROZEN_APPLICATION_ID,
        f"package={manifest.application_id!r} (frozen: {FROZEN_APPLICATION_ID!r})",
    )
    version_code_ok = manifest.version_code is not None and manifest.version_code > 0
    if expected_version_code is not None:
        version_code_ok = version_code_ok and manifest.version_code == expected_version_code
    result.add(
        "version-code",
        version_code_ok,
        f"versionCode={manifest.version_code} (expected: {expected_version_code or 'any > 0'})",
    )
    version_name_ok = manifest.version_name is not None
    if expected_version_name is not None:
        version_name_ok = version_name_ok and manifest.version_name == expected_version_name
    result.add(
        "version-name",
        version_name_ok,
        f"versionName={manifest.version_name!r} (expected: {expected_version_name or 'non-empty'})",
    )
    result.add(
        "target-sdk",
        manifest.target_sdk is not None and manifest.target_sdk >= PLAY_MIN_TARGET_SDK,
        f"targetSdkVersion={manifest.target_sdk} (Google Play minimum: {PLAY_MIN_TARGET_SDK})",
    )
    result.add("not-debuggable", not manifest.debuggable, f"debuggable={manifest.debuggable}")
    result.add("not-test-only", not manifest.test_only, f"testOnly={manifest.test_only}")
    extra = apk.permission_problems(manifest.permissions, allowed_permissions)
    result.add(
        "permissions-allowed",
        not extra,
        f"requested: {list(manifest.permissions)}"
        + (f"; not allowed: {extra}" if extra else "; all allowed"),
    )

    abis = sorted({library.abi for library in libraries})
    result.add("abi-arm64-v8a", apk.REQUIRED_ABI in abis, f"base/lib ABIs: {abis}")
    missing = [abi for abi in abis if SIXTY_FOUR_BIT_FOR.get(abi, abi) not in abis]
    covered = bool(abis) and not missing
    result.add(
        "abi-64-bit-coverage",
        covered,
        "every 32-bit ABI has its 64-bit counterpart"
        if covered
        else f"no 64-bit counterpart for {missing}, or no native ABI",
    )
    problems = apk.page_size_problems(libraries, check_zip=False)
    sixty_four = [library for library in libraries if library.abi in apk.SIXTY_FOUR_BIT_ABIS]
    result.add(
        "native-16kb-page-size",
        bool(sixty_four) and not problems,
        f"{len(sixty_four)} 64-bit libraries; "
        + ("; ".join(problems) if problems else "ELF LOAD alignment and RELRO fit 16 KB pages"),
    )
    aligned = config.uncompressed and config.alignment in ("16K", "64K")
    result.add(
        "bundle-native-page-alignment",
        aligned,
        f"BundleConfig: uncompress native libraries={config.uncompressed}, "
        f"page alignment={config.alignment} (required: uncompressed, 16K or 64K)",
    )
    return result


# ------------------------------------------------------------------------------ CLI


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate a TalentForge app bundle before it is uploaded to Google Play."
    )
    parser.add_argument("--aab", required=True, help="the exact .aab file to upload")
    parser.add_argument(
        "--expected-upload-cert-sha256",
        help=f"the owner's upload-key certificate fingerprint (default: ${UPLOAD_CERT_ENV})",
    )
    parser.add_argument(
        "--app-signing-cert-sha256",
        help="the app signing key's certificate fingerprint, which the bundle must NOT carry "
        f"(default: ${APP_SIGNING_CERT_ENV})",
    )
    parser.add_argument("--expected-version-code", type=int)
    parser.add_argument("--expected-version-name")
    parser.add_argument("--keytool", default="keytool", help="keytool executable")
    parser.add_argument("--jarsigner", default="jarsigner", help="jarsigner executable")
    parser.add_argument(
        "--debug-keystore",
        help="the local Android debug keystore; the bundle certificate must differ from its",
    )
    parser.add_argument(
        "--allow-permission",
        action="append",
        default=[],
        help="a permission the bundle may request (repeatable; INTERNET only with production OTA)",
    )
    parser.add_argument(
        "--selftest",
        action="store_true",
        help="signing self-test: require a self-test key; the result is SELFTEST-PASS, not PASS",
    )
    parser.add_argument("--report", help="write the JSON report to this file")
    return parser.parse_args(argv)


def read_signature(bundle: Path, keytool: str, jarsigner: str) -> BundleSignature:
    verified = apk._run([jarsigner, "-verify", str(bundle)])
    verify_output = verified.stdout + "\n" + verified.stderr
    verifies = verified.returncode == 0 and any(
        line.strip().startswith("jar verified") for line in verify_output.splitlines()
    )
    printed = apk._run([keytool, "-printcert", "-jarfile", str(bundle)])
    if printed.returncode != 0 and "Not a signed jar file" not in printed.stdout + printed.stderr:
        raise ToolError("keytool could not read the bundle's signature")
    certificates = parse_printcert(printed.stdout)
    with zipfile.ZipFile(bundle) as archive:
        unsigned, signature_files = unsigned_entries(archive)
    return BundleSignature(
        verifies=verifies,
        unsigned_entries=unsigned,
        signature_files=signature_files,
        signer_count=certificates.signers,
        certificate_dns=tuple(dict.fromkeys(certificates.dns)),
        certificate_sha256s=tuple(dict.fromkeys(certificates.sha256s)),
        key_algorithms=tuple(dict.fromkeys(certificates.algorithms)),
        key_sizes=tuple(dict.fromkeys(certificates.sizes)),
    )


def build_report(
    *,
    bundle: Path,
    bundle_sha256: str,
    signature: BundleSignature,
    manifest: BundleManifest,
    config: NativeLibraryConfig,
    abis: list[str],
    evaluation: Any,
    selftest: bool,
) -> dict[str, object]:
    return {
        "schema": REPORT_SCHEMA,
        "generatedAt": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "result": evaluation.result(selftest=selftest),
        "purpose": "signing-selftest" if selftest else "release",
        "bundle": {
            "fileName": bundle.name,
            "sizeBytes": bundle.stat().st_size,
            "sha256": bundle_sha256,
        },
        "package": {
            "applicationId": manifest.application_id,
            "versionCode": manifest.version_code,
            "versionName": manifest.version_name,
            "minSdk": manifest.min_sdk,
            "targetSdk": manifest.target_sdk,
            "nativeCode": abis,
            "debuggable": manifest.debuggable,
            "testOnly": manifest.test_only,
            "permissions": list(manifest.permissions),
            "nativeLibrariesUncompressed": config.uncompressed,
            "nativePageAlignment": config.alignment,
        },
        "signature": {
            "role": "google-play-upload-key",
            "verifies": signature.verifies,
            "signers": signature.signer_count,
            "certificateDn": list(signature.certificate_dns),
            "certificateSha256": list(signature.certificate_sha256s),
            "keyAlgorithm": list(signature.key_algorithms),
            "keySizeBits": list(signature.key_sizes),
        },
        "checks": [
            {"id": c.id, "status": "PASS" if c.passed else "FAIL", "detail": c.detail}
            for c in evaluation.checks
        ],
    }


def run_checks(
    args: argparse.Namespace, upload: str, app_signing: str
) -> tuple[Any, dict[str, object], str]:
    """Run every check on ``args.aab``; raises ToolError when a check cannot run."""
    bundle = Path(args.aab)
    if not bundle.is_file():
        raise ToolError("the bundle file does not exist")
    if not zipfile.is_zipfile(bundle):
        raise ToolError("the file is not an app bundle (zip) archive")
    with zipfile.ZipFile(bundle) as archive:
        names = set(archive.namelist())
        if not {MANIFEST_ENTRY, BUNDLE_CONFIG_ENTRY} <= names:
            raise ToolError(f"not an app bundle: {MANIFEST_ENTRY} or {BUNDLE_CONFIG_ENTRY} missing")
        manifest = parse_bundle_manifest(archive.read(MANIFEST_ENTRY))
        config = parse_bundle_config(archive.read(BUNDLE_CONFIG_ENTRY))
    signature = read_signature(bundle, args.keytool, args.jarsigner)
    libraries = apk.native_libraries(bundle, prefix="base/lib/")
    debug_sha = None
    if args.debug_keystore and Path(args.debug_keystore).is_file():
        debug_sha = apk.debug_certificate_sha256(args.keytool, Path(args.debug_keystore))
    evaluation = evaluate(
        signature=signature,
        manifest=manifest,
        config=config,
        libraries=libraries,
        expected_upload_cert_sha256=upload,
        app_signing_cert_sha256=app_signing,
        debug_cert_sha256=debug_sha,
        expected_version_code=args.expected_version_code,
        expected_version_name=args.expected_version_name,
        allowed_permissions=tuple(args.allow_permission),
        selftest=args.selftest,
    )
    bundle_sha256: str = apk.file_sha256(bundle)
    report = build_report(
        bundle=bundle,
        bundle_sha256=bundle_sha256,
        signature=signature,
        manifest=manifest,
        config=config,
        abis=sorted({library.abi for library in libraries}),
        evaluation=evaluation,
        selftest=args.selftest,
    )
    return evaluation, report, bundle_sha256


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    upload_raw = args.expected_upload_cert_sha256 or os.environ.get(UPLOAD_CERT_ENV)
    app_signing_raw = args.app_signing_cert_sha256 or os.environ.get(APP_SIGNING_CERT_ENV)
    if not upload_raw or not app_signing_raw:
        print(
            "ERROR: both fingerprints are required: the upload key's "
            f"(--expected-upload-cert-sha256 / {UPLOAD_CERT_ENV}) and the app signing key's "
            f"(--app-signing-cert-sha256 / {APP_SIGNING_CERT_ENV})",
            file=sys.stderr,
        )
        return EXIT_ERROR
    try:
        upload = apk.normalize_sha256(upload_raw)
        app_signing = apk.normalize_sha256(app_signing_raw)
    except ValueError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return EXIT_ERROR
    if upload == app_signing:
        print(
            "ERROR: the upload key and the app signing key have the same fingerprint: Google "
            "Play needs a separate upload key (docs/release/google-play/signing.md)",
            file=sys.stderr,
        )
        return EXIT_ERROR
    try:
        evaluation, report, bundle_sha256 = run_checks(args, upload, app_signing)
    except (ToolError, ValueError, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return EXIT_ERROR

    summary = apk.format_summary(
        evaluation, Path(args.aab), bundle_sha256, selftest=args.selftest, kind="AAB"
    )
    print(summary)
    if args.report:
        Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return EXIT_PASS if evaluation.passed else EXIT_FAIL


if __name__ == "__main__":
    sys.exit(main())
