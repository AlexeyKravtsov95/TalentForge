"""OTA contract v1 (M5.OTA.1): the committed fixtures are current, and the reference
verifier reaches the recorded verdict for every case.

``docs/ota/fixtures/v1/cases.json`` is the shared conformance suite of the contract: the
Kotlin client (M5.OTA.3) replays the same cases and must reach the same verdicts. Every
error code and every activation decision of the specification is exercised by at least one
case, and the two real releases list exactly the committed files of their builds.
"""

from __future__ import annotations

import base64
import importlib.util
import json
import sys
from datetime import datetime
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

from talent_data.models.manifest import DatasetManifest
from talent_data.ota.models import CatalogRelease, Channel, ReleaseFile
from talent_data.ota.verify import (
    ActivationDecision,
    OtaError,
    OtaErrorCode,
    SeenCatalog,
    decide_activation,
    load_keyring,
    pae,
    strict_b64decode,
    verify_catalog,
    verify_release_files,
    verify_release_manifest,
)

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / "docs" / "ota" / "fixtures" / "v1"


def _load_generator() -> ModuleType:
    name = "generate_ota_fixtures"
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


generator = _load_generator()
SUITE: dict[str, Any] = json.loads((FIXTURES / "cases.json").read_text(encoding="utf-8"))
CASES: list[dict[str, Any]] = SUITE["cases"]
KEYRING_DOC = json.loads((FIXTURES / "keyring.json").read_text(encoding="utf-8"))
KEYRING = load_keyring(KEYRING_DOC["keys"])


def _instant(text: str) -> datetime:
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def run_case(case: dict[str, Any]) -> dict[str, Any]:
    """The verdict of the reference verifier, in the fixture's ``expect`` shape."""
    data = (FIXTURES / case["file"]).read_bytes()
    ctx = case["context"]
    try:
        if case["kind"] == "catalog":
            seen = ctx["lastSeen"]
            verified = verify_catalog(
                data,
                keyring=KEYRING,
                game=ctx["game"],
                channel=Channel(ctx["channel"]),
                now=_instant(ctx["now"]),
                last_seen=None
                if seen is None
                else SeenCatalog(seen["sequence"], seen["payloadSha256"]),
            )
            decision, release = decide_activation(
                verified,
                supported_data_level=ctx["supportedDataLevel"],
                bundled_product_build=ctx["bundledProductBuild"],
                current_release_id=ctx["currentReleaseId"],
            )
            return {"result": "ACCEPT", "decision": decision.value, "releaseId": release.release_id}
        entry = CatalogRelease.model_validate_json(json.dumps(case["catalogEntry"]))
        manifest = verify_release_manifest(
            data, entry, game=ctx["game"], supported_data_level=ctx["supportedDataLevel"]
        )
        overrides: dict[str, str] = case["files"]["overrides"]

        def read_file(file: ReleaseFile) -> bytes:
            source = overrides.get(file.path)
            if source is None:
                return generator.repository_source(file.path).read_bytes()
            if source.startswith("repository:"):
                return generator.repository_source(source.removeprefix("repository:")).read_bytes()
            return (FIXTURES / source).read_bytes()

        known = None
        if ctx["knownSameBuildManifest"] == "repository":
            path = f"dataset/{manifest.game}/{manifest.client_build}/manifest.json"
            known = DatasetManifest.model_validate_json(
                generator.repository_source(path).read_bytes()
            )
        verify_release_files(manifest, read_file, known_same_build_manifest=known)
    except OtaError as exc:
        return {"result": "REJECT", "code": exc.code.value}
    return {"result": "ACCEPT"}


def test_committed_fixtures_are_current(tmp_path: Path) -> None:
    generator.generate(tmp_path / "v1")
    assert generator.drifted(tmp_path / "v1", FIXTURES) == [], (
        "docs/ota/fixtures/v1 is stale: run `python scripts/generate_ota_fixtures.py`"
    )


@pytest.mark.parametrize("case", CASES, ids=[c["id"] for c in CASES])
def test_reference_verifier_reaches_the_recorded_verdict(case: dict[str, Any]) -> None:
    assert run_case(case) == case["expect"], case["description"]


def test_case_ids_are_unique() -> None:
    ids = [c["id"] for c in CASES]
    assert len(ids) == len(set(ids))


def test_every_error_code_and_decision_is_exercised() -> None:
    codes = {c["expect"]["code"] for c in CASES if c["expect"]["result"] == "REJECT"}
    assert codes == {code.value for code in OtaErrorCode}
    decisions = {
        c["expect"]["decision"]
        for c in CASES
        if c["kind"] == "catalog" and c["expect"]["result"] == "ACCEPT"
    }
    assert decisions == {d.value for d in ActivationDecision}


@pytest.mark.parametrize("build", [generator.BUILD_A, generator.BUILD_B])
def test_real_releases_list_exactly_the_committed_files(build: str) -> None:
    release = json.loads((FIXTURES / "releases" / f"{build}-r1.release.json").read_bytes())
    assert [f["path"] for f in release["files"]] == sorted(
        generator.snapshot(build), key=lambda p: p.encode("utf-8")
    )
    roles = {f["role"] for f in release["files"]}
    assert len(roles) == 7


def test_fixture_keys_are_test_keys_only() -> None:
    every = KEYRING_DOC["keys"] + KEYRING_DOC["untrustedKeys"]
    assert all(k["keyId"].startswith("test-") for k in every)
    assert {k["keyId"] for k in KEYRING_DOC["untrustedKeys"]}.isdisjoint(KEYRING)


def test_pae_is_the_dsse_v1_encoding() -> None:
    # DSSE v1 protocol: "DSSEv1" SP LEN(type) SP type SP LEN(body) SP body, ASCII decimals.
    assert pae("application/x", b"hello") == b"DSSEv1 13 application/x 5 hello"
    assert pae("t", b"") == b"DSSEv1 1 t 0 "


@pytest.mark.parametrize("text", ["aGk", "aGk=\n", "aG k=", "aGl=", "a-k="])
def test_only_canonical_base64_decodes(text: str) -> None:
    with pytest.raises(ValueError):
        strict_b64decode(text)


def test_canonical_base64_decodes() -> None:
    assert strict_b64decode(base64.b64encode(b"hi").decode()) == b"hi"
