"""Explicit external CI signer; secrets are read lazily after publisher checks."""

from __future__ import annotations

import contextlib
import os
import tempfile
from collections.abc import Callable, Iterator, Mapping
from pathlib import Path
from typing import Protocol

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from talent_data.ota.verify import TrustedKey


class SecretError(Exception):
    """Sanitized secret setup/signing/cleanup failure."""


class Signer(Protocol):
    def sign(self, pae_bytes: bytes) -> bytes: ...


SecretReader = Callable[[str], str | None]


@contextlib.contextmanager
def _temporary_secret(raw: bytes, *, temporary_root: Path | None) -> Iterator[Path]:
    directory = tempfile.TemporaryDirectory(prefix="ota-secret-", dir=temporary_root)
    try:
        path = Path(directory.name) / "secret"
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as output:
            output.write(raw)
        yield path
    finally:
        try:
            directory.cleanup()
        except OSError:
            raise SecretError("temporary secret cleanup failed") from None


class CiPemSigner:
    """Only primary production key; TEST ids permit isolated offline rehearsals.

    No secret read occurs during construction. DER is the only output. Private PEM
    and password never enter command arguments, journal models or exception text.
    """

    def __init__(
        self,
        keyring: Mapping[str, TrustedKey],
        *,
        key_id: str = "tf-prod-2026a",
        read_secret: SecretReader = os.environ.get,
        temporary_root: Path | None = None,
    ):
        if key_id != "tf-prod-2026a" and not key_id.startswith("test-"):
            raise SecretError("CI signing key is not the primary key")
        if key_id not in keyring:
            raise SecretError("CI signing key is not pinned")
        self.key_id, self.keyring = key_id, keyring
        self.read_secret, self.temporary_root = read_secret, temporary_root

    def sign(self, pae_bytes: bytes) -> bytes:
        try:
            pem = self.read_secret("OTA_PRODUCTION_PRIVATE_KEY_PEM")
            password = self.read_secret("OTA_PRODUCTION_KEY_PASSWORD")
            if not pem:
                raise SecretError("CI signing secret is missing")
            with _temporary_secret(pem.encode(), temporary_root=self.temporary_root) as path:
                key = serialization.load_pem_private_key(
                    path.read_bytes(), password=password.encode() if password else None
                )
                if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(
                    key.curve, ec.SECP256R1
                ):
                    raise SecretError("CI signing key must be P-256")
                if (
                    key.public_key().public_numbers()
                    != self.keyring[self.key_id].public_key.public_numbers()
                ):
                    raise SecretError("CI signing key does not match pinned public key")
                return key.sign(pae_bytes, ec.ECDSA(hashes.SHA256(), deterministic_signing=True))
        except (OSError, ValueError, TypeError):
            raise SecretError("CI secret loading or signing failed") from None


@contextlib.contextmanager
def temporary_aws_profile(
    *, read_secret: SecretReader = os.environ.get, temporary_root: Path | None = None
) -> Iterator[Path]:
    """Scoped profile for the committed production adapter; environment restored finally."""
    access = read_secret("OTA_PRODUCTION_S3_ACCESS_KEY_ID")
    secret = read_secret("OTA_PRODUCTION_S3_SECRET_ACCESS_KEY")
    if not access or not secret or any(c in access + secret for c in "\r\n\x00"):
        raise SecretError("production storage credentials missing or malformed")
    raw = (
        f"[talentforge-production]\naws_access_key_id = {access}\n"
        f"aws_secret_access_key = {secret}\n"
    ).encode()
    try:
        with _temporary_secret(raw, temporary_root=temporary_root) as path:
            previous = os.environ.get("AWS_SHARED_CREDENTIALS_FILE")
            os.environ["AWS_SHARED_CREDENTIALS_FILE"] = str(path)
            try:
                yield path
            finally:
                if previous is None:
                    os.environ.pop("AWS_SHARED_CREDENTIALS_FILE", None)
                else:
                    os.environ["AWS_SHARED_CREDENTIALS_FILE"] = previous
    except OSError:
        raise SecretError("production storage profile setup failed") from None
