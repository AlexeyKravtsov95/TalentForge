"""Talent localization - the localized-text overlay contract (schema v1).

``talent-localization.v1`` is one public, versioned, closed document per class, client build
and locale. It carries, for every talent entry of the matching ``talent-data.v1`` document,
the client's own text for that locale: the talent name and - where it can be rendered
honestly - the description at every rank. It is a pure presentation overlay:

* ``talent-data.v1`` and ``talent-tooltips.v1`` stay untouched and remain the canonical
  English content. A consumer shows the English text whenever the overlay does not mark a
  localized text as usable (``name.status != "translated"``, ``description.status !=
  "rendered"``) - the overlay never contains English fallback text of its own;
* identity is never localized: entries are keyed by ``entryId`` and repeat the dataset's
  ``nodeId`` / ``definitionId`` / ``spellId`` / ``maxRank`` so a reader can prove the overlay
  belongs to exactly that dataset revision (``talentDataContentHash``), and the descriptions
  were checked against exactly that English sidecar (``tooltipsContentHash``);
* nothing here is an input to allocation, validation or saved builds.

Name status:

* ``translated``   - the locale's string differs from the English string of the same build
  and contains Cyrillic text; ``text`` is it;
* ``untranslated`` - the source served the English string for this locale (the row is not
  translated in the client data, or the source fell back); ``text`` is null;
* ``missing``      - the locale has no row, or an empty string, for this text; ``text`` is null.

Description status: ``rendered`` (every rank is complete in ``ranks``) or the first gate that
failed (see ``DescriptionStatus``); only ``rendered`` has ranks.

JSON uses camelCase; Python uses snake_case. Parsing is strict: unknown fields, missing fields
and wrong wire types are rejected. No field has a default. Everything is deterministic - no
timestamp - so regenerating from the same inputs yields byte-identical files.
"""

from __future__ import annotations

import re
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

from talent_data.models.tooltips import MARKUP_CHARACTERS
from talent_data.models.visuals import Sha256, content_hash, hashed_payload

LOCALIZATION_SCHEMA_VERSION: Literal[1] = 1

_SHA256_PATTERN = r"^sha256:[0-9a-f]{64}$"
_BUILD_PATTERN = r"^\d+\.\d+\.\d+\.\d+$"

# Closed set: a locale is supported only after its source behaviour and text rules have been
# investigated (docs/talent-localization.md). English is the canonical dataset itself.
Locale = Literal["ruRU"]
SUPPORTED_LOCALES: tuple[Locale, ...] = ("ruRU",)
SOURCE_LOCALE: Literal["enUS"] = "enUS"

CYRILLIC = re.compile("[Ѐ-ӿ]")

NameStatus = Literal["translated", "untranslated", "missing"]
NameSource = Literal["SpellName.Name_lang", "TraitDefinition.OverrideName_lang"]
DescriptionSource = Literal["Spell.Description_lang", "TraitDefinition.OverrideDescription_lang"]
# Gates in evaluation order; the first one that fails is the status (docs/talent-localization.md).
DescriptionStatus = Literal[
    "missing",  # no localized row/text for the template (or for a nested template)
    "untranslated",  # the localized template is the English one (or a nested one is)
    "sourceNotResolved",  # the English sidecar is not resolved at every rank
    "pluralRuleUnverified",  # the localized template selects plural forms ($l...;)
    "structureMismatch",  # value constructs or rendered numbers differ from English
    "renderGap",  # a localized rank did not render completely
    "decimalFormatUnverified",  # a rendered value has a decimal part (separator unproven)
    "rendered",
]
DESCRIPTION_STATUSES: tuple[DescriptionStatus, ...] = (
    "missing",
    "untranslated",
    "sourceNotResolved",
    "pluralRuleUnverified",
    "structureMismatch",
    "renderGap",
    "decimalFormatUnverified",
    "rendered",
)
NAME_STATUSES: tuple[NameStatus, ...] = ("translated", "untranslated", "missing")
SegmentKind = Literal["text", "value"]


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        frozen=True,
        extra="forbid",
        strict=True,
    )


class LocalizedSegment(_Model):
    kind: SegmentKind = Field(
        description="text: literal localized template text; value: one evaluated value"
    )
    text: str = Field(min_length=1)

    @model_validator(mode="after")
    def _plain(self) -> Self:
        leaked = [c for c in MARKUP_CHARACTERS if c in self.text]
        if leaked:
            raise ValueError(f"{self.kind} segment contains template markup {leaked!r}")
        return self


class LocalizedRank(_Model):
    rank: int = Field(ge=1)
    segments: list[LocalizedSegment] = Field(min_length=1)


class LocalizedName(_Model):
    status: NameStatus
    text: str | None = Field(description="The localized name; present exactly when translated")
    source: NameSource = Field(description="The client column the name is taken from")

    @model_validator(mode="after")
    def _consistent(self) -> Self:
        if self.status == "translated":
            if not self.text or not CYRILLIC.search(self.text):
                raise ValueError("a translated name has Cyrillic text")
        elif self.text is not None:
            raise ValueError(f"a {self.status} name has no text (the consumer uses English)")
        return self


class LocalizedDescription(_Model):
    status: DescriptionStatus
    source: DescriptionSource = Field(description="The client column the template comes from")
    ranks: list[LocalizedRank] = Field(
        description="Exactly ranks 1..maxRank when rendered; empty otherwise"
    )

    @model_validator(mode="after")
    def _consistent(self) -> Self:
        if self.status != "rendered" and self.ranks:
            raise ValueError(f"a {self.status} description has no ranks")
        return self


class LocalizedEntry(_Model):
    entry_id: int = Field(gt=0, description="TalentEntry.id of the matching talent-data.v1")
    node_id: int = Field(gt=0, description="TalentNode.id the entry belongs to")
    definition_id: int = Field(gt=0, description="TraitDefinition.ID")
    spell_id: int = Field(gt=0, description="TraitDefinition.SpellID (= TalentEntry.spellId)")
    text_spell_id: int = Field(
        gt=0,
        description="The spell whose client strings name and describe the entry: "
        "TraitDefinition.VisibleSpellID when set, otherwise SpellID (the dataset's own rule)",
    )
    max_rank: int = Field(ge=1, description="TalentEntry.maxRank")
    name: LocalizedName
    description: LocalizedDescription

    @model_validator(mode="after")
    def _every_rank(self) -> Self:
        if self.description.status == "rendered" and [
            r.rank for r in self.description.ranks
        ] != list(range(1, self.max_rank + 1)):
            raise ValueError(f"entry {self.entry_id}: ranks must be exactly 1..{self.max_rank}")
        return self


class LocalizationSourceTable(_Model):
    table: str = Field(min_length=1)
    locale: Literal["enUS", "ruRU"]
    url: str = Field(min_length=1, description="Build- and locale-pinned export URL")


class LocalizationProvenance(_Model):
    source: str
    renderer: str = Field(description="Renderer and grammar revision that produced the text")
    tables: list[LocalizationSourceTable]


class TalentLocalizationDocument(_Model):
    schema_version: Literal[1]
    game: str = Field(min_length=1)
    client_build: str = Field(pattern=_BUILD_PATTERN)
    class_token: str = Field(
        pattern=r"^[A-Z]+$", description="Locale-independent class token, e.g. WARRIOR"
    )
    locale: Locale
    talent_data_content_hash: Sha256 = Field(
        pattern=_SHA256_PATTERN,
        description="contentHash of the talent-data.v1 document this overlay belongs to",
    )
    tooltips_content_hash: Sha256 = Field(
        pattern=_SHA256_PATTERN,
        description="contentHash of the English talent-tooltips.v1 sidecar the rendered "
        "descriptions were checked against",
    )
    entries: list[LocalizedEntry] = Field(
        description="One entry per talent entry of the class, ordered by entryId"
    )
    content_hash: Sha256 = Field(
        pattern=_SHA256_PATTERN,
        description="sha256 of the canonical JSON of every field except contentHash and provenance",
    )
    provenance: LocalizationProvenance

    def to_json(self) -> str:
        return self.model_dump_json(by_alias=True, indent=2) + "\n"


def expected_content_hash(document: TalentLocalizationDocument) -> Sha256:
    return content_hash(hashed_payload(document.model_dump(mode="json", by_alias=True)))


def rank_text(rank: LocalizedRank) -> str:
    return "".join(segment.text for segment in rank.segments)
