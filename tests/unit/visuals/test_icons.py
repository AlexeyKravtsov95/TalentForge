"""BLP2 client texture -> PNG conversion and the icon registry / file integrity checks."""

from __future__ import annotations

import io
import shutil
from pathlib import Path

import pytest
from PIL import Image

from talent_data.models.visuals import expected_content_hash, sha256_of
from talent_data.visuals.icons import (
    IconAssetError,
    build_icon_registry,
    convert_blp2,
    read_blp2_size,
    read_png_size,
)
from talent_data.visuals.pipeline import check_icon_registry, read_icon_registry
from tests.helpers import NEW_BUILD
from tests.unit.visuals.conftest import (
    BLP_WITH_ALPHA,
    BLP_WITHOUT_ALPHA,
    CANONICAL_VISUALS,
    blp_bytes,
)


def decoded(data: bytes) -> Image.Image:
    with Image.open(io.BytesIO(data)) as image:
        image.load()
        return image.copy()


@pytest.mark.parametrize("file_data_id", [BLP_WITH_ALPHA, BLP_WITHOUT_ALPHA])
def test_real_client_icon_is_a_64x64_blp2(file_data_id: int) -> None:
    data = blp_bytes(file_data_id)
    assert data[:4] == b"BLP2"
    assert read_blp2_size(data) == (64, 64)


@pytest.mark.parametrize("file_data_id", [BLP_WITH_ALPHA, BLP_WITHOUT_ALPHA])
def test_conversion_is_pixel_exact_and_keeps_the_native_size(file_data_id: int) -> None:
    source = blp_bytes(file_data_id)
    converted = convert_blp2(file_data_id, source)
    assert (converted.width, converted.height) == (64, 64)
    assert read_png_size(converted.png) == (64, 64)
    texture, image = decoded(source), decoded(converted.png)
    assert image.mode == texture.mode
    assert image.tobytes() == texture.tobytes()
    assert converted.source_byte_length == len(source)
    assert converted.source_sha256 == sha256_of(source)


def test_transparency_of_the_source_is_preserved() -> None:
    """The rounded icon corners are real alpha in the client texture; they stay alpha."""
    image = decoded(convert_blp2(BLP_WITH_ALPHA, blp_bytes(BLP_WITH_ALPHA)).png)
    assert image.mode == "RGBA"
    alpha = image.getchannel("A")
    assert alpha.getextrema() == (0, 255)
    assert alpha.getpixel((0, 0)) == 0  # corner is transparent
    assert alpha.getpixel((32, 32)) == 255  # artwork is opaque


def test_a_texture_without_alpha_gets_no_invented_alpha() -> None:
    image = decoded(convert_blp2(BLP_WITHOUT_ALPHA, blp_bytes(BLP_WITHOUT_ALPHA)).png)
    assert image.mode == "RGB"


def test_conversion_is_deterministic() -> None:
    source = blp_bytes(BLP_WITH_ALPHA)
    assert convert_blp2(BLP_WITH_ALPHA, source).png == convert_blp2(BLP_WITH_ALPHA, source).png


def test_png_carries_no_metadata_chunks() -> None:
    png = convert_blp2(BLP_WITH_ALPHA, blp_bytes(BLP_WITH_ALPHA)).png
    chunks, offset = [], 8
    while offset < len(png):
        length = int.from_bytes(png[offset : offset + 4], "big")
        chunks.append(png[offset + 4 : offset + 8].decode("ascii"))
        offset += 12 + length
    assert set(chunks) == {"IHDR", "IDAT", "IEND"}


@pytest.mark.parametrize("file_data_id", [BLP_WITH_ALPHA, BLP_WITHOUT_ALPHA])
def test_committed_image_is_the_conversion_of_the_real_client_file(file_data_id: int) -> None:
    """Ties a committed asset to its client file: same recorded source hash, same pixels."""
    registry = read_icon_registry(CANONICAL_VISUALS / "icons.json")
    (asset,) = [icon for icon in registry.icons if icon.file_data_id == file_data_id]
    source = blp_bytes(file_data_id)
    assert asset.source_sha256 == sha256_of(source)
    assert asset.source_byte_length == len(source)
    committed = decoded((CANONICAL_VISUALS / asset.file).read_bytes())
    assert committed.tobytes() == decoded(source).tobytes()


@pytest.mark.parametrize(
    "payload",
    [
        b"",
        b"BLP1" + b"\0" * 200,
        b"\x89PNG\r\n\x1a\n" + b"\0" * 200,
        b'{"error":"File not found","0":400}',
        b"<html>Not Found</html>",
    ],
)
def test_non_blp2_payloads_are_rejected_before_decoding(payload: bytes) -> None:
    with pytest.raises(IconAssetError, match="not a BLP2 file"):
        convert_blp2(1, payload)


def test_truncated_blp2_is_rejected() -> None:
    with pytest.raises(IconAssetError, match="cannot decode BLP2 texture"):
        convert_blp2(BLP_WITH_ALPHA, blp_bytes(BLP_WITH_ALPHA)[:600])


@pytest.mark.parametrize(
    "payload", [b"", b"GIF89a" + b"\0" * 40, b"\xff\xd8\xff\xe0" + b"\0" * 40, b"BLP2" + b"\0" * 40]
)
def test_only_png_is_a_supported_bundle_format(payload: bytes) -> None:
    with pytest.raises(IconAssetError, match="not a PNG file"):
        read_png_size(payload)


def test_palette_png_is_not_a_supported_bundle_format() -> None:
    out = io.BytesIO()
    Image.new("P", (64, 64)).save(out, format="PNG")
    with pytest.raises(IconAssetError, match="unsupported PNG"):
        read_png_size(out.getvalue())


def test_registry_is_deterministic_and_ordered() -> None:
    icons = [convert_blp2(fid, blp_bytes(fid)) for fid in (BLP_WITH_ALPHA, BLP_WITHOUT_ALPHA)]
    first = build_icon_registry(
        game="forever", client_build=str(NEW_BUILD), icons=icons, source_name="wago.tools"
    )
    second = build_icon_registry(
        game="forever",
        client_build=str(NEW_BUILD),
        icons=list(reversed(icons)),
        source_name="wago.tools",
    )
    assert first.to_json() == second.to_json()
    assert [i.file_data_id for i in first.icons] == [BLP_WITHOUT_ALPHA, BLP_WITH_ALPHA]
    assert [i.file for i in first.icons] == ["icons/132090.png", "icons/132269.png"]
    assert first.content_hash == expected_content_hash(first)


def test_registry_refuses_duplicate_file_data_ids() -> None:
    icon = convert_blp2(BLP_WITH_ALPHA, blp_bytes(BLP_WITH_ALPHA))
    with pytest.raises(IconAssetError, match="duplicate FileDataID"):
        build_icon_registry(
            game="forever", client_build=str(NEW_BUILD), icons=[icon, icon], source_name="x"
        )


# --------------------------------------------------------------------------- registry <-> files


@pytest.fixture
def visuals_copy(tmp_path: Path) -> Path:
    target = tmp_path / "visuals"
    shutil.copytree(CANONICAL_VISUALS, target)
    return target


def registry_problems(directory: Path, used: set[int] | None = None) -> list[str]:
    registry = read_icon_registry(directory / "icons.json")
    return check_icon_registry(
        registry,
        directory,
        game="forever",
        client_build=str(NEW_BUILD),
        used_file_data_ids={i.file_data_id for i in registry.icons} if used is None else used,
    )


def test_untouched_copy_has_no_problems(visuals_copy: Path) -> None:
    assert registry_problems(visuals_copy) == []


def test_missing_file_is_detected(visuals_copy: Path) -> None:
    (visuals_copy / "icons" / "132269.png").unlink()
    assert registry_problems(visuals_copy) == ["icon 132269: icons/132269.png is missing"]


def test_zero_byte_file_is_detected(visuals_copy: Path) -> None:
    (visuals_copy / "icons" / "132269.png").write_bytes(b"")
    assert registry_problems(visuals_copy) == ["icon 132269: icons/132269.png is empty"]


def test_orphan_file_is_detected(visuals_copy: Path) -> None:
    shutil.copy(visuals_copy / "icons" / "132269.png", visuals_copy / "icons" / "424242.png")
    assert registry_problems(visuals_copy) == [
        "icons/424242.png is not in the icon registry (orphan file)"
    ]


def test_replaced_image_is_detected(visuals_copy: Path) -> None:
    shutil.copy(visuals_copy / "icons" / "132090.png", visuals_copy / "icons" / "132269.png")
    assert registry_problems(visuals_copy) == [
        "icon 132269: icons/132269.png does not match its recorded size/sha256"
    ]


def test_unsupported_format_behind_a_png_name_is_detected(visuals_copy: Path) -> None:
    (visuals_copy / "icons" / "132269.png").write_bytes(blp_bytes(BLP_WITH_ALPHA))
    problems = registry_problems(visuals_copy)
    assert "icon 132269: icons/132269.png: not a PNG file" in problems


def test_registry_must_match_the_icons_the_talents_use(visuals_copy: Path) -> None:
    registry = read_icon_registry(visuals_copy / "icons.json")
    used = {i.file_data_id for i in registry.icons}
    assert registry_problems(visuals_copy, (used - {132269}) | {7}) == [
        "icon 7 is used by a talent but not in the registry",
        "icon 132269 is in the registry but no talent uses it",
    ]


def test_registry_of_another_build_is_refused(visuals_copy: Path) -> None:
    registry = read_icon_registry(visuals_copy / "icons.json")
    problems = check_icon_registry(
        registry,
        visuals_copy,
        game="forever",
        client_build="1.60.1.69977",
        used_file_data_ids={i.file_data_id for i in registry.icons},
    )
    assert problems == ["icon registry clientBuild '1.60.1.70009' != '1.60.1.69977'"]


def test_duplicate_registry_keys_are_detected(visuals_copy: Path) -> None:
    registry = read_icon_registry(visuals_copy / "icons.json")
    doubled = registry.model_copy(update={"icons": [registry.icons[0], *registry.icons]})
    problems = check_icon_registry(
        doubled,
        visuals_copy,
        game="forever",
        client_build=str(NEW_BUILD),
        used_file_data_ids={i.file_data_id for i in registry.icons},
    )
    assert "icon registry has duplicate fileDataId keys" in problems
    assert "icon registry maps several FileDataIDs to one file" in problems
