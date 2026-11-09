"""Regressions for the single fresh whole-branch review's five Important findings."""

import io
import json
import re
import zipfile
from pathlib import Path

import pytest

from talent_data.ota.automation.probe import _history_document, probe_delivery
from tests.unit.ota.test_automation_e2e import (  # noqa: F401
    env,
    exact_package,
    prepared_inputs,
    rehearsal,
    world,
)
from tests.unit.ota.test_automation_probe import API, add_failure, pending, probe  # noqa: F401
from tests.unit.ota.test_automation_workflows import workflow_jobs
from tests.unit.ota.test_delivery_routing import document, hint

# ruff: noqa: F811


def test_failed_consumer_rerun_uses_retained_producer_artifact_identity():
    for workflow in ("ci.yml", "ota-production-publish.yml"):
        _, jobs = workflow_jobs(workflow)
        downloads = 0
        for job in jobs.values():
            for step in re.split(r"^      - ", job, flags=re.M):
                if "uses: actions/download-artifact@" not in step:
                    continue
                downloads += 1
                binding = re.search(
                    r"artifact-ids: \$\{\{ needs\.([\w-]+)\.outputs\.artifact_id \}\}", step
                )
                assert binding, step
                producer = binding[1]
                assert "artifact_id:" in jobs[producer]
                assert "outputs.artifact-id" in jobs[producer]
                assert "merge-multiple: true" in step  # pinned v4 flattens ID downloads explicitly
                # A retained producer's ID is unchanged when only the consumer reaches attempt2.
                retained = {producer: {"artifact_id": "1234", "attempt": 1}}
                consumer_attempt = 2
                assert (
                    retained[producer]["artifact_id"] == "1234"
                    and consumer_attempt != retained[producer]["attempt"]
                )
                assert "github.run_attempt" not in step
        assert downloads


def test_queue_preserves_daily_pending_when_watchdog_arrives():
    text, jobs = workflow_jobs()
    assert re.findall(r"^      queue: (.+)$", text, re.M) == ["max"] * 4
    assert len(re.findall(r"^\s*queue:", text, re.M)) == 4
    for name in ("preflight", "writer", "renewal-preflight", "renewal-writer"):
        block = jobs[name].split("concurrency:\n", 1)[1].split("    permissions:", 1)[0]
        assert "queue: max" in block and "cancel-in-progress: false" in block
        assert "group: ota-production-writer" in block
        pending_jobs = []
        for incoming in ("daily-renewal-writer", "watchdog-preflight"):
            if "queue: max" not in block:
                pending_jobs.clear()
            pending_jobs.append(incoming)
        assert pending_jobs == ["daily-renewal-writer", "watchdog-preflight"]


def day_history(pending, monkeypatch, *, failure=False):
    api = pending[2]
    for n in range(1000, 1288):
        record = api.run_record(
            n, ".github/workflows/ota-production-publish.yml", api.head, "schedule"
        )
        if failure:
            record["conclusion"] = "failure"
        api.productions.append(record)
    original = api.pages

    def pages(path, member, **kwargs):
        values = original(path, member, **kwargs)
        if member == "jobs" and "/90/" not in path:
            n = int(path.split("/")[3])
            return [
                {
                    "name": "probe",
                    "status": "completed",
                    "conclusion": "success",
                    "run_id": n,
                    "run_attempt": 1,
                    "head_sha": api.head,
                }
            ]
        return values

    monkeypatch.setattr(api, "pages", pages)


def test_full_day_probe_only_history_is_cheap(pending, monkeypatch):
    day_history(pending, monkeypatch)
    assert probe(pending).action == "PUBLISH"
    assert len(pending[2].calls) < 24


def test_incomplete_history_budget_blocks_without_silent_truncation(pending, monkeypatch):
    day_history(pending, monkeypatch, failure=True)
    result = probe(pending)
    assert result.action == "BLOCKED" and result.reason == "HISTORY_UNAVAILABLE"
    assert len(pending[2].calls) < 64


def test_untouched_target_is_not_missing_failure_evidence_but_started_target_is(pending, tmp_path):
    from talent_data.ota.automation.delivery_routing import route
    from talent_data.ota.automation.prepare import PreparationBlocked

    output = tmp_path / "batch"
    plan = hint(count=2)

    def invoke(argv):
        progress = json.loads((output / "execution.json").read_bytes())
        assert progress["started"] == [plan.request_digests[0]]
        status = document(argv, "PUBLISHED_NOT_VERIFIED").model_copy(
            update={"reason": "PUBLIC_NOT_VERIFIED"}
        )
        Path(argv[argv.index("--status-file") + 1]).write_text(
            status.model_dump_json(by_alias=True)
        )
        return 3

    result = route(plan, "writer", output, invoke=invoke, head_sha="a" * 40)
    assert result.outcome == "PUBLISHED_NOT_VERIFIED"
    api = pending[2]
    add_failure(pending, 101)
    run = api.productions[0]
    run["head_sha"] = "a" * 40
    artifact = next(iter(api.artifacts.values()))
    artifact["workflow_run"]["head_sha"] = "a" * 40

    def archive():
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, "w") as z:
            for path in sorted(output.rglob("*.json")):
                z.writestr(path.relative_to(output).as_posix(), path.read_bytes())
        body = raw.getvalue()
        artifact.update(
            digest="sha256:" + __import__("hashlib").sha256(body).hexdigest(),
            size_in_bytes=len(body),
        )
        api.zips[artifact["id"]] = body

    archive()
    assert (
        _history_document(
            api,
            [artifact],
            name=artifact["name"],
            filename="candidate-status.json",
            run=run,
            digest=plan.request_digests[1],
        )
        is None
    )
    progress = json.loads((output / "execution.json").read_bytes())
    progress["started"] = list(plan.request_digests)
    (output / "execution.json").write_text(json.dumps(progress))
    archive()
    with pytest.raises(PreparationBlocked):
        _history_document(
            api,
            [artifact],
            name=artifact["name"],
            filename="candidate-status.json",
            run=run,
            digest=plan.request_digests[1],
        )


def test_signer_failure_survives_timings_and_becomes_permanent_probe_refusal(
    rehearsal, monkeypatch, tmp_path
):
    from talent_data.ota.automation.timings import read_timings
    from talent_data.ota.verify import sha256_ref
    from tests.unit.ota import test_automation_probe as fixture_module

    r = rehearsal
    r.fail_sign[0] = True
    timing_path = tmp_path / "timing.json"
    code, status = r.command(
        arguments=("--release-id", r.receipt.request.release_id, "--timings-file", str(timing_path))
    )
    assert code == 1 and status.reason == "SIGNER_FAILED"
    timing = read_timings(timing_path.read_bytes())
    assert timing.failure.reason == "SIGNER_FAILED"
    monkeypatch.setattr(fixture_module, "NOW", r.clock[0])
    api = API(r.environment["GITHUB_SHA"], r.receipt.request)
    state = (r.repo, r.receipt.request, api, r.journal)
    add_failure(state, 101)
    original_pages = api.pages

    def pages(path, member, **kwargs):
        values = original_pages(path, member, **kwargs)
        return (
            [{**j, "name": "writer"} for j in values]
            if "/101/" in path and member == "jobs"
            else values
        )

    monkeypatch.setattr(api, "pages", pages)
    for artifact in api.artifacts.values():
        raw = io.BytesIO()
        writer = artifact["name"].startswith("ota-production")
        name = "candidate-status.json" if writer else "delivery-timings.json"
        artifact["name"] = "ota-production-writer-101-1" if writer else "ota-delivery-timings-101-1"
        with zipfile.ZipFile(raw, "w") as z:
            z.writestr(
                name, status.model_dump_json(by_alias=True) if writer else timing_path.read_text()
            )
        artifact.update(digest=sha256_ref(raw.getvalue()), size_in_bytes=len(raw.getvalue()))
        api.zips[artifact["id"]] = raw.getvalue()
    result = probe_delivery(
        api, r.repo, r.journal, now=r.clock[0], trigger_sha=None, trigger_pin=None
    )
    assert result.action == "BLOCKED" and result.reason == "PERMANENT_FAILURE"


def test_partial_preflight_failure_cannot_hide_in_successful_writer_run(tmp_path, monkeypatch):
    from talent_data.ota.automation import delivery_routing as routing

    plan = hint(count=2)

    def prepare(argv):
        release = argv[argv.index("--release-id") + 1]
        result = document(argv, "READY" if release == plan.release_ids[0] else "BLOCKED")
        Path(argv[argv.index("--status-file") + 1]).write_text(
            result.model_dump_json(by_alias=True)
        )
        return result.exit_code

    preflight = tmp_path / "preflight"
    routing.route(plan, "preflight", preflight, invoke=prepare, head_sha="a" * 40)
    monkeypatch.setenv("OTA_PRODUCTION_AUTOMATION_ENABLED", "true")
    monkeypatch.setenv("GITHUB_SHA", "a" * 40)
    monkeypatch.delenv("GITHUB_TOKEN", raising=False)

    class Journal:
        def read_all(self):
            return {}

    monkeypatch.setattr(routing, "GitJournal", lambda _: Journal())

    def publish(argv):
        result = document(argv)
        Path(argv[argv.index("--status-file") + 1]).write_text(
            result.model_dump_json(by_alias=True)
        )
        return 0

    monkeypatch.setattr(routing, "command_main", publish)
    output = tmp_path / "writer"
    assert (
        routing.main(
            ["writer", "--probe-file", str(preflight / "plan.json"), "--output-dir", str(output)]
        )
        == 1
    )
    assert routing.read_status((output / "candidate-status.json").read_bytes()).outcome == "BLOCKED"
    assert (
        routing.read_status(
            next((output / "targets").glob("*/candidate-status.json")).read_bytes()
        ).outcome
        == "VERIFIED"
    )


def test_history_reads_are_shared_and_bounded_across_targets(pending):
    from talent_data.ota.automation.probe import ProbeCandidate, _backoff, _HistoryReads
    from talent_data.ota.automation.state import request_digest
    from talent_data.ota.automation.timings import delivery_input_fingerprint
    from talent_data.ota.verify import sha256_ref
    from tests.unit.ota.test_automation_probe import NOW

    repo, request, api, _ = pending
    add_failure(pending, 101)
    approval = (repo / "docs/ota/releases" / f"{request.release_id}.md").read_bytes()
    candidate = ProbeCandidate(
        action="PUBLISH",
        release_id=request.release_id,
        request_digest=request_digest(request),
        input_fingerprint=delivery_input_fingerprint(
            request_digest(request), sha256_ref(approval), api.head, (request.artifact.artifact_id,)
        ),
    )
    history = _HistoryReads(api)
    assert _backoff(history, api.head, candidate, NOW)[0] == "COOLDOWN"
    count = len(api.calls)
    assert _backoff(history, api.head, candidate, NOW)[0] == "COOLDOWN"
    assert len(api.calls) == count and history.remaining >= 0


def test_partial_preflight_failure_is_audited_even_when_writer_is_skipped(tmp_path, monkeypatch):
    from talent_data.ota.automation import delivery_routing as routing

    _, jobs = workflow_jobs()
    assert "needs.preflight.outputs.partial_failure == 'true'" in jobs["delivery-result"]
    assert "needs.writer" not in jobs["delivery-result"]
    assert "run: exit 1" in jobs["delivery-result"]
    assert (
        "permissions: {}" in jobs["delivery-result"] and "secrets." not in jobs["delivery-result"]
    )
    plan = hint(count=2)
    probe_file = tmp_path / "probe.json"
    probe_file.write_text(plan.model_dump_json(by_alias=True))

    def invoke(argv):
        release = argv[argv.index("--release-id") + 1]
        status = document(argv, "READY" if release == plan.release_ids[0] else "BLOCKED")
        Path(argv[argv.index("--status-file") + 1]).write_text(
            status.model_dump_json(by_alias=True)
        )
        return status.exit_code

    class Journal:
        def read_all(self):
            return {}

    monkeypatch.setattr(routing, "command_main", invoke)
    monkeypatch.setattr(routing, "GitJournal", lambda _: Journal())
    monkeypatch.setenv("GITHUB_SHA", "a" * 40)
    monkeypatch.setenv("OTA_PRODUCTION_AUTOMATION_ENABLED", "true")
    monkeypatch.delenv("GITHUB_TOKEN", raising=False)
    output = tmp_path / "github-output"
    monkeypatch.setenv("GITHUB_OUTPUT", str(output))
    assert (
        routing.main(
            ["preflight", "--probe-file", str(probe_file), "--output-dir", str(tmp_path / "out")]
        )
        == 0
    )
    assert "partial_failure=true" in output.read_text()
