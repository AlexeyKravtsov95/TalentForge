"""Command line interface: ``python -m talent_data <command> ...``.

builds list    --game forever
builds latest  --game forever
builds freshness --game forever --product-build <version> [--output <file.json>]
fetch          --game forever --build <version|latest>
normalize      --game forever --build <version|latest> --class warrior|all|warrior,priest,...
diff           --game forever --class warrior|all --from <version> --to <version|latest>
validate       <normalized.json>
manifest       <manifest.json>
schema         [--out docs/schema]
allocation     validate|replay   (M2 allocation engine developer tools)
visuals        fetch|report|normalize|check   (M5.V1 talent icons, build-pinned)
tooltips       fetch|report|normalize|check   (M5.V2 talent descriptions, build-pinned)
localization   fetch|report|normalize|check   (M5.REL.3a localized text overlays)
ota            build-release|validate|report|stage|promote|attach-signature|
               rollback|resign|verify-live|sign|gc   (M5.OTA.2 publisher; needs the
               `ota` extra)

``builds freshness`` (M5.DATA.2) compares the product build with the latest publishable
build and prints a ``product-freshness`` v1 JSON document; its exit code is the status:
0 CURRENT, 3 OUTDATED, 4 UNKNOWN (a source/network failure is UNKNOWN, never CURRENT).

Every other command exits 0 on success and 1 on a failure, except 5 when the failure was
caused by the source being unreachable (network, timeout, retryable HTTP status after the
client's retries; ``source_unavailable``) - the answer is then unknown, not wrong.

``--class all`` (M1) normalizes / diffs every class of the build, deterministically ordered
by class id, and writes a dataset manifest (normalize). A batch that fails for any class
names the class, prints its error, exits non-zero and publishes nothing.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from collections.abc import Sequence
from datetime import UTC, datetime
from pathlib import Path

import httpx
from pydantic import BaseModel

from talent_data import paths
from talent_data.allocation.conformance import ConformanceSuite
from talent_data.allocation.models import BuildFile
from talent_data.cli import allocation as allocation_cli
from talent_data.cli import localization as localization_cli
from talent_data.cli import ota as ota_cli
from talent_data.cli import tooltips as tooltips_cli
from talent_data.cli import visuals as visuals_cli
from talent_data.db2.tables import TRAIT_TABLES
from talent_data.diff.models import DatasetDiffReport, DiffReport
from talent_data.diff.render import render_dataset_text, render_text
from talent_data.freshness import FreshnessStatus, evaluate_freshness
from talent_data.games import GAMES, GameConfig, get_game
from talent_data.models.localization import TalentLocalizationDocument
from talent_data.models.manifest import DatasetManifest
from talent_data.models.talents import TalentDataDocument
from talent_data.models.tooltips import TalentTooltipsDocument
from talent_data.models.visuals import TalentIconRegistry, TalentVisualsDocument
from talent_data.normalization.class_resolution import ClassResolutionError
from talent_data.normalization.normalizer import NormalizationError
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import OtaCatalog, OtaReleaseManifest, SignedEnvelope
from talent_data.pipeline import (
    ALL_CLASSES,
    PipelineError,
    check_game_version,
    diff,
    diff_batch,
    fetch_tables,
    find_latest_build,
    normalize,
    normalize_batch,
    parse_class_keys,
    probe_table_status,
    read_document,
    read_manifest,
    validate_manifest_against_documents,
    write_batch,
    write_document,
    write_json,
)
from talent_data.sources.base import SourceError, TableStatus, classify_build
from talent_data.sources.capabilities import snapshot_path
from talent_data.sources.http import RETRYABLE_STATUS, DownloadError, HttpClient
from talent_data.sources.wago.source import WagoBuildSource, WagoDb2Source
from talent_data.validation.validators import ValidationReport, validate_document
from talent_data.versions import ClientVersion

log = logging.getLogger("talent_data")

EXIT_OK = 0
EXIT_FAILURE = 1
# The source could not be reached - a network/timeout failure or a retryable HTTP status
# that outlived the client's retries - so the answer is unknown, not wrong. Automation
# (M5.OTA.AUTO.1) tells it apart from a data or pipeline failure (1) by this code alone.
EXIT_SOURCE_UNAVAILABLE = 5
# ``builds freshness``: the exit code is the status (argparse already uses 2 for usage).
FRESHNESS_EXIT = {
    FreshnessStatus.CURRENT: EXIT_OK,
    FreshnessStatus.OUTDATED: 3,
    FreshnessStatus.UNKNOWN: 4,
}


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
        OtaPublisherError,
    ) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_SOURCE_UNAVAILABLE if source_unavailable(exc) else EXIT_FAILURE


def source_unavailable(exc: BaseException) -> bool:
    """Whether ``exc`` was caused by a transport failure of the source, not by its data.

    Sources wrap :class:`DownloadError` in their own errors and the HTTP client raises its
    "failed after N attempts" error from the last transport failure (``raise ... from``), so
    the explicit cause chain is followed down to a connection/timeout error or a retryable
    HTTP status. Anything the source *answered* - a definitive 4xx such as "Table not
    found", an HTML page instead of CSV, an empty or non-JSON body - is its data, never
    "unavailable"."""
    current: BaseException | None = exc
    seen: set[int] = set()
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        if isinstance(current, httpx.TransportError | httpx.TimeoutException):
            return True
        if isinstance(current, DownloadError) and current.status_code in RETRYABLE_STATUS:
            return True
        current = current.__cause__
    return False


# --------------------------------------------------------------------------- parser


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m talent_data",
        description="WoW talent data pipeline (M1: Forever, all nine classes)",
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
    b_fresh = builds_sub.add_parser(
        "freshness",
        help="is the product build the latest publishable build? (CURRENT/OUTDATED/UNKNOWN)",
    )
    game_arg(b_fresh)
    b_fresh.add_argument(
        "--product-build",
        required=True,
        help="the build the app creates new builds on (PRODUCT_BUNDLE), e.g. 1.60.1.70338",
    )
    b_fresh.add_argument(
        "--output", type=Path, help="also write the JSON result to this file (CI artifact)"
    )
    b_fresh.set_defaults(handler=_cmd_builds_freshness)

    fetch = sub.add_parser("fetch", help="download the DB2 tables needed for talents")
    game_arg(fetch)
    fetch.add_argument("--build", required=True, help="client version or 'latest'")
    fetch.add_argument("--force", action="store_true", help="re-download even if cached")
    fetch.set_defaults(handler=_cmd_fetch)

    norm = sub.add_parser("normalize", help="build the normalized talent JSON for classes")
    game_arg(norm)
    norm.add_argument("--build", required=True, help="client version or 'latest'")
    norm.add_argument(
        "--class",
        dest="class_key",
        required=True,
        help=f"class key (e.g. warrior), '{ALL_CLASSES}', or a comma-separated list",
    )
    norm.add_argument("--output", type=Path, help="default: data/normalized/<game>/<build>/")
    norm.add_argument(
        "--offline", action="store_true", help="use only the local DB2 cache, no network"
    )
    norm.set_defaults(handler=_cmd_normalize)

    dif = sub.add_parser("diff", help="semantic diff of classes between two builds")
    game_arg(dif)
    dif.add_argument(
        "--class", dest="class_key", required=True, help=f"class key or '{ALL_CLASSES}'"
    )
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

    man = sub.add_parser("manifest", help="validate a dataset manifest and its class documents")
    man.add_argument("path", type=Path)
    man.add_argument("--game", help="expected game key (default: trust the manifest's own claim)")
    man.add_argument(
        "--build", help="expected client build (default: trust the manifest's own claim)"
    )
    man.add_argument(
        "--expect-complete",
        action="store_true",
        help="also require the game's full class inventory (recommended for CI)",
    )
    man.set_defaults(handler=_cmd_manifest)

    allocation_cli.register(sub)
    visuals_cli.register(sub)
    tooltips_cli.register(sub)
    localization_cli.register(sub)
    ota_cli.register(sub)
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
    for issue in report.warnings:
        print(f"  warning: {issue}")
    for issue in report.errors:
        print(f"  ERROR: {issue}")


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


def _normalize_batch_to_disk(
    game: GameConfig,
    version: ClientVersion,
    class_keys: Sequence[str] | None,
    http: HttpClient | None,
    offline: bool,
) -> int:
    source = _db2_source(game, http, offline)
    batch = normalize_batch(source, game, version, class_keys=class_keys)
    for r in batch.results:
        if r.failure is not None or r.document is None or r.report is None:
            failure = r.failure or "unknown normalization failure"
            print(f"{game.display_name} {r.chr_class.name} @ {version}: FAILED")
            print(f"  error: {failure}")
            continue
        doc = r.document
        print(f"{game.display_name} {doc.class_.name} @ {version}: {len(doc.trees)} tree(s)")
        print(_summary(doc))
        _print_report(r.report)
    if not batch.ok:
        raise PipelineError(
            f"batch normalize of {version} failed; nothing was written "
            f"(failing classes above). {batch.describe_failures()}"
        )
    written = write_batch(batch, source_name=source.name)
    manifest = read_manifest(paths.manifest_path(game.key, version))
    print(f"wrote {len(written) - 1} class documents + {written[-1]}")
    print(
        f"dataset: {version}, {len(manifest.classes)} classes, 0 errors, "
        f"{sum(c.validation.warnings for c in manifest.classes)} warnings"
    )
    return EXIT_OK


# --------------------------------------------------------------------------- commands


def _cmd_builds_list(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    with _http() as http:
        builds = WagoBuildSource(http).list_builds(game)
    for b in sorted(builds, key=lambda b: (b.created_at, b.version), reverse=True):
        state = classify_build(b).background_download
        suffix = {True: "  [background-download]", False: "", None: "  [bgdl unknown]"}[state]
        print(f"{b.version}  {b.created_at:%Y-%m-%d %H:%M:%S}  {b.product}{suffix}")
    return EXIT_OK


def _cmd_builds_latest(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    with _http() as http:
        info = find_latest_build(WagoBuildSource(http), game)
    print(f"{game.display_name} latest build: {info.version}")
    state = classify_build(info).background_download
    note = {
        True: "background-download build: not publishable without human review",
        False: "not a background-download build ('latest' != proven live)",
        None: "background-download status unknown: not publishable without human review",
    }[state]
    print(
        f"  product={info.product} created_at={info.created_at:%Y-%m-%d %H:%M:%S}; {note}",
        file=sys.stderr,
    )
    return EXIT_OK


def _cmd_builds_freshness(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    try:
        product_build = ClientVersion.parse(args.product_build)
    except ValueError as exc:
        raise PipelineError(str(exc)) from exc
    check_game_version(game, product_build)
    with _http() as http:
        report = evaluate_freshness(
            WagoBuildSource(http), game, product_build, now=datetime.now(UTC)
        )
    text = report.to_json()
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    sys.stdout.write(text)
    print(f"{report.status}: {report.reason}", file=sys.stderr)
    return FRESHNESS_EXIT[report.status]


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
            # re-prove absences too: drop the recorded snapshot of this build
            snapshot_path(source.table_path(version, "x").parent).unlink(missing_ok=True)
        fetched = fetch_tables(source, version, progress=lambda t: print(f"  {t}"))
        statuses = probe_table_status(source, version)
    print(
        f"fetched {len(fetched)} DB2 tables for {version} into "
        f"{paths.raw_cache_dir('wago', game.key) / str(version)}"
    )
    for availability in statuses:
        print(f"  must be absent: {availability.table}: {availability.status.value}")
    if any(a.status is not TableStatus.ABSENT for a in statuses):
        print(
            "error: a table talent-data.v1 requires to be absent is present or could not be "
            "proven absent; normalization of this build will fail closed",
            file=sys.stderr,
        )
        return EXIT_FAILURE
    return EXIT_OK


def _cmd_normalize(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    class_keys = parse_class_keys(args.class_key)
    if class_keys is None or len(class_keys) > 1:
        if args.output is not None:
            raise PipelineError("--output is only supported for a single class")
        if args.offline:
            version = _resolve_version(game, args.build, None)
            return _normalize_batch_to_disk(game, version, class_keys, None, True)
        with _http() as http:
            version = _resolve_version(game, args.build, http)
            return _normalize_batch_to_disk(game, version, class_keys, http, False)
    if args.offline:
        version = _resolve_version(game, args.build, None)
        _normalize_to_disk(game, version, class_keys[0], None, True, args.output)
        return EXIT_OK
    with _http() as http:
        version = _resolve_version(game, args.build, http)
        _normalize_to_disk(game, version, class_keys[0], http, False, args.output)
    return EXIT_OK


def _cmd_diff(args: argparse.Namespace) -> int:
    game = get_game(args.game)
    class_keys = parse_class_keys(args.class_key)
    http = None if args.offline else _http()
    try:
        old_v = _resolve_version(game, args.from_build, http)
        new_v = _resolve_version(game, args.to_build, http)
        if class_keys is None:
            return _diff_batch(game, old_v, new_v, http, args.offline, args.json_out)
        docs = []
        for version in (old_v, new_v):
            existing = _existing_document(game, version, class_keys[0])
            if existing is None:
                print(f"normalizing {class_keys[0]} @ {version} ...", file=sys.stderr)
                existing, _ = _normalize_to_disk(game, version, class_keys[0], http, args.offline)
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


def _diff_batch(
    game: GameConfig,
    old_v: ClientVersion,
    new_v: ClientVersion,
    http: HttpClient | None,
    offline: bool,
    json_out: Path | None,
) -> int:
    old_docs: list[TalentDataDocument] = []
    new_docs: list[TalentDataDocument] = []
    for version, sink in ((old_v, old_docs), (new_v, new_docs)):
        existing = _existing_batch(game, version)
        if existing is not None:
            sink.extend(existing)
            continue
        print(f"normalizing all classes @ {version} ...", file=sys.stderr)
        source = _db2_source(game, http, offline)
        batch = normalize_batch(source, game, version, class_keys=None)
        for r in batch.results:
            if r.failure is None and r.report is not None and r.report.ok:
                continue
            details = r.failure or "; ".join(str(i) for i in (r.report.errors if r.report else []))
            raise PipelineError(
                f"cannot diff: {r.chr_class.name} @ {version} failed to normalize: {details}"
            )
        write_batch(batch, source_name=source.name)
        sink.extend(r.document for r in batch.results if r.document is not None)
    reports, aggregate = diff_batch(old_docs, new_docs)
    print(render_dataset_text(aggregate, game.display_name), end="")
    for report in reports:
        out = paths.diff_path(game.key, report.class_.token, old_v, new_v)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(report.to_json(), encoding="utf-8")
    out = json_out or (
        paths.project_root()
        / "artifacts"
        / "diff"
        / DatasetDiffReport.file_name(game.key, str(old_v), str(new_v))
    )
    write_json(aggregate.to_json(), out)
    print(f"\nJSON diffs: {len(reports)} class documents + aggregate {out}")
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


def _existing_batch(game: GameConfig, version: ClientVersion) -> list[TalentDataDocument] | None:
    """All class documents of a build, if a complete, consistent dataset is on disk.

    The manifest is never trusted blindly: it must validate against every document it
    indexes (game, build, class identity, tree ids, content hashes, live validation
    reports) and cover the game's complete class inventory. Any problem - partial,
    stale or corrupt - means "no usable dataset": the caller re-normalizes when raw
    data / network is available, or fails with a clear error otherwise.
    """
    mpath = paths.manifest_path(game.key, version)
    if not mpath.is_file():
        return None
    try:
        manifest = read_manifest(mpath)
    except PipelineError as exc:
        log.warning("%s: %s; re-normalizing", mpath, exc)
        return None
    docs: dict[str, TalentDataDocument] = {}
    for entry in manifest.classes:
        path = mpath.parent / entry.file
        try:
            docs[entry.token] = read_document(path)
        except PipelineError as exc:
            log.warning("%s: %s; re-normalizing", path, exc)
            return None
    problems = validate_manifest_against_documents(
        manifest,
        docs,
        expected_game=game.key,
        expected_build=str(version),
        expected_class_tokens=game.dataset_class_tokens,
    )
    if problems:
        for problem in problems:
            log.warning("existing %s dataset: %s", version, problem)
        log.warning("existing %s dataset is incomplete/inconsistent; re-normalizing", version)
        return None
    return [docs[entry.token] for entry in manifest.classes]


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
        "dataset-manifest.v1.schema.json": _schema_text(DatasetManifest),
        "dataset-diff.v1.schema.json": _schema_text(DatasetDiffReport),
        "allocation-build.v1.schema.json": _schema_text(BuildFile),
        "allocation-conformance.v1.schema.json": _schema_text(ConformanceSuite),
        "talent-visuals.v1.schema.json": _schema_text(TalentVisualsDocument),
        "talent-icons.v1.schema.json": _schema_text(TalentIconRegistry),
        "talent-tooltips.v1.schema.json": _schema_text(TalentTooltipsDocument),
        "talent-localization.v1.schema.json": _schema_text(TalentLocalizationDocument),
        "ota-envelope.v1.schema.json": _schema_text(SignedEnvelope),
        "ota-catalog.v1.schema.json": _schema_text(OtaCatalog),
        "ota-release.v1.schema.json": _schema_text(OtaReleaseManifest),
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


def _cmd_manifest(args: argparse.Namespace) -> int:
    manifest = read_manifest(args.path)
    print(f"{args.path}: {manifest.game} {manifest.client_build}, {len(manifest.classes)} classes")
    docs: dict[str, TalentDataDocument] = {}
    failed = False
    for entry in manifest.classes:
        doc_path = args.path.parent / entry.file
        try:
            docs[entry.token] = read_document(doc_path)
        except PipelineError as exc:
            print(f"  ERROR: [{entry.token}] {exc}")
            failed = True
    # full integrity check: identity, uniqueness, completeness, hashes, live validation
    problems = validate_manifest_against_documents(
        manifest,
        docs,
        expected_game=args.game,
        expected_build=args.build,
        expected_class_tokens=(
            get_game(manifest.game).dataset_class_tokens if args.expect_complete else None
        ),
    )
    for problem in problems:
        print(f"  ERROR: {problem}")
    if problems:
        failed = True
    if not failed:
        for entry in manifest.classes:
            codes = " ".join(f"{code}={n}" for code, n in entry.validation.warnings_by_code.items())
            print(
                f"  {entry.token}: ok, hash ok, {entry.validation.errors} error(s), "
                f"{entry.validation.warnings} warning(s) {codes}"
            )
    return EXIT_FAILURE if failed else EXIT_OK
