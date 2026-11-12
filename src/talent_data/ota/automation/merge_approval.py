"""A conditional approval becomes effective only after independently verified owner merge."""

import json
import re
from typing import Literal, Self

from pydantic import Field, model_validator

from talent_data.ota.automation.candidate import VersionedModel
from talent_data.ota.models import COMMIT_PATTERN, RELEASE_ID_PATTERN, SHA256_PATTERN
from talent_data.ota.verify import OtaError, parse_strict

MODE_LINE = "- Authorization mode: owner-merge-v1"
FENCE = "```owner-merge-v1"


class OwnerMergeRecord(VersionedModel):
    kind: Literal["ota-owner-merge-approval"]
    policy: Literal["owner-merge-v1"]
    repository: Literal["AlexeyKravtsov95/TalentForge"]
    pull_number: int = Field(gt=0)
    release_id: str = Field(pattern=RELEASE_ID_PATTERN)
    source_sha: str = Field(pattern=COMMIT_PATTERN)
    binding_sha256: str = Field(pattern=SHA256_PATTERN)
    request_digest: str = Field(pattern=SHA256_PATTERN)
    accepted_flags: tuple[Literal["G5", "G7"], ...]
    revoked: bool

    @model_validator(mode="after")
    def unique_flags(self) -> Self:
        if len(set(self.accepted_flags)) != len(self.accepted_flags):
            raise ValueError("duplicate accepted flags")
        return self


def parse_merge_record(text: str) -> OwnerMergeRecord | None:
    if "- Authorization mode:" not in text and FENCE not in text:
        return None
    if text.splitlines().count(MODE_LINE) != 1 or text.count(FENCE) != 1:
        raise ValueError("ambiguous or unsupported merge authorization")
    match = re.search(r"^```owner-merge-v1\n(.+?)\n```$", text, re.M | re.S)
    if match is None:
        raise ValueError("missing conditional authorization payload")
    try:
        return parse_strict(OwnerMergeRecord, match.group(1).encode())
    except OtaError as exc:
        raise ValueError("invalid conditional authorization payload") from exc


def render_merge_record(record: OwnerMergeRecord) -> str:
    raw = json.dumps(record.model_dump(mode="json", by_alias=True), indent=2, ensure_ascii=False)
    return (
        f"# OTA release authorization — {record.release_id}\n\n{MODE_LINE}\n\n"
        "Слияние этого MR владельцем разрешает публикацию указанного пакета для live-серверов.\n"
        "До проверенного слияния разрешение не действует.\n\n"
        f"{FENCE}\n{raw}\n```\n"
    )
