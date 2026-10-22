"""Command line of the OTA preparation (M5.OTA.AUTO.1): ``detect`` and ``prepare``.

Called through ``scripts/ota_prepare.py`` (which adds the app's ``PRODUCT_BUNDLE``) by
``.github/workflows/ota-prepare.yml``; runbook ``docs/ota/auto-prepare.md``.

``detect`` exit codes: 0 a decision was made (``PREPARE`` or ``NO_ACTION``; the action is in
the JSON and in ``$GITHUB_OUTPUT``), 4 ``UNKNOWN``, 1 the detection itself failed.

``prepare`` exit codes: 0 ``PREPARED``, 3 ``BLOCKED``, 4 ``UNKNOWN``, 1 the preparation
could not run (an invalid detection document, an unexpected crash - its traceback is kept in
``<out>/crash.txt``). A preparation never makes anything publishable.
"""

from __future__ import annotations

import argparse
import os
import sys
import tempfile
import traceback
from collections.abc import Sequence
from datetime import UTC, datetime
from pathlib import Path

import httpx

from talent_data.freshness import evaluate_freshness
from talent_data.games import GameConfig, get_game
from talent_data.ota.s3_channels import PRODUCTION_PROFILE
from talent_data.pipeline import PipelineError, check_game_version
from talent_data.prepare.detect import Action, PriorListing, decide, read_detection
from talent_data.prepare.orchestrate import (
    EXIT_CODES,
    PrepareError,
    Services,
    committed_builds,
    evaluate_release,
    main_commit,
    prepare,
    run_command,
)
from talent_data.prepare.production import mirror_production, read_production
from talent_data.sources.base import BuildInfo, BuildSource
from talent_data.sources.http import HttpClient
from talent_data.sources.wago.source import WagoBuildSource
from talent_data.versions import ClientVersion

EXIT_DECIDED = 0
EXIT_FAILURE = 1
EXIT_UNKNOWN = 4
DEFAULT_KEYRING = Path("docs/ota/keys/production-keyring.json")
GAME = "forever"


class _RecordingSource:
    """The build source the freshness check reads, keeping what it listed: the same
    single request answers both "is there a newer build" and "is the requested one listed"."""

    def __init__(self, inner: BuildSource) -> None:
        self._inner = inner
        self.name = inner.name
        self.builds: list[BuildInfo] | None = None

    def list_builds(self, game: GameConfig) -> list[BuildInfo]:
        self.builds = self._inner.list_builds(game)
        return self.builds


def _now() -> datetime:
    return datetime.now(UTC).replace(microsecond=0)


def _version(game: GameConfig, text: str) -> ClientVersion:
    try:
        version = ClientVersion.parse(text)
    except ValueError as exc:
        raise PipelineError(str(exc)) from exc
    check_game_version(game, version)
    return version


def _append(path: str | None, text: str) -> None:
    if path:
        with Path(path).open("a", encoding="utf-8") as out:
            out.write(text)


def _write_outputs(path: str | None, values: dict[str, str]) -> None:
    _append(path, "".join(f"{name}={value}\n" for name, value in values.items()))


def _cmd_detect(args: argparse.Namespace) -> int:
    from talent_data.ota.preflight import production_keyring

    game = get_game(args.game)
    product = _version(game, args.product_build)
    requested = None if args.build is None else _version(game, args.build)
    base = None if args.base is None else _version(game, args.base)
    repo = args.repo.resolve()
    main = main_commit(repo)
    committed = committed_builds(repo, main, game.key)
    if base is not None and str(base) not in committed:
        raise PipelineError(f"--base {base} has no committed dataset at origin/main {main[:12]}")
    keyring = production_keyring(repo / args.keyring)
    now = _now()
    with HttpClient() as http:
        source = _RecordingSource(WagoBuildSource(http))
        freshness = evaluate_freshness(source, game, product, now)
    with httpx.Client(follow_redirects=False, timeout=30.0) as client:
        production = read_production(
            client, origin=args.production_origin, keyring=keyring, game=game.key, now=now
        )
    prior: list[str] | None = None
    if args.prior_artifacts is not None:
        prior = [
            line.strip()
            for line in args.prior_artifacts.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
    detection = decide(
        game=game,
        freshness=freshness,
        builds=source.builds,
        production=production,
        product_build=product,
        committed_builds=committed,
        main_commit=main,
        now=now,
        requested_build=requested,
        requested_base=base,
        force=args.force,
        prior_artifacts=prior,
        unlisted=PriorListing.UNAVAILABLE if args.prior_unavailable else PriorListing.NOT_CHECKED,
    )
    text = detection.to_json()
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    sys.stdout.write(text)
    print(f"{detection.action.value}: {detection.reason}", file=sys.stderr)
    _append(
        args.step_summary,
        f"## OTA preparation: detection {detection.action.value}\n\n{detection.reason}\n\n"
        f"* App PRODUCT_BUNDLE `{detection.product_build}`; production catalog "
        f"{detection.production.status.value} ({detection.production.reason})\n"
        f"* Build index: {detection.source_status.value} ({detection.source_reason})\n\n",
    )
    _write_outputs(
        args.github_output,
        {
            "action": detection.action.value,
            "candidate": detection.candidate_build or "",
            "key": detection.preparation_key or "",
        },
    )
    return EXIT_UNKNOWN if detection.action is Action.UNKNOWN else EXIT_DECIDED


def _cmd_prepare(args: argparse.Namespace) -> int:
    from talent_data.ota.preflight import production_keyring

    try:
        detection = read_detection(args.detection)
    except (OSError, ValueError) as exc:
        print(f"error: {args.detection} is not a valid detection document: {exc}", file=sys.stderr)
        return EXIT_FAILURE
    if detection.action is not Action.PREPARE:
        print(
            f"error: the detection says {detection.action.value}; nothing to prepare",
            file=sys.stderr,
        )
        return EXIT_FAILURE
    repo = args.repo.resolve()
    out = args.out.resolve()
    workspace = (
        args.workspace.resolve()
        if args.workspace is not None
        else Path(tempfile.mkdtemp(prefix="ota-prepare-")) / "workspace"
    )
    keyring = production_keyring(repo / args.keyring)
    with httpx.Client(follow_redirects=False, timeout=30.0) as client:
        services = Services(
            run=run_command,
            read_production=lambda: read_production(
                client,
                origin=args.production_origin,
                keyring=keyring,
                game=detection.game,
                now=_now(),
            ),
            mirror=lambda state, store: mirror_production(
                client, state, store, game=detection.game
            ),
            evaluate=evaluate_release,
            clock=_now,
        )
        try:
            report = prepare(detection, repo=repo, out=out, workspace=workspace, services=services)
        except Exception:  # noqa: BLE001 - the crash is recorded, then reported as a failure
            out.mkdir(parents=True, exist_ok=True)
            (out / "crash.txt").write_text(traceback.format_exc(), encoding="utf-8")
            print(f"error: the preparation crashed; see {out / 'crash.txt'}", file=sys.stderr)
            traceback.print_exc()
            return EXIT_FAILURE
    summary = (out / "summary.md").read_text(encoding="utf-8")
    sys.stdout.write(summary)
    _append(args.step_summary, summary)
    _write_outputs(
        args.github_output,
        {"status": report.status.value, "artifact_name": report.artifact_name},
    )
    print(f"workspace (disposable): {workspace}", file=sys.stderr)
    return EXIT_CODES[report.status]


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ota_prepare.py", description="Prepare a new Forever build for OTA review"
    )
    sub = parser.add_subparsers(dest="command", required=True)

    def common(p: argparse.ArgumentParser) -> None:
        p.add_argument("--repo", type=Path, default=Path.cwd(), help="checkout (default: cwd)")
        p.add_argument("--production-origin", default=PRODUCTION_PROFILE.public_origin)
        p.add_argument(
            "--keyring",
            type=Path,
            default=DEFAULT_KEYRING,
            help="committed production public keyring, relative to --repo",
        )
        p.add_argument(
            "--github-output",
            default=os.environ.get("GITHUB_OUTPUT"),
            help="append step outputs here (default: $GITHUB_OUTPUT)",
        )
        p.add_argument(
            "--step-summary",
            default=os.environ.get("GITHUB_STEP_SUMMARY"),
            help="append a markdown summary here (default: $GITHUB_STEP_SUMMARY)",
        )

    detect = sub.add_parser("detect", help="decide whether a build needs preparing")
    common(detect)
    detect.add_argument("--game", default=GAME)
    detect.add_argument("--product-build", required=True, help="the app's PRODUCT_BUNDLE")
    detect.add_argument("--build", help="prepare this listed, publishable build")
    detect.add_argument("--base", help="diff against this committed build")
    detect.add_argument(
        "--force",
        action="store_true",
        help="prepare even when published, bundled, not newer or already prepared",
    )
    detect.add_argument(
        "--prior-artifacts", type=Path, help="names of kept artifacts, one per line"
    )
    detect.add_argument(
        "--prior-unavailable",
        action="store_true",
        help="the artifact listing failed (recorded; preparing again is harmless)",
    )
    detect.add_argument("--output", type=Path, help="also write the detection JSON here")
    detect.set_defaults(handler=_cmd_detect)

    prep = sub.add_parser("prepare", help="prepare the detected build (never publishes)")
    common(prep)
    prep.add_argument("--detection", type=Path, required=True)
    prep.add_argument("--out", type=Path, required=True, help="report and artifacts directory")
    prep.add_argument("--workspace", type=Path, help="empty directory for the disposable clone")
    prep.set_defaults(handler=_cmd_prepare)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        return int(args.handler(args))
    except (PipelineError, PrepareError, ValueError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_FAILURE


if __name__ == "__main__":
    raise SystemExit(main())
