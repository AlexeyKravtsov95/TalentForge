"""WoW client version parsing (``major.minor.patch.build``)."""

from __future__ import annotations

import re
from dataclasses import dataclass
from functools import total_ordering

_VERSION_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)\.(\d+)$")


@total_ordering
@dataclass(frozen=True)
class ClientVersion:
    major: int
    minor: int
    patch: int
    build: int

    @classmethod
    def parse(cls, text: str) -> ClientVersion:
        match = _VERSION_RE.match(text.strip())
        if not match:
            raise ValueError(f"not a WoW client version (expected a.b.c.build): {text!r}")
        return cls(*(int(part) for part in match.groups()))

    @property
    def branch(self) -> str:
        """``major.minor`` - e.g. ``1.60`` for WoW Forever."""
        return f"{self.major}.{self.minor}"

    def _key(self) -> tuple[int, int, int, int]:
        return (self.major, self.minor, self.patch, self.build)

    def __lt__(self, other: object) -> bool:
        if not isinstance(other, ClientVersion):
            return NotImplemented
        return self._key() < other._key()

    def __str__(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}.{self.build}"
