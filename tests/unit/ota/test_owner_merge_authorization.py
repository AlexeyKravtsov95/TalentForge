"""Merge is the sole authorization only for the exact generated and validated MR."""

import copy
import os
import subprocess
from datetime import UTC, datetime

import pytest

from talent_data.ota.automation.merge_approval import parse_merge_record
from talent_data.ota.automation.prepare import PreparationBlocked
from tests.unit.ota.single_merge_testkit import OWNER
from tests.unit.ota.test_premerge_proposal import setup
from tests.unit.test_ota_prepare import git


def merged(tmp_path):
    from talent_data.ota.automation.premerge import bind_candidate_request

    repo, api, writer, candidate, request, binding = setup(tmp_path)
    head = bind_candidate_request(api, writer, repo, candidate, request, binding)
    record = parse_merge_record(
        api.content(f"docs/ota/releases/{request.release_id}.md", head).decode()
    )
    api.runs[301] = copy.deepcopy(api.runs[201])
    api.runs[301].update(id=301, head_sha=head)
    api.runs[301]["pull_requests"][0]["head"]["sha"] = head
    api.jobs[301] = copy.deepcopy(api.jobs[201])
    for n, job in enumerate(api.jobs[301]):
        job.update(id=3010 + n, run_id=301, head_sha=head)
    oldpages = api.pages

    def pages(path, *args, **kwargs):
        if path == "/actions/workflows/ci.yml/runs":
            return [copy.deepcopy(api.runs[301]), copy.deepcopy(api.runs[201])]
        return oldpages(path, *args, **kwargs)

    api.pages = pages
    tree = git(repo, "rev-parse", head + "^{tree}")
    env = {
        **os.environ,
        "GIT_AUTHOR_NAME": "owner",
        "GIT_AUTHOR_EMAIL": "owner@example.invalid",
        "GIT_COMMITTER_NAME": "owner",
        "GIT_COMMITTER_EMAIL": "owner@example.invalid",
    }
    merge = (
        subprocess.check_output(
            ["git", "-C", str(repo), "commit-tree", tree, "-p", candidate.base_sha, "-p", head],
            input=b"owner merge\n",
            env=env,
        )
        .decode()
        .strip()
    )
    git(repo, "update-ref", "refs/remotes/origin/main", merge)
    git(repo, "push", "--quiet", "origin", merge + ":refs/heads/main")
    api.pull.update(
        state="closed",
        merged=True,
        draft=False,
        merged_by=OWNER.copy(),
        merge_commit_sha=merge,
        merged_at="2026-11-12T10:00:00Z",
    )
    return repo, api, request, binding, record, head, merge


def check(data):
    from talent_data.ota.automation.merge_approval import verify_owner_merge

    repo, api, request, binding, record, *_ = data
    return verify_owner_merge(api, repo, request, record, binding=binding)


def test_real_owner_merge_of_exact_final_tree_authorizes_without_personal_evidence(tmp_path):
    from talent_data.ota.automation.merge_approval import as_gate_approval

    data = merged(tmp_path)
    verified = check(data)
    assert verified.merge_sha == data[-1] and verified.final_head_sha == data[-2]
    assert verified.merged_at == datetime(2026, 11, 12, 10, tzinfo=UTC)
    gate = as_gate_approval(verified)
    assert gate.live_build_evidence is None
    assert "owner merge" in gate.merge_authorization_evidence
    assert gate.manifest_sha256 == data[2].manifest_sha256


@pytest.mark.parametrize(
    "fault",
    [
        "merger-id",
        "merger-login",
        "unmerged",
        "draft",
        "fork",
        "policy",
        "squash",
        "tree",
        "revoked",
        "missing-record",
        "flags",
        "package-source",
        "expired-original",
        "failed-final",
        "synthetic-tree",
        "changed-main-record",
    ],
)
def test_wrong_or_withdrawn_merge_never_authorizes(tmp_path, fault):
    data = list(merged(tmp_path))
    _repo, api, _request, binding, record, head, merge = data
    if fault == "merger-id":
        api.pull["merged_by"]["id"] = 999
    elif fault == "merger-login":
        api.pull["merged_by"]["login"] = "other"
    elif fault == "unmerged":
        api.pull["merged"] = False
    elif fault == "draft":
        api.pull["draft"] = True
    elif fault == "fork":
        api.pull["head"]["repo"]["full_name"] = "foreign/repo"
    elif fault == "policy":
        api.pull["body"] = "old MR"
    elif fault == "squash":
        api.pull["merge_commit_sha"] = head
    elif fault == "tree":
        original = api.repo

        def read(method, path, **kwargs):
            value = original(method, path, **kwargs)
            if path == "/git/commits/" + merge:
                value["tree"]["sha"] = binding.source_tree
            return value

        api.repo = read
    elif fault == "revoked":
        data[4] = record.model_copy(update={"revoked": True})
    elif fault == "missing-record":
        original = api.content
        api.content = lambda path, ref: (
            None if path.endswith(".md") and ref == merge else original(path, ref)
        )
    elif fault == "flags":
        data[4] = record.model_copy(update={"accepted_flags": ("G5", "G7")})
    elif fault == "package-source":
        data[3] = binding.model_copy(update={"source_sha": binding.base_sha})
    elif fault == "expired-original":
        api.artifact["expired"] = True
    elif fault == "failed-final":
        api.jobs[301][1]["conclusion"] = "failure"
    elif fault == "synthetic-tree":
        api.runs[301]["head_sha"] = merge
        for job in api.jobs[301]:
            job["head_sha"] = merge
        # A real main merge has [B,H] and same tree and is valid as synthetic PR evidence;
        # mutate its parents instead to make it unprovable.
        original = api.repo

        def read(method, path, **kwargs):
            value = original(method, path, **kwargs)
            if path == "/git/commits/" + merge:
                value["parents"] = [{"sha": head}]
            return value

        api.repo = read
    else:
        original = api.content
        api.content = lambda path, ref: (
            b"revoked\n" if path.endswith(".md") and ref == merge else original(path, ref)
        )
    with pytest.raises((PreparationBlocked, ValueError)):
        check(data)


def test_conditional_record_is_rejected_by_legacy_manual_parser(tmp_path):
    from talent_data.ota.automation.merge_approval import render_merge_record
    from talent_data.ota.errors import OtaPublisherError
    from talent_data.ota.gates import load_approval_record

    data = merged(tmp_path)
    (tmp_path / (data[2].release_id + ".md")).write_text(render_merge_record(data[4]))
    with pytest.raises(OtaPublisherError, match="merge"):
        load_approval_record(data[2].release_id, tmp_path)
