"""Shared fixtures: the committed, real normalized Forever datasets (offline)."""

from __future__ import annotations

import pytest

from talent_data.allocation import TalentAllocationEngine
from talent_data.models.talents import TalentDataDocument, TalentNode
from tests.conformance.scenarios import BASELINE, load_documents
from tests.helpers import OLD_BUILD


@pytest.fixture(scope="session")
def docs() -> dict[str, TalentDataDocument]:
    """Baseline (1.60.1.70009) documents by class token."""
    return load_documents(BASELINE)


@pytest.fixture(scope="session")
def old_docs() -> dict[str, TalentDataDocument]:
    """Previous captured build (1.60.1.69977) documents by class token."""
    return load_documents(OLD_BUILD)


@pytest.fixture(scope="session")
def warrior(docs: dict[str, TalentDataDocument]) -> TalentAllocationEngine:
    return TalentAllocationEngine(docs["WARRIOR"])


def node(doc_or_engine: TalentDataDocument | TalentAllocationEngine, name: str) -> TalentNode:
    tree = (
        doc_or_engine.tree
        if isinstance(doc_or_engine, TalentAllocationEngine)
        else doc_or_engine.trees[0]
    )
    matches = [n for n in tree.nodes if n.name == name]
    assert len(matches) == 1, f"{name}: {len(matches)} matches"
    return matches[0]
