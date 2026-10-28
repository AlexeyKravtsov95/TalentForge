"""Prepare a production OTA release package for the owner (M5.OTA.PROD.4) - CI side.

One run builds one release from one commit fixed at the start and writes a self-contained
package (src/talent_data/ota/release_package.py) for the owner's review and publication:

  1. resolve  the source revision (empty: the HEAD of origin/main) to one full commit id on
              origin/main; the release build and the diff base must be committed there
  2. build    the release with the existing publisher (build-release at that commit, createdAt
              fixed once); the exact manifest bytes and every blob go into the package
  3. checks   gates G1-G8 for the production channel against the diff base rebuilt from the
              same commit (a stand-in for the live release: CI never reads production), the
              review diff (ota report) against it, the approval record committed on origin/main
  4. staging  the staging verification run given by --staging-run is bound to this release from
              its report, the staged manifest and the client's installed copy (path, SHA-256,
              size); a PASS of another commit counts only with those files proven equal
  5. report   package.json (every file hashed), report/conditions.md: what is met, what is the
              owner's, what is missing; the package is re-loaded and proven before exit

    python scripts/ota_release_package.py build <out> --build 1.60.1.70532 [--revision 1] \\
        [--source-revision <sha|ref>] [--base 1.60.1.70450] \\
        --staging-run run.json --staging-artifact <dir> [--repository owner/name]

<out> must be empty; the package is <out>/<releaseId>/. Exit codes: 0 ready for the owner's
review (package proven, no STOP gate, staging bound), 1 a FAIL (a STOP gate, a staging run that
is not evidence for these files, a package that does not prove), 3 the staging evidence is
missing, 2 usage. Nothing is signed or published: CI holds neither the production signing key
nor an S3 credential, and no condition the owner decides (approval, G5/G7) is taken here.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from talent_data.cli.ota import STANDARD_VALIDATION_NOTE  # noqa: E402
from talent_data.ota import release_package as rp  # noqa: E402
from talent_data.ota.catalog import catalog_entry, next_catalog  # noqa: E402
from talent_data.ota.errors import OtaPublisherError  # noqa: E402
from talent_data.ota.gates import (  # noqa: E402
    ApprovalRecord,
    GateResult,
    GateStatus,
    load_approval_record,
    under_test_from_bytes,
    validate_release,
)
from talent_data.ota.models import Channel  # noqa: E402
from talent_data.ota.release import ReleaseArtifact, build_release  # noqa: E402
from talent_data.ota.report import render_review_report  # noqa: E402
from talent_data.ota.store import LocalDirectoryStore, upload_blobs  # noqa: E402
from talent_data.ota.verify import sha256_ref  # noqa: E402
from talent_data.prepare import staging_verify as sv  # noqa: E402

GAME = "forever"
EXIT_READY, EXIT_FAIL, EXIT_USAGE, EXIT_MISSING = 0, 1, 2, 3
DEFAULT_BASE = "1.60.1.70450"


def _stamp(instant: dt.datetime) -> str:
    return instant.astimezone(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def _git(*args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(ROOT), *args], capture_output=True, text=True, check=True
    ).stdout.strip()


def staging_inputs(artifact: Path, run: dict[str, Any]) -> dict[str, bytes]:
    """The binding's inputs from a downloaded staging artifact (``gh run download``): the
    run's ``ota-staging-verify-<id>-<attempt>`` directory, or the directory holding it."""
    name = f"ota-staging-verify-{run.get('id')}-{run.get('attempt')}"
    root = artifact / name if (artifact / name).is_dir() else artifact
    if root.name != name:
        raise OtaPublisherError(f"{artifact} holds no artifact {name} of the staging run")
    report = (root / "staging-report.json").read_bytes()
    candidate = json.loads(report).get("candidateBuild")
    return {
        "report": report,
        "manifest": (root / "releases" / f"{candidate}-r1" / "release.json").read_bytes(),
        "update": (root / "client" / f"{rp.STAGING_UPDATE_TEST}.json").read_bytes(),
    }


def bind(
    args: argparse.Namespace, artifact: ReleaseArtifact
) -> tuple[rp.StagingBinding, dict[str, bytes]]:
    """The staging binding and the evidence copies the package keeps."""
    if args.staging_run is None:
        return rp.missing_staging("no staging verification run was given (--staging-run)"), {}
    run = rp.run_record(json.loads(args.staging_run.read_text(encoding="utf-8")))
    try:
        inputs = staging_inputs(args.staging_artifact, run)
    except (OSError, ValueError, OtaPublisherError) as exc:
        failed = rp.StagingBinding(
            status="FAIL",
            mode=None,
            run_id=run["id"] if isinstance(run.get("id"), int) else None,
            run_url=run["url"] if isinstance(run.get("url"), str) else None,
            staging_revision=None,
            staging_base=None,
            staging_manifest_sha256=None,
            problems=[f"the staging artifact is unusable: {exc}"],
        )
        return failed, {}
    staging = rp.bind_staging(
        artifact.manifest_bytes,
        report=inputs["report"],
        staging_manifest=inputs["manifest"],
        update_evidence=inputs["update"],
        run=run,
        repository=args.repository,
    )
    copies = {rp.STAGING_FILES[key]: data for key, data in inputs.items()}
    copies[rp.STAGING_FILES["run"]] = (json.dumps(run, indent=2) + "\n").encode()
    return staging, copies


def ci_gates(
    artifact: ReleaseArtifact,
    base: ReleaseArtifact,
    approval: ApprovalRecord | None,
    now: dt.datetime,
) -> tuple[list[GateResult], str]:
    """Gates G1-G8 (production) and the review diff, against the diff base rebuilt from the
    same commit as a stand-in for the live release: CI never reads production."""
    with tempfile.TemporaryDirectory(prefix="ota-package-") as scratch:
        store = LocalDirectoryStore(Path(scratch))
        sha, data = base.manifest_blob
        upload_blobs(store, {sha: data, **base.blobs})
        stand_in = next_catalog(
            None,
            game=GAME,
            channel=Channel.PRODUCTION,
            active_release_id=base.release_id,
            append=catalog_entry(base.manifest_bytes, base.manifest, published_at=now),
            issued_at=now,
        ).catalog
        under_test = under_test_from_bytes(
            artifact.manifest_bytes, ROOT, origin=Path(rp.MANIFEST_NAME)
        )
        gates = validate_release(
            under_test,
            repo_root=ROOT,
            store=store,
            live=stand_in,
            channel=Channel.PRODUCTION,
            now=now,
            approval=approval,
        )
        return gates, render_review_report(under_test, stand_in, store)


def conditions(
    *,
    gates: list[GateResult],
    staging: rp.StagingBinding,
    approval_problems: list[str] | None,
    accepted: set[str],
    package_problem: str | None,
) -> list[dict[str, str]]:
    """What publication needs, each ``MET`` / ``FAIL`` / ``MISSING`` / ``OWNER``."""
    stops = [r.gate.value for r in gates if r.status is GateStatus.STOP]
    flags = {r.gate.value for r in gates if r.status is GateStatus.FLAG}
    open_flags = sorted(flags - accepted)
    rows = [
        (
            "package",
            "the package holds the exact manifest and every blob, each hash proven",
            "FAIL" if package_problem else "MET",
            package_problem or "re-loaded and proven",
        ),
        (
            "gates",
            "gates G1-G8 (production) have no STOP",
            "FAIL" if stops else "MET",
            f"STOP: {', '.join(stops)}" if stops else f"FLAG: {', '.join(sorted(flags)) or 'none'}",
        ),
        (
            "staging",
            "a staging verification PASS is bound to exactly these files",
            "MET" if staging.status == "PASS" else staging.status,
            f"{staging.run_url} ({staging.mode})"
            if staging.status == "PASS"
            else "; ".join(staging.problems),
        ),
        (
            "approval",
            "docs/ota/releases/<releaseId>.md on origin/main pins this manifest",
            "MET" if approval_problems == [] else "MISSING",
            "merged and pinned"
            if approval_problems == []
            else "; ".join(approval_problems or ["no record on origin/main yet"]),
        ),
        (
            "acceptance",
            "every FLAG gate (G5/G7) is accepted by the owner in that record",
            "OWNER" if open_flags else "MET",
            f"to decide: {', '.join(open_flags)}" if open_flags else "nothing left to accept",
        ),
        (
            "live",
            "gates, diff and staging base re-checked against live production",
            "OWNER",
            "ota_production_release.py check (CI never reads production)",
        ),
        (
            "signature",
            "the catalog payload signed offline with a tf-prod-* key",
            "OWNER",
            "ota_production_release.py prepare --execute, then openssl offline",
        ),
    ]
    return [{"id": i, "condition": c, "status": s, "detail": d} for i, c, s, d in rows]


def render_conditions(index: rp.PackageIndex, rows: list[dict[str, str]], verdict: str) -> str:
    staging = index.staging
    lines = [
        f"# OTA release package {index.release_id}: {verdict}",
        "",
        f"* source commit `{index.source_commit}` ({index.source})",
        f"* manifest `{index.manifest_sha256}` ({index.manifest_byte_length} bytes), "
        f"createdAt `{index.created_at}`",
        f"* {index.file_count} files, {index.blob_count} blobs, {index.total_byte_length:,} bytes",
        f"* staging: {staging.status}"
        + (f" — {staging.run_url} ({staging.mode})" if staging.status == "PASS" else ""),
        "* not a publication and not an approval: the owner reviews, merges the approval record, "
        "signs offline and publishes (docs/ota/production-release.md)",
        "",
        "| condition | status | detail |",
        "| --- | --- | --- |",
    ]
    lines += [
        f"| {r['id']}: {r['condition']} | {r['status']} | {r['detail'].replace('|', '/')} |"
        for r in rows
    ]
    return "\n".join(lines) + "\n"


def verdict_of(rows: list[dict[str, str]]) -> str:
    if any(r["status"] == "FAIL" for r in rows):
        return "FAIL"
    staging = next(r for r in rows if r["id"] == "staging")
    return "INCOMPLETE" if staging["status"] == "MISSING" else "READY FOR REVIEW"


def build(args: argparse.Namespace) -> int:
    out: Path = args.out.expanduser().resolve()
    if out.exists() and any(out.iterdir()):
        print(f"error: {out} is not empty", file=sys.stderr)
        return EXIT_USAGE
    try:
        subject = sv.resolve_subject(ROOT, args.source_revision, args.base, args.build)
    except sv.InputError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_USAGE
    created_at = (
        dt.datetime.fromisoformat(args.created_at.replace("Z", "+00:00"))
        if args.created_at
        else dt.datetime.now(dt.UTC).replace(microsecond=0)
    )
    print(f"source {subject.sha} ({subject.source}); createdAt {_stamp(created_at)}")

    def release(build: str, revision: int) -> ReleaseArtifact:
        return build_release(
            ROOT, subject.sha, GAME, build, revision, created_at, STANDARD_VALIDATION_NOTE
        )

    artifact = release(args.build, args.revision)
    release_id = artifact.release_id
    # a record on main counts only when it approves exactly these bytes: a record pinning an
    # earlier build of the same release id must not turn G7 into a PASS here
    record = rp.approval_record_at(ROOT, sv.MAIN_REF, release_id)
    approval: ApprovalRecord | None = None
    approval_problems: list[str] | None = None
    if record is not None:
        approval_problems = rp.approval_record_problems(
            record.decode("utf-8", "replace"),
            release_id=release_id,
            manifest_sha256=sha256_ref(artifact.manifest_bytes),
            source_commit=subject.sha,
        )
        with tempfile.TemporaryDirectory(prefix="ota-approval-") as scratch:
            (Path(scratch) / f"{release_id}.md").write_bytes(record)
            try:
                parsed = load_approval_record(release_id, Path(scratch))
            except OtaPublisherError as exc:
                approval_problems.append(f"the publisher cannot parse it: {exc}")
            else:
                approval = None if approval_problems else parsed
    gates, diff = ci_gates(artifact, release(args.base, 1), approval, created_at)
    staging, attachments = bind(args, artifact)

    rows = [(r.gate.value, r.status.value, r.detail) for r in gates]
    attachments["report/gates.json"] = (
        json.dumps([{"gate": g, "status": s, "detail": d} for g, s, d in rows], indent=2) + "\n"
    ).encode()
    attachments["report/diff.txt"] = (
        f"# ota report against {args.base}-r1 rebuilt from {subject.sha} as a stand-in for the\n"
        "# live production release (CI never reads production); the 'live production catalog'\n"
        "# below is that stand-in. ota_production_release.py check repeats it against live.\n"
        + diff
    ).encode()
    attachments[rp.STAGING_BINDING_NAME] = (
        json.dumps(staging.model_dump(mode="json", by_alias=True), indent=2) + "\n"
    ).encode()

    package_root = out / release_id
    common: dict[str, Any] = {
        "revision": args.revision,
        "source_input": args.source_revision,
        "source": subject.source,
        "diff_base": args.base,
        "produced_by": args.run_url or None,
        "tooling_commit": _git("rev-parse", "HEAD"),
        "staging": staging,
    }
    # the template and the conditions quote the index, and the index hashes them: they are
    # rendered from the index of a scratch copy (same identity), then the package is written
    with tempfile.TemporaryDirectory(prefix="ota-package-") as scratch:
        identity = rp.write_package(Path(scratch) / "p", artifact, attachments={}, **common)
    accepted = set() if approval is None else {gate.value for gate in approval.accepted_gates}
    table = conditions(
        gates=gates,
        staging=staging,
        approval_problems=approval_problems,
        accepted=accepted,
        package_problem=None,
    )
    verdict = verdict_of(table)
    attachments[rp.APPROVAL_TEMPLATE_NAME] = rp.render_approval_template(
        identity, gates=rows
    ).encode()
    attachments["report/conditions.json"] = (json.dumps(table, indent=2) + "\n").encode()
    attachments["report/conditions.md"] = render_conditions(identity, table, verdict).encode()
    index = rp.write_package(package_root, artifact, attachments=attachments, **common)

    try:
        rp.load_package(package_root)
    except rp.PackageError as exc:
        table = conditions(
            gates=gates,
            staging=staging,
            approval_problems=approval_problems,
            accepted=accepted,
            package_problem=str(exc),
        )
        verdict = verdict_of(table)
    summary = render_conditions(index, table, verdict)
    (out / "summary.md").write_text(summary, encoding="utf-8")
    if step_summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(step_summary).open("a", encoding="utf-8") as handle:
            handle.write(summary)
    print(summary)
    print(f"package: {package_root}")
    if verdict == "FAIL":
        return EXIT_FAIL
    return EXIT_MISSING if verdict == "INCOMPLETE" else EXIT_READY


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    go = sub.add_parser("build", help="build and prove the package into an empty directory")
    go.add_argument("out", type=Path)
    go.add_argument("--build", required=True, help="the client build released, e.g. 1.60.1.70532")
    go.add_argument("--revision", type=int, default=1, help="the release revision (-r<n>)")
    go.add_argument(
        "--source-revision", default="", help="commit or ref on origin/main (default: its HEAD)"
    )
    go.add_argument(
        "--base",
        default=DEFAULT_BASE,
        help="the build live clients run: the diff and the CI gates compare against it",
    )
    go.add_argument("--staging-run", type=Path, help="GitHub API JSON of the staging run")
    go.add_argument("--staging-artifact", type=Path, help="the downloaded staging artifact")
    go.add_argument(
        "--repository",
        default=os.environ.get("GITHUB_REPOSITORY", ""),
        help="owner/name the staging run must belong to (default: $GITHUB_REPOSITORY)",
    )
    go.add_argument("--run-url", default="", help="the URL of the workflow run building this")
    go.add_argument("--created-at", default="", help=argparse.SUPPRESS)  # tests: a fixed clock
    args = parser.parse_args(argv)
    if (args.staging_run is None) != (args.staging_artifact is None):
        parser.error("--staging-run and --staging-artifact go together")
    if args.revision < 1:
        parser.error("--revision must be >= 1")
    return build(args)


if __name__ == "__main__":
    raise SystemExit(main())
