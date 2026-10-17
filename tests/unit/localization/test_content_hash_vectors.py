"""Cross-language vectors of the talent-localization.v1 ``contentHash`` (M5.REL.3b.2).

The mobile client recomputes every overlay's ``contentHash`` at runtime
(``mobile/shared/.../localization/LocalizationContentHash.kt``) instead of trusting the
published value. These vectors pin that the Kotlin canonical JSON + SHA-256 and the Python
producer (``talent_data.models.visuals.content_hash``) are byte-for-byte the same procedure:
the Kotlin ``LocalizationContentHashTest`` reads the same committed file.

Regenerate after a deliberate change:
``python tests/unit/localization/test_content_hash_vectors.py``.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from talent_data.localization.pipeline import read_localization_document
from talent_data.models.localization import expected_content_hash
from talent_data.models.visuals import content_hash

ROOT = Path(__file__).resolve().parents[3]
VECTORS = ROOT / "tests" / "fixtures" / "localization" / "content-hash-vectors.json"
OVERLAYS = ROOT / "data" / "localization"

# Payloads that exercise every rule of json.dumps(sort_keys=True, separators=(",", ":"),
# ensure_ascii=False): key order by code point, string escapes, non-ASCII passthrough.
PAYLOADS: list[tuple[str, Any]] = [
    ("empty object", {}),
    ("scalars", {"b": True, "a": False, "n": None, "z": 0, "neg": -17, "big": 2147483647}),
    ("key order is by code point", {"b": 1, "B": 2, "a": 3, "_": 4, "1": 5, "aa": 6, "é": 7}),
    ("astral key sorts after U+FFFF", {"\U0001f600": 1, "￿": 2, "": 3}),
    ("nested arrays and objects", {"x": [{"d": 1, "c": [2, {"f": [], "e": {}}]}, [], "s"]}),
    (
        "string escapes",
        {
            "quote": 'say "hi"',
            "backslash": "a\\b",
            "short": "\n\r\t\b\f",
            "controls": "\x00\x01\x1f",
            "not escaped": "\x7f / < > & '",
        },
    ),
    (
        "non-ASCII stays literal",
        {
            "ru": "Улучшенный удар героя",
            "dash": "—",
            "ls": "\N{LINE SEPARATOR}\N{PARAGRAPH SEPARATOR}",
            "emoji": "\U0001f600",
        },
    ),
    (
        "an overlay-shaped payload",
        {
            "schemaVersion": 1,
            "game": "forever",
            "clientBuild": "1.60.1.70374",
            "classToken": "WARRIOR",
            "locale": "ruRU",
            "talentDataContentHash": "sha256:" + "0" * 64,
            "tooltipsContentHash": "sha256:" + "1" * 64,
            "entries": [
                {
                    "entryId": 1,
                    "nodeId": 2,
                    "definitionId": 3,
                    "spellId": 4,
                    "textSpellId": 4,
                    "maxRank": 1,
                    "name": {"status": "missing", "text": None, "source": "SpellName.Name_lang"},
                    "description": {
                        "status": "rendered",
                        "source": "Spell.Description_lang",
                        "ranks": [
                            {
                                "rank": 1,
                                "segments": [
                                    {"kind": "text", "text": 'Урон "Удара" +'},
                                    {"kind": "value", "text": "10%"},
                                ],
                            }
                        ],
                    },
                }
            ],
        },
    ),
]


def canonical(payload: Any) -> str:
    return json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def generate() -> str:
    vectors = [
        {
            "name": name,
            "payload": payload,
            "canonical": canonical(payload),
            "contentHash": content_hash(payload),
        }
        for name, payload in PAYLOADS
    ]
    overlays = [
        {
            "path": path.relative_to(ROOT).as_posix(),
            "contentHash": expected_content_hash(read_localization_document(path)),
        }
        for path in sorted(OVERLAYS.glob("*/*/*/*.json"))
    ]
    document = {"vectors": vectors, "overlays": overlays}
    return json.dumps(document, ensure_ascii=False, indent=2) + "\n"


def test_the_committed_vectors_are_what_python_computes() -> None:
    assert VECTORS.read_text(encoding="utf-8") == generate()


def test_every_bundled_overlay_is_a_vector_and_carries_its_python_hash() -> None:
    listed = json.loads(VECTORS.read_text(encoding="utf-8"))["overlays"]
    assert len(listed) == 45  # 5 builds x 9 classes (M5.REL.3b.1, 70450 since M5.DATA.4)
    for row in listed:
        raw = json.loads((ROOT / row["path"]).read_text(encoding="utf-8"))
        assert raw["contentHash"] == row["contentHash"], row["path"]


if __name__ == "__main__":
    VECTORS.write_text(generate(), encoding="utf-8")
    print(f"wrote {VECTORS.relative_to(ROOT)}")
