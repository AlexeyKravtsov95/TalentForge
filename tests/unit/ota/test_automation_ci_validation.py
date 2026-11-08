"""Metadata fast path needs a real full CI ancestor and the entire safe Git diff."""

import json

import pytest

from talent_data.ota.automation.prepare import PreparationBlocked
from tests.unit.ota.test_automation_probe import git, pending  # noqa: F401

# ruff: noqa: F811


@pytest.fixture
def baseline(pending, monkeypatch):
    _repo, request, api, _ = pending
    run = api.run_record(91, ".github/workflows/ci.yml", request.source_commit, "push")
    jobs = [
        {
            "name": name,
            "status": "completed",
            "conclusion": "success",
            "run_id": 91,
            "run_attempt": 1,
            "head_sha": request.source_commit,
        }
        for name in ("checks", "full-validation")
    ]
    original_run, original_pages = api.run, api.pages
    monkeypatch.setattr(api, "run", lambda n, a: run.copy() if n == 91 else original_run(n, a))

    def pages(path, member, **kwargs):
        if path == "/actions/workflows/ci.yml/runs":
            return [{"id": 91, "run_attempt": 1}]
        if "/91/" in path and member == "jobs":
            return [j.copy() for j in jobs]
        return original_pages(path, member, **kwargs)

    monkeypatch.setattr(api, "pages", pages)
    return pending, run, jobs


def classify(baseline, event="push"):
    from talent_data.ota.automation.ci_validation import classify_changes

    (repo, _, api, _), _, _ = baseline
    return classify_changes(
        api, repo, head_sha=git(repo, "rev-parse", "HEAD").decode().strip(), event=event
    )


def commit(repo, message="TEST metadata"):
    git(repo, "add", ".")
    git(repo, "commit", "-qm", message)
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")


def test_metadata_requires_real_full_validation_ancestor(baseline):
    decision = classify(baseline)
    assert decision.mode == "metadata"
    assert decision.baseline_sha == baseline[1]["head_sha"]
    assert decision.baseline_run_id == 91 and decision.baseline_attempt == 1
    assert len(decision.changed_paths) == 2
    assert classify(baseline, event="pull_request").mode == "full"


@pytest.mark.parametrize(
    "field,value",
    [
        ("repository", {"full_name": "other/repo"}),
        ("head_repository", {"full_name": "other/repo"}),
        ("event", "pull_request"),
        ("path", "other.yml"),
        ("run_attempt", 2),
        ("head_branch", "feature"),
        ("status", "in_progress"),
        ("conclusion", "failure"),
    ],
)
def test_untrusted_or_failed_baseline_forces_full(baseline, field, value):
    baseline[1][field] = value
    assert classify(baseline).mode == "full"


@pytest.mark.parametrize(
    "field,value",
    [
        ("conclusion", "skipped"),
        ("status", "queued"),
        ("run_attempt", 2),
        ("run_id", 999),
        ("head_sha", "f" * 40),
    ],
)
def test_skipped_or_wrong_full_validation_is_not_a_baseline(baseline, field, value):
    baseline[2][1][field] = value
    assert classify(baseline).mode == "full"


@pytest.mark.parametrize(
    "change",
    [
        "delete",
        "rename",
        "copy",
        "symlink",
        "mode",
        "newline",
        "code",
        "workflow",
        "graph",
        "dependency",
    ],
)
def test_non_allowlisted_git_change_forces_full(baseline, change):
    repo, request, _, _ = baseline[0]
    approval = repo / "docs/ota/releases" / f"{request.release_id}.md"
    baseline[1]["head_sha"] = git(repo, "rev-parse", "HEAD").decode().strip()
    for job in baseline[2]:
        job["head_sha"] = baseline[1]["head_sha"]
    if change == "delete":
        approval.unlink()
    elif change in {"rename", "copy"}:
        target = approval.with_name("1.60.1.70451-r1.md")
        if change == "rename":
            approval.rename(target)
        else:
            target.write_bytes(approval.read_bytes())
        # Make the already-approved tree the trusted full baseline to exercise R/C records.
        baseline[1]["head_sha"] = git(repo, "rev-parse", "HEAD").decode().strip()
        for job in baseline[2]:
            job["head_sha"] = baseline[1]["head_sha"]
    elif change == "symlink":
        approval.unlink()
        approval.symlink_to("../../../source.txt")
    elif change == "mode":
        approval.chmod(0o755)
    else:
        relative = {
            "newline": "docs/ota/releases/1.60.1.70451-r1\n.md",
            "code": "src/new.py",
            "workflow": ".github/workflows/ci.yml",
            "graph": "graphify-out/graph.json",
            "dependency": "pyproject.toml",
        }[change]
        path = repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("TEST changed")
    commit(repo)
    assert classify(baseline).mode == "full"


def test_metadata_last_commit_cannot_hide_code_since_full_baseline(baseline):
    repo, request, _, _ = baseline[0]
    (repo / "code.py").write_text("# TEST code change")
    commit(repo, "TEST code")
    approval = repo / "docs/ota/releases" / f"{request.release_id}.md"
    approval.write_text(approval.read_text() + "\nTEST clarification\n")
    commit(repo)
    assert classify(baseline).mode == "full"


def test_missing_or_incomplete_baseline_falls_back_to_full(baseline, monkeypatch):
    api = baseline[0][2]
    monkeypatch.setattr(api, "pages", lambda *args, **kwargs: [])
    assert classify(baseline).mode == "full"


def test_truncated_job_listing_cannot_be_hidden_by_an_older_baseline(baseline, monkeypatch):
    api = baseline[0][2]
    original_pages, original_run = api.pages, api.run
    monkeypatch.setattr(
        api,
        "run",
        lambda n, a: {**original_run(91, a), "id": 92} if n == 92 else original_run(n, a),
    )

    def pages(path, member, **kwargs):
        if path == "/actions/workflows/ci.yml/runs":
            return [{"id": 92, "run_attempt": 1}, {"id": 91, "run_attempt": 1}]
        if "/92/" in path:
            raise PreparationBlocked("LISTING_TRUNCATED", "TEST missing jobs")
        return original_pages(path, member, **kwargs)

    monkeypatch.setattr(api, "pages", pages)
    assert classify(baseline).mode == "full"

    def unavailable(*args, **kwargs):
        raise PreparationBlocked("LISTING_TRUNCATED", "TEST missing history")

    monkeypatch.setattr(api, "pages", unavailable)
    assert classify(baseline).mode == "full"


def test_short_validator_binds_request_and_provenance_without_package_download(baseline):
    from talent_data.ota.automation.ci_validation import validate_metadata_changes

    repo, _, api, journal = baseline[0]
    before = journal.path.read_bytes()
    validate_metadata_changes(api, repo, classify(baseline))
    assert journal.path.read_bytes() == before
    assert not any(c[0] == "archive" for c in api.calls)


@pytest.mark.parametrize("change", ["unknown-request-field", "wrong-approval-pin", "accept-stop"])
def test_invalid_metadata_is_ci_failure_without_green_full_fallback(baseline, change):
    from talent_data.ota.automation.ci_validation import validate_metadata_changes

    repo, request, api, _ = baseline[0]
    if change == "unknown-request-field":
        path = repo / "docs/ota/release-requests" / f"{request.release_id}.json"
        raw = json.loads(path.read_bytes())
        raw["unknown"] = True
        path.write_text(json.dumps(raw))
    else:
        path = repo / "docs/ota/releases" / f"{request.release_id}.md"
        path.write_text(
            path.read_text().replace(request.manifest_sha256, "sha256:" + "e" * 64)
            if change == "wrong-approval-pin"
            else path.read_text().replace("Accepted gates: G7", "Accepted gates: G1")
        )
    commit(repo)
    decision = classify(baseline)
    assert decision.mode == "metadata"
    from talent_data.ota.verify import OtaError

    with pytest.raises((PreparationBlocked, OtaError, ValueError)):
        validate_metadata_changes(api, repo, decision)


def test_short_validator_does_not_fill_owner_template(baseline):
    from talent_data.ota.automation.ci_validation import validate_metadata_changes

    repo, request, api, _ = baseline[0]
    path = repo / "docs/ota/releases" / f"{request.release_id}.md"
    path.write_text(
        path.read_text().replace("Accepted gates: G7", "Accepted gates: <OWNER: gates>")
    )
    commit(repo)
    before = path.read_bytes()
    validate_metadata_changes(api, repo, classify(baseline))
    assert path.read_bytes() == before and b"<OWNER:" in before


def test_ci_workflow_retains_full_checks_and_strict_selected_branch_aggregator(tmp_path):
    import os
    import re
    import subprocess

    from tests.unit.ota.test_automation_workflows import workflow_jobs
    from tests.unit.test_telegram_notify import workflow_step_script

    text, jobs = workflow_jobs("ci.yml")
    assert set(jobs) == {"classify", "full-validation", "metadata-validation", "checks"}
    for command in (
        "ruff check .",
        "ruff format --check .",
        "mypy",
        "run: pytest",
        "git cat-file -e 'c749ebc5fc31c43be742844cb89d012f100a2a0a^{commit}'",
    ):
        assert command in jobs["full-validation"]
    assert "if: always()" in jobs["checks"]
    assert "contents: read" in text and "actions: read" in text and "secrets." not in text
    for action in re.findall(r"uses: (\S+)", text):
        assert re.fullmatch(r"[\w/-]+@[0-9a-f]{40}", action)
    for checkout in text.split("uses: actions/checkout@")[1:]:
        settings = checkout.split("      - ", 1)[0]
        assert "fetch-depth: 0" in settings and "persist-credentials: false" in settings
    script = workflow_step_script("ci.yml", "Require selected validation")
    for mode, full, metadata, classify, ok in [
        ("full", "success", "skipped", "success", True),
        ("metadata", "skipped", "success", "success", True),
        ("metadata", "skipped", "skipped", "success", False),
        ("full", "failure", "skipped", "success", False),
        ("unknown", "success", "success", "success", False),
        ("metadata", "success", "success", "success", False),
        ("full", "success", "skipped", "failure", False),
    ]:
        result = subprocess.run(
            ["bash", "-e", "-c", script],
            env={
                **os.environ,
                "MODE": mode,
                "FULL_RESULT": full,
                "METADATA_RESULT": metadata,
                "CLASSIFY_RESULT": classify,
            },
            capture_output=True,
        )
        assert (result.returncode == 0) == ok
