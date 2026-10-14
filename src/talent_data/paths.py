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


def tooltips_dir(game: str, version: ClientVersion) -> Path:
    """Canonical talent tooltips of one build: data/tooltips/<game>/<build>/"""
    return project_root() / "data" / "tooltips" / game / str(version)


def tooltips_path(game: str, version: ClientVersion, class_token: str) -> Path:
    """talent-tooltips.v1 sidecar of one class: data/tooltips/<game>/<build>/<class>.json"""
    return tooltips_dir(game, version) / f"{class_token.lower()}.json"


def tooltip_coverage_path(game: str, version: ClientVersion) -> Path:
    """Tooltip coverage report: artifacts/tooltips/<game>-<build>-tooltip-coverage.json"""
    return project_root() / "artifacts" / "tooltips" / f"{game}-{version}-tooltip-coverage.json"


def tooltip_coverage_markdown_path(game: str, version: ClientVersion) -> Path:
    """Human tooltip coverage summary: docs/research/<game>-<build>-tooltip-coverage.md"""
    return project_root() / "docs" / "research" / f"{game}-{version}-tooltip-coverage.md"


def localization_dir(game: str, version: ClientVersion, locale: str) -> Path:
    """Localized text overlays of one build: data/localization/<game>/<build>/<locale>/"""
    return project_root() / "data" / "localization" / game / str(version) / locale


def localization_path(game: str, version: ClientVersion, locale: str, class_token: str) -> Path:
    """talent-localization.v1 overlay of one class: .../<build>/<locale>/<class>.json"""
    return localization_dir(game, version, locale) / f"{class_token.lower()}.json"


def localization_coverage_path(game: str, version: ClientVersion, locale: str) -> Path:
    """Coverage report: artifacts/localization/<game>-<build>-<locale>-coverage.json"""
    return (
        project_root() / "artifacts" / "localization" / f"{game}-{version}-{locale}-coverage.json"
    )


def localization_coverage_markdown_path(game: str, version: ClientVersion, locale: str) -> Path:
    """Human coverage summary: docs/research/<game>-<build>-<locale>-coverage.md"""
    return project_root() / "docs" / "research" / f"{game}-{version}-{locale}-coverage.md"
