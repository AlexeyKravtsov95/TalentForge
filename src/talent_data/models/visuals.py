"""Talent visuals - the presentation sidecar contracts (schema v1).

Two public, versioned, closed documents that live next to (never inside) ``talent-data.v1``:

* ``talent-visuals.v1`` - one document per class and client build, mapping every talent
  entry of the matching ``talent-data.v1`` document to the FileDataID of its icon;
* ``talent-icons.v1`` - one registry per client build, mapping every FileDataID used by
  that build's sidecars to the bundled image file, with integrity facts for both the
  converted image and the client file it was converted from.

Icons are media, not allocation semantics: ``talent-data.v1`` is untouched and stays
loadable without any of this. Both documents are deterministic - there is no timestamp, so
regenerating from the same inputs yields byte-identical files.

JSON uses camelCase; Python uses snake_case. Parsing is strict: unknown fields, missing
fields and wrong wire types (``"123"`` for a number, ``1.0`` for an integer) are rejected.

Every field is explicit on the wire. No model field has a default - not even the constant
``schemaVersion`` / ``imageFormat`` - because a default would make the generated JSON
Schema stop requiring the property. Writers pass the constants explicitly.
"""

from __future__ import annotations

import hashlib
import json
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

VISUALS_SCHEMA_VERSION: Literal[1] = 1
ICONS_SCHEMA_VERSION: Literal[1] = 1
ICON_IMAGE_FORMAT: Literal["png"] = "png"

# Fields that carry no visual meaning and are excluded from ``contentHash``.
_UNHASHED_FIELDS = frozenset({"contentHash", "provenance"})

Sha256 = str  # "sha256:<64 lowercase hex>"
_SHA256_PATTERN = r"^sha256:[0-9a-f]{64}$"
_BUILD_PATTERN = r"^\d+\.\d+\.\d+\.\d+$"


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        frozen=True,
        extra="forbid",
        strict=True,
    )


# --------------------------------------------------------------------------- talent-visuals.v1


class TalentVisualEntry(_Model):
    entry_id: int = Field(gt=0, description="TalentEntry.id of the matching talent-data.v1")
    definition_id: int = Field(gt=0, description="TraitDefinition.ID")
    spell_id: int = Field(gt=0, description="TraitDefinition.SpellID (= TalentEntry.spellId)")
    display_spell_id: int = Field(
        gt=0,
        description="The spell the client presents: TraitDefinition.VisibleSpellID when "
        "non-zero, otherwise TraitDefinition.SpellID",
    )
    icon_file_data_id: int = Field(
        gt=0,
        description="SpellMisc.SpellIconFileDataID of displaySpellId; key into the build's "
        "talent-icons.v1 registry",
    )


class VisualsProvenance(_Model):
    source: str
    tables: list[str]
    icon_column: str = Field(description="DB2 column the icon FileDataID was read from")


class TalentVisualsDocument(_Model):
    schema_version: Literal[1]
    game: str = Field(min_length=1)
    client_build: str = Field(pattern=_BUILD_PATTERN)
    class_token: str = Field(
        pattern=r"^[A-Z]+$", description="Locale-independent class token, e.g. WARRIOR"
    )
    talent_data_content_hash: Sha256 = Field(
        pattern=_SHA256_PATTERN,
        description="contentHash of the talent-data.v1 document these visuals were resolved "
        "for; a sidecar is only valid next to exactly that document",
    )
    entries: list[TalentVisualEntry] = Field(
        description="One entry per talent entry of the class, ordered by entryId"
    )
    content_hash: Sha256 = Field(
        pattern=_SHA256_PATTERN,
        description="sha256 of the canonical JSON of every field except contentHash and provenance",
    )
    provenance: VisualsProvenance

    def to_json(self) -> str:
        return self.model_dump_json(by_alias=True, indent=2) + "\n"


# --------------------------------------------------------------------------- talent-icons.v1


class IconAsset(_Model):
    file_data_id: int = Field(gt=0)
    file: str = Field(
        pattern=r"^icons/[1-9][0-9]*\.png$",
        description="Bundled image, relative to the registry: icons/<fileDataId>.png",
    )
    width: int = Field(gt=0)
    height: int = Field(gt=0)
    byte_length: int = Field(gt=0)
    sha256: Sha256 = Field(pattern=_SHA256_PATTERN, description="sha256 of the bundled image")
    source_format: Literal["BLP2"] = Field(description="Container of the client file")
    source_byte_length: int = Field(gt=0)
    source_sha256: Sha256 = Field(
        pattern=_SHA256_PATTERN, description="sha256 of the client file as retrieved"
    )


class IconsProvenance(_Model):
    source: str
    retrieval: str = Field(description="How the client files were obtained (never a URL)")
    converter: str = Field(description="Tool that produced the bundled images")


class TalentIconRegistry(_Model):
    schema_version: Literal[1]
    game: str = Field(min_length=1)
    client_build: str = Field(pattern=_BUILD_PATTERN)
    image_format: Literal["png"]
    icons: list[IconAsset] = Field(description="Ordered by fileDataId, one per FileDataID")
    content_hash: Sha256 = Field(pattern=_SHA256_PATTERN)
    provenance: IconsProvenance

    def to_json(self) -> str:
        return self.model_dump_json(by_alias=True, indent=2) + "\n"


# --------------------------------------------------------------------------- hashing


def hashed_payload(document: dict[str, Any]) -> dict[str, Any]:
    """The part of a visuals/icons JSON document that ``contentHash`` covers."""
    return {k: v for k, v in document.items() if k not in _UNHASHED_FIELDS}


def content_hash(payload: dict[str, Any]) -> Sha256:
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def sha256_of(data: bytes) -> Sha256:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def expected_content_hash(document: TalentVisualsDocument | TalentIconRegistry) -> Sha256:
    return content_hash(hashed_payload(document.model_dump(mode="json", by_alias=True)))
