"""Publishing visuals end to end, offline: real DB2 fixture rows, real committed dataset,
and a file source that serves the two real client icon fixtures."""

from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path

import pytest

from talent_data.cli.main import main
from talent_data.sources.casc import CascFileError, CascFileErrorKind
from talent_data.versions import ClientVersion
from talent_data.visuals.pipeline import (
    check_canonical,
    publish,
    read_icon_registry,
    read_visuals_document,
    resolve_build,
)
from tests.conftest import ALL_CLASSES
from tests.helpers import DB2_FIXTURES, NEW_BUILD
from tests.unit.test_cli import _merge_class_fixtures
from tests.unit.visuals.conftest import (
    BLP_WITH_ALPHA,
    BLP_WITHOUT_ALPHA,
    CANONICAL_DATASET,
    CANONICAL_VISUALS,
    CASC_FIXTURES,
    GAME,
)


class FixtureCascSource:
    """Serves one of the two real client icon files for any FileDataID of the build.

    The 358 client files are not committed (only their conversions are), so offline tests
    exercise the publish plumbing with real BLP2 payloads rather than the real mapping; the
    real mapping is pinned by the committed registry's source hashes.
    """

    name = "fixture-casc"

    def __init__(self, missing: frozenset[int] = frozenset()) -> None:
        self.missing = missing
        self.requested: list[tuple[ClientVersion, int]] = []

    def fetch_file(self, version: ClientVersion, file_data_id: int) -> Path:
        self.requested.append((version, file_data_id))
        if file_data_id in self.missing:
            raise CascFileError(
                CascFileErrorKind.NOT_CACHED, version, file_data_id, "not in the fixture cache"
            )
        pick = BLP_WITH_ALPHA if file_data_id % 2 else BLP_WITHOUT_ALPHA
        return CASC_FIXTURES / f"{pick}.blp"


@pytest.fixture
def project(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """A project root with the committed dataset and a DB2 cache built from the fixtures."""
    shutil.copytree(
        CANONICAL_DATASET, tmp_path / "data" / "normalized" / "forever" / str(NEW_BUILD)
    )
    cache = tmp_path / ".cache" / "wago" / "forever" / str(NEW_BUILD)
    _merge_class_fixtures(DB2_FIXTURES / str(NEW_BUILD), cache)
    for csv_path in cache.glob("*.csv"):
        data = csv_path.read_bytes()
        meta = {"url": "fixture", "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        csv_path.with_name(csv_path.name + ".meta.json").write_text(json.dumps(meta))
    monkeypatch.setenv("TALENT_DATA_ROOT", str(tmp_path))
    return tmp_path


def tree_digest(directory: Path) -> dict[str, str]:
    return {
        p.relative_to(directory).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted(directory.rglob("*"))
        if p.is_file()
    }


def published_dir(project: Path) -> Path:
    return project / "data" / "visuals" / "forever" / str(NEW_BUILD)


def test_publish_writes_sidecars_registry_and_one_image_per_file_data_id(
    project: Path, merged_source
) -> None:
    resolution = resolve_build(merged_source, GAME, NEW_BUILD)
    casc = FixtureCascSource()
    result = publish(resolution, casc, source_name="wago.tools")

    directory = published_dir(project)
    assert result.directory == directory
    assert sorted(p.name for p in directory.iterdir()) == sorted(
        ["icons", "icons.json", *(f"{key}.json" for key in ALL_CLASSES)]
    )
    # Deduplicated by FileDataID: 469 entries, 358 distinct icons, 358 files, 358 fetches.
    assert len(casc.requested) == 358
    assert {version for version, _ in casc.requested} == {NEW_BUILD}
    assert len(list((directory / "icons").iterdir())) == 358
    assert len(read_icon_registry(directory / "icons.json").icons) == 358
    assert sum(len(d.entries) for d in result.documents) == 469
    # The sidecars do not depend on the image bytes: identical to the committed ones.
    for key in ALL_CLASSES:
        assert (directory / f"{key}.json").read_bytes() == (
            CANONICAL_VISUALS / f"{key}.json"
        ).read_bytes()
        assert read_visuals_document(directory / f"{key}.json").class_token == key.upper()
    assert check_canonical(GAME, NEW_BUILD) == []


def test_publish_is_byte_deterministic(project: Path, merged_source) -> None:
    resolution = resolve_build(merged_source, GAME, NEW_BUILD)
    publish(resolution, FixtureCascSource(), source_name="wago.tools")
    first = tree_digest(published_dir(project))
    shutil.rmtree(published_dir(project))
    publish(
        resolve_build(merged_source, GAME, NEW_BUILD), FixtureCascSource(), source_name="wago.tools"
    )
    assert tree_digest(published_dir(project)) == first


def test_publish_removes_files_of_a_previous_run(project: Path, merged_source) -> None:
    directory = published_dir(project)
    (directory / "icons").mkdir(parents=True)
    (directory / "icons" / "424242.png").write_bytes(b"stale")
    (directory / "monk.json").write_text("{}")
    publish(resolve_build(merged_source, GAME, NEW_BUILD), FixtureCascSource(), source_name="x")
    assert not (directory / "icons" / "424242.png").exists()
    assert not (directory / "monk.json").exists()
    assert check_canonical(GAME, NEW_BUILD) == []


def test_publish_is_all_or_nothing_when_one_icon_file_is_unavailable(
    project: Path, merged_source
) -> None:
    resolution = resolve_build(merged_source, GAME, NEW_BUILD)
    with pytest.raises(CascFileError) as caught:
        publish(resolution, FixtureCascSource(frozenset({136218})), source_name="wago.tools")
    assert caught.value.kind is CascFileErrorKind.NOT_CACHED
    assert caught.value.file_data_id == 136218
    assert not published_dir(project).exists()


def test_check_reports_a_stale_or_stray_canonical_tree(project: Path, merged_source) -> None:
    publish(resolve_build(merged_source, GAME, NEW_BUILD), FixtureCascSource(), source_name="x")
    directory = published_dir(project)
    (directory / "warrior.json").unlink()
    (directory / "notes.txt").write_text("hand-made")
    problems = check_canonical(GAME, NEW_BUILD)
    assert any("cannot read talent visuals" in p and "warrior.json" in p for p in problems)
    assert f"notes.txt is not part of the visuals of {NEW_BUILD}" in problems


# --------------------------------------------------------------------------- CLI


def test_cli_report_offline_prints_exact_coverage_and_writes_the_artifact(
    project: Path, capsys
) -> None:
    code = main(["visuals", "report", "--build", str(NEW_BUILD), "--offline"])
    out = capsys.readouterr().out
    assert code == 0, out
    assert "total entries 469, resolved 469, unresolved 0" in out
    assert "distinct FileDataIDs 358, shared by more than one entry 86" in out
    artifact = project / "artifacts" / "visuals" / f"forever-{NEW_BUILD}-icon-coverage.json"
    report = json.loads(artifact.read_text())
    assert (report["totalEntries"], report["unresolvedEntries"]) == (469, 0)


def test_cli_report_fails_and_names_every_unresolved_entry(project: Path, capsys) -> None:
    """Coverage below 100% is an error with an exact, per-entry explanation."""
    table = project / ".cache" / "wago" / "forever" / str(NEW_BUILD) / "SpellMisc.csv"
    lines = table.read_text(encoding="utf-8").splitlines(keepends=True)
    kept = [line for line in lines if not line.rstrip("\n").endswith(",12286")]
    assert len(kept) == len(lines) - 1  # exactly Improved Rend's row removed
    data = "".join(kept).encode("utf-8")
    table.write_bytes(data)
    meta = {"url": "fixture", "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    table.with_name(table.name + ".meta.json").write_text(json.dumps(meta))

    code = main(["visuals", "report", "--build", str(NEW_BUILD), "--offline"])
    out = capsys.readouterr().out
    assert code == 1
    assert "total entries 469, resolved 468, unresolved 1" in out
    assert (
        "UNRESOLVED WARRIOR node 105956 entry 130686 spell 12286 display 12286: "
        "spell-misc-missing (no SpellMisc row)"
    ) in out

    code = main(["visuals", "normalize", "--build", str(NEW_BUILD), "--offline"])
    captured = capsys.readouterr()
    assert code == 1
    assert "icon coverage of 1.60.1.70009 is 468/469" in captured.err
    assert not published_dir(project).exists()


def test_cli_normalize_offline_without_fetched_icons_is_an_actionable_error(
    project: Path, capsys
) -> None:
    code = main(["visuals", "normalize", "--build", str(NEW_BUILD), "--offline"])
    captured = capsys.readouterr()
    assert code == 1
    assert "not-cached" in captured.err
    assert "visuals fetch --build 1.60.1.70009" in captured.err
    assert not published_dir(project).exists()


def test_cli_check_passes_on_the_committed_repository(capsys, monkeypatch) -> None:
    monkeypatch.delenv("TALENT_DATA_ROOT", raising=False)
    monkeypatch.chdir(Path(__file__).resolve().parents[3])
    assert main(["visuals", "check", "--build", str(NEW_BUILD)]) == 0
    assert "consistent with the dataset, registry and image files" in capsys.readouterr().out


def test_cli_refuses_latest_and_foreign_builds(project: Path, capsys) -> None:
    assert main(["visuals", "check", "--build", "latest"]) == 1
    assert "pinned to an exact build" in capsys.readouterr().err
    assert main(["visuals", "check", "--build", "5.5.0.60000"]) == 1
    assert "is not a Forever build" in capsys.readouterr().err
