#!/usr/bin/env python3
"""Synchronize the canonical Forever dataset into the mobile runtime bundle.

The canonical source of truth is ``data/normalized/forever/<build>/``. The
mobile application packages a copy of those files as Compose Multiplatform
resources; that copy must be deterministically derived and byte-identical to
the canonical files, otherwise the app would silently run against stale or
hand-edited data.

Modes:
    (default)  copy canonical files into the bundled resource directory
    --check    fail (exit 1) if bundled files differ byte-for-byte from the
               canonical source; used by CI to catch dataset drift

Usage:
    python scripts/sync_mobile_datasets.py
    python scripts/sync_mobile_datasets.py --check
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

GAME = "forever"
BASELINE_BUILD = "1.60.1.70009"
BASELINE_CLASS_FILES = (
    "druid.json",
    "hunter.json",
    "mage.json",
    "paladin.json",
    "priest.json",
    "rogue.json",
    "shaman.json",
    "warlock.json",
    "warrior.json",
)
BUNDLED_FILE_NAMES = ("manifest.json", *BASELINE_CLASS_FILES)

REPO_ROOT = Path(__file__).resolve().parent.parent
CANONICAL_DIR = REPO_ROOT / "data" / "normalized" / GAME / BASELINE_BUILD
BUNDLED_DIR = (
    REPO_ROOT
    / "mobile"
    / "composeApp"
    / "src"
    / "commonMain"
    / "composeResources"
    / "files"
    / "dataset"
    / GAME
    / BASELINE_BUILD
)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify the bundle matches the canonical dataset instead of copying",
    )
    return parser.parse_args(argv)


def canonical_paths() -> dict[str, Path]:
    return {name: CANONICAL_DIR / name for name in BUNDLED_FILE_NAMES}


def bundled_paths() -> dict[str, Path]:
    return {name: BUNDLED_DIR / name for name in BUNDLED_FILE_NAMES}


def check() -> int:
    problems: list[str] = []
    for name, canonical in canonical_paths().items():
        bundled = bundled_paths()[name]
        if not bundled.is_file():
            problems.append(f"{name}: missing from bundle ({bundled})")
        elif bundled.read_bytes() != canonical.read_bytes():
            problems.append(f"{name}: bundle differs from canonical {canonical}")
    if problems:
        print("mobile dataset bundle is out of sync with canonical data:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        print("run: python scripts/sync_mobile_datasets.py", file=sys.stderr)
        return 1
    print(f"bundle in sync: {len(BUNDLED_FILE_NAMES)} files match {CANONICAL_DIR}")
    return 0


def sync() -> int:
    BUNDLED_DIR.mkdir(parents=True, exist_ok=True)
    for name, canonical in canonical_paths().items():
        target = bundled_paths()[name]
        target.write_bytes(canonical.read_bytes())
        print(f"copied {name} -> {target.relative_to(REPO_ROOT)}")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    missing = [str(path) for path in canonical_paths().values() if not path.is_file()]
    if missing:
        print("canonical dataset files are missing:", file=sys.stderr)
        for path in missing:
            print(f"  - {path}", file=sys.stderr)
        return 1
    return check() if args.check else sync()


if __name__ == "__main__":
    raise SystemExit(main())
