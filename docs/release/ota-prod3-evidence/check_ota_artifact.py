"""M5.OTA.PROD.3: assert the OTA pins of one Android release artifact (APK or AAB).

Usage::

    python3 -I check_ota_artifact.py --mode on|off --artifact X.apk|X.aab \
        --generated GeneratedOtaSettings.kt --keyring production-keyring.json \
        --origin https://ota.mythweld.com \
        --fingerprint tf-prod-2026a=<sha256> --fingerprint tf-prod-2026b=<sha256>

Reads the exact string table of every ``*.dex`` in the artifact (DEX ``string_ids``), the generated
OTA settings the artifact was compiled from, and the committed keyring, and checks:

* the keyring holds exactly the fingerprinted keys, each with the pinned SPKI SHA-256;
* ``--mode on``: ``androidProduction`` is enabled with exactly the origin and exactly the keyring's
  keys; every other channel is disabled with no URL and no keys; no trust anchor. The dex holds the
  origin, exactly the two production key ids and exactly their two SPKIs; no staging or test key id;
* ``--mode off``: every channel is disabled with no URL and no keys; no trust anchor. The dex holds
  no production origin, no key id and no SPKI.

Prints one ``PASS``/``FAIL`` line per assertion; exits 1 when any fails, 2 on unusable input.
Standard library only; reads nothing but its arguments.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

KEY_ID = re.compile(r"(?:tf-prod-|tf-staging-|test-)[0-9a-z-]+")
P256_SPKI = re.compile(r"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE[A-Za-z0-9+/]+={0,2}")
CHANNEL = re.compile(
    r"val (?P<name>\w+): OtaPlatformSettings = OtaPlatformSettings\(\s*"
    r"enabled = (?P<enabled>true|false),\s*channel = OtaChannel\.\w+,\s*"
    # every key line ends with "),": the list is a run of OtaKeySetting(...) entries
    r"baseUrl = (?P<base>null|\"[^\"]*\"),\s*"
    r"keys = listOf\((?P<keys>(?:\s*OtaKeySetting\([^)]*\),)*)\s*\),\s*\)",
    re.DOTALL,
)
KEY = re.compile(r'OtaKeySetting\("([^"]*)", "([^"]*)", "([^"]*)"\)')
ANCHOR = re.compile(r"val androidStagingTrustAnchorPem: String\? = (null|\".*\")")
CHANNELS = {"androidProduction", "androidStaging", "iosProduction", "iosStaging"}


def uleb128_end(data: bytes, offset: int) -> int:
    """The offset just past one ULEB128 value."""
    while data[offset] & 0x80:
        offset += 1
    return offset + 1


def dex_strings(dex: bytes) -> set[str]:
    """Every string of the dex ``string_ids`` table (MUTF-8 read as UTF-8, bad bytes replaced)."""
    if dex[:4] != b"dex\n":
        raise ValueError("not a dex file")
    size, offset = struct.unpack_from("<II", dex, 0x38)
    strings = set()
    for i in range(size):
        (data_offset,) = struct.unpack_from("<I", dex, offset + 4 * i)
        start = uleb128_end(dex, data_offset)
        end = dex.index(b"\0", start)
        strings.add(dex[start:end].decode("utf-8", errors="replace"))
    return strings


def artifact_strings(path: Path) -> set[str]:
    """The union of the string tables of every dex in an APK or AAB."""
    with zipfile.ZipFile(path) as archive:
        names = [name for name in archive.namelist() if name.endswith(".dex")]
        if not names:
            raise ValueError(f"{path.name}: no dex")
        return set().union(*(dex_strings(archive.read(name)) for name in names))


def spki_sha256(spki: str) -> str:
    """SHA-256 (hex) of a base64 DER SPKI."""
    return hashlib.sha256(base64.b64decode(spki, validate=True)).hexdigest()


class Checks:
    """Prints each assertion and counts the failed ones."""

    def __init__(self) -> None:
        self.failures = 0

    def __call__(self, ok: bool, what: str) -> None:
        print(("PASS " if ok else "FAIL ") + what)
        self.failures += 0 if ok else 1


def check_generated(
    check: Checks,
    generated: str,
    mode: str,
    origin: str,
    expected_keys: list[tuple[str, str, str]],
    fingerprints: dict[str, str],
) -> None:
    """The generated OTA settings of an OTA ON or OTA OFF build."""
    channels = {match["name"]: match for match in CHANNEL.finditer(generated)}
    check(set(channels) == CHANNELS, f"generated channels {sorted(channels)}")
    anchor = ANCHOR.search(generated)
    check(anchor is not None and anchor[1] == "null", "generated: no staging trust anchor")
    for name, match in sorted(channels.items()):
        keys = KEY.findall(match["keys"])
        if mode == "on" and name == "androidProduction":
            check(match["enabled"] == "true", f"generated {name}: enabled")
            check(match["base"] == f'"{origin}"', f"generated {name}: baseUrl {match['base']}")
            check(
                sorted(keys) == sorted(expected_keys),
                f"generated {name}: keys {[k[0] for k in keys]} == the keyring's, SPKIs equal",
            )
            check(
                all(spki_sha256(k[2]) == fingerprints.get(k[0]) for k in keys),
                f"generated {name}: every SPKI has its pinned SHA-256",
            )
        else:
            check(
                match["enabled"] == "false" and match["base"] == "null" and not keys,
                f"generated {name}: disabled, no URL, no keys "
                f"(enabled={match['enabled']}, baseUrl={match['base']}, {len(keys)} keys)",
            )


def check_dex(
    check: Checks,
    strings: set[str],
    mode: str,
    origin: str,
    expected_keys: list[tuple[str, str, str]],
    fingerprints: dict[str, str],
) -> None:
    """The dex string table of an OTA ON or OTA OFF build."""
    key_ids = {s for s in strings if KEY_ID.fullmatch(s)}
    spkis = {s for s in strings if P256_SPKI.fullmatch(s)}
    origins = {s for s in strings if "ota.mythweld.com" in s}
    if mode == "on":
        check(origins == {origin}, f"dex: production origin strings {sorted(origins)}")
        check(
            key_ids == set(fingerprints),
            f"dex: key ids {sorted(key_ids)} == {sorted(fingerprints)} (no staging or test key)",
        )
        check(
            spkis == {k[2] for k in expected_keys},
            f"dex: {len(spkis)} P-256 SPKI string(s), exactly the keyring's",
        )
        check(
            all(spki_sha256(s) in fingerprints.values() for s in spkis),
            "dex: every SPKI has a pinned SHA-256",
        )
    else:
        check(not origins, f"dex: no production origin ({sorted(origins)})")
        check(not key_ids, f"dex: no key id ({sorted(key_ids)})")
        check(not spkis, f"dex: no SPKI ({len(spkis)})")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Assert the OTA pins of one artifact.")
    parser.add_argument("--mode", choices=("on", "off"), required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--generated", type=Path, required=True)
    parser.add_argument("--keyring", type=Path, required=True)
    parser.add_argument("--origin", required=True)
    parser.add_argument("--fingerprint", action="append", default=[], help="KEYID=SHA256")
    args = parser.parse_args(argv)
    fingerprints = dict(item.split("=", 1) for item in args.fingerprint)

    try:
        keyring = json.loads(args.keyring.read_text())["keys"]
        generated = args.generated.read_text()
        strings = artifact_strings(args.artifact)
        actual = {k["keyId"]: spki_sha256(k["publicKeySpki"]) for k in keyring}
    except (OSError, ValueError, KeyError, binascii.Error, zipfile.BadZipFile) as error:
        print(f"FAIL unusable input: {error}")
        return 2

    check = Checks()
    check(
        len(keyring) == len(fingerprints) and actual == fingerprints,
        f"keyring SPKI SHA-256 {actual} == pinned {fingerprints}",
    )
    expected_keys = [(k["keyId"], k["algorithm"], k["publicKeySpki"]) for k in keyring]
    check_generated(check, generated, args.mode, args.origin, expected_keys, fingerprints)
    check_dex(check, strings, args.mode, args.origin, expected_keys, fingerprints)
    return 1 if check.failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
