"""Talent localization commands (``python -m talent_data localization ...``).

localization fetch      --build <v> --locale ruRU            online: enUS + locale tables
localization report     --build <v> --locale ruRU [--offline]        coverage (printed)
localization normalize  --build <v> --locale ruRU --classes <c> [--offline]
                                                             overlays of <c> + coverage report
localization check      --build <v> --locale ruRU            offline: committed files (CI)

``fetch`` (and ``report``/``normalize`` without ``--offline``) is the only network use; the
app never fetches text. ``normalize`` publishes only the classes it is given (M5.REL.3a: the
Warrior pilot) - the coverage report always covers every class of the build.
"""

from __future__ import annotations

import argparse

from talent_data import paths
from talent_data.db2.tables import TOOLTIP_TABLES
from talent_data.games import GAMES, GameConfig, get_game
from talent_data.localization.coverage import LocalizationCoverageReport
from talent_data.localization.pipeline import (
    BuildLocalization,
    check_canonical,
    localize_build,
    publish,
    published_classes,
)
from talent_data.localization.source import (
    LOCALIZATION_TABLES,
    WagoLocalizedDb2Source,
    require_supported_locale,
)
from talent_data.models.localization import Locale
from talent_data.pipeline import PipelineError, check_game_version
from talent_data.sources.http import HttpClient
from talent_data.sources.wago.source import WagoDb2Source
from talent_data.versions import ClientVersion

EXIT_OK = 0
EXIT_FAILURE = 1


def register(sub: argparse._SubParsersAction[argparse.ArgumentParser]) -> None:
    command = sub.add_parser("localization", help="localized talent text overlays")
    localization_sub = command.add_subparsers(dest="localization_command", required=True)

    def common(p: argparse.ArgumentParser) -> None:
        p.add_argument("--game", default="forever", choices=sorted(GAMES))
        p.add_argument("--build", required=True, help="exact client version (never 'latest')")
        p.add_argument("--locale", required=True, help="client locale, e.g. ruRU")

    fetch = localization_sub.add_parser("fetch", help="download the enUS and locale tables")
    common(fetch)
    fetch.set_defaults(handler=_cmd_fetch)

    report = localization_sub.add_parser("report", help="print the coverage of every class")
    common(report)
    report.add_argument("--offline", action="store_true", help="use only the local DB2 cache")
    report.set_defaults(handler=_cmd_report)

    norm = localization_sub.add_parser(
        "normalize", help="write the overlays of --classes and the coverage report"
    )
    common(norm)
    norm.add_argument(
        "--classes", required=True, help="comma-separated class tokens to publish, or 'all'"
    )
    norm.add_argument("--offline", action="store_true", help="use only the local DB2 cache")
    norm.set_defaults(handler=_cmd_normalize)

    check = localization_sub.add_parser(
        "check", help="verify the committed overlays and report (offline)"
    )
    common(check)
    check.set_defaults(handler=_cmd_check)


def _version(game: GameConfig, text: str) -> ClientVersion:
    try:
        version = ClientVersion.parse(text)
    except ValueError as exc:
        raise PipelineError(f"localization is pinned to an exact build: {exc}") from exc
    check_game_version(game, version)
    return version


def _sources(
    game: GameConfig, locale: Locale, http: HttpClient | None
) -> tuple[WagoDb2Source, WagoLocalizedDb2Source]:
    cache = paths.raw_cache_dir("wago", game.key)
    return (
        WagoDb2Source(http, cache, offline=http is None),
        WagoLocalizedDb2Source(http, cache, locale, offline=http is None),
    )


def _localize(args: argparse.Namespace) -> BuildLocalization:
    game = get_game(args.game)
    version = _version(game, args.build)
    locale = require_supported_locale(args.locale)
    if args.offline:
        english, localized = _sources(game, locale, None)
        return localize_build(
            english, localized, english, game, version, locale, source_name=english.name
        )
    with HttpClient() as http:
        english, localized = _sources(game, locale, http)
        return localize_build(
            english, localized, english, game, version, locale, source_name=english.name
        )


def _print(build: BuildLocalization, report: LocalizationCoverageReport) -> None:
    for check in build.verification:
        print(
            f"  {check.table:<16} {check.locale}: {check.localized_rows}/{check.source_rows} rows, "
            f"{check.translated_rows} translated, {check.identical_rows} identical to enUS, "
            f"{check.missing_rows} missing"
        )
    print(f"{report.game} {report.client_build} {report.locale}: talent text coverage")
    for row in report.classes:
        print(
            f"  {row.class_token:<8} entries {row.entries:>3}  names {row.names.translated:>3}"
            f" translated / {row.names.untranslated} untranslated / {row.names.missing} missing"
            f"  descriptions {row.descriptions.rendered:>3} rendered  ranks "
            f"{row.rendered_ranks:>3}/{row.ranks:<3}  {'published' if row.published else ''}"
        )
    t = report.totals
    print(
        f"  total entries {t.entries}: names {t.names.translated} translated, descriptions "
        f"{t.descriptions.rendered} rendered ({t.rendered_ranks}/{t.ranks} ranks)"
    )


def _cmd_fetch(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    version = _version(game, args.build)
    locale = require_supported_locale(args.locale)
    with HttpClient() as http:
        english, localized = _sources(game, locale, http)
        for table in sorted({*LOCALIZATION_TABLES, *TOOLTIP_TABLES}):
            english.ensure_table(version, table)
        for table in LOCALIZATION_TABLES:
            localized.ensure_table(version, table)
            print(f"  {table} {locale}: {localized.table_url(version, table)}")
    print(f"fetched the localization tables of {version} {locale}")
    return EXIT_OK


def _cmd_report(args: argparse.Namespace) -> int:
    build = _localize(args)
    _print(build, build.report(frozenset()))
    return EXIT_OK


def _cmd_normalize(args: argparse.Namespace) -> int:
    build = _localize(args)
    chosen = published_classes(build, [c.strip() for c in args.classes.split(",") if c.strip()])
    published = publish(build, chosen)
    _print(build, published.report)
    print(f"wrote {len(chosen)} talent-localization documents into {published.directory}")
    print(
        "coverage report: "
        f"{paths.localization_coverage_path(build.game.key, build.version, build.locale)}"
    )
    return EXIT_OK


def _cmd_check(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    version = _version(game, args.build)
    locale = require_supported_locale(args.locale)
    problems = check_canonical(game, version, locale)
    for problem in problems:
        print(f"  ERROR: {problem}")
    if problems:
        return EXIT_FAILURE
    print(f"{locale} localization of {version}: consistent with the dataset and the report")
    return EXIT_OK
