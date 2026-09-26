"""Use-case layer wiring sources, normalizer, validation, storage and diff together.

The CLI is a thin shell over these functions; tests can call them with fake sources.
"""

from __future__ import annotations

import json
import logging
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path

from talent_data.db2.dataset import TraitDataSet
from talent_data.db2.tables import TRAIT_TABLES
from talent_data.diff.engine import diff_documents
from talent_data.diff.models import DiffReport
from talent_data.games import GameConfig
from talent_data.models.talents import TalentDataDocument
from talent_data.normalization.normalizer import normalize_class
from talent_data.sources.base import BuildInfo, BuildSource, Db2Source, SourceError, latest_build
from talent_data.validation.validators import ValidationReport, validate_document
from talent_data.versions import ClientVersion

log = logging.getLogger(__name__)


class PipelineError(RuntimeError):
    pass


def find_latest_build(source: BuildSource, game: GameConfig) -> BuildInfo:
    builds = source.list_builds(game)
    if not builds:
        raise PipelineError(
            f"{source.name} lists no {game.display_name} builds "
            f"(products {list(game.products)}, branch {game.version_branch}.x)"
        )
    return latest_build(builds)


def check_game_version(game: GameConfig, version: ClientVersion) -> None:
    if version.branch != game.version_branch:
        raise PipelineError(
            f"{version} is not a {game.display_name} build "
            f"(expected branch {game.version_branch}.x)"
        )


def fetch_tables(
    source: Db2Source,
    version: ClientVersion,
    tables: Iterable[str] = TRAIT_TABLES,
    progress: Callable[[str], None] | None = None,
) -> list[str]:
    fetched = []
    for table in tables:
        source.ensure_table(version, table)
        fetched.append(table)
        if progress:
            progress(table)
    return fetched


@dataclass(frozen=True)
class NormalizeResult:
    document: TalentDataDocument
    report: ValidationReport


def normalize(
    source: Db2Source, game: GameConfig, version: ClientVersion, class_key: str
) -> NormalizeResult:
    check_game_version(game, version)
    dataset = TraitDataSet.load(source, version)
    document = normalize_class(dataset, game=game.key, class_key=class_key, source_name=source.name)
    return NormalizeResult(document=document, report=validate_document(document))


def write_document(document: TalentDataDocument, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(document.to_json(), encoding="utf-8")
    tmp.replace(path)


def read_document(path: Path) -> TalentDataDocument:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise PipelineError(f"cannot read normalized document {path}: {exc}") from exc
    version = raw.get("schemaVersion")
    if version != 1:
        raise PipelineError(f"{path}: unsupported schemaVersion {version!r}")
    return TalentDataDocument.model_validate(raw)


def diff(old: TalentDataDocument, new: TalentDataDocument) -> DiffReport:
    return diff_documents(old, new)


__all__ = [
    "NormalizeResult",
    "PipelineError",
    "SourceError",
    "check_game_version",
    "diff",
    "fetch_tables",
    "find_latest_build",
    "normalize",
    "read_document",
    "write_document",
]
