"""The structural report counts and quotes what a capture contains - nothing more."""

from __future__ import annotations

import json
import re
from typing import Any

import pytest

from talent_data.pipeline import read_document
from talent_data.tooltips.capture import ClientTooltipCapture, import_client_capture
from talent_data.tooltips.manifest import CaptureManifest
from talent_data.tooltips.report import render_report
from tests.conftest import ALL_CLASSES
from tests.helpers import NEW_BUILD
from tests.unit.tooltips.conftest import ROOT, payload, saved_json

FERAL_CHARGE = 129604  # the first Druid entry whose template has a `$?` conditional
WEAPONMASTER = 130674


@pytest.fixture(scope="module")
def templates() -> dict[int, str | None]:
    dataset = ROOT / "data" / "normalized" / "forever" / str(NEW_BUILD)
    return {
        entry.id: entry.description_template
        for key in ALL_CLASSES
        for tree in read_document(dataset / f"{key}.json").trees
        for node in tree.nodes
        for entry in node.entries
    }


@pytest.fixture(scope="module")
def capture(real_manifest: CaptureManifest) -> ClientTooltipCapture:
    """A synthetic capture with a few deliberately different tooltips."""
    value = payload(real_manifest)
    by_key: dict[tuple[int, int], dict[str, Any]] = {
        (r["entryId"], r["rank"]): r for r in value["records"]
    }
    charge = by_key[(FERAL_CHARGE, 1)]["tooltip"]
    charge["hasDynamicData"] = True
    charge["lines"].insert(
        2,
        {"type": 0, "leftText": "|CFFFF2020Requires Bear Form, Dire Bear Form|R", "wrapText": True},
    )
    charge["lines"].append({"type": 7, "leftText": "8 sec cooldown", "rightText": "8-25 yd range"})
    by_key[(WEAPONMASTER, 3)]["tooltip"]["lines"][2]["leftText"] = "Leaked $s1% and ${$m1/10}.1"
    by_key[(WEAPONMASTER, 5)]["reads"] = 4
    del by_key[(WEAPONMASTER, 5)]["tooltip"]["hasDynamicData"]
    return import_client_capture(saved_json(value), real_manifest)


@pytest.fixture(scope="module")
def report(
    capture: ClientTooltipCapture, real_manifest: CaptureManifest, templates: dict[int, str | None]
) -> str:
    return render_report(capture, real_manifest, templates)


def row(report: str, label: str) -> str:
    cells = [
        [cell.strip().replace("\\|", "|") for cell in re.split(r"(?<!\\)\|", line)[1:-1]]
        for line in report.splitlines()
        if line.startswith("| ")
    ]
    matches = [row for row in cells if row[0] == label]
    assert len(matches) == 1, f"{label}: {matches}"
    return matches[0][1]


def test_totals_answer_the_structural_questions(report: str) -> None:
    assert row(report, "talent entries") == "469"
    assert row(report, "entry/rank requests") == "1333"
    assert row(report, "nil responses in the final capture").startswith("0 ")
    assert row(report, "tooltips with `hasDynamicData` true") == "1"
    assert row(report, "tooltips with `hasDynamicData` false") == "1331"
    assert row(report, "tooltips without a `hasDynamicData` field") == "1"
    assert row(report, "tooltips whose first line is exactly the talent name") == "1333 / 1333"
    assert row(report, "tooltips with template-like `$` tokens (heuristic)") == "1"
    assert row(report, "tooltips with colour markup `|c…` / `|r`") == "1"
    assert row(report, "tooltips with the word `Requires` (heuristic)") == "1"
    assert row(report, "tooltips with the word `Rank` (heuristic)") == "1333"


def test_rank_variation_is_measured_per_multi_rank_entry(
    report: str, real_manifest: CaptureManifest
) -> None:
    multi_rank = sum(1 for e in real_manifest.entries if e.max_rank > 1)
    assert row(report, "multi-rank entries") == str(multi_rank)
    # Every synthetic rank has its own "Rank n/m" and value line.
    assert row(report, "multi-rank entries whose lines differ between ranks") == str(multi_rank)
    assert row(report, "multi-rank entries whose line count differs between ranks") == "0"


def test_line_types_line_counts_and_reads_are_distributions(report: str) -> None:
    assert row(report, "0") == str(1333 * 3 + 1)  # line type 0
    assert row(report, "7") == "1"  # line type 7
    assert row(report, "3") == "1332"  # tooltips with 3 lines
    assert row(report, "5") == "1"  # ... and the one with 5
    assert row(report, "0 0 0") == "1332"  # line-type sequence
    assert row(report, "0 0 0 0 7") == "1"
    assert row(report, "2") == "1332"  # reads
    assert row(report, "4") == "1"
    assert row(report, "leftColor") == "1333"
    assert row(report, "rightText") == "1"


def test_requested_examples_are_quoted_verbatim(report: str) -> None:
    for heading in (
        "#### WARRIOR · Improved Rend (entry 130686, max rank 3)",
        "#### WARRIOR · Improved Slam (entry 137850, max rank 2)",
        "#### WARRIOR · Mortal Strike (entry 130671, max rank 1)",
        "#### WARRIOR · Weaponmaster (entry 130674, max rank 5)",
        "#### DRUID · Feral Charge (entry 129604, max rank 1)",
    ):
        assert report.count(heading) == 1
    assert report.count("#### MAGE · ") == 1
    assert report.count("#### ROGUE · ") == 1
    # Client strings are shown JSON-escaped: markup, quotes and line breaks stay visible.
    quoted = json.dumps("|CFFFF2020Requires Bear Form, Dire Bear Form|R")
    assert f"`{quoted}`" in report
    assert '· right `"8-25 yd range"`' in report
    assert '`"Leaked $s1% and ${$m1/10}.1"`' in report


def test_report_is_deterministic_and_identifies_the_capture(
    report: str,
    capture: ClientTooltipCapture,
    real_manifest: CaptureManifest,
    templates: dict[int, str | None],
) -> None:
    assert render_report(capture, real_manifest, templates) == report
    assert report.startswith("# Client tooltip capture report: forever 1.60.1.70009 enUS\n")
    assert f"`{capture.semantic_hash}`" in report
    assert f"`{real_manifest.manifest_hash}`" in report
    assert report.endswith("\n") and not report.endswith("\n\n")
