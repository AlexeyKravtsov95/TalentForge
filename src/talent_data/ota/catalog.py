"""Build the next ``ota-catalog.v1`` of a channel from the live one.

The catalog is the only mutable object of a channel, and its publisher obligations are
the point of this module (contract §4):

* **append-only releases** - a release listed in a production catalog stays listed in
  every later catalog of that channel with identical members; dropping or altering one
  is refused here, not just disliked later;
* **monotonic sequence** - every new catalog (publication, rollback, re-sign) carries
  ``live.sequence + 1``; a rollback is a new catalog whose ``activeReleaseId`` names an
  older listed release, never a decrease of ``sequence``;
* **validity window** - positive and at most 180 days (default 45, OD-13).

The 1 MiB size limit is enforced on the written envelope bytes (``store.write_catalog``),
because the limit is over ``catalog.json`` as received.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timedelta

from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import (
    CatalogRelease,
    Channel,
    OtaCatalog,
    OtaReleaseManifest,
)
from talent_data.ota.verify import MAX_CATALOG_VALIDITY, release_sort_key

#: OD-13: 45-day catalogs, re-signed every 30 days or with any change.
DEFAULT_VALIDITY = timedelta(days=45)

_VALIDITY_CAP = MAX_CATALOG_VALIDITY


def catalog_entry(
    manifest_bytes: bytes, manifest: OtaReleaseManifest, published_at: datetime
) -> CatalogRelease:
    """The catalog's pin of a release manifest: its hash, length and identity."""
    from talent_data.ota.verify import sha256_ref

    return CatalogRelease(
        release_id=manifest.release_id,
        client_build=manifest.client_build,
        min_data_level=manifest.min_data_level,
        manifest_sha256=sha256_ref(manifest_bytes),
        manifest_byte_length=len(manifest_bytes),
        total_byte_length=manifest.total_byte_length,
        published_at=published_at,
    )


def serialize_catalog(catalog: OtaCatalog) -> bytes:
    """The payload bytes that get signed: indent-2 JSON, trailing newline, ``Z`` stamps."""
    return (
        json.dumps(catalog.model_dump(mode="json", by_alias=True), indent=2, ensure_ascii=False)
        + "\n"
    ).encode("utf-8")


@dataclass(frozen=True)
class CatalogUpdate:
    """One next catalog, plus the exact state it was built on."""

    catalog: OtaCatalog
    payload: bytes
    #: the live sequence the update was derived from (``None`` = channel is empty)
    base_sequence: int | None


def next_catalog(
    live: OtaCatalog | None,
    *,
    game: str,
    channel: Channel,
    active_release_id: str,
    append: CatalogRelease | None = None,
    issued_at: datetime,
    validity: timedelta = DEFAULT_VALIDITY,
) -> CatalogUpdate:
    """The next catalog of a channel: ``live`` plus (optionally) one appended release.

    Refuses to drop or alter any listed release (append-only), keeps the sequence
    monotonic, requires the active release to be listed and the validity window
    positive and within the 180-day cap.
    """
    if live is not None:
        if live.game != game or live.channel != channel:
            raise OtaPublisherError(
                f"live catalog is {live.game}/{live.channel.value}, building {game}/{channel.value}"
            )
        releases = list(live.releases)
    else:
        releases = []

    if append is not None:
        existing = next((r for r in releases if r.release_id == append.release_id), None)
        if existing is None:
            releases.append(append)
        elif existing != append:
            # Identity of a listed release is immutable (contract §4 append-only).
            changed = [
                f"{field}: {getattr(existing, field)} -> {getattr(append, field)}"
                for field in (
                    "client_build",
                    "min_data_level",
                    "manifest_sha256",
                    "manifest_byte_length",
                    "total_byte_length",
                )
                if getattr(existing, field) != getattr(append, field)
            ]
            raise OtaPublisherError(
                f"release {append.release_id} is already listed with different identity: "
                f"{'; '.join(changed) or 'publishedAt differs'}"
            )

    releases.sort(key=lambda r: release_sort_key(r.release_id))
    ids = [r.release_id for r in releases]
    if len(ids) != len(set(ids)):
        raise OtaPublisherError(f"duplicate release id in catalog: {sorted(ids)}")
    if active_release_id not in ids:
        raise OtaPublisherError(
            f"activeReleaseId {active_release_id!r} is not listed (releases: {ids})"
        )

    if validity <= timedelta(0):
        raise OtaPublisherError(f"validity must be positive, got {validity}")
    if validity > _VALIDITY_CAP:
        raise OtaPublisherError(f"validity {validity} exceeds the {_VALIDITY_CAP.days}-day cap")
    expires_at = issued_at + validity
    catalog = OtaCatalog(
        schema_version=1,
        game=game,
        channel=channel,
        sequence=1 if live is None else live.sequence + 1,
        issued_at=issued_at,
        expires_at=expires_at,
        active_release_id=active_release_id,
        releases=releases,
    )
    payload = serialize_catalog(catalog)
    return CatalogUpdate(
        catalog=catalog,
        payload=payload,
        base_sequence=None if live is None else live.sequence,
    )


def resign_catalog(
    live: OtaCatalog, *, issued_at: datetime, validity: timedelta = DEFAULT_VALIDITY
) -> CatalogUpdate:
    """Re-sign before expiry: same releases, same active release, new sequence/stamps."""
    return next_catalog(
        live,
        game=live.game,
        channel=live.channel,
        active_release_id=live.active_release_id,
        append=None,
        issued_at=issued_at,
        validity=validity,
    )


def rollback_catalog(
    live: OtaCatalog,
    *,
    active_release_id: str,
    issued_at: datetime,
    validity: timedelta = DEFAULT_VALIDITY,
) -> CatalogUpdate:
    """Rollback: a NEW catalog with a higher sequence whose active release is older."""
    if active_release_id == live.active_release_id:
        raise OtaPublisherError(
            f"{active_release_id} is already active; re-issuing it is `resign`, not `rollback`"
        )
    return next_catalog(
        live,
        game=live.game,
        channel=live.channel,
        active_release_id=active_release_id,
        append=None,
        issued_at=issued_at,
        validity=validity,
    )


def find_release(catalog: OtaCatalog, release_id: str) -> CatalogRelease | None:
    return next((r for r in catalog.releases if r.release_id == release_id), None)


def releases_of_build(catalog: OtaCatalog, client_build: str) -> list[CatalogRelease]:
    return [r for r in catalog.releases if r.client_build == client_build]


def catalog_summary(catalog: OtaCatalog) -> str:
    return (
        f"sequence {catalog.sequence} ({catalog.game}/{catalog.channel.value}), "
        f"{len(catalog.releases)} release(s), active {catalog.active_release_id}, "
        f"valid until {catalog.expires_at:%Y-%m-%dT%H:%M:%SZ}"
    )
