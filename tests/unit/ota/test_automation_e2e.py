"""Offline production command rehearsal: real packages, signatures and reference proof."""

# ruff: noqa: F811

import copy
import json
import os
import subprocess
from contextlib import contextmanager
from datetime import datetime, timedelta
from pathlib import Path

import httpx
import pytest
from cryptography.hazmat.primitives import serialization

from talent_data.ota.automation import cli
from talent_data.ota.automation.state import RequestState
from talent_data.ota.catalog import next_catalog
from talent_data.ota.models import Channel
from talent_data.ota.signing import build_envelope, sign_pae_der
from talent_data.ota.store import CATALOG_CACHE_CONTROL
from talent_data.ota.verify import TrustedKey
from tests.unit.ota.publisher_testkit import derive_test_key
from tests.unit.ota.test_automation_prepare import prepared_inputs  # noqa: F401
from tests.unit.ota.test_automation_provenance import exact_package  # noqa: F401
from tests.unit.ota.test_automation_publisher import env, git  # noqa: F401
from tests.unit.ota.test_ota_preflight import FakeOrigin
from tests.unit.ota.test_ota_production_release import CATALOG, NOW, world  # noqa: F401


@pytest.fixture
def rehearsal(env, tmp_path, monkeypatch):
    request = env.receipt.request
    path = env.remote / "docs/ota/release-requests" / f"{request.release_id}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(request.model_dump_json(by_alias=True))
    git(env.remote, "add", ".")
    git(
        env.remote,
        "-c",
        "user.name=TEST",
        "-c",
        "user.email=test@example.invalid",
        "-c",
        "core.hooksPath=/dev/null",
        "commit",
        "-qm",
        "TEST release merge",
    )
    git(env.repo, "fetch", "origin", "main")
    git(env.repo, "reset", "--hard", "origin/main")
    commit = git(env.repo, "rev-parse", "HEAD").decode().strip()
    from talent_data.ota.automation import prepare

    def main_commit(sha):
        git(env.repo, "merge-base", "--is-ancestor", sha, "origin/main")

    monkeypatch.setattr(prepare, "_main_commit", main_commit)
    reads, writers, calls = [], [], []
    clock = [datetime.fromisoformat(NOW)]
    fail_sign = [False]
    key = derive_test_key(env.key_id)
    env.owner.keyring[cli.PRIMARY_KEY] = TrustedKey(cli.PRIMARY_KEY, key.public_key())
    password = "TEST-E2E-PASSWORD-NEVER-EXPORTED"
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.BestAvailableEncryption(password.encode()),
    ).decode()
    secrets = {
        "OTA_PRODUCTION_PRIVATE_KEY_PEM": pem,
        "OTA_PRODUCTION_KEY_PASSWORD": password,
        "OTA_PRODUCTION_S3_ACCESS_KEY_ID": "TEST-E2E-ACCESS-NEVER-EXPORTED",
        "OTA_PRODUCTION_S3_SECRET_ACCESS_KEY": "TEST-E2E-SECRET-NEVER-EXPORTED",
    }

    def secret(name):
        reads.append(name)
        if fail_sign[0] and name == "OTA_PRODUCTION_KEY_PASSWORD":
            return "TEST-WRONG-PASSWORD"
        return secrets.get(name)

    class API:
        corrupt = None

        def run(self, run_id, attempt):
            calls.append(("run", run_id, attempt))
            if run_id == 55:
                return {
                    "id": 55,
                    "run_attempt": 1,
                    "path": ".github/workflows/ota-production-publish.yml",
                    "event": environment["GITHUB_EVENT_NAME"],
                    "head_sha": environment["GITHUB_SHA"],
                    "head_branch": "main",
                    "repository": {"full_name": request.repository},
                    "head_repository": {"full_name": request.repository},
                    "actor": {"login": "operator"},
                }
            if run_id == 66:
                return {
                    "id": 66,
                    "run_attempt": 2,
                    "path": ".github/workflows/ci.yml",
                    "name": "CI",
                    "event": "push",
                    "status": "completed",
                    "conclusion": "success",
                    "head_sha": commit,
                    "head_branch": "main",
                    "repository": {"full_name": request.repository},
                    "head_repository": {"full_name": request.repository},
                }
            run = copy.deepcopy(
                env.evidence.run_metadata[
                    "package" if run_id == request.package_run.run_id else "staging"
                ]
            )
            if self.corrupt == "attempt":
                run["run_attempt"] += 1
            return run

        def repo(self, method, route, **kwargs):
            calls.append((method, route))
            if route.startswith("/collaborators/"):
                return {"permission": "write"}
            assert route == f"/actions/artifacts/{request.artifact.artifact_id}"
            artifact = copy.deepcopy(env.evidence.artifact_metadata)
            if self.corrupt == "artifact":
                artifact["expired"] = True
            return artifact

        def pages(self, route, member, **kwargs):
            calls.append(("pages", route))
            if member == "jobs":
                return [{"name": "checks", "status": "completed", "conclusion": "success"}]
            return [{"id": 66, "run_attempt": 2}]

        def archive(self, artifact):
            calls.append(("archive", artifact["id"]))
            return env.evidence.archive_bytes

    @contextmanager
    def writer():
        profile = Path(os.environ["AWS_SHARED_CREDENTIALS_FILE"])
        assert profile.stat().st_mode & 0o777 == 0o600
        assert secrets["OTA_PRODUCTION_S3_SECRET_ACCESS_KEY"] in profile.read_text()
        writers.append(True)
        yield env.store

    origin = FakeOrigin({})
    public_old = [None]

    def handler(request):
        key = request.url.path.lstrip("/")
        path = env.owner.store / key
        objects = {key: path.read_bytes()} if path.is_file() else {}
        if public_old[0] is not None and key == CATALOG:
            objects[CATALOG] = public_old[0]
        origin.objects = objects
        return origin.handler(request)

    client = httpx.Client(transport=httpx.MockTransport(handler))
    temporary = tmp_path / "temporary"
    temporary.mkdir()
    dependencies = cli.Dependencies(
        repo=env.repo,
        api=API(),
        client=client,
        journal=env.journal,
        keyring=env.owner.keyring,
        writer=writer,
        read_secret=secret,
        temporary_root=temporary,
        now=lambda: clock[0],
        verification_budget=3,
    )
    environment = {
        "OTA_PRODUCTION_AUTOMATION_ENABLED": "true",
        "OTA_PRODUCTION_KEY_ID": cli.PRIMARY_KEY,
        "GITHUB_REPOSITORY": request.repository,
        "GITHUB_REF": "refs/heads/main",
        "GITHUB_SHA": commit,
        "GITHUB_EVENT_NAME": "workflow_run",
        "GITHUB_RUN_ID": "55",
        "GITHUB_RUN_ATTEMPT": "1",
        "GITHUB_ACTOR": "operator",
        "GITHUB_EVENT_PATH": str(tmp_path / "event.json"),
        "GITHUB_STEP_SUMMARY": str(tmp_path / "summary.md"),
        "GITHUB_OUTPUT": str(tmp_path / "github-output"),
    }
    payload = {
        "action": "completed",
        "repository": {"full_name": request.repository},
        "sender": {"login": "github-actions[bot]"},
        "workflow_run": {"id": 66, "run_attempt": 2, "head_sha": commit},
    }
    status = tmp_path / "artifacts" / "status.json"

    def run(command="publish", *, execute=True, arguments=(), **updates):
        environment.update(updates)
        Path(environment["GITHUB_EVENT_PATH"]).write_text(json.dumps(payload))
        args = [command, "--status-file", str(status)]
        args.extend(arguments)
        if execute:
            args.append("--execute")
        code = cli.main(args, environment=environment, dependencies=dependencies)
        document = cli.read_status(status.read_bytes())
        return code, document

    env.dependencies, env.environment, env.payload, env.command = (
        dependencies,
        environment,
        payload,
        run,
    )
    env.reads, env.writers, env.api_calls, env.secrets = reads, writers, calls, secrets
    env.clock, env.public_old, env.fail_sign = clock, public_old, fail_sign
    env.status, env.temporary = status, temporary
    yield env
    client.close()


def test_rehearsal_main_contains_only_its_test_request(rehearsal):
    paths = (
        git(
            rehearsal.repo,
            "ls-tree",
            "-r",
            "--name-only",
            "HEAD",
            "docs/ota/release-requests",
        )
        .decode()
        .splitlines()
    )
    assert paths == [f"docs/ota/release-requests/{rehearsal.receipt.request.release_id}.json"]


def test_approved_request_to_verified_publication_and_one_notification(rehearsal, tmp_path):
    r = rehearsal
    from talent_data.notify.automation import notify_status
    from talent_data.notify.cli import _Summary
    from talent_data.notify.telegram import SendResult, SendStatus

    state = tmp_path / "notify/production.json"
    sent = []

    def send(text):
        sent.append(text)
        return SendResult(SendStatus.SENT, "TEST delivered")

    def notify(document, sender=send):
        return notify_status(
            document,
            state=state,
            client=r.dependencies.client,
            keyring=r.owner.keyring,
            now=r.clock[0],
            send=sender,
            summary=_Summary(None),
            run_url="https://github.com/TEST/actions/runs/55",
        )

    # Quiet verified baseline through the shared production marker.
    baseline_status = r.command("verify")[1]
    notify(baseline_status)
    assert not sent
    original_entries = cli.read_live(
        r.store, keyring=r.owner.keyring, game="forever", now=r.clock[0]
    ).catalog.releases
    code, document = r.command()
    assert code == 0 and document.outcome == "VERIFIED"
    before_notify = r.journal.read_all()
    assert notify(document, lambda _: SendResult(SendStatus.FAILED, "TEST outage")) == "FAILED"
    assert r.journal.read_all() == before_notify
    notify(document)
    assert len(sent) == 1 and r.receipt.request.release_id in sent[0]
    retained = r.journal.read_all()
    assert all(x.receipt.state is RequestState.VERIFIED for x in retained.values())
    assert r.command()[0] == 0
    notify(document)
    assert r.journal.read_all() == retained and len(sent) == 1
    assert len(r.writers) == 1
    # Lost webhook: daily discovery uses the complete committed tree, no event pins.
    r.payload.clear()
    r.payload["repository"] = {"full_name": r.receipt.request.repository}
    assert r.command("reconcile", GITHUB_EVENT_NAME="schedule")[0] == 0
    # A legitimate sequence+1 rollback, retaining every published entry.
    live = cli.read_live(r.store, keyring=r.owner.keyring, game="forever", now=r.clock[0])
    rollback = next_catalog(
        live.catalog,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=original_entries[0].release_id,
        issued_at=r.clock[0],
    )
    r.store.put(
        CATALOG,
        build_envelope(
            rollback.payload, {r.key_id: sign_pae_der(derive_test_key(r.key_id), rollback.payload)}
        ),
        cache_control=CATALOG_CACHE_CONTROL,
    )
    before = r.store.get(CATALOG)
    assert r.command("reconcile")[0] == 0
    assert r.store.get(CATALOG) == before
    r.clock[0] += timedelta(days=30)
    code, renewed = r.command("resign")
    assert code == 0 and renewed.outcome == "VERIFIED"
    after = cli.read_live(r.store, keyring=r.owner.keyring, game="forever", now=r.clock[0])
    assert after.catalog.releases == live.catalog.releases
    assert after.catalog.active_release_id == original_entries[0].release_id
    # Watcher learns the rollback, then identical active renewal stays quiet.
    notify(renewed)
    count = len(sent)
    assert r.command("resign")[1].outcome == "NO_ACTION"
    notify(renewed)
    assert len(sent) == count


@pytest.mark.parametrize("mode", ["publish", "reconcile", "resign", "signer-smoke", "init-journal"])
def test_disabled_or_pr_event_never_reaches_secret_job(rehearsal, mode):
    r = rehearsal
    assert r.command(mode, OTA_PRODUCTION_AUTOMATION_ENABLED="false")[1].outcome == "DISABLED"
    assert not r.reads and not r.writers and not r.api_calls
    code, result = r.command(
        mode, OTA_PRODUCTION_AUTOMATION_ENABLED="true", GITHUB_EVENT_NAME="pull_request"
    )
    assert code == 1 and result.outcome == "BLOCKED"
    assert not r.reads and not r.writers and not r.journal.read_all()


@pytest.mark.parametrize("failure", ["artifact", "attempt", "approval"])
def test_refused_preflight_is_durable_without_production_secret_reads(rehearsal, failure):
    r = rehearsal
    r.dependencies.api.corrupt = failure
    if failure == "approval":
        r.approval.unlink()
        git(r.remote, "add", "-u")
        git(
            r.remote,
            "-c",
            "user.name=TEST",
            "-c",
            "user.email=test@example.invalid",
            "-c",
            "core.hooksPath=/dev/null",
            "commit",
            "-qm",
            "withdraw",
        )
        git(r.repo, "fetch", "origin", "main")
        git(r.repo, "reset", "--hard", "origin/main")
        r.environment["GITHUB_SHA"] = git(r.repo, "rev-parse", "HEAD").decode().strip()
    code, document = r.command("preflight")
    assert code == 1 and document.outcome == "BLOCKED"
    assert not r.reads and not r.writers
    receipt = next(iter(r.journal.read_all().values())).receipt
    assert receipt.state is RequestState.BLOCKED


def test_daily_reconcile_publishes_request_without_webhook(rehearsal):
    r = rehearsal
    r.payload.clear()
    r.payload["repository"] = {"full_name": r.receipt.request.repository}
    code, result = r.command("reconcile", GITHUB_EVENT_NAME="schedule")
    assert code == 0 and result.outcome == "VERIFIED"


def test_ci_secrets_absent_from_all_outputs_and_cleanup_after_failure(
    rehearsal, capsys, monkeypatch
):
    r = rehearsal
    argv = []
    original = subprocess.run

    def record(args, **kwargs):
        argv.extend(str(x) for x in args)
        return original(args, **kwargs)

    monkeypatch.setattr(subprocess, "run", record)
    r.fail_sign[0] = True
    assert r.command()[1].outcome == "BLOCKED"
    assert not list(r.temporary.iterdir())
    r.fail_sign[0] = False
    assert r.command()[1].outcome == "VERIFIED"
    assert not list(r.temporary.iterdir())
    captured = capsys.readouterr()
    surfaces = captured.out + captured.err + "\n".join(argv)
    for path in (
        r.status,
        r.journal.path,
        Path(r.environment["GITHUB_STEP_SUMMARY"]),
        Path(r.environment["GITHUB_OUTPUT"]),
    ):
        surfaces += path.read_text()
    for file in r.status.parent.rglob("*"):
        if file.is_file():
            surfaces += file.read_text()
    for secret in r.secrets.values():
        assert secret not in surfaces
    assert "PRIVATE KEY" not in surfaces


def advance_main(r, *, remove=False):
    if remove:
        (r.remote / "docs/ota/release-requests" / f"{r.receipt.request.release_id}.json").unlink()
    else:
        (r.remote / "unrelated-change").write_text("TEST")
    git(r.remote, "add", ".")
    git(
        r.remote,
        "-c",
        "user.name=TEST",
        "-c",
        "user.email=test@example.invalid",
        "-c",
        "core.hooksPath=/dev/null",
        "commit",
        "-qm",
        "TEST current main",
    )
    git(r.repo, "fetch", "origin", "main")
    git(r.repo, "reset", "--hard", "origin/main")
    r.environment["GITHUB_SHA"] = git(r.repo, "rev-parse", "HEAD").decode().strip()


def test_public_retry_without_request_or_artifact_never_reads_secrets_or_republishes(
    rehearsal, monkeypatch
):
    r = rehearsal
    before = r.store.get(CATALOG)
    original = r.store.put
    writes = []

    def delayed(key, raw, *, cache_control):
        original(key, raw, cache_control=cache_control)
        if key == CATALOG:
            writes.append(key)
            r.public_old[0] = before

    monkeypatch.setattr(r.store, "put", delayed)
    code, status = r.command()
    assert code == 3 and status.outcome == "PUBLISHED_NOT_VERIFIED"
    assert writes == [CATALOG]
    r.public_old[0] = None
    advance_main(r, remove=True)
    r.payload.clear()
    r.payload["repository"] = {"full_name": r.receipt.request.repository}
    r.dependencies.api.corrupt = "artifact"
    r.reads.clear()
    r.api_calls.clear()
    code, status = r.command("reconcile", GITHUB_EVENT_NAME="schedule")
    assert code == 0 and status.operations[0].state is RequestState.VERIFIED
    assert not r.reads and writes == [CATALOG]
    assert not any(call[0] == "archive" for call in r.api_calls)


def test_retained_ambiguous_write_is_diagnosed_without_package_and_blocks_new_work(
    rehearsal, monkeypatch
):
    from talent_data.ota.automation.state import JournalError

    r = rehearsal
    original = r.journal.save
    puts = []
    store_put = r.store.put

    def put(key, raw, *, cache_control):
        puts.append(key)
        store_put(key, raw, cache_control=cache_control)

    monkeypatch.setattr(r.store, "put", put)

    def interrupted(receipt, prepared=None):
        saved = original(receipt, prepared)
        if receipt.state is RequestState.WRITE_STARTED:
            raise JournalError("TEST after durable barrier")
        return saved

    monkeypatch.setattr(r.journal, "save", interrupted)
    code, status = r.command()
    assert code == 3 and status.outcome == "AMBIGUOUS"
    assert CATALOG not in puts
    monkeypatch.setattr(r.journal, "save", original)
    entry = next(iter(r.journal.read_all().values()))
    live = cli.read_live(r.store, keyring=r.owner.keyring, game="forever", now=r.clock[0])
    other = next_catalog(
        live.catalog,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=live.catalog.active_release_id,
        issued_at=r.clock[0],
    )
    store_put(
        CATALOG,
        build_envelope(
            other.payload, {r.key_id: sign_pae_der(derive_test_key(r.key_id), other.payload)}
        ),
        cache_control=CATALOG_CACHE_CONTROL,
    )
    advance_main(r, remove=True)
    r.payload.clear()
    r.payload["repository"] = {"full_name": r.receipt.request.repository}
    r.dependencies.api.corrupt = "artifact"
    r.api_calls.clear()
    r.reads.clear()
    for _ in range(2):
        code, status = r.command("reconcile", GITHUB_EVENT_NAME="schedule")
        assert code == 3 and status.outcome == "AMBIGUOUS"
    assert r.journal.load(entry.receipt.request_digest).receipt.state is RequestState.AMBIGUOUS
    assert CATALOG not in puts
    assert "OTA_PRODUCTION_PRIVATE_KEY_PEM" not in r.reads
    assert not any(call[0] == "archive" for call in r.api_calls)


@pytest.mark.parametrize("barrier", ["upload", "journal"])
@pytest.mark.parametrize("change", ["request", "api"])
def test_current_request_and_api_provenance_rechecked_at_every_publisher_barrier(
    rehearsal, monkeypatch, barrier, change
):
    r = rehearsal
    original_put, original_save = r.store.put, r.journal.save
    revoked = [False]

    def revoke():
        if revoked[0]:
            return
        revoked[0] = True
        if change == "api":
            r.dependencies.api.corrupt = "attempt"
            return
        # Authorization, not approval, is removed during the long-running writer.
        (r.remote / "docs/ota/release-requests" / f"{r.receipt.request.release_id}.json").unlink()
        git(r.remote, "add", "-u")
        git(
            r.remote,
            "-c",
            "user.name=TEST",
            "-c",
            "user.email=test@example.invalid",
            "-c",
            "core.hooksPath=/dev/null",
            "commit",
            "-qm",
            "withdraw request",
        )

    puts = []

    def put(key, raw, *, cache_control):
        original_put(key, raw, cache_control=cache_control)
        puts.append(key)
        if barrier == "upload":
            revoke()

    def save(receipt, prepared=None):
        result = original_save(receipt, prepared)
        if barrier == "journal" and receipt.state is RequestState.WRITE_STARTED:
            revoke()
        return result

    monkeypatch.setattr(r.store, "put", put)
    monkeypatch.setattr(r.journal, "save", save)
    code, status = r.command()
    assert code == 1 and status.outcome == "BLOCKED"
    assert CATALOG not in puts
    if barrier == "upload":
        assert "OTA_PRODUCTION_PRIVATE_KEY_PEM" not in r.reads


def test_default_dry_run_and_smoke_have_no_catalog_capability(rehearsal):
    r = rehearsal
    before = r.owner.snapshot()
    assert r.command(execute=False)[1].outcome == "DRY_RUN"
    assert not r.reads and not r.writers and r.journal.read_all() == {}
    r.payload["sender"] = {"login": "operator"}
    code, status = r.command("signer-smoke", GITHUB_EVENT_NAME="workflow_dispatch")
    assert code == 0 and status.reason == "SIGNER_VERIFIED"
    assert not r.writers and before == r.owner.snapshot()
    assert not list(r.temporary.iterdir())
    assert not any("S3_" in name for name in r.reads)


@pytest.mark.parametrize("boundary", ["profile", "writer"])
def test_cleanup_failure_after_put_preserves_published_outcome_and_verify_only_retry(
    rehearsal, monkeypatch, capsys, boundary
):
    from talent_data.ota.automation.ci_signer import SecretError

    r = rehearsal
    original = cli.temporary_aws_profile if boundary == "profile" else r.dependencies.writer

    @contextmanager
    def fail_cleanup(*args, **kwargs):
        with original(*args, **kwargs) as value:
            yield value
        raise SecretError(
            "TEST external cleanup failure " + r.secrets["OTA_PRODUCTION_KEY_PASSWORD"]
        )

    if boundary == "profile":
        monkeypatch.setattr(cli, "temporary_aws_profile", fail_cleanup)
    else:
        r.dependencies.writer = fail_cleanup
    code, status = r.command()
    assert code == 3 and status.outcome == "PUBLISHED_NOT_VERIFIED"
    assert status.stage == "CLEANUP" and status.reason == "CLEANUP_FAILED"
    assert {x.state for x in status.operations} == {RequestState.PUBLISHED_NOT_VERIFIED}
    assert not list(r.temporary.iterdir())
    output = capsys.readouterr()
    for secret in r.secrets.values():
        assert secret not in output.out + output.err + r.status.read_text()
    snapshot, reads, writers = r.owner.snapshot(), len(r.reads), len(r.writers)
    code, verified = r.command("verify")
    assert code == 0 and verified.outcome == "VERIFIED"
    assert r.owner.snapshot() == snapshot and len(r.reads) == reads and len(r.writers) == writers


def test_status_wire_is_closed_and_cannot_smuggle_a_reason_or_url():
    wire = {
        "schemaVersion": 1,
        "kind": "ota-production-status",
        "command": "verify",
        "operation": "verify",
        "outcome": "VERIFIED",
        "stage": "PUBLIC_VERIFY",
        "reason": "PUBLIC_VERIFIED",
        "writerNeeded": False,
        "operations": [],
    }
    assert cli.read_status(json.dumps(wire).encode()).exit_code == 0
    for change in (
        {"schemaVersion": True},
        {"schemaVersion": "1"},
        {"reason": "arbitrary secret text"},
        {"url": "https://evil.test"},
        {"outcome": "success"},
        {"writerNeeded": "false"},
    ):
        with pytest.raises(ValueError):
            cli.read_status(json.dumps({**wire, **change}).encode())


def test_notification_reverifies_success_and_failure_delivery_retries(rehearsal, tmp_path):
    from talent_data.notify.automation import notify_status
    from talent_data.notify.cli import _Summary
    from talent_data.notify.telegram import SendResult, SendStatus
    from talent_data.ota.store import blob_key

    r = rehearsal
    state = tmp_path / "notify/production.json"
    sent = []

    def send(text):
        sent.append(text)
        return SendResult(SendStatus.SENT, "TEST delivered")

    def notify(status, sender=send):
        return notify_status(
            status,
            state=state,
            client=r.dependencies.client,
            keyring=r.owner.keyring,
            now=r.clock[0],
            send=sender,
            summary=_Summary(None),
            run_url="https://github.com/TEST/actions/runs/55",
        )

    baseline = r.command("verify")[1]
    notify(baseline)
    r.dependencies.api.corrupt = "artifact"
    blocked = r.command("preflight")[1]
    before = state.read_bytes()
    assert notify(blocked, lambda _: SendResult(SendStatus.FAILED, "TEST outage")) == "FAILED"
    assert state.read_bytes() == before
    assert notify(blocked) == "SENT"
    assert "PROVENANCE" in sent[0] and "PACKAGE_PROVENANCE_FAILED" in sent[0]
    notify(blocked)
    assert len(sent) == 1
    # Healthy hourly observation and independent daily renewal cannot acknowledge a
    # broken candidate stage. Only that same operation's successful run recovers it.
    from talent_data.notify.production_watch import observe_production
    from talent_data.notify.state import ProductionDedup, decide_production, load_dedup, save_dedup

    observed = observe_production(
        r.dependencies.client,
        origin="https://ota.mythweld.com",
        keyring=r.owner.keyring,
        game="forever",
        now=r.clock[0],
        verify_files=True,
    )
    previous = load_dedup(ProductionDedup, state)
    hourly = decide_production(previous, observation=observed, run_url="TEST", now=NOW)
    save_dedup(state, hourly.state)
    notify(baseline.model_copy(update={"operation": "resign"}))
    notify(blocked)
    assert len(sent) == 1
    r.dependencies.api.corrupt = None
    verified = r.command()[1]
    assert verified.outcome == "VERIFIED"
    retained = r.journal.read_all()
    sha, raw = next(iter(r.package.blobs.items()))
    key = blob_key(sha)
    r.store.put(key, b"TEST corruption", cache_control="public")
    notify(verified)
    assert len(sent) == 2 and "сбой" in sent[-1]
    assert json.loads(state.read_bytes())["notifiedRelease"] != r.receipt.request.release_id
    r.store.put(key, raw, cache_control="public")
    notify(verified)
    notify(verified)
    assert len(sent) == 3 and "production OTA обновился" in sent[-1]
    assert r.journal.read_all() == retained


def test_manual_ci_arguments_are_bound_to_the_event_and_permission(rehearsal):
    r = rehearsal
    r.payload["sender"] = {"login": "operator"}
    r.payload["inputs"] = {"ci_run_id": "66", "ci_attempt": "1"}
    args = ("--ci-run-id", "66", "--ci-attempt", "2")
    code, status = r.command(arguments=args, GITHUB_EVENT_NAME="workflow_dispatch")
    assert code == 1 and status.stage == "INVOCATION"
    assert not r.reads and not r.writers
    r.payload["inputs"]["ci_attempt"] = "2"
    assert r.command(arguments=args)[1].outcome == "VERIFIED"


def test_explicit_one_time_journal_setup_is_manual_and_never_reads_production_secrets(
    rehearsal, tmp_path
):
    from talent_data.ota.automation.state import FileJournal

    r = rehearsal
    journal = FileJournal(tmp_path / "new-journal.json")
    r.dependencies.journal = journal
    r.payload["sender"] = {"login": "operator"}
    code, status = r.command("init-journal", execute=False, GITHUB_EVENT_NAME="workflow_dispatch")
    assert code == 0 and status.outcome == "DRY_RUN" and not journal.path.exists()
    code, preflight = r.command("preflight", arguments=("--operation", "init-journal"))
    assert code == 0 and preflight.outcome == "READY" and preflight.writer_needed
    assert not journal.path.exists() and not r.reads and not r.writers
    assert r.command("init-journal")[1].reason == "JOURNAL_INITIALIZED"
    assert journal.read_all() == {}
    assert r.command("init-journal")[0] == 1
    assert not r.reads and not r.writers


@pytest.mark.parametrize("flag", ["TRUE", "true ", "1", "", "false"])
def test_enable_flag_is_exact_without_constructing_any_dependencies(tmp_path, flag):
    code = cli.main(
        ["publish", "--execute", "--status-file", str(tmp_path / "status.json")],
        environment={cli.ENABLE_FLAG: flag},
    )
    assert code == 0
    assert cli.read_status((tmp_path / "status.json").read_bytes()).outcome == "DISABLED"


def test_clean_checkout_authenticates_git_journal_without_persisting_token(
    rehearsal, tmp_path, monkeypatch, capsys
):
    import base64
    import sys

    from talent_data.ota.automation.state import GitJournal, JournalIndex
    from talent_data.ota.verify import parse_strict

    r = rehearsal
    protected = tmp_path / "auth-required.git"
    git(r.remote, "clone", "--bare", str(r.remote), str(protected))
    git(r.repo, "remote", "set-url", "origin", "ssh://git@github.com" + str(protected))
    # Real Git pack transport; only this local SSH boundary is synthetic. It refuses
    # every remote read/write unless the ephemeral HTTP authorization is supplied.
    shim = tmp_path / "auth-required-ssh"
    shim.write_text(
        f"#!{sys.executable}\n"
        "import os, shlex, sys\n"
        "expected = os.environ.get('TEST_EXPECTED_GIT_AUTH')\n"
        "if not expected or os.environ.get('GIT_CONFIG_VALUE_0') != expected:\n"
        "    sys.exit(71)\n"
        "if os.environ.get('GIT_CONFIG_KEY_0') != 'http.https://github.com/.extraheader':\n"
        "    sys.exit(72)\n"
        "command = shlex.split(sys.argv[-1])\n"
        "os.execvp(command[0], command)\n"
    )
    shim.chmod(0o700)
    token = "TEST-GITHUB-JOURNAL-TOKEN-NOT-PERSISTED"
    encoded = base64.b64encode(("x-access-token:" + token).encode()).decode()
    monkeypatch.setenv("GIT_SSH_COMMAND", str(shim))
    monkeypatch.setenv("GIT_SSH_VARIANT", "ssh")
    monkeypatch.setenv("TEST_EXPECTED_GIT_AUTH", "AUTHORIZATION: basic " + encoded)
    for key in ("GIT_CONFIG_COUNT", "GIT_CONFIG_KEY_0", "GIT_CONFIG_VALUE_0"):
        monkeypatch.delenv(key, raising=False)
    denied = subprocess.run(["git", "-C", str(r.repo), "ls-remote", "origin"], capture_output=True)
    assert denied.returncode != 0
    r.dependencies.journal = GitJournal(r.repo)
    r.payload["sender"] = {"login": "operator"}
    r.environment["GITHUB_TOKEN"] = token
    argv = []
    original_run = subprocess.run

    def record(command, **kwargs):
        argv.extend(map(str, command))
        return original_run(command, **kwargs)

    monkeypatch.setattr(subprocess, "run", record)
    code, status = r.command("init-journal", GITHUB_EVENT_NAME="workflow_dispatch")
    assert code == 0 and status.reason == "JOURNAL_INITIALIZED"
    r.dependencies.api.corrupt = "artifact"
    code, status = r.command("preflight")
    assert code == 1 and status.outcome == "BLOCKED"
    raw = git(protected, "show", "refs/heads/ota-publication-state:journal.json")
    index = parse_strict(JournalIndex, raw)
    assert next(iter(index.entries.values())).receipt.state is RequestState.BLOCKED
    # An explicit setup refusal also restores authentication after the failing command.
    assert r.command("init-journal")[0] == 1
    assert not r.reads and not r.writers
    assert all(
        key not in os.environ
        for key in ("GIT_CONFIG_COUNT", "GIT_CONFIG_KEY_0", "GIT_CONFIG_VALUE_0")
    )
    inherited = {
        "GIT_CONFIG_COUNT": "1",
        "GIT_CONFIG_KEY_0": "http.https://prior.invalid/.extraheader",
        "GIT_CONFIG_VALUE_0": "TEST-inherited-auth",
        "GIT_TERMINAL_PROMPT": "1",
    }
    for name, value in inherited.items():
        monkeypatch.setenv(name, value)
    assert r.command("init-journal")[0] == 1
    assert all(os.environ[name] == value for name, value in inherited.items())
    captured = capsys.readouterr()
    surfaces = captured.out + captured.err + "\n".join(argv) + raw.decode()
    for path in (
        r.repo / ".git/config",
        r.status,
        Path(r.environment["GITHUB_STEP_SUMMARY"]),
        Path(r.environment["GITHUB_OUTPUT"]),
        shim,
    ):
        surfaces += path.read_text()
    assert token not in surfaces and encoded not in surfaces
    assert "extraheader" not in (r.repo / ".git/config").read_text()


@pytest.mark.parametrize("barrier", ["upload", "journal"])
@pytest.mark.parametrize("race", ["approval_before_callback", "request_after_callback"])
def test_assessment_uses_one_stable_main_snapshot(rehearsal, monkeypatch, barrier, race):
    from talent_data.ota.automation import publisher

    r = rehearsal
    original_approval = publisher.approval_directory
    original_evidence = cli._Run.evidence
    original_put, original_save = r.store.put, r.journal.save
    armed, changed, puts = [False], [False], []

    def change_main():
        if changed[0] or not armed[0]:
            return
        changed[0] = True
        if race == "approval_before_callback":
            r.approval.unlink()
        else:
            (
                r.remote / "docs/ota/release-requests" / f"{r.receipt.request.release_id}.json"
            ).unlink()
        git(r.remote, "add", "-u")
        git(
            r.remote,
            "-c",
            "user.name=TEST",
            "-c",
            "user.email=test@example.invalid",
            "-c",
            "core.hooksPath=/dev/null",
            "commit",
            "-qm",
            "TEST revoke during assessment",
        )

    def approval(*args, **kwargs):
        result = original_approval(*args, **kwargs)
        if race == "approval_before_callback":
            change_main()
        return result

    def evidence(run, request):
        result = original_evidence(run, request)
        if race == "request_after_callback" and armed[0]:
            change_main()
            publisher.refresh_main(r.repo)
        return result

    def put(key, raw, *, cache_control):
        original_put(key, raw, cache_control=cache_control)
        puts.append(key)
        if barrier == "upload":
            armed[0] = True

    def save(receipt, prepared=None):
        result = original_save(receipt, prepared)
        if barrier == "journal" and receipt.state is RequestState.WRITE_STARTED:
            armed[0] = True
        return result

    monkeypatch.setattr(publisher, "approval_directory", approval)
    monkeypatch.setattr(cli._Run, "evidence", evidence)
    monkeypatch.setattr(r.store, "put", put)
    monkeypatch.setattr(r.journal, "save", save)
    code, status = r.command()
    assert changed[0]
    assert code == 1 and status.outcome == "BLOCKED"
    assert CATALOG not in puts
    if barrier == "upload":
        assert "OTA_PRODUCTION_PRIVATE_KEY_PEM" not in r.reads


@pytest.mark.parametrize("check", ["references", "appended"])
@pytest.mark.parametrize("revoked", ["approval", "request"])
def test_revocation_during_last_object_checks_prevents_catalog_put(
    rehearsal, monkeypatch, check, revoked
):
    from talent_data.ota.automation import publisher

    r = rehearsal
    original_save, original_get, original_put = r.journal.save, r.store.get, r.store.put
    armed, phase, changed, puts = [False], [None], [False], []

    def save(receipt, prepared=None):
        result = original_save(receipt, prepared)
        if receipt.state is RequestState.WRITE_STARTED:
            armed[0] = True
        return result

    def checking(name, function):
        def run(*args, **kwargs):
            phase[0] = name
            try:
                return function(*args, **kwargs)
            finally:
                phase[0] = None

        return run

    def get(key):
        if armed[0] and phase[0] == check and key != CATALOG and not changed[0]:
            changed[0] = True
            path = (
                r.approval
                if revoked == "approval"
                else r.remote / "docs/ota/release-requests" / f"{r.receipt.request.release_id}.json"
            )
            path.unlink()
            git(r.remote, "add", "-u")
            git(
                r.remote,
                "-c",
                "user.name=TEST",
                "-c",
                "user.email=test@example.invalid",
                "-c",
                "core.hooksPath=/dev/null",
                "commit",
                "-qm",
                "TEST revoke during final object read",
            )
        return original_get(key)

    def put(key, raw, *, cache_control):
        puts.append(key)
        return original_put(key, raw, cache_control=cache_control)

    monkeypatch.setattr(r.journal, "save", save)
    monkeypatch.setattr(r.store, "get", get)
    monkeypatch.setattr(r.store, "put", put)
    monkeypatch.setattr(
        publisher,
        "ensure_catalog_references",
        checking("references", publisher.ensure_catalog_references),
    )
    monkeypatch.setattr(
        publisher,
        "verify_appended_releases",
        checking("appended", publisher.verify_appended_releases),
    )
    code, status = r.command()
    assert changed[0] and armed[0]
    assert code == 1 and status.outcome == "BLOCKED"
    entry = r.journal.load(r.receipt.request_digest)
    assert entry.receipt.state is RequestState.BLOCKED
    assert entry.receipt.reason == "CHECK_FAILED" and entry.prepared is not None
    assert CATALOG not in puts


@pytest.mark.parametrize("first_command", ["preflight", "publish"])
def test_old_public_base_is_retryable_until_authoritative_catalog_converges(
    rehearsal, monkeypatch, tmp_path, first_command
):
    from talent_data.notify.automation import notify_status
    from talent_data.notify.cli import _Summary
    from talent_data.notify.telegram import SendResult, SendStatus
    from talent_data.ota.catalog import catalog_entry
    from talent_data.ota.models import OtaReleaseManifest
    from talent_data.ota.release import serialize_manifest
    from talent_data.ota.store import blob_key
    from talent_data.ota.verify import parse_strict, sha256_ref

    r = rehearsal
    before = cli.read_live(r.store, keyring=r.owner.keyring, game="forever", now=r.clock[0])
    base = next(
        x for x in before.catalog.releases if x.release_id == before.catalog.active_release_id
    )
    manifest = parse_strict(OtaReleaseManifest, r.store.get(blob_key(base.manifest_sha256)))
    # A real metadata-only base revision, followed by an authoritative rollback to
    # the exact request's base. Both signed catalogs retain all prior entries.
    revision = manifest.model_copy(
        update={"release_id": manifest.release_id.rsplit("-r", 1)[0] + "-r2"}
    )
    raw = serialize_manifest(revision)
    r.store.put(blob_key(sha256_ref(raw)), raw, cache_control="public")
    old = next_catalog(
        before.catalog,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=revision.release_id,
        append=catalog_entry(raw, revision, published_at=r.clock[0]),
        issued_at=r.clock[0],
    )
    authoritative = next_catalog(
        old.catalog,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=manifest.release_id,
        issued_at=r.clock[0],
    )

    def signed(payload):
        return build_envelope(payload, {r.key_id: sign_pae_der(derive_test_key(r.key_id), payload)})

    r.store.put(CATALOG, signed(authoritative.payload), cache_control=CATALOG_CACHE_CONTROL)
    r.public_old[0] = signed(old.payload)
    original_request = git(
        r.repo, "show", f"origin/main:docs/ota/release-requests/{r.receipt.request.release_id}.json"
    )
    archive, created_at = r.evidence.archive_bytes, r.package.manifest.created_at
    original_put, puts, sent = r.store.put, [], []

    def put(key, data, *, cache_control):
        if key == CATALOG:
            puts.append(data)
        return original_put(key, data, cache_control=cache_control)

    def send(text):
        sent.append(text)
        return SendResult(SendStatus.SENT, "TEST delivered")

    def notify(status):
        return notify_status(
            status,
            state=tmp_path / "notify/production.json",
            client=r.dependencies.client,
            keyring=r.owner.keyring,
            now=r.clock[0],
            send=send,
            summary=_Summary(None),
            run_url="https://github.com/TEST/actions/runs/55",
        )

    monkeypatch.setattr(r.store, "put", put)
    notify(r.command("verify")[1])  # quiet public baseline
    code, status = r.command(first_command)
    entry = r.journal.load(r.receipt.request_digest)
    assert code == 1 and status.outcome == "BLOCKED"
    assert (entry.receipt.state, entry.receipt.reason) == (RequestState.BLOCKED, "LIVE_CHANGED")
    assert not r.reads and not r.writers and not puts
    r.public_old[0] = None
    r.payload.clear()
    r.payload["repository"] = {"full_name": r.receipt.request.repository}
    code, status = r.command("reconcile", GITHUB_EVENT_NAME="schedule")
    assert code == 0 and status.outcome == "VERIFIED"
    assert r.journal.load(r.receipt.request_digest).receipt.state is RequestState.VERIFIED
    assert len(puts) == 1
    notify(status)
    assert len(sent) == 1 and r.receipt.request.release_id in sent[0]
    assert r.command("reconcile")[1].outcome == "NO_ACTION"
    notify(status)
    assert len(puts) == 1 and len(sent) == 1
    assert (
        git(
            r.repo,
            "show",
            f"origin/main:docs/ota/release-requests/{r.receipt.request.release_id}.json",
        )
        == original_request
    )
    assert r.evidence.archive_bytes == archive and r.package.manifest.created_at == created_at


def test_blocked_candidate_does_not_turn_verified_due_renewal_into_failure(rehearsal, tmp_path):
    from talent_data.notify.automation import notify_status
    from talent_data.notify.cli import _Summary
    from talent_data.notify.telegram import SendResult, SendStatus

    r = rehearsal
    state, sent = tmp_path / "notify/production.json", []

    def send(text):
        sent.append(text)
        return SendResult(SendStatus.SENT, "TEST delivered")

    def notify(status):
        return notify_status(
            status,
            state=state,
            client=r.dependencies.client,
            keyring=r.owner.keyring,
            now=r.clock[0],
            send=send,
            summary=_Summary(None),
            run_url="https://github.com/TEST/actions/runs/55",
        )

    notify(r.command("verify")[1])
    r.dependencies.api.corrupt = "artifact"
    r.payload.clear()
    r.payload["repository"] = {"full_name": r.receipt.request.repository}
    blocked = r.command("reconcile", GITHUB_EVENT_NAME="schedule")[1]
    assert blocked.outcome == "BLOCKED"
    notify(blocked)
    before_marker = json.loads(state.read_bytes())["failuresNotified"]
    candidate = next(iter(r.journal.read_all().values())).receipt
    r.clock[0] += timedelta(days=30)
    before = cli.read_live(r.store, keyring=r.owner.keyring, game="forever", now=r.clock[0])
    code, renewed = r.command("resign")
    assert code == 0 and renewed.outcome == "VERIFIED"
    assert r.journal.load(candidate.request_digest).receipt == candidate
    after = cli.read_live(r.store, keyring=r.owner.keyring, game="forever", now=r.clock[0])
    assert after.catalog.sequence == before.catalog.sequence + 1
    assert after.catalog.releases == before.catalog.releases
    assert after.catalog.active_release_id == before.catalog.active_release_id
    count = len(sent)
    assert notify(renewed) == "QUIET"
    assert (
        len(sent) == count and json.loads(state.read_bytes())["failuresNotified"] == before_marker
    )
    assert r.command("resign")[1].outcome == "NO_ACTION"
    notify(blocked)
    assert len(sent) == count


def test_unknown_put_is_privately_diagnosed_while_public_files_are_unavailable(
    rehearsal, monkeypatch
):
    from talent_data.ota.errors import OtaPublisherError
    from talent_data.ota.store import blob_key

    r = rehearsal
    original_put, original_get = r.store.put, r.store.get
    landed, private_down, puts = [False], [False], []

    def put(key, raw, *, cache_control):
        original_put(key, raw, cache_control=cache_control)
        puts.append(key)
        if key == CATALOG:
            landed[0] = True
            private_down[0] = True
            raise OtaPublisherError("TEST lost PUT response")

    def get(key):
        if private_down[0] and key == CATALOG:
            raise OtaPublisherError("TEST private read unavailable")
        return original_get(key)

    monkeypatch.setattr(r.store, "put", put)
    monkeypatch.setattr(r.store, "get", get)
    assert r.command()[1].outcome == "AMBIGUOUS"
    assert landed[0] and puts.count(CATALOG) == 1
    private_down[0] = False
    sha = next(iter(r.package.blobs))
    public_path = "/" + blob_key(sha)
    healthy_client = r.dependencies.client
    healthy_transport = healthy_client._transport

    def unavailable(request):
        if request.url.path == public_path:
            return httpx.Response(503, stream=httpx.ByteStream(b""))
        return healthy_transport.handle_request(request)

    client = httpx.Client(transport=httpx.MockTransport(unavailable))
    r.dependencies.client = client
    r.reads.clear()
    r.api_calls.clear()
    before = list(puts)
    try:
        code, diagnosed = r.command()
        assert code == 3 and diagnosed.outcome == "PUBLISHED_NOT_VERIFIED"
        retained = next(iter(r.journal.read_all().values())).receipt
        assert retained.state is RequestState.PUBLISHED_NOT_VERIFIED
        assert "OTA_PRODUCTION_PRIVATE_KEY_PEM" not in r.reads
        assert not any(call[0] == "archive" for call in r.api_calls)
        assert puts == before
        r.reads.clear()
        writers = len(r.writers)
        assert r.command()[1].outcome == "PUBLISHED_NOT_VERIFIED"
        assert not r.reads and len(r.writers) == writers and puts == before
    finally:
        r.dependencies.client = healthy_client
        client.close()
    assert r.command()[0] == 0
    assert all(
        entry.receipt.state is RequestState.VERIFIED for entry in r.journal.read_all().values()
    )
    assert puts == before and not r.reads


def test_ambiguous_diagnosis_still_retries_other_retained_public_verification(rehearsal):
    from talent_data.ota.automation.publisher import renew_catalog
    from talent_data.ota.errors import OtaPublisherError

    r = rehearsal
    first = r.run()  # real release write; deliberately leave public confirmation pending
    assert first.state is RequestState.PUBLISHED_NOT_VERIFIED
    r.clock[0] += timedelta(days=30)
    get, put = r.store.get, r.store.put
    down = [False]

    def lost_put(key, raw, *, cache_control):
        put(key, raw, cache_control=cache_control)
        if key == CATALOG:
            down[0] = True
            raise OtaPublisherError("TEST lost renewal response")

    def unavailable(key):
        if down[0] and key == CATALOG:
            raise OtaPublisherError("TEST lost renewal read")
        return get(key)

    r.store.put, r.store.get = lost_put, unavailable
    unknown = renew_catalog(
        store=r.store,
        keyring=r.owner.keyring,
        game="forever",
        now=r.clock[0],
        journal=r.journal,
        signer=r.signer,
        run_id=77,
        attempt=1,
        key_id=r.key_id,
    )
    assert unknown.state is RequestState.AMBIGUOUS
    down[0] = False
    r.store.put, r.store.get = put, get
    live = cli.read_live(r.store, keyring=r.owner.keyring, game="forever", now=r.clock[0])
    other = next_catalog(
        live.catalog,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=live.catalog.active_release_id,
        issued_at=r.clock[0],
    )
    put(
        CATALOG,
        build_envelope(
            other.payload, {r.key_id: sign_pae_der(derive_test_key(r.key_id), other.payload)}
        ),
        cache_control=CATALOG_CACHE_CONTROL,
    )
    r.payload.clear()
    r.payload["repository"] = {"full_name": r.receipt.request.repository}
    healthy_client, public_reads = r.dependencies.client, []

    def observe(request):
        public_reads.append(request.url.path)
        return healthy_client._transport.handle_request(request)

    with httpx.Client(transport=httpx.MockTransport(observe)) as client:
        r.dependencies.client = client
        try:
            assert r.command("reconcile", GITHUB_EVENT_NAME="schedule")[1].outcome == "AMBIGUOUS"
        finally:
            r.dependencies.client = healthy_client
    assert "/" + CATALOG in public_reads
    assert r.journal.load(first.request_digest).receipt.state is RequestState.PUBLISHED_NOT_VERIFIED
    assert r.journal.load(unknown.request_digest).receipt.state is RequestState.AMBIGUOUS


@pytest.mark.parametrize("command", ["publish", "resign"])
@pytest.mark.parametrize("readback", [True, False], ids=["exact", "unknown"])
def test_journal_outage_after_put_preserves_outcome_and_recovers_without_rewrite(
    rehearsal, monkeypatch, tmp_path, command, readback
):
    from talent_data.notify.automation import notify_status
    from talent_data.notify.cli import _Summary
    from talent_data.notify.telegram import SendResult, SendStatus
    from talent_data.ota.errors import OtaPublisherError

    r = rehearsal
    if command == "resign":
        r.clock[0] += timedelta(days=30)
    before = cli.read_live(r.store, keyring=r.owner.keyring, game="forever", now=r.clock[0])
    state, sent = tmp_path / "notify/production.json", []

    def send(text):
        sent.append(text)
        return SendResult(SendStatus.SENT, "TEST delivered")

    def notify(status):
        return notify_status(
            status,
            state=state,
            client=r.dependencies.client,
            keyring=r.owner.keyring,
            now=r.clock[0],
            send=send,
            summary=_Summary(None),
            run_url="https://github.com/TEST/actions/runs/55",
        )

    notify(r.command("verify")[1])  # quiet, fully verified public baseline
    summary = Path(r.environment["GITHUB_STEP_SUMMARY"])
    summary.write_text("")
    original_put, original_get, original_read = r.store.put, r.store.get, r.journal._read
    landed, down, puts = [False], [False], []

    def journal_read():
        if down[0]:
            raise OSError("TEST journal unavailable")
        return original_read()

    def put(key, raw, *, cache_control):
        original_put(key, raw, cache_control=cache_control)
        if key == CATALOG:
            puts.append(raw)
            landed[0] = down[0] = True

    def get(key):
        if landed[0] and down[0] and not readback and key == CATALOG:
            raise OtaPublisherError("TEST private read unavailable")
        return original_get(key)

    monkeypatch.setattr(r.store, "put", put)
    monkeypatch.setattr(r.store, "get", get)
    monkeypatch.setattr(r.journal, "_read", journal_read)
    code, status = r.command(command)
    assert landed[0] and len(puts) == 1
    assert original_get(CATALOG) != before.envelope
    expected = "PUBLISHED_NOT_VERIFIED" if readback else "AMBIGUOUS"
    assert code == 3 and status.outcome == expected
    assert (status.stage, status.reason) == ("JOURNAL", "JOURNAL_FAILED")
    kind = "release" if command == "publish" else "renewal"
    operations = [x for x in status.operations if x.kind == kind]
    assert len(operations) == 1
    operation = operations[0]
    assert operation.state.value == expected
    assert operation.payload_sha256 is not None
    assert f"OTA production: {expected}" in summary.read_text()
    assert "PACKAGE_PROVENANCE_FAILED" not in summary.read_text()
    notify(status)
    notify(status)
    assert len(sent) == 1 and expected in sent[0]
    assert json.loads(state.read_bytes())["notifiedRelease"] != r.receipt.request.release_id

    down[0] = False
    assert r.journal.load(operation.digest).receipt.state is RequestState.WRITE_STARTED
    r.reads.clear()
    r.api_calls.clear()
    after_put = original_get(CATALOG)
    code, recovered = r.command(command)
    assert code == 0 and recovered.outcome == "VERIFIED"
    assert r.journal.load(operation.digest).receipt.state is RequestState.VERIFIED
    assert "OTA_PRODUCTION_PRIVATE_KEY_PEM" not in r.reads
    assert not any(call[0] == "archive" for call in r.api_calls)
    assert len(puts) == 1 and original_get(CATALOG) == after_put
    after = cli.read_live(r.store, keyring=r.owner.keyring, game="forever", now=r.clock[0])
    assert after.catalog.sequence == before.catalog.sequence + 1
    notify(recovered)
    notify(recovered)
    assert len(sent) == (2 if command == "publish" else 1)
    assert r.command(command)[1].outcome == "NO_ACTION"
    assert len(puts) == 1 and original_get(CATALOG) == after_put


@pytest.mark.parametrize("command", ["publish", "resign"])
def test_journal_outage_before_put_still_prohibits_write(rehearsal, monkeypatch, command):
    r = rehearsal
    if command == "resign":
        r.clock[0] += timedelta(days=30)
    before, puts = r.store.get(CATALOG), []
    original_save, original_put = r.journal.save, r.store.put

    def save(receipt, prepared=None):
        if receipt.state is RequestState.WRITE_STARTED:

            def unavailable():
                raise OSError("TEST journal unavailable before PUT")

            monkeypatch.setattr(r.journal, "_read", unavailable)
        return original_save(receipt, prepared)

    def put(key, raw, *, cache_control):
        puts.append(key)
        return original_put(key, raw, cache_control=cache_control)

    monkeypatch.setattr(r.journal, "save", save)
    monkeypatch.setattr(r.store, "put", put)
    code, status = r.command(command)
    assert code == 1 and status.outcome == "BLOCKED"
    assert CATALOG not in puts and r.store.get(CATALOG) == before


@pytest.mark.parametrize("malformed", [True, False], ids=["broken-request", "control"])
def test_due_resign_preflight_and_writer_do_not_parse_release_requests(
    rehearsal, monkeypatch, malformed
):
    r = rehearsal
    if malformed:
        path = r.remote / "docs/ota/release-requests" / f"{r.receipt.request.release_id}.json"
        path.write_text('{"schemaVersion"}')
        git(r.remote, "add", ".")
        git(
            r.remote,
            "-c",
            "user.name=TEST",
            "-c",
            "user.email=test@example.invalid",
            "-c",
            "core.hooksPath=/dev/null",
            "commit",
            "-qm",
            "TEST malformed request",
        )
        git(r.repo, "fetch", "origin", "main")
        git(r.repo, "reset", "--hard", "origin/main")
        r.environment["GITHUB_SHA"] = git(r.repo, "rev-parse", "HEAD").decode().strip()
    r.payload.clear()
    r.payload["repository"] = {"full_name": r.receipt.request.repository}
    r.environment["GITHUB_EVENT_NAME"] = "schedule"
    r.clock[0] += timedelta(days=30)
    before = cli.read_live(r.store, keyring=r.owner.keyring, game="forever", now=r.clock[0])
    original_put, puts = r.store.put, []

    def put(key, raw, *, cache_control):
        if key == CATALOG:
            puts.append(raw)
        return original_put(key, raw, cache_control=cache_control)

    monkeypatch.setattr(r.store, "put", put)
    initial_entries = r.journal.read_all()
    code, planned = r.command("preflight", arguments=("--operation", "resign"))
    assert code == 0 and planned.outcome == "READY" and planned.writer_needed
    assert not r.reads and not r.writers and not puts
    if malformed:
        assert r.journal.read_all() == initial_entries
    code, status = r.command("resign")
    assert code == 0 and status.outcome == "VERIFIED"
    after = cli.read_live(r.store, keyring=r.owner.keyring, game="forever", now=r.clock[0])
    assert after.catalog.sequence == before.catalog.sequence + 1
    assert after.catalog.releases == before.catalog.releases
    assert after.catalog.active_release_id == before.catalog.active_release_id
    assert after.catalog.expires_at > before.catalog.expires_at
    assert len(puts) == 1
    assert not any(call[0] == "archive" for call in r.api_calls)
    assert [x.kind for x in status.operations].count("renewal") == 1
    if malformed:
        assert all(r.journal.load(k) == v for k, v in initial_entries.items())
    assert r.command("resign")[1].outcome == "NO_ACTION"
    assert len(puts) == 1
    if malformed:
        reads, writers = len(r.reads), len(r.writers)
        code, refused = r.command("publish")
        assert code == 1 and refused.outcome == "BLOCKED"
        assert len(puts) == 1 and len(r.reads) == reads and len(r.writers) == writers
