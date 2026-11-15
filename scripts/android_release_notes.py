"""Release-scoped UTF-8 notes. No fallback to a previous release's text."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import store_delivery as core

NOTES_DIR = Path("docs/release/m7-android-1.1.0")
LANGUAGES = ("ru-RU", "en-US")
LIMITS = {"play": 500, "rustore": 5000, "appgallery": 500}


def validate(notes: Any, stores: tuple[str, ...]) -> dict[str, str]:
    if not isinstance(notes, dict) or set(notes) != set(LANGUAGES):
        raise core.DeliveryError("CONFIG", "release notes require exactly ru-RU and en-US")
    for language, value in notes.items():
        if (
            not isinstance(value, str)
            or not value.strip()
            or value != value.strip()
            or any(ord(c) < 32 and c not in "\n\t" for c in value)
            or "\ufeff" in value
        ):
            raise core.DeliveryError("CONFIG", f"invalid UTF-8 release notes: {language}")
        for store in stores:
            if store not in LIMITS or len(value) > LIMITS[store]:
                raise core.DeliveryError(
                    "CONFIG", f"release notes exceed {store} limit: {language}"
                )
    return dict(notes)


def load(
    repo: Path, version_name: str, version_code: int, stores: tuple[str, ...]
) -> dict[str, str]:
    if version_name != "1.1.0" or type(version_code) is not int or version_code != 6:
        raise core.DeliveryError("GATE", "M7 release notes are only for Android 1.1.0 (6)")
    try:
        notes = {
            language: (repo / NOTES_DIR / f"notes-{short}.txt").read_text(encoding="utf-8").strip()
            for language, short in zip(LANGUAGES, ("ru", "en"), strict=True)
        }
    except (OSError, UnicodeError):
        raise core.DeliveryError("CONFIG", "missing or non-UTF-8 release notes") from None
    return validate(notes, stores)
