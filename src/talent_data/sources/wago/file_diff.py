"""Complete, build-pinned Wago CASC diffs; absence from a partial page proves nothing."""

from __future__ import annotations

import json
from collections import Counter
from dataclasses import dataclass
from functools import partial
from html.parser import HTMLParser
from pathlib import Path
from typing import Any

from talent_data.sources.base import SourceError
from talent_data.sources.http import HttpClient, is_valid_cache_entry
from talent_data.sources.wago.source import WAGO_BASE_URL
from talent_data.versions import ClientVersion


class _Page(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.documents: list[object] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        for key, value in attrs:
            if key == "data-page" and value is not None:
                self.documents.append(json.loads(value))


def _parse_page(raw: bytes, base: str, target: str, page: int) -> dict[str, Any]:
    """Validate the identity and pagination before trusting any FDID absence."""
    parser = _Page()
    parser.feed(raw.decode("utf-8"))
    if len(parser.documents) != 1:
        raise ValueError("expected one Inertia page")
    document = parser.documents[0]
    if not isinstance(document, dict):
        raise TypeError("invalid page")
    if document.get("component") != "Builds/Diff":
        raise ValueError("unexpected page component")
    props = document["props"]
    errors = props.get("errors", {})
    if not isinstance(errors, dict) or errors:
        raise ValueError("upstream diff reports errors; equality is not established")
    if props.get("branch") is not None or set(props["filters"]) - {"from", "to", "page"}:
        raise ValueError("filtered diffs cannot prove equality")
    if props["fromVersion"] != base or props["toVersion"] != target:
        raise ValueError("build identity differs")
    items, counts = props["items"], props["counts"]
    for key in ("current_page", "last_page", "per_page", "total"):
        if type(items[key]) is not int or items[key] < (0 if key == "total" else 1):
            raise ValueError(f"invalid {key}")
    if items["current_page"] != page or items["last_page"] > 10000:
        raise ValueError("pagination identity differs")
    if set(counts) != {"added", "removed", "changed"} or any(
        type(n) is not int or n < 0 for n in counts.values()
    ):
        raise ValueError("invalid action counts")
    if sum(counts.values()) != items["total"] or not isinstance(items["data"], list):
        raise ValueError("inconsistent total")
    if items["last_page"] != max(1, (items["total"] + items["per_page"] - 1) // items["per_page"]):
        raise ValueError("invalid page count")
    if len(items["data"]) != min(
        items["per_page"], max(0, items["total"] - (page - 1) * items["per_page"])
    ):
        raise ValueError("incomplete page")
    for row in items["data"]:
        if set(row) != {"Action", "FDID", "Filename", "Type"}:
            raise ValueError("unknown diff row fields")
        if row["Action"] not in {"added", "removed", "modified"}:
            raise ValueError("unknown file action")
        if type(row["FDID"]) is not int or row["FDID"] <= 0:
            raise ValueError("invalid FileDataID")
        if any(row[k] is not None and not isinstance(row[k], str) for k in ("Filename", "Type")):
            raise ValueError("invalid file metadata")
    return {"items": items, "counts": counts}


def parse_page(raw: bytes, base: str, target: str, page: int) -> dict[str, Any]:
    try:
        result = _parse_page(raw, base, target, page)
    except (ValueError, KeyError, TypeError, AttributeError) as exc:
        raise SourceError(f"CASC_DIFF_INVALID {base} -> {target} page {page}: {exc}") from exc
    else:
        return result


def _validate_page(path: Path, *, base: str, target: str, page: int) -> None:
    parse_page(path.read_bytes(), base, target, page)


@dataclass(frozen=True)
class FileDiff:
    changed: frozenset[int]
    pages: tuple[Path, ...]


def load_diff(
    http: HttpClient | None, cache: Path, base: ClientVersion, target: ClientVersion
) -> FileDiff:
    """Successful pages survive retries/reruns. Never infer equality from FileDataID alone."""
    if base >= target:
        raise SourceError("CASC_DIFF_INVALID: reuse base must precede target")
    rows: dict[int, str] = {}
    pages: list[Path] = []
    expected: tuple[int, int, int, dict[str, int]] | None = None
    page = 1
    while True:
        path = cache / str(target) / "file-diff" / str(base) / f"{page}.html"
        url = f"{WAGO_BASE_URL}/builds-diff?from={base}&to={target}&page={page}"
        if http is not None:
            http.download_file(
                url,
                path,
                validate=partial(_validate_page, base=str(base), target=str(target), page=page),
            )
        if not is_valid_cache_entry(path):
            raise SourceError(f"CASC_DIFF_MISSING: {url}; no verified cached page")
        meta = json.loads(path.with_name(path.name + ".meta.json").read_bytes())
        if meta["url"] != url:
            raise SourceError(f"CASC_DIFF_INVALID: cache URL differs for {path}")
        data = parse_page(path.read_bytes(), str(base), str(target), page)
        items, counts = data["items"], data["counts"]
        assert isinstance(items, dict) and isinstance(counts, dict)
        identity = (items["last_page"], items["per_page"], items["total"], counts)
        if expected is None:
            expected = identity
        elif expected != identity:
            raise SourceError("CASC_DIFF_INVALID: pages disagree about totals")
        for row in items["data"]:
            if row["FDID"] in rows:
                raise SourceError("CASC_DIFF_INVALID: duplicate FileDataID across pages")
            rows[row["FDID"]] = row["Action"]
        pages.append(path)
        if page == items["last_page"]:
            break
        page += 1
    actual = Counter(rows.values())
    if len(rows) != expected[2] or any(
        actual[action] != counts[key]
        for action, key in (("added", "added"), ("removed", "removed"), ("modified", "changed"))
    ):
        raise SourceError("CASC_DIFF_INCOMPLETE: row/action counts differ; reuse forbidden")
    return FileDiff(frozenset(rows), tuple(pages))
