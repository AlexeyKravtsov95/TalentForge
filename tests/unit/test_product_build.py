"""M5.DATA: the Forever product dataset 1.60.1.70245, end to end and offline.

1.60.1.70245 is the product dataset (new builds are created on it); 1.60.1.70009 stays the
verified reference baseline every older test, the live-client evidence and the M2
conformance vectors are pinned to. These tests prove that the committed 70245 artifacts are
exactly what the unchanged pipeline produces from that build's own client rows, that the
build brings no mechanic the allocation engine does not support, and that the documented
70009 -> 70245 changes are the real ones. Facts and evidence: docs/forever-dataset.md.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from talent_data.allocation import TalentAllocationEngine
from talent_data.games import get_game
from talent_data.models.talents import TalentDataDocument
from talent_data.pipeline import diff_batch, read_document, read_manifest
from talent_data.sources.local import LocalCsvDb2Source
from talent_data.tooltips.coverage import render_markdown
from talent_data.tooltips.pipeline import check_canonical as check_tooltips
from talent_data.tooltips.pipeline import read_coverage_report, render_build
from talent_data.validation.validators import validate_document
from talent_data.visuals.pipeline import check_canonical as check_visuals
from talent_data.visuals.pipeline import read_icon_registry, read_visuals_document
from talent_data.visuals.resolver import VisualSourceTables, resolve_document
from tests.conftest import ALL_CLASSES, normalize_fixture_class
from tests.helpers import FIXTURES, NEW_BUILD, PRODUCT_BUILD
from tests.unit.test_known_anomalies import EXPECTED_WARNINGS

ROOT = Path(__file__).resolve().parents[2]
GAME = get_game("forever")
DATASET = ROOT / "data" / "normalized" / "forever" / str(PRODUCT_BUILD)
REFERENCE_DATASET = ROOT / "data" / "normalized" / "forever" / str(NEW_BUILD)
VISUALS = ROOT / "data" / "visuals" / "forever" / str(PRODUCT_BUILD)
TOOLTIPS = ROOT / "data" / "tooltips" / "forever" / str(PRODUCT_BUILD)
DIFFS = ROOT / "artifacts" / "diff"
TOOLTIP_REPORT = ROOT / "artifacts" / "tooltips" / f"forever-{PRODUCT_BUILD}-tooltip-coverage.json"
TOOLTIP_MARKDOWN = ROOT / "docs" / "research" / f"forever-{PRODUCT_BUILD}-tooltip-coverage.md"
TOOLTIP_FIXTURES = FIXTURES / "tooltips" / "forever" / str(PRODUCT_BUILD)

# class: (tree_id, nodes, point budget, prerequisite links, total ranks, entries) of 70245.
# Identical to 70009 except Druid (+1 node "Improved Shifting Power", +2 links).
CLASS_STATS = {
    "warrior": (1117, 53, 51, 8, 154, 53),
    "paladin": (1100, 50, 51, 7, 138, 50),
    "hunter": (1091, 52, 51, 9, 158, 52),
    "rogue": (1111, 53, 51, 8, 139, 53),
    "priest": (1114, 54, 51, 8, 153, 54),
    "shaman": (1082, 50, 51, 7, 148, 50),
    "mage": (1112, 54, 51, 6, 146, 54),
    "warlock": (1116, 52, 51, 8, 149, 52),
    "druid": (1089, 52, 51, 11, 148, 52),
}
VENOM = 105712
SHIFTING_POWER = 104951
IMPROVED_SHIFTING_POWER = 113563


@pytest.fixture(scope="module")
def committed() -> dict[str, TalentDataDocument]:
    return {key: read_document(DATASET / f"{key}.json") for key in ALL_CLASSES}


@pytest.fixture(scope="module")
def reference() -> dict[str, TalentDataDocument]:
    return {key: read_document(REFERENCE_DATASET / f"{key}.json") for key in ALL_CLASSES}


@pytest.fixture
def at_repo_root(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("TALENT_DATA_ROOT", str(ROOT))


# --------------------------------------------------------------------------- dataset


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_committed_document_is_reproduced_from_the_build_rows(
    class_key: str, committed: dict[str, TalentDataDocument]
) -> None:
    """No hand edits: normalizing the build's own fixture rows yields the committed
    semantic payload (only ``generatedAt``/provenance source differ)."""
    fresh = normalize_fixture_class(class_key, PRODUCT_BUILD)
    assert fresh.client_build == str(PRODUCT_BUILD)
    assert fresh.content_hash == committed[class_key].content_hash


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_class_shape_and_point_budget(
    class_key: str, committed: dict[str, TalentDataDocument]
) -> None:
    tree_id, nodes, points, links, ranks, entries = CLASS_STATS[class_key]
    (tree,) = committed[class_key].trees
    assert tree.id == tree_id
    assert len(tree.nodes) == nodes
    assert tree.point_budget is not None and tree.point_budget.max_points == points
    assert sum(len(n.prerequisites) for n in tree.nodes) == links
    assert sum(n.max_rank for n in tree.nodes) == ranks
    assert sum(len(n.entries) for n in tree.nodes) == entries


def test_warnings_are_exactly_the_investigated_set_of_the_reference_baseline(
    committed: dict[str, TalentDataDocument],
) -> None:
    """70245 raises no new warning code and no new instance: the investigated 70009 set."""
    for class_key in ALL_CLASSES:
        report = validate_document(committed[class_key])
        assert report.errors == [], class_key
        assert report.count_by_code() == EXPECTED_WARNINGS[class_key], class_key


def test_manifest_lists_the_nine_committed_documents(
    committed: dict[str, TalentDataDocument],
) -> None:
    manifest = read_manifest(DATASET / "manifest.json")
    assert (manifest.game, manifest.client_build) == ("forever", str(PRODUCT_BUILD))
    assert [c.token for c in manifest.classes] == [k.upper() for k in ALL_CLASSES]
    for entry in manifest.classes:
        assert entry.content_hash == committed[entry.token.lower()].content_hash


def test_committed_diff_against_the_reference_baseline_is_current(
    committed: dict[str, TalentDataDocument], reference: dict[str, TalentDataDocument]
) -> None:
    reports, aggregate = diff_batch(list(reference.values()), list(committed.values()))
    name = f"forever-all-{NEW_BUILD}-{PRODUCT_BUILD}.json"
    assert aggregate.to_json() == (DIFFS / name).read_text(encoding="utf-8")
    for report in reports:
        file = f"forever-{report.class_.token.lower()}-{NEW_BUILD}-{PRODUCT_BUILD}.json"
        assert report.to_json() == (DIFFS / file).read_text(encoding="utf-8"), file
    assert (aggregate.total.added, aggregate.total.removed, aggregate.total.changed) == (1, 0, 36)


def test_druid_changes_that_make_saved_builds_build_specific(
    committed: dict[str, TalentDataDocument], reference: dict[str, TalentDataDocument]
) -> None:
    """The same node id means different things in the two builds - why a saved build is
    opened on its own build and never migrated (docs/forever-dataset.md)."""
    old = {n.id: n for n in reference["druid"].trees[0].nodes}
    new = {n.id: n for n in committed["druid"].trees[0].nodes}
    assert set(new) - set(old) == {IMPROVED_SHIFTING_POWER}
    assert set(old) - set(new) == set()
    assert (old[SHIFTING_POWER].max_rank, new[SHIFTING_POWER].max_rank) == (3, 1)
    assert old[SHIFTING_POWER].entries[0].spell_id != new[SHIFTING_POWER].entries[0].spell_id
    improved = new[IMPROVED_SHIFTING_POWER]
    assert (improved.name, improved.max_rank) == ("Improved Shifting Power", 2)
    assert [(p.node_id, p.kind) for p in improved.prerequisites] == [(SHIFTING_POWER, "sufficient")]


# --------------------------------------------------------------------------- allocation


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_engine_supports_every_node_except_the_known_unresolved_venom(
    class_key: str, committed: dict[str, TalentDataDocument]
) -> None:
    engine = TalentAllocationEngine(committed[class_key])
    expected = {VENOM} if class_key == "rogue" else set()
    assert engine.unsupported_nodes().keys() == expected


def test_a_reference_build_is_not_valid_on_the_product_build(
    committed: dict[str, TalentDataDocument], reference: dict[str, TalentDataDocument]
) -> None:
    """A 70009 Druid build with Shifting Power 3/3 is valid on 70009 and invalid on 70245
    (max rank 1): re-reading old builds against the product build would change them."""
    old_engine = TalentAllocationEngine(reference["druid"])
    state = old_engine.empty_state()
    while old_engine.can_spend(state, SHIFTING_POWER).allowed is False:
        candidates = [
            n.id
            for n in old_engine.tree.nodes
            if n.section_index == 1 and n.id != SHIFTING_POWER
            if old_engine.can_spend(state, n.id).allowed
        ]
        assert candidates, "Shifting Power must be reachable on 70009"
        state = old_engine.spend(state, min(candidates)).state
    for _ in range(3):
        state = old_engine.spend(state, SHIFTING_POWER).state
    assert state.rank_of(SHIFTING_POWER) == 3
    assert old_engine.validate(state).valid

    new_engine = TalentAllocationEngine(committed["druid"])
    assert not new_engine.validate(state).valid


# --------------------------------------------------------------------------- visuals


def test_committed_visuals_are_consistent_with_the_product_dataset(at_repo_root) -> None:
    assert check_visuals(GAME, PRODUCT_BUILD) == []


def test_icons_resolve_from_the_build_s_own_rows(
    committed: dict[str, TalentDataDocument], merged_source: LocalCsvDb2Source
) -> None:
    """Every sidecar icon is the one the 70245 TraitDefinition/SpellMisc rows name -
    nothing is carried over from another build."""
    tables = VisualSourceTables.load(merged_source, PRODUCT_BUILD)
    registry = read_icon_registry(VISUALS / "icons.json")
    assert registry.client_build == str(PRODUCT_BUILD)
    registered = {icon.file_data_id for icon in registry.icons}
    used: set[int] = set()
    for class_key, document in committed.items():
        sidecar = read_visuals_document(VISUALS / f"{class_key}.json")
        assert sidecar.client_build == str(PRODUCT_BUILD)
        assert sidecar.talent_data_content_hash == document.content_hash
        resolved = {r.entry_id: r.icon_file_data_id for r in resolve_document(document, tables)}
        assert {e.entry_id: e.icon_file_data_id for e in sidecar.entries} == resolved
        used.update(resolved.values())
    assert registered == used
    assert len(registered) == 357
    images = {p.name for p in (VISUALS / "icons").glob("*.png")}
    assert images == {f"{file_data_id}.png" for file_data_id in registered}


# --------------------------------------------------------------------------- tooltips


def test_committed_tooltips_are_consistent_with_the_product_dataset(at_repo_root) -> None:
    assert check_tooltips(GAME, PRODUCT_BUILD) == []


def test_regenerating_tooltips_from_the_build_rows_reproduces_the_committed_files(
    at_repo_root,
) -> None:
    source = LocalCsvDb2Source(TOOLTIP_FIXTURES, per_version=False)
    build = render_build(source, GAME, PRODUCT_BUILD, source_name="wago.tools")
    for token, document in build.tooltips.items():
        committed = (TOOLTIPS / f"{token.lower()}.json").read_text(encoding="utf-8")
        assert document.to_json() == committed, f"{token} sidecar is stale"
    assert build.report.to_json() == TOOLTIP_REPORT.read_text(encoding="utf-8")
    assert render_markdown(build.report) == TOOLTIP_MARKDOWN.read_text(encoding="utf-8")


def test_tooltip_coverage_and_the_explicitly_unresolved_rank() -> None:
    report = read_coverage_report(TOOLTIP_REPORT)
    totals = report.totals
    assert (totals.entries, totals.ranks) == (470, 1333)
    assert (totals.resolved, totals.partial, totals.unresolved) == (1289, 43, 1)
    # the only unresolved rank: Druid Shifting Power's new "$c" mana-cost reference is
    # outside the supported grammar and stays an explicit gap, never a guessed number
    druid = json.loads((TOOLTIPS / "druid.json").read_text(encoding="utf-8"))
    unresolved = [
        (entry["entryId"], rank["rank"])
        for entry in druid["entries"]
        for rank in entry["ranks"]
        if rank["status"] == "unresolved"
    ]
    assert unresolved == [(129611, 1)]
