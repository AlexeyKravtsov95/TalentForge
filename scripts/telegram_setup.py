#!/usr/bin/env python3
"""Local, interactive setup of the Telegram notifications recipient (the owner's machine).

Safe by construction, per the task's rules:

* read-only until the owner explicitly chooses: it only calls ``getMe`` and ``getUpdates``
  and lists the *private* chats that recently messaged the bot (after you send it
  ``/start`` from your Telegram account);
* nothing is ever sent to the first chat found: the recipient is the chat you pick by
  number, and a check message goes out only after an explicit ``y`` on the
  "send a test message?" question (default: no);
* the bot token never appears in the output: errors are redacted by the shared client.

What to do with the chosen chat id: add it as the secret ``TELEGRAM_CHAT_ID`` of the
GitHub Environment ``notifications`` (next to ``TELEGRAM_BOT_TOKEN``), then run the
manual "Telegram smoke test" workflow. See ``docs/ota/telegram-notifications.md``.

    TELEGRAM_BOT_TOKEN=123:abc python scripts/telegram_setup.py           # list + choose
    TELEGRAM_BOT_TOKEN=123:abc python scripts/telegram_setup.py --list    # list only
"""

from __future__ import annotations

import argparse
import os
import sys
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from talent_data.notify.telegram import TelegramClient, TelegramError

EXIT_OK = 0
EXIT_NO_CHATS = 1
EXIT_BAD_SELECTION = 2
_TEST_TEXT = (
    "TalentForge — проверка уведомлений (настройка)\n\n"
    "Вы явно выбрали этот чат получателем личных уведомлений."
)


@dataclass(frozen=True)
class ChatCandidate:
    chat_id: int
    title: str  # "Иван Петров (@ivan)" or the raw first name
    last_text: str
    date: str  # UTC, the last message's

    def line(self, index: int) -> str:
        return f"  {index}. {self.title} — chat_id {self.chat_id} (последнее: {self.last_text})"


def _iso(seconds: int | None) -> str:
    if seconds is None:
        return "?"
    return datetime.fromtimestamp(seconds, UTC).strftime("%Y-%m-%d %H:%M:%SZ")


def candidate_chats(updates: list[dict[str, Any]]) -> list[ChatCandidate]:
    """The private chats that recently messaged the bot, newest message first, one entry
    per chat. Group chats, channels and non-message updates are not candidates."""
    latest: dict[int, ChatCandidate] = {}
    stamps: dict[int, int] = {}
    for update in updates:
        message = update.get("message")
        if not isinstance(message, dict):
            continue
        chat = message.get("chat")
        if not isinstance(chat, dict) or chat.get("type") != "private":
            continue
        chat_id = chat.get("id")
        if not isinstance(chat_id, int) or isinstance(chat_id, bool):
            continue
        from_ = message.get("from") if isinstance(message.get("from"), dict) else {}
        names = [
            str(n)
            for n in (from_.get("first_name"), from_.get("last_name"))
            if isinstance(n, str) and n
        ]
        username = from_.get("username")
        title = " ".join(names) or "личный чат"
        if isinstance(username, str) and username:
            title += f" (@{username})"
        text = message.get("text")
        date = message.get("date")
        candidate = ChatCandidate(
            chat_id=chat_id,
            title=title,
            last_text=text if isinstance(text, str) else "(не текст)",
            date=_iso(date if isinstance(date, int) else None),
        )
        stamp = date if isinstance(date, int) else 0
        if chat_id not in stamps or stamp >= stamps[chat_id]:
            latest[chat_id] = candidate
            stamps[chat_id] = stamp
    return sorted(latest.values(), key=lambda c: stamps[c.chat_id], reverse=True)


def pick(chats: list[ChatCandidate], answer: str) -> ChatCandidate | None:
    """The chat the owner explicitly chose by its 1-based number, or ``None``."""
    text = answer.strip()
    if not text.isdigit():
        return None
    index = int(text)
    if not 1 <= index <= len(chats):
        return None
    return chats[index - 1]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Choose the Telegram chat that receives TalentForge notifications"
    )
    parser.add_argument("--token", default="", help="bot token (default: $TELEGRAM_BOT_TOKEN)")
    parser.add_argument(
        "--list", action="store_true", help="only list the candidate chats, choose nothing"
    )
    args = parser.parse_args(argv)

    token = args.token or os.environ.get("TELEGRAM_BOT_TOKEN", "")
    if not token:
        print("error: передайте токен: --token или переменная TELEGRAM_BOT_TOKEN", file=sys.stderr)
        return EXIT_BAD_SELECTION
    try:
        with TelegramClient(token) as client:
            bot = client.get_me()
            print(f"Бот: @{bot}")
            chats = candidate_chats(client.get_updates())
            if not chats:
                print(
                    "Личные чаты не найдены. Откройте диалог с ботом и отправьте ему /start,\n"
                    "затем запустите этот помощник снова (getUpdates помнит ~24 часа)."
                )
                return EXIT_NO_CHATS
            print("Кандидаты личных чатов (новый — выше):")
            for index, chat in enumerate(chats, start=1):
                print(chat.line(index))
            if args.list:
                return EXIT_OK
            chosen = pick(chats, input("Введите номер чата получателя: "))
            if chosen is None:
                print(f"error: нужно ввести номер из списка (1..{len(chats)})", file=sys.stderr)
                return EXIT_BAD_SELECTION
            print()
            print(f"Выбран получатель: {chosen.title}")
            print(f"TELEGRAM_CHAT_ID = {chosen.chat_id}")
            print(
                "Добавьте это значение в секрет TELEGRAM_CHAT_ID окружения `notifications`\n"
                "репозитория (рядом с TELEGRAM_BOT_TOKEN), затем запустите workflow\n"
                "Actions → Telegram smoke test."
            )
            answer = input("Отправить проверочное сообщение в этот чат сейчас? [y/N] ")
            if answer.strip().lower() in {"y", "yes", "д", "да"}:
                result = client.send_message(str(chosen.chat_id), _TEST_TEXT)
                print(
                    "Проверочное сообщение доставлено."
                    if result.status.value == "SENT"
                    else f"Проверочное сообщение НЕ доставлено: {result.detail}"
                )
            else:
                print("Проверочное сообщение не отправлялось (smoke-test workflow проверит позже).")
            return EXIT_OK
    except TelegramError as exc:
        print(f"error: Telegram недоступен или токен неверен: {exc}", file=sys.stderr)
        return EXIT_BAD_SELECTION


if __name__ == "__main__":
    raise SystemExit(main())
