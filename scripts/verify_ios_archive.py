#!/usr/bin/env python3
"""Validate a TalentForge iOS ``.xcarchive`` before it is distributed (M5.IOS.1).

The App Store build is produced by ``xcodebuild archive`` from ``mobile/iosApp``. This script
checks the archive's application bundle and writes a JSON report. Every check must pass;
anything it cannot establish is a failure, never a pass:

- the archive has exactly one application, the archive's own record names it, and its
  architectures are exactly ``arm64``;
- ``CFBundleIdentifier`` is the frozen ``io.github.alexeykravtsov95.talentforge``;
  ``CFBundleShortVersionString`` / ``CFBundleVersion`` read correctly (and equal the expected
  values when given; ``CFBundleVersion`` is a positive integer);
- ``MinimumOSVersion`` is the expected deployment target (17.2), the app supports iPhone and
  iPad (``UIDeviceFamily`` 1, 2), declares ``en`` and ``ru`` and requires ``arm64``;
- the SDK (``DTSDKName``) and Xcode build (``DTXcodeBuild``) equal the expected values when
  given — so a build made with an older Xcode can never pass as one made with the required one;
- export compliance is declared (``ITSAppUsesNonExemptEncryption`` = false) and multiple
  scenes are off in the **built** plist (TF-RC1-IOS-SCENES);
- the app icon is compiled (``Assets.car`` and ``CFBundleIconName`` = ``AppIcon``);
- the main executable is a thin ``arm64`` Mach-O executable; no static library and no
  framework without an ``@rpath`` install name is embedded;
- ``PrivacyInfo.xcprivacy`` is bundled: no tracking, no tracking domains, every reason code is
  one Apple defines for its category, and every required-reason API category the executable
  imports (by symbol or Objective-C selector) is declared;
- the bundled Compose resources hold the talent datasets and the Russian strings;
- App Transport Security keeps Apple's defaults: the built ``Info.plist`` has no
  ``NSAppTransportSecurity`` dictionary at all (M5.IOS.2);
- the OTA configuration is exactly the mode the caller names (``--ota``, required, M5.IOS.2).
  Kotlin/Native keeps string literals as UTF-16LE, so every search covers UTF-8 and UTF-16LE,
  and every embedded P-256 public key (any base64 DER SPKI literal) is enumerated:

  * ``off``: no production origin and no public key at all in the executable;
  * ``on``: the production origin ``https://ota.mythweld.com`` and exactly the two pinned
    production keys ``tf-prod-2026a`` / ``tf-prod-2026b`` — identified by the SHA-256 of their
    DER SPKI, pinned here as in the Gradle build — and no other key (staging, ``test-`` or
    unknown);
  * both: no staging or development origin (the VK Cloud staging host, ``localhost``,
    loopback, the Android emulator alias), and the production keyring given is the committed
    one (its fingerprints equal the pinned ones), so a substituted keyring cannot make a
    build pass;
- ``NSPrivacyCollectedDataTypes`` is exactly the agreed App Privacy declaration
  (``DECLARED_COLLECTED_DATA``, app-privacy.md §2): Other Diagnostic Data, linked to the user,
  not used for tracking, for App Functionality only — the request logs the OTA delivery
  infrastructure may keep. The manifest is one committed file bundled in every archive, so the
  same declaration is required in both OTA modes; anything else — an empty array, ``Linked``
  false, another type or purpose — fails;
- no private key, certificate store or key file is bundled.

The report holds public facts only: file names, sizes, SHA-256 digests and plist values.
Signing is reported (``signed``) and verified only with ``--require-signed`` (M5.IOS.3): the CI
archive is built unsigned; the owner signs the archive with their own team. The signature is
then checked with the macOS tools themselves, never by the presence of files alone:

- ``codesign --verify --strict --verbose=2`` accepts the application;
- ``codesign -d --verbose=4`` names a real signing identity (Apple Development or Apple
  Distribution), a Team ID and the frozen bundle id: an ad-hoc signature (``Signature=adhoc``,
  no team) fails, as does a Team ID other than ``--expected-team-id`` when one is given;
- ``security cms -D`` decodes ``embedded.mobileprovision``: the profile is in force (created
  before and expiring after now), belongs to the same team, names exactly
  ``<team>.io.github.alexeykravtsov95.talentforge`` (no wildcard) and is not an enterprise
  profile; the signing certificate (``codesign --extract-certificates``) is one of its
  ``DeveloperCertificates``; the signature's entitlements match the profile's;
- the configuration can reach TestFlight: an App Store profile with an Apple Distribution
  identity, or the development signing Xcode gives an archive (Organizer re-signs it with
  Apple Distribution on Distribute App → App Store Connect). An Ad Hoc profile (a distribution
  identity with listed devices) and ``get-task-allow`` with a distribution identity fail.

Without ``codesign`` or ``security`` (not macOS) the check cannot run: exit 2, never a pass.

``--ipa`` (issue #90) checks the exported App Store package instead of the archive: the one
that is uploaded. It is unpacked safely (no entry may leave it, symlinks are refused, modes
are kept), must hold exactly one ``Payload/*.app`` (next to it only ``Symbols``, ``SwiftSupport`` or
``BCSymbolMaps``), and every check above runs on that application. The signature is always
verified, and ``testflight-signing`` then accepts only Apple Distribution with an App Store
profile: a development-signed archive is acceptable because Organizer re-signs it, a
development-signed export is not. The report names the package and its SHA-256.

Usage::

    python3 scripts/verify_ios_archive.py --archive TalentForge.xcarchive --ota off \\
        --expected-version 1.0.0 --expected-build 1 \\
        --expected-sdk iphoneos27.1 --expected-xcode-build 27A9275 --report report.json
    python3 scripts/verify_ios_archive.py --ipa export/iosApp.ipa --ota on \\
        --expected-team-id ABCDE12345 --expected-version 1.0.0 --expected-build 4 --report ipa.json

Exit status: 0 = all checks pass, 1 = a check failed, 2 = the check could not run.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import plistlib
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import zipfile
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any

FROZEN_BUNDLE_ID = "io.github.alexeykravtsov95.talentforge"
DEFAULT_MIN_OS = "17.2"
REQUIRED_LOCALIZATIONS = frozenset({"en", "ru"})
REPORT_SCHEMA = "talentforge.ios-archive-report.v1"
PRODUCTION_OTA_ORIGIN_HOST = "ota.mythweld.com"
PRODUCTION_OTA_ORIGIN = f"https://{PRODUCTION_OTA_ORIGIN_HOST}"
# The SHA-256 of each pinned production key's DER SPKI (the key ceremony's fingerprints; the same
# values pin the keyring in mobile/composeApp/build.gradle.kts).
PRODUCTION_KEY_SPKI_SHA256 = {
    "tf-prod-2026a": "1fac1b0d15f2a09fc3769099623fe1038e846f0033ec6e233f5111ff71239278",
    "tf-prod-2026b": "6997b3d47d74ceeb6daec62b1d94f3f3e3bc476534eefe8cd140f67578532b26",
}
OTA_MODES = ("off", "on")
# Hosts of staging and development origins: none may be compiled into a store archive.
FORBIDDEN_ORIGIN_HOSTS = re.compile(
    r"(^|\.)(localhost|vkcloud-storage\.ru)$|^127\.|^10\.0\.2\.2$|(^|[.-])staging([.-]|$)"
)
# Every P-256 public key as the app pins it: base64 of a 91-byte DER SPKI with this fixed header.
P256_SPKI = rb"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE[A-Za-z0-9+/]{86}=="
HTTPS_ORIGIN = rb"https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?"
# The App Privacy declaration agreed for the App Store (app-privacy.md §2): the technical request
# logs (IP address, time, URL, User-Agent) the OTA delivery infrastructure may keep after a
# response. App Store Connect must carry the same answers.
DECLARED_COLLECTED_DATA: list[dict[str, Any]] = [
    {
        "NSPrivacyCollectedDataType": "NSPrivacyCollectedDataTypeOtherDiagnosticData",
        "NSPrivacyCollectedDataTypeLinked": True,
        "NSPrivacyCollectedDataTypeTracking": False,
        "NSPrivacyCollectedDataTypePurposes": ["NSPrivacyCollectedDataTypePurposeAppFunctionality"],
    }
]
ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PRODUCTION_KEYRING = ROOT / "docs/ota/keys/production-keyring.json"
COMPOSE_RESOURCES = "compose-resources/composeResources"
# A file under this size is searched for private-key markers; the executable is not text.
TEXT_SCAN_LIMIT = 8 * 1024 * 1024
FORBIDDEN_SUFFIXES = frozenset({".p12", ".p8", ".pem", ".key", ".keystore", ".jks", ".pfx"})

# Signing (--require-signed): the macOS tools and what a TestFlight-ready signature looks like.
SIGNING_TOOLS = ("codesign", "security")
DEVELOPMENT_IDENTITY = re.compile(r"^(Apple Development|iPhone Developer): ")
DISTRIBUTION_IDENTITY = re.compile(r"^(Apple Distribution|iPhone Distribution): ")
TEAM_ID = re.compile(r"^[A-Z0-9]{10}$")
TOOL_TIMEOUT_SECONDS = 120

EXIT_PASS, EXIT_FAIL, EXIT_ERROR = 0, 1, 2
#: what an App Store export may hold next to Payload/ (symbols, Swift support)
IPA_TOP_LEVEL = frozenset({"Payload", "Symbols", "SwiftSupport", "BCSymbolMaps"})

# Mach-O constants (<mach-o/loader.h>, <mach-o/fat.h>, <mach-o/nlist.h>).
MH_MAGIC_64 = 0xFEEDFACF
FAT_MAGIC, FAT_MAGIC_64 = 0xCAFEBABE, 0xCAFEBABF
CPU_TYPES = {0x0100000C: "arm64", 0x01000007: "x86_64", 0x0000000C: "arm", 0x00000007: "i386"}
MH_EXECUTE, MH_DYLIB = 0x2, 0x6
LC_SYMTAB, LC_ID_DYLIB = 0x2, 0xD
N_STAB, N_TYPE, N_EXT, N_UNDF = 0xE0, 0x0E, 0x01, 0x0

# Apple's required-reason API categories: the symbols and selectors that make an executable
# use one, and the reason codes Apple defines for it ("Describing use of required reason API").
REASON_CODES: dict[str, frozenset[str]] = {
    "NSPrivacyAccessedAPICategoryFileTimestamp": frozenset(
        {"DDA9.1", "C617.1", "3B52.1", "0A2A.1"}
    ),
    "NSPrivacyAccessedAPICategorySystemBootTime": frozenset({"35F9.1", "8FFB.1", "3D61.1"}),
    "NSPrivacyAccessedAPICategoryDiskSpace": frozenset({"85F4.1", "E174.1", "7D9E.1", "B728.1"}),
    "NSPrivacyAccessedAPICategoryActiveKeyboards": frozenset({"3EC4.1", "54BD.1"}),
    "NSPrivacyAccessedAPICategoryUserDefaults": frozenset({"CA92.1", "1C8F.1", "C56D.1", "AC6B.1"}),
}
CATEGORY_SYMBOLS: dict[str, frozenset[str]] = {
    "NSPrivacyAccessedAPICategoryFileTimestamp": frozenset(
        {
            "_stat",
            "_fstat",
            "_lstat",
            "_fstatat",
            "_getattrlist",
            "_fgetattrlist",
            "_getattrlistat",
            "_getattrlistbulk",
            "_NSFileCreationDate",
            "_NSFileModificationDate",
            "_NSURLContentModificationDateKey",
            "_NSURLCreationDateKey",
        }
    ),
    "NSPrivacyAccessedAPICategorySystemBootTime": frozenset({"_mach_absolute_time"}),
    "NSPrivacyAccessedAPICategoryDiskSpace": frozenset(
        {
            "_statfs",
            "_statvfs",
            "_fstatfs",
            "_fstatvfs",
            "_NSFileSystemFreeSize",
            "_NSFileSystemSize",
            "_NSURLVolumeAvailableCapacityKey",
            "_NSURLVolumeAvailableCapacityForImportantUsageKey",
            "_NSURLVolumeAvailableCapacityForOpportunisticUsageKey",
            "_NSURLVolumeTotalCapacityKey",
        }
    ),
    "NSPrivacyAccessedAPICategoryActiveKeyboards": frozenset(),
    "NSPrivacyAccessedAPICategoryUserDefaults": frozenset(
        {"_OBJC_CLASS_$_NSUserDefaults", "_CFPreferencesCopyAppValue", "_CFPreferencesSetAppValue"}
    ),
}
CATEGORY_SELECTORS: dict[str, frozenset[str]] = {
    "NSPrivacyAccessedAPICategoryFileTimestamp": frozenset(
        {"creationDate", "modificationDate", "fileModificationDate"}
    ),
    "NSPrivacyAccessedAPICategorySystemBootTime": frozenset({"systemUptime"}),
    "NSPrivacyAccessedAPICategoryDiskSpace": frozenset(),
    "NSPrivacyAccessedAPICategoryActiveKeyboards": frozenset({"activeInputModes"}),
    "NSPrivacyAccessedAPICategoryUserDefaults": frozenset(),
}


class ToolError(Exception):
    """The archive could not be read, or a file in it could not be understood."""


@dataclass(frozen=True)
class MachO:
    """What the checks need from one Mach-O file: its slices and their imports."""

    kind: str  # "macho", "static-library" or "unknown"
    architectures: tuple[str, ...]
    file_types: tuple[int, ...]
    undefined_symbols: frozenset[str]
    install_name: str | None


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


# ----------------------------------------------------------------------------- Mach-O


def _slice(data: bytes, offset: int) -> tuple[str, int, frozenset[str], str | None]:
    """Architecture, file type, undefined external symbols and install name of one slice."""
    if len(data) < offset + 32:
        raise ToolError("truncated Mach-O header")
    magic, cputype, _sub, filetype, ncmds, _size, _flags, _res = struct.unpack_from(
        "<IiiIIIII", data, offset
    )
    if magic != MH_MAGIC_64:
        raise ToolError(f"not a 64-bit little-endian Mach-O slice (magic {magic:#x})")
    arch = CPU_TYPES.get(cputype & 0xFFFFFFFF, f"cpu-{cputype & 0xFFFFFFFF:#x}")
    cursor = offset + 32
    symbols: set[str] = set()
    install_name: str | None = None
    for _ in range(ncmds):
        if len(data) < cursor + 8:
            raise ToolError("truncated Mach-O load command")
        cmd, cmdsize = struct.unpack_from("<II", data, cursor)
        if cmdsize < 8:
            raise ToolError("malformed Mach-O load command")
        if cmd == LC_SYMTAB:
            symoff, nsyms, stroff, strsize = struct.unpack_from("<IIII", data, cursor + 8)
            strings = data[offset + stroff : offset + stroff + strsize]
            for index in range(nsyms):
                entry = offset + symoff + index * 16
                if len(data) < entry + 16:
                    raise ToolError("truncated Mach-O symbol table")
                strx, ntype, _sect, _desc, _value = struct.unpack_from("<IBBHQ", data, entry)
                is_undefined = (ntype & N_TYPE) == N_UNDF and ntype & N_EXT
                if ntype & N_STAB or not is_undefined:
                    continue
                end = strings.find(b"\0", strx)
                symbols.add(
                    strings[strx : end if end >= 0 else len(strings)].decode("utf-8", "replace")
                )
        elif cmd == LC_ID_DYLIB:
            (name_offset,) = struct.unpack_from("<I", data, cursor + 8)
            raw = data[cursor + name_offset : cursor + cmdsize]
            install_name = raw.split(b"\0", 1)[0].decode("utf-8", "replace")
        cursor += cmdsize
    return arch, filetype, frozenset(symbols), install_name


def read_macho(data: bytes) -> MachO:
    """Parse a thin or fat Mach-O file (or recognise a static library); never raises on
    unknown content — that is ``kind = "unknown"``."""
    if data.startswith(b"!<arch>\n"):
        return MachO("static-library", (), (), frozenset(), None)
    if len(data) < 8:
        return MachO("unknown", (), (), frozenset(), None)
    (big_magic,) = struct.unpack_from(">I", data, 0)
    offsets: list[int] = []
    if big_magic in (FAT_MAGIC, FAT_MAGIC_64):
        (count,) = struct.unpack_from(">I", data, 4)
        entry_size = 20 if big_magic == FAT_MAGIC else 32
        for index in range(count):
            base = 8 + index * entry_size
            if big_magic == FAT_MAGIC:
                _cpu, _sub, slice_offset, _size, _align = struct.unpack_from(">iiIII", data, base)
            else:
                _cpu, _sub, slice_offset, _size, _align, _res = struct.unpack_from(
                    ">iiQQII", data, base
                )
            offsets.append(slice_offset)
    elif struct.unpack_from("<I", data, 0)[0] == MH_MAGIC_64:
        offsets.append(0)
    else:
        return MachO("unknown", (), (), frozenset(), None)
    slices = [_slice(data, offset) for offset in offsets]
    return MachO(
        "macho",
        tuple(arch for arch, _, _, _ in slices),
        tuple(filetype for _, filetype, _, _ in slices),
        frozenset().union(*(symbols for _, _, symbols, _ in slices)),
        next((name for _, _, _, name in slices if name), None),
    )


def selectors_in(data: bytes, selectors: frozenset[str]) -> set[str]:
    """The selectors whose exact NUL-terminated name occurs in ``data`` (``__objc_methname``)."""
    return {name for name in selectors if b"\0" + name.encode() + b"\0" in data}


def contains_text(data: bytes, text: str) -> bool:
    """Whether ``text`` occurs in ``data`` as UTF-8 or as UTF-16LE (Kotlin/Native literals)."""
    return text.encode() in data or text.encode("utf-16-le") in data


# A run of at least four printable ASCII characters encoded as UTF-16LE (Kotlin/Native literals).
UTF16_RUN = re.compile(rb"(?:[\x20-\x7e]\x00){4,}")


def find_text(data: bytes, pattern: bytes) -> set[str]:
    """Every match of an ASCII regex ``pattern`` in ``data``, read as UTF-8 and as UTF-16LE."""
    regex = re.compile(pattern)
    found = {match.decode("ascii") for match in regex.findall(data)}
    for run in UTF16_RUN.findall(data):
        text = run.decode("utf-16-le").encode("ascii")
        found |= {match.decode("ascii") for match in regex.findall(text)}
    return found


# ----------------------------------------------------------------------------- reading


def load_plist(path: Path) -> dict[str, Any]:
    try:
        with path.open("rb") as handle:
            value = plistlib.load(handle)
    except (OSError, plistlib.InvalidFileException, ValueError) as error:
        raise ToolError(f"cannot read {path.name}: {error}") from error
    if not isinstance(value, dict):
        raise ToolError(f"{path.name} is not a dictionary")
    return value


def find_app(archive: Path) -> Path:
    applications = archive / "Products" / "Applications"
    if not applications.is_dir():
        raise ToolError("the archive has no Products/Applications directory")
    apps = sorted(p for p in applications.iterdir() if p.suffix == ".app" and p.is_dir())
    if len(apps) != 1:
        raise ToolError(f"expected exactly one application in the archive, found {len(apps)}")
    return apps[0]


def production_keys(keyring: Path) -> dict[str, str]:
    """``keyId`` → base64 SPKI of the production keyring file."""
    try:
        parsed = json.loads(keyring.read_text(encoding="utf-8"))
        keys = {key["keyId"]: key["publicKeySpki"] for key in parsed["keys"]}
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise ToolError(f"cannot read the production keyring {keyring}: {error}") from error
    if not keys or not all(isinstance(spki, str) and spki for spki in keys.values()):
        raise ToolError("the production keyring pins no public key")
    return keys


def production_spkis(keyring: Path) -> list[str]:
    return list(production_keys(keyring).values())


def spki_sha256(base64_spki: str) -> str | None:
    """SHA-256 (hex) of a base64 DER SPKI, or None when it is not base64."""
    try:
        return hashlib.sha256(base64.b64decode(base64_spki, validate=True)).hexdigest()
    except ValueError:
        return None


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


# ----------------------------------------------------------------------------- checks


def _required_reason_check(
    evaluation: Evaluation, manifest: dict[str, Any], executable: bytes, macho: MachO
) -> dict[str, list[str]]:
    declared: dict[str, list[str]] = {}
    problems: list[str] = []
    for entry in manifest.get("NSPrivacyAccessedAPITypes", []):
        category = entry.get("NSPrivacyAccessedAPIType") if isinstance(entry, dict) else None
        reasons = (
            entry.get("NSPrivacyAccessedAPITypeReasons", []) if isinstance(entry, dict) else []
        )
        if category not in REASON_CODES:
            problems.append(f"unknown category {category!r}")
            continue
        if not reasons or not all(reason in REASON_CODES[category] for reason in reasons):
            problems.append(f"{category}: reasons {reasons} are not Apple's codes for it")
        declared[category] = list(reasons)
    used: dict[str, list[str]] = {}
    for category in REASON_CODES:
        hits = sorted(macho.undefined_symbols & CATEGORY_SYMBOLS[category])
        hits += sorted(selectors_in(executable, CATEGORY_SELECTORS[category]))
        if hits:
            used[category] = hits
    for category, hits in used.items():
        if category not in declared:
            problems.append(f"{category} is used ({', '.join(hits)}) but not declared")
    evaluation.add(
        "privacy-required-reasons",
        not problems,
        "; ".join(problems)
        or "declared "
        + ", ".join(
            f"{c.removeprefix('NSPrivacyAccessedAPICategory')}={'/'.join(r)}"
            for c, r in declared.items()
        )
        + "; used "
        + ", ".join(
            f"{c.removeprefix('NSPrivacyAccessedAPICategory')} ({', '.join(h)})"
            for c, h in used.items()
        ),
    )
    return used


def evaluate(
    archive: Path,
    *,
    expected_version: str | None,
    expected_build: str | None,
    expected_min_os: str,
    expected_sdk: str | None,
    expected_xcode_build: str | None,
    keyring: Path,
    ota: str,
    require_signed: bool = False,
    expected_team_id: str | None = None,
    runner: Runner | None = None,
    now: dt.datetime | None = None,
) -> tuple[Evaluation, dict[str, Any]]:
    """Run every check on ``archive``; raises ToolError when a check cannot run."""
    if ota not in OTA_MODES:
        raise ToolError(f"--ota must be one of {OTA_MODES}, not {ota!r}")
    if not archive.is_dir():
        raise ToolError("the archive does not exist or is not a directory")
    record = load_plist(archive / "Info.plist")
    app = find_app(archive)
    evaluation = Evaluation()
    properties = record.get("ApplicationProperties", {})

    evaluation.add(
        "archive-record",
        properties.get("ApplicationPath") == f"Applications/{app.name}"
        and properties.get("Architectures") == ["arm64"],
        f"ApplicationPath={properties.get('ApplicationPath')!r}, "
        f"Architectures={properties.get('Architectures')!r}",
    )
    return _evaluate_app(
        evaluation,
        app,
        identity={"archive": archive.name},
        expected_version=expected_version,
        expected_build=expected_build,
        expected_min_os=expected_min_os,
        expected_sdk=expected_sdk,
        expected_xcode_build=expected_xcode_build,
        keyring=keyring,
        ota=ota,
        require_signed=require_signed,
        require_distribution=False,
        expected_team_id=expected_team_id,
        runner=runner,
        now=now,
    )


def extract_ipa(ipa: Path, into: Path) -> tuple[Path, list[str]]:
    """Unpack an exported ``.ipa`` (a zip) safely; return its one application and top entries.

    Entries that could leave ``into`` are refused (absolute paths, ``..``, any symlink, a name
    given twice); file modes are restored, so ``codesign`` sees the bundle as exported.
    """
    try:
        archive = zipfile.ZipFile(ipa)
    except (OSError, zipfile.BadZipFile) as error:
        raise ToolError(f"{ipa.name} is not a readable .ipa (zip): {error}") from error
    root = into.resolve()
    seen: set[str] = set()
    with archive:
        for entry in archive.infolist():
            parts = PurePosixPath(entry.filename).parts
            if entry.filename.startswith("/") or ".." in parts or not parts:
                raise ToolError(f"{ipa.name} has an unsafe entry {entry.filename!r}")
            target = root.joinpath(*parts)
            mode = entry.external_attr >> 16
            if stat.S_ISLNK(mode):
                # an iOS application bundle holds no symlinks (frameworks are flat); refusing
                # them outright closes every link-chain escape
                raise ToolError(f"{ipa.name} holds a symlink, refused: {entry.filename}")
            if entry.filename.rstrip("/") in seen:
                raise ToolError(f"{ipa.name} holds {entry.filename!r} twice")
            seen.add(entry.filename.rstrip("/"))
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                if not target.parent.resolve().is_relative_to(root):
                    raise ToolError(f"{ipa.name} has an unsafe entry {entry.filename!r}")
                with archive.open(entry) as source, target.open("wb") as sink:
                    shutil.copyfileobj(source, sink)
                if mode & 0o777:
                    target.chmod(mode & 0o777)
        top = sorted({PurePosixPath(name).parts[0] for name in archive.namelist()})
    payload = root / "Payload"
    apps = (
        sorted(p for p in payload.iterdir() if p.suffix == ".app" and p.is_dir())
        if payload.is_dir()
        else []
    )
    if len(apps) != 1:
        raise ToolError(f"expected exactly one application in Payload/, found {len(apps)}")
    return apps[0], top


def evaluate_ipa(
    ipa: Path,
    *,
    expected_version: str | None,
    expected_build: str | None,
    expected_min_os: str,
    expected_sdk: str | None,
    expected_xcode_build: str | None,
    keyring: Path,
    ota: str,
    expected_team_id: str | None = None,
    runner: Runner | None = None,
    now: dt.datetime | None = None,
) -> tuple[Evaluation, dict[str, Any]]:
    """Run every check on the exported App Store package: always signed, always distribution.

    A development-signed archive passes ``evaluate`` because Organizer re-signs it at upload;
    what is uploaded here is the export itself, so it must already carry Apple Distribution and
    an App Store profile.
    """
    if ota not in OTA_MODES:
        raise ToolError(f"--ota must be one of {OTA_MODES}, not {ota!r}")
    if not ipa.is_file():
        raise ToolError("the .ipa does not exist or is not a file")
    with tempfile.TemporaryDirectory() as scratch:
        app, top = extract_ipa(ipa, Path(scratch))
        evaluation = Evaluation()
        unexpected = [entry for entry in top if entry not in IPA_TOP_LEVEL]
        evaluation.add(
            "ipa-payload",
            not unexpected,
            f"Payload/{app.name}; top-level entries {top}"
            if not unexpected
            else f"unexpected top-level entries {unexpected} (allowed {sorted(IPA_TOP_LEVEL)})",
        )
        return _evaluate_app(
            evaluation,
            app,
            identity={
                "package": {
                    "kind": "ipa",
                    "fileName": ipa.name,
                    "sizeBytes": ipa.stat().st_size,
                    "sha256": file_sha256(ipa),
                }
            },
            expected_version=expected_version,
            expected_build=expected_build,
            expected_min_os=expected_min_os,
            expected_sdk=expected_sdk,
            expected_xcode_build=expected_xcode_build,
            keyring=keyring,
            ota=ota,
            require_signed=True,
            require_distribution=True,
            expected_team_id=expected_team_id,
            runner=runner,
            now=now,
        )


def _evaluate_app(
    evaluation: Evaluation,
    app: Path,
    *,
    identity: dict[str, Any],
    expected_version: str | None,
    expected_build: str | None,
    expected_min_os: str,
    expected_sdk: str | None,
    expected_xcode_build: str | None,
    keyring: Path,
    ota: str,
    require_signed: bool,
    require_distribution: bool,
    expected_team_id: str | None,
    runner: Runner | None,
    now: dt.datetime | None,
) -> tuple[Evaluation, dict[str, Any]]:
    """The checks of the application bundle itself, for an archive and an export alike."""
    info = load_plist(app / "Info.plist")
    bundle_id = info.get("CFBundleIdentifier")
    evaluation.add("bundle-id", bundle_id == FROZEN_BUNDLE_ID, f"CFBundleIdentifier={bundle_id!r}")
    version = info.get("CFBundleShortVersionString")
    build = info.get("CFBundleVersion")
    version_ok = (
        isinstance(version, str)
        and bool(version)
        and (expected_version is None or version == expected_version)
    )
    build_ok = (
        isinstance(build, str)
        and build.isdigit()
        and int(build) > 0
        and (expected_build is None or build == expected_build)
    )
    evaluation.add(
        "version",
        version_ok and build_ok,
        f"CFBundleShortVersionString={version!r} (expected {expected_version or 'any'}), "
        f"CFBundleVersion={build!r} (expected {expected_build or 'a positive integer'})",
    )
    min_os = info.get("MinimumOSVersion")
    evaluation.add(
        "minimum-os",
        min_os == expected_min_os,
        f"MinimumOSVersion={min_os!r} (expected {expected_min_os})",
    )
    families = info.get("UIDeviceFamily")
    evaluation.add(
        "device-family",
        families == [1, 2],
        f"UIDeviceFamily={families!r} (expected [1, 2]: iPhone and iPad)",
    )
    localizations = set(info.get("CFBundleLocalizations", []))
    evaluation.add(
        "localizations",
        localizations >= REQUIRED_LOCALIZATIONS,
        f"CFBundleLocalizations={sorted(localizations)} "
        f"(required {sorted(REQUIRED_LOCALIZATIONS)})",
    )
    capabilities = info.get("UIRequiredDeviceCapabilities", [])
    evaluation.add(
        "device-capabilities",
        "arm64" in capabilities,
        f"UIRequiredDeviceCapabilities={capabilities!r}",
    )
    sdk, xcode_build = info.get("DTSDKName"), info.get("DTXcodeBuild")
    evaluation.add(
        "toolchain",
        isinstance(sdk, str)
        and sdk.startswith("iphoneos")
        and (expected_sdk is None or sdk == expected_sdk)
        and (expected_xcode_build is None or xcode_build == expected_xcode_build),
        f"DTSDKName={sdk!r} (expected {expected_sdk or 'an iphoneos SDK'}), "
        f"DTXcodeBuild={xcode_build!r} (expected {expected_xcode_build or 'any'}), "
        f"DTXcode={info.get('DTXcode')!r}",
    )
    encryption = info.get("ITSAppUsesNonExemptEncryption")
    evaluation.add(
        "export-compliance",
        encryption is False,
        f"ITSAppUsesNonExemptEncryption={encryption!r} (expected false)",
    )
    scenes = info.get("UIApplicationSceneManifest", {}).get("UIApplicationSupportsMultipleScenes")
    evaluation.add(
        "single-scene",
        scenes is False,
        f"UIApplicationSupportsMultipleScenes={scenes!r} (expected false, TF-RC1-IOS-SCENES)",
    )
    ats = info.get("NSAppTransportSecurity")
    evaluation.add(
        "ats-default",
        ats is None,
        "no NSAppTransportSecurity: Apple's defaults (HTTPS only, TLS 1.2+, no exceptions)"
        if ats is None
        else f"App Transport Security is changed: NSAppTransportSecurity={ats!r}",
    )
    icon_name = info.get("CFBundleIcons", {}).get("CFBundlePrimaryIcon", {}).get("CFBundleIconName")
    has_assets = (app / "Assets.car").is_file()
    evaluation.add(
        "app-icon",
        icon_name == "AppIcon" and has_assets,
        f"CFBundleIconName={icon_name!r}, Assets.car {'present' if has_assets else 'missing'}",
    )

    executable_name = info.get("CFBundleExecutable")
    executable_path = app / str(executable_name)
    if not isinstance(executable_name, str) or not executable_path.is_file():
        raise ToolError(f"the main executable {executable_name!r} is missing")
    executable = executable_path.read_bytes()
    macho = read_macho(executable)
    evaluation.add(
        "executable",
        macho.kind == "macho"
        and macho.architectures == ("arm64",)
        and macho.file_types == (MH_EXECUTE,),
        f"kind={macho.kind}, architectures={list(macho.architectures)}, "
        f"file types={list(macho.file_types)} (expected a thin arm64 executable)",
    )

    embedded_problems: list[str] = []
    embedded: list[str] = []
    for path in sorted(app.rglob("*")):
        if not path.is_file() or path == executable_path:
            continue
        relative = path.relative_to(app).as_posix()
        if path.suffix == ".a":
            embedded_problems.append(f"{relative} is a static library")
        if (
            ".framework/" in relative
            and path.parent.suffix == ".framework"
            and path.name == path.parent.stem
        ):
            embedded.append(relative)
            framework = read_macho(path.read_bytes())
            if framework.kind != "macho" or MH_DYLIB not in framework.file_types:
                embedded_problems.append(f"{relative} is not a dynamic library ({framework.kind})")
            elif not (framework.install_name or "").startswith("@rpath/"):
                embedded_problems.append(
                    f"{relative} has install name {framework.install_name!r}, not @rpath/…"
                )
    evaluation.add(
        "embedded-code",
        not embedded_problems,
        "; ".join(embedded_problems)
        or (
            f"embedded frameworks: {embedded}"
            if embedded
            else "no embedded frameworks or libraries"
        ),
    )

    manifest_path = app / "PrivacyInfo.xcprivacy"
    used_categories: dict[str, list[str]] = {}
    if manifest_path.is_file():
        manifest = load_plist(manifest_path)
        tracking_ok = (
            manifest.get("NSPrivacyTracking") is False
            and manifest.get("NSPrivacyTrackingDomains", []) == []
        )
        evaluation.add(
            "privacy-tracking",
            tracking_ok,
            f"NSPrivacyTracking={manifest.get('NSPrivacyTracking')!r}, "
            f"NSPrivacyTrackingDomains={manifest.get('NSPrivacyTrackingDomains', [])!r}",
        )
        used_categories = _required_reason_check(evaluation, manifest, executable, macho)
        collected = manifest.get("NSPrivacyCollectedDataTypes", [])
        evaluation.add(
            "privacy-collected-data",
            collected == DECLARED_COLLECTED_DATA,
            f"NSPrivacyCollectedDataTypes={collected!r} "
            f"(expected the App Privacy declaration: {DECLARED_COLLECTED_DATA!r})",
        )
    else:
        evaluation.add(
            "privacy-manifest", False, "PrivacyInfo.xcprivacy is not bundled (TF-RC1-IOS-PRIVACY)"
        )
        collected = None

    resources = app / COMPOSE_RESOURCES
    packages = sorted(p for p in resources.iterdir() if p.is_dir()) if resources.is_dir() else []
    datasets = [p for package in packages for p in (package / "files" / "dataset").rglob("*.json")]
    russian = [package / "values-ru" for package in packages if (package / "values-ru").is_dir()]
    evaluation.add(
        "compose-resources",
        bool(datasets) and bool(russian),
        f"{len(datasets)} dataset JSON files, "
        f"values-ru {'present' if russian else 'missing'} under {COMPOSE_RESOURCES}",
    )

    ota_report = _ota_checks(evaluation, executable, keyring, ota)

    forbidden: list[str] = []
    for path in sorted(app.rglob("*")):
        if not path.is_file() or path == executable_path:
            continue
        relative = path.relative_to(app).as_posix()
        if path.suffix.lower() in FORBIDDEN_SUFFIXES:
            forbidden.append(f"{relative} (key or certificate store)")
        elif path.stat().st_size <= TEXT_SCAN_LIMIT and b"PRIVATE KEY-----" in path.read_bytes():
            forbidden.append(f"{relative} (contains a private key)")
    evaluation.add(
        "no-secrets",
        not forbidden,
        "; ".join(forbidden) or "no key files or private keys in the bundle",
    )

    signed = (app / "_CodeSignature").is_dir()
    signing: dict[str, Any] | None = None
    if require_signed:
        if runner is None:
            missing = [tool for tool in SIGNING_TOOLS if shutil.which(tool) is None]
            if missing:
                raise ToolError(
                    f"BLOCKED: {', '.join(missing)} not available — the signature can only be "
                    "verified on macOS with the Xcode command line tools"
                )
            runner = run_tool
        signing = _signing_checks(
            evaluation,
            app,
            runner=runner,
            expected_team_id=expected_team_id,
            now=now or dt.datetime.now(dt.UTC),
            require_distribution=require_distribution,
        )

    report: dict[str, Any] = {
        "schema": REPORT_SCHEMA,
        **identity,
        "application": app.name,
        "signed": signed,
        "signing": signing,
        "info": {
            key: info.get(key)
            for key in (
                "CFBundleIdentifier",
                "CFBundleShortVersionString",
                "CFBundleVersion",
                "MinimumOSVersion",
                "UIDeviceFamily",
                "CFBundleLocalizations",
                "DTSDKName",
                "DTXcode",
                "DTXcodeBuild",
                "DTPlatformVersion",
                "BuildMachineOSBuild",
            )
        },
        "executable": {
            "name": executable_name,
            "size": len(executable),
            "sha256": hashlib.sha256(executable).hexdigest(),
            "architectures": list(macho.architectures),
        },
        "ota": ota_report,
        "requiredReasonApisUsed": used_categories,
        "collectedDataTypes": collected,
        "bundleSize": sum(p.stat().st_size for p in app.rglob("*") if p.is_file()),
        "checks": [{"id": c.id, "passed": c.passed, "detail": c.detail} for c in evaluation.checks],
        "result": "PASS" if evaluation.passed else "FAIL",
    }
    return evaluation, report


@dataclass(frozen=True)
class ToolResult:
    """What a command line tool returned."""

    returncode: int
    stdout: bytes
    stderr: str


Runner = Callable[[list[str]], ToolResult]


def run_tool(args: list[str]) -> ToolResult:
    try:
        completed = subprocess.run(
            args, capture_output=True, check=False, timeout=TOOL_TIMEOUT_SECONDS
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ToolError(f"cannot run {args[0]}: {error}") from error
    return ToolResult(
        completed.returncode, completed.stdout, completed.stderr.decode("utf-8", "replace")
    )


def _codesign_fields(text: str) -> tuple[dict[str, str], list[str]]:
    """The ``key=value`` lines of ``codesign -d --verbose=4`` and its ``Authority`` chain."""
    fields: dict[str, str] = {}
    authorities: list[str] = []
    for line in text.splitlines():
        key, sep, value = line.partition("=")
        if not sep:
            continue
        if key == "Authority":
            authorities.append(value)
        else:
            fields.setdefault(key, value)
    return fields, authorities


def _plist(data: bytes) -> dict[str, Any] | None:
    try:
        value = plistlib.loads(data)
    except (plistlib.InvalidFileException, ValueError, TypeError):
        return None
    return value if isinstance(value, dict) else None


def _as_utc(value: object) -> dt.datetime | None:
    if not isinstance(value, dt.datetime):
        return None
    return value if value.tzinfo else value.replace(tzinfo=dt.UTC)


def _signing_checks(
    evaluation: Evaluation,
    app: Path,
    *,
    runner: Runner,
    expected_team_id: str | None,
    now: dt.datetime,
    require_distribution: bool = False,
) -> dict[str, Any]:
    """The --require-signed checks: the real signature, its identity and its profile.

    ``require_distribution`` (an exported ``.ipa``): only an App Store profile with Apple
    Distribution passes ``testflight-signing``; development signing is not re-signed any more.
    """
    verify = runner(["codesign", "--verify", "--strict", "--verbose=2", str(app)])
    evaluation.add(
        "signature-valid",
        verify.returncode == 0,
        f"codesign --verify --strict: exit {verify.returncode}: "
        + " / ".join(line.strip() for line in verify.stderr.splitlines() if line.strip()),
    )

    display = runner(["codesign", "-d", "--verbose=4", str(app)])
    fields, authorities = _codesign_fields(
        display.stderr + "\n" + display.stdout.decode(errors="replace")
    )
    identity = authorities[0] if authorities else None
    team = fields.get("TeamIdentifier")
    # codesign prints "Signature=adhoc" and "CodeDirectory v=… flags=0x2(adhoc) …" for -s - signing
    adhoc = fields.get("Signature") == "adhoc" or "(adhoc)" in fields.get("CodeDirectory v", "")
    development = bool(identity and DEVELOPMENT_IDENTITY.match(identity))
    distribution = bool(identity and DISTRIBUTION_IDENTITY.match(identity))
    identity_problems = []
    if display.returncode != 0:
        identity_problems.append(f"codesign -d failed (exit {display.returncode})")
    if adhoc:
        identity_problems.append("ad-hoc signature (no certificate, no team)")
    if not (development or distribution):
        identity_problems.append(
            f"signing identity {identity!r} is not Apple Development/Distribution"
        )
    if not (team and TEAM_ID.fullmatch(team)):
        identity_problems.append(f"TeamIdentifier={team!r}")
    elif expected_team_id is not None and team != expected_team_id:
        identity_problems.append(f"Team ID {team} is not the expected {expected_team_id}")
    if fields.get("Identifier") != FROZEN_BUNDLE_ID:
        identity_problems.append(f"Identifier={fields.get('Identifier')!r}")
    evaluation.add(
        "signature-identity",
        not identity_problems,
        "; ".join(identity_problems)
        or f"{identity}, Team ID {team}, identifier {FROZEN_BUNDLE_ID}",
    )

    profile_path = app / "embedded.mobileprovision"
    profile: dict[str, Any] | None = None
    if profile_path.is_file():
        decoded = runner(["security", "cms", "-D", "-i", str(profile_path)])
        profile = _plist(decoded.stdout) if decoded.returncode == 0 else None
    profile_problems = []
    entitlements: dict[str, Any] = {}
    devices = False
    if profile is None:
        profile_problems.append(
            "embedded.mobileprovision is missing or not a CMS-signed provisioning profile"
        )
    else:
        profile_entitlements = profile.get("Entitlements")
        entitlements = profile_entitlements if isinstance(profile_entitlements, dict) else {}
        created, expires = (
            _as_utc(profile.get("CreationDate")),
            _as_utc(profile.get("ExpirationDate")),
        )
        if not (created and expires and created <= now < expires):
            profile_problems.append(f"not in force now (created {created}, expires {expires})")
        teams = profile.get("TeamIdentifier") or []
        if team not in teams:
            profile_problems.append(f"profile teams {teams} do not include the signature's {team}")
        if entitlements.get("application-identifier") != f"{team}.{FROZEN_BUNDLE_ID}":
            profile_problems.append(
                f"application-identifier {entitlements.get('application-identifier')!r} is not "
                f"{team}.{FROZEN_BUNDLE_ID}"
            )
        if profile.get("ProvisionsAllDevices") is True:
            profile_problems.append("enterprise (ProvisionsAllDevices) profile")
        devices = bool(profile.get("ProvisionedDevices"))
    evaluation.add(
        "provisioning-profile",
        not profile_problems,
        "; ".join(profile_problems)
        or f"{profile.get('Name') if profile else None!r}, team {team}, expires "
        f"{_as_utc(profile.get('ExpirationDate')) if profile else None}",
    )

    with tempfile.TemporaryDirectory() as scratch:
        prefix = Path(scratch) / "cert"
        extracted = runner(["codesign", "-d", f"--extract-certificates={prefix}", str(app)])
        leaf_path = Path(f"{prefix}0")
        leaf = leaf_path.read_bytes() if extracted.returncode == 0 and leaf_path.is_file() else None
    certificates = [
        c for c in (profile or {}).get("DeveloperCertificates", []) if isinstance(c, bytes)
    ]
    evaluation.add(
        "profile-certificate",
        leaf is not None and leaf in certificates,
        "the signing certificate is one of the profile's DeveloperCertificates"
        if leaf is not None and leaf in certificates
        else "the signing certificate is missing or not in the profile",
    )

    signed_entitlements = (
        _plist(runner(["codesign", "-d", "--entitlements", "-", "--xml", str(app)]).stdout) or {}
    )
    entitlement_problems = [
        f"{key}: signature {signed_entitlements.get(key)!r}, profile {entitlements.get(key)!r}"
        for key in (
            "application-identifier",
            "com.apple.developer.team-identifier",
            "get-task-allow",
        )
        if signed_entitlements.get(key) != entitlements.get(key)
    ]
    get_task_allow = signed_entitlements.get("get-task-allow") is True
    if distribution and devices:
        kind, kind_ok = "Ad Hoc (distribution identity, listed devices): not for TestFlight", False
    elif distribution and get_task_allow:
        kind, kind_ok = "distribution identity with get-task-allow: not for TestFlight", False
    elif distribution:
        kind, kind_ok = "App Store (Apple Distribution, no device list)", True
    elif development and require_distribution:
        kind, kind_ok = (
            "development-signed export: the uploaded package must be signed with Apple "
            "Distribution and an App Store profile",
            False,
        )
    elif development:
        kind, kind_ok = (
            "development-signed archive: Organizer re-signs it with Apple Distribution on "
            "Distribute App → App Store Connect",
            True,
        )
    else:
        kind, kind_ok = "no usable signing identity", False
    evaluation.add(
        "testflight-signing",
        kind_ok and not entitlement_problems,
        "; ".join([kind, *entitlement_problems]),
    )
    return {
        "identity": identity,
        "teamId": team,
        "adhoc": adhoc,
        "profileName": profile.get("Name") if profile else None,
        "profileExpires": str(_as_utc(profile.get("ExpirationDate"))) if profile else None,
        "profileHasDevices": devices,
        "configuration": kind,
    }


def _ota_checks(
    evaluation: Evaluation, executable: bytes, keyring: Path, ota: str
) -> dict[str, Any]:
    """The OTA mode checks (M5.IOS.2): what the executable pins against what ``ota`` requires."""
    keys = production_keys(keyring)
    fingerprints = {key_id: spki_sha256(spki) for key_id, spki in keys.items()}
    evaluation.add(
        "ota-keyring",
        fingerprints == PRODUCTION_KEY_SPKI_SHA256,
        f"the production keyring given is the committed one: {sorted(keys)}"
        if fingerprints == PRODUCTION_KEY_SPKI_SHA256
        else f"the production keyring given is not the pinned one: {fingerprints} "
        f"(expected {PRODUCTION_KEY_SPKI_SHA256})",
    )
    embedded = {spki: spki_sha256(spki) for spki in find_text(executable, P256_SPKI)}
    by_fingerprint = {sha: key_id for key_id, sha in PRODUCTION_KEY_SPKI_SHA256.items()}
    embedded_ids = sorted(
        by_fingerprint.get(sha or "", f"unknown:{sha}") for sha in embedded.values()
    )
    origins = sorted(find_text(executable, HTTPS_ORIGIN))
    has_origin = PRODUCTION_OTA_ORIGIN in origins or contains_text(
        executable, PRODUCTION_OTA_ORIGIN_HOST
    )
    forbidden = [
        o for o in origins if FORBIDDEN_ORIGIN_HOSTS.search(o.split("//", 1)[1].split(":")[0])
    ]
    evaluation.add(
        "ota-no-staging-origin",
        not forbidden,
        f"staging or development origins compiled in: {forbidden}"
        if forbidden
        else "no staging, loopback or emulator origin in the executable",
    )
    if ota == "off":
        passed = not embedded and not has_origin
        evaluation.add(
            "ota-off",
            passed,
            "no production OTA origin and no public key in the executable"
            if passed
            else f"OTA compiled in: production origin={has_origin}, keys={embedded_ids}",
        )
    else:
        expected_ids = sorted(PRODUCTION_KEY_SPKI_SHA256)
        ids_named = all(contains_text(executable, key_id) for key_id in expected_ids)
        passed = PRODUCTION_OTA_ORIGIN in origins and embedded_ids == expected_ids and ids_named
        evaluation.add(
            "ota-on",
            passed,
            f"origin {PRODUCTION_OTA_ORIGIN} and exactly the pinned keys {expected_ids} "
            "(SPKI SHA-256 verified)"
            if passed
            else f"expected origin {PRODUCTION_OTA_ORIGIN} and exactly {expected_ids}: "
            f"origin {'present' if PRODUCTION_OTA_ORIGIN in origins else 'missing'}, "
            f"keys {embedded_ids}, key ids {'named' if ids_named else 'missing'}",
        )
    return {
        "mode": ota,
        "productionOrigin": has_origin,
        "embeddedKeys": embedded_ids,
        "embeddedKeySpkiSha256": sorted(sha or "not-base64" for sha in embedded.values()),
        "httpsOrigins": origins,
    }


def format_summary(evaluation: Evaluation, archive: Path) -> str:
    kind = "iOS package" if archive.suffix == ".ipa" else "iOS archive"
    lines = [f"{kind}: {archive.name}"]
    lines += [f"  [{'PASS' if c.passed else 'FAIL'}] {c.id}: {c.detail}" for c in evaluation.checks]
    lines.append(f"RESULT: {'PASS' if evaluation.passed else 'FAIL'}")
    return "\n".join(lines)


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0] if __doc__ else None)
    package = parser.add_mutually_exclusive_group(required=True)
    package.add_argument("--archive", help="the .xcarchive to check")
    package.add_argument(
        "--ipa",
        help="the exported App Store .ipa to check (always --require-signed, and the signature "
        "must be Apple Distribution with an App Store profile)",
    )
    parser.add_argument(
        "--ota",
        required=True,
        choices=OTA_MODES,
        help="the OTA mode the archive must have: off (no origin, no key) or on (production)",
    )
    parser.add_argument("--expected-version", help="CFBundleShortVersionString, e.g. 1.0.0")
    parser.add_argument("--expected-build", help="CFBundleVersion, e.g. 1")
    parser.add_argument(
        "--expected-min-os", default=DEFAULT_MIN_OS, help="MinimumOSVersion (default 17.2)"
    )
    parser.add_argument("--expected-sdk", help="DTSDKName, e.g. iphoneos27.1")
    parser.add_argument("--expected-xcode-build", help="DTXcodeBuild, e.g. 27A9275 (Xcode 27.1)")
    parser.add_argument(
        "--production-keyring",
        default=str(DEFAULT_PRODUCTION_KEYRING),
        help="the committed production keyring (its fingerprints must be the pinned ones)",
    )
    parser.add_argument(
        "--require-signed",
        action="store_true",
        help="verify the real signature and provisioning profile with codesign/security (macOS)",
    )
    parser.add_argument(
        "--expected-team-id",
        help="with --require-signed: the owner's 10-character Team ID (passed, never committed)",
    )
    parser.add_argument("--report", help="write the JSON report to this file")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    package = Path(args.archive or args.ipa)
    expected = {
        "expected_version": args.expected_version,
        "expected_build": args.expected_build,
        "expected_min_os": args.expected_min_os,
        "expected_sdk": args.expected_sdk,
        "expected_xcode_build": args.expected_xcode_build,
        "keyring": Path(args.production_keyring),
        "ota": args.ota,
        "expected_team_id": args.expected_team_id,
    }
    try:
        if args.ipa:
            evaluation, report = evaluate_ipa(package, **expected)
        else:
            evaluation, report = evaluate(package, require_signed=args.require_signed, **expected)
    except ToolError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return EXIT_ERROR
    print(format_summary(evaluation, package))
    if args.report:
        Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return EXIT_PASS if evaluation.passed else EXIT_FAIL


if __name__ == "__main__":
    sys.exit(main())
