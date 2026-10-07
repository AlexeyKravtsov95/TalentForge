"""talent-visuals.v1 / talent-icons.v1: closed, strict, deterministic, identity-bound."""

from __future__ import annotations

import json
from typing import Any

import pytest
from pydantic import ValidationError

from talent_data.models.visuals import (
    TalentIconRegistry,
    TalentVisualsDocument,
    content_hash,
    expected_content_hash,
    hashed_payload,
)
from talent_data.pipeline import PipelineError
from talent_data.visuals.coverage import IconCoverageReport, build_coverage_report
from talent_data.visuals.pipeline import (
    check_canonical,
    check_visuals_document,
    read_icon_registry,
    read_visuals_document,
)
from talent_data.visuals.resolver import build_visuals_document
from tests.conftest import ALL_CLASSES
from tests.helpers import NEW_BUILD
from tests.unit.visuals.conftest import CANONICAL_VISUALS, COVERAGE_REPORT, GAME


def raw_visuals(class_key: str = "warrior") -> dict[str, Any]:
    return json.loads((CANONICAL_VISUALS / f"{class_key}.json").read_text(encoding="utf-8"))


def raw_registry() -> dict[str, Any]:
    return json.loads((CANONICAL_VISUALS / "icons.json").read_text(encoding="utf-8"))


# --------------------------------------------------------------------------- baseline


def test_committed_visuals_are_consistent_with_the_committed_dataset() -> None:
    """All nine sidecars, the registry and every image file - the offline CI gate."""
    assert check_canonical(GAME, NEW_BUILD) == []


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_committed_sidecar_is_exactly_what_the_real_rows_resolve_to(
    class_key: str, canonical_docs, resolutions
) -> None:
    """Regenerating from the real fixture rows reproduces the committed bytes: the
    sidecars are derived data, deterministic, and nobody hand-edited them."""
    doc = canonical_docs[class_key]
    rebuilt = build_visuals_document(doc, resolutions[doc.class_.token], source_name="wago.tools")
    committed = (CANONICAL_VISUALS / f"{class_key}.json").read_text(encoding="utf-8")
    assert rebuilt.to_json() == committed
    again = build_visuals_document(doc, resolutions[doc.class_.token], source_name="wago.tools")
    assert again.content_hash == rebuilt.content_hash == expected_content_hash(rebuilt)


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_sidecar_identity_and_entry_references(class_key: str, canonical_docs) -> None:
    doc = canonical_docs[class_key]
    visuals = read_visuals_document(CANONICAL_VISUALS / f"{class_key}.json")
    assert (visuals.game, visuals.client_build, visuals.class_token) == (
        "forever",
        str(NEW_BUILD),
        doc.class_.token,
    )
    assert visuals.talent_data_content_hash == doc.content_hash
    talent_entries = {e.id for tree in doc.trees for node in tree.nodes for e in node.entries}
    assert {e.entry_id for e in visuals.entries} == talent_entries
    assert [e.entry_id for e in visuals.entries] == sorted(talent_entries)
    assert check_visuals_document(visuals, doc) == []


def test_content_hash_ignores_provenance_but_not_content() -> None:
    raw = raw_visuals()
    base = content_hash(hashed_payload(raw))
    assert base == raw["contentHash"]
    relabelled = {**raw, "provenance": {**raw["provenance"], "source": "another mirror"}}
    assert content_hash(hashed_payload(relabelled)) == base
    changed = json.loads(json.dumps(raw))
    changed["entries"][0]["iconFileDataId"] += 1
    assert content_hash(hashed_payload(changed)) != base


def test_coverage_report_is_exact(resolutions) -> None:
    report = build_coverage_report(
        game="forever", client_build=str(NEW_BUILD), by_class=resolutions
    )
    assert report.complete
    assert (report.total_entries, report.resolved_entries, report.unresolved_entries) == (
        469,
        469,
        0,
    )
    assert report.distinct_file_data_ids == 358
    assert report.shared_file_data_ids == 86
    assert report.unresolved == []
    assert {c.class_token: c.total_entries for c in report.classes} == {
        "WARRIOR": 53,
        "PALADIN": 50,
        "HUNTER": 52,
        "ROGUE": 53,
        "PRIEST": 54,
        "SHAMAN": 50,
        "MAGE": 54,
        "WARLOCK": 52,
        "DRUID": 51,
    }
    assert sum(s.entry_count for s in report.shared_icons) - len(report.shared_icons) == 469 - 358
    committed = IconCoverageReport.model_validate_json(COVERAGE_REPORT.read_text("utf-8"))
    assert committed == report
    assert COVERAGE_REPORT.read_text("utf-8") == report.to_json()


def test_registry_contains_every_resolved_icon_and_nothing_else(resolutions) -> None:
    registry = read_icon_registry(CANONICAL_VISUALS / "icons.json")
    used = {r.icon_file_data_id for rs in resolutions.values() for r in rs}
    assert {icon.file_data_id for icon in registry.icons} == used
    assert len(registry.icons) == 358
    assert registry.content_hash == expected_content_hash(registry)
    for icon in registry.icons:
        data = (CANONICAL_VISUALS / icon.file).read_bytes()
        assert len(data) == icon.byte_length > 0
        assert (icon.width, icon.height) == (64, 64)
    on_disk = {p.name for p in (CANONICAL_VISUALS / "icons").iterdir()}
    assert on_disk == {f"{icon.file_data_id}.png" for icon in registry.icons}


# --------------------------------------------------------------------------- closed contract


def mutate(raw: dict[str, Any], path: tuple[Any, ...], value: Any) -> dict[str, Any]:
    out = json.loads(json.dumps(raw))
    target = out
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = value
    return out


VISUALS_REJECTED = {
    "unknown top-level field": lambda r: {**r, "atlas": "icons.png"},
    "unknown entry field": lambda r: mutate(r, ("entries", 0, "assetKey"), "132307"),
    "unknown provenance field": lambda r: mutate(r, ("provenance", "url"), "https://x"),
    "missing schemaVersion": lambda r: {k: v for k, v in r.items() if k != "schemaVersion"},
    "missing game": lambda r: {k: v for k, v in r.items() if k != "game"},
    "missing clientBuild": lambda r: {k: v for k, v in r.items() if k != "clientBuild"},
    "missing classToken": lambda r: {k: v for k, v in r.items() if k != "classToken"},
    "missing dataset hash": lambda r: {k: v for k, v in r.items() if k != "talentDataContentHash"},
    "missing entries": lambda r: {k: v for k, v in r.items() if k != "entries"},
    "missing entry field": lambda r: {
        **r,
        "entries": [
            {k: v for k, v in r["entries"][0].items() if k != "displaySpellId"},
            *r["entries"][1:],
        ],
    },
    "missing contentHash": lambda r: {k: v for k, v in r.items() if k != "contentHash"},
    "missing provenance": lambda r: {k: v for k, v in r.items() if k != "provenance"},
    "quoted schemaVersion": lambda r: {**r, "schemaVersion": "1"},
    "wrong schemaVersion": lambda r: {**r, "schemaVersion": 2},
    "quoted entryId": lambda r: mutate(r, ("entries", 0, "entryId"), "130656"),
    "quoted iconFileDataId": lambda r: mutate(r, ("entries", 0, "iconFileDataId"), "132307"),
    "fractional iconFileDataId": lambda r: mutate(r, ("entries", 0, "iconFileDataId"), 132307.5),
    "float-typed iconFileDataId": lambda r: mutate(r, ("entries", 0, "iconFileDataId"), 132307.0),
    "boolean spellId": lambda r: mutate(r, ("entries", 0, "spellId"), True),
    "zero iconFileDataId": lambda r: mutate(r, ("entries", 0, "iconFileDataId"), 0),
    "null iconFileDataId": lambda r: mutate(r, ("entries", 0, "iconFileDataId"), None),
    "negative displaySpellId": lambda r: mutate(r, ("entries", 0, "displaySpellId"), -5),
    "numeric clientBuild": lambda r: {**r, "clientBuild": 70009},
    "not a client build": lambda r: {**r, "clientBuild": "latest"},
    "lowercase class token": lambda r: {**r, "classToken": "warrior"},
    "malformed dataset hash": lambda r: {**r, "talentDataContentHash": "cab5b5"},
    "entries not a list": lambda r: {**r, "entries": {}},
}


@pytest.mark.parametrize("case", sorted(VISUALS_REJECTED))
def test_visuals_document_is_a_closed_strict_contract(case: str) -> None:
    with pytest.raises(ValidationError):
        TalentVisualsDocument.model_validate(VISUALS_REJECTED[case](raw_visuals()))


def test_unsupported_schema_version_is_reported_explicitly(tmp_path) -> None:
    path = tmp_path / "warrior.json"
    path.write_text(json.dumps({**raw_visuals(), "schemaVersion": 2}), encoding="utf-8")
    with pytest.raises(PipelineError, match="unsupported talent-visuals schemaVersion 2"):
        read_visuals_document(path)


REGISTRY_REJECTED = {
    "unknown top-level field": lambda r: {**r, "baseUrl": "https://wago.tools"},
    "unknown icon field": lambda r: mutate(r, ("icons", 0, "url"), "https://x/132089"),
    "quoted fileDataId": lambda r: mutate(r, ("icons", 0, "fileDataId"), "132089"),
    "zero fileDataId": lambda r: mutate(r, ("icons", 0, "fileDataId"), 0),
    "zero byteLength": lambda r: mutate(r, ("icons", 0, "byteLength"), 0),
    "zero width": lambda r: mutate(r, ("icons", 0, "width"), 0),
    "absolute file path": lambda r: mutate(r, ("icons", 0, "file"), "/icons/132089.png"),
    "path traversal": lambda r: mutate(r, ("icons", 0, "file"), "icons/../132089.png"),
    "network url as file": lambda r: mutate(r, ("icons", 0, "file"), "https://x/132089.png"),
    "unsupported image format": lambda r: {**r, "imageFormat": "webp"},
    "unsupported file extension": lambda r: mutate(r, ("icons", 0, "file"), "icons/132089.blp"),
    "unknown source format": lambda r: mutate(r, ("icons", 0, "sourceFormat"), "PNG"),
    "malformed sha256": lambda r: mutate(r, ("icons", 0, "sha256"), "0e7602"),
    "wrong schemaVersion": lambda r: {**r, "schemaVersion": 2},
    "quoted schemaVersion": lambda r: {**r, "schemaVersion": "1"},
    "missing schemaVersion": lambda r: {k: v for k, v in r.items() if k != "schemaVersion"},
    "missing imageFormat": lambda r: {k: v for k, v in r.items() if k != "imageFormat"},
    "missing game": lambda r: {k: v for k, v in r.items() if k != "game"},
    "missing clientBuild": lambda r: {k: v for k, v in r.items() if k != "clientBuild"},
    "missing contentHash": lambda r: {k: v for k, v in r.items() if k != "contentHash"},
    "missing provenance": lambda r: {k: v for k, v in r.items() if k != "provenance"},
    "missing icons": lambda r: {k: v for k, v in r.items() if k != "icons"},
    "missing icon field": lambda r: {
        **r,
        "icons": [{k: v for k, v in r["icons"][0].items() if k != "sourceFormat"}, *r["icons"][1:]],
    },
}


@pytest.mark.parametrize("case", sorted(REGISTRY_REJECTED))
def test_icon_registry_is_a_closed_strict_contract(case: str) -> None:
    with pytest.raises(ValidationError):
        TalentIconRegistry.model_validate(REGISTRY_REJECTED[case](raw_registry()))


def test_missing_schema_version_is_not_reported_as_an_unsupported_version(tmp_path) -> None:
    """A document without ``schemaVersion`` is rejected by both readers, explicitly."""
    visuals = tmp_path / "warrior.json"
    visuals.write_text(
        json.dumps({k: v for k, v in raw_visuals().items() if k != "schemaVersion"}),
        encoding="utf-8",
    )
    with pytest.raises(PipelineError, match="unsupported talent-visuals schemaVersion None"):
        read_visuals_document(visuals)
    registry = tmp_path / "icons.json"
    registry.write_text(
        json.dumps({k: v for k, v in raw_registry().items() if k != "schemaVersion"}),
        encoding="utf-8",
    )
    with pytest.raises(PipelineError, match="unsupported talent-icons schemaVersion None"):
        read_icon_registry(registry)


SCHEMA_DIR = CANONICAL_VISUALS.parents[3] / "docs" / "schema"


def object_definitions(schema: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """The root object and every ``$defs`` object of a JSON Schema, by name."""
    return {"<root>": schema, **schema.get("$defs", {})}


@pytest.mark.parametrize(
    "schema_file", ["talent-visuals.v1.schema.json", "talent-icons.v1.schema.json"]
)
def test_published_schema_requires_every_property_of_every_object(schema_file: str) -> None:
    """Every field is explicit on the wire: nothing in either contract is optional, and
    every object is closed. A model default would silently drop a property from
    ``required`` - this pins the committed schema, which is generated from the models."""
    schema = json.loads((SCHEMA_DIR / schema_file).read_text(encoding="utf-8"))
    for name, definition in object_definitions(schema).items():
        properties = set(definition["properties"])
        assert set(definition["required"]) == properties, (schema_file, name)
        assert definition["additionalProperties"] is False, (schema_file, name)
        assert not [p for p, spec in definition["properties"].items() if "default" in spec], name


def test_constants_are_required_and_emitted_explicitly() -> None:
    visuals = json.loads((SCHEMA_DIR / "talent-visuals.v1.schema.json").read_text("utf-8"))
    icons = json.loads((SCHEMA_DIR / "talent-icons.v1.schema.json").read_text("utf-8"))
    assert "schemaVersion" in visuals["required"]
    assert {"schemaVersion", "imageFormat"} <= set(icons["required"])
    assert visuals["properties"]["schemaVersion"] == {
        "const": 1,
        "title": "Schemaversion",
        "type": "integer",
    }
    assert icons["properties"]["imageFormat"]["const"] == "png"
    # and the writers still put both constants on the wire
    assert raw_visuals()["schemaVersion"] == 1
    assert (raw_registry()["schemaVersion"], raw_registry()["imageFormat"]) == (1, "png")


# --------------------------------------------------------------------------- identity


def checked(raw: dict[str, Any], doc) -> list[str]:
    return check_visuals_document(TalentVisualsDocument.model_validate(raw), doc)


def rehashed(raw: dict[str, Any]) -> dict[str, Any]:
    return {**raw, "contentHash": content_hash(hashed_payload(raw))}


def test_visuals_of_another_game_build_or_class_are_refused(canonical_docs) -> None:
    doc = canonical_docs["warrior"]
    raw = raw_visuals("warrior")
    assert checked(raw, doc) == []
    assert any("game" in p for p in checked(rehashed({**raw, "game": "retail"}), doc))
    other_build = rehashed({**raw, "clientBuild": "1.60.1.69977"})
    assert any("clientBuild" in p for p in checked(other_build, doc))
    assert any("visuals are for MAGE" in p for p in checked(raw_visuals("mage"), doc))


def test_visuals_resolved_for_another_dataset_revision_are_refused(canonical_docs) -> None:
    stale = rehashed({**raw_visuals(), "talentDataContentHash": "sha256:" + "ab" * 32})
    problems = checked(stale, canonical_docs["warrior"])
    assert len(problems) == 1
    assert "visuals were resolved for talent-data" in problems[0]


def test_tampered_document_fails_its_content_hash(canonical_docs) -> None:
    tampered = mutate(raw_visuals(), ("entries", 0, "iconFileDataId"), 132155)
    assert checked(tampered, canonical_docs["warrior"]) == [
        "WARRIOR: contentHash does not match the document (stale or tampered)"
    ]


def test_unknown_missing_and_duplicate_entries_are_refused(canonical_docs) -> None:
    doc = canonical_docs["warrior"]
    raw = raw_visuals()

    unknown = json.loads(json.dumps(raw))
    unknown["entries"].append({**raw["entries"][-1], "entryId": 999_999})
    assert "WARRIOR: visuals reference unknown talent entries [999999]" in checked(
        rehashed(unknown), doc
    )

    missing = {**raw, "entries": raw["entries"][1:]}
    assert f"WARRIOR: talent entries without visuals [{raw['entries'][0]['entryId']}]" in checked(
        rehashed(missing), doc
    )

    duplicated = {**raw, "entries": [raw["entries"][0], *raw["entries"]]}
    assert "WARRIOR: duplicate entryId in entries" in checked(rehashed(duplicated), doc)

    unordered = {**raw, "entries": list(reversed(raw["entries"]))}
    assert "WARRIOR: entries are not ordered by entryId" in checked(rehashed(unordered), doc)

    wrong_spell = mutate(raw, ("entries", 0, "spellId"), 772)
    assert any("dataset says" in p for p in checked(rehashed(wrong_spell), doc))
