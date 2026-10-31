"""Provenance uses real packages; only GitHub's read-only API facts are synthetic."""

import copy
import io
import json
import stat
import zipfile
from pathlib import Path

import pytest

from talent_data.ota.automation import provenance as p
from talent_data.ota.automation.requests import parse_request
from talent_data.ota.release_package import load_package
from talent_data.ota.verify import sha256_ref
from tests.unit.ota import release_package_testkit as kit
from tests.unit.ota.publisher_testkit import COMMIT
from tests.unit.ota.test_automation_requests import request_wire


def zip_tree(root: Path) -> bytes:
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(root.rglob("*")):
            if file.is_file():
                archive.writestr(file.relative_to(root).as_posix(), file.read_bytes())
    return out.getvalue()


@pytest.fixture(scope="module")
def exact_package(tmp_path_factory: pytest.TempPathFactory):
    root = tmp_path_factory.mktemp("automation-package")
    # The old helper's arbitrary staging head is inappropriate for trusted-main tests.
    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(kit, "STAGING_HEAD", COMMIT)
        assert (
            kit.build_package(
                root / "out",
                root / "scratch",
                "--run-url",
                f"https://github.com/{kit.REPOSITORY}/actions/runs/9000",
            )
            == 0
        )
    index_path = root / "out" / kit.RELEASE / "package.json"
    index = json.loads(index_path.read_bytes())
    # Model a historical trusted-main run, independently of this unmerged test branch.
    index["toolingCommit"] = COMMIT
    index_path.write_text(json.dumps(index))
    package = load_package(root / "out" / kit.RELEASE)
    raw = zip_tree(package.root)
    wire = request_wire()
    wire["build"] = package.index.client_build
    wire["releaseId"] = package.index.release_id
    wire["sourceCommit"] = package.index.source_commit
    wire["manifestSha256"] = package.index.manifest_sha256
    wire["packageRun"]["headSha"] = package.index.tooling_commit
    wire["stagingRun"]["headSha"] = COMMIT
    wire["artifact"]["archiveSha256"] = sha256_ref(raw)
    request = parse_request(json.dumps(wire).encode())

    def run(pin):
        return {
            "id": pin.run_id,
            "run_attempt": pin.attempt,
            "path": pin.workflow_path,
            "head_sha": pin.head_sha,
            "head_branch": "main",
            "event": "workflow_dispatch",
            "status": "completed",
            "conclusion": "success",
            "repository": {"full_name": kit.REPOSITORY, "id": 42},
            "head_repository": {"full_name": kit.REPOSITORY, "id": 42},
            "html_url": f"https://github.com/{kit.REPOSITORY}/actions/runs/{pin.run_id}",
            "created_at": "2026-10-17T20:00:00Z",
            "run_started_at": "2026-10-17T20:00:00Z",
            "updated_at": "2026-10-17T23:01:00Z",
        }

    runs = {"package": run(request.package_run), "staging": run(request.staging_run)}
    artifact = {
        "id": 1234,
        "name": request.artifact.name,
        "digest": sha256_ref(raw),
        "expired": False,
        "created_at": "2026-10-17T23:00:01Z",
        "workflow_run": {
            "id": 9000,
            "repository_id": 42,
            "head_repository_id": 42,
            "head_branch": "main",
            "head_sha": request.package_run.head_sha,
        },
    }
    return request, runs, artifact, raw, package


def verify(inputs):
    request, runs, artifact, raw, package = inputs
    p.verify_package_origin(
        request, run_metadata=runs, artifact_metadata=artifact, archive_bytes=raw, package=package
    )


def test_exact_package_has_positive_control(exact_package):
    verify(exact_package)


@pytest.mark.parametrize("kind", ["package", "staging"])
@pytest.mark.parametrize(
    "field,value",
    [
        ("run_attempt", 2),
        ("run_attempt", True),
        ("id", 1),
        ("repository", {"full_name": "attacker/TalentForge", "id": 42}),
        ("head_repository", {"full_name": "attacker/TalentForge", "id": 42}),
        ("path", ".github/workflows/evil.yml"),
        ("head_sha", "f" * 40),
        ("event", "pull_request"),
        ("conclusion", "failure"),
        ("status", "in_progress"),
        ("head_branch", "feature"),
        ("head_sha", "5" * 40),
    ],
)
def test_reject_foreign_attempt_before_package_use(exact_package, kind, field, value):
    request, runs, artifact, raw, _ = exact_package
    runs = copy.deepcopy(runs)
    runs[kind][field] = value
    # No package object: wrong metadata must fail before any package access.
    with pytest.raises(p.RequestRejected, match="RUN"):
        p.verify_package_origin(
            request, run_metadata=runs, artifact_metadata=artifact, archive_bytes=raw, package=None
        )


@pytest.mark.parametrize(
    "field,value",
    [
        ("id", 5678),
        ("id", True),
        ("digest", "sha256:" + "0" * 64),
        ("expired", True),
        ("expired", "false"),
        ("name", "other"),
        ("created_at", "2026-10-16T23:00:00Z"),
    ],
)
def test_artifact_identity_is_not_just_its_name(exact_package, field, value):
    request, runs, artifact, raw, package = exact_package
    with pytest.raises(p.RequestRejected):
        verify((request, runs, {**artifact, field: value}, raw, package))


@pytest.mark.parametrize(
    "field,value",
    [
        ("source_commit", "b" * 40),
        ("created_at", "2026-10-18T23:00:00Z"),
        ("manifest_sha256", "sha256:" + "0" * 64),
        ("base_release_id", "1.60.1.70000-r1"),
    ],
)
def test_request_must_pin_package_identity(exact_package, field, value):
    request, *rest = exact_package
    with pytest.raises(p.RequestRejected):
        verify((request.model_copy(update={field: value}), *rest))


def test_correct_name_cannot_hide_changed_archive(exact_package):
    request, runs, artifact, raw, package = exact_package
    with pytest.raises(p.RequestRejected, match="ARCHIVE"):
        verify((request, runs, artifact, raw + b"tampered", package))


def test_archive_roundtrip_is_closed(exact_package, tmp_path):
    request, _, _, raw, package = exact_package
    root = p.extract_verified_archive(raw, tmp_path / "extract")
    assert load_package(root).index == package.index
    assert sha256_ref((root / "release.json").read_bytes()) == request.manifest_sha256


@pytest.mark.parametrize(
    "name", ["../escape", "/absolute", "a/../../escape", "a\\escape", "a//b", "a/./b", "C:/escape"]
)
def test_unsafe_zip_paths_are_refused(name, tmp_path):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as archive:
        archive.writestr(name, b"payload")
    with pytest.raises(p.RequestRejected, match="ARCHIVE"):
        p.extract_verified_archive(out.getvalue(), tmp_path / "out")
    assert not (tmp_path / "escape").exists()


@pytest.mark.parametrize(
    "kind",
    [
        "symlink",
        "duplicate",
        "oversize",
        "total",
        "entries",
        "publish",
        "unindexed",
        "parent-symlink",
    ],
)
def test_hostile_archive_is_refused(exact_package, tmp_path, monkeypatch, kind):
    _, _, _, raw, package = exact_package
    out = io.BytesIO(raw)
    with zipfile.ZipFile(out, "a") as archive:
        if kind == "symlink":
            entry = zipfile.ZipInfo("link")
            entry.create_system = 3
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(entry, "../outside")
        elif kind == "duplicate":
            with pytest.warns(UserWarning):
                archive.writestr("package.json", (package.root / "package.json").read_bytes())
        elif kind == "oversize":
            monkeypatch.setattr(p, "MAX_ARCHIVE_FILE_BYTES", 16)
        elif kind == "total":
            monkeypatch.setattr(p, "MAX_ARCHIVE_EXPANDED_BYTES", 16)
        elif kind == "entries":
            monkeypatch.setattr(p, "MAX_ARCHIVE_ENTRIES", 1)
        elif kind == "publish":
            archive.writestr("publish/hidden.py", b"raise RuntimeError('never execute')")
        elif kind == "unindexed":
            archive.writestr("hidden.py", b"raise RuntimeError('never execute')")
    destination = tmp_path / "out"
    if kind == "parent-symlink":
        (tmp_path / "target").mkdir()
        destination.symlink_to(tmp_path / "target", target_is_directory=True)
    with pytest.raises(p.RequestRejected):
        p.extract_verified_archive(out.getvalue(), destination)


@pytest.mark.parametrize(
    "field,value",
    [
        ("id", 8999),
        ("repository_id", 99),
        ("head_repository_id", 99),
        ("head_branch", "feature"),
        ("head_sha", "f" * 40),
    ],
)
def test_artifact_must_belong_to_pinned_run(exact_package, field, value):
    request, runs, artifact, raw, package = exact_package
    artifact = copy.deepcopy(artifact)
    artifact["workflow_run"][field] = value
    with pytest.raises(p.RequestRejected, match="ARTIFACT_RUN"):
        verify((request, runs, artifact, raw, package))


def test_matching_head_pin_and_metadata_still_require_trusted_main(exact_package):
    request, runs, artifact, raw, _ = exact_package
    runs = copy.deepcopy(runs)
    runs["package"]["head_sha"] = "5" * 40
    request = request.model_copy(
        update={"package_run": request.package_run.model_copy(update={"head_sha": "5" * 40})}
    )
    with pytest.raises(p.RequestRejected, match="RUN_MAIN"):
        verify((request, runs, artifact, raw, None))


def test_supplied_package_cannot_be_swapped_under_verified_archive(exact_package, tmp_path):
    request, runs, artifact, raw, _ = exact_package
    root = p.extract_verified_archive(raw, tmp_path / "unpacked")
    index_path = root / "package.json"
    index = json.loads(index_path.read_bytes())
    # Self-consistent closed index, but not the exact archived package.
    changed = b"other report bytes"
    (root / "extra-report.txt").write_bytes(changed)
    index["files"]["extra-report.txt"] = {"sha256": sha256_ref(changed), "byteLength": len(changed)}
    index_path.write_text(json.dumps(index))
    package = load_package(root)
    with pytest.raises(p.RequestRejected, match="PACKAGE_ARCHIVE"):
        verify((request, runs, artifact, raw, package))


def test_indexed_artifact_code_is_never_executed(exact_package, tmp_path):
    request, runs, artifact, raw, _ = exact_package
    root = p.extract_verified_archive(raw, tmp_path / "unpacked")
    index_path = root / "package.json"
    index = json.loads(index_path.read_bytes())
    marker = tmp_path / "executed"
    script = f"from pathlib import Path\nPath({str(marker)!r}).touch()\n".encode()
    (root / "report.py").write_bytes(script)
    index["files"]["report.py"] = {"sha256": sha256_ref(script), "byteLength": len(script)}
    index_path.write_text(json.dumps(index))
    raw = zip_tree(root)
    digest = sha256_ref(raw)
    request = request.model_copy(
        update={"artifact": request.artifact.model_copy(update={"archive_sha256": digest})}
    )
    verify((request, runs, {**artifact, "digest": digest}, raw, load_package(root)))
    assert not marker.exists()
