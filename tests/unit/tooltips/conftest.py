from __future__ import annotations

import base64
import json
from pathlib import Path
from typing import Any

import pytest
from lupa import lua51

from talent_data.games import get_game
from talent_data.tooltips.manifest import (
    CaptureManifest,
    DatasetIdentity,
    ManifestEntry,
    load_capture_manifest,
)
from tests.helpers import NEW_BUILD

ROOT = Path(__file__).resolve().parents[3]
GAME = get_game("forever")
ADDON_DIR = ROOT / "tools" / "wow-tooltip-capture" / "TalentForgeTooltipCapture"
ADDON_SOURCE = ADDON_DIR / "TalentForgeTooltipCapture.lua"
GENERATED_MANIFEST = ADDON_DIR / "GeneratedTalentManifest.lua"
HARNESS = Path(__file__).with_name("wow_client_harness.lua")

EXPECTED_ENTRIES = 469
EXPECTED_RECORDS = 1333


@pytest.fixture(scope="session")
def real_manifest() -> CaptureManifest:
    """Capture manifest of the committed 1.60.1.70009 dataset (all nine classes)."""
    return load_capture_manifest(GAME, NEW_BUILD)


# --------------------------------------------------------------------------- synthetic


def small_manifest(**changes: Any) -> CaptureManifest:
    """A three-entry manifest: enough for every importer rule, small enough to read."""
    fields: dict[str, Any] = {
        "format_version": 1,
        "game": "forever",
        "client_build": "1.60.1.70009",
        "locale": "enUS",
        "datasets": (
            DatasetIdentity(class_token="WARRIOR", content_hash="sha256:" + "a" * 64),
            DatasetIdentity(class_token="MAGE", content_hash="sha256:" + "b" * 64),
        ),
        "entries": (
            ManifestEntry(
                class_token="WARRIOR",
                tree_id=1117,
                node_id=105956,
                entry_id=130686,
                spell_id=12286,
                name="Improved Rend",
                max_rank=3,
            ),
            ManifestEntry(
                class_token="WARRIOR",
                tree_id=1117,
                node_id=105941,
                entry_id=130671,
                spell_id=12294,
                name="Mortal Strike",
                max_rank=1,
            ),
            ManifestEntry(
                class_token="MAGE",
                tree_id=1095,
                node_id=105300,
                entry_id=130500,
                spell_id=11366,
                name="Pyroblast",
                max_rank=2,
            ),
        ),
    }
    fields.update(changes)
    return CaptureManifest(**fields)


def tooltip(entry: ManifestEntry, rank: int, *, text: str | None = None) -> dict[str, Any]:
    return {
        "type": 26,
        "id": entry.entry_id,
        "dataInstanceID": 5000 + entry.entry_id + rank,
        "hasDynamicData": False,
        "lines": [
            {"type": 0, "leftText": entry.name, "wrapText": False},
            {"type": 0, "leftText": f"Rank {rank}/{entry.max_rank}", "wrapText": False},
            {
                "type": 0,
                "leftText": text if text is not None else f"Does {rank * 5}% more.",
                "leftColor": {"r": 1, "g": 0.82, "b": 0, "a": 1},
                "wrapText": True,
            },
        ],
    }


def record(entry: ManifestEntry, rank: int, **changes: Any) -> dict[str, Any]:
    fields: dict[str, Any] = {
        "classToken": entry.class_token,
        "treeId": entry.tree_id,
        "nodeId": entry.node_id,
        "entryId": entry.entry_id,
        "spellId": entry.spell_id,
        "rank": rank,
        "status": "ok",
        "reads": 2,
        "error": None,
        "tooltip": tooltip(entry, rank),
    }
    fields.update(changes)
    return fields


def payload(manifest: CaptureManifest, **changes: Any) -> dict[str, Any]:
    """A complete, valid capture-format-v1 payload for ``manifest``."""
    records = [record(e, rank) for e in manifest.entries for rank in range(1, e.max_rank + 1)]
    fields: dict[str, Any] = {
        "captureFormatVersion": 1,
        "capturedAt": "2026-10-08T00:00:00Z",
        "clientBuild": manifest.client_build,
        "complete": True,
        "datasets": [d.model_dump(mode="json", by_alias=True) for d in manifest.datasets],
        "diagnostics": {
            "buildDate": "Oct  1 2026",
            "buildNumber": "70009",
            "buildVersion": "1.60.1",
            "interfaceVersion": 16001,
            "passes": 2,
            "playerClassToken": "MAGE",
            "playerLevel": 60,
            "updateEventRegistered": True,
            "updateEvents": 0,
            "updateEventsWithoutId": 0,
        },
        "expectedRecords": len(records),
        "game": manifest.game,
        "locale": manifest.locale,
        "manifestHash": manifest.manifest_hash,
        "records": records,
    }
    fields.update(changes)
    return fields


def saved_variables(
    data: bytes,
    *,
    chunk_bytes: int = 48,
    format_version: int = 1,
    payload_bytes: int | None = None,
) -> str:
    """SavedVariables text as the client writes it, for an arbitrary payload byte string."""
    chunks = [
        base64.b64encode(data[i : i + chunk_bytes]).decode("ascii")
        for i in range(0, len(data), chunk_bytes)
    ]
    lines = ["", "TalentForgeTooltipCaptureDB = {", '\t["payloadBase64"] = {']
    lines.extend(f'\t\t"{chunk}", -- [{index}]' for index, chunk in enumerate(chunks, start=1))
    lines.append("\t},")
    lines.append(f'\t["formatVersion"] = {format_version},')
    lines.append(f'\t["payloadBytes"] = {len(data) if payload_bytes is None else payload_bytes},')
    lines.append("}")
    return "\n".join(lines) + "\n"


def saved_json(value: Any) -> str:
    text = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return saved_variables(text.encode("utf-8"))


# --------------------------------------------------------------------------- Lua client


class Client:
    """The real addon running under Lua 5.1 against the stand-in client harness."""

    def __init__(self, manifest_source: str | None = None, **config: Any) -> None:
        self.lua = lua51.LuaRuntime(unpack_returned_tuples=True)
        settings: dict[str, Any] = {
            "version": "1.60.1",
            "build": "70009",
            "locale": "enUS",
            "sources": self.lua.table_from(
                {
                    "manifest": manifest_source
                    if manifest_source is not None
                    else GENERATED_MANIFEST.read_text(encoding="utf-8"),
                    "addon": ADDON_SOURCE.read_text(encoding="utf-8"),
                }
            ),
        }
        for key, value in config.items():
            # Behaviour hooks are given as Lua source: "function(client, ...) ... end".
            is_hook = key in ("getTraitEntry", "beforeFrame")
            settings[key] = self.lua.eval(value) if is_hook else value
        harness = self.lua.execute(HARNESS.read_text(encoding="utf-8"))
        self.client = harness(self.lua.table_from(settings))

    def slash(self, text: str) -> None:
        self.client.slash(text)

    def run(self, *, elapsed: float = 0.1, max_frames: int = 20000) -> int:
        return int(self.client.runUntilIdle(elapsed, max_frames))

    def tick(self, elapsed: float = 0.1, frames: int = 1) -> None:
        for _ in range(frames):
            self.client.tick(elapsed)

    def fire(self, event: str, *args: Any) -> None:
        self.client.fire(event, *args)

    @property
    def running(self) -> bool:
        return bool(self.client.running())

    @property
    def messages(self) -> list[str]:
        return list(self.client.messages.values())

    @property
    def calls(self) -> list[tuple[int, int]]:
        return [(int(c.entryId), int(c.rank)) for c in self.client.calls.values()]

    @property
    def globals_read(self) -> set[str]:
        return set(filter(None, self.client.list(self.client.globalsRead).split(",")))

    @property
    def globals_written(self) -> set[str]:
        return set(filter(None, self.client.list(self.client.globalsWritten).split(",")))

    @property
    def has_saved_value(self) -> bool:
        return self.client.savedValue() is not None

    def saved_variables(self) -> str:
        return str(self.client.savedVariables())

    def internal(self, name: str) -> Any:
        return self.client.namespace.internal[name]
