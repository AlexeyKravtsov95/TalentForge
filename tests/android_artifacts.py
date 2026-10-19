"""Synthetic Android artifact parts for the release validators' tests (M5.PLAY.1).

``elf`` writes a minimal ELF header with program headers, enough for the validators'
``PT_LOAD`` alignment reader; ``proto_*`` encode the few protobuf messages of an app
bundle (aapt2 ``XmlNode`` manifests, bundletool ``BundleConfig``) that the bundle
validator decodes. Real artifacts are covered by the committed fixtures and by the
signing self-tests.
"""

from __future__ import annotations

PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
PF_R, PF_W, PF_X = 4, 2, 1

# (p_type, p_flags, p_vaddr, p_memsz, p_align)
Segment = tuple[int, int, int, int, int]


def elf_with_segments(segments: list[Segment], *, wide: bool = True) -> bytes:
    """A little-endian ELF file with exactly these program headers."""
    header_size, entry_size = (64, 56) if wide else (52, 32)
    header = bytearray(header_size)
    header[:4] = b"\x7fELF"
    header[4] = 2 if wide else 1  # EI_CLASS
    header[5] = 1  # EI_DATA: little-endian
    header[6] = 1  # EI_VERSION
    if wide:
        header[32:40] = header_size.to_bytes(8, "little")  # e_phoff
        header[54:56] = entry_size.to_bytes(2, "little")
        header[56:58] = len(segments).to_bytes(2, "little")
    else:
        header[28:32] = header_size.to_bytes(4, "little")
        header[42:44] = entry_size.to_bytes(2, "little")
        header[44:46] = len(segments).to_bytes(2, "little")
    entries = bytearray()
    for kind, flags, vaddr, memsz, align in segments:
        entry = bytearray(entry_size)
        entry[0:4] = kind.to_bytes(4, "little")
        if wide:
            entry[4:8] = flags.to_bytes(4, "little")
            entry[16:24] = vaddr.to_bytes(8, "little")
            entry[40:48] = memsz.to_bytes(8, "little")
            entry[48:56] = align.to_bytes(8, "little")
        else:
            entry[8:12] = vaddr.to_bytes(4, "little")
            entry[20:24] = memsz.to_bytes(4, "little")
            entry[24:28] = flags.to_bytes(4, "little")
            entry[28:32] = align.to_bytes(4, "little")
        entries += entry
    return bytes(header + entries)


def elf(*, wide: bool = True, alignments: tuple[int, ...] = (16384, 16384)) -> bytes:
    """An ELF file whose program headers are read-only ``PT_LOAD`` with these alignments."""
    return elf_with_segments(
        [(PT_LOAD, PF_R, index * 0x10000, 0x100, align) for index, align in enumerate(alignments)],
        wide=wide,
    )


def _varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def proto_field(number: int, value: bytes | str | int) -> bytes:
    """One protobuf field: an int is a varint, bytes/str are length-delimited."""
    if isinstance(value, int):
        return _varint(number << 3) + _varint(value)
    raw = value.encode() if isinstance(value, str) else value
    return _varint(number << 3 | 2) + _varint(len(raw)) + raw


ANDROID_NS = "http://schemas.android.com/apk/res/android"


def proto_attribute(name: str, value: str, *, android: bool = True) -> bytes:
    """aapt2 ``XmlAttribute``: namespace_uri = 1, name = 2, value = 3."""
    namespace = proto_field(1, ANDROID_NS) if android else b""
    return namespace + proto_field(2, name) + proto_field(3, value)


def proto_element(name: str, attributes: list[bytes], children: list[bytes]) -> bytes:
    """aapt2 ``XmlNode`` holding an ``XmlElement`` (name = 3, attribute = 4, child = 5)."""
    element = proto_field(3, name)
    element += b"".join(proto_field(4, attribute) for attribute in attributes)
    element += b"".join(proto_field(5, child) for child in children)
    return proto_field(1, element)


def proto_manifest(
    *,
    package: str = "io.github.alexeykravtsov95.talentforge",
    version_code: str = "1",
    version_name: str = "0.1.0",
    min_sdk: str = "26",
    target_sdk: str = "36",
    permissions: tuple[str, ...] = (
        "io.github.alexeykravtsov95.talentforge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    ),
    application_attributes: tuple[tuple[str, str], ...] = (),
) -> bytes:
    """A bundle manifest (``base/manifest/AndroidManifest.xml``) in aapt2's proto format."""
    sdk = [
        proto_attribute("minSdkVersion", min_sdk),
        proto_attribute("targetSdkVersion", target_sdk),
    ]
    children = [
        proto_element("uses-sdk", sdk, []),
        *(
            proto_element("uses-permission", [proto_attribute("name", name)], [])
            for name in permissions
        ),
        proto_element(
            "application",
            [proto_attribute(name, value) for name, value in application_attributes],
            [],
        ),
    ]
    attributes = [
        proto_attribute("versionCode", version_code),
        proto_attribute("versionName", version_name),
        proto_attribute("package", package, android=False),
    ]
    return proto_element("manifest", attributes, children)


def proto_bundle_config(*, uncompress_native: bool = True, alignment: int = 2) -> bytes:
    """bundletool ``BundleConfig``: optimizations (2) → uncompress_native_libraries (2)."""
    native = proto_field(1, int(uncompress_native)) + proto_field(2, alignment)
    return proto_field(2, proto_field(2, native))
