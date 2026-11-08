"""Publication outcomes use the watcher's public proof, marker and durable delivery.

The notifications job must share ota-production-watch serialization. A status artifact
is strict data, never evidence of success; public bytes are always verified here.
"""

from __future__ import annotations

from collections.abc import Mapping
from datetime import datetime
from pathlib import Path

import httpx

from talent_data.notify.cli import Sender, _deliver, _Summary
from talent_data.notify.events import StageFailed
from talent_data.notify.production_watch import observe_production
from talent_data.notify.state import (
    Decision,
    ProductionDedup,
    baseline,
    decide_production,
    initial_production,
    load_dedup,
)
from talent_data.ota.automation.cli import StatusDocument
from talent_data.ota.s3_channels import PRODUCTION_PROFILE
from talent_data.ota.verify import TrustedKey


def notify_status(
    document: StatusDocument,
    *,
    state: Path,
    client: httpx.Client,
    keyring: Mapping[str, TrustedKey],
    now: datetime,
    send: Sender,
    summary: _Summary,
    run_url: str,
) -> str:
    if document.outcome in {"DISABLED", "DRY_RUN", "READY", "NO_ACTION"}:
        return "QUIET"
    previous = load_dedup(ProductionDedup, state)
    first = previous is None
    timestamp = now.isoformat()
    current = previous if previous is not None else initial_production(timestamp)
    observation = observe_production(
        client,
        origin=PRODUCTION_PROFILE.public_origin,
        keyring=keyring,
        game="forever",
        now=now,
        verify_files=True,
    )
    decision = decide_production(current, observation=observation, run_url=run_url, now=timestamp)
    if document.outcome in {"BLOCKED", "AMBIGUOUS", "PUBLISHED_NOT_VERIFIED"}:
        fingerprint = "ota-production/" + document.operation + "/" + document.stage
        events = []
        failures = current.failures_notified
        if fingerprint not in failures:
            events.append(
                StageFailed(
                    stage="production: " + document.stage,
                    reason=document.outcome + ": " + document.reason,
                    run_url=run_url,
                    fingerprint=fingerprint,
                )
            )
            failures = (*failures, fingerprint)
        # An outcome failure cannot claim a successful activation or occupy its marker.
        decision = Decision(
            tuple(events),
            current.model_copy(
                update={
                    "failures_notified": failures,
                    "updated_at": timestamp,
                }
            ),
        )
    else:
        decision = Decision(
            decision.events,
            decision.state.model_copy(
                update={
                    "failures_notified": tuple(
                        x
                        for x in decision.state.failures_notified
                        if not x.startswith("ota-production/" + document.operation + "/")
                    ),
                }
            ),
        )
    if first:
        decision = baseline(decision)
    return _deliver(decision, state, send, summary, first_run=first)
