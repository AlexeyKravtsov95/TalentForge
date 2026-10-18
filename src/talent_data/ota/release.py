"""Build an immutable OTA release from the committed data of one git commit.

A release is a complete snapshot of one client build in the app's bundled resource
layout (contract §5.1): ``dataset/<g>/<b>``, ``visuals/<g>/<b>``,
``tooltips/<g>/<b>``, ``localization/<g>/<b>`` - read from the repository tree **at a
commit** (``git archive``), never from the working tree, so uncommitted changes are
invisible by construction (gate G1).

The build is deterministic: file roles come from the path grammar, files are ordered
by the UTF-8 byte order of their paths, the manifest is serialized in one fixed byte
form, and no clock, counter or environment input enters the output. Building the same
commit twice yields identical bytes (gate G8), which is also what lets
``scripts/generate_ota_fixtures.py`` commit reproducible fixtures: the generator calls
:func:`release_files`, :func:`build_manifest` and :func:`serialize_manifest` here.
"""

from __future__ import annotations

import io
import json
import re
import subprocess
import tarfile
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path, PurePosixPath
from typing import Any

from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import (
    FileRole,
    OtaReleaseManifest,
    ReleaseFile,
    ReleaseProvenance,
)
from talent_data.ota.verify import sha256_ref

# Release path prefix -> repository directory that holds it (contract §5.1). The release
# layout is the app's bundled layout; the repository layout is the pipeline's.
ROOT_DIRS = {
    "dataset": "data/normalized",
    "visuals": "data/visuals",
    "tooltips": "data/tooltips",
    "localization": "data/localization",
}

_COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")


def role_for_path(path: str) -> FileRole:
    """The role of a release path, from the path grammar alone (contract §5.1)."""
    head, rest = path.split("/", 1)
    name = rest.split("/", 2)[2]
    if head == "dataset":
        return FileRole.DATASET_MANIFEST if name == "manifest.json" else FileRole.TALENT_DATA
    if head == "visuals":
        if name == "icons.json":
            return FileRole.ICON_REGISTRY
        return FileRole.ICON_IMAGE if name.startswith("icons/") else FileRole.VISUALS
    return FileRole.TOOLTIPS if head == "tooltips" else FileRole.LOCALIZATION


def commit_snapshot(repo_root: Path, commit: str, game: str, build: str) -> dict[str, bytes]:
    """Release path -> bytes of every committed file of ``build`` at ``commit``.

    Reads the committed tree only (``git archive``): a dirty working tree, staged
    changes and unmerged branches cannot enter a release. Fails closed when the commit
    does not exist or carries no data for the build.
    """
    if not _COMMIT_RE.match(commit):
        raise OtaPublisherError(f"not a commit id: {commit!r} (40 lowercase hex expected)")
    exists = subprocess.run(
        ["git", "-C", str(repo_root), "cat-file", "-e", f"{commit}^{{commit}}"],
        capture_output=True,
    )
    if exists.returncode != 0:
        raise OtaPublisherError(f"commit {commit} does not exist in {repo_root}")
    # ``git archive`` fails when a pathspec matches nothing, so archive only the
    # directories that exist at the commit (a build with, say, no localization sidecars
    # is incomplete, not unarchivable; gates G2/G3 catch incompleteness).
    prefixes: list[str] = []
    for repo_dir in ROOT_DIRS.values():
        directory = f"{repo_dir}/{game}/{build}"
        probe = subprocess.run(
            ["git", "-C", str(repo_root), "ls-tree", "--name-only", commit, directory],
            capture_output=True,
        )
        if probe.returncode == 0 and probe.stdout.strip():
            prefixes.append(directory)
    if not prefixes:
        raise OtaPublisherError(f"commit {commit} has no data for game {game!r} build {build!r}")
    command = [
        "git",
        "-C",
        str(repo_root),
        "archive",
        "--format=tar",
        commit,
        "--",
        *prefixes,
    ]
    try:
        result = subprocess.run(command, capture_output=True, check=True)
    except subprocess.CalledProcessError as exc:
        detail = exc.stderr.decode("utf-8", "replace").strip() if exc.stderr else "unknown"
        raise OtaPublisherError(f"git archive {commit} failed: {detail}") from exc
    except OSError as exc:
        raise OtaPublisherError(f"cannot run git in {repo_root}: {exc}") from exc

    files: dict[str, bytes] = {}
    repo_to_release = {repo_dir: head for head, repo_dir in ROOT_DIRS.items()}
    with tarfile.open(fileobj=io.BytesIO(result.stdout)) as tar:
        for member in tar.getmembers():
            if not member.isfile():
                continue
            # repo layout "data/normalized/<g>/<b>/<rest>" -> release layout
            # "dataset/<g>/<b>/<rest>" (contract §5.1), etc.
            parts = PurePosixPath(member.name).parts
            if len(parts) < 5 or f"{parts[0]}/{parts[1]}" not in repo_to_release:
                continue
            head = repo_to_release[f"{parts[0]}/{parts[1]}"]
            release_path = f"{head}/" + "/".join(parts[2:])
            files[release_path] = tar.extractfile(member).read()  # type: ignore[union-attr]
    if not files:
        raise OtaPublisherError(f"commit {commit} has no data for game {game!r} build {build!r}")
    return files


def release_files(contents: dict[str, bytes]) -> list[ReleaseFile]:
    """The manifest's ``files`` array: ordered by path bytes, content-addressed."""
    return [
        ReleaseFile(
            path=path, role=role_for_path(path), sha256=sha256_ref(data), byte_length=len(data)
        )
        for path, data in sorted(contents.items(), key=lambda item: item[0].encode("utf-8"))
    ]


def build_manifest(
    *,
    game: str,
    client_build: str,
    revision: int,
    min_data_level: int,
    created_at: datetime,
    repository_commit: str,
    validation_note: str,
    files: list[ReleaseFile],
) -> OtaReleaseManifest:
    return OtaReleaseManifest(
        schema_version=1,
        game=game,
        client_build=client_build,
        release_id=f"{client_build}-r{revision}",
        min_data_level=min_data_level,
        created_at=created_at,
        provenance=ReleaseProvenance(
            repository_commit=repository_commit, validation=validation_note
        ),
        files=files,
        total_byte_length=sum(f.byte_length for f in files),
    )


def serialize_manifest(manifest: OtaReleaseManifest | dict[str, Any]) -> bytes:
    """The fixed byte form of a release manifest.

    Any byte form is valid on the wire (the catalog pins the bytes, nothing is
    canonicalized); this one - indented JSON with a single line per file entry - keeps
    committed manifests diffable and is the form the fixtures reproduce.
    """
    doc = (
        manifest.model_dump(mode="json", by_alias=True)
        if isinstance(manifest, OtaReleaseManifest)
        else manifest
    )
    members = []
    for key, value in doc.items():
        if key == "files" and isinstance(value, list) and value:
            rows = ",\n".join(f"    {json.dumps(f, ensure_ascii=False)}" for f in value)
            members.append(f'  "files": [\n{rows}\n  ]')
        else:
            text = json.dumps(value, indent=2, ensure_ascii=False).replace("\n", "\n  ")
            members.append(f"  {json.dumps(key)}: {text}")
    return ("{\n" + ",\n".join(members) + "\n}\n").encode("utf-8")


@dataclass(frozen=True)
class ReleaseArtifact:
    """Everything one build of a commit produces: the manifest and its blobs."""

    manifest: OtaReleaseManifest
    manifest_bytes: bytes
    snapshot: dict[str, bytes]
    #: content-addressed blobs of the release's files (two paths may share one blob)
    blobs: dict[str, bytes]

    @property
    def release_id(self) -> str:
        return self.manifest.release_id

    @property
    def manifest_blob(self) -> tuple[str, bytes]:
        """The manifest itself is a blob, pinned by the catalog's ``manifestSha256``."""
        return sha256_ref(self.manifest_bytes), self.manifest_bytes


def build_release(
    repo_root: Path,
    commit: str,
    game: str,
    build: str,
    revision: int,
    created_at: datetime,
    validation_note: str,
    *,
    min_data_level: int = 1,
) -> ReleaseArtifact:
    """Build the release of ``build`` at ``commit`` - deterministic, committed data only.

    The byte form of the manifest, every file hash and the blob set depend only on the
    inputs; two calls with the same arguments return equal artifacts (gate G8).
    """
    snapshot = commit_snapshot(repo_root, commit, game, build)
    files = release_files(snapshot)
    manifest = build_manifest(
        game=game,
        client_build=build,
        revision=revision,
        min_data_level=min_data_level,
        created_at=created_at,
        repository_commit=commit,
        validation_note=validation_note,
        files=files,
    )
    blobs = {file.sha256: snapshot[file.path] for file in files}
    return ReleaseArtifact(
        manifest=manifest,
        manifest_bytes=serialize_manifest(manifest),
        snapshot=snapshot,
        blobs=blobs,
    )
