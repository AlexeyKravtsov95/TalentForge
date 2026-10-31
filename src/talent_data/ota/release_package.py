"""The production release package (M5.OTA.PROD.4): what CI prepares, what the owner publishes.

CI builds one release with the existing publisher (``build-release`` at one fixed commit on
``main``) and writes it as a self-contained directory - the package - together with everything
the owner reviews before publishing it. CI holds no production signing key and no S3
credential: the package publishes nothing. The owner's helper
(``scripts/ota_production_release.py``) verifies the package, uploads its blobs, has the owner
sign the catalog offline and writes the catalog - each step through the existing publisher
commands and their checks.

Layout (``<root>`` = the package directory)::

    package.json                  index: identity, provenance, staging binding, and the
                                  SHA-256 + size of every other file of the package
    release.json                  the exact manifest bytes (what the approval record pins)
    blobs/sha256/<64 hex>         every distinct file of the release, named by its hash
    approval-record.template.md   the record for docs/ota/releases/<releaseId>.md, with its pins
    report/…                      gates, review diff, staging binding, publication conditions
    staging/…                     copies of the staging evidence the binding was proven from
    publish/                      the owner's working directory; never part of the index

The package is closed: :func:`load_package` refuses a missing, extra or altered file, a blob
that does not hash to its name, a blob set other than the manifest's, and an index that does
not describe the manifest - the bytes are never re-downloaded or regenerated to fill a gap.

The staging binding (:func:`bind_staging`) attributes a staging PASS to this release only from
verifiable data: the staging run's own report, the release manifest it staged and the client's
installed copy must name the same build, and their files (path, SHA-256, size) must equal this
release's files. A different source commit is accepted only when those files are proven equal
(``mode = equalFiles``); the staging run is never attributed by name or build number alone.
"""

from __future__ import annotations

import json
import re
import string
import subprocess
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.gates import FLAGGABLE_GATES
from talent_data.ota.models import COMMIT_PATTERN, SHA256_PATTERN, OtaReleaseManifest
from talent_data.ota.release import ReleaseArtifact, build_release
from talent_data.ota.verify import parse_strict, sha256_ref

PACKAGE_FORMAT: Literal["talentforge-ota-release-package"] = "talentforge-ota-release-package"
INDEX_NAME = "package.json"
MANIFEST_NAME = "release.json"
BLOBS_DIR = "blobs/sha256"
WORK_DIR = "publish"
APPROVAL_TEMPLATE_NAME = "approval-record.template.md"
STAGING_BINDING_NAME = "report/staging-binding.json"
STAGING_BASE_MANIFEST_NAME = "staging/base-release.json"
APPROVALS_DIR = "docs/ota/releases"
#: the committed template of an approval record (owner decisions as <OWNER: …> lines)
APPROVAL_TEMPLATE = Path(__file__).resolve().parents[3] / "docs/ota/release-approval.template.md"

STAGING_WORKFLOW = ".github/workflows/ota-staging-verify.yml"
STAGING_REPORT_FORMAT = "talentforge-ota-staging-verification"
#: the client test whose evidence lists the installed copy of the candidate
STAGING_UPDATE_TEST = "theCandidateReplacesTheBaseOverHttps"
#: where the binding's inputs are copied inside the package
STAGING_FILES = {
    "report": "staging/staging-report.json",
    "manifest": "staging/release.json",
    "update": "staging/update-evidence.json",
    "run": "staging/run.json",
}

#: a template line the owner has not filled in yet; such a record is never accepted
OWNER_PLACEHOLDER = "<OWNER:"

_HEX_RE = re.compile(r"^[0-9a-f]{64}$")
_COMMIT_RE = re.compile(COMMIT_PATTERN)

Files = Mapping[str, tuple[str, int]]


class PackageError(OtaPublisherError):
    """The package is incomplete, altered or does not describe its release."""


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        frozen=True,
        extra="forbid",
        strict=True,
    )


class PackageFile(_Model):
    sha256: str = Field(pattern=SHA256_PATTERN)
    byte_length: int = Field(ge=0)


class StagingBinding(_Model):
    """Whether a staging PASS is attributable to this release, and from what."""

    status: Literal["PASS", "FAIL", "MISSING"]
    #: ``sameCommit``: staged from this release's commit; ``equalFiles``: another commit whose
    #: files were proven equal to this release's
    mode: Literal["sameCommit", "equalFiles"] | None
    run_id: int | None
    run_url: str | None
    staging_revision: str | None
    staging_base: str | None
    staging_manifest_sha256: str | None
    problems: list[str]


class PackageIndex(_Model):
    format: Literal["talentforge-ota-release-package"]
    format_version: Literal[1]
    release_id: str
    game: str
    client_build: str
    revision: int = Field(ge=1)
    min_data_level: int = Field(ge=1)
    source_commit: str = Field(pattern=COMMIT_PATTERN)
    #: what was requested (empty: the HEAD of origin/main) and how the commit was chosen
    source_input: str
    source: str
    created_at: str
    validation_note: str
    manifest_sha256: str = Field(pattern=SHA256_PATTERN)
    manifest_byte_length: int = Field(gt=0)
    file_count: int = Field(gt=0)
    blob_count: int = Field(gt=0)
    total_byte_length: int = Field(gt=0)
    #: the build the review diff and the CI gates compare against (the live build's data)
    diff_base: str
    #: the workflow run that built the package, and the commit of its tooling
    produced_by: str | None
    tooling_commit: str = Field(pattern=COMMIT_PATTERN)
    staging: StagingBinding
    files: dict[str, PackageFile]


@dataclass(frozen=True)
class LoadedPackage:
    """A package that passed :func:`load_package`: every byte accounted for."""

    root: Path
    index: PackageIndex
    manifest_bytes: bytes
    manifest: OtaReleaseManifest
    blobs: dict[str, bytes]

    @property
    def work(self) -> Path:
        return self.root / WORK_DIR

    def attachment(self, name: str) -> bytes:
        return (self.root / name).read_bytes()


# --------------------------------------------------------------------------- files


def _file_entry(data: bytes) -> PackageFile:
    return PackageFile(sha256=sha256_ref(data), byte_length=len(data))


def package_files(root: Path) -> dict[str, Path]:
    """Every entry the index must describe: all of ``root`` but the index and ``publish/``."""
    found: dict[str, Path] = {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        if relative == INDEX_NAME or relative.split("/", 1)[0] == WORK_DIR:
            continue
        if path.is_symlink() or path.is_file():
            found[relative] = path  # a link is refused by load_package: a package holds bytes
    return found


def blob_path(sha_ref: str) -> str:
    return f"{BLOBS_DIR}/{sha_ref.removeprefix('sha256:')}"


def created_at_of(manifest_bytes: bytes) -> str:
    """The manifest's ``createdAt`` exactly as written (the bytes the approval pins)."""
    value = json.loads(manifest_bytes)["createdAt"]
    if not isinstance(value, str):
        raise PackageError("createdAt is not a string")
    return value


def write_package(
    root: Path,
    artifact: ReleaseArtifact,
    *,
    revision: int,
    source_input: str,
    source: str,
    diff_base: str,
    produced_by: str | None,
    tooling_commit: str,
    staging: StagingBinding,
    attachments: Mapping[str, bytes],
) -> PackageIndex:
    """Write the package of ``artifact`` under ``root`` (absent or empty), its index last."""
    if root.exists() and any(root.iterdir()):
        raise PackageError(f"{root} is not empty; a package is written into a fresh directory")
    manifest = artifact.manifest
    contents: dict[str, bytes] = {MANIFEST_NAME: artifact.manifest_bytes}
    contents.update({blob_path(sha): data for sha, data in artifact.blobs.items()})
    for name, data in attachments.items():
        if name in contents or name == INDEX_NAME or name.split("/", 1)[0] in (WORK_DIR, "blobs"):
            raise PackageError(f"attachment {name!r} collides with the package layout")
        contents[name] = data
    for name, data in contents.items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    index = PackageIndex(
        format=PACKAGE_FORMAT,
        format_version=1,
        release_id=manifest.release_id,
        game=manifest.game,
        client_build=manifest.client_build,
        revision=revision,
        min_data_level=manifest.min_data_level,
        source_commit=manifest.provenance.repository_commit,
        source_input=source_input,
        source=source,
        created_at=created_at_of(artifact.manifest_bytes),
        validation_note=manifest.provenance.validation,
        manifest_sha256=sha256_ref(artifact.manifest_bytes),
        manifest_byte_length=len(artifact.manifest_bytes),
        file_count=len(manifest.files),
        blob_count=len(artifact.blobs),
        total_byte_length=manifest.total_byte_length,
        diff_base=diff_base,
        produced_by=produced_by,
        tooling_commit=tooling_commit,
        staging=staging,
        files={name: _file_entry(data) for name, data in sorted(contents.items())},
    )
    (root / INDEX_NAME).write_text(
        json.dumps(index.model_dump(mode="json", by_alias=True), indent=2) + "\n",
        encoding="utf-8",
    )
    return index


def load_package(root: Path) -> LoadedPackage:
    """The package at ``root``, after proving every byte of it. :class:`PackageError` lists
    every problem found; nothing is fetched or rebuilt to repair one."""
    root = root.expanduser().resolve()
    try:
        index = parse_strict(PackageIndex, (root / INDEX_NAME).read_bytes())
    except (OSError, ValueError) as exc:
        raise PackageError(f"{root / INDEX_NAME}: not a release package index: {exc}") from exc

    problems: list[str] = []
    on_disk = package_files(root)
    problems += [f"missing {name}" for name in sorted(index.files.keys() - on_disk.keys())]
    problems += [
        f"unexpected {name} (not in the index)"
        for name in sorted(on_disk.keys() - index.files.keys())
    ]
    contents: dict[str, bytes] = {}
    for name in sorted(index.files.keys() & on_disk.keys()):
        path = on_disk[name]
        if path.is_symlink():
            problems.append(f"{name} is a symbolic link")
            continue
        data = path.read_bytes()
        if _file_entry(data) != index.files[name]:
            problems.append(
                f"{name}: {sha256_ref(data)} ({len(data)} bytes) != indexed "
                f"{index.files[name].sha256} ({index.files[name].byte_length} bytes)"
            )
            continue
        contents[name] = data
    if problems:
        raise PackageError(f"package {root} is not intact: " + "; ".join(problems))

    manifest_bytes = contents.get(MANIFEST_NAME, b"")
    if (sha256_ref(manifest_bytes), len(manifest_bytes)) != (
        index.manifest_sha256,
        index.manifest_byte_length,
    ):
        raise PackageError(f"{MANIFEST_NAME} is not the manifest the index pins")
    try:
        manifest = parse_strict(OtaReleaseManifest, manifest_bytes)
    except ValueError as exc:
        raise PackageError(f"{MANIFEST_NAME}: not a valid release manifest: {exc}") from exc

    identity = {
        "releaseId": (manifest.release_id, index.release_id),
        "revision": (manifest.release_id, f"{index.client_build}-r{index.revision}"),
        "game": (manifest.game, index.game),
        "clientBuild": (manifest.client_build, index.client_build),
        "minDataLevel": (manifest.min_data_level, index.min_data_level),
        "sourceCommit": (manifest.provenance.repository_commit, index.source_commit),
        "createdAt": (created_at_of(manifest_bytes), index.created_at),
        "validation": (manifest.provenance.validation, index.validation_note),
        "fileCount": (len(manifest.files), index.file_count),
        "totalByteLength": (manifest.total_byte_length, index.total_byte_length),
    }
    problems += [
        f"index {name} {indexed!r} != manifest {found!r}"
        for name, (found, indexed) in identity.items()
        if found != indexed
    ]
    # the index is not hashed by itself: its staging block must equal the hashed report of it
    try:
        reported = parse_strict(StagingBinding, contents.get(STAGING_BINDING_NAME, b""))
    except ValueError as exc:
        problems.append(f"{STAGING_BINDING_NAME}: {exc}")
    else:
        if reported != index.staging:
            problems.append(f"the index's staging binding differs from {STAGING_BINDING_NAME}")
    missing_copies = [n for n in STAGING_FILES.values() if n not in contents]
    if index.staging.status != "MISSING" and missing_copies:
        problems.append(f"the staging evidence copies are missing: {missing_copies}")

    needed = {file.sha256.removeprefix("sha256:") for file in manifest.files}
    present: set[str] = set()
    for name in sorted(n for n in contents if n.startswith("blobs/")):
        hex_name = name.removeprefix(f"{BLOBS_DIR}/")
        if not name.startswith(f"{BLOBS_DIR}/") or not _HEX_RE.fullmatch(hex_name):
            problems.append(f"{name} is not a blob name")
        elif sha256_ref(contents[name]) != f"sha256:{hex_name}":
            problems.append(f"{name} does not hash to its name")
        else:
            present.add(hex_name)
    problems += [f"blob sha256:{h} of the release is missing" for h in sorted(needed - present)]
    problems += [f"blob sha256:{h} is not a file of the release" for h in sorted(present - needed)]
    blobs = {f"sha256:{h}": contents[f"{BLOBS_DIR}/{h}"] for h in sorted(needed & present)}
    if len(needed) != index.blob_count:
        problems.append(f"the release has {len(needed)} blobs, the index counts {index.blob_count}")
    for file in manifest.files:
        blob = blobs.get(file.sha256)
        if blob is not None and len(blob) != file.byte_length:
            problems.append(f"{file.path}: blob is {len(blob)} bytes, manifest {file.byte_length}")
    if problems:
        raise PackageError(f"package {root} does not hold its release: " + "; ".join(problems))
    return LoadedPackage(root, index, manifest_bytes, manifest, blobs)


def rebuild_problems(repo: Path, package: LoadedPackage) -> list[str]:
    """What differs between the package and ``build-release`` at its commit: the manifest
    bytes (gate G8) and every blob. The package must be what the committed tree produces."""
    index = package.index
    try:
        rebuild = build_release(
            repo,
            index.source_commit,
            index.game,
            index.client_build,
            index.revision,
            datetime.fromisoformat(index.created_at),
            index.validation_note,
            min_data_level=index.min_data_level,
        )
    except OtaPublisherError as exc:
        return [f"cannot rebuild {index.release_id} at {index.source_commit}: {exc}"]
    problems: list[str] = []
    if rebuild.manifest_bytes != package.manifest_bytes:
        problems.append(f"{MANIFEST_NAME} is not what build-release produces at the commit")
    if rebuild.blobs != package.blobs:
        differ = sorted(
            sha
            for sha in rebuild.blobs.keys() | package.blobs.keys()
            if rebuild.blobs.get(sha) != package.blobs.get(sha)
        )
        problems.append(f"{len(differ)} blob(s) differ from the committed data: {differ[:5]}")
    return problems


# --------------------------------------------------------------------------- staging binding


def manifest_files(manifest: bytes) -> dict[str, tuple[str, int]]:
    """``path -> (sha256 ref, byteLength)`` of a release manifest."""
    parsed = parse_strict(OtaReleaseManifest, manifest)
    return {f.path: (f.sha256, f.byte_length) for f in parsed.files}


def compare_files(expected: Files, actual: Files) -> list[str]:
    """Every difference between two file sets (empty: the same paths, hashes and sizes)."""
    problems = [f"missing {path}" for path in sorted(expected.keys() - actual.keys())]
    problems += [f"unexpected {path}" for path in sorted(actual.keys() - expected.keys())]
    problems += [
        f"{path}: {tuple(actual[path])} != {tuple(expected[path])}"
        for path in sorted(expected.keys() & actual.keys())
        if tuple(expected[path]) != tuple(actual[path])
    ]
    return problems


def missing_staging(why: str) -> StagingBinding:
    return StagingBinding(
        status="MISSING",
        mode=None,
        run_id=None,
        run_url=None,
        staging_revision=None,
        staging_base=None,
        staging_manifest_sha256=None,
        problems=[why],
    )


def run_record(api_run: Mapping[str, Any]) -> dict[str, Any]:
    """The facts of a GitHub Actions run (``GET /repos/{r}/actions/runs/{id}``) the binding
    relies on, as kept in the package (``staging/run.json``)."""
    repository = api_run.get("repository")
    return {
        "id": api_run.get("id"),
        "attempt": api_run.get("run_attempt"),
        "url": api_run.get("html_url"),
        "workflowPath": api_run.get("path"),
        "event": api_run.get("event"),
        "status": api_run.get("status"),
        "conclusion": api_run.get("conclusion"),
        "headSha": api_run.get("head_sha"),
        "createdAt": api_run.get("created_at"),
        "repository": repository.get("full_name") if isinstance(repository, dict) else None,
    }


def _object(data: bytes, name: str, problems: list[str]) -> dict[str, Any]:
    try:
        value = json.loads(data)
    except ValueError as exc:
        problems.append(f"{name}: not JSON: {exc}")
        return {}
    if not isinstance(value, dict):
        problems.append(f"{name}: not a JSON object")
        return {}
    return value


def _member(doc: Mapping[str, Any], key: str) -> dict[str, Any]:
    value = doc.get(key)
    return value if isinstance(value, dict) else {}


def bind_staging(
    manifest_bytes: bytes,
    *,
    report: bytes,
    staging_manifest: bytes,
    update_evidence: bytes,
    run: Mapping[str, Any],
    repository: str,
) -> StagingBinding:
    """Is the staging run ``run`` evidence for the release of ``manifest_bytes``?

    ``PASS`` only when all hold: the run is a successful run of the staging verification
    workflow in ``repository``; the report is that run's (its tooling head is the run's commit)
    and a PASS for this build; the release it staged and the copy the client installed have
    exactly this release's files (path, SHA-256, size), game, build and data level. The source
    commit may differ only with those files proven equal (``equalFiles``); otherwise ``FAIL``.
    """
    problems: list[str] = []
    mine = parse_strict(OtaReleaseManifest, manifest_bytes)
    my_files = manifest_files(manifest_bytes)

    if run.get("workflowPath") != STAGING_WORKFLOW:
        problems.append(f"the run is of {run.get('workflowPath')!r}, not {STAGING_WORKFLOW}")
    if run.get("repository") != repository:
        problems.append(f"the run belongs to {run.get('repository')!r}, not {repository!r}")
    if (run.get("status"), run.get("conclusion")) != ("completed", "success"):
        problems.append(
            f"the run is {run.get('status')!r}/{run.get('conclusion')!r}, not completed/success"
        )
    run_id = run.get("id")
    run_url = run.get("url")

    doc = _object(report, "staging report", problems)
    revision = doc.get("revision")
    base = doc.get("baseBuild")
    if (doc.get("format"), doc.get("formatVersion")) != (STAGING_REPORT_FORMAT, 1):
        problems.append("the staging report is not a talentforge-ota-staging-verification v1")
    if doc.get("verdict") != "PASS":
        problems.append(f"the staging verdict is {doc.get('verdict')!r}, not PASS")
    if doc.get("candidateBuild") != mine.client_build:
        problems.append(
            f"staging verified candidate {doc.get('candidateBuild')!r}, not {mine.client_build}"
        )
    if not (isinstance(revision, str) and _COMMIT_RE.fullmatch(revision)):
        problems.append(f"the staging revision {revision!r} is not a commit id")
        revision = None
    facts = _member(doc, "facts")
    tooling = _member(facts, "tooling")
    if tooling.get("head") != run.get("headSha"):
        problems.append(
            f"the report's tooling head {tooling.get('head')!r} is not the run's commit "
            f"{run.get('headSha')!r}: the report is not this run's"
        )

    staged_sha = sha256_ref(staging_manifest)
    try:
        staged: OtaReleaseManifest | None = parse_strict(OtaReleaseManifest, staging_manifest)
    except ValueError as exc:
        problems.append(f"the staged release manifest: {exc}")
        staged = None
    if staged is not None:
        for name, found, expected in (
            ("game", staged.game, mine.game),
            ("clientBuild", staged.client_build, mine.client_build),
            ("minDataLevel", staged.min_data_level, mine.min_data_level),
            ("repositoryCommit", staged.provenance.repository_commit, revision),
        ):
            if found != expected:
                problems.append(f"the staged release's {name} {found!r} != {expected!r}")
        recorded = _member(_member(facts, "releases"), staged.release_id)
        if recorded.get("manifestSha256") != staged_sha:
            problems.append(
                f"the staging report does not pin the staged manifest {staged_sha} of "
                f"{staged.release_id}"
            )
        differs = compare_files(my_files, manifest_files(staging_manifest))
        if differs:
            problems.append(
                f"staging verified other files than this release ({len(differs)} "
                "difference(s): " + "; ".join(differs[:5]) + ")"
            )

    update = _object(update_evidence, "client update evidence", problems)
    expected_request = {"base": base, "candidate": mine.client_build, "revision": revision}
    if update.get("requested") != expected_request:
        problems.append(
            f"the client evidence requested {update.get('requested')} != {expected_request}"
        )
    for key, value in (("candidate", mine.client_build), ("commit", revision)):
        if update.get(key) != value:
            problems.append(f"the client evidence {key} {update.get(key)!r} != {value!r}")
    raw = update.get("installedFiles")
    installed: dict[str, tuple[str, int]] = {}
    if isinstance(raw, dict):
        for path, entry in raw.items():
            if (
                isinstance(entry, list)
                and len(entry) == 2
                and isinstance(entry[0], str)
                and isinstance(entry[1], int)
            ):
                installed[path] = (entry[0], entry[1])
            else:
                problems.append(f"the client evidence entry of {path} is malformed")
    if not installed:
        problems.append("the client evidence lists no installed files")
    else:
        differs = compare_files(my_files, installed)
        if differs:
            problems.append(
                f"the client installed other files than this release ({len(differs)} "
                "difference(s): " + "; ".join(differs[:5]) + ")"
            )

    mode: Literal["sameCommit", "equalFiles"] | None = None
    if not problems:
        mode = "sameCommit" if revision == mine.provenance.repository_commit else "equalFiles"
    return StagingBinding(
        status="FAIL" if problems else "PASS",
        mode=mode,
        run_id=run_id if isinstance(run_id, int) else None,
        run_url=run_url if isinstance(run_url, str) else None,
        staging_revision=revision,
        staging_base=base if isinstance(base, str) else None,
        staging_manifest_sha256=staged_sha,
        problems=problems,
    )


def rebind_staging(package: LoadedPackage, repository: str) -> StagingBinding:
    """The binding re-proven from the copies inside the package (no network)."""
    if package.index.staging.status == "MISSING":
        return package.index.staging
    run = json.loads(package.attachment(STAGING_FILES["run"]))
    return bind_staging(
        package.manifest_bytes,
        report=package.attachment(STAGING_FILES["report"]),
        staging_manifest=package.attachment(STAGING_FILES["manifest"]),
        update_evidence=package.attachment(STAGING_FILES["update"]),
        run=run if isinstance(run, dict) else {},
        repository=repository,
    )


# --------------------------------------------------------------------------- approval record


def approval_record_at(repo: Path, ref: str, release_id: str) -> bytes | None:
    """``docs/ota/releases/<releaseId>.md`` as committed at ``ref``; ``None`` when absent."""
    result = subprocess.run(
        ["git", "-C", str(repo), "show", f"{ref}:{APPROVALS_DIR}/{release_id}.md"],
        capture_output=True,
    )
    return result.stdout if result.returncode == 0 else None


def approval_record_problems(
    text: str, *, release_id: str, manifest_sha256: str, source_commit: str
) -> list[str]:
    """What keeps an approval record from approving exactly this release's manifest bytes.

    Stricter than the publisher's parser, which checks the pin only when one is present: the
    record must pin this manifest, name this release and its source commit, keep no unfilled
    ``<OWNER: …>`` line of the template, and state its accepted gates - none, or only the
    FLAG-able G5/G7 (a G7 with its live-build evidence). Which of them it accepts is the
    owner's decision."""
    fields: dict[str, str] = {}
    for line in text.splitlines():
        for name in (
            "Release",
            "Manifest SHA-256",
            "Source commit",
            "Accepted gates",
            "Live-build evidence",
        ):
            prefix = f"- {name}: "
            if line.startswith(prefix) and name not in fields:
                fields[name] = line.removeprefix(prefix).strip()
    problems = []
    for name, expected in (
        ("Release", release_id),
        ("Manifest SHA-256", manifest_sha256),
        ("Source commit", source_commit),
    ):
        if name not in fields:
            problems.append(f"the record has no '- {name}:' line")
        elif fields[name] != expected:
            problems.append(f"the record's {name} {fields[name]!r} != this release's {expected!r}")
    if "Accepted gates" not in fields:
        problems.append("the record has no '- Accepted gates:' line")
    elif OWNER_PLACEHOLDER not in fields["Accepted gates"]:  # unfilled: counted below
        flaggable = sorted(gate.value for gate in FLAGGABLE_GATES)
        accepted = [name.strip() for name in fields["Accepted gates"].split(",") if name.strip()]
        if invalid := [name for name in accepted if name not in flaggable]:
            problems.append(
                f"the record accepts {invalid}: only {', '.join(flaggable)} may be accepted"
            )
        if "G7" in accepted and not fields.get("Live-build evidence"):
            problems.append("the record accepts G7 without its 'Live-build evidence'")
    unfilled = [line.strip() for line in text.splitlines() if OWNER_PLACEHOLDER in line]
    if unfilled:
        problems.append(f"the record still has {len(unfilled)} unfilled template line(s)")
    return problems


def render_approval_template(index: PackageIndex, *, gates: list[tuple[str, str, str]]) -> str:
    """The approval record of this package for ``docs/ota/releases/<releaseId>.md``, from the
    committed template ``docs/ota/release-approval.template.md``.

    Every machine-checked pin is filled in; every decision is left to the owner as an
    ``<OWNER: …>`` line, which neither the publisher nor the helper accepts. Nothing here
    accepts a gate."""
    staging = index.staging
    staging_line = (
        f"{staging.run_url} - PASS, source `{staging.staging_revision}`, base "
        f"`{staging.staging_base}`, binding `{staging.mode}`"
        if staging.status == "PASS"
        else f"{staging.status}: " + "; ".join(staging.problems)
    )
    template = string.Template(APPROVAL_TEMPLATE.read_text(encoding="utf-8"))
    return template.substitute(
        release_id=index.release_id,
        manifest_sha256=index.manifest_sha256,
        manifest_byte_length=index.manifest_byte_length,
        source_commit=index.source_commit,
        game=index.game,
        client_build=index.client_build,
        min_data_level=index.min_data_level,
        created_at=index.created_at,
        file_count=index.file_count,
        total_byte_length=f"{index.total_byte_length:,}",
        diff_base=index.diff_base,
        package=index.produced_by or "(local package build)",
        staging=staging_line,
        gate_rows="\n".join(
            f"| {gate} | {status} | {detail.replace('|', '/')} |" for gate, status, detail in gates
        ),
        flagged=", ".join(gate for gate, status, _ in gates if status == "FLAG") or "-",
    )
