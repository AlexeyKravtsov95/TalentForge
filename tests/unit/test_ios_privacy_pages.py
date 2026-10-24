"""Publishing the iOS privacy pages (M5.IOS.3).

``scripts/ios_privacy_pages.py`` renders the committed pages with the publication date and
checks the published copies. The network is replaced by a fake ``fetch``: the live check must
pass only for HTTP 200, HTML with UTF-8 declared in the document (and optionally the HTTP
header), valid UTF-8 bytes, and the exact rendered content.
"""

from __future__ import annotations

import datetime as dt
import importlib.util
import sys
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[2]


def load_tool() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "ios_privacy_pages", ROOT / "scripts" / "ios_privacy_pages.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


t = load_tool()
DATE = dt.date(2026, 10, 25)


def test_the_date_has_the_published_android_format() -> None:
    assert t.format_date(DATE, "ru") == "25 октября 2026 года"
    assert t.format_date(DATE, "en") == "25 October 2026"
    assert t.format_date(dt.date(2027, 3, 1), "ru") == "1 марта 2027 года"


def test_render_fills_the_one_placeholder_of_each_committed_page() -> None:
    pages = t.render(t.SOURCE, DATE)
    assert set(pages) == {"privacy-ios.html", "privacy-ios-en.html"}
    ru, en = pages["privacy-ios.html"].decode(), pages["privacy-ios-en.html"].decode()
    assert "Обновлено: 25 октября 2026 года" in ru
    assert "Updated: 25 October 2026" in en
    assert "{{" not in ru + en


@pytest.mark.parametrize(
    "text",
    ["<p>no date</p>", "{{PUBLICATION_DATE}} {{PUBLICATION_DATE}}", "{{PUBLICATION_DATE}} {{X}}"],
)
def test_render_requires_exactly_the_one_placeholder(tmp_path: Path, text: str) -> None:
    for name in t.PAGES:
        (tmp_path / name).write_text(text, encoding="utf-8")
    with pytest.raises(t.ToolError):
        t.render(tmp_path, DATE)


def serving(pages: dict[str, bytes], **override: Any) -> Any:
    def fetch(url: str) -> Any:
        name = url.rsplit("/", 1)[1]
        response = {"status": 200, "content_type": "text/html; charset=utf-8", "body": pages[name]}
        response.update(override)
        return t.Response(**response)

    return fetch


@pytest.mark.parametrize(
    "content_type",
    [
        "text/html",
        "text/html; charset=utf-8",
        'Text/HTML; Charset="UTF-8"',
        "text/html;\tcharset=utf-8",
    ],
)
def test_verify_live_passes_for_the_exact_pages(content_type: str) -> None:
    pages = t.render(t.SOURCE, DATE)
    passed, lines = t.verify_live(
        pages, "https://legal.mythweld.com", serving(pages, content_type=content_type)
    )
    assert passed
    assert lines[0].startswith("  [PASS] https://legal.mythweld.com/privacy-ios.html")
    assert content_type in lines[0]


@pytest.mark.parametrize(
    ("override", "problem"),
    [
        ({"status": 403, "body": b"Forbidden"}, "HTTP 403"),
        ({"content_type": "text/plain; charset=utf-8"}, "Content-Type"),
        ({"content_type": "text/html; charset=windows-1251"}, "Content-Type"),
        ({"content_type": "text/html; charset=utf-8; charset=windows-1251"}, "Content-Type"),
        ({"content_type": "text / html"}, "Content-Type"),
        ({"content_type": None}, "Content-Type"),
        ({"body": b"<html>older edition</html>"}, "is not the rendered page"),
    ],
)
def test_verify_live_fails_closed(override: dict[str, Any], problem: str) -> None:
    pages = t.render(t.SOURCE, DATE)
    passed, lines = t.verify_live(pages, "https://legal.mythweld.com", serving(pages, **override))
    assert not passed
    assert all(problem in line for line in lines)


@pytest.mark.parametrize(
    "body",
    [
        b"<!doctype html><head></head><body>No declaration</body>",
        b'<!-- <meta charset="utf-8"> -->',
        b"<script>const text = '<meta charset=\"utf-8\">';</script>",
        b'<meta charset="windows-1251">',
        b'<meta charset="utf-8"><meta charset="windows-1251">',
        b'<meta charset="utf-8" charset="windows-1251">',
        b" " * 1024 + b'<meta charset="utf-8">',
        b" " * 1023 + b'<meta charset="utf-8">',
    ],
)
def test_matching_bytes_still_require_a_real_early_utf8_declaration(body: bytes) -> None:
    pages = dict.fromkeys(t.PAGES, body)
    passed, lines = t.verify_live(pages, "https://x", serving(pages, content_type="text/html"))
    assert not passed
    assert all("UTF-8 meta declaration" in line for line in lines)


def test_the_complete_meta_tag_may_end_at_byte_1024() -> None:
    tag = b'<meta charset="utf-8">'
    pages = dict.fromkeys(t.PAGES, b" " * (1024 - len(tag)) + tag)
    passed, _ = t.verify_live(pages, "https://x", serving(pages, content_type="text/html"))
    assert passed


def test_matching_bytes_with_a_meta_declaration_must_still_be_valid_utf8() -> None:
    pages = dict.fromkeys(t.PAGES, b'<meta charset="utf-8"><p>\xff</p>')
    passed, lines = t.verify_live(pages, "https://x", serving(pages, content_type="text/html"))
    assert not passed
    assert all("body is not valid UTF-8" in line for line in lines)


def test_a_page_published_with_another_date_does_not_pass() -> None:
    published = t.render(t.SOURCE, dt.date(2026, 10, 24))
    passed, _ = t.verify_live(t.render(t.SOURCE, DATE), "https://x", serving(published))
    assert not passed


def test_an_unreachable_site_fails() -> None:
    def unreachable(url: str) -> Any:
        raise OSError("no route to host")

    passed, lines = t.verify_live(t.render(t.SOURCE, DATE), "https://x", unreachable)
    assert not passed
    assert "not reachable" in lines[0]


def test_cli(tmp_path: Path) -> None:
    out = tmp_path / "out"
    assert t.main(["render", "--date", "2026-10-25", "--out", str(out)]) == t.EXIT_PASS
    pages = {name: (out / name).read_bytes() for name in t.PAGES}
    assert pages == t.render(t.SOURCE, DATE)
    live = ["verify-live", "--date", "2026-10-25"]
    assert t.main(live, fetch=serving(pages)) == t.EXIT_PASS
    assert t.main(live, fetch=serving(pages, status=404)) == t.EXIT_FAIL
    assert t.main(["render", "--date", "25.10.2026", "--out", str(out)]) == t.EXIT_ERROR
