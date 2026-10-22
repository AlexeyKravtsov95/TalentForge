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
import platform
import struct
import zlib
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


# The only chunks the converter writes; anything else could change how the image renders.
_PNG_CHUNKS = (b"IHDR", b"IDAT", b"IEND")
_BYTES_PER_PIXEL = {2: 3, 6: 4}


@dataclass(frozen=True)
class PngImage:
    """A converter PNG, inflated but still filtered: one filter-type byte, then the row."""

    width: int
    height: int
    colour_type: int
    filtered: bytes

    def pixels(self) -> bytes:
        """The unfiltered 8-bit samples, row after row (PNG specification §9)."""
        bpp = _BYTES_PER_PIXEL[self.colour_type]
        stride = self.width * bpp
        out = bytearray()
        prior = bytearray(stride)
        for y in range(self.height):
            start = y * (stride + 1)
            kind = self.filtered[start]
            row = bytearray(self.filtered[start + 1 : start + 1 + stride])
            if kind == 1:
                for i in range(bpp, stride):
                    row[i] = (row[i] + row[i - bpp]) & 0xFF
            elif kind == 2:
                for i in range(stride):
                    row[i] = (row[i] + prior[i]) & 0xFF
            elif kind == 3:
                for i in range(stride):
                    left = row[i - bpp] if i >= bpp else 0
                    row[i] = (row[i] + ((left + prior[i]) >> 1)) & 0xFF
            elif kind == 4:
                for i in range(stride):
                    a = row[i - bpp] if i >= bpp else 0
                    b = prior[i]
                    c = prior[i - bpp] if i >= bpp else 0
                    p = a + b - c
                    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                    pred = a if pa <= pb and pa <= pc else b if pb <= pc else c
                    row[i] = (row[i] + pred) & 0xFF
            elif kind != 0:
                raise IconAssetError(f"PNG row {y} has unknown filter type {kind}")
            out += row
            prior = row
        return bytes(out)


def read_png(data: bytes) -> PngImage:
    """Parse a PNG as the converter writes it - ``IHDR``, ``IDAT``..., ``IEND``, 8-bit RGB or
    RGBA, not interlaced - verifying every CRC and the zlib stream; reject anything else."""
    width, height = read_png_size(data)
    offset, kinds, idat = 8, [], bytearray()
    while offset < len(data):
        if offset + 12 > len(data):
            raise IconAssetError("PNG ends inside a chunk header")
        (length,) = struct.unpack_from(">I", data, offset)
        kind = data[offset + 4 : offset + 8]
        body = data[offset + 8 : offset + 8 + length]
        if offset + 12 + length > len(data):
            raise IconAssetError(f"PNG chunk {kind!r} is truncated")
        (crc,) = struct.unpack_from(">I", data, offset + 8 + length)
        if zlib.crc32(kind + body) != crc:
            raise IconAssetError(f"PNG chunk {kind!r} has a wrong CRC")
        if kind not in _PNG_CHUNKS:
            raise IconAssetError(f"PNG chunk {kind!r} is not one the converter writes")
        kinds.append(kind)
        if kind == b"IHDR" and (body[10], body[11], body[12]) != (0, 0, 0):
            raise IconAssetError("PNG is interlaced or uses a non-standard method")
        if kind == b"IDAT":
            idat += body
        offset += 12 + length
        if kind == b"IEND":
            break
    if offset != len(data):
        raise IconAssetError("PNG has data after IEND")
    if kinds.count(b"IHDR") != 1 or kinds[0] != b"IHDR" or kinds[-1] != b"IEND":
        raise IconAssetError("PNG chunks are not IHDR, IDAT..., IEND")
    if b"IDAT" not in kinds:
        raise IconAssetError("PNG has no image data")
    inflater = zlib.decompressobj()
    try:
        filtered = inflater.decompress(bytes(idat)) + inflater.flush()
    except zlib.error as exc:
        raise IconAssetError(f"PNG image data is not a valid zlib stream: {exc}") from exc
    if not inflater.eof or inflater.unused_data:
        raise IconAssetError("PNG image data is not exactly one zlib stream")
    colour_type = data[25]
    if len(filtered) != height * (1 + width * _BYTES_PER_PIXEL[colour_type]):
        raise IconAssetError(f"PNG image data does not hold {width}x{height} pixels")
    return PngImage(width, height, colour_type, filtered)


def same_image(a: bytes, b: bytes) -> bool:
    """Whether two converter PNGs hold the same image: size, colour type and every pixel.

    The compressed bytes are not compared - they depend on the zlib build (see
    ``docs/talent-visuals.md`` §3). Equal filtered streams already mean equal pixels; only
    when the encoders chose other row filters are the rows unfiltered and compared.
    """
    first, second = read_png(a), read_png(b)
    if (first.width, first.height, first.colour_type) != (
        second.width,
        second.height,
        second.colour_type,
    ):
        return False
    return first.filtered == second.filtered or first.pixels() == second.pixels()


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
    """The converter and the environment its PNG bytes depend on: Pillow's zlib build and
    the platform (the pixels do not depend on either; see ``docs/talent-visuals.md`` §3)."""
    try:
        import PIL
        from PIL import features
    except ImportError:  # pragma: no cover - environment dependent
        return "Pillow BLP decoder -> PNG"
    return (
        f"Pillow {PIL.__version__} BLP decoder -> PNG (compress_level=9; "
        f"zlib {features.version('zlib')}; {platform.system()} {platform.machine()})"
    )


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
