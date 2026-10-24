#!/usr/bin/env python3
"""Prepare and verify the publication of the iOS privacy policy pages (M5.IOS.3).

The committed pages ``docs/legal/ios/privacy-ios.html`` (ru) and ``privacy-ios-en.html`` (en)
carry one ``{{PUBLICATION_DATE}}`` placeholder each. Only the owner uploads them to
``https://legal.mythweld.com`` (docs/legal/ios/README.md §3). This script does the two steps
around that upload that can be checked:

``render --date YYYY-MM-DD --out DIR``
    Write both pages with the date in the format of the published Android policy
    ("25 октября 2026 года" / "25 October 2026") and print their SHA-256. Fails if a page does
    not hold exactly one placeholder or still holds ``{{`` afterwards.

``verify-live --date YYYY-MM-DD``
    Fetch both published pages (anonymous ``GET``, no cookies) and require for each: HTTP 200,
    ``Content-Type: text/html`` (optionally with a UTF-8 charset), valid UTF-8 with one
    ``<meta charset="utf-8">`` declaration within the first 1024 bytes, and a body
    byte-for-byte equal to the page rendered with that date. Only when both pass may the
    iOS About screen link to the policy and App Store Connect use the URLs.

Exit status: 0 = pass, 1 = a check failed, 2 = the check could not run.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import re
import sys
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/legal/ios"
BASE_URL = "https://legal.mythweld.com"
PLACEHOLDER = "{{PUBLICATION_DATE}}"
PAGES = {"privacy-ios.html": "ru", "privacy-ios-en.html": "en"}
CONTENT_TYPE = "text/html; charset=utf-8"
HTML_CONTENT_TYPE = re.compile(
    r'[ \t]*text/html[ \t]*(?:;[ \t]*charset[ \t]*=[ \t]*(?:"utf-8"|utf-8)[ \t]*)?',
    re.IGNORECASE | re.ASCII,
)
RU_MONTHS = (
    "января",
    "февраля",
    "марта",
    "апреля",
    "мая",
    "июня",
    "июля",
    "августа",
    "сентября",
    "октября",
    "ноября",
    "декабря",
)
EN_MONTHS = (
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
)
TIMEOUT_SECONDS = 20

EXIT_PASS, EXIT_FAIL, EXIT_ERROR = 0, 1, 2


class ToolError(Exception):
    """A page could not be read or rendered."""


@dataclass(frozen=True)
class Response:
    status: int
    content_type: str | None
    body: bytes


Fetch = Callable[[str], Response]


class MetaCharsetParser(HTMLParser):
    """Collect real meta charset attributes, excluding comments and script contents."""

    def __init__(self) -> None:
        super().__init__()
        self.charsets: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "meta":
            self.charsets.extend((value or "").lower() for key, value in attrs if key == "charset")


def has_early_utf8_meta(body: bytes) -> bool:
    parser = MetaCharsetParser()
    # Latin-1 maps bytes one-to-one for parsing ASCII markup at the 1024-byte boundary.
    # The complete document is separately required to decode as UTF-8 below.
    parser.feed(body[:1024].decode("latin-1"))
    parser.close()
    return parser.charsets == ["utf-8"]


def format_date(date: dt.date, language: str) -> str:
    if language == "ru":
        return f"{date.day} {RU_MONTHS[date.month - 1]} {date.year} года"
    return f"{date.day} {EN_MONTHS[date.month - 1]} {date.year}"


def render(source: Path, date: dt.date) -> dict[str, bytes]:
    """Both pages with ``date`` in place of the placeholder, by file name."""
    pages = {}
    for name, language in PAGES.items():
        try:
            text = (source / name).read_text(encoding="utf-8")
        except OSError as error:
            raise ToolError(f"cannot read {name}: {error}") from error
        if text.count(PLACEHOLDER) != 1:
            raise ToolError(
                f"{name} holds {text.count(PLACEHOLDER)} {PLACEHOLDER} placeholders (expected 1)"
            )
        rendered = text.replace(PLACEHOLDER, format_date(date, language))
        if "{{" in rendered:
            raise ToolError(f"{name} still holds a '{{{{' placeholder after rendering")
        pages[name] = rendered.encode("utf-8")
    return pages


def http_fetch(url: str) -> Response:
    request = urllib.request.Request(url, headers={"User-Agent": "TalentForge-privacy-check"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return Response(response.status, response.headers.get("Content-Type"), response.read())
    except urllib.error.HTTPError as error:
        return Response(error.code, error.headers.get("Content-Type"), error.read())


def verify_live(pages: dict[str, bytes], base_url: str, fetch: Fetch) -> tuple[bool, list[str]]:
    """Whether every published page is exactly the rendered one, and one line per page."""
    lines, passed = [], True
    for name, expected in pages.items():
        url = f"{base_url.rstrip('/')}/{name}"
        try:
            response = fetch(url)
        except (urllib.error.URLError, OSError) as error:
            lines.append(f"  [FAIL] {url}: not reachable ({error})")
            passed = False
            continue
        problems = []
        if response.status != 200:
            problems.append(f"HTTP {response.status} (expected 200)")
        if not HTML_CONTENT_TYPE.fullmatch(response.content_type or ""):
            problems.append(
                f"Content-Type {response.content_type!r} "
                f"(expected {CONTENT_TYPE!r} or 'text/html' with an early UTF-8 meta declaration)"
            )
        if response.status == 200:
            try:
                response.body.decode("utf-8")
            except UnicodeDecodeError:
                problems.append("body is not valid UTF-8")
            if not has_early_utf8_meta(response.body):
                problems.append("expected one UTF-8 meta declaration within the first 1024 bytes")
        if response.status == 200 and response.body != expected:
            problems.append(
                f"body sha256 {hashlib.sha256(response.body).hexdigest()} is not the rendered "
                f"page {hashlib.sha256(expected).hexdigest()}"
            )
        passed = passed and not problems
        lines.append(
            f"  [{'FAIL' if problems else 'PASS'}] {url}: "
            + (
                "; ".join(problems)
                or f"200, {response.content_type}, UTF-8 meta, identical to the rendered page"
            )
        )
    return passed, lines


def main(argv: list[str] | None = None, fetch: Fetch = http_fetch) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0] if __doc__ else None)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("render", "verify-live"):
        sub = commands.add_parser(command)
        sub.add_argument("--date", required=True, help="the publication date, YYYY-MM-DD")
        sub.add_argument("--source", default=str(SOURCE), help="the committed pages")
        if command == "render":
            sub.add_argument("--out", required=True, help="the directory to write both pages to")
        else:
            sub.add_argument("--base-url", default=BASE_URL, help="where the pages are published")
    args = parser.parse_args(argv)
    try:
        date = dt.date.fromisoformat(args.date)
        pages = render(Path(args.source), date)
    except (ValueError, ToolError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return EXIT_ERROR
    if args.command == "render":
        out = Path(args.out)
        out.mkdir(parents=True, exist_ok=True)
        for name, body in pages.items():
            (out / name).write_bytes(body)
            print(f"{hashlib.sha256(body).hexdigest()}  {out / name}")
        return EXIT_PASS
    passed, lines = verify_live(pages, args.base_url, fetch)
    print(f"iOS privacy pages published on {args.date}:")
    print("\n".join(lines))
    print(f"RESULT: {'PASS' if passed else 'FAIL'}")
    return EXIT_PASS if passed else EXIT_FAIL


if __name__ == "__main__":
    sys.exit(main())
