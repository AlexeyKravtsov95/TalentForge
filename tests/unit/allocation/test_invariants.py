"""Engine-wide invariants on every class of the captured builds (deterministic).

* random-walk invariants: allowed actions keep the build valid, refused actions never touch
  the state, spend-then-refund restores the state, points/ranks stay in bounds, results are
  deterministic;
* an *independent* brute-force reachability checker (a second, deliberately naive
  implementation of the documented rules, searching over purchase orders) agrees with
  ``validate`` on generated imports.

Seeds are fixed, so failures are reproducible; no property-testing dependency is needed.
"""

from __future__ import annotations

import random
from functools import cache

import pytest

from talent_data.allocation import BuildState, TalentAllocationEngine
from talent_data.models.talents import TalentDataDocument, TalentTree
from tests.conformance.scenarios import BASELINE
from tests.helpers import OLD_BUILD, PRODUCT_BUILD

CLASSES = ["WARRIOR", "PALADIN", "HUNTER", "ROGUE", "PRIEST", "SHAMAN", "MAGE", "WARLOCK", "DRUID"]
BUILDS = [str(BASELINE), str(OLD_BUILD), str(PRODUCT_BUILD)]


def _docs(
    build: str,
    docs: dict[str, TalentDataDocument],
    old_docs: dict[str, TalentDataDocument],
    product_docs: dict[str, TalentDataDocument],
) -> dict[str, TalentDataDocument]:
    return {str(BASELINE): docs, str(OLD_BUILD): old_docs, str(PRODUCT_BUILD): product_docs}[build]


@pytest.mark.parametrize("build", BUILDS)
@pytest.mark.parametrize("cls", CLASSES)
def test_random_walk_invariants(
    cls: str,
    build: str,
    docs: dict[str, TalentDataDocument],
    old_docs: dict[str, TalentDataDocument],
    product_docs: dict[str, TalentDataDocument],
) -> None:
    doc = _docs(build, docs, old_docs, product_docs)[cls]
    engine = TalentAllocationEngine(doc)
    twin = TalentAllocationEngine(doc)  # a second instance must answer identically
    rng = random.Random(f"{cls}-{build}")
    node_ids = sorted(n.id for n in engine.tree.nodes)
    max_rank = {n.id: n.max_rank for n in engine.tree.nodes}
    state = engine.empty_state()
    for _ in range(400):
        node_id = rng.choice(node_ids)
        if rng.random() < 0.7:
            result = engine.spend(state, node_id)
            assert result.decision == twin.can_spend(state, node_id)
        else:
            result = engine.refund(state, node_id)
            assert result.decision == twin.can_refund(state, node_id)
        if result.allowed:
            assert engine.validate(result.state).valid
            if result.state.rank_of(node_id) > state.rank_of(node_id):
                # spend then refund restores the previous state exactly
                assert engine.refund(result.state, node_id).state == state
        else:
            assert result.state is state
            assert result.decision.issues
        state = result.state
        spent, remaining = engine.spent_points(state), engine.remaining_points(state)
        assert spent >= 0
        assert remaining >= 0
        assert spent + remaining == engine.point_budget
        assert all(1 <= a.rank <= max_rank[a.node_id] for a in state.allocations)
        assert len({a.node_id for a in state.allocations}) == len(state.allocations)


# --------------------------------------------------------------------------- brute force


def brute_force_reachable(tree: TalentTree, target: dict[int, int]) -> bool:
    """Naive second implementation of the documented purchase rules: depth-first search
    over purchase orders (memoised), one rank at a time, from an empty tree."""
    nodes = {n.id: n for n in tree.nodes}

    def purchasable(ranks: dict[int, int], nid: int) -> bool:
        n = nodes[nid]
        if n.unresolved_condition_ids or n.type != "single" or n.flags & ~8:
            return False
        for req in n.unlock_requirements:
            if sum(ranks.get(c, 0) for c in req.counted_node_ids) < req.required_points:
                return False
        required = [p.node_id for p in n.prerequisites if p.kind == "required"]
        sufficient = [p.node_id for p in n.prerequisites if p.kind == "sufficient"]
        if required and sufficient:
            return False
        if any(ranks.get(s, 0) < nodes[s].max_rank for s in required):
            return False
        return not sufficient or any(ranks.get(s, 0) >= nodes[s].max_rank for s in sufficient)

    goal = tuple(sorted(target.items()))

    @cache
    def search(current: tuple[tuple[int, int], ...]) -> bool:
        if current == goal:
            return True
        ranks = dict(current)
        for nid, want in target.items():
            if ranks.get(nid, 0) < want and purchasable(ranks, nid):
                nxt = dict(ranks)
                nxt[nid] = nxt.get(nid, 0) + 1
                if search(tuple(sorted(nxt.items()))):
                    return True
        return False

    return search(())


@pytest.mark.parametrize("cls", CLASSES)
def test_validate_agrees_with_brute_force_reachability(
    cls: str, docs: dict[str, TalentDataDocument]
) -> None:
    engine = TalentAllocationEngine(docs[cls])
    tree = engine.tree
    rng = random.Random(f"brute-{cls}")
    row0 = [n for n in tree.nodes if n.position.row == 0]
    deeper = [n for n in tree.nodes if 1 <= n.position.row <= 2]
    agreements = {True: 0, False: 0}
    for _ in range(150):
        # row-0 talents (often fully ranked, feeding gates and edges) + a few deeper ones
        chosen = rng.sample(row0, rng.randint(1, 2)) + rng.sample(deeper, rng.randint(0, 3))
        target = {n.id: rng.randint(max(1, n.max_rank - 1), n.max_rank) for n in chosen}
        if sum(target.values()) > 14:
            continue
        state = BuildState.of(tree.id, [(n.id, n.entries[0].id, target[n.id]) for n in chosen])
        expected = brute_force_reachable(tree, target)
        assert engine.validate(state).valid is expected, target
        agreements[expected] += 1
    assert agreements[True] > 5
    assert agreements[False] > 5
