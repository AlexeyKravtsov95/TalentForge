"""The OSS-notices generator and its drift check, offline (M5.RC.2b LR-4 and review,
TF-RC2B-IOS-NOTICES).

``scripts/generate_oss_notices.py`` derives the Android and iOS notices from Gradle and
from the static archives inside the iOS klibs. These tests pin, without Gradle or the
network (resolution and POM lookups are stubbed): the Mach-O universal / BSD ``ar``
parsing, the klib scan, every fail-closed rule of the native inventory, the bundled
licence texts, that the committed documents are exactly what the generator builds from
their own module lists, and that ``--check`` fails on ANY difference — not only changed
coordinates: a licence that changed upstream while the coordinates stayed the same, an
edited licence id or name, a changed URL or attribution, or a module moved to another
component. The real resolution runs in mobile-ci.
"""

from __future__ import annotations

import copy
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
    assert gen.render(gen.build_notices(platform, modules, None, verify_poms=False)) == text


def test_the_committed_ios_notices_are_what_the_generator_builds() -> None:
    platform, text, modules = committed("ios")
    native = {SKIKO: skiko_content(), UIKIT: {"libCMPUIKitUtils.a": {"CMPViewController"}}}
    built = gen.build_notices(platform, modules, native, verify_poms=False)
    assert gen.render(built) == text
    linked = {c["name"]: c for c in built["components"] if "linkedInto" in c}
    assert linked["Skia"]["linkedInto"] == SKIKO
    assert linked["Adobe DNG SDK"]["attribution"] == (
        "This product includes DNG technology under license by Adobe Systems Incorporated."
    )


# --- embedded third-party code (M5.LEGAL.OSS.1) ---------------------------------------------


def test_the_android_notices_carry_the_stdlibs_threeten_licence() -> None:
    """The Android kotlin-stdlib jar embeds ThreeTen-derived kotlin.time: its BSD-3-Clause
    text is listed on the component (with the POM's Apache-2.0 first), all 122 modules are
    still listed, and the iOS document keeps attributing the code through its inventory."""
    _, android_text, android_modules = committed("android")
    assert len(android_modules) == 122
    document = json.loads(android_text)
    stdlib = next(c for c in document["components"] if c["name"] == "Kotlin Standard Library")
    assert stdlib["modules"] == [STDLIB]
    assert stdlib["licenses"] == ["Apache-2.0", "ThreeTen-BSD-3-Clause"]
    assert document["licenses"]["ThreeTen-BSD-3-Clause"] == {
        "name": "BSD 3-Clause License (ThreeTen)",
        "file": "files/legal/LICENSE-ThreeTen.txt",
    }
    _, ios_text, _ = committed("ios")
    ios_stdlib = next(
        c for c in json.loads(ios_text)["components"] if c["name"] == "Kotlin Standard Library"
    )
    assert ios_stdlib["licenses"] == ["Apache-2.0"]


def test_embedded_licences_are_shown_only_where_no_native_inventory_attributes_them() -> None:
    android = json.loads(
        gen.render(
            gen.build_notices(gen.PLATFORMS["android"], list(MODULES), None, verify_poms=False)
        )
    )
    android_stdlib = next(
        c for c in android["components"] if c["name"] == "Kotlin Standard Library"
    )
    assert android_stdlib["licenses"] == ["Apache-2.0", "ThreeTen-BSD-3-Clause"]
    ios = json.loads(
        gen.render(gen.build_notices(gen.PLATFORMS["ios"], list(MODULES), {}, verify_poms=False))
    )
    ios_stdlib = next(c for c in ios["components"] if c["name"] == "Kotlin Standard Library")
    assert ios_stdlib["licenses"] == ["Apache-2.0"]
    linked = next(c for c in ios["components"] if c.get("linkedInto") == STDLIB)
    assert linked["licenses"] == ["ThreeTen-BSD-3-Clause", "Apache-2.0"]


def test_a_stdlib_version_without_established_embedded_licences_fails() -> None:
    modules = [m if m != STDLIB else "org.jetbrains.kotlin:kotlin-stdlib:2.5.0" for m in MODULES]
    pattern = r"kotlin-stdlib:2\.5\.0.*not established"
    with pytest.raises(gen.NoticesError, match=pattern):
        gen.build_notices(gen.PLATFORMS["android"], modules, None, verify_poms=False)
    with pytest.raises(gen.NoticesError, match=pattern):
        gen.build_notices(gen.PLATFORMS["ios"], modules, {}, verify_poms=False)


def test_an_inventory_that_does_not_attribute_the_embedded_code_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        gen,
        "EMBEDDED_LICENSES",
        {STDLIB: ("ThreeTen-BSD-3-Clause", "IJG")},
    )
    with pytest.raises(gen.NoticesError, match=r"requires \['IJG'\].*no linked component"):
        gen.build_notices(gen.PLATFORMS["ios"], list(MODULES), {}, verify_poms=False)


def test_check_reports_the_module_drift(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    platform, text, modules = committed("android")
    target = tmp_path / "oss-notices-android.json"
    target.write_text(text, encoding="utf-8")
    monkeypatch.setattr(gen, "ROOT", tmp_path)
    moved = gen.Platform(platform.name, platform.configuration, target, platform.native)
    gen.check(moved, gen.build_notices(moved, modules, None, verify_poms=False))
    drifted = gen.build_notices(
        moved, [*modules, "androidx.core:core:1.0.0"], None, verify_poms=False
    )
    with pytest.raises(
        gen.NoticesError, match=r"modules: not listed \['androidx.core:core:1.0.0'\]"
    ):
        gen.check(moved, drifted)


# --- --check fails on any difference (PR #38 review, ported to v2 and both platforms) -------

MODULES = [
    "androidx.activity:activity:1.13.0",
    "com.google.guava:listenablefuture:1.0",
    "org.jetbrains.kotlin:kotlin-stdlib:2.4.20",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0",
]
APACHE = ["The Apache Software License, Version 2.0"]


@pytest.fixture
def notices_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Gradle resolves MODULES; every POM declares Apache-2.0; the notices live in tmp_path."""
    path = tmp_path / "files" / "legal" / "oss-notices-android.json"
    path.parent.mkdir(parents=True)
    android = gen.PLATFORMS["android"]
    monkeypatch.setattr(gen, "ROOT", tmp_path)
    monkeypatch.setitem(
        gen.PLATFORMS,
        "android",
        gen.Platform(android.name, android.configuration, path, android.native),
    )
    monkeypatch.setattr(gen, "resolve", lambda platform: gen.Resolution(list(MODULES), {}))
    monkeypatch.setattr(gen, "declared_licenses", lambda group, artifact, version: list(APACHE))
    return path


def run(*args: str) -> int:
    return int(gen.main([*args, "--platform", "android"]))


def edit(path: Path, change: Any) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    change(data)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def component(data: dict[str, Any], name: str) -> dict[str, Any]:
    return next(c for c in data["components"] if c["name"] == name)


def test_generated_notices_pass_the_check(notices_file: Path) -> None:
    assert run() == 0
    assert run("--check") == 0


def test_an_upstream_licence_change_with_unchanged_coordinates_fails(
    notices_file: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    assert run() == 0
    # the same coordinates, but the kotlinx.coroutines POM now declares another licence
    monkeypatch.setattr(
        gen,
        "declared_licenses",
        lambda group, artifact, version: ["MIT"] if artifact.startswith("kotlinx-") else APACHE,
    )
    assert run("--check") == 1
    error = capsys.readouterr().err
    assert "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0" in error
    assert "MIT" in error


def test_an_edited_component_licence_with_unchanged_coordinates_fails(
    notices_file: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    assert run() == 0

    def relicense(data: dict[str, Any]) -> None:
        data["licenses"]["MIT"] = {"name": "MIT License", "file": "files/legal/LICENSE-MIT.txt"}
        component(data, "kotlinx.coroutines")["licenses"] = ["MIT"]

    edit(notices_file, relicense)
    assert run("--check") == 1
    error = capsys.readouterr().err
    assert (
        "notices.components[2].licenses: not listed ['Apache-2.0'], not expected ['MIT']" in error
    )
    assert "notices.licenses.MIT: not expected" in error


@pytest.mark.parametrize(
    ("change", "path"),
    [
        (
            lambda d: d["licenses"]["Apache-2.0"].update(name="Apache License 2.1"),
            "notices.licenses.Apache-2.0.name",
        ),
        (
            lambda d: d["licenses"]["Apache-2.0"].update(file="files/legal/OTHER.txt"),
            "notices.licenses.Apache-2.0.file",
        ),
        (
            lambda d: component(d, "Guava ListenableFuture").update(url="https://example.org"),
            "notices.components[3].url",
        ),
        (lambda d: d.update(schema="talentforge.oss-notices.v1"), "notices.schema"),
        (lambda d: d.update(platform="ios"), "notices.platform"),
        (lambda d: d.pop("source"), "notices.source: missing"),
    ],
)
def test_any_licence_or_component_metadata_edit_fails(
    notices_file: Path, capsys: pytest.CaptureFixture[str], change: Any, path: str
) -> None:
    assert run() == 0
    edit(notices_file, change)
    assert run("--check") == 1
    assert path in capsys.readouterr().err


def test_a_module_moved_to_another_component_fails(
    notices_file: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    assert run() == 0

    def move(data: dict[str, Any]) -> None:
        stdlib = "org.jetbrains.kotlin:kotlin-stdlib:2.4.20"
        component(data, "Kotlin Standard Library")["modules"].remove(stdlib)
        component(data, "AndroidX (Android Jetpack)")["modules"].append(stdlib)

    edit(notices_file, move)
    assert run("--check") == 1
    error = capsys.readouterr().err
    assert "notices.components[0].modules: not listed [], not expected" in error


def test_coordinate_drift_still_fails(
    notices_file: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    assert run() == 0
    monkeypatch.setattr(
        gen, "resolve", lambda platform: gen.Resolution([*MODULES, "androidx.core:core:1.18.0"], {})
    )
    assert run("--check") == 1
    assert "androidx.core:core:1.18.0" in capsys.readouterr().err


def test_a_missing_or_malformed_file_fails(notices_file: Path) -> None:
    assert run("--check") == 1
    notices_file.write_text("{not json", encoding="utf-8")
    assert run("--check") == 1


def test_a_module_no_component_claims_fails(
    notices_file: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        gen, "resolve", lambda platform: gen.Resolution([*MODULES, "com.example:unknown:1.0"], {})
    )
    assert run() == 1


def test_an_edited_linked_component_in_the_ios_notices_fails() -> None:
    platform, text, modules = committed("ios")
    native = {SKIKO: skiko_content(), UIKIT: {"libCMPUIKitUtils.a": {"CMPViewController"}}}
    expected = gen.build_notices(platform, modules, native, verify_poms=False)
    edited = copy.deepcopy(json.loads(text))
    dng = next(c for c in edited["components"] if c["name"] == "Adobe DNG SDK")
    dng["attribution"] = None
    dng["version"] = "1.5"
    found = gen.differences(expected, edited)
    assert any(".attribution: committed None" in line for line in found)
    assert any(".version: committed '1.5', expected '1.4'" in line for line in found)
    assert gen.differences(expected, json.loads(text)) == []
