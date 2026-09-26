"""Reading DB2 tables exported as CSV (header row + one record per row, RFC 4180 quoting)."""

from __future__ import annotations

import csv
from collections.abc import Callable
from pathlib import Path

from talent_data.sources.base import Db2Table, SourceError


def read_csv_table(path: Path, table: str, rename: Callable[[str], str] = lambda c: c) -> Db2Table:
    with path.open(encoding="utf-8", newline="") as fh:
        reader = csv.reader(fh)
        try:
            header = next(reader)
        except StopIteration:
            raise SourceError(f"{path} is empty") from None
        columns = tuple(rename(c) for c in header)
        rows = []
        for line_no, values in enumerate(reader, start=2):
            if len(values) != len(columns):
                raise SourceError(
                    f"{path}: record {line_no} has {len(values)} fields, header has {len(columns)}"
                )
            rows.append(dict(zip(columns, values, strict=True)))
    return Db2Table(name=table, columns=columns, rows=tuple(rows))
