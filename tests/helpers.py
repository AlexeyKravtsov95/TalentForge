"""Helpers to derive modified documents from real normalized data in tests.

Mutations are explicit and local to each test: they simulate *future* client changes to
exercise diff rules that the two captured real builds do not happen to contain.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from talent_data.models.talents import (
    TalentDataDocument,
    TalentNode,
    content_hash,
    semantic_payload,
)
from talent_data.versions import ClientVersion

FIXTURES = Path(__file__).parent / "fixtures"
DB2_FIXTURES = FIXTURES / "db2" / "forever"

# The two real consecutive Forever builds captured in tests/fixtures/db2/forever/.
OLD_BUILD = ClientVersion.parse("1.60.1.69977")
NEW_BUILD = ClientVersion.parse("1.60.1.70009")
# The product dataset of M5.DATA.4 (also captured in tests/fixtures/db2/forever/). NEW_BUILD
# stays the verified reference baseline the M0-M5.V2 tests and the conformance pin to.
PRODUCT_BUILD = ClientVersion.parse("1.60.1.70450")
# The product dataset of M5.DATA.3, still committed and bundled: builds saved on it keep
# opening on it (also captured in tests/fixtures/db2/forever/).
PREVIOUS_PRODUCT_BUILD = ClientVersion.parse("1.60.1.70374")
# The product dataset of M5.DATA.2, still committed and bundled for the builds saved on it
# (also captured in tests/fixtures/db2/forever/). It is also the build the M5.REL.3a ruRU
# pilot, its fixtures and tests/unit/localization/ are pinned to.
BUILD_70338 = ClientVersion.parse("1.60.1.70338")
PILOT_BUILD = BUILD_70338
# The product dataset of M5.DATA, still committed and bundled for the builds saved on it.
BUILD_70245 = ClientVersion.parse("1.60.1.70245")
FIXED_TIME = datetime(2026, 9, 27, 12, 0, tzinfo=UTC)


def node_by_name(doc: TalentDataDocument, name: str) -> TalentNode:
    matches = [n for t in doc.trees for n in t.nodes if n.name == name]
    assert len(matches) == 1, f"{name}: {len(matches)} matches"
    return matches[0]


def with_nodes(
    doc: TalentDataDocument,
    change: Callable[[list[TalentNode]], list[TalentNode]],
    build: str | None = None,
) -> TalentDataDocument:
    tree = doc.trees[0]
    new_tree = tree.model_copy(update={"nodes": change(list(tree.nodes))})
    updated = doc.model_copy(
        update={"trees": [new_tree, *doc.trees[1:]], "client_build": build or doc.client_build}
    )
    return rehash(updated)


def replace_node(doc: TalentDataDocument, node_name: str, /, **update: Any) -> TalentDataDocument:
    target = node_by_name(doc, node_name)
    return with_nodes(
        doc, lambda nodes: [n.model_copy(update=update) if n.id == target.id else n for n in nodes]
    )


def rehash(doc: TalentDataDocument) -> TalentDataDocument:
    digest = content_hash(semantic_payload(doc.model_dump(mode="json", by_alias=True)))
    return doc.model_copy(update={"content_hash": digest})
