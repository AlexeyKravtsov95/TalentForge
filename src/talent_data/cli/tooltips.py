"""Client tooltip capture commands (``python -m talent_data tooltips ...``).

tooltips manifest       --build <version> [--check]     generate / verify the addon manifest
tooltips import-client  --build <version> --saved-variables <file>
                                                        validate a capture, write the artifact
tooltips report         --build <version>               analyse the imported capture

Everything here is offline. The capture itself is a manual step in the exact game client
(see tools/wow-tooltip-capture/README.md); it is never a CI dependency.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from talent_data import paths
from talent_data.games import GAMES, GameConfig, get_game
from talent_data.pipeline import PipelineError, check_game_version, write_json
from talent_data.tooltips.capture import import_client_capture, read_client_capture
from talent_data.tooltips.manifest import (
    CaptureManifest,
    addon_manifest_problems,
    load_capture_manifest,
    render_lua,
)
from talent_data.tooltips.report import render_report
from talent_data.versions import ClientVersion
from talent_data.visuals.pipeline import load_canonical_dataset

EXIT_OK = 0
EXIT_FAILURE = 1


def register(sub: argparse._SubParsersAction[argparse.ArgumentParser]) -> None:
    tooltips = sub.add_parser(
        "tooltips", help="client-rendered talent tooltips: capture manifest, import, report"
    )
    tooltips_sub = tooltips.add_subparsers(dest="tooltips_command", required=True)

    def common(p: argparse.ArgumentParser) -> None:
        p.add_argument("--game", default="forever", choices=sorted(GAMES))
        p.add_argument("--build", required=True, help="exact client version (never 'latest')")

    manifest = tooltips_sub.add_parser(
        "manifest", help="write the capture addon's GeneratedTalentManifest.lua"
    )
    common(manifest)
    manifest.add_argument(
        "--check", action="store_true", help="verify the committed file instead of writing it"
    )
    manifest.set_defaults(handler=_cmd_manifest)

    imp = tooltips_sub.add_parser(
        "import-client", help="validate a SavedVariables capture and write the audit artifact"
    )
    common(imp)
    imp.add_argument(
        "--saved-variables",
        type=Path,
        required=True,
        help="WTF/Account/<account>/SavedVariables/TalentForgeTooltipCapture.lua",
    )
    imp.add_argument("--output", type=Path, help="default: artifacts/tooltips/<game>/<build>/")
    imp.set_defaults(handler=_cmd_import)

    report = tooltips_sub.add_parser("report", help="structural report of the imported capture")
    common(report)
    report.add_argument("--capture", type=Path, help="default: the imported audit artifact")
    report.add_argument("--output", type=Path, help="default: next to the capture")
    report.set_defaults(handler=_cmd_report)


def _resolve(args: argparse.Namespace) -> tuple[GameConfig, ClientVersion, CaptureManifest]:
    game = get_game(args.game)
    try:
        version = ClientVersion.parse(args.build)
    except ValueError as exc:
        raise PipelineError(f"tooltip captures are pinned to an exact build: {exc}") from exc
    check_game_version(game, version)
    return game, version, load_capture_manifest(game, version)


def _cmd_manifest(args: argparse.Namespace) -> int:
    _, _, manifest = _resolve(args)
    path = paths.tooltip_capture_manifest_path()
    summary = (
        f"{manifest.game} {manifest.client_build} {manifest.locale}: "
        f"{len(manifest.entries)} entries, {manifest.rank_count} entry/rank tooltips, "
        f"manifest {manifest.manifest_hash}"
    )
    if args.check:
        problems = addon_manifest_problems(manifest, path)
        for problem in problems:
            print(f"  ERROR: {problem}")
        if problems:
            print("run `python -m talent_data tooltips manifest --build <version>` to regenerate")
            return EXIT_FAILURE
        print(f"capture manifest in sync: {summary}")
        return EXIT_OK
    write_json(render_lua(manifest), path)
    print(f"wrote {path}")
    print(f"  {summary}")
    return EXIT_OK


def _cmd_import(args: argparse.Namespace) -> int:
    game, version, manifest = _resolve(args)
    source: Path = args.saved_variables
    try:
        text = source.read_bytes().decode("utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        raise PipelineError(f"cannot read SavedVariables file {source}: {exc}") from exc
    capture = import_client_capture(text, manifest)
    out = args.output or paths.tooltip_capture_path(game.key, version, capture.locale)
    write_json(capture.to_json(), out)
    print(
        f"imported {capture.record_count} tooltips of {capture.entry_count} talent entries "
        f"({capture.game} {capture.client_build} {capture.locale})"
    )
    print(f"  semantic hash {capture.semantic_hash}")
    print(f"wrote {out}")
    return EXIT_OK


def _cmd_report(args: argparse.Namespace) -> int:
    game, version, manifest = _resolve(args)
    source = args.capture or paths.tooltip_capture_path(game.key, version, manifest.locale)
    if not source.is_file():
        raise PipelineError(
            f"no imported client capture at {source}; run `tooltips import-client` first"
        )
    capture = read_client_capture(source, manifest)
    templates = {
        entry.id: entry.description_template
        for doc in load_canonical_dataset(game, version)
        for tree in doc.trees
        for node in tree.nodes
        for entry in node.entries
    }
    out = args.output or paths.tooltip_capture_report_path(game.key, version, capture.locale)
    write_json(render_report(capture, manifest, templates), out)
    print(f"wrote {out}")
    return EXIT_OK
