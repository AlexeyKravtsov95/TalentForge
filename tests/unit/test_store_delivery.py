"""The store delivery orchestration (issue #90), offline.

``scripts/store_delivery.py`` makes every decision of ``.github/workflows/store-delivery.yml``.
Nothing here talks to a store: GitHub, Google Play and App Store Connect are fakes behind
``httpx.MockTransport``, so these tests are a dry run of the orchestration and never evidence
that a real upload works. They pin the refusals the owner relies on: the revision gate, CI of
the exact commit, missing configuration, version conflicts (a used number is never reused),
re-runs that find a build already delivered, partial success of one platform, and the
per-platform summary that never hides a mismatch behind a success.
"""

from __future__ import annotations

import base64
import hashlib
import importlib.util
import json
import subprocess
import sys
import zipfile
from collections.abc import Callable
from pathlib import Path
from types import ModuleType
from typing import Any

import httpx
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

ROOT = Path(__file__).resolve().parents[2]


def load() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "store_delivery", ROOT / "scripts" / "store_delivery.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


d = load()

SOURCE = "a" * 40
OTHER = "b" * 40
AAB_SHA = "1" * 64
OTHER_SHA = "2" * 64
IPA_SHA = "3" * 64
RUN_ID = 4242


def gate(**overrides: Any) -> dict[str, Any]:
    value: dict[str, Any] = {
        "sourceSha": SOURCE,
        "workflowSha": SOURCE,
        "revisionInput": "",
        "revisionKind": "main-head",
        "platforms": ["android", "ios"],
        "mode": "deliver",
        "android": {"versionName": "1.0.0", "versionCode": 3},
        "ios": {"version": "1.0.0", "build": 4},
    }
    value.update(overrides)
    return value


def refused(code: str, call: Callable[[], object]) -> str:
    with pytest.raises(d.DeliveryError) as error:
        call()
    assert error.value.code == code, error.value.message
    return str(error.value.message)


def record(**overrides: Any) -> Any:
    value: dict[str, Any] = {
        "platform": "android",
        "version": "1.0.0",
        "build": 3,
        "phase": "delivered",
        "source_sha": SOURCE,
        "artifact_sha256": AAB_SHA,
        "run_id": RUN_ID,
    }
    value.update(overrides)
    return d.Record(**value)


# --------------------------------------------------------------------------- the revision gate


def git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *args], check=True, capture_output=True, text=True
    ).stdout.strip()


GRADLE = """android {
    defaultConfig {
        applicationId = "io.github.alexeykravtsov95.talentforge"
        versionCode = 3
        versionName = "1.0.0"
    }
}
abstract class Stage { abstract val versionCode: Property<Int> }
"""
PBXPROJ = """
    CURRENT_PROJECT_VERSION = 4;
    MARKETING_VERSION = 1.0.0;
    CURRENT_PROJECT_VERSION = 4;
    MARKETING_VERSION = 1.0.0;
"""


@pytest.fixture
def repo(tmp_path: Path) -> dict[str, Any]:
    """main: base -> head (origin/main = head); a side commit off base is not on main."""
    root = tmp_path / "repo"
    root.mkdir()
    git(root, "init", "-q", "-b", "main")
    git(root, "config", "user.email", "test@example.invalid")
    git(root, "config", "user.name", "test")
    git(root, "config", "commit.gpgsign", "false")
    git(root, "config", "tag.gpgsign", "false")
    (root / "mobile/composeApp").mkdir(parents=True)
    (root / "mobile/iosApp/iosApp.xcodeproj").mkdir(parents=True)
    (root / d.ANDROID_GRADLE_FILE).write_text(GRADLE)
    (root / d.IOS_PROJECT_FILE).write_text(PBXPROJ)
    git(root, "add", ".")
    git(root, "commit", "-qm", "base")
    base = git(root, "rev-parse", "HEAD")
    git(root, "tag", "v1.0.0")
    (root / "README").write_text("x")
    git(root, "add", ".")
    git(root, "commit", "-qm", "head")
    head = git(root, "rev-parse", "HEAD")
    git(root, "update-ref", "refs/remotes/origin/main", head)
    git(root, "checkout", "-q", "-b", "side", base)
    (root / "side").write_text("x")
    git(root, "add", ".")
    git(root, "commit", "-qm", "side")
    side = git(root, "rev-parse", "HEAD")
    git(root, "tag", "v9.9.9")
    git(root, "checkout", "-q", "main")
    return {"root": root, "base": base, "head": head, "side": side}


def ci_run(sha: str, workflow: str, **overrides: Any) -> dict[str, Any]:
    value: dict[str, Any] = {
        "id": 1,
        "run_number": 7,
        "html_url": f"https://github.com/o/r/actions/runs/{Path(workflow).stem}",
        "head_sha": sha,
        "path": workflow,
        "head_branch": "main",
        "event": "push",
        "status": "completed",
        "conclusion": "success",
        "repository": {"id": 1},
        "head_repository": {"id": 1},
    }
    value.update(overrides)
    return value


class FakeGitHub:
    """Workflow runs and run artifacts, as the GitHub REST API lists them."""

    def __init__(self) -> None:
        self.runs: dict[str, list[dict[str, Any]]] = {}
        self.artifacts: dict[int, list[dict[str, Any]]] = {}

    def green(self, sha: str) -> FakeGitHub:
        for workflow in d.REQUIRED_CI_WORKFLOWS:
            self.runs.setdefault(Path(workflow).name, []).append(ci_run(sha, workflow))
        return self

    def handler(self, request: httpx.Request) -> httpx.Response:
        assert request.method == "GET"  # the gate and the record lookup only read
        assert request.headers["Authorization"] == "Bearer gh-token"
        parts = request.url.path.split("/")
        if "workflows" in parts:
            runs = self.runs.get(parts[parts.index("workflows") + 1], [])
            sha = request.url.params.get("head_sha")
            return httpx.Response(
                200, json={"workflow_runs": [r for r in runs if not sha or r["head_sha"] == sha]}
            )
        if "runs" in parts and parts[-1] == "artifacts":
            run_id = int(parts[parts.index("runs") + 1])
            return httpx.Response(200, json={"artifacts": self.artifacts.get(run_id, [])})
        raise AssertionError(request.url)

    def api(self) -> Any:
        client = httpx.Client(transport=httpx.MockTransport(self.handler))
        return d.GitHubApi("o/r", "gh-token", client)


def run_gate(repo: dict[str, Any], github: FakeGitHub, revision: str = "", **env: str) -> Any:
    return d.run_gate(
        repo=repo["root"],
        revision=revision,
        platforms="both",
        mode="deliver",
        environ={"GITHUB_REF": "refs/heads/main", "GITHUB_SHA": repo["head"], **env},
        api=github.api(),
    )


def test_the_gate_takes_main_s_head_and_records_its_full_sha_and_versions(
    repo: dict[str, Any],
) -> None:
    result = run_gate(repo, FakeGitHub().green(repo["head"]))
    assert result["sourceSha"] == repo["head"] and result["revisionKind"] == "main-head"
    assert result["android"] == {"versionName": "1.0.0", "versionCode": 3}
    assert result["ios"] == {"version": "1.0.0", "build": 4}
    assert set(result["ci"]) == set(d.REQUIRED_CI_WORKFLOWS)


def test_the_gate_accepts_a_full_sha_and_a_release_tag_on_main(repo: dict[str, Any]) -> None:
    github = FakeGitHub().green(repo["base"])
    assert run_gate(repo, github, repo["base"])["sourceSha"] == repo["base"]
    tagged = run_gate(repo, github, "v1.0.0")
    assert (tagged["sourceSha"], tagged["revisionKind"]) == (repo["base"], "tag")


@pytest.mark.parametrize(
    "revision", ["main", "side", "abc1234", "refs/heads/main", "v1.0", "1.0.0", "HEAD~1"]
)
def test_the_gate_refuses_short_shas_branches_and_other_names(
    repo: dict[str, Any], revision: str
) -> None:
    message = refused("GATE", lambda: run_gate(repo, FakeGitHub().green(repo["head"]), revision))
    assert "not allowed" in message


def test_the_gate_refuses_a_commit_or_tag_that_is_not_on_main(repo: dict[str, Any]) -> None:
    github = FakeGitHub().green(repo["side"])
    assert "not on main" in refused("GATE", lambda: run_gate(repo, github, repo["side"]))
    assert "not on main" in refused("GATE", lambda: run_gate(repo, github, "v9.9.9"))
    assert "does not exist" in refused("GATE", lambda: run_gate(repo, github, "v2.0.0"))
    assert "not in the repository" in refused("GATE", lambda: run_gate(repo, github, "c" * 40))


def test_the_gate_refuses_a_workflow_started_from_another_ref(repo: dict[str, Any]) -> None:
    github = FakeGitHub().green(repo["head"])
    message = refused("GATE", lambda: run_gate(repo, github, GITHUB_REF="refs/heads/feature"))
    assert "must be started from main" in message


@pytest.mark.parametrize(
    "bad",
    [
        {"conclusion": "failure"},
        {"status": "in_progress", "conclusion": None},
        {"head_branch": "feature"},
        {"event": "pull_request"},
        {"head_repository": {"id": 99}},  # a fork
        {"path": ".github/workflows/other.yml"},
    ],
    ids=["failed", "running", "branch", "pull-request", "fork", "other-workflow"],
)
def test_ci_counts_only_a_successful_main_run_of_the_exact_commit(
    repo: dict[str, Any], bad: dict[str, Any]
) -> None:
    github = FakeGitHub().green(repo["head"])
    github.runs["mobile-ci.yml"] = [ci_run(repo["head"], ".github/workflows/mobile-ci.yml", **bad)]
    message = refused("GATE", lambda: run_gate(repo, github))
    assert "mobile-ci.yml has no successful run for" in message


def test_ci_of_another_commit_does_not_count(repo: dict[str, Any]) -> None:
    github = FakeGitHub().green(repo["base"])  # green for base; the delivery asks for head
    assert "no runs" in refused("GATE", lambda: run_gate(repo, github))


def test_versions_must_be_unambiguous() -> None:
    assert d.android_version(GRADLE) == ("1.0.0", 3)
    assert d.ios_version(PBXPROJ) == ("1.0.0", 4)
    refused("GATE", lambda: d.android_version(GRADLE + "versionCode = 4\n"))
    refused("GATE", lambda: d.android_version(GRADLE.replace('"1.0.0"', '"1.0.0-rc"')))
    refused("GATE", lambda: d.ios_version(PBXPROJ.replace("VERSION = 4;", "VERSION = 5;", 1)))
    refused("GATE", lambda: d.ios_version(PBXPROJ.replace("1.0.0", "1.0", 1)))


def test_the_committed_project_versions_parse() -> None:
    gradle = (ROOT / d.ANDROID_GRADLE_FILE).read_text()
    pbxproj = (ROOT / d.IOS_PROJECT_FILE).read_text()
    name, code = d.android_version(gradle)
    version, build = d.ios_version(pbxproj)
    assert name == version == "1.0.0" and code >= 3 and build >= 3


# --------------------------------------------------------------------------- configuration


def test_missing_configuration_names_every_setting_and_never_a_value(
    capsys: pytest.CaptureFixture[str],
) -> None:
    secret = "super-secret-password-value"
    environ = {"ANDROID_RELEASE_KEYSTORE_PASSWORD": secret, "ANDROID_UPLOAD_KEY_ALIAS": "  "}
    code = d.main(
        [
            "require-env",
            "ANDROID_RELEASE_KEYSTORE_PASSWORD",
            "ANDROID_RELEASE_KEYSTORE_BASE64",
            "ANDROID_UPLOAD_KEY_ALIAS",
        ],
        environ,
    )
    out = capsys.readouterr().out
    assert code == d.EXIT_REFUSED
    assert "Delivery refused (CONFIG)" in out
    assert "ANDROID_RELEASE_KEYSTORE_BASE64, ANDROID_UPLOAD_KEY_ALIAS" in out
    assert "nothing is built unsigned, with a debug key or with OTA off" in out
    assert secret not in out


def test_present_configuration_passes(capsys: pytest.CaptureFixture[str]) -> None:
    assert d.main(["require-env", "A", "B"], {"A": "value-a", "B": "value-b"}) == d.EXIT_OK
    assert "value-" not in capsys.readouterr().out


def test_a_store_command_without_credentials_is_refused(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    (tmp_path / "gate.json").write_text(json.dumps(gate()))
    for command in ("play-check", "asc-check"):
        code = d.main(
            [command, "--gate", str(tmp_path / "gate.json"), "--status", str(tmp_path / "s")],
            {"PLAY_TRACK": "alpha", "GITHUB_REPOSITORY": "o/r", "GH_TOKEN": "t"},
        )
        assert code == d.EXIT_REFUSED
    out = capsys.readouterr().out
    assert "PLAY_SERVICE_ACCOUNT_JSON" in out
    assert "ASC_API_KEY_ID, ASC_API_ISSUER_ID, ASC_API_PRIVATE_KEY" in out


def service_account_json(key: rsa.RSAPrivateKey, **overrides: Any) -> str:
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()
    value = {
        "type": "service_account",
        "client_email": "delivery@talentforge.iam.gserviceaccount.com",
        "private_key": pem,
        "token_uri": d.GOOGLE_TOKEN_URI,
    }
    value.update(overrides)
    return json.dumps(value)


@pytest.fixture(scope="module")
def rsa_key() -> rsa.RSAPrivateKey:
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def test_a_malformed_service_account_is_refused_without_echoing_it(
    rsa_key: rsa.RSAPrivateKey,
) -> None:
    for text in (
        "not json",
        json.dumps({"type": "authorized_user"}),
        service_account_json(rsa_key, client_email="someone@example.com"),
        service_account_json(rsa_key, token_uri="https://attacker.example/token"),
        service_account_json(rsa_key, private_key="-----BEGIN PRIVATE KEY-----\nAAAA\n"),
    ):
        message = refused("CONFIG", lambda text=text: d.parse_service_account(text))
        assert "BEGIN" not in message and "attacker" not in message


@pytest.mark.parametrize("track", ["production", "beta", "a b", ""])
def test_production_and_open_testing_tracks_are_refused(track: str) -> None:
    refused("CONFIG", lambda: d.check_play_track(track))


# --------------------------------------------------------------------------- delivery records


def test_records_round_trip_through_their_artifact_name() -> None:
    value = record()
    parsed = d.parse_record_name(value.name)
    assert parsed is not None and parsed.name == value.name and parsed.run_id is None
    assert d.parse_record_name("tf-delivery-android-1.0.0-3-delivered-short-sha") is None
    assert d.parse_record_name(value.name + "-x") is None


def test_only_records_of_this_workflow_on_main_in_this_repository_count() -> None:
    github = FakeGitHub()
    good = ci_run(SOURCE, d.WORKFLOW_FILE, id=1, event="workflow_dispatch")
    fork = ci_run(SOURCE, d.WORKFLOW_FILE, id=2, event="workflow_dispatch")
    fork["head_repository"] = {"id": 99}
    branch = ci_run(SOURCE, d.WORKFLOW_FILE, id=3, event="workflow_dispatch", head_branch="x")
    other = ci_run(SOURCE, ".github/workflows/ci.yml", id=4, event="workflow_dispatch")
    github.runs["store-delivery.yml"] = [good, fork, branch, other]
    for run_id in (1, 2, 3, 4):
        github.artifacts[run_id] = [{"name": record().name, "expired": False}]
    github.artifacts[1] += [
        {"name": record(build=9).name, "expired": True},
        {"name": "android-release", "expired": False},
    ]
    assert d.delivery_records(github.api()) == [record(run_id=1)]


# --------------------------------------------------------------------------- Google Play


def play_state(
    bundles: dict[int, str] | None = None,
    tracks: dict[str, list[dict[str, Any]]] | None = None,
    apks: dict[int, str] | None = None,
) -> Any:
    return d.PlayState(
        bundles=bundles if bundles is not None else {1: "0" * 64, 2: "9" * 64},
        apks=apks or {},
        tracks=tracks if tracks is not None else {"alpha": [{"versionCodes": ["2"]}]},
    )


def decide(state: Any, *, records: tuple[Any, ...] = (), artifact: str | None = None) -> Any:
    return d.decide_play(state, 3, source_sha=SOURCE, records=records, artifact_sha256=artifact)


def test_a_new_version_code_is_uploaded() -> None:
    assert decide(play_state()).kind == "UPLOAD"


def test_a_lower_version_code_than_the_store_s_is_a_conflict() -> None:
    message = refused("CONFLICT", lambda: decide(play_state({4: "4" * 64})))
    assert "versionCode 4, higher than 3" in message


def test_a_version_code_already_in_play_without_a_record_is_a_conflict() -> None:
    # e.g. a code uploaded by hand: never uploaded again, never reported as delivered
    state = play_state({3: AAB_SHA})
    assert "no run of this workflow delivered it" in refused("CONFLICT", lambda: decide(state))
    for other in (record(source_sha=OTHER), record(artifact_sha256=OTHER_SHA), record(build=2)):
        refused("CONFLICT", lambda other=other: decide(state, records=(other,)))
    refused("CONFLICT", lambda: decide(play_state({}, apks={3: AAB_SHA})))


def test_a_re_run_finds_the_bundle_an_earlier_run_delivered() -> None:
    state = play_state({3: AAB_SHA}, tracks={"alpha": [{"versionCodes": ["3"]}]})
    decision = decide(state, records=(record(),))
    assert decision.kind == "ALREADY_DELIVERED"
    assert (decision.delivered_sha256, decision.record_run_id) == (AAB_SHA, RUN_ID)
    assert decision.tracks == ["alpha"]
    assert (
        d.decide_play(state, 3, source_sha=SOURCE, records=(record(),), track="alpha").kind
        == "ALREADY_DELIVERED"
    )


def test_a_delivered_code_off_the_configured_track_is_a_mismatch() -> None:
    # the release was removed from the closed track, or PLAY_TRACK names another track now
    state = play_state({3: AAB_SHA}, tracks={"alpha": [{"versionCodes": ["2"]}], "qa": []})
    for options in ({"records": (record(),)}, {"records": (), "artifact_sha256": AAB_SHA}):
        message = refused(
            "MISMATCH",
            lambda options=options: d.decide_play(
                state, 3, source_sha=SOURCE, track="alpha", **options
            ),
        )
        assert "not on PLAY_TRACK='alpha'" in message


def test_right_before_the_upload_only_the_exact_bundle_counts() -> None:
    state = play_state({3: AAB_SHA})
    assert decide(state, artifact=AAB_SHA).kind == "ALREADY_DELIVERED"
    message = refused("CONFLICT", lambda: decide(state, artifact=OTHER_SHA, records=(record(),)))
    assert f"this run's verified bundle is {OTHER_SHA}" in message


class FakePlay:
    """The Google Play Developer API v3 edits endpoints, in memory."""

    def __init__(self, key: rsa.RSAPrivateKey, *, bundles: dict[int, str] | None = None) -> None:
        self.public_key = key.public_key()
        self.bundles = dict(bundles or {1: "0" * 64, 2: "9" * 64})
        self.tracks: dict[str, list[dict[str, Any]]] = {
            "alpha": [{"versionCodes": ["2"], "status": "completed"}],
            "production": [],
        }
        self.edits: dict[str, dict[str, Any]] = {}
        self.calls: list[str] = []
        self.upload_sha_override: str | None = None
        self.commit_error: str | None = None
        self.counter = 0

    def token(self, request: httpx.Request) -> httpx.Response:
        form = dict(x.split("=", 1) for x in request.content.decode().split("&"))
        header, payload, signature = form["assertion"].split(".")
        self.public_key.verify(
            base64.urlsafe_b64decode(signature + "=="),
            f"{header}.{payload}".encode(),
            padding.PKCS1v15(),
            hashes.SHA256(),
        )
        claims = json.loads(base64.urlsafe_b64decode(payload + "=="))
        assert claims["scope"] == d.PLAY_SCOPE and claims["aud"] == d.GOOGLE_TOKEN_URI
        return httpx.Response(200, json={"access_token": "play-token", "expires_in": 3600})

    def handler(self, request: httpx.Request) -> httpx.Response:
        if str(request.url) == d.GOOGLE_TOKEN_URI:
            return self.token(request)
        assert request.headers["Authorization"] == "Bearer play-token"
        path = request.url.path.split(f"/applications/{d.APPLICATION_ID}/", 1)[1]
        self.calls.append(f"{request.method} {path}")
        if request.method == "POST" and path == "edits":
            self.counter += 1
            edit = f"e{self.counter}"
            self.edits[edit] = json.loads(
                json.dumps({"bundles": self.bundles, "tracks": self.tracks})
            )
            return httpx.Response(200, json={"id": edit})
        edit_id, _, rest = path.removeprefix("edits/").partition("/")
        if request.method == "DELETE":
            if self.edits.pop(edit_id, None) is None:  # e.g. after a refused commit
                return httpx.Response(404, json={"error": {"message": "Edit not found"}})
            return httpx.Response(204)
        if edit_id.endswith(":commit"):
            edit = self.edits.pop(edit_id.removesuffix(":commit"))
            if self.commit_error:
                return httpx.Response(400, json={"error": {"message": self.commit_error}})
            self.bundles = {int(c): s for c, s in edit["bundles"].items()}
            self.tracks = edit["tracks"]
            return httpx.Response(200, json={})
        return self.edit_call(request, edit_id, rest)

    def edit_call(self, request: httpx.Request, edit_id: str, rest: str) -> httpx.Response:
        edit = self.edits[edit_id]
        if rest == "bundles" and request.method == "GET":
            bundles = [{"versionCode": int(c), "sha256": s} for c, s in edit["bundles"].items()]
            return httpx.Response(200, json={"bundles": bundles})
        if rest == "apks":
            return httpx.Response(200, json={})
        if rest == "tracks":
            tracks = [{"track": t, "releases": r} for t, r in edit["tracks"].items()]
            return httpx.Response(200, json={"tracks": tracks})
        if rest == "bundles" and request.method == "POST":
            assert request.url.params["uploadType"] == "resumable"
            assert int(request.headers["X-Upload-Content-Length"]) > 0
            location = f"{d.PLAY_UPLOAD_API}{d.APPLICATION_ID}/edits/{edit_id}/bundles?upload_id=1"
            return httpx.Response(200, headers={"Location": location})
        if request.method == "PUT" and rest == "bundles":
            sha = self.upload_sha_override or hashlib.sha256(request.content).hexdigest()
            edit["bundles"]["3"] = sha
            return httpx.Response(200, json={"versionCode": 3, "sha256": sha})
        if request.method == "PUT" and rest.startswith("tracks/"):
            body = json.loads(request.content)
            edit["tracks"][rest.removeprefix("tracks/")] = body["releases"]
            return httpx.Response(200, json=body)
        raise AssertionError(f"{request.method} {rest}")

    def client(self, key: rsa.RSAPrivateKey) -> Any:
        http = httpx.Client(transport=httpx.MockTransport(self.handler))
        account = d.parse_service_account(service_account_json(key))
        return d.GooglePlay(account, http, sleep=lambda _: None)


def bundle_file(tmp_path: Path, content: bytes = b"aab-bytes") -> Path:
    path = tmp_path / "TalentForge-1.0.0-3.aab"
    path.write_bytes(content)
    return path


def deliver(play: Any, aab: Path, **overrides: Any) -> Any:
    options: dict[str, Any] = {
        "gate": gate(),
        "aab": aab,
        "track": "alpha",
        "release_status": "completed",
        "changes_not_sent_for_review": False,
    }
    options.update(overrides)
    return d.play_deliver(play, **options)


def test_the_bundle_is_uploaded_released_on_the_closed_track_and_read_back(
    tmp_path: Path, rsa_key: rsa.RSAPrivateKey
) -> None:
    fake = FakePlay(rsa_key)
    aab = bundle_file(tmp_path)
    result = deliver(fake.client(rsa_key), aab)
    assert result["status"] == "DELIVERED"
    assert fake.bundles[3] == d.file_sha256(aab) == result["deliveredSha256"]
    assert fake.tracks["alpha"] == [
        {"name": f"1.0.0 (3) {SOURCE[:12]}", "versionCodes": ["3"], "status": "completed"}
    ]
    assert fake.tracks["production"] == []
    assert "POST edits/e1:commit" in fake.calls
    assert fake.edits == {}  # the read-back edit is deleted too


def test_a_re_run_after_a_successful_upload_uploads_nothing(
    tmp_path: Path, rsa_key: rsa.RSAPrivateKey
) -> None:
    aab = bundle_file(tmp_path)
    fake = FakePlay(rsa_key, bundles={1: "0" * 64, 3: d.file_sha256(aab)})
    fake.tracks["alpha"] = [{"versionCodes": ["3"], "status": "completed"}]
    assert deliver(fake.client(rsa_key), aab)["status"] == "ALREADY_DELIVERED"
    assert not any(c.startswith("PUT") or c.endswith(":commit") for c in fake.calls)
    assert fake.edits == {}


def test_another_bundle_with_the_same_code_is_never_uploaded(
    tmp_path: Path, rsa_key: rsa.RSAPrivateKey
) -> None:
    fake = FakePlay(rsa_key, bundles={3: OTHER_SHA})
    refused("CONFLICT", lambda: deliver(fake.client(rsa_key), bundle_file(tmp_path)))
    assert not any(call.startswith("PUT") for call in fake.calls)
    assert fake.edits == {}


def test_a_store_reading_of_the_upload_that_differs_is_never_committed(
    tmp_path: Path, rsa_key: rsa.RSAPrivateKey
) -> None:
    fake = FakePlay(rsa_key)
    fake.upload_sha_override = OTHER_SHA
    refused("MISMATCH", lambda: deliver(fake.client(rsa_key), bundle_file(tmp_path)))
    assert not any(call.endswith(":commit") for call in fake.calls)
    assert fake.edits == {} and 3 not in fake.bundles


def test_an_unknown_track_and_a_refused_commit_stop_the_delivery(
    tmp_path: Path, rsa_key: rsa.RSAPrivateKey
) -> None:
    fake = FakePlay(rsa_key)
    refused("CONFIG", lambda: deliver(fake.client(rsa_key), bundle_file(tmp_path), track="qa"))
    assert fake.edits == {}
    fake.commit_error = "Changes cannot be sent for review automatically."
    message = refused("STORE", lambda: deliver(fake.client(rsa_key), bundle_file(tmp_path)))
    assert "sent for review" in message and 3 not in fake.bundles
    refused(
        "CONFIG",
        lambda: deliver(fake.client(rsa_key), bundle_file(tmp_path), release_status="inProgress"),
    )


def test_the_read_only_check_deletes_its_edit(rsa_key: rsa.RSAPrivateKey) -> None:
    fake = FakePlay(rsa_key)
    decision = d.play_check(fake.client(rsa_key), gate=gate(), track="alpha", records=())
    assert decision.kind == "UPLOAD"
    assert fake.edits == {}
    assert not any(c.startswith("PUT") or c.endswith(":commit") for c in fake.calls)


# --------------------------------------------------------------------------- App Store Connect


def asc_build(number: str, version: str | None = "1.0.0", **overrides: Any) -> Any:
    value: dict[str, Any] = {
        "id": f"build-{number}-{version}",
        "number": number,
        "version": version,
        "processing_state": "VALID",
        "expired": False,
        "uses_non_exempt_encryption": False,
        "uploaded": "2026-10-26T10:00:00Z",
    }
    value.update(overrides)
    return d.AscBuild(**value)


def ios_record(**overrides: Any) -> Any:
    fields = {
        "platform": "ios",
        "build": 4,
        "phase": "upload-started",
        "artifact_sha256": IPA_SHA,
        "created_at": "2026-10-26T09:50:00Z",  # kept before the upload (ASC: 10:00:00Z)
    }
    return record(**{**fields, **overrides})


def decide_ios(builds: list[Any], **kwargs: Any) -> Any:
    options: dict[str, Any] = {"version": "1.0.0", "build": 4, "source_sha": SOURCE, "records": ()}
    options.update(kwargs)
    return d.decide_ios(builds, **options)


def test_a_new_ios_build_number_is_uploaded() -> None:
    assert decide_ios([asc_build("1"), asc_build("3")]).kind == "UPLOAD"


def test_the_uploaded_ios_build_3_is_never_uploaded_again() -> None:
    # 1.0.0 (3) is in App Store Connect (uploaded by the owner): main at build 3 is a conflict
    message = refused("CONFLICT", lambda: decide_ios([asc_build("3")], build=3))
    assert "1.0.0 (3) is already in App Store Connect" in message
    assert "never uploaded again" in message


def test_a_higher_or_foreign_ios_build_number_is_a_conflict() -> None:
    refused("CONFLICT", lambda: decide_ios([asc_build("5")]))
    refused("CONFLICT", lambda: decide_ios([asc_build("4", version="0.9.0")]))


def test_an_ios_re_run_recognises_its_own_upload_by_record() -> None:
    builds = [asc_build("4")]
    decision = decide_ios(builds, records=(ios_record(),))
    assert decision.kind == "ALREADY_UPLOADED" and decision.record_run_id == RUN_ID
    assert "exposes no file hash" in decision.detail
    same = decide_ios(builds, records=(ios_record(),), artifact_sha256=IPA_SHA)
    assert same.kind == "ALREADY_UPLOADED"
    refused(
        "CONFLICT", lambda: decide_ios(builds, records=(ios_record(),), artifact_sha256=OTHER_SHA)
    )
    refused("CONFLICT", lambda: decide_ios(builds, records=(ios_record(source_sha=OTHER),)))
    refused("CONFLICT", lambda: decide_ios(builds, records=(record(),)))  # an Android record


def test_a_build_uploaded_before_the_record_is_not_that_run_s() -> None:
    # e.g. the owner uploaded 1.0.0 (4) by hand, then a run of the same SHA failed before upload
    later = ios_record(created_at="2026-10-26T11:00:00Z")
    refused("CONFLICT", lambda: decide_ios([asc_build("4")], records=(later,)))
    refused(
        "CONFLICT", lambda: decide_ios([asc_build("4")], records=(ios_record(created_at=None),))
    )
    refused(
        "CONFLICT", lambda: decide_ios([asc_build("4", uploaded=None)], records=(ios_record(),))
    )
    offset = ios_record(created_at="2026-10-26T02:55:00-07:00")  # 09:55Z, still before 10:00Z
    assert decide_ios([asc_build("4")], records=(offset,)).kind == "ALREADY_UPLOADED"


@pytest.fixture(scope="module")
def p256_key() -> ec.EllipticCurvePrivateKey:
    return ec.generate_private_key(ec.SECP256R1())


def test_the_app_store_connect_token_is_a_valid_es256_jwt(
    p256_key: ec.EllipticCurvePrivateKey,
) -> None:
    token = d.es256_jwt(p256_key, "ABC123DEF4", {"iss": "issuer", "aud": d.ASC_AUDIENCE})
    header, payload, signature = token.split(".")
    assert json.loads(base64.urlsafe_b64decode(header + "==")) == {
        "alg": "ES256",
        "kid": "ABC123DEF4",
        "typ": "JWT",
    }
    raw = base64.urlsafe_b64decode(signature + "==")
    assert len(raw) == 64  # JOSE r||s, not DER
    der = encode_dss_signature(int.from_bytes(raw[:32]), int.from_bytes(raw[32:]))
    p256_key.public_key().verify(der, f"{header}.{payload}".encode(), ec.ECDSA(hashes.SHA256()))


def pem(key: ec.EllipticCurvePrivateKey | rsa.RSAPrivateKey) -> str:
    return key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    ).decode()


def test_the_altool_key_file_is_the_key_the_api_accepted(
    tmp_path: Path, p256_key: ec.EllipticCurvePrivateKey
) -> None:
    body = "".join(pem(p256_key).strip().splitlines()[1:-1])
    out = tmp_path / "keys" / "AuthKey_ABC123DEF4.p8"
    assert d.main(["write-asc-key", "--out", str(out)], {"ASC_API_PRIVATE_KEY": body}) == 0
    assert out.stat().st_mode & 0o777 == 0o600
    assert d.load_asc_key(out.read_text()).curve.name == "secp256r1"
    assert out.read_text().startswith("-----BEGIN PRIVATE KEY-----\n")
    assert (
        d.main(["write-asc-key", "--out", str(tmp_path / "bad.p8")], {"ASC_API_PRIVATE_KEY": "x"})
        == 1
    )
    assert not (tmp_path / "bad.p8").exists()


def test_app_store_connect_keys_are_checked_before_use(
    p256_key: ec.EllipticCurvePrivateKey, rsa_key: rsa.RSAPrivateKey
) -> None:
    text = pem(p256_key)
    body = "".join(text.strip().splitlines()[1:-1])
    assert d.load_asc_key(body).curve.name == "secp256r1"  # only the base64 body, as pasted
    assert d.load_asc_key(text.replace("\n", "\\n")).curve.name == "secp256r1"
    refused("CONFIG", lambda: d.load_asc_key(pem(rsa_key)))
    refused("CONFIG", lambda: d.load_asc_key("garbage"))
    client = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(500)))
    refused("CONFIG", lambda: d.AppStoreConnect("short", "x" * 36, p256_key, client))
    refused("CONFIG", lambda: d.AppStoreConnect("ABC123DEF4", "issuer", p256_key, client))


class FakeAsc:
    """The App Store Connect API v1 resources the workflow reads and the one it writes."""

    def __init__(self) -> None:
        self.states = ["PROCESSING", "VALID"]
        self.encryption: bool | None = False
        self.group = {"name": "Owner", "isInternalGroup": True, "hasAccessToAllBuilds": False}
        self.group_builds: list[str] = []
        self.added: list[str] = []
        self.listed = True

    def handler(self, request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"].startswith("Bearer ")
        path = request.url.path.removeprefix("/v1/")
        if path == "apps":
            attributes = {"bundleId": d.APPLICATION_ID}
            return httpx.Response(200, json={"data": [{"id": "app1", "attributes": attributes}]})
        if path == "builds":
            return self.builds()
        if path == "betaGroups":
            return httpx.Response(200, json={"data": [{"id": "g1", "attributes": self.group}]})
        if path == "betaGroups/g1/relationships/builds" and request.method == "GET":
            return httpx.Response(200, json={"data": [{"id": b} for b in self.group_builds]})
        if path == "betaGroups/g1/relationships/builds" and request.method == "POST":
            build = json.loads(request.content)["data"][0]["id"]
            self.added.append(build)
            self.group_builds.append(build)
            return httpx.Response(204)
        raise AssertionError(f"{request.method} {path}")

    def builds(self) -> httpx.Response:
        if not self.listed:
            return httpx.Response(200, json={"data": []})
        state = self.states.pop(0) if len(self.states) > 1 else self.states[0]
        attributes = {
            "version": "4",
            "processingState": state,
            "expired": False,
            "usesNonExemptEncryption": self.encryption,
            "uploadedDate": "2026-10-26T10:00:00Z",
        }
        relation = {"preReleaseVersion": {"data": {"type": "preReleaseVersions", "id": "p1"}}}
        data = [{"id": "b4", "attributes": attributes, "relationships": relation}]
        included = [{"type": "preReleaseVersions", "id": "p1", "attributes": {"version": "1.0.0"}}]
        return httpx.Response(200, json={"data": data, "included": included})


def asc_client(fake: FakeAsc, key: ec.EllipticCurvePrivateKey) -> Any:
    clock = [0.0]

    def sleep(seconds: float) -> None:
        clock[0] += seconds

    http = httpx.Client(transport=httpx.MockTransport(fake.handler))
    issuer = "00000000-0000-0000-0000-000000000000"
    return d.AppStoreConnect("ABC123DEF4", issuer, key, http, clock=lambda: clock[0], sleep=sleep)


def distribute(fake: FakeAsc, key: ec.EllipticCurvePrivateKey, timeout: float = 600) -> Any:
    return d.testflight_distribute(
        asc_client(fake, key), gate=gate(), group="Owner", timeout_seconds=timeout, poll_seconds=60
    )


def test_a_processed_build_is_given_to_the_internal_group(
    p256_key: ec.EllipticCurvePrivateKey,
) -> None:
    fake = FakeAsc()
    result = distribute(fake, p256_key)
    assert result["status"] == "DELIVERED" and fake.added == ["b4"]
    assert "added to the group" in result["detail"]
    again = distribute(fake, p256_key)  # a re-run adds nothing
    assert "already in the group" in again["detail"] and fake.added == ["b4"]


def test_a_group_with_access_to_all_builds_needs_no_assignment(
    p256_key: ec.EllipticCurvePrivateKey,
) -> None:
    fake = FakeAsc()
    fake.group["hasAccessToAllBuilds"] = True
    assert "access to all builds" in distribute(fake, p256_key)["detail"]
    assert fake.added == []


def test_testflight_refusals(p256_key: ec.EllipticCurvePrivateKey) -> None:
    fake = FakeAsc()
    fake.states = ["INVALID"]
    assert "ended INVALID" in refused("STORE", lambda: distribute(fake, p256_key))
    fake = FakeAsc()
    fake.states = ["PROCESSING"]
    assert "still PROCESSING" in refused("TIMEOUT", lambda: distribute(fake, p256_key, 120))
    fake = FakeAsc()
    fake.listed = False
    assert "not listed yet" in refused("TIMEOUT", lambda: distribute(fake, p256_key, 60))
    fake = FakeAsc()
    fake.encryption = None
    assert "export compliance" in refused("STORE", lambda: distribute(fake, p256_key))
    fake = FakeAsc()
    fake.group["isInternalGroup"] = False
    assert "not an internal" in refused("CONFIG", lambda: distribute(fake, p256_key))
    assert fake.added == []


def test_the_asc_check_reads_versions_and_numbers(p256_key: ec.EllipticCurvePrivateKey) -> None:
    asc = asc_client(FakeAsc(), p256_key)
    refused("CONFLICT", lambda: d.asc_check(asc, gate=gate(), records=()))
    assert d.asc_check(asc, gate=gate(), records=(ios_record(),)).kind == "ALREADY_UPLOADED"
    later = gate(ios={"version": "1.0.0", "build": 5})
    assert d.asc_check(asc, gate=later, records=()).kind == "UPLOAD"


# --------------------------------------------------------------------------- artifact checks

# what a real OTA ON release dex holds (checked on an unsigned assembleRelease/bundlePlay): the
# production origin and key ids, and OtaConfig's refusal prefix "tf-staging-" without any id
OTA_DEX = b"dex\n035\0https://ota.mythweld.com\0tf-prod-2026a\0tf-prod-2026b\0tf-staging-\0"
AndroidFiles = tuple[Path, dict[str, Any], Path, dict[str, Any]]


def android_files(
    tmp_path: Path, *, dex: bytes = OTA_DEX, permissions: tuple[str, ...] = (d.INTERNET_PERMISSION,)
) -> AndroidFiles:
    apk, aab = tmp_path / "TalentForge-1.0.0-3.apk", tmp_path / "TalentForge-1.0.0-3.aab"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("classes.dex", dex)
        archive.writestr("assets/x", b"")
    with zipfile.ZipFile(aab, "w") as archive:
        archive.writestr("base/dex/classes.dex", dex)
        archive.writestr("base/dex/classes2.dex", b"dex\n035\0more")
    package = {
        "applicationId": d.APPLICATION_ID,
        "versionCode": 3,
        "versionName": "1.0.0",
        "permissions": list(permissions),
    }
    apk_report = {
        "result": "PASS",
        "purpose": "release",
        "apk": {"sha256": d.file_sha256(apk)},
        "package": package,
    }
    aab_report = {
        "result": "PASS",
        "purpose": "release",
        "bundle": {"sha256": d.file_sha256(aab)},
        "package": dict(package),
    }
    return apk, apk_report, aab, aab_report


def check_android(files: AndroidFiles) -> Any:
    apk, apk_report, aab, aab_report = files
    return d.check_android_release(
        gate=gate(), apk=apk, apk_report=apk_report, aab=aab, aab_report=aab_report
    )


def test_apk_and_aab_of_one_ota_on_release_pass(tmp_path: Path) -> None:
    result = check_android(android_files(tmp_path))
    assert result["otaProduction"] is True and result["sourceSha"] == SOURCE


@pytest.mark.parametrize(
    ("change", "problem"),
    [
        (lambda f: f[1].update(result="SELFTEST-PASS"), "not PASS"),
        (lambda f: f[3]["package"].update(versionCode=2), "the source commit sets 1.0.0 (3)"),
        (lambda f: f[1]["package"].update(versionName="1.0.1"), "the source commit sets"),
        (lambda f: f[3]["bundle"].update(sha256=OTHER_SHA), "does not describe this file"),
        (lambda f: f[1]["package"].update(permissions=[]), "production OTA is off"),
    ],
    ids=["selftest", "aab-code", "apk-name", "aab-report", "ota-off-permission"],
)
def test_a_release_that_is_not_one_ota_on_release_fails(
    tmp_path: Path, change: Callable[[AndroidFiles], None], problem: str
) -> None:
    files = android_files(tmp_path)
    change(files)
    assert problem in refused("MISMATCH", lambda: check_android(files))


def test_the_dex_must_carry_production_ota_and_no_staging(tmp_path: Path) -> None:
    off = android_files(tmp_path, dex=b"dex\n035\0nothing")
    assert "lacks the production OTA values" in refused("MISMATCH", lambda: check_android(off))
    (tmp_path / "s").mkdir()
    staging = android_files(tmp_path / "s", dex=OTA_DEX + b"tf-staging-2026\0")
    assert "staging values" in refused("MISMATCH", lambda: check_android(staging))


def ios_reports(ipa: Path, *, identity: str = "Apple Distribution: Owner (ABCDE12345)") -> Any:
    info = {"CFBundleShortVersionString": "1.0.0", "CFBundleVersion": "4"}
    archive = {
        "result": "PASS",
        "info": info,
        "ota": {"mode": "on"},
        "signing": {"identity": identity},
    }
    return archive, {**archive, "package": {"sha256": d.file_sha256(ipa)}}


def test_archive_and_export_of_one_release_pass_and_development_exports_fail(
    tmp_path: Path,
) -> None:
    ipa = tmp_path / "TalentForge-1.0.0-4.ipa"
    ipa.write_bytes(b"ipa")
    archive, exported = ios_reports(ipa)

    def check(report: dict[str, Any]) -> Any:
        return d.check_ios_release(gate=gate(), archive_report=archive, ipa=ipa, ipa_report=report)

    assert check(exported)["ipaSha256"] == d.file_sha256(ipa)
    _, development = ios_reports(ipa, identity="Apple Development: Owner (QWERTY1234)")
    assert "not Apple Distribution" in refused("MISMATCH", lambda: check(development))
    refused("MISMATCH", lambda: check({**exported, "ota": {"mode": "off"}}))
    old = {"CFBundleShortVersionString": "1.0.0", "CFBundleVersion": "3"}
    refused("MISMATCH", lambda: check({**exported, "info": old}))
    refused("MISMATCH", lambda: check({**exported, "package": {"sha256": OTHER_SHA}}))


# --------------------------------------------------------------------------- the summary

JOBS = ("gate", *d.PLATFORM_JOBS["android"], *d.PLATFORM_JOBS["ios"])


def needs(**results: str) -> dict[str, Any]:
    value = {job: {"result": "success", "outputs": {}} for job in JOBS}
    for job, result in results.items():
        value[job.replace("_", "-")]["result"] = result
    return value


def status(platform: str, value: str, **fields: Any) -> dict[str, Any]:
    return {"platform": platform, "status": value, "sourceSha": SOURCE, **fields}


def summarize(results: dict[str, Any], statuses: dict[str, Any], **kwargs: Any) -> Any:
    options: dict[str, Any] = {
        "platforms": ["android", "ios"],
        "mode": "deliver",
        "needs": results,
        "statuses": statuses,
        "gate": gate(),
    }
    options.update(kwargs)
    rows, ok = d.summarize(**options)
    return {row["platform"]: row for row in rows}, ok


def test_both_platforms_delivered_is_ok() -> None:
    rows, ok = summarize(
        needs(),
        {
            "android": status("android", "DELIVERED", deliveredSha256=AAB_SHA),
            "ios": status("ios", "ALREADY_DELIVERED"),
        },
    )
    assert ok and rows["android"]["status"] == "DELIVERED"
    assert rows["android"]["version"] == "1.0.0 (3)" and rows["ios"]["version"] == "1.0.0 (4)"


def test_partial_success_is_shown_per_platform_and_fails_the_run() -> None:
    rows, ok = summarize(
        needs(ios_upload="failure", ios_testflight="skipped"),
        {"android": status("android", "DELIVERED")},
    )
    assert not ok
    assert rows["android"]["status"] == "DELIVERED"
    assert rows["ios"]["status"] == "FAILED" and "ios-upload (failure)" in rows["ios"]["detail"]


def test_a_failed_build_holds_the_other_platform_back() -> None:
    rows, ok = summarize(
        needs(android_build="failure", android_deliver="skipped", ios_upload="skipped"), {}
    )
    assert not ok
    assert rows["android"]["status"] == "FAILED"
    assert rows["ios"]["status"] == "NOT_DELIVERED"
    assert "android-build (failure)" in rows["ios"]["detail"]


def test_a_failed_job_wins_over_an_earlier_success_status() -> None:
    # a status file never hides a failure that happened after it was written
    rows, ok = summarize(
        needs(android_deliver="failure"), {"android": status("android", "VERIFIED")}
    )
    assert not ok and rows["android"]["status"] == "FAILED"
    assert rows["android"]["lastRecorded"] == "VERIFIED"


def test_a_failed_gate_blocks_every_selected_platform() -> None:
    rows, ok = summarize(needs(gate="failure"), {}, gate=None, platforms=["ios"])
    assert not ok
    assert rows["ios"]["status"] == "BLOCKED" and rows["android"]["status"] == "NOT_SELECTED"


def test_an_ios_build_found_in_app_store_connect_is_not_delivered_until_testflight() -> None:
    # ios-testflight skipped (another platform failed): the early store-check status stays
    early = {"android": status("android", "DELIVERED"), "ios": status("ios", "ALREADY_UPLOADED")}
    rows, ok = summarize(needs(ios_testflight="skipped"), early)
    assert not ok and rows["ios"]["status"] == "ALREADY_UPLOADED"
    assert summarize(needs(), early, mode="verify")[1] is False  # android DELIVERED in verify
    verify = {"android": status("android", "VERIFIED"), "ios": status("ios", "ALREADY_UPLOADED")}
    assert summarize(needs(), verify, mode="verify")[1]


def test_verify_mode_never_counts_as_delivered() -> None:
    verified = {"android": status("android", "VERIFIED"), "ios": status("ios", "VERIFIED")}
    assert summarize(needs(), verified, mode="verify")[1]
    assert not summarize(needs(), verified, mode="deliver")[1]
    delivered = {"android": status("android", "DELIVERED"), "ios": status("ios", "DELIVERED")}
    assert not summarize(needs(), delivered, mode="verify")[1]


def test_the_summary_command_ignores_status_files_of_another_source(tmp_path: Path) -> None:
    (tmp_path / "status").mkdir()
    (tmp_path / "gate.json").write_text(json.dumps(gate(platforms=["android"])))
    stale = {**status("android", "DELIVERED"), "sourceSha": OTHER}
    (tmp_path / "status/android.json").write_text(json.dumps(stale))
    summary = tmp_path / "summary.md"
    arguments = ["--gate", str(tmp_path / "gate.json"), "--status-dir", str(tmp_path / "status")]
    arguments += ["--platforms", "both", "--mode", "deliver", "--out", str(tmp_path / "r.json")]
    code = d.main(
        ["summary", *arguments],
        {"NEEDS": json.dumps(needs()), "GITHUB_STEP_SUMMARY": str(summary)},
    )
    assert code == d.EXIT_REFUSED
    report = json.loads((tmp_path / "r.json").read_text())
    assert [row["status"] for row in report["platforms"]] == ["NOT_DELIVERED", "NOT_SELECTED"]
    assert "NOT OK" in summary.read_text()


# --------------------------------------------------------------------------- the workflow


def workflow_jobs(text: str) -> dict[str, str]:
    jobs: dict[str, str] = {}
    current = None
    for line in text.split("\njobs:\n", 1)[1].splitlines():
        if line.startswith("  ") and not line.startswith("   ") and line.rstrip().endswith(":"):
            current = line.strip().rstrip(":")
            jobs[current] = ""
        elif current is not None:
            jobs[current] += line + "\n"
    return jobs


def test_the_workflow_is_owner_started_serialised_and_keeps_secrets_in_environments() -> None:
    text = (ROOT / d.WORKFLOW_FILE).read_text()
    triggers = text.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
    assert "workflow_dispatch:" in triggers
    for event in ("push:", "pull_request", "workflow_run", "schedule"):
        assert event not in triggers
    assert "group: store-delivery\n  cancel-in-progress: false" in text
    jobs = workflow_jobs(text)
    assert set(jobs) == {*JOBS, "summary"}
    environments = {
        "android-store-check": "google-play",
        "android-build": "android-signing",
        "android-deliver": "google-play",
        "ios-store-check": "app-store-connect",
        "ios-build": "ios-signing",
        "ios-upload": "app-store-connect",
        "ios-testflight": "app-store-connect",
    }
    for job, body in jobs.items():
        if "secrets." in body:
            assert f"environment: {environments[job]}" in body, job
        else:
            assert "environment:" not in body, job
    for job in ("android-deliver", "ios-upload", "ios-testflight"):
        assert "needs.gate.outputs.mode == 'deliver'" in jobs[job], job
        assert "!contains(needs.*.result, 'failure')" in jobs[job], job
    assert 'TALENTFORGE_OTA_ANDROID_PRODUCTION_ENABLED: "true"' in jobs["android-build"]
    assert 'TALENTFORGE_OTA_IOS_PRODUCTION_ENABLED: "true"' in jobs["ios-build"]
    assert "--ipa" in jobs["ios-build"] and "--require-signed" in jobs["ios-build"]
    assert "Remove the signing material\n        if: always()" in jobs["android-build"]
    assert "Remove the signing material\n        if: always()" in jobs["ios-build"]
    assert "runs-on: xcode-27" in jobs["ios-build"]
    assert (
        "CODE_SIGNING_ALLOWED=NO" not in text
        and 'OTA_ANDROID_PRODUCTION_ENABLED: "false"' not in text
    )
