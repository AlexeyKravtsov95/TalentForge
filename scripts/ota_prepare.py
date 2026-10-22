#!/usr/bin/env python3
"""Prepare a new Forever build for a later OTA release (M5.OTA.AUTO.1). Never publishes.

``detect`` reads ``PRODUCT_BUNDLE`` from the app (``BundledDatasets.kt``, via
``scripts/mobile_bundles.py``, like ``check_product_freshness.py``) and decides from the
wago.tools build index and the signed production catalog whether a build needs preparing;
``prepare`` runs the existing pipeline, checks and publisher gates over it in a disposable
clone. Logic and exit codes: ``talent_data.prepare.runner``; runbook:
``docs/ota/auto-prepare.md``.

Usage:
    python scripts/ota_prepare.py detect --output prep/detection.json [--build B] [--force]
    python scripts/ota_prepare.py prepare --detection prep/detection.json --out prep/out
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from mobile_bundles import BUNDLED_BUILDS, product_build

from talent_data.prepare.runner import main as runner_main


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if args[:1] == ["detect"]:
        build = product_build()
        if build not in BUNDLED_BUILDS:
            print(
                f"PRODUCT_BUNDLE {build} is not in BUNDLED_BUILDS {BUNDLED_BUILDS} "
                "(scripts/mobile_bundles.py)",
                file=sys.stderr,
            )
            return 1
        args += ["--product-build", build]
    return runner_main(args)


if __name__ == "__main__":
    raise SystemExit(main())
