"""Fail-closed rank-cost mechanics: the production pipeline must *prove* that the
per-group / per-node / per-entry cost tables are absent before it publishes a
talent-data.v1 document ("1 rank = 1 point").

Covers the source-neutral table-availability model (present / absent / unknown), the
offline snapshot, the wago.tools probe (definitive "Table not found" vs anything else), the
normalizer gate and the CLI path. No network: wago responses are scripted.
"""

from __future__ import annotations

import dataclasses
import hashlib
import json
import shutil
from pathlib import Path

import httpx
import pytest

from talent_data.cli.main import main
from talent_data.db2.dataset import TraitDataSet
from talent_data.db2.tables import MUST_BE_ABSENT_TABLES, RANK_COST_EXTENSION_TABLES
from talent_data.games import get_game
from talent_data.normalization.normalizer import NormalizationError
from talent_data.pipeline import normalize_batch, probe_table_status
from talent_data.sources.base import SourceError, TableAvailability, TableStatus
from talent_data.sources.capabilities import SNAPSHOT_FILE, read_snapshot, record_absent
from talent_data.sources.http import HttpClient, HttpConfig
from talent_data.sources.local import LocalCsvDb2Source
from talent_data.sources.wago.source import WagoDb2Source
from talent_data.versions import ClientVersion
from tests.conftest import (
    ALL_CLASSES,
    fixture_source_for,
    normalize_fixture_class,
    normalize_warrior,
)
from tests.helpers import DB2_FIXTURES, NEW_BUILD, OLD_BUILD

COST_TABLES = list(RANK_COST_EXTENSION_TABLES)


def warrior_copy(tmp_path: Path) -> Path:
    """A private copy of the real Warrior fixture dir (new build) to mutate."""
    dst = tmp_path / "warrior"
    shutil.copytree(DB2_FIXTURES / str(NEW_BUILD) / "warrior", dst)
    return dst


def drop_from_snapshot(directory: Path, table: str) -> None:
    path = directory / SNAPSHOT_FILE
    raw = json.loads(path.read_text(encoding="utf-8"))
    del raw["tables"][table]
    path.write_text(json.dumps(raw), encoding="utf-8")


def load(directory: Path) -> TraitDataSet:
    return TraitDataSet.load(LocalCsvDb2Source(directory, per_version=False), NEW_BUILD)


# --------------------------------------------------------------------------- current builds


def test_contract_tables_are_the_three_cost_extensions() -> None:
    assert set(MUST_BE_ABSENT_TABLES) == {
        "TraitNodeGroupXTraitCost",
        "TraitNodeXTraitCost",
        "TraitNodeEntryXTraitCost",
    }


@pytest.mark.parametrize("build", [OLD_BUILD, NEW_BUILD])
@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_captured_builds_prove_absence_and_normalize(class_key: str, build: ClientVersion) -> None:
    source = fixture_source_for(class_key, build)
    statuses = probe_table_status(source, build)
    assert [(a.table, a.status) for a in statuses] == [
        (t, TableStatus.ABSENT) for t in MUST_BE_ABSENT_TABLES
    ]
    for availability in statuses:
        assert f"build={build}" in availability.evidence
        assert "Table not found." in availability.evidence
    dataset = TraitDataSet.load(source, build)
    assert {a.status for a in dataset.table_status.values()} == {TableStatus.ABSENT}
    assert normalize_fixture_class(class_key, build).trees  # normalization succeeds


# --------------------------------------------------------------------------- present


@pytest.mark.parametrize("table", COST_TABLES)
def test_future_cost_table_present_fails_normalization(tmp_path: Path, table: str) -> None:
    """Presence alone - here an *empty* table with no rows for this tree - is an
    unsupported mechanic until its schema/semantics are reviewed."""
    directory = warrior_copy(tmp_path)
    drop_from_snapshot(directory, table)
    (directory / f"{table}.csv").write_text("ID,TraitCostID\n", encoding="utf-8")
    dataset = load(directory)
    assert dataset.table_status[table].status is TableStatus.PRESENT
    with pytest.raises(NormalizationError) as err:
        normalize_warrior(dataset)
    message = str(err.value)
    assert f"build {NEW_BUILD} introduces {table}" in message
    assert "talent-data.v1 cannot prove uniform one-point rank costs" in message
    assert "extend cost modeling/schema" in message


def test_table_both_present_and_recorded_absent_is_a_source_error(tmp_path: Path) -> None:
    directory = warrior_copy(tmp_path)
    (directory / "TraitNodeXTraitCost.csv").write_text("ID\n", encoding="utf-8")
    with pytest.raises(SourceError, match="records it absent"):
        load(directory)


# --------------------------------------------------------------------------- unknown


@pytest.mark.parametrize("table", COST_TABLES)
def test_unproven_absence_fails_normalization(tmp_path: Path, table: str) -> None:
    directory = warrior_copy(tmp_path)
    drop_from_snapshot(directory, table)
    dataset = load(directory)
    assert dataset.table_status[table].status is TableStatus.UNKNOWN
    with pytest.raises(NormalizationError, match=f"cannot prove that {table} is absent"):
        normalize_warrior(dataset)


def test_missing_snapshot_means_unknown_not_absent(tmp_path: Path) -> None:
    directory = warrior_copy(tmp_path)
    (directory / SNAPSHOT_FILE).unlink()
    dataset = load(directory)
    assert {a.status for a in dataset.table_status.values()} == {TableStatus.UNKNOWN}
    with pytest.raises(NormalizationError, match="cannot prove"):
        normalize_warrior(dataset)


def test_dataset_without_probe_results_fails_closed(dataset_new: TraitDataSet) -> None:
    with pytest.raises(NormalizationError, match="not probed"):
        normalize_warrior(dataclasses.replace(dataset_new, table_status={}))


def test_batch_with_unknown_status_publishes_nothing(tmp_path: Path) -> None:
    root = tmp_path / "merged"
    from tests.unit.test_cli import _merge_class_fixtures

    _merge_class_fixtures(DB2_FIXTURES / str(NEW_BUILD), root / str(NEW_BUILD))
    (root / str(NEW_BUILD) / SNAPSHOT_FILE).unlink()
    batch = normalize_batch(LocalCsvDb2Source(root), get_game("forever"), NEW_BUILD, None)
    assert not batch.ok
    assert len(batch.results) == 9
    assert all(r.failure and "cannot prove" in r.failure for r in batch.results)


@pytest.mark.parametrize(
    "content",
    [
        "not json",
        json.dumps({"schemaVersion": 2, "tables": {}}),
        json.dumps({"schemaVersion": 1, "tables": {"X": {"status": "present", "evidence": "?"}}}),
        json.dumps({"schemaVersion": 1, "tables": {"X": {"status": "maybe", "evidence": "?"}}}),
    ],
)
def test_corrupt_snapshot_is_an_error_not_absence(tmp_path: Path, content: str) -> None:
    (tmp_path / SNAPSHOT_FILE).write_text(content, encoding="utf-8")
    with pytest.raises(SourceError):
        read_snapshot(tmp_path)


def test_record_absent_round_trip(tmp_path: Path) -> None:
    record_absent(tmp_path, "B", "evidence b")
    record_absent(tmp_path, "A", "evidence a")
    assert read_snapshot(tmp_path) == {
        "A": TableAvailability("A", TableStatus.ABSENT, "evidence a"),
        "B": TableAvailability("B", TableStatus.ABSENT, "evidence b"),
    }


# --------------------------------------------------------------------------- wago.tools


def _wago(tmp_path: Path, *responses: httpx.Response) -> tuple[WagoDb2Source, list[str]]:
    calls: list[str] = []
    queue = list(responses)

    def server(request: httpx.Request) -> httpx.Response:
        calls.append(str(request.url))
        return queue.pop(0)

    http = HttpClient(
        HttpConfig(max_attempts=2, backoff_base=0),
        transport=httpx.MockTransport(server),
        sleep=lambda _: None,
    )
    return WagoDb2Source(http, tmp_path), calls


TABLE_NOT_FOUND = httpx.Response(400, json={"errors": "Table not found."})


def test_wago_definitive_not_found_is_absent_and_recorded(tmp_path: Path) -> None:
    source, calls = _wago(tmp_path, TABLE_NOT_FOUND)
    availability = source.table_status(NEW_BUILD, "TraitNodeXTraitCost")
    assert availability.status is TableStatus.ABSENT
    assert "Table not found." in availability.evidence
    assert len(calls) == 1
    # recorded: a later offline source proves the same without the network
    offline = WagoDb2Source(None, tmp_path, offline=True)
    assert offline.table_status(NEW_BUILD, "TraitNodeXTraitCost").status is TableStatus.ABSENT


@pytest.mark.parametrize(
    "response",
    [
        # unknown build: wago answers with an HTML error page, not "Table not found"
        httpx.Response(400, html="<!DOCTYPE html><html>error</html>"),
        httpx.Response(404, json={"errors": "Build not found."}),
        httpx.Response(403, text="forbidden"),
    ],
)
def test_wago_other_errors_are_unknown_and_not_recorded(
    tmp_path: Path, response: httpx.Response
) -> None:
    source, _ = _wago(tmp_path, response)
    availability = source.table_status(NEW_BUILD, "TraitNodeXTraitCost")
    assert availability.status is TableStatus.UNKNOWN
    assert not (tmp_path / str(NEW_BUILD) / SNAPSHOT_FILE).exists()


def test_wago_server_errors_after_retries_are_unknown(tmp_path: Path) -> None:
    source, calls = _wago(tmp_path, httpx.Response(503), httpx.Response(503))
    assert source.table_status(NEW_BUILD, "TraitNodeXTraitCost").status is TableStatus.UNKNOWN
    assert len(calls) == 2


def test_wago_existing_table_is_present(tmp_path: Path) -> None:
    source, _ = _wago(tmp_path, httpx.Response(200, content=b"ID,TraitNodeID\n1,2\n"))
    availability = source.table_status(NEW_BUILD, "TraitNodeXTraitCost")
    assert availability.status is TableStatus.PRESENT
    # cached now: answered without the network
    offline = WagoDb2Source(None, tmp_path, offline=True)
    assert offline.table_status(NEW_BUILD, "TraitNodeXTraitCost").status is TableStatus.PRESENT


def test_wago_offline_without_evidence_is_unknown(tmp_path: Path) -> None:
    offline = WagoDb2Source(None, tmp_path, offline=True)
    availability = offline.table_status(NEW_BUILD, "TraitNodeEntryXTraitCost")
    assert availability.status is TableStatus.UNKNOWN
    assert "fetch" in availability.evidence


# --------------------------------------------------------------------------- CLI


def _seed_cache(root: Path, *, snapshot: bool) -> None:
    from tests.unit.test_cli import _merge_class_fixtures

    for build in (OLD_BUILD, NEW_BUILD):
        dst = root / ".cache" / "wago" / "forever" / str(build)
        _merge_class_fixtures(DB2_FIXTURES / str(build), dst)
        if not snapshot:
            (dst / SNAPSHOT_FILE).unlink()
        for csv_path in dst.glob("*.csv"):
            data = csv_path.read_bytes()
            meta = {"url": "fixture", "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
            csv_path.with_name(csv_path.name + ".meta.json").write_text(json.dumps(meta))


def test_cli_normalize_all_works_with_proven_absence(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _seed_cache(tmp_path, snapshot=True)
    monkeypatch.setenv("TALENT_DATA_ROOT", str(tmp_path))
    code = main(["normalize", "--build", str(NEW_BUILD), "--class", "all", "--offline"])
    assert code == 0, capsys.readouterr().out
    assert (tmp_path / "data/normalized/forever" / str(NEW_BUILD) / "manifest.json").is_file()


def test_cli_normalize_all_fails_closed_without_proof(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    """An old cache without a recorded absence snapshot (e.g. fetched before this check
    existed) must not publish anything."""
    _seed_cache(tmp_path, snapshot=False)
    monkeypatch.setenv("TALENT_DATA_ROOT", str(tmp_path))
    code = main(["normalize", "--build", str(NEW_BUILD), "--class", "all", "--offline"])
    captured = capsys.readouterr()
    assert code == 1
    assert "cannot prove that TraitNodeGroupXTraitCost is absent" in captured.out + captured.err
    assert not (tmp_path / "data/normalized/forever" / str(NEW_BUILD)).exists()
