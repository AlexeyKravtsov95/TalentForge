"""Internal release requests; these do not change the public OTA wire contracts."""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel

from talent_data.ota.models import (
    BUILD_PATTERN,
    COMMIT_PATTERN,
    GAME_PATTERN,
    RELEASE_ID_PATTERN,
    SHA256_PATTERN,
)
from talent_data.ota.verify import parse_strict, release_sort_key


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, frozen=True, extra="forbid", strict=True
    )


class RunPin(_Model):
    workflow_path: str = Field(min_length=1, max_length=200)
    run_id: int = Field(gt=0)
    attempt: int = Field(gt=0)
    head_sha: str = Field(pattern=COMMIT_PATTERN)


class ArtifactPin(_Model):
    artifact_id: int = Field(gt=0)
    name: str = Field(min_length=1, max_length=200)
    archive_sha256: str = Field(pattern=SHA256_PATTERN)


class ReleaseRequest(_Model):
    schema_version: Literal[1]
    repository: str = Field(pattern=r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    game: str = Field(pattern=GAME_PATTERN)
    build: str = Field(pattern=BUILD_PATTERN)
    revision: int = Field(ge=1, le=999999)
    release_id: str = Field(pattern=RELEASE_ID_PATTERN)
    source_commit: str = Field(pattern=COMMIT_PATTERN)
    # Preserve the spelling as well as the instant: no new timestamp under old approval.
    created_at: str = Field(
        pattern=r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?(?:Z|\+00:00)$"
    )
    manifest_sha256: str = Field(pattern=SHA256_PATTERN)
    base_release_id: str = Field(pattern=RELEASE_ID_PATTERN)
    base_manifest_sha256: str = Field(pattern=SHA256_PATTERN)
    staging_run: RunPin
    package_run: RunPin
    artifact: ArtifactPin

    @field_validator("schema_version", mode="before")
    @classmethod
    def integer_version(cls, value: object) -> object:
        if type(value) is not int:
            raise ValueError("schemaVersion must be an integer")
        return value

    @field_validator("created_at")
    @classmethod
    def utc_timestamp(cls, value: str) -> str:
        stamp = datetime.fromisoformat(value)
        if "T" not in value or stamp.tzinfo is None or stamp.utcoffset() != timedelta(0):
            raise ValueError("createdAt must be an RFC 3339 UTC timestamp")
        return value

    @model_validator(mode="after")
    def consistent_identity(self) -> Self:
        if self.release_id != f"{self.build}-r{self.revision}":
            raise ValueError("releaseId must name build and revision")
        if release_sort_key(self.base_release_id) >= release_sort_key(self.release_id):
            raise ValueError("baseReleaseId must precede releaseId")
        return self


def parse_request(raw: bytes) -> ReleaseRequest:
    return parse_strict(ReleaseRequest, raw)
