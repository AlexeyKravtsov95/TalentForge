"""Regenerated visuals of a committed build: the same visuals, by meaning, or a blocker.

Regression for the first live run of the OTA preparation (GitHub Actions run 39776364185):
the committed 1.60.1.70450 icons were encoded on macOS arm64, whose Pillow 12.3.0 wheel
bundles zlib-ng; the ubuntu-24.04 x86_64 runner (manylinux wheel, zlib 1.3.1) encoded the
same client files into other PNG bytes for 353 of the 359 icons, and the byte-level checks
blocked the run although nothing had changed.
"""

from __future__ import annotations

import io
import json
import struct
import zlib
from pathlib import Path

import pytest
from PIL import Image

from talent_data.models.visuals import TalentIconRegistry, expected_content_hash, sha256_of
from talent_data.visuals.icons import (
    IconAssetError,
    convert_blp2,
    converter_description,
    read_png,
    same_image,
)
from talent_data.visuals.reproduction import REGISTRY_FILE, compare_visuals
from tests.helpers import FIXTURES

ROOT = Path(__file__).resolve().parents[3]
BUILD = "1.60.1.70450"
COMMITTED = ROOT / "data" / "visuals" / "forever" / BUILD
ICON = 132089
# The client file of 132089 in 1.60.1.70450 and the PNG the ubuntu-24.04 x86_64 runner made
# of it in run 39776364185 (tests/fixtures/README.md).
BLP_70450 = FIXTURES / "casc" / "forever" / BUILD / f"{ICON}.blp"
LINUX_PNG = FIXTURES / "visuals" / "forever" / BUILD / "linux-x86_64" / f"{ICON}.png"
LINUX_CONVERTER = "Pillow 12.3.0 BLP decoder -> PNG (compress_level=9; zlib 1.3.1; Linux x86_64)"


def committed_files() -> dict[str, bytes]:
    return {
        p.relative_to(COMMITTED).as_posix(): p.read_bytes()
        for p in sorted(COMMITTED.rglob("*"))
        if p.is_file() and not p.name.startswith(".")
    }


def registry_of(files: dict[str, bytes]) -> TalentIconRegistry:
    return TalentIconRegistry.model_validate_json(files[REGISTRY_FILE])


def with_icon(
    files: dict[str, bytes],
    file_data_id: int,
    png: bytes,
    *,
    converter: str | None = None,
    **changes: object,
) -> dict[str, bytes]:
    """``files`` with one icon replaced by ``png`` and a registry that is consistent with it
    (size, sha256, contentHash), as the pipeline would have written it."""
    registry = registry_of(files)
    icons = [
        icon.model_copy(update={"byte_length": len(png), "sha256": sha256_of(png), **changes})
        if icon.file_data_id == file_data_id
        else icon
        for icon in registry.icons
    ]
    provenance = registry.provenance
    if converter is not None:
        provenance = provenance.model_copy(update={"converter": converter})
    draft = registry.model_copy(update={"icons": icons, "provenance": provenance})
    draft = draft.model_copy(update={"content_hash": expected_content_hash(draft)})
    return {
        **files,
        REGISTRY_FILE: draft.to_json().encode("utf-8"),
        f"icons/{file_data_id}.png": png,
    }


def encode(image: Image.Image, **options: object) -> bytes:
    out = io.BytesIO()
    image.save(out, format="PNG", **options)
    return out.getvalue()


def decoded(data: bytes) -> Image.Image:
    with Image.open(io.BytesIO(data)) as image:
        image.load()
        return image.copy()


def chunk(kind: bytes, body: bytes) -> bytes:
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))


def png_with_rows(width: int, rows: list[tuple[int, bytes]], extra: bytes = b"") -> bytes:
    """An 8-bit RGB PNG whose rows carry the given filter types and filtered bytes."""
    ihdr = struct.pack(">IIBBBBB", width, len(rows), 8, 2, 0, 0, 0)
    raw = b"".join(bytes([kind]) + row for kind, row in rows)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + extra
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


def filtered_row(kind: int, row: bytes, prior: bytes, bpp: int = 3) -> bytes:
    out = bytearray()
    for i, value in enumerate(row):
        a = row[i - bpp] if i >= bpp else 0
        b = prior[i]
        c = prior[i - bpp] if i >= bpp else 0
        p = a + b - c
        paeth = (
            a
            if abs(p - a) <= abs(p - b) and abs(p - a) <= abs(p - c)
            else (b if abs(p - b) <= abs(p - c) else c)
        )
        predictor = (0, a, b, (a + b) >> 1, paeth)[kind]
        out.append((value - predictor) & 0xFF)
    return bytes(out)


# --------------------------------------------------------------------------- the PNG reader


def test_the_reader_decodes_every_committed_icon_like_pillow() -> None:
    for name, data in committed_files().items():
        if name.endswith(".png"):
            assert read_png(data).pixels() == decoded(data).tobytes(), name


@pytest.mark.parametrize("kind", [0, 1, 2, 3, 4])
def test_every_row_filter_is_undone(kind: int) -> None:
    """Pillow chose filters 0, 1, 2 and 4 for the committed icons; 3 (average) is covered
    too, so another encoder's filter choice is never mistaken for other pixels."""
    rows = [bytes(range(7, 19)), bytes(range(200, 212))]  # 2 rows of 4 RGB pixels
    plain = png_with_rows(4, [(0, row) for row in rows])
    png = png_with_rows(
        4,
        [
            (kind, filtered_row(kind, rows[0], bytes(12))),
            (kind, filtered_row(kind, rows[1], rows[0])),
        ],
    )
    assert read_png(png).pixels() == b"".join(rows) == decoded(png).tobytes()
    assert same_image(png, plain)


def test_the_reader_rejects_what_the_converter_never_writes() -> None:
    rows = [(0, bytes(12))]
    with pytest.raises(IconAssetError, match="gAMA"):
        read_png(png_with_rows(4, rows, extra=chunk(b"gAMA", struct.pack(">I", 45455))))
    damaged = bytearray(png_with_rows(4, rows))
    damaged[-20] ^= 0xFF  # inside the IDAT body
    with pytest.raises(IconAssetError, match="CRC"):
        read_png(bytes(damaged))
    with pytest.raises(IconAssetError, match="after IEND"):
        read_png(png_with_rows(4, rows) + b"\0")


# --------------------------------------------------------------------------- the live failure


def test_the_linux_png_of_the_live_run_is_the_committed_icon_in_other_bytes() -> None:
    """The live failure itself: the same client file, the same pixels, other PNG bytes."""
    committed = (COMMITTED / f"icons/{ICON}.png").read_bytes()
    linux = LINUX_PNG.read_bytes()
    entry = next(i for i in registry_of(committed_files()).icons if i.file_data_id == ICON)
    assert sha256_of(BLP_70450.read_bytes()) == entry.source_sha256
    assert linux != committed
    assert same_image(linux, committed)
    assert read_png(linux).filtered == read_png(committed).filtered  # only deflate differs
    converted = convert_blp2(ICON, BLP_70450.read_bytes()).png
    assert same_image(converted, committed) and same_image(converted, linux)


def test_a_regeneration_on_linux_holds_the_same_visuals() -> None:
    committed = committed_files()
    regenerated = with_icon(committed, ICON, LINUX_PNG.read_bytes(), converter=LINUX_CONVERTER)
    comparison = compare_visuals(committed, regenerated)
    assert comparison.equivalent, comparison.problems
    assert comparison.reencoded == [ICON]
    assert comparison.converters == (registry_of(committed).provenance.converter, LINUX_CONVERTER)
    assert comparison.identical_files == len(committed) - 2  # the PNG and icons.json


def test_every_icon_reencoded_with_other_settings_is_still_the_same_visuals() -> None:
    committed = committed_files()
    regenerated = dict(committed)
    for icon in registry_of(committed).icons:
        png = encode(decoded(committed[icon.file]), compress_level=1)
        regenerated = with_icon(regenerated, icon.file_data_id, png)
    comparison = compare_visuals(committed, regenerated)
    assert comparison.equivalent, comparison.problems
    assert len(comparison.reencoded) == len(registry_of(committed).icons)


def test_the_converter_records_what_the_png_bytes_depend_on() -> None:
    import platform

    import PIL
    from PIL import features

    described = converter_description()
    assert f"Pillow {PIL.__version__} " in described
    assert f"zlib {features.version('zlib')};" in described
    assert described.endswith(f"{platform.system()} {platform.machine()})")


def test_identical_visuals_are_equivalent() -> None:
    comparison = compare_visuals(committed_files(), committed_files())
    assert comparison.equivalent and comparison.reencoded == [] and comparison.converters is None


# --------------------------------------------------------------------------- real changes block


def test_changed_pixels_block() -> None:
    committed = committed_files()
    image = decoded(committed[f"icons/{ICON}.png"])
    image.putpixel((32, 32), (255, 0, 255, 255) if image.mode == "RGBA" else (255, 0, 255))
    comparison = compare_visuals(committed, with_icon(committed, ICON, encode(image)))
    assert not comparison.equivalent
    assert comparison.problems == [f"icon {ICON}: the image differs (size, colour type or pixels)"]


def test_a_changed_client_file_blocks_even_with_the_same_pixels() -> None:
    committed = committed_files()
    regenerated = with_icon(
        committed,
        ICON,
        LINUX_PNG.read_bytes(),
        source_sha256="sha256:" + "0" * 64,
        source_byte_length=6661,
    )
    comparison = compare_visuals(committed, regenerated)
    assert comparison.problems == [f"icon {ICON}: source_byte_length, source_sha256 differ"]


def test_changed_icon_ids_block() -> None:
    committed = committed_files()
    registry = registry_of(committed)
    first = registry.icons[0]
    dropped = registry.model_copy(update={"icons": registry.icons[1:]})
    dropped = dropped.model_copy(update={"content_hash": expected_content_hash(dropped)})
    regenerated = {k: v for k, v in committed.items() if k != first.file}
    regenerated[REGISTRY_FILE] = dropped.to_json().encode("utf-8")
    problems = compare_visuals(committed, regenerated).problems
    assert problems == [
        f"not regenerated: [{first.file!r}]",
        f"icons.json: the icon FileDataIDs differ (no longer used [{first.file_data_id}], new [])",
    ]


def test_a_changed_sidecar_blocks() -> None:
    committed = committed_files()
    document = json.loads(committed["mage.json"])
    document["entries"][0]["iconFileDataId"] = ICON  # another icon for one talent
    regenerated = {**committed, "mage.json": (json.dumps(document, indent=2) + "\n").encode()}
    problems = compare_visuals(committed, regenerated).problems
    assert problems == ["mage.json: the talent visuals differ (entries, icons or contentHash)"]


def test_a_sidecar_that_differs_only_in_provenance_is_the_same() -> None:
    committed = committed_files()
    document = json.loads(committed["mage.json"])
    document["provenance"] = {**document["provenance"], "source": "another mirror"}
    regenerated = {**committed, "mage.json": (json.dumps(document, indent=2) + "\n").encode()}
    assert compare_visuals(committed, regenerated).equivalent


def test_an_image_that_does_not_match_its_registry_entry_blocks() -> None:
    committed = committed_files()
    regenerated = {**committed, f"icons/{ICON}.png": LINUX_PNG.read_bytes()}  # registry stale
    assert compare_visuals(committed, regenerated).problems == [
        f"icon {ICON}: the regenerated icons/{ICON}.png does not match its registry entry"
    ]


def test_an_extra_file_blocks() -> None:
    committed = committed_files()
    regenerated = {**committed, "icons/1.png": LINUX_PNG.read_bytes()}
    assert compare_visuals(committed, regenerated).problems == ["new files: ['icons/1.png']"]
