"""Authorization of requests by the exact committed change and CI run."""

import json
import subprocess
from pathlib import Path

import pytest

from talent_data.ota.automation.prepare import PreparationBlocked
from talent_data.ota.automation.requests import parse_request
from tests.unit.ota.test_automation_requests import request_wire

ROOT = Path(__file__).resolve().parents[3]


def workflow_jobs(name="ota-production-publish.yml"):
    """Read job boundaries by YAML indentation; actionlint validates the full grammar."""
    import re

    text = (ROOT / ".github/workflows" / name).read_text()
    jobs = dict(
        re.findall(
            r"^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)", text.split("jobs:\n", 1)[1], re.M | re.S
        )
    )
    return text, jobs


def test_production_job_topology_serializes_preflights_and_independent_renewal():
    text, jobs = workflow_jobs()
    assert set(jobs) == {"preflight", "writer", "renewal-preflight", "renewal-writer", "notify"}
    assert "concurrency:" not in text.split("jobs:\n", 1)[0]
    for name in ("preflight", "writer", "renewal-preflight", "renewal-writer"):
        assert "group: ota-production-writer" in jobs[name]
        assert "cancel-in-progress: false" in jobs[name]
        assert "contents: write" in jobs[name]
        assert "actions: read" in jobs[name]
    assert "needs: preflight" in jobs["writer"]
    assert "needs: [preflight, writer]" in jobs["renewal-preflight"]
    assert "always()" in jobs["renewal-preflight"]
    assert "github.event_name == 'schedule'" in jobs["renewal-preflight"]
    assert "needs.writer.result == 'success'" not in jobs["renewal-preflight"]
    assert "needs.preflight.result == 'success'" not in jobs["renewal-preflight"]
    assert "OPERATION: resign" in jobs["renewal-preflight"]
    assert '--operation "$OPERATION"' in jobs["renewal-preflight"]
    assert "needs: renewal-preflight" in jobs["renewal-writer"]
    assert (
        "always() && !cancelled() && needs.renewal-preflight.result == 'success'"
        in jobs["renewal-writer"]
    )
    assert "group: ota-production-watch" in jobs["notify"]
    watch, _ = workflow_jobs("ota-production-watch.yml")
    assert "group: ota-production-watch" in watch


def test_production_secrets_artifacts_and_trusted_checkout_boundaries():
    import re

    text, jobs = workflow_jobs()
    assert "pull_request:" not in text and "pull_request_target:" not in text
    assert "default: false" in text
    assert "github.event.workflow_run.head_repository.full_name == github.repository" in text
    for name in ("preflight", "renewal-preflight"):
        assert "secrets." not in jobs[name] and "environment:" not in jobs[name]
    for name in ("writer", "renewal-writer"):
        job = jobs[name]
        assert "environment: ota-production" in job
        assert "!cancelled()" in job
        assert "OTA_PRODUCTION_AUTOMATION_ENABLED == 'true'" in job
        assert ".outputs.execute == 'true'" in job
        assert ".outputs.writer_needed == 'true'" in job
        assert ".outputs.outcome == 'READY'" in job
        assert "TELEGRAM_" not in job and "OTA_APP_" not in job
    assert "environment: notifications" in jobs["notify"]
    assert "continue-on-error: true" in jobs["notify"]
    assert "OTA_PRODUCTION_PRIVATE_KEY_PEM" not in jobs["notify"]
    assert "production-status --status-file" in jobs["notify"]
    assert 'WRITER_RESULT" = cancelled' in jobs["notify"]
    assert 'outcome="AMBIGUOUS"' in jobs["notify"]
    assert "candidate-status.json" in jobs["notify"] and "renewal-status.json" in jobs["notify"]
    # Each command downloads the same state branch into a fresh repository; reusing
    # one directory makes the second git remote-add fail before renewal notification.
    assert '--state-dir "$RUNNER_TEMP/candidate-notify-state"' in jobs["notify"]
    assert '--state-dir "$RUNNER_TEMP/renewal-notify-state"' in jobs["notify"]
    for step in re.split(r"^      - ", jobs["writer"], flags=re.M):
        if "name: Signer smoke" in step:
            assert "OTA_PRODUCTION_PRIVATE_KEY_PEM" in step
            assert "S3_" not in step
        if "name: Initialize journal" in step:
            assert "secrets." not in step
    artifact_names = re.findall(r"^          name: (ota-production-\S+)", text, re.M)
    assert len(set(artifact_names)) == 4  # preflight and writer artifacts never overwrite
    assert "path: ${{ runner.temp }}\n" not in text and "cache:" not in text
    for workflow in (text, workflow_jobs("ota-production-watch.yml")[0]):
        for action in re.findall(r"uses: (\S+)", workflow):
            assert re.fullmatch(r"[\w/-]+@[0-9a-f]{40}", action), action
        for checkout in workflow.split("uses: actions/checkout@")[1:]:
            settings = checkout.split("      - ", 1)[0]
            assert "ref: ${{ github.sha }}" in settings
            assert "fetch-depth: 0" in settings
            assert "persist-credentials: false" in settings


@pytest.mark.parametrize(
    ("event", "mode", "execute", "expected", "write"),
    [
        ("workflow_run", "", "", "publish", "true"),
        ("schedule", "", "", "reconcile", "true"),
        ("workflow_dispatch", "reconcile", "false", "reconcile", "false"),
        ("workflow_dispatch", "signer-smoke", "true", "signer-smoke", "true"),
        ("workflow_dispatch", "init-journal", "true", "init-journal", "true"),
        ("workflow_dispatch", "arbitrary", "true", None, None),
    ],
)
def test_real_workflow_mode_selection_is_closed_and_manual_is_dry_run(
    tmp_path, event, mode, execute, expected, write
):
    import os

    from tests.unit.test_telegram_notify import workflow_step_script

    output = tmp_path / "output"
    run = subprocess.run(
        [
            "bash",
            "-e",
            "-c",
            workflow_step_script("ota-production-publish.yml", "Select operation"),
        ],
        env={
            **os.environ,
            "EVENT": event,
            "MODE": mode,
            "EXECUTE": execute,
            "GITHUB_OUTPUT": str(output),
        },
        capture_output=True,
    )
    if expected is None:
        assert run.returncode != 0
    else:
        assert run.returncode == 0
        assert output.read_text().splitlines() == [f"operation={expected}", f"execute={write}"]


def test_interrupted_workflow_never_claims_no_write_and_preserves_existing_status(tmp_path):
    import os
    import sys

    from talent_data.ota.automation.cli import read_status
    from tests.unit.test_telegram_notify import workflow_step_script

    status = tmp_path / "status.json"
    script = workflow_step_script(
        "ota-production-publish.yml", "Preserve unknown interrupted outcome"
    )
    environment = {
        **os.environ,
        "PATH": str(Path(sys.executable).parent) + os.pathsep + os.environ["PATH"],
        "STATUS_FILE": str(status),
        "OPERATION": "publish",
    }
    first = subprocess.run(["bash", "-e", "-c", script], env=environment, capture_output=True)
    assert first.returncode == 0, first.stderr
    document = read_status(status.read_bytes())
    assert document.outcome == "AMBIGUOUS" and document.exit_code == 3
    assert document.reason == "OUTCOME_UNKNOWN" and not document.writer_needed
    status.write_bytes(b'{"outcome":')  # interrupted emit can leave a partial file
    truncated = subprocess.run(["bash", "-e", "-c", script], env=environment, capture_output=True)
    assert truncated.returncode == 0, truncated.stderr
    assert read_status(status.read_bytes()).outcome == "AMBIGUOUS"
    original = document.model_copy(update={"outcome": "VERIFIED", "reason": "PUBLIC_VERIFIED"})
    status.write_text(original.model_dump_json(by_alias=True))
    second = subprocess.run(["bash", "-e", "-c", script], env=environment, capture_output=True)
    assert second.returncode == 0, second.stderr
    assert read_status(status.read_bytes()) == original


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


@pytest.mark.parametrize("discovery", [False, True])
def test_approval_only_merge_requires_its_own_ci(request_history, monkeypatch, discovery):
    from talent_data.ota.automation import prepare
    from talent_data.ota.automation.authorization import authorize_request, request_change_commit

    repo, request, request_commit, _ = request_history
    monkeypatch.setattr(
        prepare,
        "_main_commit",
        lambda sha: git(repo, "merge-base", "--is-ancestor", sha, "origin/main"),
    )
    approval = repo / "docs/ota/releases" / f"{request.release_id}.md"
    approval.parent.mkdir(parents=True)
    git(repo, "checkout", "-qb", "approval")
    approval.write_text("owner accepts G7 for the exact package")
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "owner approval")
    branch_commit = git(repo, "rev-parse", "HEAD").decode().strip()
    git(repo, "checkout", "main")
    git(repo, "merge", "--no-ff", "approval", "-m", "approval merge")
    merged = git(repo, "rev-parse", "HEAD").decode().strip()
    git(repo, "update-ref", "refs/remotes/origin/main", merged)
    assert request_change_commit(repo, request) == merged
    pin = {} if discovery else {"ci_run_id": 9, "ci_attempt": 2}
    assert authorize_request(API(merged), repo, request, **pin) == merged
    for stale in (request_commit, branch_commit):
        with pytest.raises(PreparationBlocked):
            authorize_request(API(stale), repo, request, **pin)
    # Later work, including another release's approval, cannot authorize this release.
    (approval.parent / "1.60.1.99999-r1.md").write_text("other release")
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "another release")
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    assert authorize_request(API(merged), repo, request, **pin) == merged
    with pytest.raises(PreparationBlocked):
        authorize_request(
            API(git(repo, "rev-parse", "HEAD").decode().strip()), repo, request, **pin
        )
    # Revocation invalidates the previous authorization even with identical request bytes.
    approval.unlink()
    git(repo, "add", "-u")
    git(repo, "commit", "-qm", "withdraw approval")
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    with pytest.raises(PreparationBlocked):
        authorize_request(API(merged), repo, request, **pin)


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
