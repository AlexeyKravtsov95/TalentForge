"""Read-only preflight of the production OTA origin (M5.OTA.PROD.1).

Runs :mod:`talent_data.ota.preflight` from a checkout (``pip install -e '.[ota]'`` or the
``src`` path below). Anonymous ``GET``/``HEAD`` only; nothing is written anywhere::

    python scripts/ota_production_preflight.py                       # before publication
    python scripts/ota_production_preflight.py --expect published \\
        --keyring docs/ota/keys/production-keyring.json --json preflight.json

Procedure and expected results: ``docs/ota/production-deployment.md``.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from talent_data.ota.preflight import main

if __name__ == "__main__":
    raise SystemExit(main())
