"""End-to-end CLI runs, fully offline, on a cache seeded from the real fixture rows."""

from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path

import pytest

from talent_data.cli.main import json_schemas, main
from talent_data.pipeline import read_document
from talent_data.validation.validators import validate_document
from tests.conftest import ALL_CLASSES
from tests.helpers import DB2_FIXTURES, NEW_BUILD, OLD_BUILD

ROOT = Path(__file__).resolve().parents[2]

CLASS_ORDER = [
    "Warrior",
    "Paladin",
    "Hunter",
    "Rogue",
    "Priest",
    "Shaman",
    "Mage",
    "Warlock",
    "Druid",
]


def _merge_class_fixtures(build_dir: Path, dst: Path) -> None:
    """Merge the per-class fixture tables of one build into a single flat cache dir.

    Class fixtures are row-disjoint for tree-specific tables and identical for the shared
    class tables, so a union (deduplicated by full row) reproduces the original build
    tables for the classes under test - no hand-written data.
    """
    dst.mkdir(parents=True, exist_ok=True)
    for table_csv in sorted((build_dir / ALL_CLASSES[0]).glob("*.csv")):
        name = table_csv.name
        header: list[str] = []
        seen: set[tuple[str, ...]] = set()
        rows: list[list[str]] = []
        for class_key in ALL_CLASSES:
            with (build_dir / class_key / name).open(encoding="utf-8", newline="") as fh:
                reader = csv.reader(fh)
                if not header:
                    header = next(reader)
                else:
                    next(reader)
                for row in reader:
                    if tuple(row) not in seen:
                        seen.add(tuple(row))
                        rows.append(row)
        with (dst / name).open("w", encoding="utf-8", newline="") as fh:
            writer = csv.writer(fh, lineterminator="\n")
            writer.writerow(header)
            writer.writerows(rows)


@pytest.fixture
def project(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """A project root whose .cache holds verified entries built from the fixtures."""
    for build in (OLD_BUILD, NEW_BUILD):
        dst = tmp_path / ".cache" / "wago" / "forever" / str(build)
        _merge_class_fixtures(DB2_FIXTURES / str(build), dst)
        for csv_path in dst.glob("*.csv"):
            data = csv_path.read_bytes()
            meta = {"url": "fixture", "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
            csv_path.with_name(csv_path.name + ".meta.json").write_text(json.dumps(meta))
    monkeypatch.setenv("TALENT_DATA_ROOT", str(tmp_path))
    return tmp_path


def test_normalize_offline_writes_valid_document(project: Path, capsys) -> None:
    code = main(["normalize", "--build", str(NEW_BUILD), "--class", "warrior", "--offline"])
    out = capsys.readouterr().out
    assert code == 0, out
    assert "tree 1117: 53 nodes" in out
    path = project / "data" / "normalized" / "forever" / str(NEW_BUILD) / "warrior.json"
    doc = read_document(path)
    assert validate_document(doc).ok
    assert doc.client_build == str(NEW_BUILD)


def test_normalize_all_classes_writes_dataset_and_manifest(project: Path, capsys) -> None:
    code = main(["normalize", "--build", str(NEW_BUILD), "--class", "all", "--offline"])
    out = capsys.readouterr().out
    assert code == 0, out
    base = project / "data" / "normalized" / "forever" / str(NEW_BUILD)
    # nine class documents in deterministic (class id) order, plus the manifest
    manifest_path = base / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    assert manifest["schemaVersion"] == 1
    assert manifest["game"] == "forever"
    assert manifest["clientBuild"] == str(NEW_BUILD)
    tokens = [c["token"] for c in manifest["classes"]]
    assert tokens == [
        "WARRIOR",
        "PALADIN",
        "HUNTER",
        "ROGUE",
        "PRIEST",
        "SHAMAN",
        "MAGE",
        "WARLOCK",
        "DRUID",
    ]
    for entry in manifest["classes"]:
        assert entry["file"] == f"{entry['token'].lower()}.json"
        doc = read_document(base / entry["file"])
        report = validate_document(doc)
        assert report.ok, entry["token"]
        assert doc.content_hash == entry["contentHash"]
        assert entry["validation"]["errors"] == 0
        assert entry["validation"]["warnings"] == len(report.warnings)
        assert sum(entry["validation"]["warningsByCode"].values()) == len(report.warnings)
    for name in CLASS_ORDER:
        assert f"Forever {name} @ {NEW_BUILD}" in out
    total_warnings = sum(c["validation"]["warnings"] for c in manifest["classes"])
    assert f"9 classes, 0 errors, {total_warnings} warnings" in out


def test_normalize_batch_failure_writes_nothing(
    project: Path, capsys, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A class that fails normalization is named, the exit code is non-zero, nothing is
    published, and the classes that do normalize are still reported."""
    import talent_data.pipeline as pipeline

    original = pipeline.normalize_from_dataset

    def failing(dataset, *, game, class_key, source_name):
        if class_key == "ROGUE":
            raise pipeline.PipelineError("boom: rogue tables unreadable")
        return original(dataset, game=game, class_key=class_key, source_name=source_name)

    monkeypatch.setattr(pipeline, "normalize_from_dataset", failing)
    code = main(["normalize", "--build", str(NEW_BUILD), "--class", "all", "--offline"])
    captured = capsys.readouterr()
    assert code == 1
    combined = captured.out + captured.err
    assert "Rogue" in combined
    assert "boom: rogue tables unreadable" in combined
    assert "Warrior" in combined  # other classes were still processed
    base = project / "data" / "normalized" / "forever" / str(NEW_BUILD)
    assert not base.exists(), "a failed batch must not publish a partial dataset"


def test_normalize_unknown_class_lists_available(project: Path, capsys) -> None:
    code = main(["normalize", "--build", str(NEW_BUILD), "--class", "deathknight", "--offline"])
    assert code == 1
    err = capsys.readouterr().err
    assert "deathknight" in err and "warrior" in err


def test_diff_offline_prints_report_and_writes_json(project: Path, capsys) -> None:
    code = main(
        [
            "diff",
            "--class",
            "warrior",
            "--from",
            str(OLD_BUILD),
            "--to",
            str(NEW_BUILD),
            "--offline",
        ]
    )
    out = capsys.readouterr().out
    assert code == 0, out
    assert "Forever Warrior\n1.60.1.69977 -> 1.60.1.70009" in out
    assert "CHANGED: 2" in out
    diff_json = project / "artifacts" / "diff" / "forever-warrior-1.60.1.69977-1.60.1.70009.json"
    report = json.loads(diff_json.read_text())
    assert report["summary"]["changed"] == 2


def test_diff_all_classes_writes_aggregate(project: Path, capsys) -> None:
    code = main(
        ["diff", "--class", "all", "--from", str(OLD_BUILD), "--to", str(NEW_BUILD), "--offline"]
    )
    out = capsys.readouterr().out
    assert code == 0, out
    assert "Forever (all classes)" in out
    assert "1.60.1.69977 -> 1.60.1.70009" in out
    assert "TOTAL" in out
    for name in CLASS_ORDER:
        assert f"\n{name}\n" in out
    aggregate_path = project / "artifacts" / "diff" / f"forever-all-{OLD_BUILD}-{NEW_BUILD}.json"
    aggregate = json.loads(aggregate_path.read_text())
    assert aggregate["schemaVersion"] == 1
    assert aggregate["game"] == "forever"
    assert len(aggregate["classes"]) == 9
    # per-class talent-diff v1 documents exist next to the aggregate and match its summary
    for entry in aggregate["classes"]:
        path = project / "artifacts" / "diff" / entry["file"]
        report = json.loads(path.read_text())
        assert report["schemaVersion"] == 1
        assert report["summary"] == entry["summary"]
    total = aggregate["total"]
    assert total["added"] == sum(c["summary"]["added"] for c in aggregate["classes"])
    assert total["removed"] == sum(c["summary"]["removed"] for c in aggregate["classes"])
    assert total["changed"] == sum(c["summary"]["changed"] for c in aggregate["classes"])
    assert "renamed=2" in out  # the real Druid Mangle -> Primal Bite rename


def test_wrong_branch_is_rejected(project: Path, capsys) -> None:
    code = main(["normalize", "--build", "1.15.9.69722", "--class", "warrior", "--offline"])
    assert code == 1
    assert "not a Forever build" in capsys.readouterr().err


def test_missing_cache_gives_actionable_error(project: Path, capsys) -> None:
    code = main(["normalize", "--build", "1.60.1.69913", "--class", "warrior", "--offline"])
    assert code == 1
    assert "run `python -m talent_data fetch --build 1.60.1.69913` first" in capsys.readouterr().err


def test_validate_prints_issue_codes(project: Path, capsys) -> None:
    main(["normalize", "--build", str(NEW_BUILD), "--class", "hunter", "--offline"])
    path = project / "data" / "normalized" / "forever" / str(NEW_BUILD) / "hunter.json"
    code = main(["validate", str(path)])
    out = capsys.readouterr().out
    assert code == 0, out
    assert "[SUFFICIENT_PREREQUISITE_CYCLE]" in out
    assert "[DUPLICATE_SPELL_IDENTITY]" in out


def test_manifest_command_verifies_dataset(project: Path, capsys) -> None:
    main(["normalize", "--build", str(NEW_BUILD), "--class", "all", "--offline"])
    manifest = project / "data" / "normalized" / "forever" / str(NEW_BUILD) / "manifest.json"
    code = main(["manifest", str(manifest)])
    out = capsys.readouterr().out
    assert code == 0, out
    assert "9 classes" in out
    for token in ("WARRIOR", "DRUID"):
        assert token in out


def test_committed_json_schemas_match_models() -> None:
    for name, text in json_schemas().items():
        committed = ROOT / "docs" / "schema" / name
        assert committed.read_text(encoding="utf-8") == text, (
            f"{name} is stale: run `python -m talent_data schema` and review the contract change"
        )
