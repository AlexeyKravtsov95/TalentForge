"""M5.RC.2a icon checks: packaged Android icon, lint classification, launcher capture, iOS bundle.

Called by ``run-rc2a-checks.sh``; every subcommand writes a JSON report to ``--out`` and exits
non-zero when its acceptance criteria fail. Requires Pillow (a dev dependency of the repo).

    rc2a_checks.py apk      --apk A.apk --aapt2 PATH --res <androidMain/res> --out DIR
    rc2a_checks.py lint     --report lint-results-release.xml --out DIR
    rc2a_checks.py ios      --app iosApp.app --source <AppIcon-1024.png> --out DIR
    rc2a_checks.py launcher --adb PATH --serial S --package PKG --label TalentForge --out DIR

``launcher`` only drives the launcher (Home, app drawer, optional themed-icon toggle, which is
restored). It never opens, clears or uninstalls any app; the caller installs and removes the
isolated ``.devcheck`` package.
"""

from __future__ import annotations

import argparse
import io
import json
import plistlib
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from PIL import Image, ImageChops, ImageStat

LAYERS = ("ic_launcher_background", "ic_launcher_foreground", "ic_launcher_monochrome")
# Lint issues present before the icon existed (M5.RC.1, E1-49 / E2-13).
RC1_LINT_BASELINE = {
    "RememberReturnType",
    "ApplySharedPref",
    "UseKtx",
    "OldTargetApi",
    "MissingApplicationIcon",
}


def run(cmd: list[str], check: bool = True) -> str:
    p = subprocess.run(cmd, capture_output=True, text=True)
    if check and p.returncode != 0:
        raise SystemExit(f"command failed ({p.returncode}): {' '.join(cmd)}\n{p.stderr}")
    return p.stdout


def write(out: Path, name: str, report: dict) -> None:
    out.mkdir(parents=True, exist_ok=True)
    (out / name).write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(report, indent=2, ensure_ascii=False))


def pixel_diff(a: Image.Image, b: Image.Image) -> dict:
    """Visible-pixel comparison: alpha must match exactly, RGB only where alpha > 0.

    Release builds re-encode PNGs (e.g. RGBA -> palette); the RGB stored under fully
    transparent pixels is invisible and may change, so it is reported but not judged.
    """
    a, b = a.convert("RGBA"), b.convert("RGBA")
    if a.size != b.size:
        return {"same_size": False, "a": a.size, "b": b.size}
    alpha = ImageChops.difference(a.getchannel("A"), b.getchannel("A")).getextrema()[1]
    visible = a.getchannel("A").point(lambda v: 255 if v > 0 else 0)
    rgb = ImageChops.difference(a.convert("RGB"), b.convert("RGB"))
    shown = Image.composite(rgb, Image.new("RGB", a.size), visible)
    hidden = Image.composite(Image.new("RGB", a.size), rgb, visible)
    return {
        "same_size": True,
        "max_alpha_diff": alpha,
        "max_visible_rgb_diff": max(hi for _, hi in shown.getextrema()),
        "max_hidden_rgb_diff": max(hi for _, hi in hidden.getextrema()),
        "max_channel_diff": max(alpha, max(hi for _, hi in shown.getextrema())),
    }


# ---------------------------------------------------------------- apk
def parse_resources(dump: str) -> tuple[dict[str, list[dict]], dict[str, str]]:
    files: dict[str, list[dict]] = {}
    ids: dict[str, str] = {}
    current = None
    for line in dump.splitlines():
        m = re.match(r"\s*resource (0x[0-9a-f]+) (\S+)", line)
        if m:
            current = m.group(2)
            ids[m.group(1)] = current
            files.setdefault(current, [])
            continue
        m = re.match(r"\s*\((.*?)\) \(file\) (\S+) type=(\w+)", line)
        if m and current:
            files[current].append({"config": m.group(1), "path": m.group(2), "type": m.group(3)})
    return files, ids


def cmd_apk(a: argparse.Namespace) -> int:
    aapt2, apk, res = a.aapt2, a.apk, Path(a.res)
    fails: list[str] = []
    badging = run([aapt2, "dump", "badging", apk])
    app = re.search(r"^application: label='([^']*)' icon='([^']*)'", badging, re.M)
    launch = re.search(r"^launchable-activity: .*icon='([^']*)'", badging, re.M)
    icons = dict(re.findall(r"^application-icon-(\d+):'([^']*)'", badging, re.M))
    report: dict = {
        "apk": apk,
        "application_label": app.group(1) if app else None,
        "application_icon": app.group(2) if app else None,
        "launchable_activity_icon": launch.group(1) if launch else None,
        "application_icon_by_density": icons,
    }
    if not report["application_icon"]:
        fails.append("aapt2 badging: application icon is empty")

    files, ids = parse_resources(run([aapt2, "dump", "resources", apk]))
    report["mipmap/ic_launcher"] = files.get("mipmap/ic_launcher", [])
    for layer in LAYERS:
        report[f"drawable/{layer}"] = files.get(f"drawable/{layer}", [])
        if not report[f"drawable/{layer}"]:
            fails.append(f"drawable/{layer} not packaged")

    adaptive = [f for f in report["mipmap/ic_launcher"] if f["type"] == "XML"]
    rasters = {f["config"]: f["path"] for f in report["mipmap/ic_launcher"] if f["type"] == "PNG"}
    report["adaptive_xml"] = adaptive
    if not adaptive:
        fails.append("no adaptive-icon XML packaged for mipmap/ic_launcher")
    else:
        tree = run([aapt2, "dump", "xmltree", "--file", adaptive[0]["path"], apk])
        report["adaptive_xmltree"] = tree
        layers_found = {}
        for tag in ("background", "foreground", "monochrome"):
            m = re.search(rf"E: {tag} .*?\n\s+A: .*?drawable.*?=@(0x[0-9a-f]+)", tree, re.S)
            layers_found[tag] = ids.get(m.group(1)) if m else None
            if layers_found[tag] != f"drawable/ic_launcher_{tag}":
                fails.append(f"adaptive <{tag}> -> {layers_found[tag]}")
        report["adaptive_layers"] = layers_found

    with zipfile.ZipFile(apk) as z:
        cmp: dict = {}
        for layer in LAYERS:
            for f in files.get(f"drawable/{layer}", []):
                src = res / "drawable-nodpi" / f"{layer}.png"
                got = Image.open(io.BytesIO(z.read(f["path"])))
                cmp[f"{layer} ({f['config']})"] = pixel_diff(got, Image.open(src))
        for cfg, path in rasters.items():
            dens = cfg.split("-")[0]
            src = res / f"mipmap-{dens}" / "ic_launcher.png"
            if src.exists():
                cmp[f"mipmap-{cfg}"] = pixel_diff(
                    Image.open(io.BytesIO(z.read(path))), Image.open(src)
                )
        report["packaged_vs_source_pixels"] = cmp
        for k, v in cmp.items():
            if not v.get("same_size") or v.get("max_channel_diff", 255) > 0:
                fails.append(f"packaged pixels differ from source: {k} {v}")
    report["raster_fallback_densities"] = sorted(rasters)
    report["failures"] = fails
    report["result"] = "PASS" if not fails else "FAIL"
    write(Path(a.out), f"apk-{Path(apk).stem}.json", report)
    return 0 if not fails else 1


# ---------------------------------------------------------------- lint
def cmd_lint(a: argparse.Namespace) -> int:
    root = ET.parse(a.report).getroot()
    issues = []
    for i in root.iter("issue"):
        loc = i.find("location")
        issues.append(
            {
                "id": i.get("id"),
                "severity": i.get("severity"),
                "category": i.get("category"),
                "file": re.sub(r"^.*?/mobile/", "mobile/", loc.get("file", ""))
                if loc is not None
                else "",
                "line": loc.get("line") if loc is not None else "",
                "message": i.get("message"),
            }
        )

    def icon_related(x: dict) -> bool:
        return (
            x["category"] == "Icons"
            or (x["id"] or "").startswith("Icon")
            or x["id"] in {"MissingApplicationIcon", "MonochromeLauncherIcon"}
            or "ic_launcher" in x["file"]
            or "/res/mipmap" in x["file"]
        )

    icon = [x for x in issues if icon_related(x)]
    pre = [x for x in issues if not icon_related(x) and x["id"] in RC1_LINT_BASELINE]
    new = [x for x in issues if not icon_related(x) and x["id"] not in RC1_LINT_BASELINE]
    fails = []
    if any(x["id"] == "MissingApplicationIcon" for x in issues):
        fails.append("MissingApplicationIcon still reported")
    if icon:
        fails.append(f"{len(icon)} icon-related lint issue(s)")
    report = {
        "report": a.report,
        "total": len(issues),
        "errors": sum(1 for x in issues if x["severity"] in ("Error", "Fatal")),
        "icon_related": icon,
        "pre_existing_rc1_issues": pre,
        "other_not_in_rc1_baseline": new,
        "failures": fails,
        "result": "PASS" if not fails else "FAIL",
        "note": "lintRelease may still exit non-zero because of pre-existing issues (TF-RC1-LINT); "
        "this verdict covers icon integration only.",
    }
    write(Path(a.out), "lint-classification.json", report)
    return 0 if not fails else 1


# ---------------------------------------------------------------- ios
def cmd_ios(a: argparse.Namespace) -> int:
    app, out = Path(a.app), Path(a.out)
    fails: list[str] = []
    info = plistlib.loads((app / "Info.plist").read_bytes())
    report: dict = {
        "app": str(app),
        "CFBundleIcons": info.get("CFBundleIcons"),
        "CFBundleIcons~ipad": info.get("CFBundleIcons~ipad"),
    }
    name = (info.get("CFBundleIcons") or {}).get("CFBundlePrimaryIcon", {}).get("CFBundleIconName")
    if name != "AppIcon":
        fails.append(f"CFBundleIconName is {name!r}, expected 'AppIcon'")
    pngs = sorted(p.name for p in app.glob("AppIcon*.png"))
    report["bundle_icon_files"] = pngs
    out.mkdir(parents=True, exist_ok=True)
    for p in pngs:
        shutil.copy2(app / p, out / f"ios-bundle-{p}")
    car = app / "Assets.car"
    report["assets_car_bytes"] = car.stat().st_size if car.exists() else None
    renditions = []
    try:
        data = json.loads(
            run(["xcrun", "--sdk", "iphonesimulator", "assetutil", "--info", str(car)])
        )
        for e in data:
            if e.get("Name") == "AppIcon":
                renditions.append(
                    {
                        k: e.get(k)
                        for k in (
                            "RenditionName",
                            "PixelWidth",
                            "PixelHeight",
                            "Idiom",
                            "Appearance",
                            "Scale",
                        )
                    }
                )
    except (SystemExit, json.JSONDecodeError) as exc:
        fails.append(f"assetutil failed: {exc}")
    report["assets_car_appicon_renditions"] = renditions
    if not renditions:
        fails.append("no AppIcon rendition in Assets.car")
    src = Image.open(a.source).convert("RGB")
    sims = {}
    for p in pngs:
        got = Image.open(app / p).convert("RGB")
        ref = src.resize(got.size, Image.LANCZOS)
        mean = sum(ImageStat.Stat(ImageChops.difference(got, ref)).mean) / 3
        sims[p] = {"size": got.size, "mean_abs_diff_vs_source_0_255": round(mean, 2)}
        if mean > 6:
            fails.append(f"{p} differs from the approved master (mean diff {mean:.1f})")
    report["bundle_icons_vs_source"] = sims
    if not pngs:
        fails.append("no AppIcon*.png in the app bundle (expected for deployment target < 18)")
    report["failures"] = fails
    report["result"] = "PASS" if not fails else "FAIL"
    write(out, "ios-bundle-icon.json", report)
    return 0 if not fails else 1


# ---------------------------------------------------------------- launcher
class Adb:
    def __init__(self, adb: str, serial: str) -> None:
        self.base = [adb, "-s", serial]

    def sh(self, *args: str, check: bool = True) -> str:
        return run([*self.base, "shell", *args], check=check)

    def screencap(self, path: Path) -> Image.Image:
        png = subprocess.run(
            [*self.base, "exec-out", "screencap", "-p"], capture_output=True, check=True
        ).stdout
        path.write_bytes(png)
        return Image.open(io.BytesIO(png))

    def _dump(self) -> str:
        self.sh("uiautomator", "dump", "/sdcard/rc2a-ui.xml", check=False)
        return self.sh("cat", "/sdcard/rc2a-ui.xml", check=False)

    def find_all(self, label: str) -> list[tuple[int, int, int, int]]:
        xml = self._dump()
        try:
            root = ET.fromstring(xml[xml.find("<?xml") :] if "<?xml" in xml else xml)
        except ET.ParseError:
            return []
        boxes = []
        for n in root.iter("node"):
            if label in (n.get("text"), n.get("content-desc")):
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds", "")))
                if x2 > x1 and y2 > y1:
                    boxes.append((x1, y1, x2, y2))
        return boxes

    def tap_text(self, pattern: str) -> bool:
        for m in re.finditer(
            r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', self._dump()
        ):
            if re.search(pattern, m.group(1), re.I):
                x1, y1, x2, y2 = map(int, m.groups()[1:])
                self.sh("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                return True
        return False


def crop(img: Image.Image, box: tuple[int, int, int, int], path: Path) -> None:
    c = img.crop(box)
    c.save(path)
    c.resize((c.width * 3, c.height * 3), Image.NEAREST).save(path.with_name(path.stem + "-x3.png"))


def gold_fraction(img: Image.Image, box: tuple[int, int, int, int]) -> float:
    """Share of amber/gold pixels: the approved icon has many, a default icon has none."""
    c = img.convert("RGB").crop(box)
    px = list(c.getdata())
    gold = sum(1 for r, g, b in px if r > 170 and 90 < g < 215 and b < 110 and r - b > 100)
    return round(gold / max(1, len(px)), 4)


GOLD_MIN = 0.01


def cmd_launcher(a: argparse.Namespace) -> int:
    """Finds the launcher entry of ``--package`` among entries sharing ``--label``.

    Other installs with the same label (e.g. an older build of the real app) are recorded and
    cropped but never touched. The entry is identified by its amber/gold share.
    """
    d, out, tag = Adb(a.adb, a.serial), Path(a.out), a.tag
    out.mkdir(parents=True, exist_ok=True)

    def prop(k: str) -> str:
        return d.sh("getprop", k, check=False).strip()

    sdk = int(prop("ro.build.version.sdk") or 0)
    home = (
        d.sh(
            "cmd",
            "package",
            "resolve-activity",
            "--brief",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.HOME",
            check=False,
        )
        .strip()
        .splitlines()
    )
    report: dict = {
        "model": prop("ro.product.model"),
        "manufacturer": prop("ro.product.manufacturer"),
        "sdk": sdk,
        "density": d.sh("wm", "density", check=False).strip(),
        "size": d.sh("wm", "size", check=False).strip(),
        "launcher": home[-1] if home else None,
        "package": a.package,
        "installed_with_same_prefix": d.sh(
            "pm", "list", "packages", a.package.rsplit(".", 1)[0], check=False
        ).split(),
        "steps": {},
    }
    if f"package:{a.package}" not in d.sh("pm", "list", "packages", a.package, check=False).split():
        report["steps"]["installed"] = "FAIL: package not installed"
        report["result"] = "FAIL"
        write(out, f"launcher-{tag}.json", report)
        return 1
    w, h = map(int, re.findall(r"(\d+)x(\d+)", report["size"])[-1])
    bottom_limit = int(h * 0.92)  # above the gesture bar / screen edge

    def best(img: Image.Image, boxes: list, key: str) -> tuple | None:
        rows, pick = [], None
        for i, b in enumerate(boxes):
            full = b[3] <= bottom_limit
            g = gold_fraction(img, b)
            name = f"{tag}-{key}-match{i}.png"
            crop(img, b, out / name)
            rows.append({"bounds": b, "fully_visible": full, "gold_fraction": g, "crop": name})
            if full and g >= GOLD_MIN and (pick is None or g > pick[1]):
                pick = (b, g, name)
        report.setdefault("matches", {})[key] = rows
        return pick

    # App drawer: scroll until the gold entry is fully visible.
    d.sh("input", "keyevent", "KEYCODE_HOME")
    time.sleep(1.5)
    d.sh("input", "swipe", str(w // 2), str(int(h * 0.85)), str(w // 2), str(int(h * 0.25)), "350")
    time.sleep(2.0)
    pick = None
    for i in range(8):
        img = d.screencap(out / f"{tag}-app-drawer-{i}.png")
        pick = best(img, d.find_all(a.label), f"drawer{i}")
        if pick:
            shutil.copy2(out / f"{tag}-app-drawer-{i}.png", out / f"{tag}-app-drawer.png")
            shutil.copy2(out / pick[2], out / f"{tag}-app-drawer-icon.png")
            break
        d.sh(
            "input", "swipe", str(w // 2), str(int(h * 0.7)), str(w // 2), str(int(h * 0.45)), "400"
        )
        time.sleep(1.2)
    report["steps"]["drawer"] = (
        f"PASS: gold entry at {pick[0]} (gold share {pick[1]})"
        if pick
        else "FAIL: no fully visible launcher entry with the approved icon colours"
    )

    # Home screen: "Add to home screen" from the long-press menu, else drag to Home.
    on_home = None
    if pick:
        b = pick[0]
        cx, cy = (b[0] + b[2]) // 2, b[1] + (b[3] - b[1]) // 3
        d.sh("input", "swipe", str(cx), str(cy), str(cx), str(cy), "1200")
        time.sleep(2.0)
        how = "menu"
        if not d.tap_text(r"add to home|home screen"):
            d.sh("input", "keyevent", "KEYCODE_BACK")
            time.sleep(1.0)
            how = "drag"
            d.sh("input", "draganddrop", str(cx), str(cy), str(w // 2), str(h // 2), "2500")
        time.sleep(2.5)
        d.sh("input", "keyevent", "KEYCODE_HOME")
        time.sleep(1.5)
        home_img = d.screencap(out / f"{tag}-home.png")
        found = best(home_img, d.find_all(a.label), "home")
        if found:
            on_home = found[0]
            shutil.copy2(out / found[2], out / f"{tag}-home-icon.png")
            report["steps"]["home"] = f"PASS: placed via {how}, at {on_home}"
        else:
            report["steps"]["home"] = f"BLOCKED: placing on Home via {how} did not succeed"
    else:
        report["steps"]["home"] = "BLOCKED: no drawer entry"

    # Android 13+ themed icons via the launcher grid_control provider (restored afterwards).
    if sdk < 33:
        report["steps"]["themed"] = f"N/A: API {sdk} < 33"
    elif not on_home:
        report["steps"]["themed"] = "BLOCKED: themed icons render on Home; icon not on Home"
    else:
        launcher_pkg = (report["launcher"] or "").split("/")[0]
        auths = [
            f"{launcher_pkg}.grid_control",
            "com.android.launcher3.grid_control",
            "com.google.android.apps.nexuslauncher.grid_control",
        ]
        report["steps"]["themed"] = (
            "BLOCKED: launcher exposes no icon_themed control; toggle manually"
        )
        for auth in dict.fromkeys(auths):
            uri = f"content://{auth}/icon_themed"
            m = re.search(
                r"boolean_value=(\d)", d.sh("content", "query", "--uri", uri, check=False)
            )
            if not m:
                continue
            original = m.group(1) == "1"
            d.sh("content", "update", "--uri", uri, "--bind", "boolean_value:b:true", check=False)
            time.sleep(3.0)
            d.sh("input", "keyevent", "KEYCODE_HOME")
            time.sleep(1.5)
            now = re.search(
                r"boolean_value=(\d)", d.sh("content", "query", "--uri", uri, check=False)
            )
            themed = d.screencap(out / f"{tag}-home-themed.png")
            crop(themed, on_home, out / f"{tag}-home-themed-icon.png")
            g = gold_fraction(themed, on_home)
            d.sh(
                "content",
                "update",
                "--uri",
                uri,
                "--bind",
                f"boolean_value:b:{'true' if original else 'false'}",
                check=False,
            )
            ok = bool(now and now.group(1) == "1" and g < GOLD_MIN)
            report["steps"]["themed"] = (
                f"{'PASS' if ok else 'BLOCKED'}: {auth} themed=1 (restored to {int(original)}); "
                f"gold share at the icon {g} (themed tint replaces gold) - review the crop"
            )
            break
    d.sh("input", "keyevent", "KEYCODE_HOME")
    report["result"] = "PASS" if report["steps"]["drawer"].startswith("PASS") else "FAIL"
    write(out, f"launcher-{tag}.json", report)
    return 0 if report["result"] == "PASS" else 1


def main() -> int:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("apk")
    s.add_argument("--apk", required=True)
    s.add_argument("--aapt2", required=True)
    s.add_argument("--res", required=True)
    s.add_argument("--out", required=True)
    s = sub.add_parser("lint")
    s.add_argument("--report", required=True)
    s.add_argument("--out", required=True)
    s = sub.add_parser("ios")
    s.add_argument("--app", required=True)
    s.add_argument("--source", required=True)
    s.add_argument("--out", required=True)
    s = sub.add_parser("launcher")
    s.add_argument("--adb", required=True)
    s.add_argument("--serial", required=True)
    s.add_argument("--package", required=True)
    s.add_argument("--label", default="TalentForge")
    s.add_argument("--tag", default="android")
    s.add_argument("--out", required=True)
    a = p.parse_args()
    return {"apk": cmd_apk, "lint": cmd_lint, "ios": cmd_ios, "launcher": cmd_launcher}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
