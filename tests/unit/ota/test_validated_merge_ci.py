"""Full final PR CI is reusable only by its exact ordinary main merge."""

import copy
import os
import subprocess

import pytest

from talent_data.ota.automation.prepare import PreparationBlocked
from tests.unit.ota.test_owner_merge_authorization import merged
from tests.unit.test_ota_prepare import git
from tests.unit.test_telegram_notify import workflow_step_script


def fixture(tmp_path, monkeypatch):
    from talent_data.ota.automation import ci_validation as ci

    data = merged(tmp_path)
    repo, _api, _, binding, *_ = data
    git(repo, "checkout", "--quiet", "--detach", data[-1])
    # The independent package archive verifier has its own real-package suite.
    # Keep this test about Git/PR/CI reuse and final job aggregation.
    monkeypatch.setattr(ci, "load_merge_binding", lambda *args: binding)
    return data


def test_exact_main_merge_reuses_final_full_pr_validation(tmp_path, monkeypatch):
    from talent_data.ota.automation import ci_validation as ci

    repo, api, _request, _binding, _, head, main = fixture(tmp_path, monkeypatch)
    proof = ci.verify_validated_merge(api, repo, main)
    assert proof.merge_sha == main and proof.final_head_sha == head and proof.pr_ci.run_id == 301
    decision = ci.classify_changes(api, repo, head_sha=main, event="push")
    assert decision.mode == "validated-merge"
    assert decision.baseline_run_id == 301 and decision.baseline_sha == head
    ci.validate_merge_decision(api, repo, decision)
    assert ci.classify_changes(api, repo, head_sha=main, event="pull_request").mode == "full"


@pytest.mark.parametrize(
    "fault", ["failed-full", "metadata-instead", "wrong-merge-tree", "code-after", "wrong-pin"]
)
def test_unsafe_merge_never_uses_redundant_ci_shortcut(tmp_path, monkeypatch, fault):
    from talent_data.ota.automation import ci_validation as ci

    data = fixture(tmp_path, monkeypatch)
    repo, api, _request, binding, _, _head, main = data
    if fault == "failed-full":
        api.jobs[301][1]["conclusion"] = "failure"
    elif fault == "metadata-instead":
        api.jobs[301][1]["name"] = "metadata-validation"
    elif fault == "wrong-merge-tree":
        original = api.repo

        def read(method, path, **kwargs):
            value = original(method, path, **kwargs)
            if path == "/git/commits/" + main:
                value["tree"]["sha"] = binding.source_tree
            return value

        api.repo = read
    elif fault == "code-after":
        (repo / "code.py").write_text("foreign=1\n")
        git(repo, "add", "code.py")
        git(
            repo,
            "-c",
            "user.name=test",
            "-c",
            "user.email=test@example.invalid",
            "commit",
            "-qm",
            "code",
        )
        main = git(repo, "rev-parse", "HEAD")
        git(repo, "update-ref", "refs/remotes/origin/main", main)
    else:
        decision = ci.classify_changes(api, repo, head_sha=main, event="push")
        with pytest.raises(PreparationBlocked):
            ci.validate_merge_decision(
                api, repo, decision.model_copy(update={"baseline_run_id": 201})
            )
        return
    assert ci.classify_changes(api, repo, head_sha=main, event="push").mode == "full"


@pytest.mark.parametrize(
    "fault", [None, "foreign-job", "two-selected", "missing-skip", "failed-merge"]
)
def test_main_evidence_keeps_own_success_and_rechecks_reuse(tmp_path, monkeypatch, fault):
    from talent_data.ota.automation import ci_validation as ci

    repo, api, *rest = fixture(tmp_path, monkeypatch)
    main = rest[-1]
    run = copy.deepcopy(api.runs[301])
    run.update(id=401, event="push", head_branch="main", head_sha=main)
    jobs = [
        {
            "id": 4010 + n,
            "name": name,
            "run_id": 401,
            "run_attempt": 1,
            "head_sha": main,
            "status": "completed",
            "conclusion": result,
        }
        for n, (name, result) in enumerate(
            [
                ("classify", "success"),
                ("merge-validation", "success"),
                ("full-validation", "skipped"),
                ("metadata-validation", "skipped"),
                ("checks", "success"),
            ]
        )
    ]
    if fault == "foreign-job":
        jobs[1]["head_sha"] = "f" * 40
    elif fault == "two-selected":
        jobs[2]["conclusion"] = "success"
    elif fault == "missing-skip":
        jobs.pop(3)
    elif fault == "failed-merge":
        jobs[1]["conclusion"] = "failure"
    if fault is None:
        ci.verify_main_ci_evidence(api, repo, run, jobs)
    else:
        with pytest.raises(PreparationBlocked):
            ci.verify_main_ci_evidence(api, repo, run, jobs)


@pytest.mark.parametrize(
    "mode,full,metadata,merge,ok",
    [
        ("validated-merge", "skipped", "skipped", "success", True),
        ("validated-merge", "success", "skipped", "success", False),
        ("validated-merge", "skipped", "success", "success", False),
        ("validated-merge", "skipped", "skipped", "skipped", False),
        ("full", "success", "skipped", "success", False),
        ("metadata", "skipped", "success", "skipped", True),
    ],
)
def test_real_checks_shell_requires_exactly_one_selected_branch(mode, full, metadata, merge, ok):
    script = workflow_step_script("ci.yml", "Require selected validation")
    result = subprocess.run(
        ["bash", "-e", "-c", script],
        env={
            **os.environ,
            "MODE": mode,
            "FULL_RESULT": full,
            "METADATA_RESULT": metadata,
            "MERGE_RESULT": merge,
            "CLASSIFY_RESULT": "success",
        },
        capture_output=True,
    )
    assert (result.returncode == 0) == ok
