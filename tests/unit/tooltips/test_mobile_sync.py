"""The mobile tooltip bundle is a byte-identical mirror of the canonical directory."""

from __future__ import annotations

import importlib.util
import shutil
import socket
from pathlib import Path

import pytest

from tests.unit.tooltips.conftest import BUNDLED_TOOLTIPS, CANONICAL_TOOLTIPS, ROOT


def load_sync_script():
    spec = importlib.util.spec_from_file_location(
        "sync_mobile_tooltips", ROOT / "scripts" / "sync_mobile_tooltips.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync_script = load_sync_script()


@pytest.fixture
def no_network(monkeypatch: pytest.MonkeyPatch) -> None:
    def refuse(*args: object, **kwargs: object) -> None:
        raise AssertionError("the tooltip drift check must not open a network connection")

    monkeypatch.setattr(socket.socket, "connect", refuse)
    monkeypatch.setattr(socket, "create_connection", refuse)


def test_committed_bundle_matches_canonical_without_network(no_network, capsys) -> None:
    assert sync_script.CANONICAL_DIR == CANONICAL_TOOLTIPS
    assert sync_script.BUNDLED_DIR == BUNDLED_TOOLTIPS
    assert sync_script.main(["--check"]) == 0
    assert "bundle in sync: 9 files" in capsys.readouterr().out


def test_check_detects_changed_missing_and_extra_files(tmp_path: Path, capsys) -> None:
    canonical, bundled = tmp_path / "canonical", tmp_path / "bundled"
    canonical.mkdir()
    for name in ("warrior.json", "mage.json"):
        shutil.copy(CANONICAL_TOOLTIPS / name, canonical / name)
    assert sync_script.sync(canonical, bundled) == 0
    assert sync_script.check(canonical, bundled) == 0
    (bundled / "warrior.json").write_text("{}")
    (bundled / "mage.json").unlink()
    (bundled / "extra.json").write_text("{}")
    assert sync_script.check(canonical, bundled) == 1
    err = capsys.readouterr().err
    assert "mage.json: missing from bundle" in err
    assert "extra.json: in bundle but not canonical" in err
    assert "warrior.json: bundle differs from canonical" in err
