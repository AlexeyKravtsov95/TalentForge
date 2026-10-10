"""Pydantic models for the external wago.tools API payloads.

Observed schema of ``GET https://wago.tools/api/builds`` (checked 2026-09-27):

    {
      "<product>": [
        {"product": "wow_classic_beta", "version": "1.60.1.70009",
         "created_at": "2026-09-24 22:02:03", "build_config": "...", "product_config": "...",
         "cdn_config": "...", "is_bgdl": false},
        ...
      ],
      ...
    }

Entries are NOT sorted by date (the ``wow_classic_beta`` list starts with 2025 builds of a
different game flavour), so callers must never rely on list order.
"""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, RootModel, StrictBool


class WagoBuild(BaseModel):
    model_config = ConfigDict(extra="ignore", frozen=True)

    product: str
    version: str
    # "YYYY-MM-DD HH:MM:SS" without a timezone designator; wago does not document the zone.
    # We keep it naive and only compare values coming from this same field.
    created_at: datetime
    build_config: str | None = None
    product_config: str | None = None
    cdn_config: str | None = None
    # Background-download builds are pre-staged clients, not necessarily live on the realm.
    # Absent on the wire means *unknown* (None), never "not a background download":
    # ``classify_build`` turns an unknown flag into a non-publishable candidate.
    # Strict: the flag gates publication, so a non-boolean on the wire (``"false"``, ``0``,
    # ``"yes"``) is a schema error, never coerced into a publishability answer.
    is_bgdl: StrictBool | None = None


class WagoBuildIndex(RootModel[dict[str, list[WagoBuild]]]):
    pass
