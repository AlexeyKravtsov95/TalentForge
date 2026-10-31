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
