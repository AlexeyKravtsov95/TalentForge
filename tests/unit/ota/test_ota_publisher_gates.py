"""Publisher gates G1-G8 (M5.OTA.2, test plan §5).

Every gate has a failing case; STOP gates cannot be passed by any mechanism - the
test for G6 uses the wire-valid fixture content of ``release-open-union-node-type``
(engine-unsupported node type inside the open union of talent-data.v1) and tries an
approval record and hostile environment variables: no code path exists that changes
the verdict.
"""

from __future__ import annotations

import subprocess
from datetime import timedelta
from pathlib import Path

import pytest

from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.gates import (
    Gate,
    GateStatus,
    ReleaseUnderTest,
    any_stop,
    gate_g1_source,
    gate_g4_same_build_identity,
    gate_g5_new_warnings,
    gate_g6_unsupported_mechanics,
    gate_g7_live_build_evidence,
    load_approval_record,
    validate_release,
)
from talent_data.ota.models import Channel
from talent_data.ota.release import (
    ReleaseArtifact,
    build_release,
    release_files,
    serialize_manifest,
)
from talent_data.ota.store import LocalDirectoryStore, upload_blobs
from tests.unit.ota.publisher_testkit import (
    BUILD_A,
    BUILD_B,
    COMMIT,
    CREATED_AT,
    FIXTURES,
    GAME,
    NOW,
    ROOT,
    VALIDATION_NOTE,
    write_approval_record,
)

PALADIN = f"dataset/{GAME}/{BUILD_B}/paladin.json"
DATASET_A = f"dataset/{GAME}/{BUILD_A}/manifest.json"
VALIDITY = timedelta(days=45)


@pytest.fixture(scope="module")
def under_test_b(tmp_path_factory: pytest.TempPathFactory) -> ReleaseUnderTest:
    """Release B loaded the way `ota validate` loads it (rebuild at provenance)."""
    artifact = build_release(ROOT, COMMIT, GAME, BUILD_B, 1, CREATED_AT, VALIDATION_NOTE)
    manifest_path = tmp_path_factory.mktemp("manifests") / "b.release.json"
    manifest_path.write_bytes(artifact.manifest_bytes)
    return ReleaseUnderTest(
        manifest_path=manifest_path,
        manifest_bytes=artifact.manifest_bytes,
        manifest=artifact.manifest,
        rebuild=artifact,
    )


def with_snapshot(under_test: ReleaseUnderTest, snapshot: dict[str, bytes]) -> ReleaseUnderTest:
    """The same release under test with an altered snapshot: a gate-level view of a
    hypothetical content change (G3/G8 still see the original bytes)."""
    changed = ReleaseArtifact(
        manifest=under_test.rebuild.manifest,
        manifest_bytes=under_test.rebuild.manifest_bytes,
        snapshot=snapshot,
        blobs={f.sha256: snapshot[f.path] for f in under_test.rebuild.manifest.files},
    )
    return ReleaseUnderTest(
        manifest_path=under_test.manifest_path,
        manifest_bytes=under_test.manifest_bytes,
        manifest=under_test.manifest,
        rebuild=changed,
    )


def open_union_under_test(under_test_b: ReleaseUnderTest) -> ReleaseUnderTest:
    """Release B with the wire-valid fixture content of release-open-union-node-type."""
    blob = (FIXTURES / "blobs" / "release-open-union-node-type--paladin.json").read_bytes()
    return with_snapshot(under_test_b, {**under_test_b.rebuild.snapshot, PALADIN: blob})


# --------------------------------------------------------------------------- G1: source


def _git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, text=True, check=True
    ).stdout.strip()


@pytest.fixture()
def scratch_repo(tmp_path: Path) -> tuple[Path, str, str]:
    """A repo with one commit on origin/main and one on an unmerged branch."""
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init", "-b", "main")
    _git(repo, "config", "user.email", "t@e.invalid")
    _git(repo, "config", "user.name", "t")
    _git(repo, "commit", "--allow-empty", "-m", "main commit")
    main = _git(repo, "rev-parse", "HEAD")
    _git(repo, "update-ref", "refs/remotes/origin/main", main)
    _git(repo, "switch", "-c", "feature")
    _git(repo, "commit", "--allow-empty", "-m", "unmerged commit")
    side = _git(repo, "rev-parse", "HEAD")
    return repo, main, side


def _with_commit(under_test: ReleaseUnderTest, commit: str) -> ReleaseUnderTest:
    manifest = under_test.manifest.model_copy(
        update={
            "provenance": under_test.manifest.provenance.model_copy(
                update={"repository_commit": commit}
            )
        }
    )
    return ReleaseUnderTest(
        manifest_path=under_test.manifest_path,
        manifest_bytes=under_test.manifest_bytes,
        manifest=manifest,
        rebuild=under_test.rebuild,
    )


def test_g1_passes_for_a_commit_on_origin_main(
    under_test_b: ReleaseUnderTest, scratch_repo: tuple[Path, str, str]
) -> None:
    repo, main, _ = scratch_repo
    result = gate_g1_source(_with_commit(under_test_b, main), repo, main_ref="origin/main")
    assert result.status is GateStatus.PASS


def test_g1_stops_for_a_commit_not_on_origin_main(
    under_test_b: ReleaseUnderTest, scratch_repo: tuple[Path, str, str]
) -> None:
    repo, _, side = scratch_repo
    result = gate_g1_source(_with_commit(under_test_b, side), repo)
    assert result.status is GateStatus.STOP
    assert "not on origin/main" in result.detail


def test_g1_stops_when_origin_main_is_missing(
    under_test_b: ReleaseUnderTest, scratch_repo: tuple[Path, str, str]
) -> None:
    repo, main, _ = scratch_repo
    result = gate_g1_source(_with_commit(under_test_b, main), repo, main_ref="origin/other")
    assert result.status is GateStatus.STOP


# ------------------------------------------------- G6: the STOP without override


@pytest.mark.parametrize("channel", [Channel.PRODUCTION, Channel.STAGING])
def test_g6_stops_the_open_union_fixture_whatever_the_channel(
    under_test_b: ReleaseUnderTest, channel: Channel
) -> None:
    result = gate_g6_unsupported_mechanics(open_union_under_test(under_test_b), channel=channel)
    assert result.status is GateStatus.STOP
    assert "UNSUPPORTED_NODE_TYPE" in result.detail


def test_g6_has_no_override_parameter() -> None:
    import inspect

    parameters = inspect.signature(gate_g6_unsupported_mechanics).parameters
    assert list(parameters) == ["under_test", "channel"]
    # and its engine has none either (restated from M5.OTA.1: this gate is the
    # publisher's load-bearing wall)
    from talent_data.ota.mechanics import new_unsupported_mechanics

    assert list(inspect.signature(new_unsupported_mechanics).parameters) == [
        "documents",
        "baseline",
    ]


def test_g6_ignores_hostile_environment_variables(under_test_b: ReleaseUnderTest) -> None:
    with pytest.MonkeyPatch.context() as mp:
        for name in (
            "OTA_ALLOW_UNSUPPORTED_MECHANICS",
            "OTA_SKIP_G6",
            "OTA_PUBLISH_ANYWAY",
            "TF_APPROVE_ALL",
        ):
            mp.setenv(name, "1")
        result = gate_g6_unsupported_mechanics(
            open_union_under_test(under_test_b), channel=Channel.PRODUCTION
        )
    assert result.status is GateStatus.STOP


def test_g6_cannot_be_approved_not_even_in_an_approval_record(
    under_test_b: ReleaseUnderTest, tmp_path: Path
) -> None:
    write_approval_record(tmp_path, f"{BUILD_B}-r1", accepted="G5, G6")
    with pytest.raises(OtaPublisherError, match="STOP gate cannot be approved"):
        load_approval_record(f"{BUILD_B}-r1", tmp_path)


def test_g6_stops_a_level_without_a_baseline_for_production(
    under_test_b: ReleaseUnderTest, tmp_path: Path
) -> None:
    level2 = ReleaseUnderTest(
        manifest_path=under_test_b.manifest_path,
        manifest_bytes=under_test_b.manifest_bytes,
        manifest=under_test_b.manifest.model_copy(update={"min_data_level": 2}),
        rebuild=under_test_b.rebuild,
    )
    stopped = gate_g6_unsupported_mechanics(level2, channel=Channel.PRODUCTION)
    assert stopped.status is GateStatus.STOP
    assert "no frozen baseline" in stopped.detail
    # staging may carry it while the implementing app is being developed...
    allowed = gate_g6_unsupported_mechanics(level2, channel=Channel.STAGING)
    assert allowed.status is GateStatus.PASS
    # ...but the full production validation still stops, G6 among the STOPs
    # (G3 stops too: the manifest's identity no longer matches its own rebuild)
    results = validate_release(
        level2,
        repo_root=ROOT,
        store=LocalDirectoryStore(tmp_path),
        live=None,
        channel=Channel.PRODUCTION,
        now=NOW,
    )
    assert any_stop(results) is not None
    assert any(r.stopped and r.gate is Gate.G6 for r in results)


def test_g6_passes_for_the_committed_data(under_test_b: ReleaseUnderTest) -> None:
    result = gate_g6_unsupported_mechanics(under_test_b, channel=Channel.PRODUCTION)
    assert result.status is GateStatus.PASS


# ---------------------------------------------------- G4: same-build identity


def _published_a(tmp_path: Path) -> LocalDirectoryStore:
    store = LocalDirectoryStore(tmp_path)
    artifact = build_release(ROOT, COMMIT, GAME, BUILD_A, 1, CREATED_AT, VALIDATION_NOTE)
    blobs = dict(artifact.blobs)
    sha, data = artifact.manifest_blob
    blobs[sha] = data
    upload_blobs(store, blobs)
    return store


def test_g4_stops_changed_talent_data_of_a_published_build(
    under_test_b: ReleaseUnderTest, tmp_path: Path
) -> None:
    from talent_data.ota.catalog import catalog_entry
    from talent_data.ota.models import OtaCatalog

    store = _published_a(tmp_path)
    artifact = build_release(ROOT, COMMIT, GAME, BUILD_A, 1, CREATED_AT, VALIDATION_NOTE)
    entry = catalog_entry(artifact.manifest_bytes, artifact.manifest, published_at=NOW)
    live = OtaCatalog(
        schema_version=1,
        game=GAME,
        channel=Channel.PRODUCTION,
        sequence=1,
        issued_at=NOW,
        expires_at=NOW + VALIDITY,
        active_release_id=entry.release_id,
        releases=[entry],
    )

    # a hypothetical r2 of build A whose dataset manifest claims different talent data
    original = artifact.snapshot[DATASET_A]
    first_hash = original.split(b'"contentHash": "')[1][:64]
    tampered = original.replace(first_hash[:8], b"deadbeef", 1)
    r2_files = release_files({**artifact.snapshot, DATASET_A: tampered})
    r2_manifest = artifact.manifest.model_copy(
        update={
            "release_id": f"{BUILD_A}-r2",
            "files": r2_files,
            "total_byte_length": sum(f.byte_length for f in r2_files),
        }
    )
    snapshot = {**artifact.snapshot, DATASET_A: tampered}
    r2 = ReleaseUnderTest(
        manifest_path=tmp_path / "r2.json",
        manifest_bytes=serialize_manifest(r2_manifest),
        manifest=r2_manifest,
        rebuild=ReleaseArtifact(
            manifest=r2_manifest,
            manifest_bytes=serialize_manifest(r2_manifest),
            snapshot=snapshot,
            blobs={f.sha256: snapshot[f.path] for f in r2_files},
        ),
    )
    result = gate_g4_same_build_identity(r2, live, store, bundled_root=tmp_path)
    assert result.status is GateStatus.STOP
    assert "differs from published" in result.detail


def test_g4_passes_without_a_published_or_bundled_copy(
    under_test_b: ReleaseUnderTest, tmp_path: Path
) -> None:
    result = gate_g4_same_build_identity(
        under_test_b, None, LocalDirectoryStore(tmp_path), bundled_root=tmp_path
    )
    assert result.status is GateStatus.PASS
    assert "no published or bundled dataset" in result.detail


# --------------------------------------------------------------------------- G5: new warnings


def grow_warnings(under_test: ReleaseUnderTest) -> ReleaseUnderTest:
    """Double every warningsByCode count in the snapshot's dataset manifest."""
    import json

    from talent_data.models.manifest import DatasetManifest

    path = f"dataset/{under_test.manifest.game}/{under_test.manifest.client_build}/manifest.json"
    dataset = DatasetManifest.model_validate_json(under_test.rebuild.snapshot[path])
    doubled = dataset.model_copy(
        update={
            "classes": [
                entry.model_copy(
                    update={
                        "validation": entry.validation.model_copy(
                            update={
                                "warnings_by_code": {
                                    code: 2 * count
                                    for code, count in entry.validation.warnings_by_code.items()
                                }
                            }
                        )
                    }
                )
                for entry in dataset.classes
            ]
        }
    )
    text = (
        json.dumps(doubled.model_dump(mode="json", by_alias=True), indent=2, ensure_ascii=False)
        + "\n"
    )
    return with_snapshot(under_test, {**under_test.rebuild.snapshot, path: text.encode("utf-8")})


def _live_catalog_with_a(tmp_path: Path) -> tuple[LocalDirectoryStore, object]:
    from talent_data.ota.catalog import catalog_entry
    from talent_data.ota.models import OtaCatalog

    store = _published_a(tmp_path)
    artifact = build_release(ROOT, COMMIT, GAME, BUILD_A, 1, CREATED_AT, VALIDATION_NOTE)
    entry = catalog_entry(artifact.manifest_bytes, artifact.manifest, published_at=NOW)
    return store, OtaCatalog(
        schema_version=1,
        game=GAME,
        channel=Channel.PRODUCTION,
        sequence=1,
        issued_at=NOW,
        expires_at=NOW + VALIDITY,
        active_release_id=entry.release_id,
        releases=[entry],
    )


def test_g5_flags_grown_warnings_and_never_covers_g6(
    under_test_b: ReleaseUnderTest, tmp_path: Path
) -> None:
    store, live = _live_catalog_with_a(tmp_path)
    g6 = gate_g6_unsupported_mechanics(under_test_b, channel=Channel.PRODUCTION)
    assert g6.status is GateStatus.PASS

    flagged = gate_g5_new_warnings(grow_warnings(under_test_b), live, store, g6)
    assert flagged.status is GateStatus.FLAG
    assert "needs an approval record" in flagged.detail

    # a warning that reflects an engine-unsupported mechanic is G6's, never G5's:
    # when G6 stopped, G5 is not evaluated
    stopped_g6 = gate_g6_unsupported_mechanics(
        open_union_under_test(under_test_b), channel=Channel.PRODUCTION
    )
    silenced = gate_g5_new_warnings(
        grow_warnings(open_union_under_test(under_test_b)), live, store, stopped_g6
    )
    assert silenced.status is GateStatus.PASS
    assert "G6 stopped" in silenced.detail


def test_g5_passes_without_a_live_catalog(under_test_b: ReleaseUnderTest, tmp_path: Path) -> None:
    g6 = gate_g6_unsupported_mechanics(under_test_b, channel=Channel.PRODUCTION)
    result = gate_g5_new_warnings(under_test_b, None, LocalDirectoryStore(tmp_path), g6)
    assert result.status is GateStatus.PASS
    assert "no active production release" in result.detail


# --------------------------------------------------------------------------- G7 + approval records


def test_g7_flags_production_without_evidence_and_passes_with_it(
    under_test_b: ReleaseUnderTest, tmp_path: Path
) -> None:
    flagged = gate_g7_live_build_evidence(under_test_b, channel=Channel.PRODUCTION, approval=None)
    assert flagged.status is GateStatus.FLAG

    write_approval_record(tmp_path, f"{BUILD_B}-r1")
    record = load_approval_record(f"{BUILD_B}-r1", tmp_path)
    passed = gate_g7_live_build_evidence(under_test_b, channel=Channel.PRODUCTION, approval=record)
    assert passed.status is GateStatus.PASS

    staging = gate_g7_live_build_evidence(under_test_b, channel=Channel.STAGING, approval=None)
    assert staging.status is GateStatus.PASS


def test_approval_record_rules(tmp_path: Path) -> None:
    release = f"{BUILD_B}-r1"
    assert load_approval_record(release, tmp_path) is None  # no record file

    write_approval_record(tmp_path, release, accepted="G5")
    assert load_approval_record(release, tmp_path).accepted_gates == {Gate.G5}

    # a record whose Release line names another id is an error, not a pass
    mismatched = tmp_path / f"{release}-r2.md"
    mismatched.write_text(f"- Release: {BUILD_A}-r1\n- Accepted gates: G5\n", encoding="utf-8")
    with pytest.raises(OtaPublisherError, match="names release"):
        load_approval_record(f"{release}-r2", tmp_path)

    # G7 acceptance without evidence is an error
    empty = tmp_path / f"{BUILD_B}-r3.md"
    empty.write_text(f"- Release: {BUILD_B}-r3\n- Accepted gates: G7\n", encoding="utf-8")
    with pytest.raises(OtaPublisherError, match="Live-build evidence"):
        load_approval_record(f"{BUILD_B}-r3", tmp_path)


# --------------------------------------------------------------------------- full validation


def test_full_validation_of_release_b(under_test_b: ReleaseUnderTest, tmp_path: Path) -> None:
    store = LocalDirectoryStore(tmp_path)
    results = validate_release(
        under_test_b,
        repo_root=ROOT,
        store=store,
        live=None,
        channel=Channel.PRODUCTION,
        now=NOW,
    )
    statuses = {r.gate: r.status for r in results}
    assert statuses[Gate.G1] is GateStatus.PASS
    assert statuses[Gate.G2] is GateStatus.PASS
    assert statuses[Gate.G3] is GateStatus.PASS
    assert statuses[Gate.G4] is GateStatus.PASS
    assert statuses[Gate.G5] is GateStatus.PASS
    assert statuses[Gate.G6] is GateStatus.PASS
    assert statuses[Gate.G7] is GateStatus.FLAG  # no approval record yet
    assert statuses[Gate.G8] is GateStatus.PASS

    docs = tmp_path / "approvals"
    write_approval_record(docs, f"{BUILD_B}-r1")
    with_approval = validate_release(
        under_test_b,
        repo_root=ROOT,
        store=store,
        live=None,
        channel=Channel.PRODUCTION,
        now=NOW,
        approval=load_approval_record(f"{BUILD_B}-r1", docs),
    )
    assert all(r.status is GateStatus.PASS for r in with_approval)


def test_validation_stops_on_a_tampered_manifest(
    under_test_b: ReleaseUnderTest, tmp_path: Path
) -> None:
    tampered = ReleaseUnderTest(
        manifest_path=under_test_b.manifest_path,
        manifest_bytes=under_test_b.manifest_bytes + b" ",
        manifest=under_test_b.manifest,
        rebuild=under_test_b.rebuild,
    )
    results = validate_release(
        tampered,
        repo_root=ROOT,
        store=LocalDirectoryStore(tmp_path),
        live=None,
        channel=Channel.PRODUCTION,
        now=NOW,
    )
    stopped = any_stop(results)
    assert stopped is not None
    assert stopped.gate in (Gate.G3, Gate.G8)
