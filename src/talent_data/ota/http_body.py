"""Read a single HTTP representation with independent wire and decoded byte caps."""

import zlib
from collections.abc import Callable

import httpx


class BoundedBodyError(ValueError):
    pass


def read_bounded_body(
    response: httpx.Response,
    limit: int,
    *,
    remaining: Callable[[], float] = lambda: 30.0,
) -> bytes:
    if remaining() <= 0:
        raise BoundedBodyError("VERIFY_BUDGET_EXHAUSTED")
    encoding = response.headers.get("content-encoding")
    if encoding is not None and encoding.lower().strip() != "gzip":
        raise BoundedBodyError("CONTENT_ENCODING: expected identity or one gzip member")
    declared = response.headers.get("content-length")
    wire_length = None
    if declared is not None:
        if not declared.isascii() or not declared.isdecimal():
            raise BoundedBodyError("CONTENT_LENGTH: invalid length")
        normalized = declared.lstrip("0") or "0"
        if len(normalized) > len(str(limit)):
            raise BoundedBodyError("SIZE_LIMIT: declared wire body exceeds pin/limit")
        wire_length = int(normalized)
        if wire_length > limit:
            raise BoundedBodyError("SIZE_LIMIT: declared wire body exceeds pin/limit")
    decoder = None if encoding is None else zlib.decompressobj(16 + zlib.MAX_WBITS)
    body = bytearray()
    received = 0
    for chunk in response.iter_raw():
        if remaining() <= 0:
            raise BoundedBodyError("VERIFY_BUDGET_EXHAUSTED")
        received += len(chunk)
        if received > limit:
            raise BoundedBodyError("SIZE_LIMIT: received wire body exceeds pin/limit")
        try:
            decoded = chunk if decoder is None else decoder.decompress(chunk, limit - len(body) + 1)
        except zlib.error as exc:
            raise BoundedBodyError("CONTENT_ENCODING: invalid gzip body") from exc
        if len(body) + len(decoded) > limit:
            raise BoundedBodyError("SIZE_LIMIT: decoded body exceeds pin/limit")
        body.extend(decoded)
        if decoder is not None and decoder.unused_data:
            raise BoundedBodyError("CONTENT_ENCODING: trailing data or another gzip member")
        if remaining() <= 0:
            raise BoundedBodyError("VERIFY_BUDGET_EXHAUSTED")
    if remaining() <= 0:
        raise BoundedBodyError("VERIFY_BUDGET_EXHAUSTED")
    if wire_length is not None and wire_length != received:
        raise BoundedBodyError("CONTENT_LENGTH: received wire length differs")
    if decoder is not None and not decoder.eof:
        raise BoundedBodyError("CONTENT_ENCODING: incomplete gzip body")
    return bytes(body)
