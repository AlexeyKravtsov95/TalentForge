"""Talent tooltips - the rendered-description sidecar contract (schema v1).

``talent-tooltips.v1`` is one public, versioned, closed document per class and client build.
It holds, for every talent entry of the matching ``talent-data.v1`` document and for every
rank ``1..maxRank``, the description the client tooltip template renders to - as plain
presentation segments, never as template markup. ``talent-data.v1`` is untouched: the raw
``descriptionTemplate`` stays there, and allocation never depends on this sidecar.

A rank is in exactly one of three states:

* ``resolved``   - every construct of the template was evaluated from build-pinned client
  data; ``segments`` is the complete description and ``gaps`` is empty;
* ``partial``    - the text could be laid out but some constructs could not be evaluated
  honestly (a value that depends on the character, a source this build does not prove,
  missing client rows); each such construct is one ``gap`` segment (empty text) and is
  explained, in the same order, by an entry of ``gaps``;
* ``unresolved`` - nothing can be shown (the template uses syntax the renderer does not
  know, so even the surrounding text cannot be trusted); ``segments`` is empty and
  ``gaps`` says why.

Segments carry no markup: colour codes are dropped, line breaks are ``\\n`` inside text
segments, and a text or value segment never contains ``$``, ``|`` or ``\\r``. A ``value``
segment is one evaluated number, duration or plural word, so consumers can compare ranks
value by value. Everything is deterministic - no timestamp - so regenerating from the same
inputs yields byte-identical files.

JSON uses camelCase; Python uses snake_case. Parsing is strict: unknown fields, missing
fields and wrong wire types are rejected. No field has a default.
"""

from __future__ import annotations

from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

from talent_data.models.visuals import Sha256, content_hash, hashed_payload

TOOLTIPS_SCHEMA_VERSION: Literal[1] = 1

_SHA256_PATTERN = r"^sha256:[0-9a-f]{64}$"
_BUILD_PATTERN = r"^\d+\.\d+\.\d+\.\d+$"

# Characters that only exist in template markup. Their presence in a rendered segment means
# raw template syntax leaked into presentation data.
MARKUP_CHARACTERS = ("$", "|", "\r")

SegmentKind = Literal["text", "value", "gap"]
RankStatus = Literal["resolved", "partial", "unresolved"]
GapClassification = Literal["contextDependent", "unsupported", "missingData"]
# The syntax categories of docs/research/forever-tooltip-template-inventory.md.
TokenCategory = Literal[
    "local-effect-value",
    "local-periodic-total",
    "local-radius",
    "local-period",
    "local-duration",
    "local-proc",
    "cross-spell-effect-value",
    "cross-spell-radius",
    "cross-spell-period",
    "cross-spell-duration",
    "cross-spell-proc",
    "scaled-value",
    "arithmetic",
    "named-variable",
    "description-variable",
    "plural",
    "conditional",
    "nested-tooltip",
    "color",
    "line-break",
    "unclassified",
]


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        frozen=True,
        extra="forbid",
        strict=True,
    )


class TooltipSegment(_Model):
    kind: SegmentKind = Field(
        description="text: literal template text; value: one evaluated value (number, "
        "duration or plural word); gap: a construct that could not be evaluated (see gaps)"
    )
    text: str = Field(description="Plain text; empty exactly for gap segments")

    @model_validator(mode="after")
    def _plain(self) -> Self:
        if self.kind == "gap":
            if self.text:
                raise ValueError("a gap segment has empty text")
        else:
            if not self.text:
                raise ValueError(f"a {self.kind} segment has text")
            leaked = [c for c in MARKUP_CHARACTERS if c in self.text]
            if leaked:
                raise ValueError(f"{self.kind} segment contains template markup {leaked!r}")
        return self


class TooltipGap(_Model):
    category: TokenCategory = Field(description="Syntax category of the root-cause token")
    classification: GapClassification = Field(
        description="contextDependent: needs character/client state a planner does not have; "
        "unsupported: the meaning is not proven for this build; missingData: a referenced "
        "client row does not exist in this build"
    )
    fragment: str = Field(min_length=1, description="The template text this gap stands for")
    token: str = Field(min_length=1, description="The token that could not be evaluated")
    reason: str = Field(min_length=1)


class TooltipRank(_Model):
    rank: int = Field(ge=1)
    status: RankStatus
    segments: list[TooltipSegment]
    gaps: list[TooltipGap] = Field(
        description="One per gap segment, in order; for an unresolved rank, why nothing is shown"
    )

    @model_validator(mode="after")
    def _consistent(self) -> Self:
        gap_segments = sum(1 for s in self.segments if s.kind == "gap")
        shown = [s for s in self.segments if s.kind != "gap"]
        if self.status == "resolved":
            if self.gaps or gap_segments or not self.segments:
                raise ValueError("a resolved rank has segments and no gaps")
        elif self.status == "partial":
            if not self.gaps or gap_segments != len(self.gaps) or not shown:
                raise ValueError("a partial rank has text, and one gap entry per gap segment")
        elif self.segments or not self.gaps:
            raise ValueError("an unresolved rank has no segments and at least one gap")
        return self


class TooltipEntry(_Model):
    entry_id: int = Field(gt=0, description="TalentEntry.id of the matching talent-data.v1")
    definition_id: int = Field(gt=0, description="TraitDefinition.ID")
    spell_id: int = Field(gt=0, description="TraitDefinition.SpellID (= TalentEntry.spellId)")
    max_rank: int = Field(ge=1, description="TalentEntry.maxRank")
    ranks: list[TooltipRank] = Field(description="Exactly ranks 1..maxRank, in order")

    @model_validator(mode="after")
    def _every_rank(self) -> Self:
        if [r.rank for r in self.ranks] != list(range(1, self.max_rank + 1)):
            raise ValueError(f"entry {self.entry_id}: ranks must be exactly 1..{self.max_rank}")
        return self


class TooltipsProvenance(_Model):
    source: str
    tables: list[str]
    renderer: str = Field(description="Renderer and grammar revision that produced the text")


class TalentTooltipsDocument(_Model):
    schema_version: Literal[1]
    game: str = Field(min_length=1)
    client_build: str = Field(pattern=_BUILD_PATTERN)
    class_token: str = Field(
        pattern=r"^[A-Z]+$", description="Locale-independent class token, e.g. WARRIOR"
    )
    talent_data_content_hash: Sha256 = Field(
        pattern=_SHA256_PATTERN,
        description="contentHash of the talent-data.v1 document these descriptions were "
        "rendered for; a sidecar is only valid next to exactly that document",
    )
    entries: list[TooltipEntry] = Field(
        description="One entry per talent entry of the class, ordered by entryId"
    )
    content_hash: Sha256 = Field(
        pattern=_SHA256_PATTERN,
        description="sha256 of the canonical JSON of every field except contentHash and provenance",
    )
    provenance: TooltipsProvenance

    def to_json(self) -> str:
        return self.model_dump_json(by_alias=True, indent=2) + "\n"


def expected_content_hash(document: TalentTooltipsDocument) -> Sha256:
    return content_hash(hashed_payload(document.model_dump(mode="json", by_alias=True)))


def rank_text(rank: TooltipRank) -> str:
    """The rendered description of a rank as one string (gaps contribute nothing)."""
    return "".join(segment.text for segment in rank.segments)
