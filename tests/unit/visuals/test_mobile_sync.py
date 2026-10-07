"""The mobile visuals bundle is a byte-identical mirror of the canonical directory."""

from __future__ import annotations

import importlib.util
import shutil
import socket
from pathlib import Path

import pytest

from tests.unit.visuals.conftest import BUNDLED_VISUALS, CANONICAL_VISUALS, ROOT


def load_sync_script():
    spec = importlib.util.spec_from_file_location(
        "sync_mobile_visuals", ROOT / "scripts" / "sync_mobile_visuals.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync_script = load_sync_script()


@pytest.fixture
def no_network(monkeypatch: pytest.MonkeyPatch) -> None:
    def refuse(*args: object, **kwargs: object) -> None:
        raise AssertionError("the visual drift check must not open a network connection")

    monkeypatch.setattr(socket.socket, "connect", refuse)
    monkeypatch.setattr(socket, "create_connection", refuse)


def test_committed_bundle_matches_canonical_without_network(no_network, capsys) -> None:
    assert sync_script.CANONICAL_DIR == CANONICAL_VISUALS
    assert sync_script.BUNDLED_DIR == BUNDLED_VISUALS
    assert sync_script.main(["--check"]) == 0
    assert "bundle in sync: 368 files" in capsys.readouterr().out


@pytest.fixture
def mirrored(tmp_path: Path) -> tuple[Path, Path]:
    canonical, bundled = tmp_path / "canonical", tmp_path / "bundled"
    (canonical / "icons").mkdir(parents=True)
    for name in ("warrior.json", "icons.json", "icons/132269.png", "icons/132090.png"):
        shutil.copy(CANONICAL_VISUALS / name, canonical / name)
    assert sync_script.sync(canonical, bundled) == 0
    return canonical, bundled


def test_sync_produces_a_byte_identical_mirror(mirrored, capsys) -> None:
    canonical, bundled = mirrored
    assert sync_script.drift(canonical, bundled) == []
    assert sync_script.check(canonical, bundled) == 0


def test_check_detects_changed_missing_and_extra_files(mirrored, capsys) -> None:
    canonical, bundled = mirrored
    (bundled / "warrior.json").write_text("{}")
    (bundled / "icons" / "132269.png").unlink()
    (bundled / "icons" / "424242.png").write_bytes(b"orphan")
    assert sync_script.drift(canonical, bundled) == [
        "icons/132269.png: missing from bundle",
        "icons/424242.png: in bundle but not canonical",
        "warrior.json: bundle differs from canonical",
    ]
    assert sync_script.check(canonical, bundled) == 1
    assert "out of sync" in capsys.readouterr().err


def test_sync_repairs_drift_and_removes_stale_files(mirrored, capsys) -> None:
    canonical, bundled = mirrored
    (bundled / "warrior.json").write_text("{}")
    (bundled / "icons" / "424242.png").write_bytes(b"orphan")
    (canonical / "icons" / "132090.png").unlink()  # an icon no talent uses any more
    assert sync_script.sync(canonical, bundled) == 0
    assert sync_script.drift(canonical, bundled) == []
    assert not (bundled / "icons" / "424242.png").exists()
    assert not (bundled / "icons" / "132090.png").exists()
