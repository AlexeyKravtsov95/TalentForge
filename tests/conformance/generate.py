"""Regenerate the committed allocation conformance vectors.

python -m tests.conformance.generate          # writes data/conformance/...
python -m tests.conformance.generate --check  # exit 1 if the committed file drifted
"""

from __future__ import annotations

import argparse
import sys

from talent_data.paths import conformance_path
from tests.conformance.scenarios import BASELINE, GAME, ROOT, build_suite


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="only compare, do not write")
    args = parser.parse_args(argv)
    path = conformance_path(GAME, BASELINE)
    if not path.is_absolute():
        path = ROOT / path
    text = build_suite().to_json()
    if args.check:
        current = path.read_text(encoding="utf-8") if path.is_file() else ""
        if current != text:
            print(f"{path} is out of date; run python -m tests.conformance.generate")
            return 1
        print(f"{path} is up to date")
        return 0
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    print(f"wrote {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
