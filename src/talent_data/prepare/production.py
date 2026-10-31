"""What the production OTA channel serves, read anonymously and verified (M5.OTA.AUTO.1).

The app's bundled ``PRODUCT_BUNDLE`` and the active production OTA release are different
facts: a device runs whatever the *signed* production catalog activates, and that can be a
newer build than the one compiled into the APK. A build is therefore never treated as
published because of a file in the repository, a release record or a staging catalog -
only because the production catalog served by the public origin lists it and that catalog
verified against the committed production keyring (``tf-prod-*`` keys only) on the wall
clock.

Everything is an anonymous ``GET`` through :func:`talent_data.ota.preflight.fetch` (no
redirects, no credentials), verified with the reference verifier the publisher and the
client checks share. Three answers, never guessed:

* ``PUBLISHED`` - a catalog is served and verified; its releases are the published ones;
* ``ABSENT``    - the origin confirms there is no catalog (403/404 - anonymous S3 answers
  403 for a missing key), so nothing is published;
* ``UNKNOWN``   - anything else: network failure, timeout, another HTTP status, an
  oversized, unsigned, wrongly signed, malformed or expired catalog.

:func:`mirror_production` copies a verified catalog and every object of every listed
release into a local store, re-hashing each object against its pin, so the existing gates
(G4, G5) and the review report run unchanged against what production really serves.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum
from typing import TYPE_CHECKING

import httpx

from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import MAX_CATALOG_BYTES, CatalogRelease, Channel, OtaCatalog
from talent_data.ota.preflight import fetch
from talent_data.ota.store import (
    BLOB_CACHE_CONTROL,
    CATALOG_CACHE_CONTROL,
    BlobStore,
    blob_key,
    catalog_key,
)

if TYPE_CHECKING:
    from talent_data.ota.verify import TrustedKey

CATALOG_KEY = catalog_key(Channel.PRODUCTION)


class ProductionStatus(StrEnum):
    PUBLISHED = "PUBLISHED"
    ABSENT = "ABSENT"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class ProductionState:
    status: ProductionStatus
    origin: str
    # Human-readable; automation branches on ``status`` only.
    reason: str
    catalog: OtaCatalog | None = None
    # The exact served envelope bytes and the SHA-256 of its signed payload (PUBLISHED only).
    envelope: bytes | None = None
    payload_sha256: str | None = None

    @property
    def active(self) -> CatalogRelease | None:
        if self.catalog is None:
            return None
        return next(
            r for r in self.catalog.releases if r.release_id == self.catalog.active_release_id
        )

    def releases_of(self, client_build: str) -> list[CatalogRelease]:
        if self.catalog is None:
            return []
        return [r for r in self.catalog.releases if r.client_build == client_build]

    def to_dict(self) -> dict[str, object]:
        catalog = self.catalog
        active = self.active
        return {
            "status": self.status.value,
            "origin": self.origin,
            "reason": self.reason,
            "sequence": None if catalog is None else catalog.sequence,
            "activeReleaseId": None if active is None else active.release_id,
            "activeClientBuild": None if active is None else active.client_build,
            "listedReleaseIds": [] if catalog is None else [r.release_id for r in catalog.releases],
            "expiresAt": None
            if catalog is None
            else catalog.expires_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "payloadSha256": self.payload_sha256,
        }


def read_production(
    client: httpx.Client,
    *,
    origin: str,
    keyring: Mapping[str, TrustedKey],
    game: str,
    now: datetime,
) -> ProductionState:
    """The production channel as a client sees it at ``now``. Never raises for the origin:
    an unreachable origin or an unverifiable catalog is ``UNKNOWN``, never "not published"."""
    from talent_data.ota.verify import OtaError, verify_catalog

    origin = origin.rstrip("/")
    response = fetch(client, f"{origin}/{CATALOG_KEY}", max_bytes=MAX_CATALOG_BYTES)
    if response.status in (403, 404):
        return ProductionState(
            ProductionStatus.ABSENT,
            origin,
            f"no production catalog is published (HTTP {response.status})",
        )
    if response.status != 200:
        cause = response.error or f"HTTP {response.status}"
        return ProductionState(
            ProductionStatus.UNKNOWN, origin, f"the production catalog could not be read: {cause}"
        )
    if len(response.body) > MAX_CATALOG_BYTES:
        return ProductionState(
            ProductionStatus.UNKNOWN,
            origin,
            f"the production catalog is {len(response.body)} bytes, over the 1 MiB limit",
        )
    try:
        verified = verify_catalog(
            response.body,
            keyring=keyring,
            game=game,
            channel=Channel.PRODUCTION,
            now=now,
            last_seen=None,
        )
    except OtaError as exc:
        return ProductionState(
            ProductionStatus.UNKNOWN,
            origin,
            f"the served production catalog did not verify ({exc.code.value}: {exc.detail}); "
            "what it lists is not treated as published",
        )
    catalog = verified.catalog
    return ProductionState(
        ProductionStatus.PUBLISHED,
        origin,
        f"verified production catalog: sequence {catalog.sequence}, active "
        f"{catalog.active_release_id}, {len(catalog.releases)} listed release(s)",
        catalog=catalog,
        envelope=response.body,
        payload_sha256=verified.payload_sha256,
    )


def mirror_production(
    client: httpx.Client, state: ProductionState, store: BlobStore, *, game: str
) -> int:
    """Copy the verified catalog and every object of every listed release into ``store``.

    Each release manifest is verified against its catalog pin and each file re-hashed
    against the manifest (the client's own checks), so the mirror holds exactly what
    production serves. Returns the number of blobs written; raises
    :class:`OtaPublisherError` on any download or verification failure."""
    from talent_data.ota.verify import OtaError, verify_release_files, verify_release_manifest

    if (
        state.status is not ProductionStatus.PUBLISHED
        or state.catalog is None
        or state.envelope is None
    ):
        raise OtaPublisherError(f"nothing to mirror: production is {state.status.value}")
    bodies: dict[str, bytes] = {}

    def blob(sha_ref: str) -> bytes:
        if sha_ref not in bodies:
            key = blob_key(sha_ref)
            response = fetch(client, f"{state.origin}/{key}")
            if response.status != 200:
                cause = response.error or f"HTTP {response.status}"
                raise OtaPublisherError(f"production object {key} could not be read: {cause}")
            bodies[sha_ref] = response.body
        return bodies[sha_ref]

    for entry in state.catalog.releases:
        try:
            manifest = verify_release_manifest(
                blob(entry.manifest_sha256),
                entry,
                game=game,
                supported_data_level=entry.min_data_level,
            )
            verify_release_files(manifest, lambda file: blob(file.sha256))
        except OtaError as exc:
            raise OtaPublisherError(
                f"production release {entry.release_id} did not verify: "
                f"{exc.code.value}: {exc.detail}"
            ) from exc
    for sha_ref, data in bodies.items():
        store.put(blob_key(sha_ref), data, cache_control=BLOB_CACHE_CONTROL)
    store.put(CATALOG_KEY, state.envelope, cache_control=CATALOG_CACHE_CONTROL)
    return len(bodies)
