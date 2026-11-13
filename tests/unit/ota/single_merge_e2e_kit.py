"""Real 403-file Git/package/TEST-store twin. Only GitHub/remote runtime facts are fixtures.

No production credential, network or gate/provenance/authorization/public-proof replacement.
"""

import copy
import json
import os
import subprocess
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import httpx
from cryptography.hazmat.primitives import serialization

from talent_data.ota.automation import candidate, cli, prepare, provenance
from talent_data.ota.automation.premerge import (
    bind_candidate_request,
    mark_candidate_ready,
    prepare_candidate,
)
from talent_data.ota.automation.requests import RunPin
from talent_data.ota.automation.state import FileJournal
from talent_data.ota.catalog import catalog_entry, next_catalog
from talent_data.ota.models import Channel
from talent_data.ota.release import build_release
from talent_data.ota.release_package import (
    STAGING_UPDATE_TEST,
    candidate_binding,
    load_package,
)
from talent_data.ota.signing import build_envelope, sign_pae_der
from talent_data.ota.store import (
    BLOB_CACHE_CONTROL,
    CATALOG_CACHE_CONTROL,
    LocalDirectoryStore,
    blob_key,
    catalog_key,
)
from talent_data.ota.verify import sha256_ref
from talent_data.prepare.detect import artifact_name, read_detection
from talent_data.prepare.orchestrate import (
    CANDIDATE_AUTHOR,
    CANDIDATE_BRANCH,
    candidate_path_allowed,
)
from talent_data.prepare.staging_verify import CLIENT_SCENARIOS
from tests.unit.ota.publisher_testkit import ROOT, VALIDATION_NOTE, derive_test_key
from tests.unit.ota.release_package_testkit import load_script
from tests.unit.ota.single_merge_testkit import OWNER, REPOSITORY, CandidateAPI, candidate_fixture
from tests.unit.ota.test_automation_provenance import zip_tree
from tests.unit.ota.test_ota_preflight import KEYRING, PROD_A, FakeOrigin
from tests.unit.ota.test_premerge_proposal import App
from tests.unit.test_ota_prepare import git

BUILD, BASE = "1.60.1.70888", "1.60.1.70862"
NOW = datetime(2026, 11, 14, 10, tzinfo=UTC)
CATALOG = catalog_key(Channel.PRODUCTION)


def stamp(value):
    return value.isoformat().replace("+00:00", "Z")


def commit_tree(repo, tree, parents, message, author=("TEST", "test@example.invalid")):
    env = {
        **os.environ,
        "GIT_AUTHOR_NAME": author[0],
        "GIT_AUTHOR_EMAIL": author[1],
        "GIT_COMMITTER_NAME": author[0],
        "GIT_COMMITTER_EMAIL": author[1],
    }
    argv = ["git", "-C", str(repo), "commit-tree", tree]
    for parent in parents:
        argv += ["-p", parent]
    return subprocess.check_output(argv, input=message.encode(), env=env).decode().strip()


def actual_candidate(tmp_path):
    repo = tmp_path / "repo"
    git(tmp_path, "clone", "--quiet", "--shared", "--no-checkout", str(ROOT), str(repo))
    git(repo, "config", "core.hooksPath", "/dev/null")
    head = git(ROOT, "rev-parse", "HEAD")
    git(repo, "read-tree", head)
    paths = git(repo, "ls-tree", "-r", "--name-only", head).splitlines()
    # No inherited request/approval can authorize this prospective TEST release.
    metadata = [
        p for p in paths if p.startswith(("docs/ota/release-requests/", "docs/ota/releases/"))
    ]
    for path in metadata:
        git(repo, "update-index", "--force-remove", "--", path)
    source_tree = git(repo, "write-tree")
    owned = [p for p in paths if candidate_path_allowed(p, game="forever", build=BUILD, base=BASE)]
    # Keep only actual candidate additions: its committed data and derived reports.
    assert any(p.startswith(f"data/normalized/forever/{BUILD}/") for p in owned)
    for path in owned:
        git(repo, "update-index", "--force-remove", "--", path)
    base_tree = git(repo, "write-tree")
    base = commit_tree(repo, base_tree, [head], "TEST trusted main without candidate")
    source = commit_tree(repo, source_tree, [base], "TEST generated candidate", CANDIDATE_AUTHOR)
    git(repo, "checkout", "--quiet", "--detach", base)
    remote = tmp_path / "origin.git"
    git(tmp_path, "init", "--quiet", "--bare", str(remote))
    git(repo, "remote", "set-url", "origin", str(remote))
    git(repo, "push", "--quiet", "origin", base + ":refs/heads/main")
    git(repo, "fetch", "--quiet", "origin", "main")
    ref = "refs/heads/" + CANDIDATE_BRANCH.format(build=BUILD)
    git(repo, "update-ref", ref, source)
    git(repo, "push", "--quiet", "origin", source + ":refs/heads/ota-candidate/forever-" + BUILD)
    seed = tmp_path / "seed"
    seed.mkdir()
    seed_repo, _seed_api = candidate_fixture(seed)
    artifact = tmp_path / "candidate"
    (artifact / "out").mkdir(parents=True)
    seed_out = seed_repo.parent / "prep" / "out"
    detection = json.loads((seed_out / "detection.json").read_bytes())
    raw = json.dumps(detection).replace("1.60.1.70500", BUILD).replace("1.60.1.70450", BASE)
    detection = json.loads(raw)
    detection["mainCommit"] = base
    (artifact / "out/detection.json").write_text(json.dumps(detection))
    detected = read_detection(artifact / "out/detection.json")
    name = artifact_name(detected.preparation_key, "PREPARED")
    report = json.loads(
        json.dumps(json.loads((seed_out / "report.json").read_bytes()))
        .replace("1.60.1.70500", BUILD)
        .replace("1.60.1.70450", BASE)
    )
    report.update(
        mainCommit=base,
        candidateCommit=source,
        artifactName=name,
        changedPaths=len(git(repo, "diff", "--name-only", base, source).splitlines()),
        startedAt=stamp(NOW - timedelta(hours=4)),
        finishedAt=stamp(NOW - timedelta(hours=3)),
    )
    (artifact / "out/report.json").write_text(json.dumps(report))
    git(repo, "bundle", "create", str(artifact / "out/candidate.bundle"), ref, "^" + base)
    return (
        repo,
        source,
        base,
        artifact,
        SimpleNamespace(
            artifact_name=name,
            started_at=NOW - timedelta(hours=4),
            finished_at=NOW - timedelta(hours=3),
        ),
    )


class Store(LocalDirectoryStore):
    def __init__(self, root):
        super().__init__(root)
        self.catalog_puts = 0
        self.hook = None
        self.unknown = False

    def put(self, key, data, *, cache_control):
        super().put(key, data, cache_control=cache_control)
        if key == CATALOG:
            self.catalog_puts += 1
            if self.unknown:
                self.unknown = False
                raise OSError("TEST lost catalog PUT response after exact write")
        elif self.hook is not None:
            hook, self.hook = self.hook, None
            hook()


class API(CandidateAPI):
    def __init__(self, world, source, base, artifact, report):
        super().__init__(world.repo, source, base, artifact, report)
        self.world = world
        self.token = "TEST_READER"
        self.branch = f"ota-candidate/forever-{BUILD}"
        self.pull["head"]["ref"] = self.branch
        self.pull["node_id"] = "PR_403"
        self.runs[201]["head_branch"] = self.branch
        self.runs[201]["created_at"] = stamp(NOW - timedelta(hours=2))
        self.runs[201]["updated_at"] = stamp(NOW - timedelta(hours=2) + timedelta(minutes=5))
        self.producers = {prepare.STAGING_WORKFLOW: [], prepare.PACKAGE_WORKFLOW: []}
        self.artifacts = {11: self.artifact}
        self.archives = {11: self.raw}
        self.tags, self.refs = {}, {}
        self.posts = []
        self.events = []

    def repo(self, method, path, **kwargs):
        data = kwargs.get("data")
        if method != "GET":
            self.posts.append((method, path, copy.deepcopy(data)))
        if path.startswith("/actions/workflows/") and path.endswith("/dispatches"):
            assert method == "POST" and data["ref"] == "main"
            workflow = ".github/workflows/" + path.split("/")[3]
            run = self.world.produce(workflow, data["inputs"])
            self.producers[workflow].append(run)
            return {"workflow_run_id": run["id"]}
        if path.startswith("/actions/runs/"):
            return copy.deepcopy(self.runs[int(path.split("/")[3])])
        if path.startswith("/actions/artifacts/"):
            return copy.deepcopy(self.artifacts[int(path.split("/")[3])])
        if path == "/git/ref/heads/main":
            return {"object": {"sha": git(self.repo_path, "rev-parse", "origin/main")}}
        if path.startswith("/git/ref/tags/"):
            tag = path.removeprefix("/git/ref/tags/")
            return {"object": {"sha": self.refs[tag], "type": "tag"}} if tag in self.refs else None
        if path.startswith("/git/tags/"):
            return copy.deepcopy(self.tags[path.rsplit("/", 1)[-1]])
        if path == "/git/tags" and method == "POST":
            date = int(
                datetime.fromisoformat(data["tagger"]["date"].replace("Z", "+00:00")).timestamp()
            )
            body = (
                f"object {data['object']}\ntype commit\ntag {data['tag']}\n"
                f"tagger TEST <test@example.invalid> {date} +0000\n\n{data['message']}"
            )
            sha = (
                subprocess.check_output(
                    ["git", "-C", str(self.repo_path), "mktag"], input=body.encode()
                )
                .decode()
                .strip()
            )
            self.tags[sha] = {
                "tag": data["tag"],
                "message": data["message"],
                "object": {"sha": data["object"], "type": "commit"},
            }
            return {"sha": sha}
        if path == "/git/refs" and method == "POST":
            name = data["ref"].removeprefix("refs/tags/")
            assert name not in self.refs
            self.refs[name] = data["sha"]
            git(self.repo_path, "update-ref", data["ref"], data["sha"])
            return {"object": {"sha": data["sha"], "type": "tag"}}
        if path.startswith("/git/commits/"):
            result = super().repo(method, path, **kwargs)
            sha = path.rsplit("/", 1)[-1]
            result["author"] = {
                "name": git(self.repo_path, "show", "-s", "--format=%an", sha),
                "email": git(self.repo_path, "show", "-s", "--format=%ae", sha),
            }
            return result
        if path.startswith("/git/trees/"):
            tree = path.rsplit("/", 1)[-1]
            return {
                "truncated": False,
                "tree": [
                    {
                        "mode": line.split()[0],
                        "type": line.split()[1],
                        "sha": line.split()[2],
                        "path": line.split("\t")[1],
                    }
                    for line in git(self.repo_path, "ls-tree", "-r", tree).splitlines()
                ],
            }
        if path.startswith("/compare/"):
            base, head = path.rsplit("/", 1)[-1].split("...")
            return {
                "total_commits": int(
                    git(self.repo_path, "rev-list", "--count", base + ".." + head)
                ),
                "files": [
                    {"filename": p, "status": {"A": "added", "M": "modified", "D": "removed"}[s]}
                    for s, p in [
                        line.split("\t", 1)
                        for line in git(
                            self.repo_path, "diff", "--name-status", base, head
                        ).splitlines()
                    ]
                ],
            }
        return super().repo(method, path, **kwargs)

    def pages(self, path, member=None, *, params=None, **kwargs):
        if path.startswith("/actions/workflows/"):
            name = path.split("/")[3]
            if name == "ci.yml":
                values = [r for r in self.runs.values() if r["path"] == ".github/workflows/ci.yml"]
            else:
                values = self.producers[".github/workflows/" + name]
            return [
                copy.deepcopy(r)
                for r in values
                if all(
                    r.get(k) == v
                    for k, v in (params or {}).items()
                    if k in ("event", "head_branch", "head_sha")
                )
            ]
        if path.endswith("/artifacts"):
            run_id = int(path.split("/")[3])
            return [
                copy.deepcopy(a)
                for a in self.artifacts.values()
                if a["workflow_run"]["id"] == run_id
            ]
        if path == "/issues/141/timeline":
            return copy.deepcopy(self.events)
        return super().pages(path, member, params=params, **kwargs)

    def archive(self, metadata, **kwargs):
        return self.archives[metadata["id"]]


class Writer(App):
    def call(self, method, path, **kwargs):
        if path == "/graphql":
            self.writes.append((method, path, kwargs["data"]))
            self.api.pull["draft"] = "convertPullRequestToDraft" in kwargs["data"]["query"]
            self.api.events.append(
                {
                    "event": "ready_for_review",
                    "actor": {"id": 351368232},
                    "created_at": stamp(NOW - timedelta(minutes=20)),
                }
            )
            return {"data": {}}
        return super().call(method, path, **kwargs)

    def repo(self, method, path, **kwargs):
        result = super().repo(method, path, **kwargs)
        if method == "PATCH" and path.startswith("/git/refs/heads/"):
            git(
                self.api.repo_path,
                "update-ref",
                "refs/heads/" + self.api.branch,
                kwargs["data"]["sha"],
            )
        return result


class World:
    def __init__(self, tmp_path, monkeypatch):
        self.root = tmp_path
        self.repo, source, base, artifact, report = actual_candidate(tmp_path)
        self.api = API(self, source, base, artifact, report)
        self.writer = Writer(self.api)
        self.package_script = load_script("ota_release_package")
        monkeypatch.setattr(self.package_script, "ROOT", self.repo)
        monkeypatch.setattr(provenance, "TRUSTED_REPO", self.repo)
        monkeypatch.setattr(
            candidate, "GitHub", lambda *args: self.api
        )  # external API adapter only
        self.monkeypatch = monkeypatch
        self.store = Store(tmp_path / "store")
        self.base_release = build_release(
            self.repo, base, "forever", BASE, 1, NOW - timedelta(hours=5), VALIDATION_NOTE
        )
        sha, raw = self.base_release.manifest_blob
        for digest, data in {sha: raw, **self.base_release.blobs}.items():
            self.store.put(blob_key(digest), data, cache_control=BLOB_CACHE_CONTROL)
        update = next_catalog(
            None,
            game="forever",
            channel=Channel.PRODUCTION,
            active_release_id=self.base_release.release_id,
            append=catalog_entry(
                raw, self.base_release.manifest, published_at=NOW - timedelta(hours=4)
            ),
            issued_at=NOW - timedelta(hours=4),
        )
        self.store.put(
            CATALOG,
            build_envelope(
                update.payload, {PROD_A: sign_pae_der(derive_test_key(PROD_A), update.payload)}
            ),
            cache_control=CATALOG_CACHE_CONTROL,
        )
        self.store.catalog_puts = 0
        self.public_gets = []

        def handler(request):
            key = request.url.path.lstrip("/")
            self.public_gets.append(key)
            path = self.store.root / key
            return FakeOrigin({key: path.read_bytes()} if path.is_file() else {}).handler(request)

        self.client = httpx.Client(transport=httpx.MockTransport(handler))
        self.api.client = self.client
        self.created = stamp(NOW - timedelta(hours=2))
        self.secrets = []
        self.writers = []

    def run_record(self, rid, path, head, event="workflow_dispatch", branch="main"):
        meta = copy.deepcopy(self.api.runs[101])
        meta.update(
            id=rid,
            path=path,
            name="CI" if path.endswith("/ci.yml") else "TEST producer",
            head_sha=head,
            head_branch=branch,
            event=event,
            status="completed",
            conclusion="success",
            created_at=stamp(NOW - timedelta(hours=2)),
            run_started_at=stamp(NOW - timedelta(hours=2)),
            updated_at=stamp(NOW - timedelta(hours=1)),
            html_url=f"https://github.com/{REPOSITORY}/actions/runs/{rid}",
            actor={"login": "operator"},
            pull_requests=[],
        )
        return meta

    def add_artifact(self, rid, aid, name, raw):
        meta = copy.deepcopy(self.api.artifact)
        meta.update(
            id=aid,
            name=name,
            digest=sha256_ref(raw),
            size_in_bytes=len(raw),
            created_at=stamp(NOW - timedelta(hours=1, minutes=5)),
        )
        meta["workflow_run"].update(id=rid, head_sha=self.api.base)
        self.api.artifacts[aid], self.api.archives[aid] = meta, raw

    def produce(self, workflow, inputs):
        source = self.api.source
        if workflow == prepare.STAGING_WORKFLOW:
            rid = 4242
            run = self.run_record(rid, workflow, self.api.base)
            self.api.runs[rid] = run
            root = self.root / f"ota-staging-verify-{rid}-1"
            root.mkdir()
            candidate_release = build_release(
                self.repo, source, "forever", BUILD, 1, NOW - timedelta(hours=2), VALIDATION_NOTE
            )
            base_release = build_release(
                self.repo, source, "forever", BASE, 1, NOW - timedelta(hours=2), VALIDATION_NOTE
            )
            for release in (base_release, candidate_release):
                p = root / "releases" / release.release_id / "release.json"
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(release.manifest_bytes)
            report = {
                "format": "talentforge-ota-staging-verification",
                "formatVersion": 1,
                "verdict": "PASS",
                "productionApproval": False,
                "revision": source,
                "baseBuild": BASE,
                "candidateBuild": BUILD,
                "checks": [
                    {"id": n, "automatic": True, "verdict": "PASS"}
                    for n in ["K0", "K1", *(s.id for s in CLIENT_SCENARIOS)]
                ],
                "facts": {
                    "tooling": {"head": self.api.base},
                    "releases": {
                        r.release_id: {"manifestSha256": sha256_ref(r.manifest_bytes)}
                        for r in (base_release, candidate_release)
                    },
                    "client": {
                        "gradleExit": 0,
                        "notExecuted": None,
                        "tests": {t: "PASS" for s in CLIENT_SCENARIOS for t in s.tests},
                    },
                },
            }
            (root / "staging-report.json").write_text(json.dumps(report))
            update = {
                "base": BASE,
                "candidate": BUILD,
                "candidateRelease": candidate_release.release_id,
                "commit": source,
                "installedFiles": {
                    f.path: [f.sha256, f.byte_length] for f in candidate_release.manifest.files
                },
                "requested": {"base": BASE, "candidate": BUILD, "revision": source},
            }
            (root / "client").mkdir()
            (root / "client" / f"{STAGING_UPDATE_TEST}.json").write_text(json.dumps(update))
            self.stage_root = root
            self.stage_run = self.root / "staging-run.json"
            self.stage_run.write_text(json.dumps(run))
            self.add_artifact(rid, 42, f"ota-staging-verify-{rid}-1", zip_tree(root))
        else:
            rid = 9000
            run = self.run_record(rid, workflow, self.api.base)
            self.api.runs[rid] = run
            out = self.root / "built-package"
            with self.monkeypatch.context() as patch:
                for key, value in {
                    "GITHUB_REPOSITORY": REPOSITORY,
                    "GITHUB_REF": "refs/heads/main",
                    "GITHUB_EVENT_NAME": "workflow_dispatch",
                    "GITHUB_RUN_ID": str(rid),
                    "GITHUB_RUN_ATTEMPT": "1",
                    "GH_TOKEN": "TEST_READER",
                }.items():
                    patch.setenv(key, value)
                args = [
                    "build",
                    str(out),
                    "--build",
                    BUILD,
                    "--base",
                    BASE,
                    "--source-revision",
                    source,
                    "--created-at",
                    inputs["created_at"],
                    "--staging-run",
                    str(self.stage_run),
                    "--staging-artifact",
                    str(self.stage_root),
                    "--repository",
                    REPOSITORY,
                    "--run-url",
                    f"https://github.com/{REPOSITORY}/actions/runs/{rid}",
                    "--candidate-pr",
                    "141",
                    "--candidate-ci-run-id",
                    "201",
                    "--candidate-ci-attempt",
                    "1",
                    "--candidate-base-release-id",
                    inputs["candidate_base_release_id"],
                    "--candidate-base-manifest-sha256",
                    inputs["candidate_base_manifest_sha256"],
                ]
                assert self.package_script.main(args) == 0
            self.add_artifact(rid, 90, f"ota-release-package-{rid}-1", zip_tree(out))
        run["display_title"] = ("ota-staging-" if rid == 4242 else "ota-package-") + inputs[
            "automation_key"
        ]
        return run

    def prepare_and_ready(self):
        pin = RunPin(
            workflow_path=".github/workflows/ci.yml",
            run_id=201,
            attempt=1,
            head_sha=self.api.source,
        )
        self.proposal = prepare_candidate(
            self.client,
            reader=self.api,
            repo=self.repo,
            ci_pin=pin,
            destination=self.root / "frozen",
            now=NOW,
            keyring=KEYRING,
        )
        before = len(self.api.posts)
        retried = prepare_candidate(
            self.client,
            reader=self.api,
            repo=self.repo,
            ci_pin=pin,
            destination=self.root / "retained-package",
            now=NOW,
            keyring=KEYRING,
        )
        assert retried.request == self.proposal.request
        assert len(self.api.posts) == before  # no new dispatch/tag or replacement archive
        self.request = self.proposal.request
        package = load_package(self.root / "frozen" / self.request.release_id)
        self.binding = candidate_binding(package)
        self.head = bind_candidate_request(
            self.api,
            self.writer,
            self.repo,
            candidate.verify_candidate(
                self.api, self.repo, 141, source_sha=self.api.source, ci_pin=pin
            ),
            self.request,
            self.binding,
        )
        run = copy.deepcopy(self.api.runs[201])
        run.update(id=301, head_sha=self.head, updated_at=stamp(NOW - timedelta(minutes=25)))
        run["pull_requests"][0]["head"]["sha"] = self.head
        self.api.runs[301] = run
        self.api.jobs[301] = copy.deepcopy(self.api.jobs[201])
        for n, job in enumerate(self.api.jobs[301]):
            job.update(id=3010 + n, run_id=301, head_sha=self.head)
        mark_candidate_ready(
            self.api,
            self.writer,
            self.repo,
            141,
            ci_pin=RunPin(
                workflow_path=".github/workflows/ci.yml", run_id=301, attempt=1, head_sha=self.head
            ),
        )
        return package

    def owner_merge(self):
        self.merge = commit_tree(
            self.repo,
            git(self.repo, "rev-parse", self.head + "^{tree}"),
            [self.api.base, self.head],
            "TEST ordinary owner merge",
            ("owner", "owner@example.invalid"),
        )
        git(self.repo, "push", "--quiet", "origin", self.merge + ":refs/heads/main")
        git(self.repo, "fetch", "--quiet", "origin", "main")
        git(self.repo, "checkout", "--quiet", "--detach", self.merge)
        self.api.pull.update(
            state="closed",
            merged=True,
            draft=False,
            merged_by=OWNER.copy(),
            merge_commit_sha=self.merge,
            merged_at=stamp(NOW),
        )
        run = self.run_record(401, ".github/workflows/ci.yml", self.merge, "push")
        run["name"] = "CI"
        self.api.runs[401] = run
        self.api.jobs[401] = [
            {
                "id": 4010 + n,
                "name": name,
                "run_id": 401,
                "run_attempt": 1,
                "head_sha": self.merge,
                "status": "completed",
                "conclusion": result,
            }
            for n, (name, result) in enumerate(
                [
                    ("classify", "success"),
                    ("full-validation", "skipped"),
                    ("metadata-validation", "skipped"),
                    ("merge-validation", "success"),
                    ("checks", "success"),
                ]
            )
        ]
        self.api.runs[500] = self.run_record(
            500, ".github/workflows/ota-production-publish.yml", self.merge, "workflow_run"
        )

    def publish(self):
        def secret(name):
            self.secrets.append(name)
            return {
                "OTA_PRODUCTION_PRIVATE_KEY_PEM": derive_test_key(PROD_A)
                .private_bytes(
                    serialization.Encoding.PEM,
                    serialization.PrivateFormat.PKCS8,
                    serialization.NoEncryption(),
                )
                .decode(),
                "OTA_PRODUCTION_S3_ACCESS_KEY_ID": "TEST_ACCESS",
                "OTA_PRODUCTION_S3_SECRET_ACCESS_KEY": "TEST_SECRET",
            }.get(name)

        @contextmanager
        def writer():
            self.writers.append(True)
            yield self.store

        self.journal = FileJournal(self.root / "journal.json")
        if not (self.root / "journal.json").exists():
            self.journal.initialize()
        temp = self.root / "secrets"
        temp.mkdir(exist_ok=True)
        deps = cli.Dependencies(
            repo=self.repo,
            api=self.api,
            client=self.client,
            journal=self.journal,
            keyring=KEYRING,
            writer=writer,
            read_secret=secret,
            temporary_root=temp,
            now=lambda: NOW + timedelta(minutes=3),
            verification_budget=2,
        )
        event = self.root / "event.json"
        event.write_text(
            json.dumps(
                {
                    "action": "completed",
                    "repository": {"full_name": REPOSITORY},
                    "sender": {"login": "github-actions[bot]"},
                    "workflow_run": {"id": 401, "run_attempt": 1, "head_sha": self.merge},
                }
            )
        )
        environment = {
            "OTA_PRODUCTION_AUTOMATION_ENABLED": "true",
            "OTA_PRODUCTION_KEY_ID": PROD_A,
            "GITHUB_REPOSITORY": REPOSITORY,
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_SHA": self.merge,
            "GITHUB_EVENT_NAME": "workflow_run",
            "GITHUB_RUN_ID": "500",
            "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_ACTOR": "operator",
            "GITHUB_EVENT_PATH": str(event),
        }
        status = self.root / "status.json"
        code = cli.main(
            [
                "publish",
                "--execute",
                "--release-id",
                self.request.release_id,
                "--status-file",
                str(status),
                "--timings-file",
                str(self.root / "delivery-timings.json"),
            ],
            environment=environment,
            dependencies=deps,
        )
        return code, cli.read_status(status.read_bytes())
