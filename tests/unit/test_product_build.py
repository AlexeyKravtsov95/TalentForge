"""M5.DATA.4: the Forever product dataset 1.60.1.70450, end to end and offline.

1.60.1.70450 is the product dataset (new builds are created on it). 1.60.1.70374 (M5.DATA.3,
tests/unit/test_build_70374.py), 1.60.1.70338 (M5.DATA.2, tests/unit/test_build_70338.py)
and 1.60.1.70245 (M5.DATA, tests/unit/test_build_70245.py) stay committed and bundled for
the builds saved on them; 1.60.1.70009 stays the verified reference baseline. Every artifact
here is generated from 70450's own client rows (DB2, icon files, tooltip tables, ruRU
strings), never copied.

70450 is a real balance patch (1 added, 1 removed, 65 changed nodes against 70374) with one
new kind of anomaly: the new Mage node Arcane Wrath (113627) belongs to no node group any
gate counts. It joins the Arcane tab because its column lies in the Arcane columns (layout,
flagged SECTION_NOT_GATE_COUNTED), while its gates stay exactly as the client rows say: no
points gate, and its point counts toward no Arcane gate. These tests pin the cause, the rule
and its fail-closed edges, and every other fact. Facts and evidence: docs/forever-dataset.md.
"""

from __future__ import annotations

import csv
import dataclasses
import json
from pathlib import Path

import pytest
from PIL import Image

from talent_data.allocation import BuildState, TalentAllocationEngine
from talent_data.games import get_game
from talent_data.localization.coverage import render_markdown as render_localization_markdown
from talent_data.localization.pipeline import check_canonical as check_localization
from talent_data.localization.pipeline import localize_build, read_localization_document
from talent_data.localization.pipeline import read_coverage_report as read_localization_report
from talent_data.models.talents import TalentDataDocument, TalentNode
from talent_data.normalization.normalizer import normalize_class
from talent_data.pipeline import diff_batch, read_document, read_manifest
from talent_data.sources.local import LocalCsvDb2Source
from talent_data.tooltips.coverage import render_markdown
from talent_data.tooltips.pipeline import check_canonical as check_tooltips
from talent_data.tooltips.pipeline import read_coverage_report, render_build
from talent_data.validation.validators import validate_document
from talent_data.visuals.pipeline import check_canonical as check_visuals
from talent_data.visuals.pipeline import read_icon_registry, read_visuals_document
from talent_data.visuals.resolver import VisualSourceTables, resolve_document
from tests.conftest import ALL_CLASSES, load_dataset, normalize_fixture_class
from tests.helpers import DB2_FIXTURES, FIXTURES, PREVIOUS_PRODUCT_BUILD, PRODUCT_BUILD
from tests.unit.test_known_anomalies import EXPECTED_WARNINGS

ROOT = Path(__file__).resolve().parents[2]
GAME = get_game("forever")
LOCALE = "ruRU"
DATASET = ROOT / "data" / "normalized" / "forever" / str(PRODUCT_BUILD)
PREVIOUS_DATASET = ROOT / "data" / "normalized" / "forever" / str(PREVIOUS_PRODUCT_BUILD)
VISUALS = ROOT / "data" / "visuals" / "forever" / str(PRODUCT_BUILD)
PREVIOUS_VISUALS = ROOT / "data" / "visuals" / "forever" / str(PREVIOUS_PRODUCT_BUILD)
TOOLTIPS = ROOT / "data" / "tooltips" / "forever" / str(PRODUCT_BUILD)
OVERLAYS = ROOT / "data" / "localization" / "forever" / str(PRODUCT_BUILD) / LOCALE
PREVIOUS_OVERLAYS = ROOT / "data" / "localization" / "forever" / str(PREVIOUS_PRODUCT_BUILD)
DIFFS = ROOT / "artifacts" / "diff"
TOOLTIP_REPORT = ROOT / "artifacts" / "tooltips" / f"forever-{PRODUCT_BUILD}-tooltip-coverage.json"
TOOLTIP_MARKDOWN = ROOT / "docs" / "research" / f"forever-{PRODUCT_BUILD}-tooltip-coverage.md"
TOOLTIP_FIXTURES = FIXTURES / "tooltips" / "forever" / str(PRODUCT_BUILD)
LOCALIZATION_REPORT = (
    ROOT / "artifacts" / "localization" / f"forever-{PRODUCT_BUILD}-{LOCALE}-coverage.json"
)
LOCALIZATION_MARKDOWN = ROOT / "docs" / "research" / f"forever-{PRODUCT_BUILD}-{LOCALE}-coverage.md"
LOCALIZATION_FIXTURES = FIXTURES / "localization" / "forever" / str(PRODUCT_BUILD)
MAGE_ROWS = DB2_FIXTURES / str(PRODUCT_BUILD) / "mage"

# class: (tree_id, nodes, point budget, prerequisite links, total ranks, entries, sections)
# of 70450.
CLASS_STATS = {
    "warrior": (1117, 52, 51, 8, 150, 52, 3),
    "paladin": (1100, 50, 51, 7, 138, 50, 3),
    "hunter": (1091, 52, 51, 9, 158, 52, 3),
    "rogue": (1111, 53, 51, 8, 139, 53, 3),
    "priest": (1114, 53, 51, 8, 153, 53, 3),
    "shaman": (1082, 50, 51, 7, 148, 50, 3),
    "mage": (1112, 55, 51, 7, 148, 55, 3),
    "warlock": (1116, 52, 51, 8, 146, 52, 3),
    "druid": (1089, 52, 51, 11, 148, 52, 3),
}
# The investigated warning set of 70450: the reference baseline's, except Mage (below).
PRODUCT_WARNINGS = {
    **EXPECTED_WARNINGS,
    "mage": {"SECTION_NOT_GATE_COUNTED": 1},
}
# class: (added, removed, changed) of the 70374 -> 70450 diff.
DIFF_TOTALS = {
    "warrior": (0, 0, 1),
    "paladin": (0, 0, 3),
    "hunter": (0, 0, 6),
    "rogue": (0, 0, 0),
    "priest": (0, 1, 11),
    "shaman": (0, 0, 15),
    "mage": (1, 0, 21),
    "warlock": (0, 0, 5),
    "druid": (0, 0, 3),
}
VENOM = 105712
ARCANE_WRATH = 113627  # new in 1.60.1.70425, unchanged in 70450
ARCANE_BLAST = 105806
ARCANE_WRATH_GROUPS = {12862, 12863, 12864, 12865}
IMPROVED_MIND_BLAST_70374 = 105827  # Priest, removed in 70450
DARK_INSPIRATION = 105823  # Priest: Improved Fade (max 2) on 70374, Dark Inspiration (max 5)


@pytest.fixture(scope="module")
def committed() -> dict[str, TalentDataDocument]:
    return {key: read_document(DATASET / f"{key}.json") for key in ALL_CLASSES}


@pytest.fixture(scope="module")
def previous() -> dict[str, TalentDataDocument]:
    return {key: read_document(PREVIOUS_DATASET / f"{key}.json") for key in ALL_CLASSES}


@pytest.fixture
def at_repo_root(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("TALENT_DATA_ROOT", str(ROOT))


def _nodes(document: TalentDataDocument) -> dict[int, TalentNode]:
    return {n.id: n for n in document.trees[0].nodes}


def _rows(table: str) -> list[dict[str, str]]:
    with (MAGE_ROWS / f"{table}.csv").open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def _gate_groups(document: TalentDataDocument) -> list[frozenset[int]]:
    """The maximal node groups the tree's gates count, ordered by their leftmost node -
    the groups the normalizer accepts as talent tabs when they partition the tree."""
    nodes = _nodes(document)
    referenced = {
        frozenset(req.counted_node_ids) for n in nodes.values() for req in n.unlock_requirements
    }
    maximal = [g for g in referenced if not any(g < other for other in referenced)]
    return sorted(maximal, key=lambda g: min(nodes[i].position.x for i in g))


# --------------------------------------------------------------------------- dataset


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_committed_document_is_reproduced_from_the_build_rows(
    class_key: str, committed: dict[str, TalentDataDocument]
) -> None:
    """Normalizing 70450's own fixture rows yields the committed semantic payload."""
    fresh = normalize_fixture_class(class_key, PRODUCT_BUILD)
    assert fresh.client_build == str(PRODUCT_BUILD)
    assert fresh.content_hash == committed[class_key].content_hash


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_class_shape_and_point_budget(
    class_key: str, committed: dict[str, TalentDataDocument]
) -> None:
    tree_id, nodes, points, links, ranks, entries, sections = CLASS_STATS[class_key]
    (tree,) = committed[class_key].trees
    assert tree.id == tree_id
    assert len(tree.nodes) == nodes
    assert tree.point_budget is not None and tree.point_budget.max_points == points
    assert sum(len(n.prerequisites) for n in tree.nodes) == links
    assert sum(n.max_rank for n in tree.nodes) == ranks
    assert sum(len(n.entries) for n in tree.nodes) == entries
    assert len(tree.sections) == sections


def test_warnings_are_exactly_the_investigated_set(
    committed: dict[str, TalentDataDocument],
) -> None:
    """The reference baseline's warnings plus the investigated Mage anomaly - nothing else.
    A new code or count fails here until it is investigated."""
    for class_key in ALL_CLASSES:
        report = validate_document(committed[class_key])
        assert report.errors == [], class_key
        assert report.count_by_code() == PRODUCT_WARNINGS[class_key], class_key


def test_manifest_lists_the_nine_committed_documents(
    committed: dict[str, TalentDataDocument],
) -> None:
    manifest = read_manifest(DATASET / "manifest.json")
    assert (manifest.game, manifest.client_build) == ("forever", str(PRODUCT_BUILD))
    assert [c.token for c in manifest.classes] == [k.upper() for k in ALL_CLASSES]
    for entry in manifest.classes:
        assert entry.content_hash == committed[entry.token.lower()].content_hash
        assert entry.validation.errors == 0
        assert entry.validation.warnings_by_code == PRODUCT_WARNINGS[entry.token.lower()]


def test_committed_diff_against_the_previous_product_dataset_is_current(
    committed: dict[str, TalentDataDocument], previous: dict[str, TalentDataDocument]
) -> None:
    reports, aggregate = diff_batch(list(previous.values()), list(committed.values()))
    old, new = PREVIOUS_PRODUCT_BUILD, PRODUCT_BUILD
    assert aggregate.to_json() == (DIFFS / f"forever-all-{old}-{new}.json").read_text(
        encoding="utf-8"
    )
    for report in reports:
        file = f"forever-{report.class_.token.lower()}-{old}-{new}.json"
        assert report.to_json() == (DIFFS / file).read_text(encoding="utf-8"), file
        summary = report.summary
        totals = (summary.added, summary.removed, summary.changed)
        assert totals == DIFF_TOTALS[report.class_.token.lower()], file
    assert (aggregate.total.added, aggregate.total.removed, aggregate.total.changed) == (1, 1, 65)


# --------------------------------------------------------------------------- the Mage anomaly


def test_arcane_wrath_is_in_no_gate_group_and_joins_the_arcane_tab_by_its_column(
    committed: dict[str, TalentDataDocument],
) -> None:
    """The three maximal gate groups cover every Mage node except Arcane Wrath, exactly as
    on 70374; Arcane Wrath's x lies inside the Arcane group's column range only, so it is the
    Arcane tab's 19th node - while no gate counts it."""
    mage = committed["mage"]
    (tree,) = mage.trees
    nodes = _nodes(mage)
    arcane, fire, frost = _gate_groups(mage)
    assert sum(len(g) for g in (arcane, fire, frost)) == len(arcane | fire | frost)  # disjoint
    assert arcane | fire | frost == frozenset(nodes) - {ARCANE_WRATH}
    assert [sorted(s.node_ids) for s in tree.sections] == [
        sorted(arcane | {ARCANE_WRATH}),
        sorted(fire),
        sorted(frost),
    ]
    assert nodes[ARCANE_WRATH].section_index == 0
    assert (
        min(nodes[i].position.x for i in arcane) <= 2820 <= max(nodes[i].position.x for i in arcane)
    )
    counted = {i for n in tree.nodes for req in n.unlock_requirements for i in req.counted_node_ids}
    assert ARCANE_WRATH not in counted
    warnings = [w for w in validate_document(mage).warnings if w.code == "SECTION_NOT_GATE_COUNTED"]
    assert [w.node_id for w in warnings] == [ARCANE_WRATH]


def test_arcane_wrath_is_ungated_and_requires_arcane_blast(
    committed: dict[str, TalentDataDocument],
) -> None:
    """Arcane Wrath sits in row 3 of the Arcane columns (Missile Barrage's 70374 cell) but,
    by the client rows, has no points gate - only its sufficient prerequisite."""
    node = _nodes(committed["mage"])[ARCANE_WRATH]
    assert node.name == "Arcane Wrath"
    assert node.max_rank == 1
    assert (node.position.x, node.position.y) == (2820, 3930)
    assert node.unlock_requirements == []
    assert node.unresolved_condition_ids == []
    assert [(p.node_id, p.kind) for p in node.prerequisites] == [(ARCANE_BLAST, "sufficient")]


def test_arcane_wrath_raw_rows_point_at_four_single_node_groups_without_conditions() -> None:
    """The client rows behind the anomaly: four TraitNodeGroupXTraitNode rows for 113627,
    each into a group that has no other member, and no TraitCond / TraitNodeGroupXTraitCond
    / TraitNodeXTraitCond row refers to any of those groups or to the node."""
    membership = _rows("TraitNodeGroupXTraitNode")
    own = {int(r["TraitNodeGroupID"]) for r in membership if int(r["TraitNodeID"]) == ARCANE_WRATH}
    assert own == ARCANE_WRATH_GROUPS
    for group in ARCANE_WRATH_GROUPS:
        members = {int(r["TraitNodeID"]) for r in membership if int(r["TraitNodeGroupID"]) == group}
        assert members == {ARCANE_WRATH}
    gated = {int(r["TraitNodeGroupID"]) for r in _rows("TraitNodeGroupXTraitCond")}
    counted = {int(r["TraitNodeGroupID"]) for r in _rows("TraitCond")}
    assert not ARCANE_WRATH_GROUPS & (gated | counted)
    assert all(int(r["TraitNodeID"]) != ARCANE_WRATH for r in _rows("TraitNodeXTraitCond"))
    edges = [r for r in _rows("TraitEdge") if int(r["RightTraitNodeID"]) == ARCANE_WRATH]
    assert [(int(r["LeftTraitNodeID"]), int(r["Type"])) for r in edges] == [(ARCANE_BLAST, 2)]


def test_arcane_wrath_sits_on_the_arcane_grid(committed: dict[str, TalentDataDocument]) -> None:
    """With the tabs resolved, Arcane Wrath is on its tab's grid: row 3, column 3 - Missile
    Barrage's 70374 cell - and the Mage tree raises no off-grid warning."""
    node = _nodes(committed["mage"])[ARCANE_WRATH]
    assert (node.position.row, node.position.column) == (3, 3)
    codes = validate_document(committed["mage"]).count_by_code()
    assert "OFF_GRID_NODE" not in codes


def _mage_with_arcane_wrath_at(x: int) -> TalentDataDocument:
    """The real 70450 Mage rows with only Arcane Wrath's raw x moved."""
    ds = load_dataset("mage", PRODUCT_BUILD)
    moved = [
        dataclasses.replace(n, pos_x=x) if n.id == ARCANE_WRATH else n
        for n in ds.nodes_by_tree[1112]
    ]
    return normalize_class(
        dataclasses.replace(ds, nodes_by_tree={**ds.nodes_by_tree, 1112: moved}),
        game="forever",
        class_key="mage",
        source_name="fixture",
    )


def test_an_ungated_node_between_two_tabs_leaves_the_tabs_unresolved() -> None:
    """Fail closed: x = 4000 lies between the Arcane (..2820) and Fire (5020..) columns, so
    no tab contains it and no section is published - nothing is guessed."""
    document = _mage_with_arcane_wrath_at(4000)
    assert document.trees[0].sections == []
    assert validate_document(document).count_by_code()["SECTIONS_UNRESOLVED"] == 1


def test_an_ungated_node_follows_its_column_not_its_prerequisite() -> None:
    """The rule is the column alone: moved into the Fire columns, Arcane Wrath would be a
    Fire node although its prerequisite (Arcane Blast) is in Arcane."""
    document = _mage_with_arcane_wrath_at(5620)
    nodes = _nodes(document)
    assert nodes[ARCANE_WRATH].section_index == 1
    assert nodes[ARCANE_BLAST].section_index == 0


# --------------------------------------------------------------------------- allocation


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_engine_supports_every_node_except_the_known_unresolved_venom(
    class_key: str, committed: dict[str, TalentDataDocument]
) -> None:
    engine = TalentAllocationEngine(committed[class_key])
    expected = {VENOM} if class_key == "rogue" else set()
    assert engine.unsupported_nodes().keys() == expected


def _spend_until_maxed(
    engine: TalentAllocationEngine, target: int, candidates: frozenset[int]
) -> BuildState:
    """Spend the lowest-id allowed node of ``candidates`` until the target is purchasable,
    then max it - a real engine-made build, never hand-written."""
    node = next(n for n in engine.tree.nodes if n.id == target)
    state = engine.empty_state()
    while not engine.can_spend(state, target).allowed:
        allowed = [n for n in sorted(candidates - {target}) if engine.can_spend(state, n).allowed]
        assert allowed, f"node {target} must be reachable"
        state = engine.spend(state, min(allowed)).state
    for _ in range(node.max_rank):
        state = engine.spend(state, target).state
    assert state.rank_of(target) == node.max_rank
    assert engine.validate(state).valid
    return state


def _section_of(document: TalentDataDocument, node_id: int) -> frozenset[int]:
    section = _nodes(document)[node_id].section_index
    return frozenset(n.id for n in document.trees[0].nodes if n.section_index == section)


def test_arcane_wrath_is_purchasable_once_arcane_blast_is_fully_ranked(
    committed: dict[str, TalentDataDocument],
) -> None:
    """The engine reads the rows literally: Arcane Wrath is closed on an empty build and
    opens exactly when its prerequisite is fully ranked - nothing is invented for it."""
    engine = TalentAllocationEngine(committed["mage"])
    assert not engine.can_spend(engine.empty_state(), ARCANE_WRATH).allowed
    arcane = _gate_groups(committed["mage"])[0]
    state = _spend_until_maxed(engine, ARCANE_BLAST, arcane)
    assert engine.can_spend(state, ARCANE_WRATH).allowed


def test_a_previous_product_build_is_not_valid_on_the_product_build(
    committed: dict[str, TalentDataDocument], previous: dict[str, TalentDataDocument]
) -> None:
    """A 70374 Priest build with Improved Mind Blast (105827) 5/5 is valid on 70374 and
    invalid on 70450, where the node no longer exists: re-reading saved builds against the
    product build would change them."""
    engine = TalentAllocationEngine(previous["priest"])
    candidates = _section_of(previous["priest"], IMPROVED_MIND_BLAST_70374)
    state = _spend_until_maxed(engine, IMPROVED_MIND_BLAST_70374, candidates)
    assert IMPROVED_MIND_BLAST_70374 not in _nodes(committed["priest"])
    assert not TalentAllocationEngine(committed["priest"]).validate(state).valid


def test_a_previous_product_build_can_stay_valid_and_still_change_meaning(
    committed: dict[str, TalentDataDocument], previous: dict[str, TalentDataDocument]
) -> None:
    """Node 105823 at 2/2 is Improved Fade on 70374 and Dark Inspiration (max 5) on 70450 -
    the ranks still validate, the talent does not match. Validity is no license to migrate:
    a saved build always opens on its own build. (The build avoids the removed node, which
    the test above covers.)"""
    engine = TalentAllocationEngine(previous["priest"])
    candidates = _section_of(previous["priest"], DARK_INSPIRATION) - {IMPROVED_MIND_BLAST_70374}
    state = _spend_until_maxed(engine, DARK_INSPIRATION, candidates)
    assert TalentAllocationEngine(committed["priest"]).validate(state).valid
    old, new = _nodes(previous["priest"]), _nodes(committed["priest"])
    assert (old[DARK_INSPIRATION].name, old[DARK_INSPIRATION].max_rank) == ("Improved Fade", 2)
    assert (new[DARK_INSPIRATION].name, new[DARK_INSPIRATION].max_rank) == ("Dark Inspiration", 5)
    assert old[DARK_INSPIRATION].entries[0].spell_id != new[DARK_INSPIRATION].entries[0].spell_id


# --------------------------------------------------------------------------- visuals


def test_committed_visuals_are_consistent_with_the_product_dataset(at_repo_root) -> None:
    assert check_visuals(GAME, PRODUCT_BUILD) == []


def test_icons_resolve_from_the_build_s_own_rows(
    committed: dict[str, TalentDataDocument], merged_source: LocalCsvDb2Source
) -> None:
    """Every sidecar icon is the one the 70450 TraitDefinition/SpellMisc rows name, and
    every sidecar is linked to the 70450 document's hash."""
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
    assert len(registered) == 359
    images = {p.name for p in (VISUALS / "icons").glob("*.png")}
    assert images == {f"{file_data_id}.png" for file_data_id in registered}


def test_icon_changes_against_the_previous_build_and_the_shared_icons_pixels() -> None:
    """3 icons are new and 2 no longer used. The 356 shared client files are byte-identical
    to 70374's (same source hash) and decode to the same pixels; their PNG bytes may differ,
    because Pillow's bundled zlib build (zlib-ng in the macOS wheel) shapes the compressed
    stream - recorded in docs/talent-visuals.md."""
    current = {i.file_data_id: i for i in read_icon_registry(VISUALS / "icons.json").icons}
    before = {i.file_data_id: i for i in read_icon_registry(PREVIOUS_VISUALS / "icons.json").icons}
    assert sorted(current.keys() - before.keys()) == [136127, 236222, 463284]
    assert sorted(before.keys() - current.keys()) == [136014, 136137]
    shared = sorted(current.keys() & before.keys())
    assert len(shared) == 356
    for file_data_id in shared:
        assert current[file_data_id].source_sha256 == before[file_data_id].source_sha256
        with (
            Image.open(VISUALS / "icons" / f"{file_data_id}.png") as new,
            Image.open(PREVIOUS_VISUALS / "icons" / f"{file_data_id}.png") as old,
        ):
            assert (new.mode, new.size) == (old.mode, old.size), file_data_id
            assert new.tobytes() == old.tobytes(), file_data_id


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
    assert (totals.entries, totals.ranks) == (469, 1328)
    assert (totals.resolved, totals.partial, totals.unresolved) == (1272, 55, 1)
    druid = json.loads((TOOLTIPS / "druid.json").read_text(encoding="utf-8"))
    unresolved = [
        (entry["entryId"], rank["rank"])
        for entry in druid["entries"]
        for rank in entry["ranks"]
        if rank["status"] == "unresolved"
    ]
    assert unresolved == [(129611, 1)]


# --------------------------------------------------------------------------- localization


def test_committed_localization_is_consistent_with_the_product_dataset(at_repo_root) -> None:
    assert check_localization(GAME, PRODUCT_BUILD, LOCALE) == []


def test_regenerating_the_ruRU_overlays_from_the_build_rows_reproduces_the_committed_files(
    at_repo_root,
) -> None:
    """The 70450 overlays, report and Markdown come from 70450's own enUS/ruRU rows and its
    own dataset and tooltip revisions, all nine classes."""
    build = localize_build(
        LocalCsvDb2Source(LOCALIZATION_FIXTURES / "enUS", per_version=False),
        LocalCsvDb2Source(LOCALIZATION_FIXTURES / LOCALE, per_version=False),
        LocalCsvDb2Source(TOOLTIP_FIXTURES, per_version=False),
        GAME,
        PRODUCT_BUILD,
        LOCALE,
        source_name="wago.tools",
    )
    for token, overlay in build.overlays.items():
        committed = (OVERLAYS / f"{token.lower()}.json").read_text(encoding="utf-8")
        assert overlay.to_json() == committed, f"{token} overlay is stale"
    report = build.report(frozenset(build.overlays))
    assert report.to_json() == LOCALIZATION_REPORT.read_text(encoding="utf-8")
    markdown = LOCALIZATION_MARKDOWN.read_text(encoding="utf-8")
    assert render_localization_markdown(report) == markdown


def test_the_ruRU_overlays_are_linked_to_the_product_revisions(
    committed: dict[str, TalentDataDocument],
) -> None:
    for class_key, document in committed.items():
        overlay = read_localization_document(OVERLAYS / f"{class_key}.json")
        tooltips = json.loads((TOOLTIPS / f"{class_key}.json").read_text(encoding="utf-8"))
        assert overlay.client_build == str(PRODUCT_BUILD)
        assert overlay.talent_data_content_hash == document.content_hash
        assert overlay.tooltips_content_hash == tooltips["contentHash"]
    # a build's overlay is never another build's: 70374 keeps its own revision links
    overlay = read_localization_document(OVERLAYS / "warrior.json")
    previous = read_localization_document(PREVIOUS_OVERLAYS / LOCALE / "warrior.json")
    assert overlay.talent_data_content_hash != previous.talent_data_content_hash
    assert overlay.content_hash != previous.content_hash


def test_ruRU_coverage_of_the_product_build() -> None:
    """Every class reported and published; every name translated."""
    report = read_localization_report(LOCALIZATION_REPORT)
    totals = report.totals
    assert (totals.entries, totals.ranks) == (469, 1328)
    names = totals.names
    assert (names.translated, names.untranslated, names.missing) == (469, 0, 0)
    assert totals.descriptions.rendered == 383
    assert totals.rendered_ranks == 1130
    assert [row.class_token for row in report.classes if row.published] == [
        key.upper() for key in ALL_CLASSES
    ]
    assert len(report.classes) == 9
