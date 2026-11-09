"""Sequential trusted argv routing; immutable probe artifacts remain hints only."""

from __future__ import annotations

import argparse
import os
from collections.abc import Callable
from pathlib import Path
from typing import Literal

from talent_data.ota.automation.cli import StatusDocument, _emit, read_status
from talent_data.ota.automation.cli import main as command_main
from talent_data.ota.automation.probe import (
    Action,
    ExecutionProgress,
    ProbeCandidate,
    ProbeResult,
    _result,
    _retained,
    execution_progress,
    read_probe,
)
from talent_data.ota.automation.provenance import TRUSTED_REPO
from talent_data.ota.automation.state import GitJournal, Journal, RequestState
from talent_data.ota.verify import sha256_ref

Mode = Literal["preflight", "writer"]


def _empty(mode: Mode, *, unknown: bool = False) -> StatusDocument:
    return StatusDocument(
        schema_version=1,
        kind="ota-production-status",
        command="preflight" if mode == "preflight" else "publish",
        operation="publish",
        outcome="AMBIGUOUS" if unknown else "NO_ACTION",
        stage="RECOVERY" if unknown else "PLANNING",
        reason="OUTCOME_UNKNOWN" if unknown else "NO_WORK",
        writer_needed=False,
        operations=(),
    )


def _combine(documents: list[StatusDocument], mode: Mode) -> StatusDocument:
    if not documents:
        return _empty(mode)
    if mode == "preflight" and any(d.writer_needed for d in documents):
        result = next(d for d in documents if d.writer_needed)
    else:
        priority = {
            "AMBIGUOUS": 0,
            "PUBLISHED_NOT_VERIFIED": 1,
            "BLOCKED": 2,
            "VERIFIED": 3,
            "READY": 4,
            "DISABLED": 5,
            "DRY_RUN": 6,
            "NO_ACTION": 7,
        }
        result = min(documents, key=lambda d: priority[d.outcome])
    operations = {op.digest: op for d in documents for op in d.operations}
    return result.model_copy(update={"operations": tuple(operations.values())})


def route(
    probe: ProbeResult,
    mode: Mode,
    output: Path,
    *,
    invoke: Callable[[list[str]], int],
    head_sha: str,
    journal: Journal | None = None,
) -> StatusDocument:
    if probe.main_sha != head_sha:
        raise ValueError("probe tooling snapshot differs")
    if journal is not None:
        unresolved = tuple(
            _retained(e, "RECOVER", head_sha)
            for _, e in sorted(journal.read_all().items())
            if e.receipt.state in {RequestState.WRITE_STARTED, RequestState.AMBIGUOUS}
        )
        if unresolved:
            probe = _result("RECOVER", "OUTCOME_UNKNOWN", head=head_sha, candidates=unresolved)
    output.mkdir(parents=True, exist_ok=True)
    plan_raw = probe.model_dump_json(by_alias=True).encode()
    (output / "input-plan.json").write_bytes(plan_raw)
    started: list[str] = []

    def save_progress() -> None:
        progress = ExecutionProgress(
            schema_version=1,
            kind="ota-batch-execution",
            main_sha=head_sha,
            input_sha256=sha256_ref(plan_raw),
            started=tuple(started),
        )
        temporary = output / "execution.json.tmp"
        temporary.write_text(progress.model_dump_json(by_alias=True))
        temporary.replace(output / "execution.json")

    save_progress()
    candidates = probe.candidates
    documents: list[StatusDocument] = []
    ready: list[ProbeCandidate] = []
    if mode == "preflight" and probe.action == "VERIFY":
        # Full proof runs in the verify command, without loading a writer/profile.
        ready.extend(candidates)
    for candidate in candidates:
        if mode == "preflight" and candidate.action == "VERIFY":
            if candidate not in ready:
                ready.append(candidate)
            continue
        directory = output / "targets" / candidate.request_digest.removeprefix("sha256:")
        directory.mkdir(parents=True, exist_ok=True)
        status_path = directory / (
            "preflight.json" if mode == "preflight" else "candidate-status.json"
        )
        operation = {"PUBLISH": "publish", "VERIFY": "verify", "RECOVER": "reconcile"}[
            candidate.action
        ]
        args = ["preflight", "--operation", operation] if mode == "preflight" else [operation]
        args += ["--execute", "--status-file", str(status_path)]
        if candidate.release_id is not None and candidate.action != "RECOVER":
            args += [
                "--release-id",
                candidate.release_id,
                "--timings-file",
                str(
                    directory
                    / ("preflight-timings.json" if mode == "preflight" else "delivery-timings.json")
                ),
            ]
        started.append(candidate.request_digest)
        save_progress()
        try:
            invoke(args)  # Exact CLI repeats invocation, journal, current CI and all gates.
            document = read_status(status_path.read_bytes())
        except Exception:  # noqa: BLE001 - an interrupted writer may already have performed PUT
            if mode == "writer":
                document = _empty(mode, unknown=True)
            else:
                document = _empty(mode).model_copy(
                    update={
                        "outcome": "BLOCKED",
                        "stage": "PLANNING",
                        "reason": "ASSESSMENT_FAILED",
                    }
                )
            status_path.write_text(document.model_dump_json(by_alias=True))
        documents.append(document)
        if mode == "preflight" and document.writer_needed:
            ready.append(candidate)
        if candidate.action == "RECOVER" or document.outcome == "AMBIGUOUS":
            break  # One diagnostic turn. It cannot spill into a new write.
        if mode == "writer" and document.outcome == "PUBLISHED_NOT_VERIFIED":
            break  # Public convergence must not turn into another PUT.
    if mode == "preflight" and ready:
        action: Action = (
            "RECOVER"
            if ready[0].action == "RECOVER"
            else "PUBLISH"
            if any(c.action == "PUBLISH" for c in ready)
            else "VERIFY"
        )
        planned = _result(action, "READY", head=head_sha, candidates=tuple(ready))
        (output / "plan.json").write_text(planned.model_dump_json(by_alias=True))
        documents.append(
            _empty(mode).model_copy(
                update={"outcome": "READY", "reason": "READY", "writer_needed": True}
            )
        )
    result = _combine(documents, mode)
    (output / ("preflight.json" if mode == "preflight" else "candidate-status.json")).write_text(
        result.model_dump_json(by_alias=True)
    )
    return result


def prior_failures(plan_file: Path, ready: ProbeResult) -> list[StatusDocument]:
    """Preserve partial preflight failures without blocking other ready targets."""
    root = plan_file.parent
    raw = (root / "input-plan.json").read_bytes()
    original = read_probe(raw)
    if original.main_sha != ready.main_sha:
        raise ValueError("preflight snapshot differs")
    progress = execution_progress(raw, (root / "execution.json").read_bytes())
    failed = []
    for digest in progress.started:
        if digest in ready.request_digests:
            continue
        document = read_status((root / "targets" / digest[7:] / "preflight.json").read_bytes())
        if document.outcome in {"BLOCKED", "AMBIGUOUS", "PUBLISHED_NOT_VERIFIED"}:
            failed.append(document)
    return failed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("preflight", "writer"))
    parser.add_argument("--probe-file", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    if os.environ.get("OTA_PRODUCTION_AUTOMATION_ENABLED") != "true":
        args.output_dir.mkdir(parents=True, exist_ok=True)
        document = _empty(args.mode).model_copy(
            update={"outcome": "DISABLED", "stage": "CONFIG", "reason": "AUTOMATION_DISABLED"}
        )
        path = args.output_dir / (
            "preflight.json" if args.mode == "preflight" else "candidate-status.json"
        )
        return _emit(document, path, os.environ)
    from talent_data.ota.automation.cli import _git_authentication

    plan = read_probe(args.probe_file.read_bytes())
    failures = prior_failures(args.probe_file, plan) if args.mode == "writer" else []
    with _git_authentication(os.environ.get("GITHUB_TOKEN")):
        result = route(
            plan,
            args.mode,
            args.output_dir,
            invoke=command_main,
            head_sha=os.environ.get("GITHUB_SHA", ""),
            journal=GitJournal(TRUSTED_REPO),
        )
    if failures:
        result = _combine([result, *failures], "writer")
    if args.mode == "preflight" and result.writer_needed:
        ready = read_probe((args.output_dir / "plan.json").read_bytes())
        partial = bool(prior_failures(args.output_dir / "plan.json", ready))
        if output := os.environ.get("GITHUB_OUTPUT"):
            with Path(output).open("a") as stream:
                stream.write(f"partial_failure={str(partial).lower()}\n")
    path = args.output_dir / (
        "preflight.json" if args.mode == "preflight" else "candidate-status.json"
    )
    return _emit(result, path, os.environ)
