"""Indexed candidate proof is closed and independently bound to request producers."""


# ruff: noqa: F811 -- imported pytest fixture is injected by name

import json
import shutil
from pathlib import Path

import pytest

from talent_data.ota import release_package as rp
from talent_data.ota.automation import provenance as p
from talent_data.ota.automation.candidate import CandidateBinding
from talent_data.ota.verify import sha256_ref
from tests.unit.ota.test_automation_provenance import exact_package, zip_tree  # noqa: F401
from tests.unit.ota.test_merge_contracts import binding_wire


@pytest.fixture
def bound_package(exact_package, tmp_path):
    request, runs, metadata, _, package = exact_package
    root = tmp_path / "package"
    shutil.copytree(package.root, root)
    wire = binding_wire()
    wire.update(
        sourceSha=request.source_commit,
        sourceTree="b" * 40,
        preparationHeadSha=request.source_commit,
        toolingSha=request.package_run.head_sha,
        build=request.build,
        baseReleaseId=request.base_release_id,
        baseManifestSha256=request.base_manifest_sha256,
    )
    wire["stagingRun"] = request.staging_run.model_dump(mode="json", by_alias=True)
    wire["packageRun"] = request.package_run.model_dump(mode="json", by_alias=True)
    wire["flaggedGates"] = [
        r["gate"]
        for r in json.loads(package.attachment("report/gates.json"))
        if r["status"] == "FLAG"
    ]
    binding = CandidateBinding.model_validate_json(json.dumps(wire))
    attach(root, binding.model_dump_json(by_alias=True).encode())
    raw = zip_tree(root)
    request = request.model_copy(
        update={"artifact": request.artifact.model_copy(update={"archive_sha256": sha256_ref(raw)})}
    )
    return (
        request,
        runs,
        {**metadata, "digest": sha256_ref(raw)},
        raw,
        rp.load_package(root),
        binding,
    )


def attach(root: Path, raw: bytes):
    name = "report/candidate-binding.json"
    (root / name).write_bytes(raw)
    index = json.loads((root / "package.json").read_bytes())
    index["files"][name] = {"sha256": sha256_ref(raw), "byteLength": len(raw)}
    (root / "package.json").write_text(json.dumps(index))


def verify(data):
    request, runs, metadata, raw, package, _ = data
    p.verify_package_origin(
        request, run_metadata=runs, artifact_metadata=metadata, archive_bytes=raw, package=package
    )


def test_indexed_binding_roundtrip_and_legacy_absence(bound_package, exact_package):
    *_, package, binding = bound_package
    assert rp.candidate_binding(package) == binding
    assert rp.candidate_binding(exact_package[-1]) is None
    verify(bound_package)


@pytest.mark.parametrize(
    "field,value",
    [
        ("sourceSha", "c" * 40),
        ("build", "1.60.1.70000"),
        ("baseManifestSha256", "sha256:" + "0" * 64),
        ("packageRun", None),
        ("unknown", 1),
    ],
)
def test_proof_must_match_actual_package_and_request(bound_package, field, value):
    request, runs, metadata, _, package, binding = bound_package
    wire = binding.model_dump(mode="json", by_alias=True)
    if field == "sourceSha":
        wire["preparationHeadSha"] = value
    wire[field] = value
    attach(package.root, json.dumps(wire).encode())
    raw = zip_tree(package.root)
    data = (
        request.model_copy(
            update={
                "artifact": request.artifact.model_copy(update={"archive_sha256": sha256_ref(raw)})
            }
        ),
        runs,
        {**metadata, "digest": sha256_ref(raw)},
        raw,
        rp.load_package(package.root),
        binding,
    )
    with pytest.raises((p.RequestRejected, rp.PackageError)):
        verify(data)


def test_duplicate_key_proof_and_changed_indexed_attachment_refused(bound_package):
    *_, package, binding = bound_package
    raw = (
        binding.model_dump_json(by_alias=True)
        .replace('"schemaVersion":1', '"schemaVersion":1,"schemaVersion":1')
        .encode()
    )
    attach(package.root, raw)
    with pytest.raises(rp.PackageError):
        rp.candidate_binding(rp.load_package(package.root))
    (package.root / "report/candidate-binding.json").write_bytes(b"changed")
    with pytest.raises(rp.PackageError):
        rp.load_package(package.root)


def test_alternate_successful_package_run_is_not_original_proof(bound_package):
    request, runs, metadata, raw, package, binding = bound_package
    other = request.package_run.model_copy(update={"run_id": 9001})
    request = request.model_copy(update={"package_run": other})
    runs = {**runs, "package": {**runs["package"], "id": 9001}}
    metadata = {**metadata, "workflow_run": {**metadata["workflow_run"], "id": 9001}}
    with pytest.raises(p.RequestRejected):
        verify((request, runs, metadata, raw, package, binding))
