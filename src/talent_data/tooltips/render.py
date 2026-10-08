"""Render a talent's description template for one rank.

Source of every value kind (evidence in ``docs/talent-tooltips.md``):

=================  ==============================================================
``$sN`` ``$mN``     effect N-1 (``$s1`` is EffectIndex 0). The talent's own spell:
                    ``effects[].perRank`` of talent-data.v1 at the rank (or its
                    ``basePoints`` when the effect has no rank curve). Any other
                    spell: ``SpellEffect.EffectBasePointsF``.
``$oN``             effect value x (duration / ``EffectAuraPeriod``)
``$aN``             ``SpellEffect.EffectRadiusIndex_0`` -> ``SpellRadius.Radius``
``$tN``             ``SpellEffect.EffectAuraPeriod`` / 1000
``$d``              ``SpellMisc.DurationIndex`` -> ``SpellDuration.Duration``
``$h`` ``$n`` ``$u``  ``SpellAuraOptions.ProcChance`` / ``ProcCharges`` / ``CumulativeAura``
=================  ==============================================================

A value that depends on the character (level-, spell-power- or attack-power-scaled
amounts, description variables built from them), on client state (a conditional whose
branches differ) or on a meaning this build does not prove becomes a *gap* - never a
guess. Unknown syntax makes the whole rank unresolved.
"""

from __future__ import annotations

import re
from collections.abc import Sequence
from dataclasses import dataclass, field, replace
from fractions import Fraction
from typing import Literal

from talent_data.db2 import tables as t
from talent_data.models.talents import Effect, TalentEntry
from talent_data.models.tooltips import (
    GapClassification,
    RankStatus,
    TokenCategory,
    TooltipGap,
    TooltipRank,
    TooltipSegment,
)
from talent_data.tooltips.grammar import (
    Binary,
    Color,
    Conditional,
    DescriptionVariable,
    Expression,
    ExprNode,
    LineBreak,
    NamedVariable,
    Nested,
    Node,
    Number,
    Plural,
    Ref,
    Scaled,
    TemplateSyntaxError,
    Text,
    category_of,
    parse,
)
from talent_data.tooltips.spells import SpellLookup
from talent_data.tooltips.values import exact, format_bare, format_fixed, round_fixed

RENDERER = "talent_data.tooltips grammar-v1"

# Nested tooltips deeper than this are refused (the 1.60.1.70009 talents nest one level).
MAX_NESTING_DEPTH = 3

# SpellEffect.Effect / EffectAura values whose points are a damage, healing or absorb
# AMOUNT (WoWDBDefs/TrinityCore enums: Effect 2 SCHOOL_DAMAGE, 10 HEAL; aura 3
# PERIODIC_DAMAGE, 8 PERIODIC_HEAL, 53 PERIODIC_LEECH, 69 SCHOOL_ABSORB). For these a
# non-zero EffectBonusCoefficient is the spell-power share the client adds to the base.
AMOUNT_EFFECTS = frozenset({2, 10})
AMOUNT_AURAS = frozenset({3, 8, 53, 69})

# Markers in a SpellDescriptionVariables definition that read character or client state.
_STATE_MARKERS = (
    (re.compile(r"\$\?[as]\d"), "a known-spell/aura condition"),
    (re.compile(r"\$(?:pl|PL)\b"), "the caster's level"),
    (re.compile(r"\$SP[A-Z]*\b"), "the caster's spell power"),
)


class Failure(Exception):
    """A token that cannot be evaluated honestly; becomes one gap."""

    def __init__(
        self,
        category: TokenCategory,
        classification: GapClassification,
        token: str,
        reason: str,
    ) -> None:
        super().__init__(reason)
        self.category: TokenCategory = category
        self.classification: GapClassification = classification
        self.token = token
        self.reason = reason


# --------------------------------------------------------------------------- outcomes


Outcome = Literal["resolved", "contextDependent", "unsupported", "missingData"]


@dataclass(frozen=True)
class TokenOutcome:
    """One occurrence of a token of the talent's own template, at one rank."""

    category: TokenCategory
    token: str
    outcome: Outcome


@dataclass(frozen=True)
class RenderedRank:
    rank: TooltipRank
    outcomes: tuple[TokenOutcome, ...]


# --------------------------------------------------------------------------- context


@dataclass(frozen=True)
class _Context:
    spell_id: int  # the spell whose template is being rendered
    stack: tuple[int, ...]  # nested-tooltip spells, outermost first
    record: bool  # record token outcomes (only for the talent's own template)


@dataclass
class _PluralState:
    """The last number written before a plural token."""

    value: Fraction | None = None
    failure: Failure | None = None


@dataclass
class _Output:
    segments: list[tuple[str, str]] = field(default_factory=list)  # (kind, text)
    gaps: list[TooltipGap] = field(default_factory=list)

    def text(self, text: str) -> None:
        self.segments.append(("text", text))

    def value(self, text: str) -> None:
        self.segments.append(("value", text))

    def gap(self, failure: Failure, fragment: str) -> None:
        self.segments.append(("gap", ""))
        self.gaps.append(
            TooltipGap(
                category=failure.category,
                classification=failure.classification,
                fragment=fragment,
                token=failure.token,
                reason=failure.reason,
            )
        )


class TalentRenderer:
    """Renders the description of one talent entry at any of its ranks."""

    def __init__(self, entry: TalentEntry, spells: SpellLookup) -> None:
        self.entry = entry
        self.spells = spells
        self._effects: dict[int, Effect] = {e.index: e for e in entry.effects}
        self._rank = 1
        self._outcomes: list[TokenOutcome] = []

    # ------------------------------------------------------------------ public

    def render(self, rank: int) -> RenderedRank:
        if not 1 <= rank <= self.entry.max_rank:
            raise ValueError(f"entry {self.entry.id}: rank {rank} outside 1..{self.entry.max_rank}")
        self._rank = rank
        self._outcomes = []
        template = self.entry.description_template
        if template is None:
            gap = TooltipGap(
                category="unclassified",
                classification="missingData",
                fragment="(no template)",
                token="(no template)",
                reason="talent-data.v1 has no descriptionTemplate for this entry",
            )
            return RenderedRank(_rank(rank, "unresolved", [], [gap]), ())
        try:
            nodes = parse(template)
        except TemplateSyntaxError as exc:
            gap = TooltipGap(
                category="unclassified",
                classification="unsupported",
                fragment=template,
                token=exc.token,
                reason=f"template syntax outside the supported grammar: {exc}",
            )
            self._outcomes.append(TokenOutcome("unclassified", exc.token, "unsupported"))
            return RenderedRank(_rank(rank, "unresolved", [], [gap]), tuple(self._outcomes))

        output = _Output()
        context = _Context(spell_id=self.entry.spell_id, stack=(self.entry.spell_id,), record=True)
        self._render_nodes(nodes, context, output, _PluralState())
        segments = _normalize(output.segments)
        shown = [s for s in segments if s[0] != "gap"]
        status: RankStatus = (
            "resolved" if not output.gaps else ("partial" if shown else "unresolved")
        )
        if status == "unresolved":
            segments = []
        return RenderedRank(
            _rank(rank, status, segments, output.gaps),
            tuple(self._outcomes),
        )

    # ------------------------------------------------------------------ nodes

    def _record(self, context: _Context, category: TokenCategory, token: str, out: Outcome) -> None:
        if context.record:
            self._outcomes.append(TokenOutcome(category, token, out))

    def _render_nodes(
        self, nodes: Sequence[Node], context: _Context, output: _Output, plural: _PluralState
    ) -> None:
        for node in nodes:
            match node:
                case Text():
                    output.text(node.text)
                case LineBreak():
                    self._record(context, "line-break", node.raw, "resolved")
                    output.text("\n")
                case Color():
                    self._record(context, "color", node.raw, "resolved")
                case _:
                    self._render_token(node, context, output, plural)

    def _render_token(
        self, node: Node, context: _Context, output: _Output, plural: _PluralState
    ) -> None:
        category = category_of(node)
        raw = getattr(node, "raw", "")
        numeric = isinstance(node, Scaled | Expression) or (
            isinstance(node, Ref) and node.letter != "d"
        )
        try:
            self._emit(node, context, output, plural)
        except Failure as failure:
            if numeric:
                plural.value, plural.failure = None, failure
            self._record(context, category, raw, failure.classification)
            output.gap(failure, raw)
        else:
            self._record(context, category, raw, "resolved")

    def _emit(self, node: Node, context: _Context, output: _Output, plural: _PluralState) -> None:
        """Append the rendering of one token, or raise :class:`Failure`."""
        match node:
            case Conditional():
                self._conditional(node, context, output, plural)
            case Nested():
                output.segments.extend(self._nested(node, context))
            case Plural():
                word = self._plural(node, plural)
                if word:
                    output.value(word)
            case Ref() if node.letter == "d":
                output.value(self._duration_text(node, context))
            case Ref() | Scaled() | Expression():
                if isinstance(node, Ref):
                    value, text = self._bare_ref(node, context)
                elif isinstance(node, Scaled):
                    value, text = self._scaled(node, context)
                else:
                    value, text = self._expression(node, context)
                output.value(text)
                plural.value, plural.failure = value, None
            case NamedVariable():
                raise self._named_failure(node)
            case DescriptionVariable():
                raise self._variable_failure(node, context)
            case Text() | LineBreak() | Color():
                raise AssertionError("plain nodes are rendered by _render_nodes")

    # ------------------------------------------------------------------ effect values

    def _effect_row(self, ref: Ref, spell_id: int, index0: int) -> t.SpellEffectValues:
        rows = self.spells.effect_rows(spell_id, index0)
        if not rows:
            exists = spell_id in self.spells.descriptions
            raise Failure(
                ref.category,
                "missingData",
                ref.raw,
                f"spell {spell_id} has no SpellEffect row for effect index {index0} "
                f"(DifficultyID 0) in {self.spells.version}"
                + ("" if exists else f"; spell {spell_id} does not exist in this build"),
            )
        if len(rows) > 1:
            raise Failure(
                ref.category,
                "unsupported",
                ref.raw,
                f"spell {spell_id} has {len(rows)} default-difficulty rows for effect "
                f"index {index0}",
            )
        return rows[0]

    def _effect_index(self, ref: Ref, spell_id: int, relevant: str) -> int:
        """0-based effect index of a reference. ``$s1`` reads EffectIndex 0. A reference
        without an index is only accepted when the spell has exactly one relevant effect
        and it is EffectIndex 0, so both readings ("first effect" and "the effect") agree."""
        if ref.index is not None:
            if ref.index < 1:
                raise Failure(ref.category, "unsupported", ref.raw, "effect index 0 in a reference")
            return ref.index - 1
        rows = self.spells.default_effects(spell_id)
        if relevant == "period":
            rows = [r for r in rows if r.aura_period > 0]
        candidates = sorted({r.effect_index for r in rows})
        if candidates != [0]:
            raise Failure(
                ref.category,
                "unsupported",
                ref.raw,
                f"{ref.raw} names no effect and spell {spell_id} has "
                f"{len(candidates)} candidate effects {candidates}",
            )
        return 0

    def _caster_independent(self, ref: Ref, row: t.SpellEffectValues) -> None:
        """Refuse effect values the client scales by the character or a roll."""

        def context(reason: str) -> Failure:
            return Failure(ref.category, "contextDependent", ref.raw, reason)

        where = f"spell {row.spell_id} effect {row.effect_index}"
        if row.real_points_per_level:
            raise context(
                f"{where}: EffectRealPointsPerLevel={row.real_points_per_level:g}, the amount "
                "scales with the caster's level"
            )
        if row.scaling_class or row.coefficient:
            raise context(f"{where}: ScalingClass/Coefficient scale the amount with level")
        if row.bonus_coefficient_from_ap:
            raise context(
                f"{where}: BonusCoefficientFromAP={row.bonus_coefficient_from_ap:g}, the amount "
                "includes the caster's attack power"
            )
        is_amount = row.effect in AMOUNT_EFFECTS or row.effect_aura in AMOUNT_AURAS
        if row.bonus_coefficient and (is_amount or row.bonus_coefficient != 1):
            raise context(
                f"{where}: EffectBonusCoefficient={row.bonus_coefficient:g} on a "
                f"{'damage/healing/absorb amount' if is_amount else 'value'}, the amount "
                "includes the caster's spell power"
            )
        if row.points_per_resource or row.resource_coefficient:
            raise context(f"{where}: the amount scales with a resource (e.g. combo points)")
        if row.variance:
            raise Failure(
                ref.category,
                "unsupported",
                ref.raw,
                f"{where}: Variance={row.variance:g}, the client rolls a range whose tooltip "
                "display is not proven",
            )
        if (
            row.group_size_base_points_coefficient != 1
            or row.pvp_multiplier != 1
            or row.chain_amplitude != 1
        ):
            raise Failure(
                ref.category,
                "unsupported",
                ref.raw,
                f"{where}: a non-neutral group-size/PvP/chain multiplier",
            )

    def _effect_value(self, ref: Ref, context: _Context) -> Fraction:
        """The signed value of ``$sN``/``$mN`` (also inside expressions)."""
        spell_id = ref.spell_id if ref.spell_id is not None else context.spell_id
        index0 = self._effect_index(ref, spell_id, "value")
        row = self._effect_row(ref, spell_id, index0)
        self._caster_independent(ref, row)
        if spell_id != self.entry.spell_id:
            return exact(row.base_points)
        effect = self._effects.get(index0)
        if effect is None:
            raise Failure(
                ref.category,
                "missingData",
                ref.raw,
                f"talent-data.v1 has no effect {index0} for spell {spell_id}",
            )
        if effect.base_points is not None and exact(effect.base_points) != exact(row.base_points):
            raise ValueError(
                f"talent-data.v1 entry {self.entry.id} effect {index0} basePoints "
                f"{effect.base_points} != SpellEffect {row.base_points}: the dataset and the "
                "DB2 tables are not the same build"
            )
        if effect.per_rank_status == "resolved":
            values = {v.rank: v.value for v in effect.per_rank or ()}
            if self._rank not in values:
                raise ValueError(f"entry {self.entry.id}: resolved effect {index0} lacks a rank")
            return exact(values[self._rank])
        if effect.per_rank_status == "none":
            assert effect.base_points is not None
            return exact(effect.base_points)
        raise Failure(
            ref.category,
            "unsupported",
            ref.raw,
            f"the per-rank value of effect {index0} is {effect.per_rank_status!r} in "
            "talent-data.v1",
        )

    # ------------------------------------------------------------------ other kinds

    def _duration_ms(self, ref: Ref, spell_id: int) -> int:
        rows = self.spells.misc_rows(spell_id)
        if not rows:
            raise Failure(
                ref.category,
                "missingData",
                ref.raw,
                f"spell {spell_id} has no SpellMisc row (DifficultyID 0) in "
                f"{self.spells.version}, so it has no duration",
            )
        indices = {row.duration_index for row in rows}
        if len(indices) != 1:
            raise Failure(
                ref.category,
                "unsupported",
                ref.raw,
                f"spell {spell_id} has SpellMisc rows with different DurationIndex {indices}",
            )
        index = indices.pop()
        if index == 0:
            raise Failure(ref.category, "missingData", ref.raw, f"spell {spell_id} has no duration")
        duration = self.spells.durations.get(index)
        if duration is None:
            raise Failure(
                ref.category, "missingData", ref.raw, f"SpellDuration {index} does not exist"
            )
        if duration.duration <= 0:
            raise Failure(
                ref.category,
                "unsupported",
                ref.raw,
                f"SpellDuration {index} is {duration.duration} (no fixed duration)",
            )
        if duration.max_duration != duration.duration or duration.duration_per_resource:
            raise Failure(
                ref.category,
                "contextDependent",
                ref.raw,
                f"SpellDuration {index} varies ({duration.duration}..{duration.max_duration} ms, "
                f"{duration.duration_per_resource} per resource)",
            )
        return duration.duration

    def _duration_text(self, ref: Ref, context: _Context) -> str:
        if ref.index is not None:
            raise Failure(ref.category, "unsupported", ref.raw, "an indexed duration reference")
        spell_id = ref.spell_id if ref.spell_id is not None else context.spell_id
        milliseconds = self._duration_ms(ref, spell_id)
        text = self.spells.duration_formats.render(milliseconds)
        if text is None:
            raise Failure(
                ref.category,
                "unsupported",
                ref.raw,
                f"a duration of {milliseconds} ms has no exact rendering in the build's "
                "duration strings",
            )
        return text

    def _bare_number(self, ref: Ref, value: Fraction) -> tuple[Fraction, str]:
        text = format_bare(value)
        if text is None:
            raise Failure(
                ref.category,
                "unsupported",
                ref.raw,
                f"the value {float(value):g} needs more than two decimals; its display is "
                "not proven",
            )
        return value, text

    def _bare_ref(self, ref: Ref, context: _Context) -> tuple[Fraction, str]:
        spell_id = ref.spell_id if ref.spell_id is not None else context.spell_id
        match ref.letter:
            case "s" | "m":
                # A stand-alone effect value is written without its sign: all 113 negative
                # stand-alone values of 1.60.1.70009 sit in "reduces ... by", "less", "lower"
                # contexts (see docs/talent-tooltips.md).
                return self._bare_number(ref, abs(self._effect_value(ref, context)))
            case "o":
                return self._bare_number(ref, abs(self._periodic_total(ref, context, spell_id)))
            case "a":
                return self._bare_number(ref, self._radius(ref, spell_id))
            case "t":
                index0 = self._effect_index(ref, spell_id, "period")
                row = self._effect_row(ref, spell_id, index0)
                if row.aura_period <= 0:
                    raise Failure(
                        ref.category,
                        "missingData",
                        ref.raw,
                        f"spell {spell_id} effect {index0} has no EffectAuraPeriod",
                    )
                return self._bare_number(ref, Fraction(row.aura_period, 1000))
            case _:
                return self._bare_number(ref, self._proc_value(ref, spell_id))

    def _periodic_total(self, ref: Ref, context: _Context, spell_id: int) -> Fraction:
        index0 = self._effect_index(ref, spell_id, "period")
        row = self._effect_row(ref, spell_id, index0)
        if row.aura_period <= 0:
            raise Failure(
                ref.category,
                "missingData",
                ref.raw,
                f"spell {spell_id} effect {index0} is not periodic (no EffectAuraPeriod)",
            )
        try:
            per_tick = self._effect_value(replace(ref, letter="s", index=index0 + 1), context)
        except Failure as failure:
            raise Failure(ref.category, failure.classification, ref.raw, failure.reason) from None
        duration = self._duration_ms(ref, spell_id)
        if duration % row.aura_period:
            raise Failure(
                ref.category,
                "unsupported",
                ref.raw,
                f"duration {duration} ms is not a whole number of {row.aura_period} ms ticks",
            )
        return per_tick * (duration // row.aura_period)

    def _radius(self, ref: Ref, spell_id: int) -> Fraction:
        if ref.index is None:
            raise Failure(ref.category, "unsupported", ref.raw, "a radius without effect index")
        row = self._effect_row(ref, spell_id, ref.index - 1)
        first, second = row.radius_index_0, row.radius_index_1
        if first == 0 or second not in (0, first):
            raise Failure(
                ref.category,
                "unsupported",
                ref.raw,
                f"spell {spell_id} effect {ref.index - 1} has EffectRadiusIndex "
                f"[{first}, {second}]; which one {ref.raw} reads is only proven when the "
                "first is set and the second is empty or equal",
            )
        radius = self.spells.radii.get(first)
        if radius is None:
            raise Failure(ref.category, "missingData", ref.raw, f"SpellRadius {first} is missing")
        if radius.radius_per_level:
            raise Failure(
                ref.category,
                "contextDependent",
                ref.raw,
                f"SpellRadius {first} grows with the caster's level",
            )
        if exact(radius.radius) != exact(radius.radius_max) or radius.radius_min:
            raise Failure(
                ref.category,
                "unsupported",
                ref.raw,
                f"SpellRadius {first} is a range ({radius.radius_min:g}..{radius.radius_max:g})",
            )
        return exact(radius.radius)

    def _proc_value(self, ref: Ref, spell_id: int) -> Fraction:
        if ref.index is not None:
            raise Failure(ref.category, "unsupported", ref.raw, "an indexed proc reference")
        rows = self.spells.aura_option_rows(spell_id)
        if len(rows) != 1:
            raise Failure(
                ref.category,
                "missingData" if not rows else "unsupported",
                ref.raw,
                f"spell {spell_id} has {len(rows)} SpellAuraOptions rows (DifficultyID 0)",
            )
        row = rows[0]
        if ref.letter == "h":
            if row.spell_procs_per_minute_id:
                raise Failure(
                    ref.category,
                    "unsupported",
                    ref.raw,
                    f"spell {spell_id} procs per minute (SpellProcsPerMinute "
                    f"{row.spell_procs_per_minute_id}); $h of a PPM proc is not proven",
                )
            if not 1 <= row.proc_chance <= 100:
                raise Failure(
                    ref.category,
                    "unsupported",
                    ref.raw,
                    f"SpellAuraOptions.ProcChance={row.proc_chance} is not a percentage",
                )
            return Fraction(row.proc_chance)
        value = row.proc_charges if ref.letter == "n" else row.cumulative_aura
        column = "ProcCharges" if ref.letter == "n" else "CumulativeAura"
        if value <= 0:
            raise Failure(
                ref.category,
                "missingData",
                ref.raw,
                f"SpellAuraOptions.{column} of spell {spell_id} is {value}",
            )
        return Fraction(value)

    # ------------------------------------------------------------------ composites

    def _scaled(self, node: Scaled, context: _Context) -> tuple[Fraction, str]:
        if node.factor == 0:
            raise Failure("scaled-value", "unsupported", node.raw, "a scale factor of 0")
        try:
            value = self._effect_value(node.ref, context)
            result = value / node.factor if node.operator == "/" else value * node.factor
            # Same sign rule as a stand-alone value; "$/1000;s1 sec" with -5000 -> "5 sec".
            return self._bare_number(node.ref, abs(result))
        except Failure as failure:
            raise Failure(
                "scaled-value", failure.classification, node.raw, failure.reason
            ) from None

    def _expression(self, node: Expression, context: _Context) -> tuple[Fraction, str]:
        if node.tree is None:
            raise Failure(
                "arithmetic", "unsupported", node.raw, node.error or "unsupported expression"
            )
        failures: list[Failure] = []
        value = self._evaluate(node.tree, context, failures)
        if failures or value is None:
            first = failures[0]
            raise Failure(first.category, first.classification, first.token, first.reason)
        if value < 0:
            raise Failure(
                "arithmetic",
                "unsupported",
                node.raw,
                f"the expression evaluates to {float(value):g}; how the client shows a "
                "negative result is not proven",
            )
        if node.precision is not None:
            text = format_fixed(value, node.precision)
            rounded = round_fixed(value, node.precision)
            if text is None or rounded is None:
                raise Failure(
                    "arithmetic",
                    "unsupported",
                    node.raw,
                    f"{value} lies exactly between two {node.precision}-decimal values; the "
                    "client's tie rounding is not proven",
                )
            return rounded, text
        if value.denominator != 1:
            raise Failure(
                "arithmetic",
                "unsupported",
                node.raw,
                f"the expression evaluates to {float(value):g} without a precision suffix; "
                "how the client rounds it is not proven",
            )
        return value, str(value.numerator)

    def _evaluate(
        self, node: ExprNode, context: _Context, failures: list[Failure]
    ) -> Fraction | None:
        """Signed exact evaluation. Every leaf is evaluated (and recorded) once, so the
        coverage report sees each token even when a sibling fails."""
        match node:
            case Number():
                return node.value
            case Binary():
                left = self._evaluate(node.left, context, failures)
                right = self._evaluate(node.right, context, failures)
                if left is None or right is None:
                    return None
                if node.operator == "+":
                    return left + right
                if node.operator == "-":
                    return left - right
                if node.operator == "*":
                    return left * right
                if right == 0:
                    failures.append(Failure("arithmetic", "unsupported", "/0", "division by zero"))
                    return None
                return left / right
            case _:
                try:
                    value = self._expression_leaf(node, context)
                except Failure as failure:
                    failures.append(failure)
                    self._record(context, category_of(node), node.raw, failure.classification)
                    return None
                self._record(context, category_of(node), node.raw, "resolved")
                return value

    def _expression_leaf(
        self, node: Ref | NamedVariable | DescriptionVariable, context: _Context
    ) -> Fraction:
        match node:
            case NamedVariable():
                raise self._named_failure(node)
            case DescriptionVariable():
                raise self._variable_failure(node, context)
            case Ref() if node.letter in "sm":
                return self._effect_value(node, context)
            case Ref() if node.letter == "d":
                raise Failure(
                    node.category,
                    "unsupported",
                    node.raw,
                    f"{node.raw} inside an expression: whether it counts seconds or "
                    "milliseconds there is not proven",
                )
            case _:
                raise Failure(
                    node.category,
                    "unsupported",
                    node.raw,
                    f"{node.raw} inside an expression is not supported",
                )

    def _named_failure(self, node: NamedVariable) -> Failure:
        reason = (
            f"named variable ${node.name} has no source in the build's DB2 tables "
            "(a client-side value)"
        )
        if node.name == "proccooldown":
            reason += (
                "; SpellAuraOptions.ProcCategoryRecovery is a plausible source but is not "
                "proven to be what the client substitutes"
            )
        return Failure("named-variable", "unsupported", node.raw, reason)

    def _variable_failure(self, node: DescriptionVariable, context: _Context) -> Failure:
        definitions = self.spells.description_variables.get(context.spell_id, ())
        defining = [d for d in definitions if re.search(rf"\${node.name}=", d.variables)]
        if not defining:
            return Failure(
                "description-variable",
                "missingData",
                node.raw,
                f"spell {context.spell_id} defines no description variable {node.raw}",
            )
        found = sorted(
            {
                label
                for definition in defining
                for marker, label in _STATE_MARKERS
                if marker.search(definition.variables)
            }
        )
        ids = ", ".join(str(d.id) for d in defining)
        if found:
            return Failure(
                "description-variable",
                "contextDependent",
                node.raw,
                f"{node.raw} is defined in SpellDescriptionVariables {ids} from "
                + ", ".join(found),
            )
        return Failure(
            "description-variable",
            "unsupported",
            node.raw,
            f"{node.raw} is defined in SpellDescriptionVariables {ids}; description variables "
            "are not evaluated",
        )

    def _plural(self, node: Plural, plural: _PluralState) -> str:
        if plural.failure is not None:
            raise Failure(
                "plural",
                plural.failure.classification,
                node.raw,
                f"the number {node.raw} agrees with is unresolved ({plural.failure.token})",
            )
        if plural.value is None:
            raise Failure("plural", "unsupported", node.raw, "no number precedes the plural")
        return node.singular if plural.value == 1 else node.plural

    def _conditional(
        self, node: Conditional, context: _Context, output: _Output, plural: _PluralState
    ) -> None:
        """A client-side condition is never evaluated. When every branch renders to the
        same text (e.g. only the colour differs), that text is shown - no branch is chosen.
        Otherwise the conditional is a gap."""
        rendered: list[tuple[_Output, _PluralState]] = []
        for branch in node.branches:
            branch_output = _Output()
            branch_plural = _PluralState(plural.value, plural.failure)
            self._render_nodes(branch, context, branch_output, branch_plural)
            rendered.append((branch_output, branch_plural))
        if len(rendered) < 2:
            raise Failure(
                "conditional",
                "unsupported",
                node.raw,
                "a conditional with a single branch has an unproven empty alternative",
            )
        if any(o.gaps for o, _ in rendered):
            first = next(g for o, _ in rendered for g in o.gaps)
            raise Failure(first.category, first.classification, first.token, first.reason)
        texts = {tuple(_normalize(o.segments)) for o, _ in rendered}
        if len(texts) != 1:
            raise Failure(
                "conditional",
                "contextDependent",
                node.raw,
                f"client-side condition ${'?'}{node.condition} selects between "
                f"{len(rendered)} different texts; the character's state is unknown",
            )
        output.segments.extend(rendered[0][0].segments)
        plural.value, plural.failure = rendered[0][1].value, rendered[0][1].failure

    def _nested(self, node: Nested, context: _Context) -> list[tuple[str, str]]:
        if node.kind != "spelltooltip":
            raise Failure("nested-tooltip", "unsupported", node.raw, f"unknown $@{node.kind}")
        if node.spell_id in context.stack:
            raise Failure(
                "nested-tooltip",
                "unsupported",
                node.raw,
                f"nested tooltip cycle {' -> '.join(map(str, (*context.stack, node.spell_id)))}",
            )
        if len(context.stack) > MAX_NESTING_DEPTH:
            raise Failure(
                "nested-tooltip",
                "unsupported",
                node.raw,
                f"nesting deeper than {MAX_NESTING_DEPTH} levels",
            )
        description = self.spells.descriptions.get(node.spell_id)
        if description is None:
            raise Failure(
                "nested-tooltip",
                "missingData",
                node.raw,
                f"spell {node.spell_id} does not exist in {self.spells.version}",
            )
        try:
            nodes = parse(description)
        except TemplateSyntaxError as exc:
            raise Failure(
                "nested-tooltip",
                "unsupported",
                node.raw,
                f"spell {node.spell_id}'s description uses unsupported syntax: {exc}",
            ) from exc
        nested_output = _Output()
        nested_context = _Context(
            spell_id=node.spell_id, stack=(*context.stack, node.spell_id), record=False
        )
        self._render_nodes(nodes, nested_context, nested_output, _PluralState())
        if nested_output.gaps:
            inner = nested_output.gaps[0]
            raise Failure(
                inner.category,
                inner.classification,
                inner.token,
                f"in the nested tooltip of spell {node.spell_id}: {inner.reason}",
            )
        return nested_output.segments


# --------------------------------------------------------------------------- assembly


def _rank(
    rank: int, status: RankStatus, segments: list[tuple[str, str]], gaps: list[TooltipGap]
) -> TooltipRank:
    return TooltipRank(
        rank=rank,
        status=status,
        segments=[TooltipSegment(kind=kind, text=text) for kind, text in segments],  # type: ignore[arg-type]
        gaps=gaps,
    )


def _normalize(segments: list[tuple[str, str]]) -> list[tuple[str, str]]:
    """Presentation normalization: merge text, attach a following ``%`` to its value,
    collapse runs of spaces, drop spaces around line breaks, at most one empty line, and
    no leading/trailing whitespace. Changes whitespace only - never words or values."""
    merged: list[list[str]] = []
    for kind, text in segments:
        if kind == "text" and merged and merged[-1][0] == "text":
            merged[-1][1] += text
        else:
            merged.append([kind, text])
    for index in range(len(merged) - 1):
        if (
            merged[index][0] == "value"
            and merged[index + 1][0] == "text"
            and merged[index + 1][1].startswith("%")
        ):
            merged[index][1] += "%"
            merged[index + 1][1] = merged[index + 1][1][1:]
    for item in merged:
        if item[0] == "text":
            text = re.sub(r"[ \t]+", " ", item[1])
            text = re.sub(r" *\n *", "\n", text)
            item[1] = re.sub(r"\n{3,}", "\n\n", text)
    if merged and merged[0][0] == "text":
        merged[0][1] = merged[0][1].lstrip()
    if merged and merged[-1][0] == "text":
        merged[-1][1] = merged[-1][1].rstrip()
    return [(kind, text) for kind, text in merged if kind == "gap" or text]
