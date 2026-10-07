"""Client icon files -> bundled images, and the per-build icon registry.

The talent icons of 1.60.1.70009 are BLP2 textures (64x64, DXT-compressed, with a real
alpha channel: the rounded icon corners are transparent). They are converted, at build time
only, to 8-bit PNG - lossless with respect to the decoded texture, alpha preserved, native
size, no resampling and no effects.

Decoding uses Pillow's maintained BLP plugin instead of a hand-written DXT decoder. Pillow
is an optional dependency (``pip install -e ".[visuals]"``) needed only to *generate*
assets; validating the committed assets (:func:`read_png_size`, the registry checks) needs
nothing but the standard library, so normal CI stays dependency-light and offline.
"""

from __future__ import annotations

import io
import struct
from dataclasses import dataclass

from talent_data.models.visuals import (
    ICON_IMAGE_FORMAT,
    ICONS_SCHEMA_VERSION,
    IconAsset,
    IconsProvenance,
    TalentIconRegistry,
    content_hash,
    hashed_payload,
    sha256_of,
)
from talent_data.sources.base import SourceError

BLP2_MAGIC = b"BLP2"
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
ICON_DIR = "icons"

# PNG colour types written by the converter: 2 = truecolour, 6 = truecolour with alpha.
_PNG_COLOUR_TYPES = frozenset({2, 6})


class IconAssetError(SourceError):
    """An icon file is not what the pipeline can safely convert or bundle."""


def icon_file_name(file_data_id: int) -> str:
    return f"{ICON_DIR}/{file_data_id}.png"


def read_blp2_size(data: bytes) -> tuple[int, int]:
    """``(width, height)`` from a BLP2 header; rejects every other payload."""
    if len(data) < 20 or data[:4] != BLP2_MAGIC:
        raise IconAssetError(f"not a BLP2 file (starts with {data[:4]!r}, {len(data)} bytes)")
    width, height = struct.unpack_from("<II", data, 12)
    if width <= 0 or height <= 0:
        raise IconAssetError(f"BLP2 header has an empty image ({width}x{height})")
    return int(width), int(height)


def read_png_size(data: bytes) -> tuple[int, int]:
    """``(width, height)`` of an 8-bit truecolour PNG; rejects every other payload."""
    if len(data) < 33 or data[:8] != PNG_SIGNATURE or data[12:16] != b"IHDR":
        raise IconAssetError("not a PNG file")
    width, height, bit_depth, colour_type = struct.unpack_from(">IIBB", data, 16)
    if bit_depth != 8 or colour_type not in _PNG_COLOUR_TYPES:
        raise IconAssetError(
            f"unsupported PNG (bit depth {bit_depth}, colour type {colour_type}); "
            "bundled icons are 8-bit RGB or RGBA"
        )
    if width <= 0 or height <= 0:
        raise IconAssetError(f"PNG has an empty image ({width}x{height})")
    return int(width), int(height)


@dataclass(frozen=True)
class ConvertedIcon:
    file_data_id: int
    png: bytes
    width: int
    height: int
    source_byte_length: int
    source_sha256: str


def convert_blp2(file_data_id: int, data: bytes) -> ConvertedIcon:
    """Decode a BLP2 client texture and re-encode it as PNG, pixel for pixel."""
    width, height = read_blp2_size(data)
    try:
        from PIL import Image
    except ImportError as exc:  # pragma: no cover - environment dependent
        raise IconAssetError(
            'converting client icons needs Pillow: pip install -e ".[visuals]"'
        ) from exc

    try:
        with Image.open(io.BytesIO(data), formats=["BLP"]) as image:
            image.load()
            if image.size != (width, height):
                raise IconAssetError(
                    f"file {file_data_id}: decoded {image.size}, header says {(width, height)}"
                )
            if image.mode not in ("RGB", "RGBA"):
                raise IconAssetError(f"file {file_data_id}: unexpected pixel mode {image.mode}")
            out = io.BytesIO()
            # Fixed encoder settings; no ancillary chunks (no timestamps, no text).
            image.save(out, format="PNG", optimize=False, compress_level=9)
    except (OSError, ValueError, SyntaxError) as exc:
        raise IconAssetError(f"file {file_data_id}: cannot decode BLP2 texture: {exc}") from exc
    png = out.getvalue()
    if read_png_size(png) != (width, height):
        raise IconAssetError(f"file {file_data_id}: PNG size differs from the source texture")
    return ConvertedIcon(
        file_data_id=file_data_id,
        png=png,
        width=width,
        height=height,
        source_byte_length=len(data),
        source_sha256=sha256_of(data),
    )


def converter_description() -> str:
    try:
        import PIL
    except ImportError:  # pragma: no cover - environment dependent
        return "Pillow BLP decoder -> PNG"
    return f"Pillow {PIL.__version__} BLP decoder -> PNG (compress_level=9)"


def build_icon_registry(
    *, game: str, client_build: str, icons: list[ConvertedIcon], source_name: str
) -> TalentIconRegistry:
    ordered = sorted(icons, key=lambda icon: icon.file_data_id)
    ids = [icon.file_data_id for icon in ordered]
    if len(ids) != len(set(ids)):
        raise IconAssetError("duplicate FileDataID in the icon set")
    draft = TalentIconRegistry(
        schema_version=ICONS_SCHEMA_VERSION,
        game=game,
        client_build=client_build,
        image_format=ICON_IMAGE_FORMAT,
        icons=[
            IconAsset(
                file_data_id=icon.file_data_id,
                file=icon_file_name(icon.file_data_id),
                width=icon.width,
                height=icon.height,
                byte_length=len(icon.png),
                sha256=sha256_of(icon.png),
                source_format="BLP2",
                source_byte_length=icon.source_byte_length,
                source_sha256=icon.source_sha256,
            )
            for icon in ordered
        ],
        content_hash="sha256:" + "0" * 64,
        provenance=IconsProvenance(
            source=source_name,
            retrieval="client file by FileDataID, pinned to clientBuild",
            converter=converter_description(),
        ),
    )
    digest = content_hash(hashed_payload(draft.model_dump(mode="json", by_alias=True)))
    return draft.model_copy(update={"content_hash": digest})
