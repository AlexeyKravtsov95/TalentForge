"""Shared fixtures of the M5.OTA.2 publisher tests (L3, fully offline).

Everything runs against the real repository (its committed history and committed
data) and a :class:`~talent_data.ota.store.LocalDirectoryStore` in a pytest temp
directory standing in for the bucket. Signing uses only the public-seed TEST keys of
the committed fixtures (``test-*``); no production key material exists here and no
network is touched.
"""

from __future__ import annotations

import base64
import hashlib
import importlib.util
import json
import sys
from datetime import UTC, datetime
from pathlib import Path
from types import ModuleType

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / "docs" / "ota" / "fixtures" / "v1"
COMMIT = "c749ebc5fc31c43be742844cb89d012f100a2a0a"  # main after PR #41
BUILD_A = "1.60.1.70374"
BUILD_B = "1.60.1.70450"
GAME = "forever"
VALIDATION_NOTE = "talent-data validate + manifest + visuals/tooltips/localization sync checks"
CREATED_AT = datetime(2026, 10, 17, 23, 0, tzinfo=UTC)
NOW = datetime(2026, 10, 18, 12, 0, tzinfo=UTC)
PRIMARY, BACKUP = "test-primary", "test-backup"

_P256_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551


def generator() -> ModuleType:
    """The committed fixture generator (its TEST keys are derived from public seeds)."""
    name = "generate_ota_fixtures"
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def derive_test_key(name: str) -> ec.EllipticCurvePrivateKey:
    seed = hashlib.sha256(f"talentforge-ota-v1-test-key:{name}".encode()).digest()
    return ec.derive_private_key(int.from_bytes(seed) % (_P256_ORDER - 1) + 1, ec.SECP256R1())


def write_test_keyring(directory: Path) -> tuple[Path, Path]:
    """A keyring JSON (test-primary + test-backup) and test-primary's private PEM."""
    directory.mkdir(parents=True, exist_ok=True)

    def entry(name: str) -> dict[str, str]:
        der = (
            derive_test_key(name)
            .public_key()
            .public_bytes(
                serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
            )
        )
        return {
            "keyId": name,
            "algorithm": "ecdsa-p256-sha256",
            "publicKeySpki": base64.b64encode(der).decode("ascii"),
        }

    keyring = directory / "keyring.json"
    keyring.write_text(json.dumps({"keys": [entry(PRIMARY), entry(BACKUP)]}, indent=2) + "\n")
    pem = directory / f"{PRIMARY}.pem"
    pem.write_bytes(
        derive_test_key(PRIMARY).private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    return keyring, pem


def sign_der(key: ec.EllipticCurvePrivateKey, payload: bytes) -> bytes:
    """Deterministic (RFC 6979) DER signature over the catalog PAE - the offline signer."""
    from talent_data.ota.models import CATALOG_PAYLOAD_TYPE
    from talent_data.ota.verify import pae

    return key.sign(
        pae(CATALOG_PAYLOAD_TYPE, payload),
        ec.ECDSA(hashes.SHA256(), deterministic_signing=True),
    )


def write_approval_record(
    docs_dir: Path,
    release_id: str,
    *,
    accepted: str = "G5, G7",
    evidence: str | None = "offline E2E rehearsal record (pytest)",
) -> Path:
    """An OD-12 approval record; only G5/G7 may be accepted.

    ``evidence=None`` omits the evidence line (a record that does not accept G7
    does not need one)."""
    docs_dir.mkdir(parents=True, exist_ok=True)
    evidence_line = "" if evidence is None else f"- Live-build evidence: {evidence}\n"
    path = docs_dir / f"{release_id}.md"
    path.write_text(
        f"# OTA promotion approval: {release_id}\n\n"
        f"- Release: {release_id}\n"
        f"- Manifest SHA-256: sha256:{'0' * 64}\n"
        f"- Source commit: {COMMIT}\n"
        f"- Accepted gates: {accepted}\n"
        f"{evidence_line}"
        f"- minDataLevel: 1\n"
        f"- Approver: pytest\n"
        f"- Date: 2026-10-18\n",
        encoding="utf-8",
    )
    return path
