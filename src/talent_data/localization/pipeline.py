"""Localization use cases: generate, publish and verify the text overlays of one build.

* ``localize_build`` reads the local DB2 cache (or any table reader) and the committed
  dataset and English tooltips; it writes nothing;
* ``publish`` writes the overlays of the requested classes, the coverage report (of every
  class) and its Markdown - all produced and validated in memory first;
* ``check_canonical`` reads nothing but committed repository files - no cache, no network.

Only ``data/localization/``, ``artifacts/localization/`` and the coverage Markdown are ever
written: the canonical English dataset and tooltips are inputs, never outputs.
"""

from __future__ import annotations

import json
import re
from collections import Counter
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

from pydantic import ValidationError

from talent_data import paths
from talent_data.games import GameConfig
from talent_data.localization.coverage import (
    ClassInput,
    LocalizationCoverageReport,
    build_report,
    render_markdown,
)
from talent_data.localization.generate import load_inputs, localize_document
from talent_data.localization.source import TableReader, TableVerification
from talent_data.models.localization import (
    LOCALIZATION_SCHEMA_VERSION,
    Locale,
    TalentLocalizationDocument,
    expected_content_hash,
)
from talent_data.models.talents import TalentDataDocument
from talent_data.models.tooltips import TalentTooltipsDocument
from talent_data.pipeline import PipelineError
from talent_data.sources.base import Db2Source
from talent_data.tooltips.pipeline import _relative_files, _write, read_tooltips_document
from talent_data.tooltips.spells import SpellLookup
from talent_data.versions import ClientVersion
from talent_data.visuals.pipeline import load_canonical_dataset

_NUMBER = re.compile(r"\d+(?:\.\d+)?")
_DECIMAL = re.compile(r"\d\.\d")


@dataclass(frozen=True)
class BuildLocalization:
    game: GameConfig
    version: ClientVersion
    locale: Locale
    documents: tuple[TalentDataDocument, ...]
    overlays: Mapping[str, TalentLocalizationDocument]  # class token -> overlay
    verification: tuple[TableVerification, ...]

    def report(self, published: frozenset[str]) -> LocalizationCoverageReport:
        return build_report(
            game=self.game.key,
            client_build=str(self.version),
            locale=self.locale,
            classes=[
                ClassInput(
                    talents=doc,
                    overlay=self.overlays[doc.class_.token],
                    published=doc.class_.token in published,
                )
                for doc in self.documents
            ],
        )


def localize_build(
    english: TableReader,
    localized: TableReader,
    tooltip_tables: Db2Source,
    game: GameConfig,
    version: ClientVersion,
    locale: Locale,
    *,
    source_name: str,
) -> BuildLocalization:
    """The overlays of every class of ``version`` (nothing is written)."""
    documents = load_canonical_dataset(game, version)
    inputs = load_inputs(
        english, localized, SpellLookup.load(tooltip_tables, version), version, locale
    )
    overlays: dict[str, TalentLocalizationDocument] = {}
    for document in documents:
        token = document.class_.token
        tooltips = read_tooltips_document(paths.tooltips_path(game.key, version, token))
        overlays[token] = localize_document(document, tooltips, inputs, source_name=source_name)
    return BuildLocalization(
        game=game,
        version=version,
        locale=locale,
        documents=tuple(documents),
        overlays=overlays,
        verification=inputs.verification,
    )


def published_classes(build: BuildLocalization, keys: Sequence[str]) -> frozenset[str]:
    """Class tokens for ``keys`` (tokens, case-insensitive, or 'all'); unknown keys fail."""
    tokens = [doc.class_.token for doc in build.documents]
    if list(keys) == ["all"]:
        return frozenset(tokens)
    chosen = {key.upper() for key in keys}
    unknown = sorted(chosen - set(tokens))
    if unknown or not chosen:
        raise PipelineError(f"unknown classes {unknown} (known: {', '.join(tokens)})")
    return frozenset(chosen)


@dataclass(frozen=True)
class PublishedLocalization:
    directory: Path
    report: LocalizationCoverageReport


def publish(build: BuildLocalization, published: frozenset[str]) -> PublishedLocalization:
    """Write the overlays of ``published`` (exactly those files), the coverage report of every
    class and its Markdown summary."""
    game, version, locale = build.game.key, build.version, build.locale
    report = build.report(published)
    directory = paths.localization_dir(game, version, locale)
    files = {
        paths.localization_path(game, version, locale, token).name: build.overlays[token].to_json()
        for token in sorted(published)
    }
    for name, text in sorted(files.items()):
        _write(directory / name, text)
    for stale in _relative_files(directory) - files.keys():
        (directory / stale).unlink()
    _write(paths.localization_coverage_path(game, version, locale), report.to_json())
    _write(
        paths.localization_coverage_markdown_path(game, version, locale), render_markdown(report)
    )
    return PublishedLocalization(directory=directory, report=report)


# --------------------------------------------------------------------------- offline check


def read_localization_document(path: Path) -> TalentLocalizationDocument:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise PipelineError(f"cannot read talent localization {path}: {exc}") from exc
    if not isinstance(raw, dict) or raw.get("schemaVersion") != LOCALIZATION_SCHEMA_VERSION:
        version = raw.get("schemaVersion") if isinstance(raw, dict) else None
        raise PipelineError(f"{path}: unsupported talent-localization schemaVersion {version!r}")
    try:
        return TalentLocalizationDocument.model_validate(raw)
    except ValidationError as exc:
        raise PipelineError(f"{path}: not a talent-localization.v1 document: {exc}") from exc


def read_coverage_report(path: Path) -> LocalizationCoverageReport:
    try:
        return LocalizationCoverageReport.model_validate_json(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise PipelineError(f"cannot read localization coverage report {path}: {exc}") from exc


def _numbers(texts: Sequence[str]) -> Counter[str]:
    return Counter(n for text in texts for n in _NUMBER.findall(text))


def check_localization_document(
    overlay: TalentLocalizationDocument,
    talents: TalentDataDocument,
    tooltips: TalentTooltipsDocument,
) -> list[str]:
    """Problems that make ``overlay`` unusable next to ``talents`` and its English
    ``tooltips`` (empty = consistent). Identity first: a stale or foreign overlay is never
    partially applied."""
    problems: list[str] = []
    add = problems.append
    where = f"{overlay.class_token} {overlay.locale}"
    if overlay.game != talents.game:
        add(f"{where}: overlay game {overlay.game!r} != dataset {talents.game!r}")
    if overlay.client_build != talents.client_build:
        add(
            f"{where}: overlay clientBuild {overlay.client_build!r} "
            f"!= dataset {talents.client_build!r}"
        )
    if overlay.class_token != talents.class_.token:
        add(f"{where}: overlay is for {overlay.class_token}, dataset is {talents.class_.token}")
    if overlay.talent_data_content_hash != talents.content_hash:
        add(
            f"{where}: overlay belongs to talent-data {overlay.talent_data_content_hash}, "
            f"dataset is {talents.content_hash}"
        )
    if tooltips.talent_data_content_hash != talents.content_hash:
        add(f"{where}: the English tooltips do not belong to the dataset")
    if overlay.tooltips_content_hash != tooltips.content_hash:
        add(
            f"{where}: overlay was checked against tooltips {overlay.tooltips_content_hash}, "
            f"the English tooltips are {tooltips.content_hash}"
        )
    if overlay.content_hash != expected_content_hash(overlay):
        add(f"{where}: contentHash does not match the document (stale or tampered)")
    if problems:
        return problems

    entry_ids = [e.entry_id for e in overlay.entries]
    if entry_ids != sorted(entry_ids):
        add(f"{where}: entries are not ordered by entryId")
    if len(entry_ids) != len(set(entry_ids)):
        add(f"{where}: duplicate entryId in entries")
    known = {
        e.id: (node.id, e) for tree in talents.trees for node in tree.nodes for e in node.entries
    }
    unknown = sorted(set(entry_ids) - known.keys())
    missing = sorted(known.keys() - set(entry_ids))
    if unknown:
        add(f"{where}: overlay references unknown talent entries {unknown}")
    if missing:
        add(f"{where}: talent entries without an overlay entry {missing}")
    english = {e.entry_id: e for e in tooltips.entries}
    for entry in overlay.entries:
        if entry.entry_id not in known:
            continue
        node_id, talent = known[entry.entry_id]
        actual = (entry.node_id, entry.definition_id, entry.spell_id, entry.max_rank)
        expected = (node_id, talent.definition_id, talent.spell_id, talent.max_rank)
        if actual != expected:
            add(
                f"{where}: entry {entry.entry_id} is node/definition/spell/maxRank {actual}, "
                f"dataset says {expected}"
            )
            continue
        if entry.description.status != "rendered":
            continue
        ranks = english[entry.entry_id].ranks
        for mine, theirs in zip(entry.description.ranks, ranks, strict=True):
            if theirs.status != "resolved":
                add(
                    f"{where}: entry {entry.entry_id} rank {mine.rank} is rendered but the "
                    "English rank is not resolved"
                )
                continue
            ours = [s.text for s in mine.segments if s.kind == "value"]
            reference = [s.text for s in theirs.segments if s.kind == "value"]
            if _numbers(ours) != _numbers(reference):
                add(
                    f"{where}: entry {entry.entry_id} rank {mine.rank} shows numbers "
                    f"{sorted(_numbers(ours).elements())}, English shows "
                    f"{sorted(_numbers(reference).elements())}"
                )
            if any(_DECIMAL.search(text) for text in ours):
                add(f"{where}: entry {entry.entry_id} rank {mine.rank} shows a decimal value")
    return problems


def check_canonical(game: GameConfig, version: ClientVersion, locale: Locale) -> list[str]:
    """Verify the committed overlays of ``version``/``locale`` against the committed dataset
    and English tooltips, and the committed coverage report and Markdown against them. Pure
    repository check. The report rows of unpublished classes need the DB2 tables and are
    covered by the regeneration test."""
    documents = load_canonical_dataset(game, version)
    directory = paths.localization_dir(game.key, version, locale)
    if not directory.is_dir():
        return [f"no {locale} localization of {version} ({directory})"]
    problems: list[str] = []
    by_name = {
        paths.localization_path(game.key, version, locale, d.class_.token).name: d
        for d in documents
    }
    present = _relative_files(directory)
    classes: dict[str, ClassInput] = {}
    for name in sorted(present):
        talents = by_name.get(name)
        if talents is None:
            problems.append(f"{name} is not part of the {locale} localization of {version}")
            continue
        try:
            overlay = read_localization_document(directory / name)
            tooltips = read_tooltips_document(
                paths.tooltips_path(game.key, version, talents.class_.token)
            )
        except PipelineError as exc:
            problems.append(str(exc))
            continue
        problems.extend(check_localization_document(overlay, talents, tooltips))
        classes[talents.class_.token] = ClassInput(talents=talents, overlay=overlay, published=True)
    if not present:
        problems.append(f"{directory} contains no overlay")
    if problems:
        return problems
    # recompute in dataset order: the report and its exception lists are ordered by the
    # dataset's class order, not by file name
    ordered = [classes[d.class_.token] for d in documents if d.class_.token in classes]

    report_path = paths.localization_coverage_path(game.key, version, locale)
    try:
        report = read_coverage_report(report_path)
    except PipelineError as exc:
        return [str(exc)]
    if (report.game, report.client_build, report.locale) != (game.key, str(version), locale):
        return [f"{report_path}: is for {report.game} {report.client_build} {report.locale}"]
    flagged = {row.class_token for row in report.classes if row.published}
    if flagged != classes.keys():
        problems.append(
            f"{report_path}: published classes {sorted(flagged)} != committed overlays "
            f"{sorted(classes)}"
        )
    if [row.class_token for row in report.classes] != [d.class_.token for d in documents]:
        problems.append(f"{report_path}: does not list every class of the dataset in order")
    recomputed = build_report(
        game=game.key, client_build=str(version), locale=locale, classes=ordered
    )
    rows = {row.class_token: row for row in report.classes}
    for row in recomputed.classes:
        if rows.get(row.class_token) != row:
            problems.append(f"{report_path}: the {row.class_token} row does not match its overlay")
    for kind, ours, theirs in (
        ("name", recomputed.name_exceptions, report.name_exceptions),
        ("description", recomputed.description_exceptions, report.description_exceptions),
    ):
        listed = [e for e in theirs if e.class_token in classes]
        if listed != list(ours):
            problems.append(f"{report_path}: the {kind} exceptions do not match the overlays")
    markdown_path = paths.localization_coverage_markdown_path(game.key, version, locale)
    if not markdown_path.is_file() or markdown_path.read_text(encoding="utf-8") != render_markdown(
        report
    ):
        problems.append(f"{markdown_path}: does not match the coverage report")
    return problems
