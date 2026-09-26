"""Machine-readable semantic diff contract (diff schema v1)."""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from talent_data.models.talents import ClassRef, Position

DIFF_SCHEMA_VERSION: Literal[1] = 1

ChangeKind = Literal[
    # identity / naming
    "renamed",  # same node, same spell id, different name
    "spellReplaced",  # same node, different spell id (NOT a rename)
    "entriesChanged",  # entry ids added/removed on a multi-entry node
    # structure / layout
    "treeChanged",
    "sectionChanged",
    "positionChanged",
    "typeChanged",
    "flagsChanged",
    "maxRankChanged",
    "prerequisitesChanged",
    "unlockRequirementsChanged",
    "unresolvedConditionsChanged",
    # numbers / text
    "effectAdded",
    "effectRemoved",
    "effectRankValueChanged",
    "effectBasePointsChanged",
    "effectStatusChanged",
    "descriptionChanged",
]


class _Model(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)


class Change(_Model):
    kind: ChangeKind
    entry_id: int | None = None
    effect_index: int | None = None
    rank: int | None = None
    old: Any = None
    new: Any = None


class NodeRef(_Model):
    node_id: int
    tree_id: int
    name: str | None
    spell_id: int | None
    section_index: int | None
    position: Position
    max_rank: int
    related_node_id: int | None = Field(
        default=None,
        description="Hint only: a node on the other side with the same spellId. The diff "
        "never merges such pairs into a rename because node identity differs.",
    )


class NodeChange(_Model):
    node_id: int
    tree_id: int
    name: str | None
    changes: list[Change]


class TreeChange(_Model):
    tree_id: int
    kind: Literal["treeAdded", "treeRemoved", "pointBudgetChanged", "sectionsChanged"]
    old: Any = None
    new: Any = None


class DiffSummary(_Model):
    added: int
    removed: int
    changed: int
    changes_by_kind: dict[str, int]


class DiffReport(_Model):
    schema_version: Literal[1] = DIFF_SCHEMA_VERSION
    game: str
    class_: ClassRef = Field(alias="class")
    from_build: str
    to_build: str
    from_content_hash: str
    to_content_hash: str
    summary: DiffSummary
    tree_changes: list[TreeChange]
    added: list[NodeRef]
    removed: list[NodeRef]
    changed: list[NodeChange]

    @property
    def is_empty(self) -> bool:
        return not (self.tree_changes or self.added or self.removed or self.changed)

    def to_json(self) -> str:
        return self.model_dump_json(by_alias=True, indent=2) + "\n"
