"""Extract a small, real DB2 fixture set for one class tree from the local wago cache.

Keeps only the rows reachable from the class's TraitTree (nodes, entries, definitions,
edges, gate groups/conditions, rank curves, spells), with canonical column names. The
result is committed under tests/fixtures/db2/ so unit tests run offline on real client rows.

    python scripts/extract_fixtures.py --build 1.60.1.70009 --tree 1117
"""

from __future__ import annotations

import argparse
import csv
from collections.abc import Callable, Mapping
from pathlib import Path

from talent_data.db2.tables import TRAIT_TABLES
from talent_data.sources.wago.source import WagoDb2Source
from talent_data.versions import ClientVersion

ROOT = Path(__file__).resolve().parents[1]

Row = Mapping[str, str]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--game", default="forever")
    parser.add_argument("--build", required=True)
    parser.add_argument("--tree", type=int, required=True)
    args = parser.parse_args()

    src = ROOT / ".cache" / "wago" / args.game / args.build
    dst = ROOT / "tests" / "fixtures" / "db2" / args.game / args.build
    dst.mkdir(parents=True, exist_ok=True)

    cache = WagoDb2Source(None, src.parent, offline=True)
    version = ClientVersion.parse(args.build)
    tables = {name: cache.load_table(version, name) for name in TRAIT_TABLES}

    def ids(table: str, column: str, keep: Callable[[Row], bool]) -> set[str]:
        return {r[column] for r in tables[table].rows if keep(r)}

    tree = str(args.tree)
    nodes = ids("TraitNode", "ID", lambda r: r["TraitTreeID"] == tree)
    links = ids("TraitNodeXTraitNodeEntry", "ID", lambda r: r["TraitNodeID"] in nodes)
    entries = ids("TraitNodeXTraitNodeEntry", "TraitNodeEntryID", lambda r: r["ID"] in links)
    definitions = ids("TraitNodeEntry", "TraitDefinitionID", lambda r: r["ID"] in entries)
    spells = ids("TraitDefinition", "SpellID", lambda r: r["ID"] in definitions) | ids(
        "TraitDefinition", "VisibleSpellID", lambda r: r["ID"] in definitions
    )
    groups = ids(
        "TraitNodeGroupXTraitNode", "TraitNodeGroupID", lambda r: r["TraitNodeID"] in nodes
    )
    conds = ids(
        "TraitNodeGroupXTraitCond", "TraitCondID", lambda r: r["TraitNodeGroupID"] in groups
    )
    conds |= ids("TraitNodeXTraitCond", "TraitCondID", lambda r: r["TraitNodeID"] in nodes)
    groups |= ids("TraitCond", "TraitNodeGroupID", lambda r: r["ID"] in conds)
    curves = ids(
        "TraitDefinitionEffectPoints", "CurveID", lambda r: r["TraitDefinitionID"] in definitions
    )
    currencies = ids(
        "TraitTreeXTraitCurrency", "TraitCurrencyID", lambda r: r["TraitTreeID"] == tree
    )
    skill_lines = ids("SkillLineXTraitTree", "SkillLineID", lambda r: True)

    keep: dict[str, Callable[[Row], bool]] = {
        "ChrClasses": lambda r: True,
        "SkillRaceClassInfo": lambda r: r["SkillID"] in skill_lines,
        "SkillLineXTraitTree": lambda r: True,
        "TraitTree": lambda r: r["ID"] == tree,
        "TraitTreeXTraitCurrency": lambda r: r["TraitTreeID"] == tree,
        "TraitCurrency": lambda r: r["ID"] in currencies,
        "TraitNode": lambda r: r["ID"] in nodes,
        "TraitNodeXTraitNodeEntry": lambda r: r["ID"] in links,
        "TraitNodeEntry": lambda r: r["ID"] in entries,
        "TraitDefinition": lambda r: r["ID"] in definitions,
        "TraitEdge": lambda r: r["LeftTraitNodeID"] in nodes or r["RightTraitNodeID"] in nodes,
        "TraitNodeGroupXTraitNode": lambda r: r["TraitNodeGroupID"] in groups,
        "TraitNodeGroupXTraitCond": lambda r: r["TraitNodeGroupID"] in groups,
        "TraitNodeXTraitCond": lambda r: r["TraitNodeID"] in nodes,
        "TraitCond": lambda r: r["ID"] in conds,
        "TraitDefinitionEffectPoints": lambda r: r["TraitDefinitionID"] in definitions,
        "Curve": lambda r: r["ID"] in curves,
        "CurvePoint": lambda r: r["CurveID"] in curves,
        "Spell": lambda r: r["ID"] in spells,
        "SpellName": lambda r: r["ID"] in spells,
        "SpellEffect": lambda r: r["SpellID"] in spells,
    }
    for name, table in tables.items():
        rows = [r for r in table.rows if keep[name](r)]
        with (dst / f"{name}.csv").open("w", encoding="utf-8", newline="") as fh:
            writer = csv.writer(fh, lineterminator="\n")
            writer.writerow(table.columns)
            writer.writerows([r[c] for c in table.columns] for r in rows)
        print(f"{name}: {len(rows)} rows")


if __name__ == "__main__":
    main()
