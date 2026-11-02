"""Real package provenance/gates and TEST signatures, with a local Git/S3 twin."""

import copy
import json
import shutil
import subprocess
from datetime import datetime

import pytest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

from talent_data.ota.automation.publisher import ProvenanceEvidence, publish_request
from talent_data.ota.automation.state import (
    FileJournal,
    JournalError,
    PublicationReceipt,
    RequestState,
)
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.release_package import load_package
from talent_data.ota.store import LocalDirectoryStore
from tests.unit.ota.publisher_testkit import ROOT, derive_test_key
from tests.unit.ota.test_automation_prepare import prepared_inputs  # noqa: F401
from tests.unit.ota.test_automation_provenance import exact_package, zip_tree  # noqa: F401
from tests.unit.ota.test_ota_production_release import (
    CATALOG,
    KEY_A,
    NOW,
    Owner,
    world,  # noqa: F401
)


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, check=True).stdout


@pytest.fixture
def env(prepared_inputs, world, tmp_path):  # noqa: F811
    request, runs, artifact, raw, package = prepared_inputs
    owner = Owner(world, tmp_path / "owner")
    shutil.rmtree(owner.package)
    shutil.copytree(package.root, owner.package)
    package = load_package(owner.package)
    remote = tmp_path / "main"
    git(ROOT, "clone", "--quiet", "--shared", str(ROOT), str(remote))
    git(remote, "checkout", "-B", "main")
    approval = remote / "docs/ota/releases" / f"{request.release_id}.md"
    shutil.copyfile(owner.approvals / approval.name, approval)
    git(remote, "add", str(approval))
    git(
        remote,
        "-c",
        "user.name=TEST",
        "-c",
        "user.email=test@example.invalid",
        "-c",
        "core.hooksPath=/dev/null",
        "commit",
        "-qm",
        "TEST approval",
    )
    repo = tmp_path / "repo"
    git(ROOT, "clone", "--quiet", "--shared", str(remote), str(repo))
    base = owner.helper.read_live(
        owner.helper.Target(store=owner.store, keyring_path=owner.keyring_path, now=NOW),
        request.game,
    )
    entry = next(x for x in base.catalog.releases if x.release_id == request.base_release_id)
    request = request.model_copy(update={"base_manifest_sha256": entry.manifest_sha256})
    journal = FileJournal(tmp_path / "journal.json")
    journal.initialize()
    receipt = PublicationReceipt.pending(request, run_id=10, attempt=1)

    class Signer:
        calls = 0

        def sign(self, data):
            self.calls += 1
            return derive_test_key(KEY_A).sign(data, ec.ECDSA(hashes.SHA256()))

    signer = Signer()
    evidence = ProvenanceEvidence(runs, artifact, raw)

    class Env:
        def run(self):
            return publish_request(
                self.receipt.request,
                package=self.package,
                repo=repo,
                store=self.store,
                keyring=owner.keyring,
                signer=self.signer,
                receipt=self.receipt,
                now=datetime.fromisoformat(NOW),
                journal=journal,
                provenance=lambda: self.evidence,
                key_id=self.key_id,
            )

    result = Env()
    result.package = package
    result.key_id = KEY_A
    result.store = LocalDirectoryStore(owner.store)
    result.evidence = evidence
    result.owner, result.signer, result.journal, result.receipt = owner, signer, journal, receipt
    result.remote, result.approval, result.repo = remote, approval, repo
    return result


def test_no_sign_or_write_when_approval_was_withdrawn(env):
    env.approval.unlink()
    git(env.remote, "add", "-u")
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
        "withdraw",
    )
    before = env.owner.snapshot()
    assert env.run().state is RequestState.BLOCKED
    assert env.signer.calls == 0
    assert before == env.owner.snapshot()


def test_provenance_failure_never_reaches_signer(env):
    runs = copy.deepcopy(env.evidence.run_metadata)
    runs["package"]["event"] = "pull_request"
    env.evidence = ProvenanceEvidence(
        runs, env.evidence.artifact_metadata, env.evidence.archive_bytes
    )
    before = env.owner.snapshot()
    assert env.run().state is RequestState.BLOCKED
    assert env.signer.calls == 0
    assert before == env.owner.snapshot()


def test_interrupted_upload_reuses_blobs(env, monkeypatch):
    puts = []
    original = env.store.put

    def failing(key, data, *, cache_control):
        if len(puts) == 3:
            raise OtaPublisherError("TEST interruption")
        puts.append(key)
        original(key, data, cache_control=cache_control)

    monkeypatch.setattr(env.store, "put", failing)
    assert env.run().state is RequestState.BLOCKED
    first = set(puts)
    assert env.signer.calls == 0

    def resumed(key, data, *, cache_control):
        assert key not in first
        original(key, data, cache_control=cache_control)

    monkeypatch.setattr(env.store, "put", resumed)
    assert env.run().state is RequestState.PUBLISHED_NOT_VERIFIED
    assert env.signer.calls == 1


def test_lost_put_response_reads_exact_payload(env, monkeypatch):
    original = env.store.put
    writes = []

    def landed(key, data, *, cache_control):
        original(key, data, cache_control=cache_control)
        if key == CATALOG:
            writes.append(key)
            raise OtaPublisherError("TEST lost PUT response")

    monkeypatch.setattr(env.store, "put", landed)
    result = env.run()
    assert result.state is RequestState.PUBLISHED_NOT_VERIFIED
    assert env.run() == result
    assert writes == [CATALOG]


def test_ambiguous_outcome_never_creates_next_payload(env, monkeypatch):
    original_put, original_get = env.store.put, env.store.get
    wrote = False

    def put(key, data, *, cache_control):
        nonlocal wrote
        original_put(key, data, cache_control=cache_control)
        if key == CATALOG:
            wrote = True

    def get(key):
        if wrote and key == CATALOG:
            raise OtaPublisherError("TEST unreadable")
        return original_get(key)

    monkeypatch.setattr(env.store, "put", put)
    monkeypatch.setattr(env.store, "get", get)
    result = env.run()
    assert result.state is RequestState.AMBIGUOUS
    retained = env.journal.load(result.request_digest)
    assert env.run() == result
    assert env.journal.load(result.request_digest) == retained
    assert env.signer.calls == 1


def test_old_request_never_reactivates_after_rollback(env):
    before = env.store.get(CATALOG)
    published = env.run()
    assert published.state is RequestState.PUBLISHED_NOT_VERIFIED
    # Replay receipt after an operator restored another live pointer: no catalog write.
    env.store.put(CATALOG, before, cache_control="public, max-age=300")
    assert env.run() == published
    assert env.store.get(CATALOG) == before
    assert env.signer.calls == 1


def test_missing_or_corrupt_journal_blocks_write(env):
    env.journal.path.write_bytes(b"{}")
    before = env.owner.snapshot()
    with pytest.raises(JournalError):
        env.run()
    assert env.signer.calls == 0
    assert before == env.owner.snapshot()


def test_withdrawn_approval_after_upload_never_reaches_secret_reader(env, monkeypatch):
    original = env.store.put
    withdrawn = False

    def revoke(key, data, *, cache_control):
        nonlocal withdrawn
        original(key, data, cache_control=cache_control)
        if not withdrawn:
            withdrawn = True
            env.approval.unlink()
            git(env.remote, "add", "-u")
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
                "withdraw",
            )

    monkeypatch.setattr(env.store, "put", revoke)
    result = env.run()
    assert result.state is RequestState.BLOCKED
    assert env.signer.calls == 0
    assert env.owner.live()[0] == 1


def test_accepting_stop_in_approval_never_signs(env):
    env.approval.write_text(
        env.approval.read_text().replace("- Accepted gates: G5, G7", "- Accepted gates: G5, G6, G7")
    )
    git(env.remote, "add", "-u")
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
        "invalid STOP approval",
    )
    before = env.owner.snapshot()
    assert env.run().state is RequestState.BLOCKED
    assert env.signer.calls == 0
    assert env.owner.snapshot() == before


def test_base_changes_after_upload_is_stale_without_signing(env, monkeypatch):
    original = env.store.put
    raced = False

    def race(key, data, *, cache_control):
        nonlocal raced
        original(key, data, cache_control=cache_control)
        if not raced:
            raced = True
            env.owner.compete(now=datetime.fromisoformat(NOW))

    monkeypatch.setattr(env.store, "put", race)
    assert env.run().state is RequestState.STALE
    assert env.signer.calls == 0


def test_write_started_is_read_back_after_runner_crash(env, monkeypatch):
    original = env.journal.save

    def interrupted(receipt, prepared=None):
        saved = original(receipt, prepared)
        if receipt.state is RequestState.WRITE_STARTED:
            raise JournalError("TEST runner crash after persistence")
        return saved

    monkeypatch.setattr(env.journal, "save", interrupted)
    with pytest.raises(JournalError):
        env.run()
    exact = env.journal.load(env.receipt.request_digest).prepared
    assert env.owner.live()[0] == 1
    monkeypatch.setattr(env.journal, "save", original)
    # First invocation diagnoses unchanged base; second safely retries the exact retained bytes.
    env.receipt = env.receipt.evolve(run_id=11, attempt=2)
    assert env.run().state is RequestState.PREPARED
    result = env.run()
    assert result.state is RequestState.PUBLISHED_NOT_VERIFIED
    assert (result.run_id, result.attempt) == (11, 2)
    assert env.journal.load(env.receipt.request_digest).prepared == exact
    assert env.signer.calls == 1


def test_journal_failure_after_signing_prohibits_put(env, monkeypatch):
    original = env.journal.save

    def fail(receipt, prepared=None):
        if receipt.state is RequestState.PREPARED:
            raise JournalError("TEST durability failure")
        return original(receipt, prepared)

    monkeypatch.setattr(env.journal, "save", fail)
    with pytest.raises(JournalError):
        env.run()
    assert env.owner.live()[0] == 1


def test_rejected_request_never_reads_ci_secrets(env):
    from talent_data.ota.automation.ci_signer import CiPemSigner
    from talent_data.ota.verify import TrustedKey

    reads = []
    env.owner.keyring["tf-prod-2026a"] = TrustedKey(
        "tf-prod-2026a", derive_test_key(KEY_A).public_key()
    )
    env.signer = CiPemSigner(env.owner.keyring, read_secret=lambda name: reads.append(name))
    env.key_id = "tf-prod-2026a"
    env.approval.write_text(
        env.approval.read_text().replace("- Accepted gates: G5, G7", "- Accepted gates: G5, G6, G7")
    )
    git(env.remote, "add", "-u")
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
        "STOP",
    )
    assert env.run().state is RequestState.BLOCKED
    assert reads == []


def test_approval_is_rechecked_after_durable_barrier(env, monkeypatch):
    original = env.journal.save

    def revoke(receipt, prepared=None):
        result = original(receipt, prepared)
        if receipt.state is RequestState.WRITE_STARTED:
            env.approval.unlink()
            git(env.remote, "add", "-u")
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
                "withdraw at barrier",
            )
        return result

    monkeypatch.setattr(env.journal, "save", revoke)
    assert env.run().state is RequestState.BLOCKED
    assert env.owner.live()[0] == 1


def test_same_build_different_staged_base_files_never_signs(env):
    from talent_data.ota.release_package import STAGING_BASE_MANIFEST_NAME, STAGING_FILES
    from talent_data.ota.verify import sha256_ref

    root = env.package.root
    base_path = root / STAGING_BASE_MANIFEST_NAME
    base = json.loads(base_path.read_bytes())
    base["files"][0]["sha256"] = "sha256:" + "f" * 64
    base_path.write_text(json.dumps(base))
    report_path = root / STAGING_FILES["report"]
    report = json.loads(report_path.read_bytes())
    report["facts"]["releases"][base["releaseId"]]["manifestSha256"] = sha256_ref(
        base_path.read_bytes()
    )
    report_path.write_text(json.dumps(report))
    index_path = root / "package.json"
    index = json.loads(index_path.read_bytes())
    for path in (base_path, report_path):
        data = path.read_bytes()
        index["files"][path.relative_to(root).as_posix()] = {
            "sha256": sha256_ref(data),
            "byteLength": len(data),
        }
    index_path.write_text(json.dumps(index))
    env.package = load_package(root)
    raw = zip_tree(root)
    digest = sha256_ref(raw)
    request = env.receipt.request
    request = request.model_copy(
        update={"artifact": request.artifact.model_copy(update={"archive_sha256": digest})}
    )
    env.receipt = PublicationReceipt.pending(request, run_id=10, attempt=1)
    env.evidence = ProvenanceEvidence(
        env.evidence.run_metadata, {**env.evidence.artifact_metadata, "digest": digest}, raw
    )
    before = env.owner.snapshot()
    assert env.run().state is RequestState.BLOCKED
    assert env.signer.calls == 0
    assert env.owner.snapshot() == before


def test_preflight_assessment_reuses_publisher_checks_without_writes(env, tmp_path):
    from talent_data.ota.automation.publisher import assess_request

    before = env.owner.snapshot()
    live, _ = assess_request(
        env.receipt.request,
        package=env.package,
        repo=env.repo,
        store=env.store,
        keyring=env.owner.keyring,
        now=datetime.fromisoformat(NOW),
        provenance=lambda: env.evidence,
        directory=tmp_path,
    )
    assert live.catalog.active_release_id == env.receipt.request.base_release_id
    assert env.signer.calls == 0
    assert env.owner.snapshot() == before
