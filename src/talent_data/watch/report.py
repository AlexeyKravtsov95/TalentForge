"""Strict reader of the freshness check's result: its exit code and its JSON document.

``scripts/check_product_freshness.py`` exits 0 / 3 / 4 for CURRENT / OUTDATED / UNKNOWN
and writes one ``product-freshness`` v1 document. Anything else - another exit code, a
missing or unparsable document, an unknown field, a wrong wire type, another schema
version, or a document that contradicts its own status or exit code - is a technical
failure of the check (:class:`CheckFailure`), never a freshness answer.
"""

from __future__ import annotations

import re
from datetime import datetime
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, ValidationError
from pydantic.alias_generators import to_camel

from talent_data.cli.main import FRESHNESS_EXIT
from talent_data.freshness import FreshnessStatus
from talent_data.versions import ClientVersion

EXPECTED_GAME = "forever"
_CHECKED_AT_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
_CREATED_AT_FORMAT = "%Y-%m-%d %H:%M:%S"


class CheckFailure(RuntimeError):
    """The freshness check did not produce a trustworthy result (watcher failure)."""


class _Strict(BaseModel):
    # Closed contract: unknown fields and coerced wire types are rejected. Read with
    # ``model_validate_json`` so enum and literal values are taken from the JSON wire.
    model_config = ConfigDict(alias_generator=to_camel, strict=True, extra="forbid", frozen=True)


class BuildFact(_Strict):
    version: str
    product: str
    created_at: str
    # Required on the wire, ``null`` when the source did not say.
    is_bgdl: bool | None


class FreshnessDocument(_Strict):
    schema_version: Literal[1]
    kind: Literal["product-freshness"]
    game: str
    status: FreshnessStatus
    product_build: str
    latest_publishable: BuildFact | None
    latest_detected: BuildFact | None
    newer_unpublishable: tuple[BuildFact, ...]
    reason: str
    source: str
    checked_at: str


def parse_document(text: str) -> FreshnessDocument:
    """Parse and cross-check one ``product-freshness`` v1 document."""
    try:
        document = FreshnessDocument.model_validate_json(text)
    except ValidationError as exc:
        raise CheckFailure(f"not a valid product-freshness v1 document: {exc}") from exc
    _check_consistency(document)
    return document


def read_result(path: Path, exit_code: int) -> FreshnessDocument:
    """The check's result, or :class:`CheckFailure` when it cannot be trusted."""
    expected = {code: status for status, code in FRESHNESS_EXIT.items()}
    if exit_code not in expected:
        raise CheckFailure(
            f"the freshness check exited with {exit_code}, which is not a freshness status "
            f"(expected {sorted(expected)}): a failure of the check, not CURRENT/OUTDATED/UNKNOWN"
        )
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise CheckFailure(f"the freshness report {path} could not be read: {exc}") from exc
    document = parse_document(text)
    if document.status is not expected[exit_code]:
        raise CheckFailure(
            f"the report says {document.status} but the check exited with {exit_code} "
            f"({expected[exit_code]})"
        )
    return document


def version(text: str) -> ClientVersion:
    """A canonical ``a.b.c.build`` version (no whitespace, no leading zeros)."""
    try:
        parsed = ClientVersion.parse(text)
    except ValueError as exc:
        raise CheckFailure(str(exc)) from exc
    if str(parsed) != text:
        raise CheckFailure(f"not a canonical client version: {text!r}")
    return parsed


def _check_consistency(document: FreshnessDocument) -> None:
    if document.game != EXPECTED_GAME:
        raise CheckFailure(f"the report is for game {document.game!r}, not {EXPECTED_GAME!r}")
    if not document.reason.strip():
        raise CheckFailure("the report has an empty reason")
    if not document.source.strip():
        raise CheckFailure("the report has an empty source")
    if not _CHECKED_AT_RE.match(document.checked_at):
        raise CheckFailure(f"checkedAt is not a UTC timestamp: {document.checked_at!r}")
    try:
        datetime.strptime(document.checked_at, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as exc:
        raise CheckFailure(f"checkedAt is not a valid date: {document.checked_at!r}") from exc
    product = version(document.product_build)
    facts = [document.latest_publishable, document.latest_detected]
    for fact in [*facts, *document.newer_unpublishable]:
        if fact is not None:
            version(fact.version)
            try:
                datetime.strptime(fact.created_at, _CREATED_AT_FORMAT)
            except ValueError as exc:
                raise CheckFailure(f"createdAt is not a timestamp: {fact.created_at!r}") from exc

    publishable = document.latest_publishable
    if document.status is FreshnessStatus.UNKNOWN:
        return
    if publishable is None:
        raise CheckFailure(f"a {document.status} report must name its latest publishable build")
    if publishable.is_bgdl is not False:
        raise CheckFailure(
            f"the latest publishable build {publishable.version} does not have is_bgdl=false"
        )
    latest = version(publishable.version)
    if document.status is FreshnessStatus.CURRENT and latest != product:
        raise CheckFailure(
            f"a CURRENT report must have latestPublishable == productBuild "
            f"({publishable.version} != {document.product_build})"
        )
    if document.status is FreshnessStatus.OUTDATED and not latest > product:
        raise CheckFailure(
            f"an OUTDATED report must have latestPublishable > productBuild "
            f"({publishable.version} <= {document.product_build})"
        )
