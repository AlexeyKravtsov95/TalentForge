"""Filesystem layout (all relative to the project root / current working directory)."""

from __future__ import annotations

import os
from pathlib import Path

from talent_data.versions import ClientVersion


def project_root() -> Path:
    return Path(os.environ.get("TALENT_DATA_ROOT", Path.cwd()))


def raw_cache_dir(source: str, game: str) -> Path:
    """Raw DB2 downloads (git-ignored): .cache/<source>/<game>/<build>/<Table>.csv"""
    return project_root() / ".cache" / source / game


def normalized_path(game: str, version: ClientVersion, class_token: str) -> Path:
    return (
        project_root() / "data" / "normalized" / game / str(version) / f"{class_token.lower()}.json"
    )


def manifest_path(game: str, version: ClientVersion) -> Path:
    """Dataset manifest of one build: data/normalized/<game>/<build>/manifest.json"""
    return project_root() / "data" / "normalized" / game / str(version) / "manifest.json"


def diff_path(game: str, class_token: str, old: ClientVersion, new: ClientVersion) -> Path:
    return project_root() / "artifacts" / "diff" / f"{game}-{class_token.lower()}-{old}-{new}.json"


def conformance_path(game: str, version: ClientVersion) -> Path:
    """Allocation conformance vectors: data/conformance/<game>/<build>/allocation.v1.json"""
    return project_root() / "data" / "conformance" / game / str(version) / "allocation.v1.json"


def visuals_dir(game: str, version: ClientVersion) -> Path:
    """Canonical talent visuals of one build: data/visuals/<game>/<build>/"""
    return project_root() / "data" / "visuals" / game / str(version)


def visuals_path(game: str, version: ClientVersion, class_token: str) -> Path:
    """talent-visuals.v1 sidecar of one class: data/visuals/<game>/<build>/<class>.json"""
    return visuals_dir(game, version) / f"{class_token.lower()}.json"


def icon_registry_path(game: str, version: ClientVersion) -> Path:
    """talent-icons.v1 registry of one build: data/visuals/<game>/<build>/icons.json"""
    return visuals_dir(game, version) / "icons.json"


def icon_coverage_path(game: str, version: ClientVersion) -> Path:
    """Icon coverage report: artifacts/visuals/<game>-<build>-icon-coverage.json"""
    return project_root() / "artifacts" / "visuals" / f"{game}-{version}-icon-coverage.json"


def casc_cache_dir(source: str, game: str) -> Path:
    """Raw CASC file downloads (git-ignored): .cache/<source>/<game>/<build>/casc/<id>.bin"""
    return raw_cache_dir(source, game)


def tooltip_capture_addon_dir() -> Path:
    """The WoW addon that captures client-rendered tooltips (a development utility)."""
    return project_root() / "tools" / "wow-tooltip-capture" / "TalentForgeTooltipCapture"


def tooltip_capture_manifest_path() -> Path:
    """Generated Lua capture manifest - the addon's only talent input."""
    return tooltip_capture_addon_dir() / "GeneratedTalentManifest.lua"


def tooltip_capture_dir(game: str, version: ClientVersion, locale: str) -> Path:
    """Raw client tooltip evidence: artifacts/tooltips/<game>/<build>/<locale>/"""
    return project_root() / "artifacts" / "tooltips" / game / str(version) / locale


def tooltip_capture_path(game: str, version: ClientVersion, locale: str) -> Path:
    """Imported raw client tooltip capture (audit artifact, not a mobile contract)."""
    return tooltip_capture_dir(game, version, locale) / "client-capture.json"


def tooltip_capture_report_path(game: str, version: ClientVersion, locale: str) -> Path:
    """Structural analysis of the imported capture (input to M5.V2B)."""
    return tooltip_capture_dir(game, version, locale) / "client-capture-report.md"
