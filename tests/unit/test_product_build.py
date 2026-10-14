"""M5.DATA.3: the Forever product dataset 1.60.1.70374, end to end and offline.

1.60.1.70374 is the product dataset (new builds are created on it). 1.60.1.70338 (M5.DATA.2,
tests/unit/test_build_70338.py) and 1.60.1.70245 (M5.DATA, tests/unit/test_build_70245.py)
stay committed and bundled for the builds saved on them; 1.60.1.70009 stays the verified
reference baseline. 70374 carries exactly 70338's talent rows - the diff is empty - and is
nevertheless its own dataset: every artifact here is generated from 70374's own client rows
(DB2, icon files, tooltip tables, ruRU strings), never copied, and carries 70374's identity
and hashes. These tests prove both halves. Facts and evidence: docs/forever-dataset.md.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from talent_data.allocation import BuildState, TalentAllocationEngine
from talent_data.games import get_game
from talent_data.localization.coverage import render_markdown as render_localization_markdown
from talent_data.localization.pipeline import check_canonical as check_localization
from talent_data.localization.pipeline import localize_build, read_localization_document
from talent_data.localization.pipeline import read_coverage_report as read_localization_report
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
from tests.helpers import FIXTURES, PREVIOUS_PRODUCT_BUILD, PRODUCT_BUILD
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

# class: (tree_id, nodes, point budget, prerequisite links, total ranks, entries) of 70374 -
# identical to 70338 for every class.
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


@pytest.fixture(scope="module")
def committed() -> dict[str, TalentDataDocument]:
    return {key: read_document(DATASET / f"{key}.json") for key in ALL_CLASSES}


@pytest.fixture(scope="module")
def previous() -> dict[str, TalentDataDocument]:
    return {key: read_document(PREVIOUS_DATASET / f"{key}.json") for key in ALL_CLASSES}


@pytest.fixture
def at_repo_root(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("TALENT_DATA_ROOT", str(ROOT))


# --------------------------------------------------------------------------- dataset


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_committed_document_is_reproduced_from_the_build_rows(
    class_key: str, committed: dict[str, TalentDataDocument]
) -> None:
    """No copy of 70338: normalizing 70374's own fixture rows yields the committed
    semantic payload, with 70374's identity."""
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


def test_committed_diff_against_the_previous_product_dataset_is_current_and_empty(
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
    assert (aggregate.total.added, aggregate.total.removed, aggregate.total.changed) == (0, 0, 0)


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_same_talent_rows_but_a_distinct_dataset_identity(
    class_key: str,
    committed: dict[str, TalentDataDocument],
    previous: dict[str, TalentDataDocument],
) -> None:
    """The anomaly review of the empty diff, field by field: class identity, trees, nodes,
    entries, spells, ranks, gates, prerequisites, sections and layout are all equal to
    70338 - yet the document is 70374's own, with its own build and content hash, so a
    saved build of either build can never be mistaken for the other."""
    new, old = committed[class_key], previous[class_key]
    assert new.class_ == old.class_
    assert new.trees == old.trees
    assert (new.client_build, old.client_build) == (str(PRODUCT_BUILD), str(PREVIOUS_PRODUCT_BUILD))
    assert new.content_hash != old.content_hash


# --------------------------------------------------------------------------- allocation


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_engine_supports_every_node_except_the_known_unresolved_venom(
    class_key: str, committed: dict[str, TalentDataDocument]
) -> None:
    engine = TalentAllocationEngine(committed[class_key])
    expected = {VENOM} if class_key == "rogue" else set()
    assert engine.unsupported_nodes().keys() == expected


def _spend_all_points(engine: TalentAllocationEngine) -> BuildState:
    """Spend the lowest-id allowed node until nothing more can be spent - a real
    engine-made build, never hand-written."""
    state = engine.empty_state()
    while True:
        allowed = [n.id for n in engine.tree.nodes if engine.can_spend(state, n.id).allowed]
        if not allowed:
            return state
        state = engine.spend(state, min(allowed)).state


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_a_previous_product_build_validates_alike_on_both_builds(
    class_key: str,
    committed: dict[str, TalentDataDocument],
    previous: dict[str, TalentDataDocument],
) -> None:
    """A full 70338 build gets the same verdict from both engines. That is a property of
    the data, not a license to migrate: a saved build still opens on its own client build
    (the mobile tests pin that)."""
    engine = TalentAllocationEngine(previous[class_key])
    state = _spend_all_points(engine)
    assert engine.spent_points(state) > 0
    on_previous = TalentAllocationEngine(previous[class_key]).validate(state)
    on_product = TalentAllocationEngine(committed[class_key]).validate(state)
    assert on_previous.valid and on_product.valid


# --------------------------------------------------------------------------- visuals


def test_committed_visuals_are_consistent_with_the_product_dataset(at_repo_root) -> None:
    assert check_visuals(GAME, PRODUCT_BUILD) == []


def test_icons_resolve_from_the_build_s_own_rows(
    committed: dict[str, TalentDataDocument], merged_source: LocalCsvDb2Source
) -> None:
    """Every sidecar icon is the one the 70374 TraitDefinition/SpellMisc rows name, and
    every sidecar is linked to the 70374 document's hash."""
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


def test_icon_images_were_regenerated_and_match_the_previous_build() -> None:
    """Recorded investigation: the 358 client icon files of 70374 (fetched from 70374, not
    copied) are byte-identical to 70338's, and so are the PNGs made from them."""
    names = sorted(p.name for p in (VISUALS / "icons").glob("*.png"))
    assert names == sorted(p.name for p in (PREVIOUS_VISUALS / "icons").glob("*.png"))
    for name in names:
        current = (VISUALS / "icons" / name).read_bytes()
        assert current == (PREVIOUS_VISUALS / "icons" / name).read_bytes(), name


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
    """Coverage is unchanged from 70338: same totals, same single unresolved rank."""
    report = read_coverage_report(TOOLTIP_REPORT)
    totals = report.totals
    assert (totals.entries, totals.ranks) == (469, 1329)
    assert (totals.resolved, totals.partial, totals.unresolved) == (1281, 47, 1)
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


def test_regenerating_the_ruRU_pilot_from_the_build_rows_reproduces_the_committed_files(
    at_repo_root,
) -> None:
    """The 70374 overlay, report and Markdown come from 70374's own enUS/ruRU rows and its
    own dataset and tooltip revisions - M5.REL.3b.1 can build on them directly."""
    build = localize_build(
        LocalCsvDb2Source(LOCALIZATION_FIXTURES / "enUS", per_version=False),
        LocalCsvDb2Source(LOCALIZATION_FIXTURES / LOCALE, per_version=False),
        LocalCsvDb2Source(TOOLTIP_FIXTURES, per_version=False),
        GAME,
        PRODUCT_BUILD,
        LOCALE,
        source_name="wago.tools",
    )
    overlay = (OVERLAYS / "warrior.json").read_text(encoding="utf-8")
    assert build.overlays["WARRIOR"].to_json() == overlay
    report = build.report(frozenset({"WARRIOR"}))
    assert report.to_json() == LOCALIZATION_REPORT.read_text(encoding="utf-8")
    markdown = LOCALIZATION_MARKDOWN.read_text(encoding="utf-8")
    assert render_localization_markdown(report) == markdown


def test_the_ruRU_overlay_is_linked_to_the_product_revisions(
    committed: dict[str, TalentDataDocument],
) -> None:
    overlay = read_localization_document(OVERLAYS / "warrior.json")
    tooltips = json.loads((TOOLTIPS / "warrior.json").read_text(encoding="utf-8"))
    assert overlay.client_build == str(PRODUCT_BUILD)
    assert overlay.talent_data_content_hash == committed["warrior"].content_hash
    assert overlay.tooltips_content_hash == tooltips["contentHash"]
    previous = read_localization_document(PREVIOUS_OVERLAYS / LOCALE / "warrior.json")
    assert overlay.talent_data_content_hash != previous.talent_data_content_hash
    assert overlay.content_hash != previous.content_hash


def test_ruRU_coverage_of_the_product_build() -> None:
    """Same coverage as 70338 (identical ruRU exports): every class reported, Warrior
    published as the pilot, the other classes left to M5.REL.3b.1."""
    report = read_localization_report(LOCALIZATION_REPORT)
    totals = report.totals
    assert (totals.entries, totals.ranks) == (469, 1329)
    names = totals.names
    assert (names.translated, names.untranslated, names.missing) == (459, 10, 0)
    assert totals.descriptions.rendered == 355
    assert totals.rendered_ranks == 1058
    assert [row.class_token for row in report.classes if row.published] == ["WARRIOR"]
    assert len(report.classes) == 9
