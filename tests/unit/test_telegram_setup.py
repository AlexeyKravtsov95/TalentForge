"""The local setup helper of the Telegram notifications (M5.NOTIFY), offline.

The pure parts - which chats are candidates (private chats only, one entry per chat, the
newest message first) and what an explicit pick means - plus the interactive flow with a
fake client and faked answers: the recipient is only ever the explicitly chosen chat, the
check message goes out only after an explicit yes, and nothing is ever sent to the first
chat found by accident.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from typing import Any

from talent_data.notify.telegram import SendResult, SendStatus

SETUP_PATH = Path(__file__).resolve().parents[2] / "scripts" / "telegram_setup.py"


def setup() -> Any:
    spec = importlib.util.spec_from_file_location("telegram_setup", SETUP_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def update(
    chat_id: int,
    first: str,
    text: str,
    date: int,
    username: str | None = None,
    chat_type: str = "private",
) -> dict[str, Any]:
    frm: dict[str, Any] = {"first_name": first, "id": chat_id}
    if username:
        frm["username"] = username
    return {
        "update_id": date,
        "message": {
            "chat": {"id": chat_id, "type": chat_type},
            "from": frm,
            "text": text,
            "date": date,
        },
    }


def test_private_chats_are_candidates_latest_message_wins() -> None:
    module = setup()
    updates = [
        update(101, "Ivan", "/start", 100),
        update(-1002, "Group", "/start", 300, chat_type="group"),  # a group: not a candidate
        {"update_id": 9},  # not a message
        update(202, "Anna", "hi", 200, username="anna"),
        update(101, "Ivan", "one more", 400),  # the newer message of chat 101
    ]
    chats = module.candidate_chats(updates)
    assert [c.chat_id for c in chats] == [101, 202]  # newest first
    assert chats[0].last_text == "one more"
    assert chats[1].title == "Anna (@anna)"


def test_a_pick_is_explicit_and_validated() -> None:
    module = setup()
    chats = module.candidate_chats([update(101, "A", "/start", 1), update(202, "B", "/start", 2)])
    assert module.pick(chats, "1") is chats[0]  # explicit 1-based choice
    assert module.pick(chats, " 2 ") is chats[1]
    for bad in ("", "0", "3", "abc", "first"):
        assert module.pick(chats, bad) is None


class FakeClient:
    def __init__(self, updates: list[dict[str, Any]]) -> None:
        self.updates = updates
        self.sent: list[tuple[str, str]] = []

    def __enter__(self) -> FakeClient:
        return self

    def __exit__(self, *exc: object) -> None:
        return None

    def get_me(self) -> str:
        return "tf_notify_bot"

    def get_updates(self) -> list[dict[str, Any]]:
        return self.updates

    def send_message(self, chat_id: str, text: str) -> SendResult:
        self.sent.append((chat_id, text))
        return SendResult(SendStatus.SENT, "delivered")


def run_setup(monkeypatch: Any, fake: FakeClient, answers: list[str], argv: list[str]) -> int:
    module = setup()
    monkeypatch.setattr(module, "TelegramClient", lambda token: fake)
    inputs = iter(answers)
    monkeypatch.setattr("builtins.input", lambda prompt="": next(inputs))
    return module.main(argv)


def test_nothing_is_sent_without_an_explicit_yes(monkeypatch: Any, capsys: Any) -> None:
    fake = FakeClient([update(424242, "Owner", "/start", 500)])
    code = run_setup(monkeypatch, fake, ["1", ""], ["--token", "t"])  # default answer: no
    assert code == 0 and fake.sent == []
    out = capsys.readouterr().out
    assert "TELEGRAM_CHAT_ID = 424242" in out
    assert "не отправлялось" in out


def test_the_check_message_goes_only_to_the_chosen_chat(monkeypatch: Any) -> None:
    fake = FakeClient(
        [update(111, "Friend", "/start", 100), update(424242, "Owner", "/start", 900)]
    )
    # the owner's chat is the newest, i.e. number 1 - and only the explicit pick is used
    code = run_setup(monkeypatch, fake, ["1", "y"], ["--token", "t"])
    assert code == 0
    [(chat_id, text)] = fake.sent
    assert chat_id == "424242" and "выбрали" in text


def test_listing_sends_nothing_and_chooses_nothing(monkeypatch: Any) -> None:
    fake = FakeClient([update(424242, "Owner", "/start", 500)])
    code = run_setup(monkeypatch, fake, [], ["--token", "t", "--list"])
    assert code == 0 and fake.sent == []


def test_without_a_start_there_are_no_candidates(monkeypatch: Any) -> None:
    fake = FakeClient([])
    code = run_setup(monkeypatch, fake, [], ["--token", "t"])
    assert code == 1 and fake.sent == []


def test_a_bad_selection_is_rejected_without_sending(monkeypatch: Any) -> None:
    fake = FakeClient([update(424242, "Owner", "/start", 500)])
    code = run_setup(monkeypatch, fake, ["7"], ["--token", "t"])
    assert code == 2 and fake.sent == []
