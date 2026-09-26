"""The committed conformance vectors: reproducible, complete, and pinned to real data."""

from __future__ import annotations

from talent_data.allocation.conformance import (
    ConformanceSuite,
    load_suite,
    load_suite_documents,
    outcome_report,
    run_scenario,
)
from talent_data.paths import conformance_path
from tests.conformance.scenarios import BASELINE, GAME, ROOT, build_suite

SUITE_FILE = ROOT / "data" / "conformance" / GAME / str(BASELINE) / "allocation.v1.json"


def committed() -> ConformanceSuite:
    return load_suite(SUITE_FILE)


REQUIRED_COVERAGE = {
    # task-mandated coverage -> scenario ids
    "basic rank purchase": "warrior.basic_rank_purchase",
    "max rank rejection": "warrior.max_rank_rejection",
    "point budget exhaustion": "warrior.point_budget_exhaustion",
    "tier gate locked": "warrior.tier_gate_locked",
    "tier gate unlocked": "warrior.tier_gate_unlocked",
    "required prerequisite": "druid.required_prerequisite",
    "multiple required prerequisites": "synthetic.multiple_required_prerequisites_all",
    "sufficient prerequisite": "warrior.sufficient_prerequisite_requires_max_rank",
    "multiple sufficient prerequisites": "hunter.multiple_sufficient_prerequisites_any",
    "Hunter mutual sufficient edges": "hunter.mutual_sufficient_cycle",
    "refund leaf talent": "warrior.refund_leaf_talent",
    "refund prerequisite with active dependent": (
        "warrior.refund_prerequisite_with_active_dependent"
    ),
    "multi-rank refund": "warrior.multi_rank_refund",
    "invalid imported build": "warrior.invalid_imported_build",
    "unknown node": "warrior.unknown_node",
    "wrong entry": "warrior.wrong_entry",
    "unresolved condition": "rogue.unresolved_condition_fails_closed",
    "selection node (absent in Forever -> fail closed)": "synthetic.selection_node_fail_closed",
}


def test_committed_vectors_are_up_to_date() -> None:
    """Drift test: regenerating from the authored scenarios reproduces the committed file
    byte for byte (run `python -m tests.conformance.generate` after a reviewed change)."""
    assert SUITE_FILE.read_text(encoding="utf-8") == build_suite().to_json()


def test_conformance_path_points_at_the_committed_file() -> None:
    assert conformance_path(GAME, BASELINE).parts[-5:] == SUITE_FILE.parts[-5:]


def test_reference_engine_reproduces_every_vector() -> None:
    suite = committed()
    documents = load_suite_documents(suite, ROOT)
    mismatches = []
    for scenario in suite.scenarios:
        mismatches += outcome_report(scenario, run_scenario(scenario, documents))
    assert mismatches == []


def test_suite_identity_and_pinned_datasets() -> None:
    suite = committed()
    assert (suite.schema_version, suite.game, suite.client_build) == (1, "forever", "1.60.1.70009")
    assert {d.class_ for d in suite.datasets} == {
        "WARRIOR",
        "PALADIN",
        "HUNTER",
        "ROGUE",
        "PRIEST",
        "SHAMAN",
        "MAGE",
        "WARLOCK",
        "DRUID",
    }
    load_suite_documents(suite, ROOT)  # raises on any content-hash drift


def test_required_coverage_and_every_class() -> None:
    suite = committed()
    ids = {s.id for s in suite.scenarios}
    missing = {k: v for k, v in REQUIRED_COVERAGE.items() if v not in ids}
    assert missing == {}
    for token in suite.datasets:
        cls = token.class_.lower()
        assert {f"{cls}.smoke", f"{cls}.golden_51_point_build"} <= ids


def test_golden_builds_spend_exactly_51_points_and_validate() -> None:
    suite = committed()
    golden = [s for s in suite.scenarios if s.id.endswith(".golden_51_point_build")]
    assert len(golden) == 9
    for scenario in golden:
        assert sum(a.rank for a in scenario.initial) == 51
        first = scenario.steps[0]
        assert first.op == "validate"
        assert first.expect.ok
        assert (first.expect.spent_points, first.expect.remaining_points) == (51, 0)


def test_synthetic_scenarios_are_labelled() -> None:
    for scenario in committed().scenarios:
        assert scenario.synthetic == bool(scenario.mutations)
        assert scenario.synthetic == scenario.id.startswith("synthetic.")
        assert scenario.rules, scenario.id
