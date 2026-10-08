"""wago.tools implementation of :class:`BuildSource` and :class:`Db2Source`.

Endpoints (public, no auth):

* ``GET /api/builds``                          - all known builds per product
* ``GET /db2/<Table>/csv?build=<version>``     - one DB2 table of one build, as CSV

CSV specifics handled here (and nowhere else):

* reserved column names are prefixed with an underscore (``_Index``); we strip it so the
  domain sees WoWDBDefs names (``Index``);
* array columns are already flattened as ``<Name>_<i>``, which is our canonical form;
* text cells can contain quoted newlines, so the file is parsed with :mod:`csv`, never
  line-by-line.
"""

from __future__ import annotations

import csv
import json
import logging
from pathlib import Path

from pydantic import ValidationError

from talent_data.games import GameConfig
from talent_data.sources.base import (
    BuildInfo,
    Db2Table,
    SourceError,
    TableAvailability,
    TableStatus,
)
from talent_data.sources.capabilities import read_snapshot, record_absent, snapshot_path
from talent_data.sources.csvio import read_csv_table
from talent_data.sources.http import DownloadError, HttpClient, is_valid_cache_entry
from talent_data.sources.wago.models import WagoBuildIndex
from talent_data.versions import ClientVersion

log = logging.getLogger(__name__)

WAGO_BASE_URL = "https://wago.tools"


class WagoBuildSource:
    name = "wago.tools"

    def __init__(self, http: HttpClient, base_url: str = WAGO_BASE_URL) -> None:
        self._http = http
        self._base_url = base_url.rstrip("/")

    def list_builds(self, game: GameConfig) -> list[BuildInfo]:
        payload = self._http.get_json(f"{self._base_url}/api/builds")
        try:
            index = WagoBuildIndex.model_validate(payload)
        except ValidationError as exc:
            raise SourceError(f"unexpected /api/builds schema from wago.tools: {exc}") from exc
        return filter_game_builds(index, game)


def filter_game_builds(index: WagoBuildIndex, game: GameConfig) -> list[BuildInfo]:
    """Keep builds of ``game``: right product AND right ``major.minor`` branch."""
    builds: list[BuildInfo] = []
    for product in game.products:
        for entry in index.root.get(product, []):
            if entry.product != product:
                raise SourceError(
                    f"wago.tools listed {entry.version} under {product!r} "
                    f"but its product field is {entry.product!r}"
                )
            try:
                version = ClientVersion.parse(entry.version)
            except ValueError:
                log.warning("skipping unparseable version %r in %s", entry.version, product)
                continue
            if version.branch != game.version_branch:
                continue
            extra = {
                "build_config": entry.build_config or "",
                "cdn_config": entry.cdn_config or "",
            }
            # Only a flag wago.tools actually sent is lifted; a missing one stays unknown.
            if entry.is_bgdl is not None:
                extra["is_bgdl"] = str(entry.is_bgdl).lower()
            builds.append(
                BuildInfo(
                    version=version,
                    product=product,
                    created_at=entry.created_at,
                    extra=extra,
                )
            )
    return builds


class WagoDb2Source:
    """Downloads DB2 CSVs into ``<cache_root>/<version>/<Table>.csv`` and reads them."""

    name = "wago.tools"

    def __init__(
        self,
        http: HttpClient | None,
        cache_root: Path,
        base_url: str = WAGO_BASE_URL,
        offline: bool = False,
    ) -> None:
        if http is None and not offline:
            raise ValueError("an HttpClient is required unless offline=True")
        self._http = http
        self._cache_root = cache_root
        self._base_url = base_url.rstrip("/")
        self._offline = offline

    def table_path(self, version: ClientVersion, table: str) -> Path:
        return self._cache_root / str(version) / f"{table}.csv"

    def ensure_table(self, version: ClientVersion, table: str) -> None:
        path = self.table_path(version, table)
        if self._offline or self._http is None:
            if not is_valid_cache_entry(path):
                raise SourceError(
                    f"{table} for {version} is not in the local cache ({path}); "
                    f"run `python -m talent_data fetch --build {version}` first"
                )
            return
        url = f"{self._base_url}/db2/{table}/csv"
        try:
            self._http.download_file(
                url, path, params={"build": str(version)}, validate=_validate_csv
            )
        except DownloadError as exc:
            raise SourceError(
                f"could not fetch DB2 table {table} for build {version} from wago.tools "
                f"(unknown build/table, or wago.tools unavailable): {exc}"
            ) from exc

    def load_table(self, version: ClientVersion, table: str) -> Db2Table:
        self.ensure_table(version, table)
        return read_csv_table(self.table_path(version, table), table, rename=_canonical_column)

    def table_status(self, version: ClientVersion, table: str) -> TableAvailability:
        """PRESENT when the table is (or can now be) cached; ABSENT only on wago.tools'
        definitive answer for a *known* build - an HTTP 4xx JSON body
        ``{"errors": "Table not found."}`` - which is then recorded in the build's
        ``_table_status.json``. Unknown builds (wago answers with an HTML error page),
        network failures, 5xx and anything else are UNKNOWN. Offline, only the cache and
        the recorded snapshot count."""
        directory = self._cache_root / str(version)
        if is_valid_cache_entry(self.table_path(version, table)):
            if table in read_snapshot(directory):
                raise SourceError(
                    f"{table} for {version} is cached but {snapshot_path(directory)} records "
                    "it absent"
                )
            return TableAvailability(table, TableStatus.PRESENT, f"{self.name}: cached CSV")
        recorded = read_snapshot(directory).get(table)
        if recorded is not None:
            return recorded
        if self._offline or self._http is None:
            return TableAvailability(
                table,
                TableStatus.UNKNOWN,
                f"{self.name} (offline): {table} not cached and no absence recorded in "
                f"{snapshot_path(directory)}; run `python -m talent_data fetch --build "
                f"{version}` online",
            )
        try:
            self.ensure_table(version, table)
        except SourceError as exc:
            cause = exc.__cause__
            if isinstance(cause, DownloadError) and _is_table_not_found(cause):
                evidence = (
                    f"{self.name}: GET /db2/{table}/csv?build={version} -> HTTP "
                    f'{cause.status_code} {{"errors": "Table not found."}}'
                )
                return record_absent(directory, table, evidence)
            return TableAvailability(table, TableStatus.UNKNOWN, f"{self.name}: {exc}")
        return TableAvailability(table, TableStatus.PRESENT, f"{self.name}: downloaded CSV")


def _is_table_not_found(error: DownloadError) -> bool:
    """wago.tools' definitive "no such DB2 table in this build" answer. An unknown build
    gets an HTML error page instead, so this cannot be confused with a missing build."""
    if error.status_code not in (400, 404) or error.body is None:
        return False
    if "json" not in (error.content_type or ""):
        return False
    try:
        payload = json.loads(error.body)
    except ValueError:
        return False
    return bool(payload == {"errors": "Table not found."})


def _canonical_column(name: str) -> str:
    # wago.tools escapes reserved column names such as "Index" as "_Index".
    return name[1:] if name.startswith("_") else name


def _validate_csv(path: Path) -> None:
    """Reject payloads that are not a DB2 CSV (e.g. an HTML error page served with 200)."""
    with path.open(encoding="utf-8", newline="") as fh:
        head = fh.read(4096)
    if head.lstrip().startswith("<"):
        raise DownloadError(f"{path.name}: expected CSV, got HTML/XML")
    header = next(csv.reader([head.splitlines()[0] if head else ""]), [])
    if "ID" not in {_canonical_column(c) for c in header}:
        raise DownloadError(f"{path.name}: CSV header has no ID column: {header[:8]}")
