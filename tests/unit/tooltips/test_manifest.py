"""The generated capture manifest is exactly the canonical dataset - no hand-kept ids."""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from talent_data.cli.main import main
from talent_data.models.talents import TalentDataDocument, content_hash, semantic_payload
from talent_data.pipeline import PipelineError, read_document
from talent_data.tooltips.manifest import (
    CaptureManifest,
    addon_manifest_problems,
    build_capture_manifest,
    lua_string,
    render_lua,
)
from tests.conftest import ALL_CLASSES
from tests.helpers import NEW_BUILD
from tests.unit.tooltips.conftest import (
    ADDON_DIR,
    EXPECTED_ENTRIES,
    EXPECTED_RECORDS,
    GAME,
    GENERATED_MANIFEST,
    ROOT,
)

CANONICAL_DATASET = ROOT / "data" / "normalized" / "forever" / str(NEW_BUILD)

# One generated entry line. Parsed here independently of the generator's own renderer.
_ENTRY_LINE = re.compile(
    r'^        \{ classToken = "(?P<classToken>[A-Z]+)", treeId = (?P<treeId>\d+), '
    r"nodeId = (?P<nodeId>\d+), entryId = (?P<entryId>\d+), spellId = (?P<spellId>\d+), "
    r'name = "(?P<name>(?:[^"\\]|\\.)*)", maxRank = (?P<maxRank>\d+) \},$'
)


@pytest.fixture(scope="module")
def documents() -> list[TalentDataDocument]:
    return [read_document(CANONICAL_DATASET / f"{key}.json") for key in ALL_CLASSES]


def dataset_entries() -> dict[int, dict[str, object]]:
    """Every talent entry straight from the committed JSON, keyed by entry id."""
    entries: dict[int, dict[str, object]] = {}
    for key in ALL_CLASSES:
        doc = json.loads((CANONICAL_DATASET / f"{key}.json").read_text(encoding="utf-8"))
        for tree in doc["trees"]:
            for node in tree["nodes"]:
                for entry in node["entries"]:
                    assert entry["id"] not in entries
                    entries[entry["id"]] = {
                        "classToken": doc["class"]["token"],
                        "treeId": tree["id"],
                        "nodeId": node["id"],
                        "entryId": entry["id"],
                        "spellId": entry["spellId"],
                        "name": entry["name"],
                        "maxRank": entry["maxRank"],
                    }
    return entries


def generated_entries() -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for line in GENERATED_MANIFEST.read_text(encoding="utf-8").splitlines():
        match = _ENTRY_LINE.match(line)
        if match:
            row: dict[str, object] = {
                k: int(v) if v.isdigit() and k not in ("name", "classToken") else v
                for k, v in match.groupdict().items()
            }
            row["name"] = str(row["name"]).replace('\\"', '"').replace("\\\\", "\\")
            rows.append(row)
    return rows


def test_generated_manifest_contains_all_469_canonical_entries() -> None:
    rows = generated_entries()
    expected = dataset_entries()
    assert len(rows) == len(expected) == EXPECTED_ENTRIES
    # No missing entries, no extra entries.
    assert {row["entryId"] for row in rows} == set(expected)


def test_every_class_is_present() -> None:
    tokens = {row["classToken"] for row in generated_entries()}
    assert tokens == set(GAME.dataset_class_tokens)
    assert len(tokens) == 9


def test_every_entry_id_is_unique() -> None:
    ids = [row["entryId"] for row in generated_entries()]
    assert len(ids) == len(set(ids))


def test_max_rank_matches_talent_data() -> None:
    expected = dataset_entries()
    rows = generated_entries()
    assert all(row["maxRank"] == expected[row["entryId"]]["maxRank"] for row in rows)  # type: ignore[index]
    assert sum(int(row["maxRank"]) for row in rows) == EXPECTED_RECORDS  # type: ignore[call-overload]


def test_tree_node_spell_and_name_identity_matches_talent_data() -> None:
    expected = dataset_entries()
    for row in generated_entries():
        assert row == expected[row["entryId"]]  # type: ignore[index]


def test_manifest_ordering_is_deterministic(documents: list[TalentDataDocument]) -> None:
    manifest = build_capture_manifest(GAME, NEW_BUILD, documents)
    assert [d.class_token for d in manifest.datasets] == list(GAME.dataset_class_tokens)
    # Classes in manifest (class id) order, entries by entry id inside a class.
    order = {token: i for i, token in enumerate(GAME.dataset_class_tokens)}
    keys = [(order[e.class_token], e.entry_id) for e in manifest.entries]
    assert keys == sorted(keys)
    # The order in which a tree lists its nodes does not leak into the manifest.
    shuffled = [
        doc.model_copy(
            update={
                "trees": [
                    tree.model_copy(update={"nodes": list(reversed(tree.nodes))})
                    for tree in doc.trees
                ]
            }
        )
        for doc in documents
    ]
    again = build_capture_manifest(GAME, NEW_BUILD, shuffled)
    assert again == manifest
    assert render_lua(again) == render_lua(manifest)


def test_manifest_hash_is_deterministic_and_carried_by_the_lua(
    documents: list[TalentDataDocument], real_manifest: CaptureManifest
) -> None:
    rebuilt = build_capture_manifest(GAME, NEW_BUILD, documents)
    assert rebuilt.manifest_hash == real_manifest.manifest_hash
    assert re.fullmatch(r"sha256:[0-9a-f]{64}", rebuilt.manifest_hash)
    text = GENERATED_MANIFEST.read_text(encoding="utf-8")
    assert f'manifestHash = "{rebuilt.manifest_hash}"' in text
    assert f"entryCount = {EXPECTED_ENTRIES}," in text
    assert f"rankCount = {EXPECTED_RECORDS}," in text
    assert 'clientBuild = "1.60.1.70009"' in text
    assert 'locale = "enUS"' in text
    # Dataset identity: the content hash of every class document.
    for doc in documents:
        assert f'classToken = "{doc.class_.token}", contentHash = "{doc.content_hash}"' in text


def _with_changed_rank(doc: TalentDataDocument) -> TalentDataDocument:
    raw = doc.model_dump(mode="json", by_alias=True)
    raw["trees"][0]["nodes"][0]["entries"][0]["maxRank"] += 1
    raw["contentHash"] = content_hash(semantic_payload(raw))
    return TalentDataDocument.model_validate(raw)


def test_drift_check_detects_dataset_changes(
    documents: list[TalentDataDocument], real_manifest: CaptureManifest
) -> None:
    assert addon_manifest_problems(real_manifest, GENERATED_MANIFEST) == []
    changed = build_capture_manifest(
        GAME, NEW_BUILD, [_with_changed_rank(documents[0]), *documents[1:]]
    )
    assert changed.manifest_hash != real_manifest.manifest_hash
    problems = addon_manifest_problems(changed, GENERATED_MANIFEST)
    assert len(problems) == 1
    assert "out of date" in problems[0]


def test_a_content_hash_change_alone_changes_the_manifest_hash(
    real_manifest: CaptureManifest,
) -> None:
    first = real_manifest.datasets[0]
    other = real_manifest.model_copy(
        update={
            "datasets": (
                first.model_copy(update={"content_hash": "sha256:" + "0" * 64}),
                *real_manifest.datasets[1:],
            )
        }
    )
    assert other.manifest_hash != real_manifest.manifest_hash


def test_check_command_passes_on_the_repository_and_fails_on_drift(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.delenv("TALENT_DATA_ROOT", raising=False)
    monkeypatch.chdir(ROOT)
    assert main(["tooltips", "manifest", "--build", str(NEW_BUILD), "--check"]) == 0
    out = capsys.readouterr().out
    assert f"{EXPECTED_ENTRIES} entries, {EXPECTED_RECORDS} entry/rank tooltips" in out

    # A project whose addon manifest lost one entry is reported, not repaired.
    project = tmp_path / "project"
    (project / "data" / "normalized" / "forever").mkdir(parents=True)
    (project / "data" / "normalized" / "forever" / str(NEW_BUILD)).symlink_to(CANONICAL_DATASET)
    stale = project / "tools" / "wow-tooltip-capture" / "TalentForgeTooltipCapture"
    stale.mkdir(parents=True)
    lines = GENERATED_MANIFEST.read_text(encoding="utf-8").splitlines(keepends=True)
    dropped = next(i for i, line in enumerate(lines) if _ENTRY_LINE.match(line.rstrip("\n")))
    (stale / GENERATED_MANIFEST.name).write_text("".join(lines[:dropped] + lines[dropped + 1 :]))
    monkeypatch.setenv("TALENT_DATA_ROOT", str(project))
    assert main(["tooltips", "manifest", "--build", str(NEW_BUILD), "--check"]) == 1
    assert "out of date" in capsys.readouterr().out
    # Regenerating restores the committed bytes exactly.
    assert main(["tooltips", "manifest", "--build", str(NEW_BUILD)]) == 0
    assert (stale / GENERATED_MANIFEST.name).read_bytes() == GENERATED_MANIFEST.read_bytes()


def test_generated_lua_contains_no_hand_authored_talent_records() -> None:
    lines = GENERATED_MANIFEST.read_text(encoding="utf-8").splitlines()
    assert lines[0] == "-- GENERATED FILE - DO NOT EDIT."
    start = lines.index("    entries = {")
    end = lines.index("    },", start)
    body = lines[start + 1 : end]
    assert len(body) == EXPECTED_ENTRIES
    assert all(_ENTRY_LINE.match(line) for line in body)
    # The addon's own source carries no talent ids at all: no number of 4+ digits except
    # its named tuning constants.
    source = (ADDON_DIR / "TalentForgeTooltipCapture.lua").read_text(encoding="utf-8")
    code = "\n".join(line.split("--", 1)[0] for line in source.splitlines())
    numbers = set(re.findall(r"(?<![\w.])\d{4,}(?![\w.])", code))
    assert numbers == {"12288", "65536", "262144", "4096", "9007199254740992"}


def test_generator_refuses_ambiguous_datasets(documents: list[TalentDataDocument]) -> None:
    with pytest.raises(PipelineError, match="duplicate"):
        build_capture_manifest(GAME, NEW_BUILD, [documents[0], documents[0]])
    with pytest.raises(PipelineError, match="nothing to capture"):
        build_capture_manifest(GAME, NEW_BUILD, [])


def test_lua_string_escapes_everything_lua_would_interpret() -> None:
    assert lua_string("Nature's Grace") == '"Nature\'s Grace"'
    assert lua_string('a"b\\c') == '"a\\"b\\\\c"'
    assert lua_string("a\r\nb\tc\x01") == '"a\\r\\nb\\tc\\001"'
    assert lua_string("naïve 日本語") == '"naïve 日本語"'
