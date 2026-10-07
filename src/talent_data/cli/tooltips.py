"""Client tooltip capture commands (``python -m talent_data tooltips ...``).

tooltips manifest       --build <version> [--check]     generate / verify the addon manifest

Everything here is offline. The capture itself is a manual step in the exact game client
(see tools/wow-tooltip-capture/README.md); it is never a CI dependency.
"""

from __future__ import annotations

import argparse

from talent_data import paths
from talent_data.games import GAMES, GameConfig, get_game
from talent_data.pipeline import PipelineError, check_game_version, write_json
from talent_data.tooltips.manifest import (
    CaptureManifest,
    addon_manifest_problems,
    load_capture_manifest,
    render_lua,
)
from talent_data.versions import ClientVersion

EXIT_OK = 0
EXIT_FAILURE = 1


def register(sub: argparse._SubParsersAction[argparse.ArgumentParser]) -> None:
    tooltips = sub.add_parser("tooltips", help="client-rendered talent tooltips: capture manifest")
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
