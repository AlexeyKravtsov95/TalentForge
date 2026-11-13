"""Real Git branch operations; external App/PR calls are recorded."""

import copy
import json
import os
import subprocess

import pytest

from talent_data.ota.automation.candidate import CandidateBinding, verify_candidate
from talent_data.ota.automation.prepare import PreparationBlocked
from talent_data.ota.automation.requests import parse_request
from talent_data.ota.verify import sha256_ref
from tests.unit.ota.single_merge_testkit import REPOSITORY, candidate_fixture
from tests.unit.ota.test_automation_requests import request_wire
from tests.unit.ota.test_merge_contracts import binding_wire
from tests.unit.test_ota_prepare import git


class App:
    def __init__(self, api):
        self.api, self.writes, self.trees = api, [], {}
        self.race = None

    def call(self, method, path, **kwargs):
        if method == "POST" and path == "/graphql":
            self.writes.append((method, path, copy.deepcopy(kwargs["data"])))
            assert "updateRefs" in kwargs["data"]["query"]
            inputs = kwargs["data"]["variables"]["input"]
            assert inputs["repositoryId"] == "R_test"
            (update,) = inputs["refUpdates"]
            assert update["force"] is False
            assert update["name"] == "refs/heads/" + self.api.branch
            if self.race:
                self.api.pull["head"]["sha"] = self.race
            if update["beforeOid"] != self.api.pull["head"]["sha"]:
                return {"errors": [{"message": "expected old ref differs"}]}
            assert (
                git(self.api.repo_path, "rev-parse", update["afterOid"] + "^")
                == update["beforeOid"]
            )
            self.api.pull["head"]["sha"] = update["afterOid"]
            return {"data": {"updateRefs": {"clientMutationId": None}}}
        assert method == "GET" and path == "/installation/repositories"
        return {"total_count": 1, "repositories": [{"full_name": REPOSITORY, "node_id": "R_test"}]}

    def repo(self, method, path, *, data=None, **kwargs):
        if method == "GET":
            return self.api.repo(method, path, **kwargs)
        self.writes.append((method, path, copy.deepcopy(data)))
        repo = self.api.repo_path
        if path == "/git/trees":
            env = {**os.environ, "GIT_INDEX_FILE": str(repo / "metadata-index")}
            subprocess.run(
                ["git", "-C", str(repo), "read-tree", data["base_tree"]], env=env, check=True
            )
            for f in data["tree"]:
                sha = (
                    subprocess.check_output(
                        ["git", "-C", str(repo), "hash-object", "-w", "--stdin"],
                        input=f["content"].encode(),
                    )
                    .decode()
                    .strip()
                )
                subprocess.run(
                    [
                        "git",
                        "-C",
                        str(repo),
                        "update-index",
                        "--add",
                        "--cacheinfo",
                        "100644",
                        sha,
                        f["path"],
                    ],
                    env=env,
                    check=True,
                )
            tree = (
                subprocess.check_output(["git", "-C", str(repo), "write-tree"], env=env)
                .decode()
                .strip()
            )
            return {"sha": tree}
        if path == "/git/commits":
            env = {
                **os.environ,
                "GIT_AUTHOR_NAME": data["author"]["name"],
                "GIT_AUTHOR_EMAIL": data["author"]["email"],
                "GIT_COMMITTER_NAME": data["committer"]["name"],
                "GIT_COMMITTER_EMAIL": data["committer"]["email"],
            }
            sha = (
                subprocess.check_output(
                    ["git", "-C", str(repo), "commit-tree", data["tree"], "-p", data["parents"][0]],
                    input=data["message"].encode(),
                    env=env,
                )
                .decode()
                .strip()
            )
            return {"sha": sha}
        if path.startswith("/git/refs/heads/"):
            assert data["force"] is False
            if self.race:
                self.api.pull["head"]["sha"] = self.race
            sha = data["sha"]
            if subprocess.run(
                [
                    "git",
                    "-C",
                    str(repo),
                    "merge-base",
                    "--is-ancestor",
                    self.api.pull["head"]["sha"],
                    sha,
                ],
                capture_output=True,
            ).returncode:
                raise PreparationBlocked("BRANCH_CHANGED", "non fast forward")
            self.api.pull["head"]["sha"] = sha
            return {"object": {"sha": sha}}
        if path == "/pulls/141":
            self.api.pull.update(data)
            return self.api.pull
        raise AssertionError((method, path, data))


def setup(tmp_path):
    repo, api = candidate_fixture(tmp_path)
    candidate = verify_candidate(api, repo, 141, source_sha=api.source)
    wire = request_wire()
    wire.update(
        build=candidate.build, releaseId=candidate.build + "-r1", sourceCommit=candidate.source_sha
    )
    wire["packageRun"]["headSha"] = wire["stagingRun"]["headSha"] = candidate.tooling_sha
    request = parse_request(json.dumps(wire).encode())
    b = binding_wire()
    b.update(
        build=candidate.build,
        sourceSha=candidate.source_sha,
        preparationHeadSha=candidate.source_sha,
        sourceTree=candidate.source_tree,
        baseSha=candidate.base_sha,
        toolingSha=candidate.tooling_sha,
        baseReleaseId=request.base_release_id,
        baseManifestSha256=request.base_manifest_sha256,
    )
    for name, pin in [
        ("packageRun", request.package_run),
        ("stagingRun", request.staging_run),
        ("preparationCi", candidate.ci_pin),
        ("candidateRun", candidate.candidate_run),
        ("candidateArtifact", candidate.candidate_artifact),
    ]:
        b[name] = pin.model_dump(mode="json", by_alias=True)
    b["flaggedGates"] = ["G7"]
    binding = CandidateBinding.model_validate_json(json.dumps(b))
    original = api.repo

    def read(method, path, **kwargs):
        if path.startswith("/git/ref/heads/"):
            return {"object": {"sha": api.pull["head"]["sha"]}}
        if path.startswith("/git/commits/"):
            record = original(method, path, **kwargs)
            sha = path.rsplit("/", 1)[-1]
            record["author"] = {
                "name": "TalentForge OTA preparation",
                "email": git(repo, "show", "-s", "--format=%ae", sha),
            }
            return record
        if path.startswith("/compare/"):
            start, head = path.rsplit("/", 1)[-1].split("...")
            return {
                "total_commits": int(git(repo, "rev-list", "--count", start + ".." + head)),
                "files": [
                    {"filename": n, "status": "added"}
                    for n in git(repo, "diff", "--name-only", start, head).splitlines()
                ],
            }
        if path.startswith("/git/trees/"):
            tree = path.rsplit("/", 1)[-1]
            return {
                "truncated": False,
                "tree": [
                    {
                        "mode": line.split()[0],
                        "type": line.split()[1],
                        "path": line.split("\t")[1],
                        "sha": line.split()[2],
                    }
                    for line in git(repo, "ls-tree", "-r", tree).splitlines()
                ],
            }
        return original(method, path, **kwargs)

    api.repo = read

    def content(path, ref):
        result = subprocess.run(
            ["git", "-C", str(repo), "show", ref + ":" + path], capture_output=True
        )
        return result.stdout if result.returncode == 0 else None

    api.content = content
    return repo, api, App(api), candidate, request, binding


def test_one_metadata_commit_is_idempotent_and_keeps_data(tmp_path):
    from talent_data.ota.automation.merge_approval import parse_merge_record
    from talent_data.ota.automation.premerge import bind_candidate_request

    repo, reader, writer, candidate, request, binding = setup(tmp_path)
    head = bind_candidate_request(reader, writer, repo, candidate, request, binding)
    assert git(repo, "rev-parse", head + "^") == candidate.source_sha
    paths = set(git(repo, "diff", "--name-only", candidate.source_sha, head).splitlines())
    assert paths == {
        f"docs/ota/release-requests/{request.release_id}.json",
        f"docs/ota/releases/{request.release_id}.md",
    }
    raw = reader.content(f"docs/ota/release-requests/{request.release_id}.json", head)
    record = parse_merge_record(
        reader.content(f"docs/ota/releases/{request.release_id}.md", head).decode()
    )
    assert record.request_digest == sha256_ref(raw)
    assert record.accepted_flags == ("G7",)
    before = len(writer.writes)
    assert bind_candidate_request(reader, writer, repo, candidate, request, binding) == head
    assert len(writer.writes) == before
    assert all(path != "/pulls" for _, path, _ in writer.writes)


def test_owner_rewind_at_ref_update_is_not_overwritten(tmp_path):
    from talent_data.ota.automation.premerge import bind_candidate_request

    repo, reader, writer, candidate, request, binding = setup(tmp_path)
    writer.race = reader.base
    with pytest.raises(PreparationBlocked):
        bind_candidate_request(reader, writer, repo, candidate, request, binding)
    assert reader.pull["head"]["sha"] == reader.base


@pytest.mark.parametrize("fault", ["foreign-head", "race", "wrong-binding", "revoked-policy"])
def test_changed_branch_never_overwritten(tmp_path, fault):
    from talent_data.ota.automation.premerge import bind_candidate_request

    repo, reader, writer, candidate, request, binding = setup(tmp_path)
    if fault == "foreign-head":
        reader.pull["head"]["sha"] = reader.base
    elif fault == "race":
        writer.race = reader.base
    elif fault == "wrong-binding":
        binding = binding.model_copy(update={"source_sha": reader.base})
    else:
        reader.pull["body"] = "owner removed conditional policy"
    with pytest.raises(PreparationBlocked):
        bind_candidate_request(reader, writer, repo, candidate, request, binding)
    assert reader.pull["head"]["sha"] != candidate.source_sha or not writer.writes
    assert not any("/git/refs/heads/main" in p or p.endswith("/merge") for _, p, _ in writer.writes)


def test_new_candidate_is_draft_with_single_merge_terms(tmp_path):
    import httpx

    from talent_data.prepare.propose import Git, GitHub, propose
    from tests.unit.test_ota_propose import FakeGitHub, prepared

    repo, api = candidate_fixture(tmp_path)
    # Use the original artifact again; the review branch is already the candidate.
    artifact, _report = prepared(repo, name="second-preparation")
    fake = FakeGitHub(repo.parent / "remote.git")
    # candidate_fixture uses its own remote path: ask Git rather than assume it.
    fake.remote = __import__("pathlib").Path(git(repo, "remote", "get-url", "origin"))

    def handler(req):
        if req.method == "POST":
            payload = json.loads(req.content)
            assert payload["draft"] is True
            pull = fake.open_pull(
                payload["head"],
                git(fake.remote, "rev-parse", "refs/heads/" + payload["head"]),
                payload["body"],
            )
            pull["draft"] = True
            return httpx.Response(201, json=pull)
        return fake.handler(req)

    with httpx.Client(
        transport=httpx.MockTransport(handler),
        base_url="https://api.github.test",
        headers={"Authorization": "Bearer tok-test"},
    ) as client:
        result, body = propose(
            artifact,
            git=Git(repo),
            github=GitHub(client, "owner/talentforge"),
            owner_merge_notice=json.dumps(api.notice),
        )
    assert result.pull_number == 1
    assert fake.pulls[0]["draft"] is True
    assert "owner-merge-v1" in body
    assert "Слияние" in body and "автоматическую публикацию" in body
    assert "Merging this PR only puts" not in body
    assert "rebuilt from the merge commit" not in body


@pytest.mark.parametrize(
    "fault", [None, "failed", "missing-full", "wrong-attempt", "manual-undraft"]
)
def test_ready_requires_exact_final_full_validation(tmp_path, monkeypatch, fault):
    from talent_data.ota.automation import premerge
    from talent_data.ota.automation.requests import RunPin

    repo, reader, writer, candidate, request, binding = setup(tmp_path)
    head = premerge.bind_candidate_request(reader, writer, repo, candidate, request, binding)
    reader.pull["node_id"] = "PR_test"
    run = reader.runs[201]
    run["head_sha"] = head
    run["pull_requests"][0]["head"]["sha"] = head
    for job in reader.jobs[201]:
        job["head_sha"] = head
    pin = RunPin(workflow_path=".github/workflows/ci.yml", run_id=201, attempt=1, head_sha=head)
    # Archive/source verification is tested separately; isolate the final-CI/ready boundary.
    monkeypatch.setattr(
        premerge,
        "review_package",
        lambda *args: premerge.CandidateReview(
            copy.deepcopy(reader.pull),
            request,
            binding,
            candidate,
            ({"gate": "G7", "status": "FLAG", "detail": "merge decision"},),
            403,
            5125504,
        ),
    )
    original = writer.call

    def call(method, path, **kwargs):
        if path == "/graphql":
            writer.writes.append((method, path, kwargs["data"]))
            reader.pull["draft"] = False
            return {
                "data": {
                    "markPullRequestReadyForReview": {
                        "pullRequest": {"id": "PR_test", "isDraft": False}
                    }
                }
            }
        return original(method, path, **kwargs)

    writer.call = call
    writer.writes.clear()
    if fault in {"failed", "manual-undraft"}:
        reader.jobs[201][1]["conclusion"] = "failure"
        if fault == "manual-undraft":
            reader.pull["draft"] = False
    elif fault == "missing-full":
        reader.jobs[201] = reader.jobs[201][-1:]
    elif fault == "wrong-attempt":
        pin = pin.model_copy(update={"attempt": 2})
    if fault is not None:
        with pytest.raises(PreparationBlocked):
            premerge.mark_candidate_ready(reader, writer, repo, 141, ci_pin=pin)
        assert not writer.writes
    else:
        premerge.mark_candidate_ready(reader, writer, repo, 141, ci_pin=pin)
        assert reader.pull["draft"] is False
        assert [p for _, p, _ in writer.writes] == ["/pulls/141", "/graphql"]
        premerge.mark_candidate_ready(reader, writer, repo, 141, ci_pin=pin)
        assert len(writer.writes) == 2


def test_racing_head_during_ready_is_returned_to_draft(tmp_path, monkeypatch):
    from talent_data.ota.automation import premerge
    from talent_data.ota.automation.requests import RunPin

    repo, reader, writer, candidate, request, binding = setup(tmp_path)
    head = premerge.bind_candidate_request(reader, writer, repo, candidate, request, binding)
    reader.pull["node_id"] = "PR_test"
    run = reader.runs[201]
    run["head_sha"] = head
    run["pull_requests"][0]["head"]["sha"] = head
    for job in reader.jobs[201]:
        job["head_sha"] = head
    monkeypatch.setattr(
        premerge,
        "review_package",
        lambda *args: premerge.CandidateReview(
            copy.deepcopy(reader.pull),
            request,
            binding,
            candidate,
            ({"gate": "G7", "status": "FLAG", "detail": "merge"},),
            403,
            5125504,
        ),
    )
    original = writer.call

    def call(method, path, **kwargs):
        if path == "/graphql":
            writer.writes.append((method, path, kwargs["data"]))
            draft = "convertPullRequestToDraft" in kwargs["data"]["query"]
            reader.pull["draft"] = draft
            if not draft:
                reader.pull["head"]["sha"] = candidate.source_sha
            return {"data": {}}
        return original(method, path, **kwargs)

    writer.call = call
    pin = RunPin(workflow_path=".github/workflows/ci.yml", run_id=201, attempt=1, head_sha=head)
    with pytest.raises(PreparationBlocked, match="CANDIDATE_CHANGED"):
        premerge.mark_candidate_ready(reader, writer, repo, 141, ci_pin=pin)
    assert reader.pull["draft"] is True
    assert all(not p.endswith("/merge") for _, p, _ in writer.writes)


@pytest.mark.parametrize(
    "fault", ["stale-success", "base-during-body", "base-during-ready", "ci-during-ready"]
)
def test_readiness_rechecks_latest_ci_and_frozen_base(tmp_path, monkeypatch, fault):
    from talent_data.ota.automation import premerge
    from talent_data.ota.automation.requests import RunPin

    repo, reader, writer, candidate, request, binding = setup(tmp_path)
    head = premerge.bind_candidate_request(reader, writer, repo, candidate, request, binding)
    reader.pull["node_id"] = "PR_test"
    reader.runs[201].update(head_sha=head, pull_requests=[])
    for job in reader.jobs[201]:
        job["head_sha"] = head
    review = premerge.CandidateReview(
        copy.deepcopy(reader.pull),
        request,
        binding,
        candidate,
        ({"gate": "G7", "status": "FLAG", "detail": "merge"},),
        403,
        5125504,
    )
    monkeypatch.setattr(premerge, "review_package", lambda *args: review)
    original_pages = reader.pages

    def fail_current_ci():
        reader.runs[301] = {**copy.deepcopy(reader.runs[201]), "id": 301, "conclusion": "failure"}
        reader.pages = lambda path, *args, **kwargs: (
            [reader.runs[301], reader.runs[201]]
            if path == "/actions/workflows/ci.yml/runs"
            else original_pages(path, *args, **kwargs)
        )

    original_repo, original_call = writer.repo, writer.call

    def write(method, path, **kwargs):
        result = original_repo(method, path, **kwargs)
        if fault == "base-during-body" and path == "/pulls/141":
            reader.pull["base"]["sha"] = "f" * 40
        return result

    def call(method, path, **kwargs):
        if path == "/graphql":
            writer.writes.append((method, path, kwargs["data"]))
            draft = "convertPullRequestToDraft" in kwargs["data"]["query"]
            reader.pull["draft"] = draft
            if not draft and fault == "base-during-ready":
                reader.pull["base"]["sha"] = "f" * 40
            if not draft and fault == "ci-during-ready":
                fail_current_ci()
            return {"data": {}}
        return original_call(method, path, **kwargs)

    writer.repo, writer.call = write, call
    if fault == "stale-success":
        fail_current_ci()
    pin = RunPin(workflow_path=".github/workflows/ci.yml", run_id=201, attempt=1, head_sha=head)
    with pytest.raises(PreparationBlocked):
        premerge.mark_candidate_ready(reader, writer, repo, 141, ci_pin=pin)
    assert reader.pull["draft"] is True
