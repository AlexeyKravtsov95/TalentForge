"""Standing authorization for reviewed main data, never for code or structural changes.

An exact request still passes CI, staging, provenance, G1-G8, signing, public readback
and the durable publication journal. A frozen request tag removes the release-approval
PR. It neither merges a data PR nor treats Wago's publishable flag as live-realm evidence.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal

from pydantic import Field

from talent_data.models.talents import TalentDataDocument
from talent_data.ota.automation.prepare import GitHub, git, obj, require, same
from talent_data.ota.automation.requests import ReleaseRequest, _Model
from talent_data.ota.gates import ApprovalRecord, Gate, ReleaseUnderTest, talent_documents
from talent_data.ota.models import SHA256_PATTERN, FileRole, OtaCatalog
from talent_data.ota.release_package import LoadedPackage
from talent_data.ota.store import BlobStore, blob_key
from talent_data.ota.verify import (
    parse_strict,
    sha256_ref,
    verify_release_manifest,
)
from talent_data.prepare.orchestrate import candidate_path_allowed

POLICY_PATH = "docs/ota/data-only-policy.json"
MARKER = "- Data-only policy: data-only-v1"


class Policy(_Model):
    schema_version: Literal[1]
    kind: Literal["ota-data-only-policy"]
    policy: Literal["data-only-v1"]
    enabled: bool
    game: Literal["forever"]
    branch: Literal["1.60"]
    max_data_level: Literal[1]
    rules: Literal["same-allocation-shape-no-warning-growth-verified-resources"]
    g7_acceptance: Literal["standing-policy-not-live-realm-evidence"]


class AutomaticRequest(_Model):
    schema_version: Literal[1]
    kind: Literal["ota-data-only-request"]
    policy_sha256: str = Field(pattern=SHA256_PATTERN)
    request: ReleaseRequest


def tagged_request(repo: Path, release_id: str, ref: str = "origin/main") -> ReleaseRequest | None:
    name = "ota-data-only/" + release_id
    raw = git(repo, "cat-file", "tag", "refs/tags/" + name, missing_ok=True)
    if not raw:
        return None
    headers, separator, message = raw.partition(b"\n\n")
    require(bool(separator), "DATA_ONLY_TAG", "invalid annotated tag")
    pairs = [line.decode().split(" ", 1) for line in headers.splitlines()]
    require(all(len(pair) == 2 for pair in pairs), "DATA_ONLY_TAG", "invalid tag headers")
    fields = dict(pairs)
    require(
        len(pairs) == len(fields) and set(fields) == {"object", "type", "tag", "tagger"},
        "DATA_ONLY_TAG",
        "unknown or duplicate tag headers",
    )
    record = parse_strict(AutomaticRequest, message)
    require(
        type(json.loads(message)["schemaVersion"]) is int,
        "DATA_ONLY_TAG",
        "invalid schema version type",
    )
    request = record.request
    require(
        fields.get("type") == "commit"
        and fields.get("tag") == name
        and fields.get("object") == request.source_commit
        and request.release_id == release_id,
        "DATA_ONLY_TAG",
        "tag target, name or release differs",
    )
    same(
        record.policy_sha256,
        sha256_ref(policy_bytes(repo, ref)),
        "DATA_ONLY_POLICY",
        "current standing policy",
    )
    return request


def tagged_requests(repo: Path) -> tuple[ReleaseRequest, ...]:
    refs = (
        git(repo, "for-each-ref", "--format=%(refname)", "refs/tags/ota-data-only/")
        .decode()
        .splitlines()
    )
    result = []
    for ref in refs:
        release_id = ref.removeprefix("refs/tags/ota-data-only/")
        request = tagged_request(repo, release_id)
        require(request is not None, "DATA_ONLY_TAG", "tag disappeared")
        assert request is not None
        result.append(request)
    return tuple(result)


def standing_record(repo: Path, ref: str, request: ReleaseRequest) -> bytes | None:
    tagged = tagged_request(repo, request.release_id, ref)
    if tagged is None:
        return None
    same(tagged, request, "DATA_ONLY_TAG", "frozen request")
    return approval_text(request, policy_bytes(repo, ref)).encode()


def policy_bytes(repo: Path, ref: str) -> bytes:
    raw = git(repo, "show", f"{ref}:{POLICY_PATH}")
    policy = parse_strict(Policy, raw)
    wire = json.loads(raw)
    require(
        type(wire["schemaVersion"]) is int and type(wire["maxDataLevel"]) is int,
        "DATA_ONLY_POLICY",
        "policy numeric types differ",
    )
    require(policy.enabled, "DATA_ONLY_DISABLED", "standing policy is disabled")
    return raw


def approval_text(request: ReleaseRequest, policy: bytes) -> str:
    """A closed, deterministic authorization receipt. It is never a manual approval."""
    return (
        f"# Data-only authorization: {request.release_id}\n\n{MARKER}\n"
        f"- Policy SHA-256: {sha256_ref(policy)}\n"
        f"- Request SHA-256: {sha256_ref(request.model_dump_json(by_alias=True).encode())}\n"
    )


def record_matches(repo: Path, ref: str, request: ReleaseRequest, raw: bytes) -> bool:
    return raw.decode("utf-8", "strict") == approval_text(request, policy_bytes(repo, ref))


def allocation_shape(doc: TalentDataDocument) -> dict[str, Any]:
    """Keep every allocation/identity/mechanic field; remove only explicit presentation."""
    raw = doc.model_dump(mode="json", by_alias=True)
    for key in ("clientBuild", "contentHash", "generatedAt", "provenance"):
        del raw[key]
    raw["class"].pop("name")
    for tree in raw["trees"]:
        for section in tree["sections"]:
            section.pop("name")
        for node in tree["nodes"]:
            node.pop("name")
            for entry in node["entries"]:
                for key in ("name", "descriptionTemplate", "effects"):
                    entry.pop(key)
    return raw


@dataclass(frozen=True)
class VerifiedDataOnly:
    request: ReleaseRequest
    main_ref: str
    record: bytes

    def gate_approval(self) -> ApprovalRecord:
        return ApprovalRecord(
            release_id=self.request.release_id,
            accepted_gates=frozenset({Gate.G7}),
            live_build_evidence=None,
            manifest_sha256=self.request.manifest_sha256,
            policy_authorization_evidence=(
                "data-only-v1: standing G7 acceptance for data-only updates; "
                "not a claim of observed live-realm deployment"
            ),
        )


def verify_data_only(
    repo: Path,
    main_ref: str,
    request: ReleaseRequest,
    under_test: ReleaseUnderTest,
    live: OtaCatalog,
    store: BlobStore,
) -> VerifiedDataOnly:
    policy = policy_bytes(repo, main_ref)
    same(policy_bytes(repo, request.source_commit), policy, "DATA_ONLY_POLICY", "source policy")
    require(
        request.build.startswith("1.60.") and under_test.manifest.min_data_level == 1,
        "DATA_ONLY_LEVEL",
        "branch/data level outside policy",
    )
    require(
        request.repository == "AlexeyKravtsov95/TalentForge"
        and request.game == "forever"
        and under_test.manifest.game == request.game
        and under_test.manifest.client_build == request.build
        and under_test.manifest.release_id == request.release_id
        and under_test.manifest.provenance.repository_commit == request.source_commit
        and sha256_ref(under_test.manifest_bytes) == request.manifest_sha256,
        "DATA_ONLY_IDENTITY",
        "request/manifest differs",
    )
    active = next(e for e in live.releases if e.release_id == live.active_release_id)
    require(
        active.release_id == request.base_release_id
        and active.manifest_sha256 == request.base_manifest_sha256,
        "DATA_ONLY_BASE",
        "active production base changed",
    )
    base = verify_release_manifest(
        store.get(blob_key(active.manifest_sha256)),
        active,
        game=request.game,
        supported_data_level=1,
    )
    require(
        base.min_data_level == under_test.manifest.min_data_level,
        "DATA_ONLY_LEVEL",
        "data level changed",
    )
    changed = (
        git(repo, "diff", "--name-only", request.source_commit + "^1", request.source_commit)
        .decode()
        .splitlines()
    )
    require(
        bool(changed)
        and all(
            candidate_path_allowed(
                p, game=request.game, build=request.build, base=base.client_build
            )
            for p in changed
        ),
        "DATA_ONLY_SOURCE",
        "source includes code/config or unrelated files",
    )
    old = {}
    for file in base.files:
        if file.role is not FileRole.TALENT_DATA:
            continue
        raw = store.get(blob_key(file.sha256))
        require(
            len(raw) == file.byte_length and sha256_ref(raw) == file.sha256,
            "DATA_ONLY_BASE_HASH",
            f"{file.path}: baseline data failed integrity",
        )
        document = parse_strict(TalentDataDocument, raw)
        require(document.class_.token not in old, "DATA_ONLY_CLASSES", "duplicate baseline class")
        old[document.class_.token] = document
    new = {d.class_.token: d for d in talent_documents(under_test)}
    require(
        old.keys() == new.keys() and len(new) == 9,
        "DATA_ONLY_CLASSES",
        "nine-class inventory changed",
    )
    for token in sorted(new):
        require(
            allocation_shape(old[token]) == allocation_shape(new[token]),
            "DATA_ONLY_STRUCTURE",
            f"{token}: saved-build allocation shape changed",
        )
    return VerifiedDataOnly(request, main_ref, approval_text(request, policy).encode())


def commit_authorization(
    api: GitHub,
    repo: Path,
    request: ReleaseRequest,
    package: LoadedPackage,
    live: OtaCatalog,
    store: BlobStore,
) -> str:
    """Persist only an immutable annotated request tag; never edit main or merge a PR.

    Source main CI has already passed. The scheduled protected publisher rechecks
    source CI, the current standing policy and G1-G8 before loading its signer.
    """
    from datetime import UTC, datetime

    from talent_data.ota.gates import under_test_from_bytes, validate_release
    from talent_data.ota.models import Channel

    main = git(repo, "rev-parse", "origin/main").decode().strip()
    # G1 below proves source ancestry. A later reviewed main commit must not starve
    # an exact staged data package while its source/current policy remain identical.
    installation = obj(api.call("GET", "/installation/repositories", params={"per_page": 100}))
    require(
        installation.get("total_count") == 1
        and len(installation.get("repositories", [])) == 1
        and installation["repositories"][0].get("full_name") == request.repository,
        "APP_SCOPE",
        "App must be scoped to TalentForge",
    )
    under_test = under_test_from_bytes(package.manifest_bytes, repo)
    proof = verify_data_only(repo, main, request, under_test, live, store)
    results = validate_release(
        under_test,
        repo_root=repo,
        store=store,
        live=live,
        channel=Channel.PRODUCTION,
        now=datetime.now(UTC),
        approval=proof.gate_approval(),
        main_ref=main,
    )
    require(
        all(r.status.value == "PASS" for r in results),
        "DATA_ONLY_GATES",
        "; ".join(f"{r.gate}: {r.detail}" for r in results if r.status.value != "PASS"),
    )
    from talent_data.ota.automation.prepare import write_tag

    record = AutomaticRequest(
        schema_version=1,
        kind="ota-data-only-request",
        request=request,
        policy_sha256=sha256_ref(policy_bytes(repo, main)),
    )
    tag = "ota-data-only/" + request.release_id
    write_tag(
        api,
        tag,
        request.source_commit,
        (record.model_dump_json(by_alias=True, indent=2) + "\n").encode(),
        request.created_at,
    )
    return "refs/tags/" + tag
