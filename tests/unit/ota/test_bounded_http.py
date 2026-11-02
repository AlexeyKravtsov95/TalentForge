"""The real bounded readers must handle gzip without relaxing either byte budget."""

import gzip

import httpx
import pytest

from talent_data.ota.automation.public_verify import _fetch, _PublicFailure
from talent_data.ota.preflight import fetch


def read(reader, wire, limit, *, encoding="gzip", declared=None, stream=None):
    headers = {} if encoding is None else {"Content-Encoding": encoding}
    if declared is not None:
        headers["Content-Length"] = declared

    def handler(request):
        return httpx.Response(200, headers=headers, stream=stream or httpx.ByteStream(wire))

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        if reader == "preflight":
            result = fetch(
                client, "https://example.invalid/object", accept_gzip=True, max_bytes=limit
            )
            if result.status != 200 or result.error:
                raise ValueError(result.error)
            return result.body
        try:
            return _fetch(client, "https://example.invalid/object", limit)
        except _PublicFailure as exc:
            raise ValueError(str(exc)) from exc


@pytest.mark.parametrize("reader", ["preflight", "public"])
def test_bounded_gzip_returns_exact_identity_bytes(reader):
    body = b"signed bytes " * 20
    wire = gzip.compress(body, mtime=0)
    assert read(reader, wire, len(body), declared=str(len(wire))) == body


@pytest.mark.parametrize("reader", ["preflight", "public"])
def test_bounded_identity_remains_supported(reader):
    body = b"signed bytes"
    assert read(reader, body, len(body), encoding=None, declared=str(len(body))) == body


@pytest.mark.parametrize("reader", ["preflight", "public"])
def test_gzip_decompressed_size_cannot_exceed_limit(reader):
    wire = gzip.compress(b"x" * 1_000_000, mtime=0)
    assert len(wire) < 4096
    with pytest.raises(ValueError, match="SIZE_LIMIT"):
        read(reader, wire, 4096)


@pytest.mark.parametrize("reader", ["preflight", "public"])
def test_gzip_wire_size_cannot_exceed_limit_even_if_decoded_body_fits(reader):
    wire = gzip.compress(b"x", mtime=0)
    with pytest.raises(ValueError, match="SIZE_LIMIT"):
        read(reader, wire, len(wire) - 1)


@pytest.mark.parametrize("reader", ["preflight", "public"])
@pytest.mark.parametrize("fault", ["truncated", "crc", "trailing", "second_member"])
def test_gzip_requires_one_complete_member_with_valid_crc(reader, fault):
    wire = gzip.compress(b"x" * 200, mtime=0)
    if fault == "truncated":
        wire = wire[:-1]
    elif fault == "crc":
        wire = wire[:-8] + bytes([wire[-8] ^ 1]) + wire[-7:]
    elif fault == "trailing":
        wire += b"ignored trailing data"
    else:
        wire += gzip.compress(b"another member", mtime=0)
    with pytest.raises(ValueError, match="CONTENT_ENCODING"):
        read(reader, wire, 4096)


@pytest.mark.parametrize("reader", ["preflight", "public"])
@pytest.mark.parametrize("encoding", ["br", "deflate", "gzip, br"])
def test_unknown_or_stacked_encodings_are_rejected(reader, encoding):
    with pytest.raises(ValueError, match="CONTENT_ENCODING"):
        read(reader, b"x", 4096, encoding=encoding)


@pytest.mark.parametrize("reader", ["preflight", "public"])
@pytest.mark.parametrize("declared", ["no", "-1", "9999999", "1", "9" * 5000])
def test_gzip_content_length_is_checked_against_wire_not_decoded_bytes(reader, declared):
    wire = gzip.compress(b"x" * 200, mtime=0)
    with pytest.raises(ValueError, match=r"CONTENT_LENGTH|SIZE_LIMIT"):
        read(reader, wire, 4096, declared=declared)


class BombStream(httpx.SyncByteStream):
    def __iter__(self):
        yield gzip.compress(b"x" * 1_000_000, mtime=0)[:64]
        raise AssertionError("read continued after decoded size exceeded the budget")


@pytest.mark.parametrize("reader", ["preflight", "public"])
def test_gzip_bomb_is_stopped_before_reading_the_next_chunk(reader):
    with pytest.raises(ValueError, match="SIZE_LIMIT"):
        read(reader, b"", 4096, stream=BombStream())


def test_explicit_bounded_identity_request_still_rejects_encoding():
    wire = gzip.compress(b"x" * 200, mtime=0)

    def handler(request):
        assert request.headers["accept-encoding"] == "identity"
        return httpx.Response(
            200, headers={"Content-Encoding": "gzip"}, stream=httpx.ByteStream(wire)
        )

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = fetch(client, "https://example.invalid/object", max_bytes=4096)
    assert result.status == 0 and "bounded identity" in result.error
