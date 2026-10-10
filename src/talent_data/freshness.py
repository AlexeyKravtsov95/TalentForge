"""Product freshness: is the build the app creates new builds on still the newest one?

``evaluate_freshness`` compares the product build (``PRODUCT_BUNDLE`` of the mobile app,
passed in explicitly - this package never reads the mobile project) with the latest
*publishable* build a :class:`BuildSource` reports, and answers with one of three
statuses:

* ``CURRENT``  - the latest publishable build is the product build, and no newer build has
  an unknown background-download status;
* ``OUTDATED`` - a publishable build newer than the product build exists: the product
  dataset must be refreshed before the next release;
* ``UNKNOWN``  - the answer cannot be established: the source failed (network, HTTP,
  unexpected schema), no build is publishable, a newer build's ``is_bgdl`` is unknown, or
  the product build is newer than anything publishable. A failed lookup is never "no
  update".

"Publishable" is the project's existing notion (``latest_publishable_build``: ``is_bgdl``
explicitly ``false``). It is a necessary condition for production data, never proof that
a build is live - ``CURRENT`` does not mean "the realms run this build".

The result is a stable, machine-readable JSON document (``product-freshness`` v1) read by
the scheduled watcher (M5.DATA.WATCH, ``talent_data.watch``); nothing here publishes data
or changes the repository.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any

from talent_data.games import GameConfig
from talent_data.sources.base import (
    BuildCandidate,
    BuildSource,
    SourceError,
    classify_build,
    latest_build,
    latest_publishable_build,
)
from talent_data.sources.http import DownloadError
from talent_data.versions import ClientVersion

SCHEMA_VERSION = 1


class FreshnessStatus(StrEnum):
    CURRENT = "CURRENT"
    OUTDATED = "OUTDATED"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class FreshnessReport:
    game: str
    product_build: ClientVersion
    status: FreshnessStatus
    # Human-readable explanation; machine consumers branch on ``status`` only.
    reason: str
    source: str
    checked_at: datetime
    latest_publishable: BuildCandidate | None = None
    latest_detected: BuildCandidate | None = None
    # Newer than the product build but not publishable (background download / unknown).
    newer_unpublishable: tuple[BuildCandidate, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": SCHEMA_VERSION,
            "kind": "product-freshness",
            "game": self.game,
            "status": self.status.value,
            "productBuild": str(self.product_build),
            "latestPublishable": _candidate(self.latest_publishable),
            "latestDetected": _candidate(self.latest_detected),
            "newerUnpublishable": [_candidate(c) for c in self.newer_unpublishable],
            "reason": self.reason,
            "source": self.source,
            "checkedAt": self.checked_at.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        }

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), indent=2) + "\n"


def _candidate(candidate: BuildCandidate | None) -> dict[str, Any] | None:
    if candidate is None:
        return None
    info = candidate.info
    return {
        "version": str(info.version),
        "product": info.product,
        # the source's own naive timestamp, verbatim (wago.tools documents no zone)
        "createdAt": info.created_at.strftime("%Y-%m-%d %H:%M:%S"),
        "isBgdl": candidate.background_download,
    }


def evaluate_freshness(
    source: BuildSource,
    game: GameConfig,
    product_build: ClientVersion,
    now: datetime,
) -> FreshnessReport:
    """Compare ``product_build`` with what ``source`` reports. Never raises for a source
    failure: an unreachable or malformed source is ``UNKNOWN``, not ``CURRENT``."""

    def report(status: FreshnessStatus, reason: str, **facts: Any) -> FreshnessReport:
        return FreshnessReport(
            game=game.key,
            product_build=product_build,
            status=status,
            reason=reason,
            source=source.name,
            checked_at=now,
            **facts,
        )

    if product_build.branch != game.version_branch:
        raise ValueError(
            f"{product_build} is not a {game.display_name} build "
            f"(expected branch {game.version_branch}.x)"
        )
    try:
        builds = source.list_builds(game)
    except (DownloadError, SourceError) as exc:
        return report(FreshnessStatus.UNKNOWN, f"{source.name} could not be read: {exc}")
    if not builds:
        return report(
            FreshnessStatus.UNKNOWN,
            f"{source.name} lists no {game.display_name} builds "
            f"(products {list(game.products)}, branch {game.version_branch}.x)",
        )

    candidates = [classify_build(b) for b in builds]
    detected = classify_build(latest_build(builds))
    newer_unpublishable = tuple(
        sorted(
            (c for c in candidates if c.info.version > product_build and not c.publishable),
            key=lambda c: c.info.version,
        )
    )
    try:
        publishable = latest_publishable_build(candidates)
    except SourceError as exc:
        return report(
            FreshnessStatus.UNKNOWN,
            str(exc),
            latest_detected=detected,
            newer_unpublishable=newer_unpublishable,
        )
    facts: dict[str, Any] = {
        "latest_publishable": publishable,
        "latest_detected": detected,
        "newer_unpublishable": newer_unpublishable,
    }
    latest = publishable.info.version
    if latest > product_build:
        return report(
            FreshnessStatus.OUTDATED,
            f"{latest} is publishable (is_bgdl=false) and newer than the product build "
            f"{product_build}",
            **facts,
        )
    if latest < product_build:
        return report(
            FreshnessStatus.UNKNOWN,
            f"the product build {product_build} is newer than the latest publishable build "
            f"{latest} {source.name} reports",
            **facts,
        )
    unknown = [c for c in newer_unpublishable if c.background_download is None]
    if unknown:
        versions = ", ".join(str(c.info.version) for c in unknown)
        return report(
            FreshnessStatus.UNKNOWN,
            f"newer build(s) {versions} have no is_bgdl flag: publishability unknown",
            **facts,
        )
    reason = f"{product_build} is the latest publishable build {source.name} reports"
    if newer_unpublishable:
        versions = ", ".join(str(c.info.version) for c in newer_unpublishable)
        reason += f" (newer background-download builds, not publishable: {versions})"
    return report(FreshnessStatus.CURRENT, reason, **facts)
