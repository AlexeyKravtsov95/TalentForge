"""Command line of the Telegram notifications: ``freshness`` / ``candidate`` /
``production`` / ``smoke`` (``scripts/telegram_notify.py``).

Each command gathers its facts from the reports the existing workflows already produce,
decides the events with :mod:`talent_data.notify.state` (durable deduplication), sends
them and records the state. Exit codes, in line with the task's rules:

* ``0`` - done: events sent, nothing to send, or the Telegram send failed (the failure is
  the notification's own status in the summary, never the preparation's or publication's
  result; the event stays unnotified and the next run retries it);
* ``1`` - the notification machinery itself is broken (an invalid state document, the
  state branch unreadable or unwritable, bad arguments) - the notify job is
  ``continue-on-error``, so the pipeline it reports on stays unaffected.

Missing secrets are an explicit ``SKIPPED`` in the summary, exit ``0``. Delivery is
at-least-once: a state branch that cannot be pushed after a successful send is reported
as such (the message may repeat on the next run).
"""

from __future__ import annotations

import argparse
import os
from collections.abc import Callable, Mapping
from datetime import UTC, datetime
from pathlib import Path
from typing import TYPE_CHECKING, Any

import httpx

from talent_data.notify.events import Event, render
from talent_data.notify.gitstate import GitStateError, download, upload
from talent_data.notify.production_watch import observe_production
from talent_data.notify.state import (
    CandidateDedup,
    Decision,
    FreshnessDedup,
    ProductionDedup,
    StateError,
    baseline,
    decide_candidate,
    decide_freshness,
    decide_production,
    initial_candidate,
    initial_freshness,
    initial_production,
    load_dedup,
    save_dedup,
)
from talent_data.notify.telegram import SendResult, SendStatus, TelegramClient, utc_now

if TYPE_CHECKING:
    from talent_data.prepare.propose import PreparationReport
    from talent_data.watch.report import FreshnessDocument

EXIT_OK = 0
EXIT_FAILURE = 1

Sender = Callable[[str], SendResult]
Handler = Callable[[argparse.Namespace, Mapping[str, str], Sender, "_Summary"], "tuple[int, bool]"]

#: the preparation outcomes the workflow's "Record the outcome" step can report
CANDIDATE_OUTCOMES = (
    "PREPARED",
    "NO_ACTION",
    "DETECT_UNKNOWN",
    "DETECT_FAILED",
    "BLOCKED",
    "UNKNOWN",
    "FAILED",
)
PROPOSE_OUTCOMES = (
    "OPENED",
    "EXISTS",
    "ALREADY_ON_MAIN",
    "DECLINED",
    "REFUSED",
    "CONFLICT",
    "FAILED",
)
GAME = "forever"


class _Summary:
    """Lines for the job summary (and the log; nothing here ever holds the token)."""

    def __init__(self, path: str | None) -> None:
        self._path = Path(path) if path else None

    def append(self, *lines: str) -> None:
        text = "\n".join(lines) + "\n"
        print(text, end="")
        if self._path is not None:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            with self._path.open("a", encoding="utf-8") as out:
                out.write(text)


def _now() -> datetime:
    return datetime.now(UTC).replace(microsecond=0)


def _run_url(env: Mapping[str, str]) -> str:
    server, repo, run = (
        env.get(k) for k in ("GITHUB_SERVER_URL", "GITHUB_REPOSITORY", "GITHUB_RUN_ID")
    )
    if server and repo and run:
        return f"{server}/{repo}/actions/runs/{run}"
    raise SystemExit("error: --run-url is required outside GitHub Actions")


def _state_path(args: argparse.Namespace, name: str) -> Path:
    if args.state_dir is not None:
        directory: Path = args.state_dir
        return directory / name
    file: Path = args.state_file
    return file


# ---------------------------------------------------------------------- freshness


def read_freshness(
    report_dir: Path, check_exit_code: str
) -> tuple[FreshnessDocument | None, str | None]:
    """(document, check_failure) - exactly one is set; a check that cannot be trusted is
    a technical failure of the check, never a freshness answer."""
    from talent_data.watch.report import CheckFailure, read_result

    code_text = check_exit_code.strip()
    code_file = report_dir / "exit-code.txt"
    if not code_text and code_file.is_file():
        code_text = code_file.read_text(encoding="utf-8").strip()
    if not code_text:
        return None, "проверка свежести не записала свой код завершения"
    if not code_text.lstrip("-").isdigit():
        return None, f"проверка свежести завершилась с нечисловым кодом {code_text!r}"
    report = report_dir / "freshness.json"
    if not report.is_file():
        return None, "отчёт проверки свежести отсутствует (артефакт freshness-report)"
    try:
        return read_result(report, int(code_text)), None
    except CheckFailure as exc:
        return None, f"результат проверки свежести недостоверен: {exc}"
    except OSError as exc:
        return None, f"отчёт проверки свежести не читается: {exc}"


def cmd_freshness(
    args: argparse.Namespace, env: Mapping[str, str], send: Sender, summary: _Summary
) -> tuple[int, bool]:
    document, check_failure = read_freshness(args.report_dir, args.check_exit_code)
    path = _state_path(args, "freshness.json")
    state = load_dedup(FreshnessDedup, path)
    first = state is None
    decision = decide_freshness(
        state if state is not None else initial_freshness(utc_now()),
        document=document,
        check_failure=check_failure,
        issue_url=args.issue_url or None,
        run_url=args.run_url or _run_url(env),
        now=utc_now(),
    )
    if first:
        decision = baseline(decision)
    _deliver(decision, path, send, summary, first_run=first)
    return EXIT_OK, True


# ---------------------------------------------------------------------- candidate


def _read_preparation_report(path: Path) -> tuple[PreparationReport | None, str | None]:
    """The strict reader the proposal reuses; a missing or invalid report is a fact, not
    a crash of the notification."""
    from pydantic import ValidationError

    from talent_data.prepare.propose import PreparationReport

    try:
        return PreparationReport.model_validate_json(path.read_bytes()), None
    except FileNotFoundError:
        return None, f"report.json отсутствует ({path})"
    except (OSError, ValidationError) as exc:
        return (
            None,
            f"report.json не читается как ota-prepare-report v1: {type(exc).__name__}: {exc}",
        )


def _read_detection_reason(path: Path) -> str | None:
    """The detection's own reason, quoted briefly in a detect failure."""
    import json

    try:
        document: Any = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if isinstance(document, dict) and isinstance(document.get("reason"), str):
        reason: str = document["reason"]
        return reason[:200] + ("…" if len(reason) > 200 else "")
    return None


def cmd_candidate(
    args: argparse.Namespace, env: Mapping[str, str], send: Sender, summary: _Summary
) -> tuple[int, bool]:
    report, report_problem = _read_preparation_report(args.report)
    detection_reason = _read_detection_reason(args.detection)
    if report_problem is not None:
        summary.append(f"note: {report_problem}")
    path = _state_path(args, "candidate.json")
    state = load_dedup(CandidateDedup, path)
    first = state is None
    decision = decide_candidate(
        state if state is not None else initial_candidate(utc_now()),
        outcome=args.outcome,
        report=report,
        detection_reason=detection_reason,
        propose_outcome=args.propose_outcome,
        propose_result=args.propose_result,
        pr_url=args.pr_url or None,
        artifact_name=args.artifact or "артефакт не назван",
        run_url=args.run_url or _run_url(env),
        now=utc_now(),
    )
    if first:
        decision = baseline(decision)
    _deliver(decision, path, send, summary, first_run=first)
    return EXIT_OK, True


# ---------------------------------------------------------------------- production


def cmd_production(
    args: argparse.Namespace, env: Mapping[str, str], send: Sender, summary: _Summary
) -> tuple[int, bool]:
    from talent_data.ota.preflight import production_keyring
    from talent_data.ota.s3_channels import PRODUCTION_PROFILE

    keyring = production_keyring(args.repo / args.keyring)
    origin = args.production_origin or PRODUCTION_PROFILE.public_origin
    with httpx.Client(follow_redirects=False, timeout=30.0) as client:
        observation = observe_production(
            client, origin=origin, keyring=keyring, game=GAME, now=_now()
        )
    summary.append(f"production: {observation.status.value} - {observation.reason}")
    path = _state_path(args, "production.json")
    state = load_dedup(ProductionDedup, path)
    first = state is None
    decision = decide_production(
        state if state is not None else initial_production(utc_now()),
        observation=observation,
        run_url=args.run_url or _run_url(env),
        now=utc_now(),
    )
    if first:
        decision = baseline(decision)
    _deliver(decision, path, send, summary, first_run=first)
    return EXIT_OK, True


# ---------------------------------------------------------------------- delivery


def _deliver(
    decision: Decision[Any], path: Path, send: Sender, summary: _Summary, *, first_run: bool
) -> str:
    """Send the events, persist the state when they are all delivered (or none were),
    and report the notification's own status. Never raises for a send failure."""
    note = (
        " (первый запуск: база зафиксирована, старые события не отправлялись)" if first_run else ""
    )
    if not decision.events:
        save_dedup(path, decision.state)
        summary.append(
            f"## Telegram-уведомления: нет событий{note}",
            "",
            f"Состояние дедупликации обновлено: {path.name}.",
        )
        return "QUIET"
    failures: list[str] = []
    for event in decision.events:
        result = send(render(event))
        if result.status is SendStatus.SENT:
            summary.append(f"- отправлено: {_title(event)}")
        else:
            failures.append(result.detail)
            summary.append(f"- НЕ отправлено: {_title(event)} — {result.detail}")
    if failures:
        summary.append(
            "## Telegram-уведомления: FAILED",
            "",
            "События не доставлены; состояние не записано — следующий запуск повторит "
            "отправку (возможно повторение уже дошедшего сообщения: доставка не exactly-once).",
        )
        return "FAILED"
    save_dedup(path, decision.state)
    summary.append(
        f"## Telegram-уведомления: SENT ({len(decision.events)}){note}",
        "",
        f"Состояние дедупликации обновлено: {path.name}.",
    )
    return "SENT"


def _title(event: Event) -> str:
    return render(event).splitlines()[0]


# ---------------------------------------------------------------------- smoke


def cmd_smoke(env: Mapping[str, str], summary: _Summary) -> int:
    from talent_data.notify.telegram import TelegramError

    token = env.get("TELEGRAM_BOT_TOKEN", "").strip()
    chat = env.get("TELEGRAM_CHAT_ID", "").strip()
    if not token or not chat:
        summary.append(
            "## Telegram-уведомления: SKIPPED",
            "",
            "Секреты TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID не настроены "
            "(Settings → Environments → `notifications` → Environment secrets).",
        )
        return EXIT_OK
    with TelegramClient(token) as client:
        try:
            bot = client.get_me()
        except TelegramError as exc:
            summary.append("## Telegram smoke test: FAILED", "", f"getMe: {exc}")
            return EXIT_FAILURE
        result = client.send_message(
            chat,
            "TalentForge — проверка уведомлений (smoke test)\n\n"
            "Если вы видите это сообщение, личные уведомления настроены верно.",
        )
    if result.status is SendStatus.SENT:
        summary.append(
            "## Telegram smoke test: SENT",
            "",
            f"Бот @{bot} доставил проверочное сообщение в выбранный чат.",
        )
        return EXIT_OK
    summary.append("## Telegram smoke test: FAILED", "", result.detail)
    return EXIT_FAILURE


# ---------------------------------------------------------------------- main


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="telegram_notify.py",
        description="Personal Telegram notifications about new game data and OTA progress",
    )

    def common(p: argparse.ArgumentParser, state_file: str) -> None:
        group = p.add_mutually_exclusive_group(required=True)
        group.add_argument(
            "--state-dir",
            type=Path,
            help="CI mode: a git workdir of the telegram-notify-state branch",
        )
        group.add_argument(
            "--state-file", type=Path, help=f"local mode: this {state_file} directly"
        )
        p.add_argument(
            "--run-url", default="", help="link to this run (default: from GITHUB_* env)"
        )

    sub = parser.add_subparsers(dest="command", required=True)

    fresh = sub.add_parser("freshness", help="the daily freshness answer")
    fresh.add_argument("--report-dir", type=Path, required=True, help="the freshness artifact")
    fresh.add_argument("--check-exit-code", default="", help="the check's exit code")
    fresh.add_argument("--issue-url", default="", help="the open tracking issue's URL")
    common(fresh, "freshness.json")

    cand = sub.add_parser("candidate", help="one OTA data preparation run")
    cand.add_argument("--outcome", required=True, choices=CANDIDATE_OUTCOMES)
    cand.add_argument("--report", type=Path, required=True, help="prep/out/report.json")
    cand.add_argument("--detection", type=Path, required=True, help="prep/detection.json")
    cand.add_argument("--propose-outcome", default="", choices=["", *PROPOSE_OUTCOMES])
    cand.add_argument(
        "--propose-result", default="", help="the propose job's result (success/failure/skipped)"
    )
    cand.add_argument("--pr-url", default="", help="the candidate review PR's URL")
    cand.add_argument("--artifact", default="", help="this run's uploaded artifact name")
    common(cand, "candidate.json")

    prod = sub.add_parser("production", help="observe the public production catalog")
    prod.add_argument("--repo", type=Path, default=Path.cwd(), help="checkout (default: cwd)")
    prod.add_argument(
        "--production-origin", default="", help="default: the committed production profile"
    )
    prod.add_argument(
        "--keyring",
        type=Path,
        default=Path("docs/ota/keys/production-keyring.json"),
        help="committed production public keyring, relative to --repo",
    )
    common(prod, "production.json")

    sub.add_parser("smoke", help="verify the token and the chosen chat (manual)")
    return parser


def main(argv: list[str] | None = None, env: Mapping[str, str] | None = None) -> int:
    env = os.environ if env is None else env
    args = _parser().parse_args(argv)
    summary = _Summary(env.get("GITHUB_STEP_SUMMARY"))

    if args.command == "smoke":
        return cmd_smoke(env, summary)

    token = env.get("TELEGRAM_BOT_TOKEN", "").strip()
    chat = env.get("TELEGRAM_CHAT_ID", "").strip()
    if not token or not chat:
        summary.append(
            "## Telegram-уведомления: SKIPPED",
            "",
            "Секреты TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID не настроены "
            "(Settings → Environments → `notifications` → Environment secrets). "
            "Ничего не отправлено и состояние дедупликации не менялось.",
        )
        return EXIT_OK

    handlers: dict[str, Handler] = {
        "freshness": cmd_freshness,
        "candidate": cmd_candidate,
        "production": cmd_production,
    }
    handler = handlers[args.command]

    git_token = env.get("GITHUB_TOKEN", "").strip()
    if args.state_dir is not None and not git_token:
        summary.append(
            "## Telegram-уведомления: FAILED",
            "",
            "GITHUB_TOKEN не задан (режиму --state-dir он нужен для ветки состояния).",
        )
        return EXIT_FAILURE

    try:
        if args.state_dir is not None:
            existed = download(
                args.state_dir,
                repository=env.get("GITHUB_REPOSITORY", ""),
                server_url=env.get("GITHUB_SERVER_URL", "https://github.com"),
                token=git_token,
            )
            if not existed:
                summary.append(
                    "ветка состояния не найдена — первый запуск (база фиксируется без рассылки)."
                )
        with TelegramClient(token) as client:
            _code, saved = handler(args, env, lambda text: client.send_message(chat, text), summary)
        if args.state_dir is not None and saved:
            try:
                upload(
                    args.state_dir,
                    token=git_token,
                    server_url=env.get("GITHUB_SERVER_URL", "https://github.com"),
                    message=f"notify: {args.command} {utc_now()}",
                )
            except GitStateError as exc:
                summary.append(
                    "## Telegram-уведомления: состояние НЕ сохранено",
                    "",
                    f"Сообщения могли быть доставлены, но ветка состояния не обновлена: {exc}. "
                    "Возможно повторение уведомления (доставка не exactly-once).",
                )
                return EXIT_FAILURE
    except (GitStateError, StateError) as exc:
        summary.append("## Telegram-уведомления: FAILED", "", str(exc))
        return EXIT_FAILURE
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
