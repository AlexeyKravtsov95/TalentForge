"""The tracked freshness Issue: its machine state and the open / update / close decision.

One GitHub Issue at a time tracks the product dataset's freshness. It is found by the
hidden :data:`MARKER` in its description - never by its title, which people may edit -
and carries its own state as a hidden JSON comment (:data:`STATE_PREFIX`), so every run
decides from the Issue itself, without any other storage.

:func:`decide` is pure: given the check's document, the run URL and the open tracked
Issue's description (or ``None``), it returns the :class:`Plan` to apply. The rules:

* ``CURRENT``  - nothing to do without an open Issue; otherwise comment and close it.
* ``OUTDATED`` - open an Issue; or, when the product build or the latest publishable build
  differs from the tracked one, update it (title, description, history) and comment once.
  The same facts again change nothing.
* ``UNKNOWN``  - never closes anything. Open an Issue; or record the failure on the open
  one (an OUTDATED Issue keeps its findings and title) and comment once per distinct
  reason. The same reason again changes nothing.

A description whose state cannot be read (edited by hand) is rebuilt from the current
check, with a comment saying so; the Issue is not duplicated.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, ConfigDict, ValidationError
from pydantic.alias_generators import to_camel

from talent_data.freshness import FreshnessStatus
from talent_data.watch.report import BuildFact, FreshnessDocument

MARKER = "<!-- talentforge:forever-dataset-freshness -->"
STATE_PREFIX = "<!-- talentforge:freshness-state "
_STATE_RE = re.compile(re.escape(STATE_PREFIX) + r"(.*?) -->", re.DOTALL)
HISTORY_LIMIT = 50

NOT_LIVE_NOTE = (
    "`is_bgdl=false` only means the build is not a background download. It is the "
    "project's publishability condition, **not** proof that the realms run this build."
)


class _Strict(BaseModel):
    # Written by this module, read back from the Issue: camelCase on the wire, built by
    # field name in Python.
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, strict=True, extra="forbid", frozen=True
    )


class CheckSnapshot(_Strict):
    report: FreshnessDocument
    run_url: str


class HistoryEntry(_Strict):
    checked_at: str
    status: FreshnessStatus
    note: str
    run_url: str


class TrackingState(_Strict):
    state_version: Literal[1]
    # The OUTDATED check that established the tracked build (``None``: nothing proven).
    outdated: CheckSnapshot | None
    # The latest UNKNOWN check since then (``None``: the last check was conclusive).
    unknown: CheckSnapshot | None
    history: tuple[HistoryEntry, ...]


class Action(StrEnum):
    NONE = "none"
    CREATE = "create"
    UPDATE = "update"
    CLOSE = "close"


@dataclass(frozen=True)
class Plan:
    action: Action
    # One line for logs and the job summary.
    summary: str
    title: str | None = None
    body: str | None = None
    comment: str | None = None


_EMPTY = TrackingState(state_version=1, outdated=None, unknown=None, history=())


# --------------------------------------------------------------------------- decision


def decide(report: FreshnessDocument, run_url: str, issue_body: str | None) -> Plan:
    """What to do with the tracked Issue (``issue_body``: the open one, if any)."""
    check = CheckSnapshot(report=report, run_url=run_url)
    status = report.status
    if issue_body is None:
        if status is FreshnessStatus.CURRENT:
            return Plan(Action.NONE, f"CURRENT: {report.product_build} is up to date; no issue")
        title, body = _render(_append(_EMPTY, check))
        return Plan(Action.CREATE, f"{status}: open the tracking issue", title, body)

    if status is FreshnessStatus.CURRENT:
        return Plan(
            Action.CLOSE, "CURRENT: close the tracking issue", comment=_close_comment(check)
        )

    state = read_state(issue_body)
    if state is None:
        title, body = _render(_append(_EMPTY, check))
        comment = (
            "The tracking state in this issue's description could not be read (was it "
            "edited by hand?). It was rebuilt from this check; earlier history remains in "
            "the comments.\n\n" + _status_comment(None, check)
        )
        return Plan(Action.UPDATE, f"{status}: rebuild the unreadable state", title, body, comment)

    if status is FreshnessStatus.OUTDATED:
        previous = state.outdated
        if previous is not None and _same_outdated(previous.report, report):
            if state.unknown is None:
                return Plan(Action.NONE, f"OUTDATED: unchanged ({_pair(report)})")
            # The check works again with the same findings: clear the failure note quietly.
            title, body = _render(state.model_copy(update={"unknown": None}))
            return Plan(Action.UPDATE, "OUTDATED: unchanged; clear the UNKNOWN note", title, body)
        title, body = _render(_append(state, check))
        return Plan(
            Action.UPDATE,
            f"OUTDATED: tracked facts changed ({_pair(report)})",
            title,
            body,
            _status_comment(state, check),
        )

    # UNKNOWN
    previous = state.unknown
    if (
        previous is not None
        and previous.report.reason == report.reason
        and previous.report.product_build == report.product_build
    ):
        return Plan(Action.NONE, "UNKNOWN: same reason as already reported")
    title, body = _render(_append(state, check))
    return Plan(
        Action.UPDATE, "UNKNOWN: record the new reason", title, body, _status_comment(state, check)
    )


def _same_outdated(a: FreshnessDocument, b: FreshnessDocument) -> bool:
    return a.product_build == b.product_build and _latest(a) == _latest(b)


def _latest(report: FreshnessDocument) -> str | None:
    return None if report.latest_publishable is None else report.latest_publishable.version


def _pair(report: FreshnessDocument) -> str:
    return f"{report.product_build} -> {_latest(report)}"


def _append(state: TrackingState, check: CheckSnapshot) -> TrackingState:
    """``state`` after ``check`` (OUTDATED or UNKNOWN), with one more history entry."""
    report = check.report
    if report.status is FreshnessStatus.OUTDATED:
        note = f"publishable {_latest(report)} is newer than the product {report.product_build}"
        update: dict[str, object] = {"outdated": check, "unknown": None}
    else:
        note = report.reason
        update = {"unknown": check}
    entry = HistoryEntry(
        checked_at=report.checked_at, status=report.status, note=note, run_url=check.run_url
    )
    # Bounded: an issue description holds at most 65536 characters; comments keep the rest.
    update["history"] = (*state.history, entry)[-HISTORY_LIMIT:]
    return state.model_copy(update=update)


# --------------------------------------------------------------------------- state I/O


def read_state(body: str) -> TrackingState | None:
    """The state stored in a tracked Issue's description, or ``None`` if unreadable."""
    found = _STATE_RE.findall(body)
    if len(found) != 1:
        return None
    try:
        state = TrackingState.model_validate_json(found[0])
    except ValidationError:
        return None
    if state.outdated is None and state.unknown is None:
        return None
    return state


def _state_comment(state: TrackingState) -> str:
    # ``<`` / ``>`` / ``@`` only occur inside JSON strings: escaping them keeps ``-->`` and
    # mentions out of the description.
    raw = (
        state.model_dump_json(by_alias=True)
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("@", "\\u0040")
    )
    return f"{STATE_PREFIX}{raw} -->"


# --------------------------------------------------------------------------- rendering


def _render(state: TrackingState) -> tuple[str, str]:
    return _title(state), _body(state)


def _build_number(version: str | None) -> str:
    return "?" if version is None else version.rsplit(".", 1)[-1]


def _title(state: TrackingState) -> str:
    if state.outdated is not None:
        report = state.outdated.report
        return (
            f"Forever dataset outdated: {_build_number(report.product_build)} "
            f"→ {_build_number(_latest(report))}"
        )
    assert state.unknown is not None
    product = _build_number(state.unknown.report.product_build)
    return f"Forever dataset freshness unknown: {product} could not be verified"


def _text(value: str) -> str:
    """Untrusted text as inert Markdown: no HTML/comments, mentions, tables or code breaks."""
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("@", "&#64;")
        .replace("|", "\\|")
        .replace("`", "'")
        .replace("\r", " ")
        .replace("\n", " ")
    )


def _fact(fact: BuildFact | None) -> str:
    if fact is None:
        return "—"
    bgdl = {True: "true", False: "false", None: "unknown"}[fact.is_bgdl]
    return f"`{fact.version}` (wago `created_at` {_text(fact.created_at)}, `is_bgdl={bgdl}`)"


def _facts_table(check: CheckSnapshot) -> list[str]:
    report = check.report
    newer = ", ".join(_fact(f) for f in report.newer_unpublishable) or "—"
    return [
        "| | |",
        "| --- | --- |",
        f"| Current `PRODUCT_BUNDLE` | `{report.product_build}` |",
        f"| Latest confirmed publishable build | {_fact(report.latest_publishable)} |",
        f"| Latest detected build | {_fact(report.latest_detected)} |",
        f"| Newer non-publishable builds | {newer} |",
        f"| Status | `{report.status}` |",
        f"| Reason | {_text(report.reason)} |",
        f"| Checked at (UTC) | {report.checked_at} |",
        f"| Data source | {_text(report.source)} build index (`GET /api/builds`) |",
        f"| Workflow run | {check.run_url} |",
    ]


def _refresh_steps(product: str, new: str) -> list[str]:
    return [
        "### How to update the app dataset (M5.DATA refresh)",
        "",
        "Nothing was changed automatically: `PRODUCT_BUNDLE`, `BUNDLED_BUILDS`, the normalized "
        "datasets and saved builds stay as they are until a developer decides.",
        "",
        f"1. Re-run `python scripts/check_product_freshness.py` and confirm `{new}` is still "
        "the latest publishable build.",
        f"2. Fetch, normalize and diff: `python -m talent_data fetch --game forever --build "
        f"{new}`, `python -m talent_data normalize --game forever --build {new} --class all`, "
        f"`python -m talent_data diff --game forever --class all --from {product} --to {new}`.",
        "3. Review the diff and the allocation compatibility of saved builds (conformance, "
        "`docs/talent-allocation-semantics.md`).",
        f"4. Publish icons and descriptions: `python -m talent_data visuals fetch --build {new}` "
        f"and `python -m talent_data tooltips fetch --build {new}`, then their "
        "`normalize` / `check`.",
        f"5. Add `{new}` to `BUNDLED_BUILDS` (keep `{product}` and older builds for saved "
        "builds), switch `PRODUCT_BUNDLE` in `BundledDatasets.kt`, run "
        "`scripts/sync_mobile_datasets.py`, `sync_mobile_visuals.py`, `sync_mobile_tooltips.py` "
        "and their `--check`.",
        "6. Run the Python and mobile validation baseline, open the refresh PR, review the "
        "diff and merge it manually.",
        "7. The next watcher run then reports `CURRENT` and closes this issue.",
        "",
        "Details: `docs/dataset-freshness-watch.md`, `docs/forever-dataset.md`.",
    ]


_UNKNOWN_STEPS = [
    "### What to do",
    "",
    "1. Read the reason above and the workflow run's log and `freshness-report` artifact.",
    "2. Re-run the workflow (Actions → Dataset freshness → Run workflow) or run "
    "`python scripts/check_product_freshness.py` locally.",
    "3. If the source keeps failing, check wago.tools availability and its `/api/builds` "
    "schema; do not treat the product dataset as current meanwhile.",
    "4. The issue closes automatically on the next `CURRENT` result.",
]


def _body(state: TrackingState) -> str:
    lines = [MARKER]
    if state.outdated is not None:
        report = state.outdated.report
        lines += [
            "## Forever dataset outdated",
            "",
            "A publishable Forever build newer than the app's product dataset was detected.",
            "",
            *_facts_table(state.outdated),
            "",
            f"> {NOT_LIVE_NOTE}",
            "",
        ]
        if state.unknown is not None:
            lines += [
                "### Latest check could not be verified (UNKNOWN)",
                "",
                "The findings above stay in force; this issue stays open.",
                "",
                *_facts_table(state.unknown),
                "",
            ]
        lines += [*_refresh_steps(report.product_build, _latest(report) or "?"), ""]
    else:
        assert state.unknown is not None
        lines += [
            "## Forever dataset freshness could not be verified",
            "",
            "The product dataset is **not** confirmed current: the freshness check returned "
            "`UNKNOWN`.",
            "",
            *_facts_table(state.unknown),
            "",
            f"> {NOT_LIVE_NOTE}",
            "",
            *_UNKNOWN_STEPS,
            "",
        ]
    lines += ["### History", "", "| Checked at (UTC) | Status | Details |", "| --- | --- | --- |"]
    lines += [
        f"| [{e.checked_at}]({e.run_url}) | `{e.status}` | {_text(e.note)} |" for e in state.history
    ]
    lines += ["", _state_comment(state), ""]
    return "\n".join(lines)


# --------------------------------------------------------------------------- comments


def _status_comment(previous: TrackingState | None, check: CheckSnapshot) -> str:
    report = check.report
    if report.status is FreshnessStatus.OUTDATED:
        before = None if previous is None else previous.outdated
        if before is None:
            head = (
                f"A newer publishable build was detected: `{_latest(report)}` "
                f"(product `{report.product_build}`)."
            )
        else:
            head = (
                f"The tracked facts changed: product `{before.report.product_build}` → "
                f"`{report.product_build}`, latest publishable `{_latest(before.report)}` → "
                f"`{_latest(report)}`."
            )
        return f"{head} The description and title were updated.\n\nRun: {check.run_url}"
    keep = (
        " The OUTDATED findings stay in force; this issue stays open."
        if previous is not None and previous.outdated is not None
        else ""
    )
    return (
        f"Freshness could not be verified (`UNKNOWN`) at {report.checked_at}: "
        f"{_text(report.reason)}.{keep}\n\nRun: {check.run_url}"
    )


def _close_comment(check: CheckSnapshot) -> str:
    report = check.report
    pending = ""
    if report.newer_unpublishable:
        builds = ", ".join(f"`{f.version}`" for f in report.newer_unpublishable)
        pending = f" Newer non-publishable builds (not updates): {builds}."
    return (
        f"Freshness verified at {report.checked_at} (UTC): `PRODUCT_BUNDLE` "
        f"`{report.product_build}` is the latest publishable Forever build "
        f"{_text(report.source)} reports (`is_bgdl=false`).{pending}\n\n"
        "This confirms publishability only, not that the realms run this build. "
        f"Closing this tracking issue.\n\nRun: {check.run_url}"
    )
