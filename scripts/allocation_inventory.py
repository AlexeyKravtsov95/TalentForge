"""Allocation-relevant inventory of the raw Forever client data, per class.

Reads the committed per-class DB2 fixtures (strict subsets of the wago.tools exports, see
tests/fixtures/README.md), so the output is reproducible offline. Prints the markdown
tables used in docs/talent-allocation-semantics.md ("Dataset inventory").

    python scripts/allocation_inventory.py --build 1.60.1.70009
"""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

from talent_data.db2.dataset import TraitDataSet
from talent_data.normalization.class_resolution import resolve_class, trees_for_class
from talent_data.sources.local import LocalCsvDb2Source
from talent_data.versions import ClientVersion

ROOT = Path(__file__).resolve().parents[1]
CLASSES = ["warrior", "paladin", "hunter", "rogue", "priest", "shaman", "mage", "warlock", "druid"]
COND_FIELDS = (
    "trait_node_group_id",
    "trait_node_id",
    "trait_node_entry_id",
    "trait_cond_account_element_id",
    "trait_currency_id",
    "spent_amount_required",
    "required_level",
    "granted_ranks",
    "quest_id",
    "achievement_id",
    "spec_set_id",
    "flags",
)
SHORT = {
    "trait_node_group_id": "group",
    "trait_node_id": "node",
    "trait_node_entry_id": "entry",
    "trait_cond_account_element_id": "account",
    "trait_currency_id": "currency",
    "spent_amount_required": "spent",
    "required_level": "level",
    "granted_ranks": "granted",
    "quest_id": "quest",
    "achievement_id": "achievement",
    "spec_set_id": "specSet",
    "flags": "flags",
}


def fmt(counter: Counter[object]) -> str:
    return ", ".join(f"{k}x{v}" for k, v in sorted(counter.items(), key=lambda kv: str(kv[0])))


def inventory(build: ClientVersion) -> None:
    rows = []
    shapes: Counter[str] = Counter()
    for key in CLASSES:
        source = LocalCsvDb2Source(ROOT / "tests/fixtures/db2/forever" / str(build) / key, False)
        ds = TraitDataSet.load(source, build)
        chr_class = resolve_class(ds, key)
        (tree_id,) = trees_for_class(ds, chr_class)
        nodes = ds.nodes_by_tree[tree_id]
        node_ids = {n.id for n in nodes}
        links = {n.id: ds.node_entry_links.get(n.id, []) for n in nodes}
        entries = [ds.entries[link.trait_node_entry_id] for ls in links.values() for link in ls]
        edges = [e for e in ds.edges if e.right_trait_node_id in node_ids]
        incoming: Counter[int] = Counter(e.right_trait_node_id for e in edges if e.type in (2, 3))
        cond_ids = set()
        for n in nodes:
            cond_ids.update(ds.node_conds.get(n.id, []))
            for g in ds.node_groups.get(n.id, []):
                cond_ids.update(ds.group_conds.get(g, []))
        conds = [ds.conds[c] for c in sorted(cond_ids)]
        for c in conds:
            shape = "+".join(SHORT[f] for f in COND_FIELDS if getattr(c, f)) or "(all zero)"
            shapes[f"CondType={c.cond_type} {shape}"] += 1
        (cost_link,) = ds.tree_costs[tree_id]
        cost = ds.costs[cost_link.trait_cost_id]
        (cur_link,) = ds.tree_currencies[tree_id]
        currency = ds.currencies[cur_link.trait_currency_id]
        rows.append(
            [
                chr_class.token,
                str(tree_id),
                str(len(nodes)),
                fmt(Counter(n.type for n in nodes)),
                fmt(Counter(n.flags for n in nodes)),
                fmt(Counter(len(v) for v in links.values())),
                fmt(Counter(e.node_entry_type for e in entries)),
                fmt(Counter(e.max_ranks for e in entries)),
                fmt(Counter(e.type for e in edges)),
                str(sum(1 for v in incoming.values() if v > 1)),
                f"{currency.id} (max {currency.sourced_max})",
                f"{cost.amount}x{cost.trait_currency_id} curve {cost.curve_id}",
                str(len(conds)),
                str(sum(1 for c in conds if c.granted_ranks or c.cond_type == 2)),
                str(ds.trees[tree_id].flags),
            ]
        )
    header = [
        "Class",
        "Tree",
        "Nodes",
        "Node.Type",
        "Node.Flags",
        "Entries/node",
        "EntryType",
        "MaxRanks",
        "Edge.Type",
        "Nodes w/ >1 prereq edge",
        "Currency",
        "Rank cost (tree)",
        "Conds",
        "Granted conds",
        "Tree.Flags",
    ]
    print("| " + " | ".join(header) + " |")
    print("|" + "---|" * len(header))
    for row in rows:
        print("| " + " | ".join(row) + " |")
    print()
    print("| TraitCond shape (non-zero functional fields) | Count |")
    print("|---|---|")
    for shape, count in shapes.most_common():
        print(f"| {shape} | {count} |")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build", default="1.60.1.70009")
    inventory(ClientVersion.parse(parser.parse_args().build))


if __name__ == "__main__":
    main()
