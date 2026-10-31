#!/usr/bin/env python3
"""Personal Telegram notifications for the owner (thin wrapper).

Runs one command of ``talent_data.notify.cli``:

    python scripts/telegram_notify.py freshness  --report-dir freshness --check-exit-code 3
    python scripts/telegram_notify.py candidate  --outcome PREPARED --report … --detection …
    python scripts/telegram_notify.py production  --repo .
    python scripts/telegram_notify.py smoke

Reads ``TELEGRAM_BOT_TOKEN`` and ``TELEGRAM_CHAT_ID`` from the environment (the GitHub
Environment ``notifications``); without them it reports an explicit ``SKIPPED``. The
token is never printed, logged or written anywhere. See ``docs/ota/telegram-notifications.md``.
"""

from __future__ import annotations

from talent_data.notify.cli import main

if __name__ == "__main__":
    raise SystemExit(main())
