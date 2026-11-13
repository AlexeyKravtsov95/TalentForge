"""Trusted main coordinator for one-MR OTA preparation; never publish or merge."""

import argparse
import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

import httpx  # noqa: E402

from talent_data.ota.automation.candidate import (  # noqa: E402
    CandidateNotice,
    verify_candidate,
)
from talent_data.ota.automation.premerge import (  # noqa: E402
    bind_candidate_request,
    invalidate_candidate_ready,
    mark_candidate_ready,
    prepare_candidate,
    route_candidate_ci,
)
from talent_data.ota.automation.prepare import (  # noqa: E402
    GitHub,
    PreparationBlocked,
    artifact_for,
    git,
    load_proposal,
    obj,
    positive,
    require,
    same,
)
from talent_data.ota.automation.provenance import REPOSITORY, RequestRejected  # noqa: E402
from talent_data.ota.automation.requests import ArtifactPin, RunPin, parse_request  # noqa: E402
from talent_data.ota.preflight import production_keyring  # noqa: E402
from talent_data.ota.release_package import candidate_binding, load_package  # noqa: E402


def notice(api: GitHub, run_id: int, name: str) -> CandidateNotice:
    run = obj(api.repo("GET", f"/actions/runs/{positive(run_id)}"))
    same(
        run.get("path"),
        ".github/workflows/ota-prepare.yml",
        "CANDIDATE_ORIGIN",
        "producer workflow",
    )
    same(run.get("head_branch"), "main", "CANDIDATE_ORIGIN", "producer branch")
    for field in ("repository", "head_repository"):
        same(
            obj(run.get(field)).get("full_name"),
            REPOSITORY,
            "CANDIDATE_ORIGIN",
            "producer repository",
        )
    artifact = artifact_for(api, run, name)
    # A retained producer is pinned to its actual artifact's attempt interval, never
    # to the consumer attempt. A live same-run proposal uses its exact current attempt.
    attempt = positive(run.get("run_attempt"))
    require(attempt <= 20, "CANDIDATE_ORIGIN", "producer attempt history bound")
    if str(run_id) == os.environ.get("GITHUB_RUN_ID"):
        same(
            attempt,
            int(os.environ.get("GITHUB_RUN_ATTEMPT", "0")),
            "CANDIDATE_ORIGIN",
            "current attempt",
        )
    else:
        created = datetime.fromisoformat(str(artifact.get("created_at")))
        matching = []
        for number in range(1, attempt + 1):
            producer = api.run(run_id, number)
            start = datetime.fromisoformat(str(producer.get("run_started_at")))
            end = datetime.fromisoformat(str(producer.get("updated_at")))
            if start <= created <= end:
                matching.append(number)
        require(len(matching) == 1, "CANDIDATE_ORIGIN", "artifact original attempt")
        attempt = matching[0]
    return CandidateNotice(
        schema_version=1,
        kind="ota-candidate-notice",
        policy="owner-merge-v1",
        preparation_run=RunPin(
            workflow_path=".github/workflows/ota-prepare.yml",
            run_id=run_id,
            attempt=attempt,
            head_sha=str(run["head_sha"]),
        ),
        candidate_artifact=ArtifactPin(
            artifact_id=artifact["id"], name=artifact["name"], archive_sha256=artifact["digest"]
        ),
    )


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("notice", "prepare", "bind", "ready", "invalidate"))
    parser.add_argument("--ci-run-id", type=int, default=0)
    parser.add_argument("--ci-attempt", type=int, default=0)
    parser.add_argument("--run-id", type=int, default=0)
    parser.add_argument("--artifact-name", default="")
    parser.add_argument("--out", type=Path, default=Path("premerge"))
    parser.add_argument("--request", type=Path)
    args = parser.parse_args(argv)
    try:
        same(os.environ.get("GITHUB_REPOSITORY"), REPOSITORY, "PREMERGE_TRIGGER", "repository")
        same(
            os.environ.get("GITHUB_REF"), "refs/heads/main", "PREMERGE_TRIGGER", "trusted main ref"
        )
        same(
            git(ROOT, "rev-parse", "HEAD").decode().strip(),
            os.environ.get("GITHUB_SHA"),
            "PREMERGE_TRIGGER",
            "trusted checkout",
        )
        with httpx.Client(follow_redirects=False, timeout=30.0) as client:
            reader = GitHub(client, os.environ.get("GH_TOKEN", ""))
            if args.phase == "notice":
                args.out.write_text(
                    notice(reader, args.run_id, args.artifact_name).model_dump_json(by_alias=True)
                    + "\n"
                )
                return 0
            same(os.environ.get("GITHUB_EVENT_NAME"), "workflow_run", "PREMERGE_TRIGGER", "event")
            run = reader.run(positive(args.ci_run_id), positive(args.ci_attempt))
            pin = RunPin(
                workflow_path=".github/workflows/ci.yml",
                run_id=args.ci_run_id,
                attempt=args.ci_attempt,
                head_sha=str(run["head_sha"]),
            )
            if args.phase == "invalidate":
                writer = GitHub(client, os.environ.get("OTA_APP_TOKEN", ""))
                invalidate_candidate_ready(reader, writer, ROOT, pin)
                return 0
            phase, number = route_candidate_ci(reader, ROOT, pin)
            if args.phase == "prepare":
                if phase == "prepare":
                    proposal = prepare_candidate(
                        client,
                        reader=reader,
                        repo=ROOT,
                        ci_pin=pin,
                        destination=args.out / "package",
                        now=datetime.now(UTC),
                        keyring=production_keyring(ROOT / "docs/ota/keys/production-keyring.json"),
                    )
                    require(proposal is not None, "PREMERGE_PHASE", "candidate request required")
                    assert proposal is not None
                    args.out.mkdir(parents=True, exist_ok=True)
                    (args.out / "release-request.json").write_text(
                        proposal.request.model_dump_json(by_alias=True, indent=2) + "\n"
                    )
                output = os.environ.get("GITHUB_OUTPUT")
                if output:
                    with Path(output).open("a", encoding="utf-8") as handle:
                        handle.write(f"phase={'bind' if phase == 'prepare' else 'ready'}\n")
                print(
                    json.dumps(
                        {"phase": "bind" if phase == "prepare" else "ready", "pullNumber": number}
                    )
                )
                return 0
            writer = GitHub(client, os.environ.get("OTA_APP_TOKEN", ""))
            if args.phase == "ready":
                same(phase, "ready", "PREMERGE_PHASE", "readiness phase")
                mark_candidate_ready(reader, writer, ROOT, number, ci_pin=pin)
            else:
                same(phase, "prepare", "PREMERGE_PHASE", "binding phase")
                require(args.request is not None, "PREMERGE_PHASE", "request required")
                assert args.request is not None
                request = parse_request(args.request.read_bytes())
                candidate = verify_candidate(
                    reader, ROOT, number, source_sha=request.source_commit, ci_pin=pin
                )
                load_proposal(
                    reader, request, destination=args.out / "package", candidate_context=candidate
                )
                root = args.out / "package"
                if not (root / "package.json").is_file():
                    root = root / request.release_id
                binding = candidate_binding(load_package(root))
                require(binding is not None, "CANDIDATE_BINDING", "proof required")
                assert binding is not None
                bind_candidate_request(reader, writer, ROOT, candidate, request, binding)
            return 0
    except (PreparationBlocked, RequestRejected, ValueError, OSError) as exc:
        print(f"BLOCKED: {exc}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
