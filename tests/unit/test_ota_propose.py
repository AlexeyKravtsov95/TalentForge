"""Proposing a PREPARED OTA candidate as a pull request (M5.OTA.AUTO.3), fully offline.

Every test prepares a real candidate with ``orchestrate.prepare`` (fake pipeline runner, as
in ``test_ota_prepare.py``) in a temporary checkout whose ``origin`` is a local bare
repository, then proposes it with real git against that remote and an in-memory GitHub
(``httpx.MockTransport``): opening the PR, repeated runs (no second PR, no extra commit),
recovery after a failed API call, a race on creation, data already on main, refusal of
every unsuccessful or tampered preparation, branches the automation does not own, closed
PRs, the CLI and the workflow's selection and status scripts.
"""

from __future__ import annotations

import json
import os
import subprocess
from collections.abc import Callable, Sequence
from pathlib import Path
from typing import Any

import httpx
import pytest

from talent_data.cli import main as cli_main
from talent_data.prepare.detect import Action
from talent_data.prepare.orchestrate import CANDIDATE_AUTHOR, PrepareReport, Readiness, prepare
from talent_data.prepare.propose import (
    EXIT_CODES,
    Git,
    GitHub,
    Outcome,
    PreparationReport,
    Proposal,
    propose,
    render_summary,
)
from talent_data.prepare.runner import detection_outputs, main
from tests.unit.test_ota_prepare import (
    BASE,
    BUILD,
    INDEX,
    REAL_MANIFEST,
    ROOT,
    FakeRunner,
    detect,
    git,
    services,
    workflow_step_script,
)

REPOSITORY = "owner/talentforge"
API = "https://api.github.test"
RUN_URL = "https://github.test/owner/talentforge/actions/runs/101"
BRANCH = f"ota-candidate/forever-{BUILD}"

DIFF = {
    "schemaVersion": 1,
    "game": "forever",
    "fromBuild": BASE,
    "toBuild": BUILD,
    "classes": [
        {
            "class": {"id": 1, "token": "WARRIOR", "name": "Warrior"},
            "file": f"forever-warrior-{BASE}-{BUILD}.json",
            "summary": {"added": 2, "removed": 0, "changed": 1, "changesByKind": {"maxRank": 1}},
        },
        {
            "class": {"id": 2, "token": "PALADIN", "name": "Paladin"},
            "file": f"forever-paladin-{BASE}-{BUILD}.json",
            "summary": {"added": 0, "removed": 0, "changed": 0, "changesByKind": {}},
        },
    ],
    "total": {"added": 2, "removed": 0, "changed": 1, "changesByKind": {"maxRank": 1}},
}
LOCALIZATION = {
    "game": "forever",
    "clientBuild": BUILD,
    "locale": "ruRU",
    "classes": [],
    "totals": {
        "entries": 10,
        "names": {"translated": 9, "untranslated": 1, "missing": 0},
        "descriptions": {
            "missing": 0,
            "untranslated": 1,
            "sourceNotResolved": 1,
            "pluralRuleUnverified": 0,
            "structureMismatch": 0,
            "renderGap": 0,
            "decimalFormatUnverified": 0,
            "rendered": 8,
        },
        "ranks": 20,
        "renderedRanks": 17,
    },
    "nameExceptions": [],
    "descriptionExceptions": [],
}
TOOLTIPS = {
    "game": "forever",
    "clientBuild": BUILD,
    "totals": {
        "entries": 10,
        "ranks": 20,
        "resolved": 18,
        "partial": 1,
        "unresolved": 1,
        "resolvedPercent": 90.0,
    },
    "classes": [],
    "categories": [],
    "gaps": [],
}
WRITES = {
    "diff": (f"artifacts/diff/forever-all-{BASE}-{BUILD}.json", DIFF),
    "localization-normalize-ruRU": (
        f"artifacts/localization/forever-{BUILD}-ruRU-coverage.json",
        LOCALIZATION,
    ),
    "tooltips-normalize": (f"artifacts/tooltips/forever-{BUILD}-tooltip-coverage.json", TOOLTIPS),
}


class CoverageRunner(FakeRunner):
    """The fake pipeline, also writing the diff and coverage reports the PR describes."""

    def __call__(self, argv: Sequence[str], cwd: Path, log: Path) -> int:
        code = super().__call__(argv, cwd, log)
        if self.calls[-1] in WRITES:
            path, doc = WRITES[self.calls[-1]]
            (cwd / path).parent.mkdir(parents=True, exist_ok=True)
            (cwd / path).write_text(json.dumps(doc, indent=2) + "\n")
        return code


def commit(cwd: Path, message: str, files: dict[str, bytes] | None = None) -> str:
    for path, data in (files or {}).items():
        (cwd / path).parent.mkdir(parents=True, exist_ok=True)
        (cwd / path).write_bytes(data)
    git(cwd, "add", "--all")
    git(
        cwd,
        "-c",
        "user.name=owner",
        "-c",
        "user.email=owner@example.invalid",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "--quiet",
        "--allow-empty",
        "-m",
        message,
    )
    return git(cwd, "rev-parse", "HEAD")


@pytest.fixture
def checkout(tmp_path: Path) -> Path:
    """A checkout whose ``origin`` is a local bare repository carrying the base build."""
    root = tmp_path / "repo"
    git(tmp_path, "init", "--quiet", "-b", "main", str(root))
    commit(root, "main", {f"data/normalized/forever/{BASE}/manifest.json": b"{}\n"})
    remote = tmp_path / "remote.git"
    git(tmp_path, "clone", "--quiet", "--bare", str(root), str(remote))
    git(root, "remote", "add", "origin", str(remote))
    git(root, "fetch", "--quiet", "origin")
    return root


def remote_of(checkout: Path) -> Path:
    return checkout.parent / "remote.git"


def advance_main(checkout: Path, files: dict[str, bytes] | None = None) -> str:
    """Another change lands on main (pushed to the remote, fetched back)."""
    git(checkout, "checkout", "--quiet", "main")
    head = commit(checkout, "another change on main", files or {"README.md": b"next\n"})
    git(checkout, "push", "--quiet", "origin", "main")
    git(checkout, "fetch", "--quiet", "origin")
    return head


def prepared(
    checkout: Path, name: str = "prep", runner: FakeRunner | None = None
) -> tuple[Path, PrepareReport]:
    """A preparation from the remote's main, laid out like the workflow's ``prep/``."""
    svc, _ = services(runner or CoverageRunner())
    detection = detect(main=git(checkout, "rev-parse", "origin/main"))
    root = checkout.parent / name
    root.mkdir()
    (root / "detection.json").write_text(detection.to_json())
    report = prepare(
        detection,
        repo=checkout,
        out=root / "out",
        workspace=checkout.parent / f"{name}-ws" / "workspace",
        services=svc,
    )
    return root, report


class FakeGitHub:
    """The pull request API of one repository, in memory, over the bare remote (an open
    PR's head follows its branch, as on GitHub). ``failures`` holds queued answers per
    HTTP method - a status code or an exception - served before the real one."""

    def __init__(self, remote: Path) -> None:
        self.remote = remote
        self.pulls: list[dict[str, Any]] = []
        self.calls: list[str] = []
        self.failures: dict[str, list[int | Exception]] = {}
        self.before_create: Callable[[], None] | None = None

    def open_pull(self, head: str, sha: str, body: str = "") -> dict[str, Any]:
        number = len(self.pulls) + 1
        pull = {
            "number": number,
            "html_url": f"https://github.test/{REPOSITORY}/pull/{number}",
            "state": "open",
            "merged_at": None,
            "body": body,
            "head": {"ref": head, "sha": sha, "repo": {"full_name": REPOSITORY}},
            "base": {"ref": "main"},
            "title": "t",
        }
        self.pulls.append(pull)
        return pull

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.calls.append(request.method)
        queued = self.failures.get(request.method)
        if queued:
            failure = queued.pop(0)
            if isinstance(failure, Exception):
                raise failure
            return httpx.Response(failure, json={"message": f"injected HTTP {failure}"})
        assert request.headers["Authorization"] == "Bearer tok-test"
        if request.method == "GET":
            assert request.url.path == f"/repos/{REPOSITORY}/pulls"
            owner, branch = request.url.params["head"].split(":", 1)
            assert owner == "owner" and request.url.params["state"] == "all"
            for pull in self.pulls:
                if pull["state"] == "open":
                    pull["head"]["sha"] = git(
                        self.remote, "rev-parse", f"refs/heads/{pull['head']['ref']}"
                    )
            return httpx.Response(200, json=[p for p in self.pulls if p["head"]["ref"] == branch])
        if request.method == "POST":
            if self.before_create is not None:
                self.before_create()
            payload = json.loads(request.content)
            assert payload["base"] == "main" and payload["draft"] is False
            if any(
                p["state"] == "open" and p["head"]["ref"] == payload["head"] for p in self.pulls
            ):
                return httpx.Response(
                    422,
                    json={
                        "message": "Validation Failed",
                        "errors": [{"message": "A pull request already exists"}],
                    },
                )
            sha = git(self.remote, "rev-parse", f"refs/heads/{payload['head']}")
            pull = self.open_pull(payload["head"], sha, payload["body"])
            pull["title"] = payload["title"]
            return httpx.Response(201, json=pull)
        if request.method == "PATCH":
            number = int(request.url.path.rsplit("/", 1)[1])
            self.pulls[number - 1]["body"] = json.loads(request.content)["body"]
            return httpx.Response(200, json=self.pulls[number - 1])
        return httpx.Response(405)

    def client(self) -> httpx.Client:
        return httpx.Client(
            transport=httpx.MockTransport(self.handler),
            base_url=API,
            headers={"Authorization": "Bearer tok-test"},
        )


def run(
    checkout: Path, artifact: Path, gh: FakeGitHub, **options: Any
) -> tuple[Proposal, str | None, Git]:
    g = Git(checkout)
    with gh.client() as client:
        proposal, body = propose(
            artifact,
            git=g,
            github=GitHub(client, REPOSITORY),
            preparation_url=RUN_URL,
            **options,
        )
    return proposal, body, g


def branch_tip(checkout: Path) -> str | None:
    ref = f"refs/heads/{BRANCH}"
    return git(remote_of(checkout), "for-each-ref", "--format=%(objectname)", ref) or None


# --------------------------------------------------------------------------- success


def test_a_prepared_candidate_is_pushed_to_its_branch_and_proposed(checkout: Path) -> None:
    main_before = git(remote_of(checkout), "rev-parse", "main")
    artifact, report = prepared(checkout)
    assert report.status is Readiness.PREPARED
    gh = FakeGitHub(remote_of(checkout))

    proposal, body, _ = run(checkout, artifact, gh)

    assert proposal.outcome is Outcome.OPENED and EXIT_CODES[proposal.outcome] == 0
    assert proposal.pushed and proposal.pull_number == 1
    assert proposal.pull_url == f"https://github.test/{REPOSITORY}/pull/1"
    # exactly the artifact's candidate commit, one commit on top of the preparation's main
    assert branch_tip(checkout) == report.candidate_commit == proposal.branch_commit
    remote = remote_of(checkout)
    assert git(remote, "rev-list", "--count", f"main..{BRANCH}") == "1"
    assert git(remote, "rev-parse", "main") == main_before  # main is never touched
    changed = git(remote, "diff", "--name-only", f"main...{BRANCH}").splitlines()
    assert sorted(changed) == sorted(
        [
            f"data/normalized/forever/{BUILD}/manifest.json",
            f"docs/research/forever-{BUILD}-tooltip-coverage.md",
            *(path for path, _ in WRITES.values()),
        ]
    )
    assert gh.calls == ["GET", "POST"]
    (pull,) = gh.pulls
    assert pull["title"] == f"data: OTA candidate forever {BUILD} (diff from {BASE})"
    assert body is not None and pull["body"] == body
    for expected in (
        f"`{BUILD}` (release revision r1)",
        f"`{BASE}` (productionRelease, release `1.60.1.70450-r1`)",
        f"| Source `main` commit | `{report.detection.main_commit}` |",
        f"| Candidate commit | `{report.candidate_commit}` |",
        f"[preparation run]({RUN_URL})",
        report.artifact_name,
        "**2 added, 0 removed, 1 changed** talent nodes",
        "| WARRIOR | 2 | 0 | 1 | maxRank 1 |",
        "ruRU: names 9 / 10 translated (1 untranslated, 0 missing); descriptions 8 / 10",
        "(17 / 20 ranks)",
        "tooltips: 18 / 20 ranks resolved (90.0 %)",
        f"{len(report.steps)} / {len(report.steps)} steps passed",
        "| G1 | STOP |",
        "| G7 | FLAG |",
        "- [ ] **G1**:",
        "- [ ] **G7**:",
        "- [ ] **staging**:",
        "- [ ] **production**:",
        "publishes, signs and approves nothing",
    ):
        assert expected in body, expected
    assert "PALADIN" not in body  # classes without changes are not listed


# --------------------------------------------------------------------------- repeatability


def test_repeated_runs_open_no_second_pull_request_and_push_no_commit(checkout: Path) -> None:
    artifact, report = prepared(checkout)
    gh = FakeGitHub(remote_of(checkout))
    first, _, _ = run(checkout, artifact, gh)
    assert first.outcome is Outcome.OPENED

    again, _, _ = run(checkout, artifact, gh)
    assert again.outcome is Outcome.EXISTS and EXIT_CODES[again.outcome] == 0
    assert not again.pushed and not again.body_updated and again.pull_number == 1
    assert gh.calls.count("POST") == 1 and "PATCH" not in gh.calls
    assert branch_tip(checkout) == report.candidate_commit

    # a later preparation from a newer main with the same files: no new commit, no new PR
    advance_main(checkout)
    later, later_report = prepared(checkout, "prep-later")
    assert later_report.candidate_commit != report.candidate_commit
    third, _, _ = run(checkout, later, gh)
    assert third.outcome is Outcome.EXISTS and not third.pushed and not third.body_updated
    assert branch_tip(checkout) == report.candidate_commit
    assert len(gh.pulls) == 1 and gh.calls.count("POST") == 1


def test_an_automation_branch_with_other_files_moves_to_the_new_candidate(
    checkout: Path,
) -> None:
    artifact, report = prepared(checkout)
    gh = FakeGitHub(remote_of(checkout))
    run(checkout, artifact, gh)

    changed, changed_report = prepared(checkout, "prep-2", CoverageRunner(manifest=b'{"v": 2}\n'))
    proposal, body, _ = run(checkout, changed, gh)

    assert proposal.outcome is Outcome.EXISTS and proposal.pushed and proposal.body_updated
    assert branch_tip(checkout) == changed_report.candidate_commit != report.candidate_commit
    assert len(gh.pulls) == 1 and gh.pulls[0]["body"] == body
    assert git(remote_of(checkout), "rev-list", "--count", f"main..{BRANCH}") == "1"


# --------------------------------------------------------------------------- failures


@pytest.mark.parametrize(
    "failure",
    [403, 500, httpx.ConnectError("connection refused")],
    ids=["forbidden", "server-error", "unreachable"],
)
def test_a_failed_pull_request_creation_is_retried_by_the_next_run(
    checkout: Path, failure: int | Exception
) -> None:
    artifact, report = prepared(checkout)
    gh = FakeGitHub(remote_of(checkout))
    gh.failures["POST"] = [failure]

    failed, _, _ = run(checkout, artifact, gh)
    assert failed.outcome is Outcome.FAILED and EXIT_CODES[failed.outcome] == 1
    assert failed.pushed and branch_tip(checkout) == report.candidate_commit
    assert failed.http_status == (failure if isinstance(failure, int) else None)
    assert failed.error and "create pull request" in failed.error
    assert gh.pulls == []
    summary = render_summary(failed)
    assert "FAILED" in summary and "the next run retries it" in summary
    assert ("Allow GitHub Actions to create" in summary) is (failure == 403)

    retried, _, _ = run(checkout, artifact, gh)
    assert retried.outcome is Outcome.OPENED and not retried.pushed
    assert len(gh.pulls) == 1 and branch_tip(checkout) == report.candidate_commit


def test_a_failed_listing_is_retried_without_a_second_push(checkout: Path) -> None:
    artifact, _ = prepared(checkout)
    gh = FakeGitHub(remote_of(checkout))
    gh.failures["GET"] = [502]
    failed, _, _ = run(checkout, artifact, gh)
    assert failed.outcome is Outcome.FAILED and failed.http_status == 502 and failed.pushed
    retried, _, _ = run(checkout, artifact, gh)
    assert retried.outcome is Outcome.OPENED and not retried.pushed


def test_a_concurrent_creation_reuses_the_other_runs_pull_request(checkout: Path) -> None:
    artifact, report = prepared(checkout)
    gh = FakeGitHub(remote_of(checkout))
    sha = report.candidate_commit or ""
    gh.before_create = lambda: None if gh.pulls else gh.open_pull(BRANCH, sha, "by the other run")

    proposal, _, _ = run(checkout, artifact, gh)

    assert proposal.outcome is Outcome.EXISTS and proposal.pull_number == 1
    assert len(gh.pulls) == 1 and gh.calls.count("POST") == 1


def test_an_unreachable_remote_fails_before_any_api_call(checkout: Path) -> None:
    artifact, _ = prepared(checkout)
    gh = FakeGitHub(remote_of(checkout))
    git(checkout, "remote", "set-url", "origin", str(checkout.parent / "missing.git"))
    proposal, _, g = run(checkout, artifact, gh)
    assert proposal.outcome is Outcome.FAILED and gh.calls == []
    assert any(line.startswith("$ git fetch") and "exit 0" not in line for line in g.log)


# --------------------------------------------------------------------------- nothing to propose


def test_a_candidate_main_already_carries_opens_nothing(checkout: Path) -> None:
    artifact, report = prepared(checkout)
    # the data reached main another way (as 70532 did through a manual PR, #80)
    bundle = artifact / "out" / "candidate.bundle"
    git(checkout, "fetch", "--quiet", str(bundle), f"ota-prepare/{BUILD}")
    git(checkout, "push", "--quiet", "origin", f"{report.candidate_commit}:refs/heads/main")
    gh = FakeGitHub(remote_of(checkout))

    proposal, body, _ = run(checkout, artifact, gh)

    assert proposal.outcome is Outcome.ALREADY_ON_MAIN and EXIT_CODES[proposal.outcome] == 0
    assert proposal.reason == "main already carries every file of the candidate"
    assert body is None and gh.calls == [] and branch_tip(checkout) is None


def test_a_candidate_that_changes_nothing_opens_nothing(checkout: Path) -> None:
    # main already carries the build: the forced re-run keeps main's bytes, the commit is empty
    advance_main(
        checkout,
        {
            f"data/normalized/forever/{BUILD}/manifest.json": REAL_MANIFEST,
            f"docs/research/forever-{BUILD}-tooltip-coverage.md": b"ok\n",
        },
    )
    artifact, report = prepared(checkout, runner=FakeRunner(manifest=REAL_MANIFEST))
    assert report.status is Readiness.PREPARED and report.changed_paths == []
    gh = FakeGitHub(remote_of(checkout))

    proposal, _, _ = run(checkout, artifact, gh)

    assert proposal.outcome is Outcome.ALREADY_ON_MAIN
    assert proposal.reason == "the candidate changes nothing on main"
    assert gh.calls == [] and branch_tip(checkout) is None


def test_a_closed_pull_request_is_not_reopened(checkout: Path) -> None:
    artifact, _ = prepared(checkout)
    gh = FakeGitHub(remote_of(checkout))
    run(checkout, artifact, gh)
    gh.pulls[0]["state"] = "closed"

    proposal, _, _ = run(checkout, artifact, gh)

    assert proposal.outcome is Outcome.DECLINED and EXIT_CODES[proposal.outcome] == 0
    assert proposal.pull_number == 1 and gh.calls.count("POST") == 1


# --------------------------------------------------------------------------- refusals


@pytest.mark.parametrize(
    ("codes", "status"),
    [
        ({"pytest": 1}, Readiness.BLOCKED),
        ({"sync-datasets-check": 1}, Readiness.BLOCKED),
        ({"fetch": cli_main.EXIT_SOURCE_UNAVAILABLE}, Readiness.UNKNOWN),
    ],
    ids=["tests-failed", "bundle-check-failed", "source-unavailable"],
)
def test_an_unsuccessful_preparation_is_never_proposed(
    checkout: Path, codes: dict[str, int], status: Readiness
) -> None:
    artifact, report = prepared(checkout, runner=CoverageRunner(codes))
    assert report.status is status
    gh = FakeGitHub(remote_of(checkout))

    proposal, body, _ = run(checkout, artifact, gh)

    assert proposal.outcome is Outcome.REFUSED and EXIT_CODES[proposal.outcome] == 3
    assert f"ended {status.value}" in proposal.reason
    assert body is None and gh.calls == [] and branch_tip(checkout) is None


def test_a_crashed_preparation_is_never_proposed(checkout: Path, tmp_path: Path) -> None:
    artifact = tmp_path / "crashed"
    (artifact / "out").mkdir(parents=True)
    (artifact / "out" / "crash.txt").write_text("Traceback ...\n")
    gh = FakeGitHub(remote_of(checkout))
    proposal, _, _ = run(checkout, artifact, gh)
    assert proposal.outcome is Outcome.REFUSED and gh.calls == []


def rewrite(artifact: Path, edit: Callable[[dict[str, Any]], None]) -> None:
    path = artifact / "out" / "report.json"
    doc = json.loads(path.read_text())
    edit(doc)
    path.write_text(json.dumps(doc, indent=2))


@pytest.mark.parametrize(
    ("edit", "reason"),
    [
        (lambda d: d.update(unexpected=1), "no valid preparation report"),
        (lambda d: d.update(publishable=True), "no valid preparation report"),
        (lambda d: d.update(changedPaths="5"), "no valid preparation report"),
        (
            lambda d: d["findings"].append({"kind": "BLOCKER", "source": "G3", "detail": "x"}),
            "lists BLOCKER (G3)",
        ),
        (lambda d: d["steps"][-1].update(outcome="FAILED"), "not every step passed"),
        (lambda d: d.update(candidateBuild="1.60.1.70600"), "different preparations"),
        (lambda d: d.update(candidateCommit="f" * 40), "the report names ffffffffffff"),
        (lambda d: d.update(changedPaths=1), "the report recorded 1"),
    ],
    ids=[
        "unknown-field",
        "publishable",
        "wrong-type",
        "blocker",
        "failed-step",
        "other-build",
        "other-commit",
        "file-count",
    ],
)
def test_an_inconsistent_report_is_refused(
    checkout: Path, edit: Callable[[dict[str, Any]], None], reason: str
) -> None:
    artifact, _ = prepared(checkout)
    rewrite(artifact, edit)
    gh = FakeGitHub(remote_of(checkout))
    proposal, _, _ = run(checkout, artifact, gh)
    assert proposal.outcome is Outcome.REFUSED and reason in proposal.reason, proposal.reason
    assert gh.calls == [] and branch_tip(checkout) is None


def test_an_artifact_with_another_name_is_refused(checkout: Path) -> None:
    artifact, _ = prepared(checkout)
    gh = FakeGitHub(remote_of(checkout))
    proposal, _, _ = run(checkout, artifact, gh, expected_artifact="ota-prepare-other-PREPARED")
    assert proposal.outcome is Outcome.REFUSED and gh.calls == []


def tamper(checkout: Path, artifact: Path, change: Callable[[Path], None], *, email: str) -> None:
    """Replace the bundle's candidate commit by a changed one (and update the report)."""
    report = json.loads((artifact / "out" / "report.json").read_text())
    scratch = checkout.parent / "scratch"
    git(checkout.parent, "clone", "--quiet", str(checkout), str(scratch))
    branch = f"ota-prepare/{BUILD}"
    git(scratch, "fetch", "--quiet", str(artifact / "out" / "candidate.bundle"), branch)
    git(scratch, "checkout", "--quiet", "--detach", "FETCH_HEAD")
    change(scratch)
    git(scratch, "add", "--all")
    identity = {"GIT_AUTHOR_NAME": "x", "GIT_AUTHOR_EMAIL": email}
    identity |= {"GIT_COMMITTER_NAME": "x", "GIT_COMMITTER_EMAIL": email}
    subprocess.run(
        ["git", "-C", str(scratch), "-c", "commit.gpgsign=false", "commit", "--quiet", *AMEND],
        check=True,
        env={**os.environ, **identity},
    )
    head = git(scratch, "rev-parse", "HEAD")
    git(scratch, "branch", "--force", branch, head)
    bundle = artifact / "out" / "candidate.bundle"
    bundle.unlink()
    git(scratch, "bundle", "create", str(bundle), branch, f"^{report['mainCommit']}")
    count = len(git(scratch, "diff", "--name-only", report["mainCommit"], head).splitlines())
    rewrite(artifact, lambda d: d.update(candidateCommit=head, changedPaths=count))


AMEND = ("--amend", "--reset-author", "--allow-empty", "-m", "changed")


def _stray(scratch: Path) -> None:
    (scratch / "mobile").mkdir(exist_ok=True)
    (scratch / "mobile" / "settings.gradle.kts").write_text("stray\n")


def _executable(scratch: Path) -> None:
    (scratch / f"docs/research/forever-{BUILD}-tooltip-coverage.md").chmod(0o755)


def _symlink(scratch: Path) -> None:
    (scratch / f"docs/research/forever-{BUILD}-ruRU-coverage.md").symlink_to("/etc/passwd")


def _deletion(scratch: Path) -> None:
    (scratch / f"data/normalized/forever/{BASE}/manifest.json").unlink()


@pytest.mark.parametrize(
    ("change", "reason"),
    [
        (_stray, "mobile/settings.gradle.kts, outside its own paths"),
        (_executable, "mode 100755"),
        (_symlink, "mode 120000"),
        (_deletion, f"{BASE}/manifest.json, outside its own paths"),
    ],
    ids=["stray-file", "executable", "symlink", "deleted-base"],
)
def test_a_candidate_commit_with_files_outside_its_scope_is_refused(
    checkout: Path, change: Callable[[Path], None], reason: str
) -> None:
    artifact, _ = prepared(checkout)
    tamper(checkout, artifact, change, email=CANDIDATE_AUTHOR[1])
    gh = FakeGitHub(remote_of(checkout))
    proposal, _, _ = run(checkout, artifact, gh)
    assert proposal.outcome is Outcome.REFUSED and reason in proposal.reason, proposal.reason
    assert gh.calls == [] and branch_tip(checkout) is None


def test_a_candidate_commit_by_someone_else_is_refused(checkout: Path) -> None:
    artifact, _ = prepared(checkout)
    tamper(checkout, artifact, lambda scratch: None, email="someone@example.invalid")
    gh = FakeGitHub(remote_of(checkout))
    proposal, _, _ = run(checkout, artifact, gh)
    assert proposal.outcome is Outcome.REFUSED and "not made by the preparation" in proposal.reason


def test_a_branch_with_commits_the_automation_did_not_make_is_never_overwritten(
    checkout: Path,
) -> None:
    artifact, _ = prepared(checkout)
    gh = FakeGitHub(remote_of(checkout))
    run(checkout, artifact, gh)
    # a reviewer pushes a fix to the review branch
    git(checkout, "fetch", "--quiet", "origin", BRANCH)
    git(checkout, "checkout", "--quiet", "--detach", "FETCH_HEAD")
    reviewed = commit(checkout, "review fix", {f"docs/research/forever-{BUILD}-note.md": b"x\n"})
    git(checkout, "push", "--quiet", "origin", f"HEAD:refs/heads/{BRANCH}")
    git(checkout, "checkout", "--quiet", "main")

    changed, _ = prepared(checkout, "prep-2", CoverageRunner(manifest=b'{"v": 2}\n'))
    proposal, _, _ = run(checkout, changed, gh)

    assert proposal.outcome is Outcome.CONFLICT and EXIT_CODES[proposal.outcome] == 3
    assert branch_tip(checkout) == reviewed
    assert gh.calls.count("POST") == 1 and "PATCH" not in gh.calls


# --------------------------------------------------------------------------- contract and CLI


def test_every_preparation_report_reads_back_strictly(checkout: Path) -> None:
    for name, runner in (
        ("ok", CoverageRunner()),
        ("blocked", CoverageRunner({"pytest": 1})),
        ("unknown", CoverageRunner({"fetch": cli_main.EXIT_SOURCE_UNAVAILABLE})),
    ):
        artifact, report = prepared(checkout, name, runner)
        doc = PreparationReport.model_validate_json((artifact / "out/report.json").read_bytes())
        assert doc.status is report.status and doc.artifact_name == report.artifact_name


def test_the_cli_proposes_and_keeps_diagnostics_without_the_token(
    checkout: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import talent_data.prepare.propose as module

    artifact, report = prepared(checkout)
    gh = FakeGitHub(remote_of(checkout))
    monkeypatch.setattr(module, "github_client", lambda token, api_url: gh.client())
    monkeypatch.setenv("GH_TOKEN", "tok-secret-value")
    outputs, summary, out = tmp_path / "outputs", tmp_path / "summary.md", tmp_path / "propose"
    args = [
        "propose",
        "--repo",
        str(checkout),
        "--artifact",
        str(artifact),
        "--artifact-name",
        report.artifact_name,
        "--out",
        str(out),
        "--repository",
        REPOSITORY,
        "--preparation-url",
        RUN_URL,
        "--github-output",
        str(outputs),
        "--step-summary",
        str(summary),
    ]

    assert main(args) == 0
    recorded = dict(line.split("=", 1) for line in outputs.read_text().splitlines())
    assert recorded["outcome"] == "OPENED" and recorded["branch"] == BRANCH
    assert recorded["pr_url"].endswith("/pull/1")
    assert "OTA candidate pull request: OPENED" in summary.read_text()
    doc = json.loads((out / "proposal.json").read_text())
    assert doc["kind"] == "ota-prepare-proposal" and doc["outcome"] == "OPENED"
    names = {p.name for p in out.iterdir()}
    assert names == {"proposal.json", "summary.md", "git.log", "pr-body.md"}
    for path in out.iterdir():
        assert "tok-secret-value" not in path.read_text()
    assert "$ git push" in (out / "git.log").read_text()

    assert main(args) == 0  # again: nothing new
    assert "outcome=EXISTS" in outputs.read_text() and len(gh.pulls) == 1


def test_the_cli_fails_without_a_token(
    checkout: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("GH_TOKEN", raising=False)
    monkeypatch.delenv("GITHUB_TOKEN", raising=False)
    artifact, _ = prepared(checkout)
    out = tmp_path / "propose"
    args = ["propose", "--repo", str(checkout), "--artifact", str(artifact), "--out", str(out)]
    args += ["--repository", REPOSITORY, "--github-output", str(tmp_path / "o")]
    assert main(args) == 1
    assert json.loads((out / "proposal.json").read_text())["outcome"] == "FAILED"
    assert branch_tip(checkout) is None


def test_the_detection_names_the_prepared_artifact_only_for_its_no_action() -> None:
    key = detect().preparation_key or ""
    prepared_name = f"ota-prepare-{key}-PREPARED"
    done = detect(prior_artifacts=[prepared_name])
    assert done.action is Action.NO_ACTION
    assert detection_outputs(done)["prepared_artifact"] == prepared_name
    retried = detect(prior_artifacts=[f"ota-prepare-{key}-BLOCKED"])
    assert retried.action is Action.PREPARE and retried.prior_artifact
    assert detection_outputs(retried)["prepared_artifact"] == ""
    assert detection_outputs(detect(INDEX[:2]))["prepared_artifact"] == ""


# --------------------------------------------------------------------------- the workflow

WORKFLOW = ROOT / ".github/workflows/ota-prepare.yml"


def bash(script: str, env: dict[str, str], cwd: Path) -> subprocess.CompletedProcess[str]:
    # GitHub runs ``shell: bash`` as ``bash --noprofile --norc -eo pipefail {0}``
    return subprocess.run(
        ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", script],
        env={"PATH": "/usr/bin:/bin", **env},
        cwd=cwd,
        capture_output=True,
        text=True,
        check=False,
    )


NAME = "ota-prepare-forever-1.60.1.70500-r1-from-1.60.1.70450-r1-main-aaaaaaaaaaaa-PREPARED"
KEPT = f"other-artifact\t7\n{NAME}\t42\n{NAME}\t41\n"
PREPARED_HERE = {"DETECT_ACTION": "PREPARE", "PREPARE_EXIT": "0", "PREPARE_STATUS": "PREPARED"}


@pytest.mark.parametrize(
    ("env", "artifact", "run_id"),
    [
        (PREPARED_HERE, NAME, "100"),
        ({**PREPARED_HERE, "UPLOAD_OUTCOME": "failure"}, "", ""),  # the artifact is missing
        ({"DETECT_ACTION": "PREPARE", "PREPARE_EXIT": "3", "PREPARE_STATUS": "BLOCKED"}, "", ""),
        ({"DETECT_ACTION": "PREPARE", "PREPARE_EXIT": "4", "PREPARE_STATUS": "UNKNOWN"}, "", ""),
        ({"DETECT_ACTION": "PREPARE", "PREPARE_EXIT": "1"}, "", ""),  # crashed
        ({"DETECT_ACTION": "PREPARE", "PREPARE_EXIT": "0", "PREPARE_STATUS": "BLOCKED"}, "", ""),
        ({**PREPARED_HERE, "ARTIFACT_NAME": NAME.replace("PREPARED", "BLOCKED")}, "", ""),
        ({"DETECT_ACTION": "NO_ACTION", "PREPARED_ARTIFACT": NAME}, NAME, "42"),  # newest run
        ({"DETECT_ACTION": "NO_ACTION", "PREPARED_ARTIFACT": NAME.replace("aaaa", "bbbb")}, "", ""),
        ({"DETECT_ACTION": "NO_ACTION"}, "", ""),
        ({"DETECT_EXIT": "4", "DETECT_ACTION": "UNKNOWN"}, "", ""),
        ({"DETECT_EXIT": "", "DETECT_ACTION": ""}, "", ""),  # the detection never ran
    ],
)
def test_the_workflow_proposes_only_a_prepared_candidate(
    tmp_path: Path, env: dict[str, str], artifact: str, run_id: str
) -> None:
    (tmp_path / "prep").mkdir()
    (tmp_path / "prep" / "prior-artifacts.tsv").write_text(KEPT)
    outputs = tmp_path / "outputs"
    completed = bash(
        workflow_step_script("Select the candidate to propose"),
        {
            "DETECT_EXIT": "0",
            "ARTIFACT_NAME": NAME,
            "UPLOAD_OUTCOME": "success",
            "RUN_ID": "100",
            "GITHUB_OUTPUT": str(outputs),
            **env,
        },
        tmp_path,
    )
    assert completed.returncode == 0, completed.stderr
    recorded = dict(line.split("=", 1) for line in outputs.read_text().splitlines())
    assert recorded == {"artifact": artifact, "run_id": run_id}


@pytest.mark.parametrize(
    ("download", "code", "outcome", "job_exit"),
    [
        ("success", "0", "OPENED", 0),
        ("success", "0", "EXISTS", 0),
        ("success", "0", "ALREADY_ON_MAIN", 0),
        ("success", "0", "DECLINED", 0),
        ("success", "1", "FAILED", 1),
        ("success", "3", "REFUSED", 3),
        ("success", "3", "CONFLICT", 3),
        ("success", "0", "FAILED", 1),  # contradictory
        ("success", "0", "", 1),
        ("success", "", "", 1),  # the step died
        ("failure", "", "", 1),  # the artifact could not be downloaded
    ],
)
def test_the_proposal_status_is_green_only_for_a_finished_proposal(
    tmp_path: Path, download: str, code: str, outcome: str, job_exit: int
) -> None:
    summary = tmp_path / "summary.md"
    completed = bash(
        workflow_step_script("Final proposal status"),
        {
            "DOWNLOAD_OUTCOME": download,
            "PROPOSE_EXIT": code,
            "PROPOSE_OUTCOME": outcome,
            "ARTIFACT": NAME,
            "GITHUB_STEP_SUMMARY": str(summary),
        },
        tmp_path,
    )
    assert completed.returncode == job_exit, completed.stdout + completed.stderr
    if download != "success":
        assert NAME in summary.read_text()


def job(name: str) -> str:
    text = WORKFLOW.read_text(encoding="utf-8")
    start = text.index(f"\n  {name}:\n")
    others = ("prepare", "propose", "notify")
    following = [text.find(f"\n  {other}:\n", start + 1) for other in others]
    return text[start : min([i for i in following if i > start] or [len(text)])]


def test_only_the_propose_job_can_write_and_only_what_it_needs() -> None:
    text = WORKFLOW.read_text(encoding="utf-8")
    # App key stays in the main-only proposal job; Telegram stays in notify.
    # No production signing/S3 credential exists anywhere in this workflow.
    assert sorted(line.strip() for line in text.splitlines() if "secrets." in line) == [
        "OTA_APP_PRIVATE_KEY: ${{ secrets.OTA_APP_PRIVATE_KEY }}",
        "TELEGRAM_BOT_TOKEN: ${{ secrets.TELEGRAM_BOT_TOKEN }}",
        "TELEGRAM_CHAT_ID: ${{ secrets.TELEGRAM_CHAT_ID }}",
        "private-key: ${{ secrets.OTA_APP_PRIVATE_KEY }}",
    ]
    for forbidden in ("--execute", "promote", "attach-signature", "aws ", "AWS_", "git push"):
        assert forbidden not in text, forbidden
    assert "permissions:\n  contents: read\n  actions: read" in text  # the default
    assert "permissions:" not in job("prepare")  # inherits the read-only default
    assert "candidate_artifact: ${{ steps.select.outputs.artifact }}" in job("prepare")
    propose_job = job("propose")
    block = propose_job.split("    permissions:\n", 1)[1].split("    steps:\n", 1)[0]
    assert [line.split("#")[0].strip() for line in block.splitlines()] == [
        "contents: read",
        "actions: read",
    ]
    assert "needs: prepare" in propose_job
    assert "if: needs.prepare.outputs.candidate_artifact != ''" in propose_job
    assert propose_job.count("persist-credentials: false") == 1
    assert "run-id: ${{ needs.prepare.outputs.candidate_run_id }}" in propose_job
    assert '--artifact-name "$ARTIFACT"' in propose_job
    assert "name: ota-propose-${{ github.run_id }}" in propose_job
    assert 'run: pip install -e ".[ota]"' in propose_job  # runtime + cryptography only
