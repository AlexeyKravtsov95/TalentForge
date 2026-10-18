"""Signing of OTA catalogs: ECDSA P-256 / SHA-256 over the DSSE v1 PAE (contract §3).

Two ways to produce a signed catalog, and only two:

* **External signing (production).** ``promote``/``rollback``/``resign`` write the
  unsigned payload and print the SHA-256 of the PAE the owner must sign; the owner
  signs it offline with the production key (hardware token, keychain, offline media -
  OD-2) and hands the tool a DER signature *file*. :func:`verify_der_signature` checks
  it against the committed public key before anything is written. No code path in this
  module loads a production private key: the convenience signer below refuses
  production key ids outright.

* **Inline signing (staging and tests only).** :func:`inline_sign_envelope` signs with
  a private PEM on the operator's machine. Allowed key ids: ``test-*`` (fixtures,
  public seeds) and ``tf-staging-*`` (the staging channel). Anything else - in
  particular every ``tf-prod-*`` production key id - is refused, so a production
  private key can never be fed to the tool "for convenience".

Signatures are deterministic (RFC 6979), the same property that makes the committed
fixtures reproducible.
"""

from __future__ import annotations

import base64
import hashlib
import json
from collections.abc import Mapping
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import CATALOG_PAYLOAD_TYPE, MAX_SIGNATURES
from talent_data.ota.verify import TrustedKey, load_keyring, pae

#: key ids the inline (private-key) signer accepts: fixtures and the staging channel.
INLINE_SIGNING_PREFIXES = ("test-", "tf-staging-")


def load_private_key(pem_path: Path) -> ec.EllipticCurvePrivateKey:
    key = serialization.load_pem_private_key(pem_path.read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise OtaPublisherError(f"{pem_path}: not an ECDSA P-256 private key")
    return key


def sign_pae_der(key: ec.EllipticCurvePrivateKey, payload: bytes) -> bytes:
    """Deterministic (RFC 6979) DER signature over ``PAE(CATALOG_PAYLOAD_TYPE, payload)``."""
    return key.sign(
        pae(CATALOG_PAYLOAD_TYPE, payload), ec.ECDSA(hashes.SHA256(), deterministic_signing=True)
    )


def verify_der_signature(
    public: ec.EllipticCurvePublicKey, der_signature: bytes, payload: bytes
) -> bool:
    from cryptography.exceptions import InvalidSignature

    try:
        public.verify(der_signature, pae(CATALOG_PAYLOAD_TYPE, payload), ec.ECDSA(hashes.SHA256()))
    except InvalidSignature:
        return False
    return True


def build_envelope(payload: bytes, signatures: Mapping[str, bytes]) -> bytes:
    """The ``ota-envelope.v1`` bytes for ``payload`` signed by ``{keyId: DER}``.

    Exactly one serialization (canonical base64 with padding, indent-2 JSON): the
    written catalog is byte-reproducible from (payload, signatures).
    """
    if not 1 <= len(signatures) <= MAX_SIGNATURES:
        raise OtaPublisherError(f"an envelope carries 1..{MAX_SIGNATURES} signatures")
    doc = {
        "payloadType": CATALOG_PAYLOAD_TYPE,
        "payload": base64.b64encode(payload).decode("ascii"),
        "signatures": [
            {"keyId": key_id, "sig": base64.b64encode(der).decode("ascii")}
            for key_id, der in sorted(signatures.items())
        ],
    }
    return (json.dumps(doc, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def inline_sign_envelope(payload: bytes, *, key_pem: Path, key_id: str) -> bytes:
    """Sign ``payload`` with a local private key - staging and tests only.

    Refuses every key id that is not ``test-*`` or ``tf-staging-*``: production keys
    are signed externally and can never be loaded here.
    """
    if not key_id.startswith(INLINE_SIGNING_PREFIXES):
        raise OtaPublisherError(
            f"inline signing refuses key id {key_id!r}: only test-/tf-staging- keys may "
            "be loaded by the tool; production catalogs are signed externally "
            "(promote prints the payload to sign, attach-signature verifies the result)"
        )
    return build_envelope(payload, {key_id: sign_pae_der(load_private_key(key_pem), payload)})


def load_keyring_file(path: Path) -> dict[str, TrustedKey]:
    """A keyring in the fixture format: ``{"keys": [{keyId, algorithm, publicKeySpki}]}``."""
    doc = json.loads(path.read_text(encoding="utf-8"))
    entries = doc.get("keys") if isinstance(doc, dict) else None
    if not isinstance(entries, list):
        raise OtaPublisherError(f"{path}: expected a JSON object with a 'keys' array")
    try:
        return load_keyring(entries)
    except (KeyError, TypeError, ValueError) as exc:
        raise OtaPublisherError(f"{path}: invalid keyring: {exc}") from exc


def pae_sha256(payload: bytes) -> str:
    """The SHA-256 of the PAE - the digest the offline signer must sign."""
    return "sha256:" + hashlib.sha256(pae(CATALOG_PAYLOAD_TYPE, payload)).hexdigest()
