"""Publisher gate G6 - no release may carry a mechanic the allocation engine cannot handle.

Wire validity is not functional support. ``talent-data.v1`` keeps some values open (node
``type``, raw entry types, node flags, unresolved conditions), so a release can pass every
contract check while the allocation engine fails closed on some of its nodes. This module
computes exactly those nodes - the engine's own static fail-closed issues
(``TreeIndex.static_issues``: ``UNSUPPORTED_*``, ``UNRESOLVED_CONDITION``,
``NODE_NEVER_PURCHASABLE``) - and compares them with the frozen baseline of the release's
data level (``docs/ota/data-levels/level-<n>.json``).

Any finding outside the baseline is a STOP. There is deliberately no override parameter:
an owner's approval cannot make the engine support a mechanic. The path for a new mechanic
is an app release that supports it (or ships it investigated as a known fail-closed
anomaly), a new data level with its own baseline, and only then a release with that
``minDataLevel``. A level's baseline is frozen once an app implementing the level has shipped.
"""

from __future__ import annotations

import json
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from talent_data.allocation.rules import TreeIndex
from talent_data.models.talents import TalentDataDocument

DATA_LEVELS_DIR = Path(__file__).resolve().parents[3] / "docs" / "ota" / "data-levels"
_ITEM_KEYS = {"classToken", "treeId", "nodeId", "entryId", "code", "conditionId", "evidence"}


@dataclass(frozen=True, order=True)
class UnsupportedMechanic:
    """One fail-closed engine issue, identified independently of its message text."""

    class_token: str
    tree_id: int
    node_id: int
    code: str
    entry_id: int | None
    condition_id: int | None


@dataclass(frozen=True)
class DataLevelBaseline:
    data_level: int
    known_unsupported: frozenset[UnsupportedMechanic]


def unsupported_mechanics(
    documents: Iterable[TalentDataDocument],
) -> frozenset[UnsupportedMechanic]:
    """Every static fail-closed issue the allocation engine reports for these documents."""
    found: set[UnsupportedMechanic] = set()
    for doc in documents:
        for tree in doc.trees:
            for node_id, issues in TreeIndex.build(tree).static_issues.items():
                for issue in issues:
                    found.add(
                        UnsupportedMechanic(
                            class_token=doc.class_.token,
                            tree_id=tree.id,
                            node_id=node_id,
                            code=issue.code.value,
                            entry_id=issue.entry_id,
                            condition_id=issue.condition_id,
                        )
                    )
    return frozenset(found)


def load_baseline(data_level: int, directory: Path = DATA_LEVELS_DIR) -> DataLevelBaseline:
    """The frozen baseline of ``data_level``. A level without a baseline file does not exist."""
    path = directory / f"level-{data_level}.json"
    raw: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    if set(raw) != {"schemaVersion", "dataLevel", "knownUnsupported"} or raw["schemaVersion"] != 1:
        raise ValueError(f"{path.name}: unexpected shape")
    if raw["dataLevel"] != data_level:
        raise ValueError(f"{path.name} declares level {raw['dataLevel']}")
    items: list[dict[str, Any]] = raw["knownUnsupported"]
    for item in items:
        if set(item) != _ITEM_KEYS or not item["evidence"]:
            raise ValueError(f"{path.name}: every entry needs exactly {sorted(_ITEM_KEYS)}")
    known = frozenset(
        UnsupportedMechanic(
            class_token=item["classToken"],
            tree_id=item["treeId"],
            node_id=item["nodeId"],
            code=item["code"],
            entry_id=item["entryId"],
            condition_id=item["conditionId"],
        )
        for item in items
    )
    return DataLevelBaseline(data_level, known)


def new_unsupported_mechanics(
    documents: Iterable[TalentDataDocument], baseline: DataLevelBaseline
) -> list[UnsupportedMechanic]:
    """Gate G6: the findings the baseline does not cover, sorted. Non-empty means STOP."""
    return sorted(unsupported_mechanics(documents) - baseline.known_unsupported)
