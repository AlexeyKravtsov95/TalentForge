"""Durable, strict publication state has no implicit reset or terminal replay."""

import json

import pytest

from talent_data.ota.automation.requests import parse_request
from talent_data.ota.automation.state import (
    FileJournal,
    JournalError,
    PublicationReceipt,
    RequestState,
    parse_receipt,
)
from tests.unit.ota.test_automation_requests import request_wire


def receipt():
    return PublicationReceipt.pending(
        parse_request(json.dumps(request_wire()).encode()), run_id=9, attempt=1
    )


def test_missing_or_corrupt_journal_blocks_write(tmp_path):
    journal = FileJournal(tmp_path / "journal.json")
    with pytest.raises(JournalError):
        journal.save(receipt())
    journal.initialize()
    journal.path.write_bytes(b"{}")
    with pytest.raises(JournalError):
        journal.save(receipt())
    with pytest.raises(JournalError):
        journal.initialize()


def test_receipt_is_closed_and_preserves_pins(tmp_path):
    item = receipt()
    raw = item.model_dump_json(by_alias=True).encode()
    assert parse_receipt(raw) == item
    for update in ({"runId": True}, {"schemaVersion": True}, {"extra": 1}, {"targetSequence": "2"}):
        with pytest.raises(ValueError):
            parse_receipt(json.dumps({**json.loads(raw), **update}).encode())
    journal = FileJournal(tmp_path / "journal.json")
    journal.initialize()
    journal.save(item)
    assert journal.load(item.request_digest).receipt == item


def test_old_request_never_reactivates_after_rollback(tmp_path):
    journal = FileJournal(tmp_path / "journal.json")
    journal.initialize()
    item = receipt()
    journal.save(item)
    terminal = item.evolve(state=RequestState.STALE, reason="LIVE_CHANGED")
    journal.save(terminal)
    with pytest.raises(JournalError):
        journal.save(item)


def test_git_journal_is_durable_and_missing_branch_never_resets(tmp_path):
    import subprocess

    from talent_data.ota.automation.state import GitJournal

    def git(*args):
        return subprocess.run(["git", *map(str, args)], check=True, capture_output=True)

    remote, local = tmp_path / "origin.git", tmp_path / "checkout"
    git("init", "--bare", remote)
    git("init", local)
    git("-C", local, "remote", "add", "origin", remote)
    journal = GitJournal(local)
    with pytest.raises(JournalError):
        journal.save(receipt())
    journal.initialize()
    item = receipt()
    journal.save(item)
    assert GitJournal(local).load(item.request_digest).receipt == item
    with pytest.raises(JournalError):
        journal.initialize()
    git("-C", remote, "update-ref", "-d", "refs/heads/ota-publication-state")
    with pytest.raises(JournalError):
        journal.save(item)
    assert git("-C", remote, "for-each-ref", "refs/heads/ota-publication-state").stdout == b""


def test_directory_fsync_failure_blocks_journal_save(tmp_path, monkeypatch):
    import os
    import stat

    journal = FileJournal(tmp_path / "journal.json")
    journal.initialize()
    original = os.fsync

    def fsync(fd):
        if stat.S_ISDIR(os.fstat(fd).st_mode):
            raise OSError("TEST directory durability failure")
        return original(fd)

    monkeypatch.setattr(os, "fsync", fsync)
    with pytest.raises(JournalError):
        journal.save(receipt())
