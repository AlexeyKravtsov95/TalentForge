"""Use-case layer wiring sources, normalizer, validation, storage and diff together.

The CLI is a thin shell over these functions; tests can call them with fake sources.
"""

from __future__ import annotations

import json
import logging
from collections import Counter
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from talent_data.db2 import tables as t
from talent_data.db2.dataset import TraitDataSet
from talent_data.db2.tables import TRAIT_TABLES
from talent_data.diff.engine import diff_documents
from talent_data.diff.models import DatasetClassDiff, DatasetDiffReport, DiffReport, DiffSummary
from talent_data.games import GameConfig
from talent_data.models.manifest import (
    DatasetManifest,
    ManifestClassEntry,
    ManifestValidation,
)
from talent_data.models.talents import TalentDataDocument
from talent_data.normalization.class_resolution import (
    ClassResolutionError,
    resolve_class,
)
from talent_data.normalization.normalizer import normalize_class
from talent_data.paths import manifest_path, normalized_path
from talent_data.sources.base import BuildInfo, BuildSource, Db2Source, SourceError, latest_build
from talent_data.validation.validators import ValidationReport, validate_document
from talent_data.versions import ClientVersion

log = logging.getLogger(__name__)

ALL_CLASSES = "all"


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
    return normalize_from_dataset(
        dataset, game=game.key, class_key=class_key, source_name=source.name
    )


def normalize_from_dataset(
    dataset: TraitDataSet, *, game: str, class_key: str, source_name: str
) -> NormalizeResult:
    document = normalize_class(dataset, game=game, class_key=class_key, source_name=source_name)
    return NormalizeResult(document=document, report=validate_document(document))


# --------------------------------------------------------------------------- batch normalize


def discover_classes(dataset: TraitDataSet) -> list[t.ChrClass]:
    """All classes of the build, in deterministic (id) order. The set itself is data:
    Forever's ChrClasses contains exactly the nine classic classes."""
    return sorted(dataset.classes.values(), key=lambda c: c.id)


def parse_class_keys(keys: str) -> list[str] | None:
    """``all`` -> None (discover from data); otherwise a comma-separated class key list."""
    values = [k.strip().lower() for k in keys.split(",") if k.strip()]
    if not values:
        raise PipelineError("no class given")
    if ALL_CLASSES in values:
        if len(values) > 1:
            raise PipelineError("'all' cannot be combined with individual classes")
        return None
    return values


@dataclass(frozen=True)
class BatchClassResult:
    chr_class: t.ChrClass
    document: TalentDataDocument | None = None
    report: ValidationReport | None = None
    failure: str | None = None

    @property
    def ok(self) -> bool:
        return self.failure is None and self.report is not None and self.report.ok


@dataclass(frozen=True)
class BatchResult:
    game: GameConfig
    version: ClientVersion
    results: list[BatchClassResult]  # ordered by class id, one entry per requested class

    @property
    def ok(self) -> bool:
        return all(r.ok for r in self.results)

    def describe_failures(self) -> str:
        lines = []
        for r in self.results:
            if r.failure is not None:
                lines.append(f"{r.chr_class.name}: {r.failure}")
            elif r.report is not None and not r.report.ok:
                details = "; ".join(str(i) for i in r.report.errors)
                lines.append(f"{r.chr_class.name}: validation failed: {details}")
        return "\n".join(lines)


def normalize_batch(
    source: Db2Source,
    game: GameConfig,
    version: ClientVersion,
    class_keys: Sequence[str] | None = None,
) -> BatchResult:
    """Normalize + validate every requested class of one build.

    Deterministic (classes ordered by ChrClasses.ID). A failure in one class never stops
    the others and never hides: it is recorded verbatim on that class's result. The caller
    decides what to publish - :func:`write_batch` refuses to write anything unless every
    class is error-free.
    """
    check_game_version(game, version)
    dataset = TraitDataSet.load(source, version)
    if class_keys is None:
        selected = discover_classes(dataset)
    else:
        try:
            selected = [resolve_class(dataset, key) for key in class_keys]
        except ClassResolutionError as exc:
            available = ", ".join(c.token.lower() for c in discover_classes(dataset))
            raise PipelineError(f"{exc}; available classes: {available}") from exc
        # canonical id order even for hand-written lists, so the manifest order is stable
        selected = sorted(set(selected), key=lambda c: c.id)

    results = []
    for chr_class in selected:
        try:
            result = normalize_from_dataset(
                dataset, game=game.key, class_key=chr_class.token, source_name=source.name
            )
        except (PipelineError, SourceError, ValueError) as exc:
            # ClassResolutionError and NormalizationError are ValueError subclasses; any of
            # these means "this class's data is broken/unknown", not "the pipeline crashed":
            # record it on the class and keep going so one class cannot hide another.
            results.append(BatchClassResult(chr_class=chr_class, failure=str(exc)))
            continue
        results.append(
            BatchClassResult(
                chr_class=chr_class,
                document=result.document,
                report=result.report,
            )
        )
    return BatchResult(game=game, version=version, results=results)


def build_manifest(batch: BatchResult, *, source_name: str) -> DatasetManifest:
    if not batch.ok:
        raise PipelineError("cannot build a manifest for a batch with failing classes")
    entries = [
        ManifestClassEntry(
            token=r.chr_class.token,
            file=normalized_path(batch.game.key, batch.version, r.chr_class.token).name,
            tree_ids=[tree.id for tree in r.document.trees],
            content_hash=r.document.content_hash,
            validation=ManifestValidation(
                errors=len(r.report.errors),
                warnings=len(r.report.warnings),
                warnings_by_code=dict(sorted(Counter(i.code for i in r.report.warnings).items())),
            ),
        )
        for r in batch.results
        if r.document is not None and r.report is not None
    ]
    return DatasetManifest(
        game=batch.game.key,
        client_build=str(batch.version),
        generated_at=datetime.now(UTC).replace(microsecond=0),
        source=source_name,
        classes=entries,
    )


def write_batch(batch: BatchResult, *, source_name: str) -> list[Path]:
    """Publish a batch: all class documents plus the dataset manifest, or nothing.

    Writing happens only after every class passed validation, so a failed batch never
    leaves a partially-valid dataset behind.
    """
    if not batch.ok:
        raise PipelineError(
            f"batch for {batch.version} is not publishable "
            f"({sum(1 for r in batch.results if not r.ok)} failing class(es)):\n"
            f"{batch.describe_failures()}"
        )
    written = []
    for r in batch.results:
        if r.document is None:
            raise PipelineError(f"{r.chr_class.name} passed validation without a document")
        path = normalized_path(batch.game.key, batch.version, r.chr_class.token)
        write_document(r.document, path)
        written.append(path)
    manifest = build_manifest(batch, source_name=source_name)
    mpath = manifest_path(batch.game.key, batch.version)
    write_json(manifest.to_json(), mpath)
    written.append(mpath)
    return written


# --------------------------------------------------------------------------- storage


def write_document(document: TalentDataDocument, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(document.to_json(), encoding="utf-8")
    tmp.replace(path)


def write_json(text: str, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(text, encoding="utf-8")
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


def read_manifest(path: Path) -> DatasetManifest:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise PipelineError(f"cannot read dataset manifest {path}: {exc}") from exc
    version = raw.get("schemaVersion")
    if version != 1:
        raise PipelineError(f"{path}: unsupported manifest schemaVersion {version!r}")
    return DatasetManifest.model_validate(raw)


# --------------------------------------------------------------------------- diff


def diff(old: TalentDataDocument, new: TalentDataDocument) -> DiffReport:
    return diff_documents(old, new)


def diff_batch(
    old_docs: Sequence[TalentDataDocument], new_docs: Sequence[TalentDataDocument]
) -> tuple[list[DiffReport], DatasetDiffReport]:
    """All-class diff: one talent-diff v1 report per class plus the aggregate v1 index."""
    old_by_token = {d.class_.token: d for d in old_docs}
    new_by_token = {d.class_.token: d for d in new_docs}
    if len(old_by_token) != len(old_docs) or len(new_by_token) != len(new_docs):
        raise PipelineError("duplicate class documents given to diff_batch")
    if old_by_token.keys() != new_by_token.keys():
        raise PipelineError(
            f"class sets differ between builds: {sorted(old_by_token)} vs {sorted(new_by_token)}"
        )
    if not old_docs:
        raise PipelineError("no class documents given to diff_batch")

    ordered = sorted(old_docs, key=lambda d: d.class_.id)
    reports = [
        diff_documents(old_by_token[d.class_.token], new_by_token[d.class_.token]) for d in ordered
    ]
    classes = [
        DatasetClassDiff(
            class_=report.class_,
            file=_class_diff_file_name(report),
            summary=report.summary,
        )
        for report in reports
    ]
    total_kinds: Counter[str] = Counter()
    for report in reports:
        total_kinds.update(report.summary.changes_by_kind)
    total = DiffSummary(
        added=sum(r.summary.added for r in reports),
        removed=sum(r.summary.removed for r in reports),
        changed=sum(r.summary.changed for r in reports),
        changes_by_kind=dict(sorted(total_kinds.items())),
    )
    aggregate = DatasetDiffReport(
        game=reports[0].game,
        from_build=reports[0].from_build,
        to_build=reports[0].to_build,
        classes=classes,
        total=total,
    )
    return reports, aggregate


def _class_diff_file_name(report: DiffReport) -> str:
    from talent_data.paths import diff_path

    old = ClientVersion.parse(report.from_build)
    new = ClientVersion.parse(report.to_build)
    return diff_path(report.game, report.class_.token, old, new).name


__all__ = [
    "ALL_CLASSES",
    "BatchClassResult",
    "BatchResult",
    "NormalizeResult",
    "PipelineError",
    "SourceError",
    "build_manifest",
    "check_game_version",
    "diff",
    "diff_batch",
    "discover_classes",
    "fetch_tables",
    "find_latest_build",
    "normalize",
    "normalize_batch",
    "normalize_from_dataset",
    "parse_class_keys",
    "read_document",
    "read_manifest",
    "write_batch",
    "write_document",
    "write_json",
]
