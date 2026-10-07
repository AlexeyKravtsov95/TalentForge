"""The M5.V2 tooltip-template inventory: committed, in sync, and exhaustive."""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load_script():
    spec = importlib.util.spec_from_file_location(
        "tooltip_template_inventory", ROOT / "scripts" / "tooltip_template_inventory.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module  # dataclasses resolve their module while it executes
    spec.loader.exec_module(module)
    return module


inventory_script = load_script()


def categories_of(template: str) -> list[tuple[str, str, str | None]]:
    return [(t.category, t.text, t.nested_in) for t in inventory_script.tokenize(template)]


def test_committed_inventory_is_in_sync_with_the_dataset(capsys) -> None:
    assert inventory_script.main(["--check"]) == 0
    assert "in sync (469 entries)" in capsys.readouterr().out


def test_every_construct_of_every_bundled_template_is_classified() -> None:
    inventory = json.loads(inventory_script.JSON_OUT.read_text(encoding="utf-8"))
    by_name = {c["category"]: c for c in inventory["categories"]}
    assert by_name["unclassified"]["occurrences"] == 0
    assert inventory["totalEntries"] == 469
    assert inventory["entriesWithoutTemplate"] == 0
    assert inventory["staticEntries"] == 8
    assert inventory["entriesNeedingEvaluation"] == 461
    # Every category the M5.V1 brief names is actually present in the bundled data.
    for name in (
        "local-effect-value",
        "cross-spell-effect-value",
        "cross-spell-duration",
        "local-duration",
        "local-radius",
        "arithmetic",
        "plural",
        "color",
        "conditional",
        "nested-tooltip",
        "named-variable",
    ):
        assert by_name[name]["occurrences"] > 0, name
    assert len(inventory["entries"]) == 469
    assert sum(1 for e in inventory["entries"] if e["static"]) == 8


def test_static_entries_contain_no_template_markup() -> None:
    inventory = json.loads(inventory_script.JSON_OUT.read_text(encoding="utf-8"))
    for entry in inventory["static"]:
        assert "$" not in entry["text"] and "|" not in entry["text"], entry["name"]


def test_tokenizer_on_real_templates() -> None:
    # Warrior - Improved Rend
    assert categories_of("Increases the Bleed damage done by your Rend ability by $s1%.") == [
        ("local-effect-value", "$s1", None)
    ]
    # Druid - Eclipse: arithmetic with a precision suffix, cross-spell stacks and duration
    eclipse = (
        "Your Wrath spell reduces the cast time of your next $s3 Starfire spells by "
        "${$m2/-1000}.2 sec. Stores up to $408255u charges. Lasts $408255d."
    )
    assert categories_of(eclipse) == [
        ("local-effect-value", "$s3", None),
        ("arithmetic", "${$m2/-1000}.2", None),
        ("local-effect-value", "$m2", "arithmetic"),
        ("cross-spell-proc", "$408255u", None),
        ("cross-spell-duration", "$408255d", None),
    ]
    # Druid - Feral Charge: conditional with colour branches, line breaks, nested tooltip
    feral_charge = (
        "$?a5487|a9634[|CFFFFFFFFRequires Bear Form, Dire Bear Form|R]"
        "[|CFFFF2020Requires Bear Form, Dire Bear Form|R]\r\nCharge an enemy, immobilizing "
        "them and interrupting any spell they are casting for $19675d.\r\n\r\n"
        "$@spelltooltip414924"
    )
    assert [c for c, _, _ in categories_of(feral_charge)] == [
        "line-break",
        "line-break",
        "line-break",
        "conditional",
        "color",
        "color",
        "color",
        "color",
        "cross-spell-duration",
        "nested-tooltip",
    ]
    assert ("color", "|CFFFF2020", "conditional") in categories_of(feral_charge)
    # Warlock - Demonic Brand: description variables
    assert ("description-variable", "$<minDam>", None) in categories_of(
        "deal $<minDam> to $<maxDam> Fire or Shadow damage"
    )
    # Rogue / Druid: plurals and scaled values
    assert categories_of("$s1 $lpoint:points;") == [
        ("local-effect-value", "$s1", None),
        ("plural", "$lpoint:points;", None),
    ]
    assert categories_of("by $/10;16959s1%") == [("scaled-value", "$/10;16959s1", None)]
    # Paladin - Shield Specialization: a named value
    assert categories_of("every $proccooldown sec") == [("named-variable", "$proccooldown", None)]


def test_unknown_markup_is_reported_not_ignored() -> None:
    assert categories_of("costs $ 5 and |T123:0|t") == [
        ("unclassified", "$ 5 and ", None),
        ("unclassified", "|T123:0|", None),
        ("unclassified", "|t", None),
    ]
    assert categories_of("${$m1/10")[0][0] == "unclassified"
