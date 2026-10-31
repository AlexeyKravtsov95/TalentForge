"""Bind exact package bytes to trusted GitHub API evidence. Artifacts are data only.

``run_metadata`` holds ``package`` and ``staging`` GitHub run/attempt API objects.
Callers fetch these from the repository API using pinned ids, never artifact URLs or
artifact-supplied metadata. A full-history trusted checkout with current origin/main is
required. No source/tooling from an archive is imported, run or put on sys.path.
"""

from __future__ import annotations

import io
import stat
import struct
import subprocess
import tempfile
import zipfile
import zlib
from datetime import datetime, timedelta
from pathlib import Path, PurePosixPath

from talent_data.ota.automation.requests import ReleaseRequest, RunPin
from talent_data.ota.catalog import catalog_entry
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.release_package import (
    STAGING_FILES,
    STAGING_WORKFLOW,
    LoadedPackage,
    load_package,
    rebind_staging,
    rebuild_problems,
    run_record,
)
from talent_data.ota.verify import (
    OtaError,
    sha256_ref,
    strict_json,
    verify_release_files,
    verify_release_manifest,
)

REPOSITORY = "AlexeyKravtsov95/TalentForge"
PACKAGE_WORKFLOW = ".github/workflows/ota-release-package.yml"
TRUSTED_REPO = Path(__file__).resolve().parents[4]
MAX_ARCHIVE_BYTES = 256 * 1024 * 1024
MAX_ARCHIVE_FILE_BYTES = 32 * 1024 * 1024
MAX_ARCHIVE_EXPANDED_BYTES = 512 * 1024 * 1024
MAX_ARCHIVE_ENTRIES = 10000
MAX_ARCHIVE_DIRECTORY_BYTES = 8 * 1024 * 1024


class RequestRejected(Exception):
    def __init__(self, code: str, detail: str) -> None:
        self.code = code
        self.detail = detail
        super().__init__(f"{code}: {detail}")


def _require(condition: bool, code: str, detail: str) -> None:
    if not condition:
        raise RequestRejected(code, detail)


def _object(value: object, code: str) -> dict[str, object]:
    _require(isinstance(value, dict), code, "missing API object")
    assert isinstance(value, dict)
    return value


def _same(found: object, expected: object, code: str, field: str) -> None:
    _require(type(found) is type(expected) and found == expected, code, f"{field} differs")


def _main_commit(sha: str) -> None:
    result = subprocess.run(
        ["git", "-C", str(TRUSTED_REPO), "merge-base", "--is-ancestor", sha, "origin/main"],
        capture_output=True,
        check=False,
    )
    _require(result.returncode == 0, "RUN_MAIN", "commit is not on trusted origin/main")


def _run(metadata: object, pin: RunPin, workflow: str) -> dict[str, object]:
    run = _object(metadata, "RUN_METADATA")
    _same(pin.workflow_path, workflow, "RUN_WORKFLOW", "request workflowPath")
    for field, expected in {
        "id": pin.run_id,
        "run_attempt": pin.attempt,
        "path": workflow,
        "head_sha": pin.head_sha,
        "head_branch": "main",
        "status": "completed",
        "conclusion": "success",
    }.items():
        _same(run.get(field), expected, "RUN_METADATA", field)
    # These workflows currently build trusted main only via workflow_dispatch.
    # workflow_run/call require an explicit future trust policy, not a name match.
    _same(run.get("event"), "workflow_dispatch", "RUN_EVENT", "event")
    for field in ("repository", "head_repository"):
        repo = _object(run.get(field), "RUN_REPOSITORY")
        _same(repo.get("full_name"), REPOSITORY, "RUN_REPOSITORY", field)
    _main_commit(pin.head_sha)
    return run


def _check_zip_directory(raw: bytes) -> None:
    """Bound actual classic ZIP central records before ZipFile allocates ZipInfos.

    Workflow packages fit ordinary single-disk ZIP; ZIP64/multi-disk inputs are refused.
    Do not trust the declared record count: walk the bounded directory independently.
    """
    end = raw.rfind(b"PK\x05\x06", max(0, len(raw) - 65557))
    _require(end >= 0 and end + 22 <= len(raw), "ARCHIVE_DIRECTORY", "missing ZIP end record")
    _, disk, directory_disk, disk_count, count, size, offset, comment = struct.unpack_from(
        "<4s4H2LH", raw, end
    )
    _require(
        disk == directory_disk == 0 and disk_count == count and count != 65535,
        "ARCHIVE_DIRECTORY",
        "expected single-disk classic ZIP",
    )
    _require(
        count <= MAX_ARCHIVE_ENTRIES and size <= MAX_ARCHIVE_DIRECTORY_BYTES,
        "ARCHIVE_SIZE",
        "central directory too large",
    )
    _require(
        end + 22 + comment == len(raw) and offset + size == end,
        "ARCHIVE_DIRECTORY",
        "invalid central directory bounds",
    )
    cursor, actual = offset, 0
    while cursor < end:
        _require(
            cursor + 46 <= end and raw[cursor : cursor + 4] == b"PK\x01\x02",
            "ARCHIVE_DIRECTORY",
            "invalid central record",
        )
        name, extra, note = struct.unpack_from("<HHH", raw, cursor + 28)
        cursor += 46 + name + extra + note
        actual += 1
        _require(actual <= MAX_ARCHIVE_ENTRIES, "ARCHIVE_SIZE", "too many central records")
        _require(cursor <= end, "ARCHIVE_DIRECTORY", "central record exceeds bounds")
    _require(actual == count, "ARCHIVE_DIRECTORY", "declared entry count differs")


def extract_verified_archive(archive_bytes: bytes, destination: Path) -> Path:
    """Extract a bounded ZIP into a fresh directory and run the closed package verifier.

    Accept the workflow's ``<releaseId>/`` plus optional outer ``summary.md``, or a
    package at ZIP root. Reject hidden publish/ work, multiple packages and loose files.
    No extractall, permissions restoration, executable code or symlink following.
    """
    _require(len(archive_bytes) <= MAX_ARCHIVE_BYTES, "ARCHIVE_SIZE", "archive too large")
    _require(
        not any(p.is_symlink() for p in (destination, *destination.parents)),
        "ARCHIVE_DESTINATION",
        "symlink destination",
    )
    _require(
        not destination.exists() or (destination.is_dir() and not any(destination.iterdir())),
        "ARCHIVE_DESTINATION",
        "destination must be fresh",
    )
    try:
        _check_zip_directory(archive_bytes)
        with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
            entries = archive.infolist()
            _require(len(entries) <= MAX_ARCHIVE_ENTRIES, "ARCHIVE_SIZE", "too many entries")
            paths: set[str] = set()
            total = 0
            for entry in entries:
                _require(
                    entry.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
                    "ARCHIVE_TYPE",
                    "unsupported ZIP compression",
                )
                name = entry.filename.rstrip("/")
                parts = name.split("/")
                _require(
                    bool(name)
                    and not name.startswith("/")
                    and "\\" not in name
                    and ":" not in name
                    and "\x00" not in entry.orig_filename
                    and all(part not in ("", ".", "..") for part in parts),
                    "ARCHIVE_PATH",
                    "unsafe path",
                )
                _require(name not in paths, "ARCHIVE_PATH", "duplicate path")
                paths.add(name)
                mode = stat.S_IFMT(entry.external_attr >> 16)
                _require(
                    mode in (0, stat.S_IFREG, stat.S_IFDIR)
                    and (mode != stat.S_IFDIR or entry.is_dir()),
                    "ARCHIVE_TYPE",
                    "non-regular entry",
                )
                _require(not entry.flag_bits & 1, "ARCHIVE_TYPE", "encrypted entry")
                _require(
                    entry.file_size <= MAX_ARCHIVE_FILE_BYTES, "ARCHIVE_SIZE", "file too large"
                )
                total += entry.file_size
                _require(
                    total <= MAX_ARCHIVE_EXPANDED_BYTES,
                    "ARCHIVE_SIZE",
                    "expanded archive too large",
                )
            # A file cannot also be a directory prefix (a vs a/b), in either ZIP order.
            files = {e.filename for e in entries if not e.is_dir()}
            _require(
                not any(
                    str(parent) in files
                    for name in paths
                    for parent in PurePosixPath(name).parents
                    if str(parent) != "."
                ),
                "ARCHIVE_PATH",
                "file/directory collision",
            )
            indexes = [PurePosixPath(n) for n in files if PurePosixPath(n).name == "package.json"]
            _require(len(indexes) == 1, "ARCHIVE_PACKAGE", "expected one package index")
            prefix = indexes[0].parent
            _require(len(prefix.parts) <= 1, "ARCHIVE_PACKAGE", "unexpected package nesting")
            for name in files:
                path = PurePosixPath(name)
                if prefix != PurePosixPath(".") and path == PurePosixPath("summary.md"):
                    continue
                _require(path.is_relative_to(prefix), "ARCHIVE_PACKAGE", "file outside package")
                _require(
                    path.relative_to(prefix).parts[0] != "publish",
                    "ARCHIVE_PACKAGE",
                    "mutable publish work is not a package input",
                )
            destination.mkdir(parents=True, exist_ok=True)
            for entry in entries:
                target = destination / entry.filename
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(entry) as source, target.open("xb") as output:
                    remaining = entry.file_size
                    while chunk := source.read(min(65536, remaining + 1)):
                        remaining -= len(chunk)
                        _require(remaining >= 0, "ARCHIVE_SIZE", "actual size exceeds declaration")
                        output.write(chunk)
                    _require(remaining == 0, "ARCHIVE_SIZE", "truncated entry")
            root = destination / str(prefix)
            load_package(root)
            return root
    except (
        OSError,
        ValueError,
        zipfile.BadZipFile,
        zlib.error,
        NotImplementedError,
        OtaPublisherError,
    ) as exc:
        raise RequestRejected("ARCHIVE_INVALID", "invalid or non-closed package archive") from exc


def verify_package_origin(
    request: ReleaseRequest,
    *,
    run_metadata: dict[str, object],
    artifact_metadata: dict[str, object],
    archive_bytes: bytes,
    package: LoadedPackage,
) -> None:
    """Fail closed before package use on any foreign/mismatched run or artifact."""
    _same(request.repository, REPOSITORY, "RUN_REPOSITORY", "repository")
    _same(request.game, "forever", "REQUEST_IDENTITY", "game")
    package_run = _run(run_metadata.get("package"), request.package_run, PACKAGE_WORKFLOW)
    staging_run = _run(run_metadata.get("staging"), request.staging_run, STAGING_WORKFLOW)
    for field, expected in {
        "id": request.artifact.artifact_id,
        "name": request.artifact.name,
        "digest": request.artifact.archive_sha256,
        "expired": False,
    }.items():
        _same(artifact_metadata.get(field), expected, "ARTIFACT_METADATA", field)
    repo = _object(package_run.get("repository"), "RUN_REPOSITORY")
    repo_id = repo.get("id")
    _require(type(repo_id) is int and repo_id > 0, "RUN_REPOSITORY", "missing repository id")
    workflow_run = _object(artifact_metadata.get("workflow_run"), "ARTIFACT_RUN")
    for field, expected in {
        "id": request.package_run.run_id,
        "repository_id": repo_id,
        "head_repository_id": repo_id,
        "head_branch": "main",
        "head_sha": request.package_run.head_sha,
    }.items():
        _same(workflow_run.get(field), expected, "ARTIFACT_RUN", field)
    try:
        times = []
        for value in (
            package_run.get("run_started_at"),
            artifact_metadata.get("created_at"),
            package_run.get("updated_at"),
        ):
            _require(isinstance(value, str), "ARTIFACT_ATTEMPT", "missing attempt timestamps")
            assert isinstance(value, str)
            stamp = datetime.fromisoformat(value)
            _require(
                stamp.tzinfo is not None and stamp.utcoffset() == timedelta(0),
                "ARTIFACT_ATTEMPT",
                "timestamps must be UTC",
            )
            times.append(stamp)
        _require(
            times[0] <= times[1] <= times[2],
            "ARTIFACT_ATTEMPT",
            "artifact outside successful attempt",
        )
    except ValueError as exc:
        raise RequestRejected("ARTIFACT_ATTEMPT", "invalid API timestamp") from exc
    _same(
        sha256_ref(archive_bytes),
        request.artifact.archive_sha256,
        "ARCHIVE_DIGEST",
        "archive digest",
    )
    _main_commit(request.source_commit)
    try:
        # Do not rely on a previously loaded object or an independently supplied package.
        current = load_package(package.root)
        _require(
            package.index == current.index
            and package.manifest == current.manifest
            and package.manifest_bytes == current.manifest_bytes
            and package.blobs == current.blobs,
            "PACKAGE_SNAPSHOT",
            "supplied loaded snapshot differs from verified disk contents",
        )
        with tempfile.TemporaryDirectory(prefix="ota-package-") as temp:
            archived = load_package(
                extract_verified_archive(archive_bytes, Path(temp).resolve() / "unpacked")
            )
            _require(
                current.index == archived.index
                and current.manifest_bytes == archived.manifest_bytes
                and current.blobs == archived.blobs
                and all(
                    current.attachment(n) == archived.attachment(n) for n in current.index.files
                ),
                "PACKAGE_ARCHIVE",
                "supplied package differs from archive",
            )
        index = current.index
        for field, expected in {
            "game": request.game,
            "client_build": request.build,
            "revision": request.revision,
            "release_id": request.release_id,
            "source_commit": request.source_commit,
            "created_at": request.created_at,
            "manifest_sha256": request.manifest_sha256,
            "tooling_commit": request.package_run.head_sha,
            "produced_by": f"https://github.com/{REPOSITORY}/actions/runs/{request.package_run.run_id}",
            "diff_base": request.base_release_id.rsplit("-r", 1)[0],
        }.items():
            _same(getattr(index, field), expected, "PACKAGE_IDENTITY", field)
        # Re-parse copied staging JSON with duplicate rejection before existing rebind.
        for name in STAGING_FILES.values():
            strict_json(current.attachment(name))
        recorded = strict_json(current.attachment(STAGING_FILES["run"]))
        _require(
            isinstance(recorded, dict)
            and recorded.keys() == run_record(staging_run).keys()
            and all(
                type(recorded[k]) is type(v) and recorded[k] == v
                for k, v in run_record(staging_run).items()
            ),
            "STAGING_RUN",
            "copied run differs from API evidence",
        )
        report = _object(strict_json(current.attachment(STAGING_FILES["report"])), "STAGING_REPORT")
        _same(report.get("formatVersion"), 1, "STAGING_REPORT", "formatVersion")
        binding = rebind_staging(current, REPOSITORY)
        _same(
            binding.staging_base,
            request.base_release_id.rsplit("-r", 1)[0],
            "STAGING_BASE",
            "staging base",
        )
        _require(
            binding.status == "PASS" and binding == index.staging,
            "STAGING_BINDING",
            "staging does not verify these exact files",
        )
        problems = rebuild_problems(TRUSTED_REPO, current)
        _require(not problems, "PACKAGE_REBUILD", "; ".join(problems))
        entry = catalog_entry(
            current.manifest_bytes, current.manifest, published_at=current.manifest.created_at
        )
        verified = verify_release_manifest(
            current.manifest_bytes, entry, game=request.game, supported_data_level=1
        )
        verify_release_files(verified, lambda file: current.blobs[file.sha256])
    except (OSError, ValueError, OtaPublisherError, OtaError) as exc:
        raise RequestRejected("PACKAGE_INVALID", "package failed existing verification") from exc
