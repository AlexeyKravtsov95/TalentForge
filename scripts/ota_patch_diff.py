"""Record exact before/after presentation changes and allocation compatibility of a patch."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from talent_data.ota.automation.data_only import allocation_shape
from talent_data.ota.verify import strict_json
from talent_data.pipeline import read_document

METADATA = {
    "clientBuild",
    "contentHash",
    "talentDataContentHash",
    "tooltipsContentHash",
    "generatedAt",
    "provenance",
}


def changes(before: object, after: object, path: str = "") -> list[dict[str, object]]:
    if type(before) is type(after) and before == after:
        return []
    if isinstance(before, dict) and isinstance(after, dict) and before.keys() == after.keys():
        return [c for key in before for c in changes(before[key], after[key], f"{path}/{key}")]
    if isinstance(before, list) and isinstance(after, list) and len(before) == len(after):
        return [
            c
            for i, (a, b) in enumerate(zip(before, after, strict=True))
            for c in changes(a, b, f"{path}/{i}")
        ]
    return [{"path": path, "before": before, "after": after}]


def report(root: Path, base: str, target: str) -> dict[str, object]:
    rows = []
    inventory = json.loads((root / f"data/normalized/forever/{target}/manifest.json").read_bytes())
    for entry in inventory["classes"]:
        name = entry["file"]
        old = read_document(root / f"data/normalized/forever/{base}" / name)
        new = read_document(root / f"data/normalized/forever/{target}" / name)
        row: dict[str, object] = {
            "classToken": entry["token"],
            "savedAllocationShapeUnchanged": allocation_shape(old) == allocation_shape(new),
        }
        for kind, sub in (
            ("normalized", ""),
            ("visuals", ""),
            ("tooltips", ""),
            ("localization", "ruRU/"),
        ):
            docs = []
            for build in (base, target):
                raw = strict_json((root / f"data/{kind}/forever/{build}/{sub}{name}").read_bytes())
                assert isinstance(raw, dict)
                docs.append({k: v for k, v in raw.items() if k not in METADATA})
            row[kind] = changes(*docs)
        rows.append(row)
    return {
        "schemaVersion": 1,
        "kind": "talentforge-patch-diff",
        "fromBuild": base,
        "toBuild": target,
        "classes": rows,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-build", required=True)
    parser.add_argument("--to-build", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    document = report(Path.cwd(), args.from_build, args.to_build)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
