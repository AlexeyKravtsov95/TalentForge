"""The Android OSS notices generator and its drift check, offline (M5.RC.2b review).

``scripts/generate_oss_notices.py --check`` must fail on ANY difference between the
committed notices and what the build ships — not only on changed coordinates: a licence
that changed upstream while the coordinates stayed the same, an edited licence id or name,
a changed URL, or a module moved to another component. Gradle resolution and POM lookups
are replaced by stubs here; the real resolution runs in mobile-ci.
"""

from __future__ import annotations

import copy
import importlib.util
import json
import sys
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[2]
COMMITTED = (
    ROOT / "mobile/composeApp/src/commonMain/composeResources/files/legal/oss-notices-android.json"
)

MODULES = [
    "androidx.activity:activity:1.13.0",
    "com.google.guava:listenablefuture:1.0",
    "org.jetbrains.kotlin:kotlin-stdlib:2.4.20",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0",
]
APACHE = ["The Apache Software License, Version 2.0"]


def load_generator() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "generate_oss_notices", ROOT / "scripts" / "generate_oss_notices.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


g = load_generator()


@pytest.fixture
def notices_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Gradle resolves MODULES; every POM declares Apache-2.0; the notices live in tmp_path."""
    path = tmp_path / "files" / "legal" / "oss-notices-android.json"
    monkeypatch.setattr(g, "ROOT", tmp_path)
    monkeypatch.setattr(g, "NOTICES", path)
    monkeypatch.setattr(g, "resolved_modules", lambda: list(MODULES))
    monkeypatch.setattr(g, "declared_licenses", lambda group, artifact, version: list(APACHE))
    return path


def edit(path: Path, change: Any) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    change(data)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def component(data: dict[str, Any], name: str) -> dict[str, Any]:
    return next(c for c in data["components"] if c["name"] == name)


def test_generated_notices_pass_the_check(notices_file: Path) -> None:
    assert g.main([]) == 0
    assert g.main(["--check"]) == 0


def test_an_upstream_licence_change_with_unchanged_coordinates_fails(
    notices_file: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    assert g.main([]) == 0
    # the same coordinates, but the kotlinx.coroutines POM now declares another licence
    monkeypatch.setattr(
        g,
        "declared_licenses",
        lambda group, artifact, version: ["MIT"] if artifact.startswith("kotlinx-") else APACHE,
    )
    assert g.main(["--check"]) == 1
    error = capsys.readouterr().err
    assert "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0" in error
    assert "MIT" in error


def test_an_edited_component_licence_with_unchanged_coordinates_fails(
    notices_file: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    assert g.main([]) == 0

    def relicense(data: dict[str, Any]) -> None:
        data["licenses"]["MIT"] = {"name": "MIT License", "file": "files/legal/LICENSE-MIT.txt"}
        component(data, "kotlinx.coroutines")["license"] = "MIT"

    edit(notices_file, relicense)
    assert g.main(["--check"]) == 1
    error = capsys.readouterr().err
    assert "notices.components[2].license: committed 'MIT', expected 'Apache-2.0'" in error
    assert "notices.licenses.MIT: not expected" in error


@pytest.mark.parametrize(
    ("change", "path"),
    [
        (
            lambda d: d["licenses"]["Apache-2.0"].update(name="Apache License 2.1"),
            "notices.licenses.Apache-2.0.name",
        ),
        (
            lambda d: d["licenses"]["Apache-2.0"].update(file="files/legal/OTHER.txt"),
            "notices.licenses.Apache-2.0.file",
        ),
        (
            lambda d: component(d, "Guava ListenableFuture").update(url="https://example.org"),
            "notices.components[3].url",
        ),
        (lambda d: d.update(schema="talentforge.oss-notices.v2"), "notices.schema"),
        (lambda d: d.update(platform="ios"), "notices.platform"),
        (lambda d: d.pop("source"), "notices.source: missing"),
    ],
)
def test_any_licence_or_component_metadata_edit_fails(
    notices_file: Path, capsys: pytest.CaptureFixture[str], change: Any, path: str
) -> None:
    assert g.main([]) == 0
    edit(notices_file, change)
    assert g.main(["--check"]) == 1
    assert path in capsys.readouterr().err


def test_a_module_moved_to_another_component_fails(
    notices_file: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    assert g.main([]) == 0

    def move(data: dict[str, Any]) -> None:
        stdlib = "org.jetbrains.kotlin:kotlin-stdlib:2.4.20"
        component(data, "Kotlin Standard Library")["modules"].remove(stdlib)
        component(data, "AndroidX (Android Jetpack)")["modules"].append(stdlib)

    edit(notices_file, move)
    assert g.main(["--check"]) == 1
    error = capsys.readouterr().err
    assert "notices.components[0].modules: not listed [], not expected" in error


def test_coordinate_drift_still_fails(
    notices_file: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    assert g.main([]) == 0
    monkeypatch.setattr(g, "resolved_modules", lambda: [*MODULES, "androidx.core:core:1.18.0"])
    assert g.main(["--check"]) == 1
    assert "androidx.core:core:1.18.0" in capsys.readouterr().err


def test_a_missing_or_malformed_file_fails(notices_file: Path) -> None:
    assert g.main(["--check"]) == 1
    notices_file.parent.mkdir(parents=True)
    notices_file.write_text("{not json", encoding="utf-8")
    assert g.main(["--check"]) == 1


def test_a_module_no_component_claims_fails(
    notices_file: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(g, "resolved_modules", lambda: [*MODULES, "com.example:unknown:1.0"])
    assert g.main([]) == 1


def test_the_committed_notices_match_the_curated_components(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The committed file is exactly what the generator renders for its own module list
    when every POM declares Apache-2.0 (the real POMs are checked in mobile-ci)."""
    committed = json.loads(COMMITTED.read_text(encoding="utf-8"))
    modules = sorted(m for c in committed["components"] for m in c["modules"])
    monkeypatch.setattr(g, "declared_licenses", lambda group, artifact, version: list(APACHE))
    expected = g.build_notices(modules)
    assert g.differences(expected, copy.deepcopy(committed)) == []
    assert COMMITTED.read_text(encoding="utf-8") == json.dumps(expected, indent=2) + "\n"
