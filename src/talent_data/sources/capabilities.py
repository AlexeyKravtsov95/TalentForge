"""Machine-readable table-availability snapshot of one client build.

A snapshot (``_table_status.json``, stored next to the build's table CSVs) records tables
that a source has *proven absent* for that build, with the evidence. Presence is proven by
the table itself (its CSV); absence cannot be proven by a missing file, so it is recorded
explicitly. Offline sources answer from the snapshot and report UNKNOWN for anything not
recorded - never "absent by default".

    {"schemaVersion": 1,
     "tables": {"TraitNodeXTraitCost": {"status": "absent",
                                         "evidence": "wago.tools: HTTP 400 ..."}}}
"""

from __future__ import annotations

import json
from pathlib import Path

from talent_data.sources.base import SourceError, TableAvailability, TableStatus

SNAPSHOT_FILE = "_table_status.json"
SNAPSHOT_SCHEMA_VERSION = 1


def snapshot_path(directory: Path) -> Path:
    return directory / SNAPSHOT_FILE


def read_snapshot(directory: Path) -> dict[str, TableAvailability]:
    """Recorded (absent) tables of the build directory; ``{}`` when there is no snapshot."""
    path = snapshot_path(directory)
    if not path.is_file():
        return {}
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
        version = raw.get("schemaVersion")
        tables = raw["tables"].items()
        parsed = [(t, TableStatus(e["status"]), str(e["evidence"])) for t, e in tables]
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
        raise SourceError(f"{path}: corrupt table status snapshot: {exc}") from exc
    if version != SNAPSHOT_SCHEMA_VERSION:
        raise SourceError(f"{path}: unsupported schemaVersion {version!r}")
    out = {}
    for table, status, evidence in parsed:
        if status is not TableStatus.ABSENT:
            raise SourceError(f"{path}: only 'absent' may be recorded, {table} is {status}")
        out[table] = TableAvailability(table, status, evidence)
    return out


def record_absent(directory: Path, table: str, evidence: str) -> TableAvailability:
    """Record ``table`` as proven absent (atomic write)."""
    entries = {
        t: {"status": a.status.value, "evidence": a.evidence}
        for t, a in read_snapshot(directory).items()
    }
    entries[table] = {"status": TableStatus.ABSENT.value, "evidence": evidence}
    directory.mkdir(parents=True, exist_ok=True)
    path = snapshot_path(directory)
    part = path.with_name(path.name + ".part")
    payload = {"schemaVersion": SNAPSHOT_SCHEMA_VERSION, "tables": dict(sorted(entries.items()))}
    part.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    part.replace(path)
    return TableAvailability(table, TableStatus.ABSENT, evidence)


def status_from_files(
    directory: Path, table: str, csv_path: Path, origin: str
) -> TableAvailability:
    """Offline answer: CSV present -> PRESENT; recorded absent -> ABSENT; else UNKNOWN.

    A table that is both recorded absent and present on disk is a contradiction and fails.
    """
    recorded = read_snapshot(directory).get(table)
    if csv_path.is_file():
        if recorded is not None:
            raise SourceError(
                f"{table}: {csv_path} exists but {snapshot_path(directory)} records it absent"
            )
        return TableAvailability(table, TableStatus.PRESENT, f"{origin}: {csv_path.name}")
    if recorded is not None:
        return recorded
    return TableAvailability(
        table,
        TableStatus.UNKNOWN,
        f"{origin}: no {csv_path.name} and no absence recorded in {snapshot_path(directory)}",
    )
