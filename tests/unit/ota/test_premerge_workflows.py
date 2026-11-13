"""CI events select preparation once and readiness after metadata; no executable artifact."""

import copy
from pathlib import Path

import pytest
import yaml

from talent_data.ota.automation.candidate import verify_candidate
from talent_data.ota.automation.prepare import PreparationBlocked
from talent_data.ota.automation.requests import RunPin
from tests.unit.ota.single_merge_testkit import candidate_fixture
from tests.unit.ota.test_premerge_proposal import setup

ROOT = Path(__file__).resolve().parents[3]


@pytest.mark.parametrize("empty_association", [False, True])
def test_initial_exact_pr_ci_routes_preparation(tmp_path, empty_association):
    from talent_data.ota.automation.premerge import route_candidate_ci

    repo, api = candidate_fixture(tmp_path)
    if empty_association:
        api.runs[201]["pull_requests"] = []
    pin = verify_candidate(api, repo, 141, source_sha=api.source).ci_pin
    assert route_candidate_ci(api, repo, pin) == ("prepare", 141)


@pytest.mark.parametrize(
    "fault", ["fork", "workflow", "event", "attempt", "failed", "foreign-branch"]
)
def test_foreign_ci_cannot_reach_dispatch(tmp_path, fault):
    from talent_data.ota.automation.premerge import route_candidate_ci

    repo, api = candidate_fixture(tmp_path)
    pin = RunPin(
        workflow_path=".github/workflows/ci.yml", run_id=201, attempt=1, head_sha=api.source
    )
    if fault == "fork":
        api.runs[201]["head_repository"]["full_name"] = "other/repo"
    elif fault == "workflow":
        api.runs[201]["path"] = ".github/workflows/evil.yml"
    elif fault == "event":
        api.runs[201]["event"] = "push"
    elif fault == "attempt":
        pin = pin.model_copy(update={"attempt": 2})
    elif fault == "failed":
        api.jobs[201][1]["conclusion"] = "failure"
    else:
        api.runs[201]["head_branch"] = "foreign"
    with pytest.raises(PreparationBlocked):
        route_candidate_ci(api, repo, pin)


def test_final_head_routes_ready_without_preparation(tmp_path):
    from talent_data.ota.automation.premerge import bind_candidate_request, route_candidate_ci

    repo, reader, writer, candidate, request, binding = setup(tmp_path)
    head = bind_candidate_request(reader, writer, repo, candidate, request, binding)
    run = reader.runs[201]
    run["head_sha"] = head
    run["pull_requests"][0]["head"]["sha"] = head
    for job in reader.jobs[201]:
        job["head_sha"] = head
    pin = RunPin(workflow_path=".github/workflows/ci.yml", run_id=201, attempt=1, head_sha=head)
    before = copy.deepcopy(writer.writes)
    assert route_candidate_ci(reader, repo, pin) == ("ready", 141)
    assert writer.writes == before


def test_workflow_keeps_pr_input_away_from_app_and_production_secrets():
    workflow = yaml.load(
        (ROOT / ".github/workflows/ota-release-prepare.yml").read_text(), Loader=yaml.BaseLoader
    )
    jobs = workflow["jobs"]
    prepare = jobs["premerge"]
    review = jobs["premerge-review"]
    assert prepare["permissions"] == {
        "contents": "write",
        "actions": "write",
        "pull-requests": "read",
    }
    assert "pull_request" in prepare["if"] and "head_repository.full_name" in prepare["if"]
    for job in (prepare, review):
        checkout = next(
            s for s in job["steps"] if s.get("uses", "").startswith("actions/checkout@")
        )
        assert checkout["with"]["ref"] == "${{ github.sha }}"
        assert checkout["with"]["persist-credentials"] == "false"
        assert "production" not in job.get("environment", "")
    text = str(prepare)
    assert "PRIVATE_KEY" not in text and "S3" not in text
    assert review["needs"] == "premerge"
    assert "pull_request_target" not in workflow["on"]


def test_consumer_retry_keeps_successful_original_preparation_when_proposal_failed(tmp_path):
    repo, api = candidate_fixture(tmp_path)
    api.runs[101]["conclusion"] = "failure"
    api.jobs[101][1]["conclusion"] = "failure"  # App/API failure after immutable data preparation
    original = (api.notice["candidateArtifact"].copy(), api.notice["preparationRun"].copy())
    candidate = verify_candidate(api, repo, 141, source_sha=api.source)
    assert candidate.candidate_artifact.artifact_id == original[0]["artifactId"]
    assert candidate.candidate_run.run_id == original[1]["runId"]
    assert candidate.candidate_run.attempt == original[1]["attempt"]


@pytest.mark.parametrize("fault", [None, "stale-head", "older-attempt"])
@pytest.mark.parametrize("empty_association", [False, True])
def test_changed_head_returns_to_draft_without_authorizing_any_write(
    tmp_path, fault, empty_association
):
    from talent_data.ota.automation.premerge import invalidate_candidate_ready
    from tests.unit.ota.test_premerge_proposal import App

    repo, reader = candidate_fixture(tmp_path)
    writer = App(reader)
    reader.pull.update(draft=False, node_id="PR_test")
    reader.runs[201].update(status="in_progress", conclusion=None)
    if empty_association:
        reader.runs[201]["pull_requests"] = []
    pin = RunPin(
        workflow_path=".github/workflows/ci.yml", run_id=201, attempt=1, head_sha=reader.source
    )
    original = writer.call

    def call(method, path, **kwargs):
        if path == "/graphql":
            assert "convertPullRequestToDraft" in kwargs["data"]["query"]
            writer.writes.append((method, path, kwargs["data"]))
            reader.pull["draft"] = True
            return {
                "data": {
                    "convertPullRequestToDraft": {"pullRequest": {"id": "PR_test", "isDraft": True}}
                }
            }
        return original(method, path, **kwargs)

    writer.call = call
    if fault == "stale-head":
        reader.pull["head"]["sha"] = reader.base
    elif fault == "older-attempt":
        listing = reader.pages
        reader.pages = lambda path, *args, **kwargs: (
            [{**reader.runs[201], "run_attempt": 2}]
            if path == "/actions/workflows/ci.yml/runs"
            else listing(path, *args, **kwargs)
        )
    if fault is None:
        invalidate_candidate_ready(reader, writer, repo, pin)
        assert reader.pull["draft"] is True
        assert [p for _, p, _ in writer.writes] == ["/graphql"]
        invalidate_candidate_ready(reader, writer, repo, pin)
        assert len(writer.writes) == 1
    else:
        with pytest.raises(PreparationBlocked):
            invalidate_candidate_ready(reader, writer, repo, pin)
        assert not writer.writes


def test_notice_reuses_original_artifact_attempt_not_consumer_attempt(tmp_path, monkeypatch):
    from tests.unit.ota.release_package_testkit import load_script

    _repo, api = candidate_fixture(tmp_path)
    script = load_script("ota_premerge")
    original_run = copy.deepcopy(api.runs[101])
    api.runs[101].update(
        run_attempt=2, run_started_at="2027-01-01T10:00:00Z", updated_at="2027-01-01T11:00:00Z"
    )
    run = api.run
    api.run = lambda rid, attempt: (
        copy.deepcopy(original_run) if (rid, attempt) == (101, 1) else run(rid, attempt)
    )
    original_repo, original_pages = api.repo, api.pages
    api.repo = lambda method, path, **kwargs: (
        copy.deepcopy(api.runs[101])
        if path == "/actions/runs/101"
        else original_repo(method, path, **kwargs)
    )
    api.pages = lambda path, *args, **kwargs: (
        [copy.deepcopy(api.artifact)]
        if path == "/actions/runs/101/artifacts"
        else original_pages(path, *args, **kwargs)
    )
    monkeypatch.setenv("GITHUB_RUN_ID", "999")
    notice = script.notice(api, 101, api.artifact["name"])
    assert notice.preparation_run.attempt == 1
    assert notice.candidate_artifact.artifact_id == 11


def test_current_producer_can_open_draft_before_run_end_timestamp_updates(tmp_path, monkeypatch):
    from tests.unit.ota.release_package_testkit import load_script

    _repo, api = candidate_fixture(tmp_path)
    script = load_script("ota_premerge")
    api.runs[101].update(
        status="in_progress", conclusion=None, updated_at=api.runs[101]["run_started_at"]
    )
    original_repo, original_pages = api.repo, api.pages
    api.repo = lambda method, path, **kwargs: (
        copy.deepcopy(api.runs[101])
        if path == "/actions/runs/101"
        else original_repo(method, path, **kwargs)
    )
    api.pages = lambda path, *args, **kwargs: (
        [copy.deepcopy(api.artifact)]
        if path == "/actions/runs/101/artifacts"
        else original_pages(path, *args, **kwargs)
    )
    monkeypatch.setenv("GITHUB_RUN_ID", "101")
    monkeypatch.setenv("GITHUB_RUN_ATTEMPT", "1")
    notice = script.notice(api, 101, api.artifact["name"])
    assert notice.preparation_run.attempt == 1


@pytest.mark.parametrize("fault", [None, "stale-main", "legacy"])
def test_main_movement_invalidates_ready_before_owner_merge(tmp_path, fault):
    from talent_data.ota.automation.premerge import invalidate_ready_on_main_change
    from tests.unit.ota.single_merge_e2e_kit import commit_tree
    from tests.unit.ota.test_premerge_proposal import App
    from tests.unit.test_ota_prepare import git

    repo, reader = candidate_fixture(tmp_path)
    original = reader.base
    main = commit_tree(
        repo, git(repo, "rev-parse", original + "^{tree}"), [original], "TEST new main"
    )
    git(repo, "update-ref", "refs/remotes/origin/main", main)
    git(repo, "checkout", "--quiet", "--detach", main)
    reader.pull.update(draft=False, node_id="PR_test")
    reader.pull["base"]["sha"] = main
    run = copy.deepcopy(reader.runs[201])
    run.update(
        id=401,
        event="push",
        head_branch="main",
        head_sha=main,
        status="in_progress",
        conclusion=None,
    )
    reader.runs[401] = run
    pages = reader.pages
    reader.pages = lambda path, *args, **kwargs: (
        [copy.deepcopy(reader.pull)]
        if path == "/pulls" and (kwargs.get("params") or {}).get("base") == "main"
        else pages(path, *args, **kwargs)
    )
    writer = App(reader)
    original_call = writer.call

    def call(method, path, **kwargs):
        if path == "/graphql":
            assert "convertPullRequestToDraft" in kwargs["data"]["query"]
            writer.writes.append((method, path, kwargs["data"]))
            reader.pull["draft"] = True
            return {"data": {}}
        return original_call(method, path, **kwargs)

    writer.call = call
    if fault == "legacy":
        reader.pull["body"] = "legacy, no conditional merge policy"
    pin = RunPin(workflow_path=".github/workflows/ci.yml", run_id=401, attempt=1, head_sha=main)
    if fault == "stale-main":
        pin = pin.model_copy(update={"head_sha": original})
        with pytest.raises(PreparationBlocked):
            invalidate_ready_on_main_change(reader, writer, repo, pin)
        assert not writer.writes
    else:
        invalidate_ready_on_main_change(reader, writer, repo, pin)
        assert reader.pull["draft"] is (fault is None)
        assert len(writer.writes) == (1 if fault is None else 0)
