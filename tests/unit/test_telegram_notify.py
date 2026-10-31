"""The owner's Telegram notifications (M5.NOTIFY), fully offline - no real send ever.

* the client against ``httpx.MockTransport``: delivery, the limited retries, 429 with
  ``retry_after`` (waited within the budget, refused beyond it), 5xx and transport
  failures retried, definitive 4xx not, non-JSON answers, and the token never appearing
  in any error text (the URL is part of httpx exceptions - it must be redacted);
* the rendered Russian messages: the two mandatory disclaimers, the links;
* the pure deduplication decisions: one notification per build / release / active
  release (a re-sign or a sequence bump is not a release), failures once per episode
  with recovery re-arming them, the first-run baseline, the daily OUTDATED about the
  same build staying silent (PRODUCT_BUNDLE 70450 vs production 70532);
* the production watch against the signed in-memory origin of the preflight tests:
  a verified publication, a wrongly signed catalog, no catalog, a broken manifest;
* the CLI: SKIPPED without secrets, the first run, retries after a failed send, smoke;
* the state branch: download / upload / rebase onto a racing producer;
* the workflows: the secrets only in main-only jobs, notify jobs that cannot change the
  pipeline's result, and the "Record the outcome" script for every combination.
"""

from __future__ import annotations

import json
import subprocess
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx
import pytest

from talent_data.freshness import FreshnessStatus
from talent_data.notify import cli as notify_cli
from talent_data.notify.cli import main as cli_main
from talent_data.notify.events import (
    BuildDetected,
    CandidatePrepared,
    ProductionUpdated,
    StageFailed,
    render,
)
from talent_data.notify.gitstate import download, upload
from talent_data.notify.production_watch import ProductionObservation, observe_production
from talent_data.notify.state import (
    FP_FRESHNESS_CHECK,
    FP_FRESHNESS_UNKNOWN,
    FP_PREPARE_DETECT,
    FP_PREPARE_PROPOSE,
    FP_PREPARE_RUN,
    CandidateDedup,
    FreshnessDedup,
    PendingCandidate,
    StateError,
    acknowledge_candidates,
    baseline,
    confirm_candidate_event,
    decide_candidate,
    decide_freshness,
    decide_production,
    initial_candidate,
    initial_freshness,
    initial_production,
    load_dedup,
    save_dedup,
)
from talent_data.notify.telegram import (
    ATTEMPTS,
    RETRY_PAUSE,
    SendResult,
    SendStatus,
    TelegramClient,
    TelegramError,
    redact,
)
from talent_data.ota.gates import Gate, GateStatus
from talent_data.prepare.orchestrate import FindingKind, Readiness, StepKind, StepOutcome
from talent_data.prepare.production import ProductionStatus
from talent_data.prepare.propose import (
    PreparationReport,
    ReportFinding,
    ReportGate,
    ReportRelease,
    ReportStep,
)
from talent_data.watch.report import BuildFact, FreshnessDocument
from tests.unit.ota.test_ota_preflight import CATALOG, KEYRING, ORIGIN, FakeOrigin, publication

ROOT = Path(__file__).resolve().parents[2]
NOW = "2026-10-30T06:00:00Z"
RUN = "https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/1"
TOKEN = "123456:AA-test-token-do-not-print"
SERVED_AT = datetime(2026, 10, 18, 13, 0, tzinfo=UTC)


# --------------------------------------------------------------------------- client


def ok(answer: dict[str, Any] | None = None) -> httpx.Response:
    return httpx.Response(200, json={"ok": True, "result": answer or {"message_id": 1}})


def fail(status: int, description: str = "nope", retry_after: int | None = None) -> httpx.Response:
    body: dict[str, Any] = {"ok": False, "error_code": status, "description": description}
    if retry_after is not None:
        body["parameters"] = {"retry_after": retry_after}
    return httpx.Response(status, json=body)


class Scripted:
    """A Telegram transport replaying scripted answers and recording the requests."""

    def __init__(self, *answers: httpx.Response | Exception) -> None:
        self.answers = list(answers)
        self.requests: list[httpx.Request] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if not self.answers:
            raise AssertionError("the script ran out of answers: unexpected extra request")
        answer = self.answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return answer


def client(scripted: Scripted, sleeps: list[float] | None = None) -> TelegramClient:
    waits = sleeps if sleeps is not None else []
    return TelegramClient(
        TOKEN, transport=httpx.MockTransport(scripted.handler), sleeper=waits.append
    )


def test_redact_replaces_every_secret() -> None:
    assert redact(f"url https://api.telegram.org/bot{TOKEN}/sendMessage", TOKEN) == (
        "url https://api.telegram.org/bot***/sendMessage"
    )
    assert redact("nothing to hide", "") == "nothing to hide"


def test_a_message_is_sent_on_ok() -> None:
    scripted = Scripted(ok())
    with client(scripted) as c:
        result = c.send_message("42", "привет")
    assert result.status is SendStatus.SENT and result.attempts == 1
    (request,) = scripted.requests
    assert request.url.path == f"/bot{TOKEN}/sendMessage"  # the token rides the URL only
    payload = json.loads(request.read())
    assert payload == {"chat_id": "42", "text": "привет", "disable_web_page_preview": True}


def test_429_is_waited_within_the_budget_and_retried() -> None:
    sleeps: list[float] = []
    scripted = Scripted(fail(429, "Too Many Requests: retry after 1", retry_after=1), ok())
    with client(scripted, sleeps) as c:
        result = c.send_message("42", "hi")
    assert result.status is SendStatus.SENT and result.attempts == 2
    assert sleeps == [1.0]


def test_429_beyond_the_budget_gives_up_instead_of_parking() -> None:
    sleeps: list[float] = []
    scripted = Scripted(fail(429, retry_after=300))
    with client(scripted, sleeps) as c:
        result = c.send_message("42", "hi")
    assert result.status is SendStatus.FAILED and result.attempts == 1
    assert sleeps == []
    assert "300s" in result.detail and TOKEN not in result.detail


def test_429_without_retry_after_fails() -> None:
    with client(Scripted(fail(429, "Too Many Requests"))) as c:
        assert c.send_message("42", "hi").status is SendStatus.FAILED


def test_5xx_is_retried_with_a_pause() -> None:
    sleeps: list[float] = []
    scripted = Scripted(fail(500, "Internal Server Error"), ok())
    with client(scripted, sleeps) as c:
        result = c.send_message("42", "hi")
    assert result.status is SendStatus.SENT and result.attempts == 2
    assert sleeps == [2.0]


def test_a_definitive_4xx_is_not_retried() -> None:
    scripted = Scripted(fail(400, "Bad Request: chat not found"))
    with client(scripted) as c:
        result = c.send_message("42", "hi")
    assert result.status is SendStatus.FAILED and result.attempts == 1
    assert "chat not found" in result.detail and TOKEN not in result.detail


def test_transport_failures_are_retried_then_reported() -> None:
    sleeps: list[float] = []
    boom = httpx.ConnectError("connection refused")
    with client(Scripted(boom, boom, boom), sleeps) as c:
        result = c.send_message("42", "hi")
    assert result.status is SendStatus.FAILED and result.attempts == ATTEMPTS
    assert sleeps == [2.0, 2.0]


def test_a_transport_failure_recovers_on_the_next_attempt() -> None:
    scripted = Scripted(httpx.ConnectError("reset"), ok())
    with client(scripted) as c:
        assert c.send_message("42", "hi").status is SendStatus.SENT


def test_an_ok_without_ok_true_is_ambiguous_and_fails_cleanly() -> None:
    degraded = httpx.Response(200, json={"ok": False, "description": "degraded"})
    with client(Scripted(degraded, degraded, degraded)) as c:
        result = c.send_message("42", "hi")
    assert result.status is SendStatus.FAILED and "degraded" in result.detail
    assert result.attempts == ATTEMPTS


def test_a_non_json_answer_fails() -> None:
    html = httpx.Response(200, text="<html>gateway</html>")
    with client(Scripted(html, html, html)) as c:
        assert c.send_message("42", "hi").status is SendStatus.FAILED


def test_transport_exceptions_never_leak_the_token() -> None:
    def leak(request: httpx.Request) -> httpx.Response:
        # httpx exceptions embed the full request URL; the token is part of that URL
        raise httpx.ConnectError(f"cannot connect to {request.url}", request=request)

    sleeps: list[float] = []
    c = TelegramClient(TOKEN, transport=httpx.MockTransport(leak), sleeper=sleeps.append)
    result = c.send_message("42", "hi")
    assert result.status is SendStatus.FAILED
    assert TOKEN not in result.detail and "***" in result.detail


def test_the_api_description_is_redacted_in_every_error_text() -> None:
    """Telegram (or a proxy in front of it) may echo the request URL - token included -
    in its own ``description``; no detail that leaves the client may carry it."""
    echo = (
        f"Bad Request: chat not found; saw {TOKEN} at "
        f"https://api.telegram.org/bot{TOKEN}/sendMessage"
    )
    with client(Scripted(fail(400, echo))) as c:
        result = c.send_message("42", "hi")
    assert result.status is SendStatus.FAILED
    assert TOKEN not in result.detail and "***" in result.detail

    with client(Scripted(fail(401, echo))) as c, pytest.raises(TelegramError) as raised:
        c.get_me()
    assert TOKEN not in str(raised.value) and "***" in str(raised.value)

    with client(Scripted(fail(400, echo))) as c, pytest.raises(TelegramError) as raised:
        c.get_updates()
    assert TOKEN not in str(raised.value) and "***" in str(raised.value)

    rate = fail(429, echo, retry_after=300)
    with client(Scripted(rate)) as c:
        result = c.send_message("42", "hi")
    assert TOKEN not in result.detail


def test_the_client_sleeps_for_real_without_an_injected_sleeper(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The default sleeper is a real delay (a 429 must not hammer); tests observe it by
    patching time.sleep, not by injecting their own callable."""
    from talent_data.notify import telegram as telegram_module

    slept: list[float] = []
    monkeypatch.setattr(telegram_module.time, "sleep", slept.append)
    scripted = Scripted(fail(500, "Internal Server Error"), ok())
    with TelegramClient(TOKEN, transport=httpx.MockTransport(scripted.handler)) as c:
        assert c.send_message("42", "hi").status is SendStatus.SENT
    assert slept == [RETRY_PAUSE]


def test_get_me_and_get_updates_parse_and_raise_redacted() -> None:
    scripted = Scripted(
        httpx.Response(200, json={"ok": True, "result": {"username": "tf_notify_bot"}}),
        httpx.Response(200, json={"ok": True, "result": [{"update_id": 1}, {"update_id": 2}]}),
    )
    with client(scripted) as c:
        assert c.get_me() == "tf_notify_bot"
        assert len(c.get_updates()) == 2
    with client(Scripted(fail(404, "Not Found"))) as c:
        with pytest.raises(TelegramError, match="404") as raised:
            c.get_me()
        assert TOKEN not in str(raised.value)


# --------------------------------------------------------------------------- events


def test_every_message_carries_its_mandatory_disclaimers_and_links() -> None:
    detected = render(
        BuildDetected(
            build="1.60.1.70533",
            source="wago.tools",
            product_build="1.60.1.70450",
            issue_url="https://github.com/AlexeyKravtsov95/TalentForge/issues/102",
            run_url=RUN,
        )
    )
    assert "1.60.1.70533" in detected and "wago.tools" in detected
    assert "issues/102" in detected and RUN in detected
    assert "не подтверждение" in detected and "сервер" in detected

    prepared = render(
        CandidatePrepared(
            release_id="1.60.1.70532-r1",
            build="1.60.1.70532",
            base="1.60.1.70450-r1",
            steps_passed=18,
            steps_total=18,
            gates_passed=("G2", "G3", "G4", "G5", "G6", "G8"),
            gates_required=("G1", "G7", "staging", "production"),
            pr_url="https://github.com/AlexeyKravtsov95/TalentForge/pull/101",
            artifact_name="ota-prepare-forever-1.60.1.70532-r1-PREPARED",
            run_url=RUN,
        )
    )
    assert "18/18" in prepared and "G2" in prepared and "G7" in prepared
    assert "pull/101" in prepared and RUN in prepared
    assert "PREPARED — не разрешение" in prepared

    updated = render(
        ProductionUpdated(
            release_id="1.60.1.70532-r1",
            sequence=2,
            origin="https://ota.mythweld.com",
            checked="каталог получен, подпись проверена",
            run_url=RUN,
        )
    )
    assert "1.60.1.70532-r1" in updated and "sequence: 2" in updated and RUN in updated
    assert "подпись проверена" in updated

    broken = render(
        StageFailed(stage="ota-prepare: prepare", reason="шаг «normalize» — FAILED", run_url=RUN)
    )
    assert "Этап: ota-prepare: prepare" in broken and "normalize" in broken and RUN in broken


# --------------------------------------------------------------------------- state


def fact(version: str) -> BuildFact:
    # the strict reader's models take their camelCase wire aliases (no populate_by_name)
    return BuildFact(
        version=version,
        product="wow_classic_beta",
        createdAt="2026-10-20 03:00:00",
        isBgdl=False,
    )


def freshness_doc(
    status: FreshnessStatus, latest: str | None = "1.60.1.70532", product: str = "1.60.1.70450"
) -> FreshnessDocument:
    return FreshnessDocument(
        schemaVersion=1,
        kind="product-freshness",
        game="forever",
        status=status,
        productBuild=product,
        latestPublishable=None if latest is None else fact(latest),
        latestDetected=None,
        newerUnpublishable=(),
        reason="the reason",
        source="wago.tools",
        checkedAt=NOW,
    )


def observation(
    status: ProductionStatus = ProductionStatus.PUBLISHED,
    release: str | None = "1.60.1.70532-r1",
    sequence: int = 2,
    manifest_problem: str | None = None,
) -> ProductionObservation:
    return ProductionObservation(
        status=status,
        origin="https://ota.mythweld.com",
        reason="the catalog reader's reason",
        release_id=release,
        sequence=sequence,
        manifest_problem=manifest_problem,
        checked="проверено",
    )


def freshness(state: Any, document: FreshnessDocument | None = None, **kw: Any) -> Any:
    kw.setdefault("issue_url", None)
    return decide_freshness(
        state, document=document, check_failure=kw.pop("check_failure", None), **kw
    )


def test_the_same_outdated_build_never_renotifies_daily() -> None:
    """PRODUCT_BUNDLE stays 70450 while production serves 70532: the daily OUTDATED
    answer about 70532 notifies once, and only a newer build notifies again."""
    decision = freshness(
        initial_freshness(NOW), freshness_doc(FreshnessStatus.OUTDATED), run_url=RUN, now=NOW
    )
    assert [type(e) for e in decision.events] == [BuildDetected]
    state = decision.state
    assert state.notified_build == "1.60.1.70532"
    for _ in range(3):  # the next daily runs about the very same build
        state = freshness(
            state, freshness_doc(FreshnessStatus.OUTDATED), run_url=RUN, now=NOW
        ).state
        assert state.notified_build == "1.60.1.70532"
    newer = freshness(
        state, freshness_doc(FreshnessStatus.OUTDATED, latest="1.60.1.70533"), run_url=RUN, now=NOW
    )
    assert [type(e) for e in newer.events] == [BuildDetected]
    assert newer.state.notified_build == "1.60.1.70533"


def test_the_first_run_records_the_baseline_and_sends_nothing_old() -> None:
    decision = freshness(
        initial_freshness(NOW),
        freshness_doc(FreshnessStatus.OUTDATED, latest="1.60.1.70532"),
        run_url=RUN,
        now=NOW,
    )
    quiet = baseline(decision)
    assert quiet.events == ()
    assert quiet.state.notified_build == "1.60.1.70532"


def test_current_clears_both_failure_fingerprints() -> None:
    state = initial_freshness(NOW)
    state = freshness(state, freshness_doc(FreshnessStatus.UNKNOWN), run_url=RUN, now=NOW).state
    state = decide_freshness(
        state,
        document=None,
        check_failure="the report is missing",
        issue_url=None,
        run_url=RUN,
        now=NOW,
    ).state
    assert set(state.failures_notified) == {FP_FRESHNESS_UNKNOWN, FP_FRESHNESS_CHECK}
    recovered = freshness(
        state, freshness_doc(FreshnessStatus.CURRENT, latest="1.60.1.70450"), run_url=RUN, now=NOW
    )
    assert recovered.events == () and recovered.state.failures_notified == ()


def test_unknown_and_check_failures_notify_once_per_episode() -> None:
    state = initial_freshness(NOW)
    first = freshness(state, freshness_doc(FreshnessStatus.UNKNOWN), run_url=RUN, now=NOW)
    assert [e.reason for e in first.events if isinstance(e, StageFailed)] == ["the reason"]
    again = freshness(first.state, freshness_doc(FreshnessStatus.UNKNOWN), run_url=RUN, now=NOW)
    assert again.events == ()
    broken = decide_freshness(
        again.state,
        document=None,
        check_failure="bad JSON",
        issue_url=None,
        run_url=RUN,
        now=NOW,
    )
    assert len(broken.events) == 1
    assert (
        decide_freshness(
            broken.state,
            document=None,
            check_failure="bad JSON",
            issue_url=None,
            run_url=RUN,
            now=NOW,
        ).events
        == ()
    )


# ---------------------------------------------------------------------- candidate state


def report(
    status: Readiness = Readiness.PREPARED, build: str = "1.60.1.70532"
) -> PreparationReport:
    return PreparationReport(
        schema_version=1,
        kind="ota-prepare-report",
        game="forever",
        status=status,
        publishable=False,
        artifact_name=f"ota-prepare-forever-{build}-r1-from-70450-main-aaaaaaaaaaaa-PREPARED",
        candidate_build=build,
        base_build="1.60.1.70450",
        base_kind="productionRelease",
        base_release_id="1.60.1.70450-r1",
        product_build="1.60.1.70450",
        main_commit="a" * 40,
        candidate_commit="b" * 40,
        started_at=NOW,
        finished_at=NOW,
        production=None,
        release=ReportRelease(
            release_id=f"{build}-r1",
            manifest_sha256="sha256:" + "1" * 64,
            candidate_on_main=False,
        ),
        gates=[
            ReportGate(gate=Gate.G1, status=GateStatus.STOP, detail="not on main"),
            ReportGate(gate=Gate.G7, status=GateStatus.FLAG, detail="no record"),
            ReportGate(gate=Gate.G2, status=GateStatus.PASS, detail=""),
        ],
        diff_total=None,
        steps=[
            ReportStep(
                id="fetch",
                kind=StepKind.SOURCE,
                outcome=StepOutcome.PASSED,
                exit_code=0,
                seconds=1.0,
                log="l",
                command="c",
            ),
            ReportStep(
                id="tooltips",
                kind=StepKind.SOURCE,
                outcome=StepOutcome.FAILED,
                exit_code=2,
                seconds=2.0,
                log="l",
                command="c",
            ),
        ],
        changed_paths=3,
        findings=[
            ReportFinding(kind=FindingKind.REQUIRED, source="G1", detail="merge it"),
            ReportFinding(kind=FindingKind.REQUIRED, source="G7", detail="approval record"),
        ],
        files=[],
    )


def candidate(state: Any, **kw: Any) -> Any:
    kw.setdefault("outcome", "PREPARED")
    kw.setdefault("report", report())
    kw.setdefault("detection_reason", None)
    kw.setdefault("propose_outcome", "OPENED")
    kw.setdefault("propose_result", "success")
    kw.setdefault("pr_url", "https://github.com/AlexeyKravtsov95/TalentForge/pull/101")
    kw.setdefault("artifact_name", "art")
    kw.setdefault("run_url", RUN)
    kw.setdefault("now", NOW)
    return decide_candidate(state, **kw)


def test_a_prepared_candidate_notifies_once_per_release_id() -> None:
    decision = candidate(initial_candidate(NOW))
    (event,) = decision.events
    assert isinstance(event, CandidatePrepared)
    assert event.release_id == "1.60.1.70532-r1" and event.steps_total == 2
    assert event.gates_passed == ("G2",) and event.gates_required == ("G1", "G7")
    # the decision assumes no delivery: the event is the durable pending marker
    assert decision.state.notified == () and len(decision.state.pending) == 1
    assert decision.state.pending[0].release_id == event.release_id
    confirmed = confirm_candidate_event(decision.state, event, NOW)
    assert confirmed.notified == ("1.60.1.70532-r1",) and confirmed.pending == ()
    repeat = candidate(confirmed)
    assert repeat.events == () and repeat.state.pending == ()
    assert baseline(decision).events == ()  # a first run records, never announces
    acknowledged = acknowledge_candidates(baseline(decision).state, NOW)  # silent baseline
    assert acknowledged.notified == ("1.60.1.70532-r1",) and acknowledged.pending == ()


def test_an_undelivered_candidate_is_retried_after_the_run_goes_quiet() -> None:
    """The sequence the task fixes: Telegram is down when the candidate is PREPARED, the
    next scheduled run is the quiet NO_ACTION/EXISTS of the kept artifact, Telegram is
    back - the message is delivered exactly once, and never again."""
    undelivered = candidate(initial_candidate(NOW)).state  # the PREPARED run, send failed
    assert len(undelivered.pending) == 1 and undelivered.notified == ()
    retry = candidate(undelivered, outcome="NO_ACTION", report=None, propose_outcome="EXISTS")
    (event,) = retry.events
    assert isinstance(event, CandidatePrepared) and event.release_id == "1.60.1.70532-r1"
    assert len(retry.state.pending) == 1  # still due until its own confirmed delivery
    recovered = confirm_candidate_event(retry.state, event, NOW)
    assert recovered.notified == ("1.60.1.70532-r1",) and recovered.pending == ()
    quiet = candidate(recovered, outcome="NO_ACTION", report=None, propose_outcome="EXISTS")
    assert quiet.events == ()  # no second delivery, ever


def test_a_fresh_prepared_of_the_same_release_supersedes_the_retry() -> None:
    undelivered = candidate(initial_candidate(NOW)).state
    assert len(undelivered.pending) == 1
    fresh = candidate(
        undelivered, pr_url="https://github.com/AlexeyKravtsov95/TalentForge/pull/102"
    )
    (event,) = fresh.events  # one message, not both the retry and the fresh event
    assert isinstance(event, CandidatePrepared) and event.pr_url is not None
    assert len(fresh.state.pending) == 1 and fresh.state.pending[0].pr_url is not None


def test_a_undelivered_failure_is_not_suppressed_until_delivered() -> None:
    """A broken stage re-alerts on every run until its alert was actually delivered -
    the fingerprint is recorded by the confirmation, not by the decision."""
    blocked = report(Readiness.BLOCKED)
    first = candidate(initial_candidate(NOW), outcome="BLOCKED", report=blocked, propose_outcome="")
    (event,) = first.events
    assert isinstance(event, StageFailed) and "tooltips" in event.reason
    assert first.state.failures_notified == ()  # nothing sent yet: no fingerprint
    again = candidate(first.state, outcome="BLOCKED", report=blocked, propose_outcome="")
    assert len(again.events) == 1  # the same failure alerts again
    confirmed = confirm_candidate_event(again.state, again.events[0], NOW)
    assert confirmed.failures_notified == (event.fingerprint,)
    quiet = candidate(confirmed, outcome="BLOCKED", report=blocked, propose_outcome="")
    assert quiet.events == ()  # delivered once: the episode is closed


def test_no_action_is_quiet_and_clears_the_detection_failure() -> None:
    broken = candidate(
        initial_candidate(NOW), outcome="DETECT_UNKNOWN", report=None, propose_outcome=""
    )
    assert len(broken.events) == 1
    quiet = candidate(broken.state, outcome="NO_ACTION", report=None, propose_outcome="")
    assert quiet.events == () and FP_PREPARE_DETECT not in quiet.state.failures_notified


def test_a_blocked_preparation_names_its_failed_step_once() -> None:
    blocked = report(Readiness.BLOCKED)
    decision = candidate(
        initial_candidate(NOW), outcome="BLOCKED", report=blocked, propose_outcome=""
    )
    (event,) = decision.events
    assert isinstance(event, StageFailed) and "tooltips" in event.reason
    delivered = confirm_candidate_event(decision.state, event, NOW)
    assert candidate(delivered, outcome="BLOCKED", report=blocked, propose_outcome="").events == ()


def test_prepared_without_a_report_is_a_failure_not_a_crash() -> None:
    decision = candidate(
        initial_candidate(NOW), outcome="PREPARED", report=None, propose_outcome=""
    )
    (event,) = decision.events
    assert isinstance(event, StageFailed) and "report.json" in event.reason


def test_proposal_failures_notify_once_and_conclusions_clear_them() -> None:
    refused = candidate(
        initial_candidate(NOW), outcome="NO_ACTION", report=None, propose_outcome="REFUSED"
    )
    assert any(isinstance(e, StageFailed) and "REFUSED" in e.reason for e in refused.events)
    died = candidate(
        initial_candidate(NOW),
        outcome="NO_ACTION",
        report=None,
        propose_outcome="",
        propose_result="failure",
    )
    assert any("умер" in e.reason for e in died.events if isinstance(e, StageFailed))
    # the fingerprint exists only after the alert was delivered
    assert refused.state.failures_notified == ()
    delivered = confirm_candidate_event(refused.state, refused.events[0], NOW)
    assert delivered.failures_notified == (FP_PREPARE_PROPOSE,)
    assert (
        candidate(delivered, outcome="NO_ACTION", report=None, propose_outcome="REFUSED").events
        == ()
    )
    cleared = candidate(delivered, outcome="NO_ACTION", report=None, propose_outcome="DECLINED")
    assert cleared.events == () and FP_PREPARE_PROPOSE not in cleared.state.failures_notified


# ---------------------------------------------------------------------- production state


def prod(state: Any, observed: ProductionObservation) -> Any:
    return decide_production(state, observation=observed, run_url=RUN, now=NOW)


def test_a_release_change_notifies_once_and_a_resign_is_not_a_release() -> None:
    state = prod(initial_production(NOW), observation(release="1.60.1.70450-r1", sequence=1)).state
    assert state.notified_release == "1.60.1.70450-r1"
    resign = prod(state, observation(release="1.60.1.70450-r1", sequence=2))
    assert resign.events == () and resign.state.notified_release == "1.60.1.70450-r1"
    updated = prod(resign.state, observation(release="1.60.1.70532-r1", sequence=3))
    (event,) = updated.events
    assert isinstance(event, ProductionUpdated) and event.sequence == 3
    assert updated.state.notified_release == "1.60.1.70532-r1"
    fresh = prod(initial_production(NOW), observation())
    assert baseline(fresh).events == () and baseline(fresh).state.notified_release is not None


def test_a_broken_manifest_defers_the_release_notification_until_healthy() -> None:
    state = prod(initial_production(NOW), observation(release="1.60.1.70450-r1", sequence=1)).state
    broken = prod(
        state,
        observation(release="1.60.1.70532-r1", sequence=2, manifest_problem="манифест не получен"),
    )
    (event,) = broken.events
    assert isinstance(event, StageFailed) and "манифест" in event.reason
    assert broken.state.notified_release == "1.60.1.70450-r1"  # unchanged: not notified
    repeat = prod(
        broken.state,
        observation(release="1.60.1.70532-r1", sequence=2, manifest_problem="манифест не получен"),
    )
    assert repeat.events == ()
    healthy = prod(repeat.state, observation(release="1.60.1.70532-r1", sequence=2))
    assert any(isinstance(e, ProductionUpdated) for e in healthy.events)
    assert healthy.state.failures_notified == ()


def test_a_disappeared_or_unverifiable_catalog_notifies_once() -> None:
    state = prod(initial_production(NOW), observation(release="1.60.1.70450-r1", sequence=1)).state
    gone = prod(state, observation(status=ProductionStatus.ABSENT, release=None))
    (event,) = gone.events
    assert isinstance(event, StageFailed) and "исчез" in event.reason
    assert prod(gone.state, observation(status=ProductionStatus.ABSENT, release=None)).events == ()
    fresh = prod(initial_production(NOW), observation(status=ProductionStatus.ABSENT, release=None))
    assert fresh.events == ()  # nothing was ever published: not a failure
    unknown = prod(state, observation(status=ProductionStatus.UNKNOWN, release=None, sequence=None))
    assert any(isinstance(e, StageFailed) for e in unknown.events)
    assert (
        prod(
            unknown.state, observation(status=ProductionStatus.UNKNOWN, release=None, sequence=None)
        ).events
        == ()
    )


def test_an_invalid_state_document_is_rejected(tmp_path: Path) -> None:
    assert load_dedup(FreshnessDedup, tmp_path / "never.json") is None  # absent: first run
    bad = tmp_path / "bad.json"
    bad.write_text(
        '{"stateVersion": 1, "kind": "notify-freshness-state", "surprise": 1}', encoding="utf-8"
    )
    with pytest.raises(StateError):
        load_dedup(FreshnessDedup, bad)


# ---------------------------------------------------------------------- production watch


def watch(origin: FakeOrigin) -> ProductionObservation:
    with httpx.Client(transport=httpx.MockTransport(origin.handler)) as client:
        return observe_production(
            client, origin=ORIGIN, keyring=KEYRING, game="forever", now=SERVED_AT
        )


def test_a_verified_publication_is_observed_with_its_active_release() -> None:
    observed = watch(FakeOrigin(publication()))
    assert observed.status is ProductionStatus.PUBLISHED
    assert observed.release_id is not None and observed.release_id.endswith("-r1")
    assert observed.sequence == 1 and observed.manifest_problem is None
    assert "подпись проверена" in observed.checked


def test_a_wrongly_signed_catalog_is_unknown_never_published() -> None:
    observed = watch(FakeOrigin(publication(signer="tf-prod-2026x")))
    assert observed.status is ProductionStatus.UNKNOWN
    assert observed.release_id is None and observed.manifest_problem is None


def test_an_absent_origin_and_a_broken_manifest_are_reported() -> None:
    assert watch(FakeOrigin()).status is ProductionStatus.ABSENT

    tampered = publication()
    victim = next(k for k, v in tampered.items() if k != CATALOG and b'"releaseId"' in v)
    tampered[victim] = tampered[victim] + b" "  # the catalog pin no longer matches
    observed = watch(FakeOrigin(tampered))
    assert observed.status is ProductionStatus.PUBLISHED
    assert observed.manifest_problem is not None and "манифест" in observed.manifest_problem

    missing = publication()
    del missing[next(k for k, v in missing.items() if k != CATALOG and b'"releaseId"' in v)]
    observed = watch(FakeOrigin(missing))  # anonymous S3 answers 403 for a missing key
    assert observed.status is ProductionStatus.PUBLISHED
    assert observed.manifest_problem is not None and "манифест" in observed.manifest_problem


def test_the_watch_only_reads() -> None:
    origin = FakeOrigin(publication())
    watch(origin)
    assert set(origin.methods) == {"GET"}


# ---------------------------------------------------------------------- CLI


class FakeTelegram:
    """A fake client: every message SENT (or FAILED), or a per-message ``plan`` of
    outcomes for partial-delivery scenarios (first message failed, second sent, ...)."""

    def __init__(
        self, outcome: SendStatus = SendStatus.SENT, plan: list[SendStatus] | None = None
    ) -> None:
        self.outcome = outcome
        self.plan = list(plan) if plan is not None else None
        self.sent: list[tuple[str, str]] = []

    def __enter__(self) -> FakeTelegram:
        return self

    def __exit__(self, *exc: object) -> None:
        return None

    def send_message(self, chat_id: str, text: str) -> SendResult:
        self.sent.append((chat_id, text))
        status = self.plan.pop(0) if self.plan else self.outcome
        if status is SendStatus.SENT:
            return SendResult(SendStatus.SENT, "delivered")
        return SendResult(SendStatus.FAILED, "sendMessage: HTTP 400: chat not found")

    def get_me(self) -> str:
        return "tf_notify_bot"


def cli_env(tmp_path: Path, fake: FakeTelegram, monkeypatch: pytest.MonkeyPatch) -> dict[str, str]:
    monkeypatch.setattr(notify_cli, "TelegramClient", lambda token: fake)
    return {
        "TELEGRAM_BOT_TOKEN": TOKEN,
        "TELEGRAM_CHAT_ID": "424242",
        "GITHUB_STEP_SUMMARY": str(tmp_path / "summary.md"),
    }


def write_artifact(directory: Path, latest: str = "1.60.1.70532") -> None:
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "freshness.json").write_text(
        freshness_doc(FreshnessStatus.OUTDATED, latest=latest).model_dump_json(by_alias=True),
        encoding="utf-8",
    )
    (directory / "exit-code.txt").write_text("3\n", encoding="utf-8")


def freshness_args(artifact: Path, state: Path) -> list[str]:
    return [
        "freshness",
        "--report-dir",
        str(artifact),
        "--check-exit-code",
        "3",
        "--issue-url",
        "https://github.com/AlexeyKravtsov95/TalentForge/issues/102",
        "--state-file",
        str(state),
        "--run-url",
        RUN,
    ]


def test_the_cli_skips_explicitly_without_secrets(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    summary = tmp_path / "summary.md"
    code = cli_main(
        freshness_args(tmp_path, tmp_path / "freshness.json"), {"GITHUB_STEP_SUMMARY": str(summary)}
    )
    assert code == 0
    assert "SKIPPED" in summary.read_text(encoding="utf-8")
    assert not (tmp_path / "freshness.json").exists()  # nothing recorded, nothing sent


def test_the_cli_baselines_then_stays_quiet_then_announces_the_new_build(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    fake = FakeTelegram()
    env = cli_env(tmp_path, fake, monkeypatch)
    artifact, state, summary = (
        tmp_path / "freshness",
        tmp_path / "freshness.json",
        tmp_path / "s.md",
    )
    args = freshness_args(artifact, state)

    write_artifact(artifact)
    assert cli_main(args, env | {"GITHUB_STEP_SUMMARY": str(summary)}) == 0
    assert fake.sent == []  # the first run sends nothing old
    assert "первый запуск" in summary.read_text(encoding="utf-8")
    recorded = load_dedup(FreshnessDedup, state)
    assert recorded is not None and recorded.notified_build == "1.60.1.70532"

    assert cli_main(args, env | {"GITHUB_STEP_SUMMARY": str(summary)}) == 0  # next day
    assert fake.sent == []

    write_artifact(artifact, latest="1.60.1.70533")
    assert cli_main(args, env | {"GITHUB_STEP_SUMMARY": str(summary)}) == 0
    [(chat, text)] = fake.sent
    assert chat == "424242" and "1.60.1.70533" in text and "issues/102" in text
    assert "не подтверждение" in text


def test_a_failed_send_keeps_the_event_for_the_next_run(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    fake = FakeTelegram(SendStatus.FAILED)
    env = cli_env(tmp_path, fake, monkeypatch)
    artifact, state = tmp_path / "freshness", tmp_path / "freshness.json"
    write_artifact(artifact, latest="1.60.1.70533")
    # not the first run: 70532 was already notified, so 70533 is a real event
    save_dedup(
        state,
        FreshnessDedup(
            state_version=1,
            kind="notify-freshness-state",
            notifiedBuild="1.60.1.70532",
            updatedAt=NOW,
        ),
    )
    args = freshness_args(artifact, state)

    assert cli_main(args, env) == 0  # a Telegram failure is not the pipeline's failure
    assert "FAILED" in Path(env["GITHUB_STEP_SUMMARY"]).read_text(encoding="utf-8")
    # the state did not advance past 70532: the event stays due for a retry
    recorded = load_dedup(FreshnessDedup, state)
    assert recorded is not None and recorded.notified_build == "1.60.1.70532"

    recovered = FakeTelegram()
    monkeypatch.setattr(notify_cli, "TelegramClient", lambda token: recovered)
    assert cli_main(args, env) == 0
    assert len(recovered.sent) == 1  # the retry delivers it now
    recorded = load_dedup(FreshnessDedup, state)
    assert recorded is not None and recorded.notified_build == "1.60.1.70533"


def test_the_candidate_cli_reads_the_preparation_report(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    fake = FakeTelegram()
    env = cli_env(tmp_path, fake, monkeypatch)
    report_path = tmp_path / "report.json"
    report_path.write_text(report().model_dump_json(by_alias=True), encoding="utf-8")
    detection = tmp_path / "detection.json"
    detection.write_text('{"reason": "new build"}', encoding="utf-8")
    state = tmp_path / "candidate.json"
    # not the first run: an older release was notified before
    save_dedup(
        state,
        CandidateDedup(
            state_version=1,
            kind="notify-candidate-state",
            notified=("1.60.1.70374-r1",),
            updatedAt=NOW,
        ),
    )
    args = [
        "candidate",
        "--outcome",
        "PREPARED",
        "--report",
        str(report_path),
        "--detection",
        str(detection),
        "--propose-outcome",
        "OPENED",
        "--propose-result",
        "success",
        "--pr-url",
        "https://github.com/AlexeyKravtsov95/TalentForge/pull/101",
        "--artifact",
        "ota-prepare-forever-1.60.1.70532-r1-PREPARED",
        "--state-file",
        str(state),
        "--run-url",
        RUN,
    ]
    assert cli_main(args, env) == 0
    [(chat, text)] = fake.sent
    assert chat == "424242" and "1.60.1.70532-r1" in text and "pull/101" in text
    assert "PREPARED — не разрешение" in text

    again = [
        "candidate",
        "--outcome",
        "NO_ACTION",
        *args[3:7],
        "--propose-outcome",
        "EXISTS",
        "--propose-result",
        "success",
        "--pr-url",
        "",
        "--artifact",
        "",
        "--state-file",
        str(state),
        "--run-url",
        RUN,
    ]
    assert cli_main(again, env) == 0
    assert len(fake.sent) == 1  # deduplicated


def test_the_candidate_cli_retries_an_undelivered_message(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Telegram down at the PREPARED run, up again at the next (quiet NO_ACTION/EXISTS)
    run: the message is delivered exactly once, and never repeated afterwards."""
    report_path = tmp_path / "report.json"
    report_path.write_text(report().model_dump_json(by_alias=True), encoding="utf-8")
    detection = tmp_path / "detection.json"
    detection.write_text('{"reason": "new build"}', encoding="utf-8")
    state = tmp_path / "candidate.json"
    save_dedup(
        state,
        CandidateDedup(
            state_version=1,
            kind="notify-candidate-state",
            notified=("1.60.1.70374-r1",),
            updated_at=NOW,
        ),
    )
    prepared_args = [
        "candidate",
        "--outcome",
        "PREPARED",
        "--report",
        str(report_path),
        "--detection",
        str(detection),
        "--propose-outcome",
        "OPENED",
        "--propose-result",
        "success",
        "--pr-url",
        "https://github.com/x/pull/9",
        "--artifact",
        "art",
        "--state-file",
        str(state),
        "--run-url",
        RUN,
    ]
    quiet_args = [
        "candidate",
        "--outcome",
        "NO_ACTION",
        "--report",
        str(report_path),
        "--detection",
        str(detection),
        "--propose-outcome",
        "EXISTS",
        "--propose-result",
        "success",
        "--state-file",
        str(state),
        "--run-url",
        RUN,
    ]

    down = FakeTelegram(SendStatus.FAILED)
    env = cli_env(tmp_path, down, monkeypatch)
    assert cli_main(prepared_args, env) == 0  # not the pipeline's failure
    recorded = load_dedup(CandidateDedup, state)
    assert recorded is not None and len(recorded.pending) == 1
    assert recorded.pending[0].release_id == "1.60.1.70532-r1"
    assert recorded.failures_notified == ()  # no alert fingerprint without a delivery
    assert "1.60.1.70532-r1" not in recorded.notified

    up = FakeTelegram()
    monkeypatch.setattr(notify_cli, "TelegramClient", lambda token: up)
    assert cli_main(quiet_args, env) == 0
    [(_chat, text)] = up.sent
    assert "1.60.1.70532-r1" in text
    recorded = load_dedup(CandidateDedup, state)
    assert recorded is not None and recorded.pending == ()
    assert "1.60.1.70532-r1" in recorded.notified

    assert cli_main(quiet_args, env) == 0
    assert len(up.sent) == 1  # delivered once, no repeats


# ------------------------------------------------------- delivery regression scenarios


def _pending(release: str, pr_url: str | None = None) -> PendingCandidate:
    return PendingCandidate(
        release_id=release,
        build=release.rsplit("-r", 1)[0],
        base="1.60.1.70450-r1",
        steps_passed=18,
        steps_total=18,
        gates_passed=("G2",),
        gates_required=("G1", "G7"),
        pr_url=pr_url,
        artifact_name="art",
        run_url=RUN,
    )


def _seed_candidate(
    state: Path,
    *,
    notified: tuple[str, ...] = ("1.60.1.70374-r1",),
    pending: tuple[PendingCandidate, ...] = (),
    failures: tuple[str, ...] = (),
) -> None:
    save_dedup(
        state,
        CandidateDedup(
            state_version=1,
            kind="notify-candidate-state",
            notified=notified,
            failures_notified=failures,
            pending=pending,
            updated_at=NOW,
        ),
    )


def _candidate_run(
    tmp_path: Path,
    fake: FakeTelegram,
    monkeypatch: pytest.MonkeyPatch,
    *,
    outcome: str,
    build: str = "1.60.1.70532",
    status: Readiness = Readiness.PREPARED,
    propose: str = "",
    propose_result: str = "skipped",
    pr_url: str = "",
    summary_name: str = "summary.md",
) -> tuple[int, dict[str, str], Path]:
    env = cli_env(tmp_path, fake, monkeypatch)
    env = env | {"GITHUB_STEP_SUMMARY": str(tmp_path / summary_name)}
    report_path = tmp_path / f"report-{build}.json"
    report_path.write_text(
        report(status, build=build).model_dump_json(by_alias=True), encoding="utf-8"
    )
    detection = tmp_path / "detection.json"
    detection.write_text('{"reason": "new build"}', encoding="utf-8")
    state = tmp_path / "candidate.json"
    args = [
        "candidate",
        "--outcome",
        outcome,
        "--report",
        str(report_path),
        "--detection",
        str(detection),
        "--propose-outcome",
        propose,
        "--propose-result",
        propose_result,
        "--pr-url",
        pr_url,
        "--artifact",
        "art",
        "--state-file",
        str(state),
        "--run-url",
        RUN,
    ]
    return cli_main(args, env), env, state


def _summary(env: dict[str, str]) -> str:
    return Path(env["GITHUB_STEP_SUMMARY"]).read_text(encoding="utf-8")


def test_a_failed_alert_alerts_again_until_it_is_actually_delivered(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A preparation fails while Telegram is down: nothing is recorded as notified (the
    old code recorded the fingerprint and suppressed the retry forever); the same
    failure with Telegram back delivers exactly one alert, and the episode closes."""
    _seed_candidate(tmp_path / "candidate.json")  # not the first run: no silent baseline

    # 1. the broken preparation, Telegram down
    down = FakeTelegram(SendStatus.FAILED)
    code, env, state = _candidate_run(
        tmp_path, down, monkeypatch, outcome="BLOCKED", status=Readiness.BLOCKED
    )
    assert code == 0  # the preparation's own result never depends on Telegram
    assert "FAILED" in _summary(env)
    recorded = load_dedup(CandidateDedup, state)
    assert recorded is not None and recorded.failures_notified == ()  # not suppressed

    # 2. the same failure, Telegram is back: one delivered alert
    up = FakeTelegram()
    code, env, state = _candidate_run(
        tmp_path, up, monkeypatch, outcome="BLOCKED", status=Readiness.BLOCKED, summary_name="s2.md"
    )
    assert code == 0
    [(_chat, text)] = up.sent
    assert "сбой" in text and "tooltips" in text
    assert "SENT (1)" in _summary(env)
    recorded = load_dedup(CandidateDedup, state)
    assert recorded is not None and recorded.failures_notified == (FP_PREPARE_RUN,)

    # 3. the same failure again: silent, the episode is closed
    again = FakeTelegram()
    code, _env, state = _candidate_run(
        tmp_path,
        again,
        monkeypatch,
        outcome="BLOCKED",
        status=Readiness.BLOCKED,
        summary_name="s3.md",
    )
    assert code == 0 and again.sent == []


def test_a_new_candidate_does_not_replace_the_undelivered_one(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Pending A survives a new PREPARED B: on a partial delivery B is confirmed, A
    stays pending, and the next quiet run retries exactly A."""
    state = tmp_path / "candidate.json"
    _seed_candidate(state, pending=(_pending("1.60.1.70532-r1"),))

    # A's retry is not delivered, B (the fresh candidate) is: one out of two
    partial = FakeTelegram(plan=[SendStatus.FAILED, SendStatus.SENT])
    code, env, state = _candidate_run(
        tmp_path, partial, monkeypatch, outcome="PREPARED", build="1.60.1.70600"
    )
    assert code == 0 and len(partial.sent) == 2
    assert "1 из 2" in _summary(env)
    recorded = load_dedup(CandidateDedup, state)
    assert recorded is not None
    assert [p.release_id for p in recorded.pending] == ["1.60.1.70532-r1"]  # A stayed
    assert "1.60.1.70600-r1" in recorded.notified  # B confirmed

    # the next quiet run retries only A
    up = FakeTelegram()
    code, _env2, state = _candidate_run(
        tmp_path, up, monkeypatch, outcome="NO_ACTION", summary_name="s2.md"
    )
    assert code == 0 and len(up.sent) == 1
    assert "1.60.1.70532-r1" in up.sent[0][1]
    assert "1.60.1.70600-r1" not in up.sent[0][1]  # B is not repeated
    recorded = load_dedup(CandidateDedup, state)
    assert recorded is not None and recorded.pending == ()

    # and nothing repeats after the confirmation
    code, _env, _state = _candidate_run(
        tmp_path, up, monkeypatch, outcome="NO_ACTION", summary_name="s3.md"
    )
    assert code == 0 and len(up.sent) == 1


def test_two_undelivered_candidates_are_delivered_together(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    state = tmp_path / "candidate.json"
    _seed_candidate(state, pending=(_pending("1.60.1.70532-r1"),))

    both = FakeTelegram(plan=[SendStatus.FAILED, SendStatus.FAILED])
    code, _env1, state = _candidate_run(
        tmp_path, both, monkeypatch, outcome="PREPARED", build="1.60.1.70600"
    )
    assert code == 0 and len(both.sent) == 2
    recorded = load_dedup(CandidateDedup, state)
    assert recorded is not None
    assert [p.release_id for p in recorded.pending] == ["1.60.1.70532-r1", "1.60.1.70600-r1"]
    assert recorded.notified == ("1.60.1.70374-r1",)

    up = FakeTelegram()
    code, _env2, state = _candidate_run(
        tmp_path, up, monkeypatch, outcome="NO_ACTION", summary_name="s2.md"
    )
    assert code == 0 and len(up.sent) == 2  # both delivered, oldest first
    assert "1.60.1.70532-r1" in up.sent[0][1] and "1.60.1.70600-r1" in up.sent[1][1]
    recorded = load_dedup(CandidateDedup, state)
    assert recorded is not None and recorded.pending == ()
    assert "1.60.1.70532-r1" in recorded.notified and "1.60.1.70600-r1" in recorded.notified

    code, _env, _state = _candidate_run(
        tmp_path, up, monkeypatch, outcome="NO_ACTION", summary_name="s3.md"
    )
    assert code == 0 and len(up.sent) == 2  # no repeats after the confirmation


def test_the_smoke_command_reports_sent_and_failed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    fake = FakeTelegram()
    env = cli_env(tmp_path, fake, monkeypatch)
    assert cli_main(["smoke"], env) == 0
    assert len(fake.sent) == 1 and "smoke test" in fake.sent[0][1]
    assert "SENT" in Path(env["GITHUB_STEP_SUMMARY"]).read_text(encoding="utf-8")

    broken = FakeTelegram(SendStatus.FAILED)
    monkeypatch.setattr(notify_cli, "TelegramClient", lambda token: broken)
    assert cli_main(["smoke"], env) == 1
    assert "FAILED" in Path(env["GITHUB_STEP_SUMMARY"]).read_text(encoding="utf-8")


# ---------------------------------------------------------------------- the state branch


def git(cwd: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(cwd), *args], capture_output=True, text=True, check=False
    )


def test_the_state_branch_roundtrips_and_rebases_onto_a_racing_producer(
    tmp_path: Path,
) -> None:
    origin = tmp_path / "origin.git"
    assert git(tmp_path, "init", "--bare", str(origin)).returncode == 0
    server, repository = str(tmp_path), "origin"

    a = tmp_path / "a"
    assert download(a, repository=repository, server_url=server, token="gh") is False
    save_dedup(a / "freshness.json", initial_freshness(NOW))
    assert upload(a, token="gh", server_url=server, message="notify: freshness")

    b = tmp_path / "b"
    assert download(b, repository=repository, server_url=server, token="gh") is True
    assert load_dedup(FreshnessDedup, b / "freshness.json") is not None
    assert upload(b, token="gh", server_url=server, message="notify: freshness") is False

    # a racing producer committed between b's download and upload: rebase, lose nothing
    save_dedup(a / "production.json", initial_production(NOW))
    assert upload(a, token="gh", server_url=server, message="notify: production")
    save_dedup(b / "candidate.json", initial_candidate(NOW))
    assert upload(b, token="gh", server_url=server, message="notify: candidate")

    c = tmp_path / "c"
    assert download(c, repository=repository, server_url=server, token="gh") is True
    for name in ("freshness.json", "production.json", "candidate.json"):
        assert (c / name).is_file(), name


def test_upload_survives_a_broken_rebase_machine(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The rebase can fail for reasons of its own (a runner without a git identity, an
    unrelated-history bootstrap race). The producers write disjoint files, so re-applying
    the commit's bytes on the fetched tip must still land the same merge."""
    from talent_data.notify import gitstate

    real_git = gitstate._git

    def broken_rebase(cwd: Path, env: dict[str, str], *args: str) -> subprocess.CompletedProcess:
        if "rebase" in args and "FETCH_HEAD" in args:
            return subprocess.CompletedProcess(
                ["git", *args], 128, "", "fatal: committer identity unknown"
            )
        return real_git(cwd, env, *args)

    monkeypatch.setattr(gitstate, "_git", broken_rebase)
    origin = tmp_path / "origin.git"
    assert git(tmp_path, "init", "--bare", str(origin)).returncode == 0
    server, repository = str(tmp_path), "origin"

    a = tmp_path / "a"
    assert download(a, repository=repository, server_url=server, token="gh") is False
    save_dedup(a / "freshness.json", initial_freshness(NOW))
    assert upload(a, token="gh", server_url=server, message="notify: freshness")

    b = tmp_path / "b"
    assert download(b, repository=repository, server_url=server, token="gh") is True
    save_dedup(a / "production.json", initial_production(NOW))
    assert upload(a, token="gh", server_url=server, message="notify: production")
    save_dedup(b / "candidate.json", initial_candidate(NOW))
    assert upload(b, token="gh", server_url=server, message="notify: candidate")

    c = tmp_path / "c"
    assert download(c, repository=repository, server_url=server, token="gh") is True
    for name in ("freshness.json", "production.json", "candidate.json"):
        assert (c / name).is_file(), name


# ---------------------------------------------------------------------- the workflows


def workflow_text(name: str) -> str:
    return (ROOT / ".github/workflows" / name).read_text(encoding="utf-8")


def test_notify_jobs_live_in_the_notifications_environment_and_cannot_fail_a_run() -> None:
    for name in ("dataset-freshness.yml", "ota-prepare.yml"):
        text = workflow_text(name)
        assert "environment: notifications" in text
        assert "continue-on-error: true" in text
        assert "TELEGRAM_BOT_TOKEN: ${{ secrets.TELEGRAM_BOT_TOKEN }}" in text
        assert "TELEGRAM_CHAT_ID: ${{ secrets.TELEGRAM_CHAT_ID }}" in text


def test_the_environment_workflows_never_run_pull_request_code() -> None:
    for name in (
        "dataset-freshness.yml",
        "ota-prepare.yml",
        "ota-production-watch.yml",
        "telegram-smoke-test.yml",
    ):
        text = workflow_text(name)
        assert "pull_request:" not in text, name
        assert "push:" not in text, name


def test_workflows_that_run_pr_code_hold_no_telegram_secrets() -> None:
    for name in (
        "ci.yml",
        "mobile-ci.yml",
        "integration.yml",
        "ota-staging-verify.yml",
        "ota-release-package.yml",
        "ota-historical-replay.yml",
    ):
        text = workflow_text(name)
        assert "TELEGRAM_" not in text, name
        assert "notifications" not in text, name


def workflow_step_script(name: str, step: str) -> str:
    """The ``run: |`` block of one step of a workflow (no YAML library needed)."""
    lines = (ROOT / ".github/workflows" / name).read_text(encoding="utf-8").splitlines()
    start = lines.index(f"      - name: {step}")
    run = next(i for i in range(start, len(lines)) if lines[i] == "        run: |")
    body: list[str] = []
    for line in lines[run + 1 :]:
        if line and not line.startswith(" " * 10):
            break
        body.append(line[10:])
    return "\n".join(body) + "\n"


def expected_outcome(detect_exit: str, action: str, prepare_exit: str, status: str) -> str:
    if not detect_exit:
        return "DETECT_FAILED"
    if detect_exit == "4":
        return "DETECT_UNKNOWN"
    if detect_exit != "0":
        return "DETECT_FAILED"
    if action == "NO_ACTION":
        return "NO_ACTION"
    if action != "PREPARE":
        return "DETECT_FAILED"
    if not prepare_exit:
        return "FAILED"
    if prepare_exit == "0":
        return "PREPARED" if status == "PREPARED" else "FAILED"
    if prepare_exit == "3":
        return "BLOCKED"
    if prepare_exit == "4":
        return "UNKNOWN"
    return "FAILED"


@pytest.mark.parametrize(
    ("detect_exit", "action", "prepare_exit", "status"),
    [
        ("0", "NO_ACTION", "", ""),
        ("0", "PREPARE", "0", "PREPARED"),
        ("0", "PREPARE", "3", "BLOCKED"),
        ("0", "PREPARE", "4", "UNKNOWN"),
        ("0", "PREPARE", "1", ""),
        ("0", "PREPARE", "", ""),
        ("0", "PREPARE", "0", "BLOCKED"),
        ("0", "PREPARE", "0", ""),
        ("0", "", "", ""),
        ("4", "UNKNOWN", "", ""),
        ("1", "", "", ""),
        ("", "", "", ""),
        ("2", "PREPARE", "0", "PREPARED"),
    ],
)
def test_the_workflow_records_the_outcome_for_every_combination(
    detect_exit: str, action: str, prepare_exit: str, status: str, tmp_path: Path
) -> None:
    output = tmp_path / "github_output"
    env = {
        "PATH": "/usr/bin:/bin",
        "GITHUB_OUTPUT": str(output),
        "DETECT_EXIT": detect_exit,
        "DETECT_ACTION": action,
        "PREPARE_EXIT": prepare_exit,
        "PREPARE_STATUS": status,
    }
    completed = subprocess.run(
        [
            "bash",
            "--noprofile",
            "--norc",
            "-eo",
            "pipefail",
            "-c",
            workflow_step_script("ota-prepare.yml", "Record the outcome"),
        ],
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 0, completed.stdout + completed.stderr
    recorded = dict(
        line.split("=", 1)
        for line in output.read_text(encoding="utf-8").splitlines()
        if "=" in line
    )
    assert recorded["outcome"] == expected_outcome(detect_exit, action, prepare_exit, status)


@pytest.mark.parametrize(
    ("artifact", "key", "expected"),
    [
        ("ota-prepare-x-PREPARED", "unused", "ota-prepare-x-PREPARED"),
        ("", "the-key", "ota-prepare-the-key-FAILED"),
    ],
)
def test_the_workflow_names_this_runs_artifact(
    artifact: str, key: str, expected: str, tmp_path: Path
) -> None:
    output = tmp_path / "github_output"
    env = {
        "PATH": "/usr/bin:/bin",
        "GITHUB_OUTPUT": str(output),
        "PREPARE_ARTIFACT": artifact,
        "DETECT_KEY": key,
    }
    completed = subprocess.run(
        [
            "bash",
            "--noprofile",
            "--norc",
            "-eo",
            "pipefail",
            "-c",
            workflow_step_script("ota-prepare.yml", "Name this run's artifact"),
        ],
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 0, completed.stdout + completed.stderr
    assert f"name={expected}\n" in output.read_text(encoding="utf-8")


def test_the_state_documents_never_contain_a_token_or_a_chat_id() -> None:
    for state in (initial_freshness(NOW), initial_candidate(NOW), initial_production(NOW)):
        dumped = state.model_dump_json(by_alias=True)
        assert TOKEN not in dumped and "424242" not in dumped


def test_the_notification_jobs_install_the_extras_the_cli_actually_imports() -> None:
    """The CLI's import chain (the strict preparation-report reader lives in
    talent_data.prepare.propose) needs the `ota` extra (cryptography): a plain
    ``pip install -e .`` fails to import it, which a clean venv proved the hard way."""
    for name in (
        "dataset-freshness.yml",
        "ota-prepare.yml",
        "ota-production-watch.yml",
        "telegram-smoke-test.yml",
    ):
        text = workflow_text(name)
        assert 'run: pip install -e ".[ota]"' in text, name
    # the local setup instruction of the runbook installs the same set
    doc = (ROOT / "docs" / "ota" / "telegram-notifications.md").read_text(encoding="utf-8")
    assert 'pip install -e ".[ota]"' in doc
