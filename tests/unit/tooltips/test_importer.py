"""The strict SavedVariables importer, on synthetic captures (no game client involved)."""

from __future__ import annotations

import base64
import copy
import json
from pathlib import Path
from typing import Any

import pytest

from talent_data.cli.main import main
from talent_data.pipeline import PipelineError
from talent_data.tooltips import errors
from talent_data.tooltips.capture import (
    ClientTooltipCapture,
    import_client_capture,
    read_client_capture,
)
from talent_data.tooltips.saved_variables import parse_saved_variables
from tests.helpers import NEW_BUILD
from tests.unit.tooltips.conftest import (
    ROOT,
    payload,
    record,
    saved_json,
    saved_variables,
    small_manifest,
    tooltip,
)

MANIFEST = small_manifest()
REND, MORTAL_STRIKE, PYROBLAST = MANIFEST.entries


def import_payload(value: Any) -> ClientTooltipCapture:
    return import_client_capture(saved_json(value), MANIFEST)


# --------------------------------------------------------------------------- acceptance


def test_valid_base64_json_imports() -> None:
    capture = import_payload(payload(MANIFEST))
    assert capture.capture_format_version == 1
    assert (capture.game, capture.client_build, capture.locale) == (
        "forever",
        "1.60.1.70009",
        "enUS",
    )
    assert capture.manifest_hash == MANIFEST.manifest_hash
    assert capture.entry_count == 3
    assert capture.record_count == 6
    # Deterministic order: class / entry / rank, whatever order the payload used.
    assert [(r.entry_id, r.rank) for r in capture.records] == [
        (130686, 1),
        (130686, 2),
        (130686, 3),
        (130671, 1),
        (130500, 1),
        (130500, 2),
    ]
    # Every record stays traceable to the dataset.
    first = capture.records[0]
    assert (first.class_token, first.tree_id, first.node_id, first.spell_id) == (
        "WARRIOR",
        1117,
        105956,
        12286,
    )
    assert first.tooltip == tooltip(REND, 1)


def test_payload_record_order_does_not_matter() -> None:
    forward = payload(MANIFEST)
    backward = payload(MANIFEST, records=list(reversed(forward["records"])))
    assert import_payload(backward) == import_payload(forward)


def test_wrapper_key_order_chunking_and_line_endings_do_not_matter() -> None:
    data = json.dumps(payload(MANIFEST), separators=(",", ":")).encode()
    reference = import_client_capture(saved_variables(data), MANIFEST)
    one_chunk = saved_variables(data, chunk_bytes=len(data))
    assert import_client_capture(one_chunk, MANIFEST) == reference
    assert import_client_capture(one_chunk.replace("\n", "\r\n"), MANIFEST) == reference


# --------------------------------------------------------------------------- wrapper

VALID_WRAPPER = saved_json(payload(MANIFEST))


@pytest.mark.parametrize(
    "text",
    [
        "",
        "not lua at all",
        "SomeOtherAddonDB = {\n}\n",
        VALID_WRAPPER.replace("TalentForgeTooltipCaptureDB", "TalentForgeTooltipCaptureDB2"),
        VALID_WRAPPER + "\nSecond = {\n}\n",
        VALID_WRAPPER.replace('["formatVersion"] = 1,', ""),
        VALID_WRAPPER.replace('["formatVersion"] = 1,', '["formatVersion"] = "1",'),
        VALID_WRAPPER.replace('["formatVersion"] = 1,', '["formatVersion"] = 1.0,'),
        VALID_WRAPPER.replace(
            '["formatVersion"] = 1,', '["formatVersion"] = 1,\n\t["formatVersion"] = 1,'
        ),
        VALID_WRAPPER.replace('["formatVersion"] = 1,', '["formatVersion"] = 1,\n\t["x"] = 1,'),
        VALID_WRAPPER.replace('["payloadBytes"]', '["payloadSize"]'),
        VALID_WRAPPER.replace('\t["payloadBase64"] = {', '\t["payloadBase64"] = {\n\t\t{},'),
        VALID_WRAPPER.replace('\t["payloadBase64"] = {', '\t["payloadBase64"] = {\n\t\t12,'),
        VALID_WRAPPER.replace('", -- [1]', '\\n", -- [1]'),
        VALID_WRAPPER.replace('["formatVersion"] = 1,', '["formatVersion"] = os.time(),'),
        VALID_WRAPPER.rstrip("\n").removesuffix("}"),
        'TalentForgeTooltipCaptureDB = {\n\t["formatVersion"] = 1,\n\t["payloadBytes"] = 3,\n}\n',
    ],
)
def test_malformed_wrapper_is_rejected(text: str) -> None:
    with pytest.raises(errors.MalformedSavedVariablesError):
        import_client_capture(text, MANIFEST)


def test_a_cleared_or_never_captured_variable_is_its_own_failure() -> None:
    with pytest.raises(errors.NoStoredCaptureError):
        import_client_capture("\nTalentForgeTooltipCaptureDB = nil\n", MANIFEST)


def test_unsupported_saved_variables_format_is_rejected() -> None:
    text = VALID_WRAPPER.replace('["formatVersion"] = 1,', '["formatVersion"] = 2,')
    with pytest.raises(errors.UnsupportedCaptureFormatError):
        import_client_capture(text, MANIFEST)


def test_the_wrapper_parser_never_evaluates_lua() -> None:
    # Nothing that looks like code survives the tokenizer, whatever it would do.
    for text in (
        'TalentForgeTooltipCaptureDB = loadstring("x")()',
        "TalentForgeTooltipCaptureDB = { [1] = 1 }",
        'TalentForgeTooltipCaptureDB = { ["formatVersion"] = 1 + 0 }',
        "TalentForgeTooltipCaptureDB = { formatVersion = 1 }",
        "TalentForgeTooltipCaptureDB = { [\"formatVersion\"] = 1 } print('x')",
    ):
        with pytest.raises(errors.MalformedSavedVariablesError):
            parse_saved_variables(text)


# --------------------------------------------------------------------------- payload


def test_malformed_base64_is_rejected() -> None:
    for broken in (
        VALID_WRAPPER.replace('", -- [1]', '!", -- [1]'),
        VALID_WRAPPER.replace('", -- [1]', '=", -- [1]'),
    ):
        with pytest.raises(errors.MalformedPayloadError, match="base64"):
            import_client_capture(broken, MANIFEST)


def test_truncated_payload_is_rejected() -> None:
    data = json.dumps(payload(MANIFEST), separators=(",", ":")).encode()
    with pytest.raises(errors.MalformedPayloadError, match="declares"):
        import_client_capture(saved_variables(data, payload_bytes=len(data) + 48), MANIFEST)


@pytest.mark.parametrize(
    "data",
    [
        b"{not json",
        b"[1, 2",
        b'{"captureFormatVersion": 1, "captureFormatVersion": 1}',
        b'{"captureFormatVersion": NaN}',
        b'{"captureFormatVersion": 1e999}',
        b"\xff\xfe\x00",
    ],
)
def test_malformed_json_is_rejected(data: bytes) -> None:
    with pytest.raises(errors.MalformedPayloadError):
        import_client_capture(saved_variables(data), MANIFEST)


@pytest.mark.parametrize("version", [0, 2, 99])
def test_unsupported_capture_format_version_is_rejected(version: int) -> None:
    with pytest.raises(errors.UnsupportedCaptureFormatError):
        import_payload(payload(MANIFEST, captureFormatVersion=version))


@pytest.mark.parametrize("version", ["1", 1.0, None, True])
def test_capture_format_version_wire_type_is_not_coerced(version: object) -> None:
    with pytest.raises(errors.MalformedPayloadError):
        import_payload(payload(MANIFEST, captureFormatVersion=version))


def test_payload_shape_is_closed_and_strict() -> None:
    unknown_field = payload(MANIFEST, surprise=1)
    missing_field = payload(MANIFEST)
    del missing_field["locale"]
    quoted_number = payload(MANIFEST, expectedRecords="6")
    unknown_status = payload(MANIFEST)
    unknown_status["records"][0]["status"] = "probably-fine"
    record_extra = payload(MANIFEST)
    record_extra["records"][0]["note"] = "x"
    nullable_still_required = payload(MANIFEST)
    del nullable_still_required["records"][0]["error"]
    float_rank = payload(MANIFEST)
    float_rank["records"][0]["rank"] = 1.0
    for broken in (
        unknown_field,
        missing_field,
        quoted_number,
        unknown_status,
        record_extra,
        nullable_still_required,
        float_rank,
        [1, 2, 3],
    ):
        with pytest.raises(errors.MalformedPayloadError):
            import_payload(broken)


# --------------------------------------------------------------------------- identity


def test_wrong_client_build_is_rejected() -> None:
    with pytest.raises(errors.ClientBuildMismatchError):
        import_payload(payload(MANIFEST, clientBuild="1.60.1.69977"))
    with pytest.raises(errors.ClientBuildMismatchError):
        import_payload(payload(MANIFEST, clientBuild="1.60.1"))


def test_wrong_locale_is_rejected() -> None:
    with pytest.raises(errors.LocaleMismatchError):
        import_payload(payload(MANIFEST, locale="ruRU"))


def test_wrong_game_is_rejected() -> None:
    with pytest.raises(errors.GameMismatchError):
        import_payload(payload(MANIFEST, game="classic"))


def test_wrong_manifest_hash_is_rejected() -> None:
    with pytest.raises(errors.ManifestHashMismatchError):
        import_payload(payload(MANIFEST, manifestHash="sha256:" + "f" * 64))


def test_capture_from_another_manifest_is_rejected() -> None:
    # Same client, same entries - but the dataset moved on, so the manifest hash did too.
    other = small_manifest(
        datasets=(
            MANIFEST.datasets[0].model_copy(update={"content_hash": "sha256:" + "c" * 64}),
            MANIFEST.datasets[1],
        )
    )
    with pytest.raises(errors.ManifestHashMismatchError):
        import_client_capture(saved_json(payload(other)), MANIFEST)


def test_wrong_dataset_hash_is_rejected() -> None:
    wrong_hash = payload(MANIFEST)
    wrong_hash["datasets"][1]["contentHash"] = "sha256:" + "d" * 64
    missing_class = payload(MANIFEST)
    missing_class["datasets"].pop()
    for broken in (wrong_hash, missing_class):
        with pytest.raises(errors.DatasetHashMismatchError):
            import_payload(broken)


def test_identity_failures_are_all_capture_identity_errors() -> None:
    for kind in (
        errors.GameMismatchError,
        errors.ClientBuildMismatchError,
        errors.LocaleMismatchError,
        errors.ManifestHashMismatchError,
        errors.DatasetHashMismatchError,
    ):
        assert issubclass(kind, errors.CaptureIdentityError)
        assert issubclass(kind, PipelineError)


# --------------------------------------------------------------------------- completeness


def test_incomplete_capture_is_rejected() -> None:
    with pytest.raises(errors.IncompleteCaptureError):
        import_payload(payload(MANIFEST, complete=False))


def test_an_unfinished_capture_without_payload_is_rejected() -> None:
    text = '\nTalentForgeTooltipCaptureDB = {\n\t["formatVersion"] = 1,\n}\n'
    with pytest.raises(errors.IncompleteCaptureError, match="never finished"):
        import_client_capture(text, MANIFEST)


def test_incomplete_capture_reports_what_is_not_final() -> None:
    value = payload(MANIFEST, complete=False)
    value["records"][1].update(status="nil", tooltip=None)
    value["records"][5].update(status="error", tooltip=None, error="boom")
    with pytest.raises(errors.IncompleteCaptureError) as caught:
        import_payload(value)
    assert caught.value.not_final_by_status == {"nil": 1, "error": 1}
    assert caught.value.not_final_by_class == {"WARRIOR": 1, "MAGE": 1}


@pytest.mark.parametrize("status", ["pending", "unverified", "nil", "empty", "error"])
def test_a_complete_flag_does_not_override_a_non_final_record(status: str) -> None:
    value = payload(MANIFEST)
    value["records"][2]["status"] = status
    with pytest.raises(errors.IncompleteCaptureError):
        import_payload(value)


def test_a_final_record_without_tooltip_is_incomplete() -> None:
    value = payload(MANIFEST)
    value["records"][0]["tooltip"] = None
    with pytest.raises(errors.IncompleteCaptureError):
        import_payload(value)


# --------------------------------------------------------------------------- coverage


def test_unknown_entry_is_rejected() -> None:
    value = payload(MANIFEST)
    value["records"].append(record(REND.model_copy(update={"entry_id": 999999, "max_rank": 1}), 1))
    with pytest.raises(errors.UnknownEntryError):
        import_payload(value)


def test_duplicate_entry_rank_is_rejected() -> None:
    value = payload(MANIFEST)
    value["records"].append(copy.deepcopy(value["records"][0]))
    with pytest.raises(errors.DuplicateRecordError):
        import_payload(value)


def test_missing_rank_is_rejected() -> None:
    value = payload(MANIFEST)
    removed = value["records"].pop(2)
    assert (removed["entryId"], removed["rank"]) == (130686, 3)
    value["expectedRecords"] = 5
    with pytest.raises(errors.MissingRecordError, match="130686 rank 3"):
        import_payload(value)


def test_missing_entry_is_rejected() -> None:
    value = payload(MANIFEST)
    value["records"] = [r for r in value["records"] if r["entryId"] != MORTAL_STRIKE.entry_id]
    with pytest.raises(errors.MissingRecordError):
        import_payload(value)


@pytest.mark.parametrize("rank", [0, 4, -1])
def test_out_of_range_rank_is_rejected(rank: int) -> None:
    value = payload(MANIFEST)
    value["records"].append(record(REND, rank))
    with pytest.raises(errors.RankOutOfRangeError):
        import_payload(value)


@pytest.mark.parametrize(
    ("field", "wrong"),
    [("classToken", "MAGE"), ("treeId", 1), ("nodeId", 1), ("spellId", 1)],
)
def test_a_record_must_carry_the_manifest_identity_of_its_entry(field: str, wrong: object) -> None:
    value = payload(MANIFEST)
    value["records"][0][field] = wrong
    with pytest.raises(errors.RecordIdentityError):
        import_payload(value)


def test_declared_record_count_must_match() -> None:
    with pytest.raises(errors.MissingRecordError):
        import_payload(payload(MANIFEST, expectedRecords=7))


def test_coverage_failures_are_all_coverage_errors() -> None:
    for kind in (
        errors.UnknownEntryError,
        errors.DuplicateRecordError,
        errors.MissingRecordError,
        errors.RankOutOfRangeError,
        errors.RecordIdentityError,
    ):
        assert issubclass(kind, errors.CaptureCoverageError)


# --------------------------------------------------------------------------- raw tooltip


@pytest.mark.parametrize(
    "broken",
    [
        {},
        {"lines": []},
        {"lines": {}},
        {"lines": ["text"]},
        {"lines": [{"type": 0, "leftText": ""}]},
        {"lines": [{"type": 0}]},
        {"lines": [{"type": "0", "leftText": "x"}]},
        {"lines": [{"type": 0, "leftText": 5}]},
        {"lines": [{"type": 0, "leftText": "x", "wrapText": 1}]},
        {"lines": [{"type": 0, "leftText": "x", "rightText": None}]},
        {"type": "26", "lines": [{"leftText": "x"}]},
        {"id": True, "lines": [{"leftText": "x"}]},
        {"hasDynamicData": 0, "lines": [{"leftText": "x"}]},
        {"dataInstanceID": "7", "lines": [{"leftText": "x"}]},
    ],
)
def test_a_tooltip_without_the_minimal_shape_is_rejected(broken: dict[str, Any]) -> None:
    value = payload(MANIFEST)
    value["records"][0]["tooltip"] = broken
    with pytest.raises(errors.MalformedTooltipError):
        import_payload(value)


def test_unknown_raw_tooltip_fields_are_preserved_not_dropped() -> None:
    value = payload(MANIFEST)
    raw = tooltip(REND, 1)
    raw["isAzeriteItem"] = False
    raw["lines"][0]["unitToken"] = "player"
    raw["lines"].append({"type": 19, "leftText": " ", "args": [{"field": "x", "intVal": 3}]})
    value["records"][0]["tooltip"] = raw
    assert import_payload(value).records[0].tooltip == raw


TRICKY = (
    "He said \"no\" \\ and 'yes'; |cFFFF2020Requires Bear Form|r |T123:0|t ||\r\n"
    "\tnaïve — 日本語 😀  trailing spaces  \r\n\n "
)


def test_strings_with_quotes_unicode_pipes_and_crlf_round_trip_exactly() -> None:
    value = payload(MANIFEST)
    value["records"][0]["tooltip"] = tooltip(REND, 1, text=TRICKY)
    value["records"][0]["tooltip"]["lines"][0]["rightText"] = " \r\n"
    # ensure_ascii on and off: both are valid encodings of the same strings.
    for ensure_ascii in (False, True):
        text = json.dumps(value, ensure_ascii=ensure_ascii, separators=(",", ":"))
        capture = import_client_capture(saved_variables(text.encode("utf-8")), MANIFEST)
        lines = capture.records[0].tooltip["lines"]
        assert lines[2]["leftText"] == TRICKY
        assert lines[0]["rightText"] == " \r\n"
        # ... and they survive the artifact file byte for byte.
        stored = json.loads(capture.to_json())
        assert stored["records"][0]["tooltip"]["lines"][2]["leftText"] == TRICKY
        assert stored["records"][0]["tooltip"]["lines"][0]["rightText"] == " \r\n"


# --------------------------------------------------------------------------- semantic hash


def test_semantic_hash_is_deterministic() -> None:
    first = import_payload(payload(MANIFEST))
    second = import_payload(payload(MANIFEST))
    assert first.semantic_hash == second.semantic_hash
    assert first.semantic_hash.startswith("sha256:")
    assert first.to_json() == second.to_json()


def test_timestamp_and_diagnostics_do_not_alter_the_semantic_hash() -> None:
    reference = import_payload(payload(MANIFEST))
    later = payload(MANIFEST, capturedAt="2027-01-01T12:34:56Z")
    later["diagnostics"].update(playerClassToken="DRUID", playerLevel=1, passes=5, updateEvents=9)
    for item in later["records"]:
        item["reads"] = 4
        # A per-session handle, not content.
        item["tooltip"]["dataInstanceID"] += 100000
    imported = import_payload(later)
    assert imported.captured_at != reference.captured_at
    assert imported.semantic_hash == reference.semantic_hash


def test_any_change_of_client_text_or_structure_alters_the_semantic_hash() -> None:
    reference = import_payload(payload(MANIFEST)).semantic_hash

    def changed(mutate: Any) -> str:
        value = payload(MANIFEST)
        mutate(value["records"][3]["tooltip"])
        return import_payload(value).semantic_hash

    hashes = {
        changed(lambda t: t["lines"][2].update(leftText="Does 5% more. ")),  # one space
        changed(lambda t: t["lines"][2].update(wrapText=False)),
        changed(lambda t: t["lines"][2]["leftColor"].update(g=0.83)),
        changed(lambda t: t.update(hasDynamicData=True)),
        changed(lambda t: t["lines"].append({"type": 0, "leftText": "Requires 5 points"})),
        changed(lambda t: t["lines"][0].update(type=1)),
    }
    assert reference not in hashes
    assert len(hashes) == 6


# --------------------------------------------------------------------------- artifact


def test_artifact_round_trips_and_is_verified_on_read(tmp_path: Path) -> None:
    capture = import_payload(payload(MANIFEST))
    path = tmp_path / "client-capture.json"
    path.write_text(capture.to_json(), encoding="utf-8")
    assert read_client_capture(path, MANIFEST) == capture
    # The artifact is the decoded capture, not the SavedVariables wrapper.
    stored = json.loads(path.read_text(encoding="utf-8"))
    assert "payloadBase64" not in stored
    assert stored["semanticHash"] == capture.semantic_hash

    edited = json.loads(capture.to_json())
    edited["records"][0]["tooltip"]["lines"][2]["leftText"] = "Hand-improved wording."
    path.write_text(json.dumps(edited), encoding="utf-8")
    with pytest.raises(errors.SemanticHashMismatchError):
        read_client_capture(path, MANIFEST)

    # No cross-build and no cross-dataset reuse of a stored artifact either.
    path.write_text(capture.to_json(), encoding="utf-8")
    with pytest.raises(errors.ClientBuildMismatchError):
        read_client_capture(path, small_manifest(client_build="1.60.1.70010"))
    moved_on = MANIFEST.datasets[0].model_copy(update={"content_hash": "sha256:" + "e" * 64})
    with pytest.raises(errors.ManifestHashMismatchError):
        read_client_capture(path, small_manifest(datasets=(moved_on, MANIFEST.datasets[1])))


def test_no_real_client_capture_is_committed_yet() -> None:
    """M5.V2A ships tooling only. This fails on purpose when a capture is added, so the
    change that adds it also replaces this guard with a check of the real artifact."""
    assert not (ROOT / "artifacts" / "tooltips").exists()


# --------------------------------------------------------------------------- CLI


def real_payload(real_manifest: Any) -> dict[str, Any]:
    return payload(real_manifest)


def test_cli_imports_into_the_build_and_locale_pinned_artifact(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    real_manifest: Any,
) -> None:
    project = tmp_path / "project"
    dataset = project / "data" / "normalized" / "forever"
    dataset.mkdir(parents=True)
    (dataset / str(NEW_BUILD)).symlink_to(ROOT / "data" / "normalized" / "forever" / str(NEW_BUILD))
    monkeypatch.setenv("TALENT_DATA_ROOT", str(project))
    source = tmp_path / "TalentForgeTooltipCapture.lua"
    source.write_text(saved_json(real_payload(real_manifest)), encoding="utf-8")

    args = ["tooltips", "import-client", "--build", str(NEW_BUILD)]
    assert main([*args, "--saved-variables", str(source)]) == 0
    out = capsys.readouterr().out
    assert "imported 1333 tooltips of 469 talent entries (forever 1.60.1.70009 enUS)" in out
    artifact = (
        project / "artifacts" / "tooltips" / "forever" / "1.60.1.70009" / "enUS"
    ) / "client-capture.json"
    stored = json.loads(artifact.read_text(encoding="utf-8"))
    assert stored["recordCount"] == 1333
    assert stored["manifestHash"] == real_manifest.manifest_hash

    assert main(["tooltips", "report", "--build", str(NEW_BUILD)]) == 0
    report = artifact.with_name("client-capture-report.md").read_text(encoding="utf-8")
    assert "| entry/rank requests | 1333 |" in report

    # A rejected capture exits non-zero, names the reason and writes nothing.
    artifact.unlink()
    broken = real_payload(real_manifest)
    broken["clientBuild"] = "1.60.1.69977"
    source.write_text(saved_json(broken), encoding="utf-8")
    assert main([*args, "--saved-variables", str(source)]) == 1
    assert "exactly 1.60.1.70009 is required" in capsys.readouterr().err
    assert not artifact.exists()

    assert main([*args, "--saved-variables", str(tmp_path / "missing.lua")]) == 1
    assert main(["tooltips", "import-client", "--build", "latest", "--saved-variables", "x"]) == 1


def test_base64_helper_matches_what_the_importer_decodes() -> None:
    # Guard for the synthetic wrapper itself: chunked base64 of 3-byte multiples
    # concatenates into the base64 of the whole payload.
    data = bytes(range(256)) * 3
    text = saved_variables(data, chunk_bytes=48)
    chunks = parse_saved_variables(text).chunks
    assert chunks is not None
    assert base64.b64decode("".join(chunks), validate=True) == data
