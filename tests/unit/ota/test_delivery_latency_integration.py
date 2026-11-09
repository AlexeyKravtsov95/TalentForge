"""A lost event/code fix still delivers exactly once, with the original approval clock."""

import json
from datetime import timedelta
from pathlib import Path

from talent_data.ota.automation import cli
from talent_data.ota.automation.delivery_routing import route
from talent_data.ota.automation.probe import probe_delivery, read_probe
from talent_data.ota.automation.public_verify import verify_publication
from talent_data.ota.automation.state import PublicationReceipt, RequestState
from talent_data.ota.automation.timings import read_timings, resolve_approval_epoch
from talent_data.ota.models import Channel
from talent_data.ota.store import catalog_key
from tests.unit.ota.test_automation_e2e import (  # noqa: F401
    code_only_recovery,
    env,
    exact_package,
    git,
    prepared_inputs,
    rehearsal,
    world,
)

# ruff: noqa: F811


def test_approval_to_verified_delivery_with_code_only_recovery(
    code_only_recovery, monkeypatch, tmp_path
):
    r, approval_sha, _ = code_only_recovery
    request = r.receipt.request
    other = request.model_copy(update={"revision": 2, "release_id": request.build + "-r2"})
    other_path = r.remote / "docs/ota/release-requests" / f"{other.release_id}.json"
    other_path.write_text(other.model_dump_json(by_alias=True))
    git(r.remote, "add", ".")
    git(
        r.remote,
        "-c",
        "user.name=TEST",
        "-c",
        "user.email=test@example.invalid",
        "-c",
        "core.hooksPath=/dev/null",
        "commit",
        "-qm",
        "TEST unrelated pending request",
    )
    git(r.repo, "fetch", "origin", "main")
    git(r.repo, "reset", "--hard", "origin/main")
    head = git(r.repo, "rev-parse", "HEAD").decode().strip()
    r.environment["GITHUB_SHA"] = head
    r.payload["workflow_run"]["head_sha"] = head
    Path(r.environment["GITHUB_EVENT_PATH"]).write_text(json.dumps(r.payload))
    blocked = PublicationReceipt.pending(other, run_id=3, attempt=1).evolve(
        state=RequestState.BLOCKED, reason="CHECK_FAILED"
    )
    r.journal.save(blocked)
    api = r.dependencies.api
    original_run, original_pages, original_repo = api.run, api.pages, api.repo
    approved_at = r.clock[0] - timedelta(minutes=3)

    def run(n, a):
        result = original_run(n, a)
        return {**result, "head_sha": head} if n == 66 else result

    def pages(path, member=None, **kw):
        if path.startswith("/commits/"):
            return [{"number": 1}] if path.split("/")[2] == approval_sha else []
        if path == "/actions/workflows/ota-production-publish.yml/runs":
            return []  # Lost completion event: durable request selection supplies delivery.
        return original_pages(path, member, **kw)

    def repo(method, path, **kw):
        if path == "/pulls/1":
            return {
                "merged": True,
                "merge_commit_sha": approval_sha,
                "merged_at": approved_at.isoformat(),
                "base": {"ref": "main", "repo": {"full_name": request.repository}},
            }
        return original_repo(method, path, **kw)

    monkeypatch.setattr(api, "run", run)
    monkeypatch.setattr(api, "pages", pages)
    monkeypatch.setattr(api, "repo", repo)
    before = resolve_approval_epoch(api, r.repo, request)
    assert before.approved_at == approved_at
    probe = probe_delivery(
        api, r.repo, r.journal, now=r.clock[0], trigger_sha=head, trigger_pin=(66, 2)
    )
    assert probe.action == "PUBLISH" and probe.release_ids == (request.release_id,)
    puts = []
    original_put = r.store.put

    def put(key, raw, **kw):
        if key == catalog_key(Channel.PRODUCTION):
            puts.append(raw)
        return original_put(key, raw, **kw)

    monkeypatch.setattr(r.store, "put", put)

    def invoke(argv):
        return cli.main(argv, environment=r.environment, dependencies=r.dependencies)

    planned = route(
        probe, "preflight", tmp_path / "preflight", invoke=invoke, head_sha=head, journal=r.journal
    )
    assert planned.writer_needed and not r.reads and not puts
    plan = read_probe((tmp_path / "preflight/plan.json").read_bytes())
    result = route(
        plan, "writer", tmp_path / "writer", invoke=invoke, head_sha=head, journal=r.journal
    )
    assert result.outcome == "VERIFIED" and len(puts) == len(r.writers) == 1
    assert r.journal.load(blocked.request_digest).receipt.state is RequestState.BLOCKED
    retained = r.journal.load(r.receipt.request_digest).receipt
    assert retained.state is RequestState.VERIFIED
    timing = read_timings(
        next((tmp_path / "writer/targets").glob("*/delivery-timings.json")).read_bytes()
    )
    assert timing.identity.approval_epoch == before and timing.slo == "PASS"
    assert (
        timing.identity.trigger_ci.head_sha == head
        and timing.identity.request_ci.head_sha == approval_sha
    )
    proof = verify_publication(
        r.dependencies.client,
        origin=r.dependencies.origin,
        keyring=r.dependencies.keyring,
        game="forever",
        now=r.clock[0],
        expected_payload_sha256=retained.payload_sha256,
    )
    assert proof.ok
    route(plan, "writer", tmp_path / "retry", invoke=invoke, head_sha=head, journal=r.journal)
    assert len(puts) == len(r.writers) == 1
