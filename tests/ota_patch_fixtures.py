"""Small synthetic OTA snapshots: nine classes, two nodes, two fixture BLP icons.

No production candidate or canonical dataset is an input. These fixtures describe test
semantics only and must never be used as game data or publication evidence.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from pydantic import BaseModel

from talent_data.models.localization import TalentLocalizationDocument
from talent_data.models.manifest import DatasetManifest
from talent_data.models.talents import TalentDataDocument, content_hash, semantic_payload
from talent_data.models.tooltips import TalentTooltipsDocument
from talent_data.models.visuals import (
    TalentIconRegistry,
    TalentVisualsDocument,
    hashed_payload,
    sha256_of,
)
from talent_data.visuals.icons import convert_blp2
from tests.helpers import FIXTURES, NEW_BUILD

BASE = "1.60.1.70938"
TARGET = "1.60.1.70959"
CLASSES = dict(
    zip(
        (1, 2, 3, 4, 5, 7, 8, 9, 11),
        ("warrior", "paladin", "hunter", "rogue", "priest", "shaman", "mage", "warlock", "druid"),
        strict=True,
    )
)
ICONS = (132090, 132269)
STAMP = "2026-11-18T00:00:00Z"


def write_snapshot(root: Path, build: str, *, text: str = "Fixture") -> None:
    def write(kind: str, name: str, raw: dict[str, Any], model: type[BaseModel]) -> dict[str, Any]:
        if model is TalentDataDocument:
            raw["contentHash"] = content_hash(semantic_payload(raw))
        elif model is not DatasetManifest:
            raw["contentHash"] = content_hash(hashed_payload(raw))
        checked = model.model_validate_json(json.dumps(raw))
        path = root / f"data/{kind}/forever/{build}/{name}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(checked.model_dump_json(by_alias=True, indent=2) + "\n")
        return raw

    identity = {"schemaVersion": 1, "game": "forever", "clientBuild": build}
    classes = []
    for class_id, token in CLASSES.items():
        nodes = []
        for index in range(2):
            nodes.append(
                {
                    "id": index + 1,
                    "sectionIndex": 0,
                    "position": {"x": 0, "y": index * 100, "row": index, "column": 0},
                    "type": "single",
                    "flags": 0,
                    "maxRank": 1,
                    "spellId": index + 101,
                    "name": "Fixture talent",
                    "prerequisites": [] if index == 0 else [{"nodeId": 1, "kind": "required"}],
                    "unlockRequirements": []
                    if index == 0
                    else [{"kind": "pointsSpent", "requiredPoints": 1, "countedNodeIds": [1]}],
                    "unresolvedConditionIds": [],
                    "entries": [
                        {
                            "id": index + 11,
                            "definitionId": index + 21,
                            "spellId": index + 101,
                            "name": "Fixture talent",
                            "maxRank": 1,
                            "entryType": 1,
                            "descriptionTemplate": text,
                            "effects": [],
                        }
                    ],
                }
            )
        doc = write(
            "normalized",
            token,
            {
                **identity,
                "class": {"id": class_id, "token": token.upper(), "name": token},
                "trees": [
                    {
                        "id": class_id,
                        "pointBudget": {"currencyId": class_id, "maxPoints": 2},
                        "layout": {"xStep": 100, "yStep": 100},
                        "sections": [{"index": 0, "name": None, "nodeIds": [1, 2]}],
                        "nodes": nodes,
                        "visualOnlyEdges": [],
                    }
                ],
                "generatedAt": STAMP,
                "provenance": {"source": "synthetic-fixture", "tables": []},
            },
            TalentDataDocument,
        )
        classes.append(
            {
                "token": token.upper(),
                "file": f"{token}.json",
                "treeIds": [class_id],
                "contentHash": doc["contentHash"],
                "validation": {
                    "errors": 0,
                    "warnings": 1,
                    "warningsByCode": {"SECTION_NOT_GATE_COUNTED": 1},
                },
            }
        )
        sidecar = {
            **identity,
            "classToken": token.upper(),
            "talentDataContentHash": doc["contentHash"],
        }
        write(
            "visuals",
            token,
            {
                **sidecar,
                "entries": [
                    {
                        "entryId": i + 11,
                        "definitionId": i + 21,
                        "spellId": i + 101,
                        "displaySpellId": i + 101,
                        "iconFileDataId": icon,
                    }
                    for i, icon in enumerate(ICONS)
                ],
                "provenance": {
                    "source": "synthetic-fixture",
                    "tables": [],
                    "iconColumn": "fixture",
                },
            },
            TalentVisualsDocument,
        )
        tooltips = write(
            "tooltips",
            token,
            {
                **sidecar,
                "entries": [
                    {
                        "entryId": i + 11,
                        "definitionId": i + 21,
                        "spellId": i + 101,
                        "maxRank": 1,
                        "ranks": [
                            {
                                "rank": 1,
                                "status": "resolved",
                                "segments": [{"kind": "text", "text": text}],
                                "gaps": [],
                            }
                        ],
                    }
                    for i in range(2)
                ],
                "provenance": {"source": "synthetic-fixture", "tables": [], "renderer": "fixture"},
            },
            TalentTooltipsDocument,
        )
        write(
            "localization",
            f"ruRU/{token}",
            {
                **sidecar,
                "locale": "ruRU",
                "tooltipsContentHash": tooltips["contentHash"],
                "entries": [
                    {
                        "entryId": i + 11,
                        "nodeId": i + 1,
                        "definitionId": i + 21,
                        "spellId": i + 101,
                        "textSpellId": i + 101,
                        "maxRank": 1,
                        "name": {
                            "status": "translated",
                            "text": "Тест",
                            "source": "SpellName.Name_lang",
                        },
                        "description": {
                            "status": "rendered",
                            "source": "Spell.Description_lang",
                            "ranks": [{"rank": 1, "segments": [{"kind": "text", "text": "Тест"}]}],
                        },
                    }
                    for i in range(2)
                ],
                "provenance": {"source": "synthetic-fixture", "renderer": "fixture", "tables": []},
            },
            TalentLocalizationDocument,
        )
    write(
        "normalized",
        "manifest",
        {**identity, "generatedAt": STAMP, "source": "synthetic-fixture", "classes": classes},
        DatasetManifest,
    )
    icons = []
    for file_id in ICONS:
        source = (FIXTURES / f"casc/forever/{NEW_BUILD}/{file_id}.blp").read_bytes()
        converted = convert_blp2(file_id, source)
        path = root / f"data/visuals/forever/{build}/icons/{file_id}.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(converted.png)
        icons.append(
            {
                "fileDataId": file_id,
                "file": f"icons/{file_id}.png",
                "width": converted.width,
                "height": converted.height,
                "byteLength": len(converted.png),
                "sha256": sha256_of(converted.png),
                "sourceFormat": "BLP2",
                "sourceByteLength": len(source),
                "sourceSha256": converted.source_sha256,
            }
        )
    write(
        "visuals",
        "icons",
        {
            **identity,
            "imageFormat": "png",
            "icons": icons,
            "provenance": {
                "source": "synthetic-fixture",
                "retrieval": "fixture BLPs",
                "converter": "fixture",
            },
        },
        TalentIconRegistry,
    )
