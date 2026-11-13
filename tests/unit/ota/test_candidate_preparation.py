"""Preparing a candidate never changes the default production main ancestry rule."""

import json
import os
import subprocess
from types import SimpleNamespace

import pytest

from talent_data.ota.automation.prepare import PreparationBlocked
from talent_data.ota.gates import GateStatus, gate_g1_source
from talent_data.prepare import staging_verify as sv
from tests.unit.ota.single_merge_testkit import candidate_fixture
from tests.unit.test_ota_prepare import BASE, BUILD, git


@pytest.fixture
def context(tmp_path):
    from talent_data.ota.automation.candidate import verify_candidate

    repo, api = candidate_fixture(tmp_path)
    return repo, api, verify_candidate(api, repo, 141, source_sha=api.source)


def test_preparation_context_uses_candidate_ref_without_relaxing_main(context):
    repo, api, ctx = context
    with pytest.raises(sv.InputError, match="not on"):
        sv.resolve_subject(repo, api.source, BASE, BUILD)
    subject = sv.resolve_subject(repo, api.source, BASE, BUILD, candidate_context=ctx)
    assert subject.sha == api.source
    assert subject.verification_ref == ctx.verified_ref
    assert "candidate" in subject.source
    assert git(repo, "rev-parse", "HEAD") == api.base
    under_test = SimpleNamespace(
        manifest=SimpleNamespace(provenance=SimpleNamespace(repository_commit=api.source))
    )
    assert gate_g1_source(under_test, repo).status is GateStatus.STOP
    ready = gate_g1_source(under_test, repo, main_ref=subject.verification_ref)
    assert ready.status is GateStatus.PASS and ctx.verified_ref in ready.detail
    git(repo, "update-ref", "refs/remotes/origin/main", api.source)
    assert gate_g1_source(under_test, repo).status is GateStatus.PASS


@pytest.mark.parametrize("fault", ["source", "base", "candidate", "ref", "checkout"])
def test_preparation_context_cannot_be_reused_for_other_inputs(context, fault):
    repo, api, ctx = context
    source, base, build = api.source, BASE, BUILD
    if fault == "source":
        source = api.base
    elif fault == "base":
        base = BUILD
    elif fault == "candidate":
        build = "1.60.1.70999"
    elif fault == "ref":
        git(repo, "update-ref", ctx.verified_ref, api.base)
    elif fault == "checkout":
        git(repo, "checkout", "--quiet", "--detach", api.source)
    with pytest.raises(sv.InputError):
        sv.resolve_subject(repo, source, base, build, candidate_context=ctx)


def test_candidate_cli_default_does_not_read_token_or_contact_github(tmp_path, monkeypatch):
    from talent_data.ota.automation.candidate import candidate_context_from_cli

    monkeypatch.delenv("GH_TOKEN", raising=False)
    assert candidate_context_from_cli(tmp_path, source_sha="origin/main") is None


@pytest.mark.parametrize(
    "args",
    [
        {"pull_number": 141},
        {"ci_run_id": 201, "ci_attempt": 1},
        {"pull_number": 141, "ci_run_id": 201, "ci_attempt": 1, "source_sha": "origin/main"},
    ],
)
def test_partial_or_non_sha_candidate_cli_never_becomes_a_bypass(tmp_path, args):
    from talent_data.ota.automation.candidate import candidate_context_from_cli

    with pytest.raises(PreparationBlocked, match="CANDIDATE_INPUT"):
        candidate_context_from_cli(tmp_path, **args)


def test_candidate_cli_requires_reader_evidence_and_keeps_actual_pin(context):
    from talent_data.ota.automation.candidate import candidate_context_from_cli

    repo, api, ctx = context
    actual = candidate_context_from_cli(
        repo, pull_number=141, source_sha=api.source, ci_run_id=201, ci_attempt=1, api=api
    )
    assert actual == ctx


def test_staging_resolve_reports_the_verified_candidate_context(context, tmp_path, monkeypatch):
    from scripts import ota_staging_verify as script

    repo, api, ctx = context
    monkeypatch.setattr(script, "ROOT", repo)
    report = script.Run(tmp_path / "staging")
    subject = script.phase_resolve(report, api.source, BASE, BUILD, candidate_context=ctx)
    assert subject is not None and subject.verification_ref == ctx.verified_ref
    assert report.facts["subject"]["sha"] == api.source
    assert report.facts["subject"]["verificationRef"] == ctx.verified_ref


@pytest.mark.parametrize(
    "workflow,job,step",
    [
        ("ota-staging-verify.yml", "verify", "Staging verification"),
        ("ota-release-package.yml", "package", "Build the release package"),
    ],
)
def test_workflow_passes_candidate_identity_as_literal_cli_arguments(tmp_path, workflow, job, step):
    from tests.unit.ota.test_automation_workflows import workflow_jobs

    _, jobs = workflow_jobs(workflow)
    lines = jobs[job].split(f"      - name: {step}\n", 1)[1].splitlines()
    start = lines.index("        run: |")
    body = []
    for line in lines[start + 1 :]:
        if line and not line.startswith(" " * 10):
            break
        body.append(line[10:])
    script = "\n".join(body) + "\n"
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    executable = bin_dir / "python"
    executable.write_text(
        "#!/usr/bin/env python3\nimport json,os,sys\n"
        "open(os.environ['CAPTURE'],'w').write(json.dumps(sys.argv[1:]))\n"
    )
    executable.chmod(0o755)
    capture = tmp_path / "argv.json"
    env = {
        **os.environ,
        "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
        "RUNNER_TEMP": str(tmp_path),
        "GITHUB_OUTPUT": str(tmp_path / "output"),
        "CAPTURE": str(capture),
        "BASE": BASE,
        "CANDIDATE": BUILD,
        "BUILD": BUILD,
        "REVISION": "a" * 40,
        "SOURCE_REVISION": "a" * 40,
        "RELEASE_REVISION": "1",
        "CREATED_AT": "2026-11-12T00:00:00Z",
        "RUN_URL": "https://example.invalid/run",
        "CANDIDATE_PR": "141",
        "CANDIDATE_CI_RUN_ID": "201",
        "CANDIDATE_CI_ATTEMPT": "1",
    }
    result = subprocess.run(["bash", "-e", "-c", script], env=env, capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    argv = json.loads(capture.read_text())
    for flag, value in (
        ("--candidate-pr", "141"),
        ("--candidate-ci-run-id", "201"),
        ("--candidate-ci-attempt", "1"),
    ):
        assert flag in argv
        assert argv[argv.index(flag) + 1] == value


@pytest.mark.parametrize(
    "env",
    [
        {
            "GITHUB_REPOSITORY": "foreign/TalentForge",
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_REF": "refs/heads/main",
        },
        {
            "GITHUB_REPOSITORY": "AlexeyKravtsov95/TalentForge",
            "GITHUB_EVENT_NAME": "pull_request",
            "GITHUB_REF": "refs/pull/141/merge",
        },
    ],
)
def test_candidate_cli_cannot_use_a_reader_in_foreign_or_pr_execution(tmp_path, monkeypatch, env):
    from talent_data.ota.automation.candidate import candidate_context_from_cli

    monkeypatch.setenv("GH_TOKEN", "synthetic-read-only-token")
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    with pytest.raises(PreparationBlocked, match="CANDIDATE_ORIGIN"):
        candidate_context_from_cli(
            tmp_path, pull_number=141, source_sha="a" * 40, ci_run_id=201, ci_attempt=1
        )
