"""wago.tools CASC file source: build pinning, cache identity, typed errors (no network)."""

from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest

from talent_data.sources.casc import CascFileError, CascFileErrorKind
from talent_data.sources.http import HttpClient, HttpConfig
from talent_data.sources.wago.casc import WagoCascFileSource
from tests.helpers import NEW_BUILD, OLD_BUILD
from tests.unit.visuals.conftest import BLP_WITH_ALPHA, blp_bytes

FID = BLP_WITH_ALPHA
# Verbatim wago.tools answers captured on 2026-10-07.
FILE_NOT_FOUND_BODY = b'{"error":"File not found","0":400}'
VERSION_NOT_FOUND_BODY = b'{"error":"Version not found"}'


class Server:
    def __init__(self, *responses: httpx.Response | Exception) -> None:
        self.responses = list(responses)
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        item = self.responses.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


def source(server: Server, cache: Path) -> WagoCascFileSource:
    http = HttpClient(
        HttpConfig(max_attempts=2, backoff_base=0),
        transport=httpx.MockTransport(server),
        sleep=lambda _: None,
    )
    return WagoCascFileSource(http, cache)


def ok(body: bytes) -> httpx.Response:
    return httpx.Response(200, content=body, headers={"content-type": "application/octet-stream"})


def test_request_is_pinned_to_the_client_build(tmp_path: Path) -> None:
    server = Server(ok(blp_bytes(FID)))
    path = source(server, tmp_path).fetch_file(NEW_BUILD, FID)
    assert str(server.requests[0].url) == f"https://wago.tools/api/casc/{FID}?version={NEW_BUILD}"
    assert path == tmp_path / str(NEW_BUILD) / "casc" / f"{FID}.bin"
    assert path.read_bytes() == blp_bytes(FID)
    assert not path.with_name(path.name + ".part").exists()
    meta = json.loads(path.with_name(path.name + ".meta.json").read_text())
    assert meta["url"] == f"https://wago.tools/api/casc/{FID}?version={NEW_BUILD}"


def test_cached_file_is_not_downloaded_again(tmp_path: Path) -> None:
    source(Server(ok(blp_bytes(FID))), tmp_path).fetch_file(NEW_BUILD, FID)
    server = Server()  # any request would fail with IndexError
    assert source(server, tmp_path).fetch_file(NEW_BUILD, FID).is_file()
    assert server.requests == []


def test_cache_is_per_build(tmp_path: Path) -> None:
    source(Server(ok(blp_bytes(FID))), tmp_path).fetch_file(NEW_BUILD, FID)
    server = Server(ok(b"BLP2" + b"\1" * 64))
    other = source(server, tmp_path).fetch_file(OLD_BUILD, FID)
    assert len(server.requests) == 1
    assert "version=1.60.1.69977" in str(server.requests[0].url)
    assert other.read_bytes() != blp_bytes(FID)


def test_a_file_copied_from_another_build_is_never_reused(tmp_path: Path) -> None:
    """A valid cache entry whose recorded URL names another build is not this build's file."""
    fetched = source(Server(ok(blp_bytes(FID))), tmp_path).fetch_file(OLD_BUILD, FID)
    planted = tmp_path / str(NEW_BUILD) / "casc" / fetched.name
    planted.parent.mkdir(parents=True)
    planted.write_bytes(fetched.read_bytes())
    meta = fetched.with_name(fetched.name + ".meta.json")
    planted.with_name(planted.name + ".meta.json").write_bytes(meta.read_bytes())

    offline = WagoCascFileSource(None, tmp_path, offline=True)
    assert not offline.is_cached(NEW_BUILD, FID)
    with pytest.raises(CascFileError) as caught:
        offline.fetch_file(NEW_BUILD, FID)
    assert caught.value.kind is CascFileErrorKind.NOT_CACHED

    server = Server(ok(b"BLP2" + b"\2" * 64))
    assert source(server, tmp_path).fetch_file(NEW_BUILD, FID).read_bytes() == b"BLP2" + b"\2" * 64
    assert len(server.requests) == 1


def test_file_not_found_served_with_http_200_is_not_a_file(tmp_path: Path) -> None:
    server = Server(httpx.Response(200, content=FILE_NOT_FOUND_BODY))
    with pytest.raises(CascFileError) as caught:
        source(server, tmp_path).fetch_file(NEW_BUILD, 999_999_999)
    assert caught.value.kind is CascFileErrorKind.FILE_NOT_FOUND
    assert (caught.value.version, caught.value.file_data_id) == (NEW_BUILD, 999_999_999)
    assert len(server.requests) == 1  # a definitive answer is not retried
    assert list((tmp_path / str(NEW_BUILD) / "casc").iterdir()) == []


def test_unknown_build_is_a_typed_error(tmp_path: Path) -> None:
    server = Server(
        httpx.Response(
            400, content=VERSION_NOT_FOUND_BODY, headers={"content-type": "application/json"}
        )
    )
    with pytest.raises(CascFileError) as caught:
        source(server, tmp_path).fetch_file(NEW_BUILD, FID)
    assert caught.value.kind is CascFileErrorKind.UNKNOWN_BUILD


def test_another_json_error_object_is_an_invalid_payload(tmp_path: Path) -> None:
    server = Server(httpx.Response(200, content=b'{"error":"Rate limited"}'))
    with pytest.raises(CascFileError) as caught:
        source(server, tmp_path).fetch_file(NEW_BUILD, FID)
    assert caught.value.kind is CascFileErrorKind.INVALID_PAYLOAD


def test_empty_body_and_server_failures_are_transport_errors(tmp_path: Path) -> None:
    with pytest.raises(CascFileError) as empty:
        source(Server(httpx.Response(200, content=b"")), tmp_path).fetch_file(NEW_BUILD, FID)
    assert empty.value.kind is CascFileErrorKind.TRANSPORT

    server = Server(httpx.Response(503), httpx.ConnectError("down"))
    with pytest.raises(CascFileError) as down:
        source(server, tmp_path).fetch_file(NEW_BUILD, FID)
    assert down.value.kind is CascFileErrorKind.TRANSPORT
    assert len(server.requests) == 2  # transient failures are retried, then reported


def test_offline_source_never_touches_the_network(tmp_path: Path) -> None:
    offline = WagoCascFileSource(None, tmp_path, offline=True)
    with pytest.raises(CascFileError) as caught:
        offline.fetch_file(NEW_BUILD, FID)
    assert caught.value.kind is CascFileErrorKind.NOT_CACHED
    assert "visuals fetch" in str(caught.value)


def test_non_positive_file_data_id_is_refused_without_a_request(tmp_path: Path) -> None:
    server = Server()
    with pytest.raises(CascFileError) as caught:
        source(server, tmp_path).fetch_file(NEW_BUILD, 0)
    assert caught.value.kind is CascFileErrorKind.FILE_NOT_FOUND
    assert server.requests == []


def test_binary_payload_starting_with_a_brace_is_still_a_file(tmp_path: Path) -> None:
    body = b"{\xff\xfe binary, not JSON"
    path = source(Server(ok(body)), tmp_path).fetch_file(NEW_BUILD, FID)
    assert path.read_bytes() == body


def test_online_source_requires_a_client(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="HttpClient is required"):
        WagoCascFileSource(None, tmp_path)
