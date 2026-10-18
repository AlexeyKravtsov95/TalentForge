"""Differential vectors for the Kotlin strict-JSON / duplicate-name scanner (M5.OTA.3).

The OTA client must reject exactly what the reference's ``talent_data.ota.verify.strict_json``
rejects before any model sees a document (``docs/ota/ota-contract-v1.md`` §2, §2.1): a byte
order mark, malformed UTF-8, any JSON grammar violation, ``NaN``/``Infinity`` and repeated
member names at any depth. The Kotlin client implements that as a byte-level scanner
(``mobile/shared/.../data/ota/JsonDuplicateNames.kt``); its ``JsonDuplicateNamesTest`` reads
these committed vectors and must reach the same verdict for every document.

The documents are generated deterministically (fixed seed) from hand-written edge cases and
random mutations of well-formed documents; each verdict is the reference's own.

Regenerate after a deliberate change: ``python tests/unit/test_ota_strict_json_vectors.py``.
"""

from __future__ import annotations

import base64
import json
import random
from pathlib import Path
from typing import Any

from talent_data.ota.verify import strict_json

ROOT = Path(__file__).resolve().parents[2]
VECTORS = ROOT / "tests" / "fixtures" / "ota_client" / "strict-json-vectors.json"
SEED = 20261018
RANDOM_DOCUMENTS = 400

# Hand-written cases: every rule of §2.1 and of RFC 8259 the scanner implements.
EDGE_CASES: list[tuple[str, bytes]] = [
    ("empty document", b""),
    ("whitespace only", b" \n\t\r "),
    ("empty object", b"{}"),
    ("empty array", b"[]"),
    ("top-level string", b'"x"'),
    ("top-level number", b"-12.5e+3"),
    ("top-level literals", b"[true,false,null]"),
    ("surrounding whitespace", b" \r\n\t{ \"a\" : 1 } \n"),
    ("duplicate top level, different values", b'{"a":1,"a":2}'),
    ("duplicate top level, equal values", b'{"a":1,"a":1}'),
    ("duplicate in reverse order", b'{"b":1,"a":2,"b":3}'),
    ("duplicate nested object", b'{"x":{"a":1,"a":1}}'),
    ("duplicate inside an array element", b'{"x":[{"k":1},{"k":2,"k":3}]}'),
    ("same name in sibling objects", b'{"x":{"a":1},"y":{"a":1}}'),
    ("same name at two depths", b'{"a":{"a":{"a":1}}}'),
    ("duplicate after unicode escape", b'{"activeReleaseId":1,"activeRelease\\u0049d":2}'),
    ("duplicate after escaped solidus", b'{"a/b":1,"a\\/b":2}'),
    ("duplicate after short escapes", b'{"a\\nb":1,"a\\u000ab":2}'),
    ("duplicate raw vs escaped non-ASCII", '{"\N{LATIN SMALL LETTER E WITH ACUTE}":1,"\\u00e9":2}'.encode()),
    ("duplicate raw emoji vs surrogate pair", '{"\U0001f600":1,"\\ud83d\\ude00":2}'.encode()),
    ("duplicate surrogate pair, mixed case hex", b'{"\\ud83d\\ude00":1,"\\uD83D\\uDE00":2}'),
    ("lone high surrogate escape", b'{"\\ud83d":1}'),
    ("lone surrogates are distinct names", b'{"\\ud83d":1,"\\ude00":2}'),
    ("names that only look alike", b'{"a":1,"a ":2,"A":3,"\\u0061\\u0020":4}'),
    ("structural characters inside strings", b'{"a{":1,"a}":2,"a:":3,"a,":4,"a\\"":5,"a[":6}'),
    ("escaped quote and backslash names", b'{"\\"":1,"\\\\":2,"\\\\\\"":3}'),
    ("raw control character in a string", b'{"a":"x\x01y"}'),
    ("raw tab in a string", b'{"a":"x\ty"}'),
    ("raw newline in a name", b'{"a\nb":1}'),
    ("DEL is allowed raw", b'{"a":"x\x7fy"}'),
    ("invalid escape", b'{"a":"\\x41"}'),
    ("short unicode escape", b'{"a":"\\u41"}'),
    ("non-hex unicode escape", b'{"a":"\\u00g1"}'),
    ("unterminated string", b'{"a":"abc'),
    ("NaN", b'{"a":NaN}'),
    ("Infinity", b'{"a":Infinity}'),
    ("minus Infinity", b'{"a":-Infinity}'),
    ("leading zero", b'{"a":01}'),
    ("negative zero", b'{"a":-0}'),
    ("plus sign", b'{"a":+1}'),
    ("bare fraction", b'{"a":.5}'),
    ("trailing dot", b'{"a":1.}'),
    ("dangling exponent", b'{"a":1e}'),
    ("exponent with sign only", b'{"a":1e+}'),
    ("upper-case exponent", b'{"a":1E5}'),
    ("hexadecimal number", b'{"a":0x10}'),
    ("trailing comma in object", b'{"a":1,}'),
    ("trailing comma in array", b"[1,2,]"),
    ("missing colon", b'{"a" 1}'),
    ("missing comma", b'{"a":1 "b":2}'),
    ("single quotes", b"{'a':1}"),
    ("unquoted name", b"{a:1}"),
    ("unquoted value", b'{"a":abc}'),
    ("comment", b'{"a":1 /* c */}'),
    ("capitalised literal", b'{"a":True}'),
    ("truncated literal", b'{"a":tru}'),
    ("two top-level values", b"{} {}"),
    ("trailing garbage", b'{"a":1}x'),
    ("unbalanced close", b'{"a":1}}'),
    ("unclosed array", b"[1,2"),
    ("mismatched brackets", b'{"a":[1}'),
    ("non-breaking space as whitespace", '{\N{NO-BREAK SPACE}"a":1}'.encode()),
    ("byte order mark", b'\xef\xbb\xbf{"a":1}'),
    ("byte order mark inside a string is data", '{"a":"\N{ZERO WIDTH NO-BREAK SPACE}"}'.encode()),
    ("overlong NUL", b'{"a":"\xc0\x80"}'),
    ("overlong slash", b'{"a":"\xe0\x80\xaf"}'),
    ("encoded surrogate", b'{"a":"\xed\xa0\x80"}'),
    ("code point above U+10FFFF", b'{"a":"\xf4\x90\x80\x80"}'),
    ("invalid lead byte", b'{"a":"\xff"}'),
    ("truncated sequence", b'{"a":"\xe2\x82"}'),
    ("lone continuation byte", b'{"a":"\x80"}'),
    ("valid 4-byte character", '{"a":"\U0001f600"}'.encode()),
    ("deep nesting, balanced", b"[" * 64 + b"]" * 64),
    ("deep objects with duplicate at the bottom", b'{"a":' * 40 + b'{"z":1,"z":2}' + b"}" * 40),
]

NAMES = ["a", "b", "id", "releaseId", "é", "\U0001f600", "x y", "a\\u0062", "k\\n", "\\u0041"]
SCALARS = ["1", "-0.5e-3", "true", "false", "null", '"s"', '"\\u00e9\\n"', '"ф"', "0", "12345678901234567890"]


def _random_value(rng: random.Random, depth: int) -> str:
    roll = rng.random()
    if depth > 4 or roll < 0.4:
        return rng.choice(SCALARS)
    if roll < 0.7:
        return "[" + ",".join(_random_value(rng, depth + 1) for _ in range(rng.randint(0, 3))) + "]"
    count = rng.randint(0, 4)
    names = rng.sample(NAMES, count)
    members = [f'"{name}":{_random_value(rng, depth + 1)}' for name in names]
    return "{" + ",".join(members) + "}"


def _mutate(rng: random.Random, text: str) -> str:
    choice = rng.randrange(8)
    if choice == 0 and '":' in text:
        # repeat one member of some object: insert a copy of an existing name next to it
        index = text.index('":')
        start = text.rindex('"', 0, index) + 1
        name = text[start:index]
        return text[: start - 1] + f'"{name}":0,' + text[start - 1 :]
    if choice == 1:
        position = rng.randrange(len(text) + 1)
        return text[:position] + rng.choice([",", ":", "}", "]", '"', "\\", " ", "\x01"]) + text[position:]
    if choice == 2 and text:
        position = rng.randrange(len(text))
        return text[:position] + text[position + 1 :]
    if choice == 3:
        return text.replace("1", "01", 1)
    if choice == 4:
        return text.replace("true", "NaN", 1)
    if choice == 5:
        return " \n" + text + "\t"
    if choice == 6:
        return text.replace('"a"', '"\\u0061"', 1)
    return text


def _verdict(document: bytes) -> bool:
    try:
        strict_json(document)
    except ValueError:
        return False
    return True


def generate() -> dict[str, Any]:
    rng = random.Random(SEED)
    vectors: list[dict[str, Any]] = [
        {"name": name, "document": base64.b64encode(document).decode("ascii"), "valid": _verdict(document)}
        for name, document in EDGE_CASES
    ]
    for index in range(RANDOM_DOCUMENTS):
        text = "{" + f'"root":{_random_value(rng, 0)}' + "}"
        if index % 2:
            text = _mutate(rng, text)
        document = text.encode("utf-8")
        vectors.append(
            {
                "name": f"random {index}",
                "document": base64.b64encode(document).decode("ascii"),
                "valid": _verdict(document),
            }
        )
    return {
        "generator": "tests/unit/test_ota_strict_json_vectors.py",
        "reference": "talent_data.ota.verify.strict_json",
        "vectors": vectors,
    }


def render() -> str:
    return json.dumps(generate(), indent=1, ensure_ascii=True) + "\n"


def test_committed_vectors_are_current() -> None:
    assert VECTORS.read_text(encoding="utf-8") == render(), (
        "strict-JSON vectors drifted: python tests/unit/test_ota_strict_json_vectors.py"
    )


def test_vectors_cover_both_verdicts_and_every_edge_case() -> None:
    vectors = json.loads(VECTORS.read_text(encoding="utf-8"))["vectors"]
    verdicts = {vector["valid"] for vector in vectors}
    assert verdicts == {True, False}
    names = {vector["name"] for vector in vectors}
    assert {name for name, _ in EDGE_CASES} <= names
    duplicates = [v for v in vectors if v["name"].startswith("duplicate") and "sibling" not in v["name"]]
    assert duplicates and not any(v["valid"] for v in duplicates)


if __name__ == "__main__":
    VECTORS.parent.mkdir(parents=True, exist_ok=True)
    VECTORS.write_text(render(), encoding="utf-8")
    print(f"wrote {VECTORS.relative_to(ROOT)}")
