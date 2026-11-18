#!/usr/bin/env python3
"""Bound one simulator command, retaining partial output and stage timings without Xcode deps."""

from __future__ import annotations

import argparse
import json
import math
import os
import signal
import subprocess
import sys
import time
from contextlib import suppress
from pathlib import Path


def positive_seconds(value: str) -> float:
    seconds = float(value)
    if not math.isfinite(seconds) or seconds <= 0:
        raise argparse.ArgumentTypeError("timeout must be finite and positive")
    return seconds


def stop_group(process: subprocess.Popen[bytes]) -> None:
    # simctl may leave descendants behind even after its direct child exits on TERM.
    for sig in (signal.SIGTERM, signal.SIGKILL):
        with suppress(ProcessLookupError):
            os.killpg(process.pid, sig)
        if sig == signal.SIGTERM:
            with suppress(subprocess.TimeoutExpired):
                process.wait(timeout=2)
    process.wait(timeout=2)


def run(stage: str, timeout: float, output: Path, command: list[str]) -> int:
    output.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    process = None
    status, rc = "error", 1

    def interrupted(signum: int, _frame: object) -> None:
        raise InterruptedError(signum)

    old_handlers = {s: signal.signal(s, interrupted) for s in (signal.SIGTERM, signal.SIGINT)}
    print(f"START [{stage}] timeout={timeout:g}s command={command!r}", file=sys.stderr, flush=True)
    try:
        with (
            (output / f"{stage}.stdout.log").open("wb") as stdout,
            (output / f"{stage}.stderr.log").open("wb") as stderr,
        ):
            process = subprocess.Popen(
                command, stdout=stdout, stderr=stderr, start_new_session=True
            )
            while True:
                remaining = timeout - (time.monotonic() - started)
                if remaining <= 0:
                    status, rc = "timeout", 124
                    stop_group(process)
                    break
                try:
                    code = process.wait(timeout=min(10, remaining))
                    rc = code if code >= 0 else 128 - code
                    status = "success" if rc == 0 else "error"
                    break
                except subprocess.TimeoutExpired:
                    print(
                        f"WAIT [{stage}] elapsed={time.monotonic() - started:.1f}s",
                        file=sys.stderr,
                        flush=True,
                    )
    except InterruptedError as exc:
        status, rc = "cancelled", 128 + int(exc.args[0])
        if process is not None:
            stop_group(process)
    except OSError as exc:
        with (output / f"{stage}.stderr.log").open("a") as error_log:
            error_log.write(f"{exc}\n")
    finally:
        elapsed = time.monotonic() - started
        with (output / "timings.jsonl").open("a") as report:
            report.write(
                json.dumps({"stage": stage, "status": status, "exitCode": rc, "seconds": elapsed})
                + "\n"
            )
        for sig, handler in old_handlers.items():
            signal.signal(sig, handler)
        print(
            f"END [{stage}] status={status} rc={rc} elapsed={elapsed:.1f}s logs={output}",
            file=sys.stderr,
            flush=True,
        )
    return rc


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", required=True)
    parser.add_argument("--timeout", type=positive_seconds, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command
    if command[:1] == ["--"]:
        command = command[1:]
    if not command or not args.stage.replace("-", "").isalnum():
        parser.error("a command and an alphanumeric/hyphen stage name are required")
    return run(args.stage, args.timeout, args.output, command)


if __name__ == "__main__":
    sys.exit(main())
