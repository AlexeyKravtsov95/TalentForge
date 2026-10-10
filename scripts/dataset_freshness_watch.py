#!/usr/bin/env python3
"""Apply a product-freshness result to the tracked GitHub Issue (M5.DATA.WATCH).

Runs after ``scripts/check_product_freshness.py`` in
``.github/workflows/dataset-freshness.yml``; the logic lives in
``talent_data.watch.runner`` (exit codes documented there and in
``docs/dataset-freshness-watch.md``). Needs ``GITHUB_TOKEN`` with ``issues: write``.

Usage:
    python scripts/dataset_freshness_watch.py --report freshness/freshness.json \
        --check-exit-code 3 [--repository owner/name --run-url URL]
"""

from __future__ import annotations

from talent_data.watch.runner import main

if __name__ == "__main__":
    raise SystemExit(main())
