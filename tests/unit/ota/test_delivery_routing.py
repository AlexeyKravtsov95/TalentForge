"""Trusted sequential routing treats probe bytes as data, never publication authority."""

import json
from pathlib import Path

import pytest

# ruff: noqa: F811
from talent_data.ota.automation.cli import StatusDocument, read_status
from talent_data.ota.automation.probe import ProbeCandidate, _result
from tests.unit.ota.test_automation_probe import pending  # noqa: F401


def hint(action="PUBLISH", count=1):
    candidates = tuple(
        ProbeCandidate(
            action=action,
            release_id=f"1.60.1.{71000 + i}-r1",
            request_digest="sha256:" + str(i + 1) * 64,
            input_fingerprint="sha256:" + str(i + 2) * 64,
        )
        for i in range(count)
    )
    return _result(action, "READY", head="a" * 40, candidates=candidates)


def document(argv, outcome="VERIFIED"):
    command = argv[0]
    operation = argv[argv.index("--operation") + 1] if command == "preflight" else command
    return StatusDocument(
        schema_version=1,
        kind="ota-production-status",
        command=command,
        operation=operation,
        outcome=outcome,
        stage="PLANNING" if outcome == "READY" else "PUBLIC_VERIFY",
        reason="READY" if outcome == "READY" else "PUBLIC_VERIFIED",
        writer_needed=outcome == "READY",
        operations=(),
    )


def test_sequential_scoped_preflight_and_writer_keep_each_target(tmp_path):
    from talent_data.ota.automation.delivery_routing import route

    calls = []

    def invoke(argv):
        calls.append(argv)
        status = document(argv, "READY" if argv[0] == "preflight" else "VERIFIED")
        Path(argv[argv.index("--status-file") + 1]).write_text(
            status.model_dump_json(by_alias=True)
        )
        return status.exit_code

    original = hint(count=2)
    planned = route(original, "preflight", tmp_path / "preflight", invoke=invoke, head_sha="a" * 40)
    assert planned.outcome == "READY" and planned.writer_needed
    plan = tmp_path / "preflight/plan.json"
    from talent_data.ota.automation.probe import read_probe

    route(
        read_probe(plan.read_bytes()),
        "writer",
        tmp_path / "writer",
        invoke=invoke,
        head_sha="a" * 40,
    )
    assert [a[0] for a in calls] == ["preflight", "preflight", "publish", "publish"]
    assert all("--release-id" in a and "--execute" in a and "--timings-file" in a for a in calls)
    assert len(list((tmp_path / "writer/targets").glob("*/candidate-status.json"))) == 2


def test_unknown_stops_fresh_writes_and_missing_status_is_ambiguous(tmp_path):
    from talent_data.ota.automation.delivery_routing import route

    calls = []

    def interrupted(argv):
        calls.append(argv)
        return 1  # possible writer interruption; never infer that no PUT happened

    result = route(hint(count=2), "writer", tmp_path, invoke=interrupted, head_sha="a" * 40)
    assert result.outcome == "AMBIGUOUS" and len(calls) == 1


def test_read_only_verify_and_global_recovery_use_closed_commands(tmp_path):
    from talent_data.ota.automation.delivery_routing import route

    calls = []

    def invoke(argv):
        calls.append(argv)
        status = document(argv)
        Path(argv[argv.index("--status-file") + 1]).write_text(
            status.model_dump_json(by_alias=True)
        )
        return 0

    route(hint("VERIFY"), "writer", tmp_path / "verify", invoke=invoke, head_sha="a" * 40)
    assert calls[0][0] == "verify" and "--execute" in calls[0]
    calls.clear()
    route(hint("RECOVER", 2), "writer", tmp_path / "recover", invoke=invoke, head_sha="a" * 40)
    assert len(calls) == 1 and calls[0][0] == "reconcile" and "--release-id" not in calls[0]


def test_no_work_skips_deep_jobs_and_probe_injection_is_refused(tmp_path):
    from talent_data.ota.automation.delivery_routing import route
    from talent_data.ota.automation.probe import read_probe

    result = route(
        _result("NO_WORK", "IDLE", head="a" * 40),
        "preflight",
        tmp_path,
        invoke=lambda _: pytest.fail("no heavy work"),
        head_sha="a" * 40,
    )
    assert result.outcome == "NO_ACTION"
    wire = json.loads(hint().model_dump_json(by_alias=True))
    wire["candidates"][0]["releaseId"] = "$(touch /tmp/forbidden)"
    with pytest.raises(ValueError):
        read_probe(json.dumps(wire).encode())
    with pytest.raises(ValueError):
        route(
            hint(),
            "writer",
            tmp_path,
            invoke=lambda _: pytest.fail("stale head"),
            head_sha="b" * 40,
        )


def test_new_delivery_preflight_does_not_wait_for_old_verification(tmp_path):
    from talent_data.ota.automation.delivery_routing import route

    publish = hint().candidates[0]
    verify = (
        hint("VERIFY").candidates[0].model_copy(update={"request_digest": "sha256:" + "d" * 64})
    )
    plan = _result("PUBLISH", "READY", head="a" * 40, candidates=(publish, verify))
    calls = []

    def invoke(argv):
        calls.append(argv)
        status = document(argv, "READY")
        Path(argv[argv.index("--status-file") + 1]).write_text(
            status.model_dump_json(by_alias=True)
        )
        return 0

    route(plan, "preflight", tmp_path, invoke=invoke, head_sha="a" * 40)
    assert len(calls) == 1 and calls[0][calls[0].index("--operation") + 1] == "publish"


@pytest.mark.parametrize(
    ("event", "schedule", "action", "delivery", "daily"),
    [
        ("workflow_run", "", "PUBLISH", "true", "false"),
        ("schedule", "3-58/5 * * * *", "PUBLISH", "true", "false"),
        ("schedule", "41 3 * * *", "NO_WORK", "false", "true"),
        ("schedule", "3-58/5 * * * *", "WAIT", "false", "false"),
        ("schedule", "3-58/5 * * * *", "BLOCKED", "false", "false"),
    ],
)
def test_event_and_watchdog_route_without_daily_renewal(
    tmp_path, event, schedule, action, delivery, daily
):
    import os
    import subprocess

    from tests.unit.test_telegram_notify import workflow_step_script

    output = tmp_path / "out"
    result = subprocess.run(
        [
            "bash",
            "-e",
            "-c",
            workflow_step_script("ota-production-publish.yml", "Select delivery route"),
        ],
        env={
            **os.environ,
            "EVENT": event,
            "SCHEDULE": schedule,
            "PROBE_ACTION": action,
            "GITHUB_OUTPUT": str(output),
        },
        capture_output=True,
    )
    assert result.returncode == 0, result.stderr
    assert output.read_text().splitlines() == [
        f"action={action}",
        f"delivery={delivery}",
        f"daily={daily}",
    ]


def test_daily_renewal_survives_candidate_failure_and_empty_watchdog_stays_light():
    from tests.unit.ota.test_automation_workflows import workflow_jobs

    text, jobs = workflow_jobs()
    assert 'cron: "3-58/5 * * * *"' in text and 'cron: "41 3 * * *"' in text
    assert "environment:" not in jobs["probe"] and "secrets." not in jobs["probe"]
    assert "contents: read" in jobs["probe"] and "actions: read" in jobs["probe"]
    assert "needs.probe.outputs.delivery == 'true'" in jobs["preflight"]
    assert "github.event.schedule == '41 3 * * *'" in jobs["renewal-preflight"]
    assert "always()" in jobs["renewal-preflight"]
    assert "needs.writer.result == 'success'" not in jobs["renewal-preflight"]
    assert "concurrency:" not in text.split("jobs:\n")[0]
    assert {m for m in range(60) if m >= 3 and (m - 3) % 5 == 0} == set(range(3, 59, 5))


def test_new_global_unknown_under_lock_overrides_stale_publish_hint(pending, tmp_path):
    from talent_data.ota.automation.delivery_routing import route
    from talent_data.ota.automation.state import RequestState
    from tests.unit.ota.test_automation_probe import retain_renewal

    retain_renewal(pending, RequestState.AMBIGUOUS)
    calls = []

    def invoke(argv):
        calls.append(argv)
        status = document(argv)
        Path(argv[argv.index("--status-file") + 1]).write_text(
            status.model_dump_json(by_alias=True)
        )
        return 0

    route(hint(count=2), "writer", tmp_path, invoke=invoke, head_sha="a" * 40, journal=pending[3])
    assert len(calls) == 1 and calls[0][0] == "reconcile" and "--release-id" not in calls[0]


def test_disabled_batch_entrypoint_reads_no_probe_or_journal(tmp_path, monkeypatch):
    from talent_data.ota.automation import delivery_routing

    monkeypatch.setenv("OTA_PRODUCTION_AUTOMATION_ENABLED", "false")
    monkeypatch.setattr(delivery_routing, "GitJournal", lambda _: pytest.fail("disabled journal"))
    output = tmp_path / "output"
    assert (
        delivery_routing.main(
            ["preflight", "--probe-file", str(tmp_path / "missing"), "--output-dir", str(output)]
        )
        == 0
    )
    assert read_status((output / "preflight.json").read_bytes()).outcome == "DISABLED"
