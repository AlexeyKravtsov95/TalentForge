"""The narrow SavedVariables wrapper written by the capture addon.

WoW serialises the addon's one saved variable as Lua source. This module reads exactly the
shape the addon stores - and nothing else::

    TalentForgeTooltipCaptureDB = {
        ["formatVersion"] = 1,
        ["payloadBytes"] = 123456,
        ["payloadBase64"] = {
            "eyJjYXB0dXJl...", -- [1]
            "...", -- [2]
        },
    }

It is a tokenizer plus a fixed grammar, not a Lua interpreter: there is no evaluation, no
expression support and no escape processing. Anything outside the shape above - another
variable, an unknown key, a string containing a backslash, a nested table - is rejected.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from talent_data.tooltips.errors import (
    MalformedSavedVariablesError,
    NoStoredCaptureError,
    UnsupportedCaptureFormatError,
)

SAVED_VARIABLE = "TalentForgeTooltipCaptureDB"
SAVED_FORMAT_VERSION = 1

_TOKEN = re.compile(
    r"""
      (?P<space>[ \t\r\n]+)
    | (?P<comment>--[^\n]*)
    | (?P<string>"[^"\\\r\n]*")
    | (?P<int>\d+)(?![\w.])
    | (?P<name>[A-Za-z_][A-Za-z0-9_]*)
    | (?P<punct>[{}\[\]=,])
    """,
    re.VERBOSE,
)


@dataclass(frozen=True)
class SavedCapture:
    """The wrapper's content. ``chunks`` is ``None`` for a capture that never finished."""

    payload_bytes: int | None
    chunks: tuple[str, ...] | None


@dataclass(frozen=True)
class _Token:
    kind: str  # "string" | "int" | "name" | one of the punctuation characters
    text: str
    offset: int


def _tokenize(text: str) -> list[_Token]:
    tokens: list[_Token] = []
    position = 0
    while position < len(text):
        match = _TOKEN.match(text, position)
        if match is None:
            raise MalformedSavedVariablesError(
                f"unexpected content at offset {position}: {text[position : position + 20]!r}"
            )
        kind = match.lastgroup
        assert kind is not None
        if kind == "string":
            tokens.append(_Token("string", match.group()[1:-1], position))
        elif kind == "punct":
            tokens.append(_Token(match.group(), match.group(), position))
        elif kind in ("int", "name"):
            tokens.append(_Token(kind, match.group(), position))
        position = match.end()
    return tokens


class _Parser:
    def __init__(self, tokens: list[_Token]) -> None:
        self._tokens = tokens
        self._index = 0

    def peek(self) -> _Token | None:
        return self._tokens[self._index] if self._index < len(self._tokens) else None

    def take(self, kind: str) -> _Token:
        token = self.peek()
        if token is None:
            raise MalformedSavedVariablesError(f"unexpected end of file; expected {kind}")
        if token.kind != kind:
            raise MalformedSavedVariablesError(
                f"expected {kind} at offset {token.offset}, found {token.text!r}"
            )
        self._index += 1
        return token

    def accept(self, kind: str) -> bool:
        token = self.peek()
        if token is not None and token.kind == kind:
            self._index += 1
            return True
        return False

    def string_list(self) -> tuple[str, ...]:
        self.take("{")
        items: list[str] = []
        while not self.accept("}"):
            items.append(self.take("string").text)
            if not self.accept(","):
                self.take("}")
                break
        return tuple(items)

    def fields(self) -> dict[str, int | tuple[str, ...]]:
        self.take("{")
        fields: dict[str, int | tuple[str, ...]] = {}
        while not self.accept("}"):
            self.take("[")
            key = self.take("string").text
            self.take("]")
            self.take("=")
            if key in fields:
                raise MalformedSavedVariablesError(f"duplicate key {key!r}")
            if key in ("formatVersion", "payloadBytes"):
                fields[key] = int(self.take("int").text)
            elif key == "payloadBase64":
                fields[key] = self.string_list()
            else:
                raise MalformedSavedVariablesError(f"unknown key {key!r}")
            if not self.accept(","):
                self.take("}")
                break
        return fields


def parse_saved_variables(text: str) -> SavedCapture:
    """Read the addon's SavedVariables file. Raises a typed error for anything unexpected."""
    parser = _Parser(_tokenize(text))
    name = parser.take("name")
    if name.text != SAVED_VARIABLE:
        raise MalformedSavedVariablesError(
            f"expected the variable {SAVED_VARIABLE}, found {name.text!r}"
        )
    parser.take("=")
    following = parser.peek()
    if following is not None and following.kind == "name" and following.text == "nil":
        parser.take("name")
        _expect_end(parser)
        raise NoStoredCaptureError(
            f"{SAVED_VARIABLE} is nil: no capture was stored (run /tftooltips capture, wait "
            "for completion, then /reload)"
        )
    fields = parser.fields()
    _expect_end(parser)

    version = fields.get("formatVersion")
    if version is None:
        raise MalformedSavedVariablesError("formatVersion is missing")
    if version != SAVED_FORMAT_VERSION:
        raise UnsupportedCaptureFormatError(
            f"unsupported SavedVariables formatVersion {version}; "
            f"this importer reads {SAVED_FORMAT_VERSION}"
        )
    chunks = fields.get("payloadBase64")
    size = fields.get("payloadBytes")
    if (chunks is None) != (size is None):
        raise MalformedSavedVariablesError("payloadBytes and payloadBase64 must be stored together")
    if chunks is None:
        return SavedCapture(payload_bytes=None, chunks=None)
    assert isinstance(chunks, tuple)
    assert isinstance(size, int)
    return SavedCapture(payload_bytes=size, chunks=chunks)


def _expect_end(parser: _Parser) -> None:
    extra = parser.peek()
    if extra is not None:
        raise MalformedSavedVariablesError(
            f"unexpected content after the variable at offset {extra.offset}: {extra.text!r}"
        )
