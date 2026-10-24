#!/usr/bin/env python3
"""Validate the App Store Connect metadata of the TalentForge submission pack (M5.IOS.3).

The pack keeps one plain-text file per App Store Connect field, so the owner can copy each
one into App Store Connect unchanged (``docs/release/apple-appstore/submission/metadata``)::

    copyright.txt  primary_category.txt  secondary_category.txt
    <locale>/name.txt subtitle.txt promotional_text.txt description.txt keywords.txt
             support_url.txt privacy_url.txt                            (locales: en-US, ru)
    review_information/notes.txt                                        (App Review notes)

Every check must pass; a file that is missing, empty or unreadable is a failure:

- each file is UTF-8 text that ends with exactly one newline, without carriage returns, tabs,
  leading or trailing blanks, or template placeholders (``{{`` / ``TODO``);
- Apple's limits: name 2-30 characters, subtitle ≤ 30, promotional text ≤ 170, description
  ≤ 4000; keywords ≤ 100 bytes, review notes ≤ 4000 bytes. There is no What's New file: App
  Store Connect has no such field for an app's first version;
- keywords are comma-separated without blanks around the commas, unique, each longer than two
  characters, and repeat no word of the name or subtitle (App Store search indexes those);
- the name is ``TalentForge`` in every locale (the app's ``CFBundleDisplayName``);
- the positioning is kept: subtitle and description name World of Warcraft: Forever, the
  description says the app is unofficial and carries the Blizzard disclaimer;
- the texts match the OTA ON build: the description says that only signed game data is
  downloaded and that the app works offline, and no text claims the build makes no requests;
  the review notes explain the unofficial status and the OTA mechanism (Guideline 2.5.2);
- no other store or platform is named (Guideline 2.3.10);
- the URLs are the iOS pages: support ``https://legal.mythweld.com/``, privacy
  ``privacy-ios-en.html`` (en-US) / ``privacy-ios.html`` (ru); the categories are App Store
  categories and not Games.

Usage::

    python3 scripts/verify_appstore_metadata.py [--metadata DIR] [--report report.json]

Exit status: 0 = all checks pass, 1 = a check failed, 2 = the check could not run.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_METADATA = ROOT / "docs/release/apple-appstore/submission/metadata"
REPORT_SCHEMA = "talentforge.appstore-metadata-report.v1"
APP_NAME = "TalentForge"
LOCALES = ("en-US", "ru")
SUPPORT_URL = "https://legal.mythweld.com/"
PRIVACY_URLS = {
    "en-US": "https://legal.mythweld.com/privacy-ios-en.html",
    "ru": "https://legal.mythweld.com/privacy-ios.html",
}
# (minimum, maximum) characters; keywords are limited in bytes (KEYWORDS_MAX_BYTES).
CHARACTER_LIMITS = {
    "name": (2, 30),
    "subtitle": (1, 30),
    "promotional_text": (1, 170),
    "description": (1, 4000),
}
KEYWORDS_MAX_BYTES = 100
KEYWORD_MIN_CHARACTERS = 3  # "each greater than two characters"
REVIEW_NOTES_MAX_BYTES = 4000
LOCALE_FILES = (*CHARACTER_LIMITS, "keywords", "support_url", "privacy_url")
APP_STORE_CATEGORIES = frozenset(
    {
        "Books",
        "Business",
        "Developer Tools",
        "Education",
        "Entertainment",
        "Finance",
        "Food & Drink",
        "Games",
        "Graphics & Design",
        "Health & Fitness",
        "Kids",
        "Lifestyle",
        "Magazines & Newspapers",
        "Medical",
        "Music",
        "Navigation",
        "News",
        "Photo & Video",
        "Productivity",
        "Reference",
        "Shopping",
        "Social Networking",
        "Sports",
        "Travel",
        "Utilities",
        "Weather",
    }
)
GAME = "World of Warcraft: Forever"
GAME_SHORT = "WoW: Forever"
# Per locale: phrases the description must contain (positioning, disclaimer, OTA ON facts).
DESCRIPTION_REQUIRED = {
    "en-US": (
        GAME,
        "unofficial",
        "not affiliated with",
        "Blizzard Entertainment",
        "HTTPS",
        "signature",
        "never downloads code",
        "Without a connection",
    ),
    "ru": (
        GAME,
        "неофициальн",
        "не связан с Blizzard Entertainment",  # noqa: RUF001 (Russian text)
        "HTTPS",
        "подпись",
        "код никогда не загружается",
        "Без подключения",
    ),
}
# Statements true only of the OTA OFF build (M5.IOS.1 drafts): the submitted build is OTA ON.
OTA_OFF_CLAIMS = (
    "entirely offline",
    "no network request",
    "makes no request",
    "полностью работает без интернета",
    "не выполняет сетевых запросов",
)
REVIEW_NOTES_REQUIRED = (
    GAME,
    "unofficial",
    "not affiliated with",
    "Blizzard",
    "2.5.2",
    "https://ota.mythweld.com",
    "GET only",
    "ECDSA P-256",
    "SHA-256",
    "never executable code",
    "no demo account",
)
OTHER_PLATFORMS = re.compile(
    r"android|google play|rustore|appgallery|huawei|windows", re.IGNORECASE
)
PLACEHOLDER = re.compile(r"\{\{|\bTODO\b|\bTBD\b|\bFIXME\b", re.IGNORECASE)
WORD = re.compile(r"\w+")

EXIT_PASS, EXIT_FAIL, EXIT_ERROR = 0, 1, 2


class ToolError(Exception):
    """The metadata directory could not be read."""


@dataclass(frozen=True)
class Check:
    id: str
    passed: bool
    detail: str


@dataclass
class Evaluation:
    checks: list[Check] = field(default_factory=list)

    def add(self, check_id: str, passed: bool, detail: str) -> None:
        self.checks.append(Check(check_id, passed, detail))

    @property
    def passed(self) -> bool:
        return bool(self.checks) and all(check.passed for check in self.checks)


def read_field(path: Path) -> tuple[str | None, list[str]]:
    """The field text without its final newline, and the file's format problems."""
    if not path.is_file():
        return None, ["missing"]
    try:
        raw = path.read_bytes().decode("utf-8")
    except UnicodeDecodeError:
        return None, ["not UTF-8"]
    problems: list[str] = []
    if not raw.endswith("\n") or raw.endswith("\n\n"):
        problems.append("must end with exactly one newline")
    text = raw.removesuffix("\n")
    if "\r" in text or "\t" in text:
        problems.append("contains a carriage return or tab")
    if text != text.strip() or any(line != line.rstrip() for line in text.split("\n")):
        problems.append("has leading or trailing blanks")
    if not text.strip():
        problems.append("is empty")
    if PLACEHOLDER.search(text):
        problems.append("contains a placeholder")
    return text, problems


def words(text: str) -> set[str]:
    return {word.casefold() for word in WORD.findall(text)}


def _keyword_problems(keywords: str, name: str, subtitle: str) -> list[str]:
    items = keywords.split(",")
    size = len(keywords.encode("utf-8"))
    indexed = words(name) | words(subtitle)
    problems = []
    if size > KEYWORDS_MAX_BYTES:
        problems.append(f"{size} bytes (limit {KEYWORDS_MAX_BYTES})")
    if any(not item or item != item.strip() for item in items):
        problems.append("an empty keyword or blanks around a comma")
    short = sorted(item for item in items if len(item.strip()) < KEYWORD_MIN_CHARACTERS)
    if short:
        problems.append(f"keywords of two characters or fewer: {short}")
    if len({item.casefold() for item in items}) != len(items):
        problems.append("a keyword is repeated")
    repeated = sorted(item for item in items if item.casefold() in indexed)
    if repeated:
        problems.append(f"repeats the name or subtitle: {repeated}")
    return problems


def evaluate(metadata: Path) -> tuple[Evaluation, dict[str, Any]]:
    """Run every check on the metadata directory; raises ToolError when it is missing."""
    if not metadata.is_dir():
        raise ToolError(f"{metadata} is not a directory")
    evaluation = Evaluation()
    fields: dict[str, str] = {}

    def field_text(relative: str) -> str | None:
        text, problems = read_field(metadata / relative)
        if text is not None:
            fields[relative] = text
        evaluation.add(f"format:{relative}", not problems, "; ".join(problems) or "well formed")
        return None if problems else text

    for locale in LOCALES:
        texts = {name: field_text(f"{locale}/{name}.txt") for name in LOCALE_FILES}
        for name, (low, high) in CHARACTER_LIMITS.items():
            text = texts[name]
            evaluation.add(
                f"limit:{locale}/{name}",
                text is not None and low <= len(text) <= high,
                f"{len(text) if text is not None else 'no'} characters (allowed {low}-{high})",
            )
        keywords = texts["keywords"]
        if keywords is None:
            evaluation.add(f"keywords:{locale}", False, "the keywords are not readable")
        else:
            problems = _keyword_problems(keywords, texts["name"] or "", texts["subtitle"] or "")
            evaluation.add(
                f"keywords:{locale}",
                not problems,
                "; ".join(problems)
                or f"{len(keywords.split(','))} keywords, {len(keywords.encode('utf-8'))} bytes",
            )
        evaluation.add(
            f"name:{locale}",
            texts["name"] == APP_NAME,
            f"name={texts['name']!r} (expected {APP_NAME!r})",
        )
        subtitle = texts["subtitle"] or ""
        evaluation.add(
            f"positioning:{locale}/subtitle",
            GAME_SHORT in subtitle or GAME in subtitle,
            f"the subtitle must name the game ({GAME_SHORT!r}): {subtitle!r}",
        )
        description = texts["description"] or ""
        missing = [
            phrase
            for phrase in DESCRIPTION_REQUIRED[locale]
            if phrase.casefold() not in description.casefold()
        ]
        evaluation.add(
            f"positioning:{locale}/description",
            bool(description) and not missing,
            f"missing {missing}"
            if missing
            else "names the game, the unofficial status, the disclaimer and the OTA facts",
        )
        evaluation.add(
            f"urls:{locale}",
            texts["support_url"] == SUPPORT_URL and texts["privacy_url"] == PRIVACY_URLS[locale],
            f"support={texts['support_url']!r}, privacy={texts['privacy_url']!r} "
            f"(expected {SUPPORT_URL!r}, {PRIVACY_URLS[locale]!r})",
        )

    notes = field_text("review_information/notes.txt")
    notes_bytes = len(notes.encode("utf-8")) if notes is not None else None
    evaluation.add(
        "limit:review_information/notes",
        notes_bytes is not None and notes_bytes <= REVIEW_NOTES_MAX_BYTES,
        f"{notes_bytes if notes_bytes is not None else 'no'} bytes "
        f"(limit {REVIEW_NOTES_MAX_BYTES})",
    )
    missing_notes = [
        phrase for phrase in REVIEW_NOTES_REQUIRED if notes is None or phrase not in notes
    ]
    evaluation.add(
        "review-notes",
        not missing_notes,
        f"missing {missing_notes}"
        if missing_notes
        else "the unofficial status and the OTA mechanism (2.5.2) are explained",
    )
    copyright_text = field_text("copyright.txt")
    evaluation.add(
        "copyright",
        copyright_text is not None and re.fullmatch(r"\d{4} \S.*", copyright_text) is not None,
        f"copyright={copyright_text!r} (expected '<year> <owner>')",
    )
    primary = field_text("primary_category.txt")
    secondary = field_text("secondary_category.txt")
    evaluation.add(
        "categories",
        primary in APP_STORE_CATEGORIES
        and secondary in APP_STORE_CATEGORIES
        and primary != secondary
        and "Games" not in (primary, secondary),
        f"primary={primary!r}, secondary={secondary!r} (two different App Store categories, "
        "not Games: TalentForge is a tool, not a game)",
    )

    ota_off = sorted(
        name
        for name, text in fields.items()
        if any(claim in text.casefold() for claim in OTA_OFF_CLAIMS)
    )
    evaluation.add(
        "ota-on-claims",
        not ota_off,
        f"OTA OFF statements in {ota_off}"
        if ota_off
        else "no text claims that the app makes no network requests",
    )
    platforms = sorted(name for name, text in fields.items() if OTHER_PLATFORMS.search(text))
    evaluation.add(
        "other-platforms",
        not platforms,
        f"another store or platform is named in {platforms}"
        if platforms
        else "no other store or platform is named",
    )

    report: dict[str, Any] = {
        "schema": REPORT_SCHEMA,
        "fields": {
            name: {"characters": len(text), "bytes": len(text.encode("utf-8"))}
            for name, text in sorted(fields.items())
        },
        "checks": [{"id": c.id, "passed": c.passed, "detail": c.detail} for c in evaluation.checks],
        "result": "PASS" if evaluation.passed else "FAIL",
    }
    return evaluation, report


def format_summary(evaluation: Evaluation, report: dict[str, Any]) -> str:
    lines = ["App Store metadata:"]
    lines += [
        f"  {name}: {size['characters']} characters, {size['bytes']} bytes"
        for name, size in report["fields"].items()
    ]
    lines += [f"  [{'PASS' if c.passed else 'FAIL'}] {c.id}: {c.detail}" for c in evaluation.checks]
    lines.append(f"RESULT: {'PASS' if evaluation.passed else 'FAIL'}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0] if __doc__ else None)
    parser.add_argument(
        "--metadata", default=str(DEFAULT_METADATA), help="the submission metadata directory"
    )
    parser.add_argument("--report", help="write the JSON report to this file")
    args = parser.parse_args(argv)
    try:
        evaluation, report = evaluate(Path(args.metadata))
    except ToolError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return EXIT_ERROR
    print(format_summary(evaluation, report))
    if args.report:
        Path(args.report).write_text(
            json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )
    return EXIT_PASS if evaluation.passed else EXIT_FAIL


if __name__ == "__main__":
    sys.exit(main())
