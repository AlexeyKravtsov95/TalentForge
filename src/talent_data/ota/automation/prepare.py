"""Prepare one exact OTA package and a draft release PR; never publish production.

Only the two existing committed workflows are dispatched. Their completed attempt API
records and artifact bytes are checked again in the isolated App proposal job. A kept
request or a correlated package run freezes the package: retries do not rebuild it.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
import tempfile
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

import httpx

from talent_data.models.manifest import DatasetManifest
from talent_data.ota.automation.provenance import (
    MAX_ARCHIVE_BYTES,
    PACKAGE_WORKFLOW,
    REPOSITORY,
    TRUSTED_REPO,
    RequestRejected,
    _main_commit,
    _run,
    extract_data_archive,
    extract_verified_archive,
    verify_package_origin,
)
from talent_data.ota.automation.public_verify import _fetch, verify_publication
from talent_data.ota.automation.requests import ArtifactPin, ReleaseRequest, RunPin, parse_request
from talent_data.ota.catalog import catalog_entry
from talent_data.ota.models import COMMIT_PATTERN, OtaCatalog, OtaReleaseManifest
from talent_data.ota.preflight import production_keyring
from talent_data.ota.release_package import (
    APPROVAL_TEMPLATE_NAME,
    STAGING_BASE_MANIFEST_NAME,
    STAGING_FILES,
    STAGING_WORKFLOW,
    LoadedPackage,
    bind_staging,
    load_package,
    manifest_files,
    run_record,
)
from talent_data.ota.s3_channels import PRODUCTION_PROFILE
from talent_data.ota.store import blob_key
from talent_data.ota.verify import (
    TrustedKey,
    parse_strict,
    sha256_ref,
    strict_json,
    verify_release_manifest,
)
from talent_data.prepare.production import ProductionStatus, read_production
from talent_data.prepare.staging_verify import CLIENT_SCENARIOS
from talent_data.versions import ClientVersion

CI_WORKFLOW = ".github/workflows/ci.yml"
PREPARE_WORKFLOW = ".github/workflows/ota-release-prepare.yml"
API_ORIGIN = "https://api.github.com"
REQUEST_DIR = "docs/ota/release-requests"
APPROVAL_DIR = "docs/ota/releases"
BOT = {"name": "TalentForge OTA preparation", "email": "ota-preparation@talentforge.invalid"}
ALREADY_PUBLISHED = "1.60.1.70532-r1"


class PreparationBlocked(Exception):
    """An input cannot authorize a proposal; production has not been modified."""

    def __init__(self, code: str, detail: str) -> None:
        self.code, self.detail = code, detail
        super().__init__(f"{code}: {detail}")


def require(condition: bool, code: str, detail: str) -> None:
    if not condition:
        raise PreparationBlocked(code, detail)


def obj(value: Any) -> dict[str, Any]:
    require(isinstance(value, dict), "API_SHAPE", "expected an object")
    assert isinstance(value, dict)
    return value


def same(found: Any, expected: Any, code: str, field: str) -> None:
    require(type(found) is type(expected) and found == expected, code, f"{field} differs")


def positive(value: Any) -> int:
    require(type(value) is int and value > 0, "API_SHAPE", "expected a positive integer")
    return int(value)


def git(repo: Path, *args: str, missing_ok: bool = False) -> bytes:
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, check=False)
    require(result.returncode == 0 or missing_ok, "COMMITTED_TREE", "cannot read committed tree")
    return result.stdout


class GitHub:
    """Small repository-bound REST adapter; errors never echo tokens, bodies or redirects."""

    def __init__(self, client: httpx.Client, token: str) -> None:
        require(bool(token.strip()), "TOKEN_MISSING", "GitHub token is not configured")
        self.client, self.token = client, token
        self.prefix = f"/repos/{REPOSITORY}"

    def call(
        self,
        method: str,
        path: str,
        *,
        data: Any = None,
        params: Mapping[str, str | int] | None = None,
        missing_ok: bool = False,
    ) -> Any:
        try:
            response = self.client.request(
                method,
                API_ORIGIN + path,
                json=data,
                params=params,
                headers={
                    "Authorization": f"Bearer {self.token}",
                    "Accept": "application/vnd.github+json",
                    "X-GitHub-Api-Version": "2022-11-28",
                },
                follow_redirects=False,
                timeout=30.0,
            )
        except httpx.HTTPError as exc:
            raise PreparationBlocked("GITHUB_UNAVAILABLE", "GitHub request failed") from exc
        if missing_ok and response.status_code == 404:
            return None
        require(response.is_success, "GITHUB_HTTP", f"GitHub HTTP {response.status_code}")
        return None if response.status_code == 204 else strict_json(response.content)

    def repo(self, method: str, path: str, **kwargs: Any) -> Any:
        return self.call(method, self.prefix + path, **kwargs)

    def pages(
        self, path: str, member: str | None = None, *, params: Mapping[str, str | int] | None = None
    ) -> list[dict[str, Any]]:
        found: list[dict[str, Any]] = []
        for page in range(1, 201):
            raw = self.repo("GET", path, params={**(params or {}), "per_page": 100, "page": page})
            values = obj(raw).get(member) if member else raw
            require(isinstance(values, list), "LISTING_INVALID", "expected an API listing")
            assert isinstance(values, list)
            found.extend(obj(v) for v in values)
            if len(values) < 100:
                if member:
                    total = obj(raw).get("total_count")
                    require(
                        type(total) is int and total == len(found),
                        "LISTING_TRUNCATED",
                        "API listing is incomplete or changed",
                    )
                return found
        raise PreparationBlocked("LISTING_TRUNCATED", "refusing an incomplete listing")

    def run(self, run_id: int, attempt: int) -> dict[str, Any]:
        return obj(self.repo("GET", f"/actions/runs/{run_id}/attempts/{attempt}"))

    def content(self, path: str, ref: str) -> bytes | None:
        result = self.repo("GET", f"/contents/{path}", params={"ref": ref}, missing_ok=True)
        if result is None:
            return None
        record = obj(result)
        same(record.get("encoding"), "base64", "CONTENT_ENCODING", "GitHub contents encoding")
        require(isinstance(record.get("content"), str), "CONTENT_ENCODING", "missing content")
        return base64.b64decode(record["content"].replace("\n", ""), validate=True)

    def archive(self, artifact: dict[str, Any]) -> bytes:
        artifact_id = positive(artifact.get("id"))
        url = API_ORIGIN + self.prefix + f"/actions/artifacts/{artifact_id}/zip"
        try:
            request = httpx.Request(
                "GET",
                url,
                headers={"Authorization": f"Bearer {self.token}"},
                extensions={"timeout": dict.fromkeys(("connect", "read", "write", "pool"), 30.0)},
            )
            response = self.client.send(request, stream=True, auth=None, follow_redirects=False)
            try:
                if response.status_code == 302:
                    location = response.headers.get("location", "")
                    parsed = urlsplit(location)
                    require(
                        parsed.scheme == "https"
                        and bool(parsed.hostname)
                        and not parsed.username
                        and not parsed.password
                        and not parsed.fragment,
                        "ARTIFACT_REDIRECT",
                        "unsafe artifact redirect",
                    )
                    response.close()
                    # Signed URL comes only from the fixed authenticated GitHub endpoint.
                    request = httpx.Request("GET", location, extensions=request.extensions)
                    response = self.client.send(
                        request, stream=True, auth=None, follow_redirects=False
                    )
                same(response.status_code, 200, "ARTIFACT_DOWNLOAD", "archive HTTP status")
                body = bytearray()
                for chunk in response.iter_raw():
                    require(
                        len(body) + len(chunk) <= MAX_ARCHIVE_BYTES,
                        "ARTIFACT_SIZE",
                        "archive too large",
                    )
                    body.extend(chunk)
            finally:
                response.close()
        except httpx.HTTPError as exc:
            raise PreparationBlocked("ARTIFACT_DOWNLOAD", "artifact download failed") from exc
        same(sha256_ref(bytes(body)), artifact.get("digest"), "ARTIFACT_DIGEST", "archive digest")
        return bytes(body)


def verify_invocation(run: dict[str, Any], *, event: str, head_sha: str) -> None:
    positive(run.get("id"))
    positive(run.get("run_attempt"))
    require(event in ("workflow_run", "workflow_dispatch"), "TRIGGER_EVENT", "unsupported trigger")
    for field, expected in {
        "path": PREPARE_WORKFLOW,
        "event": event,
        "head_sha": head_sha,
        "head_branch": "main",
    }.items():
        same(run.get(field), expected, "TRIGGER_ORIGIN", field)
    for field in ("repository", "head_repository"):
        same(obj(run.get(field)).get("full_name"), REPOSITORY, "TRIGGER_ORIGIN", field)
    require(re.fullmatch(COMMIT_PATTERN, head_sha) is not None, "TRIGGER_SHA", "full SHA required")
    _main_commit(head_sha)


def invocation_from_environment(api: GitHub, ci_id: int, ci_attempt: int) -> None:
    same(os.environ.get("GITHUB_REPOSITORY"), REPOSITORY, "TRIGGER_REPOSITORY", "repository")
    self_id = int(os.environ.get("GITHUB_RUN_ID", "0"))
    attempt = int(os.environ.get("GITHUB_RUN_ATTEMPT", "0"))
    event = os.environ.get("GITHUB_EVENT_NAME", "")
    head = os.environ.get("GITHUB_SHA", "")
    run = api.run(positive(self_id), positive(attempt))
    same(run.get("id"), self_id, "TRIGGER_RUN", "run id")
    same(run.get("run_attempt"), attempt, "TRIGGER_RUN", "run attempt")
    verify_invocation(run, event=event, head_sha=head)
    same(
        git(TRUSTED_REPO, "rev-parse", "HEAD").decode().strip(),
        head,
        "TRIGGER_CHECKOUT",
        "tooling checkout",
    )
    payload = obj(strict_json(Path(os.environ["GITHUB_EVENT_PATH"]).read_bytes()))
    if event == "workflow_run":
        triggered = obj(payload.get("workflow_run"))
        same(triggered.get("id"), ci_id, "TRIGGER_CI", "CI run")
        same(triggered.get("run_attempt"), ci_attempt, "TRIGGER_CI", "CI attempt")
        same(
            api.run(ci_id, ci_attempt).get("head_sha"),
            triggered.get("head_sha"),
            "TRIGGER_CI",
            "CI source SHA",
        )
    else:
        inputs = obj(payload.get("inputs"))
        same(inputs.get("ci_run_id"), str(ci_id), "TRIGGER_CI", "manual CI run")
        same(inputs.get("ci_attempt"), str(ci_attempt), "TRIGGER_CI", "manual CI attempt")


def verify_ci(run: dict[str, Any], jobs: list[dict[str, Any]], *, run_id: int, attempt: int) -> str:
    for key, expected in {
        "id": run_id,
        "run_attempt": attempt,
        "path": CI_WORKFLOW,
        "name": "CI",
        "event": "push",
        "head_branch": "main",
        "status": "completed",
        "conclusion": "success",
    }.items():
        same(run.get(key), expected, "CI_ORIGIN", key)
    for field in ("repository", "head_repository"):
        same(obj(run.get(field)).get("full_name"), REPOSITORY, "CI_ORIGIN", field)
    sha = run.get("head_sha")
    require(
        isinstance(sha, str) and re.fullmatch(COMMIT_PATTERN, sha) is not None,
        "CI_SHA",
        "full SHA required",
    )
    assert isinstance(sha, str)
    _main_commit(sha)
    required = [j for j in jobs if j.get("name") == "checks"]
    require(
        len(required) == 1
        and required[0].get("status") == "completed"
        and required[0].get("conclusion") == "success",
        "CI_REQUIRED",
        "required CI checks job did not pass",
    )
    return str(sha)


def select_candidate(repo: Path, source: str, catalog: OtaCatalog) -> str | None:
    active = next(r for r in catalog.releases if r.release_id == catalog.active_release_id)
    published = {r.client_build for r in catalog.releases}
    builds = (
        git(repo, "ls-tree", "-d", "--name-only", f"{source}:data/normalized/forever")
        .decode()
        .splitlines()
    )
    candidates = []
    for build in builds:
        version = ClientVersion.parse(build)
        if (
            version <= ClientVersion.parse(active.client_build)
            or build in published
            or f"{build}-r1" == ALREADY_PUBLISHED
        ):
            continue
        raw = git(repo, "show", f"{source}:data/normalized/forever/{build}/manifest.json")
        manifest = parse_strict(DatasetManifest, raw)
        same(manifest.game, "forever", "CANDIDATE_IDENTITY", "game")
        same(manifest.client_build, build, "CANDIDATE_IDENTITY", "build")
        same(obj(strict_json(raw)).get("schemaVersion"), 1, "CANDIDATE_SCHEMA", "schemaVersion")
        require(
            bool(manifest.classes) and all(c.validation.errors == 0 for c in manifest.classes),
            "CANDIDATE_INVALID",
            "candidate has validation errors or no classes",
        )
        # A merged release request is already proposed, not a reason for another package.
        if git(repo, "show", f"{source}:{REQUEST_DIR}/{build}-r1.json", missing_ok=True):
            continue
        candidates.append(build)
    require(len(candidates) <= 1, "CANDIDATE_AMBIGUOUS", "more than one unlisted newer build")
    return candidates[0] if candidates else None


def correlation_key(source: str, release: str, base: str, base_sha: str) -> str:
    return sha256_ref(
        json.dumps([source, release, base, base_sha], separators=(",", ":")).encode()
    ).removeprefix("sha256:")


def completed_run(
    api: GitHub,
    workflow: str,
    key: str,
    inputs: dict[str, str],
    *,
    sleep: Callable[[float], None],
    allow_dispatch: bool = True,
) -> dict[str, Any]:
    label = "ota-" + ("staging" if workflow == STAGING_WORKFLOW else "package") + "-" + key
    path = f"/actions/workflows/{Path(workflow).name}"

    def matches() -> list[dict[str, Any]]:
        return [
            r
            for r in api.pages(
                path + "/runs",
                "workflow_runs",
                params={"event": "workflow_dispatch", "branch": "main"},
            )
            if r.get("display_title") == label
        ]

    found = matches()
    dispatched_head: str | None = None
    require(len(found) <= 1, "RUN_AMBIGUOUS", "multiple correlated workflow runs")
    if not found:
        require(
            allow_dispatch,
            "PACKAGE_HISTORY_MISSING",
            "reserved package run disappeared; no rebuild authorized",
        )
        dispatched_head = str(
            obj(obj(api.repo("GET", "/git/ref/heads/main")).get("object")).get("sha")
        )
        _main_commit(dispatched_head)
        dispatched = obj(
            api.repo(
                "POST",
                path + "/dispatches",
                data={
                    "ref": "main",
                    "inputs": {**inputs, "automation_key": key},
                    "return_run_details": True,
                },
            )
        )
        dispatched_id = positive(dispatched.get("workflow_run_id"))
        found = [obj(api.repo("GET", f"/actions/runs/{dispatched_id}"))]
        same(found[0].get("id"), dispatched_id, "DISPATCH_ID", "returned run id")
        same(found[0].get("display_title"), label, "DISPATCH_ID", "run correlation")
    if dispatched_head is not None:
        same(
            found[0].get("head_sha"),
            dispatched_head,
            "DISPATCH_HEAD_CHANGED",
            "workflow tooling SHA",
        )
    run_id, attempt = positive(found[0].get("id")), positive(found[0].get("run_attempt"))
    for _ in range(280):
        run = api.run(run_id, attempt)
        if run.get("status") == "completed":
            pin = RunPin(
                workflow_path=workflow,
                run_id=run_id,
                attempt=attempt,
                head_sha=str(run.get("head_sha")),
            )
            return _run(run, pin, workflow)
        sleep(15)
    raise PreparationBlocked("RUN_TIMEOUT", "workflow did not complete within budget")


def artifact_for(api: GitHub, run: dict[str, Any], name: str) -> dict[str, Any]:
    found = [
        a
        for a in api.pages(f"/actions/runs/{positive(run['id'])}/artifacts", "artifacts")
        if a.get("name") == name
    ]
    require(len(found) == 1, "ARTIFACT_AMBIGUOUS", "expected exactly one artifact for this attempt")
    artifact = found[0]
    same(artifact.get("expired"), False, "ARTIFACT_EXPIRED", "expired")
    workflow_run = obj(artifact.get("workflow_run"))
    for field, expected in {
        "id": run["id"],
        "head_sha": run["head_sha"],
        "head_branch": "main",
        "repository_id": obj(run["repository"]).get("id"),
        "head_repository_id": obj(run["repository"]).get("id"),
    }.items():
        same(workflow_run.get(field), expected, "ARTIFACT_ORIGIN", field)
    timestamps = [
        datetime.fromisoformat(str(value))
        for value in (run.get("run_started_at"), artifact.get("created_at"), run.get("updated_at"))
    ]
    require(
        all(t.tzinfo is not None for t in timestamps)
        and timestamps[0] <= timestamps[1] <= timestamps[2],
        "ARTIFACT_ATTEMPT",
        "artifact does not belong to completed attempt",
    )
    return artifact


def verify_execution(report_bytes: bytes, update_bytes: bytes) -> None:
    report, update = obj(strict_json(report_bytes)), obj(strict_json(update_bytes))
    client = obj(obj(report.get("facts")).get("client"))
    same(client.get("gradleExit"), 0, "STAGING_EXECUTION", "gradleExit")
    require(
        "notExecuted" in client, "STAGING_EXECUTION", "missing explicit execution/cache verdict"
    )
    same(client.get("notExecuted"), None, "STAGING_EXECUTION", "notExecuted")
    expected = {test for scenario in CLIENT_SCENARIOS for test in scenario.tests}
    tests = obj(client.get("tests"))
    require(
        set(tests) == expected and all(value == "PASS" for value in tests.values()),
        "STAGING_EXECUTION",
        "client suite was missing, failed or skipped",
    )
    checks = report.get("checks")
    require(isinstance(checks, list), "STAGING_EXECUTION", "missing checks")
    assert isinstance(checks, list)
    require(
        all(isinstance(obj(c).get("id"), str) for c in checks),
        "STAGING_EXECUTION",
        "malformed check id",
    )
    by_id = {obj(c).get("id"): obj(c) for c in checks}
    require(len(by_id) == len(checks), "STAGING_EXECUTION", "duplicate checks")
    for name in ("K0", "K1", *(s.id for s in CLIENT_SCENARIOS)):
        check = by_id.get(name, {})
        require(
            check.get("automatic") is True and check.get("verdict") == "PASS",
            "STAGING_EXECUTION",
            "client checks did not pass",
        )
    installed = obj(update.get("installedFiles"))
    require(
        bool(installed)
        and all(
            isinstance(v, list)
            and len(v) == 2
            and isinstance(v[0], str)
            and type(v[1]) is int
            and v[1] > 0
            for v in installed.values()
        ),
        "STAGING_EXECUTION",
        "missing or malformed installed-files evidence",
    )


@dataclass(frozen=True)
class PreparedProposal:
    request: ReleaseRequest
    approval_template: str
    pr_body: str


def make_proposal(
    package: LoadedPackage,
    *,
    staging_run: RunPin,
    package_run: RunPin,
    artifact: ArtifactPin,
    base_manifest_sha256: str,
    base_release_id: str | None = None,
) -> PreparedProposal:
    index = package.index
    verify_execution(
        package.attachment(STAGING_FILES["report"]), package.attachment(STAGING_FILES["update"])
    )
    gates = strict_json(package.attachment("report/gates.json"))
    require(isinstance(gates, list) and len(gates) == 8, "GATES_INVALID", "expected G1-G8")
    rows = [obj(row) for row in gates]
    require(
        all(
            set(row) == {"gate", "status", "detail"}
            and all(isinstance(row[field], str) for field in ("gate", "status", "detail"))
            for row in rows
        ),
        "GATES_INVALID",
        "unknown gate fields or wrong wire types",
    )
    require(
        all(isinstance(row.get("gate"), str) for row in rows), "GATES_INVALID", "malformed gate id"
    )
    require(
        {r.get("gate") for r in rows} == {f"G{i}" for i in range(1, 9)}
        and all(
            r.get("status") == "PASS"
            or (r.get("status") == "FLAG" and r.get("gate") in ("G5", "G7"))
            for r in rows
        ),
        "GATES_REFUSED",
        "STOP or unsupported gate status",
    )
    request = ReleaseRequest(
        schema_version=1,
        repository=REPOSITORY,
        game=index.game,
        build=index.client_build,
        revision=index.revision,
        release_id=index.release_id,
        source_commit=index.source_commit,
        created_at=index.created_at,
        manifest_sha256=index.manifest_sha256,
        base_release_id=base_release_id or f"{index.diff_base}-r1",
        base_manifest_sha256=base_manifest_sha256,
        staging_run=staging_run,
        package_run=package_run,
        artifact=artifact,
    )
    approval = package.attachment(APPROVAL_TEMPLATE_NAME).decode("utf-8")
    require("<OWNER:" in approval, "APPROVAL_TEMPLATE", "owner decisions must stay unfilled")
    body = f"""Prepare OTA release `{index.release_id}` for OWNER review (not approval/publication).

- Source: `{index.source_commit}`
- Manifest: `{index.manifest_sha256}`; createdAt `{index.created_at}`
- Base: `{request.base_release_id}` / `{base_manifest_sha256}`
- Package: https://github.com/{REPOSITORY}/actions/runs/{package_run.run_id}/attempts/{package_run.attempt}
- Staging: https://github.com/{REPOSITORY}/actions/runs/{staging_run.run_id}/attempts/{staging_run.attempt}
- Artifact id `{artifact.artifact_id}`, archive `{artifact.archive_sha256}`
- Download: {index.file_count} files, {index.total_byte_length} bytes (before client cache reuse)

| Gate | Status | Detail |
| --- | --- | --- |
"""
    body += "\n".join(
        f"| {r['gate']} | {r['status']} | {str(r.get('detail', ''))[:1000]} |" for r in rows
    )
    body += (
        "\n\n```text\n" + package.attachment("report/diff.txt").decode("utf-8")[:16000] + "\n```\n"
    )
    body += (
        "\nFull reports and exact blobs are in the pinned package artifact. OWNER must fill "
        "the approval record, including live-build evidence and any G5/G7 acceptance. "
        "No bot approval, merge or production writes.\n"
    )
    return PreparedProposal(request, approval, body)


def read_live(
    client: httpx.Client, keyring: Mapping[str, TrustedKey], now: datetime, *, full: bool = True
) -> OtaCatalog:
    state = read_production(
        client, origin=PRODUCTION_PROFILE.public_origin, keyring=keyring, game="forever", now=now
    )
    require(
        state.status is ProductionStatus.PUBLISHED and state.catalog is not None,
        "LIVE_UNVERIFIED",
        "signed live production catalog unavailable",
    )
    assert state.catalog is not None
    if full:
        result = verify_publication(
            client,
            origin=PRODUCTION_PROFILE.public_origin,
            keyring=keyring,
            game="forever",
            now=now,
        )
        require(
            result.ok and result.catalog_payload_sha256 == state.payload_sha256,
            "LIVE_CONTENT_UNVERIFIED",
            "public files or catalog snapshot failed verification",
        )
    return state.catalog


def check_base(catalog: OtaCatalog, request: ReleaseRequest) -> None:
    active = next(r for r in catalog.releases if r.release_id == catalog.active_release_id)
    require(
        active.release_id == request.base_release_id
        and active.manifest_sha256 == request.base_manifest_sha256,
        "BASE_CHANGED",
        "live base changed; staging/package and OWNER review must be renewed explicitly",
    )


def load_proposal(api: GitHub, request: ReleaseRequest, *, destination: Path) -> PreparedProposal:
    package_run = api.run(request.package_run.run_id, request.package_run.attempt)
    staging_run = api.run(request.staging_run.run_id, request.staging_run.attempt)
    artifact = obj(api.repo("GET", f"/actions/artifacts/{request.artifact.artifact_id}"))
    for field, expected in {
        "id": request.artifact.artifact_id,
        "name": request.artifact.name,
        "digest": request.artifact.archive_sha256,
        "expired": False,
    }.items():
        same(artifact.get(field), expected, "ARTIFACT_ORIGIN", field)
    _run(package_run, request.package_run, PACKAGE_WORKFLOW)
    _run(staging_run, request.staging_run, STAGING_WORKFLOW)
    archive = api.archive(artifact)
    package = load_package(extract_verified_archive(archive, destination))
    verify_package_origin(
        request,
        run_metadata={"package": package_run, "staging": staging_run},
        artifact_metadata=artifact,
        archive_bytes=archive,
        package=package,
    )
    return make_proposal(
        package,
        staging_run=request.staging_run,
        package_run=request.package_run,
        artifact=request.artifact,
        base_manifest_sha256=request.base_manifest_sha256,
        base_release_id=request.base_release_id,
    )


def read_tag(api: GitHub, name: str, source: str) -> bytes | None:
    ref = api.repo("GET", "/git/ref/tags/" + name, missing_ok=True)
    if ref is None:
        return None
    target = obj(obj(ref).get("object"))
    same(target.get("type"), "tag", "RESERVATION_INVALID", "annotated tag type")
    tag = obj(api.repo("GET", f"/git/tags/{target['sha']}"))
    same(tag.get("tag"), name, "RESERVATION_INVALID", "tag name")
    same(obj(tag.get("object")).get("type"), "commit", "RESERVATION_INVALID", "target type")
    same(obj(tag.get("object")).get("sha"), source, "RESERVATION_INVALID", "source commit")
    require(isinstance(tag.get("message"), str), "RESERVATION_INVALID", "missing tag message")
    return str(tag["message"]).encode().rstrip(b"\n") + b"\n"


def write_tag(api: GitHub, name: str, source: str, message: bytes, date: str) -> None:
    """Create an immutable preparation marker, never update/delete refs or main."""
    message = message.rstrip(b"\n") + b"\n"
    require(
        name.startswith(("ota-preparation/", "ota-package/")),
        "RESERVATION_NAMESPACE",
        "unexpected tag namespace",
    )
    existing = read_tag(api, name, source)
    if existing is not None:
        same(existing, message, "RESERVATION_CONFLICT", "frozen marker")
        return
    tag = obj(
        api.repo(
            "POST",
            "/git/tags",
            data={
                "tag": name,
                "message": message.decode(),
                "object": source,
                "type": "commit",
                "tagger": {**BOT, "date": date},
            },
        )
    )
    api.repo("POST", "/git/refs", data={"ref": "refs/tags/" + name, "sha": tag["sha"]})
    same(read_tag(api, name, source), message, "RESERVATION_CONFLICT", "marker read-back")


def verify_staged_base(
    client: httpx.Client, catalog: OtaCatalog, staged: bytes, report_bytes: bytes
) -> None:
    """Staging may change timestamps/provenance, but must start with live's exact files."""
    active = next(r for r in catalog.releases if r.release_id == catalog.active_release_id)
    live_bytes = _fetch(
        client,
        f"{PRODUCTION_PROFILE.public_origin}/{blob_key(active.manifest_sha256)}",
        active.manifest_byte_length,
    )
    live = verify_release_manifest(live_bytes, active, game="forever", supported_data_level=1)
    manifest = parse_strict(OtaReleaseManifest, staged)
    verify_release_manifest(
        staged,
        catalog_entry(staged, manifest, published_at=manifest.created_at),
        game="forever",
        supported_data_level=1,
    )
    report = obj(strict_json(report_bytes))
    recorded = obj(obj(obj(report.get("facts")).get("releases")).get(manifest.release_id))
    require(
        recorded.get("manifestSha256") == sha256_ref(staged)
        and manifest.client_build == live.client_build
        and manifest.min_data_level == live.min_data_level
        and manifest_files(staged) == manifest_files(live_bytes),
        "STAGING_BASE",
        "staged base does not match the verified live base files",
    )


def prepare_request(
    client: httpx.Client,
    *,
    token: str,
    ci_run_id: int,
    ci_attempt: int,
    repo: Path,
    keyring: Mapping[str, TrustedKey],
    now: datetime,
    destination: Path,
    sleep: Callable[[float], None] = time.sleep,
) -> PreparedProposal | None:
    api = GitHub(client, token)
    ci = api.run(ci_run_id, ci_attempt)
    jobs = api.pages(f"/actions/runs/{ci_run_id}/attempts/{ci_attempt}/jobs", "jobs")
    source = verify_ci(ci, jobs, run_id=ci_run_id, attempt=ci_attempt)
    catalog = read_live(client, keyring, now, full=False)
    candidate = select_candidate(repo, source, catalog)
    if candidate is None:
        return None
    verified_catalog = read_live(client, keyring, now)
    require(catalog == verified_catalog, "BASE_CHANGED", "production changed before staging")
    release = f"{candidate}-r1"
    active = next(r for r in catalog.releases if r.release_id == catalog.active_release_id)
    key = correlation_key(source, release, active.release_id, active.manifest_sha256)
    frozen = read_tag(api, "ota-package/" + key, source)
    kept = api.content(f"{REQUEST_DIR}/{release}.json", f"ota-release/{release}")
    if kept is not None and frozen is not None:
        same(
            parse_request(kept), parse_request(frozen), "REQUEST_CONFLICT", "branch/frozen request"
        )
    kept = kept if kept is not None else frozen
    if kept is not None:
        request = parse_request(kept)
        same(request.source_commit, source, "SOURCE_CHANGED", "frozen source")
        check_base(catalog, request)
        proposal = load_proposal(api, request, destination=destination)
    else:
        reservation = read_tag(api, "ota-preparation/" + key, source)
        # Discover a frozen package before any new staging or package dispatch.
        existing = [
            r
            for r in api.pages(
                f"/actions/workflows/{Path(PACKAGE_WORKFLOW).name}/runs",
                "workflow_runs",
                params={"event": "workflow_dispatch", "branch": "main"},
            )
            if r.get("display_title") == f"ota-package-{key}"
        ]
        require(len(existing) <= 1, "RUN_AMBIGUOUS", "multiple package runs for one identity")
        require(
            bool(existing) or reservation is None,
            "PACKAGE_HISTORY_MISSING",
            "reserved package disappeared before request persistence; no replacement authorized",
        )
        reserved_now = False
        if not existing:
            staging = completed_run(
                api,
                STAGING_WORKFLOW,
                key,
                {"revision": source, "base": active.client_build, "candidate": candidate},
                sleep=sleep,
            )
            stage_artifact = artifact_for(
                api, staging, f"ota-staging-verify-{staging['id']}-{staging['run_attempt']}"
            )
            with tempfile.TemporaryDirectory(prefix="ota-staging-evidence-") as scratch:
                root = extract_data_archive(
                    api.archive(stage_artifact), Path(scratch).resolve() / "stage"
                )
                report, update = (
                    (root / "staging-report.json").read_bytes(),
                    (root / "client" / "theCandidateReplacesTheBaseOverHttps.json").read_bytes(),
                )
                manifest = (root / "releases" / release / "release.json").read_bytes()
                binding = bind_staging(
                    manifest,
                    report=report,
                    staging_manifest=manifest,
                    update_evidence=update,
                    run=run_record(staging),
                    repository=REPOSITORY,
                )
                require(
                    binding.status == "PASS"
                    and binding.staging_revision == source
                    and binding.staging_base == active.client_build,
                    "STAGING_BINDING",
                    "staging verified different inputs",
                )
                verify_execution(report, update)
                base_path = root / "releases" / f"{active.client_build}-r1" / "release.json"
                require(
                    base_path.is_file(), "STAGING_BASE", "staging artifact holds no base manifest"
                )
                verify_staged_base(client, catalog, base_path.read_bytes(), report)
            reservation_message = json.dumps(
                {
                    "format": "ota-package-reservation-v1",
                    "sourceCommit": source,
                    "releaseId": release,
                    "baseReleaseId": active.release_id,
                    "baseManifestSha256": active.manifest_sha256,
                },
                sort_keys=True,
            ).encode()
            write_tag(
                api, "ota-preparation/" + key, source, reservation_message, str(ci["created_at"])
            )
            reserved_now = True
        package_run = completed_run(
            api,
            PACKAGE_WORKFLOW,
            key,
            {
                "source_revision": source,
                "build": candidate,
                "base": active.client_build,
                "release_revision": "1",
                "staging_run": str(staging["id"]) if not existing else "",
                "staging_attempt": str(staging["run_attempt"]) if not existing else "",
                "created_at": str(ci["created_at"]),
            },
            sleep=sleep,
            allow_dispatch=reserved_now,
        )
        artifact = artifact_for(
            api,
            package_run,
            f"ota-release-package-{package_run['id']}-{package_run['run_attempt']}",
        )
        archive = api.archive(artifact)
        package = load_package(extract_verified_archive(archive, destination))
        recorded_run = obj(strict_json(package.attachment(STAGING_FILES["run"])))
        staging_pin = RunPin(
            workflow_path=STAGING_WORKFLOW,
            run_id=positive(recorded_run.get("id")),
            attempt=positive(recorded_run.get("attempt")),
            head_sha=str(recorded_run.get("headSha")),
        )
        package_pin = RunPin(
            workflow_path=PACKAGE_WORKFLOW,
            run_id=package_run["id"],
            attempt=package_run["run_attempt"],
            head_sha=package_run["head_sha"],
        )
        artifact_pin = ArtifactPin(
            artifact_id=artifact["id"], name=artifact["name"], archive_sha256=artifact["digest"]
        )
        proposal = make_proposal(
            package,
            staging_run=staging_pin,
            package_run=package_pin,
            artifact=artifact_pin,
            base_manifest_sha256=active.manifest_sha256,
            base_release_id=active.release_id,
        )
        same(proposal.request.source_commit, source, "SOURCE_CHANGED", "package source")
        verify_package_origin(
            proposal.request,
            run_metadata={
                "package": package_run,
                "staging": api.run(staging_pin.run_id, staging_pin.attempt),
            },
            artifact_metadata=artifact,
            archive_bytes=archive,
            package=package,
        )
    # Independently persist the exact request even if the App job never starts.
    write_tag(
        api,
        "ota-package/" + key,
        source,
        request_text(proposal).encode(),
        proposal.request.created_at,
    )
    check_base(read_live(client, keyring, now), proposal.request)
    verified_root = (
        destination if (destination / "package.json").is_file() else destination / release
    )
    require(
        (verified_root / STAGING_BASE_MANIFEST_NAME).is_file(),
        "STAGING_BASE",
        "package lacks staged base evidence",
    )
    verify_staged_base(
        client,
        catalog,
        (verified_root / STAGING_BASE_MANIFEST_NAME).read_bytes(),
        (verified_root / STAGING_FILES["report"]).read_bytes(),
    )
    return proposal


def request_text(proposal: PreparedProposal) -> str:
    return proposal.request.model_dump_json(by_alias=True, indent=2) + "\n"


def ensure_release_pr(client: httpx.Client, *, proposal: PreparedProposal, app_token: str) -> str:
    api = GitHub(client, app_token)
    installation = obj(api.call("GET", "/installation/repositories", params={"per_page": 100}))
    repos = installation.get("repositories")
    require(
        type(installation.get("total_count")) is int
        and installation.get("total_count") == 1
        and isinstance(repos, list)
        and len(repos) == 1
        and obj(repos[0]).get("full_name") == REPOSITORY,
        "APP_SCOPE",
        "installation token must be scoped to TalentForge only",
    )
    request = proposal.request
    _main_commit(request.source_commit)
    branch = f"ota-release/{request.release_id}"
    paths = {
        f"{REQUEST_DIR}/{request.release_id}.json": request_text(proposal),
        f"{APPROVAL_DIR}/{request.release_id}.md": proposal.approval_template,
    }
    message = (
        f"ota release proposal: {request.release_id}\n\n"
        f"Request-SHA256: {sha256_ref(request_text(proposal).encode())}\n"
    )
    ref_path = "/git/ref/heads/" + branch
    ref = api.repo("GET", ref_path, missing_ok=True)
    if ref is None:
        parent = obj(api.repo("GET", f"/git/commits/{request.source_commit}"))
        tree = obj(
            api.repo(
                "POST",
                "/git/trees",
                data={
                    "base_tree": obj(parent.get("tree"))["sha"],
                    "tree": [
                        {"path": name, "mode": "100644", "type": "blob", "content": content}
                        for name, content in paths.items()
                    ],
                },
            )
        )
        identity = {**BOT, "date": request.created_at}
        commit = obj(
            api.repo(
                "POST",
                "/git/commits",
                data={
                    "message": message,
                    "tree": tree["sha"],
                    "parents": [request.source_commit],
                    "author": identity,
                    "committer": identity,
                },
            )
        )
        head = commit["sha"]
        api.repo("POST", "/git/refs", data={"ref": "refs/heads/" + branch, "sha": head})
    else:
        head = obj(obj(ref).get("object"))["sha"]
        commit = obj(api.repo("GET", f"/git/commits/{head}"))
        require(
            commit.get("message", "").rstrip() == message.rstrip()
            and isinstance(commit.get("parents"), list)
            and len(commit["parents"]) == 1
            and obj(commit["parents"][0]).get("sha") == request.source_commit,
            "BRANCH_CONFLICT",
            "release branch contains manual or foreign commits",
        )
        for name, content in paths.items():
            require(
                api.content(name, head) == content.encode(),
                "BRANCH_CONFLICT",
                "release branch files differ; owner edits are never overwritten",
            )
        comparison = obj(api.repo("GET", f"/compare/{request.source_commit}...{head}"))
        require(
            comparison.get("total_commits") == 1
            and {f.get("filename") for f in comparison.get("files", [])} == set(paths),
            "BRANCH_CONFLICT",
            "release branch has unrelated changes",
        )
    # Re-read before opening a PR; never patch or force-update a review branch.
    same(
        obj(obj(api.repo("GET", ref_path)).get("object")).get("sha"),
        head,
        "BRANCH_CHANGED",
        "branch head",
    )
    pulls = api.pages(
        "/pulls", params={"head": REPOSITORY.split("/")[0] + ":" + branch, "state": "all"}
    )
    require(len(pulls) <= 1, "PR_AMBIGUOUS", "multiple release PRs")
    if pulls:
        pull = pulls[0]
        require(
            pull.get("state") == "open"
            and obj(pull.get("head")).get("sha") == head
            and obj(pull.get("base")).get("ref") == "main",
            "PR_CLOSED_OR_CHANGED",
            "existing release PR is closed or changed; not reopened",
        )
        return str(pull["html_url"])
    pull = obj(
        api.repo(
            "POST",
            "/pulls",
            data={
                "title": f"OTA release {request.release_id}: OWNER review",
                "head": branch,
                "base": "main",
                "body": proposal.pr_body,
                "draft": True,
            },
        )
    )
    return str(pull["html_url"])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check-app")
    go = sub.add_parser("prepare-request")
    go.add_argument("--ci-run-id", type=int, required=True)
    go.add_argument("--ci-attempt", type=int, required=True)
    go.add_argument("--out", type=Path, required=True)
    propose = sub.add_parser("propose-request")
    propose.add_argument("--request", type=Path, required=True)
    propose.add_argument("--ci-run-id", type=int, required=True)
    propose.add_argument("--ci-attempt", type=int, required=True)
    stage = sub.add_parser("fetch-staging")
    stage.add_argument("--run-id", type=int, required=True)
    stage.add_argument("--attempt", type=int, required=True)
    stage.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "check-app":
            require(
                bool(os.environ.get("OTA_APP_CLIENT_ID", "").strip())
                and bool(os.environ.get("OTA_APP_PRIVATE_KEY", "").strip()),
                "APP_CONFIG_MISSING",
                "configure OTA_APP_CLIENT_ID and OTA_APP_PRIVATE_KEY in ota-preparation; "
                "autonomous proposal is not ready",
            )
            print("App configuration present (installation permissions still require validation)")
            return 0
        with httpx.Client(follow_redirects=False, timeout=30.0) as client:
            api = GitHub(client, os.environ.get("GH_TOKEN", ""))
            if args.command == "fetch-staging":
                run = api.run(positive(args.run_id), positive(args.attempt))
                pin = RunPin(
                    workflow_path=STAGING_WORKFLOW,
                    run_id=args.run_id,
                    attempt=args.attempt,
                    head_sha=str(run.get("head_sha")),
                )
                _run(run, pin, STAGING_WORKFLOW)
                artifact = artifact_for(
                    api, run, f"ota-staging-verify-{args.run_id}-{args.attempt}"
                )
                extract_data_archive(api.archive(artifact), args.out.resolve())
                (args.out.parent / "staging-run.json").write_text(json.dumps(run))
                return 0
            invocation_from_environment(api, positive(args.ci_run_id), positive(args.ci_attempt))
            keyring = production_keyring(TRUSTED_REPO / "docs/ota/keys/production-keyring.json")
            now = datetime.now(UTC)
            if args.command == "prepare-request":
                proposal = prepare_request(
                    client,
                    token=api.token,
                    ci_run_id=positive(args.ci_run_id),
                    ci_attempt=positive(args.ci_attempt),
                    repo=TRUSTED_REPO,
                    keyring=keyring,
                    now=now,
                    destination=args.out.resolve() / "package",
                )
                args.out.mkdir(parents=True, exist_ok=True)
                if proposal is None:
                    print("NO_ACTION: no newer unlisted committed data; no production publication")
                    return 0
                (args.out / "release-request.json").write_text(request_text(proposal))
                print(f"PREPARED: {proposal.request.release_id} (not approval or publication)")
            else:
                request = parse_request(args.request.read_bytes())
                ci_source = verify_ci(
                    api.run(args.ci_run_id, args.ci_attempt),
                    api.pages(
                        f"/actions/runs/{args.ci_run_id}/attempts/{args.ci_attempt}/jobs", "jobs"
                    ),
                    run_id=args.ci_run_id,
                    attempt=args.ci_attempt,
                )
                same(request.source_commit, ci_source, "CI_SOURCE", "request source")
                check_base(read_live(client, keyring, now), request)
                with tempfile.TemporaryDirectory(prefix="ota-proposal-") as scratch:
                    package_destination = Path(scratch).resolve() / "package"
                    proposal = load_proposal(api, request, destination=package_destination)
                    root = (
                        package_destination
                        if (package_destination / "package.json").is_file()
                        else package_destination / request.release_id
                    )
                    require(
                        (root / STAGING_BASE_MANIFEST_NAME).is_file(),
                        "STAGING_BASE",
                        "package lacks staged base evidence",
                    )
                    verify_staged_base(
                        client,
                        read_live(client, keyring, now),
                        (root / STAGING_BASE_MANIFEST_NAME).read_bytes(),
                        (root / STAGING_FILES["report"]).read_bytes(),
                    )
                check_base(read_live(client, keyring, now), request)
                url = ensure_release_pr(
                    client, proposal=proposal, app_token=os.environ.get("OTA_APP_TOKEN", "")
                )
                print(f"DRAFT_RELEASE_PR: {url}")
    except (PreparationBlocked, RequestRejected, ValueError, OSError) as exc:
        # Do not echo raw network/OS exceptions which might contain credential-bearing URLs.
        code = (
            exc.code
            if isinstance(exc, (PreparationBlocked, RequestRejected))
            else type(exc).__name__
        )
        message = f"BLOCKED: {code}; no production data was published"
        if isinstance(exc, PreparationBlocked) and code == "APP_CONFIG_MISSING":
            message += "; " + exc.detail
        print(message)
        if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
            with Path(summary).open("a", encoding="utf-8") as out:
                out.write(message + "\n")
        return 3
    else:
        return 0
