import pytest

from talent_data.ota.automation.prepare import PreparationBlocked
from talent_data.ota.automation.provenance import RequestRejected
from tests.unit.ota.single_merge_testkit import candidate_fixture, poison_candidate
from tests.unit.test_ota_prepare import git


@pytest.fixture
def context(tmp_path):
    return candidate_fixture(tmp_path)


def verify(context):
    from talent_data.ota.automation.candidate import verify_candidate

    repo, api = context
    return verify_candidate(api, repo, 141, source_sha=api.source)


def test_real_candidate_artifact_binds_git_source_without_checkout(context):
    repo, api = context
    result = verify(context)
    assert result.source_sha == api.source
    assert result.base_sha == api.base
    assert result.tooling_sha == api.base != api.source
    assert result.source_tree == git(repo, "rev-parse", api.source + "^{tree}")
    assert result.candidate_run.run_id == 101
    assert result.ci_pin.run_id == 201
    assert git(repo, "rev-parse", "HEAD") == api.base


@pytest.mark.parametrize(
    "fault",
    [
        "fork",
        "actor",
        "head",
        "base",
        "closed",
        "marker",
        "digest",
        "expired",
        "artifact-origin",
        "prep-event",
        "prep-attempt",
        "prep-job-failed",
        "prep-job-duplicate",
        "prep-job-origin",
        "ci-attempt",
        "ci-full-failed",
        "ci-no-full",
        "ci-foreign-job",
        "ci-pull",
        "wrong-source-tree",
    ],
)
def test_unsafe_candidate_is_never_prepared(context, fault):
    _, a = context
    if fault == "fork":
        a.pull["head"]["repo"]["full_name"] = "foreign/TalentForge"
    elif fault == "actor":
        a.pull["user"]["id"] = 999
    elif fault == "head":
        a.pull["head"]["sha"] = a.base
    elif fault == "base":
        a.pull["base"]["sha"] = "f" * 40
    elif fault == "closed":
        a.pull["state"] = "closed"
    elif fault == "marker":
        a.pull["body"] = "looks approved"
    elif fault == "digest":
        a.raw += b"trailing forged bytes"
    elif fault == "expired":
        a.artifact["expired"] = True
    elif fault == "artifact-origin":
        a.artifact["workflow_run"]["head_sha"] = a.source
    elif fault == "prep-event":
        a.runs[101]["event"] = "pull_request"
    elif fault == "prep-attempt":
        a.notice["preparationRun"]["attempt"] = 2
        a.refresh_body()
    elif fault == "prep-job-failed":
        a.jobs[101][0]["conclusion"] = "failure"
    elif fault == "prep-job-duplicate":
        a.jobs[101].append(a.jobs[101][0].copy())
    elif fault == "prep-job-origin":
        a.jobs[101][0]["head_sha"] = a.source
    elif fault == "ci-attempt":
        a.jobs[201][0]["run_attempt"] = 2
    elif fault == "ci-full-failed":
        a.jobs[201][1]["conclusion"] = "failure"
    elif fault == "ci-no-full":
        a.jobs[201] = [j for j in a.jobs[201] if j["name"] != "full-validation"]
    elif fault == "ci-foreign-job":
        a.jobs[201][0]["head_sha"] = a.base
    elif fault == "ci-pull":
        a.runs[201]["pull_requests"][0]["number"] = 999
    elif fault == "wrong-source-tree":
        a.synthetic_ci(wrong_tree=True)
    with pytest.raises((PreparationBlocked, RequestRejected, ValueError)):
        verify(context)


def test_synthetic_pr_merge_requires_equal_tree_and_exact_parents(context):
    _, api = context
    synthetic = api.synthetic_ci()
    result = verify(context)
    assert result.source_sha == api.source
    assert result.ci_pin.head_sha == synthetic != api.source


@pytest.mark.parametrize("fault", ["code", "symlink", "delete", "rename"])
def test_even_consistently_hashed_artifact_cannot_expand_candidate_scope(context, fault):
    repo, api = context
    poison_candidate(repo, api, fault)
    with pytest.raises((PreparationBlocked, RequestRejected, ValueError)):
        verify(context)
    assert git(repo, "rev-parse", "HEAD") == api.base


def test_changed_mr_during_validation_creates_no_verified_ref(context):
    from subprocess import run

    repo, api = context
    original = api.repo
    count = 0

    def changing(method, path, **kwargs):
        nonlocal count
        if path == "/pulls/141":
            count += 1
            if count == 3:
                api.pull["head"]["sha"] = api.base
        return original(method, path, **kwargs)

    api.repo = changing
    with pytest.raises(PreparationBlocked, match="CANDIDATE_CHANGED"):
        verify(context)
    assert (
        run(
            [
                "git",
                "-C",
                str(repo),
                "show-ref",
                "--verify",
                f"refs/talentforge/candidate/141/{api.source}",
            ],
            capture_output=True,
        ).returncode
        != 0
    )
