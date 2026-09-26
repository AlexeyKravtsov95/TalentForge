"""Derive a row/column grid from raw client layout coordinates.

Client layouts are mostly regular (600 units in Forever), but real data contains nodes nudged
by a few units (Paladin x=5030 next to 5020, Warlock y=2120 next to 2130) or placed between
columns (Hunter "Lightning Reflexes", +120). The grid is therefore fitted robustly:

* step    = most common distance between neighbouring distinct coordinates;
* residue = most common ``coordinate mod step`` (the grid's phase);
* index   = round((coordinate - residue) / step), shifted so the first used line is 0.

Raw coordinates stay authoritative in the output; the grid is a convenience for clients.
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Iterable
from dataclasses import dataclass
from itertools import pairwise


def _mode(counter: Counter[int]) -> int:
    # Most common value; ties broken towards the smaller value for determinism.
    return min(counter, key=lambda v: (-counter[v], v))


def dominant_step(groups: Iterable[Iterable[int]]) -> int:
    deltas: Counter[int] = Counter()
    for group in groups:
        values = sorted(set(group))
        deltas.update(b - a for a, b in pairwise(values))
    return _mode(deltas) if deltas else 1


@dataclass(frozen=True)
class Axis:
    step: int
    residue: int
    first: int

    @classmethod
    def fit(cls, values: Iterable[int], step: int) -> Axis:
        vals = list(values)
        residue = _mode(Counter(v % step for v in vals))
        first = min(round((v - residue) / step) for v in vals)
        return cls(step=step, residue=residue, first=first)

    def index(self, value: int) -> int:
        return round((value - self.residue) / self.step) - self.first

    def offset(self, value: int) -> int:
        """Distance of ``value`` from its grid line (0 when exactly on the grid)."""
        return value - (self.residue + (self.index(value) + self.first) * self.step)


@dataclass(frozen=True)
class Grid:
    columns: dict[int | None, Axis]  # per section (None = no section)
    rows: Axis

    def row(self, y: int) -> int:
        return self.rows.index(y)

    def column(self, section: int | None, x: int) -> int:
        return self.columns[section].index(x)

    def offset(self, section: int | None, x: int, y: int) -> tuple[int, int]:
        return self.columns[section].offset(x), self.rows.offset(y)


def fit_grid(points: Iterable[tuple[int | None, int, int]]) -> Grid:
    """Fit a grid to ``(section, x, y)`` points: shared steps, per-section column phase."""
    items = list(points)
    xs_by_section: dict[int | None, list[int]] = {}
    for section, x, _ in items:
        xs_by_section.setdefault(section, []).append(x)
    x_step = dominant_step(xs_by_section.values())
    ys = [y for _, _, y in items]
    return Grid(
        columns={sec: Axis.fit(xs, x_step) for sec, xs in xs_by_section.items()},
        rows=Axis.fit(ys, dominant_step([ys])),
    )
