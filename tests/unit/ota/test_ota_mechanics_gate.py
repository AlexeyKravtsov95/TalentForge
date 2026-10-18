"""Publisher gate G6 (M5.OTA.1): unsupported mechanics can never be published.

The level-1 baseline is exactly what the engine already fails closed on in the data every
shipped app bundles (Rogue Venom); a release with any other fail-closed node is a STOP, and
the gate has no parameter through which an approval could override it.
"""

from __future__ import annotations

import inspect
import json
from pathlib import Path

import pytest

from talent_data.models.talents import TalentDataDocument
from talent_data.ota.mechanics import (
    UnsupportedMechanic,
    load_baseline,
    new_unsupported_mechanics,
    unsupported_mechanics,
)

ROOT = Path(__file__).resolve().parents[3]
BUNDLED_BUILDS = ["1.60.1.70009", "1.60.1.70245", "1.60.1.70338", "1.60.1.70374", "1.60.1.70450"]
VENOM = UnsupportedMechanic("ROGUE", 1111, 105712, "UNRESOLVED_CONDITION", None, 50984)


def documents(build: str) -> list[TalentDataDocument]:
    folder = ROOT / "data" / "normalized" / "forever" / build
    return [
        TalentDataDocument.model_validate_json(p.read_bytes())
        for p in sorted(folder.glob("*.json"))
        if p.name != "manifest.json"
    ]


def test_level_1_baseline_is_exactly_the_known_venom_anomaly() -> None:
    assert load_baseline(1).known_unsupported == {VENOM}


@pytest.mark.parametrize("build", BUNDLED_BUILDS)
def test_every_bundled_build_has_only_the_baseline_findings(build: str) -> None:
    assert unsupported_mechanics(documents(build)) == {VENOM}
    assert new_unsupported_mechanics(documents(build), load_baseline(1)) == []


def test_a_wire_valid_release_with_an_unknown_node_type_is_stopped() -> None:
    """The fixture release-open-union-node-type passes every contract check (open union),
    but its Paladin node is unsupported by the engine: G6 stops it."""
    blob = ROOT / "docs/ota/fixtures/v1/blobs/release-open-union-node-type--paladin.json"
    paladin = TalentDataDocument.model_validate_json(blob.read_bytes())
    others = [d for d in documents("1.60.1.70450") if d.class_.token != "PALADIN"]
    found = new_unsupported_mechanics([*others, paladin], load_baseline(1))
    assert [(m.class_token, m.code) for m in found] == [("PALADIN", "UNSUPPORTED_NODE_TYPE")]


def test_the_gate_offers_no_override() -> None:
    assert list(inspect.signature(new_unsupported_mechanics).parameters) == [
        "documents",
        "baseline",
    ]


def test_a_level_without_a_baseline_does_not_exist(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError):
        load_baseline(2)
    (tmp_path / "level-1.json").write_text(
        json.dumps(
            {"schemaVersion": 1, "dataLevel": 1, "knownUnsupported": [{"classToken": "ROGUE"}]}
        )
    )
    with pytest.raises(ValueError, match="every entry needs"):
        load_baseline(1, tmp_path)
