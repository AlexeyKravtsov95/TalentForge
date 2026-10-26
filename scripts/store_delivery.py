#!/usr/bin/env python3
"""Store delivery orchestration for ``.github/workflows/store-delivery.yml`` (issue #90).

One owner-started run takes one revision of ``main`` (or a release tag on it), checks that CI
passed for that exact commit, and delivers what that commit builds:

- Android: the bundle (``bundlePublishablePlay``) to an existing Google Play closed-testing
  track; the universal APK (``assemblePublishableRelease``) stays a verified run artifact;
- iOS: the App Store export of the signed archive to App Store Connect and an existing internal
  TestFlight group.

This module holds every decision the workflow makes; the workflow only builds, signs and calls
it. It talks to the stores through their own HTTP APIs (Google Play Developer API v3, App Store
Connect API v1) with ``httpx`` and signs the API tokens with ``cryptography`` - both are already
dependencies of the repository, so no delivery tool is installed. ``xcrun altool`` (part of
Xcode) uploads the ``.ipa``.

Everything is fail-closed. A refusal is a :class:`DeliveryError` whose ``code`` (never its
message) says what happened:

- ``GATE``: the revision is not an allowed one, or CI did not pass for it;
- ``CONFIG``: a credential or setting is missing or malformed (its value is never printed);
- ``CONFLICT``: the store already holds this version number for something this workflow did not
  deliver from this source commit, or a higher number: a used number is never reused;
- ``MISMATCH``: what a store or a report says differs from the verified artifact;
- ``STORE``: a store API refused a request or a build failed processing;
- ``TIMEOUT``: App Store Connect did not finish processing in time.

Repeated runs: before anything is built, each platform's store state is read. A version number
already in the store counts as delivered only when a record of an earlier run of this workflow
names the same source commit (and, on Google Play, the same bundle SHA-256 the store reports).
Records are artifacts named ``tf-delivery-…`` of runs of this workflow on ``main``; artifacts of
other workflows, branches or forks are never trusted.

Owner guide and the meaning of every status: ``docs/release/ci-delivery.md``.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import zipfile
from collections.abc import Callable, Iterator, Mapping, Sequence
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any, Literal

import httpx
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

APPLICATION_ID = "io.github.alexeykravtsov95.talentforge"
WORKFLOW_FILE = ".github/workflows/store-delivery.yml"
#: CI that must have passed for the exact commit that is delivered
REQUIRED_CI_WORKFLOWS = (".github/workflows/ci.yml", ".github/workflows/mobile-ci.yml")
ANDROID_GRADLE_FILE = "mobile/composeApp/build.gradle.kts"
IOS_PROJECT_FILE = "mobile/iosApp/iosApp.xcodeproj/project.pbxproj"
MAIN_REF = "refs/heads/main"

FULL_SHA = re.compile(r"[0-9a-f]{40}")
RELEASE_TAG = re.compile(r"v\d+\.\d+\.\d+(?:-[0-9A-Za-z][0-9A-Za-z.]*)?")
VERSION = re.compile(r"\d+\.\d+\.\d+")
PLATFORMS = ("android", "ios")
PLATFORM_CHOICES = {"both": PLATFORMS, "android": ("android",), "ios": ("ios",)}
MODES = ("verify", "deliver")

#: Google Play tracks this workflow refuses to release to: production is the owner's own
#: decision and open testing is public; the delivery target is an existing closed test.
REFUSED_PLAY_TRACKS = {
    "production": "the production track (a public release is the owner's separate decision)",
    "beta": "the open-testing track (open testing is public)",
}
PLAY_RELEASE_STATUSES = ("completed", "draft")
GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token"
PLAY_SCOPE = "https://www.googleapis.com/auth/androidpublisher"
PLAY_API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"
PLAY_UPLOAD_API = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/"
ASC_API = "https://api.appstoreconnect.apple.com/v1/"
ASC_AUDIENCE = "appstoreconnect-v1"
GITHUB_API = "https://api.github.com/"

#: what the Android release must carry with production OTA on (android-production-enablement §3)
ANDROID_OTA_REQUIRED = (b"https://ota.mythweld.com", b"tf-prod-2026a", b"tf-prod-2026b")
#: a staging key id; the bare prefix "tf-staging-" is OtaConfig's own refusal rule and stays
ANDROID_OTA_FORBIDDEN = re.compile(rb"tf-staging-[0-9a-z]+")
INTERNET_PERMISSION = "android.permission.INTERNET"

RECORD_NAME = re.compile(
    r"tf-delivery-(?P<platform>android|ios)-(?P<version>\d+\.\d+\.\d+)-(?P<build>[1-9]\d*)"
    r"-(?P<phase>delivered|upload-started)-(?P<source>[0-9a-f]{40})-(?P<artifact>[0-9a-f]{64})"
)
RecordPhase = Literal["delivered", "upload-started"]

EXIT_OK, EXIT_REFUSED = 0, 1
MAX_PAGES = 30
RETRY_STATUSES = frozenset({429, 500, 502, 503, 504})


class DeliveryError(Exception):
    """A refusal. Callers branch on ``code``; ``message`` is for the owner and never a secret."""

    CODES = frozenset({"GATE", "CONFIG", "CONFLICT", "MISMATCH", "STORE", "TIMEOUT"})

    def __init__(self, code: str, message: str) -> None:
        if code not in self.CODES:
            raise ValueError(f"unknown DeliveryError code {code!r}")
        super().__init__(message)
        self.code = code
        self.message = message


# --------------------------------------------------------------------------- small helpers


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def require_env(environ: Mapping[str, str], names: Sequence[str]) -> dict[str, str]:
    """The named settings, all non-empty, or one CONFIG refusal naming every missing one."""
    missing = [name for name in names if not environ.get(name, "").strip()]
    if missing:
        raise DeliveryError(
            "CONFIG",
            "missing delivery configuration: "
            + ", ".join(missing)
            + ". Add them to the GitHub Environment named in docs/release/ci-delivery.md §3; "
            "nothing is built unsigned, with a debug key or with OTA off instead.",
        )
    return {name: environ[name].strip() for name in names}


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def read_json_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise DeliveryError("MISMATCH", f"cannot read {path.name}: {error}") from error
    if not isinstance(value, dict):
        raise DeliveryError("MISMATCH", f"{path.name} is not a JSON object")
    return value


def set_outputs(values: Mapping[str, str], environ: Mapping[str, str]) -> None:
    target = environ.get("GITHUB_OUTPUT")
    if not target:
        return
    with Path(target).open("a", encoding="utf-8") as handle:
        for key, value in values.items():
            if "\n" in value:
                raise ValueError(f"output {key} must be one line")
            handle.write(f"{key}={value}\n")


def iso_now() -> str:
    return dt.datetime.now(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def send(
    client: httpx.Client,
    method: str,
    url: str,
    *,
    retry: bool,
    service: str,
    expect: tuple[int, ...] = (200,),
    sleep: Callable[[float], None] = time.sleep,
    **kwargs: Any,
) -> httpx.Response:
    """One API call. Idempotent calls (``retry``) are retried on 429/5xx and transport errors."""
    attempts = 3 if retry else 1
    for attempt in range(1, attempts + 1):
        try:
            response = client.request(method, url, **kwargs)
        except httpx.HTTPError as error:
            if attempt == attempts:
                raise DeliveryError(
                    "STORE",
                    f"{service}: {method} {httpx.URL(url).path} failed: {type(error).__name__}",
                ) from error
        else:
            if response.status_code in expect:
                return response
            if response.status_code not in RETRY_STATUSES or attempt == attempts:
                raise DeliveryError(
                    "STORE",
                    f"{service}: {method} {httpx.URL(url).path} answered HTTP "
                    f"{response.status_code}: {api_error(response)}",
                )
        sleep(2.0**attempt)
    raise AssertionError("unreachable")


def api_error(response: httpx.Response) -> str:
    """The error text a Google, Apple or GitHub API returns (never a request header)."""
    try:
        body = response.json()
    except ValueError:
        return response.text[:300]
    if isinstance(body, dict):
        error = body.get("error")
        if isinstance(error, dict):
            return str(error.get("message") or error.get("status") or error)[:500]
        if isinstance(error, str):
            return f"{error}: {body.get('error_description', '')}"[:500]
        errors = body.get("errors")
        if isinstance(errors, list) and errors:
            return "; ".join(
                f"{e.get('code')}: {e.get('detail') or e.get('title')}"
                for e in errors
                if isinstance(e, dict)
            )[:500]
        if "message" in body:
            return str(body["message"])[:500]
    return str(body)[:300]


# --------------------------------------------------------------------------- GitHub


class GitHubApi:
    """Read-only GitHub REST calls with the workflow's own token (``actions: read``)."""

    def __init__(self, repository: str, token: str, client: httpx.Client) -> None:
        self.repository = repository
        self.client = client
        self.headers = {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }

    def get(self, path: str, params: Mapping[str, str | int] | None = None) -> dict[str, Any]:
        response = send(
            self.client,
            "GET",
            f"{GITHUB_API}repos/{self.repository}/{path}",
            retry=True,
            service="GitHub",
            headers=self.headers,
            params=dict(params or {}),
        )
        value = response.json()
        if not isinstance(value, dict):
            raise DeliveryError("STORE", f"GitHub: {path} did not return an object")
        return value

    def workflow_runs(self, workflow_file: str, **params: str) -> Iterator[dict[str, Any]]:
        name = Path(workflow_file).name
        for page in range(1, MAX_PAGES + 1):
            body = self.get(
                f"actions/workflows/{name}/runs", {**params, "per_page": 100, "page": page}
            )
            runs = body.get("workflow_runs", [])
            yield from runs
            if len(runs) < 100:
                return

    def run_artifacts(self, run_id: int) -> list[dict[str, Any]]:
        artifacts: list[dict[str, Any]] = []
        for page in range(1, MAX_PAGES + 1):
            body = self.get(f"actions/runs/{run_id}/artifacts", {"per_page": 100, "page": page})
            batch = body.get("artifacts", [])
            artifacts += batch
            if len(batch) < 100:
                break
        return artifacts


def _same_repository(run: Mapping[str, Any]) -> bool:
    head, base = run.get("head_repository") or {}, run.get("repository") or {}
    return bool(head.get("id")) and head.get("id") == base.get("id")


def _workflow_path(run: Mapping[str, Any]) -> str:
    return str(run.get("path", "")).removesuffix(f"@{MAIN_REF}")


def check_ci(api: GitHubApi, sha: str) -> dict[str, str]:
    """The successful run of every required CI workflow for exactly ``sha`` on ``main``."""
    passed: dict[str, str] = {}
    for workflow in REQUIRED_CI_WORKFLOWS:
        runs = list(api.workflow_runs(workflow, head_sha=sha))
        good = [
            run
            for run in runs
            if run.get("head_sha") == sha
            and _workflow_path(run) == workflow
            and run.get("head_branch") == "main"
            and run.get("event") in ("push", "workflow_dispatch")
            and run.get("status") == "completed"
            and run.get("conclusion") == "success"
            and _same_repository(run)
        ]
        if not good:
            seen = ", ".join(
                f"#{r.get('run_number')} {r.get('event')}/{r.get('head_branch')} "
                f"{r.get('status')}/{r.get('conclusion')}"
                for r in runs
            )
            raise DeliveryError(
                "GATE",
                f"{workflow} has no successful run for {sha} on main "
                f"({'runs: ' + seen if seen else 'no runs'}). Re-run it for this commit and "
                "start the delivery again once it is green.",
            )
        passed[workflow] = str(good[0].get("html_url", good[0].get("id")))
    return passed


@dataclass(frozen=True)
class Record:
    """One ``tf-delivery-…`` artifact of an earlier (or this) run of this workflow."""

    platform: str
    version: str
    build: int
    phase: RecordPhase
    source_sha: str
    artifact_sha256: str
    run_id: int | None = None
    created_at: str | None = None  # when the run kept the record (GitHub's artifact time)

    @property
    def name(self) -> str:
        return (
            f"tf-delivery-{self.platform}-{self.version}-{self.build}-{self.phase}-"
            f"{self.source_sha}-{self.artifact_sha256}"
        )


def parse_record_name(name: str) -> Record | None:
    match = RECORD_NAME.fullmatch(name)
    if match is None:
        return None
    phase: RecordPhase = "delivered" if match["phase"] == "delivered" else "upload-started"
    return Record(
        platform=match["platform"],
        version=match["version"],
        build=int(match["build"]),
        phase=phase,
        source_sha=match["source"],
        artifact_sha256=match["artifact"],
    )


def delivery_records(api: GitHubApi) -> list[Record]:
    """Records kept by runs of this workflow on ``main`` in this repository, and only those."""
    records: list[Record] = []
    for run in api.workflow_runs(WORKFLOW_FILE, branch="main", event="workflow_dispatch"):
        if not (
            _workflow_path(run) == WORKFLOW_FILE
            and run.get("head_branch") == "main"
            and run.get("event") == "workflow_dispatch"
            and _same_repository(run)
        ):
            continue
        for artifact in api.run_artifacts(int(run["id"])):
            if artifact.get("expired"):
                continue
            record = parse_record_name(str(artifact.get("name", "")))
            if record is not None:
                records.append(
                    replace(record, run_id=int(run["id"]), created_at=artifact.get("created_at"))
                )
    return records


# --------------------------------------------------------------------------- revision gate


@dataclass(frozen=True)
class Versions:
    android_name: str
    android_code: int
    ios_version: str
    ios_build: int


def git(repo: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, text=True, check=False
    )


def resolve_revision(repo: Path, revision: str, workflow_sha: str) -> tuple[str, str]:
    """``(full sha, kind)`` of an allowed revision: main's head, a commit on main, a release tag."""
    revision = revision.strip()
    if not revision:
        sha, kind = workflow_sha, "main-head"
    elif FULL_SHA.fullmatch(revision):
        sha, kind = revision, "commit"
    elif RELEASE_TAG.fullmatch(revision):
        resolved = git(repo, "rev-parse", "--verify", "--quiet", f"refs/tags/{revision}^{{commit}}")
        if resolved.returncode != 0:
            raise DeliveryError("GATE", f"release tag {revision!r} does not exist")
        sha, kind = resolved.stdout.strip(), "tag"
    else:
        raise DeliveryError(
            "GATE",
            f"revision {revision!r} is not allowed: give a full 40-character commit SHA on main, "
            "a release tag vMAJOR.MINOR.PATCH on main, or leave it empty for main's head "
            "(short SHAs and branch names are refused)",
        )
    if git(repo, "cat-file", "-e", f"{sha}^{{commit}}").returncode != 0:
        raise DeliveryError("GATE", f"commit {sha} is not in the repository")
    if git(repo, "merge-base", "--is-ancestor", sha, "refs/remotes/origin/main").returncode != 0:
        raise DeliveryError("GATE", f"commit {sha} ({kind} {revision!r}) is not on main")
    return sha, kind


def android_version(gradle: str) -> tuple[str, int]:
    codes = re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", gradle, re.MULTILINE)
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]*)"\s*$', gradle, re.MULTILINE)
    if len(codes) != 1 or len(names) != 1:
        raise DeliveryError(
            "GATE",
            f"{ANDROID_GRADLE_FILE}: expected one versionCode and one versionName in "
            f"defaultConfig, found {len(codes)} / {len(names)}",
        )
    if not VERSION.fullmatch(names[0]) or int(codes[0]) < 1:
        raise DeliveryError(
            "GATE", f"Android version {names[0]!r} ({codes[0]}) is not MAJOR.MINOR.PATCH (N>0)"
        )
    return names[0], int(codes[0])


def ios_version(pbxproj: str) -> tuple[str, int]:
    versions = set(re.findall(r"\bMARKETING_VERSION = ([^;]+);", pbxproj))
    builds = set(re.findall(r"\bCURRENT_PROJECT_VERSION = ([^;]+);", pbxproj))
    if len(versions) != 1 or len(builds) != 1:
        raise DeliveryError(
            "GATE",
            f"{IOS_PROJECT_FILE}: Debug and Release must share one MARKETING_VERSION and one "
            f"CURRENT_PROJECT_VERSION, found {sorted(versions)} / {sorted(builds)}",
        )
    version, build = versions.pop().strip('"'), builds.pop().strip('"')
    if not VERSION.fullmatch(version) or not build.isdigit() or int(build) < 1:
        raise DeliveryError("GATE", f"iOS version {version!r} ({build!r}) is not MAJOR.MINOR.PATCH")
    return version, int(build)


def read_versions(repo: Path, sha: str) -> Versions:
    def show(path: str) -> str:
        shown = git(repo, "show", f"{sha}:{path}")
        if shown.returncode != 0:
            raise DeliveryError("GATE", f"{path} is missing at {sha}")
        return shown.stdout

    name, code = android_version(show(ANDROID_GRADLE_FILE))
    version, build = ios_version(show(IOS_PROJECT_FILE))
    return Versions(name, code, version, build)


def run_gate(
    *,
    repo: Path,
    revision: str,
    platforms: str,
    mode: str,
    environ: Mapping[str, str],
    api: GitHubApi,
) -> dict[str, Any]:
    if environ.get("GITHUB_REF") != MAIN_REF:
        raise DeliveryError(
            "GATE",
            f"the delivery workflow must be started from main, not {environ.get('GITHUB_REF')!r}: "
            "only main's definition of the workflow may use the delivery environments",
        )
    if platforms not in PLATFORM_CHOICES or mode not in MODES:
        raise DeliveryError("GATE", f"platforms {platforms!r} / mode {mode!r} are not allowed")
    workflow_sha = environ.get("GITHUB_SHA", "")
    if not FULL_SHA.fullmatch(workflow_sha):
        raise DeliveryError("GATE", "GITHUB_SHA is not a full commit SHA")
    sha, kind = resolve_revision(repo, revision, workflow_sha)
    ci = check_ci(api, sha)
    versions = read_versions(repo, sha)
    return {
        "schema": "talentforge-delivery-gate/1",
        "revisionInput": revision,
        "revisionKind": kind,
        "sourceSha": sha,
        "workflowSha": workflow_sha,
        "platforms": list(PLATFORM_CHOICES[platforms]),
        "mode": mode,
        "ci": ci,
        "android": {"versionName": versions.android_name, "versionCode": versions.android_code},
        "ios": {"version": versions.ios_version, "build": versions.ios_build},
        "at": iso_now(),
    }


# --------------------------------------------------------------------------- tokens


def _jwt_segment(value: Mapping[str, object]) -> str:
    return b64url(json.dumps(dict(value), separators=(",", ":")).encode())


def _jwt_parts(header: Mapping[str, str], claims: Mapping[str, object]) -> str:
    return f"{_jwt_segment(header)}.{_jwt_segment(claims)}"


def rs256_jwt(private_key: rsa.RSAPrivateKey, claims: Mapping[str, object]) -> str:
    signing_input = _jwt_parts({"alg": "RS256", "typ": "JWT"}, claims)
    signature = private_key.sign(signing_input.encode(), padding.PKCS1v15(), hashes.SHA256())
    return f"{signing_input}.{b64url(signature)}"


def es256_jwt(
    private_key: ec.EllipticCurvePrivateKey, key_id: str, claims: Mapping[str, object]
) -> str:
    signing_input = _jwt_parts({"alg": "ES256", "kid": key_id, "typ": "JWT"}, claims)
    r, s = decode_dss_signature(private_key.sign(signing_input.encode(), ec.ECDSA(hashes.SHA256())))
    return f"{signing_input}.{b64url(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"


# --------------------------------------------------------------------------- Google Play


@dataclass(frozen=True)
class ServiceAccount:
    email: str
    private_key: rsa.RSAPrivateKey


def parse_service_account(text: str) -> ServiceAccount:
    """The Play service account key JSON; a malformed one is refused without echoing it."""
    try:
        value = json.loads(text)
    except ValueError as error:
        raise DeliveryError("CONFIG", "PLAY_SERVICE_ACCOUNT_JSON is not JSON") from error
    if not isinstance(value, dict) or value.get("type") != "service_account":
        raise DeliveryError("CONFIG", "PLAY_SERVICE_ACCOUNT_JSON is not a service account key")
    email, pem = value.get("client_email"), value.get("private_key")
    if not isinstance(email, str) or not email.endswith(".iam.gserviceaccount.com"):
        raise DeliveryError("CONFIG", "PLAY_SERVICE_ACCOUNT_JSON has no service account e-mail")
    if value.get("token_uri", GOOGLE_TOKEN_URI) != GOOGLE_TOKEN_URI:
        raise DeliveryError(
            "CONFIG", f"PLAY_SERVICE_ACCOUNT_JSON token_uri is not {GOOGLE_TOKEN_URI}"
        )
    try:
        key = serialization.load_pem_private_key(str(pem).encode(), password=None)
    except (ValueError, TypeError) as error:
        raise DeliveryError(
            "CONFIG", "PLAY_SERVICE_ACCOUNT_JSON private_key is not a PEM key"
        ) from error
    if not isinstance(key, rsa.RSAPrivateKey):
        raise DeliveryError("CONFIG", "PLAY_SERVICE_ACCOUNT_JSON private_key is not an RSA key")
    return ServiceAccount(email, key)


@dataclass(frozen=True)
class PlayState:
    bundles: dict[int, str]  # versionCode -> SHA-256 of every bundle the app has
    apks: dict[int, str]  # versionCode -> SHA-256 of every APK the app has
    tracks: dict[str, list[dict[str, Any]]]  # track -> its releases

    def tracks_with(self, version_code: int) -> list[str]:
        return sorted(
            name
            for name, releases in self.tracks.items()
            if any(str(version_code) in release.get("versionCodes", []) for release in releases)
        )


class GooglePlay:
    """The Google Play Developer API v3 edits this workflow uses."""

    def __init__(
        self,
        account: ServiceAccount,
        client: httpx.Client,
        *,
        package: str = APPLICATION_ID,
        clock: Callable[[], float] = time.time,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self.account = account
        self.client = client
        self.package = package
        self.clock = clock
        self.sleep = sleep
        self._token: tuple[str, float] | None = None

    def _headers(self) -> dict[str, str]:
        now = self.clock()
        if self._token is None or self._token[1] - 60 < now:
            assertion = rs256_jwt(
                self.account.private_key,
                {
                    "iss": self.account.email,
                    "scope": PLAY_SCOPE,
                    "aud": GOOGLE_TOKEN_URI,
                    "iat": int(now),
                    "exp": int(now) + 3600,
                },
            )
            response = send(
                self.client,
                "POST",
                GOOGLE_TOKEN_URI,
                retry=True,
                service="Google OAuth",
                sleep=self.sleep,
                data={
                    "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                    "assertion": assertion,
                },
            )
            body = response.json()
            token = body.get("access_token") if isinstance(body, dict) else None
            if not isinstance(token, str):
                raise DeliveryError("STORE", "Google OAuth returned no access token")
            self._token = (token, now + float(body.get("expires_in", 3600)))
        return {"Authorization": f"Bearer {self._token[0]}"}

    def _call(
        self,
        method: str,
        path: str,
        *,
        upload: bool = False,
        retry: bool = False,
        headers: Mapping[str, str] | None = None,
        **kwargs: Any,
    ) -> httpx.Response:
        base = PLAY_UPLOAD_API if upload else PLAY_API
        return send(
            self.client,
            method,
            f"{base}{self.package}/{path}",
            retry=retry,
            service="Google Play",
            sleep=self.sleep,
            headers={**self._headers(), **(headers or {})},
            **kwargs,
        )

    def insert_edit(self) -> str:
        edit = self._call("POST", "edits", json={}).json().get("id")
        if not isinstance(edit, str) or not edit:
            raise DeliveryError("STORE", "Google Play did not open an edit")
        return edit

    def delete_edit(self, edit: str) -> None:
        self._call("DELETE", f"edits/{edit}", expect=(200, 204))

    def state(self, edit: str) -> PlayState:
        bundles = self._call("GET", f"edits/{edit}/bundles", retry=True).json().get("bundles", [])
        apks = self._call("GET", f"edits/{edit}/apks", retry=True).json().get("apks", [])
        tracks = self._call("GET", f"edits/{edit}/tracks", retry=True).json().get("tracks", [])
        return PlayState(
            bundles={int(b["versionCode"]): str(b.get("sha256", "")) for b in bundles},
            apks={int(a["versionCode"]): str(a.get("binary", {}).get("sha256", "")) for a in apks},
            tracks={str(t["track"]): list(t.get("releases", [])) for t in tracks},
        )

    def upload_bundle(self, edit: str, aab: Path) -> tuple[int, str]:
        data = aab.read_bytes()
        session = self._call(
            "POST",
            f"edits/{edit}/bundles",
            upload=True,
            params={"uploadType": "resumable"},
            headers={
                "X-Upload-Content-Type": "application/octet-stream",
                "X-Upload-Content-Length": str(len(data)),
            },
            json={},
        )
        location = session.headers.get("location", "")
        if not location.startswith(PLAY_UPLOAD_API):
            raise DeliveryError("STORE", "Google Play did not start a resumable upload")
        response = send(
            self.client,
            "PUT",
            location,
            retry=False,
            service="Google Play",
            expect=(200, 201),
            headers={**self._headers(), "Content-Type": "application/octet-stream"},
            content=data,
            timeout=900.0,
        )
        body = response.json()
        return int(body.get("versionCode", -1)), str(body.get("sha256", ""))

    def set_track(self, edit: str, track: str, release: dict[str, Any]) -> None:
        self._call(
            "PUT", f"edits/{edit}/tracks/{track}", json={"track": track, "releases": [release]}
        )

    def commit(self, edit: str, *, changes_not_sent_for_review: bool) -> None:
        params = {"changesNotSentForReview": "true"} if changes_not_sent_for_review else {}
        self._call("POST", f"edits/{edit}:commit", params=params)


@dataclass(frozen=True)
class Decision:
    kind: Literal["UPLOAD", "ALREADY_DELIVERED", "ALREADY_UPLOADED"]
    detail: str
    delivered_sha256: str | None = None
    record_run_id: int | None = None
    tracks: list[str] = field(default_factory=list)


def check_play_track(track: str, state: PlayState | None = None) -> None:
    if track in REFUSED_PLAY_TRACKS:
        raise DeliveryError("CONFIG", f"PLAY_TRACK={track!r} is {REFUSED_PLAY_TRACKS[track]}")
    if not re.fullmatch(r"[A-Za-z0-9:_.-]+", track):
        raise DeliveryError("CONFIG", f"PLAY_TRACK={track!r} is not a track name")
    if state is not None and track not in state.tracks:
        raise DeliveryError(
            "CONFIG",
            f"PLAY_TRACK={track!r} is not an existing track of the app "
            f"(existing: {', '.join(sorted(state.tracks)) or 'none'})",
        )


def decide_play(
    state: PlayState,
    version_code: int,
    *,
    source_sha: str,
    records: Sequence[Record],
    artifact_sha256: str | None = None,
    track: str | None = None,
) -> Decision:
    """Whether ``version_code`` may be uploaded, was delivered already, or is a conflict.

    With ``artifact_sha256`` (right before the upload) only that exact bundle counts as
    delivered; without it (before anything is built) a record of an earlier run of this
    workflow for the same source commit and the store's own SHA-256 does. A delivered code
    that is not (or no longer) on ``track`` is a mismatch, never a success.
    """
    known = {**state.apks, **state.bundles}
    higher = sorted(code for code in known if code > version_code)
    if higher:
        raise DeliveryError(
            "CONFLICT",
            f"Google Play already has versionCode {higher[-1]}, higher than {version_code}: "
            "raise versionCode in a separate reviewed PR (docs/release/android-versioning.md §5)",
        )
    if version_code not in known:
        return Decision("UPLOAD", f"versionCode {version_code} is not in Google Play yet")
    if version_code not in state.bundles:
        raise DeliveryError(
            "CONFLICT", f"Google Play has an APK, not a bundle, with versionCode {version_code}"
        )
    store_sha = state.bundles[version_code]
    tracks = state.tracks_with(version_code)

    def delivered(detail: str, run_id: int | None = None) -> Decision:
        if track is not None and track not in tracks:
            raise DeliveryError(
                "MISMATCH",
                f"versionCode {version_code} (SHA-256 {store_sha}) is in Google Play but not on "
                f"PLAY_TRACK={track!r} (tracks {tracks}): check the track in Play Console; it is "
                "not uploaded again",
            )
        return Decision(
            "ALREADY_DELIVERED",
            detail,
            delivered_sha256=store_sha,
            record_run_id=run_id,
            tracks=tracks,
        )

    if artifact_sha256 is not None:
        if store_sha == artifact_sha256:
            return delivered(
                f"versionCode {version_code} is in Google Play with this run's bundle "
                f"(SHA-256 {store_sha}), tracks {tracks}"
            )
        raise DeliveryError(
            "CONFLICT",
            f"versionCode {version_code} is in Google Play with bundle SHA-256 {store_sha}, but "
            f"this run's verified bundle is {artifact_sha256}: the number is consumed by other "
            "bytes and is never uploaded again",
        )
    for record in records:
        if (
            record.platform == "android"
            and record.phase == "delivered"
            and record.build == version_code
            and record.source_sha == source_sha
            and record.artifact_sha256 == store_sha
        ):
            return delivered(
                f"versionCode {version_code} (SHA-256 {store_sha}) was delivered from "
                f"{source_sha} by run {record.run_id}; tracks {tracks}",
                record.run_id,
            )
    raise DeliveryError(
        "CONFLICT",
        f"versionCode {version_code} is already in Google Play (bundle SHA-256 {store_sha}, "
        f"tracks {tracks}) and no run of this workflow delivered it from {source_sha}: the "
        "number is consumed. Raise versionCode in a separate reviewed PR "
        "(docs/release/android-versioning.md §5)",
    )


def play_check(
    play: GooglePlay, *, gate: Mapping[str, Any], track: str, records: Sequence[Record]
) -> Decision:
    """Read-only: an edit is opened to read the app's bundles and tracks, then deleted."""
    check_play_track(track)
    edit = play.insert_edit()
    try:
        state = play.state(edit)
    finally:
        play.delete_edit(edit)
    check_play_track(track, state)
    return decide_play(
        state,
        int(gate["android"]["versionCode"]),
        source_sha=str(gate["sourceSha"]),
        records=records,
        track=track,
    )


def play_deliver(
    play: GooglePlay,
    *,
    gate: Mapping[str, Any],
    aab: Path,
    track: str,
    release_status: str,
    changes_not_sent_for_review: bool,
) -> dict[str, Any]:
    """Upload the verified bundle and release it on ``track`` in one edit, then read it back."""
    check_play_track(track)
    if release_status not in PLAY_RELEASE_STATUSES:
        raise DeliveryError(
            "CONFIG",
            f"PLAY_RELEASE_STATUS={release_status!r} is not one of {PLAY_RELEASE_STATUSES}",
        )
    code, name = int(gate["android"]["versionCode"]), str(gate["android"]["versionName"])
    source = str(gate["sourceSha"])
    artifact_sha = file_sha256(aab)
    edit = play.insert_edit()
    committed = False
    try:
        state = play.state(edit)
        check_play_track(track, state)
        decision = decide_play(
            state, code, source_sha=source, records=(), artifact_sha256=artifact_sha, track=track
        )
        if decision.kind == "ALREADY_DELIVERED":
            return {
                "status": "ALREADY_DELIVERED",
                "detail": decision.detail,
                "deliveredSha256": artifact_sha,
                "tracks": decision.tracks,
            }
        uploaded_code, uploaded_sha = play.upload_bundle(edit, aab)
        if (uploaded_code, uploaded_sha) != (code, artifact_sha):
            raise DeliveryError(
                "MISMATCH",
                f"Google Play read the upload as versionCode {uploaded_code}, SHA-256 "
                f"{uploaded_sha}; the verified bundle is {code}, {artifact_sha}. Nothing was "
                "committed",
            )
        play.set_track(
            edit,
            track,
            {
                "name": f"{name} ({code}) {source[:12]}",
                "versionCodes": [str(code)],
                "status": release_status,
            },
        )
        play.commit(edit, changes_not_sent_for_review=changes_not_sent_for_review)
        committed = True
    finally:
        if not committed:
            try:
                play.delete_edit(edit)
            except DeliveryError as cleanup:
                print(f"::warning::the open edit was not deleted: {cleanup.message}")
    check = play.insert_edit()
    try:
        after = play.state(check)
    finally:
        play.delete_edit(check)
    if after.bundles.get(code) != artifact_sha or track not in after.tracks_with(code):
        raise DeliveryError(
            "MISMATCH",
            f"after the commit Google Play shows versionCode {code} as "
            f"{after.bundles.get(code)!r} on tracks {after.tracks_with(code)}, expected "
            f"{artifact_sha} on {track}",
        )
    return {
        "status": "DELIVERED",
        "detail": f"versionCode {code} uploaded and released ({release_status}) on {track}",
        "deliveredSha256": artifact_sha,
        "tracks": after.tracks_with(code),
    }


# --------------------------------------------------------------------------- App Store Connect


@dataclass(frozen=True)
class AscBuild:
    id: str
    number: str
    version: str | None  # the marketing version (preReleaseVersion)
    processing_state: str
    expired: bool
    uses_non_exempt_encryption: bool | None
    uploaded: str | None


def normalize_asc_key(pem: str) -> str:
    """The .p8 key as PEM, whether stored whole, with literal ``\\n`` or as its base64 body."""
    text = pem.strip().replace("\\n", "\n")
    if "BEGIN PRIVATE KEY" not in text:  # the owner may store only the base64 body
        text = f"-----BEGIN PRIVATE KEY-----\n{text}\n-----END PRIVATE KEY-----"
    return text + "\n"


def load_asc_key(pem: str) -> ec.EllipticCurvePrivateKey:
    text = normalize_asc_key(pem)
    try:
        key = serialization.load_pem_private_key(text.encode(), password=None)
    except (ValueError, TypeError) as error:
        raise DeliveryError(
            "CONFIG", "ASC_API_PRIVATE_KEY is not an App Store Connect .p8 key"
        ) from error
    if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
        raise DeliveryError("CONFIG", "ASC_API_PRIVATE_KEY is not a P-256 key")
    return key


class AppStoreConnect:
    """The App Store Connect API v1 calls this workflow uses (Team key, App Manager role)."""

    def __init__(
        self,
        key_id: str,
        issuer_id: str,
        private_key: ec.EllipticCurvePrivateKey,
        client: httpx.Client,
        *,
        clock: Callable[[], float] = time.time,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        if not re.fullmatch(r"[A-Z0-9]{10}", key_id):
            raise DeliveryError("CONFIG", "ASC_API_KEY_ID is not a 10-character key ID")
        if not re.fullmatch(r"[0-9a-f-]{36}", issuer_id):
            raise DeliveryError("CONFIG", "ASC_API_ISSUER_ID is not an issuer UUID")
        self.key_id, self.issuer_id, self.private_key = key_id, issuer_id, private_key
        self.client, self.clock, self.sleep = client, clock, sleep
        self._token: tuple[str, float] | None = None

    def _headers(self) -> dict[str, str]:
        now = self.clock()
        if self._token is None or self._token[1] - 60 < now:
            expires = int(now) + 15 * 60  # Apple allows at most 20 minutes
            token = es256_jwt(
                self.private_key,
                self.key_id,
                {"iss": self.issuer_id, "iat": int(now), "exp": expires, "aud": ASC_AUDIENCE},
            )
            self._token = (token, float(expires))
        return {"Authorization": f"Bearer {self._token[0]}"}

    def _get(self, path: str, params: Mapping[str, str] | None = None) -> dict[str, Any]:
        url = path if path.startswith("https://") else f"{ASC_API}{path}"
        if not url.startswith(ASC_API):
            raise DeliveryError("STORE", "App Store Connect returned a link outside its API")
        body = send(
            self.client,
            "GET",
            url,
            retry=True,
            service="App Store Connect",
            sleep=self.sleep,
            headers=self._headers(),
            params=dict(params or {}),
        ).json()
        if not isinstance(body, dict):
            raise DeliveryError("STORE", f"App Store Connect: {path} did not return an object")
        return body

    def _all(
        self, path: str, params: Mapping[str, str]
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        data: list[dict[str, Any]] = []
        included: list[dict[str, Any]] = []
        body = self._get(path, params)
        for _ in range(MAX_PAGES):
            data += body.get("data", [])
            included += body.get("included", [])
            following = (body.get("links") or {}).get("next")
            if not following:
                return data, included
            body = self._get(str(following))
        raise DeliveryError("STORE", f"App Store Connect: too many pages for {path}")

    def app_id(self, bundle_id: str = APPLICATION_ID) -> str:
        apps, _ = self._all("apps", {"filter[bundleId]": bundle_id, "limit": "10"})
        exact = [a for a in apps if (a.get("attributes") or {}).get("bundleId") == bundle_id]
        if len(exact) != 1:
            raise DeliveryError(
                "CONFIG", f"App Store Connect has {len(exact)} apps with bundle id {bundle_id}"
            )
        return str(exact[0]["id"])

    def builds(self, app_id: str, **filters: str) -> list[AscBuild]:
        params = {"filter[app]": app_id, "include": "preReleaseVersion", "limit": "200"}
        params.update({f"filter[{key}]": value for key, value in filters.items()})
        data, included = self._all("builds", params)
        versions = {
            item["id"]: (item.get("attributes") or {}).get("version")
            for item in included
            if item.get("type") == "preReleaseVersions"
        }
        builds = []
        for item in data:
            attributes = item.get("attributes") or {}
            relation = ((item.get("relationships") or {}).get("preReleaseVersion") or {}).get(
                "data"
            )
            builds.append(
                AscBuild(
                    id=str(item["id"]),
                    number=str(attributes.get("version")),
                    version=versions.get(relation.get("id"))
                    if isinstance(relation, dict)
                    else None,
                    processing_state=str(attributes.get("processingState")),
                    expired=bool(attributes.get("expired")),
                    uses_non_exempt_encryption=attributes.get("usesNonExemptEncryption"),
                    uploaded=attributes.get("uploadedDate"),
                )
            )
        return builds

    def known_builds(self, app_id: str, version: str, build: int) -> list[AscBuild]:
        """Every build of ``version`` and every build numbered ``build`` (any version)."""
        found = {b.id: b for b in self.builds(app_id, **{"preReleaseVersion.version": version})}
        found.update({b.id: b for b in self.builds(app_id, version=str(build))})
        return list(found.values())

    def internal_group(self, app_id: str, name: str) -> tuple[str, bool]:
        groups, _ = self._all("betaGroups", {"filter[app]": app_id, "filter[name]": name})
        exact = [g for g in groups if (g.get("attributes") or {}).get("name") == name]
        if len(exact) != 1:
            raise DeliveryError(
                "CONFIG", f"TESTFLIGHT_INTERNAL_GROUP={name!r}: {len(exact)} groups with this name"
            )
        attributes = exact[0].get("attributes") or {}
        if attributes.get("isInternalGroup") is not True:
            raise DeliveryError(
                "CONFIG", f"TESTFLIGHT_INTERNAL_GROUP={name!r} is not an internal TestFlight group"
            )
        return str(exact[0]["id"]), attributes.get("hasAccessToAllBuilds") is True

    def group_has_build(self, group_id: str, build_id: str) -> bool:
        linked, _ = self._all(f"betaGroups/{group_id}/relationships/builds", {"limit": "200"})
        return any(str(item.get("id")) == build_id for item in linked)

    def add_build_to_group(self, group_id: str, build_id: str) -> None:
        send(
            self.client,
            "POST",
            f"{ASC_API}betaGroups/{group_id}/relationships/builds",
            retry=False,
            service="App Store Connect",
            expect=(204,),
            sleep=self.sleep,
            headers=self._headers(),
            json={"data": [{"type": "builds", "id": build_id}]},
        )


def decide_ios(
    builds: Sequence[AscBuild],
    *,
    version: str,
    build: int,
    source_sha: str,
    records: Sequence[Record],
    artifact_sha256: str | None = None,
) -> Decision:
    """Whether ``version (build)`` may be uploaded, was uploaded already, or is a conflict.

    App Store Connect exposes no file hash, so an existing build counts as this workflow's only
    by a record of a run that started its upload from the same source commit (and, right before
    an upload, from this run's exact ``.ipa``), kept no later than App Store Connect says the
    build was uploaded: a build uploaded before the record cannot be that run's. A build someone
    else uploads with the same number after a failed run is indistinguishable (residual risk,
    docs/release/ci-delivery.md §7).
    """
    higher = sorted(
        int(b.number)
        for b in builds
        if b.version == version and b.number.isdigit() and int(b.number) > build
    )
    if higher:
        raise DeliveryError(
            "CONFLICT",
            f"App Store Connect already has {version} ({higher[-1]}), higher than ({build}): "
            "raise CURRENT_PROJECT_VERSION in a separate reviewed PR",
        )
    same = [b for b in builds if b.number == str(build)]
    if not same:
        return Decision("UPLOAD", f"{version} ({build}) is not in App Store Connect yet")
    if any(b.version != version for b in same):
        raise DeliveryError(
            "CONFLICT",
            f"build number {build} is already used in App Store Connect by version(s) "
            f"{sorted({str(b.version) for b in same})}",
        )
    uploaded = [_moment(b.uploaded) for b in same]
    for record in records:
        kept = _moment(record.created_at)
        if (
            record.platform == "ios"
            and record.version == version
            and record.build == build
            and record.source_sha == source_sha
            and (artifact_sha256 is None or record.artifact_sha256 == artifact_sha256)
            and kept is not None
            and all(moment is not None and moment >= kept for moment in uploaded)
        ):
            return Decision(
                "ALREADY_UPLOADED",
                f"{version} ({build}) is in App Store Connect; run {record.run_id} started its "
                f"upload from {source_sha} (.ipa SHA-256 {record.artifact_sha256}; App Store "
                "Connect exposes no file hash, so identity rests on that record)",
                delivered_sha256=record.artifact_sha256,
                record_run_id=record.run_id,
            )
    raise DeliveryError(
        "CONFLICT",
        f"{version} ({build}) is already in App Store Connect and no run of this workflow "
        f"uploaded it from {source_sha}"
        + (f" as .ipa {artifact_sha256}" if artifact_sha256 else "")
        + ": the build number is consumed and is never uploaded again. Raise "
        "CURRENT_PROJECT_VERSION in a separate reviewed PR",
    )


def _moment(value: str | None) -> dt.datetime | None:
    """An ISO 8601 time with an offset (GitHub, App Store Connect); anything else is None."""
    if not value:
        return None
    try:
        moment = dt.datetime.fromisoformat(value)
    except ValueError:
        return None
    return moment if moment.tzinfo is not None else None


def asc_check(
    asc: AppStoreConnect,
    *,
    gate: Mapping[str, Any],
    records: Sequence[Record],
    artifact_sha256: str | None = None,
) -> Decision:
    version, build = str(gate["ios"]["version"]), int(gate["ios"]["build"])
    app = asc.app_id()
    return decide_ios(
        asc.known_builds(app, version, build),
        version=version,
        build=build,
        source_sha=str(gate["sourceSha"]),
        records=records,
        artifact_sha256=artifact_sha256,
    )


def testflight_distribute(
    asc: AppStoreConnect,
    *,
    gate: Mapping[str, Any],
    group: str,
    timeout_seconds: float,
    poll_seconds: float = 60.0,
) -> dict[str, Any]:
    """Wait until App Store Connect has processed the build, then give it to ``group``."""
    version, number = str(gate["ios"]["version"]), str(gate["ios"]["build"])
    app = asc.app_id()
    group_id, all_builds = asc.internal_group(app, group)
    deadline = asc.clock() + timeout_seconds
    while True:
        found = [b for b in asc.builds(app, version=number) if b.version == version]
        if len(found) > 1:
            raise DeliveryError(
                "MISMATCH", f"App Store Connect lists {len(found)} builds {version} ({number})"
            )
        if found and found[0].processing_state in ("FAILED", "INVALID"):
            raise DeliveryError(
                "STORE",
                f"App Store Connect processing of {version} ({number}) ended "
                f"{found[0].processing_state}: read Apple's e-mail; the number is consumed",
            )
        if found and found[0].processing_state == "VALID":
            build = found[0]
            break
        if asc.clock() >= deadline:
            state = found[0].processing_state if found else "not listed yet"
            raise DeliveryError(
                "TIMEOUT",
                f"{version} ({number}) is still {state} after {int(timeout_seconds // 60)} "
                "minutes: re-run the failed jobs later (nothing is uploaded again)",
            )
        asc.sleep(poll_seconds)
    if build.expired:
        raise DeliveryError("STORE", f"{version} ({number}) is expired in TestFlight")
    if build.uses_non_exempt_encryption is not False:
        raise DeliveryError(
            "STORE",
            f"{version} ({number}): export compliance is {build.uses_non_exempt_encryption!r}, "
            "expected false (ITSAppUsesNonExemptEncryption)",
        )
    if all_builds:
        assignment = "the group has access to all builds"
    elif asc.group_has_build(group_id, build.id):
        assignment = "already in the group"
    else:
        asc.add_build_to_group(group_id, build.id)
        if not asc.group_has_build(group_id, build.id):
            raise DeliveryError("MISMATCH", f"the build is not in {group!r} after adding it")
        assignment = "added to the group"
    return {
        "status": "DELIVERED",
        "detail": f"{version} ({number}) processed (VALID) and available to internal group "
        f"{group!r}: {assignment}",
        "ascBuildId": build.id,
        "uploadedDate": build.uploaded,
    }


# --------------------------------------------------------------------------- artifact checks


def _dex(package: Path, prefix: str) -> bytes:
    pattern = re.compile(rf"{re.escape(prefix)}classes\d*\.dex")
    with zipfile.ZipFile(package) as archive:
        return b"".join(archive.read(n) for n in archive.namelist() if pattern.fullmatch(n))


def check_android_release(
    *,
    gate: Mapping[str, Any],
    apk: Path,
    apk_report: Mapping[str, Any],
    aab: Path,
    aab_report: Mapping[str, Any],
) -> dict[str, Any]:
    """The APK and the bundle are one release: same version, PASS, production OTA on."""
    problems: list[str] = []
    code, name = int(gate["android"]["versionCode"]), str(gate["android"]["versionName"])
    for label, path, report, file_key, prefix in (
        ("APK", apk, apk_report, "apk", ""),
        ("AAB", aab, aab_report, "bundle", "base/dex/"),
    ):
        package = report.get("package") or {}
        if report.get("result") != "PASS":
            problems.append(f"{label} report result is {report.get('result')!r}, not PASS")
        if report.get("purpose") != "release":
            problems.append(f"{label} report purpose is {report.get('purpose')!r}")
        if (package.get("versionCode"), package.get("versionName")) != (code, name):
            problems.append(
                f"{label} is {package.get('versionName')} ({package.get('versionCode')}), "
                f"the source commit sets {name} ({code})"
            )
        if package.get("applicationId") != APPLICATION_ID:
            problems.append(f"{label} applicationId is {package.get('applicationId')!r}")
        if (report.get(file_key) or {}).get("sha256") != file_sha256(path):
            problems.append(f"{label} report does not describe this file (SHA-256 differs)")
        if INTERNET_PERMISSION not in package.get("permissions", []):
            problems.append(f"{label} lacks {INTERNET_PERMISSION}: production OTA is off")
        dex = _dex(path, prefix)
        if not dex:
            problems.append(f"{label} has no dex code")
        missing = [m.decode() for m in ANDROID_OTA_REQUIRED if m not in dex]
        forbidden = sorted({m.decode() for m in ANDROID_OTA_FORBIDDEN.findall(dex)})
        if missing:
            problems.append(f"{label} dex lacks the production OTA values {missing}")
        if forbidden:
            problems.append(f"{label} dex contains staging values {forbidden}")
    if problems:
        raise DeliveryError("MISMATCH", "Android release check failed: " + "; ".join(problems))
    return {
        "versionName": name,
        "versionCode": code,
        "apkSha256": file_sha256(apk),
        "aabSha256": file_sha256(aab),
        "otaProduction": True,
        "sourceSha": gate["sourceSha"],
    }


def check_ios_release(
    *,
    gate: Mapping[str, Any],
    archive_report: Mapping[str, Any],
    ipa: Path,
    ipa_report: Mapping[str, Any],
) -> dict[str, Any]:
    """The archive and its App Store export are one release: same version, both PASS, OTA on."""
    problems: list[str] = []
    version, build = str(gate["ios"]["version"]), str(gate["ios"]["build"])
    for label, report in (("archive", archive_report), ("ipa", ipa_report)):
        info = report.get("info") or {}
        if report.get("result") != "PASS":
            problems.append(f"{label} report result is {report.get('result')!r}")
        shown = (info.get("CFBundleShortVersionString"), info.get("CFBundleVersion"))
        if shown != (version, build):
            problems.append(
                f"{label} is {shown[0]} ({shown[1]}), the source commit sets {version} ({build})"
            )
        if (report.get("ota") or {}).get("mode") != "on":
            problems.append(f"{label} was not verified with production OTA on")
        if not report.get("signing"):
            problems.append(f"{label} signature was not verified")
    identity = str((ipa_report.get("signing") or {}).get("identity", ""))
    if not identity.startswith("Apple Distribution"):
        problems.append(f"ipa is signed by {identity!r}, not Apple Distribution")
    if (ipa_report.get("package") or {}).get("sha256") != file_sha256(ipa):
        problems.append("ipa report does not describe this file (SHA-256 differs)")
    if problems:
        raise DeliveryError("MISMATCH", "iOS release check failed: " + "; ".join(problems))
    return {
        "version": version,
        "build": int(build),
        "ipaSha256": file_sha256(ipa),
        "otaProduction": True,
        "sourceSha": gate["sourceSha"],
    }


# --------------------------------------------------------------------------- summary

PLATFORM_JOBS = {
    "android": ("android-store-check", "android-build", "android-deliver"),
    "ios": ("ios-store-check", "ios-build", "ios-upload", "ios-testflight"),
}
#: ALREADY_UPLOADED (iOS store check) is not a delivery: ios-testflight still has to confirm
#: processing and the group, and turns it into ALREADY_DELIVERED
SUCCESS = {
    "deliver": frozenset({"DELIVERED", "ALREADY_DELIVERED"}),
    "verify": frozenset({"VERIFIED", "ALREADY_DELIVERED", "ALREADY_UPLOADED"}),
}


def _failed_jobs(needs: Mapping[str, Any], jobs: Sequence[str]) -> list[str]:
    return [
        f"{job} ({(needs.get(job) or {}).get('result')})"
        for job in jobs
        if (needs.get(job) or {}).get("result") in ("failure", "cancelled")
    ]


def summarize(
    *,
    platforms: Sequence[str],
    mode: str,
    needs: Mapping[str, Any],
    statuses: Mapping[str, Mapping[str, Any]],
    gate: Mapping[str, Any] | None,
) -> tuple[list[dict[str, Any]], bool]:
    """One row per platform. A platform succeeds only with a success status and no failed job."""
    rows: list[dict[str, Any]] = []
    gate_result = (needs.get("gate") or {}).get("result")
    for platform in PLATFORMS:
        row: dict[str, Any] = {"platform": platform}
        failed = _failed_jobs(needs, PLATFORM_JOBS[platform])
        status = statuses.get(platform)
        if platform not in platforms:
            row.update(status="NOT_SELECTED", detail="not selected for this run")
        elif gate_result != "success" or gate is None:
            row.update(status="BLOCKED", detail=f"the gate did not pass ({gate_result})")
        elif failed:
            row.update(status="FAILED", detail="failed: " + ", ".join(failed))
            if status:
                row["lastRecorded"] = status.get("status")
        elif status:
            row.update({k: v for k, v in status.items() if k != "platform"})
        else:
            others = [job for p, jobs in PLATFORM_JOBS.items() if p != platform for job in jobs]
            held = _failed_jobs(needs, others)
            row.update(
                status="NOT_DELIVERED",
                detail="held back: " + ", ".join(held) if held else "no stage reported a result",
            )
        if gate is not None:
            versions = gate.get(platform) or {}
            row.setdefault("sourceSha", gate.get("sourceSha"))
            row.setdefault(
                "version",
                f"{versions.get('versionName', versions.get('version'))} "
                f"({versions.get('versionCode', versions.get('build'))})",
            )
        rows.append(row)
    ok = gate_result == "success" and all(
        row["status"] in SUCCESS[mode] for row in rows if row["platform"] in platforms
    )
    return rows, ok


def summary_markdown(
    rows: Sequence[Mapping[str, Any]], gate: Mapping[str, Any] | None, ok: bool, mode: str
) -> str:
    lines = [f"## Store delivery ({mode}): {'OK' if ok else 'NOT OK'}", ""]
    if gate is not None:
        lines += [
            f"Source commit `{gate.get('sourceSha')}` ({gate.get('revisionKind')} "
            f"`{gate.get('revisionInput') or 'main'}`), workflow `{gate.get('workflowSha')}`",
            "",
        ]
    lines += [
        "| Platform | Status | Version | Artifact SHA-256 | Detail |",
        "|---|---|---|---|---|",
    ]
    for row in rows:
        detail = str(row.get("detail", "")).replace("|", "\\|")
        sha = row.get("deliveredSha256") or row.get("artifactSha256") or ""
        lines.append(
            f"| {row['platform']} | **{row['status']}** | {row.get('version', '')} | "
            f"`{sha}` | {detail} |"
        )
    if mode == "verify":
        lines += ["", "Verify mode: nothing was uploaded to a store."]
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------- CLI


def _github(environ: Mapping[str, str], client: httpx.Client) -> GitHubApi:
    values = require_env(environ, ["GITHUB_REPOSITORY", "GH_TOKEN"])
    return GitHubApi(values["GITHUB_REPOSITORY"], values["GH_TOKEN"], client)


def _play(environ: Mapping[str, str], client: httpx.Client) -> GooglePlay:
    values = require_env(environ, ["PLAY_SERVICE_ACCOUNT_JSON"])
    return GooglePlay(parse_service_account(values["PLAY_SERVICE_ACCOUNT_JSON"]), client)


def _asc(environ: Mapping[str, str], client: httpx.Client) -> AppStoreConnect:
    values = require_env(environ, ["ASC_API_KEY_ID", "ASC_API_ISSUER_ID", "ASC_API_PRIVATE_KEY"])
    return AppStoreConnect(
        values["ASC_API_KEY_ID"],
        values["ASC_API_ISSUER_ID"],
        load_asc_key(values["ASC_API_PRIVATE_KEY"]),
        client,
    )


def _status(platform: str, gate: Mapping[str, Any], status: str, **fields: Any) -> dict[str, Any]:
    return {
        "platform": platform,
        "status": status,
        "sourceSha": gate["sourceSha"],
        "mode": gate["mode"],
        "at": iso_now(),
        **fields,
    }


def _record_decision(
    platform: str,
    gate: Mapping[str, Any],
    decision: Decision,
    status_path: Path,
    environ: Mapping[str, str],
) -> None:
    print(f"{decision.kind}: {decision.detail}")
    set_outputs(
        {"decision": decision.kind, "delivered_sha256": decision.delivered_sha256 or ""}, environ
    )
    if decision.kind != "UPLOAD":
        write_json(
            status_path,
            _status(
                platform,
                gate,
                decision.kind,
                detail=decision.detail,
                deliveredSha256=decision.delivered_sha256,
                recordRunId=decision.record_run_id,
                tracks=decision.tracks,
            ),
        )


def parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0] if __doc__ else None)
    commands = parser.add_subparsers(dest="command", required=True)

    def command(name: str, help_text: str, *paths: str) -> argparse.ArgumentParser:
        sub = commands.add_parser(name, help=help_text)
        for option in paths:
            sub.add_argument(f"--{option}", type=Path, required=True)
        return sub

    gate = command("gate", "resolve the revision, check its CI, read the versions", "repo", "out")
    gate.add_argument("--revision", default="")
    gate.add_argument("--platforms", choices=sorted(PLATFORM_CHOICES), required=True)
    gate.add_argument("--mode", choices=MODES, required=True)

    env = commands.add_parser("require-env", help="fail naming every missing setting")
    env.add_argument("names", nargs="+")

    command("play-check", "read Google Play's state (changes nothing)", "gate", "status")
    asc = command("asc-check", "read App Store Connect's state", "gate", "status")
    asc.add_argument("--ipa", type=Path, help="right before the upload: this run's .ipa")
    command("play-deliver", "upload the bundle to the closed track", "gate", "aab", "status")
    command(
        "android-check",
        "the APK and the AAB are one OTA ON release",
        *("gate", "apk", "apk-report", "aab", "aab-report", "out"),
    )
    command(
        "ios-check",
        "the archive and the .ipa are one OTA ON release",
        *("gate", "archive-report", "ipa", "ipa-report", "out"),
    )
    verified = command("verified", "record a verify-mode result", "gate", "check", "status")
    verified.add_argument("--platform", choices=PLATFORMS, required=True)
    command("write-asc-key", "write ASC_API_PRIVATE_KEY for altool (mode 0600)", "out")
    record = command("record-name", "print a delivery record artifact name", "gate", "artifact")
    record.add_argument("--platform", choices=PLATFORMS, required=True)
    record.add_argument("--phase", choices=["delivered", "upload-started"], required=True)
    testflight = command(
        "testflight", "wait for processing, give it to the group", "gate", "status"
    )
    testflight.add_argument("--timeout-minutes", type=float, default=90.0)
    summary = command("summary", "the per-platform result of the run", "gate", "status-dir", "out")
    summary.add_argument("--platforms", choices=sorted(PLATFORM_CHOICES), required=True)
    summary.add_argument("--mode", choices=MODES, required=True)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None, environ: Mapping[str, str] | None = None) -> int:
    args = parse_args(argv)
    try:
        return run(args, os.environ if environ is None else environ)
    except DeliveryError as error:
        print(f"::error title=Delivery refused ({error.code})::{error.message}")
        return EXIT_REFUSED


def run(args: argparse.Namespace, environ: Mapping[str, str]) -> int:
    command = args.command
    if command == "require-env":
        require_env(environ, args.names)
        print(f"configuration present: {', '.join(args.names)}")
        return EXIT_OK
    if command == "summary":
        return _summary(args, environ)
    if command == "write-asc-key":
        # the same key the API calls use, so a key asc-check accepted cannot fail in altool
        key = require_env(environ, ["ASC_API_PRIVATE_KEY"])["ASC_API_PRIVATE_KEY"]
        load_asc_key(key)
        args.out.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        descriptor = os.open(args.out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="ascii") as handle:
            handle.write(normalize_asc_key(key))
        return EXIT_OK
    gate = {} if command == "gate" else read_json_object(args.gate)
    if command == "android-check":
        result = check_android_release(
            gate=gate,
            apk=args.apk,
            apk_report=read_json_object(args.apk_report),
            aab=args.aab,
            aab_report=read_json_object(args.aab_report),
        )
        write_json(args.out, result)
        print(json.dumps(result, indent=2))
        return EXIT_OK
    if command == "ios-check":
        result = check_ios_release(
            gate=gate,
            archive_report=read_json_object(args.archive_report),
            ipa=args.ipa,
            ipa_report=read_json_object(args.ipa_report),
        )
        write_json(args.out, result)
        print(json.dumps(result, indent=2))
        return EXIT_OK
    if command == "verified":
        check = read_json_object(args.check)
        key = "aabSha256" if args.platform == "android" else "ipaSha256"
        write_json(
            args.status,
            _status(
                args.platform,
                gate,
                "VERIFIED",
                detail="built, signed and verified; verify mode uploads nothing",
                artifactSha256=check.get(key),
                check=check,
            ),
        )
        return EXIT_OK
    if command == "record-name":
        versions = gate[args.platform]
        name = Record(
            platform=args.platform,
            version=str(versions.get("versionName", versions.get("version"))),
            build=int(versions.get("versionCode", versions.get("build"))),
            phase=args.phase,
            source_sha=str(gate["sourceSha"]),
            artifact_sha256=file_sha256(args.artifact),
        ).name
        if parse_record_name(name) is None:
            raise DeliveryError("MISMATCH", f"record name {name!r} does not parse back")
        print(name)
        return EXIT_OK
    with httpx.Client(timeout=120.0, follow_redirects=False) as client:
        if command == "gate":
            result = run_gate(
                repo=args.repo,
                revision=args.revision,
                platforms=args.platforms,
                mode=args.mode,
                environ=environ,
                api=_github(environ, client),
            )
            write_json(args.out, result)
            set_outputs(
                {
                    "sha": result["sourceSha"],
                    "mode": result["mode"],
                    "android": str("android" in result["platforms"]).lower(),
                    "ios": str("ios" in result["platforms"]).lower(),
                },
                environ,
            )
            print(json.dumps(result, indent=2))
        elif command == "play-check":
            track = require_env(environ, ["PLAY_TRACK"])["PLAY_TRACK"]
            play = _play(environ, client)
            records = delivery_records(_github(environ, client))
            decision = play_check(play, gate=gate, track=track, records=records)
            _record_decision("android", gate, decision, args.status, environ)
        elif command == "asc-check":
            asc = _asc(environ, client)
            records = delivery_records(_github(environ, client))
            sha = file_sha256(args.ipa) if args.ipa else None
            decision = asc_check(asc, gate=gate, records=records, artifact_sha256=sha)
            _record_decision("ios", gate, decision, args.status, environ)
        elif command == "play-deliver":
            track = require_env(environ, ["PLAY_TRACK"])["PLAY_TRACK"]
            review = environ.get("PLAY_CHANGES_NOT_SENT_FOR_REVIEW", "").strip().lower()
            if review not in ("", "true", "false"):
                raise DeliveryError("CONFIG", "PLAY_CHANGES_NOT_SENT_FOR_REVIEW must be true/false")
            result = play_deliver(
                _play(environ, client),
                gate=gate,
                aab=args.aab,
                track=track,
                release_status=environ.get("PLAY_RELEASE_STATUS", "").strip() or "completed",
                changes_not_sent_for_review=review == "true",
            )
            write_json(args.status, _status("android", gate, **result))
            print(f"{result['status']}: {result['detail']}")
        elif command == "testflight":
            group = require_env(environ, ["TESTFLIGHT_INTERNAL_GROUP"])["TESTFLIGHT_INTERNAL_GROUP"]
            result = testflight_distribute(
                _asc(environ, client),
                gate=gate,
                group=group,
                timeout_seconds=args.timeout_minutes * 60,
            )
            # a build uploaded by an earlier run is reported as such, never as this run's upload
            if environ.get("IOS_DECISION", "").strip() == "ALREADY_UPLOADED":
                result["status"] = "ALREADY_DELIVERED"
            result["deliveredSha256"] = environ.get("IOS_DELIVERED_SHA256", "").strip() or None
            write_json(args.status, _status("ios", gate, **result))
            print(f"{result['status']}: {result['detail']}")
        else:
            raise AssertionError(command)
    return EXIT_OK


def _summary(args: argparse.Namespace, environ: Mapping[str, str]) -> int:
    try:
        needs = json.loads(environ.get("NEEDS", "{}"))
    except ValueError as error:
        raise DeliveryError("MISMATCH", "NEEDS is not JSON") from error
    gate = read_json_object(args.gate) if args.gate.is_file() else None
    statuses: dict[str, dict[str, Any]] = {}
    paths = sorted(args.status_dir.rglob("*.json")) if args.status_dir.is_dir() else []
    for path in paths:
        status = read_json_object(path)
        platform = status.get("platform")
        if (
            platform in PLATFORMS
            and gate is not None
            and status.get("sourceSha") == gate.get("sourceSha")
        ):
            statuses[str(platform)] = status
    platforms = list(gate["platforms"]) if gate else list(PLATFORM_CHOICES[args.platforms])
    mode = str(gate["mode"]) if gate else args.mode
    rows, ok = summarize(platforms=platforms, mode=mode, needs=needs, statuses=statuses, gate=gate)
    markdown = summary_markdown(rows, gate, ok, mode)
    print(markdown)
    write_json(
        args.out,
        {"schema": "talentforge-delivery-report/1", "ok": ok, "gate": gate, "platforms": rows},
    )
    target = environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with Path(target).open("a", encoding="utf-8") as handle:
            handle.write(markdown)
    return EXIT_OK if ok else EXIT_REFUSED


if __name__ == "__main__":
    sys.exit(main())
