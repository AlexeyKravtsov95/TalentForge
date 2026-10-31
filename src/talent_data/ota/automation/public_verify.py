"""Read-only proof of one stable public catalog and every referenced release/file."""

from __future__ import annotations

from collections import OrderedDict
from collections.abc import Mapping
from contextlib import closing
from dataclasses import dataclass
from datetime import datetime
from urllib.parse import urlsplit

import httpx

from talent_data.models.manifest import DatasetManifest
from talent_data.ota.models import (
    MAX_CATALOG_BYTES,
    MAX_RELEASE_BYTES,
    Channel,
    FileRole,
    ReleaseFile,
)
from talent_data.ota.store import blob_key, catalog_key
from talent_data.ota.verify import (
    SUPPORTED_DATA_LEVEL,
    OtaError,
    TrustedKey,
    parse_strict,
    verify_catalog,
    verify_release_files,
    verify_release_manifest,
)


@dataclass(frozen=True)
class PublicVerification:
    ok: bool
    catalog_payload_sha256: str | None
    active_release_id: str | None
    sequence: int | None
    problem: str | None


class _PublicFailure(Exception):
    pass


def _require(condition: bool, problem: str) -> None:
    if not condition:
        raise _PublicFailure(problem)


def _fetch(client: httpx.Client, url: str, limit: int) -> bytes:
    # Override defaults on even a credentialed/follow-redirects client supplied by a caller.
    request = client.build_request(
        "GET", url, headers={"Accept-Encoding": "identity"}, timeout=30.0
    )
    for header in ("authorization", "proxy-authorization", "cookie"):
        request.headers.pop(header, None)
    with closing(client.send(request, auth=None, follow_redirects=False, stream=True)) as response:
        if response.status_code != 200:
            raise _PublicFailure(f"HTTP_STATUS: {response.status_code}")
        if response.headers.get("content-encoding"):
            raise _PublicFailure("CONTENT_ENCODING: expected identity")
        declared = response.headers.get("content-length")
        if declared is not None:
            if not declared.isascii() or not declared.isdecimal():
                raise _PublicFailure("CONTENT_LENGTH: invalid length")
            if int(declared) > limit:
                raise _PublicFailure("SIZE_LIMIT: declared body exceeds pin/limit")
        body = bytearray()
        for chunk in response.iter_raw():
            if len(body) + len(chunk) > limit:
                raise _PublicFailure("SIZE_LIMIT: received body exceeds pin/limit")
            body.extend(chunk)
        if declared is not None and int(declared) != len(body):
            raise _PublicFailure("CONTENT_LENGTH: truncated body")
        return bytes(body)


def verify_publication(
    client: httpx.Client,
    *,
    origin: str,
    keyring: Mapping[str, TrustedKey],
    game: str,
    now: datetime,
) -> PublicVerification:
    """Verify all catalog entries, then re-read the signed payload to detect a race.

    Downloads are bounded by catalog/manifest pins. A blob cache is capped at one
    maximum release size. Nothing is persisted on failure. Same-build identity is checked
    across retained revisions too.
    """
    digest = active = None
    sequence = None
    try:
        parsed = urlsplit(origin)
        _require(
            not (
                parsed.scheme != "https"
                or not parsed.hostname
                or parsed.username is not None
                or parsed.password is not None
                or parsed.query
                or parsed.fragment
            ),
            "ORIGIN_INVALID: expected anonymous HTTPS origin",
        )
        origin = origin.rstrip("/")
        catalog_url = f"{origin}/{catalog_key(Channel.PRODUCTION)}"
        verified = verify_catalog(
            _fetch(client, catalog_url, MAX_CATALOG_BYTES),
            keyring=keyring,
            game=game,
            channel=Channel.PRODUCTION,
            now=now,
            last_seen=None,
        )
        digest = verified.payload_sha256
        catalog = verified.catalog
        active, sequence = catalog.active_release_id, catalog.sequence
        known: DatasetManifest | None = None
        bodies: OrderedDict[str, bytes] = OrderedDict()
        cached_bytes = 0
        for entry in catalog.releases:
            raw = _fetch(
                client, f"{origin}/{blob_key(entry.manifest_sha256)}", entry.manifest_byte_length
            )
            manifest = verify_release_manifest(
                raw, entry, game=game, supported_data_level=SUPPORTED_DATA_LEVEL
            )
            dataset_raw: bytes | None = None

            def read(file: ReleaseFile) -> bytes:
                nonlocal cached_bytes, dataset_raw
                if file.sha256 not in bodies:
                    body = _fetch(client, f"{origin}/{blob_key(file.sha256)}", file.byte_length)
                    bodies[file.sha256] = body
                    cached_bytes += len(body)
                    while cached_bytes > MAX_RELEASE_BYTES:
                        _, removed = bodies.popitem(last=False)
                        cached_bytes -= len(removed)
                bodies.move_to_end(file.sha256)
                body = bodies[file.sha256]
                if file.role is FileRole.DATASET_MANIFEST:
                    dataset_raw = body
                return body

            previous = (
                known if known is not None and known.client_build == entry.client_build else None
            )
            verify_release_files(manifest, read, known_same_build_manifest=previous)
            assert dataset_raw is not None  # required and verified by reference verifier
            known = parse_strict(DatasetManifest, dataset_raw)
        after = verify_catalog(
            _fetch(client, catalog_url, MAX_CATALOG_BYTES),
            keyring=keyring,
            game=game,
            channel=Channel.PRODUCTION,
            now=now,
            last_seen=None,
        )
        _require(
            after.payload_sha256 == digest, "SNAPSHOT_CHANGED: catalog changed during verification"
        )
        return PublicVerification(True, digest, active, sequence, None)
    except (OtaError, _PublicFailure, httpx.HTTPError, ValueError) as exc:
        # Transport messages can include supplied client details; emit only the type.
        problem = type(exc).__name__ if isinstance(exc, httpx.HTTPError) else str(exc)
        return PublicVerification(False, digest, active, sequence, problem)
