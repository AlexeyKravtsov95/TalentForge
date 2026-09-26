"""Typed views over the DB2 tables used by the talent pipeline.

This module is the only place that knows DB2 column names. It turns source-neutral
:class:`~talent_data.sources.base.Db2Table` objects into small typed records and fails loudly
(with the table/row/column) when a value cannot be parsed, instead of silently defaulting.

Column naming follows WoWDBDefs; array columns are flattened as ``<Name>_<i>``.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import ClassVar, Protocol, Self

from talent_data.sources.base import Db2Table, SourceError

# Tables needed to build a class talent tree. `fetch` downloads exactly this set.
TRAIT_TABLES: tuple[str, ...] = (
    # class -> tree resolution
    "ChrClasses",
    "SkillRaceClassInfo",
    "SkillLineXTraitTree",
    # tree structure
    "TraitTree",
    "TraitTreeXTraitCurrency",
    "TraitCurrency",
    # rank costs (M2: the "every rank costs 1 point" invariant is checked, not assumed)
    "TraitTreeXTraitCost",
    "TraitCost",
    "TraitNode",
    "TraitNodeXTraitNodeEntry",
    "TraitNodeEntry",
    "TraitDefinition",
    "TraitEdge",
    # unlock requirements (tier gates)
    "TraitNodeGroupXTraitNode",
    "TraitNodeGroupXTraitCond",
    "TraitNodeXTraitCond",
    "TraitCond",
    # per-rank values
    "TraitDefinitionEffectPoints",
    "Curve",
    "CurvePoint",
    # spells
    "Spell",
    "SpellName",
    "SpellEffect",
)


def _int(table: str, row: Mapping[str, str], column: str) -> int:
    raw = row.get(column)
    if raw is None:
        raise SourceError(f"{table}: column {column!r} missing in row {dict(row)!r}")
    try:
        return int(raw)
    except ValueError:
        # Some exports write integral floats ("3.0"); accept only exact integers.
        try:
            value = float(raw)
        except ValueError:
            value = None
        if value is not None and value.is_integer():
            return int(value)
        raise SourceError(f"{table}: column {column!r} is not an integer: {raw!r}") from None


def _float(table: str, row: Mapping[str, str], column: str) -> float:
    raw = row.get(column)
    if raw is None:
        raise SourceError(f"{table}: column {column!r} missing in row {dict(row)!r}")
    try:
        return float(raw)
    except ValueError:
        raise SourceError(f"{table}: column {column!r} is not a number: {raw!r}") from None


def _str(table: str, row: Mapping[str, str], column: str) -> str:
    raw = row.get(column)
    if raw is None:
        raise SourceError(f"{table}: column {column!r} missing in row {dict(row)!r}")
    return raw


@dataclass(frozen=True)
class ChrClass:
    id: int
    name: str
    token: str  # ChrClasses.Filename, e.g. "WARRIOR" - stable, locale-independent key

    TABLE: ClassVar[str] = "ChrClasses"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(_int(t, row, "ID"), _str(t, row, "Name_lang"), _str(t, row, "Filename"))


@dataclass(frozen=True)
class SkillRaceClassInfo:
    skill_id: int
    class_mask: int

    TABLE: ClassVar[str] = "SkillRaceClassInfo"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(_int(t, row, "SkillID"), _int(t, row, "ClassMask"))


@dataclass(frozen=True)
class SkillLineXTraitTree:
    id: int
    skill_line_id: int
    trait_tree_id: int

    TABLE: ClassVar[str] = "SkillLineXTraitTree"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(_int(t, row, "ID"), _int(t, row, "SkillLineID"), _int(t, row, "TraitTreeID"))


@dataclass(frozen=True)
class TraitTree:
    id: int
    trait_system_id: int
    flags: int

    TABLE: ClassVar[str] = "TraitTree"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(_int(t, row, "ID"), _int(t, row, "TraitSystemID"), _int(t, row, "Flags"))


@dataclass(frozen=True)
class TraitTreeXTraitCurrency:
    trait_tree_id: int
    trait_currency_id: int
    index: int

    TABLE: ClassVar[str] = "TraitTreeXTraitCurrency"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(
            _int(t, row, "TraitTreeID"), _int(t, row, "TraitCurrencyID"), _int(t, row, "Index")
        )


@dataclass(frozen=True)
class TraitCurrency:
    id: int
    type: int
    sourced_max: int

    TABLE: ClassVar[str] = "TraitCurrency"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(_int(t, row, "ID"), _int(t, row, "Type"), _int(t, row, "SourcedMax"))


@dataclass(frozen=True)
class TraitTreeXTraitCost:
    id: int
    trait_tree_id: int
    trait_cost_id: int

    TABLE: ClassVar[str] = "TraitTreeXTraitCost"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(_int(t, row, "ID"), _int(t, row, "TraitTreeID"), _int(t, row, "TraitCostID"))


@dataclass(frozen=True)
class TraitCost:
    """A price in one trait currency (WoWDBDefs TraitCost; API: TraitCurrencyCost)."""

    id: int
    amount: int
    trait_currency_id: int
    curve_id: int

    TABLE: ClassVar[str] = "TraitCost"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(
            _int(t, row, "ID"),
            _int(t, row, "Amount"),
            _int(t, row, "TraitCurrencyID"),
            _int(t, row, "CurveID"),
        )


@dataclass(frozen=True)
class TraitNode:
    id: int
    trait_tree_id: int
    pos_x: int
    pos_y: int
    type: int
    flags: int
    trait_sub_tree_id: int

    TABLE: ClassVar[str] = "TraitNode"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(
            _int(t, row, "ID"),
            _int(t, row, "TraitTreeID"),
            _int(t, row, "PosX"),
            _int(t, row, "PosY"),
            _int(t, row, "Type"),
            _int(t, row, "Flags"),
            _int(t, row, "TraitSubTreeID"),
        )


@dataclass(frozen=True)
class TraitNodeXTraitNodeEntry:
    id: int
    trait_node_id: int
    trait_node_entry_id: int
    index: int

    TABLE: ClassVar[str] = "TraitNodeXTraitNodeEntry"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(
            _int(t, row, "ID"),
            _int(t, row, "TraitNodeID"),
            _int(t, row, "TraitNodeEntryID"),
            _int(t, row, "Index"),
        )


@dataclass(frozen=True)
class TraitNodeEntry:
    id: int
    trait_definition_id: int
    max_ranks: int
    node_entry_type: int
    trait_sub_tree_id: int

    TABLE: ClassVar[str] = "TraitNodeEntry"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(
            _int(t, row, "ID"),
            _int(t, row, "TraitDefinitionID"),
            _int(t, row, "MaxRanks"),
            _int(t, row, "NodeEntryType"),
            _int(t, row, "TraitSubTreeID"),
        )


@dataclass(frozen=True)
class TraitDefinition:
    id: int
    spell_id: int
    visible_spell_id: int
    overrides_spell_id: int
    override_name: str
    override_description: str

    TABLE: ClassVar[str] = "TraitDefinition"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(
            _int(t, row, "ID"),
            _int(t, row, "SpellID"),
            _int(t, row, "VisibleSpellID"),
            _int(t, row, "OverridesSpellID"),
            _str(t, row, "OverrideName_lang"),
            _str(t, row, "OverrideDescription_lang"),
        )


@dataclass(frozen=True)
class TraitEdge:
    id: int
    left_trait_node_id: int
    right_trait_node_id: int
    type: int
    visual_style: int

    TABLE: ClassVar[str] = "TraitEdge"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(
            _int(t, row, "ID"),
            _int(t, row, "LeftTraitNodeID"),
            _int(t, row, "RightTraitNodeID"),
            _int(t, row, "Type"),
            _int(t, row, "VisualStyle"),
        )


@dataclass(frozen=True)
class TraitNodeGroupXTraitNode:
    trait_node_group_id: int
    trait_node_id: int

    TABLE: ClassVar[str] = "TraitNodeGroupXTraitNode"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(_int(t, row, "TraitNodeGroupID"), _int(t, row, "TraitNodeID"))


@dataclass(frozen=True)
class TraitNodeGroupXTraitCond:
    trait_node_group_id: int
    trait_cond_id: int

    TABLE: ClassVar[str] = "TraitNodeGroupXTraitCond"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(_int(t, row, "TraitNodeGroupID"), _int(t, row, "TraitCondID"))


@dataclass(frozen=True)
class TraitNodeXTraitCond:
    trait_node_id: int
    trait_cond_id: int

    TABLE: ClassVar[str] = "TraitNodeXTraitCond"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(_int(t, row, "TraitNodeID"), _int(t, row, "TraitCondID"))


@dataclass(frozen=True)
class TraitCond:
    id: int
    cond_type: int
    trait_tree_id: int
    trait_node_group_id: int
    trait_node_id: int
    # Constraint/functional fields (see _is_vacuous_cond/_is_pure_points_gate in the
    # normalizer for the evidence that these are semantic, not dead columns):
    trait_node_entry_id: int
    trait_cond_account_element_id: int
    trait_currency_id: int
    spent_amount_required: int
    required_level: int
    granted_ranks: int
    quest_id: int
    achievement_id: int
    spec_set_id: int
    flags: int
    # Presentation-only (SharedString::ID localization texts shown next to a gate, e.g.
    # 911 = "Spend %d more point:points; to unlock this talent"); never constraints.
    free_shared_string_id: int
    spend_more_shared_string_id: int

    TABLE: ClassVar[str] = "TraitCond"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(
            _int(t, row, "ID"),
            _int(t, row, "CondType"),
            _int(t, row, "TraitTreeID"),
            _int(t, row, "TraitNodeGroupID"),
            _int(t, row, "TraitNodeID"),
            _int(t, row, "TraitNodeEntryID"),
            _int(t, row, "TraitCondAccountElementID"),
            _int(t, row, "TraitCurrencyID"),
            _int(t, row, "SpentAmountRequired"),
            _int(t, row, "RequiredLevel"),
            _int(t, row, "GrantedRanks"),
            _int(t, row, "QuestID"),
            _int(t, row, "AchievementID"),
            _int(t, row, "SpecSetID"),
            _int(t, row, "Flags"),
            _int(t, row, "FreeSharedStringID"),
            _int(t, row, "SpendMoreSharedStringID"),
        )


@dataclass(frozen=True)
class TraitDefinitionEffectPoints:
    id: int
    trait_definition_id: int
    effect_index: int
    operation_type: int
    curve_id: int

    TABLE: ClassVar[str] = "TraitDefinitionEffectPoints"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(
            _int(t, row, "ID"),
            _int(t, row, "TraitDefinitionID"),
            _int(t, row, "EffectIndex"),
            _int(t, row, "OperationType"),
            _int(t, row, "CurveID"),
        )


@dataclass(frozen=True)
class Curve:
    id: int
    type: int
    flags: int

    TABLE: ClassVar[str] = "Curve"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(_int(t, row, "ID"), _int(t, row, "Type"), _int(t, row, "Flags"))


@dataclass(frozen=True)
class CurvePoint:
    id: int
    curve_id: int
    order_index: int
    x: float  # Pos_0 - for trait curves: the rank
    y: float  # Pos_1 - for trait curves: the effect value at that rank

    TABLE: ClassVar[str] = "CurvePoint"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(
            _int(t, row, "ID"),
            _int(t, row, "CurveID"),
            _int(t, row, "OrderIndex"),
            _float(t, row, "Pos_0"),
            _float(t, row, "Pos_1"),
        )


@dataclass(frozen=True)
class Spell:
    id: int
    description: str

    TABLE: ClassVar[str] = "Spell"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(_int(t, row, "ID"), _str(t, row, "Description_lang"))


@dataclass(frozen=True)
class SpellName:
    id: int
    name: str

    TABLE: ClassVar[str] = "SpellName"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(_int(t, row, "ID"), _str(t, row, "Name_lang"))


@dataclass(frozen=True)
class SpellEffect:
    id: int
    spell_id: int
    effect_index: int
    difficulty_id: int
    effect: int
    effect_aura: int
    base_points: float  # EffectBasePointsF

    TABLE: ClassVar[str] = "SpellEffect"

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self:
        t = cls.TABLE
        return cls(
            _int(t, row, "ID"),
            _int(t, row, "SpellID"),
            _int(t, row, "EffectIndex"),
            _int(t, row, "DifficultyID"),
            _int(t, row, "Effect"),
            _int(t, row, "EffectAura"),
            _float(t, row, "EffectBasePointsF"),
        )


class Db2Record(Protocol):
    TABLE: ClassVar[str]

    @classmethod
    def parse(cls, row: Mapping[str, str]) -> Self: ...


def parse_table[R](table: Db2Table, parser: Callable[[Mapping[str, str]], R]) -> list[R]:
    return [parser(row) for row in table.rows]
