"""Does a Forever build need preparing for an OTA release? (M5.OTA.AUTO.1)

:func:`decide` is pure: it combines facts gathered elsewhere and answers with one of three
actions, written as the ``ota-prepare-detection`` v1 document the preparation reads back:

* ``PREPARE``   - a publishable build (``is_bgdl`` explicitly ``false``, the existing
  freshness rule) is newer than what an up-to-date install shows, and the signed production
  catalog does not list it;
* ``NO_ACTION`` - nothing to prepare: no newer build, the build is the app's bundled
  ``PRODUCT_BUNDLE``, the *verified* production catalog already lists it, or the same inputs
  were already prepared (a kept artifact of a finished preparation);
* ``UNKNOWN``   - the answer cannot be established: the build index failed or is
  ambiguous (``FreshnessStatus.UNKNOWN``), a requested build is not listed or not
  publishable, or the base build has no committed dataset.

Facts it never confuses: ``PRODUCT_BUNDLE`` (compiled into the APK) is not the active
production OTA release, and a build is "already published" only when a production catalog
that verified against the committed production keyring lists it - an unverifiable catalog
publishes nothing, so the build is prepared (preparing publishes nothing either).

The base of the diff is what an up-to-date install shows: the newer of ``PRODUCT_BUNDLE``
and the active production release, or an explicitly requested committed build.
"""

from __future__ import annotations

from collections.abc import Collection, Sequence
from datetime import datetime
from enum import StrEnum
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

from talent_data.freshness import FreshnessReport, FreshnessStatus
from talent_data.games import GameConfig
from talent_data.ota.gates import release_revision
from talent_data.prepare.production import ProductionState, ProductionStatus
from talent_data.sources.base import BuildInfo, classify_build
from talent_data.versions import ClientVersion

TIMESTAMP_FORMAT = "%Y-%m-%dT%H:%M:%SZ"
ARTIFACT_PREFIX = "ota-prepare-"


class Action(StrEnum):
    PREPARE = "PREPARE"
    NO_ACTION = "NO_ACTION"
    UNKNOWN = "UNKNOWN"


class BaseKind(StrEnum):
    PRODUCTION_RELEASE = "productionRelease"  # the active release of the verified catalog
    PRODUCT_BUNDLE = "productBundle"  # the dataset compiled into the app
    REQUESTED = "requested"  # chosen by the operator (workflow input / --base)


class PriorListing(StrEnum):
    LISTED = "listed"  # the kept artifacts were listed and compared
    UNAVAILABLE = "unavailable"  # the listing failed: preparing again is harmless
    NOT_CHECKED = "notChecked"  # local runs


class _Strict(BaseModel):
    # Closed contract: unknown fields and coerced wire types are rejected when read back.
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, strict=True, extra="forbid", frozen=True
    )


class ProductionFact(_Strict):
    status: ProductionStatus
    origin: str
    reason: str
    sequence: int | None
    active_release_id: str | None
    active_client_build: str | None
    listed_release_ids: list[str]
    expires_at: str | None
    payload_sha256: str | None


class DetectionDocument(_Strict):
    schema_version: Literal[1]
    kind: Literal["ota-prepare-detection"]
    game: str
    checked_at: str
    action: Action
    reason: str
    main_commit: str
    product_build: str
    source_status: FreshnessStatus
    source_reason: str
    production: ProductionFact
    requested_build: str | None
    requested_base: str | None
    forced: bool
    candidate_build: str | None
    base_build: str | None
    base_kind: BaseKind | None
    base_release_id: str | None
    revision: int | None
    preparation_key: str | None
    prior_listing: PriorListing
    prior_artifact: str | None

    def to_json(self) -> str:
        return self.model_dump_json(by_alias=True, indent=2) + "\n"


def read_detection(path: Path) -> DetectionDocument:
    """Read a detection document back, strictly (raises ``ValueError`` on any deviation)."""
    return DetectionDocument.model_validate_json(path.read_bytes())


def artifact_name(preparation_key: str, status: str) -> str:
    """The name a finished preparation's artifact carries: inputs and outcome."""
    return f"{ARTIFACT_PREFIX}{preparation_key}-{status}"


#: outcomes a re-run with the same inputs would reproduce (UNKNOWN is retried)
FINAL_STATUSES = ("PREPARED", "BLOCKED")


def production_fact(state: ProductionState) -> ProductionFact:
    return ProductionFact.model_validate(state.to_dict(), strict=False)


def decide(
    *,
    game: GameConfig,
    freshness: FreshnessReport,
    builds: Sequence[BuildInfo] | None,
    production: ProductionState,
    product_build: ClientVersion,
    committed_builds: Collection[str],
    main_commit: str,
    now: datetime,
    requested_build: ClientVersion | None = None,
    requested_base: ClientVersion | None = None,
    force: bool = False,
    prior_artifacts: Collection[str] | None = None,
    unlisted: PriorListing = PriorListing.NOT_CHECKED,
) -> DetectionDocument:
    """Decide what to do. ``builds`` is the index the freshness check read (``None`` when
    it could not be read); ``committed_builds`` the builds with a dataset on ``main``;
    ``prior_artifacts`` the names of kept artifacts (``None``: not listed, for the reason
    ``unlisted`` records)."""

    facts: dict[str, object] = {
        "schema_version": 1,
        "kind": "ota-prepare-detection",
        "game": game.key,
        "checked_at": now.strftime(TIMESTAMP_FORMAT),
        "main_commit": main_commit,
        "product_build": str(product_build),
        "source_status": freshness.status,
        "source_reason": freshness.reason,
        "production": production_fact(production),
        "requested_build": None if requested_build is None else str(requested_build),
        "requested_base": None if requested_base is None else str(requested_base),
        "forced": force,
        "candidate_build": None,
        "base_build": None,
        "base_kind": None,
        "base_release_id": None,
        "revision": None,
        "preparation_key": None,
        "prior_listing": unlisted if prior_artifacts is None else PriorListing.LISTED,
        "prior_artifact": None,
    }

    def result(action: Action, reason: str, **more: object) -> DetectionDocument:
        return DetectionDocument.model_validate(
            {**facts, **more, "action": action, "reason": reason}, strict=False
        )

    # 1. the candidate: the latest publishable build, or the requested one
    if requested_build is None:
        if freshness.status is FreshnessStatus.UNKNOWN or freshness.latest_publishable is None:
            return result(Action.UNKNOWN, f"the build index is inconclusive: {freshness.reason}")
        candidate = freshness.latest_publishable.info.version
    else:
        if builds is None:
            return result(Action.UNKNOWN, f"the build index could not be read: {freshness.reason}")
        info = next((b for b in builds if b.version == requested_build), None)
        if info is None:
            return result(
                Action.UNKNOWN,
                f"{requested_build} is not a {game.display_name} build the index lists",
            )
        listed = classify_build(info)
        if not listed.publishable:
            return result(
                Action.UNKNOWN,
                f"{requested_build} is not publishable (is_bgdl={listed.background_download}); "
                "only a build with is_bgdl explicitly false is prepared",
            )
        candidate = requested_build
    facts["candidate_build"] = str(candidate)

    # 2. already published: only a *verified* production catalog says so
    published = production.releases_of(str(candidate))
    if published and not force:
        ids = ", ".join(r.release_id for r in published)
        active = production.active
        return result(
            Action.NO_ACTION,
            f"{candidate} is already published: the signed production catalog lists {ids} "
            f"(active {active.release_id if active else None})",
        )

    # 3. bundled in the app: an install already has it without OTA
    if candidate == product_build and not force:
        return result(
            Action.NO_ACTION,
            f"{candidate} is the app's bundled PRODUCT_BUNDLE; it needs no OTA release",
        )

    # 4. the base: what an up-to-date install shows (or the operator's choice)
    base_release_id: str | None = None
    if requested_base is not None:
        base, kind = requested_base, BaseKind.REQUESTED
    else:
        base, kind = product_build, BaseKind.PRODUCT_BUNDLE
        active = production.active
        if active is not None and ClientVersion.parse(active.client_build) >= product_build:
            base, kind = ClientVersion.parse(active.client_build), BaseKind.PRODUCTION_RELEASE
            base_release_id = active.release_id
    facts.update(base_build=str(base), base_kind=kind, base_release_id=base_release_id)
    if str(base) not in committed_builds:
        return result(
            Action.UNKNOWN,
            f"the base build {base} ({kind.value}) has no committed dataset on main; the "
            "candidate cannot be compared with it",
        )
    if candidate <= base and not force:
        return result(
            Action.NO_ACTION,
            f"{candidate} is not newer than the base {base} ({kind.value}); nothing to prepare",
        )

    # 5. the inputs that make a preparation reproducible
    revision = max((release_revision(r.release_id) for r in published), default=0) + 1
    key = (
        f"{game.key}-{candidate}-r{revision}-from-{base_release_id or base}-main-{main_commit[:12]}"
    )
    facts.update(revision=revision, preparation_key=key)

    # 6. idempotency: a finished preparation of the same inputs is kept as an artifact
    if prior_artifacts is not None and not force:
        finished = sorted(
            name
            for name in prior_artifacts
            if name in {artifact_name(key, status) for status in FINAL_STATUSES}
        )
        if finished:
            return result(
                Action.NO_ACTION,
                f"already prepared for these inputs: artifact {finished[0]} "
                "(re-run with force to prepare again)",
                prior_artifact=finished[0],
            )

    reason = f"{candidate} is publishable (is_bgdl=false) and newer than the base {base}"
    if requested_build is not None:
        reason = f"{candidate} was requested; it is listed and publishable (is_bgdl=false)"
    if force:
        reason += "; forced"
    if production.status is ProductionStatus.UNKNOWN:
        reason += (
            "; the production catalog could not be verified, so the build is not treated as "
            "published (the preparation cannot finish as PREPARED until it verifies)"
        )
    return result(Action.PREPARE, reason)
