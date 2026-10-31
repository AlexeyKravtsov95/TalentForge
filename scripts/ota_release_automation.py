"""Release preparation and guarded production automation entry point."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from talent_data.ota.automation.cli import main

if __name__ == "__main__":
    raise SystemExit(main())
