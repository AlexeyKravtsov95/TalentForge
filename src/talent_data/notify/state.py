"""Durable per-event deduplication state and the pure decisions that use it.

Three small strict-JSON documents, one per producer, live on the branch
``telegram-notify-state`` (see :mod:`talent_data.notify.gitstate`) - not in the Actions
cache and not in run artifacts, so eviction and retention never bring a notification back:

* ``freshness.json`` - the build whose detection was already notified (this is what keeps
  a daily ``OUTDATED`` answer about the same build, e.g. ``1.60.1.70532`` while
  ``PRODUCT_BUNDLE`` stays ``1.60.1.70450``, from re-notifying every day);
* ``candidate.json`` - the release ids whose ``PREPARED`` candidates were notified;
* ``production.json`` - the active release id whose activation was notified (a re-signed
  catalog or a sequence bump with the same active release is not a release).

Failures are deduplicated per stage: one fingerprint per ``<producer>/<stage>``. The same
broken stage stays silent until a conclusive run of the same producer clears the
fingerprint, so a failure that repeats daily notifies once, and a *new* episode (after a
recovery) notifies again. An expected freshness ``OUTDATED`` never enters this table.

Every :func:`decide_*` is pure: facts in, events and the next state out. The CLI drops the
events and keeps the state when the state file did not exist yet - the first run records
the baseline (including a failure fingerprint for an already-broken stage) and sends
nothing old.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, ValidationError
from pydantic.alias_generators import to_camel

from talent_data.freshness import FreshnessStatus
from talent_data.notify.events import (
    BuildDetected,
    CandidatePrepared,
    Event,
    ProductionUpdated,
    StageFailed,
)
from talent_data.notify.production_watch import ProductionObservation
from talent_data.prepare.production import ProductionStatus
from talent_data.prepare.propose import PreparationReport
from talent_data.watch.report import FreshnessDocument

#: stage fingerprints (one per producer/stage; never per reason - reasons vary daily)
FP_FRESHNESS_CHECK = "freshness/check"
FP_FRESHNESS_UNKNOWN = "freshness/unknown"
FP_PREPARE_DETECT = "ota-prepare/detect"
FP_PREPARE_RUN = "ota-prepare/prepare"
FP_PREPARE_PROPOSE = "ota-prepare/propose"
FP_PRODUCTION_CATALOG = "ota-production-watch/catalog"
FP_PRODUCTION_MANIFEST = "ota-production-watch/manifest"

STAGE_LABELS = {
    FP_FRESHNESS_CHECK: "dataset freshness: технический сбой проверки",
    FP_FRESHNESS_UNKNOWN: "dataset freshness: ответ UNKNOWN",
    FP_PREPARE_DETECT: "ota-prepare: detect",
    FP_PREPARE_RUN: "ota-prepare: prepare",
    FP_PREPARE_PROPOSE: "ota-prepare: propose (PR кандидата)",
    FP_PRODUCTION_CATALOG: "production watch: публичный production-каталог",
    FP_PRODUCTION_MANIFEST: "production watch: манифест активного релиза",
}

_NOTIFIED_LIMIT = 20
_FAILURES_LIMIT = 50
#: undelivered candidate events kept for retry (a safety bound; in practice one or two)
_PENDING_LIMIT = 8


class _Strict(BaseModel):
    # Written by this module, read back on the next run: camelCase on the wire, strict,
    # no unknown field, no coerced type.
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, strict=True, extra="forbid", frozen=True
    )


class FreshnessDedup(_Strict):
    state_version: Literal[1]
    kind: Literal["notify-freshness-state"]
    notified_build: str | None
    failures_notified: tuple[str, ...] = ()
    updated_at: str


class PendingCandidate(_Strict):
    """The durable "due, not yet delivered" marker of one CandidatePrepared event: the
    exact facts the message is (re-)rendered from. Every undelivered candidate stays
    here - a newer one never replaces an older one - and :func:`confirm_candidate_event`
    removes exactly the entry whose message was delivered."""

    release_id: str
    build: str
    base: str
    steps_passed: int
    steps_total: int
    gates_passed: tuple[str, ...]
    gates_required: tuple[str, ...]
    pr_url: str | None
    artifact_name: str
    run_url: str


class CandidateDedup(_Strict):
    state_version: Literal[1]
    kind: Literal["notify-candidate-state"]
    notified: tuple[str, ...] = ()
    failures_notified: tuple[str, ...] = ()
    pending: tuple[PendingCandidate, ...] = ()
    updated_at: str


class ProductionDedup(_Strict):
    state_version: Literal[1]
    kind: Literal["notify-production-state"]
    notified_release: str | None = None
    failures_notified: tuple[str, ...] = ()
    updated_at: str


class StateError(RuntimeError):
    """The deduplication state file exists but is not a valid state document."""


def load_dedup[State: (FreshnessDedup, CandidateDedup, ProductionDedup)](
    model: type[State], path: Path
) -> State | None:
    """The state document, or ``None`` when the file does not exist (the first run)."""
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return None
    try:
        return model.model_validate_json(text)  # type: ignore[return-value,unused-ignore]
    except ValidationError as exc:
        raise StateError(
            f"{path} is not a valid {model.model_fields['kind'].default} document: {exc}"
        ) from exc


def save_dedup[State: (FreshnessDedup, CandidateDedup, ProductionDedup)](
    path: Path, state: State
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(state.model_dump_json(by_alias=True, indent=2) + "\n", encoding="utf-8")


@dataclass(frozen=True)
class Decision[State]:
    events: tuple[Event, ...]
    state: State


def baseline[State](decision: Decision[State]) -> Decision[State]:
    """The first run: keep the recorded state, send none of the events."""
    return Decision((), decision.state)


def _add(state: tuple[str, ...], fingerprint: str) -> tuple[str, ...]:
    return (*state, fingerprint) if fingerprint not in state else state


def _drop(state: tuple[str, ...], *fingerprints: str) -> tuple[str, ...]:
    return tuple(f for f in state if f not in fingerprints)


def _stage_failed(
    fingerprint: str, reason: str, run_url: str, already_notified: bool
) -> StageFailed | None:
    if already_notified:
        return None
    return StageFailed(
        stage=STAGE_LABELS[fingerprint], reason=reason, run_url=run_url, fingerprint=fingerprint
    )


# ---------------------------------------------------------------------- freshness


def initial_freshness(now: str) -> FreshnessDedup:
    return FreshnessDedup(
        state_version=1, kind="notify-freshness-state", notified_build=None, updated_at=now
    )


def decide_freshness(
    state: FreshnessDedup,
    *,
    document: FreshnessDocument | None,
    check_failure: str | None,
    issue_url: str | None,
    run_url: str,
    now: str,
) -> Decision[FreshnessDedup]:
    """One freshness answer. ``OUTDATED`` is the expected answer about a newer build, not
    a failure: its build notifies once (:attr:`FreshnessDedup.notified_build` is what keeps
    the same build from re-notifying every day). ``UNKNOWN`` and a technical check failure
    notify once per episode; any conclusive answer clears both fingerprints."""
    events: list[Event] = []
    failures = state.failures_notified

    if check_failure is not None:
        event = _stage_failed(
            FP_FRESHNESS_CHECK, check_failure, run_url, FP_FRESHNESS_CHECK in failures
        )
        if event is not None:
            events.append(event)
        return Decision(
            tuple(events),
            _freshness(state.notified_build, _add(failures, FP_FRESHNESS_CHECK), now),
        )

    assert document is not None, "a conclusive run always carries its document"
    if document.status is FreshnessStatus.UNKNOWN:
        event = _stage_failed(
            FP_FRESHNESS_UNKNOWN, document.reason, run_url, FP_FRESHNESS_UNKNOWN in failures
        )
        if event is not None:
            events.append(event)
        return Decision(
            tuple(events),
            _freshness(state.notified_build, _add(failures, FP_FRESHNESS_UNKNOWN), now),
        )

    failures = _drop(failures, FP_FRESHNESS_CHECK, FP_FRESHNESS_UNKNOWN)
    build = document.latest_publishable  # never None for OUTDATED (the strict reader)
    if document.status is FreshnessStatus.OUTDATED and build is not None:
        if build.version != state.notified_build:
            events.append(
                BuildDetected(
                    build=build.version,
                    source=document.source,
                    product_build=document.product_build,
                    issue_url=issue_url,
                    run_url=run_url,
                )
            )
        return Decision(tuple(events), _freshness(build.version, failures, now))
    return Decision((), _freshness(state.notified_build, failures, now))


# ---------------------------------------------------------------------- candidate


def initial_candidate(now: str) -> CandidateDedup:
    return CandidateDedup(state_version=1, kind="notify-candidate-state", updated_at=now)


def candidate_reason(outcome: str, report: PreparationReport | None) -> str:
    """A short, typed reason for a broken preparation, from its own documents."""
    if outcome == "DETECT_UNKNOWN":
        return "детекция завершилась без ответа (UNKNOWN): индекс билдов или каталог нечитаемы"
    if outcome == "DETECT_FAILED":
        return "сбой детекции кандидата (подробнее в артефакте запуска)"
    if report is not None:
        failed = next((s for s in report.steps if s.outcome.value != "PASSED"), None)
        blocker = next((f for f in report.findings if f.kind.value == "BLOCKER"), None)
        if failed is not None:
            return f"статус {report.status.value}: шаг «{failed.id}» — {failed.outcome.value}" + (
                f" (exit {failed.exit_code})" if failed.exit_code is not None else ""
            )
        if blocker is not None:
            return f"статус {report.status.value}: BLOCKER {blocker.source}: {blocker.detail}"
        return f"статус {report.status.value} (подробнее в report.json артефакта)"
    return {
        "BLOCKED": "подготовка заблокирована (артефакт запуска недоступен)",
        "UNKNOWN": "подготовка завершилась без ответа (артефакт запуска недоступен)",
    }.get(outcome, "подготовка не завершилась (артефакт запуска недоступен)")


def _pending_event(pending: PendingCandidate) -> CandidatePrepared:
    return CandidatePrepared(
        release_id=pending.release_id,
        build=pending.build,
        base=pending.base,
        steps_passed=pending.steps_passed,
        steps_total=pending.steps_total,
        gates_passed=tuple(pending.gates_passed),
        gates_required=tuple(pending.gates_required),
        pr_url=pending.pr_url,
        artifact_name=pending.artifact_name,
        run_url=pending.run_url,
    )


def decide_candidate(
    state: CandidateDedup,
    *,
    outcome: str,
    report: PreparationReport | None,
    detection_reason: str | None,
    propose_outcome: str,
    propose_result: str,
    pr_url: str | None,
    artifact_name: str,
    run_url: str,
    now: str,
) -> Decision[CandidateDedup]:
    """One OTA data preparation run (detect + prepare + propose).

    The returned state assumes *nothing was delivered yet*: every event this run emits
    stays durable - undelivered candidates in :attr:`CandidateDedup.pending` (a newer
    candidate never replaces an older one; both are retried), failure fingerprints not
    recorded at all - and :func:`confirm_candidate_event` applies one confirmed delivery
    at a time. A quiet ``NO_ACTION``/``EXISTS`` run therefore still retries everything
    undelivered, a repeated broken stage re-alerts until its message actually goes out,
    and recovery clears fingerprints as before: ``NO_ACTION`` clears the detection one,
    ``PREPARED`` both, a concluded proposal (``OPENED`` / ``EXISTS`` / ``ALREADY_ON_MAIN``
    / ``DECLINED``) its own."""
    events: list[Event] = []
    failures = state.failures_notified
    notified = state.notified
    # stale markers for releases that were delivered after all are dropped; the rest are
    # retried, oldest first, and stay due until their own confirmed delivery
    pending = tuple(p for p in state.pending if p.release_id not in notified)
    for due in pending:
        events.append(_pending_event(due))

    if outcome in {"DETECT_UNKNOWN", "DETECT_FAILED"}:
        reason = detection_reason or candidate_reason(outcome, report)
        event = _stage_failed(FP_PREPARE_DETECT, reason, run_url, FP_PREPARE_DETECT in failures)
        if event is not None:
            events.append(event)
    elif outcome in {"BLOCKED", "UNKNOWN", "FAILED"}:
        event = _stage_failed(
            FP_PREPARE_RUN, candidate_reason(outcome, report), run_url, FP_PREPARE_RUN in failures
        )
        if event is not None:
            events.append(event)
        failures = _drop(failures, FP_PREPARE_DETECT)  # the detection itself concluded
    elif outcome == "NO_ACTION":
        failures = _drop(failures, FP_PREPARE_DETECT)
    elif outcome == "PREPARED":
        if report is None:
            event = _stage_failed(
                FP_PREPARE_RUN,
                "итог PREPARED, но report.json недоступен",
                run_url,
                FP_PREPARE_RUN in failures,
            )
            if event is not None:
                events.append(event)
        else:
            failures = _drop(failures, FP_PREPARE_DETECT, FP_PREPARE_RUN)
            release_id = report.release.release_id if report.release else report.artifact_name
            if release_id not in notified:
                steps_passed = sum(1 for s in report.steps if s.outcome.value == "PASSED")
                prepared = CandidatePrepared(
                    release_id=release_id,
                    build=report.candidate_build or "неизвестный билд",
                    base=report.base_release_id or report.base_build or "неизвестная база",
                    steps_passed=steps_passed,
                    steps_total=len(report.steps),
                    gates_passed=tuple(
                        g.gate.value for g in report.gates if g.status.value == "PASS"
                    ),
                    gates_required=tuple(
                        dict.fromkeys(
                            f.source for f in report.findings if f.kind.value == "REQUIRED"
                        )
                    ),
                    pr_url=pr_url,
                    artifact_name=report.artifact_name,
                    run_url=run_url,
                )
                marker = PendingCandidate(
                    release_id=prepared.release_id,
                    build=prepared.build,
                    base=prepared.base,
                    steps_passed=prepared.steps_passed,
                    steps_total=prepared.steps_total,
                    gates_passed=prepared.gates_passed,
                    gates_required=prepared.gates_required,
                    pr_url=prepared.pr_url,
                    artifact_name=prepared.artifact_name,
                    run_url=prepared.run_url,
                )
                same = [p for p in pending if p.release_id == release_id]
                if same:
                    # the same release prepared again: one message, this run's fresher
                    # facts (e.g. the PR link); a *different* pending release is never
                    # replaced - both stay due
                    events = [
                        e
                        for e in events
                        if not (isinstance(e, CandidatePrepared) and e.release_id == release_id)
                    ]
                    pending = tuple(marker if p.release_id == release_id else p for p in pending)
                else:
                    pending = (*pending, marker)
                events.append(prepared)
    # any other outcome word is rejected by the CLI before this function runs

    propose_done = {"OPENED", "EXISTS", "ALREADY_ON_MAIN", "DECLINED"}
    if propose_outcome in {"REFUSED", "CONFLICT"}:
        reason = {
            "REFUSED": "артефакт не прошёл проверки предложения (REFUSED); ничего не отправлено",
            "CONFLICT": "ветка кандидата содержит чужой коммит (CONFLICT); ничего не перезаписано",
        }[propose_outcome]
        event = _stage_failed(FP_PREPARE_PROPOSE, reason, run_url, FP_PREPARE_PROPOSE in failures)
        if event is not None:
            events.append(event)
    elif propose_outcome == "FAILED" or (propose_result == "failure" and not propose_outcome):
        reason = (
            "предложение PR не завершилось (git/GitHub API); следующий запуск повторит"
            if propose_outcome
            else "шаг предложения умер, не записав итог"
        )
        event = _stage_failed(FP_PREPARE_PROPOSE, reason, run_url, FP_PREPARE_PROPOSE in failures)
        if event is not None:
            events.append(event)
    elif propose_outcome in propose_done:
        failures = _drop(failures, FP_PREPARE_PROPOSE)

    return Decision(tuple(events), _candidate(notified, failures, now, pending))


def confirm_candidate_event(state: CandidateDedup, event: Event, now: str) -> CandidateDedup:
    """Apply ONE confirmed delivery to the candidate state: a delivered
    CandidatePrepared leaves ``pending`` and joins ``notified``; a delivered StageFailed
    records its fingerprint (until then the same broken stage re-alerts). Events of
    other kinds do not touch this state."""
    if isinstance(event, CandidatePrepared):
        pending = tuple(p for p in state.pending if p.release_id != event.release_id)
        notified = state.notified
        if event.release_id not in notified:
            notified = (*notified, event.release_id)
        return _candidate(notified, state.failures_notified, now, pending)
    if isinstance(event, StageFailed) and event.fingerprint:
        return _candidate(
            state.notified, _add(state.failures_notified, event.fingerprint), now, state.pending
        )
    return _candidate(state.notified, state.failures_notified, now, state.pending)


def acknowledge_candidates(state: CandidateDedup, now: str) -> CandidateDedup:
    """The first run's silent baseline: everything due is recorded as acknowledged,
    nothing is sent and nothing stays pending."""
    notified = tuple(dict.fromkeys((*state.notified, *(p.release_id for p in state.pending))))
    return _candidate(notified, state.failures_notified, now, ())


# ---------------------------------------------------------------------- production


def initial_production(now: str) -> ProductionDedup:
    return ProductionDedup(state_version=1, kind="notify-production-state", updated_at=now)


def decide_production(
    state: ProductionDedup,
    *,
    observation: ProductionObservation,
    run_url: str,
    now: str,
) -> Decision[ProductionDedup]:
    """One observation of the public production catalog.

    A release notifies only after the whole check passed (catalog verified *and* the
    active release's manifest fetched and re-pinned) and only when the *active release id*
    changed: a re-signed catalog or a new sequence with the same active release is not a
    release. A verified catalog with a broken manifest notifies the failure instead and
    leaves :attr:`ProductionDedup.notified_release` untouched, so the release change is
    still notified by the run that first sees it healthy. A catalog that disappears after
    a notified release, or that cannot be verified, notifies once per episode."""
    events: list[Event] = []
    failures = state.failures_notified
    notified_release = state.notified_release

    if observation.status is ProductionStatus.PUBLISHED:
        failures = _drop(failures, FP_PRODUCTION_CATALOG)
        if observation.manifest_problem is not None:
            event = _stage_failed(
                FP_PRODUCTION_MANIFEST,
                observation.manifest_problem,
                run_url,
                FP_PRODUCTION_MANIFEST in failures,
            )
            if event is not None:
                events.append(event)
            failures = _add(failures, FP_PRODUCTION_MANIFEST)
        else:
            failures = _drop(failures, FP_PRODUCTION_MANIFEST)
            release_id = observation.release_id
            if release_id is not None and release_id != notified_release:
                events.append(
                    ProductionUpdated(
                        release_id=release_id,
                        sequence=observation.sequence or 0,
                        origin=observation.origin,
                        checked=observation.checked,
                        run_url=run_url,
                    )
                )
                notified_release = release_id
    elif observation.status is ProductionStatus.ABSENT:
        if notified_release is None:
            # nothing was ever published: an absent catalog is the expected state
            failures = _drop(failures, FP_PRODUCTION_CATALOG, FP_PRODUCTION_MANIFEST)
        else:
            event = _stage_failed(
                FP_PRODUCTION_CATALOG,
                "публичный production-каталог исчез (403/404), хотя релиз был опубликован",
                run_url,
                FP_PRODUCTION_CATALOG in failures,
            )
            if event is not None:
                events.append(event)
            failures = _add(failures, FP_PRODUCTION_CATALOG)
            failures = _drop(failures, FP_PRODUCTION_MANIFEST)
    else:  # UNKNOWN: the origin answered nothing trustworthy; the last facts stand
        event = _stage_failed(
            FP_PRODUCTION_CATALOG,
            observation.reason,
            run_url,
            FP_PRODUCTION_CATALOG in failures,
        )
        if event is not None:
            events.append(event)
        failures = _add(failures, FP_PRODUCTION_CATALOG)

    return Decision(tuple(events), _production(notified_release, failures, now))


# ---------------------------------------------------------------------- documents


def _freshness(notified_build: str | None, failures: tuple[str, ...], now: str) -> FreshnessDedup:
    return FreshnessDedup(
        state_version=1,
        kind="notify-freshness-state",
        notified_build=notified_build,
        failures_notified=failures[-_FAILURES_LIMIT:],
        updated_at=now,
    )


def _candidate(
    notified: tuple[str, ...],
    failures: tuple[str, ...],
    now: str,
    pending: tuple[PendingCandidate, ...] = (),
) -> CandidateDedup:
    return CandidateDedup(
        state_version=1,
        kind="notify-candidate-state",
        notified=notified[-_NOTIFIED_LIMIT:],
        failures_notified=failures[-_FAILURES_LIMIT:],
        pending=pending[-_PENDING_LIMIT:],
        updated_at=now,
    )


def _production(
    notified_release: str | None, failures: tuple[str, ...], now: str
) -> ProductionDedup:
    return ProductionDedup(
        state_version=1,
        kind="notify-production-state",
        notified_release=notified_release,
        failures_notified=failures[-_FAILURES_LIMIT:],
        updated_at=now,
    )
