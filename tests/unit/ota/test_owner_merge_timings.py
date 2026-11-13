"""Preparation, owner waiting and public proof keep separate server-time intervals."""

import copy
from datetime import UTC, datetime

import pytest

from talent_data.ota.automation.merge_approval import verify_owner_merge
from tests.unit.ota.test_owner_merge_authorization import merged
from tests.unit.test_ota_prepare import git


def setup(tmp_path, monkeypatch):
    from talent_data.ota.automation import ci_validation

    data = merged(tmp_path)
    _repo, api, _request, binding, _record, *_ = data
    monkeypatch.setattr(ci_validation, "load_merge_binding", lambda *args: binding)
    api.pull["created_at"] = "2026-11-12T09:00:00Z"
    api.runs[301]["updated_at"] = "2026-11-12T09:40:00Z"
    for pin, stamp in [
        (binding.staging_run, "2026-11-12T09:20:00Z"),
        (binding.package_run, "2026-11-12T09:30:00Z"),
    ]:
        api.runs[pin.run_id] = {
            **copy.deepcopy(api.runs[101]),
            "id": pin.run_id,
            "updated_at": stamp,
        }
    original = api.pages

    def pages(path, *args, **kwargs):
        if path == "/issues/141/timeline":
            return [
                {
                    "event": "ready_for_review",
                    "actor": {"id": 351368232},
                    "created_at": "2026-11-12T09:41:00Z",
                }
            ]
        return original(path, *args, **kwargs)

    api.pages = pages
    return data


def test_epoch_is_real_merge_and_survives_code_fix(tmp_path, monkeypatch):
    from talent_data.ota.automation.timings import resolve_approval_epoch

    repo, api, request, *rest = setup(tmp_path, monkeypatch)
    first = resolve_approval_epoch(api, repo, request)
    assert first.approved_at == datetime(2026, 11, 12, 10, tzinfo=UTC)
    assert first.epoch_start_sha == rest[-1] and first.time_source == "merged_at"
    git(repo, "checkout", "--quiet", "--detach", rest[-1])
    (repo / "fix.txt").write_text("code fix\n")
    git(repo, "add", "fix.txt")
    git(
        repo,
        "-c",
        "user.name=test",
        "-c",
        "user.email=test@example.invalid",
        "commit",
        "-qm",
        "code fix",
    )
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    later = resolve_approval_epoch(api, repo, request)
    assert later.epoch_id == first.epoch_id and later.approved_at == first.approved_at


def test_unmerged_or_revoked_record_has_no_approval_epoch(tmp_path, monkeypatch):
    from talent_data.ota.automation.timings import resolve_approval_epoch

    repo, api, request, *_ = setup(tmp_path, monkeypatch)
    api.pull["merged"] = False
    assert resolve_approval_epoch(api, repo, request).approved_at is None


@pytest.mark.parametrize("seconds,slo", [(180, "PASS"), (900, "MISS"), (None, "PENDING")])
def test_timeline_separates_ready_wait_and_merge_to_proof(tmp_path, monkeypatch, seconds, slo):
    from datetime import timedelta

    from talent_data.ota.automation.timings import owner_merge_timeline

    repo, api, request, binding, record, *_ = setup(tmp_path, monkeypatch)
    proof = verify_owner_merge(api, repo, request, record, binding=binding)
    verified = proof.merged_at + timedelta(seconds=seconds) if seconds is not None else None
    timeline = owner_merge_timeline(api, proof, verified_at=verified)
    assert timeline.candidate_to_ready_seconds == 2460
    assert timeline.ready_to_merge_seconds == 1140
    assert timeline.merge_to_verified_seconds == seconds
    assert timeline.slo == slo
    assert timeline.final_ci_at == datetime(2026, 11, 12, 9, 40, tzinfo=UTC)
    assert timeline.staging_at == datetime(2026, 11, 12, 9, 20, tzinfo=UTC)
    assert timeline.package_at == datetime(2026, 11, 12, 9, 30, tzinfo=UTC)


def test_missing_ready_event_is_unknown_not_ci_timestamp(tmp_path, monkeypatch):
    from talent_data.ota.automation.timings import owner_merge_timeline

    repo, api, request, binding, record, *_ = setup(tmp_path, monkeypatch)
    original = api.pages
    api.pages = lambda path, *args, **kwargs: (
        [] if path.endswith("/timeline") else original(path, *args, **kwargs)
    )
    proof = verify_owner_merge(api, repo, request, record, binding=binding)
    timeline = owner_merge_timeline(api, proof)
    assert timeline.ready_at is None and timeline.candidate_to_ready_seconds is None


def test_frozen_review_terms_show_warning_and_exact_package(tmp_path):
    from talent_data.ota.automation.premerge import CandidateReview, render_ready_body
    from tests.unit.ota.test_premerge_proposal import setup as proposal

    _repo, reader, _writer, candidate, request, binding = proposal(tmp_path)
    binding = binding.model_copy(update={"flagged_gates": ("G5", "G7")})
    facts = CandidateReview(
        reader.pull,
        request,
        binding,
        candidate,
        ({"gate": "G5", "status": "FLAG", "detail": "3 new warnings about allocation links"},),
        403,
        5125504,
    )
    body = render_ready_body(facts)
    assert request.manifest_sha256 in body and str(request.artifact.artifact_id) in body
    assert "3 new warnings about allocation links" in body
    assert "обычный Merge" in body and "второго MR" in body
    assert "играл" not in body and "OWNER:" not in body
    from dataclasses import replace

    assert render_ready_body(replace(facts, pull={**facts.pull, "body": body})) == body


@pytest.mark.parametrize("failure", [None, "api", "http"])
@pytest.mark.parametrize("routing", [False, True])
def test_owned_client_timeline_cannot_break_authoritative_preflight(
    tmp_path, monkeypatch, failure, routing
):
    from types import SimpleNamespace

    import httpx

    from talent_data.ota.automation import cli, timings
    from talent_data.ota.automation.delivery_routing import route
    from talent_data.ota.automation.prepare import PreparationBlocked
    from tests.unit.ota.test_delivery_routing import hint

    real_client = httpx.Client
    clients, timeline_calls = [], []

    def client_factory(**kwargs):
        client = real_client(transport=httpx.MockTransport(lambda _: httpx.Response(200, json={})))
        clients.append(client)
        return client

    monkeypatch.setattr(cli.httpx, "Client", client_factory)
    monkeypatch.setattr(cli, "production_keyring", lambda _: {})
    monkeypatch.setattr(cli, "GitJournal", lambda _: object())

    def operation(self):
        assert not self.d.client.is_closed
        self.recorder = SimpleNamespace(
            failure=None, finish=lambda **_: SimpleNamespace(model_dump_json=lambda **_: "{}")
        )
        self.owner_merge = object()
        return cli._empty(self.args, "READY", "PLANNING", "READY").model_copy(
            update={"writer_needed": True}
        )

    def timeline(api, proof, **kwargs):
        timeline_calls.append(True)
        assert not api.client.is_closed
        api.repo("GET", "/pulls/141")  # actual owned httpx client, isolated transport
        if failure == "api":
            raise PreparationBlocked("GITHUB_HTTP", "unavailable optional audit")
        if failure == "http":
            raise httpx.ConnectError("unavailable optional audit")
        return SimpleNamespace(model_dump_json=lambda **_: "{}")

    monkeypatch.setattr(cli._Run, "run", operation)
    monkeypatch.setattr(timings, "owner_merge_timeline", timeline)
    env = {cli.ENABLE_FLAG: "true", "GITHUB_TOKEN": "test-isolated-token"}
    if routing:
        result = route(
            hint(),
            "preflight",
            tmp_path,
            head_sha="a" * 40,
            invoke=lambda argv: cli.main(argv, environment=env),
        )
        assert result.outcome == "READY" and result.writer_needed
    else:
        status = tmp_path / "status.json"
        assert (
            cli.main(
                [
                    "preflight",
                    "--status-file",
                    str(status),
                    "--timings-file",
                    str(tmp_path / "timings.json"),
                ],
                environment=env,
            )
            == 0
        )
        result = cli.read_status(status.read_bytes())
        assert result.outcome == "READY" and result.writer_needed
    assert timeline_calls == [True]
    assert clients and all(c.is_closed for c in clients)
