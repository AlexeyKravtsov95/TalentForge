"""Object storage of the OTA origin, and the publication write protocol.

Contract §1 layout, served by a static HTTPS origin (object storage behind a CDN):

* ``v1/blobs/sha256/<64 lowercase hex>`` - immutable, written once, named by content;
* ``v1/channels/{production,staging}/catalog.json`` - the only mutable object.

:class:`BlobStore` is the port. :class:`LocalDirectoryStore` is the adapter used by
tests, dry runs and offline rehearsals - a plain directory standing in for the bucket.
A real S3-compatible adapter (httpx + request signing) is deliberately not shipped in
M5.OTA.2: request signing (AWS SigV4) would add a large surface that cannot be tested
against real hosting from this repository; the gap and its requirements are recorded in
the publisher runbook. Every mutating path goes through the same protocol here, so
swapping in a remote adapter changes one implementation and no ordering rule.

The write protocol (publisher design §3) is enforced in code, not by convention:

1. blobs first - each missing one written, then verified by reading it back and
   re-hashing it (:func:`upload_blobs`);
2. every object the new catalog references resolves in the store
   (:func:`ensure_catalog_references`);
3. the live catalog's ``sequence`` is re-read and must equal the sequence the new
   catalog was built on - one publisher at a time (:func:`write_catalog`);
4. only then is ``catalog.json`` replaced - a single object put, and never when
   oversized (the 1 MiB cap of contract §2 rule 10).
"""

from __future__ import annotations

import os
import tempfile
from collections.abc import Mapping
from datetime import datetime, timedelta
from pathlib import Path
from typing import Protocol

from talent_data.models.manifest import DatasetManifest
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import (
    MAX_CATALOG_BYTES,
    CatalogRelease,
    Channel,
    FileRole,
    OtaCatalog,
    OtaReleaseManifest,
)
from talent_data.ota.verify import TrustedKey, sha256_ref

BLOB_PREFIX = "v1/blobs/sha256/"
#: contract §1 recommended cache headers - the publisher sets them, the client ignores them
BLOB_CACHE_CONTROL = "public, max-age=31536000, immutable"
CATALOG_CACHE_CONTROL = "public, max-age=300"


def blob_key(sha_ref: str) -> str:
    """``sha256:<hex>`` -> ``v1/blobs/sha256/<hex>``."""
    return BLOB_PREFIX + sha_ref.removeprefix("sha256:")


def catalog_key(channel: Channel) -> str:
    return f"v1/channels/{channel.value}/catalog.json"


class BlobStore(Protocol):
    """The origin as the publisher sees it: get/put/exists/list by object key."""

    def get(self, key: str) -> bytes:
        """The object's bytes; raises :class:`OtaPublisherError` when missing."""
        ...

    def put(self, key: str, data: bytes, *, cache_control: str) -> None: ...

    def exists(self, key: str) -> bool: ...

    def list(self, prefix: str) -> list[str]: ...

    def delete(self, key: str) -> None:
        """Only `ota gc` deletes, and only objects no listed release references."""
        ...


class LocalDirectoryStore:
    """A directory standing in for the bucket: ``<root>/<key>`` per object.

    Used by tests, dry runs and offline rehearsals. Cache-Control is an HTTP-layer
    concern with no local representation; the value is still required and passed
    through (and asserted in tests) so a remote adapter inherits the contract headers.
    """

    def __init__(self, root: Path) -> None:
        self.root = Path(root)

    def _path(self, key: str) -> Path:
        parts = Path(key).parts
        if (
            not key
            or key.startswith("/")
            or ".." in parts
            or "\\" in key
            or not (key.startswith(BLOB_PREFIX) or key.startswith("v1/channels/"))
        ):
            raise OtaPublisherError(f"refusing to touch non-OTA object key {key!r}")
        return self.root / key

    def get(self, key: str) -> bytes:
        path = self._path(key)
        try:
            return path.read_bytes()
        except FileNotFoundError as exc:
            raise OtaPublisherError(f"object {key} not found in {self.root}") from exc

    def put(self, key: str, data: bytes, *, cache_control: str) -> None:
        if not cache_control:
            raise OtaPublisherError(f"object {key}: cache-control is required")
        path = self._path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        # write-then-rename: a reader (or a crash) sees the old object or the new one,
        # never a half-written one - the local twin of the single-object PUT the
        # contract relies on for the catalog's atomic pointer update.
        handle, tmp_name = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
        tmp = Path(tmp_name)
        try:
            with os.fdopen(handle, "wb") as sink:
                sink.write(data)
            tmp.replace(path)
        except BaseException:
            tmp.unlink(missing_ok=True)
            raise

    def exists(self, key: str) -> bool:
        return self._path(key).is_file()

    def list(self, prefix: str) -> list[str]:
        base = self._path(prefix)
        if not base.exists():
            return []
        if base.is_file():
            return [prefix]
        return sorted(
            path.relative_to(self.root).as_posix()
            for path in base.rglob("*")
            if path.is_file() and not path.name.startswith(".")
        )

    def delete(self, key: str) -> None:
        self._path(key).unlink(missing_ok=True)


# --------------------------------------------------------------------------- read path


def read_live_catalog(
    store: BlobStore,
    channel: Channel,
    *,
    keyring: Mapping[str, TrustedKey],
    game: str,
    now: datetime,
    allow_expired: bool = False,
) -> OtaCatalog | None:
    """The live, signature-verified catalog of ``channel``; ``None`` when none exists.

    The publisher never builds on a catalog it cannot verify: a tampered or wrongly
    signed live catalog aborts the pipeline instead of being overwritten.

    ``allow_expired`` is the publisher's recovery path (runbook §4): a catalog whose
    ``expiresAt`` has lapsed still has a trustworthy signature, structure and identity,
    so ``resign``/``rollback``/``attach-signature`` may rebuild on it to issue a fresh
    one - everything except the clock check still runs. Clients keep rejecting expired
    catalogs exactly as before.
    """
    key = catalog_key(channel)
    if not store.exists(key):
        return None
    envelope = store.get(key)
    from talent_data.ota.verify import (  # lazy: needs the `ota` extra (cryptography)
        OtaError,
        OtaErrorCode,
        VerifiedCatalog,
        strict_b64decode,
        verify_catalog,
    )

    def verify(at: datetime) -> VerifiedCatalog:
        return verify_catalog(
            envelope, keyring=keyring, game=game, channel=channel, now=at, last_seen=None
        )

    try:
        verified = verify(now)
    except OtaError as exc:
        if exc.code is OtaErrorCode.CATALOG_EXPIRED and allow_expired:
            # re-verify with the clock inside the catalog's own validity window:
            # signature, shape, identity and consistency all still run; only the
            # lapsed window is forgiven, for the issuer of the *next* catalog.
            from talent_data.ota.models import SignedEnvelope
            from talent_data.ota.verify import parse_strict

            payload = strict_b64decode(parse_strict(SignedEnvelope, envelope).payload)
            issued = parse_strict(OtaCatalog, payload).issued_at
            try:
                verified = verify(issued + timedelta(seconds=1))
            except OtaError as second:
                raise OtaPublisherError(
                    f"live {channel.value} catalog failed verification: {second}"
                ) from second
        else:
            raise OtaPublisherError(
                f"live {channel.value} catalog failed verification: {exc}"
            ) from exc
    return verified.catalog


def read_release_manifest(
    store: BlobStore, entry: CatalogRelease
) -> tuple[bytes, OtaReleaseManifest]:
    """Fetch the release manifest blob pinned by a catalog entry, verifying the pin.

    The bytes must hash and size exactly to what the (signed) catalog pins before
    anything trusts their content - a manifest that was corrupted on the server into
    different, still-parseable JSON must never silently shrink the set of blobs a
    caller believes are referenced (gate G4/G5 diffs, ``report``, and above all
    ``gc``, which must never delete a blob a real release needs)."""
    data = store.get(blob_key(entry.manifest_sha256))
    if len(data) != entry.manifest_byte_length or sha256_ref(data) != entry.manifest_sha256:
        raise OtaPublisherError(
            f"release manifest of {entry.release_id} does not match its catalog pin "
            f"({len(data)} bytes {sha256_ref(data)}, pinned {entry.manifest_byte_length} "
            f"bytes {entry.manifest_sha256})"
        )
    from talent_data.ota.verify import parse_strict

    try:
        manifest = parse_strict(OtaReleaseManifest, data)
    except ValueError as exc:
        raise OtaPublisherError(
            f"release manifest of {entry.release_id} does not parse: {exc}"
        ) from exc
    return data, manifest


def dataset_manifest_of_release(store: BlobStore, entry: CatalogRelease) -> DatasetManifest:
    """The dataset manifest document of a published release, from its blobs."""
    from talent_data.ota.verify import parse_strict

    _, manifest = read_release_manifest(store, entry)
    file = next(f for f in manifest.files if f.role is FileRole.DATASET_MANIFEST)
    data = ensure_blob(store, file.sha256)
    try:
        return parse_strict(DatasetManifest, data)
    except ValueError as exc:
        raise OtaPublisherError(
            f"dataset manifest of {entry.release_id} does not parse: {exc}"
        ) from exc


# --------------------------------------------------------------------------- write path


def upload_blobs(store: BlobStore, blobs: Mapping[str, bytes]) -> list[str]:
    """Write the missing immutable blobs, each verified by reading it back.

    Returns the keys actually written. An existing object is never rewritten: its name
    is its hash, so a present object is either already correct or the store is corrupt
    - and a corrupt present object is caught by :func:`ensure_blob`.
    """
    written: list[str] = []
    for sha_ref, data in sorted(blobs.items()):
        key = blob_key(sha_ref)
        if store.exists(key):
            continue
        store.put(key, data, cache_control=BLOB_CACHE_CONTROL)
        ensure_blob(store, sha_ref)
        written.append(key)
    return written


def ensure_blob(store: BlobStore, sha_ref: str) -> bytes:
    """The blob must exist and hash to its own name."""
    data = store.get(blob_key(sha_ref))
    if sha256_ref(data) != sha_ref:
        raise OtaPublisherError(f"blob {sha_ref} does not hash to its name")
    return data


def ensure_catalog_references(store: BlobStore, catalog: OtaCatalog) -> None:
    """Everything the catalog references must resolve in the store, byte for byte.

    A catalog is never written while any object it will make clients fetch is missing
    or corrupt: every listed release's manifest must match its catalog pin (hash,
    length, identity) and every file the manifest lists must exist and hash to its
    own name."""
    for entry in catalog.releases:
        _, manifest = read_release_manifest(store, entry)  # verifies the pin
        identity = {
            "releaseId": (manifest.release_id, entry.release_id),
            "clientBuild": (manifest.client_build, entry.client_build),
            "minDataLevel": (manifest.min_data_level, entry.min_data_level),
            "totalByteLength": (manifest.total_byte_length, entry.total_byte_length),
        }
        for name, (found, expected) in identity.items():
            if found != expected:
                raise OtaPublisherError(
                    f"release manifest of {entry.release_id} disagrees with its catalog "
                    f"entry on {name}: {found!r} != {expected!r}"
                )
        for file in manifest.files:
            ensure_blob(store, file.sha256)


def write_catalog(
    store: BlobStore,
    channel: Channel,
    envelope: bytes,
    *,
    base_sequence: int | None,
    keyring: Mapping[str, TrustedKey],
    game: str,
    now: datetime,
    allow_expired_live: bool = False,
) -> None:
    """Replace the channel's catalog - last, once, and only when it is safe.

    * the envelope must respect the 1 MiB limit (contract §2 rule 10) - a catalog over
      the limit is never written, even if a client would reject it anyway;
    * the live ``sequence`` is re-read and must still equal ``base_sequence`` (the
      state the new catalog was built on): a concurrent publication aborts this one;
    * only then is the single object put.

    ``allow_expired_live`` lets a recovery re-issue succeed over a lapsed (but still
    signature-verified) live catalog.
    """
    if len(envelope) > MAX_CATALOG_BYTES:
        raise OtaPublisherError(
            f"catalog.json would be {len(envelope)} bytes, over the "
            f"{MAX_CATALOG_BYTES}-byte limit; refusing to write"
        )
    live = read_live_catalog(
        store, channel, keyring=keyring, game=game, now=now, allow_expired=allow_expired_live
    )
    live_sequence = None if live is None else live.sequence
    if live_sequence != base_sequence:
        raise OtaPublisherError(
            f"live {channel.value} catalog is at sequence {live_sequence}, but this "
            f"catalog was built on {base_sequence}; a concurrent publication happened - "
            "aborting (nothing was overwritten)"
        )
    store.put(catalog_key(channel), envelope, cache_control=CATALOG_CACHE_CONTROL)


def garbage_blob_keys(
    store: BlobStore,
    catalogs: Mapping[Channel, OtaCatalog],
) -> tuple[list[str], list[str]]:
    """(referenced, garbage) blob keys: everything listed vs. unreferenced objects.

    ``gc`` lists before deleting; deletion happens only in the CLI with ``--confirm``.
    """
    referenced: set[str] = set()
    for catalog in catalogs.values():
        for entry in catalog.releases:
            referenced.add(blob_key(entry.manifest_sha256))
            _, manifest = read_release_manifest(store, entry)
            for file in manifest.files:
                referenced.add(blob_key(file.sha256))
    stored = store.list(BLOB_PREFIX)
    return sorted(referenced), sorted(key for key in stored if key not in referenced)
