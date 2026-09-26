"""Source/domain boundary.

Everything the domain (normalizer, diff, validation) needs from the outside world goes
through these two protocols. Today they are implemented by :mod:`talent_data.sources.wago`;
a Blizzard CDN/TACT extractor or another mirror only has to implement the same protocols.

The DB2 contract is intentionally *table-shaped*: a source returns a :class:`Db2Table` whose
column names follow the community DB2 definitions (WoWDBDefs naming, e.g. ``TraitNodeID``).
How those rows were transported (CSV over HTTP, a local client extraction, ...) is invisible
to the domain.
"""

from __future__ import annotations

from collections.abc import Iterable, Iterator, Mapping
from dataclasses import dataclass, field
from datetime import datetime
from typing import Protocol

from talent_data.games import GameConfig
from talent_data.versions import ClientVersion


class SourceError(RuntimeError):
    """A source could not provide the requested data."""


@dataclass(frozen=True)
class BuildInfo:
    version: ClientVersion
    product: str
    # When the source first saw / published the build. Used to pick the *latest* build
    # explicitly instead of trusting list order.
    created_at: datetime
    # Source-specific extra facts worth surfacing to humans (e.g. build config hash).
    extra: Mapping[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class Db2Table:
    name: str
    columns: tuple[str, ...]
    rows: tuple[Mapping[str, str], ...]

    def __iter__(self) -> Iterator[Mapping[str, str]]:
        return iter(self.rows)

    def __len__(self) -> int:
        return len(self.rows)

    def require_columns(self, required: Iterable[str]) -> None:
        missing = [c for c in required if c not in self.columns]
        if missing:
            raise SourceError(
                f"DB2 table {self.name} is missing expected columns {missing}; "
                f"available: {list(self.columns)}"
            )


class BuildSource(Protocol):
    """Discovers client builds for a game."""

    name: str

    def list_builds(self, game: GameConfig) -> list[BuildInfo]:
        """All known builds of ``game`` (any order)."""
        ...


class Db2Source(Protocol):
    """Provides DB2 tables for a concrete client build."""

    name: str

    def ensure_table(self, version: ClientVersion, table: str) -> None:
        """Make ``table`` locally available (download/cache) without loading it."""
        ...

    def load_table(self, version: ClientVersion, table: str) -> Db2Table:
        """Return the full table for ``version`` (fetching it first if needed)."""
        ...


def latest_build(builds: Iterable[BuildInfo]) -> BuildInfo:
    """Pick the latest build explicitly by ``created_at``; version breaks ties.

    The input order is never trusted.
    """
    ordered = sorted(builds, key=lambda b: (b.created_at, b.version))
    if not ordered:
        raise SourceError("no builds available")
    return ordered[-1]


# --------------------------------------------------------------------- build candidates
#
# "Latest detected build" and "latest build that may be published from" are different
# notions. A background-download build (Battle.net ``is_bgdl``) is a *future* patch whose
# data Blizzard starts distributing early; its DB2 tables are not necessarily the tables the
# live game uses, and its ``created_at`` being newest must NOT make it production-ready.
#
# What the source metadata can and cannot tell us (observed 2026-09-27):
#   * wago.tools exposes ``is_bgdl`` per build; all five Forever 1.60.1 builds have it
#     false, while the retail ``wow`` product lists 12 builds with ``is_bgdl=true``
#     (patch pre-downloads) - so the flag is actively used and meaningful;
#   * wago.tools has NO field that says "this is the build the live realms run".
# Therefore: "publishable" below only means "not a background download". It is a necessary
# condition for treating a build as production data, not proof that the build is live.


@dataclass(frozen=True)
class BuildCandidate:
    """A build considered as a dataset source, with the facts that gate publication.

    ``background_download`` is tri-state: ``True``/``False`` as stated by the source,
    ``None`` when the source did not say. ``None`` must never be read as "definitely not
    a background download" - it is *unknown*, and an unknown build is not publishable
    (see ``publishable``).
    """

    info: BuildInfo
    background_download: bool | None

    @property
    def is_background_download(self) -> bool:
        """Definitely a background download (only when the source explicitly said so)."""
        return self.background_download is True

    @property
    def publishable(self) -> bool:
        """Known not to be a background download. Unknown is NOT publishable."""
        return self.background_download is False


def classify_build(info: BuildInfo) -> BuildCandidate:
    """Lift source-specific ``extra`` facts into the domain.

    A source that does not provide ``is_bgdl`` yields ``background_download=None``
    (unknown), which ``latest_publishable_build`` refuses - absence of the flag is not
    evidence of publishability.
    """
    raw = info.extra.get("is_bgdl")
    return BuildCandidate(info=info, background_download=None if raw is None else raw == "true")


def publishable(candidates: Iterable[BuildCandidate]) -> list[BuildCandidate]:
    """Candidates *known* not to be background downloads (see the note above)."""
    return [c for c in candidates if c.publishable]


def latest_publishable_build(candidates: Iterable[BuildCandidate]) -> BuildCandidate:
    ordered = sorted(publishable(candidates), key=lambda c: (c.info.created_at, c.info.version))
    if not ordered:
        raise SourceError(
            "no publishable builds available (background downloads and builds with "
            "unknown background-download status are excluded)"
        )
    return ordered[-1]
