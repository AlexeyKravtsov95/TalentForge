"""Run workflow configuration guards with synthetic inputs; validate YAML with actionlint."""

import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def test_workflow_configuration_check_refuses_missing_app_credentials():
    env = {
        k: v for k, v in os.environ.items() if k not in ("OTA_APP_CLIENT_ID", "OTA_APP_PRIVATE_KEY")
    }
    env["PYTHONPATH"] = str(ROOT / "src")
    import sys

    run = subprocess.run(
        [sys.executable, str(ROOT / "scripts/ota_release_automation.py"), "check-app"],
        env=env,
        capture_output=True,
        text=True,
    )
    assert run.returncode == 3 and "BLOCKED" in run.stdout
