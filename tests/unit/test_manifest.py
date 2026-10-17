"""Manifest integrity: validate_manifest_against_documents is the single place that
decides whether a manifest + class documents form a complete, consistent dataset.

Every test here is a review finding made executable: a partial, stale or corrupt
manifest must NEVER be accepted as a valid dataset (neither by ``_existing_batch`` nor
by the ``manifest`` CLI command).
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from talent_data.cli.main import main
from talent_data.games import get_game
from talent_data.models.manifest import (
    ManifestClassEntry,
    ManifestValidation,
)
from talent_data.pipeline import (
    build_manifest,
    normalize_batch,
    read_manifest,
    validate_manifest_against_documents,
)
from talent_data.sources.local import LocalCsvDb2Source
from tests.helpers import DB2_FIXTURES, NEW_BUILD
from tests.unit.test_batch import GAME
from tests.unit.test_cli import _merge_class_fixtures

GAME_CFG = get_game("forever")
EXPECTED_TOKENS = GAME_CFG.dataset_class_tokens
ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture(scope="module")
def real_batch(merged_source: LocalCsvDb2Source):
    batch = normalize_batch(merged_source, GAME, NEW_BUILD, class_keys=None)
    assert batch.ok
    return batch


@pytest.fixture(scope="module")
def valid_manifest(real_batch, merged_source: LocalCsvDb2Source):
    return build_manifest(real_batch, source_name=merged_source.name)


def docs_for(batch) -> dict[str, object]:
    return {r.chr_class.token: r.document for r in batch.results}


def baseline_checks(manifest, docs):
    return validate_manifest_against_documents(
        manifest,
        docs,
        expected_game="forever",
        expected_build=str(NEW_BUILD),
        expected_class_tokens=EXPECTED_TOKENS,
    )


# --------------------------------------------------------------------------- valid


def test_valid_manifest_has_no_problems(real_batch, valid_manifest) -> None:
    assert baseline_checks(valid_manifest, docs_for(real_batch)) == []


def test_every_committed_forever_manifest_passes() -> None:
    """The committed real manifests (1.60.1.69977, the reference baseline 1.60.1.70009, the
    retained product datasets 1.60.1.70245, 1.60.1.70338 and 1.60.1.70374 and the product
    dataset 1.60.1.70450) with their class documents satisfy every invariant."""
    builds = (
        "1.60.1.69977",
        "1.60.1.70009",
        "1.60.1.70245",
        "1.60.1.70338",
        "1.60.1.70374",
        "1.60.1.70450",
    )
    for build in builds:
        mpath = ROOT / "data" / "normalized" / "forever" / build / "manifest.json"
        manifest = read_manifest(mpath)
        docs = {}
        for entry in manifest.classes:
            raw = json.loads((mpath.parent / entry.file).read_text())
            from talent_data.models.talents import TalentDataDocument

            docs[entry.token] = TalentDataDocument.model_validate(raw)
        problems = validate_manifest_against_documents(
            manifest,
            docs,
            expected_game="forever",
            expected_build=build,
            expected_class_tokens=EXPECTED_TOKENS,
        )
        assert problems == [], f"{build}: {problems}"


# --------------------------------------------------------------------------- required failure modes


def test_partial_manifest_missing_class_fails(real_batch, valid_manifest) -> None:
    without_warrior = valid_manifest.model_copy(update={"classes": valid_manifest.classes[1:]})
    problems = baseline_checks(without_warrior, docs_for(real_batch))
    assert any("missing ['WARRIOR']" in p for p in problems), problems
    assert any("not in the manifest: ['WARRIOR']" in p for p in problems), problems


def test_wrong_client_build_fails(real_batch, valid_manifest) -> None:
    tampered = valid_manifest.model_copy(update={"client_build": "1.60.1.69977"})
    problems = baseline_checks(tampered, docs_for(real_batch))
    assert any("clientBuild" in p and "!=" in p for p in problems), problems
    # documents also no longer match the manifest's claim
    assert any("document clientBuild" in p for p in problems), problems


def test_wrong_game_fails(real_batch, valid_manifest) -> None:
    tampered = valid_manifest.model_copy(update={"game": "classic-era"})
    problems = baseline_checks(tampered, docs_for(real_batch))
    assert any("game 'classic-era' != expected 'forever'" in p for p in problems), problems
    assert any("document game" in p for p in problems), problems


def test_wrong_class_token_fails(real_batch, valid_manifest) -> None:
    """Manifest entry claims WARRIOR but the document under that key is another class."""
    docs = docs_for(real_batch)
    docs["WARRIOR"] = docs["MAGE"]
    problems = baseline_checks(valid_manifest, docs)
    assert any("document is for class MAGE, manifest says WARRIOR" in p for p in problems), problems


def test_wrong_tree_ids_fail(real_batch, valid_manifest) -> None:
    entry = valid_manifest.classes[0]
    assert entry.token == "WARRIOR"
    tampered_entry = entry.model_copy(update={"tree_ids": [9999]})
    tampered = valid_manifest.model_copy(
        update={"classes": [tampered_entry, *valid_manifest.classes[1:]]}
    )
    problems = baseline_checks(tampered, docs_for(real_batch))
    assert any("treeIds [9999]" in p for p in problems), problems


def test_wrong_warning_counts_fail(real_batch, valid_manifest) -> None:
    hunter = next(e for e in valid_manifest.classes if e.token == "HUNTER")
    assert hunter.validation.warnings == 4

    wrong_total = hunter.model_copy(
        update={"validation": hunter.validation.model_copy(update={"warnings": 3})}
    )
    tampered = valid_manifest.model_copy(
        update={
            "classes": [wrong_total if e.token == "HUNTER" else e for e in valid_manifest.classes]
        }
    )
    problems = baseline_checks(tampered, docs_for(real_batch))
    assert any("manifest warnings 3 != actual 4" in p for p in problems), problems

    wrong_codes = hunter.model_copy(
        update={
            "validation": hunter.validation.model_copy(
                update={"warnings_by_code": {"OFF_GRID_NODE": 2}}
            )
        }
    )
    tampered = valid_manifest.model_copy(
        update={
            "classes": [wrong_codes if e.token == "HUNTER" else e for e in valid_manifest.classes]
        }
    )
    problems = baseline_checks(tampered, docs_for(real_batch))
    assert any("warningsByCode" in p for p in problems), problems


def test_wrong_content_hash_fails(real_batch, valid_manifest) -> None:
    warrior = valid_manifest.classes[0]
    tampered_entry = warrior.model_copy(update={"content_hash": "sha256:deadbeef"})
    tampered = valid_manifest.model_copy(
        update={"classes": [tampered_entry, *valid_manifest.classes[1:]]}
    )
    problems = baseline_checks(tampered, docs_for(real_batch))
    assert any("contentHash" in p and "stale or tampered" in p for p in problems), problems


def test_duplicate_class_token_fails(real_batch, valid_manifest) -> None:
    duplicate = ManifestClassEntry(
        token="WARRIOR",
        file="warrior.json",
        tree_ids=[1117],
        content_hash=valid_manifest.classes[0].content_hash,
        validation=ManifestValidation(errors=0, warnings=0, warnings_by_code={}),
    )
    tampered = valid_manifest.model_copy(update={"classes": [*valid_manifest.classes, duplicate]})
    problems = baseline_checks(tampered, docs_for(real_batch))
    assert any("token WARRIOR appears 2 times" in p for p in problems), problems


def test_duplicate_file_fails(real_batch, valid_manifest) -> None:
    mage = next(e for e in valid_manifest.classes if e.token == "MAGE")
    tampered_entry = mage.model_copy(update={"file": "warrior.json"})
    tampered = valid_manifest.model_copy(
        update={
            "classes": [tampered_entry if e.token == "MAGE" else e for e in valid_manifest.classes]
        }
    )
    problems = baseline_checks(tampered, docs_for(real_batch))
    assert any("file warrior.json referenced by 2" in p for p in problems), problems


def test_document_with_validation_errors_fails(real_batch, valid_manifest) -> None:
    """If a document stopped validating (0 errors promised), the manifest is invalid."""
    docs = docs_for(real_batch)
    broken = docs["WARRIOR"].model_copy(update={"content_hash": "sha256:tampered"})
    docs["WARRIOR"] = broken
    problems = baseline_checks(valid_manifest, docs)
    assert any("contentHash" in p for p in problems), problems
    assert any("1 validation error" in p for p in problems), problems


def test_inventory_check_ignores_json_order(real_batch, valid_manifest) -> None:
    """Class identity, not list position, defines completeness: a shuffled manifest is
    still complete; an order-matching list with one renamed token is not."""
    shuffled = valid_manifest.model_copy(update={"classes": list(reversed(valid_manifest.classes))})
    assert baseline_checks(shuffled, docs_for(real_batch)) == []


# --------------------------------------------------------------------------- CLI + _existing_batch


@pytest.fixture
def dataset_project(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """A project root with a complete, valid dataset built offline from fixtures.

    The DB2 source is pointed at a local cache dir (tables of both builds merged from
    the per-class fixtures), so re-normalization works offline.
    """
    cache = tmp_path / "cache"
    for build in ("1.60.1.69977", "1.60.1.70009"):
        _merge_class_fixtures(DB2_FIXTURES / build, cache / build)
    monkeypatch.setenv("TALENT_DATA_ROOT", str(tmp_path))
    monkeypatch.setattr(
        "talent_data.cli.main._db2_source",
        lambda game, http, offline: LocalCsvDb2Source(cache),
    )
    code = main(["normalize", "--build", "1.60.1.70009", "--class", "all", "--offline"])
    assert code == 0
    return tmp_path


def test_manifest_command_accepts_valid_dataset(dataset_project: Path, capsys) -> None:
    mpath = dataset_project / "data" / "normalized" / "forever" / "1.60.1.70009" / "manifest.json"
    code = main(
        [
            "manifest",
            str(mpath),
            "--game",
            "forever",
            "--build",
            "1.60.1.70009",
            "--expect-complete",
        ]
    )
    out = capsys.readouterr().out
    assert code == 0, out
    assert "9 classes" in out
    assert "ok, hash ok" in out


def test_manifest_command_rejects_tampered_manifest(dataset_project: Path, capsys) -> None:
    mpath = dataset_project / "data" / "normalized" / "forever" / "1.60.1.70009" / "manifest.json"
    raw = json.loads(mpath.read_text())
    raw["clientBuild"] = "1.60.1.69977"  # stale/wrong build
    mpath.write_text(json.dumps(raw))
    code = main(["manifest", str(mpath), "--build", "1.60.1.70009"])
    out = capsys.readouterr().out
    assert code == 1
    assert "ERROR" in out and "clientBuild" in out


def test_manifest_command_rejects_partial_manifest(dataset_project: Path, capsys) -> None:
    mpath = dataset_project / "data" / "normalized" / "forever" / "1.60.1.70009" / "manifest.json"
    raw = json.loads(mpath.read_text())
    raw["classes"] = raw["classes"][1:]  # drop WARRIOR
    mpath.write_text(json.dumps(raw))
    code = main(["manifest", str(mpath), "--expect-complete"])
    out = capsys.readouterr().out
    assert code == 1
    assert "missing ['WARRIOR']" in out


def test_existing_batch_distrusts_stale_manifest_and_renormalizes(
    dataset_project: Path, capsys, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A manifest whose content hash no longer matches the documents must not be served
    as an existing dataset: the batch diff re-normalizes (raw data available here)."""
    base = dataset_project / "data" / "normalized" / "forever" / "1.60.1.70009"
    raw = json.loads((base / "manifest.json").read_text())
    raw["classes"][0]["contentHash"] = "sha256:stale"
    (base / "manifest.json").write_text(json.dumps(raw))
    code = main(
        ["diff", "--class", "all", "--from", "1.60.1.70009", "--to", "1.60.1.70009", "--offline"]
    )
    captured = capsys.readouterr()
    assert code == 0, captured.out + captured.err
    # the stale dataset was NOT trusted: it re-normalized before diffing
    assert "normalizing all classes @ 1.60.1.70009" in captured.err
    # and the repaired manifest on disk is valid again
    code = main(["manifest", str(base / "manifest.json"), "--expect-complete"])
    assert code == 0


def test_existing_batch_offline_without_raw_data_fails_clearly(
    dataset_project: Path, capsys, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """When re-normalization is impossible (no cache), a corrupt manifest must lead to
    an explicit failure, never to silently using the stale dataset."""
    base = dataset_project / "data" / "normalized" / "forever" / "1.60.1.70009"
    raw = json.loads((base / "manifest.json").read_text())
    raw["classes"][0]["contentHash"] = "sha256:stale"
    (base / "manifest.json").write_text(json.dumps(raw))
    # point the source at an empty cache: re-normalization cannot work
    empty = tmp_path / "empty-cache"
    empty.mkdir()
    monkeypatch.setattr(
        "talent_data.cli.main._db2_source",
        lambda game, http, offline: LocalCsvDb2Source(empty, per_version=False),
    )
    code = main(
        ["diff", "--class", "all", "--from", "1.60.1.70009", "--to", "1.60.1.70009", "--offline"]
    )
    captured = capsys.readouterr()
    assert code == 1
    assert "error:" in captured.err
