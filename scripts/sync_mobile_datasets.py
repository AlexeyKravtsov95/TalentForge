#!/usr/bin/env python3
"""Synchronize the canonical Forever datasets into the mobile runtime bundle.

The canonical source of truth is ``data/normalized/forever/<build>/`` (manifest plus nine
class documents). The mobile application packages a copy of those files, for every
bundled build (``BUNDLED_BUILDS`` in ``scripts/mobile_bundles.py``), as Compose
Multiplatform resources; that copy must be deterministically derived and byte-identical to
the canonical files, otherwise the app would silently run against stale or hand-edited
data. A sync never removes the bundle of another build: saved builds open on their own
build.

Modes:
    (default)  mirror the canonical directories of the bundled builds into the bundle
    --check    fail (exit 1) if the bundle differs from the canonical data - a missing,
               changed or extra file, or a bundled build that is not listed; offline, used
               by CI to catch dataset drift

Usage:
    python scripts/sync_mobile_datasets.py
    python scripts/sync_mobile_datasets.py --check
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from mobile_bundles import DATASET, run


def main(argv: list[str] | None = None) -> int:
    return run(DATASET, argv, doc=__doc__)


if __name__ == "__main__":
    raise SystemExit(main())
