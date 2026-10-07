"""Talent visuals commands (``python -m talent_data visuals ...``).

visuals fetch      --build <version>             online:  SpellMisc + every icon file
visuals report     --build <version> [--offline]          icon coverage (the gate)
visuals normalize  --build <version> [--offline]          sidecars + icons.json + PNGs
visuals check      --build <version>             offline: committed files only (CI)

``fetch`` is the only command that needs the network. ``normalize --offline`` reproduces
the committed ``data/visuals/<game>/<build>/`` from the local cache; ``check`` reads nothing
but the repository and needs neither the cache nor Pillow.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from talent_data import paths
from talent_data.games import GAMES, GameConfig, get_game
from talent_data.pipeline import PipelineError, check_game_version
from talent_data.sources.http import HttpClient
from talent_data.sources.wago.casc import WagoCascFileSource
from talent_data.sources.wago.source import WagoDb2Source
from talent_data.versions import ClientVersion
from talent_data.visuals.coverage import IconCoverageReport
from talent_data.visuals.pipeline import (
    BuildResolution,
    check_canonical,
    fetch_icons,
    fetch_visual_tables,
    publish,
    resolve_build,
    write_coverage_report,
)

EXIT_OK = 0
EXIT_FAILURE = 1


def register(sub: argparse._SubParsersAction[argparse.ArgumentParser]) -> None:
    visuals = sub.add_parser("visuals", help="talent icons: resolve, fetch, publish, verify")
    visuals_sub = visuals.add_subparsers(dest="visuals_command", required=True)

    def common(p: argparse.ArgumentParser) -> None:
        p.add_argument("--game", default="forever", choices=sorted(GAMES))
        p.add_argument("--build", required=True, help="exact client version (never 'latest')")

    fetch = visuals_sub.add_parser(
        "fetch", help="download SpellMisc and every resolved icon file (network)"
    )
    common(fetch)
    fetch.set_defaults(handler=_cmd_fetch)

    report = visuals_sub.add_parser("report", help="icon coverage report for all classes")
    common(report)
    report.add_argument("--offline", action="store_true", help="use only the local DB2 cache")
    report.add_argument("--json", dest="json_out", type=Path, help="default: artifacts/visuals/")
    report.set_defaults(handler=_cmd_report)

    norm = visuals_sub.add_parser(
        "normalize", help="write data/visuals/<game>/<build>/ (sidecars, registry, images)"
    )
    common(norm)
    norm.add_argument("--offline", action="store_true", help="use only the local cache, no network")
    norm.set_defaults(handler=_cmd_normalize)

    check = visuals_sub.add_parser(
        "check", help="verify the committed visuals against the committed dataset (offline)"
    )
    common(check)
    check.set_defaults(handler=_cmd_check)


def _version(game: GameConfig, text: str) -> ClientVersion:
    try:
        version = ClientVersion.parse(text)
    except ValueError as exc:
        raise PipelineError(f"visuals are pinned to an exact build: {exc}") from exc
    check_game_version(game, version)
    return version


def _db2(game: GameConfig, http: HttpClient | None) -> WagoDb2Source:
    return WagoDb2Source(http, paths.raw_cache_dir("wago", game.key), offline=http is None)


def _casc(game: GameConfig, http: HttpClient | None) -> WagoCascFileSource:
    return WagoCascFileSource(http, paths.casc_cache_dir("wago", game.key), offline=http is None)


def _print_report(report: IconCoverageReport) -> None:
    print(f"{report.game} {report.client_build}: icon coverage")
    for c in report.classes:
        print(
            f"  {c.class_token:<8} entries {c.total_entries:>3}  resolved {c.resolved_entries:>3}"
            f"  unresolved {c.unresolved_entries:>3}  distinct icons {c.distinct_file_data_ids:>3}"
        )
    print(
        f"  total entries {report.total_entries}, resolved {report.resolved_entries}, "
        f"unresolved {report.unresolved_entries}"
    )
    print(
        f"  distinct FileDataIDs {report.distinct_file_data_ids}, "
        f"shared by more than one entry {report.shared_file_data_ids}"
    )
    for u in report.unresolved:
        print(
            f"  UNRESOLVED {u.class_token} node {u.node_id} entry {u.entry_id} spell "
            f"{u.spell_id} display {u.display_spell_id}: {u.reason} ({u.detail})"
        )


def _require_complete(resolution: BuildResolution) -> None:
    report = resolution.report
    if not report.complete:
        raise PipelineError(
            f"icon coverage of {resolution.version} is {report.resolved_entries}/"
            f"{report.total_entries}; nothing is fetched or published for an incomplete build"
        )


def _cmd_fetch(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    version = _version(game, args.build)
    with HttpClient() as http:
        source = _db2(game, http)
        for table in fetch_visual_tables(source, version):
            print(f"  {table}")
        resolution = resolve_build(source, game, version)
        _print_report(resolution.report)
        _require_complete(resolution)
        fetched = fetch_icons(_casc(game, http), version, resolution.file_data_ids)
    total = sum(path.stat().st_size for path in fetched.values())
    print(f"fetched {len(fetched)} icon files for {version} ({total} bytes)")
    return EXIT_OK


def _cmd_report(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    version = _version(game, args.build)
    if args.offline:
        resolution = resolve_build(_db2(game, None), game, version)
    else:
        with HttpClient() as http:
            resolution = resolve_build(_db2(game, http), game, version)
    report = resolution.report
    _print_report(report)
    out = args.json_out or paths.icon_coverage_path(game.key, version)
    write_coverage_report(report, out)
    print(f"coverage report: {out}")
    return EXIT_OK if report.complete else EXIT_FAILURE


def _cmd_normalize(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    version = _version(game, args.build)
    http = None if args.offline else HttpClient()
    try:
        source = _db2(game, http)
        resolution = resolve_build(source, game, version)
        _print_report(resolution.report)
        _require_complete(resolution)
        published = publish(resolution, _casc(game, http), source_name=source.name)
    finally:
        if http is not None:
            http.close()
    write_coverage_report(resolution.report, paths.icon_coverage_path(game.key, version))
    print(
        f"wrote {len(published.documents)} talent-visuals documents + icons.json + "
        f"{len(published.registry.icons)} images into {published.directory}"
    )
    print(f"  client files {published.source_bytes} bytes -> images {published.image_bytes} bytes")
    return EXIT_OK


def _cmd_check(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    version = _version(game, args.build)
    problems = check_canonical(game, version)
    for problem in problems:
        print(f"  ERROR: {problem}")
    if problems:
        return EXIT_FAILURE
    print(f"visuals of {version}: consistent with the dataset, registry and image files")
    return EXIT_OK
