"""Future-patch safety: the committed real datasets contain only mechanics the engine has
evidence for. A new client build with unknown mechanics must fail here (and then be
investigated and documented) instead of being allocated as if it were ordinary.
"""

from __future__ import annotations

import pytest

from talent_data.allocation import TalentAllocationEngine
from talent_data.allocation.rules import (
    ALLOCATION_NEUTRAL_NODE_FLAGS,
    SUPPORTED_ENTRY_TYPES,
    SUPPORTED_NODE_TYPES,
)
from talent_data.models.talents import TalentDataDocument
from tests.conformance.scenarios import BASELINE
from tests.helpers import OLD_BUILD

CLASSES = ["WARRIOR", "PALADIN", "HUNTER", "ROGUE", "PRIEST", "SHAMAN", "MAGE", "WARLOCK", "DRUID"]

# The complete fail-closed inventory of the captured builds (docs: "Unresolved semantics").
EXPECTED_UNSUPPORTED = {"ROGUE": {105712: ("UNRESOLVED_CONDITION",)}}


def _both(
    docs: dict[str, TalentDataDocument], old_docs: dict[str, TalentDataDocument]
) -> list[tuple[str, TalentDataDocument]]:
    return [(str(BASELINE), d) for d in docs.values()] + [
        (str(OLD_BUILD), d) for d in old_docs.values()
    ]


def test_only_supported_node_and_entry_shapes(
    docs: dict[str, TalentDataDocument], old_docs: dict[str, TalentDataDocument]
) -> None:
    for build, doc in _both(docs, old_docs):
        for node in doc.trees[0].nodes:
            where = f"{build} {doc.class_.token} node {node.id} ({node.name})"
            assert node.type in SUPPORTED_NODE_TYPES, f"{where}: new node type {node.type!r}"
            assert len(node.entries) == 1, f"{where}: {len(node.entries)} entries"
            for entry in node.entries:
                assert entry.entry_type in SUPPORTED_ENTRY_TYPES, (
                    f"{where}: new entry type {entry.entry_type}"
                )
                assert entry.max_rank == node.max_rank
            assert node.flags & ~ALLOCATION_NEUTRAL_NODE_FLAGS == 0, (
                f"{where}: new TraitNode.Flags bits {node.flags:#x}"
            )


def test_forever_has_no_selection_or_tiered_nodes(
    docs: dict[str, TalentDataDocument], old_docs: dict[str, TalentDataDocument]
) -> None:
    """Selection semantics are therefore not verifiable on real data (fail closed; see the
    synthetic.selection_node_fail_closed conformance scenario)."""
    types = {n.type for _, doc in _both(docs, old_docs) for n in doc.trees[0].nodes}
    assert types == {"single"}


def test_edge_shapes_are_the_evidenced_ones(
    docs: dict[str, TalentDataDocument], old_docs: dict[str, TalentDataDocument]
) -> None:
    """Pins the edge inventory the rules were proven on: no mixed required+sufficient node,
    exactly one Required edge, exactly one node with several (sufficient) edges."""
    for build, doc in _both(docs, old_docs):
        tree = doc.trees[0]
        mixed = [
            n.id
            for n in tree.nodes
            if {p.kind for p in n.prerequisites} == {"required", "sufficient"}
        ]
        assert mixed == [], f"{build} {doc.class_.token}: mixed edge kinds on {mixed}"
    all_nodes = [(doc.class_.token, n) for doc in docs.values() for n in doc.trees[0].nodes]
    required = [(c, n.id) for c, n in all_nodes for p in n.prerequisites if p.kind == "required"]
    assert required == [("DRUID", 104928)]
    multi = [(c, n.id) for c, n in all_nodes if len(n.prerequisites) > 1]
    assert multi == [("HUNTER", 104964)]


def test_point_budget_is_51_everywhere(
    docs: dict[str, TalentDataDocument], old_docs: dict[str, TalentDataDocument]
) -> None:
    for _, doc in _both(docs, old_docs):
        budget = doc.trees[0].point_budget
        assert budget is not None
        assert (budget.currency_id, budget.max_points) == (3820, 51)


@pytest.mark.parametrize("cls", CLASSES)
def test_engine_builds_on_every_class_with_pinned_fail_closed_inventory(
    cls: str, docs: dict[str, TalentDataDocument], old_docs: dict[str, TalentDataDocument]
) -> None:
    for doc in (docs[cls], old_docs[cls]):
        engine = TalentAllocationEngine(doc)
        unsupported = {
            node_id: tuple(i.code.value for i in issues)
            for node_id, issues in engine.unsupported_nodes().items()
        }
        assert unsupported == EXPECTED_UNSUPPORTED.get(cls, {})
        assert engine.validate(engine.empty_state()).valid
        assert engine.point_budget == 51
