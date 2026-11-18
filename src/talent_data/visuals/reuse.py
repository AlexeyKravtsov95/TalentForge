"""Reuse verified PNGs only when a complete upstream CASC diff proves bytes unchanged.

This is build-time evidence, not a new public OTA schema. Changed/new IDs always use
an exact-target CASC download. A missing diff or a damaged baseline never authorizes reuse.
"""

from __future__ import annotations

import json
from dataclasses import dataclass

from talent_data import paths
from talent_data.games import get_game
from talent_data.models.visuals import IconsProvenance, sha256_of
from talent_data.pipeline import PipelineError
from talent_data.sources.http import HttpClient
from talent_data.sources.wago.file_diff import load_diff
from talent_data.versions import ClientVersion
from talent_data.visuals.icons import ConvertedIcon, converter_description


@dataclass(frozen=True)
class ReusePlan:
    icons: dict[int, ConvertedIcon]
    provenance: IconsProvenance


def plan_reuse(
    game: str, base: ClientVersion, target: ClientVersion, ids: list[int], http: HttpClient | None
) -> ReusePlan:
    from talent_data.visuals.pipeline import check_canonical, read_icon_registry

    problems = check_canonical(get_game(game), base)
    if problems:
        raise PipelineError("ICON_REUSE_BASE_INVALID: " + "; ".join(problems))
    directory = paths.visuals_dir(game, base)
    registry_raw = (directory / "icons.json").read_bytes()
    registry = read_icon_registry(directory / "icons.json")
    diff = load_diff(http, paths.raw_cache_dir("wago", game), base, target)
    icons: dict[int, ConvertedIcon] = {}
    reused = []
    for icon in registry.icons:
        if icon.file_data_id not in ids or icon.file_data_id in diff.changed:
            continue
        png = (directory / icon.file).read_bytes()
        # check_canonical checked each size, SHA-256, identity and contentHash. Recheck
        # these bytes too so a mutation between validation and reading cannot be reused.
        if len(png) != icon.byte_length or sha256_of(png) != icon.sha256:
            raise PipelineError(f"ICON_REUSE_BASE_INVALID: {icon.file} changed during read")
        icons[icon.file_data_id] = ConvertedIcon(
            file_data_id=icon.file_data_id,
            png=png,
            width=icon.width,
            height=icon.height,
            source_byte_length=icon.source_byte_length,
            source_sha256=icon.source_sha256,
        )
        reused.append(icon.model_dump(mode="json", by_alias=True))
    evidence = {
        "schemaVersion": 1,
        "kind": "talent-icon-reuse-evidence",
        "game": game,
        "fromBuild": str(base),
        "toBuild": str(target),
        "baseRegistrySha256": sha256_of(registry_raw),
        "pages": [
            {
                "url": json.loads(p.with_name(p.name + ".meta.json").read_bytes())["url"],
                "sha256": sha256_of(p.read_bytes()),
            }
            for p in diff.pages
        ],
        "changedFileDataIds": sorted(diff.changed),
        "reused": reused,
        "fetchRequired": sorted(set(ids) - icons.keys()),
    }
    raw = (json.dumps(evidence, ensure_ascii=False, indent=2) + "\n").encode()
    out = paths.icon_coverage_path(game, target).with_name(f"{game}-{target}-icon-reuse.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(raw)
    return ReusePlan(
        icons,
        IconsProvenance(
            source="wago.tools",
            retrieval=(
                f"unchanged CASC files reused from {base}; evidence "
                f"{out.name} {sha256_of(raw)}; "
                "other files retrieved by FileDataID pinned to clientBuild"
            ),
            converter=(
                f"reused PNG bytes: {registry.provenance.converter}; "
                f"new files: {converter_description()}"
            ),
        ),
    )
