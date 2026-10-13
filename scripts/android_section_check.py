#!/usr/bin/env python3
"""Android device check: Talent Tree section navigation on a real phone (M5.UX.2b.1).

Drives the ISOLATED debug build (application id suffix ``.devcheck``, its own data),
so the user's real TalentForge app and its saved builds are never seen, changed or
deleted. Uses adb + uiautomator only: Home -> + New build -> Warrior -> editor, then
the portrait <-> landscape section-navigation scenarios of the milestone.

Every step records: orientation, display size and density, the tap coordinates and
the target's bounds, the active tab before/after, the section actually on screen,
the screenshot and hierarchy files, and PASS/FAIL with a concrete reason. A gesture
that could not be performed (target missing) is reported apart from an assertion
that failed after the gesture.

How the state is read (see ``Hierarchy``):

- the active tab is the tab CONTAINER's ``selected`` flag. Compose exposes a tab as
  a node carrying ``selected`` with its label in a child ``TextView`` whose own
  ``selected`` is always false — reading the label's flag (the first version of
  this check did) can never see a selected tab;
- the section on screen is derived independently from the talents the screen
  exposes: every talent content description is mapped to its dataset section
  (bundled product dataset), and the step requires them to belong to the expected
  section only.

Device settings it touches (accelerometer_rotation, user_rotation) are read first
and restored in ``finally``; the isolated package is uninstalled at the end
(``--keep`` leaves it installed).

Runs without arguments (an IDE Run button): it builds the isolated APK from this
checkout, runs the check twice and writes ``.cache/android-section-check/<time>/``.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "scripts"))
from mobile_bundles import product_build  # noqa: E402

PACKAGE = "io.github.alexeykravtsov95.talentforge.devcheck"
ACTIVITY = "io.github.alexeykravtsov95.talentforge.ui.MainActivity"
APK = REPO / "mobile/composeApp/build/outputs/apk/debug/composeApp-debug.apk"
DATASETS = REPO / "mobile/composeApp/src/commonMain/composeResources/files/dataset/forever"
TABS = ("Tree 1", "Tree 2", "Tree 3")
CLOSE = "\u00d7"  # the side panel's close button label
BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
NOT_TALENTS = {
    "go back",
    "Undo talent change",
    "Redo talent change",
    "open build actions",
    "Fit tree",
    "Expand inspector",
    "Collapse inspector",
    "Max rank reached",
}


class GestureError(Exception):
    """A gesture could not be performed: its target was not on screen."""


class DeviceError(Exception):
    """adb or uiautomator failed."""


Rect = tuple[int, int, int, int]


@dataclass
class UiNode:
    text: str
    desc: str
    selected: bool
    clickable: bool
    bounds: Rect
    parent: UiNode | None = None
    children: list[UiNode] = field(default_factory=list)

    @property
    def centre(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2


class Hierarchy:
    """One uiautomator dump, with parent links."""

    def __init__(self, xml: str) -> None:
        self.xml = xml
        self.nodes: list[UiNode] = []
        root = ET.fromstring(xml)
        self._walk(root, None)

    def _walk(self, element: ET.Element, parent: UiNode | None) -> None:
        for child in element:
            if child.tag != "node":
                continue
            match = BOUNDS.match(child.get("bounds", ""))
            node = UiNode(
                text=child.get("text", ""),
                desc=child.get("content-desc", ""),
                selected=child.get("selected") == "true",
                clickable=child.get("clickable") == "true",
                bounds=tuple(int(v) for v in match.groups()) if match else (0, 0, 0, 0),  # type: ignore[arg-type]
                parent=parent,
            )
            if parent is not None:
                parent.children.append(node)
            self.nodes.append(node)
            self._walk(child, node)

    def find(self, label: str) -> UiNode | None:
        """A node whose text or content description is ``label`` (or a merged ``label, …``)."""
        for node in self.nodes:
            if label in (node.text, node.desc) or node.text.startswith(label + ","):
                return node
        return None

    def tab(self, label: str) -> UiNode | None:
        """The tab CONTAINER of ``label``: the label when it carries the tab, else its parent."""
        node = self.find(label)
        if node is None:
            return None
        if node.parent is not None and not node.clickable and not node.selected:
            return node.parent
        return node

    def active_tab(self) -> str | None:
        active = [label for label in TABS if (tab := self.tab(label)) is not None and tab.selected]
        return active[0] if len(active) == 1 else None

    def talents(self, sections: dict[str, int]) -> list[str]:
        return [n.desc for n in self.nodes if n.desc in sections and n.desc not in NOT_TALENTS]


def dataset_sections() -> dict[str, int]:
    """Warrior talent name -> section index, from the bundled product dataset."""
    document = json.loads((DATASETS / product_build() / "warrior.json").read_text())
    tree = document["trees"][0]
    return {node["name"]: node["sectionIndex"] for node in tree["nodes"] if node.get("name")}


@dataclass
class Device:
    serial: str
    adb: str

    def run(self, *args: str, check: bool = True, timeout: int = 300) -> bytes:
        cmd = [self.adb, "-s", self.serial, *args]
        out = subprocess.run(cmd, capture_output=True, timeout=timeout, check=False)
        if check and out.returncode != 0:
            error = out.stderr.decode(errors="replace").strip()
            raise DeviceError(f"{' '.join(cmd)} -> {out.returncode}: {error}")
        return out.stdout

    def shell(self, command: str, check: bool = True) -> str:
        return self.run("shell", command, check=check).decode(errors="replace").strip()

    def hierarchy(self) -> Hierarchy:
        last = ""
        for _ in range(8):
            last = self.shell("uiautomator dump /sdcard/tf-section-check.xml", check=False)
            xml = self.shell("cat /sdcard/tf-section-check.xml", check=False)
            if xml.startswith("<?xml"):
                return Hierarchy(xml)
            time.sleep(0.7)
        raise DeviceError(f"uiautomator dump failed: {last}")

    def wait_for(self, label: str, timeout_s: float = 20.0) -> Hierarchy:
        deadline = time.time() + timeout_s
        while True:
            ui = self.hierarchy()
            if ui.find(label) is not None or time.time() > deadline:
                return ui
            time.sleep(0.5)

    def tap_xy(self, x: int, y: int) -> None:
        self.shell(f"input tap {x} {y}")

    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 220) -> None:
        self.shell(f"input swipe {x1} {y1} {x2} {y2} {ms}")

    def rotate(self, landscape: bool) -> None:
        self.shell("settings put system accelerometer_rotation 0")
        self.shell(f"settings put system user_rotation {1 if landscape else 0}")

    def screen(self) -> dict[str, str]:
        return {
            "size": self.shell("wm size", check=False).replace("\n", "; "),
            "density": self.shell("wm density", check=False).replace("\n", "; "),
            "rotation": self.shell("settings get system user_rotation", check=False),
        }

    def screenshot(self, path: Path) -> None:
        path.write_bytes(self.run("exec-out", "screencap", "-p"))


@dataclass
class Step:
    name: str
    ok: bool
    kind: str  # "pass" | "assertion" | "gesture"
    reason: str
    orientation: str
    tap: tuple[int, int] | None
    target_bounds: Rect | None
    tab_before: str | None
    tab_after: str | None
    talents: list[str]
    sections_on_screen: list[int]
    screenshot: str
    hierarchy: str


class Check:
    """One full pass of the scenarios on one device."""

    def __init__(self, device: Device, out: Path, sections: dict[str, int]) -> None:
        self.device = device
        self.out = out
        self.sections = sections
        self.steps: list[Step] = []
        self.orientation = "portrait"
        self.counter = 0

    # ---------------------------------------------------------------- evidence

    def snapshot(self, name: str) -> tuple[Hierarchy, str, str]:
        self.counter += 1
        stem = f"{self.counter:02d}-{re.sub(r'[^a-z0-9]+', '-', name.lower()).strip('-')}"
        ui = self.device.hierarchy()
        (self.out / f"{stem}.xml").write_text(ui.xml)
        self.device.screenshot(self.out / f"{stem}.png")
        return ui, f"{stem}.png", f"{stem}.xml"

    def record(
        self,
        name: str,
        problems: list[str],
        ui: Hierarchy,
        shot: str,
        xml: str,
        *,
        tap: tuple[int, int] | None = None,
        target: UiNode | None = None,
        tab_before: str | None = None,
        gesture_failed: bool = False,
    ) -> Step:
        talents = ui.talents(self.sections)
        step = Step(
            name=name,
            ok=not problems,
            kind="pass" if not problems else ("gesture" if gesture_failed else "assertion"),
            reason="; ".join(problems) if problems else "ok",
            orientation=self.orientation,
            tap=tap,
            target_bounds=target.bounds if target else None,
            tab_before=tab_before,
            tab_after=ui.active_tab(),
            talents=talents,
            sections_on_screen=sorted({self.sections[t] for t in talents}),
            screenshot=shot,
            hierarchy=xml,
        )
        self.steps.append(step)
        flag = "PASS" if step.ok else f"FAIL ({step.kind})"
        print(
            f"[{flag}] {name} — {step.reason} | {self.orientation} tap={tap} "
            f"bounds={step.target_bounds} tab {tab_before}->{step.tab_after} "
            f"sections={step.sections_on_screen} talents={len(talents)}",
            flush=True,
        )
        return step

    # ---------------------------------------------------------------- assertions

    def section_problems(self, ui: Hierarchy, expected_tab: str) -> list[str]:
        problems = []
        active = ui.active_tab()
        if active != expected_tab:
            problems.append(f"active tab is {active}, expected {expected_tab}")
        talents = ui.talents(self.sections)
        expected_section = TABS.index(expected_tab)
        on_screen = {self.sections[t] for t in talents}
        if not talents:
            problems.append("no talent of any section is exposed on screen")
        elif on_screen != {expected_section}:
            others = sorted(t for t in talents if self.sections[t] != expected_section)
            problems.append(f"talents of section(s) {sorted(on_screen)} on screen: {others[:4]}")
        return problems

    # ---------------------------------------------------------------- gestures

    def tap_label(
        self, label: str, *, tab: bool = False
    ) -> tuple[Hierarchy, UiNode, tuple[int, int]]:
        ui = self.device.wait_for(label)
        target = ui.tab(label) if tab else ui.find(label)
        if target is None:
            raise GestureError(f"'{label}' is not on screen")
        x, y = target.centre
        self.device.tap_xy(x, y)
        time.sleep(1.2)
        return ui, target, (x, y)

    def rotate(self, landscape: bool) -> None:
        self.device.rotate(landscape)
        self.orientation = "landscape" if landscape else "portrait"
        time.sleep(3.0)  # activity recreation + first frames

    def step(self, name: str, action: Callable[[], None]) -> None:
        """Runs one scenario; a gesture failure or a device error becomes a recorded FAIL."""
        try:
            action()
        except GestureError as error:
            ui, shot, xml = self.snapshot(name)
            self.record(name, [str(error)], ui, shot, xml, gesture_failed=True)

    # ---------------------------------------------------------------- scenarios

    def open_tab(self, label: str, name: str) -> None:
        before = self.device.hierarchy().active_tab()
        _, target, xy = self.tap_label(label, tab=True)
        ui, shot, xml = self.snapshot(name)
        problems = self.section_problems(ui, label)
        if before == ui.active_tab() and before != label:
            problems.insert(0, "the tap changed nothing (gesture had no effect)")
        self.record(name, problems, ui, shot, xml, tap=xy, target=target, tab_before=before)

    def rotate_keeps(self, landscape: bool, label: str, name: str) -> None:
        before = self.device.hierarchy().active_tab()
        self.rotate(landscape)
        ui, shot, xml = self.snapshot(name)
        self.record(name, self.section_problems(ui, label), ui, shot, xml, tab_before=before)

    def swipe_to(self, direction: int, label: str, name: str) -> None:
        ui = self.device.hierarchy()
        before = ui.active_tab()
        tab = ui.tab("Tree 1")
        undo = ui.find("Undo talent change")
        if tab is None or undo is None:
            raise GestureError("tabs or header not on screen")
        size = self.device.shell("wm size").split()[-1]
        width, height = (int(v) for v in size.split("x"))
        if self.orientation == "landscape":
            width, height = max(width, height), min(width, height)
        else:
            width, height = min(width, height), max(width, height)
        top = max(tab.bounds[3], undo.bounds[3]) + 40
        y = (top + height) // 2
        x1, x2 = (
            (int(width * 0.75), int(width * 0.25))
            if direction > 0
            else (int(width * 0.25), int(width * 0.75))
        )
        self.device.swipe(x1, y, x2, y)
        time.sleep(1.5)
        ui, shot, xml = self.snapshot(name)
        problems = self.section_problems(ui, label)
        if ui.active_tab() == before:
            problems.insert(0, "the swipe changed nothing (gesture had no effect)")
        self.record(name, problems, ui, shot, xml, tap=(x1, y), tab_before=before)

    def expect(
        self,
        name: str,
        checks: dict[str, bool],
        target: UiNode | None = None,
        xy: tuple[int, int] | None = None,
    ) -> None:
        ui, shot, xml = self.snapshot(name)
        problems = [what for what, ok in checks.items() if not ok]
        self.record(name, problems, ui, shot, xml, tap=xy, target=target)

    def present(self, label: str) -> bool:
        return self.device.hierarchy().find(label) is not None

    def gone(self, label: str, timeout_s: float = 6.0) -> bool:
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            if not self.present(label):
                return True
            time.sleep(0.5)
        return False

    def run(self, apk: Path) -> None:
        d = self.device
        d.run("install", "-r", "-t", str(apk))
        if PACKAGE not in d.shell(f"pm list packages {PACKAGE}"):
            raise DeviceError(f"{apk} did not install as {PACKAGE}")
        d.shell(f"pm clear {PACKAGE}")  # the isolated package's own data only
        self.rotate(landscape=False)
        d.shell(f"am start -W -n {PACKAGE}/{ACTIVITY}")
        d.wait_for("+ New build", timeout_s=30)
        self.expect(
            "isolated app opens on an empty Home",
            {"'+ New build' on Home": self.present("+ New build")},
        )

        def create() -> None:
            _, target, xy = self.tap_label("+ New build")
            self.tap_label("Warrior")
            ui = d.wait_for("Tree 1", timeout_s=30)
            self.expect(
                "New build -> Warrior opens the editor on Tree 1",
                {
                    "editor with tabs": ui.find("Tree 1") is not None,
                    "Tree 1 active": ui.active_tab() == "Tree 1",
                },
                target,
                xy,
            )
            ui, shot, xml = self.snapshot("editor before the first interaction")
            self.record(
                "editor before the first interaction",
                self.section_problems(ui, "Tree 1"),
                ui,
                shot,
                xml,
            )

        self.step("create", create)
        self.step("portrait Tree 2", lambda: self.open_tab("Tree 2", "portrait: tap Tree 2"))
        self.step(
            "rotate", lambda: self.rotate_keeps(True, "Tree 2", "rotate to landscape keeps Tree 2")
        )
        self.step("landscape Tree 3", lambda: self.open_tab("Tree 3", "landscape: tap Tree 3"))
        self.step(
            "rotate back",
            lambda: self.rotate_keeps(False, "Tree 3", "rotate to portrait keeps Tree 3"),
        )
        self.step("portrait Tree 1", lambda: self.open_tab("Tree 1", "portrait: tap Tree 1"))
        self.step(
            "portrait swipe",
            lambda: self.swipe_to(+1, "Tree 2", "portrait: swipe left opens Tree 2"),
        )
        self.step(
            "portrait swipe back",
            lambda: self.swipe_to(-1, "Tree 1", "portrait: swipe right opens Tree 1"),
        )

        def spend() -> None:
            _, target, xy = self.tap_label("Improved Rend")
            d.wait_for("Add rank to Improved Rend")
            self.tap_label("Add rank to Improved Rend")
            self.expect(
                "portrait: select Improved Rend and + spends one point",
                {"counter 1 / 51": self.present("1 / 51")},
                target,
                xy,
            )

        self.step("spend", spend)

        def rotate_selected() -> None:
            self.rotate(landscape=True)
            self.expect(
                "rotate to landscape: selection, points and side panel survive",
                {
                    "side panel shows Improved Rend's controls": self.present(
                        "Add rank to Improved Rend"
                    ),
                    "counter 1 / 51": self.present("1 / 51"),
                    "Tree 1 still active": d.hierarchy().active_tab() == "Tree 1",
                },
            )

        self.step("rotate selected", rotate_selected)

        def close_reopen() -> None:
            self.tap_label(CLOSE)
            closed = self.gone("Add rank to Improved Rend")
            _, target, xy = self.tap_label("Improved Rend")
            self.expect(
                "landscape: the close button closes the panel, a tap reopens it",
                {"closed": closed, "reopened": self.present("Add rank to Improved Rend")},
                target,
                xy,
            )

        self.step("close reopen", close_reopen)

        def panel_and_tabs() -> None:
            before = d.hierarchy().active_tab()
            _, target, xy = self.tap_label("Tree 2", tab=True)
            ui, shot, xml = self.snapshot(
                "landscape: with the panel open, the Tree 2 tab still works"
            )
            problems = self.section_problems(ui, "Tree 2")
            if ui.find("Add rank to Improved Rend") is not None:
                problems.append("the side panel stayed open (a tab change deselects)")
            self.record(
                "landscape: with the panel open, the Tree 2 tab still works",
                problems,
                ui,
                shot,
                xml,
                tap=xy,
                target=target,
                tab_before=before,
            )

        self.step("panel vs tabs", panel_and_tabs)
        self.step(
            "landscape swipe",
            lambda: self.swipe_to(+1, "Tree 3", "landscape: swipe left opens Tree 3"),
        )
        self.step(
            "landscape swipe back",
            lambda: self.swipe_to(-1, "Tree 2", "landscape: swipe right opens Tree 2"),
        )
        self.step("landscape Tree 1", lambda: self.open_tab("Tree 1", "landscape: tap Tree 1"))

        def back() -> None:
            self.tap_label("Improved Rend")
            d.shell("input keyevent 4")
            time.sleep(1.2)
            cleared = self.gone("Add rank to Improved Rend")
            in_editor = self.present("Tree 1")
            self.expect(
                "landscape: system Back clears the selection, editor stays",
                {"selection cleared": cleared, "still in the editor": in_editor},
            )
            d.shell("input keyevent 4")
            time.sleep(1.5)
            self.expect(
                "landscape: a second Back leaves the editor",
                {
                    "Home shows 'Continue editing'": d.wait_for("Continue editing", 10).find(
                        "Continue editing"
                    )
                    is not None
                },
            )

        self.step("back", back)

        def relaunch() -> None:
            self.rotate(landscape=False)
            d.shell(f"am force-stop {PACKAGE}")
            d.shell(f"am start -W -n {PACKAGE}/{ACTIVITY}")
            _, target, xy = self.tap_label("Continue editing")
            ui = d.wait_for("1 / 51", 20)
            self.expect(
                "relaunch: the saved build reopens with 1 / 51",
                {"counter 1 / 51": ui.find("1 / 51") is not None},
                target,
                xy,
            )

        self.step("relaunch", relaunch)


def adb_path() -> str:
    found = shutil.which("adb")
    if found:
        return found
    for candidate in (
        Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools/adb",
        Path.home() / "Library/Android/sdk/platform-tools/adb",
    ):
        if candidate.is_file():
            return str(candidate)
    raise DeviceError("adb not found (PATH, $ANDROID_HOME, ~/Library/Android/sdk)")


def pick_serial(adb: str, wanted: str | None) -> str:
    listing = subprocess.run(
        [adb, "devices"], capture_output=True, text=True, timeout=30, check=False
    )
    serials = [
        line.split("\t")[0] for line in listing.stdout.splitlines()[1:] if line.endswith("\tdevice")
    ]
    if wanted:
        if wanted not in serials:
            raise DeviceError(f"device {wanted} not connected ({serials})")
        return wanted
    if not serials:
        raise DeviceError("no adb device connected")
    return serials[0]


def build_apk(log: Path) -> None:
    env = dict(os.environ)
    if "JAVA_HOME" not in env and Path("/usr/libexec/java_home").exists():
        env["JAVA_HOME"] = subprocess.run(
            ["/usr/libexec/java_home"], capture_output=True, text=True, check=False
        ).stdout.strip()
    with log.open("w") as sink:
        result = subprocess.run(
            [
                "./gradlew",
                ":composeApp:assembleDebug",
                "-Ptalentforge.applicationIdSuffix=.devcheck",
            ],
            cwd=REPO / "mobile",
            stdout=sink,
            stderr=subprocess.STDOUT,
            env=env,
            timeout=1800,
            check=False,
        )
    if result.returncode != 0:
        raise DeviceError(f"isolated APK build failed (see {log})")


def write_report(out: Path, header: dict[str, object], runs: list[list[Step]]) -> bool:
    passed = all(step.ok for run in runs for step in run) and bool(runs)
    payload = {**header, "passed": passed, "runs": [[vars(s) for s in run] for run in runs]}
    (out / "results.json").write_text(json.dumps(payload, indent=2))
    lines = [f"# Android section-navigation check — {'PASS' if passed else 'FAIL'}", ""]
    lines += [f"- {k}: {v}" for k, v in header.items()]
    for index, run in enumerate(runs, start=1):
        ok = sum(s.ok for s in run)
        lines += ["", f"## Run {index}: {ok}/{len(run)} PASS", ""]
        lines += [
            "| # | step | result | orientation | tap | target bounds | tab before -> after "
            "| sections on screen | reason | screenshot |",
            "|---|---|---|---|---|---|---|---|---|---|",
        ]
        for number, s in enumerate(run, start=1):
            result = "PASS" if s.ok else f"FAIL ({s.kind})"
            lines.append(
                f"| {number} | {s.name} | {result} | {s.orientation} | {s.tap or ''} "
                f"| {s.target_bounds or ''} | {s.tab_before or '-'} -> {s.tab_after or '-'} "
                f"| {s.sections_on_screen} ({len(s.talents)} talents) "
                f"| {s.reason} | run{index}/{s.screenshot} |"
            )
    (out / "report.md").write_text("\n".join(lines) + "\n")
    return passed


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--runs", type=int, default=2)
    parser.add_argument("--no-build", action="store_true", help="install the existing APK as is")
    parser.add_argument("--serial")
    parser.add_argument("--keep", action="store_true", help="leave the isolated app installed")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    out = args.out or REPO / ".cache/android-section-check" / time.strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True, exist_ok=True)
    head = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"],
        cwd=REPO,
        capture_output=True,
        text=True,
        check=False,
    ).stdout.strip()
    header: dict[str, object] = {"commit": head, "package": PACKAGE, "apk": str(APK)}
    runs: list[list[Step]] = []
    device: Device | None = None
    saved: dict[str, str] = {}
    try:
        if not args.no_build:
            print("building the isolated APK …", flush=True)
            build_apk(out / "build.log")
        device = Device(pick_serial(adb_path(), args.serial), adb_path())
        header |= {
            "serial": device.serial,
            "model": device.shell("getprop ro.product.model", check=False),
            "android": device.shell("getprop ro.build.version.release", check=False),
            "font_scale": device.shell("settings get system font_scale", check=False),
            **device.screen(),
        }
        saved = {
            k: device.shell(f"settings get system {k}")
            for k in ("accelerometer_rotation", "user_rotation")
        }
        sections = dataset_sections()
        for index in range(1, args.runs + 1):
            run_dir = out / f"run{index}"
            run_dir.mkdir(exist_ok=True)
            check = Check(device, run_dir, sections)
            try:
                check.run(APK)
            except (DeviceError, subprocess.TimeoutExpired) as error:
                check.steps.append(
                    Step(
                        "run aborted",
                        False,
                        "gesture",
                        str(error),
                        check.orientation,
                        None,
                        None,
                        None,
                        None,
                        [],
                        [],
                        "",
                        "",
                    )
                )
            runs.append(check.steps)
    except (DeviceError, subprocess.TimeoutExpired) as error:
        header["error"] = str(error)
        print(f"ERROR: {error}", flush=True)
    finally:
        if device is not None:
            for key, value in saved.items():
                command = (
                    f"settings delete system {key}"
                    if value in ("null", "")
                    else f"settings put system {key} {value}"
                )
                device.shell(command, check=False)
            if not args.keep:
                device.run("uninstall", PACKAGE, check=False)
        passed = write_report(out, header, runs)
        (out.parent / "latest.txt").write_text(str(out) + "\n")
    print(f"{'PASS' if passed else 'FAIL'} — report: {out / 'report.md'}", flush=True)
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
