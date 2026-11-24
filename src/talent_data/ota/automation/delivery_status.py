"""Delivery diagnostics, separate from successful scheduler execution."""

from typing import Literal

from talent_data.ota.automation.cli import StatusDocument
from talent_data.ota.automation.probe import ProbeResult
from talent_data.ota.automation.state import RequestState

DeliveryState = Literal["NO_WORK", "WAIT", "BLOCKED", "PUBLISHING", "PUBLIC_VERIFIED"]


def delivery_state(
    probe: ProbeResult | None, status: StatusDocument | None, *, failed: bool = False
) -> DeliveryState:
    if failed:
        return "BLOCKED"
    if status is not None:
        if status.outcome == "VERIFIED":
            return (
                "PUBLIC_VERIFIED"
                if status.reason == "PUBLIC_VERIFIED"
                and status.operations
                and all(op.state is RequestState.VERIFIED for op in status.operations)
                else "BLOCKED"
            )
        if status.outcome in {"BLOCKED", "AMBIGUOUS"}:
            return "BLOCKED"
        if status.outcome in {"READY", "PUBLISHED_NOT_VERIFIED"}:
            return "PUBLISHING"
        if status.outcome in {"DISABLED", "DRY_RUN", "WAIT"}:
            return "WAIT"
        if status.outcome == "NO_ACTION":
            return "NO_WORK"
    if probe is not None:
        if probe.reason == "DISABLED":
            return "WAIT"  # Disabled discovery has not established whether requests exist.
        if probe.action in {"NO_WORK", "WAIT", "BLOCKED"}:
            return probe.action
    return "BLOCKED"  # Missing/interrupted evidence is never a successful delivery.
