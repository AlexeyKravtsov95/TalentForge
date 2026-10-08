"""Tooltip use cases: render, publish and verify the talent descriptions of one build.

* ``fetch_tooltip_tables`` (and loading through an online source) may use the network;
* ``render_build`` + ``publish`` read the local DB2 cache and the committed dataset;
* ``check_canonical`` reads nothing but committed repository files - no cache, no network.

Publishing is all-or-nothing: every sidecar, the coverage report and its Markdown are
produced in memory and validated before anything is written.
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

from pydantic import ValidationError

from talent_data import paths
from talent_data.db2.tables import TOOLTIP_TABLES
from talent_data.games import GameConfig
from talent_data.models.talents import TalentDataDocument, TalentEntry
from talent_data.models.tooltips import (
    TOOLTIPS_SCHEMA_VERSION,
    TalentTooltipsDocument,
    TooltipEntry,
    TooltipsProvenance,
    expected_content_hash,
)
from talent_data.models.visuals import content_hash, hashed_payload
from talent_data.pipeline import PipelineError
from talent_data.sources.base import Db2Source
from talent_data.tooltips.coverage import (
    ClassInput,
    TooltipCoverageReport,
    build_report,
    render_markdown,
)
from talent_data.tooltips.render import RENDERER, TalentRenderer, TokenOutcome
from talent_data.tooltips.spells import SpellLookup
from talent_data.versions import ClientVersion
from talent_data.visuals.pipeline import load_canonical_dataset


def fetch_tooltip_tables(source: Db2Source, version: ClientVersion) -> list[str]:
    for table in TOOLTIP_TABLES:
        source.ensure_table(version, table)
    return list(TOOLTIP_TABLES)


@dataclass(frozen=True)
class BuildTooltips:
    game: GameConfig
    version: ClientVersion
    documents: tuple[TalentDataDocument, ...]
    tooltips: Mapping[str, TalentTooltipsDocument]  # class token -> sidecar
    outcomes: tuple[TokenOutcome, ...]

    @property
    def report(self) -> TooltipCoverageReport:
        return build_report(
            game=self.game.key,
            client_build=str(self.version),
            classes=[
                ClassInput(talents=doc, tooltips=self.tooltips[doc.class_.token])
                for doc in self.documents
            ],
            outcomes=self.outcomes,
        )


def _entries(document: TalentDataDocument) -> list[TalentEntry]:
    return sorted(
        (e for tree in document.trees for node in tree.nodes for e in node.entries),
        key=lambda e: e.id,
    )


def render_document(
    document: TalentDataDocument, spells: SpellLookup, *, source_name: str
) -> tuple[TalentTooltipsDocument, list[TokenOutcome]]:
    """The sidecar of one class document, plus every token outcome of every rank."""
    entries: list[TooltipEntry] = []
    outcomes: list[TokenOutcome] = []
    for entry in _entries(document):
        expected = spells.descriptions.get(entry.spell_id)
        if expected is None or (entry.description_template or "") != expected:
            raise PipelineError(
                f"{document.class_.token} entry {entry.id}: the dataset's description template "
                f"is not Spell {entry.spell_id}.Description_lang of {spells.version}; the "
                "dataset and the DB2 tables are not the same build"
            )
        renderer = TalentRenderer(entry, spells)
        ranks = []
        for rank in range(1, entry.max_rank + 1):
            try:
                rendered = renderer.render(rank)
            except ValueError as exc:
                raise PipelineError(str(exc)) from exc
            ranks.append(rendered.rank)
            outcomes.extend(rendered.outcomes)
        entries.append(
            TooltipEntry(
                entry_id=entry.id,
                definition_id=entry.definition_id,
                spell_id=entry.spell_id,
                max_rank=entry.max_rank,
                ranks=ranks,
            )
        )
    payload = {
        "schemaVersion": TOOLTIPS_SCHEMA_VERSION,
        "game": document.game,
        "clientBuild": document.client_build,
        "classToken": document.class_.token,
        "talentDataContentHash": document.content_hash,
        "entries": [e.model_dump(mode="json", by_alias=True) for e in entries],
    }
    sidecar = TalentTooltipsDocument(
        schema_version=TOOLTIPS_SCHEMA_VERSION,
        game=document.game,
        client_build=document.client_build,
        class_token=document.class_.token,
        talent_data_content_hash=document.content_hash,
        entries=entries,
        content_hash=content_hash(hashed_payload(payload)),
        provenance=TooltipsProvenance(
            source=source_name, tables=list(TOOLTIP_TABLES), renderer=RENDERER
        ),
    )
    if sidecar.content_hash != expected_content_hash(sidecar):
        raise PipelineError(f"{document.class_.token}: tooltip content hash is not reproducible")
    return sidecar, outcomes


def render_build(
    source: Db2Source, game: GameConfig, version: ClientVersion, *, source_name: str
) -> BuildTooltips:
    documents = load_canonical_dataset(game, version)
    spells = SpellLookup.load(source, version)
    tooltips: dict[str, TalentTooltipsDocument] = {}
    outcomes: list[TokenOutcome] = []
    for document in documents:
        sidecar, document_outcomes = render_document(document, spells, source_name=source_name)
        tooltips[document.class_.token] = sidecar
        outcomes += document_outcomes
    return BuildTooltips(
        game=game,
        version=version,
        documents=tuple(documents),
        tooltips=tooltips,
        outcomes=tuple(outcomes),
    )


@dataclass(frozen=True)
class PublishedTooltips:
    directory: Path
    report: TooltipCoverageReport


def _write(target: Path, text: str) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(target.name + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(target)


def _relative_files(directory: Path) -> set[str]:
    if not directory.is_dir():
        return set()
    return {
        p.relative_to(directory).as_posix()
        for p in directory.rglob("*")
        if p.is_file() and not p.name.startswith(".")
    }


def publish(build: BuildTooltips) -> PublishedTooltips:
    """Write ``data/tooltips/<game>/<build>/<class>.json`` (exactly the nine sidecars), the
    coverage report JSON and its Markdown summary."""
    game, version = build.game.key, build.version
    report = build.report
    directory = paths.tooltips_dir(game, version)
    files = {
        paths.tooltips_path(game, version, doc.class_.token).name: build.tooltips[
            doc.class_.token
        ].to_json()
        for doc in build.documents
    }
    for name, text in sorted(files.items()):
        _write(directory / name, text)
    for stale in _relative_files(directory) - files.keys():
        (directory / stale).unlink()
    _write(paths.tooltip_coverage_path(game, version), report.to_json())
    _write(paths.tooltip_coverage_markdown_path(game, version), render_markdown(report))
    return PublishedTooltips(directory=directory, report=report)


# --------------------------------------------------------------------------- offline check


def read_tooltips_document(path: Path) -> TalentTooltipsDocument:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise PipelineError(f"cannot read talent tooltips {path}: {exc}") from exc
    if not isinstance(raw, dict) or raw.get("schemaVersion") != TOOLTIPS_SCHEMA_VERSION:
        version = raw.get("schemaVersion") if isinstance(raw, dict) else None
        raise PipelineError(f"{path}: unsupported talent-tooltips schemaVersion {version!r}")
    try:
        return TalentTooltipsDocument.model_validate(raw)
    except ValidationError as exc:
        raise PipelineError(f"{path}: not a talent-tooltips.v1 document: {exc}") from exc


def read_coverage_report(path: Path) -> TooltipCoverageReport:
    try:
        return TooltipCoverageReport.model_validate_json(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise PipelineError(f"cannot read tooltip coverage report {path}: {exc}") from exc


def check_tooltips_document(
    tooltips: TalentTooltipsDocument, talents: TalentDataDocument
) -> list[str]:
    """Problems that make ``tooltips`` unusable next to ``talents`` (empty = consistent)."""
    problems: list[str] = []
    add = problems.append
    where = tooltips.class_token
    if tooltips.game != talents.game:
        add(f"{where}: tooltips game {tooltips.game!r} != dataset {talents.game!r}")
    if tooltips.client_build != talents.client_build:
        add(
            f"{where}: tooltips clientBuild {tooltips.client_build!r} "
            f"!= dataset {talents.client_build!r}"
        )
    if tooltips.class_token != talents.class_.token:
        add(f"{where}: tooltips are for {tooltips.class_token}, dataset is {talents.class_.token}")
    if tooltips.talent_data_content_hash != talents.content_hash:
        add(
            f"{where}: tooltips were rendered for talent-data "
            f"{tooltips.talent_data_content_hash}, dataset is {talents.content_hash}"
        )
    if tooltips.content_hash != expected_content_hash(tooltips):
        add(f"{where}: contentHash does not match the document (stale or tampered)")
    entry_ids = [e.entry_id for e in tooltips.entries]
    if entry_ids != sorted(entry_ids):
        add(f"{where}: entries are not ordered by entryId")
    if len(entry_ids) != len(set(entry_ids)):
        add(f"{where}: duplicate entryId in entries")
    known = {e.id: e for e in _entries(talents)}
    unknown = sorted(set(entry_ids) - known.keys())
    missing = sorted(known.keys() - set(entry_ids))
    if unknown:
        add(f"{where}: tooltips reference unknown talent entries {unknown}")
    if missing:
        add(f"{where}: talent entries without tooltips {missing}")
    for entry in tooltips.entries:
        talent = known.get(entry.entry_id)
        if talent is None:
            continue
        if (entry.definition_id, entry.spell_id, entry.max_rank) != (
            talent.definition_id,
            talent.spell_id,
            talent.max_rank,
        ):
            add(
                f"{where}: entry {entry.entry_id} is definition {entry.definition_id} / spell "
                f"{entry.spell_id} / maxRank {entry.max_rank}, dataset says "
                f"{talent.definition_id} / {talent.spell_id} / {talent.max_rank}"
            )
    return problems


def check_canonical(game: GameConfig, version: ClientVersion) -> list[str]:
    """Verify the committed tooltips of ``version`` against the committed dataset, and the
    committed coverage report and its Markdown against the sidecars. Pure repository check.
    """
    documents = load_canonical_dataset(game, version)
    directory = paths.tooltips_dir(game.key, version)
    problems: list[str] = []
    expected_files: set[str] = set()
    classes: list[ClassInput] = []
    for talents in documents:
        path = paths.tooltips_path(game.key, version, talents.class_.token)
        expected_files.add(path.name)
        try:
            tooltips = read_tooltips_document(path)
        except PipelineError as exc:
            problems.append(str(exc))
            continue
        problems.extend(check_tooltips_document(tooltips, talents))
        classes.append(ClassInput(talents=talents, tooltips=tooltips))
    stray = _relative_files(directory) - expected_files
    problems.extend(f"{name} is not part of the tooltips of {version}" for name in sorted(stray))
    if problems:
        return problems

    report_path = paths.tooltip_coverage_path(game.key, version)
    try:
        report = read_coverage_report(report_path)
    except PipelineError as exc:
        return [str(exc)]
    # Everything except the token-category counts can be recomputed from the sidecars;
    # the token counts need the DB2 tables and are covered by the regeneration test.
    recomputed = build_report(
        game=game.key, client_build=str(version), classes=classes, outcomes=()
    )
    if recomputed.model_copy(update={"categories": report.categories}) != report:
        problems.append(f"{report_path}: totals/classes/gaps do not match the committed sidecars")
    markdown_path = paths.tooltip_coverage_markdown_path(game.key, version)
    if not markdown_path.is_file() or markdown_path.read_text(encoding="utf-8") != render_markdown(
        report
    ):
        problems.append(f"{markdown_path}: does not match the coverage report")
    return problems
