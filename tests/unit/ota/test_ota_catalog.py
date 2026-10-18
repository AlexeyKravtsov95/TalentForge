"""Catalog construction rules (M5.OTA.2): append-only, monotonic, bounded.

Contract §4's publisher obligations, executable: releases are only ever added (with
immutable identity), ``sequence`` strictly increases across publication, rollback and
re-sign, the active release must be listed, and the validity window stays within the
180-day cap.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from talent_data.ota.catalog import (
    DEFAULT_VALIDITY,
    next_catalog,
    resign_catalog,
    rollback_catalog,
    serialize_catalog,
)
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import CatalogRelease, Channel, OtaCatalog
from talent_data.ota.verify import parse_strict

ISSUED = datetime(2026, 10, 18, 0, 0, tzinfo=UTC)
LATER = ISSUED + timedelta(days=30)
A = "1.60.1.70374-r1"
B = "1.60.1.70450-r1"
A2 = "1.60.1.70374-r2"


def make(release_id: str, *, min_level: int = 1) -> CatalogRelease:
    """A syntactically valid catalog entry, distinct per release id."""
    build, revision = release_id.rsplit("-r", 1)
    return CatalogRelease(
        release_id=release_id,
        client_build=build,
        min_data_level=min_level,
        manifest_sha256="sha256:" + (release_id.encode().hex() * 4)[:64],
        manifest_byte_length=1000 + int(revision),
        total_byte_length=5000,
        published_at=ISSUED,
    )


def first_catalog(active: str = A, **kwargs: object) -> object:
    return next_catalog(
        None,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=active,
        append=make(active),
        issued_at=ISSUED,
        **kwargs,  # type: ignore[arg-type]
    )


def test_first_publication() -> None:
    update = first_catalog()
    assert update.catalog.sequence == 1
    assert update.base_sequence is None
    assert [r.release_id for r in update.catalog.releases] == [A]
    assert update.catalog.active_release_id == A


def test_append_keeps_every_release_and_increments_sequence() -> None:
    first = first_catalog().catalog
    second = next_catalog(
        first,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=B,
        append=make(B),
        issued_at=LATER,
    )
    assert second.catalog.sequence == first.sequence + 1
    assert second.base_sequence == first.sequence
    assert [r.release_id for r in second.catalog.releases] == [A, B]
    # the previously published entry is carried over unchanged (same publishedAt)
    assert second.catalog.releases[0] == first.releases[0]


def test_appending_the_same_release_again_is_a_no_op() -> None:
    first = first_catalog().catalog
    again = next_catalog(
        first,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=A,
        append=make(A),
        issued_at=LATER,
    )
    assert again.catalog.sequence == first.sequence + 1
    assert again.catalog.releases == first.releases


def test_identity_of_a_listed_release_is_immutable() -> None:
    first = first_catalog().catalog
    with pytest.raises(OtaPublisherError, match="already listed with different identity"):
        next_catalog(
            first,
            game="forever",
            channel=Channel.PRODUCTION,
            active_release_id=A,
            append=make(A, min_level=2),
            issued_at=LATER,
        )


def test_active_release_must_be_listed() -> None:
    with pytest.raises(OtaPublisherError, match="not listed"):
        next_catalog(
            None,
            game="forever",
            channel=Channel.PRODUCTION,
            active_release_id=B,  # never appended
            append=make(A),
            issued_at=ISSUED,
        )


def test_channel_mismatch_is_refused() -> None:
    prod = first_catalog().catalog
    with pytest.raises(OtaPublisherError, match="live catalog is"):
        next_catalog(
            prod,
            game="forever",
            channel=Channel.STAGING,
            active_release_id=A,
            issued_at=LATER,
        )


def test_validity_window_bounds() -> None:
    with pytest.raises(OtaPublisherError, match="positive"):
        first_catalog(validity=timedelta(0))
    with pytest.raises(OtaPublisherError, match="cap"):
        first_catalog(validity=timedelta(days=181))
    exactly_cap = first_catalog(validity=timedelta(days=180))
    assert exactly_cap.catalog.expires_at - exactly_cap.catalog.issued_at == timedelta(days=180)


def test_rollback_is_a_new_catalog_with_an_older_active_release() -> None:
    live = next_catalog(
        first_catalog().catalog,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=B,
        append=make(B),
        issued_at=LATER,
    ).catalog
    rolled = rollback_catalog(live, active_release_id=A, issued_at=LATER + timedelta(days=1))
    assert rolled.catalog.sequence == live.sequence + 1
    assert rolled.catalog.active_release_id == A
    assert [r.release_id for r in rolled.catalog.releases] == [A, B]  # nobody is unlisted


def test_rollback_to_the_active_release_is_resign_not_rollback() -> None:
    live = first_catalog().catalog
    with pytest.raises(OtaPublisherError, match="resign"):
        rollback_catalog(live, active_release_id=A, issued_at=LATER)


def test_rollback_target_must_be_listed() -> None:
    live = first_catalog().catalog
    with pytest.raises(OtaPublisherError, match="not listed"):
        rollback_catalog(live, active_release_id=A2, issued_at=LATER)


def test_resign_changes_only_sequence_and_window() -> None:
    live = first_catalog().catalog
    resigned = resign_catalog(live, issued_at=LATER)
    assert resigned.catalog.sequence == live.sequence + 1
    assert resigned.catalog.active_release_id == live.active_release_id
    assert resigned.catalog.releases == live.releases
    assert resigned.catalog.expires_at == LATER + DEFAULT_VALIDITY


def test_payload_serialization_round_trips_strictly() -> None:
    update = first_catalog()
    parsed = parse_strict(OtaCatalog, update.payload)
    assert parsed == update.catalog
    assert serialize_catalog(parsed) == update.payload
