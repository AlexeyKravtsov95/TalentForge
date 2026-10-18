"""Publisher validation gates G1-G8 (publisher-and-security.md §2.1).

Every gate is a pure function over its inputs returning a typed :class:`GateResult`
with one of three statuses:

* ``PASS`` - the check succeeded;
* ``FLAG`` - a human must accept the finding in an approval record before
  ``promote``; only G5 (new warnings) and G7 (live-build evidence) may flag;
* ``STOP`` - the pipeline ends here. No approval record, command-line flag,
  environment variable or configuration can pass a STOP: the gate functions take no
  such parameter and the caller treats STOP as final.

G6 is the load-bearing one (publisher design §2.1a): wire validity is not functional
support, and an owner cannot approve an allocation-engine-unsupported mechanic into
production. It calls ``new_unsupported_mechanics`` - which has no override parameter by
construction - and a release whose ``minDataLevel`` has no frozen baseline file is a
STOP for production (a level without a baseline does not exist).
"""

from __future__ import annotations

import subprocess
from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum
from pathlib import Path

from talent_data.games import get_game
from talent_data.models.manifest import DatasetManifest
from talent_data.models.talents import TalentDataDocument
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.mechanics import load_baseline, new_unsupported_mechanics
from talent_data.ota.models import (
    CatalogRelease,
    Channel,
    FileRole,
    OtaCatalog,
    OtaReleaseManifest,
)
from talent_data.ota.release import ReleaseArtifact, build_release
from talent_data.ota.store import BlobStore, dataset_manifest_of_release
from talent_data.ota.verify import OtaError, parse_strict

BUNDLED_FILES_DIR = Path("mobile/composeApp/src/commonMain/composeResources/files")


class Gate(StrEnum):
    G1 = "G1"  # source
    G2 = "G2"  # pipeline validation
    G3 = "G3"  # contract
    G4 = "G4"  # same-build identity
    G5 = "G5"  # new warnings
    G6 = "G6"  # unsupported mechanics
    G7 = "G7"  # live-build evidence
    G8 = "G8"  # reproducibility


class GateStatus(StrEnum):
    PASS = "PASS"
    FLAG = "FLAG"
    STOP = "STOP"


#: the only gates an approval record may accept (publisher design §2.2)
FLAGGABLE_GATES = frozenset({Gate.G5, Gate.G7})


@dataclass(frozen=True)
class GateResult:
    gate: Gate
    status: GateStatus
    detail: str

    @property
    def stopped(self) -> bool:
        return self.status is GateStatus.STOP

    @property
    def flagged(self) -> bool:
        return self.status is GateStatus.FLAG


def any_stop(results: list[GateResult]) -> GateResult | None:
    return next((r for r in results if r.stopped), None)


# --------------------------------------------------------------------------- release under test


@dataclass(frozen=True)
class ReleaseUnderTest:
    """A release manifest being validated, plus its rebuild at the provenance commit.

    ``manifest_bytes`` are the bytes on disk (what a catalog would pin); ``rebuild`` is
    what :func:`talent_data.ota.release.build_release` produces from the commit the
    manifest names - the same function ``build-release`` used, so G8 compares two
    independent runs. All content gates run on the rebuild: files are read from the
    committed tree, never from a working tree.
    """

    manifest_path: Path
    manifest_bytes: bytes
    manifest: OtaReleaseManifest
    rebuild: ReleaseArtifact


def load_under_test(manifest_path: Path, repo_root: Path) -> ReleaseUnderTest:
    data = manifest_path.read_bytes()
    try:
        manifest = parse_strict(OtaReleaseManifest, data)
    except ValueError as exc:
        raise OtaPublisherError(f"{manifest_path}: not a valid release manifest: {exc}") from exc
    revision = release_revision(manifest.release_id)
    rebuild = build_release(
        repo_root,
        manifest.provenance.repository_commit,
        manifest.game,
        manifest.client_build,
        revision,
        manifest.created_at,
        manifest.provenance.validation,
        min_data_level=manifest.min_data_level,
    )
    return ReleaseUnderTest(
        manifest_path=manifest_path,
        manifest_bytes=data,
        manifest=manifest,
        rebuild=rebuild,
    )


def release_revision(release_id: str) -> int:
    return int(release_id.rsplit("-r", 1)[1])


def under_test_entry(under_test: ReleaseUnderTest, published_at: datetime) -> CatalogRelease:
    from talent_data.ota.catalog import catalog_entry

    return catalog_entry(under_test.rebuild.manifest_bytes, under_test.manifest, published_at)


def _dataset_manifest(under_test: ReleaseUnderTest) -> DatasetManifest:
    path = f"dataset/{under_test.manifest.game}/{under_test.manifest.client_build}/manifest.json"
    try:
        return parse_strict(DatasetManifest, under_test.rebuild.snapshot[path])
    except ValueError as exc:
        raise OtaPublisherError(f"{path}: not a valid dataset manifest: {exc}") from exc


def talent_documents(under_test: ReleaseUnderTest) -> list[TalentDataDocument]:
    docs: list[TalentDataDocument] = []
    for file in under_test.rebuild.manifest.files:
        if file.role is not FileRole.TALENT_DATA:
            continue
        try:
            docs.append(parse_strict(TalentDataDocument, under_test.rebuild.snapshot[file.path]))
        except ValueError as exc:
            raise OtaPublisherError(f"{file.path}: not valid talent data: {exc}") from exc
    return docs


# --------------------------------------------------------------------------- gates G1-G8


def gate_g1_source(
    under_test: ReleaseUnderTest, repo_root: Path, *, main_ref: str = "origin/main"
) -> GateResult:
    """The provenance commit exists and is on ``main_ref``; content was read from it."""
    commit = under_test.manifest.provenance.repository_commit

    def git(*args: str) -> subprocess.CompletedProcess[bytes]:
        return subprocess.run(["git", "-C", str(repo_root), *args], capture_output=True)

    if git("cat-file", "-e", f"{commit}^{{commit}}").returncode != 0:
        return GateResult(
            Gate.G1, GateStatus.STOP, f"commit {commit} does not exist in {repo_root}"
        )
    if git("rev-parse", "--verify", main_ref).returncode != 0:
        return GateResult(
            Gate.G1,
            GateStatus.STOP,
            f"ref {main_ref} not found; cannot prove the commit is on main",
        )
    if git("merge-base", "--is-ancestor", commit, main_ref).returncode != 0:
        return GateResult(Gate.G1, GateStatus.STOP, f"commit {commit} is not on {main_ref}")
    return GateResult(
        Gate.G1,
        GateStatus.PASS,
        f"commit {commit} is on {main_ref}; the release was read from the committed "
        "tree (git archive), never from a working tree",
    )


def gate_g2_pipeline(under_test: ReleaseUnderTest) -> GateResult:
    """The build's dataset manifest is complete and error-free for the game."""
    try:
        dataset = _dataset_manifest(under_test)
    except OtaPublisherError as exc:
        return GateResult(Gate.G2, GateStatus.STOP, str(exc))
    m = under_test.manifest
    problems: list[str] = []
    if dataset.game != m.game or dataset.client_build != m.client_build:
        problems.append(f"dataset manifest is {dataset.game}/{dataset.client_build}")
    expected = set(get_game(m.game).dataset_class_tokens)
    listed = [entry.token for entry in dataset.classes]
    if set(listed) != expected:
        missing, extra = sorted(expected - set(listed)), sorted(set(listed) - expected)
        problems.append(f"class inventory differs: missing {missing}, extra {extra}")
    if len(listed) != len(set(listed)):
        problems.append("a class is listed twice")
    for entry in dataset.classes:
        if entry.validation.errors != 0:
            problems.append(f"{entry.token}: {entry.validation.errors} validation error(s)")
    if problems:
        return GateResult(Gate.G2, GateStatus.STOP, "; ".join(problems))
    return GateResult(
        Gate.G2,
        GateStatus.PASS,
        f"{len(listed)} classes, 0 validation errors, complete for game {m.game!r}",
    )


def gate_g3_contract(under_test: ReleaseUnderTest, now: datetime) -> GateResult:
    """The release passes the reference verifier end to end (contract §6.3-6.4)."""
    from talent_data.ota.verify import verify_release_files, verify_release_manifest

    entry = under_test_entry(under_test, published_at=now)
    try:
        manifest = verify_release_manifest(
            under_test.manifest_bytes,
            entry,
            game=under_test.manifest.game,
            supported_data_level=under_test.manifest.min_data_level,
        )
        verify_release_files(
            manifest,
            lambda file: under_test.rebuild.blobs[file.sha256],
        )
    except OtaError as exc:
        return GateResult(Gate.G3, GateStatus.STOP, f"{exc.code.value}: {exc.detail}")
    return GateResult(
        Gate.G3,
        GateStatus.PASS,
        f"{len(manifest.files)} files, total {manifest.total_byte_length} bytes, "
        "verified by the reference verifier",
    )


def _fingerprint(dataset: DatasetManifest) -> list[tuple[str, str, tuple[int, ...], str]]:
    return [(e.token, e.file, tuple(sorted(e.tree_ids)), e.content_hash) for e in dataset.classes]


def gate_g4_same_build_identity(
    under_test: ReleaseUnderTest,
    live: OtaCatalog | None,
    store: BlobStore,
    *,
    bundled_root: Path | None,
) -> GateResult:
    """Talent data of a client build never changes between releases (contract §4).

    Compares the release's dataset manifest classes (token, file, treeIds,
    contentHash) with every published release of the same build and, read-only, with
    the copy bundled into shipped apps."""
    m = under_test.manifest
    try:
        mine = _fingerprint(_dataset_manifest(under_test))
    except OtaPublisherError as exc:
        return GateResult(Gate.G4, GateStatus.STOP, str(exc))

    compared: list[str] = []
    for entry in live.releases if live is not None else []:
        if entry.client_build != m.client_build or entry.release_id == m.release_id:
            continue
        try:
            theirs = _fingerprint(dataset_manifest_of_release(store, entry))
        except OtaPublisherError as exc:
            return GateResult(Gate.G4, GateStatus.STOP, f"{entry.release_id}: {exc}")
        if theirs != mine:
            return GateResult(
                Gate.G4,
                GateStatus.STOP,
                f"talent data differs from published {entry.release_id} for the same "
                f"client build {m.client_build} (OD-8 forbids corrected normalization "
                "of a published build)",
            )
        compared.append(entry.release_id)

    if bundled_root is not None:
        bundled = bundled_root / "dataset" / m.game / m.client_build / "manifest.json"
        if bundled.is_file():
            try:
                theirs = _fingerprint(parse_strict(DatasetManifest, bundled.read_bytes()))
            except ValueError as exc:
                return GateResult(Gate.G4, GateStatus.STOP, f"bundled copy: {exc}")
            if theirs != mine:
                return GateResult(
                    Gate.G4,
                    GateStatus.STOP,
                    f"talent data differs from the copy bundled in shipped apps "
                    f"({bundled}) for client build {m.client_build}",
                )
            compared.append("bundled copy")

    if compared:
        return GateResult(Gate.G4, GateStatus.PASS, f"identical to: {', '.join(compared)}")
    return GateResult(
        Gate.G4, GateStatus.PASS, f"no published or bundled dataset of build {m.client_build}"
    )


def gate_g6_unsupported_mechanics(under_test: ReleaseUnderTest, *, channel: Channel) -> GateResult:
    """G6: no release may carry a mechanic the allocation engine cannot handle.

    STOP, without override. The only path for a new mechanic is an app release that
    supports it, a new frozen data level, and only then a release at that level
    (publisher design §2.1a) - which is why this function has (and takes) no approval
    parameter at all. A ``minDataLevel`` without a baseline file does not exist as a
    level: STOP for production; staging may carry one only while the app that
    implements the level is being developed, and it can never be promoted.
    """
    level = under_test.manifest.min_data_level
    try:
        baseline = load_baseline(level)
    except FileNotFoundError:
        if channel is Channel.PRODUCTION:
            return GateResult(
                Gate.G6,
                GateStatus.STOP,
                f"data level {level} has no frozen baseline (docs/ota/data-levels/"
                f"level-{level}.json): the level does not exist, so production cannot "
                "carry this release",
            )
        return GateResult(
            Gate.G6,
            GateStatus.PASS,
            f"data level {level} has no baseline yet: staging may carry this release "
            "while the implementing app is being tested, but it cannot be promoted",
        )
    except ValueError as exc:
        return GateResult(Gate.G6, GateStatus.STOP, f"baseline of level {level} is invalid: {exc}")

    findings = new_unsupported_mechanics(talent_documents(under_test), baseline)
    if findings:
        listed = ", ".join(
            f"{f.class_token} tree {f.tree_id} node {f.node_id} {f.code}" for f in findings
        )
        return GateResult(
            Gate.G6,
            GateStatus.STOP,
            f"{len(findings)} engine-unsupported mechanic(s) outside the level-{level} "
            f"baseline: {listed}. This cannot be approved away; see "
            "publisher-and-security.md §2.1a",
        )
    return GateResult(
        Gate.G6,
        GateStatus.PASS,
        f"engine findings are within the level-{level} baseline "
        f"({len(baseline.known_unsupported)} known)",
    )


def gate_g5_new_warnings(
    under_test: ReleaseUnderTest,
    live: OtaCatalog | None,
    store: BlobStore,
    g6: GateResult,
) -> GateResult:
    """New or grown warning codes vs. the active production release - needs approval.

    A warning that reflects an engine-unsupported mechanic is G6's, never G5's: when
    G6 stopped, this gate is not evaluated (the pipeline is already stopped)."""

    def warnings_by_code(dataset: DatasetManifest) -> dict[str, int]:
        totals: dict[str, int] = {}
        for entry in dataset.classes:
            for code, count in entry.validation.warnings_by_code.items():
                totals[code] = totals.get(code, 0) + count
        return totals

    if g6.stopped:
        return GateResult(Gate.G5, GateStatus.PASS, "not evaluated: G6 stopped the pipeline")
    try:
        mine = warnings_by_code(_dataset_manifest(under_test))
    except OtaPublisherError as exc:
        return GateResult(Gate.G5, GateStatus.STOP, str(exc))

    if live is None:
        return GateResult(Gate.G5, GateStatus.PASS, "no active production release to compare")
    active_entry = next((r for r in live.releases if r.release_id == live.active_release_id), None)
    if active_entry is None or active_entry.release_id == under_test.manifest.release_id:
        return GateResult(Gate.G5, GateStatus.PASS, "the active production release is this release")
    try:
        theirs = warnings_by_code(dataset_manifest_of_release(store, active_entry))
    except OtaPublisherError as exc:
        return GateResult(Gate.G5, GateStatus.STOP, str(exc))

    grown = {
        code: (mine.get(code, 0), theirs.get(code, 0))
        for code in sorted(mine.keys() | theirs.keys())
        if mine.get(code, 0) > theirs.get(code, 0)
    }
    if not grown:
        return GateResult(
            Gate.G5,
            GateStatus.PASS,
            f"warnings not worse than {active_entry.release_id} "
            f"({sum(mine.values())} vs {sum(theirs.values())})",
        )
    detail = ", ".join(f"{code} {new}>{old}" for code, (new, old) in grown.items())
    return GateResult(
        Gate.G5,
        GateStatus.FLAG,
        f"new or grown warnings vs {active_entry.release_id}: {detail}; needs an "
        "approval record (OD-12)",
    )


# --------------------------------------------------------------------------- approval records


@dataclass(frozen=True)
class ApprovalRecord:
    """The OD-12 approval record of one promotion: ``docs/ota/releases/<id>.md``."""

    release_id: str
    accepted_gates: frozenset[Gate]
    live_build_evidence: str | None
    #: pins the exact manifest bytes the record approves, when the record states one
    manifest_sha256: str | None = None

    def accepts(self, gate: Gate) -> bool:
        return gate in self.accepted_gates


def load_approval_record(release_id: str, docs_dir: Path) -> ApprovalRecord | None:
    """Parse ``docs_dir/<releaseId>.md``; ``None`` when no record exists.

    A record can only accept G5/G7 findings: one that names a STOP gate is itself an
    error (publisher design §2.2). The optional ``Manifest SHA-256`` line pins the
    manifest bytes being approved; ``promote`` refuses a record whose pin does not
    match the release it is presented for."""
    path = docs_dir / f"{release_id}.md"
    if not path.is_file():
        return None
    fields: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        for name, prefix in (
            ("release", "- Release: "),
            ("accepted", "- Accepted gates: "),
            ("evidence", "- Live-build evidence: "),
            ("manifest", "- Manifest SHA-256: "),
        ):
            if line.startswith(prefix) and name not in fields:
                fields[name] = line.removeprefix(prefix).strip()
    release = fields.get("release", "")
    if release != release_id:
        raise OtaPublisherError(
            f"{path}: approval record names release {release!r}, expected {release_id!r}"
        )
    raw_names = [n.strip() for n in fields.get("accepted", "").split(",")]
    accepted_names = [name for name in raw_names if name]
    try:
        accepted = frozenset(Gate(name) for name in accepted_names)
    except ValueError as exc:
        raise OtaPublisherError(f"{path}: unknown gate in 'Accepted gates': {exc}") from exc
    invalid = accepted - FLAGGABLE_GATES
    if invalid:
        raise OtaPublisherError(
            f"{path}: a STOP gate cannot be approved (found {sorted(g.value for g in invalid)}); "
            "an approval record may only accept G5 and G7 findings"
        )
    evidence = fields.get("evidence")
    if Gate.G7 in accepted and not evidence:
        raise OtaPublisherError(f"{path}: G7 acceptance requires 'Live-build evidence'")
    manifest_sha = fields.get("manifest")
    if manifest_sha is not None and not manifest_sha.startswith("sha256:"):
        raise OtaPublisherError(
            f"{path}: 'Manifest SHA-256' must be 'sha256:<64 hex>', got {manifest_sha!r}"
        )
    return ApprovalRecord(
        release_id=release_id,
        accepted_gates=accepted,
        live_build_evidence=evidence,
        manifest_sha256=manifest_sha,
    )


def gate_g7_live_build_evidence(
    under_test: ReleaseUnderTest, *, channel: Channel, approval: ApprovalRecord | None
) -> GateResult:
    """Production promotion needs recorded live-build evidence (publisher design §4).

    ``is_bgdl=false`` on wago.tools is not evidence that the realms run the build;
    only a recorded observation (client screenshot on a live realm, official patch
    note) is. Staging carries no such requirement."""
    if channel is Channel.STAGING:
        return GateResult(Gate.G7, GateStatus.PASS, "staging needs no live-build evidence")
    if approval is not None and approval.live_build_evidence:
        return GateResult(
            Gate.G7, GateStatus.PASS, f"recorded evidence: {approval.live_build_evidence}"
        )
    return GateResult(
        Gate.G7,
        GateStatus.FLAG,
        "no recorded live-build evidence for this client build; staging is allowed, "
        "production needs an approval record with evidence (OD-15)",
    )


def gate_g8_reproducibility(under_test: ReleaseUnderTest) -> GateResult:
    """Rebuilding from the same commit must yield the identical manifest and blobs."""
    rebuild = under_test.rebuild
    if under_test.manifest_bytes != rebuild.manifest_bytes:
        return GateResult(
            Gate.G8,
            GateStatus.STOP,
            f"{under_test.manifest_path} is not what build-release produces for commit "
            f"{under_test.manifest.provenance.repository_commit} with the same "
            "createdAt/validation note; rebuild before publishing",
        )
    return GateResult(
        Gate.G8,
        GateStatus.PASS,
        f"manifest bytes and {len(rebuild.blobs)} blob(s) reproduce from commit "
        f"{under_test.manifest.provenance.repository_commit[:12]}",
    )


# --------------------------------------------------------------------------- orchestration


def validate_release(
    under_test: ReleaseUnderTest,
    *,
    repo_root: Path,
    store: BlobStore,
    live: OtaCatalog | None,
    channel: Channel,
    now: datetime,
    approval: ApprovalRecord | None = None,
    main_ref: str = "origin/main",
    bundled_root: Path | None = None,
) -> list[GateResult]:
    """Run G1-G8 and return the results in gate order.

    The list is the whole verdict: callers refuse to publish on any STOP, and require
    an approval record covering every FLAG. There is no parameter anywhere that
    changes a STOP into anything else.
    """
    if bundled_root is None:
        bundled_root = repo_root / BUNDLED_FILES_DIR
    g1 = gate_g1_source(under_test, repo_root, main_ref=main_ref)
    g8 = gate_g8_reproducibility(under_test)
    g3 = gate_g3_contract(under_test, now)
    g2 = gate_g2_pipeline(under_test)
    g4 = gate_g4_same_build_identity(under_test, live, store, bundled_root=bundled_root)
    g6 = gate_g6_unsupported_mechanics(under_test, channel=channel)
    g5 = gate_g5_new_warnings(under_test, live, store, g6)
    g7 = gate_g7_live_build_evidence(under_test, channel=channel, approval=approval)
    return sorted([g1, g2, g3, g4, g5, g6, g7, g8], key=lambda r: r.gate.value)
