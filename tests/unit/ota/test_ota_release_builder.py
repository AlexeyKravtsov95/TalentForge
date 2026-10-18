"""The release builder (M5.OTA.2): committed data only, deterministic, reproducible.

The two committed fixture releases are the ground truth: building them from their
provenance commit with the fixture's ``createdAt`` and validation note must reproduce
``docs/ota/fixtures/v1/releases/*.release.json`` byte for byte - the same property the
CLI smoke of the brief's Definition of Done checks.
"""

from __future__ import annotations

import subprocess
from datetime import timedelta
from pathlib import Path

import pytest

from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import FileRole
from talent_data.ota.release import (
    build_release,
    commit_snapshot,
    role_for_path,
    serialize_manifest,
)
from tests.unit.ota.publisher_testkit import (
    BUILD_A,
    BUILD_B,
    COMMIT,
    CREATED_AT,
    FIXTURES,
    GAME,
    ROOT,
    VALIDATION_NOTE,
)


@pytest.fixture(scope="module")
def release_a():
    return build_release(ROOT, COMMIT, GAME, BUILD_A, 1, CREATED_AT, VALIDATION_NOTE)


@pytest.mark.parametrize(
    ("build", "release_id"),
    [(BUILD_A, f"{BUILD_A}-r1"), (BUILD_B, f"{BUILD_B}-r1")],
)
def test_build_release_reproduces_the_committed_fixture(build: str, release_id: str) -> None:
    artifact = build_release(ROOT, COMMIT, GAME, build, 1, CREATED_AT, VALIDATION_NOTE)
    fixture = FIXTURES / "releases" / f"{release_id}.release.json"
    assert artifact.manifest_bytes == fixture.read_bytes(), f"{release_id} drifted"


def test_build_release_is_deterministic(release_a) -> None:
    again = build_release(ROOT, COMMIT, GAME, BUILD_A, 1, CREATED_AT, VALIDATION_NOTE)
    assert again.manifest_bytes == release_a.manifest_bytes
    assert again.blobs == release_a.blobs
    assert again.snapshot == release_a.snapshot


def test_different_inputs_produce_different_manifests(release_a) -> None:
    other = build_release(
        ROOT,
        COMMIT,
        GAME,
        BUILD_A,
        2,
        CREATED_AT + timedelta(minutes=1),
        VALIDATION_NOTE,
    )
    assert other.manifest_bytes != release_a.manifest_bytes
    assert other.manifest.release_id == f"{BUILD_A}-r2"


def test_manifest_serialization_is_parseable_and_ordered(release_a) -> None:
    from talent_data.ota.models import OtaReleaseManifest
    from talent_data.ota.verify import parse_strict

    manifest = parse_strict(OtaReleaseManifest, release_a.manifest_bytes)
    paths = [f.path for f in manifest.files]
    assert paths == sorted(paths, key=lambda p: p.encode("utf-8"))
    assert serialize_manifest(manifest) == release_a.manifest_bytes


def test_blobs_are_content_addressed_and_shared_between_builds(release_a) -> None:
    from talent_data.ota.verify import sha256_ref

    for sha_ref, data in release_a.blobs.items():
        assert sha256_ref(data) == sha_ref
    # icons are shared content: B's blob set overlaps A's
    release_b = build_release(ROOT, COMMIT, GAME, BUILD_B, 1, CREATED_AT, VALIDATION_NOTE)
    assert release_b.blobs.keys() & release_a.blobs.keys()


def test_role_for_path_covers_the_layout() -> None:
    assert role_for_path(f"dataset/{GAME}/{BUILD_A}/manifest.json") is FileRole.DATASET_MANIFEST
    assert role_for_path(f"dataset/{GAME}/{BUILD_A}/mage.json") is FileRole.TALENT_DATA
    assert role_for_path(f"visuals/{GAME}/{BUILD_A}/icons.json") is FileRole.ICON_REGISTRY
    assert role_for_path(f"visuals/{GAME}/{BUILD_A}/icons/123.png") is FileRole.ICON_IMAGE
    assert role_for_path(f"visuals/{GAME}/{BUILD_A}/mage.json") is FileRole.VISUALS
    assert role_for_path(f"tooltips/{GAME}/{BUILD_A}/mage.json") is FileRole.TOOLTIPS
    assert role_for_path(f"localization/{GAME}/{BUILD_A}/ruRU/mage.json") is FileRole.LOCALIZATION


def test_unknown_commit_fails_closed() -> None:
    with pytest.raises(OtaPublisherError, match="does not exist"):
        commit_snapshot(ROOT, "0" * 40, GAME, BUILD_A)


def test_not_a_commit_id_is_refused() -> None:
    with pytest.raises(OtaPublisherError, match="not a commit id"):
        commit_snapshot(ROOT, "deadbeef", GAME, BUILD_A)


# --------------------------------------------------------------------------- scratch repo


def _git(repo: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, text=True, check=True
    )
    return result.stdout.strip()


def test_snapshot_reads_the_commit_not_the_working_tree(tmp_path: Path) -> None:
    """Uncommitted changes are invisible: the release source is the committed tree."""
    repo = tmp_path / "repo"
    data_dir = repo / "data" / "normalized" / GAME / BUILD_A
    data_dir.mkdir(parents=True)
    (data_dir / "mage.json").write_bytes(b'{"committed": true}\n')
    _git(repo, "init", "-b", "main")
    _git(repo, "config", "user.email", "test@example.invalid")
    _git(repo, "config", "user.name", "test")
    _git(repo, "add", "-A")
    _git(repo, "commit", "-m", "committed data")
    commit = _git(repo, "rev-parse", "HEAD")

    committed = commit_snapshot(repo, commit, GAME, BUILD_A)
    assert committed[f"dataset/{GAME}/{BUILD_A}/mage.json"] == b'{"committed": true}\n'

    (data_dir / "mage.json").write_bytes(b'{"uncommitted": true}\n')  # dirty working tree
    (data_dir / "extra.json").write_bytes(b"{}")  # untracked file
    still_committed = commit_snapshot(repo, commit, GAME, BUILD_A)
    assert still_committed == committed
