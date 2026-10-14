"""Shared fixtures: the committed, real normalized Forever datasets (offline)."""

from __future__ import annotations

import pytest

from talent_data.allocation import TalentAllocationEngine
from talent_data.models.talents import TalentDataDocument, TalentNode
from tests.conformance.scenarios import BASELINE, load_documents
from tests.helpers import BUILD_70245, OLD_BUILD, PREVIOUS_PRODUCT_BUILD, PRODUCT_BUILD


@pytest.fixture(scope="session")
def docs() -> dict[str, TalentDataDocument]:
    """Baseline (1.60.1.70009) documents by class token."""
    return load_documents(BASELINE)


@pytest.fixture(scope="session")
def old_docs() -> dict[str, TalentDataDocument]:
    """Previous captured build (1.60.1.69977) documents by class token."""
    return load_documents(OLD_BUILD)


@pytest.fixture(scope="session")
def product_docs() -> dict[str, TalentDataDocument]:
    """Product dataset (1.60.1.70374, M5.DATA.3) documents by class token."""
    return load_documents(PRODUCT_BUILD)


@pytest.fixture(scope="session")
def previous_product_docs() -> dict[str, TalentDataDocument]:
    """Previous product dataset (1.60.1.70338, M5.DATA.2) documents by class token."""
    return load_documents(PREVIOUS_PRODUCT_BUILD)


@pytest.fixture(scope="session")
def build_70245_docs() -> dict[str, TalentDataDocument]:
    """Product dataset of M5.DATA (1.60.1.70245, still bundled) documents by class token."""
    return load_documents(BUILD_70245)


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
