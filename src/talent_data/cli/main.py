"""Command line interface: ``python -m talent_data <command> ...``.

builds list    --game forever
builds latest  --game forever
fetch          --game forever --build <version|latest>
normalize      --game forever --build <version|latest> --class warrior
diff           --game forever --class warrior --from <version> --to <version|latest>
validate       <normalized.json>
schema         [--out docs/schema]
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from collections.abc import Sequence
from pathlib import Path

from pydantic import BaseModel

from talent_data import paths
from talent_data.db2.tables import TRAIT_TABLES
from talent_data.diff.models import DiffReport
from talent_data.diff.render import render_text
from talent_data.games import GAMES, GameConfig, get_game
from talent_data.models.talents import TalentDataDocument
from talent_data.normalization.class_resolution import ClassResolutionError
from talent_data.normalization.normalizer import NormalizationError
from talent_data.pipeline import (
    PipelineError,
    check_game_version,
    diff,
    fetch_tables,
    find_latest_build,
    normalize,
    read_document,
    write_document,
)
from talent_data.sources.base import SourceError
from talent_data.sources.http import DownloadError, HttpClient
from talent_data.sources.wago.source import WagoBuildSource, WagoDb2Source
from talent_data.validation.validators import ValidationReport, validate_document
from talent_data.versions import ClientVersion

log = logging.getLogger("talent_data")

EXIT_OK = 0
EXIT_FAILURE = 1


def main(argv: Sequence[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.WARNING,
        format="%(levelname)s %(name)s: %(message)s",
    )
    try:
        return int(args.handler(args))
    except (
        PipelineError,
        SourceError,
        DownloadError,
        ClassResolutionError,
        NormalizationError,
    ) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_FAILURE


# --------------------------------------------------------------------------- parser


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m talent_data",
        description="WoW talent data pipeline (M0: Forever / Warrior proof of concept)",
    )
    parser.add_argument("-v", "--verbose", action="store_true", help="debug logging")
    sub = parser.add_subparsers(dest="command", required=True)

    def game_arg(p: argparse.ArgumentParser) -> None:
        p.add_argument("--game", default="forever", choices=sorted(GAMES))

    builds = sub.add_parser("builds", help="discover client builds")
    builds_sub = builds.add_subparsers(dest="builds_command", required=True)
    b_list = builds_sub.add_parser("list", help="list all builds of the game, newest first")
    game_arg(b_list)
    b_list.set_defaults(handler=_cmd_builds_list)
    b_latest = builds_sub.add_parser("latest", help="print the latest build of the game")
    game_arg(b_latest)
    b_latest.set_defaults(handler=_cmd_builds_latest)

    fetch = sub.add_parser("fetch", help="download the DB2 tables needed for talents")
    game_arg(fetch)
    fetch.add_argument("--build", required=True, help="client version or 'latest'")
    fetch.add_argument("--force", action="store_true", help="re-download even if cached")
    fetch.set_defaults(handler=_cmd_fetch)

    norm = sub.add_parser("normalize", help="build the normalized talent JSON for a class")
    game_arg(norm)
    norm.add_argument("--build", required=True, help="client version or 'latest'")
    norm.add_argument("--class", dest="class_key", required=True, help="e.g. warrior")
    norm.add_argument("--output", type=Path, help="default: data/normalized/<game>/<build>/")
    norm.add_argument(
        "--offline", action="store_true", help="use only the local DB2 cache, no network"
    )
    norm.set_defaults(handler=_cmd_normalize)

    dif = sub.add_parser("diff", help="semantic diff of a class between two builds")
    game_arg(dif)
    dif.add_argument("--class", dest="class_key", required=True)
    dif.add_argument("--from", dest="from_build", required=True)
    dif.add_argument("--to", dest="to_build", required=True, help="version or 'latest'")
    dif.add_argument("--json", dest="json_out", type=Path, help="default: artifacts/diff/...")
    dif.add_argument("--offline", action="store_true")
    dif.set_defaults(handler=_cmd_diff)

    schema = sub.add_parser("schema", help="write JSON Schemas of the public contracts")
    schema.add_argument("--out", type=Path, default=Path("docs/schema"))
    schema.set_defaults(handler=_cmd_schema)

    val = sub.add_parser("validate", help="validate a normalized JSON document")
    val.add_argument("path", type=Path)
    val.set_defaults(handler=_cmd_validate)
    return parser


# --------------------------------------------------------------------------- helpers


def _http() -> HttpClient:
    return HttpClient()


def _db2_source(game: GameConfig, http: HttpClient | None, offline: bool) -> WagoDb2Source:
    return WagoDb2Source(
        None if offline else http,
        paths.raw_cache_dir("wago", game.key),
        offline=offline,
    )


def _resolve_version(game: GameConfig, text: str, http: HttpClient | None) -> ClientVersion:
    if text == "latest":
        if http is None:
            raise PipelineError("'latest' needs network access (drop --offline)")
        info = find_latest_build(WagoBuildSource(http), game)
        print(f"{game.display_name} latest build: {info.version}", file=sys.stderr)
        return info.version
    try:
        version = ClientVersion.parse(text)
    except ValueError as exc:
        raise PipelineError(str(exc)) from exc
    check_game_version(game, version)
    return version


def _print_report(report: ValidationReport) -> None:
    for w in report.warnings:
        print(f"  warning: {w}")
    for e in report.errors:
        print(f"  ERROR: {e}")


def _summary(doc: TalentDataDocument) -> str:
    parts = []
    for tree in doc.trees:
        sections = (
            ", ".join(f"#{s.index}: {len(s.node_ids)} nodes" for s in tree.sections) or "unresolved"
        )
        entries = sum(len(n.entries) for n in tree.nodes)
        prereqs = sum(len(n.prerequisites) for n in tree.nodes)
        ranks = sum(n.max_rank for n in tree.nodes)
        parts.append(
            f"  tree {tree.id}: {len(tree.nodes)} nodes, {entries} entries, "
            f"{prereqs} prerequisite links, {ranks} total ranks, sections [{sections}]"
            + (f", point budget {tree.point_budget.max_points}" if tree.point_budget else "")
        )
    return "\n".join(parts)


def _normalize_to_disk(
    game: GameConfig,
    version: ClientVersion,
    class_key: str,
    http: HttpClient | None,
    offline: bool,
    output: Path | None = None,
) -> tuple[TalentDataDocument, Path]:
    result = normalize(_db2_source(game, http, offline), game, version, class_key)
    doc = result.document
    print(f"{game.display_name} {doc.class_.name} @ {version}: {len(doc.trees)} tree(s)")
    print(_summary(doc))
    _print_report(result.report)
    if not result.report.ok:
        raise PipelineError(
            f"validation failed with {len(result.report.errors)} error(s); nothing written"
        )
    path = output or paths.normalized_path(game.key, version, doc.class_.token)
    write_document(doc, path)
    print(f"wrote {path} ({doc.content_hash})")
    return doc, path


# --------------------------------------------------------------------------- commands


def _cmd_builds_list(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    with _http() as http:
        builds = WagoBuildSource(http).list_builds(game)
    for b in sorted(builds, key=lambda b: (b.created_at, b.version), reverse=True):
        print(f"{b.version}  {b.created_at:%Y-%m-%d %H:%M:%S}  {b.product}")
    return EXIT_OK


def _cmd_builds_latest(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    with _http() as http:
        info = find_latest_build(WagoBuildSource(http), game)
    print(f"{game.display_name} latest build: {info.version}")
    print(
        f"  product={info.product} created_at={info.created_at:%Y-%m-%d %H:%M:%S}", file=sys.stderr
    )
    return EXIT_OK


def _cmd_fetch(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    with _http() as http:
        version = _resolve_version(game, args.build, http)
        source = _db2_source(game, http, offline=False)
        if args.force:
            for table in TRAIT_TABLES:
                source.table_path(version, table).with_name(f"{table}.csv.meta.json").unlink(
                    missing_ok=True
                )
        fetched = fetch_tables(source, version, progress=lambda t: print(f"  {t}"))
    print(
        f"fetched {len(fetched)} DB2 tables for {version} into "
        f"{paths.raw_cache_dir('wago', game.key) / str(version)}"
    )
    return EXIT_OK


def _cmd_normalize(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    if args.offline:
        version = _resolve_version(game, args.build, None)
        _normalize_to_disk(game, version, args.class_key, None, True, args.output)
        return EXIT_OK
    with _http() as http:
        version = _resolve_version(game, args.build, http)
        _normalize_to_disk(game, version, args.class_key, http, False, args.output)
    return EXIT_OK


def _cmd_diff(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    http = None if args.offline else _http()
    try:
        old_v = _resolve_version(game, args.from_build, http)
        new_v = _resolve_version(game, args.to_build, http)
        docs = []
        for version in (old_v, new_v):
            existing = _existing_document(game, version, args.class_key)
            if existing is None:
                print(f"normalizing {args.class_key} @ {version} ...", file=sys.stderr)
                existing, _ = _normalize_to_disk(game, version, args.class_key, http, args.offline)
            docs.append(existing)
    finally:
        if http is not None:
            http.close()
    report = diff(docs[0], docs[1])
    print(render_text(report, game.display_name), end="")
    out = args.json_out or paths.diff_path(game.key, report.class_.token, old_v, new_v)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report.to_json(), encoding="utf-8")
    print(f"\nJSON diff: {out}")
    return EXIT_OK


def _existing_document(
    game: GameConfig, version: ClientVersion, class_key: str
) -> TalentDataDocument | None:
    path = paths.normalized_path(game.key, version, class_key)
    if not path.is_file():
        return None
    doc = read_document(path)
    report = validate_document(doc)
    if not report.ok:
        log.warning("existing %s is invalid; re-normalizing", path)
        return None
    return doc


def _cmd_schema(args: argparse.Namespace) -> int:
    for name, text in json_schemas().items():
        path = args.out / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        print(f"wrote {path}")
    return EXIT_OK


def json_schemas() -> dict[str, str]:
    """File name -> JSON Schema text for every public contract."""
    return {
        "talent-data.v1.schema.json": _schema_text(TalentDataDocument),
        "talent-diff.v1.schema.json": _schema_text(DiffReport),
    }


def _schema_text(model: type[BaseModel]) -> str:
    schema = model.model_json_schema(by_alias=True, mode="serialization")
    return json.dumps(schema, indent=2, sort_keys=True) + "\n"


def _cmd_validate(args: argparse.Namespace) -> int:
    doc = read_document(args.path)
    report = validate_document(doc)
    print(f"{args.path}: {len(report.errors)} error(s), {len(report.warnings)} warning(s)")
    _print_report(report)
    return EXIT_OK if report.ok else EXIT_FAILURE
