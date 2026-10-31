"""Read-only release preparation and draft PR entry point (PR B, no publisher)."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from talent_data.ota.automation.prepare import main

if __name__ == "__main__":
    raise SystemExit(main())
