"""Reference resolution of the text shown for each talent entry, with English fallback.

The executable form of the consumer rule of ``talent-localization.v1``
(``docs/talent-localization.md`` §5). The mobile client mirrors it in M5.REL.3b, the way the
Kotlin allocation engine mirrors the Python reference engine:

* an overlay is applied **as a whole or not at all**: when it is absent, or
  :func:`~talent_data.localization.pipeline.check_localization_document` reports any problem
  (another build, class or game; another dataset or tooltip revision; a tampered or stale
  document; an entry whose identity drifted), every entry shows English and the problems are
  returned - a stale overlay is never partially applied;
* the name is the overlay's text when its status is ``translated``, otherwise the English
  dataset name;
* the description is the overlay's ranks when its status is ``rendered``, otherwise the
  English sidecar ranks **unchanged** - their partial/unresolved status, gaps and missing-value
  markers come from English and are never mixed with localized text.

Identity in (``entryId``), display text out. Nothing here is an input to allocation,
validation or saved builds.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Literal

from talent_data.localization.pipeline import check_localization_document
from talent_data.models.localization import (
    LocalizedEntry,
    LocalizedRank,
    TalentLocalizationDocument,
)
from talent_data.models.talents import TalentDataDocument
from talent_data.models.tooltips import TalentTooltipsDocument, TooltipRank
from talent_data.pipeline import PipelineError
from talent_data.tooltips.pipeline import check_tooltips_document

TextLocale = Literal["enUS", "ruRU"]


@dataclass(frozen=True)
class EntryText:
    """What one talent entry shows. Exactly one of the rank tuples is set."""

    entry_id: int
    name: str | None  # None exactly when the English dataset has no name either
    name_locale: TextLocale
    description_locale: TextLocale
    english_ranks: tuple[TooltipRank, ...] | None  # the English sidecar ranks, as they are
    localized_ranks: tuple[LocalizedRank, ...] | None  # complete localized ranks 1..maxRank


@dataclass(frozen=True)
class ClassText:
    entries: Mapping[int, EntryText]  # by TalentEntry.id - every entry of the dataset
    overlay_problems: tuple[str, ...]  # why a supplied overlay was not applied (empty = applied)


def resolve_class_text(
    talents: TalentDataDocument,
    tooltips: TalentTooltipsDocument,
    overlay: TalentLocalizationDocument | None,
) -> ClassText:
    """The display text of every entry of ``talents``: localized where the overlay proves it,
    English everywhere else. Inconsistent English tooltips are an error, not a fallback."""
    english_problems = check_tooltips_document(tooltips, talents)
    if english_problems:
        raise PipelineError(f"the English tooltips do not match the dataset: {english_problems}")
    problems: tuple[str, ...] = ()
    localized: dict[int, LocalizedEntry] = {}
    if overlay is not None:
        problems = tuple(check_localization_document(overlay, talents, tooltips))
        if not problems:
            localized = {entry.entry_id: entry for entry in overlay.entries}
    locale: TextLocale = overlay.locale if overlay is not None else "enUS"
    english_ranks = {entry.entry_id: tuple(entry.ranks) for entry in tooltips.entries}
    entries: dict[int, EntryText] = {}
    for tree in talents.trees:
        for node in tree.nodes:
            for talent in node.entries:
                mine = localized.get(talent.id)
                name = mine.name.text if mine is not None else None
                translated = mine is not None and mine.name.status == "translated"
                rendered = mine is not None and mine.description.status == "rendered"
                entries[talent.id] = EntryText(
                    entry_id=talent.id,
                    name=name if translated and name else talent.name,
                    name_locale=locale if translated else "enUS",
                    description_locale=locale if rendered else "enUS",
                    english_ranks=None if rendered else english_ranks[talent.id],
                    localized_ranks=(
                        tuple(mine.description.ranks) if rendered and mine is not None else None
                    ),
                )
    return ClassText(entries=entries, overlay_problems=problems)
