"""Normalized talent data - our public, versioned domain contract (schema v1).

This is what the future mobile client consumes. It deliberately does NOT mirror DB2 tables:
join tables, curve rows, condition rows etc. are resolved into node-centric facts. Anything
the pipeline could not resolve with confidence is exposed as explicit ``unresolved`` data
instead of a guessed value.

JSON uses camelCase; Python uses snake_case.
"""

from __future__ import annotations

import hashlib
import json
from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

SCHEMA_VERSION: Literal[1] = 1

# Fields of TalentDataDocument that change on every run and carry no talent meaning.
VOLATILE_FIELDS = frozenset({"generated_at", "provenance", "content_hash"})

Number = int | float


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        frozen=True,
        extra="forbid",
    )


class ClassRef(_Model):
    id: int = Field(description="ChrClasses.ID")
    token: str = Field(description="Locale-independent class token, e.g. WARRIOR")
    name: str


class Position(_Model):
    x: int = Field(description="Raw client layout X (TraitNode.PosX). Authoritative.")
    y: int = Field(description="Raw client layout Y (TraitNode.PosY). Authoritative.")
    row: int = Field(description="Derived: round((y - tree min y) / tree.layout.yStep)")
    column: int = Field(
        description="Derived: round((x - section min x) / tree.layout.xStep); the whole "
        "tree is one section when sections are unresolved"
    )


class RankValue(_Model):
    rank: int
    value: Number


PerRankStatus = Literal["resolved", "incomplete", "none", "unresolved"]


class Effect(_Model):
    index: int = Field(description="Spell effect index; $s1 in tooltips is index 0")
    per_rank: list[RankValue] | None = Field(
        description="Value of this effect at each rank, from the trait rank curve. "
        "null when there is no curve (static effect) or it could not be interpreted."
    )
    per_rank_status: PerRankStatus = Field(
        description="resolved: every rank 1..maxRank has a value; incomplete: curve misses "
        "ranks; none: no rank curve for this effect; unresolved: curve exists but its "
        "semantics are unknown (see unresolvedReason)"
    )
    base_points: Number | None = Field(
        description="Spell's static base points for this effect (SpellEffect, default "
        "difficulty). Informational; NOT the per-rank value of multi-rank talents."
    )
    unresolved_reason: str | None = None


class TalentEntry(_Model):
    id: int = Field(description="TraitNodeEntry.ID")
    definition_id: int = Field(description="TraitDefinition.ID")
    spell_id: int
    name: str | None
    max_rank: int
    entry_type: int = Field(description="TraitNodeEntry.NodeEntryType (raw, semantics TBD)")
    description_template: str | None = Field(
        description="Raw Blizzard tooltip template (unrendered, e.g. '$s1%')"
    )
    effects: list[Effect]


PrerequisiteKind = Literal["sufficient", "required"]


class Prerequisite(_Model):
    node_id: int
    kind: PrerequisiteKind = Field(
        description="TraitEdge.Type 2 = SufficientForAvailability, 3 = RequiredForAvailability"
    )


class UnlockRequirement(_Model):
    kind: Literal["pointsSpent"] = "pointsSpent"
    required_points: int
    counted_node_ids: list[int] = Field(
        description="Points spent in these nodes count toward required_points"
    )


NodeType = Literal["single", "tiered", "selection"]


class TalentNode(_Model):
    id: int = Field(description="TraitNode.ID - stable identity across builds")
    section_index: int | None
    position: Position
    type: NodeType | str = Field(description="TraitNode.Type; 'unknown:<n>' if unmapped")
    flags: int = Field(description="Raw TraitNode.Flags (semantics not resolved in M0)")
    max_rank: int
    spell_id: int | None = Field(description="Set when the node has exactly one entry")
    name: str | None = Field(description="Set when the node has exactly one entry")
    prerequisites: list[Prerequisite]
    unlock_requirements: list[UnlockRequirement]
    unresolved_condition_ids: list[int] = Field(
        description="TraitCond IDs attached to this node that M0 cannot interpret"
    )
    entries: list[TalentEntry]


class Section(_Model):
    index: int
    name: str | None = Field(description="Not present in client data for Forever (null)")
    node_ids: list[int]


class PointBudget(_Model):
    currency_id: int
    max_points: int = Field(description="TraitCurrency.SourcedMax")


class Layout(_Model):
    x_step: int = Field(description="Dominant horizontal node spacing in client units")
    y_step: int = Field(description="Dominant vertical node spacing in client units")


class VisualEdge(_Model):
    from_node_id: int
    to_node_id: int


class TalentTree(_Model):
    id: int = Field(description="TraitTree.ID")
    point_budget: PointBudget | None
    layout: Layout
    sections: list[Section]
    nodes: list[TalentNode]
    visual_only_edges: list[VisualEdge] = Field(
        description="TraitEdge.Type 0: drawn but no gameplay requirement"
    )


class Provenance(_Model):
    source: str
    tables: list[str]


class TalentDataDocument(_Model):
    schema_version: Literal[1] = SCHEMA_VERSION
    game: str
    client_build: str
    class_: ClassRef = Field(alias="class")
    trees: list[TalentTree]
    content_hash: str = Field(
        description="sha256 of the semantic payload (everything except volatile metadata)"
    )
    generated_at: datetime
    provenance: Provenance

    def semantic_payload(self) -> dict[str, Any]:
        return semantic_payload(self.model_dump(mode="json", by_alias=True))

    def to_json(self) -> str:
        return self.model_dump_json(by_alias=True, indent=2) + "\n"


def semantic_payload(document: dict[str, Any]) -> dict[str, Any]:
    volatile_aliases = {to_camel(f) for f in VOLATILE_FIELDS}
    return {k: v for k, v in document.items() if k not in volatile_aliases}


def content_hash(payload: dict[str, Any]) -> str:
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()
