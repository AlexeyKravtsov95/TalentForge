#!/usr/bin/env python3
"""Is the app's product dataset still the latest publishable Forever build?

Reads ``PRODUCT_BUNDLE`` from the app (``BundledDatasets.kt``, via
``scripts/mobile_bundles.py``) and runs ``python -m talent_data builds freshness`` with it:
one ``product-freshness`` v1 JSON document on stdout (and in ``--output`` when given), a
one-line summary on stderr, and the status as the exit code:

    0  CURRENT   the product build is the latest publishable build
    3  OUTDATED  a newer publishable build exists - refresh the product dataset
    4  UNKNOWN   the answer could not be established (network/HTTP/schema failure, unknown
                 is_bgdl of a newer build, no publishable build, product ahead of the source)

Needs the network (wago.tools) and the installed ``talent_data`` package. It only reads:
nothing is fetched into the repository, published, committed or merged. Intended for the
scheduled watcher (M5.DATA.WATCH) and for a manual check before a release.

Usage:
    python scripts/check_product_freshness.py
    python scripts/check_product_freshness.py --output artifacts/freshness.json
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from mobile_bundles import BUNDLED_BUILDS, GAME, product_build

from talent_data.cli.main import main as talent_data_main


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", type=Path, help="also write the JSON result to this file")
    args = parser.parse_args(argv)
    build = product_build()
    if build not in BUNDLED_BUILDS:
        print(
            f"PRODUCT_BUNDLE {build} is not in BUNDLED_BUILDS {BUNDLED_BUILDS} "
            "(scripts/mobile_bundles.py)",
            file=sys.stderr,
        )
        return 1
    forwarded = ["builds", "freshness", "--game", GAME, "--product-build", build]
    if args.output is not None:
        forwarded += ["--output", str(args.output)]
    return talent_data_main(forwarded)


if __name__ == "__main__":
    raise SystemExit(main())
