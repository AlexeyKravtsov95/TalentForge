"""Only public-seed TEST keys; no production environment access."""

import os

import pytest
from cryptography.hazmat.primitives import serialization

from talent_data.ota.automation.ci_signer import CiPemSigner, SecretError, temporary_aws_profile
from talent_data.ota.models import CATALOG_PAYLOAD_TYPE
from talent_data.ota.signing import verify_der_signature
from talent_data.ota.verify import TrustedKey, pae
from tests.unit.ota.publisher_testkit import derive_test_key


def pem(key):
    return key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    ).decode()


def test_signer_is_lazy_and_matches_pinned_key(tmp_path):
    key = derive_test_key("test-ci")
    reads = []

    def read(name):
        reads.append(name)
        return {"OTA_PRODUCTION_PRIVATE_KEY_PEM": pem(key)}.get(name)

    signer = CiPemSigner(
        {"test-ci": TrustedKey("test-ci", key.public_key())},
        key_id="test-ci",
        read_secret=read,
        temporary_root=tmp_path,
    )
    assert not reads
    der = signer.sign(pae(CATALOG_PAYLOAD_TYPE, b"payload"))
    assert verify_der_signature(key.public_key(), der, b"payload")
    assert not list(tmp_path.iterdir())


def test_foreign_key_and_errors_never_echo_secrets(tmp_path):
    key = derive_test_key("test-ci")
    secret = pem(derive_test_key("test-other"))
    signer = CiPemSigner(
        {"test-ci": TrustedKey("test-ci", key.public_key())},
        key_id="test-ci",
        read_secret=lambda _: secret,
        temporary_root=tmp_path,
    )
    with pytest.raises(SecretError) as raised:
        signer.sign(b"PAE")
    assert secret not in str(raised.value)
    assert "PRIVATE KEY" not in str(raised.value)
    assert not list(tmp_path.iterdir())


def test_profile_permissions_and_cleanup(tmp_path):
    with temporary_aws_profile(
        read_secret={
            "OTA_PRODUCTION_S3_ACCESS_KEY_ID": "TEST-ACCESS",
            "OTA_PRODUCTION_S3_SECRET_ACCESS_KEY": "TEST-SECRET",
        }.get,
        temporary_root=tmp_path,
    ) as path:
        assert path.stat().st_mode & 0o777 == 0o600
        assert "TEST-SECRET" in path.read_text()
        assert os.environ["AWS_SHARED_CREDENTIALS_FILE"] == str(path)
    assert not path.exists()
    assert not list(tmp_path.iterdir())


def test_wrong_curve_and_backup_key_are_refused(tmp_path):
    from cryptography.hazmat.primitives.asymmetric import ec

    key = derive_test_key("test-ci")
    keyring = {"test-ci": TrustedKey("test-ci", key.public_key())}
    with pytest.raises(SecretError, match="primary"):
        CiPemSigner(
            keyring, key_id="tf-prod-2026b", read_secret=lambda _: pytest.fail("secret read")
        )
    signer = CiPemSigner(
        keyring,
        key_id="test-ci",
        read_secret=lambda name: (
            pem(ec.generate_private_key(ec.SECP384R1())) if name.endswith("_PEM") else None
        ),
        temporary_root=tmp_path,
    )
    with pytest.raises(SecretError, match="P-256"):
        signer.sign(b"TEST PAE")
    assert not list(tmp_path.iterdir())


def test_encrypted_pem_uses_password_without_arguments(tmp_path, monkeypatch):
    import subprocess

    key = derive_test_key("test-ci")
    password = "TEST-PASSWORD-NEVER-LOGGED"
    encrypted = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.BestAvailableEncryption(password.encode()),
    ).decode()
    secrets = {"OTA_PRODUCTION_PRIVATE_KEY_PEM": encrypted, "OTA_PRODUCTION_KEY_PASSWORD": password}
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: pytest.fail("no commands with secrets"))
    signer = CiPemSigner(
        {"test-ci": TrustedKey("test-ci", key.public_key())},
        key_id="test-ci",
        read_secret=secrets.get,
        temporary_root=tmp_path,
    )
    der = signer.sign(pae(CATALOG_PAYLOAD_TYPE, b"TEST payload"))
    assert verify_der_signature(key.public_key(), der, b"TEST payload")
    assert not list(tmp_path.iterdir())


def test_cleanup_failure_is_visible_and_sanitized(tmp_path, monkeypatch):
    from talent_data.ota.automation import ci_signer

    original = ci_signer.tempfile.TemporaryDirectory.cleanup

    def fail_after_cleanup(self):
        original(self)
        raise OSError("TEST-SECRET-CLEANUP-DETAIL")

    monkeypatch.setattr(ci_signer.tempfile.TemporaryDirectory, "cleanup", fail_after_cleanup)
    key = derive_test_key("test-ci")
    signer = CiPemSigner(
        {"test-ci": TrustedKey("test-ci", key.public_key())},
        key_id="test-ci",
        read_secret={"OTA_PRODUCTION_PRIVATE_KEY_PEM": pem(key)}.get,
        temporary_root=tmp_path,
    )
    with pytest.raises(SecretError) as error:
        signer.sign(b"TEST")
    assert str(error.value) == "temporary secret cleanup failed"
    assert not list(tmp_path.iterdir())
