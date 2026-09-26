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
