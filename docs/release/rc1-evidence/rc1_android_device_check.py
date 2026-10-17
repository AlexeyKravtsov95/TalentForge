#!/usr/bin/env python3
"""M5.RC.1 Android device checks on the ISOLATED ``.devcheck`` package only.

Reuses the adb/uiautomator helpers of ``scripts/android_section_check.py``. The user's real
TalentForge app and its saved builds are never touched: the ``.devcheck`` APK is installed,
only that package's data is cleared, and it is uninstalled at the end.

Scenarios (each ends with force-stop + relaunch + "Continue editing" and reads the counter):

* S1 rapid spend x3 on Improved Rend (maxRank 3) + immediate double Back   -> ``3 / 51``
* S2 rapid spend x3 + Undo + immediate double Back (new build)              -> ``2 / 51``
* S3 rapid spend x3 + immediate rotation (Activity recreation), then Back   -> ``3 / 51``

``--screens`` (emulator only) also captures Home/editor at font scale 1.0/1.5/2.0 and at a
compact 320x568 dp override, restoring font scale and display size afterwards.

Build the isolated APK first (from ``mobile/``)::

    ./gradlew :composeApp:assembleDebug -Ptalentforge.applicationIdSuffix=.devcheck

then::

    python3 docs/release/rc1-evidence/rc1_android_device_check.py --serial <adb serial> \
        --out .cache/rc1-device [--screens]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "scripts"))
import android_section_check as asc  # noqa: E402

COUNTER_RE = re.compile(r"^(\d+) / 51$")
APK = REPO / "mobile/composeApp/build/outputs/apk/debug/composeApp-debug.apk"
TAPS_TO_MAX = 3


def counter(ui: asc.Hierarchy) -> str | None:
    """The header's ``spent / 51`` counter, if one is on screen."""
    for node in ui.nodes:
        for value in (node.text, node.desc):
            if COUNTER_RE.match(value):
                return value
    return None


def wait_counter(device: asc.Device, timeout_s: float = 25.0) -> str | None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        value = counter(device.hierarchy())
        if value is not None:
            return value
        time.sleep(0.6)
    return None


def tap(device: asc.Device, label: str, timeout_s: float = 20.0) -> None:
    ui = device.wait_for(label, timeout_s)
    node = ui.find(label)
    if node is None:
        raise asc.GestureError(f"'{label}' not on screen")
    device.tap_xy(*node.centre)
    time.sleep(1.2)


def back(device: asc.Device, pause_s: float = 1.5) -> None:
    device.shell("input keyevent 4")
    time.sleep(pause_s)


def relaunch_and_read(device: asc.Device, copy: asc.UiCopy) -> str | None:
    device.rotate(landscape=False)
    device.shell(f"am force-stop {asc.PACKAGE}")
    time.sleep(1.5)
    device.shell(f"am start -W -n {asc.PACKAGE}/{asc.ACTIVITY}")
    tap(device, copy.continue_editing, 30)
    return wait_counter(device)


def new_warrior_and_select(
    device: asc.Device, copy: asc.UiCopy
) -> tuple[tuple[int, int], tuple[int, int] | None]:
    """Creates a Warrior draft, selects Improved Rend; returns the + and Undo centres."""
    tap(device, copy.new_build, 30)
    tap(device, copy.warrior, 30)
    device.wait_for(copy.tabs[0], 30)
    tap(device, asc.TALENT)
    ui = device.wait_for(copy.add_rank(asc.TALENT))
    plus = ui.find(copy.add_rank(asc.TALENT))
    undo = ui.find(copy.undo_change)
    if plus is None:
        raise asc.GestureError("add-rank control not found")
    return plus.centre, (undo.centre if undo else None)


def required(centre: tuple[int, int] | None, what: str) -> tuple[int, int]:
    if centre is None:
        raise asc.GestureError(f"{what} control not found")
    return centre


def rapid_taps(x: int, y: int) -> str:
    return "; ".join([f"input tap {x} {y}"] * TAPS_TO_MAX)


def cold_starts(device: asc.Device, runs: int = 5) -> list[int | None]:
    launches: list[int | None] = []
    for _ in range(runs):
        device.shell(f"am force-stop {asc.PACKAGE}")
        time.sleep(2)
        out = device.shell(f"am start -W -n {asc.PACKAGE}/{asc.ACTIVITY}")
        match = re.search(r"TotalTime: (\d+)", out)
        launches.append(int(match.group(1)) if match else None)
        time.sleep(3)
    return launches


def capture_screens(device: asc.Device, copy: asc.UiCopy, out: Path) -> None:
    """Font-scale and compact-size screenshots; always restores the display settings."""
    try:
        for scale in ("1.0", "1.5", "2.0"):
            device.shell(f"settings put system font_scale {scale}")
            time.sleep(3)
            device.screenshot(out / f"font{scale}-home.png")
            tap(device, copy.continue_editing, 20)
            time.sleep(2)
            device.screenshot(out / f"font{scale}-editor.png")
            back(device, 2)
        device.shell("settings put system font_scale 1.0")
        device.shell("wm density 320")
        device.shell("wm size 640x1136")
        time.sleep(4)
        device.screenshot(out / "compact-320x568dp-home.png")
        tap(device, copy.continue_editing, 20)
        time.sleep(2)
        device.screenshot(out / "compact-320x568dp-editor.png")
        device.rotate(landscape=True)
        time.sleep(3)
        device.screenshot(out / "compact-568x320dp-editor-landscape.png")
        device.rotate(landscape=False)
        time.sleep(2)
        back(device, 2)
    finally:
        device.shell("settings put system font_scale 1.0", check=False)
        device.shell("wm size reset", check=False)
        device.shell("wm density reset", check=False)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--screens", action="store_true", help="emulator only")
    args = parser.parse_args()
    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)
    adb = asc.adb_path()
    device = asc.Device(asc.pick_serial(adb, args.serial), adb)
    locale = device.primary_locale()
    copy = asc.UI_COPY_BY_LANGUAGE[asc.language_of(locale)]
    device.copy = copy
    saved_rotation = (
        device.shell("settings get system accelerometer_rotation", check=False),
        device.shell("settings get system user_rotation", check=False),
    )
    info: dict[str, object] = {
        "serial": args.serial,
        "model": device.shell("getprop ro.product.model", check=False),
        "android": device.shell("getprop ro.build.version.release", check=False),
        "sdk": device.shell("getprop ro.build.version.sdk", check=False),
        "locale": locale,
        "screen": device.screen(),
        "startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    results: list[dict[str, object]] = []

    def record(name: str, expected: str, actual: str | None) -> None:
        results.append(
            {
                "scenario": name,
                "expected": expected,
                "actual": actual,
                "result": "PASS" if actual == expected else "FAIL",
            }
        )
        device.screenshot(out / f"{len(results):02d}-{name}.png")
        print(f"{name}: expected {expected}, actual {actual}")

    try:
        device.run("install", "-r", "-t", str(APK))
        device.shell(f"pm clear {asc.PACKAGE}")
        device.run("logcat", "-c", check=False)
        device.rotate(landscape=False)
        info["coldStartTotalTimeMs"] = cold_starts(device)
        device.wait_for(copy.new_build, 30)
        device.screenshot(out / "00-empty-home.png")

        plus, _ = new_warrior_and_select(device, copy)
        device.shell(f"{rapid_taps(*plus)}; input keyevent 4; input keyevent 4")
        time.sleep(2.5)
        record("S1-rapid-spend-then-back", "3 / 51", relaunch_and_read(device, copy))
        back(device)

        plus, maybe_undo = new_warrior_and_select(device, copy)
        undo = required(maybe_undo, "undo")
        device.shell(
            f"{rapid_taps(*plus)}; input tap {undo[0]} {undo[1]}; "
            "input keyevent 4; input keyevent 4"
        )
        time.sleep(2.5)
        record("S2-rapid-spend-undo-then-back", "2 / 51", relaunch_and_read(device, copy))
        back(device)

        plus, _ = new_warrior_and_select(device, copy)
        device.shell(
            f"{rapid_taps(*plus)}; settings put system accelerometer_rotation 0; "
            "settings put system user_rotation 1"
        )
        time.sleep(4)
        back(device, 0.4)
        back(device, 2.5)
        record("S3-rapid-spend-then-rotate-recreate", "3 / 51", relaunch_and_read(device, copy))
        back(device)

        info["meminfoSummary"] = device.shell(
            f"dumpsys meminfo {asc.PACKAGE} | grep -E 'TOTAL|Java Heap|Native Heap'", check=False
        )
        if args.screens:
            capture_screens(device, copy, out)
        info["crashBuffer"] = device.run("logcat", "-d", "-b", "crash", check=False).decode(
            errors="replace"
        )[-4000:]
    finally:
        device.shell(
            f"settings put system accelerometer_rotation {saved_rotation[0] or 1}", check=False
        )
        if saved_rotation[1] and saved_rotation[1] != "null":
            device.shell(f"settings put system user_rotation {saved_rotation[1]}", check=False)
        device.run("uninstall", asc.PACKAGE, check=False)
        info["finishedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        (out / "results.json").write_text(
            json.dumps({"device": info, "results": results}, indent=2, ensure_ascii=False)
        )
    return 0 if results and all(r["result"] == "PASS" for r in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
