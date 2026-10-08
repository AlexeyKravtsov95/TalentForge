"""Exact numbers and their display.

Every value is a :class:`fractions.Fraction` built from the decimal text of the source
(``"0.89999997616"`` stays exactly that), so no binary floating-point rounding can change a
rendered digit. A value that cannot be displayed under a proven rule returns ``None`` and
becomes a gap; nothing is rounded silently.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from fractions import Fraction

from talent_data.db2 import tables as t

# Bare numbers ($s1, $/1000;s1, $t1, $a1 ...) print integers as integers and short exact
# decimals with as many places as they need, up to this many. Anything longer (a float32
# artefact such as 2.29999995232, a third) has no proven display and is refused.
MAX_BARE_DECIMALS = 2


def exact(value: int | float | str) -> Fraction:
    """The exact decimal value of a source number (floats via their shortest repr)."""
    if isinstance(value, bool):
        raise TypeError("a boolean is not a number")
    if isinstance(value, int):
        return Fraction(value)
    return Fraction(str(value) if isinstance(value, float) else value)


def _decimal_string(value: Fraction, places: int) -> str:
    """``value`` (already an exact multiple of 10**-places) with exactly ``places``."""
    scaled = value * 10**places
    assert scaled.denominator == 1
    negative = scaled < 0
    digits = str(abs(scaled.numerator)).rjust(places + 1, "0")
    text = digits if places == 0 else f"{digits[:-places]}.{digits[-places:]}"
    return f"-{text}" if negative else text


def format_bare(value: Fraction) -> str | None:
    """Integer, or the shortest exact decimal with at most MAX_BARE_DECIMALS places."""
    for places in range(MAX_BARE_DECIMALS + 1):
        if (value * 10**places).denominator == 1:
            return _decimal_string(value, places)
    return None


def round_fixed(value: Fraction, places: int) -> Fraction | None:
    """``value`` rounded to ``places`` decimals; ``None`` on an exact tie, whose direction
    (half-up, half-even, ...) the client does not prove."""
    scaled = value * 10**places
    floor = scaled.numerator // scaled.denominator
    remainder = scaled - floor
    if remainder == Fraction(1, 2):
        return None
    rounded = floor + (1 if remainder > Fraction(1, 2) else 0)
    return Fraction(rounded, 10**places)


def format_fixed(value: Fraction, places: int) -> str | None:
    """``${...}.N``: exactly ``places`` decimals (``None`` on an unprovable tie)."""
    rounded = round_fixed(value, places)
    return None if rounded is None else _decimal_string(rounded, places)


# --------------------------------------------------------------------------- durations

# GlobalStrings of the build that spell durations are written with. The renderer uses the
# build's own strings instead of hard-coded English units.
DURATION_TAGS = (
    "INT_SPELL_DURATION_HOURS",
    "INT_SPELL_DURATION_MIN",
    "INT_SPELL_DURATION_SEC",
    "SPELL_DURATION_SEC",
)

_FORMAT = re.compile(r"%(d|\.1f) (.+)")
_PLURAL_MARKUP = re.compile(r"\|4([^:;|]*):([^;|]*);")


class DurationFormatError(ValueError):
    """The build's duration strings are not in a supported printf shape."""


@dataclass(frozen=True)
class UnitFormat:
    tag: str
    conversion: str  # "d" (integer) or ".1f" (one decimal)
    unit: str  # may contain the client plural markup |4singular:plural;

    @classmethod
    def parse(cls, tag: str, text: str) -> UnitFormat:
        match = _FORMAT.fullmatch(text)
        if not match:
            raise DurationFormatError(f"GlobalStrings {tag} = {text!r} is not '%d <unit>'")
        unit = match.group(2)
        if "|" in _PLURAL_MARKUP.sub("", unit) or "%" in unit:
            raise DurationFormatError(f"GlobalStrings {tag} = {text!r} has unsupported markup")
        return cls(tag=tag, conversion=match.group(1), unit=unit)

    def render(self, amount: Fraction) -> str | None:
        if self.conversion == "d":
            if amount.denominator != 1:
                return None
            number = str(amount.numerator)
        else:
            if (amount * 10).denominator != 1:
                return None
            number = _decimal_string(amount, 1)
        unit = _PLURAL_MARKUP.sub(lambda m: m.group(1) if amount == 1 else m.group(2), self.unit)
        return f"{number} {unit}"


@dataclass(frozen=True)
class DurationFormats:
    """``$d`` display: the largest unit that expresses the duration exactly.

    Whole hours use INT_SPELL_DURATION_HOURS, whole minutes INT_SPELL_DURATION_MIN, whole
    seconds INT_SPELL_DURATION_SEC, tenths of a second SPELL_DURATION_SEC. Anything else
    (and a non-positive duration) has no rendering.
    """

    hours: UnitFormat
    minutes: UnitFormat
    seconds: UnitFormat
    tenths: UnitFormat

    @classmethod
    def from_global_strings(cls, rows: list[t.GlobalString]) -> DurationFormats:
        by_tag: dict[str, list[str]] = {}
        for row in rows:
            by_tag.setdefault(row.base_tag, []).append(row.text)
        texts: dict[str, str] = {}
        for tag in DURATION_TAGS:
            values = by_tag.get(tag, [])
            if len(values) != 1:
                raise DurationFormatError(f"GlobalStrings has {len(values)} rows for {tag}")
            texts[tag] = values[0]
        formats = cls(
            hours=UnitFormat.parse("INT_SPELL_DURATION_HOURS", texts["INT_SPELL_DURATION_HOURS"]),
            minutes=UnitFormat.parse("INT_SPELL_DURATION_MIN", texts["INT_SPELL_DURATION_MIN"]),
            seconds=UnitFormat.parse("INT_SPELL_DURATION_SEC", texts["INT_SPELL_DURATION_SEC"]),
            tenths=UnitFormat.parse("SPELL_DURATION_SEC", texts["SPELL_DURATION_SEC"]),
        )
        for unit_format, expected in (
            (formats.hours, "d"),
            (formats.minutes, "d"),
            (formats.seconds, "d"),
            (formats.tenths, ".1f"),
        ):
            if unit_format.conversion != expected:
                raise DurationFormatError(
                    f"GlobalStrings {unit_format.tag} uses %{unit_format.conversion}, "
                    f"expected %{expected}"
                )
        return formats

    def render(self, milliseconds: int) -> str | None:
        if milliseconds <= 0:
            return None
        if milliseconds % 3_600_000 == 0:
            return self.hours.render(Fraction(milliseconds, 3_600_000))
        if milliseconds % 60_000 == 0:
            return self.minutes.render(Fraction(milliseconds, 60_000))
        if milliseconds % 1000 == 0:
            return self.seconds.render(Fraction(milliseconds, 1000))
        if milliseconds % 100 == 0:
            return self.tenths.render(Fraction(milliseconds, 1000))
        return None
