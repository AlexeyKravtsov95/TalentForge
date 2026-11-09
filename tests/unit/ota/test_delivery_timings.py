"""Server approval epochs, honest latency outcomes and thread-safe nonsecret telemetry."""

import json
from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from talent_data.ota.automation.requests import parse_request
from talent_data.ota.verify import sha256_ref
from tests.unit.ota.test_automation_requests import request_wire
from tests.unit.ota.test_automation_workflows import git, request_history  # noqa: F401

# ruff: noqa: F811

APPROVED = datetime(2026, 11, 8, 12, tzinfo=UTC)
PAYLOAD = "sha256:" + "a" * 64


def identity(approved_at=APPROVED):
    from talent_data.ota.automation.timings import ApprovalEpoch, DeliveryIdentity

    request = parse_request(json.dumps(request_wire()).encode())
    digest = sha256_ref(request.model_dump_json(by_alias=True).encode())
    epoch = ApprovalEpoch(
        epoch_id="sha256:" + "b" * 64,
        request_digest=digest,
        approval_digest="sha256:" + "c" * 64,
        approval_change_sha="d" * 40,
        epoch_start_sha="d" * 40,
        approved_at=approved_at,
        time_source="merged_at" if approved_at else None,
    )
    return DeliveryIdentity(
        request=request,
        request_digest=digest,
        approval_epoch=epoch,
        main_sha="e" * 40,
        trigger_ci=None,
        request_ci=None,
    )


def finish(recorder, at):
    from talent_data.ota.automation.public_verify import PublicVerification

    return recorder.finish(
        verified_at=at,
        expected_payload_sha256=PAYLOAD,
        proof=PublicVerification(True, PAYLOAD, recorder.identity.request.release_id, 5, None),
    )


@pytest.mark.parametrize(("seconds", "slo"), [(600, "PASS"), (600.001, "MISS")])
def test_slo_boundary_includes_all_time_after_server_approval(seconds, slo):
    from talent_data.ota.automation.timings import DeliveryRecorder, read_timings

    at = APPROVED + timedelta(seconds=seconds)
    recorder = DeliveryRecorder(identity(), clock=lambda: 0.0, now=lambda: at)
    result = finish(recorder, at)
    assert result.slo == slo and result.elapsed_seconds == seconds
    assert read_timings(result.model_dump_json(by_alias=True).encode()) == result
    assert result.package_age_seconds > result.elapsed_seconds


def test_unknown_server_time_cannot_claim_slo_pass():
    from talent_data.ota.automation.timings import DeliveryRecorder

    result = finish(
        DeliveryRecorder(identity(None), clock=lambda: 0.0, now=lambda: APPROVED), APPROVED
    )
    assert result.slo == "UNKNOWN" and result.elapsed_seconds is None
    assert result.verified_at == APPROVED  # delivery succeeded; its latency is unknown


def test_partial_or_wrong_public_proof_cannot_finish_delivery():
    from talent_data.ota.automation.public_verify import PublicVerification
    from talent_data.ota.automation.timings import DeliveryRecorder

    recorder = DeliveryRecorder(identity(), clock=lambda: 0.0, now=lambda: APPROVED)
    result = recorder.finish(
        verified_at=APPROVED,
        expected_payload_sha256=PAYLOAD,
        proof=PublicVerification(
            True, "sha256:" + "f" * 64, recorder.identity.request.release_id, 5, None
        ),
    )
    assert result.slo == "PENDING" and result.verified_at is None


def test_parallel_counters_exact_and_secret_free():
    from talent_data.ota.automation.timings import DeliveryRecorder
    from talent_data.ota.blob_io import run_bounded

    recorder = DeliveryRecorder(identity(), clock=lambda: 0.0, now=lambda: APPROVED)

    def work(value):
        recorder.count("GET", bytes=4, retry=value % 10 == 0, sha_ref=PAYLOAD)

    run_bounded(range(1000), work, max_workers=8)
    result = finish(recorder, APPROVED + timedelta(seconds=60))
    assert result.io.get_requests == 1000
    assert result.io.retries == 100 and result.io.transferred_bytes == 4000
    assert result.io.unique_hashes == 1
    assert "TEST-SECRET" not in result.model_dump_json()


def test_observer_context_propagates_to_bounded_workers():
    from talent_data.ota import io_metrics
    from talent_data.ota.automation.timings import DeliveryRecorder
    from talent_data.ota.blob_io import run_bounded

    recorder = DeliveryRecorder(identity(), clock=lambda: 0.0, now=lambda: APPROVED)
    with io_metrics.observe(recorder):
        run_bounded(range(8), lambda _: io_metrics.count("GET", bytes=2), max_workers=4)
    io_metrics.count("GET", bytes=200)  # must not leak into the completed scope
    result = finish(recorder, APPROVED + timedelta(seconds=60))
    assert result.io.get_requests == 8 and result.io.transferred_bytes == 16


def test_phase_records_monotonic_duration_and_not_exception_text():
    from talent_data.ota.automation.timings import DeliveryRecorder

    clock = [1.0]
    recorder = DeliveryRecorder(identity(), clock=lambda: clock[0], now=lambda: APPROVED)
    with pytest.raises(RuntimeError, match="TEST-SECRET"), recorder.phase("UPLOAD"):
        clock[0] = 2.5
        raise RuntimeError("TEST-SECRET")
    result = finish(recorder, APPROVED + timedelta(seconds=60))
    assert result.phases[0].elapsed_seconds == 1.5 and not result.phases[0].ok
    assert "TEST-SECRET" not in result.model_dump_json()


@pytest.mark.parametrize(
    "update",
    [
        {"schemaVersion": True},
        {"unknown": 1},
        {"elapsedSeconds": -1.0},
        {"elapsedSeconds": float("nan")},
        {"verifiedAt": "2026-11-08T12:00:00"},
        {"inputFingerprint": "sha256:" + "1" * 64},
    ],
)
def test_timing_wire_rejects_unknown_wrong_and_nonfinite_values(update):
    from talent_data.ota.automation.timings import DeliveryRecorder, read_timings

    result = finish(DeliveryRecorder(identity(), clock=lambda: 0.0, now=lambda: APPROVED), APPROVED)
    import json

    wire = json.loads(result.model_dump_json(by_alias=True))
    wire.update(update)
    with pytest.raises((ValueError, ValidationError)):
        read_timings(json.dumps(wire).encode())


def approve(repo, request, text=None):
    path = repo / "docs/ota/releases" / f"{request.release_id}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        text
        or (
            f"- Release: {request.release_id}\n"
            f"- Manifest SHA-256: {request.manifest_sha256}\n"
            f"- Source commit: {request.source_commit}\n"
            "- Accepted gates: G7\n- Live-build evidence: TEST owner evidence\n"
        )
    )
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "TEST approval")
    sha = git(repo, "rev-parse", "HEAD").decode().strip()
    git(repo, "update-ref", "refs/remotes/origin/main", sha)
    return path, sha


class HistoryAPI:
    def __init__(self, commits):
        self.commits = commits

    def pages(self, path, member=None, **kwargs):
        sha = path.split("/")[2]
        return [{"number": n} for n, (commit, _) in enumerate(self.commits, 1) if commit == sha]

    def repo(self, method, path):
        sha, at = self.commits[int(path.rsplit("/", 1)[1]) - 1]
        return {
            "merged": True,
            "merge_commit_sha": sha,
            "merged_at": at.isoformat(),
            "base": {"ref": "main", "repo": {"full_name": "AlexeyKravtsov95/TalentForge"}},
        }


def test_retry_and_code_fix_preserve_first_approval_time(request_history):
    from talent_data.ota.automation.timings import resolve_approval_epoch

    repo, request, _, _ = request_history
    _, first = approve(repo, request)
    api = HistoryAPI([(first, APPROVED)])
    before = resolve_approval_epoch(api, repo, request)
    (repo / "code.py").write_text("# TEST fix\n")
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "code fix")
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    after = resolve_approval_epoch(api, repo, request)
    assert before.approved_at == after.approved_at == APPROVED
    assert before.epoch_id == after.epoch_id


def test_revocation_creates_new_epoch_without_hiding_package_age(request_history):
    from talent_data.ota.automation.timings import resolve_approval_epoch

    repo, request, _, _ = request_history
    path, first = approve(repo, request)
    before = resolve_approval_epoch(HistoryAPI([(first, APPROVED)]), repo, request)
    path.unlink()
    git(repo, "add", "-u")
    git(repo, "commit", "-qm", "withdraw")
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    withdrawn = resolve_approval_epoch(HistoryAPI([]), repo, request)
    assert withdrawn.approved_at is None
    _, second = approve(repo, request)
    after = resolve_approval_epoch(
        HistoryAPI([(first, APPROVED), (second, APPROVED + timedelta(minutes=5))]), repo, request
    )
    assert after.approved_at == APPROVED + timedelta(minutes=5)
    assert before.epoch_id != after.epoch_id and before.request_digest == after.request_digest


def test_missing_server_merge_evidence_gives_unknown_time(request_history):
    from talent_data.ota.automation.timings import resolve_approval_epoch

    repo, request, _, _ = request_history
    approve(repo, request)
    result = resolve_approval_epoch(HistoryAPI([]), repo, request)
    assert result.approved_at is None and result.time_source is None


def test_ci_phase_uses_server_queue_and_job_completion_times():
    from talent_data.ota.automation.requests import RunPin
    from talent_data.ota.automation.timings import DeliveryRecorder

    recorder = DeliveryRecorder(identity(), clock=lambda: 0.0, now=lambda: APPROVED)
    pin = RunPin(workflow_path=".github/workflows/ci.yml", run_id=9, attempt=2, head_sha="e" * 40)
    recorder.record_ci(
        pin,
        {
            "created_at": APPROVED.isoformat(),
            "run_started_at": (APPROVED + timedelta(seconds=30)).isoformat(),
        },
        [{"completed_at": (APPROVED + timedelta(seconds=90)).isoformat()}],
        source="trigger-ci",
    )
    result = finish(recorder, APPROVED + timedelta(seconds=120))
    assert [(p.name, p.elapsed_seconds, p.source) for p in result.phases] == [
        ("QUEUE", 30.0, "trigger-ci"),
        ("CI", 60.0, "trigger-ci"),
    ]
    assert result.identity.trigger_ci == pin
