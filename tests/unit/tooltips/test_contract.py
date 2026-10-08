"""talent-tooltips.v1: strict, closed, rank-complete."""

from __future__ import annotations

import json
from typing import Any

import pytest
from pydantic import ValidationError

from talent_data.models.tooltips import (
    TalentTooltipsDocument,
    TooltipEntry,
    TooltipRank,
    TooltipSegment,
    expected_content_hash,
)
from tests.unit.tooltips.conftest import CANONICAL_TOOLTIPS, ROOT

SCHEMA = ROOT / "docs" / "schema" / "talent-tooltips.v1.schema.json"


def warrior() -> dict[str, Any]:
    return json.loads((CANONICAL_TOOLTIPS / "warrior.json").read_text(encoding="utf-8"))


def test_committed_sidecars_validate_and_hash() -> None:
    for path in sorted(CANONICAL_TOOLTIPS.glob("*.json")):
        document = TalentTooltipsDocument.model_validate_json(path.read_text(encoding="utf-8"))
        assert document.content_hash == expected_content_hash(document), path.name
        assert document.to_json() == path.read_text(encoding="utf-8"), path.name


@pytest.mark.parametrize(
    "mutate",
    [
        lambda d: d.update(extra=1),
        lambda d: d["entries"][0].update(extra=1),
        lambda d: d["entries"][0]["ranks"][0].update(extra=1),
        lambda d: d["entries"][0].update(entryId=str(d["entries"][0]["entryId"])),
        lambda d: d["entries"][0]["ranks"][0].update(rank=1.0),
        lambda d: d.pop("talentDataContentHash"),
        lambda d: d["entries"][0]["ranks"][0].pop("gaps"),
        lambda d: d.update(schemaVersion=2),
        lambda d: d["entries"][0]["ranks"][0].update(status="approximate"),
        lambda d: d["entries"][0]["ranks"][0]["segments"][0].update(kind="html"),
    ],
)
def test_strict_parsing(mutate) -> None:
    document = warrior()
    mutate(document)
    with pytest.raises(ValidationError):
        TalentTooltipsDocument.model_validate(document)


def test_markup_never_passes_as_text() -> None:
    for text in ("by $s1%", "|cFFFFFFFFAxe|r", "a\r\nb"):
        with pytest.raises(ValidationError, match="template markup"):
            TooltipSegment(kind="text", text=text)
    with pytest.raises(ValidationError):
        TooltipSegment(kind="gap", text="$s1")
    with pytest.raises(ValidationError):
        TooltipSegment(kind="value", text="")


def gap() -> dict[str, str]:
    return {
        "category": "named-variable",
        "classification": "unsupported",
        "fragment": "$rap",
        "token": "$rap",
        "reason": "no source",
    }


@pytest.mark.parametrize(
    ("status", "segments", "gaps"),
    [
        ("resolved", [{"kind": "text", "text": "a"}], [gap()]),
        ("resolved", [{"kind": "gap", "text": ""}], []),
        ("resolved", [], []),
        ("partial", [{"kind": "text", "text": "a"}], []),
        ("partial", [{"kind": "gap", "text": ""}], [gap()]),  # nothing to show
        ("partial", [{"kind": "text", "text": "a"}, {"kind": "gap", "text": ""}], [gap(), gap()]),
        ("unresolved", [{"kind": "text", "text": "a"}], [gap()]),
        ("unresolved", [], []),
    ],
)
def test_status_matches_segments_and_gaps(status, segments, gaps) -> None:
    with pytest.raises(ValidationError):
        TooltipRank.model_validate(
            {"rank": 1, "status": status, "segments": segments, "gaps": gaps}
        )


def test_every_rank_exactly_once_in_order() -> None:
    entry = warrior()["entries"][0]
    TooltipEntry.model_validate(entry)
    for ranks in (entry["ranks"][:1], entry["ranks"][::-1], [*entry["ranks"], entry["ranks"][0]]):
        with pytest.raises(ValidationError, match="ranks must be exactly"):
            TooltipEntry.model_validate({**entry, "ranks": ranks})


def test_schema_closes_every_object_and_requires_every_property() -> None:
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    objects = [schema, *schema["$defs"].values()]
    for obj in objects:
        if obj.get("type") == "object":
            assert obj.get("additionalProperties") is False, obj.get("title")
            assert set(obj["required"]) == set(obj["properties"]), obj.get("title")
