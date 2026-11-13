"""Run the trusted CLI selected by the actual workflow predicates for CI lifecycle events."""

import copy
import re
from types import SimpleNamespace as NS

import pytest

from talent_data.ota.automation import premerge
from talent_data.ota.automation.prepare import PreparationBlocked
from tests.unit.ota.release_package_testkit import load_script
from tests.unit.ota.test_automation_workflows import workflow_jobs
from tests.unit.ota.test_premerge_proposal import setup


def evaluate(expression, github, needs):
    # This is checked-in workflow code, never event/user text evaluated as code.
    expression = expression.replace("&&", " and ").replace("||", " or ")
    return eval(
        expression,
        {"__builtins__": {}},
        {
            "github": github,
            "needs": needs,
            "always": lambda: True,
            "startsWith": lambda text, prefix: text.startswith(prefix),
        },
    )


def condition(job, github, needs):
    folded = re.search(r"^    if: >-\n((?:      .*\n)+)", job, re.M)
    assert folded is not None
    return bool(evaluate(" ".join(folded[1].split()), github, needs))


@pytest.mark.parametrize(
    "action,conclusion,genuine_failure",
    [
        ("in_progress", None, False),
        ("completed", "success", False),
        ("completed", "failure", False),
        ("completed", "cancelled", False),
        ("completed", "success", True),
    ],
)
def test_workflow_routes_ci_events_without_false_failures(
    tmp_path, monkeypatch, action, conclusion, genuine_failure
):
    repo, reader, writer, candidate, request, binding = setup(tmp_path)
    head = premerge.bind_candidate_request(reader, writer, repo, candidate, request, binding)
    reader.pull.update(node_id="PR_test", draft=action == "completed" and conclusion == "success")
    reader.runs[301] = copy.deepcopy(reader.runs[201])
    reader.runs[301].update(
        id=301,
        head_sha=head,
        status="in_progress" if action == "in_progress" else "completed",
        conclusion=conclusion,
    )
    reader.runs[301]["pull_requests"][0]["head"]["sha"] = head
    reader.jobs[301] = copy.deepcopy(reader.jobs[201])
    for n, job in enumerate(reader.jobs[301]):
        job.update(id=3010 + n, run_id=301, head_sha=head)
    pages = reader.pages
    reader.pages = lambda path, *args, **kwargs: (
        [copy.deepcopy(reader.runs[301]), copy.deepcopy(reader.runs[201])]
        if path == "/actions/workflows/ci.yml/runs"
        else pages(path, *args, **kwargs)
    )
    review = premerge.CandidateReview(
        copy.deepcopy(reader.pull),
        request,
        binding,
        candidate,
        ({"gate": "G7", "status": "FLAG", "detail": "ordinary owner merge"},),
        403,
        5125504,
    )
    # Only package loading is isolated; CI, Git metadata, CLI routing and readiness are real.
    monkeypatch.setattr(premerge, "review_package", lambda *args: review)
    original = writer.call

    def call(method, path, **kwargs):
        if path == "/graphql" and "updateRefs" not in kwargs["data"]["query"]:
            writer.writes.append((method, path, kwargs["data"]))
            reader.pull["draft"] = "convertPullRequestToDraft" in kwargs["data"]["query"]
            return {"data": {}}
        return original(method, path, **kwargs)

    writer.call = call
    writer.writes.clear()
    script = load_script("ota_premerge")
    monkeypatch.setattr(script, "ROOT", repo)
    monkeypatch.setattr(
        script, "GitHub", lambda _client, token: reader if token == "reader" else writer
    )
    for key, value in {
        "GITHUB_REPOSITORY": "AlexeyKravtsov95/TalentForge",
        "GITHUB_REF": "refs/heads/main",
        "GITHUB_SHA": reader.base,
        "GITHUB_EVENT_NAME": "workflow_run",
        "GH_TOKEN": "reader",
        "OTA_APP_TOKEN": "writer",
        "GITHUB_OUTPUT": str(tmp_path / "outputs"),
    }.items():
        monkeypatch.setenv(key, value)
    monkeypatch.setattr(
        script,
        "prepare_candidate",
        lambda *args, **kwargs: pytest.fail("already bound H must not dispatch another package"),
    )
    if genuine_failure:

        def rejected(*args):
            raise PreparationBlocked("CANDIDATE_CHANGED", "genuine preparation refusal")

        monkeypatch.setattr(script, "route_candidate_ci", rejected)
    _, jobs = workflow_jobs("ota-release-prepare.yml")
    github = NS(
        repository="AlexeyKravtsov95/TalentForge",
        event_name="workflow_run",
        event=NS(
            action=action,
            workflow_run=NS(
                event="pull_request",
                conclusion=conclusion,
                head_branch=reader.branch,
                head_repository=NS(full_name="AlexeyKravtsov95/TalentForge"),
            ),
        ),
    )
    needs = NS(premerge=NS(result="skipped", outputs=NS(phase="")))
    common = ["--ci-run-id", "301", "--ci-attempt", "1", "--out", str(tmp_path / "out")]
    if condition(jobs["premerge"], github, needs):
        code = script.main(["prepare", *common])
        needs.premerge.result = "success" if code == 0 else "failure"
        output = tmp_path / "outputs"
        if output.exists():
            needs.premerge.outputs.phase = output.read_text().strip().split("=", 1)[1]
    if condition(jobs["premerge-review"], github, needs):
        phase = re.search(r"^          PHASE: \${{ (.*) }}$", jobs["premerge-review"], re.M)
        assert phase is not None
        selected = evaluate(phase[1], github, needs)
        assert selected == (
            "ready" if conclusion == "success" and not genuine_failure else "invalidate"
        )
        assert script.main([selected, *common, "--request", str(tmp_path / "request.json")]) == 0
    else:
        pytest.fail("a normal CI lifecycle event must be handled")
    assert (needs.premerge.result == "failure") is genuine_failure
    assert (needs.premerge.result == "success") == (conclusion == "success" and not genuine_failure)
    assert reader.pull["draft"] is (conclusion != "success" or genuine_failure)
    assert all(path == "/graphql" or path == "/pulls/141" for _, path, _ in writer.writes)


@pytest.mark.parametrize(
    "event,action,expected",
    [
        ("push", "in_progress", False),
        ("push", "completed", True),
    ],
)
def test_legacy_preparation_also_waits_for_completed_success(event, action, expected):
    _, jobs = workflow_jobs("ota-release-prepare.yml")
    github = NS(
        repository="AlexeyKravtsov95/TalentForge",
        event_name="workflow_run",
        event=NS(
            action=action,
            workflow_run=NS(
                event=event,
                conclusion="success",
                head_branch="main",
                head_repository=NS(full_name="AlexeyKravtsov95/TalentForge"),
            ),
        ),
    )
    assert condition(jobs["prepare"], github, NS()) is expected


@pytest.mark.parametrize(
    "action,conclusion,expected",
    [
        ("in_progress", None, True),
        ("completed", "success", False),
        ("completed", "failure", False),
    ],
)
def test_main_base_invalidation_is_only_the_start_handler(action, conclusion, expected):
    _, jobs = workflow_jobs("ota-release-prepare.yml")
    github = NS(
        repository="AlexeyKravtsov95/TalentForge",
        event_name="workflow_run",
        event=NS(
            action=action,
            workflow_run=NS(
                event="push",
                conclusion=conclusion,
                head_branch="main",
                head_repository=NS(full_name="AlexeyKravtsov95/TalentForge"),
            ),
        ),
    )
    assert condition(jobs["premerge-main-change"], github, NS()) is expected


@pytest.mark.parametrize("fault", ["fork", "repository", "branch", "push", "dispatch"])
def test_unrelated_events_do_not_allocate_the_premerge_app_job(fault):
    _, jobs = workflow_jobs("ota-release-prepare.yml")
    github = NS(
        repository="AlexeyKravtsov95/TalentForge",
        event_name="workflow_run",
        event=NS(
            action="in_progress",
            workflow_run=NS(
                event="pull_request",
                conclusion=None,
                head_branch="ota-candidate/forever-1.60.1.71000",
                head_repository=NS(full_name="AlexeyKravtsov95/TalentForge"),
            ),
        ),
    )
    if fault == "fork":
        github.event.workflow_run.head_repository.full_name = "foreign/repo"
    elif fault == "repository":
        github.repository = "foreign/repo"
    elif fault == "branch":
        github.event.workflow_run.head_branch = "codex/unrelated"
    elif fault == "push":
        github.event.workflow_run.event = "push"
    else:
        github.event_name = "workflow_dispatch"
    needs = NS(premerge=NS(result="skipped", outputs=NS(phase="")))
    assert not condition(jobs["premerge"], github, needs)
    assert not condition(jobs["premerge-review"], github, needs)


@pytest.mark.parametrize("phase", ["bind", "ready"])
def test_completed_success_passes_through_the_verified_handoff(phase):
    _, jobs = workflow_jobs("ota-release-prepare.yml")
    github = NS(
        repository="AlexeyKravtsov95/TalentForge",
        event_name="workflow_run",
        event=NS(
            action="completed",
            workflow_run=NS(
                event="pull_request",
                conclusion="success",
                head_branch="ota-candidate/forever-1.60.1.71000",
                head_repository=NS(full_name="AlexeyKravtsov95/TalentForge"),
            ),
        ),
    )
    needs = NS(premerge=NS(result="success", outputs=NS(phase=phase)))
    assert condition(jobs["premerge-review"], github, needs)
    expression = re.search(r"^          PHASE: \${{ (.*) }}$", jobs["premerge-review"], re.M)
    assert expression is not None
    assert evaluate(expression[1], github, needs) == phase
