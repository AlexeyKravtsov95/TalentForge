"""Developer commands for the allocation engine (``python -m talent_data allocation ...``).

allocation validate --game forever --build 1.60.1.70009 --class warrior --build-file b.json
allocation replay   --scenario warrior.basic_rank_purchase      (or --all)

These are debugging aids for the Python reference engine, not a product CLI.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from pydantic import ValidationError

from talent_data import paths
from talent_data.allocation.conformance import (
    load_suite,
    load_suite_documents,
    outcome_report,
    run_scenario,
)
from talent_data.allocation.engine import AllocationEngineError, TalentAllocationEngine
from talent_data.allocation.models import BuildFile
from talent_data.games import GAMES
from talent_data.pipeline import PipelineError, read_document
from talent_data.versions import ClientVersion

EXIT_OK = 0
EXIT_FAILURE = 1
DEFAULT_CONFORMANCE_BUILD = "1.60.1.70009"


def register(sub: argparse._SubParsersAction[argparse.ArgumentParser]) -> None:
    alloc = sub.add_parser("allocation", help="talent allocation engine (developer tools)")
    alloc_sub = alloc.add_subparsers(dest="allocation_command", required=True)

    val = alloc_sub.add_parser("validate", help="validate a build file against a dataset")
    val.add_argument("--game", default="forever", choices=sorted(GAMES))
    val.add_argument("--build", required=True, help="client build of the normalized dataset")
    val.add_argument("--class", dest="class_key", required=True)
    val.add_argument("--build-file", type=Path, required=True)
    val.add_argument("--point-limit", type=int, default=None)
    val.set_defaults(handler=_cmd_validate)

    rep = alloc_sub.add_parser("replay", help="replay conformance scenarios with the engine")
    rep.add_argument("--game", default="forever", choices=sorted(GAMES))
    rep.add_argument("--build", default=DEFAULT_CONFORMANCE_BUILD)
    rep.add_argument("--suite", type=Path, default=None, help="suite file (default: committed)")
    group = rep.add_mutually_exclusive_group(required=True)
    group.add_argument("--scenario", help="scenario id, e.g. hunter.mutual_sufficient_cycle")
    group.add_argument("--all", action="store_true", help="replay every scenario")
    rep.set_defaults(handler=_cmd_replay)


def _cmd_validate(args: argparse.Namespace) -> int:
    version = ClientVersion.parse(args.build)
    doc = read_document(paths.normalized_path(args.game, version, args.class_key))
    try:
        build = BuildFile.model_validate_json(args.build_file.read_text(encoding="utf-8"))
    except (OSError, ValidationError) as exc:
        raise PipelineError(f"{args.build_file}: not a valid allocation build file: {exc}") from exc
    if build.class_.upper() != doc.class_.token:
        raise PipelineError(
            f"{args.build_file} is a {build.class_} build, dataset is {doc.class_.token}"
        )
    if (build.game, build.client_build) != (doc.game, doc.client_build):
        print(
            f"note: build file was saved for {build.game} {build.client_build}; validating "
            f"against {doc.game} {doc.client_build}"
        )
    try:
        engine = TalentAllocationEngine(doc, point_limit=args.point_limit)
    except AllocationEngineError as exc:
        raise PipelineError(str(exc)) from exc
    result = engine.validate(build.to_state())
    status = "VALID" if result.valid else "INVALID"
    print(
        f"{args.build_file}: {status} - {result.spent_points}/{result.point_budget} points "
        f"spent, {result.remaining_points} remaining"
    )
    for issue in result.issues:
        print(f"  {issue}")
    return EXIT_OK if result.valid else EXIT_FAILURE


def _cmd_replay(args: argparse.Namespace) -> int:
    version = ClientVersion.parse(args.build)
    suite_path = args.suite or paths.conformance_path(args.game, version)
    suite = load_suite(suite_path)
    try:
        documents = load_suite_documents(suite, paths.project_root())
    except ValueError as exc:
        raise PipelineError(str(exc)) from exc
    scenarios = suite.scenarios
    if args.scenario:
        scenarios = [s for s in scenarios if s.id == args.scenario]
        if not scenarios:
            raise PipelineError(f"no scenario {args.scenario!r} in {suite_path}")
    failed = 0
    for scenario in scenarios:
        outcomes = run_scenario(scenario, documents)
        mismatches = outcome_report(scenario, outcomes)
        mark = "ok  " if not mismatches else "FAIL"
        print(f"{mark} {scenario.id} ({len(scenario.steps)} steps)")
        if args.scenario:
            for step, outcome in zip(scenario.steps, outcomes, strict=True):
                target = "" if step.node_id is None else f" node {step.node_id}"
                codes = ",".join(outcome.actual.reason_codes) or "-"
                print(
                    f"    {step.op}{target}: ok={outcome.actual.ok} codes={codes} "
                    f"spent={outcome.actual.spent_points} "
                    f"remaining={outcome.actual.remaining_points}"
                    + ("" if outcome.matches else "   <-- MISMATCH")
                )
        failed += bool(mismatches)
    print(f"{len(scenarios) - failed}/{len(scenarios)} scenarios reproduce the vectors")
    return EXIT_OK if not failed else EXIT_FAILURE
