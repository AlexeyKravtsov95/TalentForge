"""Authorization of requests by the exact committed change and CI run."""

import json
import subprocess

import pytest

from talent_data.ota.automation.prepare import PreparationBlocked
from talent_data.ota.automation.requests import parse_request
from tests.unit.ota.test_automation_requests import request_wire


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True).stdout


@pytest.fixture
def request_history(tmp_path):
    request = parse_request(json.dumps(request_wire()).encode())
    repo = tmp_path / "repo"
    repo.mkdir()
    git(repo, "init", "-q", "-b", "main")
    git(repo, "config", "user.name", "TEST")
    git(repo, "config", "user.email", "test@example.invalid")
    path = repo / "docs/ota/release-requests" / f"{request.release_id}.json"
    path.parent.mkdir(parents=True)
    path.write_text(request.model_dump_json(by_alias=True))
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "release request")
    commit = git(repo, "rev-parse", "HEAD").decode().strip()
    git(repo, "update-ref", "refs/remotes/origin/main", commit)
    return repo, request, commit, path


class API:
    def __init__(self, commit):
        self.commit = commit

    def pages(self, path, member, **kwargs):
        if member == "jobs":
            return [{"name": "checks", "status": "completed", "conclusion": "success"}]
        return [{"id": 9, "run_attempt": 2}]

    def run(self, run_id, attempt):
        return {
            "id": run_id,
            "run_attempt": attempt,
            "path": ".github/workflows/ci.yml",
            "name": "CI",
            "event": "push",
            "head_branch": "main",
            "status": "completed",
            "conclusion": "success",
            "head_sha": self.commit,
            "repository": {"full_name": "AlexeyKravtsov95/TalentForge"},
            "head_repository": {"full_name": "AlexeyKravtsov95/TalentForge"},
        }


def test_current_committed_request_requires_its_exact_successful_ci(request_history, monkeypatch):
    from talent_data.ota.automation import prepare
    from talent_data.ota.automation.authorization import authorize_request, committed_requests

    repo, request, commit, path = request_history
    monkeypatch.setattr(prepare, "_main_commit", lambda _: None)
    assert committed_requests(repo) == (request,)
    assert authorize_request(API(commit), repo, request, ci_run_id=9, ci_attempt=2) == commit
    with pytest.raises(PreparationBlocked):
        authorize_request(API("f" * 40), repo, request, ci_run_id=9, ci_attempt=2)
    path.unlink()
    git(repo, "add", "-u")
    git(repo, "commit", "-qm", "remove")
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    assert committed_requests(repo) == ()
    with pytest.raises(PreparationBlocked):
        authorize_request(API(commit), repo, request, ci_run_id=9, ci_attempt=2)


def test_reconciliation_discovers_ci_for_change_not_later_unrelated_commit(
    request_history, monkeypatch
):
    from talent_data.ota.automation import prepare
    from talent_data.ota.automation.authorization import authorize_request

    repo, request, commit, _ = request_history
    monkeypatch.setattr(prepare, "_main_commit", lambda _: None)
    (repo / "other").write_text("unrelated")
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "unrelated")
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    assert authorize_request(API(commit), repo, request) == commit
    with pytest.raises(PreparationBlocked):
        authorize_request(API(git(repo, "rev-parse", "HEAD").decode().strip()), repo, request)


def test_ci_with_foreign_repository_or_failed_checks_is_refused(request_history, monkeypatch):
    from talent_data.ota.automation import prepare
    from talent_data.ota.automation.authorization import authorize_request

    repo, request, commit, _ = request_history
    monkeypatch.setattr(prepare, "_main_commit", lambda _: None)

    class Foreign(API):
        def run(self, run_id, attempt):
            run = super().run(run_id, attempt)
            run["head_repository"] = {"full_name": "attacker/fork"}
            return run

    class Failed(API):
        def pages(self, path, member, **kwargs):
            if member == "jobs":
                return [{"name": "checks", "status": "completed", "conclusion": "failure"}]
            return super().pages(path, member, **kwargs)

    for api in (Foreign(commit), Failed(commit)):
        with pytest.raises(PreparationBlocked):
            authorize_request(api, repo, request, ci_run_id=9, ci_attempt=2)


def test_invocation_rejects_untrusted_ref_actor_and_event(request_history):
    from talent_data.ota.automation.authorization import verify_invocation

    repo, _, commit, _ = request_history
    run = {
        "id": 11,
        "run_attempt": 1,
        "path": ".github/workflows/ota-production-publish.yml",
        "event": "workflow_dispatch",
        "head_sha": commit,
        "head_branch": "main",
        "repository": {"full_name": "AlexeyKravtsov95/TalentForge"},
        "head_repository": {"full_name": "AlexeyKravtsov95/TalentForge"},
        "actor": {"login": "operator"},
    }

    class InvocationAPI(API):
        def run(self, run_id, attempt):
            return run

        def repo(self, method, path):
            assert path == "/collaborators/operator/permission"
            return {"permission": "write"}

    api = InvocationAPI(commit)
    environment = {
        "GITHUB_REPOSITORY": "AlexeyKravtsov95/TalentForge",
        "GITHUB_RUN_ID": "11",
        "GITHUB_RUN_ATTEMPT": "1",
        "GITHUB_EVENT_NAME": "workflow_dispatch",
        "GITHUB_REF": "refs/heads/main",
        "GITHUB_SHA": commit,
        "GITHUB_ACTOR": "operator",
    }
    payload = {
        "repository": {"full_name": "AlexeyKravtsov95/TalentForge"},
        "sender": {"login": "operator"},
    }
    verify_invocation(api, repo, environment, payload)
    for field, value in (
        ("GITHUB_REF", "refs/heads/attacker"),
        ("GITHUB_ACTOR", "attacker"),
        ("GITHUB_EVENT_NAME", "pull_request"),
    ):
        with pytest.raises(PreparationBlocked):
            verify_invocation(api, repo, {**environment, field: value}, payload)


def test_workflow_configuration_check_refuses_missing_app_credentials():
    import os
    import sys
    from pathlib import Path

    root = Path(__file__).resolve().parents[3]
    env = {
        k: v for k, v in os.environ.items() if k not in ("OTA_APP_CLIENT_ID", "OTA_APP_PRIVATE_KEY")
    }
    env["PYTHONPATH"] = str(root / "src")
    run = subprocess.run(
        [sys.executable, str(root / "scripts/ota_release_automation.py"), "check-app"],
        env=env,
        capture_output=True,
        text=True,
    )
    assert run.returncode == 3 and "BLOCKED" in run.stdout


def test_request_introduced_on_feature_branch_is_authorized_by_merge_commit(
    request_history, monkeypatch
):
    from talent_data.ota.automation import prepare
    from talent_data.ota.automation.authorization import authorize_request, request_change_commit

    repo, request, _, path = request_history
    monkeypatch.setattr(prepare, "_main_commit", lambda _: None)
    path.unlink()
    git(repo, "add", "-u")
    git(repo, "commit", "-qm", "base without request")
    git(repo, "checkout", "-qb", "release")
    path.write_text(request.model_dump_json(by_alias=True) + "\n")
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "request on branch")
    feature = git(repo, "rev-parse", "HEAD").decode().strip()
    git(repo, "checkout", "main")
    (repo / "unrelated").write_text("main work")
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "advance main")
    git(repo, "merge", "--no-ff", "release", "-m", "release merge")
    merged = git(repo, "rev-parse", "HEAD").decode().strip()
    git(repo, "update-ref", "refs/remotes/origin/main", merged)
    assert request_change_commit(repo, request) == merged
    assert authorize_request(API(merged), repo, request, ci_run_id=9, ci_attempt=2) == merged
    with pytest.raises(PreparationBlocked):
        authorize_request(API(feature), repo, request, ci_run_id=9, ci_attempt=2)


@pytest.mark.parametrize(
    "fault",
    ["path", "repository", "event", "conclusion", "checks", "attempt", "action"],
)
def test_workflow_run_proves_entire_source_ci_before_any_request_selection(
    request_history, monkeypatch, fault
):
    from talent_data.ota.automation import prepare
    from talent_data.ota.automation.authorization import verify_invocation

    repo, _, commit, _ = request_history
    monkeypatch.setattr(prepare, "_main_commit", lambda _: None)
    environment = {
        "GITHUB_REPOSITORY": "AlexeyKravtsov95/TalentForge",
        "GITHUB_RUN_ID": "11",
        "GITHUB_RUN_ATTEMPT": "1",
        "GITHUB_EVENT_NAME": "workflow_run",
        "GITHUB_REF": "refs/heads/main",
        "GITHUB_SHA": commit,
        "GITHUB_ACTOR": "operator",
    }
    payload = {
        "action": "completed",
        "repository": {"full_name": "AlexeyKravtsov95/TalentForge"},
        "workflow_run": {"id": 9, "run_attempt": 2, "head_sha": commit},
    }

    class InvocationAPI(API):
        def run(self, run_id, attempt):
            run = super().run(run_id, attempt)
            if run_id == 11:
                run.update(
                    path=".github/workflows/ota-production-publish.yml",
                    event="workflow_run",
                    actor={"login": "operator"},
                )
                return run
            if fault == "attempt":
                run["run_attempt"] = 1
            elif fault in ("path", "repository", "event", "conclusion"):
                run[fault] = {
                    "path": ".github/workflows/evil.yml",
                    "repository": {"full_name": "attacker/fork"},
                    "event": "pull_request",
                    "conclusion": "failure",
                }[fault]
            return run

        def pages(self, path, member, **kwargs):
            if fault == "checks":
                return [{"name": "checks", "status": "completed", "conclusion": "failure"}]
            return super().pages(path, member, **kwargs)

    if fault == "action":
        payload["action"] = "requested"
    with pytest.raises(PreparationBlocked):
        verify_invocation(InvocationAPI(commit), repo, environment, payload)


def test_automatic_webhook_sender_can_differ_from_api_run_actor(request_history, monkeypatch):
    from talent_data.ota.automation import prepare
    from talent_data.ota.automation.authorization import verify_invocation

    repo, _, commit, _ = request_history
    monkeypatch.setattr(prepare, "_main_commit", lambda _: None)

    class InvocationAPI(API):
        def run(self, run_id, attempt):
            run = super().run(run_id, attempt)
            if run_id == 11:
                run.update(
                    path=".github/workflows/ota-production-publish.yml",
                    event="workflow_run",
                    actor={"login": "operator"},
                )
            return run

    verify_invocation(
        InvocationAPI(commit),
        repo,
        {
            "GITHUB_REPOSITORY": "AlexeyKravtsov95/TalentForge",
            "GITHUB_RUN_ID": "11",
            "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_EVENT_NAME": "workflow_run",
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_SHA": commit,
            "GITHUB_ACTOR": "operator",
        },
        {
            "action": "completed",
            "repository": {"full_name": "AlexeyKravtsov95/TalentForge"},
            "sender": {"login": "github-actions[bot]"},
            "workflow_run": {"id": 9, "run_attempt": 2, "head_sha": commit},
        },
    )


def test_production_wrapper_disabled_and_missing_configuration_are_explicit(tmp_path):
    import os
    import sys
    from pathlib import Path

    root = Path(__file__).resolve().parents[3]
    environment = {
        k: v
        for k, v in os.environ.items()
        if not k.startswith("OTA_PRODUCTION_") and k != "GITHUB_TOKEN"
    }
    environment["PYTHONPATH"] = str(root / "src")
    status = tmp_path / "status.json"
    command = [
        sys.executable,
        str(root / "scripts/ota_release_automation.py"),
        "preflight",
        "--status-file",
        str(status),
    ]
    disabled = subprocess.run(command, env=environment, capture_output=True, text=True)
    assert disabled.returncode == 0
    assert json.loads(status.read_bytes())["outcome"] == "DISABLED"
    environment["OTA_PRODUCTION_AUTOMATION_ENABLED"] = "true"
    blocked = subprocess.run(command, env=environment, capture_output=True, text=True)
    assert blocked.returncode == 1
    assert json.loads(status.read_bytes())["outcome"] == "BLOCKED"
    assert not disabled.stderr and not blocked.stderr
