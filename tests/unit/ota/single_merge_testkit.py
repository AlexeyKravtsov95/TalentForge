"""Real Git/candidate artifacts; only external GitHub calls are replaced."""

import copy
import io
import json
import subprocess
import zipfile
from datetime import timedelta
from pathlib import Path

from talent_data.ota.verify import sha256_ref
from talent_data.prepare.orchestrate import CANDIDATE_BRANCH
from tests.unit.test_ota_prepare import BASE, BUILD, git
from tests.unit.test_ota_propose import commit, prepared

REPOSITORY = "AlexeyKravtsov95/TalentForge"
BOT = {"login": "talentforge-ota-alexeykravtsov95[bot]", "id": 351368232, "type": "Bot"}
OWNER = {"login": "AlexeyKravtsov95", "id": 5190861, "type": "User"}


class CandidateAPI:
    def __init__(self, repo: Path, source: str, base: str, artifact: Path, report):
        self.repo_path, self.source, self.base = repo, source, base
        self.branch = f"ota-candidate/forever-{BUILD}"
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name in ("report.json", "detection.json", "candidate.bundle"):
                archive.writestr("out/" + name, (artifact / "out" / name).read_bytes())
        self.raw = stream.getvalue()
        started = report.started_at - timedelta(seconds=1)
        assert report.finished_at is not None
        finished = report.finished_at + timedelta(seconds=1)
        repo_meta = {"full_name": REPOSITORY, "id": 1389604443}
        self.artifact = {
            "id": 11,
            "name": report.artifact_name,
            "digest": sha256_ref(self.raw),
            "expired": False,
            "size_in_bytes": len(self.raw),
            "created_at": finished.isoformat(),
            "workflow_run": {
                "id": 101,
                "repository_id": 1389604443,
                "head_repository_id": 1389604443,
                "head_branch": "main",
                "head_sha": base,
            },
        }

        def pin(path, rid, sha):
            return {"workflowPath": path, "runId": rid, "attempt": 1, "headSha": sha}

        self.notice = {
            "schemaVersion": 1,
            "kind": "ota-candidate-notice",
            "policy": "owner-merge-v1",
            "preparationRun": pin(".github/workflows/ota-prepare.yml", 101, base),
            "candidateArtifact": {
                "artifactId": 11,
                "name": report.artifact_name,
                "archiveSha256": sha256_ref(self.raw),
            },
        }
        self.pull = {
            "number": 141,
            "state": "open",
            "merged": False,
            "draft": True,
            "user": BOT.copy(),
            "base": {"ref": "main", "sha": base, "repo": repo_meta.copy()},
            "head": {"ref": self.branch, "sha": source, "repo": repo_meta.copy()},
            "html_url": f"https://github.com/{REPOSITORY}/pull/141",
            "created_at": started.isoformat(),
        }
        self.runs = {}
        self.jobs = {}
        for rid, path, sha, event, branch, names in (
            (
                101,
                ".github/workflows/ota-prepare.yml",
                base,
                "workflow_run",
                "main",
                ["prepare", "propose"],
            ),
            (
                201,
                ".github/workflows/ci.yml",
                source,
                "pull_request",
                self.branch,
                ["classify", "full-validation", "checks"],
            ),
        ):
            self.runs[rid] = {
                "id": rid,
                "run_attempt": 1,
                "path": path,
                "name": "CI" if rid == 201 else "OTA data preparation",
                "event": event,
                "head_branch": branch,
                "head_sha": sha,
                "repository": repo_meta.copy(),
                "head_repository": repo_meta.copy(),
                "status": "completed",
                "conclusion": "success",
                "run_started_at": started.isoformat(),
                "updated_at": (finished + timedelta(seconds=1)).isoformat(),
                "pull_requests": [{"number": 141, "head": {"sha": source}, "base": {"sha": base}}]
                if rid == 201
                else [],
            }
            self.jobs[rid] = [
                {
                    "id": rid * 10 + i,
                    "name": name,
                    "run_id": rid,
                    "run_attempt": 1,
                    "head_sha": sha,
                    "status": "completed",
                    "conclusion": "success",
                }
                for i, name in enumerate(names)
            ]
        self.refresh_body()

    def refresh_body(self):
        self.pull["body"] = (
            "Слияние разрешает публикацию.\n<!-- talentforge:owner-merge-v1\n"
            + json.dumps(self.notice)
            + "\n-->\n"
        )

    def repo(self, method, path, **kwargs):
        assert method == "GET", "candidate verification must be read-only"
        if path == "/pulls/141":
            return copy.deepcopy(self.pull)
        if path == "/actions/artifacts/11":
            return copy.deepcopy(self.artifact)
        if path.startswith("/git/commits/"):
            sha = path.rsplit("/", 1)[-1]
            return {
                "sha": sha,
                "tree": {"sha": git(self.repo_path, "rev-parse", sha + "^{tree}")},
                "parents": [
                    {"sha": s}
                    for s in git(self.repo_path, "show", "-s", "--format=%P", sha).split()
                ],
            }
        raise AssertionError((method, path, kwargs))

    def pages(self, path, member=None, *, params=None, **kwargs):
        if path == "/pulls":
            assert params["head"] == "AlexeyKravtsov95:" + self.branch
            return [copy.deepcopy(self.pull)]
        if path == "/actions/workflows/ci.yml/runs":
            return [copy.deepcopy(self.runs[201])]
        if path.startswith("/actions/runs/") and path.endswith("/jobs"):
            return copy.deepcopy(self.jobs[int(path.split("/")[3])])
        raise AssertionError((path, member, params, kwargs))

    def run(self, run_id, attempt):
        if attempt != self.runs[run_id]["run_attempt"]:
            from talent_data.ota.automation.prepare import PreparationBlocked

            raise PreparationBlocked("GITHUB_HTTP", "GitHub HTTP 404")
        return copy.deepcopy(self.runs[run_id])

    def archive(self, metadata, **kwargs):
        assert metadata["id"] == 11
        return self.raw

    def content(self, path, ref):
        result = subprocess.run(
            ["git", "-C", str(self.repo_path), "show", ref + ":" + path],
            capture_output=True,
        )
        return result.stdout if result.returncode == 0 else None

    def synthetic_ci(self, *, wrong_tree=False):
        tree = git(
            self.repo_path, "rev-parse", (self.base if wrong_tree else self.source) + "^{tree}"
        )
        env = {
            "GIT_AUTHOR_NAME": "test",
            "GIT_AUTHOR_EMAIL": "test@example.invalid",
            "GIT_COMMITTER_NAME": "test",
            "GIT_COMMITTER_EMAIL": "test@example.invalid",
        }
        import os

        sha = (
            subprocess.check_output(
                [
                    "git",
                    "-C",
                    str(self.repo_path),
                    "commit-tree",
                    tree,
                    "-p",
                    self.base,
                    "-p",
                    self.source,
                ],
                input=b"PR synthetic merge\n",
                env={**os.environ, **env},
            )
            .decode()
            .strip()
        )
        self.runs[201]["head_sha"] = sha
        for job in self.jobs[201]:
            job["head_sha"] = sha
        return sha


def candidate_fixture(tmp_path):
    repo = tmp_path / "repo"
    git(tmp_path, "init", "--quiet", "-b", "main", str(repo))
    base = commit(repo, "base", {f"data/normalized/forever/{BASE}/manifest.json": b"{}\n"})
    remote = tmp_path / "remote.git"
    git(tmp_path, "clone", "--quiet", "--bare", str(repo), str(remote))
    git(repo, "remote", "add", "origin", str(remote))
    git(repo, "fetch", "--quiet", "origin")
    artifact, report = prepared(repo)
    git(
        repo,
        "fetch",
        "--quiet",
        str(artifact / "out" / "candidate.bundle"),
        "refs/heads/" + CANDIDATE_BRANCH.format(build=BUILD),
    )
    api = CandidateAPI(repo, report.candidate_commit, base, artifact, report)
    return repo, api


def poison_candidate(repo, api, fault):
    """A consistently rehashed artifact still must not smuggle disallowed Git changes."""
    import os

    from talent_data.prepare.orchestrate import CANDIDATE_AUTHOR

    git(repo, "checkout", "--quiet", "--detach", api.source)
    if fault == "code":
        p = repo / "scripts/foreign.py"
        p.parent.mkdir(exist_ok=True)
        p.write_text("raise RuntimeError('must never execute')\n")
    elif fault == "symlink":
        (repo / f"data/normalized/forever/{BUILD}/link.json").symlink_to("manifest.json")
    elif fault == "delete":
        (repo / f"data/normalized/forever/{BASE}/manifest.json").unlink()
    elif fault == "rename":
        p = repo / f"data/normalized/forever/{BASE}/manifest.json"
        p.rename(p.with_name("renamed.json"))
    git(repo, "add", "--all")
    tree = git(repo, "write-tree")
    identity = {
        "GIT_AUTHOR_NAME": CANDIDATE_AUTHOR[0],
        "GIT_AUTHOR_EMAIL": CANDIDATE_AUTHOR[1],
        "GIT_COMMITTER_NAME": CANDIDATE_AUTHOR[0],
        "GIT_COMMITTER_EMAIL": CANDIDATE_AUTHOR[1],
    }
    sha = (
        subprocess.check_output(
            ["git", "-C", str(repo), "commit-tree", tree, "-p", api.base],
            input=b"invalid candidate fixture\n",
            env={**os.environ, **identity},
        )
        .decode()
        .strip()
    )
    ref = "refs/heads/" + CANDIDATE_BRANCH.format(build=BUILD)
    git(repo, "update-ref", ref, sha)
    git(repo, "checkout", "--quiet", "main")
    directory = repo.parent / "prep" / "out"
    bundle = directory / "candidate.bundle"
    bundle.unlink()
    git(repo, "bundle", "create", str(bundle), ref)
    p = directory / "report.json"
    report = json.loads(p.read_text())
    report["candidateCommit"] = sha
    report["changedPaths"] = len(git(repo, "diff", "--name-only", api.base, sha).splitlines())
    p.write_text(json.dumps(report))
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as z:
        for name in ("report.json", "detection.json", "candidate.bundle"):
            z.writestr("out/" + name, (directory / name).read_bytes())
    api.raw = buffer.getvalue()
    api.source = api.pull["head"]["sha"] = api.runs[201]["head_sha"] = sha
    api.runs[201]["pull_requests"][0]["head"]["sha"] = sha
    for j in api.jobs[201]:
        j["head_sha"] = sha
    api.artifact["digest"] = api.notice["candidateArtifact"]["archiveSha256"] = sha256_ref(api.raw)
    api.artifact["size_in_bytes"] = len(api.raw)
    api.refresh_body()
