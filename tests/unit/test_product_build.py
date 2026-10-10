"""M5.DATA.2: the Forever product dataset 1.60.1.70338, end to end and offline.

1.60.1.70338 is the product dataset (new builds are created on it). 1.60.1.70245, the
product dataset of M5.DATA, stays committed and bundled for the builds saved on it
(tests/unit/test_build_70245.py); 1.60.1.70009 stays the verified reference baseline the
live-client evidence and the M2 conformance vectors are pinned to. These tests prove that
the committed 70338 artifacts are exactly what the unchanged pipeline produces from that
build's own client rows, that the build brings no mechanic the allocation engine does not
support, and that the documented 70245 -> 70338 changes (the Warrior rework) are the real
ones. Facts and evidence: docs/forever-dataset.md.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from talent_data.allocation import BuildState, TalentAllocationEngine
from talent_data.games import get_game
from talent_data.models.talents import TalentDataDocument, TalentNode
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
from tests.helpers import FIXTURES, PREVIOUS_PRODUCT_BUILD, PRODUCT_BUILD
from tests.unit.test_known_anomalies import EXPECTED_WARNINGS

ROOT = Path(__file__).resolve().parents[2]
GAME = get_game("forever")
DATASET = ROOT / "data" / "normalized" / "forever" / str(PRODUCT_BUILD)
PREVIOUS_DATASET = ROOT / "data" / "normalized" / "forever" / str(PREVIOUS_PRODUCT_BUILD)
VISUALS = ROOT / "data" / "visuals" / "forever" / str(PRODUCT_BUILD)
TOOLTIPS = ROOT / "data" / "tooltips" / "forever" / str(PRODUCT_BUILD)
DIFFS = ROOT / "artifacts" / "diff"
TOOLTIP_REPORT = ROOT / "artifacts" / "tooltips" / f"forever-{PRODUCT_BUILD}-tooltip-coverage.json"
TOOLTIP_MARKDOWN = ROOT / "docs" / "research" / f"forever-{PRODUCT_BUILD}-tooltip-coverage.md"
TOOLTIP_FIXTURES = FIXTURES / "tooltips" / "forever" / str(PRODUCT_BUILD)

# class: (tree_id, nodes, point budget, prerequisite links, total ranks, entries) of 70338.
# Identical to 70245 except Warrior (3 nodes removed, 2 added: 53 -> 52 nodes, 154 -> 150
# ranks; the prerequisite link count stays 8 although four links moved).
CLASS_STATS = {
    "warrior": (1117, 52, 51, 8, 150, 52),
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
# Warrior rework, 70245 -> 70338
PRECISION, IMPROVED_CLEAVE, TOUGHNESS = 105929, 105936, 105973  # removed
GORE_DRINKER, IRON_WILL = 113569, 113570  # added
BOUNDLESS_RAGE_NODE = 105953  # Boundless Rage on 70245, Furious Precision on 70338
IRON_WILL_NODE = 110857  # Iron Will on 70245, Lingering Rage on 70338
FLURRY, BLOODTHIRST, IMPALE, LAST_STAND = 105928, 105930, 105947, 105970
ENRAGE, DEATH_WISH, DEEP_WOUNDS, IMPROVED_BLOODRAGE = 105931, 105927, 105950, 105974


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
    assert len(tree.sections) == 3


def test_warnings_are_exactly_the_investigated_set_of_the_reference_baseline(
    committed: dict[str, TalentDataDocument],
) -> None:
    """70338 raises no new warning code and no new instance: the investigated 70009 set."""
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
    assert (aggregate.total.added, aggregate.total.removed, aggregate.total.changed) == (2, 3, 36)
    by_class = {
        r.class_.token: (r.summary.added, r.summary.removed, r.summary.changed) for r in reports
    }
    assert by_class["WARRIOR"] == (2, 3, 29)
    assert by_class["ROGUE"] == by_class["PRIEST"] == by_class["MAGE"] == (0, 0, 0)


def test_warrior_rework_nodes_and_prerequisites(
    committed: dict[str, TalentDataDocument], previous: dict[str, TalentDataDocument]
) -> None:
    """The Warrior rework as the client rows define it: removed and added nodes, spells
    replaced on unchanged node ids, and the four moved prerequisite links."""
    old, new = _nodes(previous["warrior"]), _nodes(committed["warrior"])
    assert set(old) - set(new) == {PRECISION, IMPROVED_CLEAVE, TOUGHNESS}
    assert set(new) - set(old) == {GORE_DRINKER, IRON_WILL}

    # one node id, two different talents (same max rank: the old ranks stay *valid*)
    assert (old[BOUNDLESS_RAGE_NODE].name, new[BOUNDLESS_RAGE_NODE].name) == (
        "Boundless Rage",
        "Furious Precision",
    )
    assert (old[IRON_WILL_NODE].name, new[IRON_WILL_NODE].name) == ("Iron Will", "Lingering Rage")
    for node_id in (BOUNDLESS_RAGE_NODE, IRON_WILL_NODE):
        assert old[node_id].max_rank == new[node_id].max_rank
        assert old[node_id].entries[0].spell_id != new[node_id].entries[0].spell_id
    # Iron Will's spell moved to a new node id in another section
    assert new[IRON_WILL].entries[0].spell_id == old[IRON_WILL_NODE].entries[0].spell_id
    assert (old[IRON_WILL_NODE].section_index, new[IRON_WILL].section_index) == (1, 2)

    def prereqs(nodes: dict[int, TalentNode], node_id: int) -> list[tuple[int, str]]:
        return [(p.node_id, p.kind) for p in nodes[node_id].prerequisites]

    assert (prereqs(old, FLURRY), prereqs(new, FLURRY)) == (
        [(ENRAGE, "sufficient")],
        [(DEATH_WISH, "sufficient")],
    )
    assert (prereqs(old, BLOODTHIRST), prereqs(new, BLOODTHIRST)) == (
        [(DEATH_WISH, "sufficient")],
        [],
    )
    assert (prereqs(old, IMPALE), prereqs(new, IMPALE)) == ([], [(DEEP_WOUNDS, "sufficient")])
    assert (prereqs(old, LAST_STAND), prereqs(new, LAST_STAND)) == (
        [(IMPROVED_BLOODRAGE, "sufficient")],
        [],
    )
    assert prereqs(new, GORE_DRINKER) == [(ENRAGE, "sufficient")]


# --------------------------------------------------------------------------- allocation


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_engine_supports_every_node_except_the_known_unresolved_venom(
    class_key: str, committed: dict[str, TalentDataDocument]
) -> None:
    engine = TalentAllocationEngine(committed[class_key])
    expected = {VENOM} if class_key == "rogue" else set()
    assert engine.unsupported_nodes().keys() == expected


def _spend_until_maxed(engine: TalentAllocationEngine, target: int) -> BuildState:
    """Spend the lowest-id allowed node of the target's section until the target is
    purchasable, then max it - a real engine-made build, never hand-written."""
    node = next(n for n in engine.tree.nodes if n.id == target)
    state = engine.empty_state()
    while engine.can_spend(state, target).allowed is False:
        candidates = [
            n.id
            for n in engine.tree.nodes
            if n.section_index == node.section_index and n.id != target
            if engine.can_spend(state, n.id).allowed
        ]
        assert candidates, f"node {target} must be reachable"
        state = engine.spend(state, min(candidates)).state
    for _ in range(node.max_rank):
        state = engine.spend(state, target).state
    assert state.rank_of(target) == node.max_rank
    assert engine.validate(state).valid
    return state


def test_a_previous_product_build_is_not_valid_on_the_product_build(
    committed: dict[str, TalentDataDocument], previous: dict[str, TalentDataDocument]
) -> None:
    """A 70245 Warrior build with Toughness 5/5 is valid on 70245 and invalid on 70338,
    where node 105973 no longer exists: re-reading saved builds against the product build
    would change them."""
    state = _spend_until_maxed(TalentAllocationEngine(previous["warrior"]), TOUGHNESS)
    assert not TalentAllocationEngine(committed["warrior"]).validate(state).valid


def test_a_previous_product_build_can_stay_valid_and_still_change_meaning(
    committed: dict[str, TalentDataDocument], previous: dict[str, TalentDataDocument]
) -> None:
    """Node 105953 at 3/3 is Boundless Rage on 70245 and Furious Precision on 70338 - the
    ranks still validate, the talent does not match. Validity is no license to migrate:
    a saved build always opens on its own build."""
    state = _spend_until_maxed(TalentAllocationEngine(previous["warrior"]), BOUNDLESS_RAGE_NODE)
    assert TalentAllocationEngine(committed["warrior"]).validate(state).valid
    old, new = _nodes(previous["warrior"]), _nodes(committed["warrior"])
    old_spell = old[BOUNDLESS_RAGE_NODE].entries[0].spell_id
    assert old_spell != new[BOUNDLESS_RAGE_NODE].entries[0].spell_id


# --------------------------------------------------------------------------- visuals


def test_committed_visuals_are_consistent_with_the_product_dataset(at_repo_root) -> None:
    assert check_visuals(GAME, PRODUCT_BUILD) == []


def test_icons_resolve_from_the_build_s_own_rows(
    committed: dict[str, TalentDataDocument], merged_source: LocalCsvDb2Source
) -> None:
    """Every sidecar icon is the one the 70338 TraitDefinition/SpellMisc rows name -
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
    assert len(registered) == 358
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
    assert (totals.entries, totals.ranks) == (469, 1329)
    assert (totals.resolved, totals.partial, totals.unresolved) == (1281, 47, 1)
    # still the only unresolved rank: Druid Shifting Power's "$c" mana-cost reference is
    # outside the supported grammar and stays an explicit gap, never a guessed number
    druid = json.loads((TOOLTIPS / "druid.json").read_text(encoding="utf-8"))
    unresolved = [
        (entry["entryId"], rank["rank"])
        for entry in druid["entries"]
        for rank in entry["ranks"]
        if rank["status"] == "unresolved"
    ]
    assert unresolved == [(129611, 1)]
