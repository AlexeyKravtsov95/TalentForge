"""Batch pipeline: all-class normalize + manifest, all-class diff aggregate, build
candidate semantics. Runs fully offline against the merged per-class fixtures."""

from __future__ import annotations

from datetime import datetime
from pathlib import Path

import pytest

from talent_data.db2.dataset import TraitDataSet
from talent_data.diff.models import DIAGNOSTIC_CHANGE_KINDS, USER_VISIBLE_CHANGE_KINDS
from talent_data.games import get_game
from talent_data.pipeline import (
    PipelineError,
    build_manifest,
    diff_batch,
    discover_classes,
    normalize_batch,
    parse_class_keys,
    write_batch,
)
from talent_data.sources.base import (
    BuildInfo,
    SourceError,
    classify_build,
    latest_publishable_build,
)
from talent_data.sources.local import LocalCsvDb2Source
from tests.conftest import ALL_CLASSES, normalize_fixture_class
from tests.helpers import DB2_FIXTURES, NEW_BUILD, OLD_BUILD

GAME = get_game("forever")


@pytest.fixture(scope="module")
def dataset_all(merged_source: LocalCsvDb2Source) -> TraitDataSet:
    return TraitDataSet.load(merged_source, NEW_BUILD)


def test_discover_classes_is_data_driven_and_deterministic(dataset_all: TraitDataSet) -> None:
    classes = discover_classes(dataset_all)
    assert [c.token for c in classes] == [
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
    assert discover_classes(dataset_all) == classes


def test_normalize_batch_all_classes(merged_source: LocalCsvDb2Source) -> None:
    batch = normalize_batch(merged_source, GAME, NEW_BUILD, class_keys=None)
    assert batch.ok
    assert len(batch.results) == 9
    assert [r.chr_class.token for r in batch.results] == [
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
    assert all(r.document is not None and r.report is not None for r in batch.results)


def test_normalize_batch_explicit_subset(merged_source: LocalCsvDb2Source) -> None:
    batch = normalize_batch(merged_source, GAME, NEW_BUILD, class_keys=["druid", "warrior"])
    assert [r.chr_class.token for r in batch.results] == ["WARRIOR", "DRUID"]  # id order


def test_normalize_batch_failure_is_recorded_not_hidden(
    merged_source: LocalCsvDb2Source, monkeypatch: pytest.MonkeyPatch
) -> None:
    import talent_data.pipeline as pipeline

    original = pipeline.normalize_from_dataset

    def failing(dataset, *, game, class_key, source_name):
        if class_key == "MAGE":
            raise PipelineError("mage data exploded")
        return original(dataset, game=game, class_key=class_key, source_name=source_name)

    monkeypatch.setattr(pipeline, "normalize_from_dataset", failing)
    batch = normalize_batch(merged_source, GAME, NEW_BUILD, class_keys=None)
    assert not batch.ok
    failed = [r for r in batch.results if not r.ok]
    assert [r.chr_class.token for r in failed] == ["MAGE"]
    assert "mage data exploded" in failed[0].failure
    assert "Mage: mage data exploded" in batch.describe_failures()
    # the other eight classes still normalized
    assert sum(1 for r in batch.results if r.ok) == 8


def test_write_batch_refuses_partial_dataset(
    merged_source: LocalCsvDb2Source,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import talent_data.pipeline as pipeline

    monkeypatch.setenv("TALENT_DATA_ROOT", str(tmp_path))
    original = pipeline.normalize_from_dataset

    def failing(dataset, *, game, class_key, source_name):
        if class_key == "PRIEST":
            raise pipeline.PipelineError("nope")
        return original(dataset, game=game, class_key=class_key, source_name=source_name)

    monkeypatch.setattr(pipeline, "normalize_from_dataset", failing)
    batch = normalize_batch(merged_source, GAME, NEW_BUILD, class_keys=None)
    with pytest.raises(PipelineError, match="Priest"):
        write_batch(batch, source_name=merged_source.name)
    assert not (tmp_path / "data").exists() or not list((tmp_path / "data").rglob("*.json")), (
        "nothing may be published from a failed batch"
    )


def test_manifest_matches_documents(merged_source: LocalCsvDb2Source) -> None:
    batch = normalize_batch(merged_source, GAME, NEW_BUILD, class_keys=None)
    manifest = build_manifest(batch, source_name=merged_source.name)
    assert manifest.schema_version == 1
    assert manifest.client_build == str(NEW_BUILD)
    assert len(manifest.classes) == 9
    for entry, result in zip(manifest.classes, batch.results, strict=True):
        doc, report = result.document, result.report
        assert entry.token == result.chr_class.token
        assert entry.file == f"{entry.token.lower()}.json"
        assert entry.content_hash == doc.content_hash
        assert entry.validation.errors == 0
        assert entry.validation.warnings == len(report.warnings)
        assert entry.validation.warnings_by_code == {
            code: n
            for code, n in report.count_by_code().items()
            if code in {i.code for i in report.warnings}
        }
        assert entry.tree_ids == [t.id for t in doc.trees]


def test_diff_batch_aggregates_all_classes() -> None:
    old_docs = [normalize_fixture_class(c, OLD_BUILD) for c in ALL_CLASSES]
    new_docs = [normalize_fixture_class(c, NEW_BUILD) for c in ALL_CLASSES]
    reports, aggregate = diff_batch(old_docs, new_docs)
    assert len(reports) == 9
    assert aggregate.schema_version == 1
    assert (aggregate.from_build, aggregate.to_build) == (str(OLD_BUILD), str(NEW_BUILD))
    # classes ordered by class id (warrior first, druid last)
    assert [c.class_.token for c in aggregate.classes] == [
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
    assert aggregate.total.added == sum(r.summary.added for r in reports)
    assert aggregate.total.changed == sum(r.summary.changed for r in reports)
    for entry, report in zip(aggregate.classes, reports, strict=True):
        assert entry.summary == report.summary
        assert entry.file.endswith(".json")


def test_diff_batch_rejects_mismatched_class_sets() -> None:
    old_docs = [normalize_fixture_class(c, OLD_BUILD) for c in ALL_CLASSES]
    new_docs = old_docs[:-1]  # druid missing on one side
    with pytest.raises(PipelineError, match="class sets differ"):
        diff_batch(old_docs, new_docs)


def test_parse_class_keys() -> None:
    assert parse_class_keys("all") is None
    assert parse_class_keys(" Warrior , druid ") == ["warrior", "druid"]
    with pytest.raises(PipelineError):
        parse_class_keys("all,warrior")
    with pytest.raises(PipelineError):
        parse_class_keys("  ")


# --------------------------------------------------------------------------- builds


def _build(version: str, created: str, bgdl: bool = False) -> BuildInfo:
    from talent_data.versions import ClientVersion

    return BuildInfo(
        version=ClientVersion.parse(version),
        product="wow_classic_beta",
        created_at=datetime.strptime(created, "%Y-%m-%d %H:%M:%S"),
        extra={"is_bgdl": str(bgdl).lower()},
    )


def _build_without_bgdl(version: str, created: str) -> BuildInfo:
    """A source that does not provide is_bgdl at all (possible for future sources)."""
    from talent_data.versions import ClientVersion

    return BuildInfo(
        version=ClientVersion.parse(version),
        product="wow_classic_beta",
        created_at=datetime.strptime(created, "%Y-%m-%d %H:%M:%S"),
        extra={},
    )


def test_background_download_builds_are_classified() -> None:
    candidate = classify_build(_build("1.60.1.70009", "2026-09-24 22:02:03"))
    assert not candidate.is_background_download
    bgdl = classify_build(_build("12.0.7.67808", "2026-06-15 22:45:02", bgdl=True))
    assert bgdl.is_background_download


def test_missing_bgdl_flag_is_unknown_not_false() -> None:
    """Absence of is_bgdl must not be read as 'definitely not a background download'."""
    candidate = classify_build(_build_without_bgdl("1.60.1.70009", "2026-09-24 22:02:03"))
    assert candidate.background_download is None
    assert not candidate.is_background_download  # not *definitely* bgdl...
    assert not candidate.publishable  # ...but also not publishable: unknown != safe


def test_unknown_bgdl_build_is_not_publishable() -> None:
    """A source that never says is_bgdl yields no publishable candidate, and the newest
    unknown build must not win by created_at."""
    candidates = [
        classify_build(_build("1.60.1.69977", "2026-09-23 00:35:05")),
        classify_build(_build_without_bgdl("1.60.2.70100", "2026-09-26 10:00:00")),
    ]
    assert str(latest_publishable_build(candidates).info.version) == "1.60.1.69977"
    only_unknown = [classify_build(_build_without_bgdl("1.60.2.70100", "2026-09-26 10:00:00"))]
    with pytest.raises(SourceError, match="unknown background-download status"):
        latest_publishable_build(only_unknown)


def test_latest_publishable_build_excludes_background_downloads() -> None:
    # a background-download build is NEWER than every publishable build: "latest detected"
    # and "latest publishable" diverge, and the pipeline must not silently treat the bgdl
    # build as production-ready.
    candidates = [
        classify_build(_build("1.60.1.69977", "2026-09-23 00:35:05")),
        classify_build(_build("1.60.1.70009", "2026-09-24 22:02:03")),
        classify_build(_build("1.60.2.70100", "2026-09-26 10:00:00", bgdl=True)),
    ]
    assert str(latest_publishable_build(candidates).info.version) == "1.60.1.70009"


def test_all_forever_builds_are_currently_publishable() -> None:
    import json

    from talent_data.sources.wago.models import WagoBuildIndex
    from talent_data.sources.wago.source import filter_game_builds

    wago_fixtures = DB2_FIXTURES.parent.parent / "wago"
    payload = json.loads((wago_fixtures / "builds.json").read_text())
    builds = filter_game_builds(WagoBuildIndex.model_validate(payload), GAME)
    assert len(builds) == 5
    assert all(not classify_build(b).is_background_download for b in builds)


# --------------------------------------------------------------------------- diff noise


def test_change_kind_classification_covers_every_kind() -> None:
    """Every ChangeKind is classified as user-visible or diagnostic, and the two sets do
    not overlap - the boundary a future patch-history UI will rely on."""
    from typing import get_args

    from talent_data.diff.models import ChangeKind

    all_kinds = set(get_args(ChangeKind))
    assert all_kinds == USER_VISIBLE_CHANGE_KINDS | DIAGNOSTIC_CHANGE_KINDS
    assert not USER_VISIBLE_CHANGE_KINDS & DIAGNOSTIC_CHANGE_KINDS


def test_real_warrior_index_shift_noise_is_classified_diagnostic() -> None:
    """Improved Slam (110858) gained an inserted SpellEffect at index 2 in 1.60.1.70009;
    the resulting base-points cascade is DB2 noise, while the new rank values are real."""
    old = normalize_fixture_class("warrior", OLD_BUILD)
    new = normalize_fixture_class("warrior", NEW_BUILD)
    from talent_data.diff.engine import diff_documents

    report = diff_documents(old, new)
    slam = next(nc for nc in report.changed if nc.node_id == 110858)
    kinds = {c.kind for c in slam.changes}
    assert "effectBasePointsChanged" in kinds
    assert "effectBasePointsChanged" in DIAGNOSTIC_CHANGE_KINDS
    assert "effectRankValueChanged" in kinds
    assert "effectRankValueChanged" in USER_VISIBLE_CHANGE_KINDS
