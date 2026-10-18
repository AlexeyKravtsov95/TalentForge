"""Typed errors of the OTA publisher (M5.OTA.2).

Importable without the ``ota`` extra (no third-party imports), so the rest of
``talent_data`` keeps working when ``cryptography`` is not installed.
"""

from __future__ import annotations


class OtaPublisherError(Exception):
    """A publisher operation refused to proceed.

    Fail-closed by construction: every check of ``publisher-and-security.md`` that can
    stop a publication raises (or returns a STOP gate that makes the caller raise) this
    type. Messages are for the operator; nothing is ever silently coerced or skipped.
    """
