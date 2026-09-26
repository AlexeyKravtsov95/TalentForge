"""Db2Source backed by a plain directory of ``<Table>.csv`` files.

Used for the offline test fixtures, and usable for any future extractor that dumps DB2
tables to CSV (e.g. a local client/CASC export) without going through wago.tools.
"""

from __future__ import annotations

from pathlib import Path

from talent_data.sources.base import Db2Table, SourceError
from talent_data.sources.csvio import read_csv_table
from talent_data.versions import ClientVersion


class LocalCsvDb2Source:
    name = "local-csv"

    def __init__(self, root: Path, per_version: bool = True) -> None:
        self._root = root
        self._per_version = per_version

    def _path(self, version: ClientVersion, table: str) -> Path:
        base = self._root / str(version) if self._per_version else self._root
        return base / f"{table}.csv"

    def ensure_table(self, version: ClientVersion, table: str) -> None:
        path = self._path(version, table)
        if not path.is_file():
            raise SourceError(f"{table} for {version} not found at {path}")

    def load_table(self, version: ClientVersion, table: str) -> Db2Table:
        self.ensure_table(version, table)
        return read_csv_table(self._path(version, table), table)
