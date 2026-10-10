"""The mobile bundle mirrors the canonical data of every bundled build, offline (M5.DATA).

Covers ``scripts/mobile_bundles.py`` and the three ``sync_mobile_*.py`` entry points: the
committed bundle is in sync for every bundled build, ``--check`` detects missing, changed
and extra files and unlisted builds without opening a connection, and a sync repairs the
bundle of the listed builds but never removes another build's bundle.
"""

from __future__ import annotations

import dataclasses
import importlib.util
import shutil
import socket
import sys
from pathlib import Path
from types import ModuleType

import pytest

ROOT = Path(__file__).resolve().parents[2]
REFERENCE, PREVIOUS_PRODUCT, PRODUCT = "1.60.1.70009", "1.60.1.70245", "1.60.1.70338"


def load_script(name: str) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    # registered first: the sync scripts import this very module object by name
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


bundles = load_script("mobile_bundles")
SCRIPTS = {
    "dataset": load_script("sync_mobile_datasets"),
    "visuals": load_script("sync_mobile_visuals"),
    "tooltips": load_script("sync_mobile_tooltips"),
}
KINDS = {"dataset": bundles.DATASET, "visuals": bundles.VISUALS, "tooltips": bundles.TOOLTIPS}
# committed files per kind and build: 9 class files (+ manifest | + registry and icons)
FILE_COUNTS = {
    "dataset": {REFERENCE: 10, PREVIOUS_PRODUCT: 10, PRODUCT: 10},
    "visuals": {REFERENCE: 368, PREVIOUS_PRODUCT: 367, PRODUCT: 368},
    "tooltips": {REFERENCE: 9, PREVIOUS_PRODUCT: 9, PRODUCT: 9},
}


@pytest.fixture
def no_network(monkeypatch: pytest.MonkeyPatch) -> None:
    def refuse(*args: object, **kwargs: object) -> None:
        raise AssertionError("the bundle drift check must not open a network connection")

    monkeypatch.setattr(socket.socket, "connect", refuse)
    monkeypatch.setattr(socket, "create_connection", refuse)


def test_the_reference_the_previous_and_the_current_product_build_are_bundled() -> None:
    assert bundles.BUNDLED_BUILDS == (REFERENCE, PREVIOUS_PRODUCT, PRODUCT)


def test_the_app_s_product_bundle_is_the_newest_bundled_build() -> None:
    """PRODUCT_BUNDLE in BundledDatasets.kt is what the freshness check compares; it must
    be bundled, and no bundled build may be newer than it."""
    assert bundles.product_build() == PRODUCT == bundles.BUNDLED_BUILDS[-1]


@pytest.mark.parametrize("kind", KINDS)
def test_committed_bundle_matches_canonical_without_network(kind: str, no_network, capsys):
    resources = ROOT / "mobile/composeApp/src/commonMain/composeResources/files"
    assert KINDS[kind].bundled_root == resources / kind / "forever"
    assert SCRIPTS[kind].main(["--check"]) == 0
    total = sum(FILE_COUNTS[kind].values())
    assert f"bundle in sync: {total} files of 3 builds" in capsys.readouterr().out
    for build, count in FILE_COUNTS[kind].items():
        assert len(bundles.relative_files(KINDS[kind].bundled_root / build)) == count


@pytest.fixture
def mirror(tmp_path: Path):
    """A visuals kind over a small real copy of every bundled build, already synced."""
    kind = dataclasses.replace(
        bundles.VISUALS,
        canonical_root=tmp_path / "canonical",
        bundled_root=tmp_path / "bundled",
        required_files=("icons.json", "warrior.json"),  # the subset this fixture copies
    )
    for build in bundles.BUNDLED_BUILDS:
        source = bundles.VISUALS.canonical_root / build
        target = kind.canonical_root / build
        (target / "icons").mkdir(parents=True)
        for name in ("warrior.json", "icons.json", "icons/132269.png", "icons/132090.png"):
            shutil.copy(source / name, target / name)
    assert bundles.sync(kind) == 0
    assert bundles.drift(kind) == []
    return kind


def test_check_detects_changed_missing_and_extra_files(mirror, capsys) -> None:
    bundled = mirror.bundled_root
    (bundled / PRODUCT / "warrior.json").write_text("{}")
    (bundled / REFERENCE / "icons" / "132269.png").unlink()
    (bundled / PRODUCT / "icons" / "424242.png").write_bytes(b"orphan")
    assert bundles.drift(mirror) == [
        f"{REFERENCE}/icons/132269.png: missing from bundle",
        f"{PRODUCT}/icons/424242.png: in bundle but not canonical",
        f"{PRODUCT}/warrior.json: bundle differs from canonical",
    ]
    assert bundles.check(mirror) == 1
    assert "out of sync" in capsys.readouterr().err


def test_a_missing_bundled_build_is_drift(mirror) -> None:
    shutil.rmtree(mirror.bundled_root / REFERENCE)
    problems = bundles.drift(mirror)
    assert f"{REFERENCE}/warrior.json: missing from bundle" in problems
    assert f"{REFERENCE}/icons.json: missing from bundle" in problems


def test_sync_repairs_drift_and_removes_stale_files_inside_a_build(mirror) -> None:
    bundled, canonical = mirror.bundled_root, mirror.canonical_root
    (bundled / PRODUCT / "warrior.json").write_text("{}")
    (bundled / PRODUCT / "icons" / "424242.png").write_bytes(b"orphan")
    (canonical / PRODUCT / "icons" / "132090.png").unlink()  # an icon no talent uses any more
    assert bundles.sync(mirror) == 0
    assert bundles.drift(mirror) == []
    assert not (bundled / PRODUCT / "icons" / "424242.png").exists()
    assert not (bundled / PRODUCT / "icons" / "132090.png").exists()
    # the other build keeps its own copy of the same icon
    assert (bundled / REFERENCE / "icons" / "132090.png").exists()


def test_syncing_one_build_never_removes_another_build_s_bundle(mirror, capsys) -> None:
    """Dropping a build from the list must not silently delete what saved builds open."""
    assert bundles.sync(mirror, builds=(PRODUCT,)) == 1
    assert "refusing to sync the mobile visuals bundle (nothing was changed)" in (
        capsys.readouterr().err
    )
    assert (mirror.bundled_root / REFERENCE / "warrior.json").is_file()
    assert (mirror.bundled_root / PREVIOUS_PRODUCT / "warrior.json").is_file()
    assert bundles.drift(mirror, builds=(PRODUCT,)) == [
        f"{build}: in bundle but not a bundled build (BUNDLED_BUILDS in scripts/mobile_bundles.py)"
        for build in (REFERENCE, PREVIOUS_PRODUCT)
    ]
    assert bundles.check(mirror, builds=(PRODUCT,)) == 1


def snapshot(root: Path) -> dict[str, bytes]:
    """Every file under ``root`` (dotfiles included) with its bytes."""
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob("*") if p.is_file()}


def drifted(kind) -> None:
    """Drift a sync WOULD repair: a changed file and a stale file inside the product build."""
    (kind.bundled_root / PRODUCT / "warrior.json").write_text("{}")
    (kind.bundled_root / PRODUCT / "icons" / "424242.png").write_bytes(b"orphan")


def test_failed_preflight_for_an_unlisted_build_changes_nothing(mirror, capsys) -> None:
    """Regression: the unlisted-build check used to run AFTER the listed builds were
    mirrored, so a failing sync had already rewritten and deleted files."""
    drifted(mirror)
    (mirror.bundled_root / "1.60.1.69977").mkdir()
    (mirror.bundled_root / "1.60.1.69977" / "warrior.json").write_text("{}")
    before = snapshot(mirror.bundled_root)

    assert bundles.sync(mirror) == 1

    assert snapshot(mirror.bundled_root) == before
    err = capsys.readouterr().err
    assert "nothing was changed" in err
    assert "1.60.1.69977: in bundle but not a bundled build" in err


def test_failed_preflight_for_a_missing_canonical_file_changes_nothing(mirror, capsys) -> None:
    drifted(mirror)
    (mirror.canonical_root / REFERENCE / "icons.json").unlink()
    kind = dataclasses.replace(mirror, required_files=("icons.json", "warrior.json"))
    before = snapshot(kind.bundled_root)

    assert bundles.sync(kind) == 1

    assert snapshot(kind.bundled_root) == before
    assert f"{REFERENCE}/icons.json: canonical file missing" in capsys.readouterr().err


def test_preflight_lists_every_blocking_problem(mirror) -> None:
    (mirror.canonical_root / PRODUCT / "warrior.json").unlink()
    (mirror.bundled_root / "1.60.1.69977").mkdir()
    kind = dataclasses.replace(mirror, required_files=("icons.json", "warrior.json"))
    assert bundles.preflight(kind) == [
        f"{PRODUCT}/warrior.json: canonical file missing",
        "1.60.1.69977: in bundle but not a bundled build "
        "(BUNDLED_BUILDS in scripts/mobile_bundles.py)",
    ]


def test_the_sync_entry_point_is_all_or_nothing(mirror, monkeypatch, capsys) -> None:
    """The script's own entry point (not just ``sync``) leaves a blocked bundle untouched."""
    drifted(mirror)
    (mirror.bundled_root / "1.60.1.69977").mkdir()
    before = snapshot(mirror.bundled_root)
    kind = dataclasses.replace(mirror, required_files=("icons.json", "warrior.json"))

    assert bundles.run(kind, []) == 1

    assert snapshot(mirror.bundled_root) == before


def test_an_unknown_bundled_build_directory_is_drift(mirror) -> None:
    (mirror.bundled_root / "1.60.1.69977").mkdir()
    (mirror.bundled_root / "1.60.1.69977" / "warrior.json").write_text("{}")
    assert bundles.drift(mirror) == [
        "1.60.1.69977: in bundle but not a bundled build "
        "(BUNDLED_BUILDS in scripts/mobile_bundles.py)"
    ]


def test_missing_canonical_files_are_reported_before_anything_is_mirrored(mirror) -> None:
    (mirror.canonical_root / PRODUCT / "warrior.json").unlink()
    kind = dataclasses.replace(mirror, required_files=("icons.json", "warrior.json"))
    assert bundles.missing_canonical(kind, bundles.BUNDLED_BUILDS) == [f"{PRODUCT}/warrior.json"]


def test_os_dotfiles_are_not_bundle_content(mirror) -> None:
    """Finder's .DS_Store in either tree is neither drift nor something to mirror."""
    (mirror.canonical_root / PRODUCT / ".DS_Store").write_bytes(b"finder")
    (mirror.bundled_root / ".DS_Store").write_bytes(b"finder")
    (mirror.bundled_root / REFERENCE / "icons" / ".DS_Store").write_bytes(b"finder")
    assert bundles.drift(mirror) == []
    assert bundles.sync(mirror) == 0
    assert not (mirror.bundled_root / PRODUCT / ".DS_Store").exists()
    assert (mirror.bundled_root / REFERENCE / "icons" / ".DS_Store").exists()
