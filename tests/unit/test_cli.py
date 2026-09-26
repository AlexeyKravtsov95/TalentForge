"""End-to-end CLI runs, fully offline, on a cache seeded from the real fixture rows."""

from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path

import pytest

from talent_data.cli.main import json_schemas, main
from talent_data.pipeline import read_document
from talent_data.validation.validators import validate_document
from tests.helpers import DB2_FIXTURES, NEW_BUILD, OLD_BUILD

ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture
def project(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """A project root whose .cache holds verified entries built from the fixtures."""
    for build in (OLD_BUILD, NEW_BUILD):
        dst = tmp_path / ".cache" / "wago" / "forever" / str(build)
        shutil.copytree(DB2_FIXTURES / str(build), dst)
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


def test_wrong_branch_is_rejected(project: Path, capsys) -> None:
    code = main(["normalize", "--build", "1.15.9.69722", "--class", "warrior", "--offline"])
    assert code == 1
    assert "not a Forever build" in capsys.readouterr().err


def test_missing_cache_gives_actionable_error(project: Path, capsys) -> None:
    code = main(["normalize", "--build", "1.60.1.69913", "--class", "warrior", "--offline"])
    assert code == 1
    assert "run `python -m talent_data fetch --build 1.60.1.69913` first" in capsys.readouterr().err


def test_committed_json_schemas_match_models() -> None:
    for name, text in json_schemas().items():
        committed = ROOT / "docs" / "schema" / name
        assert committed.read_text(encoding="utf-8") == text, (
            f"{name} is stale: run `python -m talent_data schema` and review the contract change"
        )
