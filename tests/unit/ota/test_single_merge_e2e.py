"""One MR, one frozen package, ordinary owner merge, one TEST catalog PUT and all files."""

from talent_data.ota.automation import ci_validation
from talent_data.ota.automation.public_verify import verify_publication
from talent_data.ota.automation.state import RequestState, request_digest
from talent_data.ota.models import Channel
from talent_data.ota.store import blob_key, verify_live_envelope
from tests.unit.ota.single_merge_e2e_kit import BUILD, CATALOG, NOW, World
from tests.unit.ota.test_ota_preflight import KEYRING


def test_one_mr_exact_package_to_all_403_public_files(tmp_path, monkeypatch):
    world = World(tmp_path, monkeypatch)
    package = world.prepare_and_ready()
    assert world.api.pull["number"] == 141 and world.api.pull["draft"] is False
    assert package.index.file_count == 403
    assert "approval-record.template.md" not in package.index.files
    assert "обычный Merge" in package.attachment("report/merge-terms.md").decode()
    before = package.manifest_bytes
    assert len(world.api.producers[".github/workflows/ota-staging-verify.yml"]) == 1
    assert len(world.api.producers[".github/workflows/ota-release-package.yml"]) == 1
    assert not any(
        path == "/pulls" or path.endswith("/merge")
        for _, path, _ in world.api.posts + world.writer.writes
    )
    world.owner_merge()
    decision = ci_validation.classify_changes(
        world.api, world.repo, head_sha=world.merge, event="push"
    )
    assert decision.mode == "validated-merge"
    ci_validation.validate_merge_decision(world.api, world.repo, decision)
    code, status = world.publish()
    assert code == 0 and status.outcome == "VERIFIED"
    receipt = world.journal.load(request_digest(world.request)).receipt
    assert receipt.state is RequestState.VERIFIED
    assert world.store.catalog_puts == 1
    catalog = verify_live_envelope(
        world.store.get(CATALOG), Channel.PRODUCTION, keyring=KEYRING, game="forever", now=NOW
    )
    assert catalog.active_release_id == BUILD + "-r1"
    assert world.store.get(blob_key(world.request.manifest_sha256)) == before
    world.public_gets.clear()
    proof = verify_publication(
        world.client, origin="https://ota.mythweld.com", keyring=KEYRING, game="forever", now=NOW
    )
    assert proof.ok and proof.active_release_id == world.request.release_id
    assert all(blob_key(f.sha256) in world.public_gets for f in package.manifest.files)
    assert world.secrets.count("OTA_PRODUCTION_PRIVATE_KEY_PEM") == 1
    assert len(world.writers) == 1


def test_lost_catalog_put_response_is_read_back_without_second_put(tmp_path, monkeypatch):
    world = World(tmp_path, monkeypatch)
    world.prepare_and_ready()
    world.owner_merge()
    world.store.unknown = True
    code, status = world.publish()
    assert code == 0 and status.outcome == "VERIFIED"
    assert world.store.catalog_puts == 1
    before = (len(world.writers), world.secrets.count("OTA_PRODUCTION_PRIVATE_KEY_PEM"))
    code, status = world.publish()
    assert code == 0 and status.outcome in {"VERIFIED", "NO_ACTION"}
    assert world.store.catalog_puts == 1
    assert (len(world.writers), world.secrets.count("OTA_PRODUCTION_PRIVATE_KEY_PEM")) == before


def test_revocation_after_upload_blocks_signing_and_catalog_activation(tmp_path, monkeypatch):
    from talent_data.ota.automation.merge_approval import parse_merge_record, render_merge_record
    from tests.unit.ota.single_merge_e2e_kit import commit_tree
    from tests.unit.test_ota_prepare import git

    world = World(tmp_path, monkeypatch)
    world.prepare_and_ready()
    world.owner_merge()

    def withdraw():
        path = f"docs/ota/releases/{world.request.release_id}.md"
        record = parse_merge_record((world.repo / path).read_text())
        (world.repo / path).write_text(
            render_merge_record(record.model_copy(update={"revoked": True}))
        )
        git(world.repo, "add", path)
        changed = commit_tree(
            world.repo, git(world.repo, "write-tree"), [world.merge], "TEST owner revocation"
        )
        git(world.repo, "push", "--quiet", "origin", changed + ":refs/heads/main")
        git(world.repo, "fetch", "--quiet", "origin", "main")

    world.store.hook = withdraw
    code, status = world.publish()
    assert code == 1 and status.outcome == "BLOCKED"
    assert world.store.catalog_puts == 0
    assert "OTA_PRODUCTION_PRIVATE_KEY_PEM" not in world.secrets


def test_expired_retained_package_cannot_use_cached_bytes_for_a_writer(tmp_path, monkeypatch):
    world = World(tmp_path, monkeypatch)
    world.prepare_and_ready()
    world.owner_merge()
    world.api.artifacts[90]["expired"] = True
    code, status = world.publish()
    assert code == 1 and status.outcome == "BLOCKED"
    assert not world.writers and not world.secrets
    assert world.store.catalog_puts == 0
