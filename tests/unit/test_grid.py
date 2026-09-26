"""Grid fitting on coordinate patterns observed in real Forever 1.60.1.70009 trees."""

from __future__ import annotations

from talent_data.normalization.grid import Axis, dominant_step, fit_grid


def test_regular_grid() -> None:
    xs = [1020, 1620, 2220, 2820]
    axis = Axis.fit(xs, dominant_step([xs]))
    assert axis.step == 600
    assert [axis.index(x) for x in xs] == [0, 1, 2, 3]
    assert all(axis.offset(x) == 0 for x in xs)


def test_nudged_column_does_not_collapse_grid() -> None:
    # Paladin section 1: x=5030 next to 5020 (a gcd would give a 10-unit grid).
    xs = [5020, 5030, 5030, 5620, 6220, 6820]
    step = dominant_step([xs])
    axis = Axis.fit(xs, step)
    assert step == 600
    assert [axis.index(x) for x in xs] == [0, 0, 0, 1, 2, 3]
    assert axis.offset(5030) == 10


def test_nudged_first_row_does_not_shift_all_rows() -> None:
    # Warlock: one node at y=2120 while the grid is 2130 + 600k.
    ys = [2120, 2130, 2730, 3330, 3930, 4530, 5130, 5730]
    axis = Axis.fit(ys, dominant_step([ys]))
    assert axis.index(2120) == 0
    assert axis.index(5730) == 6
    assert axis.offset(2130) == 0
    assert axis.offset(2120) == -10


def test_sections_have_independent_column_phase() -> None:
    # Warrior: section origins 1020 / 5020 / 9080 are not on one global 600 grid.
    grid = fit_grid([(0, 1020, 2130), (0, 1620, 2130), (2, 9080, 2130), (2, 9680, 2730)])
    assert (grid.column(0, 1020), grid.column(2, 9080), grid.column(2, 9680)) == (0, 0, 1)
    assert grid.row(2730) == 1


def test_single_coordinate_axis() -> None:
    assert dominant_step([[42]]) == 1
    assert Axis.fit([42], 1).index(42) == 0
