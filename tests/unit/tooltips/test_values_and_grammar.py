"""Exact number display and the template grammar (no evaluation by eval/exec)."""

from __future__ import annotations

from fractions import Fraction
from pathlib import Path

import pytest

from talent_data.db2 import tables as t
from talent_data.tooltips import grammar
from talent_data.tooltips.grammar import (
    Binary,
    Conditional,
    Expression,
    NamedVariable,
    Number,
    Plural,
    Ref,
    Scaled,
    TemplateSyntaxError,
    parse,
    parse_expression,
)
from talent_data.tooltips.values import (
    DurationFormatError,
    DurationFormats,
    exact,
    format_bare,
    format_fixed,
)


def test_exact_keeps_the_source_decimal() -> None:
    assert exact("0.89999997616") == Fraction(89999997616, 10**11)
    assert exact(2.5) == Fraction(5, 2)
    assert exact(-50) == -50


@pytest.mark.parametrize(
    ("value", "text"),
    [
        (Fraction(3), "3"),
        (Fraction(-1, 10), "-0.1"),
        (Fraction(11, 2), "5.5"),
        (Fraction(17, 100), "0.17"),
        (Fraction(1, 3), None),
        (exact("2.29999995232"), None),  # a float32 artefact is never rounded silently
    ],
)
def test_bare_numbers(value: Fraction, text: str | None) -> None:
    assert format_bare(value) == text


@pytest.mark.parametrize(
    ("value", "places", "text"),
    [
        (Fraction(1, 4), 2, "0.25"),
        (Fraction(1, 2), 2, "0.50"),
        (Fraction(2, 3), 2, "0.67"),
        (Fraction(4, 3), 2, "1.33"),
        (Fraction(11), 1, "11.0"),
        (Fraction(1, 8), 2, None),  # 0.125: half-up and half-even disagree -> refused
        (Fraction(1, 40), 1, "0.0"),
    ],
)
def test_precision(value: Fraction, places: int, text: str | None) -> None:
    assert format_fixed(value, places) == text


def duration_formats(**override: str) -> DurationFormats:
    texts = {
        "INT_SPELL_DURATION_HOURS": "%d |4hour:hrs;",
        "INT_SPELL_DURATION_MIN": "%d min",
        "INT_SPELL_DURATION_SEC": "%d sec",
        "SPELL_DURATION_SEC": "%.1f sec",
    } | override
    rows = [t.GlobalString(id=i, base_tag=k, text=v) for i, (k, v) in enumerate(texts.items())]
    return DurationFormats.from_global_strings(rows)


def test_durations_use_the_largest_exact_unit_of_the_build_strings() -> None:
    formats = duration_formats()
    assert formats.render(10_000) == "10 sec"
    assert formats.render(1_500) == "1.5 sec"
    assert formats.render(60_000) == "1 min"
    assert formats.render(1_800_000) == "30 min"
    assert formats.render(3_600_000) == "1 hour"
    assert formats.render(7_200_000) == "2 hrs"
    assert formats.render(1_250) is None
    assert formats.render(-1) is None


def test_unknown_duration_string_shapes_are_refused() -> None:
    with pytest.raises(DurationFormatError):
        duration_formats(INT_SPELL_DURATION_SEC="%s seconds")
    with pytest.raises(DurationFormatError):
        duration_formats(SPELL_DURATION_SEC="%d sec")
    with pytest.raises(DurationFormatError, match="0 rows"):
        DurationFormats.from_global_strings([])


def test_parse_recognises_every_observed_construct() -> None:
    nodes = parse(
        "a $s1 $12976d $/10;S1 ${$m1/-1000}.2 $lpoint:points; $?a5487|a9634[x][y] "
        "$@spelltooltip414924 $<mult> $rap |cFFFFFFFFw|r\r\n"
    )
    kinds = [type(n).__name__ for n in nodes if not isinstance(n, grammar.Text)]
    assert kinds == [
        "Ref",
        "Ref",
        "Scaled",
        "Expression",
        "Plural",
        "Conditional",
        "Nested",
        "DescriptionVariable",
        "NamedVariable",
        "Color",
        "Color",
        "LineBreak",
    ]
    scaled = next(n for n in nodes if isinstance(n, Scaled))
    assert scaled.ref == Ref(spell_id=None, letter="s", index=1, raw="$/10;S1")
    expression = next(n for n in nodes if isinstance(n, Expression))
    assert expression.precision == 2
    plural = next(n for n in nodes if isinstance(n, Plural))
    assert (plural.singular, plural.plural) == ("point", "points")
    conditional = next(n for n in nodes if isinstance(n, Conditional))
    assert conditional.condition == "a5487|a9634" and len(conditional.branches) == 2


def test_expressions_have_precedence_and_no_eval() -> None:
    tree = parse_expression("$s4-($m3)*2")
    assert isinstance(tree, Binary) and tree.operator == "-"
    assert isinstance(tree.right, Binary) and tree.right.operator == "*"
    assert parse_expression("$m1/-1000") == Binary(
        "/", Ref(None, "m", 1, "$m1"), Number(Fraction(-1000))
    )
    assert isinstance(parse_expression("$rap*2").left, NamedVariable)  # type: ignore[union-attr]
    for unsupported in ("__import__('os')", "$cond($gt(1,2),1,2)", "1^2", "-$m1", "(1"):
        with pytest.raises(TemplateSyntaxError):
            parse_expression(unsupported)


def test_unknown_markup_is_a_syntax_error() -> None:
    for template in ("a |Hitem:1|h b", "$!", "$q1", "${1", "$?a1[x"):
        with pytest.raises(TemplateSyntaxError):
            parse(template)


def test_the_renderer_never_uses_eval_or_exec() -> None:
    package = Path(grammar.__file__).parent
    for path in package.glob("*.py"):
        source = path.read_text(encoding="utf-8")
        assert "eval(" not in source and "exec(" not in source, path.name
