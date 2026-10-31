"""Read-only observation of the public production OTA catalog for notifications.

The owner publishes production from their own machine; nothing of ours writes there.
This module only watches, exactly the way the preparation's detection already does
(:func:`talent_data.prepare.production.read_production`): an anonymous ``GET`` through the
preflight fetcher, verified with the reference verifier against the committed production
keyring (``tf-prod-*`` only) on the wall clock. The default mode also checks the active
manifest. Full mode verifies all listed
manifests and files and re-reads the catalog; the CLI requires it before recording a
baseline or notifying a changed activation.

Nothing raises and nothing is guessed: :class:`ProductionObservation` carries the three
possible catalog answers (``PUBLISHED`` / ``ABSENT`` / ``UNKNOWN``) plus, for a verified
catalog, the active release id, the sequence and the manifest check result. A re-signed
envelope or a sequence bump does not change the active release id - whether that changed
is the caller's (deduplicated) decision, not this observation's.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime
from typing import TYPE_CHECKING

import httpx

from talent_data.ota.preflight import fetch
from talent_data.ota.store import blob_key
from talent_data.prepare.production import (
    ProductionState,
    ProductionStatus,
    read_production,
)

if TYPE_CHECKING:  # the verifier needs the `ota` extra; it is imported where used
    from talent_data.ota.verify import TrustedKey


@dataclass(frozen=True)
class ProductionObservation:
    """What the public production origin answered, as notification facts."""

    status: ProductionStatus
    origin: str
    # The catalog reader's own human-readable reason (English, diagnostics).
    reason: str
    # PUBLISHED only: the active release of the verified catalog.
    release_id: str | None = None
    sequence: int | None = None
    # PUBLISHED only: ``None`` when the active release's manifest verified.
    manifest_problem: str | None = None
    # The Russian one-liner the release notification shows as the endpoint check result.
    checked: str = ""


def observe_production(
    client: httpx.Client,
    *,
    origin: str,
    keyring: Mapping[str, TrustedKey],
    game: str,
    now: datetime,
    verify_files: bool = False,
) -> ProductionObservation:
    """Observe production; full mode proves every listed release before success."""
    if verify_files:
        from talent_data.ota.automation.public_verify import verify_publication

        result = verify_publication(client, origin=origin, keyring=keyring, game=game, now=now)
        return ProductionObservation(
            ProductionStatus.PUBLISHED if result.active_release_id else ProductionStatus.UNKNOWN,
            origin.rstrip("/"),
            "full public content verified"
            if result.ok
            else f"full public verification failed: {result.problem}",
            release_id=result.active_release_id,
            sequence=result.sequence,
            manifest_problem=result.problem,
            checked=(
                "каталог получен, production-подпись проверена; все манифесты и файлы "
                "прошли reference verifier; каталог повторно прочитан и не изменился"
                if result.ok
                else f"полная проверка публичных файлов не пройдена: {result.problem}"
            ),
        )
    state: ProductionState = read_production(
        client, origin=origin, keyring=keyring, game=game, now=now
    )
    if state.status is not ProductionStatus.PUBLISHED or state.catalog is None:
        return ProductionObservation(state.status, state.origin, state.reason)
    active = state.active
    if active is None:
        return ProductionObservation(
            ProductionStatus.UNKNOWN, state.origin, "the verified catalog names no active release"
        )
    sequence = state.catalog.sequence
    manifest_problem = _check_active_manifest(client, state, active.manifest_sha256, game)
    if manifest_problem is None:
        checked = (
            "каталог получен, подпись проверена (production keyring tf-prod-*, "
            f"sequence {sequence}); манифест активного релиза получен и сверен с пином каталога"
        )
    else:
        checked = (
            f"каталог получен и подпись проверена (sequence {sequence}), но "
            f"манифест активного релиза не прошёл проверку: {manifest_problem}"
        )
    return ProductionObservation(
        ProductionStatus.PUBLISHED,
        state.origin,
        state.reason,
        release_id=active.release_id,
        sequence=sequence,
        manifest_problem=manifest_problem,
        checked=checked,
    )


def _check_active_manifest(
    client: httpx.Client, state: ProductionState, manifest_sha256: str, game: str
) -> str | None:
    """Fetch and re-verify the active release's manifest; ``None`` = passed."""
    from talent_data.ota.verify import OtaError, verify_release_manifest

    active = state.active
    assert active is not None  # the caller checked
    response = fetch(
        client, f"{state.origin}/{blob_key(manifest_sha256)}", max_bytes=active.manifest_byte_length
    )
    if response.status != 200:
        cause = response.error or f"HTTP {response.status}"
        return f"манифест не получен с публичного endpoint ({cause})"
    try:
        verify_release_manifest(
            response.body, active, game=game, supported_data_level=active.min_data_level
        )
    except OtaError as exc:
        return f"манифест активного релиза не прошёл проверку: {exc.code.value}: {exc.detail}"
    return None
