"""Shared fixtures of the release package tests (M5.OTA.PROD.4), fully offline.

A package is built by the real CI script from the real repository (commit ``COMMIT`` on main,
release ``BUILD_B`` over the diff base ``BUILD_A``), with a synthetic staging verification run
whose report, staged manifest and client evidence describe exactly that release - built by the
publisher with another ``createdAt``, as the staging workflow does. The production-like keys
(``tf-prod-test-*``) derive from public seeds and exist only inside the test run.
"""

from __future__ import annotations

import importlib.util
import json
import sys
from datetime import UTC, datetime
from pathlib import Path
from types import ModuleType

from talent_data.ota.release import build_release
from talent_data.ota.release_package import STAGING_UPDATE_TEST, STAGING_WORKFLOW
from talent_data.ota.verify import sha256_ref
from tests.unit.ota.publisher_testkit import BUILD_A, BUILD_B, COMMIT, GAME, ROOT, VALIDATION_NOTE

REPOSITORY = "AlexeyKravtsov95/TalentForge"
STAGING_RUN_ID = 4242
STAGING_HEAD = "5" * 40  # the commit the staging workflow ran its tooling from
PACKAGE_CREATED = "2026-10-17T23:00:00Z"
STAGED_CREATED = datetime(2026, 10, 17, 20, 0, tzinfo=UTC)
RELEASE = f"{BUILD_B}-r1"


def load_script(name: str) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def api_run(**overrides: object) -> dict[str, object]:
    """A ``GET /repos/{r}/actions/runs/{id}`` answer of a passed staging verification."""
    run: dict[str, object] = {
        "id": STAGING_RUN_ID,
        "run_attempt": 1,
        "html_url": f"https://github.com/{REPOSITORY}/actions/runs/{STAGING_RUN_ID}",
        "path": STAGING_WORKFLOW,
        "event": "workflow_dispatch",
        "status": "completed",
        "conclusion": "success",
        "head_sha": STAGING_HEAD,
        "created_at": "2026-10-17T20:00:00Z",
        "repository": {"full_name": REPOSITORY},
    }
    run.update(overrides)
    return run


def staging_evidence(revision: str = COMMIT) -> dict[str, bytes]:
    """The staging run's report, staged manifest and client update evidence for ``RELEASE``."""
    staged = build_release(ROOT, revision, GAME, BUILD_B, 1, STAGED_CREATED, VALIDATION_NOTE)
    report = {
        "format": "talentforge-ota-staging-verification",
        "formatVersion": 1,
        "verdict": "PASS",
        "productionApproval": False,
        "revision": revision,
        "baseBuild": BUILD_A,
        "candidateBuild": BUILD_B,
        "checks": [],
        "facts": {
            "tooling": {"head": STAGING_HEAD},
            "releases": {RELEASE: {"manifestSha256": sha256_ref(staged.manifest_bytes)}},
        },
    }
    update = {
        "base": BUILD_A,
        "candidate": BUILD_B,
        "candidateRelease": RELEASE,
        "commit": revision,
        "installedFiles": {f.path: [f.sha256, f.byte_length] for f in staged.manifest.files},
        "requested": {"base": BUILD_A, "candidate": BUILD_B, "revision": revision},
    }
    return {
        "report": json.dumps(report, indent=2).encode(),
        "manifest": staged.manifest_bytes,
        "update": json.dumps(update, indent=2).encode(),
    }


def write_staging_artifact(
    root: Path, evidence: dict[str, bytes] | None = None
) -> tuple[Path, Path]:
    """(run.json, artifact dir) as the workflow's `gh api` and `gh run download` leave them."""
    evidence = evidence or staging_evidence()
    root.mkdir(parents=True, exist_ok=True)
    run_json = root / "staging-run.json"
    run_json.write_text(json.dumps(api_run()), encoding="utf-8")
    artifact = root / "staging"
    run_dir = artifact / f"ota-staging-verify-{STAGING_RUN_ID}-1"
    (run_dir / "releases" / RELEASE).mkdir(parents=True)
    (run_dir / "client").mkdir()
    (run_dir / "staging-report.json").write_bytes(evidence["report"])
    (run_dir / "releases" / RELEASE / "release.json").write_bytes(evidence["manifest"])
    (run_dir / "client" / f"{STAGING_UPDATE_TEST}.json").write_bytes(evidence["update"])
    return run_json, artifact


def build_package(out: Path, scratch: Path, *extra: str) -> int:
    """The CI script as the workflow runs it, into ``out``."""
    run_json, artifact = write_staging_artifact(scratch)
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
                "--staging-run",
                str(run_json),
                "--staging-artifact",
                str(artifact),
                "--repository",
                REPOSITORY,
                *extra,
            ]
        )
    )


def filled_record(template: str, *, accepted: str = "G5, G7") -> str:
    """The template as an owner fills it in (here only: pytest approves nothing real)."""
    answers = {
        "- Accepted gates: ": f"- Accepted gates: {accepted}",
        "- Live-build evidence: ": "- Live-build evidence: offline rehearsal record (pytest)",
        "- Approved by: ": "- Approved by: pytest",
        "- Approval date: ": "- Approval date: 2026-10-18",
    }
    lines = []
    skipping = False
    for line in template.splitlines():
        if skipping:
            skipping = not line.endswith(">")
            continue
        replaced = next((v for k, v in answers.items() if line.startswith(k)), None)
        if replaced is not None:
            lines.append(replaced)
        elif line.startswith("<OWNER:"):
            lines.append("Основание: offline rehearsal (pytest).")
            skipping = not line.endswith(">")
        else:
            lines.append(line)
    return "\n".join(lines) + "\n"
