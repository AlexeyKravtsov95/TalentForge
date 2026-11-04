"""Public Android delivery, offline evidence only (no real store writes)."""

from __future__ import annotations

import base64
import hashlib
import importlib.util
import json
import ssl
import sys
import zipfile
from pathlib import Path
from typing import Any

import httpx
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from httpx import _client

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
spec = importlib.util.spec_from_file_location(
    "android_store_delivery", ROOT / "scripts/android_store_delivery.py"
)
assert spec and spec.loader
d = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = d
spec.loader.exec_module(d)


def manifest(**updates: Any) -> dict[str, Any]:
    result = {
        "schema": "talentforge-android-public/1",
        "sourceSha": "a" * 40,
        "workflowSha": "b" * 40,
        "buildRunId": 123,
        "applicationId": d.APPLICATION_ID,
        "versionCode": 5,
        "versionName": "1.0.0",
        "apkSha256": "c" * 64,
        "certificateSha256": "d" * 64,
        "apkSize": 10,
        "otaProduction": True,
        "stores": ["rustore", "appgallery"],
        "appgalleryAppId": "119465493",
        "whatsNew": "UX fixes",
        "createdAt": "2026-11-03T00:00:00Z",
    }
    result.update(updates)
    return result


@pytest.mark.parametrize(
    "updates",
    [
        {"versionCode": True},
        {"versionCode": "5"},
        {"otaProduction": False},
        {"applicationId": "other.app"},
        {"unknown": 1},
        {"stores": ["play"]},
        {"sourceSha": "main"},
        {"apkSha256": "C" * 64},
        {"apkSize": 0},
    ],
)
def test_manifest_refuses_unapproved_identity(updates: dict[str, Any]) -> None:
    with pytest.raises(d.DeliveryError):
        d.Release.parse(manifest(**updates))


@pytest.mark.parametrize("project_id", ["", "project-from-console"])
def test_service_account_signs_ps256_without_token_exchange(project_id: str) -> None:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    ).decode()
    token = d.huawei_jwt(
        json.dumps(
            {
                "key_id": "kid",
                "sub_account": "subject",
                "private_key": pem,
                "project_id": project_id,
                "token_uri": d.HUAWEI_AUDIENCE,
            }
        ),
        now=1000,
    )
    h, p, s = token.split(".")

    def decode(x: str) -> bytes:
        return base64.urlsafe_b64decode(x + "=" * (-len(x) % 4))

    assert json.loads(decode(h)) == {"alg": "PS256", "kid": "kid", "typ": "JWT"}
    assert json.loads(decode(p)) == {
        "iss": "subject",
        "aud": d.HUAWEI_AUDIENCE,
        "iat": 1000,
        "exp": 4600,
    }
    key.public_key().verify(
        decode(s),
        f"{h}.{p}".encode(),
        padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=32),
        hashes.SHA256(),
    )


@pytest.mark.parametrize(
    ("updates", "field"),
    [
        ({"project_id": None}, "project_id"),
        ({"project_id": 123}, "project_id"),
        ({"project_id": True}, "project_id"),
        ({"project_id": []}, "project_id"),
        ({"project_id": {}}, "project_id"),
        ({"token_uri": "https://private-audience.example"}, "token_uri"),
    ],
)
def test_huawei_config_failure_names_field_without_exposing_value(
    updates: dict[str, Any], field: str
) -> None:
    account = {"project_id": "", "token_uri": d.HUAWEI_AUDIENCE, **updates}
    with pytest.raises(d.DeliveryError) as error:
        d.huawei_jwt(json.dumps(account))
    assert error.value.code == "CONFIG"
    assert field in error.value.message
    assert "private-project-value" not in error.value.message
    assert "private-audience" not in error.value.message


def test_huawei_audit_defers_permissions_to_api_and_stops_on_forbidden(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    account = {
        "project_id": "original-console-project",
        "key_id": "kid",
        "sub_account": "subject",
        "private_key": key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ).decode(),
        "token_uri": d.HUAWEI_AUDIENCE,
    }
    monkeypatch.setenv("APPGALLERY_SERVICE_ACCOUNT_JSON", json.dumps(account))
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append((request.method, request.url.host, request.url.params["appId"]))
        return httpx.Response(403, text="private API error detail")

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = d.audit_stores(client, ["appgallery"], "119465493", 5)
    assert result == {"appgallery": {"status": "STORE", "detail": "STORE: GET HTTP 403"}}
    assert calls == [("GET", "connect-api.cloud.huawei.com", "119465493")]


def test_additional_ca_is_used_only_for_exact_rustore_host(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Catch global trust expansion or loss of verification when fixing RuStore TLS."""

    class TrustProbe(httpx.BaseTransport):
        def __init__(self, *, verify: Any = True, **kwargs: Any) -> None:
            self.verify = verify

        def handle_request(self, request: httpx.Request) -> httpx.Response:
            context = self.verify
            assert context is True or isinstance(context, ssl.SSLContext)
            if isinstance(context, ssl.SSLContext):
                assert context.verify_mode == ssl.CERT_REQUIRED
                assert context.check_hostname
                roots = context.get_ca_certs(binary_form=True)
                additional = any(
                    hashlib.sha256(root).hexdigest()
                    == "d26d2d0231b7c39f92cc738512ba54103519e4405d68b5bd703e9788ca8ecf31"
                    for root in roots
                )
            else:
                additional = False
            return httpx.Response(200, json={"additionalCa": additional})

    monkeypatch.setattr(httpx, "HTTPTransport", TrustProbe)
    monkeypatch.setattr(_client, "HTTPTransport", TrustProbe)
    with d.delivery_client() as client:
        for host, expected in (
            ("public-api-m.rustore.ru", True),
            ("public-api-m.rustore.ru.evil.example", False),
            ("other.rustore.ru", False),
            ("api.github.com", False),
            ("connect-api.cloud.huawei.com", False),
        ):
            assert client.get(f"https://{host}/").json() == {"additionalCa": expected}


def test_huawei_wire_types_and_all_package_pages() -> None:
    calls = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(dict(req.url.params))
        start = int(req.url.params["fromRecCount"])
        return httpx.Response(
            200,
            json={
                "ret": {"code": 0},
                "retCount": 1,
                "totalCount": 2,
                "pkgList": [
                    {
                        "versionCode": start,
                        "pkgVersion": f"pkg{start}",
                        "fileName": "app.apk",
                        "packageSize": 10,
                        "uploadTime": 1,
                        "packageType": 1,
                        "shaCode": "c" * 64,
                    }
                ],
            },
        )

    api = d.AppGallery(httpx.Client(transport=httpx.MockTransport(handler)), "119465493", "jwt")
    assert [x["versionCode"] for x in api.packages()] == [1, 2]
    assert [x["fromRecCount"] for x in calls] == ["1", "2"]


def test_published_huawei_uses_on_shelf_id_not_only_release_state() -> None:
    r = d.Release.parse(manifest())
    package = {"pkgVersion": "new", "versionCode": 5, "shaCode": "c" * 64, "packageSize": 10}
    state = {
        "releaseState": 4,
        "versionCode": 5,
        "versionId": "new",
        "onShelfVersionCode": 4,
        "onShelfVersionId": "old",
    }
    assert d.huawei_observation(r, state, [package])["status"] == "IN_REVIEW"
    state.update(releaseState=0, onShelfVersionCode=5, onShelfVersionId="new")
    assert d.huawei_observation(r, state, [package])["status"] == "PUBLISHED"
    state["onShelfVersionId"] = "foreign"
    with pytest.raises(d.DeliveryError):
        d.huawei_observation(r, state, [package])


@pytest.mark.parametrize("value", [None, "0" * 64, True])
def test_huawei_missing_or_different_hash_never_means_published(value: Any) -> None:
    with pytest.raises(d.DeliveryError):
        d.huawei_observation(
            d.Release.parse(manifest()),
            {"releaseState": 0, "onShelfVersionCode": 5, "onShelfVersionId": "p"},
            [{"pkgVersion": "p", "versionCode": 5, "shaCode": value, "packageSize": 10}],
        )


def test_writes_are_not_retried_and_error_output_does_not_echo_body() -> None:
    calls = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(req)
        return httpx.Response(503, text="secret-private-key-and-url")

    with pytest.raises(d.DeliveryError) as error:
        d.request(
            httpx.Client(transport=httpx.MockTransport(handler)),
            "POST",
            "https://example.com/api",
            json={},
        )
    assert len(calls) == 1
    assert "secret-private-key-and-url" not in str(error.value)


def test_intent_without_receipt_stops_instead_of_repeating_write() -> None:
    j = d.MemoryJournal()
    j.put_once("upload-intent", {"hash": "c" * 64})
    with pytest.raises(d.DeliveryError, match="UNKNOWN"):
        d.write_once(j, "upload", {"hash": "c" * 64}, lambda: pytest.fail("blind retry"))


def test_checkpoint_precedes_mutation_and_is_create_only() -> None:
    j = d.MemoryJournal()

    def write() -> dict[str, Any]:
        assert j.get("upload-intent") == {"hash": "c" * 64}
        return {"id": 4}

    assert d.write_once(j, "upload", {"hash": "c" * 64}, write) == {"id": 4}
    assert d.write_once(j, "upload", {"hash": "c" * 64}, lambda: pytest.fail("repeat")) == {"id": 4}
    with pytest.raises(d.DeliveryError):
        j.put_once("upload-done", {"id": 5})


@pytest.mark.parametrize(
    "url",
    [
        "http://store.huaweicloud.com/a",
        "https://evil.com/a",
        "https://127.0.0.1/a",
        "https://user:password@x.huaweicloud.com/a",
        "https://x.huaweicloud.com:8443/a",
    ],
)
def test_upload_url_cannot_redirect_credentials(url: str) -> None:
    with pytest.raises(d.DeliveryError):
        d.huawei_upload_url(url)


def test_huawei_existing_approved_package_is_submitted_without_upload() -> None:
    r = d.Release.parse(manifest())
    calls = []
    state = {
        "releaseState": 7,
        "versionCode": 5,
        "versionId": "pkg",
        "onShelfVersionCode": 1,
        "onShelfVersionId": "old",
    }

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(req)
        assert req.headers["authorization"] == "Bearer jwt"
        if req.url.path.endswith("package-list"):
            body = {
                "retCount": 1,
                "totalCount": 1,
                "pkgList": [
                    {
                        "pkgVersion": "pkg",
                        "versionCode": 5,
                        "shaCode": r.apk_hash,
                        "packageSize": 10,
                    }
                ],
            }
        elif req.url.path.endswith("app-info"):
            body = {"appInfo": dict(state)}
        elif req.url.path.endswith("app-submit"):
            assert req.url.params["releaseType"] == "1"
            assert "releaseTime" not in req.url.params
            state["releaseState"] = 4
            body = {}
        else:
            pytest.fail(f"unexpected request: {req.method} {req.url.path}")
        return httpx.Response(200, json={"ret": {"code": 0}, **body})

    j = d.MemoryJournal()
    api = d.AppGallery(httpx.Client(transport=httpx.MockTransport(handler)), "119465493", "jwt")
    assert d.advance_appgallery(api, r, Path("unused"), j, now=1000)["status"] == "IN_REVIEW"
    assert len([q for q in calls if q.method == "POST"]) == 1
    assert j.get("appgallery-submit-intent") is not None
    assert d.advance_appgallery(api, r, Path("unused"), j, now=1100)["status"] == "IN_REVIEW"
    assert len([q for q in calls if q.method == "POST"]) == 1


def test_huawei_console_race_is_caught_immediately_before_submission() -> None:
    r = d.Release.parse(manifest())
    reads = 0

    def handler(req: httpx.Request) -> httpx.Response:
        nonlocal reads
        assert req.method == "GET", "foreign candidate must never be submitted"
        if req.url.path.endswith("package-list"):
            b = {
                "retCount": 1,
                "totalCount": 1,
                "pkgList": [
                    {
                        "pkgVersion": "approved",
                        "versionCode": 5,
                        "shaCode": r.apk_hash,
                        "packageSize": 10,
                    }
                ],
            }
        else:
            reads += 1
            b = {
                "appInfo": {
                    "releaseState": 7,
                    "versionCode": 5 if reads == 1 else 6,
                    "versionId": "approved" if reads == 1 else "foreign",
                    "onShelfVersionCode": 1,
                }
            }
        return httpx.Response(200, json={"ret": {"code": 0}, **b})

    api = d.AppGallery(httpx.Client(transport=httpx.MockTransport(handler)), "119465493", "jwt")
    with pytest.raises(d.DeliveryError, match="CONFLICT"):
        d.advance_appgallery(api, r, Path("unused"), d.MemoryJournal(), now=1000)


def test_rustore_all_statuses_are_read_and_string_codes_refused() -> None:
    def handler(req: httpx.Request) -> httpx.Response:
        assert req.url.params["filterTestingType"] == "ALL"
        assert "versionStatuses" not in req.url.params
        return httpx.Response(
            200,
            json={
                "code": "OK",
                "body": {
                    "content": [{"versionCode": "5", "versionId": 10, "versionStatus": "ACTIVE"}],
                    "pageNumber": 0,
                    "pageSize": 100,
                    "totalElements": 1,
                    "totalPages": 1,
                },
            },
        )

    api = d.RuStore(httpx.Client(transport=httpx.MockTransport(handler)), "token")
    with pytest.raises(d.DeliveryError, match="CONTRACT"):
        api.versions()


def test_rustore_published_requires_bound_apk_receipt() -> None:
    r = d.Release.parse(manifest())
    version = {
        "versionId": 9,
        "versionCode": 5,
        "versionName": "1.0.0",
        "versionStatus": "ACTIVE",
        "partialValue": 100,
        "publishType": "INSTANTLY",
    }
    detail = {
        "versionId": 9,
        "packages": [
            {"verCode": 5, "verName": "1.0.0", "apkUid": "uid", "size": 10, "isMainApk": True}
        ],
    }

    def handler(req: httpx.Request) -> httpx.Response:
        assert req.method == "GET"
        body = (
            {
                "content": [version],
                "pageNumber": 0,
                "pageSize": 100,
                "totalElements": 1,
                "totalPages": 1,
            }
            if req.url.path.endswith("/version")
            else detail
        )
        return httpx.Response(200, json={"code": "OK", "body": body})

    api = d.RuStore(httpx.Client(transport=httpx.MockTransport(handler)), "token")
    j = d.MemoryJournal()
    with pytest.raises(d.DeliveryError):
        d.advance_rustore(api, r, Path("unused"), j)
    j.put_once("rustore-create-intent", {"manifest": r.digest})
    j.put_once("rustore-create-done", {"versionId": 9})
    j.put_once("rustore-upload-intent", {"manifest": r.digest, "versionId": 9})
    j.put_once("rustore-upload-done", {"versionId": 9, "apkUid": "uid", "sha256": r.apk_hash})
    assert d.advance_rustore(api, r, Path("unused"), j)["status"] == "PUBLISHED"
    detail["packages"][0]["apkUid"] = "changed"
    with pytest.raises(d.DeliveryError):
        d.advance_rustore(api, r, Path("unused"), j)


def test_partial_success_is_reported_for_each_store() -> None:
    def advance(store: str) -> dict[str, Any]:
        if store == "rustore":
            raise d.DeliveryError("UNKNOWN", "ambiguous upload")
        return {"status": "PUBLISHED", "packageId": "p"}

    report = d.each_store(["rustore", "appgallery"], advance)
    assert report["rustore"]["status"] == "UNKNOWN"
    assert report["appgallery"]["status"] == "PUBLISHED"


def test_approval_provenance_rejects_other_workflows_forks_and_missing_gate() -> None:
    r = d.Release.parse(manifest())
    repo = "owner/repo"
    run = {
        "id": 123,
        "path": ".github/workflows/store-delivery.yml",
        "head_branch": "main",
        "event": "workflow_dispatch",
        "head_sha": "b" * 40,
        "repository": {"full_name": repo},
        "head_repository": {"full_name": repo},
    }
    jobs = [{"name": "Approve Android public release (" + r.digest + ")", "conclusion": "success"}]
    artifacts = [{"name": "tf-approved-apk-" + r.digest, "expired": False}]
    d.check_approval(r, repo, run, jobs, artifacts)
    for change in (
        {"path": "other.yml"},
        {"head_branch": "feature"},
        {"head_repository": {"full_name": "fork/repo"}},
        {"head_sha": "a" * 40},
    ):
        with pytest.raises(d.DeliveryError):
            d.check_approval(r, repo, {**run, **change}, jobs, artifacts)
    for bad_jobs, bad_assets in (
        ([], artifacts),
        (
            [{"name": "Approve Android public release (foreign)", "conclusion": "success"}],
            artifacts,
        ),
    ):
        with pytest.raises(d.DeliveryError):
            d.check_approval(r, repo, run, bad_jobs, bad_assets)


def test_apk_only_keeps_version_package_hash_and_ota_checks(tmp_path: Path) -> None:
    import store_delivery as core

    apk = tmp_path / "app.apk"
    with zipfile.ZipFile(apk, "w") as z:
        z.writestr("classes.dex", b" ".join(core.ANDROID_OTA_REQUIRED))
    gate = {"android": {"versionCode": 5, "versionName": "1.0.0"}, "sourceSha": "a" * 40}
    report = {
        "result": "PASS",
        "purpose": "release",
        "package": {
            "applicationId": core.APPLICATION_ID,
            "versionCode": 5,
            "versionName": "1.0.0",
            "permissions": [core.INTERNET_PERMISSION],
        },
        "apk": {"sha256": core.file_sha256(apk)},
    }
    check = core.check_android_release(
        gate=gate, apk=apk, apk_report=report, aab=None, aab_report=None
    )
    assert check["apkSha256"] == core.file_sha256(apk)
    assert check["otaProduction"] is True
    report["package"]["versionCode"] = 4
    with pytest.raises(core.DeliveryError):
        core.check_android_release(gate=gate, apk=apk, apk_report=report, aab=None, aab_report=None)


def test_approval_cli_writes_digest_to_github_outputs(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import hashlib

    apk = tmp_path / "app.apk"
    apk.write_bytes(b"verified-apk")
    raw = manifest(
        apkSha256=hashlib.sha256(apk.read_bytes()).hexdigest(), apkSize=apk.stat().st_size
    )
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(raw))
    for key, value in {
        "GITHUB_REF": "refs/heads/main",
        "GITHUB_RUN_ID": "123",
        "GITHUB_REPOSITORY": "owner/repo",
        "GH_TOKEN": "test",
        "GITHUB_OUTPUT": str(tmp_path / "outputs"),
    }.items():
        monkeypatch.setenv(key, value)
    monkeypatch.setattr(d.GitHub, "require_protected_approval", lambda _: None)
    assert (
        d.main(
            [
                "approval",
                "--manifest",
                str(path),
                "--apk",
                str(apk),
                "--out",
                str(tmp_path / "approval.json"),
            ]
        )
        == 0
    )
    assert (tmp_path / "outputs").read_text() == "manifest_sha256=" + d.Release.parse(
        raw
    ).digest + "\n"


class HuaweiStore:
    def __init__(self, release: Any, *, lose: str = "") -> None:
        self.release, self.lose = release, lose
        self.uploads = self.attachments = self.submissions = 0
        self.packages: list[dict[str, Any]] = []
        self.state: dict[str, Any] = {
            "releaseState": 0,
            "versionCode": 1,
            "versionId": "old",
            "onShelfVersionCode": 1,
            "onShelfVersionId": "old",
        }

    def handler(self, req: httpx.Request) -> httpx.Response:
        path = req.url.path
        if req.url.host != "connect-api.cloud.huawei.com":
            assert req.method == "PUT" and req.content == b"verified-apk"
            assert req.headers["x-amz-content-sha256"] == self.release.apk_hash
            assert req.headers["authorization"] == "OBS signature"
            self.uploads += 1
            if self.lose == "upload":
                raise httpx.ReadTimeout("accepted but response lost", request=req)
            return httpx.Response(200)
        assert req.headers["authorization"] == "Bearer jwt"
        if path.endswith("package-list"):
            body = {
                "retCount": len(self.packages),
                "totalCount": len(self.packages),
                "pkgList": self.packages,
            }
        elif path.endswith("app-info"):
            body = {"appInfo": dict(self.state)}
        elif path.endswith("upload-url/for-obs"):
            assert req.url.params["sha256"] == self.release.apk_hash
            body = {
                "urlInfo": {
                    "method": "PUT",
                    "url": "https://tf.obs.ru-northwest-2.myhuaweicloud.com/apk",
                    "objectId": "object.apk",
                    "headers": {
                        "Host": "tf.obs.ru-northwest-2.myhuaweicloud.com",
                        "Authorization": "OBS signature",
                        "x-amz-content-sha256": self.release.apk_hash,
                        "Content-Type": "application/octet-stream",
                    },
                }
            }
        elif path.endswith("app-file-info"):
            assert json.loads(req.content) == {
                "fileType": 5,
                "files": [{"fileName": "app.apk", "fileDestUrl": "object.apk"}],
            }
            self.attachments += 1
            self.packages = [
                {
                    "pkgVersion": "p5",
                    "versionCode": 5,
                    "shaCode": self.release.apk_hash,
                    "packageSize": 12,
                }
            ]
            self.state.update(releaseState=7, versionCode=5, versionId="p5")
            if self.lose == "attach":
                raise httpx.ReadTimeout("accepted but response lost", request=req)
            body = {"pkgVersion": ["p5"]}
        elif path.endswith("app-submit"):
            assert req.url.params["releaseType"] == "1" and "releaseTime" not in req.url.params
            self.submissions += 1
            self.state["releaseState"] = 4
            body = {}
        else:
            pytest.fail(f"unexpected {req.method} {path}")
        return httpx.Response(200, json={"ret": {"code": 0}, **body})


def release_file(tmp_path: Path) -> tuple[Any, Path]:
    import hashlib

    apk = tmp_path / "app.apk"
    apk.write_bytes(b"verified-apk")
    return d.Release.parse(
        manifest(apkSha256=hashlib.sha256(apk.read_bytes()).hexdigest(), apkSize=12)
    ), apk


def test_huawei_full_cycle_waits_for_parsing_and_published_version(tmp_path: Path) -> None:
    r, apk = release_file(tmp_path)
    store, j = HuaweiStore(r), d.MemoryJournal()
    api = d.AppGallery(
        httpx.Client(transport=httpx.MockTransport(store.handler)), "119465493", "jwt"
    )
    assert d.advance_appgallery(api, r, apk, j, now=1000)["status"] == "PROCESSING"
    assert d.advance_appgallery(api, r, apk, j, now=1050)["status"] == "PROCESSING"
    assert store.submissions == 0
    assert d.advance_appgallery(api, r, apk, j, now=1121)["status"] == "IN_REVIEW"
    store.state.update(releaseState=0, onShelfVersionCode=5, onShelfVersionId="p5")
    assert d.advance_appgallery(api, r, apk, j, now=1200)["status"] == "PUBLISHED"
    assert (store.uploads, store.attachments, store.submissions) == (1, 1, 1)


@pytest.mark.parametrize("lost", ["upload", "attach"])
def test_huawei_ambiguous_writes_do_not_reupload(tmp_path: Path, lost: str) -> None:
    r, apk = release_file(tmp_path)
    store, j = HuaweiStore(r, lose=lost), d.MemoryJournal()
    api = d.AppGallery(
        httpx.Client(transport=httpx.MockTransport(store.handler)), "119465493", "jwt"
    )
    with pytest.raises(d.DeliveryError, match="UNKNOWN"):
        d.advance_appgallery(api, r, apk, j, now=1000)
    if lost == "upload":
        with pytest.raises(d.DeliveryError, match="UNKNOWN"):
            d.advance_appgallery(api, r, apk, j, now=1200)
    else:
        assert d.advance_appgallery(api, r, apk, j, now=1200)["status"] == "IN_REVIEW"
        assert store.attachments == 1
    assert store.uploads == 1


@pytest.mark.parametrize(
    ("response", "expected"),
    [("refused", "STORE"), ("lost", "UNKNOWN"), ("malformed", "CONTRACT")],
)
def test_huawei_failed_attachment_stays_an_error_until_matching_package_is_observed(
    tmp_path: Path, response: str, expected: str
) -> None:
    r, apk = release_file(tmp_path)
    store, journal = HuaweiStore(r), d.MemoryJournal()
    attempts = 0

    def handler(req: httpx.Request) -> httpx.Response:
        nonlocal attempts
        if req.url.path.endswith("app-file-info"):
            attempts += 1
            if response == "lost":
                raise httpx.ReadTimeout("uncertain attachment", request=req)
            return httpx.Response(
                200,
                json={"ret": {"code": 1}}
                if response == "refused"
                else {"ret": {"code": 0}, "pkgVersion": None},
            )
        return store.handler(req)

    api = d.AppGallery(httpx.Client(transport=httpx.MockTransport(handler)), "119465493", "jwt")
    states = []
    for now in (1000, 1200, 1400):
        state = d.each_store(
            ["appgallery"],
            lambda _, now=now, journal=journal: d.advance_appgallery(api, r, apk, journal, now=now),
        )["appgallery"]
        states.append(state)
        assert d.record_transition(journal, "appgallery", state)["sequence"] == 1
        # Reload persisted JSON as a later workflow invocation would.
        records = json.loads(json.dumps(journal.records))
        journal = d.MemoryJournal()
        journal.records.update(records)
    assert [state["status"] for state in states] == [expected, expected, expected]
    assert states[0] == states[1] == states[2]
    assert (store.uploads, attempts, store.submissions) == (1, 1, 0)
    assert store.packages == []

    # A real package with the approved hash, size and candidate ID resolves the error.
    store.packages = [
        {"pkgVersion": "p5", "versionCode": 5, "shaCode": r.apk_hash, "packageSize": 12}
    ]
    store.state.update(releaseState=4, versionCode=5, versionId="p5")
    assert d.advance_appgallery(api, r, apk, journal, now=1600)["status"] == "IN_REVIEW"
    assert (store.uploads, attempts, store.submissions) == (1, 1, 0)


def test_huawei_attachment_intent_without_receipt_is_not_healthy_processing(tmp_path: Path) -> None:
    r, apk = release_file(tmp_path)
    store, journal = HuaweiStore(r), d.MemoryJournal()
    journal.put_once("appgallery-attach-intent", {"manifest": r.digest, "objectId": "object.apk"})
    api = d.AppGallery(
        httpx.Client(transport=httpx.MockTransport(store.handler)), "119465493", "jwt"
    )
    for _ in range(3):
        with pytest.raises(d.DeliveryError, match="UNKNOWN"):
            d.advance_appgallery(api, r, apk, journal, now=1200)
    assert store.uploads == store.attachments == 0


class RussianStore:
    def __init__(self, *, lose_upload: bool = False) -> None:
        self.created = False
        self.packages: list[dict[str, Any]] = []
        self.raw = "DRAFT"
        self.uploads = self.submissions = 0
        self.lose_upload = lose_upload

    def handler(self, req: httpx.Request) -> httpx.Response:
        assert req.headers["public-token"] == "token"
        path = req.url.path
        if path.endswith("/version") and req.method == "POST":
            body = json.loads(req.content)
            assert body["publishType"] == "INSTANTLY" and body["partialValue"] == 100
            self.created = True
            result: Any = 9
        elif path.endswith("/apk"):
            self.uploads += 1
            assert req.url.params["isMainApk"] == "true"
            assert req.url.params["servicesType"] == "Unknown"
            assert b"verified-apk" in req.content
            self.packages = [
                {"verCode": 5, "verName": "1.0.0", "apkUid": "uid5", "size": 12, "isMainApk": True}
            ]
            if self.lose_upload:
                raise httpx.ReadTimeout("accepted but response lost", request=req)
            result = None
        elif path.endswith("/commit"):
            self.submissions += 1
            self.raw = "MODERATION"
            result = None
        elif path.endswith("/version"):
            versions = [{"versionId": 1, "versionCode": 1, "versionStatus": "ACTIVE"}]
            if self.created:
                versions.append(
                    {
                        "versionId": 9,
                        "versionCode": 5,
                        "versionName": "1.0.0",
                        "versionStatus": self.raw,
                        "partialValue": 100,
                        "publishType": "INSTANTLY",
                    }
                )
            result = {
                "content": versions,
                "pageNumber": 0,
                "pageSize": 100,
                "totalElements": len(versions),
                "totalPages": 1,
            }
        elif path.endswith("/9"):
            result = {"versionId": 9, "packages": self.packages}
        else:
            pytest.fail(f"unexpected {req.method} {path}")
        return httpx.Response(200, json={"code": "OK", "body": result})


def test_rustore_full_cycle_does_not_confuse_upload_with_publication(tmp_path: Path) -> None:
    r, apk = release_file(tmp_path)
    store, j = RussianStore(), d.MemoryJournal()
    api = d.RuStore(httpx.Client(transport=httpx.MockTransport(store.handler)), "token")
    assert d.advance_rustore(api, r, apk, j)["status"] == "SUBMITTED"
    assert d.advance_rustore(api, r, apk, j)["status"] == "IN_REVIEW"
    store.raw = "ACTIVE"
    assert d.advance_rustore(api, r, apk, j)["status"] == "PUBLISHED"
    assert (store.uploads, store.submissions) == (1, 1)


def test_rustore_lost_upload_receipt_remains_unknown_without_second_upload(tmp_path: Path) -> None:
    r, apk = release_file(tmp_path)
    store, j = RussianStore(lose_upload=True), d.MemoryJournal()
    api = d.RuStore(httpx.Client(transport=httpx.MockTransport(store.handler)), "token")
    for _ in range(2):
        with pytest.raises(d.DeliveryError, match="UNKNOWN"):
            d.advance_rustore(api, r, apk, j)
    assert store.uploads == 1 and store.submissions == 0


def test_github_lost_checkpoint_response_reconciles_exact_bytes() -> None:
    assets: dict[str, dict[str, Any]] = {}
    payloads: dict[int, bytes] = {}
    writes = 0

    def handler(req: httpx.Request) -> httpx.Response:
        nonlocal writes
        if req.url.host == "uploads.github.com":
            writes += 1
            data = req.content
            assets[req.url.params["name"]] = {
                "id": 1,
                "name": req.url.params["name"],
                "size": len(data),
                "uploader": {"login": "github-actions[bot]"},
            }
            payloads[1] = data
            raise httpx.ReadTimeout("response lost", request=req)
        if req.url.path.endswith("/releases/9/assets"):
            return httpx.Response(200, json=list(assets.values()))
        if req.url.path.endswith("/releases/assets/1"):
            return httpx.Response(200, content=payloads[1])
        pytest.fail(f"unexpected {req.method} {req.url.path}")

    github = d.GitHub(httpx.Client(transport=httpx.MockTransport(handler)), "owner/repo", "token")
    j = d.ReleaseJournal(github, d.Release.parse(manifest()), 9)
    j.put_once("rustore-upload-intent", {"hash": "c" * 64})
    assert j.get("rustore-upload-intent") == {"hash": "c" * 64}
    j.put_once("rustore-upload-intent", {"hash": "c" * 64})
    assert writes == 1
    with pytest.raises(d.DeliveryError, match="MISMATCH"):
        j.put_once("rustore-upload-intent", {"hash": "e" * 64})


@pytest.mark.parametrize("raw", [2, 6, 9, 10])
def test_huawei_removed_states_cannot_confirm_public_availability(raw: int) -> None:
    r = d.Release.parse(manifest())
    with pytest.raises(d.DeliveryError):
        d.huawei_observation(
            r,
            {"releaseState": raw, "onShelfVersionCode": 5, "onShelfVersionId": "p"},
            [{"pkgVersion": "p", "versionCode": 5, "packageSize": 10, "shaCode": r.apk_hash}],
        )


def test_approval_digest_survives_artifact_retention() -> None:
    r = d.Release.parse(manifest())
    run = {
        "id": 123,
        "path": ".github/workflows/store-delivery.yml",
        "head_branch": "main",
        "event": "workflow_dispatch",
        "head_sha": "b" * 40,
        "repository": {"full_name": "owner/repo"},
        "head_repository": {"full_name": "owner/repo"},
    }
    d.check_approval(
        r,
        "owner/repo",
        run,
        [{"name": f"Approve Android public release ({r.digest})", "conclusion": "success"}],
        [],
    )


def test_telegram_unchanged_state_is_quiet_and_lost_response_is_not_resent() -> None:
    from types import SimpleNamespace

    r = d.Release.parse(manifest())
    calls = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(json.loads(req.content))
        return httpx.Response(200, json={"ok": True})

    j = d.MemoryJournal()
    j.release, j.release_id = r, 9
    j.github = SimpleNamespace(client=httpx.Client(transport=httpx.MockTransport(handler)))
    report = {
        "releaseId": 9,
        "manifestSha256": r.digest,
        "runUrl": "https://github.com/owner/repo/actions/runs/1",
        "stores": {"rustore": {"status": "IN_REVIEW"}, "appgallery": {"status": "PUBLISHED"}},
    }
    for store, state in report["stores"].items():
        state["transitionId"] = d.record_transition(j, store, state)["sequence"]
    for _ in range(2):
        assert all(
            x["status"] == "SENT" for x in d.notify_report(j, report, "token", "owner").values()
        )
    assert len(calls) == 2
    assert all(x["chat_id"] == "owner" for x in calls)

    def lost(req: httpx.Request) -> httpx.Response:
        calls.append(json.loads(req.content))
        raise httpx.ReadTimeout("sent but lost response", request=req)

    j.github.client = httpx.Client(transport=httpx.MockTransport(lost))
    report["stores"] = {"rustore": {"status": "PUBLISHED"}}
    report["stores"]["rustore"]["transitionId"] = d.record_transition(
        j, "rustore", {"status": "PUBLISHED"}
    )["sequence"]
    for _ in range(2):
        assert d.notify_report(j, report, "token", "owner")["rustore"]["status"] == "UNKNOWN"
    assert len(calls) == 3


def test_rustore_approved_instant_release_is_processing_until_active(tmp_path: Path) -> None:
    r, apk = release_file(tmp_path)
    store, j = RussianStore(), d.MemoryJournal()
    api = d.RuStore(httpx.Client(transport=httpx.MockTransport(store.handler)), "token")
    d.advance_rustore(api, r, apk, j)
    store.raw = "READY_FOR_PUBLICATION"
    assert d.advance_rustore(api, r, apk, j)["status"] == "PROCESSING"
    assert store.uploads == 1 and store.submissions == 1


def test_manual_github_release_cannot_supply_store_journal() -> None:
    def handler(req: httpx.Request) -> httpx.Response:
        pytest.fail("manual release must fail before loading any assets")

    github = d.GitHub(httpx.Client(transport=httpx.MockTransport(handler)), "owner/repo", "token")
    with pytest.raises(d.DeliveryError, match="GATE"):
        d.load_journal(github, {"id": 9, "author": {"login": "owner"}}, Path())


@pytest.mark.parametrize("extra", [True, False])
def test_release_approval_accepts_only_the_owner_user(
    monkeypatch: pytest.MonkeyPatch, extra: bool
) -> None:
    api = d.GitHub(httpx.Client(), "owner/repo", "token")
    reviewers = [{"type": "User" if extra else "Team", "reviewer": {"login": "owner"}}]
    if extra:
        reviewers.append({"type": "User", "reviewer": {"login": "someone-else"}})
    monkeypatch.setattr(
        api,
        "call",
        lambda *_: {
            "can_admins_bypass": False,
            "protection_rules": [{"type": "required_reviewers", "reviewers": reviewers}],
        },
    )
    monkeypatch.setattr(api, "pages", lambda *_: [{"name": "main", "type": "branch"}])
    with pytest.raises(d.DeliveryError, match="CONFIG"):
        api.require_protected_approval()


def test_one_broken_journal_does_not_block_or_discard_other_releases(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from types import SimpleNamespace

    def load(_api: Any, remote: dict[str, Any], _repo: Path) -> Any:
        if remote["id"] == 1:
            raise d.DeliveryError("GATE", "incomplete first journal")
        return SimpleNamespace(release_id=remote["id"], get=lambda _: None)

    monkeypatch.setattr(d, "load_journal", load)
    monkeypatch.setattr(
        d,
        "reconcile_journal",
        lambda j, _: {"releaseId": j.release_id, "stores": {"rustore": {"status": "PUBLISHED"}}},
    )
    out = tmp_path / "report.json"
    result = d.reconcile_releases(None, [{"id": 1}, {"id": 2}], tmp_path, out)
    assert result["releases"][0]["releaseId"] == 2
    assert result["errors"][0]["releaseId"] == 1
    assert json.loads(out.read_text()) == result


def test_store_publication_proof_survives_later_store_changes() -> None:
    j = d.MemoryJournal()
    first = d.advance_with_proof(j, "rustore", lambda: {"status": "PUBLISHED", "versionId": 9})
    assert first["status"] == "PUBLISHED" and first["observedAt"]
    second = d.advance_with_proof(j, "rustore", lambda: pytest.fail("must preserve proven success"))
    assert second["status"] == "PUBLISHED" and second["previouslyConfirmed"] is True
    assert second["observedAt"] == first["observedAt"]


def test_unchanged_polls_do_not_exhaust_release_assets() -> None:
    j = d.MemoryJournal()
    for _ in range(1100):
        event = d.record_transition(j, "rustore", {"status": "IN_REVIEW", "rawState": "MODERATION"})
        assert event["sequence"] == 1
    assert len(j.records) == 1
    assert d.record_transition(j, "rustore", {"status": "STORE"})["sequence"] == 2
    assert (
        d.record_transition(j, "rustore", {"status": "IN_REVIEW", "rawState": "MODERATION"})[
            "sequence"
        ]
        == 3
    )


def test_github_asset_timeout_is_typed_redacted_and_bounded() -> None:
    calls = []

    def fail(req: httpx.Request) -> httpx.Response:
        calls.append(req)
        raise httpx.ReadTimeout("secret signed URL", request=req)

    github = d.GitHub(httpx.Client(transport=httpx.MockTransport(fail)), "owner/repo", "token")
    with pytest.raises(d.DeliveryError) as error:
        github.asset_bytes({"id": 1, "uploader": {"login": "github-actions[bot]"}})
    assert error.value.code == "STORE" and "secret" not in str(error.value)
    assert len(calls) == 3


def test_telegram_reports_a_return_to_a_previous_error_status() -> None:
    from types import SimpleNamespace

    calls = []

    def send(req: httpx.Request) -> httpx.Response:
        calls.append(req)
        return httpx.Response(200, json={"ok": True})

    r, j = d.Release.parse(manifest()), d.MemoryJournal()
    j.release, j.release_id = r, 9
    j.github = SimpleNamespace(client=httpx.Client(transport=httpx.MockTransport(send)))
    for sequence, status in enumerate(["STORE", "IN_REVIEW", "STORE"], 1):
        j.put_once(
            f"state-rustore-{sequence:06d}",
            {
                "sequence": sequence,
                "store": "rustore",
                "state": {"status": status},
                "observedAt": "2026-11-03T00:00:00Z",
                "runUrl": "https://github.com/owner/repo/actions/runs/1",
            },
        )
        report = {
            "releaseId": 9,
            "manifestSha256": r.digest,
            "runUrl": "https://github.com/owner/repo/actions/runs/1",
            "stores": {"rustore": {"status": status, "transitionId": sequence}},
        }
        d.notify_report(j, report, "token", "owner")
    assert len(calls) == 3


@pytest.mark.parametrize(
    "host_headers",
    [
        {},
        {"Host": "evil.example"},
        {"Host": "tf.obs.ru-northwest-2.myhuaweicloud.com", "hOsT": "evil.example"},
    ],
)
def test_huawei_upload_host_is_verified_before_intent(
    tmp_path: Path, host_headers: dict[str, str]
) -> None:
    r, apk = release_file(tmp_path)
    store, j = HuaweiStore(r), d.MemoryJournal()

    def handler(req: httpx.Request) -> httpx.Response:
        response = store.handler(req)
        if req.url.path.endswith("upload-url/for-obs"):
            body = response.json()
            headers = body["urlInfo"]["headers"]
            headers.pop("Host", None)
            headers.update(host_headers)
            return httpx.Response(200, json=body)
        return response

    api = d.AppGallery(httpx.Client(transport=httpx.MockTransport(handler)), "119465493", "jwt")
    with pytest.raises(d.DeliveryError):
        d.advance_appgallery(api, r, apk, j, now=1000)
    assert store.uploads == 0 and j.get("appgallery-upload-intent") is None


def test_huawei_parsing_interval_starts_when_upload_finishes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    r, apk = release_file(tmp_path)
    store, j, clock = HuaweiStore(r), d.MemoryJournal(), [1000]
    monkeypatch.setattr(d.time, "time", lambda: clock[0])

    def handler(req: httpx.Request) -> httpx.Response:
        response = store.handler(req)
        if req.url.host != "connect-api.cloud.huawei.com":
            clock[0] = 1120
        return response

    api = d.AppGallery(httpx.Client(transport=httpx.MockTransport(handler)), "119465493", "jwt")
    d.advance_appgallery(api, r, apk, j)
    assert j.get("appgallery-upload-done")["at"] == 1120
    clock[0] = 1130
    assert d.advance_appgallery(api, r, apk, j)["status"] == "PROCESSING"
    assert store.submissions == 0


def test_malformed_provider_url_is_a_redacted_typed_error() -> None:
    with pytest.raises(d.DeliveryError) as error:
        d.huawei_upload_url("https://x.myhuaweicloud.com:secret-signature/file")
    assert "secret-signature" not in str(error.value)

    def redirect(_req: httpx.Request) -> httpx.Response:
        return httpx.Response(
            302,
            headers={
                "location": "https://release-assets.githubusercontent.com:secret-signature/file"
            },
        )

    github = d.GitHub(httpx.Client(transport=httpx.MockTransport(redirect)), "owner/repo", "token")
    with pytest.raises(d.DeliveryError) as error:
        github.asset_bytes({"id": 1, "uploader": {"login": "github-actions[bot]"}})
    assert "secret-signature" not in str(error.value)


class ReleaseAssetServer:
    """HTTP boundary for corrupt journals and durable operational notifications."""

    def __init__(self, release: Any, apk: bytes) -> None:
        self.release = release
        self.remote = {
            "id": 9,
            "author": {"login": "github-actions[bot]"},
            "tag_name": "android-store-5",
            "body": release.digest,
            "target_commitish": release.value["sourceSha"],
        }
        self.assets: dict[str, tuple[int, bytes]] = {}
        self.next_id = 1
        self.messages: list[str] = []
        self.lose_notification = False
        self.add("manifest.json", d.canonical(release.value))
        self.add("approved.apk", apk)
        self.huawei = HuaweiStore(release)
        self.huawei.packages = [
            {"pkgVersion": "p5", "versionCode": 5, "shaCode": release.apk_hash, "packageSize": 12}
        ]
        self.huawei.state.update(releaseState=4, versionCode=5, versionId="p5")

    def add(self, name: str, data: bytes) -> None:
        assert name not in self.assets
        self.assets[name] = (self.next_id, data)
        self.next_id += 1

    def handler(self, req: httpx.Request) -> httpx.Response:
        if req.url.host == "api.telegram.org":
            assert req.method == "POST"
            message = json.loads(req.content)
            assert message["chat_id"] == "owner"
            self.messages.append(message["text"])
            if self.lose_notification:
                raise httpx.ReadTimeout("notification response lost", request=req)
            return httpx.Response(200, json={"ok": True})
        if req.url.host == "connect-api.cloud.huawei.com":
            return self.huawei.handler(req)
        path = req.url.path.removeprefix("/repos/owner/repo/")
        if req.method == "POST" and req.url.host == "uploads.github.com":
            assert path == "releases/9/assets"
            self.add(req.url.params["name"], req.content)
            return httpx.Response(201, json={})
        assert req.method == "GET" and req.url.host == "api.github.com"
        if path == "releases/9":
            return httpx.Response(200, json=self.remote)
        if path == "releases/9/assets":
            return httpx.Response(
                200,
                json=[
                    {
                        "id": aid,
                        "name": name,
                        "size": len(data),
                        "uploader": {"login": "github-actions[bot]"},
                    }
                    for name, (aid, data) in self.assets.items()
                ],
            )
        if path.startswith("releases/assets/"):
            aid = int(path.split("/")[-1])
            return httpx.Response(200, content=next(b for i, b in self.assets.values() if i == aid))
        pytest.fail(f"unexpected journal HTTP request: {req.method} {path}")


def test_inspection_reads_existing_rustore_draft_without_repeating_ambiguous_upload(
    tmp_path: Path,
) -> None:
    r, _ = release_file(tmp_path)
    journal = d.MemoryJournal()
    journal.put_once("rustore-create-done", {"versionId": 9})
    journal.put_once("rustore-upload-intent", {"manifest": r.digest, "versionId": 9})
    before = dict(journal.records)
    calls = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append((req.method, req.url.path))
        assert req.method == "GET" and req.url.path.endswith("/version/9")
        return httpx.Response(
            200,
            json={
                "code": "OK",
                "body": {
                    "versionId": 9,
                    "versionStatus": "DRAFT",
                    "versionCode": None,
                    "packages": [],
                    "fileUids": [
                        {
                            "fileUid": "uid5",
                            "type": "APK",
                            "fileUrl": "https://static.rustore.ru/file?signature=secret-download",
                        }
                    ],
                },
            },
        )

    api = d.RuStore(httpx.Client(transport=httpx.MockTransport(handler)), "token")
    result = d.inspect_store(api, r, tmp_path / "app.apk", journal)
    assert result["status"] == "OBSERVED"
    assert result["detail"]["packages"] == []
    assert result["detail"]["versionCode"] is None
    assert result["journal"]["uploadIntent"] is True
    assert result["journal"]["uploadDone"] is False
    assert result["detail"]["fileUids"][0]["url"]["host"] == "static.rustore.ru"
    assert "secret-download" not in json.dumps(result)
    assert journal.records == before and len(calls) == 1


@pytest.mark.parametrize("headers", [None, {"Authorization": ["secret-header"]}])
def test_inspection_keeps_safe_huawei_authority_when_headers_are_missing_or_malformed(
    tmp_path: Path, headers: Any
) -> None:
    r, apk = release_file(tmp_path)
    store, journal = HuaweiStore(r), d.MemoryJournal()

    def handler(req: httpx.Request) -> httpx.Response:
        assert req.method == "GET"
        response = store.handler(req)
        if req.url.path.endswith("upload-url/for-obs"):
            body = response.json()
            body["urlInfo"].update(url="https://unknown.example/file?secret-query", headers=headers)
            return httpx.Response(200, json=body)
        return response

    api = d.AppGallery(httpx.Client(transport=httpx.MockTransport(handler)), "119465493", "jwt")
    result = d.inspect_store(api, r, apk, journal)
    assert result["status"] == "CONTRACT"
    assert result["upload"]["url"]["host"] == "unknown.example"
    assert "secret-" not in json.dumps(result)
    assert not journal.records and store.uploads == store.attachments == store.submissions == 0


def test_inspect_cli_checks_approval_and_redacts_huawei_url_without_any_store_write(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    r, apk = release_file(tmp_path)
    r = d.Release.parse({**r.value, "stores": ["appgallery"]})
    server = ReleaseAssetServer(r, apk.read_bytes())
    server.huawei.packages = []
    server.huawei.state.update(versionCode=1, versionId="old", releaseState=0)
    before = dict(server.assets)
    events = []

    def handler(req: httpx.Request) -> httpx.Response:
        assert req.method == "GET", "inspection must not write to GitHub or stores"
        events.append(req.url.host)
        response = server.handler(req)
        if req.url.path.endswith("upload-url/for-obs"):
            body = response.json()
            body["urlInfo"].update(
                url="https://unknown.example/object?signature=secret-query#secret-fragment",
                objectId="secret-object-id",
                headers={
                    "Host": "unknown.example",
                    "Authorization": "secret-upload-token",
                    "x-amz-content-sha256": r.apk_hash,
                },
            )
            return httpx.Response(200, json=body)
        return response

    client = httpx.Client(transport=httpx.MockTransport(handler))
    monkeypatch.setattr(d, "delivery_client", lambda: client)
    monkeypatch.setattr(d.GitHub, "prove", lambda *_: events.append("approval-proved"))
    monkeypatch.setattr(d, "store_api", lambda c, *_: d.AppGallery(c, "119465493", "jwt"))
    for name, value in {
        "GITHUB_REF": "refs/heads/main",
        "GITHUB_REPOSITORY": "owner/repo",
        "GH_TOKEN": "test",
    }.items():
        monkeypatch.setenv(name, value)
    out = tmp_path / "inspection.json"
    assert d.main(["inspect", "--release-id", "9", "--out", str(out)]) == 0
    result = json.loads(out.read_text())
    observed = result["stores"]["appgallery"]
    assert result["schema"] == "talentforge-android-store-inspection/1"
    assert result["manifestSha256"] == r.digest and result["readOnly"] is True
    assert observed["upload"]["url"]["host"] == "unknown.example"
    assert observed["upload"]["passesCurrentUrlGuard"] is False
    assert observed["upload"]["headerHostMatches"] is True
    assert observed["upload"]["sha256Matches"] is True
    assert "secret-" not in out.read_text()
    assert "unknown.example" not in events  # Inspect, never follow the returned URL.
    assert events.index("approval-proved") < events.index("connect-api.cloud.huawei.com")
    assert server.assets == before and server.messages == []
    assert server.huawei.uploads == server.huawei.attachments == server.huawei.submissions == 0


def test_inspect_requires_an_explicit_journal_id(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("GITHUB_REF", "refs/heads/main")
    monkeypatch.setenv("GITHUB_REPOSITORY", "owner/repo")
    monkeypatch.setenv("GH_TOKEN", "test")
    out = tmp_path / "inspection.json"
    assert d.main(["inspect", "--out", str(out)]) == 1
    assert json.loads(out.read_text())["status"] == "CONFIG"


@pytest.mark.parametrize("invalid", ["approval", "apk"])
def test_inspection_refuses_unproven_or_changed_approved_bytes_before_store_access(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, invalid: str
) -> None:
    r, apk = release_file(tmp_path)
    r = d.Release.parse({**r.value, "stores": ["appgallery"]})
    server = ReleaseAssetServer(r, apk.read_bytes())
    if invalid == "apk":
        aid, _ = server.assets["approved.apk"]
        server.assets["approved.apk"] = (aid, b"different-apk")
    before = dict(server.assets)
    client = httpx.Client(transport=httpx.MockTransport(server.handler))
    monkeypatch.setattr(d, "delivery_client", lambda: client)

    def prove(*_: Any) -> None:
        if invalid == "approval":
            raise d.DeliveryError("GATE", "protected approval did not succeed")

    def no_store(*_: Any) -> None:
        pytest.fail("unproven bytes must never reach store authentication or API")

    monkeypatch.setattr(d.GitHub, "prove", prove)
    monkeypatch.setattr(d, "store_api", no_store)
    for name, value in {
        "GITHUB_REF": "refs/heads/main",
        "GITHUB_REPOSITORY": "owner/repo",
        "GH_TOKEN": "test",
    }.items():
        monkeypatch.setenv(name, value)
    out = tmp_path / "inspection.json"
    assert d.main(["inspect", "--release-id", "9", "--out", str(out)]) == 1
    assert json.loads(out.read_text())["status"] == (
        "GATE" if invalid == "approval" else "MISMATCH"
    )
    assert server.assets == before and server.messages == []


@pytest.mark.parametrize("missing", ["approved.apk", "manifest.json"])
@pytest.mark.parametrize("lost_notification", [False, True])
def test_notify_cli_reports_journal_errors_without_silence_or_blind_resends(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, missing: str, lost_notification: bool
) -> None:
    r, apk = release_file(tmp_path)
    r = d.Release.parse({**r.value, "stores": ["appgallery"]})
    server = ReleaseAssetServer(r, apk.read_bytes())
    _, missing_bytes = server.assets.pop(missing)
    server.lose_notification = lost_notification
    real_client = httpx.Client

    def client(**kwargs: Any) -> httpx.Client:
        return real_client(transport=httpx.MockTransport(server.handler), **kwargs)

    monkeypatch.setattr(d.httpx, "Client", client)
    # Provenance itself has dedicated tests; journal parsing and HTTP remain real here.
    monkeypatch.setattr(d.GitHub, "prove", lambda *_: None)
    monkeypatch.setattr(d, "store_api", lambda c, *_: d.AppGallery(c, "119465493", "jwt"))
    for name, value in {
        "GITHUB_REF": "refs/heads/main",
        "GITHUB_REPOSITORY": "owner/repo",
        "GITHUB_RUN_ID": "123",
        "GH_TOKEN": "test",
        "TELEGRAM_BOT_TOKEN": "test-token",
        "TELEGRAM_CHAT_ID": "owner",
    }.items():
        monkeypatch.setenv(name, value)
    reports, notifications = tmp_path / "reports.json", tmp_path / "notifications.json"
    github = d.GitHub(client(), "owner/repo", "test")

    def poll_and_notify() -> tuple[dict[str, Any], int]:
        report = d.reconcile_releases(github, [server.remote], tmp_path, reports)
        code = d.main(["notify", "--reports", str(reports), "--out", str(notifications)])
        return report, code

    for _ in range(3):
        report, code = poll_and_notify()
        assert report["errors"][0]["status"] == "GATE"
        assert len(server.messages) == 1
        assert code == 1  # A journal failure must not be turned into a green run.
        result = json.loads(notifications.read_text())
        assert result["errors"] == report["errors"]
        assert result["journalAlerts"][0]["status"] == ("UNKNOWN" if lost_notification else "SENT")
    assert "9" in server.messages[0] and "GATE" in server.messages[0]
    assert missing.split(".")[0] in server.messages[0].lower()

    # Repaired journal: store notification is delivered and the operational error clears.
    server.lose_notification = False
    server.add(missing, missing_bytes)
    report, code = poll_and_notify()
    assert code == 0 and report["errors"] == []
    assert len(server.messages) == 2 and "IN_REVIEW" in server.messages[1]

    # The same failure returning after recovery earns a new operational alert.
    server.assets.pop(missing)
    report, code = poll_and_notify()
    assert code == 1 and report["errors"]
    assert len(server.messages) == 3 and "GATE" in server.messages[2]
    assert server.huawei.uploads == server.huawei.attachments == server.huawei.submissions == 0


class DeferredRussianStore(RussianStore):
    """RuStore documents that staged APKs appear only after commit."""

    def __init__(self, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.staged: list[dict[str, Any]] = []
        self.partial: Any = -1

    def handler(self, req: httpx.Request) -> httpx.Response:
        if req.url.path.endswith("/apk"):
            response = super().handler(req)
            self.staged, self.packages = self.packages, []
            return response
        if req.url.path.endswith("/commit"):
            self.packages = self.staged
        response = super().handler(req)
        result = response.json()
        if req.url.path.endswith("/9"):
            result["body"].update(
                versionStatus=self.raw,
                publishType="INSTANTLY",
                partialValue=self.partial,
                versionName=None if self.raw == "DRAFT" else "1.0.0",
            )
        if req.url.path.endswith("/version") and req.method == "GET":
            result["body"]["content"] = [
                v for v in result["body"]["content"] if v["versionId"] != 9 or self.raw != "DRAFT"
            ]
            result["body"]["totalElements"] = len(result["body"]["content"])
            for v in result["body"]["content"]:
                v["partialValue"] = self.partial
        return httpx.Response(200, json=result)


def test_rustore_commits_acknowledged_staged_apk_and_binds_uid_after_commit(tmp_path: Path) -> None:
    r, apk = release_file(tmp_path)
    store, j = DeferredRussianStore(), d.MemoryJournal()
    api = d.RuStore(httpx.Client(transport=httpx.MockTransport(store.handler)), "token")
    assert d.advance_rustore(api, r, apk, j)["status"] == "SUBMITTED"
    assert j.get("rustore-upload-done") == {
        "versionId": 9,
        "sha256": r.apk_hash,
        "responseCode": "OK",
    }
    assert d.advance_rustore(api, r, apk, j)["status"] == "IN_REVIEW"
    assert j.get("rustore-package-done")["apkUid"] == "uid5"
    store.raw = "ACTIVE"
    assert d.advance_rustore(api, r, apk, j)["status"] == "PUBLISHED"
    assert (store.uploads, store.submissions) == (1, 1)


@pytest.mark.parametrize("partial", [-2, 0, 50, 101, "-1", True, None])
def test_rustore_invalid_rollout_cannot_submit_staged_apk(tmp_path: Path, partial: Any) -> None:
    r, apk = release_file(tmp_path)
    store, j = DeferredRussianStore(), d.MemoryJournal()
    store.partial = partial
    api = d.RuStore(httpx.Client(transport=httpx.MockTransport(store.handler)), "token")
    with pytest.raises(d.DeliveryError):
        d.advance_rustore(api, r, apk, j)
    assert store.submissions == 0


def test_rustore_staged_upload_transport_loss_never_commits_or_reuploads(tmp_path: Path) -> None:
    r, apk = release_file(tmp_path)
    store, j = DeferredRussianStore(lose_upload=True), d.MemoryJournal()
    api = d.RuStore(httpx.Client(transport=httpx.MockTransport(store.handler)), "token")
    for _ in range(2):
        with pytest.raises(d.DeliveryError, match="UNKNOWN"):
            d.advance_rustore(api, r, apk, j)
    assert (store.uploads, store.submissions) == (1, 0)


@pytest.mark.parametrize("sha", ["C" * 64, "c" * 64])
def test_huawei_digest_hex_case_does_not_change_approved_bytes(sha: str) -> None:
    r = d.Release.parse(manifest())
    assert (
        d.huawei_observation(
            r,
            {"releaseState": 0, "onShelfVersionCode": 5, "onShelfVersionId": "p"},
            [{"pkgVersion": "p", "versionCode": 5, "packageSize": 10, "shaCode": sha}],
        )["status"]
        == "PUBLISHED"
    )


@pytest.mark.parametrize("sha", [None, 123, "c" * 63, "g" * 64, " c" * 32])
def test_huawei_digest_requires_exact_hex_wire_string(sha: Any) -> None:
    r = d.Release.parse(manifest())
    with pytest.raises(d.DeliveryError, match="CONTRACT"):
        d.huawei_observation(
            r,
            {"releaseState": 0},
            [{"pkgVersion": "p", "versionCode": 5, "packageSize": 10, "shaCode": sha}],
        )


def test_huawei_observed_russian_bucket_is_pinned_without_trusting_entire_suffix() -> None:
    host = "nsp-appgallery-agcfs-drru.obs.ru-northwest-2.myhuaweicloud.ru"
    assert d.huawei_upload_url("https://" + host + "/object") == "https://" + host + "/object"
    for bad in (
        "https://other.obs.ru-northwest-2.myhuaweicloud.ru/object",
        "https://" + host + ".evil.example/object",
        "https://evil." + host + "/object",
        "https://user@" + host + "/object",
        "https://" + host + ":444/object",
        "http://" + host + "/object",
    ):
        with pytest.raises(d.DeliveryError):
            d.huawei_upload_url(bad)


def legacy_recovery_fixture(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[Any, ...]:
    from types import SimpleNamespace

    original, apk = release_file(tmp_path)
    r = d.Release.parse(
        {
            **original.value,
            "workflowSha": d.LEGACY_RUSTORE_WORKFLOW,
            "buildRunId": d.LEGACY_RUSTORE_RUN,
        }
    )
    # Only the test APK/manifest and fake server's version differ from real pins.
    monkeypatch.setattr(d, "LEGACY_RUSTORE_MANIFEST", r.digest)
    monkeypatch.setattr(d, "LEGACY_RUSTORE_VERSION", 9)
    j = d.MemoryJournal()
    j.release, j.release_id = r, d.LEGACY_RUSTORE_RELEASE
    j.github = SimpleNamespace(repository="AlexeyKravtsov95/TalentForge")
    j.put_once("rustore-create-intent", {"manifest": r.digest})
    j.put_once("rustore-create-done", {"versionId": 9})
    j.put_once("rustore-upload-intent", {"manifest": r.digest, "versionId": 9})
    j.put_once(
        "state-rustore-000001",
        {
            "observedAt": "2026-11-03T19:20:45Z",
            "runUrl": f"https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/{d.LEGACY_RUSTORE_RUN}",
            "sequence": 1,
            "store": "rustore",
            "state": {
                "detail": "CONFLICT: RuStore must have exactly one universal APK",
                "status": "CONFLICT",
            },
        },
    )
    store = DeferredRussianStore()
    store.created = True
    store.staged = [
        {"verCode": 5, "verName": "1.0.0", "apkUid": "uid5", "size": 12, "isMainApk": True}
    ]
    api = d.RuStore(httpx.Client(transport=httpx.MockTransport(store.handler)), "token")
    return r, apk, j, store, api


def test_exact_legacy_postcondition_proof_recovers_ack_without_upload_or_asset_edits(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    r, apk, j, store, api = legacy_recovery_fixture(tmp_path, monkeypatch)
    original = json.loads(json.dumps(j.records))
    d.recover_rustore_ack(j)
    proof = j.get("rustore-upload-ack-recovered")
    assert proof["proof"] == "trusted-upload-OK-postcondition-failure"
    assert "responseCode" not in proof and "apkUid" not in proof
    assert d.advance_rustore(api, r, apk, j)["status"] == "SUBMITTED"
    d.recover_rustore_ack(j)
    assert d.advance_rustore(api, r, apk, j)["status"] == "IN_REVIEW"
    store.raw = "ACTIVE"
    assert d.advance_rustore(api, r, apk, j)["status"] == "PUBLISHED"
    assert (store.uploads, store.submissions) == (0, 1)
    assert all(j.get(k) == v for k, v in original.items())
    assert j.get("rustore-upload-done") is None


@pytest.mark.parametrize(
    "changed",
    [
        "create-intent",
        "create-done",
        "upload-intent",
        "state",
        "time",
        "run",
        "repository",
        "release-id",
        "workflow",
        "build-run",
    ],
)
def test_legacy_recovery_refuses_any_other_evidence_or_provenance(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, changed: str
) -> None:
    r, _apk, j, store, _api = legacy_recovery_fixture(tmp_path, monkeypatch)
    if changed in ("create-intent", "create-done", "upload-intent"):
        j.records["rustore-" + changed] = {"unrelated": True}
    elif changed == "state":
        j.records["state-rustore-000001"]["state"] = {
            "status": "UNKNOWN",
            "detail": "POST transport failure",
        }
    elif changed == "time":
        j.records["state-rustore-000001"]["observedAt"] = "2026-11-04T00:00:00Z"
    elif changed == "run":
        j.records["state-rustore-000001"]["runUrl"] = "https://github.com/other/repo/actions/runs/1"
    elif changed == "repository":
        j.github.repository = "other/repo"
    elif changed == "release-id":
        j.release_id += 1
    else:
        update = {"workflowSha": "e" * 40} if changed == "workflow" else {"buildRunId": 999}
        j.release = d.Release.parse({**r.value, **update})
        monkeypatch.setattr(d, "LEGACY_RUSTORE_MANIFEST", j.release.digest)
    with pytest.raises(d.DeliveryError):
        d.recover_rustore_ack(j)
    assert j.get("rustore-upload-ack-recovered") is None
    assert (store.uploads, store.submissions) == (0, 0)


def test_other_release_transport_ambiguity_has_no_recovery_rule(tmp_path: Path) -> None:
    from types import SimpleNamespace

    r, apk = release_file(tmp_path)
    j = d.MemoryJournal()
    j.release, j.release_id = r, d.LEGACY_RUSTORE_RELEASE
    j.github = SimpleNamespace(repository="AlexeyKravtsov95/TalentForge")
    j.put_once("rustore-create-done", {"versionId": 9})
    j.put_once("rustore-upload-intent", {"manifest": r.digest, "versionId": 9})
    store = DeferredRussianStore()
    store.created = True
    api = d.RuStore(httpx.Client(transport=httpx.MockTransport(store.handler)), "token")
    d.recover_rustore_ack(j)
    with pytest.raises(d.DeliveryError, match="UNKNOWN"):
        d.advance_rustore(api, r, apk, j)
    assert j.get("rustore-upload-ack-recovered") is None
    assert (store.uploads, store.submissions) == (0, 0)


def test_rustore_package_uid_change_after_submission_is_refused(tmp_path: Path) -> None:
    r, apk = release_file(tmp_path)
    store, j = DeferredRussianStore(), d.MemoryJournal()
    api = d.RuStore(httpx.Client(transport=httpx.MockTransport(store.handler)), "token")
    d.advance_rustore(api, r, apk, j)
    d.advance_rustore(api, r, apk, j)
    store.packages[0]["apkUid"] = "another-package"
    store.raw = "ACTIVE"
    with pytest.raises(d.DeliveryError, match="MISMATCH"):
        d.advance_rustore(api, r, apk, j)
    assert store.uploads == store.submissions == 1


def test_rustore_foreign_draft_blocks_commit_after_ack(tmp_path: Path) -> None:
    r, apk = release_file(tmp_path)
    store, j = DeferredRussianStore(), d.MemoryJournal()
    store.created = True
    j.put_once("rustore-create-done", {"versionId": 9})
    j.put_once("rustore-upload-intent", {"manifest": r.digest, "versionId": 9})
    j.put_once("rustore-upload-done", {"versionId": 9, "sha256": r.apk_hash, "responseCode": "OK"})

    def handler(req: httpx.Request) -> httpx.Response:
        response = store.handler(req)
        if req.method == "GET" and req.url.path.endswith("/version"):
            body = response.json()
            body["body"]["content"].append(
                {"versionId": 99, "versionCode": 4, "versionStatus": "DRAFT"}
            )
            body["body"]["totalElements"] += 1
            return httpx.Response(200, json=body)
        return response

    api = d.RuStore(httpx.Client(transport=httpx.MockTransport(handler)), "token")
    with pytest.raises(d.DeliveryError, match="CONFLICT"):
        d.advance_rustore(api, r, apk, j)
    assert (store.uploads, store.submissions) == (0, 0)


def test_rustore_lost_commit_response_is_read_back_without_second_write(tmp_path: Path) -> None:
    r, apk = release_file(tmp_path)
    store, j = DeferredRussianStore(), d.MemoryJournal()

    def handler(req: httpx.Request) -> httpx.Response:
        response = store.handler(req)
        if req.url.path.endswith("/commit"):
            raise httpx.ReadTimeout("commit accepted, response lost", request=req)
        return response

    api = d.RuStore(httpx.Client(transport=httpx.MockTransport(handler)), "token")
    with pytest.raises(d.DeliveryError, match="UNKNOWN"):
        d.advance_rustore(api, r, apk, j)
    assert d.advance_rustore(api, r, apk, j)["status"] == "IN_REVIEW"
    assert (store.uploads, store.submissions) == (1, 1)
