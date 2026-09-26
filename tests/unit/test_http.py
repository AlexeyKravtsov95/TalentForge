from __future__ import annotations

import httpx
import pytest

from talent_data.sources.http import (
    DownloadError,
    HttpClient,
    HttpConfig,
    is_valid_cache_entry,
)
from talent_data.sources.wago.source import _validate_csv

URL = "https://example.test/db2/TraitNode/csv"
CSV = b"ID,TraitTreeID\n1,1117\n"


class Server:
    """Scripted responses for httpx.MockTransport; records every request."""

    def __init__(self, *responses: httpx.Response | Exception) -> None:
        self.responses = list(responses)
        self.calls = 0

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.calls += 1
        item = self.responses.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


def client(server: Server) -> HttpClient:
    return HttpClient(
        HttpConfig(max_attempts=3, backoff_base=0),
        transport=httpx.MockTransport(server),
        sleep=lambda _: None,
    )


def test_download_writes_file_and_sidecar(tmp_path) -> None:
    server = Server(httpx.Response(200, content=CSV))
    dest = tmp_path / "TraitNode.csv"
    client(server).download_file(URL, dest, validate=_validate_csv)
    assert dest.read_bytes() == CSV
    assert is_valid_cache_entry(dest)
    assert not (tmp_path / "TraitNode.csv.part").exists()


def test_valid_cache_entry_is_not_downloaded_again(tmp_path) -> None:
    dest = tmp_path / "TraitNode.csv"
    client(Server(httpx.Response(200, content=CSV))).download_file(URL, dest)
    server = Server()  # any request would fail with IndexError
    client(server).download_file(URL, dest)
    assert server.calls == 0


def test_corrupted_cache_entry_is_downloaded_again(tmp_path) -> None:
    dest = tmp_path / "TraitNode.csv"
    client(Server(httpx.Response(200, content=CSV))).download_file(URL, dest)
    dest.write_bytes(CSV[:-3])  # simulate a damaged file on disk
    assert not is_valid_cache_entry(dest)
    server = Server(httpx.Response(200, content=CSV))
    client(server).download_file(URL, dest)
    assert server.calls == 1
    assert dest.read_bytes() == CSV


def test_transient_errors_are_retried(tmp_path) -> None:
    server = Server(
        httpx.ConnectError("boom"),
        httpx.Response(503, text="busy"),
        httpx.Response(200, content=CSV),
    )
    dest = tmp_path / "TraitNode.csv"
    client(server).download_file(URL, dest)
    assert server.calls == 3
    assert dest.read_bytes() == CSV


def test_retries_are_bounded_and_leave_no_cache_entry(tmp_path) -> None:
    server = Server(*[httpx.Response(502) for _ in range(3)])
    dest = tmp_path / "TraitNode.csv"
    with pytest.raises(DownloadError, match="after 3 attempts"):
        client(server).download_file(URL, dest)
    assert not dest.exists()
    assert not is_valid_cache_entry(dest)


def test_not_found_is_not_retried_and_message_is_clear(tmp_path) -> None:
    server = Server(httpx.Response(404, json={"errors": "Table not found."}))
    with pytest.raises(DownloadError, match=r"HTTP 404.*Table not found"):
        client(server).download_file(URL, tmp_path / "x.csv")
    assert server.calls == 1


def test_html_error_page_is_rejected_by_validation(tmp_path) -> None:
    server = Server(httpx.Response(200, content=b"<!DOCTYPE html><title>Oops</title>"))
    dest = tmp_path / "TraitNode.csv"
    with pytest.raises(DownloadError, match="expected CSV"):
        client(server).download_file(URL, dest, validate=_validate_csv)
    assert not dest.exists()


def test_csv_without_named_columns_is_rejected(tmp_path) -> None:
    # Real case: wago.tools served TraitCurrency for 1.60.1.69876 with placeholder
    # column names because no DB2 definition matched that layout.
    body = b"Field_1_60_1_69876_000,Field_1_60_1_69876_001\n3820,2\n"
    server = Server(httpx.Response(200, content=body))
    with pytest.raises(DownloadError, match="no ID column"):
        client(server).download_file(URL, tmp_path / "TraitCurrency.csv", validate=_validate_csv)


def test_truncated_body_is_retried(tmp_path) -> None:
    truncated = httpx.Response(200, headers={"content-length": "999"}, content=CSV)
    server = Server(truncated, httpx.Response(200, content=CSV))
    dest = tmp_path / "TraitNode.csv"
    client(server).download_file(URL, dest)
    assert server.calls == 2
    assert dest.read_bytes() == CSV


def test_get_json_rejects_non_json(tmp_path) -> None:
    server = Server(httpx.Response(200, text="<html>", headers={"content-type": "text/html"}))
    with pytest.raises(DownloadError, match="non-JSON"):
        client(server).get_json("https://example.test/api/builds")
