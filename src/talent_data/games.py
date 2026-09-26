"""Registry of supported game flavours.

A *game* is our domain notion ("forever"); how it maps onto Blizzard products and version
branches is configuration, not code, so adding e.g. Classic Era later is a new entry here.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class GameConfig:
    key: str
    display_name: str
    # Blizzard product codes (as used by Battle.net / TACT and mirrored by wago.tools) that can
    # carry builds of this game.
    products: tuple[str, ...]
    # ``major.minor`` version branch that identifies this game inside those products. Needed
    # because a shared product (e.g. ``wow_classic_beta``) can host several game flavours.
    version_branch: str


GAMES: dict[str, GameConfig] = {
    "forever": GameConfig(
        key="forever",
        display_name="Forever",
        # WoW: Forever beta currently ships through the Classic beta product.
        products=("wow_classic_beta",),
        version_branch="1.60",
    ),
}


def get_game(key: str) -> GameConfig:
    try:
        return GAMES[key]
    except KeyError:
        raise KeyError(f"unknown game {key!r}; supported: {', '.join(sorted(GAMES))}") from None
