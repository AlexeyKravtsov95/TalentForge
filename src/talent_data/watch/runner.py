"""Apply one freshness result to the tracked GitHub Issue; exit with the run's final status.

Exit codes (the final status of the scheduled job):

    0  CURRENT   handled (tracking issue closed, or none was open)
    3  OUTDATED  handled (tracking issue opened / updated / already up to date)
    4  UNKNOWN   handled (tracking issue opened / updated / already up to date)
    1  watcher failure: the check exited with another code, its JSON is missing or
       invalid, or contradicts its exit code - not a freshness answer; no issue is touched
    5  the notification could not be delivered (GitHub API error, Issues disabled,
       missing permission or token)

OUTDATED and UNKNOWN keep their non-zero codes on purpose: neither is a passed check.
"""

from __future__ import annotations

import argparse
import os
import sys
from collections.abc import Callable, Mapping
from pathlib import Path

from talent_data.cli.main import FRESHNESS_EXIT
from talent_data.watch.github import API_URL, GitHubIssues, GitHubIssuesError, Issue
from talent_data.watch.report import CheckFailure, FreshnessDocument, read_result
from talent_data.watch.tracking import MARKER, Action, Plan, decide

EXIT_WATCHER_FAILURE = 1
EXIT_NOTIFICATION_FAILED = 5


class _Log:
    def __init__(self, annotate: bool, summary: Path | None) -> None:
        self._annotate = annotate
        self._summary = summary

    def emit(self, level: str, message: str) -> None:
        prefix = f"::{level}::" if self._annotate else f"{level}: "
        print(prefix + message.replace("\n", " "), file=sys.stderr)

    def summary(self, lines: list[str]) -> None:
        if self._summary is None:
            return
        with self._summary.open("a", encoding="utf-8") as out:
            out.write("\n".join(lines) + "\n")


def run(
    report_path: Path,
    check_exit_code: int,
    run_url: str,
    connect: Callable[[], GitHubIssues],
    *,
    annotate: bool = False,
    summary_path: Path | None = None,
) -> int:
    log = _Log(annotate, summary_path)
    try:
        report = read_result(report_path, check_exit_code)
    except CheckFailure as exc:
        log.emit("error", f"freshness watcher failure (not CURRENT, not OUTDATED): {exc}")
        log.summary(
            [
                "## Dataset freshness: watcher failure",
                "",
                f"The check did not produce a trustworthy result: {exc}",
                "",
                "No tracking issue was changed. See the `freshness-report` artifact.",
            ]
        )
        return EXIT_WATCHER_FAILURE

    log.emit("notice", f"{report.status}: {report.reason}")
    try:
        # ValueError: no token / malformed repository - only from building the client, so a
        # bug elsewhere is never mistaken for an undelivered notification.
        try:
            client = connect()
        except ValueError as exc:
            raise GitHubIssuesError(str(exc)) from exc
        with client as issues:
            plan, issue = _apply(issues, report, run_url, log)
    except GitHubIssuesError as exc:
        log.emit("error", f"{report.status}, but the tracking issue was NOT updated: {exc}")
        log.summary(
            [
                f"## Dataset freshness: `{report.status}` - notification NOT delivered",
                "",
                f"- Reason: {report.reason}",
                f"- GitHub Issues error: {exc}",
            ]
        )
        return EXIT_NOTIFICATION_FAILED

    where = f" ({issue.html_url})" if issue is not None else ""
    log.emit("notice", f"tracking issue: {plan.summary}{where}")
    log.summary(
        [
            f"## Dataset freshness: `{report.status}`",
            "",
            f"- Product build: `{report.product_build}`",
            f"- Latest publishable: `{_latest(report)}`",
            f"- Reason: {report.reason}",
            f"- Tracking issue: {plan.summary}{where}",
        ]
    )
    return FRESHNESS_EXIT[report.status]


def _apply(
    issues: GitHubIssues, report: FreshnessDocument, run_url: str, log: _Log
) -> tuple[Plan, Issue | None]:
    tracked = issues.open_issues_with(MARKER)
    if len(tracked) > 1:
        numbers = ", ".join(f"#{i.number}" for i in tracked)
        log.emit(
            "warning",
            f"several open tracking issues ({numbers}): using #{tracked[0].number}; "
            "close the others by hand",
        )
    issue = tracked[0] if tracked else None
    plan = decide(report, run_url, None if issue is None else issue.body)
    if plan.action is Action.CREATE:
        assert plan.title is not None and plan.body is not None
        issue = issues.create(plan.title, plan.body)
    elif plan.action is Action.UPDATE:
        assert issue is not None and plan.title is not None and plan.body is not None
        # Description first: if the comment then fails, the next run does not repeat it.
        issues.edit(issue.number, plan.title, plan.body)
        if plan.comment is not None:
            issues.comment(issue.number, plan.comment)
    elif plan.action is Action.CLOSE:
        assert issue is not None and plan.comment is not None
        issues.comment(issue.number, plan.comment)
        issues.close_issue(issue.number)
    return plan, issue


def _latest(report: FreshnessDocument) -> str | None:
    return None if report.latest_publishable is None else report.latest_publishable.version


def main(argv: list[str] | None = None, env: Mapping[str, str] | None = None) -> int:
    env = os.environ if env is None else env
    parser = argparse.ArgumentParser(
        description="Apply a product-freshness result to the tracked GitHub Issue."
    )
    parser.add_argument("--report", type=Path, required=True, help="product-freshness JSON")
    parser.add_argument(
        "--check-exit-code", type=int, required=True, help="exit code of the freshness check"
    )
    parser.add_argument("--repository", default=env.get("GITHUB_REPOSITORY"), help="owner/name")
    parser.add_argument("--run-url", default=_default_run_url(env), help="this run's URL")
    parser.add_argument("--api-url", default=env.get("GITHUB_API_URL") or API_URL)
    args = parser.parse_args(argv)
    if not args.repository or not args.run_url:
        parser.error("--repository and --run-url are required outside GitHub Actions")
    summary = env.get("GITHUB_STEP_SUMMARY")

    def connect() -> GitHubIssues:
        return GitHubIssues(args.repository, env.get("GITHUB_TOKEN", ""), api_url=args.api_url)

    return run(
        args.report,
        args.check_exit_code,
        args.run_url,
        connect,
        annotate=env.get("GITHUB_ACTIONS") == "true",
        summary_path=Path(summary) if summary else None,
    )


def _default_run_url(env: Mapping[str, str]) -> str | None:
    server, repo, run_id = (
        env.get(k) for k in ("GITHUB_SERVER_URL", "GITHUB_REPOSITORY", "GITHUB_RUN_ID")
    )
    if server and repo and run_id:
        return f"{server}/{repo}/actions/runs/{run_id}"
    return None
