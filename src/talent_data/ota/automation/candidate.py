"""Strict binding of unmerged data to trusted, preparation-only producers."""

import json
from typing import Literal, Self

from pydantic import Field, field_validator, model_validator

from talent_data.ota.automation.requests import ArtifactPin, RunPin, _Model
from talent_data.ota.models import BUILD_PATTERN, COMMIT_PATTERN, RELEASE_ID_PATTERN, SHA256_PATTERN
from talent_data.ota.verify import sha256_ref


class VersionedModel(_Model):
    schema_version: Literal[1]

    @field_validator("schema_version", mode="before")
    @classmethod
    def integer_version(cls, value: object) -> object:
        if type(value) is not int:
            raise ValueError("schemaVersion must be an integer")
        return value


class CandidateBinding(VersionedModel):
    kind: Literal["ota-candidate-binding"]
    policy: Literal["owner-merge-v1"]
    repository: Literal["AlexeyKravtsov95/TalentForge"]
    pull_number: int = Field(gt=0)
    game: Literal["forever"]
    build: str = Field(pattern=BUILD_PATTERN)
    revision: int = Field(ge=1)
    source_sha: str = Field(pattern=COMMIT_PATTERN)
    source_tree: str = Field(pattern=COMMIT_PATTERN)
    preparation_head_sha: str = Field(pattern=COMMIT_PATTERN)
    base_sha: str = Field(pattern=COMMIT_PATTERN)
    tooling_sha: str = Field(pattern=COMMIT_PATTERN)
    preparation_ci: RunPin
    staging_run: RunPin
    package_run: RunPin
    candidate_artifact: ArtifactPin
    base_release_id: str = Field(pattern=RELEASE_ID_PATTERN)
    base_manifest_sha256: str = Field(pattern=SHA256_PATTERN)

    @model_validator(mode="after")
    def producer_context(self) -> Self:
        if self.preparation_head_sha != self.source_sha:
            raise ValueError("preparation head must be the frozen candidate source")
        for pin, workflow, sha in (
            (self.preparation_ci, ".github/workflows/ci.yml", self.source_sha),
            (self.staging_run, ".github/workflows/ota-staging-verify.yml", self.tooling_sha),
            (self.package_run, ".github/workflows/ota-release-package.yml", self.tooling_sha),
        ):
            if pin.workflow_path != workflow or pin.head_sha != sha:
                raise ValueError("producer workflow or source/tooling identity differs")
        return self


def binding_digest(binding: CandidateBinding) -> str:
    raw = json.dumps(
        binding.model_dump(mode="json", by_alias=True),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode()
    return sha256_ref(raw)
