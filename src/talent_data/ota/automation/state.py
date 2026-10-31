"""Strict durable publication journal. Missing state is never an empty journal.

One Git commit atomically binds the index, receipts and exact nonsecret signing work.
Initialization is an explicit owner setup action, never a publisher recovery action.
"""

from __future__ import annotations

import base64
import os
import subprocess
import tempfile
from enum import StrEnum
from pathlib import Path
from typing import Literal, Protocol, Self

from pydantic import Field, field_validator, model_validator

from talent_data.ota.automation.requests import ReleaseRequest, _Model
from talent_data.ota.models import SHA256_PATTERN, Channel, OtaCatalog
from talent_data.ota.verify import parse_strict, sha256_ref, strict_b64decode


class JournalError(Exception):
    """Durability or integrity failed; no catalog write is permitted."""


class RequestState(StrEnum):
    PENDING = "PENDING"
    BLOCKED = "BLOCKED"
    STALE = "STALE"
    PREPARED = "PREPARED"
    WRITE_STARTED = "WRITE_STARTED"
    PUBLISHED_NOT_VERIFIED = "PUBLISHED_NOT_VERIFIED"
    VERIFIED = "VERIFIED"
    AMBIGUOUS = "AMBIGUOUS"


TERMINAL = frozenset(
    {RequestState.STALE, RequestState.VERIFIED, RequestState.PUBLISHED_NOT_VERIFIED}
)


def request_digest(request: ReleaseRequest) -> str:
    return sha256_ref(request.model_dump_json(by_alias=True).encode())


class PublicationReceipt(_Model):
    schema_version: Literal[1]
    request: ReleaseRequest
    request_digest: str = Field(pattern=SHA256_PATTERN)
    base_sequence: int | None = Field(ge=1)
    base_payload_sha256: str | None = Field(pattern=SHA256_PATTERN)
    target_sequence: int | None = Field(ge=1)
    payload_sha256: str | None = Field(pattern=SHA256_PATTERN)
    run_id: int = Field(gt=0)
    attempt: int = Field(gt=0)
    state: RequestState
    # Closed machine-readable reason codes avoid carrying external stderr or secrets.
    reason: Literal[
        "NEW",
        "READY",
        "WRITE_STARTED",
        "S3_EXACT",
        "PUBLIC_VERIFIED",
        "LIVE_CHANGED",
        "ALREADY_LISTED",
        "INITIAL_RELEASE",
        "CHECK_FAILED",
        "UPLOAD_FAILED",
        "SIGN_FAILED",
        "NOT_WRITTEN",
        "OUTCOME_UNKNOWN",
    ]

    @field_validator("schema_version", mode="before")
    @classmethod
    def version_integer(cls, value: object) -> object:
        if type(value) is not int:
            raise ValueError("schemaVersion must be an integer")
        return value

    @model_validator(mode="after")
    def identities(self) -> Self:
        if self.request_digest != request_digest(self.request):
            raise ValueError("request digest mismatch")
        pins = (
            self.base_sequence,
            self.base_payload_sha256,
            self.target_sequence,
            self.payload_sha256,
        )
        if any(x is not None for x in pins) and not all(x is not None for x in pins):
            raise ValueError("partial publication pins")
        if self.base_sequence is not None and self.target_sequence != self.base_sequence + 1:
            raise ValueError("target must succeed base")
        if (
            self.state
            in {
                RequestState.PREPARED,
                RequestState.WRITE_STARTED,
                RequestState.PUBLISHED_NOT_VERIFIED,
                RequestState.VERIFIED,
                RequestState.AMBIGUOUS,
            }
            and self.payload_sha256 is None
        ):
            raise ValueError("state requires exact publication pins")
        return self

    @classmethod
    def pending(cls, request: ReleaseRequest, *, run_id: int, attempt: int) -> Self:
        return cls(
            schema_version=1,
            request=request,
            request_digest=request_digest(request),
            base_sequence=None,
            base_payload_sha256=None,
            target_sequence=None,
            payload_sha256=None,
            run_id=run_id,
            attempt=attempt,
            state=RequestState.PENDING,
            reason="NEW",
        )

    def evolve(self, **changes: object) -> PublicationReceipt:
        return PublicationReceipt.model_validate({**self.model_dump(), **changes})


def parse_receipt(raw: bytes) -> PublicationReceipt:
    return parse_strict(PublicationReceipt, raw)


class PendingMeta(_Model):
    base_sequence: int = Field(ge=1)
    payload_sha256: str = Field(pattern=SHA256_PATTERN)
    channel: Literal["production"]


class PreparedPublication(_Model):
    payload_b64: str
    meta_b64: str
    der_b64: str
    key_id: str = Field(pattern=r"^[a-zA-Z0-9-]{1,80}$")

    @property
    def payload(self) -> bytes:
        return strict_b64decode(self.payload_b64)

    @property
    def meta(self) -> bytes:
        return strict_b64decode(self.meta_b64)

    @property
    def der(self) -> bytes:
        return strict_b64decode(self.der_b64)

    @classmethod
    def create(cls, payload: bytes, *, base_sequence: int, der: bytes, key_id: str) -> Self:
        meta = (
            PendingMeta(
                base_sequence=base_sequence,
                payload_sha256=sha256_ref(payload),
                channel="production",
            )
            .model_dump_json(by_alias=True)
            .encode()
        )
        return cls(
            payload_b64=base64.b64encode(payload).decode(),
            meta_b64=base64.b64encode(meta).decode(),
            der_b64=base64.b64encode(der).decode(),
            key_id=key_id,
        )

    @model_validator(mode="after")
    def exact(self) -> Self:
        catalog = parse_strict(OtaCatalog, self.payload)
        meta = parse_strict(PendingMeta, self.meta)
        if (
            catalog.channel is not Channel.PRODUCTION
            or meta.payload_sha256 != sha256_ref(self.payload)
            or catalog.sequence != meta.base_sequence + 1
            or not self.der
        ):
            raise ValueError("invalid exact signing work")
        return self


class JournalEntry(_Model):
    receipt: PublicationReceipt
    prepared: PreparedPublication | None

    @model_validator(mode="after")
    def bindings(self) -> Self:
        r, p = self.receipt, self.prepared
        if (r.payload_sha256 is None) != (p is None):
            raise ValueError("missing or unexpected signing work")
        if p is not None:
            catalog = parse_strict(OtaCatalog, p.payload)
            if sha256_ref(p.payload) != r.payload_sha256 or catalog.sequence != r.target_sequence:
                raise ValueError("receipt does not pin signing work")
            active = next(x for x in catalog.releases if x.release_id == catalog.active_release_id)
            if (
                active.release_id != r.request.release_id
                or active.manifest_sha256 != r.request.manifest_sha256
            ):
                raise ValueError("receipt does not pin active release")
        return self


class JournalIndex(_Model):
    schema_version: Literal[1]
    entries: dict[str, JournalEntry]

    @field_validator("schema_version", mode="before")
    @classmethod
    def version_integer(cls, value: object) -> object:
        if type(value) is not int:
            raise ValueError("schemaVersion must be an integer")
        return value

    @model_validator(mode="after")
    def keys_match(self) -> Self:
        if any(k != v.receipt.request_digest for k, v in self.entries.items()):
            raise ValueError("journal index identity mismatch")
        return self


class Journal(Protocol):
    def read_all(self) -> dict[str, JournalEntry]: ...
    def load(self, digest: str) -> JournalEntry | None: ...
    def save(
        self, receipt: PublicationReceipt, prepared: PreparedPublication | None = None
    ) -> JournalEntry: ...


class FileJournal:
    """Offline adapter; same strict format as Git. One writer owns this adapter."""

    def __init__(self, path: Path):
        self.path = path

    def initialize(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        try:
            with self.path.open("xb") as stream:
                stream.write(
                    JournalIndex(schema_version=1, entries={})
                    .model_dump_json(by_alias=True)
                    .encode()
                )
                stream.flush()
                os.fsync(stream.fileno())
        except OSError:
            raise JournalError("journal initialization failed") from None
        self.read_all()

    def _read(self) -> bytes:
        return self.path.read_bytes()

    def _write(self, raw: bytes, previous: bytes) -> None:
        if self._read() != previous:
            raise JournalError("journal changed concurrently")
        fd, name = tempfile.mkstemp(dir=self.path.parent)
        temp = Path(name)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
            temp.replace(self.path)
        finally:
            temp.unlink(missing_ok=True)

    def read_all(self) -> dict[str, JournalEntry]:
        try:
            return parse_strict(JournalIndex, self._read()).entries
        except (OSError, ValueError):
            raise JournalError("journal missing, unreadable or corrupt") from None

    def load(self, digest: str) -> JournalEntry | None:
        return self.read_all().get(digest)

    def save(
        self, receipt: PublicationReceipt, prepared: PreparedPublication | None = None
    ) -> JournalEntry:
        try:
            previous = self._read()
            entries = parse_strict(JournalIndex, previous).entries
            old = entries.get(receipt.request_digest)
            if old is not None:
                if old.receipt.request != receipt.request:
                    raise JournalError("request identity changed")
                if old.receipt.state in TERMINAL and old.receipt != receipt:  # noqa: SIM102
                    # Public verification may only advance the S3-complete receipt.
                    if not (
                        old.receipt.state is RequestState.PUBLISHED_NOT_VERIFIED
                        and receipt
                        == old.receipt.evolve(state=RequestState.VERIFIED, reason="PUBLIC_VERIFIED")
                    ):
                        raise JournalError("terminal receipt cannot reactivate")
                if old.prepared is not None:
                    if prepared is not None and prepared != old.prepared:
                        raise JournalError("exact signing work cannot be replaced")
                    prepared = old.prepared
                    for field in (
                        "base_sequence",
                        "base_payload_sha256",
                        "target_sequence",
                        "payload_sha256",
                    ):
                        if getattr(old.receipt, field) != getattr(receipt, field):
                            raise JournalError("publication pins cannot change")
                if old.receipt.state is RequestState.AMBIGUOUS and receipt.state not in {
                    RequestState.AMBIGUOUS,
                    RequestState.PUBLISHED_NOT_VERIFIED,
                    RequestState.VERIFIED,
                }:
                    raise JournalError("ambiguous publication cannot be retried")
            if receipt.state in {RequestState.PREPARED, RequestState.WRITE_STARTED}:  # noqa: SIM102
                if any(
                    k != receipt.request_digest
                    and v.receipt.state in {RequestState.WRITE_STARTED, RequestState.AMBIGUOUS}
                    for k, v in entries.items()
                ):
                    raise JournalError("another publication outcome is unresolved")
            entry = JournalEntry(receipt=receipt, prepared=prepared)
            entries[receipt.request_digest] = entry
            raw = (
                JournalIndex(schema_version=1, entries=entries)
                .model_dump_json(by_alias=True)
                .encode()
            )
            self._write(raw, previous)
            if self._read() != raw or self.load(receipt.request_digest) != entry:
                raise JournalError("journal read-back mismatch")
        except (OSError, ValueError):
            raise JournalError("journal save or verification failed") from None
        else:
            return entry


class GitJournal(FileJournal):
    """Remote branch is authoritative. Push is non-force and every save fetches it back.

    ``remote`` is a trusted Git remote name/path, never an artifact-supplied URL.
    Credentials belong to Git's environment, not arguments or the journal.
    """

    branch = "ota-publication-state"

    def __init__(self, repo: Path, *, remote: str = "origin"):
        self.repo, self.remote = repo, remote

    def _git(self, repo: Path, *args: str, input_bytes: bytes | None = None) -> bytes:
        result = subprocess.run(
            ["git", "-C", str(repo), *args], input=input_bytes, capture_output=True, check=False
        )
        if result.returncode:
            raise JournalError("publication-state Git operation failed")
        return result.stdout

    def _read(self) -> bytes:
        self._git(self.repo, "fetch", "--quiet", self.remote, f"refs/heads/{self.branch}")
        return self._git(self.repo, "show", "FETCH_HEAD:journal.json")

    def _commit(self, raw: bytes, *, previous: bytes | None) -> None:
        # Plumbing writes a closed tree without touching checkout/index. Running in
        # the trusted repo preserves Git's configured auth without reading secrets
        # or copying them into another checkout, environment file or arguments.
        parents: list[str] = []
        if previous is not None:
            if self._read() != previous:
                raise JournalError("publication-state changed concurrently")
            parent = self._git(self.repo, "rev-parse", "FETCH_HEAD").decode().strip()
            parents = ["-p", parent]
        blob = self._git(self.repo, "hash-object", "-w", "--stdin", input_bytes=raw).strip()
        tree = (
            self._git(self.repo, "mktree", input_bytes=b"100644 blob " + blob + b"\tjournal.json\n")
            .decode()
            .strip()
        )
        commit = (
            self._git(
                self.repo,
                "-c",
                "user.name=OTA publication",
                "-c",
                "user.email=ota@users.noreply.github.com",
                "commit-tree",
                tree,
                *parents,
                input_bytes=b"Record exact OTA publication state\n",
            )
            .decode()
            .strip()
        )
        self._git(self.repo, "push", "--quiet", self.remote, f"{commit}:refs/heads/{self.branch}")
        if self._read() != raw:
            raise JournalError("remote publication-state read-back mismatch")

    def initialize(self) -> None:
        existing = self._git(
            self.repo, "ls-remote", "--heads", self.remote, f"refs/heads/{self.branch}"
        )
        if existing:
            raise JournalError("publication-state already exists; initialization refused")
        self._commit(
            JournalIndex(schema_version=1, entries={}).model_dump_json(by_alias=True).encode(),
            previous=None,
        )
        self.read_all()

    def _write(self, raw: bytes, previous: bytes) -> None:
        self._commit(raw, previous=previous)
