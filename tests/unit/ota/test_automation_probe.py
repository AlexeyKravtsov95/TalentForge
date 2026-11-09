"""Cheap selection, strict API provenance and bounded automatic failure history."""

import io
import json
import subprocess
import zipfile
from datetime import UTC, datetime, timedelta

import pytest

from talent_data.ota.automation.requests import parse_request
from talent_data.ota.automation.state import FileJournal, PublicationReceipt, RequestState
from talent_data.ota.verify import sha256_ref
from tests.unit.ota.test_automation_e2e import (  # noqa: F401
    env,
    exact_package,
    prepared_inputs,
    rehearsal,
    world,
)
from tests.unit.ota.test_automation_requests import request_wire

# ruff: noqa: F811
from tests.unit.ota.test_automation_workflows import git
from tests.unit.ota.test_delivery_timings import approve

NOW = datetime(2026, 11, 8, 12, tzinfo=UTC)
REPO = "AlexeyKravtsov95/TalentForge"
WORKFLOW = ".github/workflows/ota-production-publish.yml"


class API:
    def __init__(self, head, request):
        self.head, self.request = head, request
        self.ci = self.run_record(90, ".github/workflows/ci.yml", head, "push")
        self.productions = []
        self.artifacts = {}
        self.zips = {}
        self.calls = []

    def run_record(self, n, path, head, event):
        return {
            "id": n,
            "run_attempt": 1,
            "path": path,
            "name": "CI",
            "event": event,
            "head_sha": head,
            "head_branch": "main",
            "status": "completed",
            "conclusion": "success",
            "repository": {"full_name": REPO, "id": 7},
            "head_repository": {"full_name": REPO, "id": 7},
            "run_started_at": (NOW - timedelta(hours=1)).isoformat(),
            "updated_at": NOW.isoformat(),
            "created_at": (NOW - timedelta(hours=1)).isoformat(),
        }

    def run(self, n, attempt):
        self.calls.append(("run", n, attempt))
        if n == 90:
            return self.ci.copy()
        for run in self.productions:
            if run["id"] == n:
                return run.copy()
        pin = next(p for p in (self.request.package_run, self.request.staging_run) if p.run_id == n)
        return self.run_record(n, pin.workflow_path, pin.head_sha, "workflow_dispatch")

    def pages(self, path, member, **kwargs):
        self.calls.append((path, member))
        if path == "/actions/workflows/ci.yml/runs":
            return [{"id": 90, "run_attempt": 1}]
        if path == "/actions/workflows/ota-production-publish.yml/runs":
            return self.productions.copy()
        if member == "jobs":
            if "/90/" in path:
                return [
                    {
                        "name": "checks",
                        "status": "completed",
                        "conclusion": "success",
                        "run_id": 90,
                        "run_attempt": 1,
                        "head_sha": self.ci["head_sha"],
                    }
                ]
            return [
                {
                    "name": "preflight",
                    "status": "completed",
                    "conclusion": "failure",
                    "run_id": int(path.split("/")[3]),
                    "run_attempt": 1,
                    "head_sha": self.head,
                    "started_at": (NOW - timedelta(seconds=120)).isoformat(),
                    "completed_at": next(
                        r["updated_at"]
                        for r in self.productions
                        if r["id"] == int(path.split("/")[3])
                    ),
                }
            ]
        if member == "artifacts":
            n = int(path.split("/")[3])
            return [a for a in self.artifacts.values() if a["workflow_run"]["id"] == n]
        raise AssertionError(path)

    def repo(self, method, path, **kwargs):
        assert method == "GET"
        self.calls.append((method, path))
        n = int(path.rsplit("/", 1)[1])
        if n in self.artifacts:
            return self.artifacts[n].copy()
        assert n == self.request.artifact.artifact_id
        return {
            "id": n,
            "name": self.request.artifact.name,
            "digest": self.request.artifact.archive_sha256,
            "expired": False,
            "size_in_bytes": 100,
            "created_at": (NOW - timedelta(minutes=30)).isoformat(),
            "workflow_run": {
                "id": self.request.package_run.run_id,
                "repository_id": 7,
                "head_repository_id": 7,
                "head_sha": self.request.package_run.head_sha,
                "head_branch": "main",
            },
        }

    def archive(self, artifact, **kwargs):
        self.calls.append(("archive", artifact["id"]))
        assert artifact["id"] != self.request.artifact.artifact_id, "package ZIP must stay unopened"
        return self.zips[artifact["id"]]


@pytest.fixture
def pending(tmp_path, monkeypatch):
    from talent_data.ota.automation import prepare, provenance

    repo = tmp_path / "repo"
    repo.mkdir()
    for args in (
        ("init", "-q", "-b", "main"),
        ("config", "user.name", "TEST"),
        ("config", "user.email", "test@example.invalid"),
    ):
        git(repo, *args)
    (repo / "source.txt").write_text("TEST source")
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "TEST source")
    source = git(repo, "rev-parse", "HEAD").decode().strip()
    wire = request_wire()
    wire["sourceCommit"] = source
    wire["packageRun"]["headSha"] = wire["stagingRun"]["headSha"] = source
    request = parse_request(json.dumps(wire).encode())
    path = repo / "docs/ota/release-requests" / f"{request.release_id}.json"
    path.parent.mkdir(parents=True)
    path.write_text(request.model_dump_json(by_alias=True))
    _, head = approve(repo, request)

    def main_commit(sha):
        assert (
            subprocess.run(
                ["git", "-C", str(repo), "merge-base", "--is-ancestor", sha, "origin/main"],
                capture_output=True,
            ).returncode
            == 0
        )

    monkeypatch.setattr(prepare, "_main_commit", main_commit)
    monkeypatch.setattr(provenance, "_main_commit", main_commit)
    journal = FileJournal(tmp_path / "journal.json")
    journal.initialize()
    return repo, request, API(head, request), journal


def probe(pending, **kwargs):
    from talent_data.ota.automation.probe import probe_delivery

    repo, _, api, journal = pending
    before = journal.path.read_bytes()
    result = probe_delivery(
        api, repo, journal, now=NOW, trigger_sha=None, trigger_pin=None, **kwargs
    )
    assert journal.path.read_bytes() == before
    return result


def test_no_work_has_no_blob_scan_secret_or_journal_write(pending, monkeypatch):
    repo, request, api, _ = pending
    path = repo / "docs/ota/release-requests" / f"{request.release_id}.json"
    path.unlink()
    git(repo, "add", "-u")
    git(repo, "commit", "-qm", "TEST remove")
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    monkeypatch.setattr(api, "archive", lambda _: pytest.fail("no archive"))
    result = probe(pending)
    assert result.action == "NO_WORK" and not result.candidates
    assert not api.calls


def test_probe_selects_pending_after_lost_event(pending):
    result = probe(pending)
    assert result.action == "PUBLISH"
    assert result.release_ids == (pending[1].release_id,)
    assert result.candidates[0].input_fingerprint.startswith("sha256:")
    assert not any(c[0] == "archive" for c in pending[2].calls)


def test_terminal_never_reactivated(pending):
    _, request, api, journal = pending
    receipt = PublicationReceipt.pending(request, run_id=1, attempt=1)
    journal.save(receipt.evolve(state=RequestState.STALE, reason="REQUEST_REMOVED"))
    assert probe(pending).action == "NO_WORK"
    assert not api.calls


@pytest.mark.parametrize(
    "field,value",
    [
        ("head_sha", "f" * 40),
        ("event", "pull_request"),
        ("repository", {"full_name": "foreign/repo"}),
        ("run_attempt", True),
        ("path", "other.yml"),
    ],
)
def test_untrusted_current_ci_blocks(pending, field, value):
    pending[2].ci[field] = value
    assert probe(pending).action == "BLOCKED"


def test_pending_current_ci_waits_without_heavy_work(pending):
    pending[2].ci.update(status="in_progress", conclusion=None)
    result = probe(pending)
    assert result.action == "WAIT" and result.reason == "CI_PENDING"
    assert not any(c[0] == "archive" for c in pending[2].calls)


def test_invalid_approval_waits_and_expired_artifact_blocks(pending, monkeypatch):
    repo, request, api, _ = pending
    path = repo / "docs/ota/releases" / f"{request.release_id}.md"
    path.write_text("<OWNER: fill approval>")
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "TEST withdraw")
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    api.ci["head_sha"] = git(repo, "rev-parse", "HEAD").decode().strip()
    assert probe(pending).action == "WAIT"
    _, head = approve(repo, request)
    api.ci["head_sha"] = head
    original = api.repo
    monkeypatch.setattr(api, "repo", lambda *a, **kw: {**original(*a, **kw), "expired": True})
    assert probe(pending).action == "BLOCKED"


def add_failure(pending, n, *, age=60, corrupt=False, expired=False, omit=False):
    from talent_data.ota.automation.cli import StatusDocument
    from talent_data.ota.automation.state import request_digest
    from talent_data.ota.automation.timings import (
        ApprovalEpoch,
        DeliveryIdentity,
        DeliveryRecorder,
    )

    _, request, api, _ = pending
    approval = (pending[0] / "docs/ota/releases" / f"{request.release_id}.md").read_bytes()
    identity = DeliveryIdentity(
        request=request,
        request_digest=request_digest(request),
        main_sha=api.ci["head_sha"],
        trigger_ci=None,
        request_ci=None,
        approval_epoch=ApprovalEpoch(
            epoch_id=sha256_ref(b"epoch"),
            request_digest=request_digest(request),
            approval_digest=sha256_ref(approval),
            approval_change_sha=api.ci["head_sha"],
            epoch_start_sha=api.ci["head_sha"],
            approved_at=None,
            time_source=None,
        ),
    )
    timing = DeliveryRecorder(identity, clock=lambda: 0, now=lambda: NOW).finish(
        verified_at=None, expected_payload_sha256=None, proof=None
    )
    status = StatusDocument(
        schema_version=1,
        kind="ota-production-status",
        command="preflight",
        operation="publish",
        outcome="BLOCKED",
        stage="PROVENANCE",
        reason="PACKAGE_PROVENANCE_FAILED",
        writer_needed=False,
        operations=(),
    )
    run = api.run_record(n, WORKFLOW, api.head, "schedule")
    run.update(conclusion="failure", updated_at=(NOW - timedelta(seconds=age)).isoformat())
    api.productions.append(run)
    for suffix, file, document in (
        ("ota-production-preflight", "preflight.json", status),
        ("ota-delivery-preflight-timings", "preflight-timings.json", timing),
    ):
        if omit and suffix.startswith("ota-delivery"):
            continue
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr(file, document.model_dump_json(by_alias=True))
        archive = raw.getvalue()
        aid = n * 10 + len(api.artifacts)
        artifact = {
            "id": aid,
            "name": f"{suffix}-{n}-1",
            "digest": sha256_ref(archive),
            "expired": expired,
            "size_in_bytes": len(archive),
            "created_at": run["updated_at"],
            "workflow_run": {
                "id": n,
                "repository_id": 7,
                "head_repository_id": 7,
                "head_branch": "main",
                "head_sha": api.head,
            },
        }
        api.artifacts[aid] = artifact
        api.zips[aid] = archive + (b"corrupt" if corrupt else b"")


def test_same_input_failures_back_off_but_changed_input_rechecks(pending):
    add_failure(pending, 101)
    result = probe(pending)
    assert result.action == "WAIT" and result.reason == "COOLDOWN"
    assert result.next_eligible_at == NOW + timedelta(seconds=240)
    add_failure(pending, 102, age=600)
    add_failure(pending, 103, age=900)
    assert probe(pending).reason == "RETRY_LIMIT"
    repo, _, api, _ = pending
    (repo / "code.txt").write_text("TEST code fix")
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "TEST fix")
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    head = git(repo, "rev-parse", "HEAD").decode().strip()
    api.head = head
    api.ci["head_sha"] = head
    # Exact request-change CI remains independently discoverable at its old SHA.
    original = api.pages
    old_ci = api.ci.copy()
    old_ci.update(id=91, head_sha=git(repo, "rev-parse", "HEAD^").decode().strip())
    original_run = api.run
    api.run = lambda n, a: old_ci.copy() if n == 91 else original_run(n, a)
    api.pages = lambda path, member, **kw: (
        [{"id": 91, "run_attempt": 1}]
        if path.endswith("/ci.yml/runs")
        and kw.get("params", {}).get("head_sha") == old_ci["head_sha"]
        else (
            [{"name": "checks", "status": "completed", "conclusion": "success"}]
            if "/91/" in path
            else original(path, member, **kw)
        )
    )
    assert probe(pending).action == "PUBLISH"


@pytest.mark.parametrize("fault", ["corrupt", "expired", "omit"])
def test_lost_or_invalid_history_blocks_instead_of_resetting_retry_budget(pending, fault):
    add_failure(pending, 101, **{fault: True})
    result = probe(pending)
    assert result.action == "BLOCKED" and result.reason == "HISTORY_UNAVAILABLE"


def test_per_target_batch_history_retains_retry_limit(pending):
    from talent_data.ota.automation.probe import ExecutionProgress, ProbeCandidate, _result
    from talent_data.ota.automation.state import request_digest

    add_failure(pending, 101)
    api = pending[2]
    digest = request_digest(pending[1])
    candidate = ProbeCandidate(
        action="PUBLISH",
        release_id=pending[1].release_id,
        request_digest=digest,
        input_fingerprint="sha256:" + "e" * 64,
    )
    plan = _result("PUBLISH", "READY", head=api.head, candidates=(candidate,))
    for aid, artifact in api.artifacts.items():
        with zipfile.ZipFile(io.BytesIO(api.zips[aid])) as old:
            filename = old.namelist()[0]
            body = old.read(filename)
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, "w") as new:
            plan_raw = plan.model_dump_json(by_alias=True).encode()
            new.writestr("input-plan.json", plan_raw)
            progress = ExecutionProgress(
                schema_version=1,
                kind="ota-batch-execution",
                main_sha=api.head,
                input_sha256=sha256_ref(plan_raw),
                started=(digest,),
            )
            new.writestr("execution.json", progress.model_dump_json(by_alias=True))
            new.writestr(f"targets/{digest[7:]}/{filename}", body)
        api.zips[aid] = raw.getvalue()
        artifact.update(digest=sha256_ref(raw.getvalue()), size_in_bytes=len(raw.getvalue()))
    assert probe(pending).reason == "COOLDOWN"


def test_missing_journal_and_malformed_request_are_blocked(pending):
    pending[3].path.unlink()
    assert probe_without_integrity_helper(pending).action == "BLOCKED"


def retain_renewal(pending, state):
    from talent_data.ota.automation.state import PreparedPublication, RenewalReceipt
    from tests.unit.ota.test_automation_public_verify import catalog_of
    from tests.unit.ota.test_ota_preflight import publication

    catalog = catalog_of(publication())
    base = catalog.model_dump_json(by_alias=True).encode()
    target = catalog.model_copy(update={"sequence": catalog.sequence + 1})
    prepared = PreparedPublication.create(
        target.model_dump_json(by_alias=True).encode(),
        base_sequence=catalog.sequence,
        der=b"TEST journal fixture",
        key_id="test-probe",
    )
    receipt = RenewalReceipt.pending(base, run_id=8, attempt=1).evolve(
        base_sequence=catalog.sequence,
        base_payload_sha256=sha256_ref(base),
        target_sequence=catalog.sequence + 1,
        payload_sha256=sha256_ref(prepared.payload),
        state=state,
        reason="OUTCOME_UNKNOWN" if state is RequestState.AMBIGUOUS else "S3_EXACT",
    )
    pending[3].save(receipt, prepared)
    return receipt


def test_unresolved_global_only_selects_recovery_even_with_malformed_request(pending):
    receipt = retain_renewal(pending, RequestState.AMBIGUOUS)
    repo, request, api, _ = pending
    path = repo / "docs/ota/release-requests" / f"{request.release_id}.json"
    path.write_text('{"broken":')
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "TEST broken unrelated request")
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    result = probe(pending)
    assert result.action == "RECOVER" and result.request_digests == (receipt.request_digest,)
    assert not api.calls


def test_retained_public_verification_survives_removed_request_and_expired_package(pending):
    receipt = retain_renewal(pending, RequestState.PUBLISHED_NOT_VERIFIED)
    repo, request, api, _ = pending
    (repo / "docs/ota/release-requests" / f"{request.release_id}.json").unlink()
    git(repo, "add", "-u")
    git(repo, "commit", "-qm", "TEST remove request")
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    result = probe(pending)
    assert result.action == "VERIFY" and result.request_digests == (receipt.request_digest,)
    assert not api.calls


def test_ready_publish_precedes_old_public_verification(pending):
    retain_renewal(pending, RequestState.PUBLISHED_NOT_VERIFIED)
    result = probe(pending)
    assert result.action == "PUBLISH"
    assert [c.action for c in result.candidates] == ["PUBLISH", "VERIFY"]


def test_pending_code_ci_does_not_prevent_retained_read_only_verification(pending):
    retain_renewal(pending, RequestState.PUBLISHED_NOT_VERIFIED)
    pending[2].ci.update(status="in_progress", conclusion=None)
    assert probe(pending).action == "VERIFY"


def test_old_base_mismatch_uses_catalog_metadata_and_never_starts_heavy_work(pending, monkeypatch):
    from talent_data.ota.automation import probe as module
    from tests.unit.ota.test_automation_public_verify import catalog_of
    from tests.unit.ota.test_ota_preflight import publication

    _, request, api, journal = pending
    receipt = PublicationReceipt.pending(request, run_id=1, attempt=1)
    journal.save(receipt.evolve(state=RequestState.BLOCKED, reason="LIVE_CHANGED"))
    catalog = catalog_of(publication())
    monkeypatch.setattr(module, "_public_catalog", lambda *args: catalog, raising=False)
    result = probe(pending)
    assert result.action == "WAIT" and result.reason == "BASE_NOT_CURRENT"
    assert not any(c[0] == "run" and c[1] == request.package_run.run_id for c in api.calls)


def test_probe_does_not_wait_for_its_own_read_only_job(pending, monkeypatch):
    api = pending[2]
    run = api.run_record(199, WORKFLOW, api.head, "schedule")
    run.update(status="in_progress", conclusion=None)
    api.productions.append(run)
    pages = api.pages
    monkeypatch.setattr(
        api,
        "pages",
        lambda path, member, **kwargs: (
            [
                {
                    "name": "probe",
                    "run_id": 199,
                    "run_attempt": 1,
                    "head_sha": api.head,
                    "status": "in_progress",
                    "conclusion": None,
                    "started_at": NOW.isoformat(),
                }
            ]
            if "/199/" in path
            else pages(path, member, **kwargs)
        ),
    )
    assert probe(pending).action == "PUBLISH"


def test_successful_rerun_does_not_hide_previous_failed_attempt(pending, monkeypatch):
    api = pending[2]
    add_failure(pending, 101)
    first = api.productions[0].copy()
    api.productions[0].update(run_attempt=2, conclusion="success")
    original = api.run
    monkeypatch.setattr(
        api, "run", lambda n, a: first.copy() if n == 101 and a == 1 else original(n, a)
    )
    original_pages = api.pages
    monkeypatch.setattr(
        api,
        "pages",
        lambda path, member, **kw: (
            [
                {
                    "name": "probe",
                    "run_id": 101,
                    "run_attempt": 2,
                    "head_sha": api.head,
                    "status": "completed",
                    "conclusion": "success",
                }
            ]
            if "/101/attempts/2/jobs" in path
            else original_pages(path, member, **kw)
        ),
    )
    assert probe(pending).reason == "COOLDOWN"


def test_schedule_rechecks_current_code_ci_before_loading_writer_secrets(rehearsal):
    r = rehearsal
    api = r.dependencies.api
    original = api.run

    def run(n, attempt):
        result = original(n, attempt)
        if n == 66:
            result.update(status="in_progress", conclusion=None)
        return result

    api.run = run
    code, document = r.command(GITHUB_EVENT_NAME="schedule")
    assert code == 1 and document.reason == "INVOCATION_REFUSED"
    assert not r.reads and not r.writers


def test_disabled_probe_entrypoint_loads_no_dependencies(tmp_path):
    from talent_data.ota.automation.cli import main
    from talent_data.ota.automation.probe import read_probe

    status = tmp_path / "probe.json"
    assert main(["probe", "--status-file", str(status)], environment={}) == 0
    assert read_probe(status.read_bytes()).reason == "DISABLED"


def test_probe_entrypoint_binds_actual_invocation_without_writer_dependencies(pending, monkeypatch):
    import httpx

    from talent_data.ota.automation import publisher
    from talent_data.ota.automation.cli import Dependencies, main
    from talent_data.ota.automation.probe import read_probe

    repo, _, api, journal = pending
    payload = repo.parent / "event.json"
    payload.write_text(json.dumps({"repository": {"full_name": REPO}}))
    invocation = api.run_record(55, WORKFLOW, api.head, "schedule")
    invocation.update(status="in_progress", conclusion=None, actor={"login": "operator"})
    original = api.run
    monkeypatch.setattr(api, "run", lambda n, a: invocation.copy() if n == 55 else original(n, a))

    def refresh(path):
        assert git(path, "rev-parse", "HEAD") == git(path, "rev-parse", "origin/main")

    monkeypatch.setattr(publisher, "refresh_main", refresh)

    def forbidden(*args):
        pytest.fail("probe cannot read production secrets or open writer")

    status = repo.parent / "probe.json"
    with httpx.Client(transport=httpx.MockTransport(forbidden)) as client:
        deps = Dependencies(
            repo, api, client, journal, {}, forbidden, read_secret=forbidden, now=lambda: NOW
        )
        environment = {
            "OTA_PRODUCTION_AUTOMATION_ENABLED": "true",
            "GITHUB_REPOSITORY": REPO,
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_SHA": api.head,
            "GITHUB_EVENT_NAME": "schedule",
            "GITHUB_RUN_ID": "55",
            "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_ACTOR": "operator",
            "GITHUB_EVENT_PATH": str(payload),
        }
        assert (
            main(
                ["probe", "--status-file", str(status)], environment=environment, dependencies=deps
            )
            == 0
        )
    assert read_probe(status.read_bytes()).action == "PUBLISH"


def probe_without_integrity_helper(pending):
    from talent_data.ota.automation.probe import probe_delivery

    return probe_delivery(
        pending[2], pending[0], pending[3], now=NOW, trigger_sha=None, trigger_pin=None
    )


def test_partial_batch_failure_remains_in_failed_run_budget(pending):
    add_failure(pending, 101)
    assert probe(pending).reason == "COOLDOWN"
