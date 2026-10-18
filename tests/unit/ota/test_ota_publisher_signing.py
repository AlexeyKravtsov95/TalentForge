"""Signing module (M5.OTA.2): external production signing, inline test/staging only.

The load-bearing property: no code path loads a production private key. The inline
signer refuses every key id outside ``test-``/``tf-staging-``; production catalogs are
signed by an external signer over the PAE and only the DER signature (verified against
the committed public key) ever reaches the tool.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.signing import (
    INLINE_SIGNING_PREFIXES,
    build_envelope,
    inline_sign_envelope,
    load_keyring_file,
    pae_sha256,
    sign_pae_der,
    verify_der_signature,
)
from talent_data.ota.verify import SignedEnvelope, pae, parse_strict, sha256_ref
from tests.unit.ota.publisher_testkit import BACKUP, PRIMARY, derive_test_key, write_test_keyring


@pytest.fixture(scope="module")
def keys(tmp_path_factory: pytest.TempPathFactory) -> tuple[Path, Path]:
    keyring, pem = write_test_keyring(tmp_path_factory.mktemp("keys"))
    return keyring, pem


def test_inline_signing_refuses_production_key_ids(keys: tuple[Path, Path]) -> None:
    _, pem = keys
    for key_id in ("tf-prod-2026a", "tf-prod-2026b", "tf-prod-2027a", "prod", "TF-PROD-2026A"):
        with pytest.raises(OtaPublisherError, match="refuses key id"):
            inline_sign_envelope(b"payload", key_pem=pem, key_id=key_id)


def test_inline_signing_accepts_test_and_staging_ids(keys: tuple[Path, Path]) -> None:
    _, pem = keys
    envelope = inline_sign_envelope(b"{}", key_pem=pem, key_id=PRIMARY)
    assert parse_strict(SignedEnvelope, envelope).signatures[0].key_id == PRIMARY
    staging = inline_sign_envelope(b"{}", key_pem=pem, key_id="tf-staging-2026")
    assert parse_strict(SignedEnvelope, staging).signatures[0].key_id == "tf-staging-2026"


def test_inline_prefixes_are_exactly_test_and_staging() -> None:
    assert INLINE_SIGNING_PREFIXES == ("test-", "tf-staging-")


def test_external_signature_round_trip(keys: tuple[Path, Path]) -> None:
    keyring_path, _ = keys
    keyring = load_keyring_file(keyring_path)
    payload = b'{"sequence": 7}\n'
    der = sign_pae_der(derive_test_key(PRIMARY), payload)
    assert verify_der_signature(keyring[PRIMARY].public_key, der, payload)
    # any other payload, any other key: refused
    assert not verify_der_signature(keyring[PRIMARY].public_key, der, b'{"sequence": 8}\n')
    assert not verify_der_signature(keyring[BACKUP].public_key, der, payload)


def test_envelope_bytes_are_deterministic_and_verify() -> None:
    payload = b'{"sequence": 7}\n'
    der = sign_pae_der(derive_test_key(PRIMARY), payload)
    first = build_envelope(payload, {PRIMARY: der})
    second = build_envelope(payload, {PRIMARY: der})
    assert first == second
    envelope = parse_strict(SignedEnvelope, first)
    assert envelope.payload_type == "application/vnd.talentforge.ota-catalog.v1+json"


def test_pae_sha256_matches_the_signed_message() -> None:
    payload = b"catalog payload"
    assert pae_sha256(payload) == sha256_ref(
        pae("application/vnd.talentforge.ota-catalog.v1+json", payload)
    )


def test_envelope_rejects_empty_signatures() -> None:
    with pytest.raises(OtaPublisherError, match=r"1\.\.4 signatures"):
        build_envelope(b"payload", {})
