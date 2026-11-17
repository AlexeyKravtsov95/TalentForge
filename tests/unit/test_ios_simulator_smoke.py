"""The running-app check of ``scripts/ios_simulator_smoke.sh`` (M5.IOS.1).

mobile-ci run 39910279665 failed the iPhone 18 Pro Max smoke with "Child process terminated
with signal 13: Broken pipe": ``simctl spawn … launchctl list | grep -q <app>`` under
``pipefail``. grep stops reading at its first match, launchctl dies of SIGPIPE while still
writing, and pipefail turns the found app into "not running". These tests drive the script's
functions (the script is sourced; nothing runs ``main``) against a fake ``xcrun`` whose
``launchctl list`` is a large stream that dies of SIGPIPE exactly like the real one, with the
app's line near the start. They need only bash and Python, no Xcode.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "ios_simulator_smoke.sh"
BUNDLE = "io.github.alexeykravtsov95.talentforge"
APP_LINE = f"4242\t0\tUIKitApplication:{BUNDLE}[3c5b][rb-legacy]"
FILLER_LINES = 200_000  # ~10 MB: far beyond any pipe buffer

FAKE_XCRUN = f"""#!/usr/bin/env python3
import os, signal, sys
# like launchctl: a write to a closed pipe kills the process (no Python exception)
signal.signal(signal.SIGPIPE, signal.SIG_DFL)
calls = os.environ.get("FAKE_CALLS")
if calls:
    with open(calls, "a") as handle:
        handle.write(" ".join(sys.argv[1:]) + "\\n")
if sys.argv[1:3] != ["simctl", "spawn"] or sys.argv[4:] != ["launchctl", "list"]:
    sys.exit(64)
mode = os.environ.get("FAKE_MODE", "running")
if mode == "error" or (mode == "error-then-running" and open(calls).read().count("launchctl") == 1):
    sys.stderr.write("An error was encountered processing the command (code=149)\\n")
    sys.exit(149)
out = sys.stdout
out.write("PID\\tStatus\\tLabel\\n-\\t0\\tcom.apple.progressd\\n")
app = {{
    "running": {APP_LINE!r},
    "error-then-running": {APP_LINE!r},
    "dead": "-\\t0\\tUIKitApplication:{BUNDLE}[3c5b][rb-legacy]",
    "absent": None,
    "prefix": "4243\\t0\\tUIKitApplication:{BUNDLE}.widget[9a9a][rb-legacy]",
}}[mode]
if app:
    out.write(app + "\\n")
for index in range({FILLER_LINES}):
    out.write(f"{{1000 + index}}\\t0\\tcom.apple.filler.service{{index}}\\n")
out.flush()
"""


@pytest.fixture
def fake_env(tmp_path: Path) -> dict[str, str]:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    xcrun = bin_dir / "xcrun"
    xcrun.write_text(FAKE_XCRUN)
    xcrun.chmod(0o755)
    env = dict(os.environ)
    env["PATH"] = f"{bin_dir}{os.pathsep}{env['PATH']}"
    env["FAKE_CALLS"] = str(tmp_path / "calls.txt")
    return env


def bash(command: str, env: dict[str, str], mode: str) -> subprocess.CompletedProcess[str]:
    bash_path = shutil.which("bash")
    assert bash_path is not None
    return subprocess.run(
        [bash_path, "-c", command],
        env={**env, "FAKE_MODE": mode},
        capture_output=True,
        text=True,
        timeout=120,
        check=False,
    )


def sourced(call: str) -> str:
    """Source the script (functions only) and print the call's exit status as ``rc=N``."""
    return f'source "{SCRIPT}"; rc=0; {call} || rc=$?; echo "rc=$rc"'


def calls(env: dict[str, str]) -> int:
    path = Path(env["FAKE_CALLS"])
    return path.read_text().count("launchctl") if path.exists() else 0


def test_the_old_pipeline_reports_a_running_app_as_missing(fake_env: dict[str, str]) -> None:
    """Reproduces the CI defect: the app line is found, yet pipefail fails the check."""
    old = f'xcrun simctl spawn U launchctl list | grep -q "UIKitApplication:{BUNDLE}"'
    assert bash(old, fake_env, "running").returncode == 0  # grep did find the app
    result = bash("set -o pipefail; " + old, fake_env, "running")
    assert result.returncode == 141  # 128 + SIGPIPE: the writer's status, not grep's


def test_a_running_app_early_in_a_large_listing_is_found(
    fake_env: dict[str, str], tmp_path: Path
) -> None:
    listing = tmp_path / "launchctl.txt"
    result = bash(sourced(f'check_app_running U {BUNDLE} "{listing}"'), fake_env, "running")
    assert result.stdout.splitlines() == ["4242", "rc=0"], result.stderr
    # the whole stream was read: the last filler line is in the saved listing
    assert listing.read_text().rstrip().endswith(f"com.apple.filler.service{FILLER_LINES - 1}")


@pytest.mark.parametrize("mode", ["absent", "dead", "prefix"])
def test_a_missing_dead_or_other_app_is_not_running(
    fake_env: dict[str, str], tmp_path: Path, mode: str
) -> None:
    listing = tmp_path / "launchctl.txt"
    result = bash(sourced(f'check_app_running U {BUNDLE} "{listing}"'), fake_env, mode)
    assert result.stdout.splitlines() == ["rc=1"], result.stderr


def test_a_failing_simctl_is_an_error_not_an_absent_app(
    fake_env: dict[str, str], tmp_path: Path
) -> None:
    listing = tmp_path / "launchctl.txt"
    result = bash(sourced(f'check_app_running U {BUNDLE} "{listing}"'), fake_env, "error")
    assert result.stdout.splitlines() == ["rc=2"]
    assert "exited with 149" in result.stderr


def test_a_failed_check_is_retried_a_bounded_number_of_times(
    fake_env: dict[str, str], tmp_path: Path
) -> None:
    listing = tmp_path / "launchctl.txt"
    result = bash(sourced(f'wait_for_running_app U {BUNDLE} "{listing}" 3 0'), fake_env, "error")
    assert result.stdout.splitlines() == ["rc=2"]
    assert calls(fake_env) == 3


def test_a_transient_simctl_failure_then_a_running_app_passes(
    fake_env: dict[str, str], tmp_path: Path
) -> None:
    listing = tmp_path / "launchctl.txt"
    command = sourced(f'wait_for_running_app U {BUNDLE} "{listing}" 3 0')
    result = bash(command, fake_env, "error-then-running")
    assert result.stdout.splitlines() == ["4242", "rc=0"], result.stderr
    assert calls(fake_env) == 2


def test_a_missing_app_is_final_and_never_retried_into_a_pass(
    fake_env: dict[str, str], tmp_path: Path
) -> None:
    listing = tmp_path / "launchctl.txt"
    result = bash(sourced(f'wait_for_running_app U {BUNDLE} "{listing}" 3 0'), fake_env, "dead")
    assert result.stdout.splitlines() == ["rc=1"]
    assert calls(fake_env) == 1


def test_sourcing_the_script_runs_nothing(fake_env: dict[str, str]) -> None:
    result = bash(f'source "{SCRIPT}"; echo sourced', fake_env, "running")
    assert result.stdout.splitlines() == ["sourced"]
    assert calls(fake_env) == 0


SMOKE_XCRUN = r"""#!/usr/bin/env python3
import json, os, sys, time
from pathlib import Path
args = sys.argv[2:]
stage = args[0]
if args[:2] == ['list', 'devices']:
    stage = 'discovery'
elif args[0] == 'io':
    stage = 'screenshot'
elif args[0] == 'spawn':
    stage = 'processes' if args[2:4] == ['launchctl', 'list'] else 'app-log'
print('output from ' + stage, file=sys.stderr, flush=True)
if stage == os.environ.get('FAIL_STAGE'):
    print('partial ' + stage, flush=True)
    if os.environ['FAIL_MODE'] == 'timeout':
        time.sleep(60)
    sys.exit(149)
if stage == 'discovery':
    print(json.dumps({'devices': {'com.apple.CoreSimulator.SimRuntime.iOS-27-2': [
        {'name': 'iPad Pro 13-inch (M5)', 'udid': 'IPAD'}]}}))
elif stage == 'processes':
    print('4242\t0\tUIKitApplication:io.github.alexeykravtsov95.talentforge[3c5b]')
elif stage == 'screenshot' and not os.environ.get('NO_SCREENSHOT'):
    Path(args[-1]).write_bytes(b'fake screenshot')
"""


def smoke(
    fake_env: dict[str, str], tmp_path: Path, **settings: str
) -> subprocess.CompletedProcess[str]:
    xcrun = Path(fake_env["PATH"].split(os.pathsep)[0]) / "xcrun"
    xcrun.write_text(SMOKE_XCRUN)
    # Avoid reading the test host's unified log; exercise the same bounded diagnostic path.
    call = f'''source "{SCRIPT}"
    eval "$(declare -f bounded_command | sed '1s/bounded_command/real_bounded_command/')"
    bounded_command() {{
      if [ "$1" = simulator-log ]; then
        real_bounded_command "$1" "$2" echo fake-host-log
      else
        real_bounded_command "$@"
      fi
    }}
    rc=0
    smoke_device fake.app "{tmp_path}/evidence" 'iPad Pro 13-inch (M5)' {BUNDLE} iosApp || rc=$?
    echo "rc=$rc"
    '''
    env = {
        **fake_env,
        "TALENTFORGE_SMOKE_WAIT_SECONDS": "0",
        "TALENTFORGE_SMOKE_BOOT_TIMEOUT": "5",
        "TALENTFORGE_SMOKE_INSTALL_TIMEOUT": "5",
        "TALENTFORGE_SMOKE_LAUNCH_TIMEOUT": "5",
        "TALENTFORGE_SMOKE_SCREENSHOT_TIMEOUT": "5",
        "TALENTFORGE_SMOKE_QUERY_TIMEOUT": "5",
        **settings,
    }
    return bash(call, env, "running")


@pytest.mark.parametrize(
    "stage", ["discovery", "bootstatus", "install", "launch", "screenshot", "processes"]
)
@pytest.mark.parametrize("mode", ["timeout", "error"])
def test_each_stage_fails_closed_and_retains_diagnostics(
    fake_env: dict[str, str], tmp_path: Path, stage: str, mode: str
) -> None:
    timeout_setting = {
        "discovery": "QUERY",
        "processes": "QUERY",
        "bootstatus": "BOOT",
        "install": "INSTALL",
        "launch": "LAUNCH",
        "screenshot": "SCREENSHOT",
    }[stage]
    result = smoke(
        fake_env,
        tmp_path,
        FAIL_STAGE=stage,
        FAIL_MODE=mode,
        **{f"TALENTFORGE_SMOKE_{timeout_setting}_TIMEOUT": "1"},
    )
    assert "rc=1" in result.stdout, result.stderr
    evidence = tmp_path / "evidence/iPad-Pro-13-inch-M5-diagnostics"
    reports = [json.loads(line) for line in (evidence / "timings.jsonl").read_text().splitlines()]
    failed = [r for r in reports if r["stage"].startswith(stage)]
    assert failed and all(r["status"] == mode for r in failed)
    assert all(r["exitCode"] == (124 if mode == "timeout" else 149) for r in failed)
    assert (evidence / "app-log.stderr.log").exists()
    assert (evidence / "devices.stdout.log").exists()
    assert (evidence / "host-processes.stdout.log").exists()
    if stage != "discovery":
        assert (evidence / "shutdown.stderr.log").exists()
    if stage in ("discovery", "bootstatus", "install"):
        assert not any(r["stage"] == "launch" for r in reports)


def test_smoke_requires_real_screenshot_evidence(fake_env: dict[str, str], tmp_path: Path) -> None:
    result = smoke(fake_env, tmp_path, NO_SCREENSHOT="1")
    assert "rc=1" in result.stdout
    assert "PASS" not in result.stdout


def test_success_keeps_stage_timings_and_screenshot(
    fake_env: dict[str, str], tmp_path: Path
) -> None:
    result = smoke(fake_env, tmp_path)
    assert "rc=0" in result.stdout and "PASS [iPad" in result.stdout, result.stderr
    assert (tmp_path / "evidence/iPad-Pro-13-inch-M5.png").stat().st_size > 0
    reports = [
        json.loads(line)
        for line in (tmp_path / "evidence/iPad-Pro-13-inch-M5-diagnostics/timings.jsonl")
        .read_text()
        .splitlines()
    ]
    assert {r["stage"] for r in reports} >= {
        "discovery",
        "bootstatus",
        "install",
        "launch",
        "settle",
        "processes-1",
        "screenshot",
        "terminate",
        "shutdown",
    }
    assert all(r["status"] == "success" for r in reports)


def test_old_screenshot_cannot_turn_an_empty_capture_into_a_pass(
    fake_env: dict[str, str], tmp_path: Path
) -> None:
    old = tmp_path / "evidence/iPad-Pro-13-inch-M5.png"
    old.parent.mkdir()
    old.write_bytes(b"old evidence")
    result = smoke(fake_env, tmp_path, NO_SCREENSHOT="1")
    assert "rc=1" in result.stdout and not old.exists()
