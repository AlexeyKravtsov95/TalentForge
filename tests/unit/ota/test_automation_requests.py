"""The internal automation request is closed, strict and pins exact bytes."""

import json

import pytest

from talent_data.ota.automation.requests import parse_request


def request_wire() -> dict:
    run = {
        "workflowPath": ".github/workflows/ota-staging-verify.yml",
        "runId": 4242,
        "attempt": 1,
        "headSha": "a" * 40,
    }
    return {
        "schemaVersion": 1,
        "repository": "AlexeyKravtsov95/TalentForge",
        "game": "forever",
        "build": "1.60.1.70450",
        "revision": 1,
        "releaseId": "1.60.1.70450-r1",
        "sourceCommit": "a" * 40,
        "createdAt": "2026-10-17T23:00:00Z",
        "manifestSha256": "sha256:" + "b" * 64,
        "baseReleaseId": "1.60.1.70374-r1",
        "baseManifestSha256": "sha256:" + "c" * 64,
        "stagingRun": run,
        "packageRun": {
            **run,
            "runId": 9000,
            "workflowPath": ".github/workflows/ota-release-package.yml",
        },
        "artifact": {
            "artifactId": 1234,
            "name": "ota-release-package-9000",
            "archiveSha256": "sha256:" + "d" * 64,
        },
    }


def test_request_roundtrips_and_is_frozen() -> None:
    wire = request_wire()
    request = parse_request(json.dumps(wire).encode())
    assert request.model_dump(mode="json", by_alias=True) == wire
    with pytest.raises(ValueError):
        request.revision = 2


@pytest.mark.parametrize(
    "field,value",
    [
        ("schemaVersion", True),
        ("revision", True),
        ("revision", "1"),
        ("revision", 1000000),
        ("unexpected", 1),
        ("sourceCommit", "a" * 39),
        ("manifestSha256", "b" * 64),
        ("createdAt", "2026-10-17T23:00:00"),
        ("createdAt", 1),
        ("releaseId", "1.60.1.70450-r2"),
        ("baseReleaseId", "1.60.1.70450-r1"),
    ],
)
def test_request_wire_is_closed(field: str, value: object) -> None:
    wire = request_wire()
    wire[field] = value
    with pytest.raises(ValueError):
        parse_request(json.dumps(wire).encode())


@pytest.mark.parametrize("member", ["schemaVersion", "artifact", "baseManifestSha256"])
def test_required_request_members_cannot_be_omitted(member: str) -> None:
    wire = request_wire()
    del wire[member]
    with pytest.raises(ValueError):
        parse_request(json.dumps(wire).encode())


@pytest.mark.parametrize(
    "field,value",
    [("attempt", True), ("runId", 0), ("headSha", "bad"), ("url", "https://evil.invalid")],
)
def test_run_wire_is_closed(field: str, value: object) -> None:
    wire = request_wire()
    wire["packageRun"][field] = value
    with pytest.raises(ValueError):
        parse_request(json.dumps(wire).encode())


def test_request_duplicate_members_fail_at_any_depth() -> None:
    raw = json.dumps(request_wire()).encode()
    for bad in [
        raw.replace(b'"schemaVersion": 1', b'"schemaVersion": 1, "schemaVersion": 1'),
        raw.replace(b'"attempt": 1', b'"attempt": 1, "att\\u0065mpt": 2'),
    ]:
        with pytest.raises(ValueError):
            parse_request(bad)
