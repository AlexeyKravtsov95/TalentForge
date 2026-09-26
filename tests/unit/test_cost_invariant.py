"""M2: the normalizer *proves* the "one rank costs one point" invariant from client data
(TraitTreeXTraitCost -> TraitCost) instead of assuming it, and only flattens tier gates
that are priced in the tree's own point currency. Mutations below simulate future client
data that must stop the pipeline (or surface as unresolved) rather than be mis-read.
"""

from __future__ import annotations

import dataclasses

import pytest

from talent_data.db2 import tables as t
from talent_data.db2.dataset import TraitDataSet
from talent_data.normalization.normalizer import RANK_COST_POINTS, NormalizationError
from talent_data.versions import ClientVersion
from tests.conftest import ALL_CLASSES, load_dataset, normalize_warrior
from tests.helpers import NEW_BUILD, OLD_BUILD

WARRIOR_TREE = 1117


@pytest.mark.parametrize("build", [OLD_BUILD, NEW_BUILD])
@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_every_class_tree_costs_exactly_one_point_per_rank(
    class_key: str, build: ClientVersion
) -> None:
    """The raw fact behind rule COST-1, on every real fixture."""
    ds = load_dataset(class_key, build)
    (tree_id,) = ds.nodes_by_tree  # a class fixture holds exactly its own tree
    (link,) = ds.tree_costs[tree_id]
    cost = ds.costs[link.trait_cost_id]
    (currency,) = ds.tree_currencies[tree_id]
    assert (cost.amount, cost.trait_currency_id, cost.curve_id) == (
        RANK_COST_POINTS,
        currency.trait_currency_id,
        0,
    )


def _with_cost(ds: TraitDataSet, **changes: int) -> TraitDataSet:
    (link,) = ds.tree_costs[WARRIOR_TREE]
    cost = dataclasses.replace(ds.costs[link.trait_cost_id], **changes)
    return dataclasses.replace(ds, costs={**ds.costs, cost.id: cost})


@pytest.mark.parametrize(
    ("changes", "message"),
    [
        ({"amount": 2}, "Amount=2"),
        ({"trait_currency_id": 4225}, "TraitCurrencyID=4225"),
        ({"curve_id": 123}, "CurveID=123"),
    ],
)
def test_unexpected_rank_cost_stops_normalization(
    dataset_new: TraitDataSet, changes: dict[str, int], message: str
) -> None:
    with pytest.raises(NormalizationError, match=message):
        normalize_warrior(_with_cost(dataset_new, **changes))


def test_missing_or_extra_tree_cost_stops_normalization(dataset_new: TraitDataSet) -> None:
    (link,) = dataset_new.tree_costs[WARRIOR_TREE]
    for links in ([], [link, dataclasses.replace(link, id=link.id + 1)]):
        broken = dataclasses.replace(
            dataset_new, tree_costs={**dataset_new.tree_costs, WARRIOR_TREE: links}
        )
        with pytest.raises(NormalizationError, match="TraitTreeXTraitCost"):
            normalize_warrior(broken)
    dangling = dataclasses.replace(
        dataset_new,
        tree_costs={WARRIOR_TREE: [dataclasses.replace(link, trait_cost_id=1)]},
    )
    with pytest.raises(NormalizationError, match="unknown TraitCost 1"):
        normalize_warrior(dangling)


def test_sub_tree_nodes_stop_normalization(dataset_new: TraitDataSet) -> None:
    nodes = dataset_new.nodes_by_tree[WARRIOR_TREE]
    moved = [dataclasses.replace(nodes[0], trait_sub_tree_id=7), *nodes[1:]]
    broken = dataclasses.replace(
        dataset_new, nodes_by_tree={**dataset_new.nodes_by_tree, WARRIOR_TREE: moved}
    )
    with pytest.raises(NormalizationError, match="TraitSubTree 7"):
        normalize_warrior(broken)
    entry_id = dataset_new.node_entry_links[nodes[0].id][0].trait_node_entry_id
    entry = dataclasses.replace(dataset_new.entries[entry_id], trait_sub_tree_id=9)
    broken = dataclasses.replace(dataset_new, entries={**dataset_new.entries, entry_id: entry})
    with pytest.raises(NormalizationError, match="TraitSubTree 9"):
        normalize_warrior(broken)


def test_gate_in_another_currency_is_unresolved_not_flattened(dataset_new: TraitDataSet) -> None:
    doc = normalize_warrior(dataset_new)
    charge = next(n for n in doc.trees[0].nodes if n.name == "Improved Charge")
    assert [r.required_points for r in charge.unlock_requirements] == [5]
    cond_ids = {
        c
        for g in dataset_new.node_groups[charge.id]
        for c in dataset_new.group_conds.get(g, [])
        if dataset_new.conds[c].spent_amount_required == 5
    }
    (cond_id,) = cond_ids
    other: t.TraitCond = dataclasses.replace(dataset_new.conds[cond_id], trait_currency_id=4225)
    changed = normalize_warrior(
        dataclasses.replace(dataset_new, conds={**dataset_new.conds, cond_id: other})
    )
    charge2 = next(n for n in changed.trees[0].nodes if n.id == charge.id)
    assert charge2.unlock_requirements == []
    assert cond_id in charge2.unresolved_condition_ids
