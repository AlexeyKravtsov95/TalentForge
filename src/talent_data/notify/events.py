"""The four notification events and their Russian rendering for the owner.

The texts are plain (no Markdown parse mode), one message per event. Two of them carry a
mandatory disclaimer the task fixes: presence in the wago.tools index is never called a
confirmation of what the game servers run, and ``PREPARED`` is never a permission to
publish to production.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class BuildDetected:
    """A publishable build newer than the product build appeared in the index."""

    build: str
    source: str
    product_build: str
    issue_url: str | None
    run_url: str


@dataclass(frozen=True)
class CandidatePrepared:
    """A preparation finished PREPARED (never publishable by itself)."""

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


@dataclass(frozen=True)
class ProductionUpdated:
    """The verified public production catalog activates a release it did not before."""

    release_id: str
    sequence: int
    origin: str
    checked: str
    run_url: str


@dataclass(frozen=True)
class StageFailed:
    """A preparation or check broke; an expected OUTDATED freshness answer never is one.

    ``fingerprint`` is the stage's deduplication id: the producer records it only after
    this very message was delivered, so an undelivered alert is retried by the next run
    of the same broken stage instead of being suppressed forever."""

    stage: str
    reason: str
    run_url: str
    fingerprint: str = ""


Event = BuildDetected | CandidatePrepared | ProductionUpdated | StageFailed


def render(event: Event) -> str:
    if isinstance(event, BuildDetected):
        issue = (
            f"Tracking issue: {event.issue_url}"
            if event.issue_url
            else "Tracking issue: открытый issue не найден, см. запуск"
        )
        return (
            "TalentForge — обнаружен новый игровой билд\n"
            "\n"
            f"Версия: {event.build}\n"
            f"Источник: {event.source} (is_bgdl=false)\n"
            f"Билд в приложении (PRODUCT_BUNDLE): {event.product_build}\n"
            f"{issue}\n"
            f"Проверка: {event.run_url}\n"
            "\n"
            "Наличие билда в индексе источника — не подтверждение того, что игровые "
            "серверы уже работают этим билдом."
        )
    if isinstance(event, CandidatePrepared):
        link = f"PR: {event.pr_url}\n" if event.pr_url else ""
        gates = ", ".join(event.gates_passed) or "—"
        required = ", ".join(event.gates_required) or "—"
        return (
            "TalentForge — кандидат OTA подготовлен\n"
            "\n"
            f"Кандидат: {event.release_id} (билд {event.build}, база {event.base})\n"
            f"Проверки: шаги {event.steps_passed}/{event.steps_total} PASS; "
            f"гейты PASS: {gates}; требуют владельца: {required}\n"
            f"{link}"
            f"Артефакт: {event.run_url} ({event.artifact_name})\n"
            "\n"
            "PREPARED — не разрешение на production-публикацию: публикация остаётся "
            "ручной процедурой владельца (approval record, promote, подпись)."
        )
    if isinstance(event, ProductionUpdated):
        return (
            "TalentForge — production OTA обновился\n"
            "\n"
            f"Активный релиз: {event.release_id}\n"
            f"sequence: {event.sequence}\n"
            f"Публичный endpoint: {event.checked}\n"
            f"Origin: {event.origin}\n"
            f"Проверка: {event.run_url}"
        )
    assert isinstance(event, StageFailed)
    return (
        "TalentForge — сбой подготовки/проверки OTA\n"
        "\n"
        f"Этап: {event.stage}\n"
        f"Причина: {event.reason}\n"
        f"Запуск: {event.run_url}"
    )
