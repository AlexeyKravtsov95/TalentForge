"""Build one ``talent-localization.v1`` overlay from the client strings of one locale.

Mapping (deterministic, identity first):

* an overlay entry is keyed by ``TalentEntry.id``; its node, definition, spell and max rank
  are copied from the dataset, never derived from text;
* the localized strings are read with **the dataset's own English rule**, applied to the
  locale's export of the same build: the name is ``TraitDefinition.OverrideName_lang`` when
  the English override is set, otherwise ``SpellName.Name_lang`` of the text spell
  (``VisibleSpellID`` when set, otherwise ``SpellID``); the description template likewise
  ``OverrideDescription_lang`` / ``Spell.Description_lang``. The English strings obtained the
  same way must equal the dataset's ``name`` / ``descriptionTemplate`` - otherwise dataset
  and tables are not the same build and generation stops;
* several entries may share a spell (DUPLICATE_SPELL_IDENTITY): each still gets its own
  overlay entry, and the shared spell yields the same text for both - by identity, not by
  guessing which node a string "belongs" to.

Descriptions are never assembled from English fragments. The localized template is rendered
by the unchanged M5.V2 renderer (``talent_data.tooltips.render``) with the locale's own
template, nested templates and duration strings, and the same build's locale-independent
numeric tables. A description is published only when every gate passes:

1. the template exists in the locale (``missing``) and is not the English one
   (``untranslated``);
2. the English sidecar resolves every rank (``sourceNotResolved``) - the localized text can
   never claim more than the verified English one;
3. no plural-form selection ``$l...;`` / ``$L...;`` in the template or a nested one
   (``pluralRuleUnverified``) - Russian templates carry three forms whose client selection
   rule is not recorded evidence;
4. every nested template exists and is translated (``missing`` / ``untranslated``) and every
   template parses (``structureMismatch``);
5. the multiset of value constructs (``$s1``, ``${...}``, ``$@spelltooltip...``, conditions)
   equals the English template's, for the template and each nested one
   (``structureMismatch``);
6. every rank renders completely (``renderGap``);
7. at every rank the numbers shown equal the numbers of the English sidecar
   (``structureMismatch``);
8. no shown value has a decimal part (``decimalFormatUnverified``): the ruRU client's
   ``DECIMAL_SEPERATOR`` is ``","`` while the renderer prints ``"."``; which one the
   tooltip formatter uses is not recorded evidence.
"""

from __future__ import annotations

import re
from collections import Counter
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, replace

from talent_data.db2 import tables as t
from talent_data.localization.source import (
    LOCALIZATION_TABLES,
    TableReader,
    TableVerification,
    table_url,
    verify_localized_table,
)
from talent_data.models.localization import (
    CYRILLIC,
    LOCALIZATION_SCHEMA_VERSION,
    SOURCE_LOCALE,
    DescriptionSource,
    DescriptionStatus,
    Locale,
    LocalizationProvenance,
    LocalizationSourceTable,
    LocalizedDescription,
    LocalizedEntry,
    LocalizedName,
    LocalizedRank,
    LocalizedSegment,
    NameSource,
    NameStatus,
    TalentLocalizationDocument,
    expected_content_hash,
)
from talent_data.models.talents import TalentDataDocument, TalentEntry
from talent_data.models.tooltips import TalentTooltipsDocument, TooltipRank
from talent_data.models.visuals import content_hash, hashed_payload
from talent_data.pipeline import PipelineError
from talent_data.sources.base import Db2Table, SourceError
from talent_data.sources.wago.source import WAGO_BASE_URL
from talent_data.tooltips.grammar import (
    Color,
    Conditional,
    LineBreak,
    Nested,
    Node,
    Plural,
    TemplateSyntaxError,
    Text,
    parse,
)
from talent_data.tooltips.pipeline import check_tooltips_document
from talent_data.tooltips.render import RENDERER, TalentRenderer
from talent_data.tooltips.spells import SpellLookup
from talent_data.tooltips.values import DurationFormats
from talent_data.versions import ClientVersion

LOCALIZER = f"talent_data.localization gates-v1 + {RENDERER}"

_PLURAL = re.compile(r"\$[lL]")
_NUMBER = re.compile(r"\d+(?:\.\d+)?")
_DECIMAL = re.compile(r"\d\.\d")


# --------------------------------------------------------------------------- inputs


def _unique[R](records: Sequence[R], table: str) -> dict[int, R]:
    result: dict[int, R] = {}
    for record in records:
        record_id: int = getattr(record, "id")  # noqa: B009 - every DB2 record has an id
        if record_id in result:
            raise SourceError(f"{table} has two rows ID={record_id}")
        result[record_id] = record
    return result


@dataclass(frozen=True)
class LocaleTexts:
    """The text columns of one locale's export of one build."""

    locale: str
    names: Mapping[int, str]  # SpellName.ID -> Name_lang
    descriptions: Mapping[int, str]  # Spell.ID -> Description_lang
    definitions: Mapping[int, t.TraitDefinition]
    duration_formats: DurationFormats

    @classmethod
    def from_tables(cls, locale: str, tables: Mapping[str, Db2Table]) -> LocaleTexts:
        names = _unique(t.parse_table(tables["SpellName"], t.SpellName.parse), "SpellName")
        spells = _unique(t.parse_table(tables["Spell"], t.Spell.parse), "Spell")
        definitions = _unique(
            t.parse_table(tables["TraitDefinition"], t.TraitDefinition.parse), "TraitDefinition"
        )
        strings = t.parse_table(tables["GlobalStrings"], t.GlobalString.parse)
        return cls(
            locale=locale,
            names={k: v.name for k, v in names.items()},
            descriptions={k: v.description for k, v in spells.items()},
            definitions=definitions,
            duration_formats=DurationFormats.from_global_strings(strings),
        )


@dataclass(frozen=True)
class LocalizationInputs:
    version: ClientVersion
    locale: Locale
    english: LocaleTexts
    localized: LocaleTexts
    spells: SpellLookup  # the build's English tooltip tables (numbers, nested references)
    verification: tuple[TableVerification, ...]


def load_inputs(
    english: TableReader,
    localized: TableReader,
    spells: SpellLookup,
    version: ClientVersion,
    locale: Locale,
) -> LocalizationInputs:
    """Load and verify both exports of every localization table of ``version``."""
    if spells.version != version:
        raise PipelineError(f"tooltip tables of {spells.version} used for {version}")
    english_tables = {name: english.load_table(version, name) for name in LOCALIZATION_TABLES}
    localized_tables = {name: localized.load_table(version, name) for name in LOCALIZATION_TABLES}
    verification = tuple(
        verify_localized_table(english_tables[name], localized_tables[name], locale)
        for name in LOCALIZATION_TABLES
    )
    english_texts = LocaleTexts.from_tables(SOURCE_LOCALE, english_tables)
    for spell_id, text in english_texts.descriptions.items():
        known = spells.descriptions.get(spell_id)
        if known is not None and known != text:
            raise PipelineError(
                f"Spell {spell_id}.Description_lang differs between the localization and the "
                f"tooltip tables of {version}: not the same build"
            )
    return LocalizationInputs(
        version=version,
        locale=locale,
        english=english_texts,
        localized=LocaleTexts.from_tables(locale, localized_tables),
        spells=spells,
        verification=verification,
    )


# --------------------------------------------------------------------------- strings


def _localized_status(english: str | None, localized: str | None, where: str) -> NameStatus:
    if not localized:
        return "missing"
    if localized == english:
        return "untranslated"
    if not CYRILLIC.search(localized):
        # Neither English nor Russian: an unknown case is investigated, never published.
        raise PipelineError(
            f"{where}: the localized text {localized!r} differs from English {english!r} but "
            "contains no Cyrillic letter"
        )
    return "translated"


def _text_spell(definition: t.TraitDefinition) -> int:
    # VisibleSpellID, when set, is what the client shows in tooltips (the normalizer's rule).
    return definition.visible_spell_id or definition.spell_id


def _definition(texts: LocaleTexts, entry: TalentEntry) -> t.TraitDefinition:
    definition = texts.definitions.get(entry.definition_id)
    if definition is None:
        raise PipelineError(
            f"entry {entry.id}: TraitDefinition {entry.definition_id} is missing from the "
            f"{texts.locale} export"
        )
    return definition


def _name(entry: TalentEntry, inputs: LocalizationInputs) -> LocalizedName:
    definition = _definition(inputs.english, entry)
    source: NameSource
    if definition.override_name:
        source = "TraitDefinition.OverrideName_lang"
        english: str | None = definition.override_name
        row = inputs.localized.definitions.get(definition.id)
        localized = row.override_name if row else None
    else:
        source = "SpellName.Name_lang"
        english = inputs.english.names.get(_text_spell(definition))
        localized = inputs.localized.names.get(_text_spell(definition))
    if (english or None) != entry.name:
        raise PipelineError(
            f"entry {entry.id}: English {source} is {english!r}, the dataset name is "
            f"{entry.name!r}: dataset and tables are not the same build"
        )
    status = _localized_status(english, localized, f"entry {entry.id} name")
    return LocalizedName(
        status=status, text=localized if status == "translated" else None, source=source
    )


# --------------------------------------------------------------------------- descriptions


def value_signature(nodes: Sequence[Node]) -> Counter[str]:
    """The value constructs of a parsed template, as a multiset of their raw spelling.

    Literal text, line breaks, colours and plural words are language; everything else (effect
    references, expressions, nested tooltips, client conditions and their branch structure)
    must be the same in every translation of one template."""
    signature: Counter[str] = Counter()
    for node in nodes:
        match node:
            case Text() | LineBreak() | Color() | Plural():
                continue
            case Conditional():
                signature[f"$?{node.condition}/{len(node.branches)}"] += 1
                for branch in node.branches:
                    signature += value_signature(branch)
            case _:
                signature[node.raw] += 1
    return signature


def _nested_spells(nodes: Sequence[Node]) -> list[int]:
    found: list[int] = []
    for node in nodes:
        match node:
            case Nested():
                found.append(node.spell_id)
            case Conditional():
                for branch in node.branches:
                    found += _nested_spells(branch)
    return found


def _numbers(segments: Sequence[tuple[str, str]]) -> Counter[str]:
    return Counter(n for kind, text in segments if kind == "value" for n in _NUMBER.findall(text))


@dataclass(frozen=True)
class _Outcome:
    status: DescriptionStatus
    ranks: tuple[LocalizedRank, ...] = ()


def _templates(
    entry: TalentEntry, inputs: LocalizationInputs
) -> tuple[DescriptionSource, str | None, str | None]:
    definition = _definition(inputs.english, entry)
    source: DescriptionSource
    if definition.override_description:
        source = "TraitDefinition.OverrideDescription_lang"
        english: str | None = definition.override_description
        row = inputs.localized.definitions.get(definition.id)
        localized = row.override_description if row else None
    else:
        source = "Spell.Description_lang"
        english = inputs.english.descriptions.get(_text_spell(definition))
        localized = inputs.localized.descriptions.get(_text_spell(definition))
    if (english or None) != entry.description_template:
        raise PipelineError(
            f"entry {entry.id}: English {source} differs from the dataset's description "
            "template: dataset and tables are not the same build"
        )
    return source, english, localized


def _gate_text(english: str | None, localized: str | None, where: str) -> DescriptionStatus | None:
    status = _localized_status(english, localized, where)
    return None if status == "translated" else status


def _describe(
    entry: TalentEntry,
    english: str | None,
    localized: str | None,
    english_ranks: Sequence[TooltipRank],
    inputs: LocalizationInputs,
    spells: SpellLookup,
) -> _Outcome:
    where = f"entry {entry.id} description"
    # 1. the template itself
    failed = _gate_text(english, localized, where)
    if failed is not None:
        return _Outcome(failed)
    assert localized is not None and english is not None
    # 2. never more than the verified English text
    if any(rank.status != "resolved" for rank in english_ranks):
        return _Outcome("sourceNotResolved")
    # 3. plural-form selection
    if _PLURAL.search(localized):
        return _Outcome("pluralRuleUnverified")
    # 4. + 5. the template and every nested template: present, translated, parseable, same
    # value constructs as English
    pending: list[tuple[str, str]] = [(english, localized)]
    seen: set[int] = set()
    while pending:
        english_text, localized_text = pending.pop()
        try:
            english_nodes, localized_nodes = parse(english_text), parse(localized_text)
        except TemplateSyntaxError:
            return _Outcome("structureMismatch")
        if value_signature(english_nodes) != value_signature(localized_nodes):
            return _Outcome("structureMismatch")
        for spell_id in _nested_spells(localized_nodes):
            if spell_id in seen:
                continue
            seen.add(spell_id)
            nested_english = inputs.english.descriptions.get(spell_id)
            nested_localized = inputs.localized.descriptions.get(spell_id)
            failed = _gate_text(nested_english, nested_localized, f"{where}, spell {spell_id}")
            if failed is not None:
                return _Outcome(failed)
            assert nested_english is not None and nested_localized is not None
            if _PLURAL.search(nested_localized):
                return _Outcome("pluralRuleUnverified")
            pending.append((nested_english, nested_localized))
    # 6. render every rank with the locale's strings and the build's numbers
    renderer = TalentRenderer(entry.model_copy(update={"description_template": localized}), spells)
    rendered = [renderer.render(rank).rank for rank in range(1, entry.max_rank + 1)]
    if any(rank.status != "resolved" for rank in rendered):
        return _Outcome("renderGap")
    # 7. the same numbers as the English sidecar, rank by rank
    for mine, theirs in zip(rendered, english_ranks, strict=True):
        ours = [(s.kind, s.text) for s in mine.segments]
        reference = [(s.kind, s.text) for s in theirs.segments]
        if _numbers(ours) != _numbers(reference):
            return _Outcome("structureMismatch")
    # 8. decimal separator
    if any(s.kind == "value" and _DECIMAL.search(s.text) for r in rendered for s in r.segments):
        return _Outcome("decimalFormatUnverified")
    return _Outcome(
        "rendered",
        tuple(
            LocalizedRank(
                rank=rank.rank,
                segments=[
                    LocalizedSegment(kind="value" if s.kind == "value" else "text", text=s.text)
                    for s in rank.segments
                ],
            )
            for rank in rendered
        ),
    )


# --------------------------------------------------------------------------- document


def _entries(document: TalentDataDocument) -> list[tuple[int, TalentEntry]]:
    return sorted(
        ((node.id, e) for tree in document.trees for node in tree.nodes for e in node.entries),
        key=lambda pair: pair[1].id,
    )


def source_tables(
    version: ClientVersion, locale: Locale, base_url: str = WAGO_BASE_URL
) -> list[LocalizationSourceTable]:
    return [
        LocalizationSourceTable(
            table=name, locale=code, url=table_url(base_url, version, name, code)
        )
        for code in (SOURCE_LOCALE, locale)
        for name in LOCALIZATION_TABLES
    ]


def localize_document(
    document: TalentDataDocument,
    tooltips: TalentTooltipsDocument,
    inputs: LocalizationInputs,
    *,
    source_name: str,
) -> TalentLocalizationDocument:
    """The overlay of one class document in ``inputs.locale``."""
    token = document.class_.token
    if document.client_build != str(inputs.version):
        raise PipelineError(
            f"{token}: dataset {document.client_build} with tables of {inputs.version}"
        )
    problems = check_tooltips_document(tooltips, document)
    if problems:
        raise PipelineError(f"{token}: the English tooltips do not match the dataset: {problems}")
    english_ranks = {e.entry_id: e.ranks for e in tooltips.entries}
    spells = replace(
        inputs.spells,
        descriptions=inputs.localized.descriptions,
        duration_formats=inputs.localized.duration_formats,
    )
    entries: list[LocalizedEntry] = []
    for node_id, entry in _entries(document):
        definition = _definition(inputs.english, entry)
        source, english, localized = _templates(entry, inputs)
        outcome = _describe(entry, english, localized, english_ranks[entry.id], inputs, spells)
        entries.append(
            LocalizedEntry(
                entry_id=entry.id,
                node_id=node_id,
                definition_id=entry.definition_id,
                spell_id=entry.spell_id,
                text_spell_id=_text_spell(definition),
                max_rank=entry.max_rank,
                name=_name(entry, inputs),
                description=LocalizedDescription(
                    status=outcome.status, source=source, ranks=list(outcome.ranks)
                ),
            )
        )
    payload = {
        "schemaVersion": LOCALIZATION_SCHEMA_VERSION,
        "game": document.game,
        "clientBuild": document.client_build,
        "classToken": token,
        "locale": inputs.locale,
        "talentDataContentHash": document.content_hash,
        "tooltipsContentHash": tooltips.content_hash,
        "entries": [e.model_dump(mode="json", by_alias=True) for e in entries],
    }
    overlay = TalentLocalizationDocument(
        schema_version=LOCALIZATION_SCHEMA_VERSION,
        game=document.game,
        client_build=document.client_build,
        class_token=token,
        locale=inputs.locale,
        talent_data_content_hash=document.content_hash,
        tooltips_content_hash=tooltips.content_hash,
        entries=entries,
        content_hash=content_hash(hashed_payload(payload)),
        provenance=LocalizationProvenance(
            source=source_name,
            renderer=LOCALIZER,
            tables=source_tables(inputs.version, inputs.locale),
        ),
    )
    if overlay.content_hash != expected_content_hash(overlay):
        raise PipelineError(f"{token}: localization content hash is not reproducible")
    return overlay
