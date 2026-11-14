"""Conditional authorization must be strict, pinned and never claim premerge consent."""

import hashlib
import json

import pytest


def binding_wire():
    def run(path, number, sha):
        return {"workflowPath": path, "runId": number, "attempt": 1, "headSha": sha}

    return {
        "schemaVersion": 1,
        "flaggedGates": ["G7"],
        "kind": "ota-candidate-binding",
        "policy": "owner-merge-v1",
        "repository": "AlexeyKravtsov95/TalentForge",
        "pullNumber": 141,
        "game": "forever",
        "build": "1.60.1.70999",
        "revision": 1,
        "sourceSha": "a" * 40,
        "sourceTree": "b" * 40,
        "preparationHeadSha": "a" * 40,
        "baseSha": "c" * 40,
        "toolingSha": "d" * 40,
        "preparationCi": run(".github/workflows/ci.yml", 50, "a" * 40),
        "candidateRun": run(".github/workflows/ota-prepare.yml", 49, "c" * 40),
        "stagingRun": run(".github/workflows/ota-staging-verify.yml", 51, "d" * 40),
        "packageRun": run(".github/workflows/ota-release-package.yml", 52, "d" * 40),
        "candidateArtifact": {
            "artifactId": 11,
            "name": "ota-prepare-verified-candidate",
            "archiveSha256": "sha256:" + "e" * 64,
        },
        "baseReleaseId": "1.60.1.70888-r1",
        "baseManifestSha256": "sha256:"
        "aeb0bc6fe93a96255251cdf1953aed620c6a0a308e9cd3f8d1e1810919f77ca2",
    }


def record_wire():
    return {
        "schemaVersion": 1,
        "kind": "ota-owner-merge-approval",
        "policy": "owner-merge-v1",
        "repository": "AlexeyKravtsov95/TalentForge",
        "pullNumber": 141,
        "releaseId": "1.60.1.70999-r1",
        "sourceSha": "a" * 40,
        "bindingSha256": "sha256:" + "b" * 64,
        "requestDigest": "sha256:" + "f" * 64,
        "acceptedFlags": ["G7"],
        "revoked": False,
    }


def test_candidate_binding_keeps_source_and_tooling_distinct():
    from talent_data.ota.automation.candidate import CandidateBinding, binding_digest

    wire = binding_wire()
    b = CandidateBinding.model_validate_json(json.dumps(wire))
    assert b.source_sha == "a" * 40
    assert b.package_run.head_sha == "d" * 40
    canonical = json.dumps(wire, sort_keys=True, separators=(",", ":")).encode()
    assert binding_digest(b) == "sha256:" + hashlib.sha256(canonical).hexdigest()
    wire["sourceTree"] = "f" * 40
    assert binding_digest(CandidateBinding.model_validate_json(json.dumps(wire))) != binding_digest(
        b
    )


@pytest.mark.parametrize(
    "field,value",
    [
        ("schemaVersion", True),
        ("schemaVersion", "1"),
        ("unknown", "ignored"),
        ("archiveSha256", "sha256:" + "0" * 64),
        ("finalHeadSha", "e" * 40),
        ("pullNumber", True),
        ("pullNumber", 0),
        ("repository", "foreign/TalentForge"),
        ("sourceSha", "abcd"),
        ("preparationHeadSha", "c" * 40),
        ("build", "wrong"),
        ("revision", 0),
    ],
)
def test_candidate_binding_refuses_ambiguous_identity(field, value):
    from talent_data.ota.automation.candidate import CandidateBinding

    wire = {**binding_wire(), field: value}
    with pytest.raises(ValueError):
        CandidateBinding.model_validate_json(json.dumps(wire))


def test_candidate_binding_refuses_false_producer_head():
    from talent_data.ota.automation.candidate import CandidateBinding

    wire = binding_wire()
    wire["stagingRun"]["headSha"] = "a" * 40
    with pytest.raises(ValueError):
        CandidateBinding.model_validate_json(json.dumps(wire))


def test_candidate_binding_keeps_original_artifact_attempt():
    from talent_data.ota.automation.candidate import CandidateBinding

    wire = binding_wire()
    wire["candidateRun"]["attempt"] = 2
    binding = CandidateBinding.model_validate_json(json.dumps(wire))
    assert binding.candidate_run.attempt == 2
    assert binding.candidate_run.head_sha == "c" * 40


def test_conditional_record_roundtrip_requires_no_personal_observation():
    from talent_data.ota.automation.merge_approval import (
        OwnerMergeRecord,
        parse_merge_record,
        render_merge_record,
    )

    record = OwnerMergeRecord.model_validate_json(json.dumps(record_wire()))
    text = render_merge_record(record)
    assert parse_merge_record(text) == record
    assert "- Approved by:" not in text
    assert "- Live-build evidence:" not in text
    assert "<OWNER:" not in text
    assert parse_merge_record("# Legacy\n- Accepted gates: G7\n") is None


@pytest.mark.parametrize(
    "field,value",
    [
        ("schemaVersion", True),
        ("schemaVersion", "1"),
        ("repository", "foreign/TalentForge"),
        ("pullNumber", "141"),
        ("acceptedFlags", ["G1"]),
        ("acceptedFlags", ["G7", "G7"]),
        ("revoked", "false"),
        ("bindingSha256", "sha256:bad"),
        ("sourceSha", "abc"),
        ("releaseId", "invalid"),
        ("unknown", None),
    ],
)
def test_owner_merge_record_rejects_unsafe_wire(field, value):
    from talent_data.ota.automation.merge_approval import OwnerMergeRecord

    with pytest.raises(ValueError):
        OwnerMergeRecord.model_validate_json(json.dumps({**record_wire(), field: value}))


def test_merge_record_requires_explicit_revocation_field():
    from talent_data.ota.automation.merge_approval import OwnerMergeRecord

    wire = record_wire()
    del wire["revoked"]
    with pytest.raises(ValueError):
        OwnerMergeRecord.model_validate_json(json.dumps(wire))


def test_duplicate_or_partial_conditional_records_are_not_legacy():
    from talent_data.ota.automation.merge_approval import parse_merge_record

    raw = json.dumps(record_wire(), separators=(",", ":"))
    text = "- Authorization mode: owner-merge-v1\n\n```owner-merge-v1\n" + raw + "\n```\n"
    duplicate = raw.replace('"schemaVersion":1', '"schemaVersion":1,"schemaVersion":1')
    for bad in (
        text.replace(raw, duplicate),
        text + text,
        "- Authorization mode: owner-merge-v1\n",
        text.replace("- Authorization mode: owner-merge-v1\n", ""),
        text.replace("```\n", "``"),
    ):
        with pytest.raises(ValueError):
            parse_merge_record(bad)
