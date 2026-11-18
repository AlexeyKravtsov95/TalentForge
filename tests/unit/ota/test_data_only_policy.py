"""Standing policy boundaries, real patch compatibility, and unforgeable gate entry."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from talent_data.allocation.engine import TalentAllocationEngine
from talent_data.ota.automation import data_only
from talent_data.ota.automation.prepare import PreparationBlocked
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.gates import load_approval_record
from talent_data.pipeline import read_document
from tests.unit.ota.publisher_testkit import ROOT

BASE = "1.60.1.70938"
TARGET = "1.60.1.70959"
CLASSES = ("warrior", "paladin", "hunter", "rogue", "priest", "shaman", "mage", "warlock", "druid")


@pytest.mark.parametrize("token", CLASSES)
def test_70938_saved_allocations_replay_on_70959_with_the_same_decisions(token: str) -> None:
    old = read_document(ROOT / f"data/normalized/forever/{BASE}/{token}.json")
    new = read_document(ROOT / f"data/normalized/forever/{TARGET}/{token}.json")
    assert data_only.allocation_shape(old) == data_only.allocation_shape(new)
    a, b = TalentAllocationEngine(old), TalentAllocationEngine(new)
    state = a.empty_state()
    for _ in range(a.point_budget):
        for node in a.tree.nodes:
            assert a.can_spend(state, node.id) == b.can_spend(state, node.id)
        allowed = next((n for n in a.tree.nodes if a.can_spend(state, n.id).allowed), None)
        if allowed is None:
            break
        before = state
        state = a.spend(state, allowed.id).state
        assert b.spend(before, allowed.id).state == state
        assert a.validate(state) == b.validate(state)
    assert a.spent_points(state) > 0
    assert b.validate(state).valid


def test_structure_comparison_keeps_requirements_ranks_ids_and_flags() -> None:
    old = read_document(ROOT / f"data/normalized/forever/{BASE}/shaman.json")
    wire = old.model_dump(mode="json", by_alias=True)
    original = data_only.allocation_shape(old)
    for field, value in (("maxRank", 6), ("flags", 4), ("id", 999), ("spellId", 999)):
        mutated = json.loads(json.dumps(wire))
        mutated["trees"][0]["nodes"][0][field] = value
        doc = type(old).model_validate_json(json.dumps(mutated))
        assert original != data_only.allocation_shape(doc)


def test_policy_unknown_fields_wrong_types_and_revocation_fail_closed(monkeypatch) -> None:
    policy = json.loads((ROOT / data_only.POLICY_PATH).read_bytes())
    for changed in (
        {**policy, "unexpected": 1},
        {**policy, "enabled": "true"},
        {**policy, "schemaVersion": True},
        {**policy, "enabled": False},
    ):
        monkeypatch.setattr(
            data_only, "git", lambda *args, changed=changed: json.dumps(changed).encode()
        )
        with pytest.raises((ValueError, PreparationBlocked)):
            data_only.policy_bytes(ROOT, "origin/main")


def test_standing_policy_record_cannot_be_used_as_a_manual_gate_override(tmp_path: Path) -> None:
    (tmp_path / f"{TARGET}-r1.md").write_text(data_only.MARKER + "\n")
    with pytest.raises(OtaPublisherError, match="independent policy proof"):
        load_approval_record(f"{TARGET}-r1", tmp_path)


@pytest.fixture
def policy_release(tmp_path):
    import shutil
    import subprocess
    from datetime import UTC, datetime

    from talent_data.ota.automation.requests import ArtifactPin, ReleaseRequest, RunPin
    from talent_data.ota.catalog import catalog_entry, next_catalog
    from talent_data.ota.gates import under_test_from_bytes
    from talent_data.ota.models import Channel
    from talent_data.ota.release import build_release
    from talent_data.ota.store import LocalDirectoryStore, upload_blobs
    from talent_data.ota.verify import sha256_ref

    def git(*args):
        return subprocess.check_output(["git", "-C", str(tmp_path), *args]).decode().strip()

    git("init", "-q")
    git("config", "user.email", "test@example.invalid")
    git("config", "user.name", "Test")
    policy = tmp_path / data_only.POLICY_PATH
    policy.parent.mkdir(parents=True)
    shutil.copyfile(ROOT / data_only.POLICY_PATH, policy)
    for build in (BASE, TARGET):
        for kind in ("normalized", "visuals", "tooltips", "localization"):
            relative = f"data/{kind}/forever/{build}"
            shutil.copytree(ROOT / relative, tmp_path / relative)
        git("add", ".")
        git("commit", "-qm", build)
    source = git("rev-parse", "HEAD")
    now = datetime(2026, 11, 18, tzinfo=UTC)
    old = build_release(tmp_path, source, "forever", BASE, 1, now, "test")
    new = build_release(tmp_path, source, "forever", TARGET, 1, now, "test")
    catalog = next_catalog(
        None,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=old.release_id,
        append=catalog_entry(old.manifest_bytes, old.manifest, now),
        issued_at=now,
    ).catalog
    store = LocalDirectoryStore(tmp_path / "store")
    upload_blobs(store, {sha256_ref(old.manifest_bytes): old.manifest_bytes, **old.blobs})
    pin = RunPin(workflow_path=".github/workflows/ci.yml", run_id=1, attempt=1, head_sha=source)
    request = ReleaseRequest(
        schema_version=1,
        repository="AlexeyKravtsov95/TalentForge",
        game="forever",
        build=TARGET,
        revision=1,
        release_id=new.release_id,
        source_commit=source,
        created_at="2026-11-18T00:00:00Z",
        manifest_sha256=sha256_ref(new.manifest_bytes),
        base_release_id=old.release_id,
        base_manifest_sha256=sha256_ref(old.manifest_bytes),
        staging_run=pin,
        package_run=pin,
        artifact=ArtifactPin(artifact_id=1, name="test", archive_sha256="sha256:" + "0" * 64),
    )
    return (
        tmp_path,
        source,
        request,
        under_test_from_bytes(new.manifest_bytes, tmp_path),
        catalog,
        store,
    )


def test_verified_data_only_request_accepts_no_per_release_owner_input(policy_release):
    repo, source, request, under_test, catalog, store = policy_release
    proof = data_only.verify_data_only(repo, source, request, under_test, catalog, store)
    assert proof.request == request
    assert proof.gate_approval().live_build_evidence is None
    assert "not a claim" in proof.gate_approval().policy_authorization_evidence
    assert data_only.record_matches(repo, source, request, proof.record)
    altered = request.model_copy(update={"manifest_sha256": "sha256:" + "f" * 64})
    assert not data_only.record_matches(repo, source, altered, proof.record)


def test_automatic_policy_rejects_code_changes_and_wrong_baseline_hash(policy_release, monkeypatch):
    repo, source, request, under_test, catalog, store = policy_release
    git = data_only.git
    monkeypatch.setattr(
        data_only,
        "git",
        lambda path, *args: b"src/changed.py\n" if args[0] == "diff" else git(path, *args),
    )
    with pytest.raises(PreparationBlocked, match="DATA_ONLY_SOURCE"):
        data_only.verify_data_only(repo, source, request, under_test, catalog, store)
    monkeypatch.setattr(data_only, "git", git)
    from talent_data.ota.models import FileRole, OtaReleaseManifest
    from talent_data.ota.store import blob_key
    from talent_data.ota.verify import parse_strict

    base = parse_strict(OtaReleaseManifest, store.get(blob_key(request.base_manifest_sha256)))
    pin = next(f for f in base.files if f.role is FileRole.TALENT_DATA)
    (store.root / blob_key(pin.sha256)).write_bytes(b"damaged")
    with pytest.raises(PreparationBlocked, match="DATA_ONLY_BASE_HASH"):
        data_only.verify_data_only(repo, source, request, under_test, catalog, store)


def test_automatic_authorization_creates_only_an_immutable_tag(policy_release):
    import subprocess
    from types import SimpleNamespace

    repo, source, request, under_test, catalog, store = policy_release
    subprocess.run(
        ["git", "-C", str(repo), "update-ref", "refs/remotes/origin/main", source], check=True
    )

    class Api:
        def __init__(self):
            self.writes = []
            self.tag = None

        def call(self, *args, **kwargs):
            return {"total_count": 1, "repositories": [{"full_name": request.repository}]}

        def repo(self, method, path, **kwargs):
            if method == "GET" and path.startswith("/git/ref/tags/"):
                return {"object": {"type": "tag", "sha": "a" * 40}} if self.tag else None
            if method == "GET" and path.startswith("/git/tags/"):
                return self.tag
            self.writes.append((method, path, kwargs["data"]))
            if method == "POST" and path == "/git/tags":
                data = kwargs["data"]
                self.tag = {
                    "tag": data["tag"],
                    "message": data["message"],
                    "object": {"type": "commit", "sha": source},
                }
                return {"sha": "a" * 40}
            assert method == "POST" and path == "/git/refs"
            assert kwargs["data"]["ref"] == f"refs/tags/ota-data-only/{request.release_id}"
            return {}

    api = Api()
    tag = data_only.commit_authorization(
        api,
        repo,
        request,
        SimpleNamespace(manifest_bytes=under_test.manifest_bytes),
        catalog,
        store,
    )
    assert tag == f"refs/tags/ota-data-only/{request.release_id}"
    assert [p for _, p, _ in api.writes] == ["/git/tags", "/git/refs"]
    data_only.commit_authorization(
        api,
        repo,
        request,
        SimpleNamespace(manifest_bytes=under_test.manifest_bytes),
        catalog,
        store,
    )
    assert len(api.writes) == 2  # rerun reads the same frozen request; it creates nothing


def test_publisher_rechecks_exact_policy_and_refuses_an_unproved_record(policy_release):
    import argparse
    import subprocess
    from datetime import UTC, datetime

    from talent_data.cli.ota import _enforce_gates_and_approval
    from talent_data.ota.automation.publisher import approval_directory
    from talent_data.ota.models import Channel

    repo, _source, request, under_test, catalog, store = policy_release
    receipt = data_only.approval_text(request, (repo / data_only.POLICY_PATH).read_bytes())
    record_path = repo / f"docs/ota/releases/{request.release_id}.md"
    record_path.parent.mkdir(parents=True)
    record_path.write_text(receipt)
    subprocess.run(["git", "-C", str(repo), "add", "docs"], check=True)
    subprocess.run(["git", "-C", str(repo), "commit", "-qm", "policy receipt"], check=True)
    main = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"]).decode().strip()
    proof = data_only.verify_data_only(repo, main, request, under_test, catalog, store)
    out = repo / "approvals"
    out.mkdir()
    with pytest.raises(OtaPublisherError, match="unverified or withdrawn"):
        approval_directory(request, repo, out, main_ref=main)
    approval_directory(request, repo, out, main_ref=main, data_only=proof)
    args = argparse.Namespace(repo=repo, releases_docs=out, main_ref=main, verified_data_only=proof)
    assert (
        _enforce_gates_and_approval(
            args,
            under_test,
            store,
            catalog,
            datetime(2026, 11, 18, tzinfo=UTC),
            channel=Channel.PRODUCTION,
        )
        is None
    )
    altered = request.model_copy(update={"manifest_sha256": "sha256:" + "f" * 64})
    with pytest.raises(OtaPublisherError, match="unverified or withdrawn"):
        approval_directory(altered, repo, out, main_ref=main, data_only=proof)


def test_tagged_request_is_discovered_and_uses_exact_source_ci(policy_release):
    import subprocess

    from talent_data.ota.automation.authorization import (
        committed_requests,
        request_bytes,
        request_change_commit,
    )
    from talent_data.ota.verify import sha256_ref

    repo, source, request, under_test, catalog, store = policy_release
    subprocess.run(
        ["git", "-C", str(repo), "update-ref", "refs/remotes/origin/main", source], check=True
    )
    record = data_only.AutomaticRequest(
        schema_version=1,
        kind="ota-data-only-request",
        request=request,
        policy_sha256=sha256_ref((repo / data_only.POLICY_PATH).read_bytes()),
    )
    subprocess.run(
        [
            "git",
            "-C",
            str(repo),
            "tag",
            "-a",
            "ota-data-only/" + request.release_id,
            source,
            "-m",
            record.model_dump_json(by_alias=True),
        ],
        check=True,
    )
    assert committed_requests(repo) == (request,)
    assert request_change_commit(repo, request) == source
    assert json.loads(request_bytes(repo, request))["manifestSha256"] == request.manifest_sha256
    receipt = data_only.standing_record(repo, "origin/main", request)
    assert receipt is not None and data_only.MARKER.encode() in receipt
    proof = data_only.verify_data_only(repo, source, request, under_test, catalog, store)
    from talent_data.ota.automation.publisher import approval_directory

    out = repo / "tagged-approvals"
    out.mkdir()
    assert approval_directory(request, repo, out, main_ref=source, data_only=proof) == out
