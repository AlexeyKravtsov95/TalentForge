"""Publishing and verifying the tooltips of 1.60.1.70009 end to end, offline."""

from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path

import pytest

from talent_data.cli.main import main
from talent_data.models.tooltips import TalentTooltipsDocument, rank_text
from talent_data.pipeline import PipelineError
from talent_data.tooltips.coverage import render_markdown
from talent_data.tooltips.pipeline import (
    check_canonical,
    read_coverage_report,
    read_tooltips_document,
    render_build,
    render_document,
)
from talent_data.tooltips.spells import SpellLookup
from tests.conftest import ALL_CLASSES
from tests.helpers import NEW_BUILD
from tests.unit.tooltips.conftest import (
    CANONICAL_DATASET,
    CANONICAL_TOOLTIPS,
    COVERAGE_MARKDOWN,
    COVERAGE_REPORT,
    GAME,
    ROOT,
    SOURCE_NAME,
    TOOLTIP_FIXTURES,
    tooltip_source,
)


@pytest.fixture
def project(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """A project root with the committed dataset and a DB2 cache made of the fixture rows."""
    shutil.copytree(
        CANONICAL_DATASET, tmp_path / "data" / "normalized" / "forever" / str(NEW_BUILD)
    )
    cache = tmp_path / ".cache" / "wago" / "forever" / str(NEW_BUILD)
    shutil.copytree(TOOLTIP_FIXTURES, cache)
    for csv_path in cache.glob("*.csv"):
        data = csv_path.read_bytes()
        meta = {"url": "fixture", "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        csv_path.with_name(csv_path.name + ".meta.json").write_text(json.dumps(meta))
    monkeypatch.setenv("TALENT_DATA_ROOT", str(tmp_path))
    return tmp_path


def test_regenerating_from_db2_reproduces_the_committed_files(monkeypatch) -> None:
    """The committed sidecars, report and Markdown are exactly what the renderer produces
    from the build's rows - no hand edits, no drift."""
    monkeypatch.setenv("TALENT_DATA_ROOT", str(ROOT))
    build = render_build(tooltip_source(), GAME, NEW_BUILD, source_name=SOURCE_NAME)
    for token, document in build.tooltips.items():
        committed = (CANONICAL_TOOLTIPS / f"{token.lower()}.json").read_text(encoding="utf-8")
        assert document.to_json() == committed, f"{token} sidecar is stale"
    assert build.report.to_json() == COVERAGE_REPORT.read_text(encoding="utf-8")
    assert render_markdown(build.report) == COVERAGE_MARKDOWN.read_text(encoding="utf-8")


def test_rendering_is_deterministic(spells: SpellLookup, canonical_docs) -> None:
    first, _ = render_document(canonical_docs["druid"], spells, source_name=SOURCE_NAME)
    second, _ = render_document(canonical_docs["druid"], spells, source_name=SOURCE_NAME)
    assert first.to_json() == second.to_json()
    assert first.content_hash == second.content_hash


def test_report_covers_every_entry_and_rank() -> None:
    report = read_coverage_report(COVERAGE_REPORT)
    assert (report.totals.entries, report.totals.ranks) == (469, 1333)
    assert report.totals.resolved + report.totals.partial + report.totals.unresolved == 1333
    assert sum(c.ranks for c in report.classes) == 1333
    assert [c.class_token for c in report.classes] == [k.upper() for k in ALL_CLASSES]
    pairs = set()
    gaps = 0
    for path in CANONICAL_TOOLTIPS.glob("*.json"):
        document = TalentTooltipsDocument.model_validate_json(path.read_text(encoding="utf-8"))
        for entry in document.entries:
            for rank in entry.ranks:
                pairs.add((entry.entry_id, rank.rank))
                gaps += len(rank.gaps)
    assert len(pairs) == 1333
    assert len(report.gaps) == gaps
    assert {c.category for c in report.categories if c.status == "NOT_PRESENT"} == {"unclassified"}


def test_resolved_ranks_never_carry_template_syntax() -> None:
    for path in CANONICAL_TOOLTIPS.glob("*.json"):
        document = TalentTooltipsDocument.model_validate_json(path.read_text(encoding="utf-8"))
        for entry in document.entries:
            for rank in entry.ranks:
                text = rank_text(rank)
                assert "$" not in text and "|" not in text and "\r" not in text
                if rank.status == "resolved":
                    assert all(s.kind != "gap" for s in rank.segments)


def test_committed_tooltips_check_offline() -> None:
    assert check_canonical(GAME, NEW_BUILD) == []


def test_publish_writes_exactly_the_sidecars_and_report(project: Path, capsys) -> None:
    stale = project / "data" / "tooltips" / "forever" / str(NEW_BUILD) / "old.json"
    stale.parent.mkdir(parents=True)
    stale.write_text("{}")
    assert main(["tooltips", "normalize", "--build", str(NEW_BUILD), "--offline"]) == 0
    out = capsys.readouterr().out
    assert "ranks 1333: resolved" in out
    directory = project / "data" / "tooltips" / "forever" / str(NEW_BUILD)
    assert sorted(p.name for p in directory.iterdir()) == sorted(f"{k}.json" for k in ALL_CLASSES)
    for path in directory.iterdir():
        assert path.read_bytes() == (CANONICAL_TOOLTIPS / path.name).read_bytes()
    report = project / "artifacts" / "tooltips" / f"forever-{NEW_BUILD}-tooltip-coverage.json"
    assert report.read_bytes() == COVERAGE_REPORT.read_bytes()
    assert main(["tooltips", "check", "--build", str(NEW_BUILD)]) == 0


def test_check_detects_tampering(project: Path, capsys) -> None:
    assert main(["tooltips", "normalize", "--build", str(NEW_BUILD), "--offline"]) == 0
    path = project / "data" / "tooltips" / "forever" / str(NEW_BUILD) / "warrior.json"
    document = json.loads(path.read_text(encoding="utf-8"))
    document["entries"][0]["ranks"][0]["segments"][1]["text"] = "999"
    path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    problems = check_canonical(GAME, NEW_BUILD)
    assert any("contentHash does not match" in p for p in problems)
    assert main(["tooltips", "check", "--build", str(NEW_BUILD)]) == 1


@pytest.mark.parametrize(
    ("field", "value", "message"),
    [
        ("clientBuild", "1.60.1.69977", "clientBuild"),
        ("classToken", "MAGE", "tooltips are for MAGE"),
        ("talentDataContentHash", "sha256:" + "0" * 64, "rendered for talent-data"),
    ],
)
def test_check_refuses_identity_drift(project: Path, field, value, message) -> None:
    assert main(["tooltips", "normalize", "--build", str(NEW_BUILD), "--offline"]) == 0
    path = project / "data" / "tooltips" / "forever" / str(NEW_BUILD) / "warrior.json"
    document = json.loads(path.read_text(encoding="utf-8"))
    document[field] = value
    path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    assert any(message in p for p in check_canonical(GAME, NEW_BUILD))


def test_check_refuses_a_missing_entry_and_a_stale_report(project: Path) -> None:
    assert main(["tooltips", "normalize", "--build", str(NEW_BUILD), "--offline"]) == 0
    report = project / "artifacts" / "tooltips" / f"forever-{NEW_BUILD}-tooltip-coverage.json"
    data = json.loads(report.read_text(encoding="utf-8"))
    data["totals"]["resolved"] += 1
    report.write_text(json.dumps(data), encoding="utf-8")
    assert any("do not match the committed sidecars" in p for p in check_canonical(GAME, NEW_BUILD))

    path = project / "data" / "tooltips" / "forever" / str(NEW_BUILD) / "rogue.json"
    document = read_tooltips_document(path)
    trimmed = document.model_copy(update={"entries": document.entries[1:]})
    path.write_text(trimmed.to_json(), encoding="utf-8")
    problems = check_canonical(GAME, NEW_BUILD)
    assert any("talent entries without tooltips" in p for p in problems)


def test_dataset_and_tables_of_different_builds_are_refused(spells: SpellLookup, canonical_docs):
    doc = canonical_docs["warrior"]
    tree = doc.trees[0]
    node = tree.nodes[0]
    entry = node.entries[0].model_copy(update={"description_template": "Something else."})
    nodes = [node.model_copy(update={"entries": [entry]}), *tree.nodes[1:]]
    changed = doc.model_copy(update={"trees": [tree.model_copy(update={"nodes": nodes})]})
    with pytest.raises(PipelineError, match="not the same build"):
        render_document(changed, spells, source_name=SOURCE_NAME)


def test_unsupported_schema_version_is_explicit(tmp_path: Path) -> None:
    path = tmp_path / "warrior.json"
    document = json.loads((CANONICAL_TOOLTIPS / "warrior.json").read_text(encoding="utf-8"))
    document["schemaVersion"] = 2
    path.write_text(json.dumps(document), encoding="utf-8")
    with pytest.raises(PipelineError, match="unsupported talent-tooltips schemaVersion 2"):
        read_tooltips_document(path)
