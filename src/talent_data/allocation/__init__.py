"""Forever talent allocation engine (M2): reference implementation and executable spec.

    from talent_data.allocation import TalentAllocationEngine
    engine = TalentAllocationEngine(document)          # a normalized TalentDataDocument
    state = engine.empty_state()
    result = engine.spend(state, node_id)               # AllocationResult(decision, state)
    result.decision.allowed, result.decision.reason_codes
    engine.validate(result.state).valid

See docs/talent-allocation-semantics.md for the rules and their evidence.
"""

from talent_data.allocation.engine import AllocationEngineError, TalentAllocationEngine
from talent_data.allocation.models import (
    AllocationDecision,
    AllocationIssue,
    AllocationIssueCode,
    AllocationResult,
    BuildFile,
    BuildState,
    BuildValidation,
    NodeAllocation,
)

__all__ = [
    "AllocationDecision",
    "AllocationEngineError",
    "AllocationIssue",
    "AllocationIssueCode",
    "AllocationResult",
    "BuildFile",
    "BuildState",
    "BuildValidation",
    "NodeAllocation",
    "TalentAllocationEngine",
]
