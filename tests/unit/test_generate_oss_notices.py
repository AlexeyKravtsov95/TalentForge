"""The OSS-notices generator, offline (M5.RC.2b LR-4, TF-RC2B-IOS-NOTICES).

``scripts/generate_oss_notices.py`` derives the Android and iOS notices from Gradle and
from the static archives inside the iOS klibs. These tests pin, without Gradle or the
network: the Mach-O universal / BSD ``ar`` parsing, the klib scan, every fail-closed
rule of the native inventory, the bundled licence texts, and that the committed
documents are exactly what the generator builds from their own module lists. That the
module lists equal what Gradle resolves is ``--check``'s job (it needs Gradle).
"""

from __future__ import annotations

import importlib.util
import json
import struct
import sys
import zipfile
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[2]


def load_generator() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "generate_oss_notices", ROOT / "scripts" / "generate_oss_notices.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


gen = load_generator()
SKIKO = "org.jetbrains.skiko:skiko-iosarm64:0.9.37.4"
STDLIB = "org.jetbrains.kotlin:kotlin-stdlib:2.4.20"
UIKIT = "org.jetbrains.compose.ui:ui-uikit-uikitarm64:1.10.3"


def ar(*names: str) -> bytes:
    """A BSD ar archive: a symbol table, then the members (long names use ``#1/``)."""
    out = bytearray(b"!<arch>\n")
    for name in ("__.SYMDEF SORTED", *names):
        body = b"\0" * 3
        if len(name) > 16 or " " in name:
            encoded = name.encode() + b"\0" * (4 - len(name) % 4)
            header_name, body = f"#1/{len(encoded)}", encoded + body
        else:
            header_name = name
        header = f"{header_name:<16}{0:<12}{0:<6}{0:<6}{644:<8}{len(body):<10}`\n"
        out += header.encode() + body
        if len(body) % 2:
            out += b"\n"
    return bytes(out)


def fat(slices: list[tuple[int, int, bytes]]) -> bytes:
    """A Mach-O universal file of (cputype, cpusubtype, payload) slices."""
    header = struct.pack(">II", 0xCAFEBABE, len(slices))
    offset = 8 + 20 * len(slices)
    table, payload = b"", b""
    for cpu, subtype, data in slices:
        table += struct.pack(">5I", cpu, subtype, offset + len(payload), len(data), 0)
        payload += data
    return header + table + payload


def klib(tmp_path: Path, entries: dict[str, bytes], name: str = "x.klib") -> Path:
    path = tmp_path / name
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("default/manifest", "unique_name=x\n")
        for entry, data in entries.items():
            archive.writestr(entry, data)
    return path


def skiko_content() -> dict[str, set[str]]:
    """The archive content the established skiko 0.9.37.4 inventory describes."""
    inventory = gen.NATIVE_INVENTORIES[SKIKO]
    content: dict[str, set[str]] = {name: {"Canvas"} for name in inventory.own_archives}
    content["libskia.a"] = {f for c in inventory.components for f in c.families}
    return content


# --- archives ------------------------------------------------------------------------------


def test_bsd_archive_members_with_long_names_skip_the_symbol_table() -> None:
    names = ["libskia.SkCanvas.o", "libzlib.adler32.o", "a.o"]
    assert gen.archive_members(ar(*names)) == names


def test_the_arm64_slice_of_a_universal_archive_is_read_not_arm64e() -> None:
    arm64e = (0x0100000C, 0x80000002, ar("libicu.e.o"))
    arm64 = (0x0100000C, 0, ar("libicu.ucol.o", "libicu.ubrk.o"))
    assert gen.archive_members(fat([arm64e, arm64])) == ["libicu.ucol.o", "libicu.ubrk.o"]
    assert gen.families(gen.archive_members(fat([arm64]))) == {"libicu"}


@pytest.mark.parametrize(
    "data",
    [
        b"not an archive",
        fat([(0x01000007, 3, ar("x.o"))]),  # x86_64 only
        b"!<arch>\ntruncated header",
    ],
)
def test_unreadable_archives_fail(data: bytes) -> None:
    with pytest.raises(gen.NoticesError):
        gen.archive_members(data)


def test_a_klib_scan_reads_included_archives_and_allows_only_cinterop_stubs(
    tmp_path: Path,
) -> None:
    good = klib(
        tmp_path,
        {
            "default/targets/ios_arm64/included/libpng.a": ar("libpng.png.o"),
            "default/targets/ios_arm64/native/cstubs.bc": b"BC",
            "default/targets/ios_x64/included/ignored.a": b"another target",
        },
    )
    assert gen.klib_native_content(good) == {"libpng.a": {"libpng"}}
    bad = klib(tmp_path, {"default/targets/ios_arm64/native/runtime.bc": b"BC"}, "bad.klib")
    with pytest.raises(gen.NoticesError, match="unexpected native entry"):
        gen.klib_native_content(bad)


# --- native inventory ----------------------------------------------------------------------


def test_the_established_native_content_is_fully_claimed() -> None:
    gen.verify_native(SKIKO, skiko_content())
    gen.verify_native(STDLIB, {})
    gen.verify_native(UIKIT, {"libCMPUIKitUtils.a": {"CMPViewController"}})
    gen.verify_native("androidx.collection:collection-iosarm64:1.5.0", {})


def test_an_unknown_object_family_fails() -> None:
    content = skiko_content()
    content["libskia.a"] |= {"libfreetype"}
    with pytest.raises(gen.NoticesError, match=r"unclaimed object families \['libfreetype'\]"):
        gen.verify_native(SKIKO, content)


def test_a_family_or_own_archive_the_klib_no_longer_has_fails() -> None:
    content = skiko_content()
    content["libskia.a"] -= {"libpiex"}
    with pytest.raises(gen.NoticesError, match=r"absent object families \['libpiex'\]"):
        gen.verify_native(SKIKO, content)
    content = skiko_content()
    del content["skiko-native-bridges-ios-arm64.a"]
    with pytest.raises(gen.NoticesError, match="absent archives"):
        gen.verify_native(SKIKO, content)


def test_a_new_skiko_or_stdlib_version_needs_its_own_inventory() -> None:
    with pytest.raises(gen.NoticesError, match="no established inventory"):
        gen.verify_native("org.jetbrains.skiko:skiko-iosarm64:0.9.38", skiko_content())
    with pytest.raises(gen.NoticesError, match="no established inventory"):
        gen.verify_native("org.jetbrains.kotlin:kotlin-stdlib:2.5.0", {})


def test_any_other_module_shipping_an_archive_fails() -> None:
    with pytest.raises(gen.NoticesError, match="no established inventory"):
        gen.verify_native("org.example:native-iosarm64:1.0", {"libfoo.a": {"libfoo"}})


def test_no_family_is_claimed_by_two_components() -> None:
    for inventory in gen.NATIVE_INVENTORIES.values():
        families = [f for c in inventory.components for f in c.families]
        assert len(families) == len(set(families))


# --- licences ------------------------------------------------------------------------------


def test_every_bundled_text_is_the_pinned_upstream_file() -> None:
    for license_id in gen.LICENSES:
        gen.verify_text(license_id, fetch=False)


def test_a_changed_or_missing_text_fails(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    legal = tmp_path / "files/legal"
    legal.mkdir(parents=True)
    (legal / "LICENSE-Skia.txt").write_text("edited\n", encoding="utf-8")
    monkeypatch.setattr(gen, "RESOURCES", tmp_path)
    with pytest.raises(gen.NoticesError, match="SHA-256"):
        gen.verify_text("Skia-BSD-3-Clause", fetch=False)
    with pytest.raises(gen.NoticesError, match="missing"):
        gen.verify_text("ICU", fetch=False)


def test_every_component_names_known_licences() -> None:
    linked = [c for i in gen.NATIVE_INVENTORIES.values() for c in i.components]
    for component in (*gen.COMPONENTS, *linked):
        assert component.licenses
        assert set(component.licenses) <= gen.LICENSES.keys()


# --- documents -----------------------------------------------------------------------------


def test_gradle_output_is_parsed_without_boms() -> None:
    stdout = "\n".join(
        [
            "noise",
            f"TF-MODULE {SKIKO}",
            "TF-MODULE androidx.compose:compose-bom:2026.01.00",
            f"TF-FILE {SKIKO} /cache/skiko.klib",
            f"TF-FILE {SKIKO} /cache/skiko-cinterop-uikit.klib",
        ]
    )
    resolution = gen.parse_resolution(stdout, "iosArm64CompileKlibraries")
    assert resolution.modules == [SKIKO]
    assert resolution.files[SKIKO] == [
        Path("/cache/skiko.klib"),
        Path("/cache/skiko-cinterop-uikit.klib"),
    ]
    with pytest.raises(gen.NoticesError):
        gen.parse_resolution("nothing", "iosArm64CompileKlibraries")


def committed(platform: str) -> tuple[Any, str, list[str]]:
    text = gen.PLATFORMS[platform].notices.read_text(encoding="utf-8")
    return gen.PLATFORMS[platform], text, gen.listed_modules(json.loads(text))


def test_the_committed_android_notices_are_what_the_generator_builds() -> None:
    platform, text, modules = committed("android")
    assert len(modules) == 122
    assert gen.render(gen.build_notices(platform, modules, None, online=False)) == text


def test_the_committed_ios_notices_are_what_the_generator_builds() -> None:
    platform, text, modules = committed("ios")
    native = {SKIKO: skiko_content(), UIKIT: {"libCMPUIKitUtils.a": {"CMPViewController"}}}
    built = gen.build_notices(platform, modules, native, online=False)
    assert gen.render(built) == text
    linked = {c["name"]: c for c in built["components"] if "linkedInto" in c}
    assert linked["Skia"]["linkedInto"] == SKIKO
    assert linked["Adobe DNG SDK"]["attribution"] == (
        "This product includes DNG technology under license by Adobe Systems Incorporated."
    )


def test_check_reports_the_module_drift(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    platform, text, modules = committed("android")
    copy = tmp_path / "oss-notices-android.json"
    copy.write_text(text, encoding="utf-8")
    monkeypatch.setattr(gen, "ROOT", tmp_path)
    moved = gen.Platform(platform.name, platform.configuration, copy, platform.native)
    gen.check(moved, gen.build_notices(moved, modules, None, online=False))
    drifted = gen.build_notices(moved, [*modules, "androidx.core:core:1.0.0"], None, online=False)
    with pytest.raises(gen.NoticesError, match=r"not listed: \['androidx.core:core:1.0.0'\]"):
        gen.check(moved, drifted)
