"""Talent tooltip commands (``python -m talent_data tooltips ...``).

tooltips fetch      --build <version>             online:  the DB2 tables tooltips read
tooltips report     --build <version> [--offline]          coverage report (printed)
tooltips normalize  --build <version> [--offline]          sidecars + coverage report
tooltips check      --build <version>             offline: committed files only (CI)

``fetch`` (and ``report``/``normalize`` without ``--offline``) is the only network use.
``normalize --offline`` reproduces ``data/tooltips/<game>/<build>/`` from the local cache;
``check`` reads nothing but the repository. Partial descriptions are published - the gate
is the report, and a construct that cannot be evaluated is a gap, never a guess.
"""

from __future__ import annotations

import argparse

from talent_data import paths
from talent_data.games import GAMES, GameConfig, get_game
from talent_data.pipeline import PipelineError, check_game_version
from talent_data.sources.http import HttpClient
from talent_data.sources.wago.source import WagoDb2Source
from talent_data.tooltips.coverage import TooltipCoverageReport
from talent_data.tooltips.pipeline import (
    BuildTooltips,
    check_canonical,
    fetch_tooltip_tables,
    publish,
    render_build,
)
from talent_data.versions import ClientVersion

EXIT_OK = 0
EXIT_FAILURE = 1


def register(sub: argparse._SubParsersAction[argparse.ArgumentParser]) -> None:
    tooltips = sub.add_parser("tooltips", help="talent descriptions: render, publish, verify")
    tooltips_sub = tooltips.add_subparsers(dest="tooltips_command", required=True)

    def common(p: argparse.ArgumentParser) -> None:
        p.add_argument("--game", default="forever", choices=sorted(GAMES))
        p.add_argument("--build", required=True, help="exact client version (never 'latest')")

    fetch = tooltips_sub.add_parser("fetch", help="download the DB2 tables tooltips read")
    common(fetch)
    fetch.set_defaults(handler=_cmd_fetch)

    report = tooltips_sub.add_parser("report", help="render every rank and print coverage")
    common(report)
    report.add_argument("--offline", action="store_true", help="use only the local DB2 cache")
    report.set_defaults(handler=_cmd_report)

    norm = tooltips_sub.add_parser(
        "normalize", help="write data/tooltips/<game>/<build>/ and the coverage report"
    )
    common(norm)
    norm.add_argument("--offline", action="store_true", help="use only the local DB2 cache")
    norm.set_defaults(handler=_cmd_normalize)

    check = tooltips_sub.add_parser(
        "check", help="verify the committed tooltips and report against the dataset (offline)"
    )
    common(check)
    check.set_defaults(handler=_cmd_check)


def _version(game: GameConfig, text: str) -> ClientVersion:
    try:
        version = ClientVersion.parse(text)
    except ValueError as exc:
        raise PipelineError(f"tooltips are pinned to an exact build: {exc}") from exc
    check_game_version(game, version)
    return version


def _db2(game: GameConfig, http: HttpClient | None) -> WagoDb2Source:
    return WagoDb2Source(http, paths.raw_cache_dir("wago", game.key), offline=http is None)


def _print_report(report: TooltipCoverageReport) -> None:
    t = report.totals
    print(f"{report.game} {report.client_build}: tooltip coverage")
    for c in report.classes:
        print(
            f"  {c.class_token:<8} entries {c.entries:>3}  ranks {c.ranks:>4}  resolved "
            f"{c.resolved:>4}  partial {c.partial:>3}  unresolved {c.unresolved:>3}"
        )
    print(
        f"  total entries {t.entries}, ranks {t.ranks}: resolved {t.resolved} "
        f"({t.resolved_percent:.2f}%), partial {t.partial}, unresolved {t.unresolved}"
    )
    for category in report.categories:
        if category.occurrences:
            print(
                f"  {category.category:<26} {category.status:<19} "
                f"{category.occurrences:>4} occurrences, {category.resolved} resolved"
            )


def _render(args: argparse.Namespace) -> BuildTooltips:
    game = get_game(args.game)
    version = _version(game, args.build)
    if args.offline:
        source = _db2(game, None)
        return render_build(source, game, version, source_name=source.name)
    with HttpClient() as http:
        source = _db2(game, http)
        return render_build(source, game, version, source_name=source.name)


def _cmd_fetch(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    version = _version(game, args.build)
    with HttpClient() as http:
        for table in fetch_tooltip_tables(_db2(game, http), version):
            print(f"  {table}")
    print(f"fetched the tooltip tables of {version}")
    return EXIT_OK


def _cmd_report(args: argparse.Namespace) -> int:
    _print_report(_render(args).report)
    return EXIT_OK


def _cmd_normalize(args: argparse.Namespace) -> int:
    build = _render(args)
    published = publish(build)
    _print_report(published.report)
    print(f"wrote {len(build.tooltips)} talent-tooltips documents into {published.directory}")
    print(f"coverage report: {paths.tooltip_coverage_path(build.game.key, build.version)}")
    return EXIT_OK


def _cmd_check(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    version = _version(game, args.build)
    problems = check_canonical(game, version)
    for problem in problems:
        print(f"  ERROR: {problem}")
    if problems:
        return EXIT_FAILURE
    print(f"tooltips of {version}: consistent with the dataset and the coverage report")
    return EXIT_OK
