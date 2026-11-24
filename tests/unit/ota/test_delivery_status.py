"""A successful scheduler is not evidence of a delivered release."""

import pytest

from talent_data.ota.automation.cli import StatusDocument
from talent_data.ota.automation.probe import _result


@pytest.mark.parametrize(
    "action,reason,expected",
    [
        ("NO_WORK", "IDLE", "NO_WORK"),
        ("NO_WORK", "DISABLED", "WAIT"),
        ("WAIT", "APPROVAL_PENDING", "WAIT"),
        ("BLOCKED", "INPUT_INVALID", "BLOCKED"),
    ],
)
def test_probe_reports_actual_delivery_state(action, reason, expected):
    from talent_data.ota.automation.delivery_status import delivery_state

    assert delivery_state(_result(action, reason), None) == expected


@pytest.mark.parametrize(
    "outcome,reason,expected",
    [
        ("READY", "READY", "PUBLISHING"),
        ("PUBLISHED_NOT_VERIFIED", "PUBLIC_NOT_VERIFIED", "PUBLISHING"),
        ("AMBIGUOUS", "OUTCOME_UNKNOWN", "BLOCKED"),
        ("BLOCKED", "ASSESSMENT_FAILED", "BLOCKED"),
        ("VERIFIED", "PUBLIC_VERIFIED", "BLOCKED"),  # Empty aggregate proves no release.
    ],
)
def test_status_requires_real_public_proof(outcome, reason, expected):
    from talent_data.ota.automation.delivery_status import delivery_state

    document = StatusDocument(
        schema_version=1,
        kind="ota-production-status",
        command="publish",
        operation="publish",
        outcome=outcome,
        stage="PLANNING",
        reason=reason,
        writer_needed=False,
        operations=(),
    )
    assert delivery_state(None, document) == expected


@pytest.mark.parametrize(
    "outcome,reason", [("WAIT", "RUN_PENDING"), ("BLOCKED", "DELIVERY_STALLED")]
)
def test_wait_or_stall_notifies_once_even_without_an_existing_marker(tmp_path, outcome, reason):
    from datetime import UTC, datetime

    import httpx

    from talent_data.notify.automation import notify_status
    from talent_data.notify.cli import _Summary
    from talent_data.notify.telegram import SendResult, SendStatus

    document = StatusDocument(
        schema_version=1,
        kind="ota-production-status",
        command="preflight",
        operation="publish",
        outcome=outcome,
        stage="PLANNING",
        reason=reason,
        writer_needed=False,
        operations=(),
    )
    sent = []

    def send(message):
        sent.append(message)
        return SendResult(SendStatus.SENT, "TEST delivered")

    with httpx.Client(
        transport=httpx.MockTransport(
            lambda _: pytest.fail("WAIT must not announce production success")
        )
    ) as client:
        for _ in range(2):
            notify_status(
                document,
                state=tmp_path / "production.json",
                client=client,
                keyring={},
                now=datetime(2026, 11, 22, tzinfo=UTC),
                send=send,
                summary=_Summary(None),
                run_url="https://github.com/TEST/actions/runs/1",
            )
    assert len(sent) == 1 and f"{outcome}: {reason}" in sent[0]
