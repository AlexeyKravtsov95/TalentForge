"""Real subprocess watchdog regressions, independent of Xcode and the runner image."""

from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/ios_smoke_command.py"


def command(output: Path, code: str, timeout: str = "1") -> list[str]:
    return [
        sys.executable,
        str(SCRIPT),
        "--stage",
        "test",
        "--timeout",
        timeout,
        "--output",
        str(output),
        "--",
        sys.executable,
        "-u",
        "-c",
        code,
    ]


@pytest.mark.parametrize("exit_code", [0, 7, 149])
def test_exit_status_output_and_timing_are_preserved(tmp_path: Path, exit_code: int) -> None:
    result = subprocess.run(
        command(
            tmp_path, f'import sys; print("out"); print("err", file=sys.stderr); exit({exit_code})'
        ),
        capture_output=True,
        text=True,
        timeout=10,
        check=False,
    )
    assert result.returncode == exit_code
    assert (tmp_path / "test.stdout.log").read_text() == "out\n"
    assert (tmp_path / "test.stderr.log").read_text() == "err\n"
    report = json.loads((tmp_path / "timings.jsonl").read_text())
    assert report["exitCode"] == exit_code
    assert report["seconds"] < 5
    assert report["status"] == ("success" if exit_code == 0 else "error")
    assert "START [test]" in result.stderr and "END [test]" in result.stderr


def test_timeout_kills_the_process_group_and_retains_partial_logs(tmp_path: Path) -> None:
    child_pid = tmp_path / "child.pid"
    code = (
        "import subprocess, signal, time; "
        "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
        "p=subprocess.Popen(['sleep','60']); "
        f"open({str(child_pid)!r},'w').write(str(p.pid)); "
        "print('partial output', flush=True); time.sleep(60)"
    )
    started = time.monotonic()
    result = subprocess.run(command(tmp_path, code), capture_output=True, timeout=10, check=False)
    assert result.returncode == 124
    assert time.monotonic() - started < 8
    assert (tmp_path / "test.stdout.log").read_text() == "partial output\n"
    assert json.loads((tmp_path / "timings.jsonl").read_text())["status"] == "timeout"
    # A killed descendant may briefly remain a zombie until the host reaps it.
    state = subprocess.run(
        ["ps", "-o", "stat=", "-p", child_pid.read_text()],
        capture_output=True,
        text=True,
        check=False,
    ).stdout.strip()
    assert not state or state.startswith("Z")


def test_cancellation_retains_evidence_and_stops_child(tmp_path: Path) -> None:
    child_pid = tmp_path / "child.pid"
    code = (
        f'import os,time; open({str(child_pid)!r},"w").write(str(os.getpid())); '
        'print("before cancellation",flush=True); time.sleep(60)'
    )
    process = subprocess.Popen(
        command(tmp_path, code, "60"), stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    try:
        deadline = time.monotonic() + 5
        while not child_pid.exists() and time.monotonic() < deadline:
            time.sleep(0.02)
        assert child_pid.exists()
        process.send_signal(signal.SIGTERM)
        process.communicate(timeout=8)
        assert process.returncode == 143
        assert (tmp_path / "test.stdout.log").read_text() == "before cancellation\n"
        assert json.loads((tmp_path / "timings.jsonl").read_text())["status"] == "cancelled"
        with pytest.raises(ProcessLookupError):
            os.kill(int(child_pid.read_text()), 0)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()


@pytest.mark.parametrize("timeout", ["0", "-1", "nan", "inf"])
def test_invalid_deadlines_never_execute_commands(tmp_path: Path, timeout: str) -> None:
    result = subprocess.run(
        command(tmp_path, 'print("must not run")', timeout), check=False, capture_output=True
    )
    assert result.returncode == 2
    assert not (tmp_path / "timings.jsonl").exists()


def test_reusing_output_does_not_reuse_previous_command_output(tmp_path: Path) -> None:
    for code in ['print("old output")', "pass"]:
        result = subprocess.run(
            command(tmp_path, code), capture_output=True, check=False, timeout=10
        )
        assert result.returncode == 0
    assert (tmp_path / "test.stdout.log").read_bytes() == b""
    assert len((tmp_path / "timings.jsonl").read_text().splitlines()) == 2


def test_missing_executable_is_an_error_with_diagnostics(tmp_path: Path) -> None:
    args = command(tmp_path, "pass")
    args[args.index("--") + 1 :] = [str(tmp_path / "missing-program")]
    result = subprocess.run(args, capture_output=True, check=False, timeout=10)
    assert result.returncode == 1
    assert (tmp_path / "test.stderr.log").stat().st_size > 0
    assert json.loads((tmp_path / "timings.jsonl").read_text())["status"] == "error"
