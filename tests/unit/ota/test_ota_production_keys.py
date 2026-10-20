"""The production-key helper and the VK Cloud templates (M5.OTA.PROD.1), offline.

Keys here are throwaway P-256 keys made in a pytest temp directory: no production key
exists or is created. The runbook's OpenSSL key ceremony (generate offline, export the
public key, sign the PAE, verify) is run as written when `openssl` is on the PATH.
"""

from __future__ import annotations

import importlib.util
import json
import shutil
import subprocess
import sys
from pathlib import Path
from types import ModuleType

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from talent_data.ota.models import CATALOG_PAYLOAD_TYPE, Channel
from talent_data.ota.s3_channels import PRODUCTION_PROFILE, STAGING_PROFILE
from talent_data.ota.signing import load_keyring_file, verify_der_signature
from talent_data.ota.store import catalog_key
from talent_data.ota.verify import pae
from tests.unit.ota.publisher_testkit import ROOT

TEMPLATES = ROOT / "deploy" / "ota" / "vkcloud" / "production"


def _helper() -> ModuleType:
    name = "ota_production_keys"
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


keys = _helper()


def throwaway(directory: Path, name: str) -> tuple[ec.EllipticCurvePrivateKey, Path, Path]:
    """A temp key pair: (private key, private PEM, public PEM)."""
    directory.mkdir(parents=True, exist_ok=True)
    key = ec.generate_private_key(ec.SECP256R1())
    private = directory / f"{name}.pem"
    private.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    public = directory / f"{name}.pub.pem"
    public.write_bytes(
        key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )
    )
    return key, private, public


def pair_argv(a: Path, b: Path) -> list[str]:
    return ["keyring", "--key", f"tf-prod-2026a={a}", "--key", f"tf-prod-2026b={b}"]


# --------------------------------------------------------------------------- keyring


def test_the_keyring_pins_a_primary_and_a_backup(tmp_path: Path, capsys) -> None:
    _, _, a = throwaway(tmp_path, "a")
    _, _, b = throwaway(tmp_path, "b")
    out = tmp_path / "production-keyring.json"
    assert keys.main([*pair_argv(a, b), "--out", str(out)]) == 0
    assert sorted(load_keyring_file(out)) == ["tf-prod-2026a", "tf-prod-2026b"]
    assert capsys.readouterr().err.count("SPKI SHA-256") == 2


@pytest.mark.parametrize(
    ("ids", "message"),
    [
        (["tf-prod-2026a"], "primary and a backup"),
        (["tf-prod-2026a", "tf-staging-2026"], "production key ids are"),
        (["tf-prod-2026a", "test-backup"], "production key ids are"),
        (["tf-prod-2026a", "tf-prod-2026a"], "duplicate key id"),
    ],
)
def test_the_keyring_refuses_wrong_ids(
    tmp_path: Path, ids: list[str], message: str, capsys
) -> None:
    argv = ["keyring"]
    for index, key_id in enumerate(ids):
        _, _, public = throwaway(tmp_path, f"k{index}")
        argv += ["--key", f"{key_id}={public}"]
    assert keys.main(argv) == 1
    assert message in capsys.readouterr().err


def test_a_private_key_is_refused_before_it_is_parsed(tmp_path: Path, capsys) -> None:
    _, private, _ = throwaway(tmp_path, "a")
    _, _, b = throwaway(tmp_path, "b")
    assert keys.main(pair_argv(private, b)) == 1
    assert "holds a PRIVATE key" in capsys.readouterr().err


def test_primary_and_backup_must_be_different_keys(tmp_path: Path, capsys) -> None:
    _, _, a = throwaway(tmp_path, "a")
    assert keys.main(pair_argv(a, a)) == 1
    assert "the same public key twice" in capsys.readouterr().err


def test_a_non_p256_key_is_refused(tmp_path: Path, capsys) -> None:
    other = ec.generate_private_key(ec.SECP384R1()).public_key()
    p384 = tmp_path / "p384.pub.pem"
    p384.write_bytes(
        other.public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )
    )
    _, _, b = throwaway(tmp_path, "b")
    assert keys.main(pair_argv(p384, b)) == 1
    assert "not an ECDSA P-256 public key" in capsys.readouterr().err


# --------------------------------------------------------------------------- ceremony


def test_the_ceremony_pae_is_signed_and_checked(tmp_path: Path, capsys) -> None:
    key_a, _, a = throwaway(tmp_path, "a")
    _, _, b = throwaway(tmp_path, "b")
    ring = tmp_path / "keyring.json"
    assert keys.main([*pair_argv(a, b), "--out", str(ring)]) == 0
    assert keys.main(["ceremony-pae", "--out", str(tmp_path / "ceremony")]) == 0
    pae_file = tmp_path / "ceremony" / "ceremony.pae.bin"
    payload = (tmp_path / "ceremony" / "ceremony.payload.json").read_bytes()
    assert pae_file.read_bytes() == pae(CATALOG_PAYLOAD_TYPE, payload)
    assert b"not a catalog" in payload  # can never verify as a catalog

    signature = tmp_path / "sig-a.der"
    signature.write_bytes(key_a.sign(pae_file.read_bytes(), ec.ECDSA(hashes.SHA256())))
    check = ["check-signature", "--keyring", str(ring), "--pae", str(pae_file)]
    assert keys.main([*check, "--key-id", "tf-prod-2026a", "--signature", str(signature)]) == 0
    assert "OK:" in capsys.readouterr().out
    # the primary's signature is not the backup's
    assert keys.main([*check, "--key-id", "tf-prod-2026b", "--signature", str(signature)]) == 1
    assert "does not verify" in capsys.readouterr().err


@pytest.mark.skipif(shutil.which("openssl") is None, reason="openssl not on PATH")
def test_the_runbook_openssl_ceremony_works_as_written(tmp_path: Path) -> None:
    """docs/ota/production-deployment.md §4 - the same OpenSSL commands, on temp keys.

    The runbook lets OpenSSL prompt for the passphrase; here ``-pass``/``-passin`` give it
    non-interactively. The private key is encrypted (AES-256) from the moment it exists.
    """

    def openssl(*args: str) -> None:
        subprocess.run(["openssl", *args], check=True, capture_output=True, cwd=tmp_path)

    names = ("tf-prod-2026a", "tf-prod-2026b")
    secret = "pass:pytest-only"
    for name in names:
        openssl(
            "genpkey",
            "-algorithm",
            "EC",
            "-pkeyopt",
            "ec_paramgen_curve:P-256",
            "-aes-256-cbc",
            "-pass",
            secret,
            "-out",
            f"{name}.key.pem",
        )
        assert b"ENCRYPTED PRIVATE KEY" in (tmp_path / f"{name}.key.pem").read_bytes()
        openssl(
            "pkey",
            "-in",
            f"{name}.key.pem",
            "-passin",
            secret,
            "-pubout",
            "-out",
            f"{name}.pub.pem",
        )
    ring = tmp_path / "production-keyring.json"
    argv = ["keyring", "--out", str(ring)]
    for name in names:
        argv += ["--key", f"{name}={tmp_path / f'{name}.pub.pem'}"]
    assert keys.main(argv) == 0
    assert keys.main(["ceremony-pae", "--out", str(tmp_path)]) == 0
    openssl(
        "dgst",
        "-sha256",
        "-sign",
        f"{names[0]}.key.pem",
        "-passin",
        secret,
        "-out",
        "sig.der",
        "ceremony.pae.bin",
    )
    check = ["check-signature", "--keyring", str(ring), "--key-id", names[0]]
    check += ["--pae", str(tmp_path / "ceremony.pae.bin")]
    assert keys.main([*check, "--signature", str(tmp_path / "sig.der")]) == 0
    # the publisher's own verifier (attach-signature) accepts an OpenSSL DER signature
    payload = (tmp_path / "ceremony.payload.json").read_bytes()
    public = load_keyring_file(ring)[names[0]].public_key
    assert verify_der_signature(public, (tmp_path / "sig.der").read_bytes(), payload)


# --------------------------------------------------------------------------- templates


def test_the_bucket_policy_grants_anonymous_get_on_the_contract_paths_only() -> None:
    policy = json.loads((TEMPLATES / "bucket-policy.json").read_text())
    assert set(policy) == {"Version", "Statement"}
    (statement,) = policy["Statement"]
    assert statement["Effect"] == "Allow"
    assert statement["Principal"] == "*"
    assert statement["Action"] == ["s3:GetObject"]
    bucket = PRODUCTION_PROFILE.bucket
    assert statement["Resource"] == [
        f"arn:aws:s3:::{bucket}/{catalog_key(Channel.PRODUCTION)}",
        f"arn:aws:s3:::{bucket}/v1/blobs/sha256/*",
    ]
    text = json.dumps(policy)
    assert STAGING_PROFILE.bucket not in text and "staging" not in text
    assert "Condition" not in statement and "NotAction" not in statement


def test_the_aws_profile_template_holds_no_secret() -> None:
    text = (TEMPLATES / "aws-cli-profile.example").read_text()
    config = [line for line in text.splitlines() if line and not line.startswith("#")]
    assert config == [
        f"[profile {PRODUCTION_PROFILE.aws_profile}]",
        f"region = {PRODUCTION_PROFILE.region}",
        f"endpoint_url = {PRODUCTION_PROFILE.endpoint_url}",
    ]
    assert "aws_access_key_id" not in text and "aws_secret_access_key" not in text
