"""The production release package, CI side (M5.OTA.PROD.4): build, closure, staging binding.

The package is built once by the real CI script (``scripts/ota_release_package.py``) from the
real repository; every damage test works on its own copy. The staging binding is exercised on
the real manifest with synthetic staging evidence: the same commit, another commit whose files
are equal, and each way the evidence can fail to describe the release. The workflow is checked
as text (inputs, permissions, no secret, no publication) and its final status as bash.
"""

from __future__ import annotations

import json
import shutil
import subprocess
from collections.abc import Callable
from datetime import datetime
from pathlib import Path
from typing import Any

import pytest

from talent_data.ota import release_package as rp
from talent_data.ota.release import build_release
from talent_data.ota.verify import sha256_ref
from tests.unit.ota.publisher_testkit import (
    BUILD_A,
    BUILD_B,
    COMMIT,
    CREATED_AT,
    GAME,
    ROOT,
    VALIDATION_NOTE,
)
from tests.unit.ota.release_package_testkit import (
    PACKAGE_CREATED,
    RELEASE,
    REPOSITORY,
    STAGING_RUN_ID,
    api_run,
    build_package,
    filled_record,
    load_script,
    staging_evidence,
    write_staging_artifact,
)

WORKFLOW = ROOT / ".github/workflows/ota-release-package.yml"
OTHER_COMMIT = "0123456789abcdef0123456789abcdef01234567"

Doc = dict[str, Any]
Evidence = dict[str, bytes]


@pytest.fixture(scope="module")
def built(tmp_path_factory: pytest.TempPathFactory) -> tuple[Path, int]:
    root = tmp_path_factory.mktemp("package")
    code = build_package(root / "out", root / "scratch")
    return root / "out" / RELEASE, code


@pytest.fixture
def package(built: tuple[Path, int], tmp_path: Path) -> Path:
    copy = tmp_path / RELEASE
    shutil.copytree(built[0], copy, symlinks=True)
    return copy


def test_the_package_holds_exactly_the_release_build_release_produces(
    built: tuple[Path, int],
) -> None:
    root, code = built
    assert code == 0  # READY FOR REVIEW
    loaded = rp.load_package(root)
    expected = build_release(
        ROOT, COMMIT, GAME, BUILD_B, 1, datetime.fromisoformat(PACKAGE_CREATED), VALIDATION_NOTE
    )
    assert loaded.manifest_bytes == expected.manifest_bytes
    assert loaded.blobs == expected.blobs
    index = loaded.index
    assert (index.release_id, index.source_commit, index.created_at) == (
        RELEASE,
        COMMIT,
        PACKAGE_CREATED,
    )
    assert index.manifest_sha256 == sha256_ref(expected.manifest_bytes)
    assert (index.staging.status, index.staging.mode) == ("PASS", "sameCommit")
    assert (index.staging.run_id, index.staging.staging_base) == (STAGING_RUN_ID, BUILD_A)
    assert rp.rebuild_problems(ROOT, loaded) == []
    summary = (root.parent / "summary.md").read_text(encoding="utf-8")
    assert summary.startswith(f"# OTA release package {RELEASE}: READY FOR REVIEW")


def test_the_reports_leave_every_owner_decision_open(built: tuple[Path, int]) -> None:
    loaded = rp.load_package(built[0])
    gates = {g["gate"]: g["status"] for g in json.loads(loaded.attachment("report/gates.json"))}
    assert sorted(gates) == [f"G{n}" for n in range(1, 9)]
    assert "STOP" not in gates.values()
    # main holds a 1.60.1.70450-r1 record already - pinned to other bytes: it must not count
    assert gates["G7"] == "FLAG"
    conditions = {c["id"]: c for c in json.loads(loaded.attachment("report/conditions.json"))}
    assert {k: c["status"] for k, c in conditions.items()} == {
        "package": "MET",
        "gates": "MET",
        "staging": "MET",
        "approval": "MISSING",
        "acceptance": "OWNER",
        "live": "OWNER",
        "signature": "OWNER",
    }
    assert "!= this release's" in conditions["approval"]["detail"]
    assert "G7" in conditions["acceptance"]["detail"]
    diff = loaded.attachment("report/diff.txt").decode()
    assert diff.startswith(f"# ota report against {BUILD_A}-r1 rebuilt from {COMMIT}")

    template = loaded.attachment(rp.APPROVAL_TEMPLATE_NAME).decode()
    assert f"- Manifest SHA-256: {loaded.index.manifest_sha256}\n" in template
    assert f"- Source commit: {COMMIT}\n" in template
    assert "- Accepted gates: <OWNER:" in template  # nothing is accepted for the owner
    pins = {
        "release_id": RELEASE,
        "manifest_sha256": loaded.index.manifest_sha256,
        "source_commit": COMMIT,
    }
    assert rp.approval_record_problems(template, **pins) == [
        "the record still has 5 unfilled template line(s)"
    ]
    assert rp.approval_record_problems(filled_record(template), **pins) == []
    other = filled_record(template).replace(loaded.index.manifest_sha256, "sha256:" + "0" * 64)
    assert rp.approval_record_problems(other, **pins)[0].startswith("the record's Manifest SHA-256")
    # accepted gates: none or only G5/G7, a G7 with its live-build evidence
    assert rp.approval_record_problems(filled_record(template, accepted=""), **pins) == []
    assert rp.approval_record_problems(filled_record(template, accepted="G5, G6"), **pins) == [
        "the record accepts ['G6']: only G5, G7 may be accepted"
    ]
    assert rp.approval_record_problems(filled_record(template, accepted="all"), **pins) == [
        "the record accepts ['all']: only G5, G7 may be accepted"
    ]
    no_evidence = "\n".join(
        line
        for line in filled_record(template).splitlines()
        if not line.startswith("- Live-build evidence:")
    )
    assert rp.approval_record_problems(no_evidence, **pins) == [
        "the record accepts G7 without its 'Live-build evidence'"
    ]
    no_gates = filled_record(template).replace("- Accepted gates: G5, G7\n", "")
    assert rp.approval_record_problems(no_gates, **pins) == [
        "the record has no '- Accepted gates:' line"
    ]


def _flip(path: Path) -> None:
    data = bytearray(path.read_bytes())
    data[len(data) // 2] ^= 0x01
    path.write_bytes(bytes(data))


def _first_blob(package: Path) -> Path:
    return sorted((package / rp.BLOBS_DIR).iterdir())[0]


def _relink(path: Path) -> None:
    target = path.with_name("elsewhere")
    path.rename(target)
    path.symlink_to(target)


def _unstage(package: Path) -> None:
    index = package / rp.INDEX_NAME
    index.write_text(index.read_text().replace('"status": "PASS"', '"status": "MISSING"', 1))


DAMAGE: dict[str, Callable[[Path], object]] = {
    "a blob byte flipped": lambda p: _flip(_first_blob(p)),
    "a blob missing": lambda p: _first_blob(p).unlink(),
    "a foreign blob": lambda p: (p / rp.BLOBS_DIR / ("f" * 64)).write_bytes(b"x"),
    "an extra file": lambda p: (p / "report" / "notes.txt").write_text("hello"),
    "the manifest edited": lambda p: _flip(p / rp.MANIFEST_NAME),
    "the template edited": lambda p: _flip(p / rp.APPROVAL_TEMPLATE_NAME),
    "the index edited": _unstage,
    "the index unparsable": lambda p: (p / rp.INDEX_NAME).write_text("{"),
    "a blob replaced by a link": lambda p: _relink(_first_blob(p)),
}


@pytest.mark.parametrize("damage", sorted(DAMAGE))
def test_a_damaged_package_is_refused(package: Path, damage: str) -> None:
    DAMAGE[damage](package)
    with pytest.raises(rp.PackageError):
        rp.load_package(package)


def test_the_owner_work_directory_is_outside_the_package_closure(package: Path) -> None:
    (package / rp.WORK_DIR).mkdir()
    (package / rp.WORK_DIR / "pending-production.payload.json").write_text("{}")
    assert rp.load_package(package).index.release_id == RELEASE


# --------------------------------------------------------------------------- staging binding


@pytest.fixture(scope="module")
def manifest_bytes() -> bytes:
    return build_release(ROOT, COMMIT, GAME, BUILD_B, 1, CREATED_AT, VALIDATION_NOTE).manifest_bytes


@pytest.fixture(scope="module")
def evidence() -> Evidence:
    return staging_evidence()


def _bind(manifest: bytes, evidence: Evidence, **run: object) -> rp.StagingBinding:
    return rp.bind_staging(
        manifest,
        report=evidence["report"],
        staging_manifest=evidence["manifest"],
        update_evidence=evidence["update"],
        run=rp.run_record(api_run(**run)),
        repository=REPOSITORY,
    )


def _edit(data: bytes, change: Callable[[Doc], object]) -> bytes:
    doc = json.loads(data)
    change(doc)
    return json.dumps(doc, indent=2).encode()


def _moved_to(commit: str, evidence: Evidence) -> Evidence:
    """The same staged files, as if the staging run had used ``commit``."""
    manifest = _edit(
        evidence["manifest"], lambda d: d["provenance"].update(repositoryCommit=commit)
    )

    def report(doc: Doc) -> None:
        doc["revision"] = commit
        doc["facts"]["releases"][RELEASE]["manifestSha256"] = sha256_ref(manifest)

    def update(doc: Doc) -> None:
        doc["commit"] = commit
        doc["requested"]["revision"] = commit

    return {
        "report": _edit(evidence["report"], report),
        "manifest": manifest,
        "update": _edit(evidence["update"], update),
    }


def test_a_staging_pass_of_the_same_commit_binds(manifest_bytes: bytes, evidence: Evidence) -> None:
    binding = _bind(manifest_bytes, evidence)
    assert (binding.status, binding.mode, binding.problems) == ("PASS", "sameCommit", [])
    # another createdAt than the staged manifest's: the files bind, not the manifest hash
    assert binding.staging_manifest_sha256 != sha256_ref(manifest_bytes)


def test_another_commit_binds_only_with_its_files_proven_equal(
    manifest_bytes: bytes, evidence: Evidence
) -> None:
    moved = _moved_to(OTHER_COMMIT, evidence)
    binding = _bind(manifest_bytes, moved)
    assert (binding.status, binding.mode, binding.staging_revision) == (
        "PASS",
        "equalFiles",
        OTHER_COMMIT,
    )

    changed_manifest = _edit(
        moved["manifest"], lambda d: d["files"][0].update(sha256="sha256:" + "0" * 64)
    )
    changed = {
        **moved,
        "manifest": changed_manifest,
        "report": _edit(
            moved["report"],
            lambda d: d["facts"]["releases"][RELEASE].update(
                manifestSha256=sha256_ref(changed_manifest)
            ),
        ),
    }
    binding = _bind(manifest_bytes, changed)
    assert (binding.status, binding.mode) == ("FAIL", None)
    assert any("staging verified other files" in p for p in binding.problems)


def _report(change: Callable[[Doc], object]) -> Callable[[Evidence], Evidence]:
    return lambda e: {**e, "report": _edit(e["report"], change)}


def _update(change: Callable[[Doc], object]) -> Callable[[Evidence], Evidence]:
    return lambda e: {**e, "update": _edit(e["update"], change)}


def _same(e: Evidence) -> Evidence:
    return e


NOT_EVIDENCE: dict[str, tuple[Callable[[Evidence], Evidence], dict[str, object], str]] = {
    "verdict FAIL": (_report(lambda d: d.update(verdict="FAIL")), {}, "verdict"),
    "another candidate": (
        _report(lambda d: d.update(candidateBuild="1.60.1.70532")),
        {},
        "candidate",
    ),
    "the report of another run": (
        _report(lambda d: d["facts"]["tooling"].update(head="6" * 40)),
        {},
        "not this run's",
    ),
    "another workflow": (_same, {"path": ".github/workflows/ci.yml"}, "the run is of"),
    "another repository": (_same, {"repository": {"full_name": "someone/else"}}, "belongs to"),
    "a failed run": (_same, {"conclusion": "failure"}, "not completed/success"),
    "another installed copy": (
        _update(lambda d: d["installedFiles"].popitem()),
        {},
        "the client installed other files",
    ),
    "another requested pair": (
        _update(lambda d: d["requested"].update(base="1.60.1.70100")),
        {},
        "requested",
    ),
    "the staged manifest unpinned": (
        _report(lambda d: d["facts"]["releases"].clear()),
        {},
        "does not pin the staged manifest",
    ),
}


@pytest.mark.parametrize("case", sorted(NOT_EVIDENCE))
def test_staging_evidence_that_does_not_describe_this_release_fails(
    manifest_bytes: bytes, evidence: Evidence, case: str
) -> None:
    alter, run, needle = NOT_EVIDENCE[case]
    binding = _bind(manifest_bytes, alter(evidence), **run)
    assert binding.status == "FAIL", binding
    assert any(needle in p for p in binding.problems), binding.problems


def _build(out: Path, *extra: str) -> int:
    return int(
        load_script("ota_release_package").main(
            [
                "build",
                str(out),
                "--build",
                BUILD_B,
                "--base",
                BUILD_A,
                "--source-revision",
                COMMIT,
                "--created-at",
                PACKAGE_CREATED,
                *extra,
            ]
        )
    )


def test_without_staging_evidence_the_package_is_incomplete(tmp_path: Path) -> None:
    assert _build(tmp_path / "out") == 3
    assert rp.load_package(tmp_path / "out" / RELEASE).index.staging.status == "MISSING"


def test_staging_evidence_of_other_files_fails_the_package(tmp_path: Path) -> None:
    evidence = staging_evidence()
    evidence["update"] = _edit(evidence["update"], lambda d: d["installedFiles"].popitem())
    run_json, artifact = write_staging_artifact(tmp_path / "scratch", evidence)
    code = _build(
        tmp_path / "out",
        "--staging-run",
        str(run_json),
        "--staging-artifact",
        str(artifact),
        "--repository",
        REPOSITORY,
    )
    assert code == 1
    assert rp.load_package(tmp_path / "out" / RELEASE).index.staging.status == "FAIL"


def test_the_builder_refuses_a_busy_directory_and_a_commit_off_main(tmp_path: Path) -> None:
    script = load_script("ota_release_package")
    (tmp_path / "busy").mkdir()
    (tmp_path / "busy" / "x").write_text("x")
    assert script.main(["build", str(tmp_path / "busy"), "--build", BUILD_B]) == 2
    off_main = ["build", str(tmp_path / "o"), "--build", BUILD_B, "--source-revision", "0" * 40]
    assert script.main(off_main) == 2


# --------------------------------------------------------------------------- the workflow


def _step(text: str, name: str) -> str:
    return text.split(f"      - name: {name}\n", 1)[1].split("      - name:", 1)[0]


def test_the_workflow_takes_its_inputs_and_holds_no_secret() -> None:
    text = WORKFLOW.read_text(encoding="utf-8")
    dispatch = text.split("workflow_dispatch:", 1)[1].split("pull_request:", 1)[0]
    for name, required in (
        ("build", "true"),
        ("release_revision", "true"),
        ("source_revision", "false"),
        ("staging_run", "true"),
        ("base", "true"),
    ):
        block = dispatch.split(f"      {name}:\n", 1)[1].split("type:", 1)[0]
        assert f"required: {required}" in block, name
    source = dispatch.split("      source_revision:\n", 1)[1].split("type:", 1)[0]
    assert 'default: ""' in source and "empty: the HEAD of main" in source
    # inputs reach the scripts through the environment only, never spliced into the shell
    for step in text.split("      - name: ")[1:]:
        run = step.split("run: |", 1)[1] if "run: |" in step else ""
        assert "${{" not in run, step.splitlines()[0]
    assert "permissions:\n  contents: read\n  actions: read" in text
    assert "persist-credentials: false" in text and "fetch-depth: 0" in text
    assert "secrets." not in text
    jobs = text.split("jobs:", 1)[1]
    for forbidden in (
        "--execute",
        "promote",
        "attach-signature",
        "ota_production_release.py ",
        "--backend",
        "aws ",
        "AWS_",
        "git push",
        "gh pr",
        "merge",
    ):
        assert forbidden not in jobs, forbidden


def test_the_workflow_reads_the_staging_run_and_uploads_the_package() -> None:
    text = WORKFLOW.read_text(encoding="utf-8")
    fetch = _step(text, "Fetch the staging evidence")
    assert 'gh api "repos/$GITHUB_REPOSITORY/actions/runs/$STAGING_RUN"' in fetch
    assert 'gh run download "$STAGING_RUN"' in fetch
    assert "*[!0-9]*" in fetch  # the run id is a number, nothing else
    build = _step(text, "Build the release package")
    assert '--source-revision "$SOURCE_REVISION"' in build
    assert '--staging-run "$RUNNER_TEMP/staging-run.json"' in build
    upload = _step(text, "Upload the release package")
    assert "if: always()" in upload and "if-no-files-found: error" in upload
    assert "retention-days: 90" in upload and "path: ${{ runner.temp }}/package" in upload


@pytest.mark.parametrize(("code", "status"), [("0", 0), ("1", 1), ("3", 1), ("2", 1), ("", 1)])
def test_the_final_status_is_green_only_when_ready(code: str, status: int) -> None:
    text = WORKFLOW.read_text(encoding="utf-8")
    script = _step(text, "Final status").split("run: |\n", 1)[1]
    script = "\n".join(line.removeprefix("          ") for line in script.splitlines())
    bash = shutil.which("bash")
    assert bash is not None
    result = subprocess.run(
        [bash, "-c", script], env={"PACKAGE_EXIT": code}, capture_output=True, text=True
    )
    assert result.returncode == status, result.stdout
