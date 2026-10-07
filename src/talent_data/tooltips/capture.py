"""Import of a raw client tooltip capture into the build-pinned audit artifact.

Flow: SavedVariables wrapper -> base64 -> strict JSON (capture format v1) -> identity
checks against the repository's capture manifest -> exact entry/rank coverage ->
``artifacts/tooltips/<game>/<build>/<locale>/client-capture.json``.

The importer never repairs a capture. Each failure has its own type (``errors.py``).

What is closed and what is open
-------------------------------
The envelope and every record's identity are closed and strict: unknown fields, missing
fields and wrong wire types are rejected. The ``tooltip`` object of a record is the
client's ``TooltipData`` dumped as it was returned; it is raw evidence, so fields this
importer does not know are preserved verbatim, never dropped and never interpreted. Only
the minimum that makes it a tooltip is required (see ``_check_tooltip``).

Semantic hash
-------------
``semanticHash`` is the sha256 of the canonical JSON of the capture identity plus, for
every record in class / entry / rank order, its identity and its tooltip. Excluded as
explicitly non-semantic: ``capturedAt``, ``diagnostics``, each record's ``reads``, and each
tooltip's top-level ``dataInstanceID`` (a per-session handle the client issues to
correlate ``TOOLTIP_DATA_UPDATE``; kept in the artifact, not part of the content).
"""

from __future__ import annotations

import base64
import binascii
import json
import math
from collections import Counter
from collections.abc import Sequence
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError
from pydantic.alias_generators import to_camel

from talent_data.models.talents import content_hash
from talent_data.tooltips.errors import (
    CaptureImportError,
    ClientBuildMismatchError,
    DatasetHashMismatchError,
    DuplicateRecordError,
    GameMismatchError,
    IncompleteCaptureError,
    LocaleMismatchError,
    MalformedPayloadError,
    MalformedTooltipError,
    ManifestHashMismatchError,
    MissingRecordError,
    RankOutOfRangeError,
    RecordIdentityError,
    SemanticHashMismatchError,
    UnknownEntryError,
    UnsupportedCaptureFormatError,
)
from talent_data.tooltips.manifest import CaptureManifest, DatasetIdentity, ManifestEntry
from talent_data.tooltips.saved_variables import parse_saved_variables

CAPTURE_FORMAT_VERSION: Literal[1] = 1
INSTANCE_ID_FIELD = "dataInstanceID"

RecordStatus = Literal["ok", "pending", "unverified", "nil", "empty", "error"]
Scalar = str | int | float | None


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        frozen=True,
        extra="forbid",
        strict=True,
    )


class CaptureDiagnostics(_Model):
    """Facts about the capturing session. Recorded for the audit trail; never semantic."""

    build_date: Scalar
    build_number: Scalar
    build_version: Scalar
    interface_version: Scalar
    passes: int
    player_class_token: Scalar
    player_level: Scalar
    update_event_registered: bool
    update_events: int
    update_events_without_id: int


class _PayloadRecord(_Model):
    class_token: str
    tree_id: int
    node_id: int
    entry_id: int
    spell_id: int
    rank: int
    status: RecordStatus
    reads: int
    error: str | None
    tooltip: dict[str, Any] | None


class _Payload(_Model):
    """Capture format v1, exactly as the addon serialises it."""

    capture_format_version: Literal[1]
    captured_at: str
    client_build: str
    complete: bool
    datasets: list[DatasetIdentity]
    diagnostics: CaptureDiagnostics
    expected_records: int
    game: str
    locale: str
    manifest_hash: str
    records: list[_PayloadRecord]


class CapturedTooltip(_Model):
    class_token: str
    tree_id: int
    node_id: int
    entry_id: int
    spell_id: int
    rank: int
    reads: int = Field(description="How often the addon asked the client. Not semantic.")
    tooltip: dict[str, Any] = Field(description="Raw TooltipData, verbatim. Open by design.")


class ClientTooltipCapture(_Model):
    """The audit artifact: the decoded, validated raw capture of one client and locale."""

    capture_format_version: Literal[1]
    game: str
    client_build: str
    locale: str
    manifest_hash: str
    datasets: list[DatasetIdentity]
    semantic_hash: str
    entry_count: int
    record_count: int
    captured_at: str = Field(description="Diagnostic only. Not semantic.")
    diagnostics: CaptureDiagnostics
    records: list[CapturedTooltip]

    def to_json(self) -> str:
        payload = self.model_dump(mode="json", by_alias=True)
        return json.dumps(payload, ensure_ascii=False, indent=2) + "\n"


# --------------------------------------------------------------------------- strict JSON


def _reject_constant(name: str) -> Any:
    raise ValueError(f"non-finite number {name}")


def _finite_float(text: str) -> float:
    value = float(text)
    if not math.isfinite(value):
        raise ValueError(f"non-finite number {text}")
    return value


def _unique_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate object key {key!r}")
        result[key] = value
    return result


def loads_strict(text: str) -> Any:
    """``json.loads`` without its leniencies: no duplicate keys, no NaN / Infinity."""
    return json.loads(
        text,
        object_pairs_hook=_unique_keys,
        parse_constant=_reject_constant,
        parse_float=_finite_float,
    )


def decode_payload(saved_variables_text: str) -> Any:
    """SavedVariables text -> the payload's JSON value (not yet validated as a capture)."""
    saved = parse_saved_variables(saved_variables_text)
    if saved.chunks is None or saved.payload_bytes is None:
        raise IncompleteCaptureError(
            "the stored record has no payload: the capture was started but never finished"
        )
    try:
        raw = base64.b64decode("".join(saved.chunks), validate=True)
    except (binascii.Error, ValueError) as exc:
        raise MalformedPayloadError(f"payload is not valid base64: {exc}") from exc
    if len(raw) != saved.payload_bytes:
        raise MalformedPayloadError(
            f"payload is {len(raw)} bytes but the record declares {saved.payload_bytes}"
        )
    try:
        return loads_strict(raw.decode("utf-8"))
    except UnicodeDecodeError as exc:
        raise MalformedPayloadError(f"payload is not valid UTF-8: {exc}") from exc
    except ValueError as exc:
        raise MalformedPayloadError(f"payload is not strict JSON: {exc}") from exc


# --------------------------------------------------------------------------- validation


def _check_identity(
    manifest: CaptureManifest,
    *,
    game: str,
    client_build: str,
    locale: str,
    manifest_hash: str,
    datasets: Sequence[DatasetIdentity],
) -> None:
    if game != manifest.game:
        raise GameMismatchError(f"capture is for game {game!r}, expected {manifest.game!r}")
    if client_build != manifest.client_build:
        raise ClientBuildMismatchError(
            f"capture was taken on client build {client_build}; "
            f"exactly {manifest.client_build} is required"
        )
    if locale != manifest.locale:
        raise LocaleMismatchError(
            f"capture was taken in locale {locale}; the canonical capture is {manifest.locale}"
        )
    if manifest_hash != manifest.manifest_hash:
        raise ManifestHashMismatchError(
            f"capture was produced from manifest {manifest_hash}; the repository's manifest "
            f"is {manifest.manifest_hash}. Regenerate the addon manifest and capture again."
        )
    if list(datasets) != list(manifest.datasets):
        expected = {d.class_token: d.content_hash for d in manifest.datasets}
        actual = {d.class_token: d.content_hash for d in datasets}
        differing = sorted(
            token
            for token in expected.keys() | actual.keys()
            if expected.get(token) != actual.get(token)
        )
        raise DatasetHashMismatchError(
            "capture references different talent-data documents than the repository: "
            + (", ".join(differing) or "class order differs")
        )


def _check_tooltip(where: str, tooltip: dict[str, Any]) -> None:
    """The minimum that makes a raw TooltipData a usable tooltip.

    ``lines`` is a non-empty list of objects and at least one line has non-empty
    ``leftText``; the documented scalar fields have their documented wire type when they
    are present. Everything else is kept as the client returned it.
    """

    def typed(owner: dict[str, Any], field: str, kind: type, label: str) -> None:
        if field not in owner:
            return
        value = owner[field]
        # bool is an int in Python; a JSON true is never an acceptable number.
        if not isinstance(value, kind) or (kind is int and isinstance(value, bool)):
            raise MalformedTooltipError(
                f"{where}: {label}{field} is {type(value).__name__}, expected {kind.__name__}"
            )

    for field in ("type", "id", INSTANCE_ID_FIELD):
        typed(tooltip, field, int, "")
    typed(tooltip, "hasDynamicData", bool, "")
    lines = tooltip.get("lines")
    if not isinstance(lines, list) or not lines:
        raise MalformedTooltipError(f"{where}: tooltip has no lines")
    has_text = False
    for index, line in enumerate(lines, start=1):
        if not isinstance(line, dict):
            raise MalformedTooltipError(f"{where}: line {index} is not an object")
        label = f"line {index} "
        typed(line, "type", int, label)
        typed(line, "leftText", str, label)
        typed(line, "rightText", str, label)
        typed(line, "wrapText", bool, label)
        has_text = has_text or bool(line.get("leftText"))
    if not has_text:
        raise MalformedTooltipError(f"{where}: no line has any text")


def _check_coverage(manifest: CaptureManifest, records: Sequence[CapturedTooltip]) -> None:
    """``records`` must be exactly the manifest's (entry, rank) pairs, each once."""
    entries: dict[int, ManifestEntry] = {e.entry_id: e for e in manifest.entries}
    seen: set[tuple[int, int]] = set()
    for record in records:
        entry = entries.get(record.entry_id)
        where = f"entry {record.entry_id} rank {record.rank}"
        if entry is None:
            raise UnknownEntryError(f"{where}: the entry is not in the capture manifest")
        actual = (record.class_token, record.tree_id, record.node_id, record.spell_id)
        expected = (entry.class_token, entry.tree_id, entry.node_id, entry.spell_id)
        if actual != expected:
            raise RecordIdentityError(
                f"{where}: recorded as class/tree/node/spell {actual}, manifest says {expected}"
            )
        if not 1 <= record.rank <= entry.max_rank:
            raise RankOutOfRangeError(f"{where}: rank is outside 1..{entry.max_rank}")
        key = (record.entry_id, record.rank)
        if key in seen:
            raise DuplicateRecordError(f"{where}: captured more than once")
        seen.add(key)
        _check_tooltip(where, record.tooltip)
    missing = [
        (e.entry_id, rank)
        for e in manifest.entries
        for rank in range(1, e.max_rank + 1)
        if (e.entry_id, rank) not in seen
    ]
    if missing:
        shown = ", ".join(f"{entry} rank {rank}" for entry, rank in missing[:5])
        raise MissingRecordError(f"{len(missing)} entry/rank pair(s) were not captured: {shown}")


def _ordered(
    manifest: CaptureManifest, records: Sequence[CapturedTooltip]
) -> list[CapturedTooltip]:
    """Class / entry / rank: the manifest's entry order, then rank ascending."""
    position = {e.entry_id: i for i, e in enumerate(manifest.entries)}
    return sorted(records, key=lambda r: (position[r.entry_id], r.rank))


def semantic_hash(
    manifest: CaptureManifest, records: Sequence[CapturedTooltip], *, client_build: str, locale: str
) -> str:
    return content_hash(
        {
            "captureFormatVersion": CAPTURE_FORMAT_VERSION,
            "game": manifest.game,
            "clientBuild": client_build,
            "locale": locale,
            "manifestHash": manifest.manifest_hash,
            "datasets": [d.model_dump(mode="json", by_alias=True) for d in manifest.datasets],
            "records": [
                {
                    "classToken": r.class_token,
                    "treeId": r.tree_id,
                    "nodeId": r.node_id,
                    "entryId": r.entry_id,
                    "spellId": r.spell_id,
                    "rank": r.rank,
                    "tooltip": {k: v for k, v in r.tooltip.items() if k != INSTANCE_ID_FIELD},
                }
                for r in _ordered(manifest, records)
            ],
        }
    )


def _summarise(error: ValidationError) -> str:
    first = error.errors()[0]
    location = ".".join(str(part) for part in first["loc"])
    more = error.error_count() - 1
    return f"{location}: {first['msg']}" + (f" (+{more} more)" if more else "")


def _require_format_version(raw: Any) -> None:
    if not isinstance(raw, dict):
        raise MalformedPayloadError("payload is not a JSON object")
    version = raw.get("captureFormatVersion")
    if isinstance(version, bool) or not isinstance(version, int):
        raise MalformedPayloadError("captureFormatVersion is missing or not an integer")
    if version != CAPTURE_FORMAT_VERSION:
        raise UnsupportedCaptureFormatError(
            f"unsupported captureFormatVersion {version}; this importer reads "
            f"{CAPTURE_FORMAT_VERSION}"
        )


def _require_complete(payload: _Payload) -> None:
    not_final = [r for r in payload.records if r.status != "ok" or r.tooltip is None]
    if payload.complete and not not_final:
        return
    by_status: Counter[str] = Counter(r.status for r in not_final)
    by_class = Counter(r.class_token for r in not_final)
    detail = ", ".join(f"{status} {count}" for status, count in sorted(by_status.items()))
    classes = ", ".join(f"{token} {count}" for token, count in sorted(by_class.items()))
    raise IncompleteCaptureError(
        f"the capture is incomplete: {len(not_final)} of {len(payload.records)} tooltips are "
        f"not final ({detail or 'the addon did not mark it complete'})"
        + (f"; by class: {classes}" if classes else ""),
        not_final_by_status=by_status,
        not_final_by_class=by_class,
    )


def import_client_capture(
    saved_variables_text: str, manifest: CaptureManifest
) -> ClientTooltipCapture:
    """Validate a SavedVariables capture against ``manifest`` and build the audit artifact."""
    raw = decode_payload(saved_variables_text)
    _require_format_version(raw)
    try:
        payload = _Payload.model_validate(raw)
    except ValidationError as exc:
        raise MalformedPayloadError(f"payload is not a v1 capture: {_summarise(exc)}") from exc

    _check_identity(
        manifest,
        game=payload.game,
        client_build=payload.client_build,
        locale=payload.locale,
        manifest_hash=payload.manifest_hash,
        datasets=payload.datasets,
    )
    _require_complete(payload)
    records = [
        CapturedTooltip(
            class_token=r.class_token,
            tree_id=r.tree_id,
            node_id=r.node_id,
            entry_id=r.entry_id,
            spell_id=r.spell_id,
            rank=r.rank,
            reads=r.reads,
            tooltip=r.tooltip or {},
        )
        for r in payload.records
    ]
    _check_coverage(manifest, records)
    if payload.expected_records != len(records):
        raise MissingRecordError(
            f"the capture declares {payload.expected_records} expected tooltips but holds "
            f"{len(records)}"
        )
    return ClientTooltipCapture(
        capture_format_version=CAPTURE_FORMAT_VERSION,
        game=payload.game,
        client_build=payload.client_build,
        locale=payload.locale,
        manifest_hash=payload.manifest_hash,
        datasets=list(payload.datasets),
        semantic_hash=semantic_hash(
            manifest, records, client_build=payload.client_build, locale=payload.locale
        ),
        entry_count=len(manifest.entries),
        record_count=len(records),
        captured_at=payload.captured_at,
        diagnostics=payload.diagnostics,
        records=_ordered(manifest, records),
    )


def read_client_capture(path: Path, manifest: CaptureManifest) -> ClientTooltipCapture:
    """Load a stored audit artifact and re-verify it against ``manifest`` and its own hash."""
    try:
        raw = loads_strict(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise CaptureImportError(f"cannot read client capture {path}: {exc}") from exc
    _require_format_version(raw)
    try:
        capture = ClientTooltipCapture.model_validate(raw)
    except ValidationError as exc:
        raise MalformedPayloadError(
            f"{path} is not a v1 client capture: {_summarise(exc)}"
        ) from exc
    _check_identity(
        manifest,
        game=capture.game,
        client_build=capture.client_build,
        locale=capture.locale,
        manifest_hash=capture.manifest_hash,
        datasets=capture.datasets,
    )
    _check_coverage(manifest, capture.records)
    expected = semantic_hash(
        manifest, capture.records, client_build=capture.client_build, locale=capture.locale
    )
    if capture.semantic_hash != expected:
        raise SemanticHashMismatchError(
            f"{path}: semanticHash is {capture.semantic_hash}, content hashes to {expected}"
        )
    return capture
