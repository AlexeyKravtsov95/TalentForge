#!/usr/bin/env python3
"""Validate the App Store screenshots of the TalentForge submission pack (M5.IOS.3).

Layout (``docs/release/apple-appstore/submission/screenshots``)::

    manifest.json
    <locale>/<display>/<NN>-<screen>.png     locales: en-US, ru; displays: iphone-6.3, ipad-13

``NN`` is the upload order in App Store Connect. ``manifest.json`` records, for every file, its
size and SHA-256 and how it was captured (device, runtime, build), so a screenshot that is
replaced or edited after the capture no longer passes.

Every check must pass (``Screenshot specifications``, App Store Connect Help, read 2026-10-24):

- both locales have both required display classes: iPhone with Dynamic Island (medium display:
  1206 x 2622 or 1179 x 2556) and iPad 13-inch (2064 x 2752 or 2048 x 2732), portrait or
  landscape, between 1 and 10 screenshots each, numbered 01..N without gaps;
- the locales show the same screens in the same order;
- each file is a complete, uncorrupted PNG: valid signature and chunk CRCs, ``IHDR`` 8-bit RGB
  (colour type 2: no alpha channel), no ``tRNS`` transparency, not interlaced, and the image
  data decompresses to exactly the declared size ("Images can't include alpha channels or
  transparencies");
- the manifest lists exactly these files, with their dimensions, byte size and SHA-256.

Content (real UI, the right language, no notifications or debug elements) cannot be decided
from pixels by a script: it is reviewed by eye and recorded in the pack's README.

Usage::

    python3 scripts/verify_appstore_screenshots.py [--screenshots DIR] [--report report.json]

Exit status: 0 = all checks pass, 1 = a check failed, 2 = the check could not run.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import sys
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SCREENSHOTS = ROOT / "docs/release/apple-appstore/submission/screenshots"
MANIFEST = "manifest.json"
MANIFEST_SCHEMA = "talentforge.appstore-screenshots.v1"
REPORT_SCHEMA = "talentforge.appstore-screenshots-report.v1"
LOCALES = ("en-US", "ru")
# Portrait sizes App Store Connect accepts per display class; landscape is the transpose.
DISPLAYS: dict[str, tuple[tuple[int, int], ...]] = {
    "iphone-6.3": ((1206, 2622), (1179, 2556)),
    "ipad-13": ((2064, 2752), (2048, 2732)),
}
MAX_PER_DISPLAY = 10
FILE_NAME = re.compile(r"(?P<order>\d{2})-(?P<screen>[a-z0-9]+(?:-[a-z0-9]+)*)\.png")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
RGB, BIT_DEPTH_8 = 2, 8

EXIT_PASS, EXIT_FAIL, EXIT_ERROR = 0, 1, 2


class ToolError(Exception):
    """The screenshots directory or its manifest could not be read."""


@dataclass(frozen=True)
class Check:
    id: str
    passed: bool
    detail: str


@dataclass
class Evaluation:
    checks: list[Check] = field(default_factory=list)

    def add(self, check_id: str, passed: bool, detail: str) -> None:
        self.checks.append(Check(check_id, passed, detail))

    @property
    def passed(self) -> bool:
        return bool(self.checks) and all(check.passed for check in self.checks)


@dataclass(frozen=True)
class PngInfo:
    width: int
    height: int
    problems: tuple[str, ...]


def inspect_png(data: bytes) -> PngInfo:
    """Width, height and every reason ``data`` is not an App Store-ready PNG (none = ready)."""
    if not data.startswith(PNG_SIGNATURE):
        return PngInfo(0, 0, ("not a PNG file",))
    problems: list[str] = []
    header: tuple[int, int, int, int, int] | None = None
    image_data = bytearray()
    offset, ended = len(PNG_SIGNATURE), False
    while offset < len(data):
        if offset + 8 > len(data):
            problems.append("truncated chunk header")
            break
        length, kind = struct.unpack(">I4s", data[offset : offset + 8])
        body = data[offset + 8 : offset + 8 + length]
        crc = data[offset + 8 + length : offset + 12 + length]
        if len(body) != length or len(crc) != 4:
            problems.append(f"truncated {kind.decode('latin-1')} chunk")
            break
        if zlib.crc32(kind + body) != struct.unpack(">I", crc)[0]:
            problems.append(f"bad CRC in the {kind.decode('latin-1')} chunk")
        if kind == b"IHDR" and length == 13:
            width, height, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", body)
            header = (width, height, depth, colour, interlace)
        elif kind == b"tRNS":
            problems.append("has a tRNS transparency chunk")
        elif kind == b"IDAT":
            image_data += body
        elif kind == b"IEND":
            ended = True
            break
        offset += 12 + length
    if header is None:
        return PngInfo(0, 0, (*problems, "no IHDR chunk"))
    width, height, depth, colour, interlace = header
    if colour != RGB or depth != BIT_DEPTH_8:
        problems.append(
            f"colour type {colour}, bit depth {depth} (expected 8-bit RGB without alpha)"
        )
    if interlace:
        problems.append("interlaced")
    if not ended:
        problems.append("no IEND chunk")
    if colour == RGB and depth == BIT_DEPTH_8 and not interlace:
        try:
            raw = zlib.decompress(bytes(image_data))
        except zlib.error:
            problems.append("the image data does not decompress")
        else:
            if len(raw) != height * (1 + width * 3):
                problems.append("the image data does not match the declared size")
    return PngInfo(width, height, tuple(problems))


def accepted_sizes(display: str) -> set[tuple[int, int]]:
    portrait = set(DISPLAYS[display])
    return portrait | {(height, width) for width, height in portrait}


def load_manifest(screenshots: Path) -> dict[str, dict[str, Any]]:
    path = screenshots / MANIFEST
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise ToolError(f"{path} is not readable JSON: {error}") from error
    if not isinstance(manifest, dict) or manifest.get("schema") != MANIFEST_SCHEMA:
        raise ToolError(f"{path} is not a {MANIFEST_SCHEMA} manifest")
    entries = manifest.get("screenshots")
    if not isinstance(entries, list) or not all(
        isinstance(entry, dict) and isinstance(entry.get("path"), str) for entry in entries
    ):
        raise ToolError(f"{path}: 'screenshots' must be a list of objects with a 'path'")
    return {entry["path"]: entry for entry in entries}


def _is_laid_out(name: str) -> bool:
    parts = name.split("/")
    return (
        len(parts) == 3
        and parts[0] in LOCALES
        and parts[1] in DISPLAYS
        and FILE_NAME.fullmatch(parts[2]) is not None
    )


def evaluate(screenshots: Path) -> tuple[Evaluation, dict[str, Any]]:
    """Run every check on the screenshots directory; raises ToolError when it is unreadable."""
    if not screenshots.is_dir():
        raise ToolError(f"{screenshots} is not a directory")
    manifest = load_manifest(screenshots)
    evaluation = Evaluation()
    files = sorted(
        path.relative_to(screenshots).as_posix()
        for path in screenshots.rglob("*")
        if path.is_file() and path.name != MANIFEST and not path.name.startswith(".")
    )
    stray = [name for name in files if not _is_laid_out(name)]
    evaluation.add(
        "layout",
        not stray,
        f"files outside <locale>/<display>/<NN>-<screen>.png: {stray}"
        if stray
        else "every file is <locale>/<display>/<NN>-<screen>.png",
    )

    report_files: list[dict[str, Any]] = []
    screens: dict[tuple[str, str], list[str]] = {}
    for locale in LOCALES:
        for display in DISPLAYS:
            prefix = f"{locale}/{display}/"
            names = [name for name in files if name.startswith(prefix) and name not in stray]
            matches = [FILE_NAME.fullmatch(name.removeprefix(prefix)) for name in names]
            orders = [int(match["order"]) for match in matches if match]
            screens[(locale, display)] = [match["screen"] for match in matches if match]
            evaluation.add(
                f"count:{locale}/{display}",
                1 <= len(names) <= MAX_PER_DISPLAY and orders == list(range(1, len(names) + 1)),
                f"{len(names)} screenshots numbered {orders} "
                f"(expected 1-{MAX_PER_DISPLAY}, numbered from 01 without gaps)",
            )
            for name in names:
                report_files.append(_check_file(evaluation, screenshots, name, display, manifest))
    for display in DISPLAYS:
        reference = screens[(LOCALES[0], display)]
        for locale in LOCALES[1:]:
            evaluation.add(
                f"locales-match:{display}",
                screens[(locale, display)] == reference,
                f"{locale}: {screens[(locale, display)]}; {LOCALES[0]}: {reference}",
            )

    unlisted = sorted(set(manifest) - set(files))
    evaluation.add(
        "manifest-complete",
        not unlisted,
        f"the manifest lists files that do not exist: {unlisted}"
        if unlisted
        else "every manifest entry exists",
    )
    report: dict[str, Any] = {
        "schema": REPORT_SCHEMA,
        "screenshots": report_files,
        "checks": [{"id": c.id, "passed": c.passed, "detail": c.detail} for c in evaluation.checks],
        "result": "PASS" if evaluation.passed else "FAIL",
    }
    return evaluation, report


def _check_file(
    evaluation: Evaluation,
    screenshots: Path,
    name: str,
    display: str,
    manifest: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    data = (screenshots / name).read_bytes()
    png = inspect_png(data)
    problems = list(png.problems)
    if (png.width, png.height) not in accepted_sizes(display):
        problems.append(
            f"{png.width} x {png.height} is not a {display} size "
            f"(portrait {list(DISPLAYS[display])}, or landscape)"
        )
    evaluation.add(
        f"png:{name}",
        not problems,
        "; ".join(problems) or f"{png.width} x {png.height}, 8-bit RGB, no alpha",
    )
    facts = {
        "path": name,
        "width": png.width,
        "height": png.height,
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }
    entry = manifest.get(name, {})
    recorded = all(entry.get(key) == value for key, value in facts.items())
    evaluation.add(
        f"manifest:{name}",
        recorded,
        "recorded in the manifest with these dimensions, size and SHA-256"
        if recorded
        else f"the manifest does not record this file as it is: {facts}",
    )
    return facts


def format_summary(evaluation: Evaluation) -> str:
    lines = ["App Store screenshots:"]
    lines += [f"  [{'PASS' if c.passed else 'FAIL'}] {c.id}: {c.detail}" for c in evaluation.checks]
    lines.append(f"RESULT: {'PASS' if evaluation.passed else 'FAIL'}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0] if __doc__ else None)
    parser.add_argument(
        "--screenshots",
        default=str(DEFAULT_SCREENSHOTS),
        help="the submission screenshots directory",
    )
    parser.add_argument("--report", help="write the JSON report to this file")
    args = parser.parse_args(argv)
    try:
        evaluation, report = evaluate(Path(args.screenshots))
    except ToolError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return EXIT_ERROR
    print(format_summary(evaluation))
    if args.report:
        Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return EXIT_PASS if evaluation.passed else EXIT_FAIL


if __name__ == "__main__":
    sys.exit(main())
