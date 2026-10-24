"""The App Store screenshot validator (M5.IOS.3).

``scripts/verify_appstore_screenshots.py`` decides whether the pack's screenshots can be
uploaded to App Store Connect. These tests write synthetic PNG files of the real sizes (a
uniform image compresses to a few kilobytes) and pin every fail-closed rule: layout, sizes per
display class, RGB without alpha, PNG integrity, numbering, locale parity and the manifest.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import struct
import sys
import zlib
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[2]


def load_validator() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "verify_appstore_screenshots", ROOT / "scripts" / "verify_appstore_screenshots.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


v = load_validator()
IPHONE, IPAD = (1206, 2622), (2064, 2752)
SCREENS = ("editor", "class-picker")


def chunk(kind: bytes, body: bytes) -> bytes:
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))


def png(
    width: int,
    height: int,
    *,
    colour: int = 2,
    extra: bytes = b"",
    interlace: int = 0,
    rows: bytes | None = None,
) -> bytes:
    channels = {2: 3, 6: 4}[colour]
    raw = rows if rows is not None else (b"\0" + b"\x20" * width * channels) * height
    header = struct.pack(">IIBBBBB", width, height, 8, colour, 0, 0, interlace)
    return (
        v.PNG_SIGNATURE
        + chunk(b"IHDR", header)
        + extra
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def make_pack(tmp_path: Path, files: dict[str, bytes] | None = None) -> Path:
    root = tmp_path / "screenshots"
    if files is None:
        files = {
            f"{locale}/{display}/{order:02d}-{screen}.png": png(*size)
            for locale in v.LOCALES
            for display, size in (("iphone-6.3", IPHONE), ("ipad-13", IPAD))
            for order, screen in enumerate(SCREENS, start=1)
        }
    for name, data in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    write_manifest(root)
    return root


def write_manifest(root: Path) -> None:
    entries = []
    for path in sorted(root.rglob("*.png")):
        data = path.read_bytes()
        width, height = struct.unpack(">II", data[16:24]) if len(data) >= 24 else (0, 0)
        entries.append(
            {
                "path": path.relative_to(root).as_posix(),
                "width": width,
                "height": height,
                "bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            }
        )
    manifest = {"schema": v.MANIFEST_SCHEMA, "capture": {}, "screenshots": entries}
    (root / v.MANIFEST).write_text(json.dumps(manifest), encoding="utf-8")


def failed(root: Path) -> dict[str, str]:
    evaluation, _ = v.evaluate(root)
    return {check.id: check.detail for check in evaluation.checks if not check.passed}


def replace(root: Path, name: str, data: bytes) -> None:
    (root / name).write_bytes(data)
    write_manifest(root)


def test_the_committed_pack_passes() -> None:
    evaluation, report = v.evaluate(v.DEFAULT_SCREENSHOTS)
    assert {c.id: c.detail for c in evaluation.checks if not c.passed} == {}
    sizes = {(s["width"], s["height"]) for s in report["screenshots"]}
    assert sizes == {IPHONE, IPAD}


def test_a_complete_pack_passes(tmp_path: Path) -> None:
    root = make_pack(tmp_path)
    _, report = v.evaluate(root)
    assert failed(root) == {}
    assert report["result"] == "PASS"
    assert len(report["screenshots"]) == 8


@pytest.mark.parametrize(
    ("display", "size"),
    [
        ("iphone-6.3", (1179, 2556)),
        ("iphone-6.3", (2622, 1206)),
        ("ipad-13", (2048, 2732)),
        ("ipad-13", (2752, 2064)),
    ],
)
def test_every_accepted_size_and_orientation_passes(
    tmp_path: Path, display: str, size: tuple[int, int]
) -> None:
    root = make_pack(tmp_path)
    replace(root, f"ru/{display}/01-editor.png", png(*size))
    assert failed(root) == {}


@pytest.mark.parametrize(
    ("display", "size"),
    [
        ("iphone-6.3", (1320, 2868)),  # 6.9" (large): another display class
        ("iphone-6.3", (1206, 2620)),  # cropped by two pixels
        ("ipad-13", (1668, 2420)),  # 11"
        ("ipad-13", IPHONE),
    ],
)
def test_a_size_of_another_display_class_fails(
    tmp_path: Path, display: str, size: tuple[int, int]
) -> None:
    root = make_pack(tmp_path)
    name = f"en-US/{display}/02-class-picker.png"
    replace(root, name, png(*size))
    assert "is not a" in failed(root)[f"png:{name}"]


def test_an_alpha_channel_fails(tmp_path: Path) -> None:
    root = make_pack(tmp_path)
    name = "en-US/iphone-6.3/01-editor.png"
    replace(root, name, png(*IPHONE, colour=6))
    assert "colour type 6" in failed(root)[f"png:{name}"]


def test_a_transparency_chunk_fails(tmp_path: Path) -> None:
    root = make_pack(tmp_path)
    name = "ru/ipad-13/01-editor.png"
    replace(root, name, png(*IPAD, extra=chunk(b"tRNS", b"\0\0\0\0\0\0")))
    assert "tRNS" in failed(root)[f"png:{name}"]


def test_an_interlaced_png_fails(tmp_path: Path) -> None:
    root = make_pack(tmp_path)
    name = "ru/ipad-13/01-editor.png"
    replace(root, name, png(*IPAD, interlace=1))
    assert "interlaced" in failed(root)[f"png:{name}"]


def test_a_damaged_png_fails(tmp_path: Path) -> None:
    root = make_pack(tmp_path)
    name = "en-US/ipad-13/01-editor.png"
    data = bytearray(png(*IPAD))
    data[45] ^= 0xFF  # inside IDAT: the chunk CRC no longer matches
    replace(root, name, bytes(data))
    assert "bad CRC" in failed(root)[f"png:{name}"]


def test_image_data_of_the_wrong_size_fails(tmp_path: Path) -> None:
    root = make_pack(tmp_path)
    name = "en-US/ipad-13/01-editor.png"
    replace(root, name, png(*IPAD, rows=b"\0" * 10))
    assert "does not match the declared size" in failed(root)[f"png:{name}"]


def test_a_truncated_file_or_another_format_fails(tmp_path: Path) -> None:
    root = make_pack(tmp_path)
    truncated, jpeg = "en-US/ipad-13/01-editor.png", "ru/ipad-13/01-editor.png"
    (root / truncated).write_bytes(png(*IPAD)[:-12])
    (root / jpeg).write_bytes(b"\xff\xd8\xff\xe0 JFIF")
    write_manifest(root)
    problems = failed(root)
    assert "no IEND chunk" in problems[f"png:{truncated}"]
    assert problems[f"png:{jpeg}"].startswith("not a PNG file")


def test_each_locale_needs_both_displays(tmp_path: Path) -> None:
    root = make_pack(tmp_path)
    for path in (root / "ru/ipad-13").iterdir():
        path.unlink()
    write_manifest(root)
    problems = failed(root)
    assert "0 screenshots" in problems["count:ru/ipad-13"]
    assert "locales-match:ipad-13" in problems


def test_numbering_has_no_gaps(tmp_path: Path) -> None:
    root = make_pack(tmp_path)
    (root / "en-US/iphone-6.3/02-class-picker.png").rename(
        root / "en-US/iphone-6.3/03-class-picker.png"
    )
    write_manifest(root)
    assert "count:en-US/iphone-6.3" in failed(root)


def test_at_most_ten_per_display(tmp_path: Path) -> None:
    files = {
        f"{locale}/{display}/{order:02d}-screen-{order}.png": png(*size)
        for locale in v.LOCALES
        for display, size in (("iphone-6.3", IPHONE), ("ipad-13", IPAD))
        for order in range(1, 12)
    }
    assert "count:en-US/iphone-6.3" in failed(make_pack(tmp_path, files))


def test_locales_must_show_the_same_screens(tmp_path: Path) -> None:
    root = make_pack(tmp_path)
    (root / "ru/iphone-6.3/02-class-picker.png").rename(root / "ru/iphone-6.3/02-share.png")
    write_manifest(root)
    assert "locales-match:iphone-6.3" in failed(root)


@pytest.mark.parametrize(
    "name",
    ["de/iphone-6.3/01-editor.png", "en-US/iphone-6.9/01-editor.png", "en-US/ipad-13/1-a.png"],
)
def test_files_outside_the_layout_fail(tmp_path: Path, name: str) -> None:
    root = make_pack(tmp_path)
    (root / name).parent.mkdir(parents=True, exist_ok=True)
    (root / name).write_bytes(png(*IPHONE))
    write_manifest(root)
    assert name in failed(root)["layout"]


def test_an_edited_file_no_longer_matches_the_manifest(tmp_path: Path) -> None:
    root = make_pack(tmp_path)
    name = "en-US/iphone-6.3/01-editor.png"
    (root / name).write_bytes(png(*IPHONE, rows=(b"\0" + b"\x21" * IPHONE[0] * 3) * IPHONE[1]))
    assert f"manifest:{name}" in failed(root)


def test_the_manifest_lists_only_existing_files(tmp_path: Path) -> None:
    root = make_pack(tmp_path)
    manifest: dict[str, Any] = json.loads((root / v.MANIFEST).read_text(encoding="utf-8"))
    manifest["screenshots"].append({"path": "en-US/iphone-6.3/09-gone.png"})
    (root / v.MANIFEST).write_text(json.dumps(manifest), encoding="utf-8")
    assert "09-gone" in failed(root)["manifest-complete"]


@pytest.mark.parametrize("content", ["not json", json.dumps({"schema": "other"})])
def test_an_unreadable_manifest_cannot_run(tmp_path: Path, content: str) -> None:
    root = make_pack(tmp_path)
    (root / v.MANIFEST).write_text(content, encoding="utf-8")
    with pytest.raises(v.ToolError):
        v.evaluate(root)


def test_cli_writes_a_report_and_exits_by_result(tmp_path: Path) -> None:
    root = make_pack(tmp_path)
    report = tmp_path / "report.json"
    assert v.main(["--screenshots", str(root), "--report", str(report)]) == v.EXIT_PASS
    assert json.loads(report.read_text(encoding="utf-8"))["result"] == "PASS"
    replace(root, "ru/ipad-13/01-editor.png", png(*IPHONE))
    assert v.main(["--screenshots", str(root)]) == v.EXIT_FAIL
    assert v.main(["--screenshots", str(tmp_path / "missing")]) == v.EXIT_ERROR
