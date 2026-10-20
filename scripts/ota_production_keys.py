"""Public-key side of the production OTA signing keys (M5.OTA.PROD.1).

The two production keys (``tf-prod-<yyyy>a`` primary, ``tf-prod-<yyyy>b`` backup) are
generated and kept **offline by the owner** (``docs/ota/production-deployment.md`` §4).
This helper never generates, reads or accepts a private key - a PEM holding one is refused
before it is parsed. It only handles what may leave the offline machine:

``keyring --key ID=PUBLIC.pem --key ID=PUBLIC.pem [--out FILE]``
    The public keyring the publisher, the preflight and (in M5.OTA.PROD.2) the app pin:
    ``{"keys": [{keyId, algorithm, publicKeySpki}]}``. Requires a primary and a backup -
    two distinct P-256 keys with ``tf-prod-<yyyy><letter>`` ids - and prints each key's
    SPKI SHA-256 fingerprint to record next to it.

``ceremony-pae --out DIR``
    A key-ceremony test payload and its PAE (``ceremony.pae.bin``): sign it offline with
    each new key, then ``check-signature``. The payload is not a catalog, so its
    signature can never be accepted as one.

``check-signature --keyring FILE --key-id ID --pae FILE --signature DER``
    Verifies an offline DER signature (``openssl dgst -sha256 -sign``) over a PAE file:
    the ceremony test, or a pending catalog before ``attach-signature``.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from cryptography.exceptions import InvalidSignature  # noqa: E402
from cryptography.hazmat.primitives import hashes, serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402

from talent_data.ota.errors import OtaPublisherError  # noqa: E402
from talent_data.ota.models import CATALOG_PAYLOAD_TYPE  # noqa: E402
from talent_data.ota.signing import PRODUCTION_KEY_PREFIX, load_keyring_file  # noqa: E402
from talent_data.ota.verify import pae  # noqa: E402

KEY_ID = re.compile(r"tf-prod-[0-9]{4}[a-z]")
CEREMONY_PAYLOAD = b'{"purpose": "talentforge production OTA key ceremony test - not a catalog"}\n'


class KeyInputError(Exception):
    """A key input is not acceptable; nothing was written."""


def public_key_of(path: Path) -> ec.EllipticCurvePublicKey:
    data = path.read_bytes()
    if b"PRIVATE KEY" in data:  # refused before parsing: private keys stay offline
        raise KeyInputError(
            f"{path} holds a PRIVATE key; pass the public key only (openssl ec -pubout)"
        )
    try:
        key = serialization.load_pem_public_key(data)
    except ValueError as exc:
        raise KeyInputError(f"{path}: not a PEM public key: {exc}") from exc
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise KeyInputError(f"{path}: not an ECDSA P-256 public key")
    return key


def spki_der(key: ec.EllipticCurvePublicKey) -> bytes:
    return key.public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )


def build_keyring(keys: list[tuple[str, Path]]) -> tuple[dict[str, Any], list[str]]:
    """The keyring document and one fingerprint line per key."""
    if len(keys) < 2:
        raise KeyInputError("a production keyring pins a primary and a backup key (two --key)")
    ids = [key_id for key_id, _ in keys]
    bad = [key_id for key_id in ids if not KEY_ID.fullmatch(key_id)]
    if bad:
        raise KeyInputError(f"production key ids are {PRODUCTION_KEY_PREFIX}<yyyy><letter>: {bad}")
    if len(set(ids)) != len(ids):
        raise KeyInputError(f"duplicate key id in {ids}")
    entries: list[dict[str, str]] = []
    lines: list[str] = []
    seen: set[bytes] = set()
    for key_id, path in keys:
        der = spki_der(public_key_of(path))
        if der in seen:
            raise KeyInputError(f"{key_id}: the same public key twice; primary and backup differ")
        seen.add(der)
        entries.append(
            {
                "keyId": key_id,
                "algorithm": "ecdsa-p256-sha256",
                "publicKeySpki": base64.b64encode(der).decode("ascii"),
            }
        )
        lines.append(f"{key_id}  SPKI SHA-256 {hashlib.sha256(der).hexdigest()}")
    return {"keys": entries}, lines


def key_spec(spec: str) -> tuple[str, Path]:
    """``ID=PUBLIC.pem`` -> ``(ID, path)``."""
    key_id, sep, path = spec.partition("=")
    if not sep:
        raise KeyInputError(f"--key takes ID=PUBLIC.pem, got {spec!r}")
    return key_id, Path(path).expanduser()


def check_signature(keyring_path: Path, key_id: str, pae_path: Path, der_path: Path) -> str:
    keyring = load_keyring_file(keyring_path)
    if key_id not in keyring:
        raise KeyInputError(f"{key_id} is not in {keyring_path}")
    try:
        keyring[key_id].public_key.verify(
            der_path.read_bytes(), pae_path.read_bytes(), ec.ECDSA(hashes.SHA256())
        )
    except (InvalidSignature, ValueError) as exc:
        raise KeyInputError(f"the signature does not verify with {key_id} ({exc!r})") from exc
    return f"OK: {der_path} is a valid {key_id} signature over {pae_path}"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Production OTA public keys (M5.OTA.PROD.1)")
    sub = parser.add_subparsers(dest="command", required=True)
    ring = sub.add_parser("keyring", help="public keyring from the two public PEMs")
    ring.add_argument("--key", action="append", required=True, metavar="ID=PUBLIC.pem")
    ring.add_argument("--out", type=Path, help="write the keyring here (default: stdout)")
    ceremony = sub.add_parser("ceremony-pae", help="a test payload + PAE to sign offline")
    ceremony.add_argument("--out", type=Path, required=True)
    check = sub.add_parser("check-signature", help="verify an offline DER signature")
    check.add_argument("--keyring", type=Path, required=True)
    check.add_argument("--key-id", required=True)
    check.add_argument("--pae", type=Path, required=True)
    check.add_argument("--signature", type=Path, required=True)
    args = parser.parse_args(argv)

    try:
        if args.command == "keyring":
            doc, lines = build_keyring([key_spec(spec) for spec in args.key])
            text = json.dumps(doc, indent=2) + "\n"
            if args.out is None:
                sys.stdout.write(text)
            else:
                args.out.write_text(text, encoding="utf-8")
                print(f"wrote {args.out}")
            for line in lines:
                print(line, file=sys.stderr)
        elif args.command == "ceremony-pae":
            args.out.mkdir(parents=True, exist_ok=True)
            (args.out / "ceremony.payload.json").write_bytes(CEREMONY_PAYLOAD)
            pae_path = args.out / "ceremony.pae.bin"
            pae_path.write_bytes(pae(CATALOG_PAYLOAD_TYPE, CEREMONY_PAYLOAD))
            print(f"wrote {pae_path}: sign it offline with each key, then check-signature")
        else:
            print(check_signature(args.keyring, args.key_id, args.pae, args.signature))
    except (KeyInputError, OtaPublisherError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
