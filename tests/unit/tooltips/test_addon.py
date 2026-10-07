"""The real capture addon, executed by a real Lua 5.1 against a stand-in client.

``wow_client_harness.lua`` provides only the globals the addon may use and records every
global it touches. The SavedVariables text it produces is fed to the real importer, so
the Lua encoder and the Python decoder are tested against each other, not against copies
of themselves. This is not client evidence: what ``GetTraitEntry`` really returns is only
known from a real capture.
"""

from __future__ import annotations

import base64
import itertools
import json
import re
from typing import Any

import pytest

from talent_data.tooltips import errors
from talent_data.tooltips.capture import ClientTooltipCapture, decode_payload, import_client_capture
from talent_data.tooltips.manifest import CaptureManifest, render_lua
from talent_data.tooltips.saved_variables import SAVED_VARIABLE
from tests.unit.tooltips.conftest import (
    ADDON_DIR,
    ADDON_SOURCE,
    EXPECTED_ENTRIES,
    EXPECTED_RECORDS,
    Client,
    small_manifest,
)

SMALL = small_manifest()
SMALL_LUA = render_lua(SMALL)
SMALL_RECORDS = 6

# The only globals the addon may read, and the only ones it may create.
ALLOWED_READS = {
    "C_TooltipInfo",
    "CreateFrame",
    "GetBuildInfo",
    "GetLocale",
    "SlashCmdList",
    "TalentForgeTooltipCaptureDB",
    "UnitClass",
    "UnitLevel",
    "date",
    "error",
    "ipairs",
    "math",
    "pairs",
    "pcall",
    "print",
    "string",
    "table",
    "tonumber",
    "tostring",
    "type",
}
ALLOWED_WRITES = {"SLASH_TALENTFORGETOOLTIPS1", "TalentForgeTooltipCaptureDB"}

COUNT_READS = """
    client.seen = client.seen or {}
    local key = entryId .. ":" .. rank
    client.seen[key] = (client.seen[key] or 0) + 1
    local read = client.seen[key]
"""


def hook(body: str) -> str:
    return f"function(client, entryId, rank) {COUNT_READS} {body} end"


def small_client(**config: Any) -> Client:
    return Client(manifest_source=SMALL_LUA, **config)


def captured(client: Client, manifest: CaptureManifest = SMALL) -> ClientTooltipCapture:
    return import_client_capture(client.saved_variables(), manifest)


def raw_records(client: Client) -> dict[tuple[int, int], dict[str, Any]]:
    payload = decode_payload(client.saved_variables())
    return {(r["entryId"], r["rank"]): r for r in payload["records"]}


# --------------------------------------------------------------------------- full capture


@pytest.fixture(scope="module")
def full() -> Client:
    """One complete capture of the real 469-entry manifest."""
    client = Client(
        beforeFrame="function(client, frame) client.perFrame = client.perFrame or {}; "
        "client.perFrame[frame] = #client.calls end"
    )
    client.slash("capture")
    client.run()
    return client


def test_complete_capture_of_the_real_manifest_imports(
    full: Client, real_manifest: CaptureManifest
) -> None:
    capture = captured(full, real_manifest)
    assert capture.entry_count == EXPECTED_ENTRIES
    assert capture.record_count == EXPECTED_RECORDS
    assert capture.manifest_hash == real_manifest.manifest_hash
    assert (capture.game, capture.client_build, capture.locale) == (
        "forever",
        "1.60.1.70009",
        "enUS",
    )
    assert full.messages[-2:] == [
        "TalentForge tooltips: TalentForge tooltip capture complete.",
        "TalentForge tooltips: Reload or exit the game to flush SavedVariables.",
    ]
    assert f"TalentForge tooltips: {EXPECTED_RECORDS} / {EXPECTED_RECORDS}" in full.messages
    assert not full.running


def test_every_rank_of_every_entry_is_requested_and_nothing_else(
    full: Client, real_manifest: CaptureManifest
) -> None:
    expected = [(e.entry_id, r) for e in real_manifest.entries for r in range(1, e.max_rank + 1)]
    # Pass 1 reads everything in manifest order, pass 2 confirms everything once.
    assert full.calls == expected + expected
    assert all(rank >= 1 for _, rank in full.calls)
    assert {r.reads for r in captured(full, real_manifest).records} == {2}


def test_capture_stays_responsive_small_batches_per_frame(full: Client) -> None:
    totals = [int(v) for v in full.client.perFrame.values()]
    per_frame = [b - a for a, b in itertools.pairwise(totals)]
    assert max(per_frame) == 20
    assert len(totals) > 2 * EXPECTED_RECORDS / 20


def test_client_strings_arrive_verbatim(full: Client, real_manifest: CaptureManifest) -> None:
    rend = next(r for r in captured(full, real_manifest).records if r.entry_id == 130686)
    assert rend.rank == 1
    tooltip = rend.tooltip
    assert tooltip["type"] == 26
    assert tooltip["id"] == 130686
    assert tooltip["hasDynamicData"] is False
    assert isinstance(tooltip["dataInstanceID"], int)
    lines = tooltip["lines"]
    assert [line["leftText"] for line in lines] == [
        "Improved Rend",
        "Rank 1/3",
        'Increases "damage" by 5%.\r\n\r\n    |CFFFFFFFFAxe:|R naïve — 日本語\tend ',
    ]
    assert [line["wrapText"] for line in lines] == [False, False, True]
    # Colours are data; the mixin's methods are not.
    assert lines[1]["leftColor"] == {"r": 1, "g": 0.82, "b": 0, "a": 1}
    assert all("rightText" not in line for line in lines)


def test_ranks_of_one_entry_are_captured_separately(
    full: Client, real_manifest: CaptureManifest
) -> None:
    weaponmaster = [r for r in captured(full, real_manifest).records if r.entry_id == 130674]
    assert [r.rank for r in weaponmaster] == [1, 2, 3, 4, 5]
    assert [r.tooltip["lines"][1]["leftText"] for r in weaponmaster] == [
        f"Rank {rank}/5" for rank in range(1, 6)
    ]


def test_addon_touches_only_the_read_only_api(full: Client) -> None:
    full.slash("status")
    full.slash("probe 130686 1")
    assert full.globals_read <= ALLOWED_READS
    assert full.globals_written == ALLOWED_WRITES
    assert full.client.slashCommand() == "/tftooltips"


def test_addon_source_names_no_talent_mutating_api() -> None:
    source = ADDON_SOURCE.read_text(encoding="utf-8")
    code = "\n".join(line.split("--", 1)[0] for line in source.splitlines())
    for forbidden in (
        "C_Traits",
        "C_ClassTalents",
        "C_SpecializationInfo",
        "PurchaseRank",
        "RefundRank",
        "ResetTree",
        "CommitConfig",
        "StageConfig",
        "LoadConfig",
        "LearnTalent",
        "SetSelection",
        "RunScript",
        "loadstring",
        "hooksecurefunc",
        "SendAddonMessage",
        "SendChatMessage",
    ):
        assert forbidden not in code
    # Exactly one C_TooltipInfo function is ever referenced.
    assert set(re.findall(r"C_TooltipInfo\.(\w+)", code)) == {"GetTraitEntry"}
    assert set(re.findall(r"\bC_\w+", code)) == {"C_TooltipInfo"}


def test_two_captures_of_the_same_client_have_the_same_semantic_hash(
    full: Client, real_manifest: CaptureManifest
) -> None:
    again = Client(now="2027-03-04T05:06:07Z", interfaceVersion=16002)
    again.client.nextInstance = 900000
    again.slash("capture")
    again.run(elapsed=0.25)
    first, second = captured(full, real_manifest), captured(again, real_manifest)
    assert second.captured_at == "2027-03-04T05:06:07Z" != first.captured_at
    assert second.records[0].tooltip["dataInstanceID"] != first.records[0].tooltip["dataInstanceID"]
    assert second.semantic_hash == first.semantic_hash


def test_saved_variables_hold_one_narrow_record(full: Client) -> None:
    text = full.saved_variables()
    assert text.count(SAVED_VARIABLE) == 1
    keys = set(re.findall(r'^\t\["(\w+)"\]', text, flags=re.MULTILINE))
    assert keys == {"formatVersion", "payloadBytes", "payloadBase64"}
    chunks = re.findall(r'^\t\t"([^"]*)", -- \[\d+\]$', text, flags=re.MULTILINE)
    # Every chunk but the last is exactly 12288 payload bytes; nothing needs escaping.
    assert all(len(chunk) == 16384 for chunk in chunks[:-1])
    assert all(re.fullmatch(r"[A-Za-z0-9+/]+=*", chunk) for chunk in chunks)
    assert len(chunks) > 10


def test_diagnostics_record_the_session_without_touching_identity(
    full: Client, real_manifest: CaptureManifest
) -> None:
    diagnostics = captured(full, real_manifest).diagnostics
    assert diagnostics.build_version == "1.60.1"
    assert diagnostics.build_number == "70009"
    assert diagnostics.interface_version == 16001
    assert (diagnostics.player_class_token, diagnostics.player_level) == ("MAGE", 60)
    assert diagnostics.passes == 2
    assert diagnostics.update_event_registered is True
    assert diagnostics.update_events == 0


# --------------------------------------------------------------------------- self-checks


def assert_not_started(client: Client, reason: str) -> None:
    assert "TalentForge tooltips: capture NOT started:" in client.messages
    assert any(reason in message for message in client.messages), client.messages
    assert client.calls == []
    assert not client.running
    assert not client.has_saved_value
    with pytest.raises(errors.NoStoredCaptureError):
        captured(client)


@pytest.mark.parametrize(
    ("version", "build"),
    [("1.60.1", "69977"), ("1.60.1", "70010"), ("1.60.2", "70009"), ("1.15.7", "70009")],
)
def test_another_client_build_stops_the_capture(version: str, build: str) -> None:
    client = small_client(version=version, build=build)
    client.slash("capture")
    assert_not_started(
        client,
        f"this client is build {version}.{build}; the capture requires exactly 1.60.1.70009",
    )


@pytest.mark.parametrize("locale", ["ruRU", "enGB", "deDE"])
def test_another_locale_is_reported_and_not_captured(locale: str) -> None:
    client = small_client(locale=locale)
    client.slash("capture")
    assert_not_started(
        client, f"this client's locale is {locale}; the canonical capture requires enUS"
    )


def test_missing_tooltip_api_aborts_with_a_clear_message() -> None:
    client = small_client(tooltipInfo=False)
    client.slash("capture")
    assert_not_started(client, "C_TooltipInfo is not available in this client")

    client = small_client(tooltipInfo="no-function")
    client.slash("capture")
    assert_not_started(client, "C_TooltipInfo.GetTraitEntry is not available in this client")


@pytest.mark.parametrize(
    ("change", "reason"),
    [
        (("entryId = 130671", "entryId = 130686"), "duplicate entry id 130686"),
        (("maxRank = 3", "maxRank = 0"), "entry 130686 has no positive maxRank"),
        (("maxRank = 3", "maxRank = 2.5"), "entry 130686 has no positive maxRank"),
        (("entryCount = 3", "entryCount = 4"), "manifest entry count does not match"),
        (("rankCount = 6", "rankCount = 7"), "manifest rank count does not match"),
        (("formatVersion = 1", "formatVersion = 2"), "unsupported manifest format 2"),
        (("spellId = 12286, ", ""), "manifest entry #1 is malformed"),
    ],
)
def test_an_inconsistent_manifest_stops_the_capture(change: tuple[str, str], reason: str) -> None:
    assert change[0] in SMALL_LUA
    client = Client(manifest_source=SMALL_LUA.replace(*change))
    client.slash("capture")
    assert_not_started(client, reason)


def test_an_empty_or_missing_manifest_stops_the_capture() -> None:
    empty = re.sub(r"    entries = \{.*?\n    \},\n", "    entries = {},\n", SMALL_LUA, flags=re.S)
    assert "entryId" not in empty
    client = Client(manifest_source=empty)
    client.slash("capture")
    assert_not_started(client, "the generated manifest has no talent entries")

    client = Client(manifest_source="local _, ns = ...")
    client.slash("capture")
    assert_not_started(client, "GeneratedTalentManifest.lua did not load")


def test_status_reports_readiness_without_requesting_anything() -> None:
    ready = small_client()
    ready.slash("status")
    assert ready.messages == [
        "TalentForge tooltips: client build 1.60.1.70009, interface 16001",
        "TalentForge tooltips: locale enUS",
        "TalentForge tooltips: manifest: forever 1.60.1.70009 enUS, 3 entries, 6 tooltips",
        f"TalentForge tooltips: manifest hash {SMALL.manifest_hash}",
        "TalentForge tooltips: stored record: none",
        "TalentForge tooltips: ready: /tftooltips capture",
    ]
    assert ready.calls == []

    wrong = small_client(build="69977", locale="ruRU")
    wrong.slash("status")
    assert "TalentForge tooltips: NOT ready:" in wrong.messages
    assert sum("requires" in message for message in wrong.messages) == 2
    assert wrong.calls == []


# --------------------------------------------------------------------------- dynamic data


def test_a_nil_answer_is_asked_again_until_the_client_has_the_data() -> None:
    client = small_client(
        getTraitEntry=hook(
            "if entryId == 130671 and read <= 2 then return nil end "
            "return client.defaultTooltip(client, entryId, rank)"
        )
    )
    client.slash("capture")
    client.run()
    reads = {(r.entry_id, r.rank): r.reads for r in captured(client).records}
    # nil, nil, data, identical data -> final after the fourth read; the others after two.
    assert reads[(130671, 1)] == 4
    assert {v for k, v in reads.items() if k != (130671, 1)} == {2}
    assert client.messages[-2].endswith("capture complete.")


def test_a_tooltip_that_changes_between_reads_is_only_final_once_stable() -> None:
    client = small_client(
        getTraitEntry=hook(
            "local data = client.defaultTooltip(client, entryId, rank) "
            "if entryId == 130686 and rank == 2 and read == 1 then "
            '  data.lines[3].leftText = "Retrieving..." '
            "end "
            "return data"
        )
    )
    client.slash("capture")
    client.run()
    record = next(r for r in captured(client).records if (r.entry_id, r.rank) == (130686, 2))
    assert record.reads == 3
    assert record.tooltip["lines"][2]["leftText"].startswith('Increases "damage" by 10%.')


def test_tooltip_data_update_reopens_exactly_the_matching_record() -> None:
    # The client answers with placeholder text, twice identically, and only announces the
    # real text through TOOLTIP_DATA_UPDATE for that tooltip's dataInstanceID.
    client = small_client(
        getTraitEntry=hook(
            "local data = client.defaultTooltip(client, entryId, rank) "
            "if entryId == 130500 and rank == 1 then "
            "  if not client.loaded then data.lines[3].leftText = 'Retrieving...' end "
            "  client.pendingInstance = data.dataInstanceID "
            "end "
            "return data"
        ),
        beforeFrame="function(client, frame) "
        "if client.pendingInstance and #client.calls == 12 and not client.loaded then "
        "  client.loaded = true "
        "  client.fire('TOOLTIP_DATA_UPDATE', client.pendingInstance) "
        "end end",
    )
    client.slash("capture")
    client.run()
    reads = {(r.entry_id, r.rank): r for r in captured(client).records}
    # Two placeholder reads, then the update, then two reads of the real text.
    assert reads[(130500, 1)].reads == 4
    assert "Retrieving" not in json.dumps(reads[(130500, 1)].tooltip)
    assert {r.reads for key, r in reads.items() if key != (130500, 1)} == {2}
    diagnostics = captured(client).diagnostics
    assert (diagnostics.update_events, diagnostics.update_events_without_id) == (1, 0)


def test_an_update_during_encoding_invalidates_the_payload() -> None:
    # With 0.25 s frames the second pass is followed by exactly four settle frames; the
    # payload is built in the fourth, so the fifth frame is the encoding frame.
    client = small_client(
        beforeFrame="function(client, frame) "
        "if #client.calls == 12 and not client.fired then "
        "  client.idleFrames = (client.idleFrames or 0) + 1 "
        "  if client.idleFrames == 5 then "
        "    client.fired = true "
        "    client.fire('TOOLTIP_DATA_UPDATE') "
        "  end "
        "end end"
    )
    client.slash("capture")
    client.run(elapsed=0.25)
    capture = captured(client)
    # The id-less update arrived after everything was final: everything was read again
    # and the capture that was stored is the re-verified one.
    assert {r.reads for r in capture.records} == {3}
    assert len(client.calls) == 3 * SMALL_RECORDS
    assert capture.diagnostics.update_events_without_id == 1
    assert capture.diagnostics.passes == 3


def test_capture_completes_when_the_client_has_no_tooltip_data_update_event() -> None:
    client = small_client(unknownEvent=True)
    client.slash("capture")
    client.run()
    capture = captured(client)
    assert capture.record_count == SMALL_RECORDS
    assert capture.diagnostics.update_event_registered is False


# --------------------------------------------------------------------------- incompleteness


def assert_incomplete(client: Client, status: str, final: int) -> dict[tuple[int, int], Any]:
    assert not client.running
    assert (
        f"TalentForge tooltips: capture INCOMPLETE: {final} / {SMALL_RECORDS} tooltips final."
        in client.messages
    )
    assert not any("capture complete" in message for message in client.messages)
    with pytest.raises(errors.IncompleteCaptureError) as caught:
        captured(client)
    assert caught.value.not_final_by_status == {status: SMALL_RECORDS - final}
    payload = decode_payload(client.saved_variables())
    assert payload["complete"] is False
    return raw_records(client)


def test_an_entry_the_client_never_answers_makes_the_capture_incomplete() -> None:
    client = small_client(
        getTraitEntry=hook(
            "if entryId == 130500 then return nil end "
            "return client.defaultTooltip(client, entryId, rank)"
        )
    )
    client.slash("capture")
    client.run()
    records = assert_incomplete(client, "nil", 4)
    assert records[(130500, 2)]["tooltip"] is None
    assert records[(130500, 2)]["reads"] == 10
    assert records[(130686, 1)]["status"] == "ok"
    assert "TalentForge tooltips: not final by class: MAGE 2" in client.messages
    with pytest.raises(errors.IncompleteCaptureError) as caught:
        captured(client)
    assert caught.value.not_final_by_class == {"MAGE": 2}


def test_a_tooltip_without_text_is_not_a_usable_answer() -> None:
    client = small_client(
        getTraitEntry=hook(
            "local data = client.defaultTooltip(client, entryId, rank) "
            "if entryId == 130671 then data.lines = {} end "
            "return data"
        )
    )
    client.slash("capture")
    client.run()
    records = assert_incomplete(client, "empty", 5)
    # The unusable answer is kept as evidence, not thrown away.
    assert records[(130671, 1)]["tooltip"]["lines"] == {}


def test_a_tooltip_that_never_stops_changing_is_not_final() -> None:
    client = small_client(
        getTraitEntry=hook(
            "local data = client.defaultTooltip(client, entryId, rank) "
            "if entryId == 130671 then data.lines[3].leftText = 'Cooldown ' .. read end "
            "return data"
        )
    )
    client.slash("capture")
    client.run()
    assert_incomplete(client, "unverified", 5)


def test_an_api_error_or_unrepresentable_value_fails_the_record_not_the_client() -> None:
    client = small_client(
        getTraitEntry=hook(
            'if entryId == 130671 then error("trait entry unavailable") end '
            "local data = client.defaultTooltip(client, entryId, rank) "
            "if entryId == 130500 then data.lines[1].widget = newproxy() end "
            "return data"
        )
    )
    client.slash("capture")
    client.run()
    with pytest.raises(errors.IncompleteCaptureError) as caught:
        captured(client)
    assert caught.value.not_final_by_status == {"error": 3}
    records = raw_records(client)
    assert "trait entry unavailable" in records[(130671, 1)]["error"]
    assert "unsupported value type: userdata" in records[(130500, 1)]["error"]


def test_an_interrupted_capture_is_never_mistaken_for_a_complete_one() -> None:
    client = small_client()
    client.slash("capture")
    client.tick(frames=3)
    assert client.running
    # What the client would write if the player logged out right now.
    with pytest.raises(errors.IncompleteCaptureError, match="never finished"):
        captured(client)
    client.slash("capture")
    assert client.messages[-1] == "TalentForge tooltips: a capture is already running."
    # It is safely restartable: finishing the run produces a normal capture.
    client.run()
    assert captured(client).record_count == SMALL_RECORDS


def test_a_previous_capture_is_replaced_as_soon_as_a_new_one_starts() -> None:
    client = small_client()
    client.slash("capture")
    client.run()
    assert captured(client).record_count == SMALL_RECORDS
    client.slash("capture")
    with pytest.raises(errors.IncompleteCaptureError, match="never finished"):
        captured(client)


def test_clear_stops_a_running_capture_and_removes_the_record() -> None:
    client = small_client()
    client.slash("capture")
    client.tick(frames=2)
    client.slash("clear")
    assert not client.running
    assert client.messages[-2:] == [
        "TalentForge tooltips: running capture stopped.",
        "TalentForge tooltips: stored record cleared.",
    ]
    with pytest.raises(errors.NoStoredCaptureError):
        captured(client)


# --------------------------------------------------------------------------- probe


def test_probe_prints_one_tooltip_and_changes_nothing() -> None:
    client = small_client()
    client.slash("probe 130686 2")
    assert client.calls == [(130686, 2)]
    assert client.messages == [
        "TalentForge tooltips: probe entry 130686 rank 2 on build 1.60.1.70009",
        "TalentForge tooltips:   type 26, id 130686, dataInstanceID 1001, hasDynamicData false",
        "TalentForge tooltips:   lines 3",
        "TalentForge tooltips:   [1] type 0: Improved Rend",
        "TalentForge tooltips:   [2] type 0: Rank 2/3",
        # Markup is shown, not interpreted: every | is doubled for the chat frame.
        'TalentForge tooltips:   [3] type 0: Increases "damage" by 10%.\r\n\r\n'
        "    ||CFFFFFFFFAxe:||R naïve — 日本語\tend ",
    ]
    assert not client.has_saved_value
    assert not client.running


def test_probe_shows_that_rank_changes_the_tooltip() -> None:
    client = small_client()
    for rank in (1, 2, 3):
        client.slash(f"probe 130686 {rank}")
    assert client.calls == [(130686, 1), (130686, 2), (130686, 3)]
    assert [m for m in client.messages if "Increases" in m] == [
        f'TalentForge tooltips:   [3] type 0: Increases "damage" by {rank * 5}%.\r\n\r\n'
        "    ||CFFFFFFFFAxe:||R naïve — 日本語\tend "
        for rank in (1, 2, 3)
    ]


@pytest.mark.parametrize("arguments", ["", "130686", "x 1", "130686 1 extra", "-1 1"])
def test_probe_rejects_anything_but_two_integers(arguments: str) -> None:
    client = small_client()
    client.slash(f"probe {arguments}")
    assert client.messages == ["TalentForge tooltips: usage: /tftooltips probe <entryId> <rank>"]
    assert client.calls == []


def test_probe_reports_nil_and_missing_api() -> None:
    client = small_client(getTraitEntry="function() return nil end")
    client.slash("probe 1 1")
    assert client.messages[-1] == "TalentForge tooltips:   returned nil"
    client = small_client(tooltipInfo=False)
    client.slash("probe 1 1")
    assert client.messages == [
        "TalentForge tooltips: C_TooltipInfo is not available in this client"
    ]


def test_unknown_command_lists_the_commands() -> None:
    client = small_client()
    for text in ("", "help", "purchase 130686"):
        client.slash(text)
    assert set(client.messages) == {
        "TalentForge tooltips: commands: status | capture | clear | probe <entryId> <rank>"
    }
    assert client.calls == []


# --------------------------------------------------------------------------- encoders


def test_lua_base64_matches_python_for_every_padding_case() -> None:
    client = small_client()
    encode = client.internal("base64")
    for length in (0, 1, 2, 3, 4, 5, 6, 47, 48, 49, 12287, 12288, 12289):
        text = ('naïve 日本語 "|\\\r\n' * (length // 8 + 1))[:length]
        data = text.encode("utf-8")
        assert encode(text) == base64.b64encode(data).decode("ascii")


def test_lua_json_strings_are_strict_json_for_every_character() -> None:
    client = small_client()
    encode = client.internal("jsonString")
    every_ascii = "".join(chr(code) for code in range(1, 128))
    for text in (
        every_ascii,
        "",
        '"',
        "\\",
        "\\u0041",
        "|cffffffffx|r",
        "naïve — 日本語 😀",
        "a\r\nb",
    ):
        assert json.loads(encode(text)) == text
    # No raw control character ever reaches the payload.
    assert not re.search(r"[\x00-\x1f]", encode(every_ascii))


def test_lua_json_numbers_keep_integers_exact_and_reject_non_finite() -> None:
    client = small_client()
    encode = client.internal("jsonNumber")
    assert encode(0) == "0"
    assert encode(130686) == "130686"
    assert encode(-5) == "-5"
    assert encode(4294967296) == "4294967296"  # beyond 32 bits
    assert encode(9007199254740991) == "9007199254740991"
    assert json.loads(encode(0.82)) == 0.82
    assert json.loads(encode(1 / 3)) == 1 / 3
    assert json.loads(encode(1e300)) == 1e300
    for bad in ("0/0", "math.huge", "-math.huge"):
        with pytest.raises(Exception, match="non-finite"):
            encode(client.lua.eval(bad))


def test_lua_raw_dump_is_lossless_sorted_and_fails_closed() -> None:
    client = small_client()
    lua = client.lua
    dump = client.internal("dumpValue")

    def dumped(source: str, *extra: Any) -> Any:
        return json.loads(dump(lua.eval(source), 1, *extra))

    assert dump(lua.eval('{ b = 1, a = "x", c = true, d = { 3, 2, 1 } }'), 1) == (
        '{"a":"x","b":1,"c":true,"d":[3,2,1]}'
    )
    # Methods are not data; everything else is kept.
    assert dumped("{ r = 1, GetRGB = function() end }") == {"r": 1}
    # A table that is not a plain 1..n array keeps its keys instead of losing elements.
    assert dumped('{ "a", "b", n = 2 }') == {"1": "a", "2": "b", "n": 2}
    assert dumped('{ [1] = "a", [3] = "c" }') == {"1": "a", "3": "c"}
    assert dumped("{}") == {}
    assert dumped("{ lines = {} }") == {"lines": {}}
    # Comparison form: only the named top-level field is left out.
    assert dumped(
        "{ dataInstanceID = 7, id = 1, lines = { { dataInstanceID = 9 } } }", "dataInstanceID"
    ) == {
        "id": 1,
        "lines": [{"dataInstanceID": 9}],
    }
    for source, message in (
        ("{ newproxy() }", "unsupported value type: userdata"),
        ("{ function() end }", "unsupported value type: function"),
        ("{ [true] = 1 }", "unsupported key type: boolean"),
        ('{ [1] = "a", ["1"] = "b", x = 1 }', "ambiguous table key: 1"),
        ("{{{{{{{{{{}}}}}}}}}}", "table nesting deeper than 8"),
    ):
        with pytest.raises(Exception, match=message):
            dump(lua.eval(source), 1)


# --------------------------------------------------------------------------- packaging


def test_generated_manifest_loads_in_lua_exactly_as_generated(
    real_manifest: CaptureManifest,
) -> None:
    manifest = Client().client.namespace.manifest
    assert manifest.manifestHash == real_manifest.manifest_hash
    assert (manifest.game, manifest.clientBuild, manifest.locale) == (
        "forever",
        "1.60.1.70009",
        "enUS",
    )
    loaded = [
        (e.classToken, e.treeId, e.nodeId, e.entryId, e.spellId, e.name, e.maxRank)
        for e in manifest.entries.values()
    ]
    assert loaded == [
        (e.class_token, e.tree_id, e.node_id, e.entry_id, e.spell_id, e.name, e.max_rank)
        for e in real_manifest.entries
    ]
    assert [(d.classToken, d.contentHash) for d in manifest.datasets.values()] == [
        (d.class_token, d.content_hash) for d in real_manifest.datasets
    ]
    assert (manifest.entryCount, manifest.rankCount) == (EXPECTED_ENTRIES, EXPECTED_RECORDS)


def test_toc_loads_the_manifest_first_and_declares_the_saved_variable() -> None:
    toc = (ADDON_DIR / "TalentForgeTooltipCapture.toc").read_text(encoding="utf-8")
    directives = dict(re.findall(r"^## ([\w-]+): (.*)$", toc, flags=re.MULTILINE))
    assert directives["SavedVariables"] == SAVED_VARIABLE
    assert "SavedVariablesPerCharacter" not in directives
    assert re.fullmatch(r"\d+", directives["Interface"])
    files = [line for line in toc.splitlines() if line and not line.startswith("#")]
    assert files == ["GeneratedTalentManifest.lua", "TalentForgeTooltipCapture.lua"]
    assert all((ADDON_DIR / name).is_file() for name in files)
    # The addon folder is the installable unit and contains nothing else.
    assert sorted(p.name for p in ADDON_DIR.iterdir()) == sorted(
        [*files, "TalentForgeTooltipCapture.toc"]
    )
