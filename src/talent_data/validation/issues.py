"""Typed validation issue model.

A validation problem is machine-readable first (``severity`` + stable ``code`` + structured
location) and human-readable second (``message``). Downstream automation must branch on
``code``, never parse ``message``; the message wording may change, codes may not.

Every code emitted by the validators is listed in :data:`KNOWN_WARNING_CODES` /
:data:`KNOWN_ERROR_CODES`. A code appearing in real client data that is absent from the
"known anomalies" table in ``docs/forever-dataset.md`` means an *uninvestigated* problem:
it must not be silently accepted as normal.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

Severity = Literal["error", "warning"]

# Warnings that real Forever client data has been shown to produce, with the reason each is
# considered a tolerable data feature rather than a pipeline defect (details + evidence in
# docs/forever-dataset.md). Anything outside this set is unreviewed.
KNOWN_WARNING_CODES: dict[str, str] = {
    "OFF_GRID_NODE": "node not on the fitted 600-unit grid (deliberate nudge or a mis-placed "
    "duplicate node in client data; raw x/y stay authoritative)",
    "DUPLICATE_SPELL_IDENTITY": "the same SpellID is used by two nodes of one tree "
    "(mis-placed duplicate node in client data; ambiguous identity hint)",
    "SUFFICIENT_PREREQUISITE_CYCLE": "mutual SufficientForAvailability edges (observed on "
    "Hunter; playable only under OR semantics, preserved as-is)",
    "INCOMPLETE_RANK_CURVE": "rank curve covers fewer ranks than MaxRanks (observed on Rogue "
    "Cutthroat; the client data itself is incomplete)",
    "UNRESOLVED_RANK_CURVE": "a rank curve exists but its semantics are not interpreted",
    "UNRESOLVED_CONDITION": "a TraitCond whose semantics the pipeline cannot prove (observed "
    "on Rogue: cond 50984 combines a node reference with a group/spend gate; unknown "
    "combinations are surfaced, never silently flattened or dropped)",
    "SHARED_GRID_CELL": "two nodes map to the same derived grid cell",
    "SECTIONS_UNRESOLVED": "talent tabs could not be derived from the gate groups",
}

KNOWN_ERROR_CODES: frozenset[str] = frozenset(
    {
        "NO_TREES",
        "DUPLICATE_TREE_ID",
        "DUPLICATE_NODE_ID",
        "DUPLICATE_ENTRY_ID",
        "TREE_HAS_NO_NODES",
        "SHARED_POSITION",
        "GRID_RC_INCONSISTENT",
        "NODE_SECTION_INCONSISTENT",
        "SECTIONS_NOT_PARTITION",
        "SECTION_INDEX_MISMATCH",
        "NODE_WITHOUT_ENTRIES",
        "INVALID_MAX_RANK",
        "INVALID_ENTRY_MAX_RANK",
        "DANGLING_PREREQUISITE",
        "SELF_PREREQUISITE",
        "REQUIRED_PREREQUISITE_CYCLE",
        "UNLOCK_COUNTS_UNKNOWN_NODES",
        "INVALID_UNLOCK_POINTS",
        "MISSING_SPELL_REFERENCE",
        "MISSING_NAME",
        "DANGLING_VISUAL_EDGE",
        "CONTENT_HASH_MISMATCH",
    }
)


@dataclass(frozen=True)
class ValidationIssue:
    """One validation finding. ``__str__`` returns the human-readable message."""

    severity: Severity
    code: str
    message: str
    tree_id: int | None = None
    node_id: int | None = None
    entry_id: int | None = None
    effect_index: int | None = None

    def __str__(self) -> str:
        return f"[{self.code}] {self.message}"
