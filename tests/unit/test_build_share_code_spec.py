"""Independent reference of the `talentforge-share.v1` wire format (docs/build-sharing.md).

The Kotlin codec (mobile/shared, `BuildShareCode`) pins the same golden vectors in
`BuildShareCodeTest`. This module re-implements the specification from the
document alone — `zlib.crc32` for the checksum, a hand-written Crockford Base32 —
so the vectors are not produced by the code they test. It also checks that the
examples printed in docs/build-sharing.md are real, decodable codes of builds that
exist in the canonical dataset of their own client build.
"""

from __future__ import annotations

import json
import re
import zlib
from dataclasses import dataclass
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
DOC = REPO / "docs" / "build-sharing.md"

PREFIX = "TF1"
ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


@dataclass(frozen=True)
class Build:
    game: str
    client_build: str
    class_token: str
    tree_id: int
    allocations: tuple[tuple[int, int, int], ...]  # (nodeId, entryId, rank)
    schema_version: int = 1


def _varint(value: int) -> bytes:
    assert value >= 0
    out = bytearray()
    while True:
        low = value & 0x7F
        value >>= 7
        if value == 0:
            out.append(low)
            return bytes(out)
        out.append(low | 0x80)


def _zigzag(value: int) -> int:
    return value * 2 if value >= 0 else -value * 2 - 1


def _unzigzag(value: int) -> int:
    return value // 2 if value % 2 == 0 else -(value + 1) // 2


def _ascii(value: str) -> bytes:
    raw = value.encode("ascii")
    return _varint(len(raw)) + raw


def _base32(data: bytes) -> str:
    bits = "".join(f"{byte:08b}" for byte in data)
    bits += "0" * (-len(bits) % 5)
    return "".join(ALPHABET[int(bits[i : i + 5], 2)] for i in range(0, len(bits), 5))


def _unbase32(text: str) -> bytes:
    bits = "".join(f"{ALPHABET.index(char):05b}" for char in text)
    whole, padding = divmod(len(bits), 8)
    assert padding < 5, "impossible length"
    assert set(bits[whole * 8 :]) <= {"0"}, "non-zero padding bits"
    return bytes(int(bits[i : i + 8], 2) for i in range(0, whole * 8, 8))


def encode(build: Build) -> str:
    payload = bytearray()
    payload += _varint(build.schema_version)
    payload += _ascii(build.game)
    payload += _ascii(build.client_build)
    payload += _ascii(build.class_token)
    payload += _varint(build.tree_id)
    ordered = sorted(build.allocations)
    payload += _varint(len(ordered))
    previous_node = previous_offset = 0
    for node_id, entry_id, rank in ordered:
        offset = entry_id - node_id
        inline = rank if rank <= 7 else 0
        payload += _varint(node_id - previous_node)
        payload += _varint((_zigzag(offset - previous_offset) << 3) | inline)
        if inline == 0:
            payload += _varint(rank - 8)
        previous_node, previous_offset = node_id, offset
    crc = zlib.crc32(PREFIX.encode("ascii") + bytes(payload))
    return PREFIX + _base32(bytes(payload) + crc.to_bytes(4, "big"))


class _Reader:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.pos = 0

    def varint(self) -> int:
        result = shift = 0
        while True:
            byte = self.data[self.pos]
            self.pos += 1
            result |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return result
            shift += 7

    def ascii(self) -> str:
        length = self.varint()
        value = self.data[self.pos : self.pos + length].decode("ascii")
        self.pos += length
        return value


def decode(code: str) -> Build:
    assert code.startswith(PREFIX)
    body = _unbase32(code[len(PREFIX) :])
    payload, stored = body[:-4], int.from_bytes(body[-4:], "big")
    assert zlib.crc32(PREFIX.encode("ascii") + payload) == stored, "checksum mismatch"
    reader = _Reader(payload)
    schema_version = reader.varint()
    game, client_build, class_token = reader.ascii(), reader.ascii(), reader.ascii()
    tree_id = reader.varint()
    allocations = []
    previous_node = previous_offset = 0
    for _ in range(reader.varint()):
        node_id = previous_node + reader.varint()
        head = reader.varint()
        offset = previous_offset + _unzigzag(head >> 3)
        rank = head & 7 or 8 + reader.varint()
        allocations.append((node_id, node_id + offset, rank))
        previous_node, previous_offset = node_id, offset
    assert reader.pos == len(payload), "trailing data"
    return Build(game, client_build, class_token, tree_id, tuple(allocations), schema_version)


# ---------------------------------------------------------------------- golden vectors
# The same builds and codes as BuildShareCodeTest (Kotlin). A change here is a
# wire-format change and needs a new format version (docs/build-sharing.md).

EMPTY = Build("forever", "1.60.1.70374", "WARRIOR", 1117, ())
WARRIOR_PARTIAL = Build(
    "forever",
    "1.60.1.70009",
    "WARRIOR",
    1117,
    ((105954, 130684, 3), (105956, 130686, 2), (105957, 130687, 2), (105958, 130688, 3)),
)
RANK_ESCAPE = Build(
    "forever",
    "1.60.1.70338",
    "MAGE",
    1112,
    ((105762, 130491, 5), (105800, 105799, 200)),
)

GOLDEN = {
    EMPTY: "TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1K6WT0ENT1A994JKTJVM400ZP1AGF0",
    WARRIOR_PARTIAL: (
        "TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1G60WGENT1A994JKTJVM409RNV0THS6602080G4083SRW7T38"
    ),
    RANK_ESCAPE: "TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1K6CW08KA18X2XG202MAX0D5CK30K9H4RRR00XR58858",
}


@pytest.mark.parametrize(("build", "code"), list(GOLDEN.items()))
def test_golden_vectors(build: Build, code: str) -> None:
    assert encode(build) == code
    assert decode(code) == build


def test_checksum_covers_the_prefix() -> None:
    code = GOLDEN[WARRIOR_PARTIAL]
    body = _unbase32(code[3:])
    assert zlib.crc32(b"TF1" + body[:-4]) == int.from_bytes(body[-4:], "big")
    assert zlib.crc32(body[:-4]) != int.from_bytes(body[-4:], "big")


# ---------------------------------------------------------------------- documentation examples

_DOC_CODE = re.compile(r"\bTF1[0-9A-HJKMNP-TV-Z]{8,}\b")


def _doc_codes() -> list[str]:
    return sorted(set(_DOC_CODE.findall(DOC.read_text(encoding="utf-8"))))


def test_the_document_prints_codes() -> None:
    assert len(_doc_codes()) >= 3


@pytest.mark.parametrize("code", _doc_codes())
def test_document_examples_decode_to_builds_of_their_own_dataset(code: str) -> None:
    build = decode(code)
    assert encode(build) == code, "documented codes are canonical"
    dataset = REPO / "data" / "normalized" / build.game / build.client_build
    document = json.loads((dataset / f"{build.class_token.lower()}.json").read_text("utf-8"))
    assert document["clientBuild"] == build.client_build
    assert document["class"]["token"] == build.class_token
    (tree,) = (tree for tree in document["trees"] if tree["id"] == build.tree_id)
    nodes = {node["id"]: node for node in tree["nodes"]}
    for node_id, entry_id, rank in build.allocations:
        entries = {entry["id"]: entry for entry in nodes[node_id]["entries"]}
        assert 1 <= rank <= entries[entry_id]["maxRank"]
