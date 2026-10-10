"""Freshness watcher (M5.DATA.WATCH): report reading, issue decisions, GitHub delivery.

Offline. The decision (:func:`talent_data.watch.tracking.decide`) is tested on its own;
delivery runs the real :class:`GitHubIssues` client against an in-memory GitHub Issues
API (``httpx.MockTransport``), so open / update / close / dedup are checked on the calls
that would reach GitHub. Reports come from the real ``evaluate_freshness`` producer where
the committed wago fixture can express the case, and from :func:`document` otherwise.
"""

from __future__ import annotations

import json
import re
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx
import pytest

from talent_data.freshness import FreshnessStatus, evaluate_freshness
from talent_data.games import get_game
from talent_data.sources.http import HttpClient, HttpConfig
from talent_data.sources.wago.source import WagoBuildSource
from talent_data.versions import ClientVersion
from talent_data.watch import runner
from talent_data.watch.github import GitHubIssues
from talent_data.watch.report import CheckFailure, FreshnessDocument, parse_document, read_result
from talent_data.watch.tracking import HISTORY_LIMIT, MARKER, Action, decide, read_state

ROOT = Path(__file__).resolve().parents[2]
BUILDS = ROOT / "tests" / "fixtures" / "wago" / "builds-2026-10-10.json"
REPO = "AlexeyKravtsov95/TalentForge"
RUN = "https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/1001"
RUN2 = "https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/1002"
P = "1.60.1.70338"  # PRODUCT_BUNDLE
NEW = "1.60.1.70400"
NEWER = "1.60.1.70450"
EXIT = {"CURRENT": 0, "OUTDATED": 3, "UNKNOWN": 4}
NETWORK_DOWN = "wago.tools could not be read: GET https://wago.tools/api/builds failed"
SCHEMA_DRIFT = "wago.tools could not be read: unexpected /api/builds schema"


# --------------------------------------------------------------------------- reports


def build(version: str, is_bgdl: bool | None = False) -> dict[str, Any]:
    return {
        "version": version,
        "product": "wow_classic_beta",
        "createdAt": "2026-10-12 05:05:05",
        "isBgdl": is_bgdl,
    }


def document(
    status: str,
    *,
    product: str = P,
    latest: str | None = None,
    reason: str | None = None,
    checked_at: str = "2026-10-12T06:00:00Z",
) -> dict[str, Any]:
    """A product-freshness v1 document shaped exactly like ``FreshnessReport.to_dict``."""
    if latest is None and status == "CURRENT":
        latest = product
    publishable = None if latest is None else build(latest)
    default_reason = {
        "CURRENT": f"{product} is the latest publishable build wago.tools reports",
        "OUTDATED": f"{latest} is publishable (is_bgdl=false) and newer than the product "
        f"build {product}",
        "UNKNOWN": NETWORK_DOWN,
    }[status]
    return {
        "schemaVersion": 1,
        "kind": "product-freshness",
        "game": "forever",
        "status": status,
        "productBuild": product,
        "latestPublishable": publishable,
        "latestDetected": publishable,
        "newerUnpublishable": [],
        "reason": reason or default_reason,
        "source": "wago.tools",
        "checkedAt": checked_at,
    }


def produced(product: str, wago: Callable[[httpx.Request], httpx.Response]) -> str:
    """The JSON the real check writes, for ``product`` against a mocked wago.tools."""
    http = HttpClient(
        HttpConfig(max_attempts=1, backoff_base=0),
        transport=httpx.MockTransport(wago),
        sleep=lambda _: None,
    )
    with http:
        report = evaluate_freshness(
            WagoBuildSource(http),
            get_game("forever"),
            ClientVersion.parse(product),
            now=datetime(2026, 10, 12, 6, 0, tzinfo=UTC),
        )
    return report.to_json()


def serving_fixture(request: httpx.Request) -> httpx.Response:
    return httpx.Response(200, json=json.loads(BUILDS.read_text(encoding="utf-8")))


def report_of(doc: dict[str, Any]) -> FreshnessDocument:
    return parse_document(json.dumps(doc))


def created_body(doc: dict[str, Any]) -> str:
    plan = decide(report_of(doc), RUN, None)
    assert plan.action is Action.CREATE and plan.body is not None
    return plan.body


# --------------------------------------------------------------------------- fake GitHub


class FakeGitHub:
    """The four Issues endpoints the watcher uses, in memory, with a call log."""

    def __init__(self) -> None:
        self.issues: dict[int, dict[str, Any]] = {}
        self.comments: dict[int, list[str]] = {}
        self.calls: list[str] = []
        self.next_number = 1
        self.fail: Callable[[httpx.Request], httpx.Response | None] = lambda request: None
        # noise the watcher must ignore: an unrelated issue and a pull request
        self.add({"title": "Unrelated", "body": "no marker", "state": "open"})
        self.add({"title": "PR", "body": MARKER, "state": "open", "pull_request": {}})

    def add(self, fields: dict[str, Any]) -> int:
        number = self.next_number
        self.next_number += 1
        url = f"https://github.com/{REPO}/issues/{number}"
        self.issues[number] = {"number": number, "html_url": url, **fields}
        self.comments[number] = []
        return number

    def handler(self, request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer test-token"
        self.calls.append(f"{request.method} {request.url.path}")
        failure = self.fail(request)
        if failure is not None:
            return failure
        prefix = f"/repos/{REPO}/issues"
        path = request.url.path
        if request.method == "GET" and path == prefix:
            page = int(request.url.params["page"])
            items = [i for i in self.issues.values() if i["state"] == "open"]
            return httpx.Response(200, json=items if page == 1 else [])
        payload = json.loads(request.content) if request.content else {}
        if request.method == "POST" and path == prefix:
            number = self.add({"title": payload["title"], "body": payload["body"], "state": "open"})
            return httpx.Response(201, json=self.issues[number])
        match = re.fullmatch(re.escape(prefix) + r"/(\d+)(/comments)?", path)
        assert match is not None, path
        number = int(match.group(1))
        if match.group(2):
            self.comments[number].append(payload["body"])
            return httpx.Response(201, json={"id": 1, "body": payload["body"]})
        self.issues[number].update(payload)
        return httpx.Response(200, json=self.issues[number])

    def connect(self) -> GitHubIssues:
        return GitHubIssues(REPO, "test-token", transport=httpx.MockTransport(self.handler))

    def tracked(self) -> list[dict[str, Any]]:
        return [i for i in self.issues.values() if MARKER in i["body"] and "pull_request" not in i]

    def open_tracked(self) -> list[dict[str, Any]]:
        return [i for i in self.tracked() if i["state"] == "open"]

    def writes(self) -> list[str]:
        return [c for c in self.calls if not c.startswith("GET")]


def watch(
    github: FakeGitHub,
    tmp_path: Path,
    doc: dict[str, Any] | str,
    run_url: str = RUN,
) -> int:
    path = tmp_path / "freshness.json"
    text = doc if isinstance(doc, str) else json.dumps(doc)
    path.write_text(text, encoding="utf-8")
    return runner.run(path, EXIT[json.loads(text)["status"]], run_url, github.connect)


def never_connect() -> GitHubIssues:
    raise AssertionError("a watcher failure must not touch GitHub")


# --------------------------------------------------------------------------- report reading


@pytest.mark.parametrize(("product", "status"), [(P, "CURRENT"), ("1.60.1.70245", "OUTDATED")])
def test_the_real_check_output_is_read(product: str, status: str) -> None:
    report = parse_document(produced(product, serving_fixture))
    assert (report.status, report.product_build) == (status, product)


def test_the_real_unknown_output_is_read() -> None:
    report = parse_document(produced(P, lambda request: httpx.Response(503)))
    assert report.status is FreshnessStatus.UNKNOWN
    assert "could not be read" in report.reason


@pytest.mark.parametrize(
    ("change", "message"),
    [
        (lambda d: d.update(schemaVersion=2), "schemaVersion"),
        (lambda d: d.update(schemaVersion="1"), "schemaVersion"),
        (lambda d: d.update(kind="talent-diff"), "kind"),
        (lambda d: d.update(status="STALE"), "status"),
        (lambda d: d.update(extra=True), "extra"),
        (lambda d: d.pop("latestDetected"), "latestDetected"),
        (lambda d: d["latestPublishable"].update(isBgdl="false"), "isBgdl"),
        (lambda d: d.update(newerUnpublishable=None), "newerUnpublishable"),
        (lambda d: d.update(game="retail"), "game"),
        (lambda d: d.update(productBuild=" 1.60.1.70338"), "canonical"),
        (lambda d: d.update(checkedAt="2026-10-12 06:00:00"), "checkedAt"),
        (lambda d: d.update(latestPublishable=None), "must name"),
        (lambda d: d["latestPublishable"].update(isBgdl=None), "is_bgdl=false"),
        (lambda d: d["latestPublishable"].update(version=P), "latestPublishable > productBuild"),
        (lambda d: d.update(reason=" "), "empty reason"),
    ],
)
def test_an_invalid_document_is_a_watcher_failure(
    change: Callable[[dict[str, Any]], object], message: str
) -> None:
    doc = document("OUTDATED", latest=NEW)
    change(doc)
    with pytest.raises(CheckFailure, match=message):
        parse_document(json.dumps(doc))


def test_a_current_document_must_name_the_product_build() -> None:
    with pytest.raises(CheckFailure, match="latestPublishable == productBuild"):
        parse_document(json.dumps(document("CURRENT", latest=NEW)))


@pytest.mark.parametrize("code", [1, 2, 5, 137])
def test_an_unexpected_exit_code_is_a_failure_even_with_valid_json(
    code: int, tmp_path: Path
) -> None:
    path = tmp_path / "freshness.json"
    path.write_text(json.dumps(document("CURRENT")), encoding="utf-8")
    with pytest.raises(CheckFailure, match=f"exited with {code}"):
        read_result(path, code)


def test_status_and_exit_code_must_agree(tmp_path: Path) -> None:
    path = tmp_path / "freshness.json"
    path.write_text(json.dumps(document("OUTDATED", latest=NEW)), encoding="utf-8")
    with pytest.raises(CheckFailure, match="exited with 0"):
        read_result(path, 0)


def test_a_missing_report_is_a_failure(tmp_path: Path) -> None:
    with pytest.raises(CheckFailure, match="could not be read"):
        read_result(tmp_path / "absent.json", 4)


# --------------------------------------------------------------------------- decision


def test_decide_current_without_an_issue_does_nothing() -> None:
    plan = decide(report_of(document("CURRENT")), RUN, None)
    assert plan.action is Action.NONE


@pytest.mark.parametrize("status", ["OUTDATED", "UNKNOWN"])
def test_decide_current_closes_any_open_tracking_issue(status: str) -> None:
    body = created_body(document(status, latest=NEW if status == "OUTDATED" else None))
    plan = decide(report_of(document("CURRENT")), RUN2, body)
    assert plan.action is Action.CLOSE
    assert plan.comment is not None
    assert "not that the realms run this build" in plan.comment


def test_decide_first_outdated_opens_an_issue_with_every_fact() -> None:
    plan = decide(report_of(document("OUTDATED", latest=NEW)), RUN, None)
    assert plan.action is Action.CREATE
    assert plan.title == "Forever dataset outdated: 70338 → 70400"
    body = plan.body or ""
    assert body.startswith(MARKER)
    for fact in (
        f"| Current `PRODUCT_BUNDLE` | `{P}` |",
        f"| Latest confirmed publishable build | `{NEW}`",
        f"| Latest detected build | `{NEW}`",
        f"| Reason | {NEW} is publishable (is_bgdl=false)",
        "| Checked at (UTC) | 2026-10-12T06:00:00Z |",
        "| Data source | wago.tools build index",
        f"| Workflow run | {RUN} |",
        "How to update the app dataset",
        f"--from {P} --to {NEW}",
        "**not** proof that the realms run this build",
    ):
        assert fact in body, fact


def test_decide_the_same_outdated_facts_change_nothing() -> None:
    body = created_body(document("OUTDATED", latest=NEW))
    later = document("OUTDATED", latest=NEW, checked_at="2026-10-13T06:00:00Z")
    assert decide(report_of(later), RUN2, body).action is Action.NONE


def test_decide_an_even_newer_build_updates_the_tracked_build_and_keeps_history() -> None:
    body = created_body(document("OUTDATED", latest=NEW))
    plan = decide(report_of(document("OUTDATED", latest=NEWER)), RUN2, body)
    assert plan.action is Action.UPDATE
    assert plan.title == "Forever dataset outdated: 70338 → 70450"
    assert plan.comment is not None and f"`{NEW}` → `{NEWER}`" in plan.comment
    state = read_state(plan.body or "")
    assert state is not None
    assert [e.note for e in state.history] == [
        f"publishable {NEW} is newer than the product {P}",
        f"publishable {NEWER} is newer than the product {P}",
    ]


def test_decide_a_new_product_build_with_a_newer_one_still_out_is_an_update() -> None:
    body = created_body(document("OUTDATED", latest=NEWER))
    plan = decide(report_of(document("OUTDATED", product=NEW, latest=NEWER)), RUN2, body)
    assert plan.action is Action.UPDATE
    assert plan.title == "Forever dataset outdated: 70400 → 70450"


def test_decide_first_unknown_opens_an_issue_with_the_reason() -> None:
    plan = decide(report_of(document("UNKNOWN", reason=NETWORK_DOWN)), RUN, None)
    assert plan.action is Action.CREATE
    assert plan.title == "Forever dataset freshness unknown: 70338 could not be verified"
    assert NETWORK_DOWN in (plan.body or "")
    assert "**not** confirmed current" in (plan.body or "")


def test_decide_the_same_unknown_reason_changes_nothing_a_new_one_is_recorded() -> None:
    body = created_body(document("UNKNOWN", reason=NETWORK_DOWN))
    again = document("UNKNOWN", reason=NETWORK_DOWN, checked_at="2026-10-13T06:00:00Z")
    assert decide(report_of(again), RUN2, body).action is Action.NONE
    plan = decide(report_of(document("UNKNOWN", reason=SCHEMA_DRIFT)), RUN2, body)
    assert plan.action is Action.UPDATE
    assert plan.comment is not None and SCHEMA_DRIFT in plan.comment


def test_decide_unknown_keeps_an_outdated_issue_open_and_its_findings() -> None:
    body = created_body(document("OUTDATED", latest=NEW))
    plan = decide(report_of(document("UNKNOWN")), RUN2, body)
    assert plan.action is Action.UPDATE  # never CLOSE
    assert plan.title == "Forever dataset outdated: 70338 → 70400"
    assert plan.comment is not None and "OUTDATED findings stay in force" in plan.comment
    state = read_state(plan.body or "")
    assert state is not None and state.outdated is not None and state.unknown is not None
    assert state.outdated.report.latest_publishable is not None
    assert state.outdated.report.latest_publishable.version == NEW


def test_decide_unknown_after_outdated_resolves_into_outdated_quietly() -> None:
    body = created_body(document("OUTDATED", latest=NEW))
    failing = decide(report_of(document("UNKNOWN")), RUN2, body).body or ""
    plan = decide(report_of(document("OUTDATED", latest=NEW)), RUN2, failing)
    assert plan.action is Action.UPDATE and plan.comment is None
    assert "Latest check could not be verified" not in (plan.body or "")


def test_decide_outdated_after_unknown_turns_the_issue_into_an_outdated_one() -> None:
    body = created_body(document("UNKNOWN"))
    plan = decide(report_of(document("OUTDATED", latest=NEW)), RUN2, body)
    assert plan.action is Action.UPDATE
    assert plan.title == "Forever dataset outdated: 70338 → 70400"
    assert plan.comment is not None and "newer publishable build was detected" in plan.comment


def test_decide_rebuilds_an_unreadable_state_instead_of_opening_another_issue() -> None:
    plan = decide(report_of(document("OUTDATED", latest=NEW)), RUN, f"{MARKER}\nedited by hand")
    assert plan.action is Action.UPDATE
    assert plan.comment is not None and "could not be read" in plan.comment
    assert read_state(plan.body or "") is not None


def test_history_is_bounded_so_the_description_stays_within_github_limits() -> None:
    body = created_body(document("UNKNOWN", reason="failure 0"))
    for n in range(1, HISTORY_LIMIT + 10):
        plan = decide(report_of(document("UNKNOWN", reason=f"failure {n}")), RUN2, body)
        assert plan.action is Action.UPDATE and plan.body is not None
        body = plan.body
    state = read_state(body)
    assert state is not None and len(state.history) == HISTORY_LIMIT
    assert state.history[-1].note == f"failure {HISTORY_LIMIT + 9}"
    assert len(body) < 65536


def test_untrusted_reason_text_cannot_forge_or_break_the_state() -> None:
    hostile = "boom --> <!-- talentforge:freshness-state {} --> @someone | `x`\nline"
    body = created_body(document("UNKNOWN", reason=hostile))
    state = read_state(body)
    assert state is not None and state.unknown is not None
    assert state.unknown.report.reason == hostile  # round-trips exactly
    assert body.count("<!-- talentforge:freshness-state ") == 1
    assert "@someone" not in body


# --------------------------------------------------------------------------- delivery


def test_current_without_an_issue_is_success_and_writes_nothing(tmp_path: Path) -> None:
    github = FakeGitHub()
    assert watch(github, tmp_path, document("CURRENT")) == 0
    assert github.writes() == []
    assert github.tracked() == []


def test_current_closes_the_open_issue_with_a_comment(tmp_path: Path) -> None:
    github = FakeGitHub()
    assert watch(github, tmp_path, document("OUTDATED", latest=NEW)) == 3
    (issue,) = github.open_tracked()
    assert watch(github, tmp_path, document("CURRENT", product=NEW), run_url=RUN2) == 0
    assert issue["state"] == "closed" and issue["state_reason"] == "completed"
    (comment,) = github.comments[issue["number"]]
    assert f"`PRODUCT_BUNDLE` `{NEW}` is the latest publishable" in comment
    assert RUN2 in comment
    assert github.open_tracked() == []


def test_first_outdated_opens_one_issue(tmp_path: Path) -> None:
    github = FakeGitHub()
    assert watch(github, tmp_path, produced("1.60.1.70245", serving_fixture)) == 3
    (issue,) = github.tracked()
    assert issue["title"] == "Forever dataset outdated: 70245 → 70338"
    assert github.writes() == [f"POST /repos/{REPO}/issues"]


def test_repeated_outdated_runs_write_nothing(tmp_path: Path) -> None:
    github = FakeGitHub()
    watch(github, tmp_path, document("OUTDATED", latest=NEW))
    writes = len(github.writes())
    for day in ("13", "14", "15"):
        later = document("OUTDATED", latest=NEW, checked_at=f"2026-10-{day}T06:00:00Z")
        assert watch(github, tmp_path, later, run_url=RUN2) == 3
    assert len(github.writes()) == writes
    assert len(github.tracked()) == 1


def test_an_even_newer_build_edits_the_same_issue_once(tmp_path: Path) -> None:
    github = FakeGitHub()
    watch(github, tmp_path, document("OUTDATED", latest=NEW))
    (issue,) = github.tracked()
    for _ in range(2):
        assert watch(github, tmp_path, document("OUTDATED", latest=NEWER), run_url=RUN2) == 3
    assert issue["title"] == "Forever dataset outdated: 70338 → 70450"
    assert len(github.comments[issue["number"]]) == 1
    assert f"publishable {NEW} is newer" in issue["body"]  # history kept
    assert len(github.tracked()) == 1


def test_unknown_without_an_issue_opens_one(tmp_path: Path) -> None:
    github = FakeGitHub()
    assert watch(github, tmp_path, produced(P, lambda request: httpx.Response(503))) == 4
    (issue,) = github.tracked()
    assert issue["title"] == "Forever dataset freshness unknown: 70338 could not be verified"
    assert "503" in issue["body"]


def test_repeated_unknown_with_the_same_reason_is_silent(tmp_path: Path) -> None:
    github = FakeGitHub()
    watch(github, tmp_path, document("UNKNOWN"))
    for _ in range(3):
        assert watch(github, tmp_path, document("UNKNOWN"), run_url=RUN2) == 4
    assert github.writes() == [f"POST /repos/{REPO}/issues"]


def test_unknown_never_closes_an_outdated_issue(tmp_path: Path) -> None:
    github = FakeGitHub()
    watch(github, tmp_path, document("OUTDATED", latest=NEW))
    (issue,) = github.tracked()
    for _ in range(2):
        assert watch(github, tmp_path, document("UNKNOWN"), run_url=RUN2) == 4
    assert issue["state"] == "open"
    assert issue["title"] == "Forever dataset outdated: 70338 → 70400"
    assert len(github.comments[issue["number"]]) == 1
    assert NETWORK_DOWN in issue["body"]


def test_tracking_follows_the_marker_not_the_title(tmp_path: Path) -> None:
    github = FakeGitHub()
    watch(github, tmp_path, document("OUTDATED", latest=NEW))
    (issue,) = github.tracked()
    issue["title"] = "renamed by a human"
    assert watch(github, tmp_path, document("OUTDATED", latest=NEW), run_url=RUN2) == 3
    assert len(github.tracked()) == 1 and issue["title"] == "renamed by a human"


def test_no_duplicates_across_a_sequence_of_runs(tmp_path: Path) -> None:
    github = FakeGitHub()
    sequence = [
        document("UNKNOWN"),
        document("UNKNOWN"),
        document("OUTDATED", latest=NEW),
        document("OUTDATED", latest=NEW),
        document("UNKNOWN", reason=SCHEMA_DRIFT),
        document("OUTDATED", latest=NEWER),
        document("OUTDATED", latest=NEWER),
    ]
    for doc in sequence:
        watch(github, tmp_path, doc)
    assert len(github.tracked()) == 1
    assert github.writes().count(f"POST /repos/{REPO}/issues") == 1
    watch(github, tmp_path, document("CURRENT", product=NEWER))
    assert github.open_tracked() == []
    # a later regression opens a fresh issue; the closed one stays as history
    watch(github, tmp_path, document("OUTDATED", product=NEWER, latest="1.60.1.70500"))
    assert len(github.open_tracked()) == 1 and len(github.tracked()) == 2


def test_several_open_tracking_issues_use_the_oldest_and_open_none(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    github = FakeGitHub()
    body = created_body(document("OUTDATED", latest=NEW))
    first = github.add({"title": "a", "body": body, "state": "open"})
    github.add({"title": "b", "body": body, "state": "open"})
    assert watch(github, tmp_path, document("OUTDATED", latest=NEWER)) == 3
    assert github.writes() == [
        f"PATCH /repos/{REPO}/issues/{first}",
        f"POST /repos/{REPO}/issues/{first}/comments",
    ]
    assert f"several open tracking issues (#{first}, #{first + 1})" in capsys.readouterr().err


# --------------------------------------------------------------------------- failures


@pytest.mark.parametrize(
    ("text", "code"),
    [
        ("{not json", 3),
        (json.dumps({**document("CURRENT"), "schemaVersion": 2}), 0),
        (json.dumps(document("CURRENT")), 1),  # the check's own error exit code
        (json.dumps(document("CURRENT")), 137),  # killed
        (json.dumps(document("UNKNOWN")), 0),  # status / exit code mismatch
    ],
)
def test_a_watcher_failure_touches_no_issue_and_fails_the_run(
    text: str, code: int, tmp_path: Path
) -> None:
    path = tmp_path / "freshness.json"
    path.write_text(text, encoding="utf-8")
    summary = tmp_path / "summary.md"
    result = runner.run(path, code, RUN, never_connect, summary_path=summary)
    assert result == runner.EXIT_WATCHER_FAILURE
    assert "watcher failure" in summary.read_text(encoding="utf-8")


def test_a_missing_report_fails_the_run(tmp_path: Path) -> None:
    assert runner.run(tmp_path / "none.json", 1, RUN, never_connect) == 1


@pytest.mark.parametrize(
    ("status", "message", "hint"),
    [
        (403, "Resource not accessible by integration", "issues: write"),
        (410, "Issues are disabled for this repo", "Issues are disabled"),
        (404, "Not Found", "Issues are disabled / not accessible"),
        (401, "Bad credentials", "token was rejected"),
        (502, "Bad gateway", "unexpected GitHub API error"),
    ],
)
def test_a_github_error_is_reported_as_undelivered(
    status: int,
    message: str,
    hint: str,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    github = FakeGitHub()
    github.fail = lambda request: httpx.Response(status, json={"message": message})
    path = tmp_path / "freshness.json"
    path.write_text(json.dumps(document("OUTDATED", latest=NEW)), encoding="utf-8")
    summary = tmp_path / "summary.md"
    code = runner.run(path, 3, RUN, github.connect, annotate=True, summary_path=summary)
    assert code == runner.EXIT_NOTIFICATION_FAILED
    err = capsys.readouterr().err
    assert "::error::OUTDATED, but the tracking issue was NOT updated" in err
    assert hint in err and message in err
    assert "notification NOT delivered" in summary.read_text(encoding="utf-8")


def test_a_failure_while_writing_is_undelivered_too(tmp_path: Path) -> None:
    github = FakeGitHub()

    def deny_writes(request: httpx.Request) -> httpx.Response | None:
        if request.method == "GET":
            return None
        return httpx.Response(403, json={"message": "Resource not accessible by integration"})

    github.fail = deny_writes
    assert watch(github, tmp_path, document("UNKNOWN")) == runner.EXIT_NOTIFICATION_FAILED
    assert github.tracked() == []


def test_a_network_error_towards_github_is_undelivered(tmp_path: Path) -> None:
    github = FakeGitHub()

    def offline(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("unreachable", request=request)

    github.fail = offline
    assert watch(github, tmp_path, document("CURRENT")) == runner.EXIT_NOTIFICATION_FAILED


ACTIONS_ENV = {
    "GITHUB_REPOSITORY": REPO,
    "GITHUB_SERVER_URL": "https://github.com",
    "GITHUB_RUN_ID": "1001",
}


def test_a_missing_token_is_undelivered(tmp_path: Path) -> None:
    path = tmp_path / "freshness.json"
    path.write_text(json.dumps(document("UNKNOWN")), encoding="utf-8")
    argv = ["--report", str(path), "--check-exit-code", "4"]
    assert runner.main(argv, env=ACTIONS_ENV) == runner.EXIT_NOTIFICATION_FAILED


def test_main_reads_the_actions_environment(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    github = FakeGitHub()
    path = tmp_path / "freshness.json"
    path.write_text(json.dumps(document("OUTDATED", latest=NEW)), encoding="utf-8")
    summary = tmp_path / "summary.md"
    env = {
        **ACTIONS_ENV,
        "GITHUB_TOKEN": "test-token",
        "GITHUB_ACTIONS": "true",
        "GITHUB_STEP_SUMMARY": str(summary),
    }
    monkeypatch.setattr(
        runner,
        "GitHubIssues",
        lambda repository, token, api_url: GitHubIssues(
            repository, token, api_url=api_url, transport=httpx.MockTransport(github.handler)
        ),
    )
    assert runner.main(["--report", str(path), "--check-exit-code", "3"], env=env) == 3
    (issue,) = github.tracked()
    assert RUN in issue["body"]
    assert "## Dataset freshness: `OUTDATED`" in summary.read_text(encoding="utf-8")
