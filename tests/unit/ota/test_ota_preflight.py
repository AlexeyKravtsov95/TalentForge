"""The read-only production preflight (M5.OTA.PROD.1) against an in-memory origin.

`FakeOrigin` answers like the production edge would (anonymous S3 semantics: 403 for
anything not public, gzip of JSON on request); DNS and TLS are injected. No network, no
cloud account, no production key: the publication is signed by TEST keys under
`tf-prod-*` ids. Every check's verdict is asserted for the correct state, the
pre-publication state, and one fault per check - and the preflight is shown to send
`GET`/`HEAD` only.
"""

from __future__ import annotations

import base64
import gzip
import json
from collections.abc import Callable
from datetime import timedelta
from functools import cache
from pathlib import Path
from typing import Any

import httpx
import pytest
from cryptography.hazmat.primitives import serialization

from talent_data.ota import preflight
from talent_data.ota.catalog import catalog_entry, next_catalog
from talent_data.ota.models import Channel
from talent_data.ota.preflight import Report, Status, TlsInfo, TlsRefused
from talent_data.ota.release import ReleaseArtifact, build_release
from talent_data.ota.s3_channels import PRODUCTION_PROFILE, STAGING_PROFILE
from talent_data.ota.s3_store import content_type_of
from talent_data.ota.signing import build_envelope, sign_pae_der
from talent_data.ota.store import BLOB_CACHE_CONTROL, CATALOG_CACHE_CONTROL, blob_key, catalog_key
from talent_data.ota.verify import load_keyring, sha256_ref
from tests.unit.ota.publisher_testkit import (
    BUILD_A,
    COMMIT,
    CREATED_AT,
    GAME,
    NOW,
    ROOT,
    VALIDATION_NOTE,
    derive_test_key,
    write_test_keyring,
)

ORIGIN = PRODUCTION_PROFILE.public_origin
EDGE_HOST = "ota.mythweld.com"
BUCKET_ORIGIN = PRODUCTION_PROFILE.bucket_origin
CATALOG = catalog_key(Channel.PRODUCTION)
PROD_A = "tf-prod-2026a"
RELEASE = f"release.{BUILD_A}-r1"
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def entry(name: str) -> dict[str, str]:
    der = (
        derive_test_key(name)
        .public_key()
        .public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    )
    return {
        "keyId": name,
        "algorithm": "ecdsa-p256-sha256",
        "publicKeySpki": base64.b64encode(der).decode("ascii"),
    }


KEYRING = load_keyring([entry(PROD_A), entry("tf-prod-2026b")])


@cache
def real_release() -> ReleaseArtifact:
    """The committed data of 70374 at the fixture commit: the client's content checks
    run on every file, so the publication must be a real one."""
    return build_release(ROOT, COMMIT, GAME, BUILD_A, 1, CREATED_AT, VALIDATION_NOTE)


def publication(
    *, validity: timedelta = timedelta(days=45), signer: str = PROD_A
) -> dict[str, bytes]:
    """``{path: bytes}`` of a production publication of the real release of 70374."""
    artifact = real_release()
    manifest, manifest_bytes = artifact.manifest, artifact.manifest_bytes
    update = next_catalog(
        None,
        game=GAME,
        channel=Channel.PRODUCTION,
        active_release_id=manifest.release_id,
        append=catalog_entry(manifest_bytes, manifest, published_at=NOW),
        issued_at=NOW,
        validity=validity,
    )
    der = sign_pae_der(derive_test_key(signer), update.payload)
    objects = {blob_key(sha): data for sha, data in artifact.blobs.items()}
    objects[blob_key(sha256_ref(manifest_bytes))] = manifest_bytes
    objects[CATALOG] = build_envelope(update.payload, {PROD_A: der})
    return objects


Answer = tuple[int, dict[str, str], bytes]


def respond(
    status: int, headers: dict[str, str] | None = None, body: bytes = b""
) -> httpx.Response:
    """A response streamed like a network one (the preflight reads raw wire bytes)."""
    return httpx.Response(status, headers=headers, stream=httpx.ByteStream(body))


class FakeOrigin:
    """The HTTPS edge and the bucket host behind it, serving ``objects`` anonymously."""

    def __init__(self, objects: dict[str, bytes] | None = None) -> None:
        self.objects = dict(objects or {})
        self.edge_overrides: dict[str, Answer] = {}  # path -> answer, on the edge only
        self.bucket_overrides: dict[str, Answer] = {}  # path -> answer, on the bucket only
        self.header_edits: dict[str, dict[str, str]] = {}
        self.gzip_bodies: dict[str, bytes] = {}  # what a gzip request gets instead
        self.plain_http: Callable[[], httpx.Response] | None = None
        self.methods: list[str] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.methods.append(request.method)
        path = request.url.raw_path.decode().removeprefix("/")
        if request.url.scheme == "http":
            if self.plain_http is None:
                raise httpx.ConnectError("connection refused", request=request)
            return self.plain_http()
        overrides = self.edge_overrides if request.url.host == EDGE_HOST else self.bucket_overrides
        if path in overrides:
            return respond(*overrides[path])
        if path not in self.objects:
            return respond(403, {}, b"<Error><Code>AccessDenied</Code></Error>")
        body = self.objects[path]
        content_type = content_type_of(path, body)
        headers = {
            "Content-Type": content_type,
            "Cache-Control": CATALOG_CACHE_CONTROL if path == CATALOG else BLOB_CACHE_CONTROL,
            **self.header_edits.get(path, {}),
        }
        if request.headers.get("accept-encoding") == "gzip" and content_type == "application/json":
            headers["Content-Encoding"] = "gzip"
            body = gzip.compress(self.gzip_bodies.get(path, body))
        return respond(200, headers, b"" if request.method == "HEAD" else body)


def resolve(host: str) -> tuple[str, list[str], list[str]]:
    return ("cl-1234.cdn.msk.vkcs.cloud", [host], ["203.0.113.7"])


def handshake(host: str, legacy: bool) -> TlsInfo:
    if legacy:
        raise TlsRefused("TLSV1_ALERT_PROTOCOL_VERSION")
    return TlsInfo("TLSv1.3", NOW + timedelta(days=80), [host], "commonName=R11")


def run(origin: FakeOrigin, **overrides: Any) -> Report:
    options: dict[str, Any] = {
        "resolve": resolve,
        "handshake": handshake,
        "now": NOW,
        "keyring": KEYRING,
        "bucket_origin": BUCKET_ORIGIN,
        **overrides,
    }
    with httpx.Client(transport=httpx.MockTransport(origin.handler)) as client:
        report = preflight.run(ORIGIN, client=client, **options)
    assert set(origin.methods) <= {"GET", "HEAD"}  # read-only, always
    return report


def statuses(report: Report) -> dict[str, Status]:
    return {c.id: c.status for c in report.checks}


# --------------------------------------------------------------------------- states


def test_before_the_first_publication_the_catalog_is_pending_not_failed() -> None:
    report = run(FakeOrigin())
    found = statuses(report)
    assert not report.failed, report.to_json()
    assert found["catalog"] is Status.PENDING
    assert found["releases"] is Status.SKIP
    assert found["dns"] is Status.PASS and found["tls"] is Status.PASS
    assert found["tls.legacy"] is Status.PASS
    assert found["private.origin"] is Status.PASS and found["private.bucket"] is Status.PASS
    assert found["write.anonymous"] is Status.MANUAL

    strict = run(FakeOrigin(), expect="published")
    assert strict.failed and statuses(strict)["catalog"] is Status.FAIL


def test_a_correct_publication_passes_every_check() -> None:
    report = run(FakeOrigin(publication()), expect="published")
    found = statuses(report)
    assert not report.failed, report.to_json()
    assert set(found.values()) <= {Status.PASS, Status.MANUAL}
    assert found[RELEASE] is Status.PASS
    assert found["catalog.signature"] is Status.PASS
    assert found["catalog.gzip"] is Status.PASS
    assert found["catalog.bucket"] is Status.PASS
    doc = json.loads(report.to_json())
    assert doc["result"] == "OK" and doc["origin"] == ORIGIN


def test_only_the_active_release_can_be_checked() -> None:
    found = statuses(run(FakeOrigin(publication()), releases="active"))
    assert found[RELEASE] is Status.PASS
    assert found["release.others"] is Status.SKIP


def test_a_published_catalog_fails_when_none_is_expected() -> None:
    report = run(FakeOrigin(publication()), expect="empty")
    assert report.failed and statuses(report)["catalog"] is Status.FAIL


def test_without_a_keyring_signatures_are_skipped_not_passed() -> None:
    found = statuses(run(FakeOrigin(publication()), keyring=None))
    assert found["catalog.signature"] is Status.SKIP
    assert found["releases"] is Status.SKIP


# --------------------------------------------------------------------------- faults


def _a_json_blob(origin: FakeOrigin) -> str:
    manifest = blob_key(sha256_ref(real_release().manifest_bytes))
    return next(
        k for k, v in origin.objects.items() if k not in (CATALOG, manifest) and v[:1] == b"{"
    )


def _the_png_blob(origin: FakeOrigin) -> str:
    return next(k for k, v in origin.objects.items() if v.startswith(PNG_MAGIC))


def fault_tampered_blob(origin: FakeOrigin) -> str:
    key = _a_json_blob(origin)
    origin.objects[key] = origin.objects[key].replace(b"{", b"{ ", 1)
    return RELEASE


def fault_blob_cache_control(origin: FakeOrigin) -> str:
    origin.header_edits[_a_json_blob(origin)] = {"Cache-Control": "no-cache"}
    return RELEASE


def fault_blob_content_encoding(origin: FakeOrigin) -> str:
    origin.header_edits[_the_png_blob(origin)] = {"Content-Encoding": "aws-chunked"}
    return RELEASE


def fault_png_as_octet_stream(origin: FakeOrigin) -> str:
    origin.header_edits[_the_png_blob(origin)] = {"Content-Type": "binary/octet-stream"}
    return RELEASE


def fault_missing_blob(origin: FakeOrigin) -> str:
    del origin.objects[_the_png_blob(origin)]
    return RELEASE


def fault_catalog_redirect(origin: FakeOrigin) -> str:
    origin.edge_overrides[CATALOG] = (301, {"Location": "https://elsewhere.example/c"}, b"")
    return "catalog.http"


def fault_catalog_content_type(origin: FakeOrigin) -> str:
    origin.header_edits[CATALOG] = {"Content-Type": "text/plain"}
    return "catalog.http"


def fault_catalog_cache_control(origin: FakeOrigin) -> str:
    origin.header_edits[CATALOG] = {"Cache-Control": "public, max-age=86400"}
    return "catalog.http"


def fault_untrusted_signer(origin: FakeOrigin) -> str:
    origin.objects[CATALOG] = publication(signer="someone-else")[CATALOG]
    return "catalog.signature"


def fault_short_validity(origin: FakeOrigin) -> str:
    origin.objects[CATALOG] = publication(validity=timedelta(days=3))[CATALOG]
    return "catalog.validity"


def fault_gzip_serves_other_bytes(origin: FakeOrigin) -> str:
    origin.gzip_bodies[CATALOG] = b'{"other": "catalog"}\n'
    return "catalog.gzip"


def fault_open_listing(origin: FakeOrigin) -> str:
    origin.edge_overrides["?list-type=2"] = (200, {}, b"<ListBucketResult/>")
    return "private.origin"


def fault_staging_catalog_served(origin: FakeOrigin) -> str:
    origin.objects[catalog_key(Channel.STAGING)] = b"{}"
    return "private.origin"


def fault_bucket_policy_readable(origin: FakeOrigin) -> str:
    origin.bucket_overrides["?policy"] = (200, {}, b"{}")
    return "private.bucket"


FAULTS = [
    fault_tampered_blob,
    fault_blob_cache_control,
    fault_blob_content_encoding,
    fault_png_as_octet_stream,
    fault_missing_blob,
    fault_catalog_redirect,
    fault_catalog_content_type,
    fault_catalog_cache_control,
    fault_untrusted_signer,
    fault_short_validity,
    fault_gzip_serves_other_bytes,
    fault_open_listing,
    fault_staging_catalog_served,
    fault_bucket_policy_readable,
]


@pytest.mark.parametrize("fault", FAULTS, ids=lambda f: f.__name__.removeprefix("fault_"))
def test_each_fault_fails_its_check(fault: Callable[[FakeOrigin], str]) -> None:
    origin = FakeOrigin(publication())
    check_id = fault(origin)
    report = run(origin)
    assert report.failed
    assert statuses(report)[check_id] is Status.FAIL, report.to_json()


def test_a_catalog_close_to_expiry_warns_to_re_sign() -> None:
    report = run(FakeOrigin(publication(validity=timedelta(days=10))))
    assert not report.failed
    assert statuses(report)["catalog.validity"] is Status.WARN


def test_a_lagging_edge_cache_only_warns() -> None:
    origin = FakeOrigin(publication())
    origin.bucket_overrides[CATALOG] = (200, {}, b"{}")
    report = run(origin)
    assert not report.failed
    assert statuses(report)["catalog.bucket"] is Status.WARN


def test_plain_http_is_reported() -> None:
    origin = FakeOrigin()
    assert statuses(run(origin))["http.plain"] is Status.PASS  # connection refused
    origin.plain_http = lambda: respond(301, {"Location": f"{ORIGIN}/{CATALOG}"})
    assert statuses(run(origin))["http.plain"] is Status.PASS
    origin.plain_http = lambda: respond(200)
    assert statuses(run(origin))["http.plain"] is Status.WARN


# --------------------------------------------------------------------------- DNS and TLS


def test_dns_failure_stops_the_origin_checks_but_not_the_bucket_check() -> None:
    def unresolved(host: str) -> tuple[str, list[str], list[str]]:
        raise OSError("nodename nor servname provided")

    report = run(FakeOrigin(publication()), resolve=unresolved)
    found = statuses(report)
    assert found["dns"] is Status.FAIL
    assert "tls" not in found and "catalog" not in found and "catalog.http" not in found
    assert found["private.bucket"] is Status.PASS


def test_an_unexpected_cname_target_fails() -> None:
    found = statuses(run(FakeOrigin(), expect_cname_suffix="cdn.msk.vkcs.cloud"))
    assert found["dns.cname"] is Status.PASS
    found = statuses(run(FakeOrigin(), expect_cname_suffix="other-cdn.example"))
    assert found["dns.cname"] is Status.FAIL


def test_tls_verdicts() -> None:
    def untrusted(host: str, legacy: bool) -> TlsInfo:
        raise TlsRefused("certificate not trusted: hostname mismatch")

    found = statuses(run(FakeOrigin(), handshake=untrusted))
    assert found["tls"] is Status.FAIL and "catalog" not in found

    def expiring(host: str, legacy: bool) -> TlsInfo:
        if legacy:
            raise TlsRefused("no protocols available", local=True)
        return TlsInfo("TLSv1.2", NOW + timedelta(days=10), [host], "commonName=R11")

    found = statuses(run(FakeOrigin(), handshake=expiring))
    assert found["tls.expiry"] is Status.WARN
    assert found["tls.legacy"] is Status.SKIP  # this machine cannot even offer TLS 1.1

    def legacy_open(host: str, legacy: bool) -> TlsInfo:
        version = "TLSv1" if legacy else "TLSv1.3"
        return TlsInfo(version, NOW + timedelta(days=80), [host], "commonName=R11")

    assert statuses(run(FakeOrigin(), handshake=legacy_open))["tls.legacy"] is Status.WARN


# --------------------------------------------------------------------------- guards


def test_the_preflight_never_mutates() -> None:
    with httpx.Client(transport=httpx.MockTransport(FakeOrigin().handler)) as client:
        for method in ("PUT", "POST", "DELETE", "PATCH"):
            with pytest.raises(ValueError, match="GET/HEAD only"):
                preflight.fetch(client, f"{ORIGIN}/{CATALOG}", method=method)


def test_the_staging_origin_and_non_https_origins_are_refused() -> None:
    options: dict[str, Any] = {"resolve": resolve, "handshake": handshake, "now": NOW}
    with httpx.Client(transport=httpx.MockTransport(FakeOrigin().handler)) as client:
        with pytest.raises(ValueError, match="staging origin is not production"):
            preflight.run(STAGING_PROFILE.public_origin, client=client, **options)
        with pytest.raises(ValueError, match="https:// host without a path"):
            preflight.run(f"http://{EDGE_HOST}", client=client, **options)
        with pytest.raises(ValueError, match="https:// host without a path"):
            preflight.run(f"{ORIGIN}/v1", client=client, **options)


def test_only_a_production_keyring_is_accepted(tmp_path: Path, capsys) -> None:
    keyring, _ = write_test_keyring(tmp_path / "keys")
    with pytest.raises(ValueError, match="not a production keyring"):
        preflight.production_keyring(keyring)
    assert preflight.main(["--keyring", str(keyring)]) == 2
    assert "not a production keyring" in capsys.readouterr().err


def test_main_prints_and_writes_the_report(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys
) -> None:
    origin = FakeOrigin()
    real_client = httpx.Client

    def mock_client(**kwargs: Any) -> httpx.Client:
        assert kwargs["follow_redirects"] is False
        return real_client(transport=httpx.MockTransport(origin.handler))

    monkeypatch.setattr(preflight.httpx, "Client", mock_client)
    monkeypatch.setattr(preflight.socket, "gethostbyname_ex", resolve)
    monkeypatch.setattr(preflight, "tls_handshake", handshake)
    out = tmp_path / "report.json"

    assert preflight.main(["--json", str(out)]) == 0  # nothing published yet: PENDING
    assert "PENDING" in capsys.readouterr().out
    assert json.loads(out.read_text())["result"] == "OK"

    assert preflight.main(["--json", str(out), "--expect", "published"]) == 1
    assert "result: FAIL" in capsys.readouterr().out
    assert json.loads(out.read_text())["result"] == "FAIL"
