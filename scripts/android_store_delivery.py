#!/usr/bin/env python3
"""RuStore/AppGallery production delivery. Official contracts and recovery: design/runbook.

No store mutation is retried automatically. A durable intent is required before each
write; a missing receipt is reconciled against store identity or remains UNKNOWN.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import ssl
import sys
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

import httpx
import store_delivery as core
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

APPLICATION_ID = core.APPLICATION_ID
HUAWEI_API = "https://connect-api.cloud.huawei.com/api/"
HUAWEI_AUDIENCE = "https://oauth-login.cloud.huawei.com/oauth2/v3/token"
RUSTORE_API = "https://public-api-m.rustore.ru/public/"
STORES = ("rustore", "appgallery")
HASH = re.compile(r"[0-9a-f]{64}")
MANIFEST_FIELDS = frozenset(
    {
        "schema",
        "sourceSha",
        "workflowSha",
        "buildRunId",
        "applicationId",
        "versionCode",
        "versionName",
        "apkSha256",
        "certificateSha256",
        "apkSize",
        "otaProduction",
        "stores",
        "appgalleryAppId",
        "whatsNew",
        "createdAt",
    }
)


class DeliveryError(core.DeliveryError):
    CODES = core.DeliveryError.CODES | {"CONTRACT", "UNKNOWN"}

    def __init__(self, code: str, message: str) -> None:
        super().__init__(code, f"{code}: {message}")


def obj(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise DeliveryError("CONTRACT", "expected JSON object")
    return value


def integer(value: Any, *, minimum: int = 0) -> int:
    if type(value) is not int or value < minimum:
        raise DeliveryError("CONTRACT", "expected integer in range")
    return value


def string(value: Any) -> str:
    if not isinstance(value, str) or not value:
        raise DeliveryError("CONTRACT", "expected nonempty string")
    return value


def items(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        raise DeliveryError("CONTRACT", "expected array")
    return [obj(item) for item in value]


def canonical(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


@dataclass(frozen=True)
class Release:
    value: dict[str, Any]

    @classmethod
    def parse(cls, value: Any) -> Release:
        value = obj(value)
        if set(value) != MANIFEST_FIELDS or value.get("schema") != "talentforge-android-public/1":
            raise DeliveryError("CONTRACT", "unknown/missing manifest fields or schema")
        for k in ("sourceSha", "workflowSha"):
            if not isinstance(value[k], str) or not core.FULL_SHA.fullmatch(value[k]):
                raise DeliveryError("CONTRACT", "invalid source/tooling SHA")
        for k in ("apkSha256", "certificateSha256"):
            if not isinstance(value[k], str) or not HASH.fullmatch(value[k]):
                raise DeliveryError("CONTRACT", "invalid digest")
        for k in ("versionCode", "apkSize", "buildRunId"):
            integer(value[k], minimum=1)
        if value["otaProduction"] is not True or value["applicationId"] != APPLICATION_ID:
            raise DeliveryError("MISMATCH", "production OTA/package mismatch")
        if not core.VERSION.fullmatch(string(value["versionName"])):
            raise DeliveryError("CONTRACT", "invalid versionName")
        selected = value["stores"]
        if (
            not isinstance(selected, list)
            or not selected
            or any(x not in STORES for x in selected)
            or len(set(selected)) != len(selected)
        ):
            raise DeliveryError("CONTRACT", "invalid target stores")
        if "appgallery" in selected and not re.fullmatch(
            r"[0-9]+", string(value["appgalleryAppId"])
        ):
            raise DeliveryError("CONTRACT", "invalid AppGallery app ID")
        for k in ("whatsNew", "createdAt"):
            string(value[k])
        if len(value["whatsNew"]) > 5000:
            raise DeliveryError("CONTRACT", "release notes too long")
        return cls(dict(value))

    @property
    def digest(self) -> str:
        return hashlib.sha256(canonical(self.value)).hexdigest()

    @property
    def code(self) -> int:
        return self.value["versionCode"]

    @property
    def apk_hash(self) -> str:
        return self.value["apkSha256"]

    def verify_apk(self, path: Path) -> None:
        if path.stat().st_size != self.value["apkSize"] or core.file_sha256(path) != self.apk_hash:
            raise DeliveryError("MISMATCH", "APK differs from approved bytes")


def delivery_client() -> httpx.Client:
    """Keep the additional RuStore CA out of GitHub/Huawei and system trust."""
    context = ssl.create_default_context()
    context.load_verify_locations(cafile=Path(__file__).parent / "certs" / "rustore-ca.pem")
    return httpx.Client(
        timeout=60,
        mounts={"https://public-api-m.rustore.ru": httpx.HTTPTransport(verify=context)},
    )


def request(
    client: httpx.Client, method: str, url: str, *, accept_redirect: bool = False, **kwargs: Any
) -> httpx.Response:
    """Bounded retries for reads only. Never report bodies, headers or signed URLs."""
    for attempt in range(3 if method == "GET" else 1):
        try:
            response = client.request(method, url, follow_redirects=False, **kwargs)
        except httpx.HTTPError as error:
            if method == "GET" and attempt < 2:
                continue
            raise DeliveryError(
                "STORE" if method == "GET" else "UNKNOWN",
                f"{method} transport failure ({type(error).__name__})",
            ) from None
        if 200 <= response.status_code < 300 or (
            accept_redirect and response.status_code in (301, 302, 303, 307, 308)
        ):
            return response
        if method == "GET" and response.status_code in core.RETRY_STATUSES and attempt < 2:
            continue
        raise DeliveryError(
            "STORE" if method == "GET" else "UNKNOWN", f"{method} HTTP {response.status_code}"
        )
    raise AssertionError("unreachable")


def json_body(response: httpx.Response) -> dict[str, Any]:
    try:
        return obj(response.json())
    except ValueError:
        raise DeliveryError("CONTRACT", "invalid JSON response") from None


def huawei_jwt(raw: str, *, now: int | None = None) -> str:
    try:
        account = obj(json.loads(raw))
        # The guide shows an empty example, not a normative emptiness constraint.
        # API authorization is established by the read-only store audit, not this metadata.
        if not isinstance(account.get("project_id"), str):
            raise DeliveryError("CONFIG", "Huawei project_id must be a string")
        if account.get("token_uri") != HUAWEI_AUDIENCE:
            raise DeliveryError("CONFIG", "Huawei token_uri differs from the pinned audience")
        kid, subject = string(account.get("key_id")), string(account.get("sub_account"))
        key = serialization.load_pem_private_key(string(account.get("private_key")).encode(), None)
        if not isinstance(key, rsa.RSAPrivateKey) or key.key_size < 2048:
            raise DeliveryError("CONFIG", "Huawei requires RSA >= 2048")
    except (ValueError, TypeError):
        raise DeliveryError("CONFIG", "invalid AppGallery service account") from None
    issued = int(time.time()) if now is None else now
    data = b".".join(
        core.b64url(canonical(v)).encode()
        for v in (
            {"alg": "PS256", "kid": kid, "typ": "JWT"},
            {"iss": subject, "aud": HUAWEI_AUDIENCE, "iat": issued, "exp": issued + 3600},
        )
    )
    sig = key.sign(
        data, padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=32), hashes.SHA256()
    )
    return data.decode() + "." + core.b64url(sig)


def parse_service_url(raw: str) -> httpx.URL:
    try:
        return httpx.URL(raw)
    except httpx.InvalidURL:
        raise DeliveryError("CONFIG", "malformed service URL") from None


def huawei_upload_url(raw: str) -> str:
    url = parse_service_url(raw)
    if (
        url.scheme != "https"
        or url.username
        or url.password
        or url.port not in (None, 443)
        or not any(
            url.host.endswith("." + host)
            for host in ("huaweicloud.com", "myhuaweicloud.com", "hicloud.com", "dbankcloud.com")
        )
    ):
        raise DeliveryError("CONFIG", "untrusted Huawei file-server URL")
    return raw


class Journal(Protocol):
    def get(self, name: str) -> dict[str, Any] | None: ...
    def put_once(self, name: str, value: dict[str, Any]) -> None: ...
    def entries(self, prefix: str) -> list[tuple[str, dict[str, Any]]]: ...


class MemoryJournal:
    """Offline test journal, never used for real store delivery."""

    def __init__(self) -> None:
        self.records: dict[str, dict[str, Any]] = {}

    def get(self, name: str) -> dict[str, Any] | None:
        return self.records.get(name)

    def put_once(self, name: str, value: dict[str, Any]) -> None:
        old = self.get(name)
        if old is not None and old != value:
            raise DeliveryError("MISMATCH", "journal checkpoint differs")
        self.records[name] = dict(value)

    def entries(self, prefix: str) -> list[tuple[str, dict[str, Any]]]:
        return sorted(
            (name, value) for name, value in self.records.items() if name.startswith(prefix + "-")
        )


def write_once(
    journal: Journal, step: str, intent: dict[str, Any], write: Callable[[], dict[str, Any]]
) -> dict[str, Any]:
    started, done = journal.get(step + "-intent"), journal.get(step + "-done")
    if started is not None and started != intent:
        raise DeliveryError("MISMATCH", "write intent differs from approved release")
    if done is not None:
        if started is None:
            raise DeliveryError("MISMATCH", "receipt without intent")
        return done
    if started is not None:
        raise DeliveryError("UNKNOWN", "write may already be accepted; reconciliation required")
    journal.put_once(step + "-intent", intent)
    result = write()
    journal.put_once(step + "-done", result)
    return result


class AppGallery:
    def __init__(self, client: httpx.Client, app_id: str, jwt: str) -> None:
        if not re.fullmatch(r"[0-9]+", app_id):
            raise DeliveryError("CONFIG", "invalid AppGallery app ID")
        self.client, self.app_id, self.headers = client, app_id, {"Authorization": "Bearer " + jwt}

    def call(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        params = {"appId": self.app_id, **kwargs.pop("params", {})}
        result = json_body(
            request(
                self.client,
                method,
                HUAWEI_API + "publish/v2/" + path,
                headers=self.headers,
                params=params,
                **kwargs,
            )
        )
        ret = obj(result.get("ret"))
        if integer(ret.get("code")) != 0:
            raise DeliveryError("STORE", "Huawei API refused request")
        return result

    def packages(self) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        for _ in range(100):
            page = self.call(
                "GET", "package-list", params={"fromRecCount": len(result) + 1, "maxReqCount": 100}
            )
            batch, count, total = (
                items(page.get("pkgList")),
                integer(page.get("retCount")),
                integer(page.get("totalCount")),
            )
            if count != len(batch) or len(result) + count > total:
                raise DeliveryError("CONTRACT", "Huawei pagination disagrees")
            for p in batch:
                integer(p.get("versionCode"), minimum=1)
                string(p.get("pkgVersion"))
                integer(p.get("packageSize"), minimum=1)
            result.extend(batch)
            if len(result) == total:
                return result
            if not batch:
                break
        raise DeliveryError("CONTRACT", "Huawei pagination incomplete")

    def info(self) -> dict[str, Any]:
        return obj(self.call("GET", "app-info", params={"releaseType": 1}).get("appInfo"))


def huawei_observation(
    release: Release, state: dict[str, Any], packages: list[dict[str, Any]]
) -> dict[str, Any]:
    raw = integer(state.get("releaseState"))
    if raw not in range(14):
        raise DeliveryError("CONTRACT", "unknown Huawei releaseState")
    matches = [p for p in packages if integer(p.get("versionCode"), minimum=1) == release.code]
    if len(matches) > 1:
        raise DeliveryError("CONFLICT", "multiple packages use approved code")
    if not matches:
        return {"status": "ABSENT", "rawState": raw}
    package = matches[0]
    if (
        package.get("shaCode") != release.apk_hash
        or package.get("packageSize") != release.value["apkSize"]
    ):
        raise DeliveryError("MISMATCH", "Huawei package hash/size does not prove approved APK")
    pid = string(package.get("pkgVersion"))
    shelf = state.get("onShelfVersionCode")
    if shelf is not None:
        integer(shelf, minimum=1)
    if shelf == release.code:
        if raw in {1, 2, 6, 9, 10}:
            raise DeliveryError("CONFLICT", "Huawei state does not prove public availability")
        if state.get("onShelfVersionId") != pid:
            raise DeliveryError("MISMATCH", "Huawei published package ID differs")
        return {"status": "PUBLISHED", "rawState": raw, "packageId": pid}
    if state.get("versionCode") != release.code or state.get("versionId") != pid:
        raise DeliveryError("CONFLICT", "Huawei candidate is not the approved package")
    status = {
        4: "IN_REVIEW",
        5: "IN_REVIEW",
        3: "PROCESSING",
        7: "READY_TO_SUBMIT",
        1: "REJECTED",
        8: "REJECTED",
        12: "IN_REVIEW",
        13: "REJECTED",
    }.get(raw)
    if status is None:
        raise DeliveryError("CONFLICT", "Huawei state does not allow approved update")
    return {"status": status, "rawState": raw, "packageId": pid}


def advance_appgallery(
    api: AppGallery, release: Release, apk: Path, journal: Journal, *, now: int | None = None
) -> dict[str, Any]:
    fixed_now = now
    now = int(time.time()) if now is None else now
    packages, state = api.packages(), api.info()
    observed = huawei_observation(release, state, packages)
    if observed["status"] == "ABSENT":
        if any(integer(p.get("versionCode"), minimum=1) >= release.code for p in packages):
            raise DeliveryError("CONFLICT", "Huawei has a higher consumed code")
        # Do not replace another draft or a review already in progress. The old released
        # version is the only state from which this workflow starts an update.
        if integer(state.get("releaseState")) not in (0, 7):
            raise DeliveryError("CONFLICT", "Huawei has another pending release")
        if state.get("versionCode") != state.get("onShelfVersionCode"):
            raise DeliveryError("CONFLICT", "Huawei has a foreign candidate")
        attached = journal.get("appgallery-attach-intent")
        if attached is not None:
            failure = journal.get("appgallery-attach-error")
            if failure is not None:
                status = string(failure.get("status"))
                if set(failure) != {"status", "detail"} or status not in DeliveryError.CODES:
                    raise DeliveryError("CONTRACT", "invalid Huawei attachment error receipt")
                return {"status": status, "detail": string(failure["detail"])}
            # Parsing is healthy only after a confirmed attach response. An intent alone
            # cannot distinguish a lost response, rejection or interrupted error checkpoint.
            if journal.get("appgallery-attach-done") is None:
                raise DeliveryError("UNKNOWN", "Huawei attachment acceptance is uncertain")
            return {"status": "PROCESSING", "detail": "Awaiting package-list reconciliation"}
        uploaded = journal.get("appgallery-upload-done")
        if uploaded is None:
            if journal.get("appgallery-upload-intent") is not None:
                raise DeliveryError("UNKNOWN", "Huawei file upload result is uncertain")
            release.verify_apk(apk)
            info = obj(
                api.call(
                    "GET",
                    "upload-url/for-obs",
                    params={
                        "fileName": apk.name,
                        "sha256": release.apk_hash,
                        "contentLength": release.value["apkSize"],
                        "releaseType": 1,
                        "chineseMainlandFlag": 0,
                    },
                ).get("urlInfo")
            )
            if info.get("method") != "PUT":
                raise DeliveryError("CONTRACT", "Huawei upload must use PUT")
            url = huawei_upload_url(string(info.get("url")))
            headers = obj(info.get("headers"))
            if any(not isinstance(k, str) or not isinstance(v, str) for k, v in headers.items()):
                raise DeliveryError("CONTRACT", "Huawei upload headers have wrong wire types")
            normalized = {k.lower(): v for k, v in headers.items()}
            if len(normalized) != len(headers):
                raise DeliveryError("CONTRACT", "Huawei upload has duplicate header names")
            authority = httpx.URL(url).host.lower()
            if normalized.get("host", "").lower() not in {authority, authority + ":443"}:
                raise DeliveryError("CONFIG", "Huawei upload Host differs from approved authority")
            headers = normalized
            if headers.get("x-amz-content-sha256") != release.apk_hash:
                raise DeliveryError("MISMATCH", "Huawei upload does not bind APK hash")
            object_id = string(info.get("objectId"))

            def upload() -> dict[str, Any]:
                with apk.open("rb") as stream:
                    request(api.client, "PUT", url, headers=headers, content=stream, timeout=600)
                finished = int(time.time()) if fixed_now is None else now
                return {"objectId": object_id, "at": finished}

            uploaded = write_once(
                journal,
                "appgallery-upload",
                {"manifest": release.digest, "objectId": object_id},
                upload,
            )
        object_id = string(uploaded.get("objectId"))

        def attach() -> dict[str, Any]:
            try:
                result = api.call(
                    "PUT",
                    "app-file-info",
                    params={"releaseType": 1},
                    json={
                        "fileType": 5,
                        "files": [{"fileName": apk.name, "fileDestUrl": object_id}],
                    },
                )
                ids = result.get("pkgVersion")
                if not isinstance(ids, list) or any(not isinstance(i, str) for i in ids):
                    raise DeliveryError("CONTRACT", "Huawei attachment package IDs malformed")
            except core.DeliveryError as error:
                journal.put_once(
                    "appgallery-attach-error", {"status": error.code, "detail": error.message}
                )
                raise
            return {"packageIds": ids, "at": now}

        write_once(
            journal,
            "appgallery-attach",
            {"manifest": release.digest, "objectId": object_id},
            attach,
        )
        return {"status": "PROCESSING", "detail": "APK attached; parsing before submission"}
    attached = journal.get("appgallery-attach-intent")
    if attached is not None and journal.get("appgallery-attach-done") is None:
        journal.put_once(
            "appgallery-attach-done", {"packageIds": [observed["packageId"]], "reconciled": True}
        )
    if observed["status"] in ("PUBLISHED", "IN_REVIEW", "REJECTED", "PROCESSING"):
        return observed
    uploaded = journal.get("appgallery-upload-done")
    if uploaded is not None and now - integer(uploaded.get("at")) < 120:
        return {
            **observed,
            "status": "PROCESSING",
            "detail": "Huawei minimum two-minute parsing interval",
        }
    # app-submit publishes the current candidate, not a package argument. Re-read and
    # compare candidate ID immediately before submitting; never send a console edit.
    fresh = huawei_observation(release, api.info(), api.packages())
    if fresh["status"] != "READY_TO_SUBMIT":
        return fresh
    intent = {"manifest": release.digest, "packageId": fresh["packageId"]}
    if (
        journal.get("appgallery-submit-intent") is not None
        and journal.get("appgallery-submit-done") is None
    ):
        raise DeliveryError("UNKNOWN", "Huawei submission result is uncertain")
    write_once(
        journal,
        "appgallery-submit",
        intent,
        lambda: api.call("POST", "app-submit", params={"releaseType": 1}, json={}) and {"at": now},
    )
    return huawei_observation(release, api.info(), api.packages())


RUSTORE_STATES = frozenset(
    {
        "ACTIVE",
        "PARTIAL_ACTIVE",
        "READY_FOR_PUBLICATION",
        "PREVIOUS_ACTIVE",
        "ARCHIVED",
        "REJECTED_BY_MODERATOR",
        "TAKEN_FOR_MODERATION",
        "MODERATION",
        "AUTO_CHECK",
        "AUTO_CHECK_FAILED",
        "DRAFT",
        "DELETED_DRAFT",
        "REJECTED_BY_SECURITY",
    }
)


class RuStore:
    def __init__(self, client: httpx.Client, token: str) -> None:
        self.client, self.headers = client, {"Public-Token": token}

    @classmethod
    def authenticate(cls, client: httpx.Client, key_id: str, key_base64: str) -> RuStore:
        try:
            key = serialization.load_der_private_key(
                base64.b64decode(key_base64, validate=True), None
            )
        except (ValueError, TypeError):
            raise DeliveryError("CONFIG", "invalid RuStore RSA key") from None
        if not isinstance(key, rsa.RSAPrivateKey) or key.key_size < 2048:
            raise DeliveryError("CONFIG", "RuStore requires RSA >= 2048")
        stamp = core.iso_now()
        signature = base64.b64encode(
            key.sign((key_id + stamp).encode(), padding.PKCS1v15(), hashes.SHA512())
        ).decode()
        result = json_body(
            request(
                client,
                "POST",
                RUSTORE_API + "auth/",
                json={"keyId": key_id, "timestamp": stamp, "signature": signature},
            )
        )
        if result.get("code") != "OK":
            raise DeliveryError("CONFIG", "RuStore authentication refused")
        return cls(client, string(obj(result.get("body")).get("jwe")))

    def call(
        self, method: str, suffix: str = "", *, version: int = 1, **kwargs: Any
    ) -> dict[str, Any]:
        result = json_body(
            request(
                self.client,
                method,
                f"{RUSTORE_API}v{version}/application/{APPLICATION_ID}/version{suffix}",
                headers=self.headers,
                **kwargs,
            )
        )
        if result.get("code") != "OK":
            raise DeliveryError("STORE", "RuStore API refused request")
        return result

    def versions(self) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        for number in range(100):
            page = obj(
                self.call(
                    "GET", params={"page": number, "size": 100, "filterTestingType": "ALL"}
                ).get("body")
            )
            if integer(page.get("pageNumber")) != number:
                raise DeliveryError("CONTRACT", "RuStore page number differs")
            batch = items(page.get("content"))
            for v in batch:
                integer(v.get("versionCode"), minimum=1)
                integer(v.get("versionId"), minimum=1)
                if v.get("versionStatus") not in RUSTORE_STATES:
                    raise DeliveryError("CONTRACT", "unknown RuStore version status")
            result.extend(batch)
            if number + 1 >= integer(page.get("totalPages")):
                if len(result) != integer(page.get("totalElements")):
                    raise DeliveryError("CONTRACT", "RuStore pagination incomplete")
                return result
        raise DeliveryError("CONTRACT", "RuStore pagination limit")

    def detail(self, version_id: int) -> dict[str, Any]:
        return obj(self.call("GET", f"/{version_id}", version=2).get("body"))


def rustore_package(release: Release, detail: dict[str, Any]) -> dict[str, Any]:
    packages = items(detail.get("packages"))
    if len(packages) != 1:
        raise DeliveryError("CONFLICT", "RuStore must have exactly one universal APK")
    p = packages[0]
    if (
        integer(p.get("verCode"), minimum=1) != release.code
        or p.get("verName") != release.value["versionName"]
        or integer(p.get("size"), minimum=1) != release.value["apkSize"]
        or p.get("isMainApk") is not True
    ):
        raise DeliveryError("MISMATCH", "RuStore package identity differs")
    string(p.get("apkUid"))
    return p


def advance_rustore(api: RuStore, release: Release, apk: Path, journal: Journal) -> dict[str, Any]:
    versions = api.versions()
    matches = [v for v in versions if v["versionCode"] == release.code]
    created = journal.get("rustore-create-done")
    if len(matches) > 1:
        raise DeliveryError("CONFLICT", "RuStore code occurs more than once")
    if matches and created is None:
        raise DeliveryError("CONFLICT", "RuStore consumed code has no bound receipt")
    if created is None:
        if any(v["versionCode"] >= release.code or v["versionStatus"] == "DRAFT" for v in versions):
            raise DeliveryError("CONFLICT", "RuStore has a used code or foreign draft")

        def create() -> dict[str, Any]:
            result = api.call(
                "POST",
                json={
                    "publishType": "INSTANTLY",
                    "partialValue": 100,
                    "whatsNew": release.value["whatsNew"],
                },
            )
            # The official response example documents an integer body. Do not coerce it.
            return {"versionId": integer(result.get("body"), minimum=1)}

        created = write_once(journal, "rustore-create", {"manifest": release.digest}, create)
    vid = integer(created.get("versionId"), minimum=1)
    detail = api.detail(vid)
    if integer(detail.get("versionId"), minimum=1) != vid:
        raise DeliveryError("MISMATCH", "RuStore version ID differs")
    uploaded = journal.get("rustore-upload-done")
    if uploaded is None:
        if detail.get("packages") or journal.get("rustore-upload-intent") is not None:
            # API does not return APK SHA-256. A lost upload receipt cannot be proven by
            # versionCode alone; retain the intent and require explicit recovery.
            raise DeliveryError(
                "UNKNOWN", "RuStore APK acceptance needs byte/receipt reconciliation"
            )
        release.verify_apk(apk)

        def upload() -> dict[str, Any]:
            with apk.open("rb") as stream:
                api.call(
                    "POST",
                    f"/{vid}/apk",
                    params={"isMainApk": "true", "servicesType": "Unknown"},
                    files={"file": (apk.name, stream, "application/vnd.android.package-archive")},
                    timeout=600,
                )
            p = rustore_package(release, api.detail(vid))
            return {"versionId": vid, "apkUid": p["apkUid"], "sha256": release.apk_hash}

        uploaded = write_once(
            journal, "rustore-upload", {"manifest": release.digest, "versionId": vid}, upload
        )
        detail = api.detail(vid)
    p = rustore_package(release, detail)
    if uploaded != {"versionId": vid, "apkUid": p["apkUid"], "sha256": release.apk_hash}:
        raise DeliveryError("MISMATCH", "RuStore package differs from accepted upload receipt")
    # Prefer the version list's mandatory fields (detail makes these optional).
    current = next((v for v in api.versions() if v["versionId"] == vid), None)
    if (
        current is None
        or current["versionCode"] != release.code
        or current.get("versionName") != release.value["versionName"]
    ):
        raise DeliveryError("MISMATCH", "RuStore has not confirmed the approved version")
    raw = current["versionStatus"]
    if raw == "ACTIVE" and integer(current.get("partialValue")) == 100:
        return {"status": "PUBLISHED", "rawState": raw, "versionId": vid, "packageId": p["apkUid"]}
    if current.get("publishType") != "INSTANTLY" or integer(current.get("partialValue")) != 100:
        raise DeliveryError("CONFLICT", "RuStore automatic full publication changed")
    if raw in {"TAKEN_FOR_MODERATION", "MODERATION", "AUTO_CHECK"}:
        return {"status": "IN_REVIEW", "rawState": raw, "versionId": vid}
    if raw == "READY_FOR_PUBLICATION":
        return {"status": "PROCESSING", "rawState": raw, "versionId": vid}
    if raw in {"REJECTED_BY_MODERATOR", "REJECTED_BY_SECURITY", "AUTO_CHECK_FAILED"}:
        return {"status": "REJECTED", "rawState": raw, "versionId": vid}
    if raw != "DRAFT":
        raise DeliveryError("CONFLICT", "RuStore is not in an allowed publication state")
    write_once(
        journal,
        "rustore-submit",
        {"manifest": release.digest, "versionId": vid, "apkUid": p["apkUid"]},
        lambda: api.call("POST", f"/{vid}/commit") and {"versionId": vid},
    )
    # A successful commit response is only submission. Subsequent polling proves review
    # and publication, rather than manufacturing a PUBLISHED state here.
    return {"status": "SUBMITTED", "rawState": raw, "versionId": vid}


def each_store(
    selected: Sequence[str], advance: Callable[[str], dict[str, Any]]
) -> dict[str, dict[str, Any]]:
    result = {}
    for store in selected:
        try:
            result[store] = advance(store)
        except core.DeliveryError as error:
            result[store] = {"status": error.code, "detail": error.message}
    return result


def check_approval(
    release: Release,
    repository: str,
    run: dict[str, Any],
    jobs: list[dict[str, Any]],
    artifacts: list[dict[str, Any]],
) -> None:
    if not (
        run.get("id") == release.value["buildRunId"]
        and run.get("path") == core.WORKFLOW_FILE
        and run.get("head_branch") == "main"
        and run.get("event") == "workflow_dispatch"
        and run.get("head_sha") == release.value["workflowSha"]
        and obj(run.get("repository")).get("full_name") == repository
        and obj(run.get("head_repository")).get("full_name") == repository
    ):
        raise DeliveryError("GATE", "approval run is not trusted main Store delivery")
    if not any(
        j.get("name") == f"Approve Android public release ({release.digest})"
        and j.get("conclusion") == "success"
        for j in jobs
    ):
        raise DeliveryError("GATE", "protected approval job did not succeed")
    # The digest in the successful job name is immutable run metadata and survives
    # artifact expiry. Initialization additionally requires the approval artifact.


class GitHub:
    def __init__(self, client: httpx.Client, repository: str, token: str) -> None:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise DeliveryError("CONFIG", "invalid GitHub repository")
        self.client, self.repository = client, repository
        self.base = f"https://api.github.com/repos/{repository}/"
        self.headers = {
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }

    def call(self, method: str, path: str, **kwargs: Any) -> Any:
        response = request(self.client, method, self.base + path, headers=self.headers, **kwargs)
        try:
            return response.json()
        except ValueError:
            raise DeliveryError("CONTRACT", "GitHub returned invalid JSON") from None

    def pages(self, path: str, key: str | None = None) -> list[dict[str, Any]]:
        result = []
        for page in range(1, 101):
            value = self.call("GET", path, params={"page": page, "per_page": 100})
            batch = items(value if key is None else obj(value).get(key))
            result.extend(batch)
            if len(batch) < 100:
                return result
        raise DeliveryError("CONTRACT", "GitHub pagination incomplete")

    def prove(self, release: Release, repo: Path) -> None:
        run_id = release.value["buildRunId"]
        run = obj(self.call("GET", f"actions/runs/{run_id}"))
        check_approval(
            release,
            self.repository,
            run,
            self.pages(f"actions/runs/{run_id}/jobs", "jobs"),
            self.pages(f"actions/runs/{run_id}/artifacts", "artifacts"),
        )
        # Both source and tooling must still belong to this repository's main history.
        for sha in (release.value["sourceSha"], release.value["workflowSha"]):
            core.resolve_revision(repo, sha, release.value["workflowSha"])

    def require_protected_approval(self) -> None:
        env = obj(self.call("GET", "environments/android-public-release"))
        owner = self.repository.split("/")[0].lower()
        rules = items(env.get("protection_rules"))
        reviewers = [
            r
            for rule in rules
            if rule.get("type") == "required_reviewers"
            for r in items(rule.get("reviewers"))
        ]
        if not (
            len(reviewers) == 1
            and reviewers[0].get("type") == "User"
            and obj(reviewers[0].get("reviewer")).get("login", "").lower() == owner
        ):
            raise DeliveryError(
                "CONFIG", "android-public-release must require repository owner's review"
            )
        if env.get("can_admins_bypass") is not False:
            raise DeliveryError("CONFIG", "android-public-release must disallow admin bypass")
        branches = self.pages(
            "environments/android-public-release/deployment-branch-policies", "branch_policies"
        )
        if not branches or any(
            b.get("name") != "main" or b.get("type") != "branch" for b in branches
        ):
            raise DeliveryError("CONFIG", "android-public-release must allow only main branch")

    def asset_bytes(self, asset: dict[str, Any]) -> bytes:
        if obj(asset.get("uploader")).get("login") != "github-actions[bot]":
            raise DeliveryError("GATE", "release asset was not uploaded by Actions")
        aid = integer(asset.get("id"), minimum=1)
        response = request(
            self.client,
            "GET",
            self.base + f"releases/assets/{aid}",
            headers={**self.headers, "Accept": "application/octet-stream"},
            accept_redirect=True,
        )
        if response.status_code in (301, 302, 303, 307, 308):
            url = parse_service_url(response.headers.get("location", ""))
            if (
                url.scheme != "https"
                or url.host
                not in {"release-assets.githubusercontent.com", "objects.githubusercontent.com"}
                or url.username
                or url.password
            ):
                raise DeliveryError("GATE", "untrusted GitHub asset redirect")
            response = request(self.client, "GET", str(url))
        if response.status_code != 200:
            raise DeliveryError("STORE", "GitHub asset unavailable")
        data = response.content
        if len(data) != integer(asset.get("size")):
            raise DeliveryError("MISMATCH", "GitHub asset size differs")
        digest = asset.get("digest")
        if digest is not None and digest != "sha256:" + hashlib.sha256(data).hexdigest():
            raise DeliveryError("MISMATCH", "GitHub asset digest differs")
        return data


class AssetJournal:
    """Create-only assets. All writers share workflow-level store-delivery concurrency.

    Never replace assets or mutate a checkpoint. If an asset write is uncertain, a
    later read must return the exact intended bytes before any store mutation.
    """

    def __init__(self, github: GitHub, release_id: int, identity: dict[str, Any]) -> None:
        self.github, self.release_id, self.identity = github, release_id, identity

    def assets(self) -> dict[str, dict[str, Any]]:
        assets = self.github.pages(f"releases/{self.release_id}/assets")
        names = [string(a.get("name")) for a in assets]
        if len(set(names)) != len(names):
            raise DeliveryError("MISMATCH", "duplicate journal assets")
        return dict(zip(names, assets, strict=True))

    def get(self, name: str) -> dict[str, Any] | None:
        asset = self.assets().get(name + ".json")
        if asset is None:
            return None
        try:
            value = obj(json.loads(self.github.asset_bytes(asset)))
        except ValueError:
            raise DeliveryError("CONTRACT", "invalid journal JSON") from None
        if set(value) != set(self.identity) | {"value"} or any(
            value[k] != v for k, v in self.identity.items()
        ):
            raise DeliveryError("MISMATCH", "journal checkpoint identity differs")
        return obj(value["value"])

    def entries(self, prefix: str) -> list[tuple[str, dict[str, Any]]]:
        result = []
        for name in sorted(self.assets()):
            if not name.startswith(prefix + "-"):
                continue
            if not re.fullmatch(re.escape(prefix) + r"-[0-9]{6}\.json", name):
                raise DeliveryError("CONTRACT", "unexpected transition asset name")
            value = self.get(name[:-5])
            if value is None:
                raise DeliveryError("STORE", "transition disappeared while reading")
            result.append((name[:-5], value))
        return result

    def put_once(self, name: str, value: dict[str, Any]) -> None:
        if not re.fullmatch(r"[a-z0-9-]+", name):
            raise DeliveryError("CONTRACT", "invalid checkpoint name")
        data = canonical({**self.identity, "value": value})
        self.put_asset(name + ".json", data, "application/json")

    def put_asset(self, name: str, data: bytes, content_type: str) -> None:
        asset = self.assets().get(name)
        if asset is not None:
            if self.github.asset_bytes(asset) != data:
                raise DeliveryError("MISMATCH", "immutable release asset differs")
            return
        url = f"https://uploads.github.com/repos/{self.github.repository}/releases/{self.release_id}/assets"
        try:
            request(
                self.github.client,
                "POST",
                url,
                headers={**self.github.headers, "Content-Type": content_type},
                params={"name": name},
                content=data,
                timeout=600,
            )
        except DeliveryError:
            # A lost GitHub upload response can be reconciled safely by exact bytes.
            asset = self.assets().get(name)
            if asset is None or self.github.asset_bytes(asset) != data:
                raise


class ReleaseJournal(AssetJournal):
    def __init__(self, github: GitHub, release: Release, release_id: int) -> None:
        super().__init__(
            github,
            release_id,
            {"schema": "talentforge-store-checkpoint/1", "manifest": release.digest},
        )
        self.release = release


def initialize_journal(github: GitHub, release: Release, apk: Path, repo: Path) -> ReleaseJournal:
    github.prove(release, repo)
    artifacts = github.pages(f"actions/runs/{release.value['buildRunId']}/artifacts", "artifacts")
    if not any(
        a.get("name") == "tf-approved-apk-" + release.digest and a.get("expired") is False
        for a in artifacts
    ):
        raise DeliveryError("GATE", "immutable approval artifact missing at initialization")
    release.verify_apk(apk)
    tag = f"android-store-{release.code}"
    found = [r for r in github.pages("releases") if r.get("tag_name") == tag]
    if len(found) > 1:
        raise DeliveryError("MISMATCH", "duplicate release journal")
    if found:
        remote = found[0]
        if obj(remote.get("author")).get("login") != "github-actions[bot]":
            raise DeliveryError("GATE", "release journal must be created by Actions")
        if (
            remote.get("target_commitish") != release.value["sourceSha"]
            or remote.get("body") != release.digest
        ):
            raise DeliveryError(
                "CONFLICT", "this Android code already has a different approved release"
            )
    else:
        remote = obj(
            github.call(
                "POST",
                "releases",
                json={
                    "tag_name": tag,
                    "target_commitish": release.value["sourceSha"],
                    "name": f"Android stores {release.value['versionName']} ({release.code})",
                    "body": release.digest,
                    "draft": True,
                    "prerelease": False,
                },
            )
        )
    journal = ReleaseJournal(github, release, integer(remote.get("id"), minimum=1))
    journal.put_asset("manifest.json", canonical(release.value), "application/json")
    journal.put_asset("approved.apk", apk.read_bytes(), "application/vnd.android.package-archive")
    return journal


def manifest_from_build(
    gate: dict[str, Any],
    report: dict[str, Any],
    apk: Path,
    stores: str,
    app_id: str,
    notes: str,
    run_id: int,
) -> Release:
    selected = list(STORES) if stores == "both" else [stores]
    if any(s not in STORES for s in selected):
        raise DeliveryError("CONFIG", "select rustore, appgallery or both")
    # Reuse existing binary/OTA checks. AAB is intentionally unnecessary for APK stores.
    core.check_android_release(gate=gate, apk=apk, apk_report=report, aab=None, aab_report=None)
    signature = obj(report.get("signature"))
    certs = signature.get("certificateSha256")
    if (
        signature.get("verifies") is not True
        or signature.get("signers") != 1
        or not isinstance(certs, list)
        or len(certs) != 1
    ):
        raise DeliveryError("MISMATCH", "APK signature evidence incomplete")
    cert = string(certs[0]).replace(":", "").lower()
    return Release.parse(
        {
            "schema": "talentforge-android-public/1",
            "sourceSha": gate["sourceSha"],
            "workflowSha": gate["workflowSha"],
            "buildRunId": run_id,
            "applicationId": APPLICATION_ID,
            "versionCode": gate["android"]["versionCode"],
            "versionName": gate["android"]["versionName"],
            "apkSha256": core.file_sha256(apk),
            "certificateSha256": cert,
            "apkSize": apk.stat().st_size,
            "otaProduction": True,
            "stores": selected,
            "appgalleryAppId": app_id,
            "whatsNew": notes,
            "createdAt": core.iso_now(),
        }
    )


def store_api(client: httpx.Client, store: str, app_id: str) -> RuStore | AppGallery:
    if store == "rustore":
        config = core.require_env(os.environ, ["RUSTORE_KEY_ID", "RUSTORE_PRIVATE_KEY_BASE64"])
        return RuStore.authenticate(
            client, config["RUSTORE_KEY_ID"], config["RUSTORE_PRIVATE_KEY_BASE64"]
        )
    config = core.require_env(os.environ, ["APPGALLERY_SERVICE_ACCOUNT_JSON"])
    return AppGallery(client, app_id, huawei_jwt(config["APPGALLERY_SERVICE_ACCOUNT_JSON"]))


def audit_stores(
    client: httpx.Client, selected: Sequence[str], app_id: str, code: int
) -> dict[str, dict[str, Any]]:
    def read(store: str) -> dict[str, Any]:
        api = store_api(client, store, app_id)
        if isinstance(api, RuStore):
            versions = api.versions()
            highest = max((v["versionCode"] for v in versions), default=0)
            active = [
                {k: v.get(k) for k in ("versionCode", "versionId", "versionStatus", "partialValue")}
                for v in versions
                if v["versionStatus"] in ("ACTIVE", "PARTIAL_ACTIVE")
            ]
        else:
            packages, info = api.packages(), api.info()
            highest = max((p["versionCode"] for p in packages), default=0)
            active = [
                {k: info.get(k) for k in ("onShelfVersionCode", "onShelfVersionId", "releaseState")}
            ]
        return {
            "status": "AVAILABLE" if code > highest else "CONFLICT",
            "highestConsumedCode": highest,
            "published": active,
        }

    return each_store(selected, read)


def load_journal(github: GitHub, remote: dict[str, Any], repo: Path) -> ReleaseJournal:
    if obj(remote.get("author")).get("login") != "github-actions[bot]":
        raise DeliveryError("GATE", "release journal must be created by Actions")
    rid = integer(remote.get("id"), minimum=1)
    assets = github.pages(f"releases/{rid}/assets")
    matches = [a for a in assets if a.get("name") == "manifest.json"]
    if len(matches) != 1:
        raise DeliveryError("GATE", "approved manifest missing/ambiguous")
    try:
        release = Release.parse(json.loads(github.asset_bytes(matches[0])))
    except ValueError:
        raise DeliveryError("CONTRACT", "invalid release manifest JSON") from None
    if (
        remote.get("tag_name") != f"android-store-{release.code}"
        or remote.get("body") != release.digest
        or remote.get("target_commitish") != release.value["sourceSha"]
    ):
        raise DeliveryError("MISMATCH", "release journal identity differs")
    github.prove(release, repo)
    return ReleaseJournal(github, release, rid)


def url_authority(raw: str) -> dict[str, Any]:
    """Diagnostic metadata only: never retain paths, signed queries or user info."""
    url = parse_service_url(raw)
    return {
        "scheme": url.scheme,
        "host": url.host,
        "port": url.port,
        "hasUserInfo": bool(url.username or url.password),
    }


def inspect_store(
    api: RuStore | AppGallery, release: Release, apk: Path, journal: Journal
) -> dict[str, Any]:
    """GET-only observations; never advance a store or repair an immutable receipt."""
    if isinstance(api, RuStore):
        created = journal.get("rustore-create-done")
        result: dict[str, Any] = {
            "status": "OBSERVED",
            "journal": {
                "createDone": created is not None,
                "uploadIntent": journal.get("rustore-upload-intent") is not None,
                "uploadDone": journal.get("rustore-upload-done") is not None,
                "submitIntent": journal.get("rustore-submit-intent") is not None,
                "submitDone": journal.get("rustore-submit-done") is not None,
            },
        }
        if created is None:
            return result
        vid = integer(created.get("versionId"), minimum=1)
        detail = api.detail(vid)
        if detail.get("versionId") != vid:
            raise DeliveryError("MISMATCH", "inspection version ID differs from journal")
        observed = {
            k: detail[k]
            for k in (
                "versionId",
                "versionStatus",
                "versionCode",
                "versionName",
                "publishType",
                "partialValue",
            )
            if k in detail
        }
        # Preserve absence/null/type differences for diagnosis, never coerce them.
        for field in ("packages", "fileUids"):
            observed[field + "Present"] = field in detail
            if field not in detail:
                continue
            value = detail[field]
            if not isinstance(value, list):
                observed[field + "WireType"] = type(value).__name__
                continue
            rows = []
            for entry in items(value):
                keys = (
                    ("apkUid", "verCode", "verName", "size", "isMainApk", "servicesType")
                    if field == "packages"
                    else ("fileUid", "type")
                )
                row = {k: entry[k] for k in keys if k in entry}
                if field == "fileUids" and "fileUrl" in entry:
                    raw_url = entry["fileUrl"]
                    row["url"] = (
                        url_authority(raw_url)
                        if isinstance(raw_url, str) and raw_url
                        else {"wireType": type(raw_url).__name__}
                    )
                rows.append(row)
            observed[field] = rows
        result["detail"] = observed
        return result

    packages, info = api.packages(), api.info()
    result = {
        "status": "OBSERVED",
        "packages": [
            {k: p[k] for k in ("pkgVersion", "versionCode", "packageSize", "shaCode") if k in p}
            for p in packages
        ],
        "appInfo": {
            k: info[k]
            for k in (
                "releaseState",
                "versionCode",
                "versionId",
                "onShelfVersionCode",
                "onShelfVersionId",
            )
            if k in info
        },
    }
    if any(p["versionCode"] == release.code for p in packages):
        return result
    upload = obj(
        api.call(
            "GET",
            "upload-url/for-obs",
            params={
                "fileName": apk.name,
                "sha256": release.apk_hash,
                "contentLength": release.value["apkSize"],
                "releaseType": 1,
                "chineseMainlandFlag": 0,
            },
        ).get("urlInfo")
    )
    raw_url = string(upload.get("url"))
    authority = url_authority(raw_url)
    headers = obj(upload.get("headers"))
    if any(not isinstance(k, str) or not isinstance(v, str) for k, v in headers.items()):
        raise DeliveryError("CONTRACT", "Huawei upload headers have wrong wire types")
    normalized = {k.lower(): v for k, v in headers.items()}
    if len(normalized) != len(headers):
        raise DeliveryError("CONTRACT", "Huawei upload has duplicate header names")
    try:
        huawei_upload_url(raw_url)
        allowed = True
    except core.DeliveryError:
        allowed = False
    result["upload"] = {
        "method": string(upload.get("method")),
        "url": authority,
        "passesCurrentUrlGuard": allowed,
        "headerHostMatches": normalized.get("host", "").lower()
        in {authority["host"], str(authority["host"]) + ":443"},
        "sha256Matches": normalized.get("x-amz-content-sha256") == release.apk_hash,
    }
    return result


def load_approved_apk(journal: ReleaseJournal, directory: Path) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    release = journal.release
    apk = directory / f"TalentForge-{release.value['versionName']}-{release.code}.apk"
    asset = journal.assets().get("approved.apk")
    if asset is None:
        raise DeliveryError("GATE", "approved APK asset missing")
    apk.write_bytes(journal.github.asset_bytes(asset))
    release.verify_apk(apk)
    return apk


def inspect_journal(journal: ReleaseJournal, directory: Path) -> dict[str, Any]:
    apk = load_approved_apk(journal, directory)
    release = journal.release
    return {
        "schema": "talentforge-android-store-inspection/1",
        "readOnly": True,
        "releaseId": journal.release_id,
        "manifestSha256": release.digest,
        "manifest": release.value,
        "at": core.iso_now(),
        "stores": each_store(
            release.value["stores"],
            lambda store: inspect_store(
                store_api(journal.github.client, store, release.value["appgalleryAppId"]),
                release,
                apk,
                journal,
            ),
        ),
    }


def advance_with_proof(
    journal: Journal, store: str, advance: Callable[[], dict[str, Any]]
) -> dict[str, Any]:
    proof = journal.get("published-" + store)
    if proof is not None:
        state = obj(proof.get("state"))
        if state.get("status") != "PUBLISHED":
            raise DeliveryError("MISMATCH", "invalid publication proof")
        return {**state, "observedAt": string(proof.get("observedAt")), "previouslyConfirmed": True}
    state = advance()
    if state.get("status") == "PUBLISHED":
        observed = core.iso_now()
        journal.put_once("published-" + store, {"state": state, "observedAt": observed})
        return {**state, "observedAt": observed}
    return state


def state_identity(state: dict[str, Any]) -> dict[str, Any]:
    return {
        k: v
        for k, v in state.items()
        if k not in {"observedAt", "previouslyConfirmed", "transitionId"}
    }


def record_transition(journal: Journal, store: str, state: dict[str, Any]) -> dict[str, Any]:
    prefix = "state-" + store
    records = journal.entries(prefix)
    for sequence, (name, value) in enumerate(records, 1):
        if (
            name != f"{prefix}-{sequence:06d}"
            or value.get("sequence") != sequence
            or value.get("store") != store
        ):
            raise DeliveryError("MISMATCH", "transition journal has a gap or wrong identity")
    identity = state_identity(state)
    if records and state_identity(obj(records[-1][1].get("state"))) == identity:
        return records[-1][1]
    sequence = len(records) + 1
    event = {
        "sequence": sequence,
        "store": store,
        "state": identity,
        "observedAt": core.iso_now(),
        "runUrl": run_url(),
    }
    journal.put_once(f"{prefix}-{sequence:06d}", event)
    return event


def reconcile_journal(journal: ReleaseJournal, directory: Path) -> dict[str, Any]:
    release = journal.release
    apk = load_approved_apk(journal, directory)

    def advance(store: str) -> dict[str, Any]:
        def perform() -> dict[str, Any]:
            api = store_api(journal.github.client, store, release.value["appgalleryAppId"])
            return (
                advance_rustore(api, release, apk, journal)
                if isinstance(api, RuStore)
                else advance_appgallery(api, release, apk, journal)
            )

        return advance_with_proof(journal, store, perform)

    states = each_store(release.value["stores"], advance)
    for store, state in states.items():
        event = record_transition(journal, store, state)
        state["transitionId"] = event["sequence"]
    report = {
        "schema": "talentforge-android-store-report/1",
        "releaseId": journal.release_id,
        "manifest": release.value,
        "manifestSha256": release.digest,
        "at": core.iso_now(),
        "runUrl": run_url(),
        "stores": states,
        "complete": all(v["status"] == "PUBLISHED" for v in states.values()),
    }
    # Routine poll reports are Actions artifacts. Only actual transitions/proofs
    # consume durable release assets, avoiding GitHub's per-release asset limit.
    if report["complete"] and journal.get("publication-complete") is None:
        journal.put_once("publication-complete", report)
    return report


def run_url() -> str:
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    return f"https://github.com/{repository}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}"


def send_telegram(client: httpx.Client, token: str, chat_id: str, text: str) -> dict[str, Any]:
    response = json_body(
        request(
            client,
            "POST",
            f"https://api.telegram.org/bot{token}/sendMessage",
            json={"chat_id": chat_id, "text": text, "disable_web_page_preview": True},
        )
    )
    if response.get("ok") is not True:
        raise DeliveryError("STORE", "Telegram refused status notification")
    return {"status": "SENT"}


def notify_report(
    journal: ReleaseJournal, report: dict[str, Any], token: str, chat_id: str
) -> dict[str, Any]:
    release = journal.release
    if (
        report.get("manifestSha256") != release.digest
        or report.get("releaseId") != journal.release_id
    ):
        raise DeliveryError("MISMATCH", "notification is not bound to approved release")
    results = {}
    for store, state in obj(report.get("stores")).items():
        if store not in release.value["stores"]:
            raise DeliveryError("MISMATCH", "notification includes unapproved store")
        status = string(obj(state).get("status"))
        if not re.fullmatch(r"[A-Z_]+", status):
            raise DeliveryError("CONTRACT", "invalid notification status")
        sequence = integer(state.get("transitionId"), minimum=1)
        event = journal.get(f"state-{store}-{sequence:06d}")
        if (
            event is None
            or event.get("store") != store
            or event.get("sequence") != sequence
            or obj(event.get("state")) != state_identity(state)
        ):
            raise DeliveryError("MISMATCH", "notification transition is not recorded")
        step = f"telegram-{store}-{sequence:06d}"
        text = (
            f"TalentForge {release.value['versionName']} ({release.code})\n{store}: {status}\n"
            f"source: {release.value['sourceSha']}\nAPK SHA-256: {release.apk_hash}\n"
            f"{report['runUrl']}"
        )

        try:
            results[store] = write_once(
                journal,
                step,
                {"status": status, "store": store, "transition": sequence},
                lambda text=text: send_telegram(journal.github.client, token, chat_id, text),
            )
        except core.DeliveryError as error:
            results[store] = {"status": error.code, "detail": error.message}
    return results


def reconcile_releases(
    github: GitHub, remotes: list[dict[str, Any]], repo: Path, out: Path
) -> dict[str, Any]:
    result: dict[str, Any] = {"releases": [], "errors": []}
    core.write_json(out, result)
    for remote in remotes:
        try:
            journal = load_journal(github, remote, repo)
            complete = journal.get("publication-complete")
            report = (
                complete
                if complete is not None
                else reconcile_journal(journal, out.parent / str(journal.release_id))
            )
            result["releases"].append(report)
        except core.DeliveryError as error:
            result["errors"].append(
                {"releaseId": remote.get("id"), "status": error.code, "detail": error.message}
            )
        # Preserve results even when a later journal is incomplete or invalid.
        core.write_json(out, result)
    return result


def notify_releases(
    github: GitHub, reports: dict[str, Any], repo: Path, out: Path, token: str, chat_id: str
) -> dict[str, Any]:
    if set(reports) != {"releases", "errors"}:
        raise DeliveryError("CONTRACT", "notification requires the complete reconciliation report")
    errors = items(reports["errors"])
    failures = {}
    for error in errors:
        rid = integer(error.get("releaseId"), minimum=1)
        status = string(error.get("status"))
        if set(error) != {"releaseId", "status", "detail"} or status not in DeliveryError.CODES:
            raise DeliveryError("CONTRACT", "invalid journal error report")
        string(error["detail"])
        if rid in failures:
            raise DeliveryError("CONTRACT", "duplicate journal error report")
        failures[rid] = error
    result: dict[str, Any] = {"notifications": [], "journalAlerts": [], "errors": list(errors)}
    core.write_json(out, result)
    checked = set()
    for report in items(reports["releases"]):
        rid = integer(report.get("releaseId"), minimum=1)
        if rid in failures or rid in checked:
            raise DeliveryError("CONTRACT", "duplicate reconciliation release")
        checked.add(rid)
        try:
            journal = load_journal(github, obj(github.call("GET", f"releases/{rid}")), repo)
            result["notifications"].append(
                {"releaseId": rid, "stores": notify_report(journal, report, token, chat_id)}
            )
        except core.DeliveryError as error:
            failure = {"releaseId": rid, "status": error.code, "detail": error.message}
            result["errors"].append(failure)
            failures[rid] = failure
        core.write_json(out, result)
    for rid in sorted(checked | failures.keys()):
        try:
            # Operational alerts must work even when manifest/approval loading fails.
            # Their separate identity never authorizes store writes or publication claims.
            alerts = AssetJournal(
                github, rid, {"schema": "talentforge-store-journal-alert/1", "releaseId": rid}
            )
            failure = failures.get(rid)
            if failure is None:
                if alerts.entries("state-journal-alert"):
                    record_transition(alerts, "journal-alert", {"status": "HEALTHY"})
            else:
                state = {"status": failure["status"], "detail": failure["detail"]}
                sequence = record_transition(alerts, "journal-alert", state)["sequence"]
                text = (
                    f"TalentForge: journal error (release {rid})\n"
                    f"{state['status']}: {state['detail']}\n{run_url()}"
                )
                receipt = write_once(
                    alerts,
                    f"telegram-journal-alert-{sequence:06d}",
                    {"releaseId": rid, "transition": sequence, "state": state},
                    lambda text=text: send_telegram(github.client, token, chat_id, text),
                )
                result["journalAlerts"].append({"releaseId": rid, **receipt})
        except core.DeliveryError as error:
            result["journalAlerts"].append(
                {"releaseId": rid, "status": error.code, "detail": error.message}
            )
        core.write_json(out, result)
    return result


def summary(report: dict[str, Any]) -> None:
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if not target:
        return
    with Path(target).open("a") as stream:
        stream.write(
            "\n### Android public stores\n\n```json\n"
            + json.dumps(report, ensure_ascii=False, indent=2)
            + "\n```\n"
        )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=("audit", "manifest", "approval", "initialize", "inspect", "reconcile", "notify"),
    )
    parser.add_argument("--repo", type=Path, default=Path())
    parser.add_argument("--gate", type=Path)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--apk-report", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--notes", type=Path)
    parser.add_argument("--reports", type=Path)
    parser.add_argument("--stores", choices=(*STORES, "both"), default="both")
    parser.add_argument("--release-id", type=int)
    parser.add_argument("--out", type=Path, required=True)
    return parser.parse_args(argv)


def run(args: argparse.Namespace) -> int:
    if os.environ.get("GITHUB_REF") != core.MAIN_REF:
        raise DeliveryError("GATE", "public store tools run only from main")
    if args.command == "inspect" and (args.release_id is None or args.release_id < 1):
        raise DeliveryError("CONFIG", "inspection requires an explicit positive release ID")
    repository = string(os.environ.get("GITHUB_REPOSITORY"))
    token = string(os.environ.get("GH_TOKEN"))
    with delivery_client() as client:
        github = GitHub(client, repository, token)
        if args.command == "audit":
            gate = core.read_json_object(args.gate)
            selected = list(STORES) if args.stores == "both" else [args.stores]
            result = audit_stores(
                client,
                selected,
                os.environ.get("APPGALLERY_APP_ID", ""),
                integer(gate["android"]["versionCode"], minimum=1),
            )
            core.write_json(args.out, result)
            summary(result)
            core.set_outputs(
                {"appgallery_app_id": os.environ.get("APPGALLERY_APP_ID", "")}, os.environ
            )
            return int(any(v["status"] != "AVAILABLE" for v in result.values()))
        if args.command == "manifest":
            release = manifest_from_build(
                core.read_json_object(args.gate),
                core.read_json_object(args.apk_report),
                args.apk,
                args.stores,
                os.environ.get("APPGALLERY_APP_ID", ""),
                args.notes.read_text().strip(),
                integer(int(os.environ["GITHUB_RUN_ID"]), minimum=1),
            )
            core.write_json(args.out, release.value)
            core.set_outputs({"manifest_sha256": release.digest}, os.environ)
            summary({"approvalRequiredFor": release.value, "manifestSha256": release.digest})
            return 0
        if args.command in {"approval", "initialize"}:
            release = Release.parse(core.read_json_object(args.manifest))
            release.verify_apk(args.apk)
            if release.value["buildRunId"] != int(os.environ["GITHUB_RUN_ID"]):
                raise DeliveryError("GATE", "approval must occur in APK's original run")
            if args.command == "approval":
                github.require_protected_approval()
                result = {"manifestSha256": release.digest}
                core.set_outputs({"manifest_sha256": release.digest}, os.environ)
            else:
                journal = initialize_journal(github, release, args.apk, args.repo)
                result = {"releaseId": journal.release_id, "manifestSha256": release.digest}
                core.set_outputs({"release_id": str(journal.release_id)}, os.environ)
            core.write_json(args.out, result)
            return 0
        if args.command == "inspect":
            remote = obj(github.call("GET", f"releases/{args.release_id}"))
            journal = load_journal(github, remote, args.repo)
            result = inspect_journal(journal, args.out.parent / "inspection-files")
            core.write_json(args.out, result)
            summary(result)
            return int(any(s["status"] != "OBSERVED" for s in result["stores"].values()))
        if args.command == "reconcile":
            remotes = (
                [obj(github.call("GET", f"releases/{args.release_id}"))]
                if args.release_id
                else [
                    r
                    for r in github.pages("releases")
                    if re.fullmatch(r"android-store-[1-9][0-9]*", str(r.get("tag_name", "")))
                ]
            )
            result = reconcile_releases(github, remotes, args.repo, args.out)
            summary(result)
            pending = {"PUBLISHED", "IN_REVIEW", "PROCESSING", "SUBMITTED", "READY_TO_SUBMIT"}
            return int(
                bool(result["errors"])
                or any(
                    s["status"] not in pending
                    for r in result["releases"]
                    for s in r["stores"].values()
                )
            )
        config = core.require_env(os.environ, ["TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID"])
        result = notify_releases(
            github,
            core.read_json_object(args.reports),
            args.repo,
            args.out,
            config["TELEGRAM_BOT_TOKEN"],
            config["TELEGRAM_CHAT_ID"],
        )
        return int(
            bool(result["errors"])
            or any(a["status"] != "SENT" for a in result["journalAlerts"])
            or any(
                s["status"] != "SENT" for n in result["notifications"] for s in n["stores"].values()
            )
        )


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        return run(args)
    except core.DeliveryError as error:
        result = {"status": error.code, "detail": error.message}
    except (OSError, ValueError, KeyError, TypeError):
        # Never dump credentials, response bodies, request URLs, or a traceback to Actions.
        result = {"status": "CONFIG", "detail": "Invalid/missing input or unavailable local file"}
    core.write_json(args.out, result)
    summary(result)
    print(json.dumps(result))
    return 1


if __name__ == "__main__":
    sys.exit(main())
