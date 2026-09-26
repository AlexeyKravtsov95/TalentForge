"""Dataset manifest - machine-readable index of one build's complete normalized dataset.

Schema v1. The manifest lets a consumer (e.g. a future mobile updater) answer, without
loading every class document: which client build this dataset is for, which classes it
contains, which file holds each class, the semantic ``contentHash`` of that file's payload,
and whether the class document passed validation (with warnings counted by stable code).

The manifest itself is only written for a *complete, error-free* batch run
(see ``pipeline.write_batch``): a partial dataset is never published as valid.
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

MANIFEST_SCHEMA_VERSION: Literal[1] = 1


class _Model(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)


class ManifestValidation(_Model):
    errors: int = Field(
        description="0 for every published class; a manifest is only written for an "
        "error-free batch"
    )
    warnings: int
    warnings_by_code: dict[str, int] = Field(
        default_factory=dict,
        description="Warning count per stable issue code (see validation/issues.py)",
    )


class ManifestClassEntry(_Model):
    token: str = Field(description="Locale-independent class token, e.g. WARRIOR")
    file: str = Field(description="File name of the class document, relative to the manifest")
    tree_ids: list[int]
    content_hash: str = Field(
        description="contentHash of the class document: sha256 over its semantic payload. "
        "Changes whenever the class's talent data changes."
    )
    validation: ManifestValidation


class DatasetManifest(_Model):
    schema_version: Literal[1] = MANIFEST_SCHEMA_VERSION
    game: str
    client_build: str
    generated_at: datetime
    source: str = Field(description="Name of the DB2 source the dataset was built from")
    classes: list[ManifestClassEntry] = Field(
        description="Ordered by class id - deterministic for a given build"
    )

    def to_json(self) -> str:
        return self.model_dump_json(by_alias=True, indent=2) + "\n"
