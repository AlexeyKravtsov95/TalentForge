"""Extract small, real DB2 fixture sets for class trees from the local wago cache.

For every requested class, keeps only the rows reachable from that class's TraitTree
(nodes, entries, definitions, edges, gate groups/conditions, rank curves, spells, and the
SpellMisc presentation rows of those spells), with canonical column names. The result is
committed under ``tests/fixtures/db2/<game>/<build>/<class-token>/`` so unit tests run
offline on real client rows. Nothing is invented or hand-edited; each fixture is a strict
subset of the wago.tools export of the named build.

    python scripts/extract_fixtures.py --build 1.60.1.70009 --classes all
    python scripts/extract_fixtures.py --build 1.60.1.69977 --classes warrior,druid

``--tooltips`` (M5.V2) instead writes ``tests/fixtures/tooltips/<game>/<build>/``: the rows of
the tooltip tables that the committed dataset's description templates can reach (the talent
spells, every spell they reference or nest, transitively), the whole of the small
``SpellDuration``/``SpellRadius`` tables and the duration rows of ``GlobalStrings``:

    python scripts/extract_fixtures.py --build 1.60.1.70009 --tooltips
"""

from __future__ import annotations

import argparse
import csv
from collections.abc import Callable, Mapping
from pathlib import Path

from talent_data.db2 import tables as t
from talent_data.db2.dataset import TraitDataSet
from talent_data.db2.tables import (
    MUST_BE_ABSENT_TABLES,
    TOOLTIP_TABLES,
    TRAIT_TABLES,
    VISUAL_TABLES,
)
from talent_data.games import get_game
from talent_data.normalization.class_resolution import resolve_class, trees_for_class
from talent_data.sources.base import Db2Table, TableAvailability, TableStatus
from talent_data.sources.capabilities import record_absent, snapshot_path
from talent_data.sources.wago.source import WagoDb2Source
from talent_data.tooltips.grammar import TemplateSyntaxError, parse, referenced_spells
from talent_data.tooltips.values import DURATION_TAGS
from talent_data.versions import ClientVersion
from talent_data.visuals.pipeline import load_canonical_dataset

ROOT = Path(__file__).resolve().parents[1]

Row = Mapping[str, str]


def extract_class(
    chr_class: t.ChrClass,
    dataset: TraitDataSet,
    tables: dict[str, Db2Table],
    game: str,
    build: str,
    statuses: Mapping[str, TableAvailability],
) -> Path:
    def ids(table: str, column: str, keep: Callable[[Row], bool]) -> set[str]:
        return {r[column] for r in tables[table].rows if keep(r)}

    tree_ids = trees_for_class(dataset, chr_class)
    assert len(tree_ids) == 1, f"{chr_class.token}: expected one tree, got {tree_ids}"
    tree = str(tree_ids[0])
    nodes = ids("TraitNode", "ID", lambda r, tree=tree: r["TraitTreeID"] == tree)
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
        "TraitDefinitionEffectPoints",
        "CurveID",
        lambda r: r["TraitDefinitionID"] in definitions,
    )
    currencies = ids(
        "TraitTreeXTraitCurrency", "TraitCurrencyID", lambda r, tree=tree: r["TraitTreeID"] == tree
    )
    cost_ids = ids(
        "TraitTreeXTraitCost", "TraitCostID", lambda r, tree=tree: r["TraitTreeID"] == tree
    )
    # Both directions of class resolution need every skill line; ClassMask rows for
    # those lines disambiguate which class the tree belongs to.
    skill_lines = ids("SkillLineXTraitTree", "SkillLineID", lambda r: True)

    keep: dict[str, Callable[[Row], bool]] = {
        "ChrClasses": lambda r: True,
        "SkillRaceClassInfo": lambda r: r["SkillID"] in skill_lines,
        "SkillLineXTraitTree": lambda r: True,
        "TraitTree": lambda r, tree=tree: r["ID"] == tree,
        "TraitTreeXTraitCurrency": lambda r, tree=tree: r["TraitTreeID"] == tree,
        "TraitCurrency": lambda r: r["ID"] in currencies,
        "TraitTreeXTraitCost": lambda r, tree=tree: r["TraitTreeID"] == tree,
        "TraitCost": lambda r: r["ID"] in cost_ids,
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
        # presentation (talent icons): every row of the class's display spells
        "SpellMisc": lambda r: r["SpellID"] in spells,
    }
    dst = ROOT / "tests" / "fixtures" / "db2" / game / build / chr_class.token.lower()
    dst.mkdir(parents=True, exist_ok=True)
    total = 0
    for name, table in tables.items():
        rows = [r for r in table.rows if keep[name](r)]
        total += len(rows)
        with (dst / f"{name}.csv").open("w", encoding="utf-8", newline="") as fh:
            writer = csv.writer(fh, lineterminator="\n")
            writer.writerow(table.columns)
            writer.writerows([r[c] for c in table.columns] for r in rows)
    # Table-availability snapshot: absence cannot be shown by a missing file, so the
    # proven-absent tables of the build are recorded next to the fixture tables.
    snapshot = snapshot_path(dst)
    snapshot.unlink(missing_ok=True)
    for name in MUST_BE_ABSENT_TABLES:
        availability = statuses[name]
        if availability.status is not TableStatus.ABSENT:
            raise SystemExit(
                f"{name} is {availability.status} for {build} ({availability.evidence}); "
                "fixtures can only be extracted for builds that prove its absence"
            )
        record_absent(dst, name, availability.evidence)
    print(f"{chr_class.token.lower()} (tree {tree}): {total} rows -> {dst}")
    return dst


def _write_table(table: Db2Table, rows: list[Row], dst: Path) -> int:
    with (dst / f"{table.name}.csv").open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh, lineterminator="\n")
        writer.writerow(table.columns)
        writer.writerows([r[c] for c in table.columns] for r in rows)
    return len(rows)


def extract_tooltips(cache: WagoDb2Source, game: str, version: ClientVersion) -> Path:
    """Tooltip fixture: the closure of spells the committed dataset's templates reach."""
    tables = {name: cache.load_table(version, name) for name in TOOLTIP_TABLES}
    descriptions = {r["ID"]: r["Description_lang"] for r in tables["Spell"].rows}
    documents = load_canonical_dataset(get_game(game), version)
    pending = [
        e.spell_id for d in documents for tree in d.trees for n in tree.nodes for e in n.entries
    ]
    spells: set[int] = set()
    while pending:
        spell_id = pending.pop()
        if spell_id in spells:
            continue
        spells.add(spell_id)
        description = descriptions.get(str(spell_id))
        if description is None:
            continue
        try:
            values, nested = referenced_spells(parse(description))
        except TemplateSyntaxError:
            continue
        pending += [*values, *nested]
    keep_ids = {str(s) for s in spells}
    links = [r for r in tables["SpellXDescriptionVariables"].rows if r["SpellID"] in keep_ids]
    variable_ids = {r["SpellDescriptionVariablesID"] for r in links}
    keep: dict[str, Callable[[Row], bool]] = {
        "Spell": lambda r: r["ID"] in keep_ids,
        "SpellEffect": lambda r: r["SpellID"] in keep_ids,
        "SpellMisc": lambda r: r["SpellID"] in keep_ids,
        "SpellDuration": lambda r: True,
        "SpellRadius": lambda r: True,
        "SpellAuraOptions": lambda r: r["SpellID"] in keep_ids,
        "SpellXDescriptionVariables": lambda r: r["SpellID"] in keep_ids,
        "SpellDescriptionVariables": lambda r: r["ID"] in variable_ids,
        "GlobalStrings": lambda r: r["BaseTag"] in DURATION_TAGS,
    }
    dst = ROOT / "tests" / "fixtures" / "tooltips" / game / str(version)
    dst.mkdir(parents=True, exist_ok=True)
    total = sum(
        _write_table(table, [r for r in table.rows if keep[name](r)], dst)
        for name, table in tables.items()
    )
    print(f"tooltips: {len(spells)} spells, {total} rows -> {dst}")
    return dst


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--game", default="forever")
    parser.add_argument("--build", required=True)
    parser.add_argument("--classes", help="'all' or comma-separated class keys")
    parser.add_argument(
        "--tooltips", action="store_true", help="write the tooltip fixture instead of classes"
    )
    args = parser.parse_args()

    cache = WagoDb2Source(None, ROOT / ".cache" / "wago" / args.game, offline=True)
    version = ClientVersion.parse(args.build)
    if args.tooltips:
        extract_tooltips(cache, args.game, version)
        return
    if not args.classes:
        parser.error("--classes is required unless --tooltips is given")
    dataset = TraitDataSet.load(cache, version)
    classes = (
        sorted(dataset.classes.values(), key=lambda c: c.id)
        if args.classes == "all"
        else [resolve_class(dataset, key) for key in args.classes.split(",")]
    )
    tables = {name: cache.load_table(version, name) for name in (*TRAIT_TABLES, *VISUAL_TABLES)}
    statuses = {name: cache.table_status(version, name) for name in MUST_BE_ABSENT_TABLES}
    for chr_class in classes:
        extract_class(chr_class, dataset, tables, args.game, args.build, statuses)


if __name__ == "__main__":
    main()
