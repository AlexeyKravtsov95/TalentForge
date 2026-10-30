"""The Telegram Bot API client of the notifications: ``sendMessage`` with limited retries.

Fail-closed like the rest of the project: a message counts as sent only when Telegram
answered HTTP 200 with ``ok: true``. Anything else - a definitive 4xx, an exhausted
retry budget, an ambiguous network timeout - is :attr:`SendStatus.FAILED`, and the event
stays unnotified so a later run may send it again (at-least-once, never promised
exactly-once: a timed-out request may have been delivered).

HTTP 429 is honoured through ``parameters.retry_after``: the client waits that many
seconds and retries, but never longer than :data:`MAX_RETRY_WAIT` and never past
:data:`ATTEMPTS` attempts in total; a larger ``retry_after`` gives up instead of parking
the job. Server errors (5xx) and transport failures are retried with a short fixed pause.

The bot token never reaches a log, an error text, a raised exception or an artifact:
the API URL is built internally, error texts are assembled from the method name, the HTTP
status and Telegram's own ``description`` (never the URL), and every text is finally
passed through :func:`redact`, which replaces the token with ``***`` - httpx exceptions
carry the full request URL, and the token is part of that URL's path.
"""

from __future__ import annotations

import os
from collections.abc import Callable
from dataclasses import dataclass
from enum import StrEnum
from typing import Any

import httpx

API_URL = "https://api.telegram.org"
#: total attempts per message, retries included
ATTEMPTS = 3
#: never sleep longer than this to honour a 429 (a bigger retry_after gives up)
MAX_RETRY_WAIT = 30.0
#: pause between attempts after a 5xx or a transport failure
RETRY_PAUSE = 2.0


class SendStatus(StrEnum):
    SENT = "SENT"
    FAILED = "FAILED"


@dataclass(frozen=True)
class SendResult:
    status: SendStatus
    # Redacted, human-readable; shown in the job summary, never contains the token.
    detail: str
    attempts: int = 1


class TelegramError(RuntimeError):
    """A Telegram call failed. The message is already redacted."""


def redact(text: str, *secrets: str) -> str:
    """Replace every non-empty secret with ``***`` (token-in-URL leaks included)."""
    for secret in secrets:
        if secret:
            text = text.replace(secret, "***")
    return text


def utc_now() -> str:
    """The UTC timestamp format of the state documents (``2026-10-30T06:00:00Z``)."""
    from datetime import UTC, datetime

    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


@dataclass(frozen=True)
class _Response:
    status: int
    body: Any


class TelegramClient:
    """``sendMessage`` / ``getMe`` / ``getUpdates`` over one injectable transport."""

    def __init__(
        self,
        token: str,
        *,
        transport: httpx.BaseTransport | None = None,
        api_url: str = os.environ.get("TELEGRAM_API_URL", API_URL),
        sleeper: Callable[[float], None] = lambda seconds: None,
        attempts: int = ATTEMPTS,
        max_retry_wait: float = MAX_RETRY_WAIT,
    ) -> None:
        if not token:
            raise ValueError("a Telegram bot token is required")
        self._token = token
        self._sleeper = sleeper
        self._attempts = attempts
        self._max_retry_wait = max_retry_wait
        self._client = httpx.Client(
            base_url=api_url.rstrip("/"),
            timeout=httpx.Timeout(20.0),
            transport=transport,
            headers={"Content-Type": "application/json"},
        )

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> TelegramClient:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    # ------------------------------------------------------------------ operations

    def send_message(self, chat_id: str, text: str) -> SendResult:
        """Send one plain-text message (no preview). Never raises; see the module
        docstring for the retry and redaction rules."""
        payload: dict[str, Any] = {
            "chat_id": chat_id,
            "text": text,
            "disable_web_page_preview": True,
        }
        return self._call("sendMessage", payload)

    def get_me(self) -> str:
        """The bot's ``@username`` (the smoke test shows it). Raises TelegramError."""
        body = self._once("getMe", {})
        result = body.get("result") if isinstance(body, dict) else None
        username = result.get("username") if isinstance(result, dict) else None
        if not isinstance(username, str) or not username:
            raise TelegramError("getMe: no username in the answer")
        return username

    def get_updates(self) -> list[dict[str, Any]]:
        """Recent updates, oldest first (the setup helper lists private chats from them).
        Raises TelegramError - a bot with a webhook set cannot use getUpdates."""
        body = self._once("getUpdates", {"timeout": 0, "allowed_updates": ["message"]})
        result = body.get("result") if isinstance(body, dict) else None
        if not isinstance(result, list):
            raise TelegramError("getUpdates: no result list in the answer")
        return [u for u in result if isinstance(u, dict)]

    # ------------------------------------------------------------------ transport

    def _once(self, method: str, payload: dict[str, Any]) -> Any:
        """One POST, no retry; the answer's JSON body, or a redacted TelegramError."""
        try:
            response = self._client.post(f"/bot{self._token}/{method}", json=payload)
        except httpx.HTTPError as exc:
            raise TelegramError(self._redact(f"{method}: {type(exc).__name__}: {exc}")) from None
        try:
            body: Any = response.json()
        except ValueError:
            body = None
        if response.status_code == 200 and _ok(body):
            return body
        raise TelegramError(f"{method}: HTTP {response.status_code}: {_description_body(body)}")

    def _call(self, method: str, payload: dict[str, Any]) -> SendResult:
        for attempt in range(1, self._attempts + 1):
            try:
                response = self._client.post(f"/bot{self._token}/{method}", json=payload)
            except httpx.HTTPError as exc:
                detail = self._redact(f"{method}: {type(exc).__name__}: {exc}")
                if attempt < self._attempts:
                    self._sleeper(RETRY_PAUSE)
                    continue
                return SendResult(SendStatus.FAILED, detail, attempt)
            try:
                body: Any = response.json()
            except ValueError:
                body = None
            wrapped = _Response(response.status_code, body)
            result = _interpret_send(method, wrapped)
            if result.status is SendStatus.SENT:
                return SendResult(SendStatus.SENT, result.detail, attempt)
            if response.status_code == 429:
                wait = _retry_after(body)
                if wait is None or wait > self._max_retry_wait or attempt >= self._attempts:
                    waited = "unknown" if wait is None else f"{wait:.0f}s"
                    budget = f"{self._max_retry_wait:.0f}s"
                    return SendResult(
                        SendStatus.FAILED,
                        f"{method}: rate limited, retry_after {waited} not waited "
                        f"(attempts {attempt}/{self._attempts}, budget {budget})",
                        attempt,
                    )
                self._sleeper(wait)
                continue
            if _retryable(wrapped) and attempt < self._attempts:
                self._sleeper(RETRY_PAUSE)
                continue
            return SendResult(SendStatus.FAILED, result.detail, attempt)
        raise AssertionError("unreachable")  # pragma: no cover - the loop always returns

    def _redact(self, text: str) -> str:
        return redact(text, self._token)


def _interpret_send(method: str, response: _Response) -> SendResult:
    """One sendMessage answer: SENT only on HTTP 200 with ``ok: true``."""
    if response.status == 200 and _ok(response.body):
        return SendResult(SendStatus.SENT, "delivered")
    return SendResult(
        SendStatus.FAILED, f"{method}: HTTP {response.status}: {_description(response)}"
    )


def _ok(body: Any) -> bool:
    return isinstance(body, dict) and body.get("ok") is True


def _description(response: _Response) -> str:
    return _description_body(response.body)


def _description_body(body: Any) -> str:
    if isinstance(body, dict) and isinstance(body.get("description"), str):
        return str(body["description"])
    return "no description"


def _retry_after(body: Any) -> float | None:
    if isinstance(body, dict):
        parameters = body.get("parameters")
        if isinstance(parameters, dict):
            value = parameters.get("retry_after")
            if isinstance(value, int | float) and not isinstance(value, bool) and value >= 0:
                return float(value)
    return None


def _retryable(response: _Response) -> bool:
    if response.status >= 500:
        return True
    # 200 without ok:true (a captive portal?) is ambiguous: one retry, then give up
    return response.status == 200 and not _ok(response.body)
