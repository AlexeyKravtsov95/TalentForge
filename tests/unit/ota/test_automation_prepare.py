"""Release preparation uses real exact packages and TEST-signed production bytes."""

from __future__ import annotations

import copy
import io
import json
import zipfile

import httpx
import pytest

from talent_data.ota.automation import prepare as p
from talent_data.ota.release_package import STAGING_FILES, load_package
from talent_data.ota.verify import sha256_ref
from talent_data.prepare.staging_verify import CLIENT_SCENARIOS
from tests.unit.ota.publisher_testkit import COMMIT, NOW, ROOT
from tests.unit.ota.test_automation_provenance import exact_package, zip_tree  # noqa: F401
from tests.unit.ota.test_ota_preflight import KEYRING, FakeOrigin, publication


@pytest.fixture
def prepared_inputs(exact_package, tmp_path):  # noqa: F811
    request, runs, metadata, raw, _ = exact_package
    root = p.extract_verified_archive(raw, tmp_path / "package")
    report_path = root / STAGING_FILES["report"]
    report = json.loads(report_path.read_bytes())
    tests = {test: "PASS" for scenario in CLIENT_SCENARIOS for test in scenario.tests}
    report["facts"]["client"] = {"gradleExit": 0, "notExecuted": None, "tests": tests}
    report["checks"] = [
        {"id": name, "automatic": True, "verdict": "PASS"}
        for name in ["K0", "K1", *(s.id for s in CLIENT_SCENARIOS)]
    ]
    body = json.dumps(report).encode()
    report_path.write_bytes(body)
    index_path = root / "package.json"
    index = json.loads(index_path.read_bytes())
    index["files"][STAGING_FILES["report"]] = {"sha256": sha256_ref(body), "byteLength": len(body)}
    index_path.write_text(json.dumps(index))
    raw = zip_tree(root)
    digest = sha256_ref(raw)
    package = load_package(root)
    name = f"ota-release-package-{runs['package']['id']}-{runs['package']['run_attempt']}"
    metadata = {**metadata, "digest": digest, "name": name}
    request = request.model_copy(
        update={
            "artifact": request.artifact.model_copy(update={"archive_sha256": digest, "name": name})
        }
    )
    return request, copy.deepcopy(runs), metadata, raw, package


class FakeGitHub:
    def __init__(self, inputs):
        self.request, self.runs, self.artifact, self.archive, self.package = inputs
        self.origin = FakeOrigin(publication())
        self.branch = None
        self.prs = []
        self.commits = {COMMIT: {"sha": COMMIT, "tree": {"sha": "base-tree"}}}
        self.trees = {}
        self.calls = []
        self.posts = []
        self.dispatched = []
        self.run_list = {p.STAGING_WORKFLOW: [], p.PACKAGE_WORKFLOW: []}
        self.failure = None
        self.ci = {
            **self.runs["package"],
            "id": 7000,
            "run_attempt": 1,
            "path": p.CI_WORKFLOW,
            "head_sha": COMMIT,
            "event": "push",
            "name": "CI",
        }
        self.jobs = [{"name": "checks", "status": "completed", "conclusion": "success"}]
        self.correlation = None

    def handler(self, req):
        path = req.url.path
        self.calls.append((req.method, path))
        if req.url.host == "ota.mythweld.com":
            return self.origin.handler(req)
        assert req.url.host in ("api.github.com", "artifacts.example.test")
        data = json.loads(req.content) if req.content else None
        if req.method != "GET":
            self.posts.append((path, data))
        prefix = "/repos/AlexeyKravtsov95/TalentForge"
        route = path.removeprefix(prefix)
        if req.url.host == "artifacts.example.test":
            assert "authorization" not in req.headers
            return httpx.Response(200, stream=httpx.ByteStream(self.archive))
        if path == "/installation/repositories":
            return httpx.Response(
                200,
                json={"total_count": 1, "repositories": [{"full_name": p.REPOSITORY, "id": 42}]},
            )
        if route == "/git/ref/heads/main":
            return httpx.Response(200, json={"object": {"sha": COMMIT}})
        if route.startswith("/actions/workflows/"):
            workflow = ".github/workflows/" + route.split("/")[3]
            if route.endswith("/dispatches"):
                self.dispatched.append((workflow, data))
                assert data["ref"] == "main"
                assert (
                    data["inputs"].get("source_revision", data["inputs"].get("revision")) == COMMIT
                )
                run = copy.deepcopy(
                    self.runs["staging" if workflow == p.STAGING_WORKFLOW else "package"]
                )
                run["display_title"] = (
                    "ota-"
                    + ("staging" if workflow == p.STAGING_WORKFLOW else "package")
                    + "-"
                    + data["inputs"]["automation_key"]
                )
                self.run_list[workflow].append(run)
                self.runs["staging" if workflow == p.STAGING_WORKFLOW else "package"] = run
                assert data["return_run_details"] is True
                return httpx.Response(200, json={"workflow_run_id": run["id"]})
            if self.failure == "listing":
                return httpx.Response(503)
            return httpx.Response(
                200,
                json={
                    "total_count": len(self.run_list[workflow]),
                    "workflow_runs": self.run_list[workflow],
                },
            )
        if route == "/actions/runs/7000/attempts/1":
            return httpx.Response(200, json=self.ci)
        if route == "/actions/runs/7000/attempts/1/jobs":
            return httpx.Response(200, json={"total_count": len(self.jobs), "jobs": self.jobs})
        for role, run in self.runs.items():
            if route in (
                f"/actions/runs/{run['id']}",
                f"/actions/runs/{run['id']}/attempts/{run['run_attempt']}",
            ):
                return httpx.Response(200, json=run)
            if route == f"/actions/runs/{run['id']}/artifacts":
                if self.failure == "artifacts":
                    return httpx.Response(503)
                artifact = self.artifact if role == "package" else self.stage_artifact()
                artifacts = [artifact, artifact] if self.failure == "ambiguous" else [artifact]
                return httpx.Response(
                    200, json={"total_count": len(artifacts), "artifacts": artifacts}
                )
        if route.startswith("/actions/artifacts/") and route.endswith("/zip"):
            if "/4321/" in route:
                return httpx.Response(200, stream=httpx.ByteStream(self.stage_zip()))
            return httpx.Response(200, stream=httpx.ByteStream(self.archive))
        if route.startswith("/actions/artifacts/"):
            return httpx.Response(200, json=self.artifact)
        if route.startswith("/compare/"):
            return httpx.Response(
                200,
                json={
                    "status": "identical",
                    "total_commits": 1,
                    "files": [{"filename": n, "status": "added"} for n in self.proposal_files()],
                },
            )
        if route.startswith("/git/ref/heads/ota-release/"):
            return (
                httpx.Response(404)
                if self.branch is None
                else httpx.Response(200, json={"object": {"sha": self.branch}})
            )
        if route.startswith("/git/commits/"):
            return httpx.Response(200, json=self.commits[route.rsplit("/", 1)[1]])
        if route.startswith("/git/trees/"):
            return httpx.Response(200, json={"sha": "base-tree"})
        if route.startswith("/contents/"):
            name = route.removeprefix("/contents/")
            files = self.proposal_files()
            if name not in files or self.branch is None:
                return httpx.Response(404)
            import base64

            return httpx.Response(
                200,
                json={
                    "encoding": "base64",
                    "content": base64.b64encode(files[name].encode()).decode(),
                },
            )
        if route == "/git/trees":
            self.trees["new-tree"] = data
            return httpx.Response(201, json={"sha": "new-tree"})
        if route == "/git/commits":
            self.commits["new-commit"] = {
                **data,
                "sha": "new-commit",
                "parents": [{"sha": n} for n in data["parents"]],
            }
            return httpx.Response(201, json={"sha": "new-commit"})
        if route == "/git/refs":
            self.branch = data["sha"]
            return httpx.Response(201, json={"object": {"sha": self.branch}})
        if route == "/pulls":
            if req.method == "GET":
                return httpx.Response(200, json=self.prs)
            assert data["draft"] is True and data["base"] == "main"
            pr = {
                "html_url": "https://github.com/AlexeyKravtsov95/TalentForge/pull/999",
                "state": "open",
                "head": {
                    "ref": data["head"],
                    "sha": self.branch,
                    "repo": {"full_name": p.REPOSITORY},
                },
                "base": {"ref": "main"},
                "number": 999,
            }
            self.prs.append(pr)
            return httpx.Response(201, json=pr)
        if route.startswith("/actions/runs/"):
            return httpx.Response(404)
        raise AssertionError((req.method, route, data))

    def proposal_files(self):
        return {
            entry["path"]: entry["content"]
            for entry in self.trees.get("new-tree", {}).get("tree", [])
        }

    def stage_zip(self):
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, target in {
                "report": "staging-report.json",
                "manifest": f"releases/{self.request.release_id}/release.json",
                "update": "client/theCandidateReplacesTheBaseOverHttps.json",
            }.items():
                archive.writestr(target, self.package.attachment(STAGING_FILES[name]))
        return out.getvalue()

    def stage_artifact(self):
        run = self.runs["staging"]
        return {
            **self.artifact,
            "id": 4321,
            "name": f"ota-staging-verify-{run['id']}-{run['run_attempt']}",
            "digest": sha256_ref(self.stage_zip()),
            "workflow_run": {
                **self.artifact["workflow_run"],
                "id": run["id"],
                "head_sha": run["head_sha"],
            },
        }


def run_prepare(fake, tmp_path):
    with httpx.Client(transport=httpx.MockTransport(fake.handler)) as client:
        return p.prepare_request(
            client,
            token="github-test",
            ci_run_id=7000,
            ci_attempt=1,
            repo=ROOT,
            keyring=KEYRING,
            now=NOW,
            destination=tmp_path,
            sleep=lambda _: None,
        )


def test_repeated_green_ci_reuses_exact_package_and_one_pr(prepared_inputs, tmp_path):
    fake = FakeGitHub(prepared_inputs)
    one = run_prepare(fake, tmp_path / "one")
    with httpx.Client(transport=httpx.MockTransport(fake.handler)) as client:
        url = p.ensure_release_pr(client, proposal=one, app_token="app-test")
    two = run_prepare(fake, tmp_path / "two")
    with httpx.Client(transport=httpx.MockTransport(fake.handler)) as client:
        assert p.ensure_release_pr(client, proposal=two, app_token="app-test") == url
    assert len(fake.dispatched) == 2
    assert len(fake.prs) == 1
    assert one.request == two.request
    assert one.request.created_at == "2026-10-17T23:00:00Z"
    assert one.request.manifest_sha256 == prepared_inputs[0].manifest_sha256
    assert "<OWNER:" in one.approval_template
    assert "Live-build evidence: <OWNER:" in one.approval_template


@pytest.mark.parametrize(
    "fault",
    [
        "listing",
        "artifacts",
        "ambiguous",
        "expired",
        "attempt",
        "head",
        "ci",
        "cached",
        "failed",
        "base",
    ],
)
def test_unproven_inputs_cannot_create_release_pr(prepared_inputs, tmp_path, fault):
    fake = FakeGitHub(prepared_inputs)
    if fault in ("listing", "artifacts", "ambiguous"):
        fake.failure = fault
    elif fault == "expired":
        fake.artifact["expired"] = True
    elif fault == "attempt":
        fake.runs["staging"]["run_attempt"] = 2
    elif fault == "head":
        fake.ci["head_repository"] = {"full_name": "attacker/TalentForge"}
    elif fault == "ci":
        fake.jobs[0]["conclusion"] = "skipped"
    elif fault == "failed":
        fake.runs["staging"]["conclusion"] = "failure"
    elif fault == "base":
        from tests.unit.ota.test_automation_public_verify import catalog_of, resign

        cat = catalog_of(fake.origin.objects)
        entry = cat.releases[0].model_copy(update={"manifest_sha256": "sha256:" + "0" * 64})
        resign(fake.origin.objects, cat.model_copy(update={"releases": [entry]}))
    else:
        report = json.loads(fake.package.attachment(STAGING_FILES["report"]))
        report["facts"]["client"]["notExecuted"] = "FROM-CACHE"
        (fake.package.root / STAGING_FILES["report"]).write_text(json.dumps(report))
    with pytest.raises((p.PreparationBlocked, p.RequestRejected)):
        run_prepare(fake, tmp_path / "out")
    assert fake.prs == [] and fake.branch is None


def test_manual_commit_on_release_branch_is_never_overwritten(prepared_inputs):
    request, _, _, _, package = prepared_inputs
    proposal = p.make_proposal(
        package,
        staging_run=request.staging_run,
        package_run=request.package_run,
        artifact=request.artifact,
        base_manifest_sha256=request.base_manifest_sha256,
    )
    fake = FakeGitHub(prepared_inputs)
    with httpx.Client(transport=httpx.MockTransport(fake.handler)) as client:
        p.ensure_release_pr(client, proposal=proposal, app_token="app-test")
        fake.branch = "manual"
        fake.commits["manual"] = {"message": "owner change", "parents": [{"sha": "new-commit"}]}
        before = list(fake.posts)
        with pytest.raises(p.PreparationBlocked, match="BRANCH"):
            p.ensure_release_pr(client, proposal=proposal, app_token="app-test")
        assert fake.posts == before and fake.branch == "manual"


def test_missing_app_configuration_is_explicit(monkeypatch, capsys):
    monkeypatch.delenv("OTA_APP_CLIENT_ID", raising=False)
    monkeypatch.delenv("OTA_APP_PRIVATE_KEY", raising=False)
    assert p.main(["check-app"]) == 3
    output = capsys.readouterr().out
    assert "BLOCKED" in output and "OTA_APP_CLIENT_ID" in output and "OTA_APP_PRIVATE_KEY" in output


@pytest.mark.parametrize(
    "field,value",
    [
        ("path", p.CI_WORKFLOW),
        ("event", "pull_request"),
        ("head_sha", "f" * 40),
        ("head_branch", "feature"),
        ("repository", {"full_name": "foreign/TalentForge"}),
        ("head_repository", {"full_name": "foreign/TalentForge"}),
    ],
)
def test_orchestrator_cannot_be_triggered_from_foreign_workflow_or_ref(
    prepared_inputs, field, value
):
    run = {**prepared_inputs[1]["package"], "path": p.PREPARE_WORKFLOW, "event": "workflow_run"}
    run[field] = value
    with pytest.raises((p.PreparationBlocked, p.RequestRejected)):
        p.verify_invocation(run, event="workflow_run", head_sha=COMMIT)


def test_truncated_listing_is_not_permission_to_dispatch_again():
    def handler(request):
        return httpx.Response(200, json={"total_count": 2, "workflow_runs": [{"id": 1}]})

    with (
        httpx.Client(transport=httpx.MockTransport(handler)) as client,
        pytest.raises(p.PreparationBlocked, match="LISTING"),
    ):
        p.GitHub(client, "test").pages(
            "/actions/workflows/ota-release-package.yml/runs", "workflow_runs"
        )


def test_current_70532_release_is_never_proposed_again(prepared_inputs):
    from tests.unit.ota.test_automation_public_verify import catalog_of

    catalog = catalog_of(publication())
    entry = catalog.releases[0].model_copy(
        update={"client_build": "1.60.1.70532", "release_id": "1.60.1.70532-r1"}
    )
    catalog = catalog.model_copy(
        update={"active_release_id": entry.release_id, "releases": [entry]}
    )
    source = p.git(ROOT, "rev-parse", "origin/main").decode().strip()
    assert p.select_candidate(ROOT, source, catalog) is None


def test_base_change_after_an_existing_draft_requires_new_review(prepared_inputs, tmp_path):
    from talent_data.ota.catalog import catalog_entry
    from talent_data.ota.release import serialize_manifest
    from talent_data.ota.store import blob_key
    from tests.unit.ota.test_automation_public_verify import catalog_of, resign
    from tests.unit.ota.test_ota_preflight import real_release

    fake = FakeGitHub(prepared_inputs)
    proposal = run_prepare(fake, tmp_path / "one")
    with httpx.Client(transport=httpx.MockTransport(fake.handler)) as client:
        p.ensure_release_pr(client, proposal=proposal, app_token="app-test")
    catalog = catalog_of(fake.origin.objects)
    base = real_release().manifest.model_copy(update={"release_id": "1.60.1.70374-r2"})
    raw = serialize_manifest(base)
    fake.origin.objects[blob_key(sha256_ref(raw))] = raw
    entry = catalog_entry(raw, base, published_at=NOW)
    resign(
        fake.origin.objects,
        catalog.model_copy(
            update={
                "sequence": 2,
                "active_release_id": entry.release_id,
                "releases": [*catalog.releases, entry],
            }
        ),
    )
    before = list(fake.posts)
    with pytest.raises(p.PreparationBlocked, match="BASE_CHANGED"):
        run_prepare(fake, tmp_path / "two")
    assert fake.posts == before and len(fake.prs) == 1


def test_retry_before_pr_creation_discovers_existing_exact_package(prepared_inputs, tmp_path):
    fake = FakeGitHub(prepared_inputs)
    first = run_prepare(fake, tmp_path / "one")
    # Crash before the App proposal job: the durable workflow artifact is enough to resume.
    again = run_prepare(fake, tmp_path / "two")
    assert first.request == again.request
    assert len(fake.dispatched) == 2 and fake.prs == []


def test_expired_frozen_package_is_blocked_without_rebuilding(prepared_inputs, tmp_path):
    fake = FakeGitHub(prepared_inputs)
    first = run_prepare(fake, tmp_path / "one")
    with httpx.Client(transport=httpx.MockTransport(fake.handler)) as client:
        p.ensure_release_pr(client, proposal=first, app_token="app-test")
    fake.artifact["expired"] = True
    before = list(fake.posts)
    with pytest.raises((p.PreparationBlocked, p.RequestRejected)):
        run_prepare(fake, tmp_path / "two")
    assert fake.posts == before


def test_ambiguous_prior_package_runs_are_not_silently_chosen(prepared_inputs, tmp_path):
    fake = FakeGitHub(prepared_inputs)
    run_prepare(fake, tmp_path / "one")
    fake.run_list[p.PACKAGE_WORKFLOW].append(copy.deepcopy(fake.run_list[p.PACKAGE_WORKFLOW][0]))
    before = list(fake.posts)
    with pytest.raises(p.PreparationBlocked, match="RUN_AMBIGUOUS"):
        run_prepare(fake, tmp_path / "two")
    assert fake.posts == before


def test_cli_proposes_from_verified_ci_and_refetches_artifact(
    prepared_inputs, tmp_path, monkeypatch
):
    fake = FakeGitHub(prepared_inputs)
    proposal = run_prepare(fake, tmp_path / "one")
    request = tmp_path / "request.json"
    request.write_text(p.request_text(proposal))
    event = tmp_path / "event.json"
    event.write_text(
        json.dumps({"workflow_run": {"id": 7000, "run_attempt": 1, "head_sha": COMMIT}})
    )
    own_head = p.git(ROOT, "rev-parse", "origin/main").decode().strip()
    read_git = p.git
    monkeypatch.setattr(
        p,
        "git",
        lambda repo, *args, **kw: (
            own_head.encode() if args == ("rev-parse", "HEAD") else read_git(repo, *args, **kw)
        ),
    )
    own = {
        **fake.runs["package"],
        "id": 6000,
        "path": p.PREPARE_WORKFLOW,
        "head_sha": own_head,
        "event": "workflow_run",
        "status": "in_progress",
    }
    original = fake.handler

    def handler(req):
        if req.url.path.endswith("/actions/runs/6000/attempts/1"):
            return httpx.Response(200, json=own)
        return original(req)

    client_type = httpx.Client
    monkeypatch.setattr(
        p.httpx, "Client", lambda **kw: client_type(transport=httpx.MockTransport(handler), **kw)
    )
    monkeypatch.setattr(p, "production_keyring", lambda path: KEYRING)
    for key, value in {
        "GITHUB_REPOSITORY": p.REPOSITORY,
        "GITHUB_RUN_ID": "6000",
        "GITHUB_RUN_ATTEMPT": "1",
        "GITHUB_SHA": own_head,
        "GITHUB_EVENT_NAME": "workflow_run",
        "GITHUB_EVENT_PATH": str(event),
        "GH_TOKEN": "read-test",
        "OTA_APP_TOKEN": "app-test",
    }.items():
        monkeypatch.setenv(key, value)
    assert (
        p.main(
            [
                "propose-request",
                "--request",
                str(request),
                "--ci-run-id",
                "7000",
                "--ci-attempt",
                "1",
            ]
        )
        == 0
    )
    assert len(fake.prs) == 1


@pytest.mark.parametrize("fault", ["missing-nullable", "malformed-check-id"])
def test_staging_report_must_prove_client_execution_without_type_fallbacks(prepared_inputs, fault):
    package = prepared_inputs[-1]
    report = json.loads(package.attachment(STAGING_FILES["report"]))
    if fault == "missing-nullable":
        del report["facts"]["client"]["notExecuted"]
    else:
        report["checks"][0]["id"] = []
    with pytest.raises(p.PreparationBlocked, match="STAGING_EXECUTION"):
        p.verify_execution(json.dumps(report).encode(), package.attachment(STAGING_FILES["update"]))
