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


@pytest.fixture(autouse=True)
def no_real_retry_delays(monkeypatch: pytest.MonkeyPatch) -> None:
    # These origins are entirely in-memory; backoff is exercised without wall-clock sleeps.
    monkeypatch.setattr(preflight.time, "sleep", lambda _: None)


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
        self.bucket_down = False  # the bucket host accepts no connection at all
        self.failures: dict[str, Exception] = {}  # path -> transport failure, on the edge
        self.methods: list[str] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.methods.append(request.method)
        path = request.url.raw_path.decode().removeprefix("/")
        if request.url.scheme == "http":
            if self.plain_http is None:
                raise httpx.ConnectError("connection refused", request=request)
            return self.plain_http()
        if request.url.host != EDGE_HOST and self.bucket_down:
            raise httpx.ConnectError("connection refused", request=request)
        if request.url.host == EDGE_HOST and path in self.failures:
            raise self.failures[path]
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
    assert found["releases"] is Status.PASS
    doc = json.loads(report.to_json())
    assert doc["result"] == "OK" and doc["origin"] == ORIGIN


def test_a_release_check_that_did_not_run_is_never_ok(monkeypatch: pytest.MonkeyPatch) -> None:
    """Content verification is mandatory: a listed release left unchecked fails the run."""
    monkeypatch.setattr(preflight, "_check_release", lambda *args: None)
    report = run(FakeOrigin(publication()))
    assert report.failed
    assert statuses(report)["releases"] is Status.FAIL
    assert RELEASE not in statuses(report)


def test_a_published_catalog_fails_when_none_is_expected() -> None:
    report = run(FakeOrigin(publication()), expect="empty")
    assert report.failed and statuses(report)["catalog"] is Status.FAIL


def test_a_published_catalog_without_a_keyring_fails() -> None:
    report = run(FakeOrigin(publication()), keyring=None)
    found = statuses(report)
    assert report.failed
    assert found["catalog.signature"] is Status.FAIL
    assert found["releases"] is Status.FAIL  # content not verified: never OK
    assert json.loads(report.to_json())["result"] == "FAIL"


def test_expect_published_requires_a_keyring() -> None:
    with pytest.raises(ValueError, match="--expect published needs --keyring"):
        run(FakeOrigin(publication()), keyring=None, expect="published")


def test_an_unverifiable_served_catalog_fails_its_releases_too() -> None:
    origin = FakeOrigin(publication())
    origin.objects[CATALOG] = publication(signer="someone-else")[CATALOG]
    found = statuses(run(origin))
    assert found["catalog.signature"] is Status.FAIL
    assert found["releases"] is Status.FAIL


def test_a_catalog_that_cannot_be_fetched_fails() -> None:
    origin = FakeOrigin(publication())
    origin.edge_overrides[CATALOG] = (503, {}, b"")
    report = run(origin)
    assert report.failed
    assert statuses(report)["catalog.http"] is Status.FAIL
    assert statuses(report)["releases"] is Status.FAIL


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


def fault_tampered_manifest(origin: FakeOrigin) -> str:
    key = blob_key(sha256_ref(real_release().manifest_bytes))
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
    fault_tampered_manifest,
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
@pytest.mark.parametrize("release_scope", ["all", "active"])
def test_each_fault_fails_its_check(fault: Callable[[FakeOrigin], str], release_scope: str) -> None:
    origin = FakeOrigin(publication())
    check_id = fault(origin)
    report = run(origin, release_scope=release_scope)
    assert report.failed
    assert statuses(report)[check_id] is Status.FAIL, report.to_json()


def test_a_catalog_close_to_expiry_warns_to_re_sign() -> None:
    report = run(FakeOrigin(publication(validity=timedelta(days=10))))
    assert not report.failed
    assert statuses(report)["catalog.validity"] is Status.WARN


@pytest.mark.parametrize("release_scope", ["all", "active"])
def test_a_store_upload_window_fails_below_the_required_days(release_scope: str) -> None:
    # issue #90: the delivery workflow uploads only with >= 30 days of catalog validity left
    ok = run(
        FakeOrigin(publication(validity=timedelta(days=30))),
        min_validity=timedelta(days=30),
        release_scope=release_scope,
    )
    assert not ok.failed and statuses(ok)["catalog.upload-window"] is Status.PASS
    short = run(
        FakeOrigin(publication(validity=timedelta(days=29))),
        min_validity=timedelta(days=30),
        release_scope=release_scope,
    )
    assert short.failed and statuses(short)["catalog.upload-window"] is Status.FAIL
    assert statuses(short)["catalog.validity"] is Status.PASS  # only the upload window fails
    assert "catalog.upload-window" not in statuses(run(FakeOrigin(publication())))


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


# --------------------------------------------------------------------------- private paths


def test_an_unreachable_bucket_origin_is_not_a_pass() -> None:
    origin = FakeOrigin()
    origin.bucket_down = True
    report = run(origin)
    assert report.failed
    assert statuses(report)["private.bucket"] is Status.FAIL
    assert "could not confirm" in next(c.detail for c in report.checks if c.id == "private.bucket")
    assert statuses(report)["private.origin"] is Status.PASS


@pytest.mark.parametrize(
    "answer",
    [
        (429, {"Retry-After": "5"}, b""),
        (500, {}, b""),
        (502, {}, b""),
        (503, {}, b""),
        (400, {}, b""),
        (204, {}, b""),
    ],
    ids=lambda a: str(a[0]),
)
def test_an_unconfirmed_answer_on_one_private_path_fails(answer: Answer) -> None:
    origin = FakeOrigin()
    origin.edge_overrides["?acl"] = answer
    report = run(origin)
    assert report.failed
    assert statuses(report)["private.origin"] is Status.FAIL


@pytest.mark.parametrize(
    "failure",
    [
        httpx.ReadTimeout("read timed out"),
        httpx.ConnectTimeout("connect timed out"),
        httpx.RemoteProtocolError("peer closed connection"),
    ],
    ids=lambda f: type(f).__name__,
)
def test_a_transport_failure_on_one_private_path_fails(failure: Exception) -> None:
    origin = FakeOrigin()
    origin.failures["v1/"] = failure
    report = run(origin)
    assert report.failed
    assert statuses(report)["private.origin"] is Status.FAIL


def test_404_confirms_a_private_path_as_well_as_403() -> None:
    origin = FakeOrigin()
    for path in preflight.PRIVATE_PATHS:
        origin.edge_overrides[path] = (404, {}, b"")
    assert statuses(run(origin))["private.origin"] is Status.PASS


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


@pytest.mark.parametrize("release_scope", ["all", "active"])
def test_tls_verdicts(release_scope: str) -> None:
    def untrusted(host: str, legacy: bool) -> TlsInfo:
        raise TlsRefused("certificate not trusted: hostname mismatch")

    found = statuses(run(FakeOrigin(), handshake=untrusted, release_scope=release_scope))
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

    # --expect published without --keyring is a usage error, before any request
    requests = len(origin.methods)
    assert preflight.main(["--expect", "published"]) == 2
    assert "--expect published needs --keyring" in capsys.readouterr().err
    assert len(origin.methods) == requests

    ring = tmp_path / "production-keyring.json"
    ring.write_text(json.dumps({"keys": [entry(PROD_A), entry("tf-prod-2026b")]}))
    published = ["--json", str(out), "--expect", "published", "--keyring", str(ring)]
    assert preflight.main(published) == 1  # nothing published
    assert "result: FAIL" in capsys.readouterr().out
    assert json.loads(out.read_text())["result"] == "FAIL"

    origin.objects.update(publication())  # published, but checked without a keyring
    assert preflight.main(["--json", str(out)]) == 1
    assert json.loads(out.read_text())["result"] == "FAIL"


def test_transient_blob_timeout_retries_the_complete_read(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = []
    monkeypatch.setattr(preflight.time, "sleep", lambda _: None)

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.method)
        if len(calls) < 3:
            raise httpx.ReadTimeout("temporary read failure")
        return respond(200, {"Cache-Control": BLOB_CACHE_CONTROL}, b"complete")

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = preflight.fetch(client, ORIGIN + "/blob", attempts=3)
    assert result.body == b"complete" and result.attempts == 3
    assert calls == ["GET"] * 3


@pytest.mark.parametrize("status", [302, 403, 404])
def test_nontransient_http_answers_are_not_retried(status: int) -> None:
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.method)
        return respond(status)

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        assert preflight.fetch(client, ORIGIN, attempts=3).status == status
    assert calls == ["GET"]


def test_bounded_parallel_downloads_keep_full_validation_and_live_progress() -> None:
    import threading

    objects = publication()
    origin = FakeOrigin(objects)
    lock = threading.Lock()
    active = peak = 0
    pair = threading.Barrier(2)
    paired = {
        "/" + blob_key(sha)
        for sha in list(dict.fromkeys(f.sha256 for f in real_release().manifest.files))[:2]
    }

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal active, peak
        if request.url.path.startswith("/v1/blobs/"):
            with lock:
                active += 1
                peak = max(peak, active)
            # Require actual parallel execution without measuring machine speed.
            if request.url.path in paired:
                pair.wait(timeout=5)
            try:
                return origin.handler(request)
            finally:
                with lock:
                    active -= 1
        return origin.handler(request)

    messages = []
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        report = preflight.run(
            ORIGIN,
            client=client,
            resolve=resolve,
            handshake=handshake,
            now=NOW,
            keyring=KEYRING,
            expect="published",
            workers=2,
            progress=messages.append,
        )
    assert not report.failed, report.to_json()
    assert 1 < peak <= 2
    assert any("objects" in message for message in messages)
    assert any(RELEASE in message for message in messages)
    assert statuses(report)["releases"] is Status.PASS


@pytest.mark.parametrize("failure", [503, "timeout"])
def test_read_retries_are_bounded_and_exhaustion_is_not_success(
    monkeypatch: pytest.MonkeyPatch,
    failure: int | str,
) -> None:
    monkeypatch.setattr(preflight.time, "sleep", lambda _: None)
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.method)
        if failure == "timeout":
            raise httpx.ReadTimeout("persistent")
        return respond(503)

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = preflight.fetch(client, ORIGIN, attempts=3)
    assert len(calls) == result.attempts == 3
    assert preflight.object_problems(result, cache_control=BLOB_CACHE_CONTROL, content_type=None)


def test_parallel_preflight_never_accepts_corrupt_content_or_headers() -> None:
    origin = FakeOrigin(publication())
    key = blob_key(next(iter(real_release().blobs)))
    origin.header_edits[key] = {"Cache-Control": "wrong"}
    report = run(origin, workers=4)
    assert report.failed and statuses(report)["releases"] is Status.FAIL
    assert "Cache-Control" in next(c.detail for c in report.checks if c.id == RELEASE)


@pytest.mark.parametrize("workers", [0, 17, True])
def test_preflight_worker_bound_is_enforced(workers: int) -> None:
    with pytest.raises(ValueError, match="workers"):
        run(FakeOrigin(), workers=workers)


def test_partial_response_is_discarded_before_read_retry(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(preflight.time, "sleep", lambda _: None)

    class Partial(httpx.SyncByteStream):
        def __iter__(self):
            yield b"partial"
            raise httpx.ReadTimeout("stream interrupted")

    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.method)
        return (
            httpx.Response(200, stream=Partial()) if len(calls) == 1 else respond(200, body=b"full")
        )

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = preflight.fetch(client, ORIGIN, attempts=3)
    assert result.body == b"full" and result.attempts == len(calls) == 2


def test_waiting_workers_emit_a_heartbeat(monkeypatch: pytest.MonkeyPatch) -> None:
    real_wait = preflight.wait
    calls = []

    def wait_once(futures, **kwargs):
        calls.append(kwargs["timeout"])
        return (set(), set(futures)) if len(calls) == 1 else real_wait(futures, **kwargs)

    monkeypatch.setattr(preflight, "wait", wait_once)
    messages = []
    report = run(FakeOrigin(publication()), workers=2, progress=messages.append)
    assert not report.failed
    assert calls[0] == 10
    assert any("waiting;" in message for message in messages)


def test_private_probe_retries_also_have_visible_progress(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(preflight.time, "sleep", lambda _: None)
    origin = FakeOrigin()
    origin.failures[preflight.PRIVATE_PATHS[0]] = httpx.ReadTimeout("private probe timeout")
    messages = []
    report = run(origin, progress=messages.append)
    assert report.failed
    assert any("private.origin: checking" in message for message in messages)
    assert any("attempt 3/3" in message for message in messages)


def test_shared_reader_preserves_one_observation_for_existing_guards() -> None:
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.method)
        return respond(503)

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = preflight.fetch(client, ORIGIN)
    assert result.status == 503 and result.attempts == len(calls) == 1


def two_release_publication() -> tuple[dict[str, bytes], str]:
    from tests.unit.ota.publisher_testkit import BUILD_B

    objects = publication()
    old = real_release()
    active = build_release(ROOT, COMMIT, GAME, BUILD_B, 1, CREATED_AT, VALIDATION_NOTE)
    first = next_catalog(
        None,
        game=GAME,
        channel=Channel.PRODUCTION,
        active_release_id=old.manifest.release_id,
        append=catalog_entry(old.manifest_bytes, old.manifest, NOW),
        issued_at=NOW,
    )
    update = next_catalog(
        first.catalog,
        game=GAME,
        channel=Channel.PRODUCTION,
        active_release_id=active.manifest.release_id,
        append=catalog_entry(active.manifest_bytes, active.manifest, NOW),
        issued_at=NOW,
    )
    objects.update({blob_key(sha): data for sha, data in active.blobs.items()})
    objects[blob_key(sha256_ref(active.manifest_bytes))] = active.manifest_bytes
    objects[CATALOG] = build_envelope(
        update.payload, {PROD_A: sign_pae_der(derive_test_key(PROD_A), update.payload)}
    )
    return objects, blob_key(sha256_ref(old.manifest_bytes))


def test_store_active_scope_is_independent_of_a_historical_tls_eof() -> None:
    objects, historical = two_release_publication()
    origin = FakeOrigin(objects)
    origin.failures[historical] = httpx.ReadError("TLS EOF")
    active = run(
        origin, release_scope="active", expect="published", min_validity=timedelta(days=30)
    )
    assert not active.failed
    assert RELEASE not in statuses(active)
    assert statuses(active)["catalog.signature"] is Status.PASS
    archive = run(origin, release_scope="all", expect="published")
    assert archive.failed
    failure = next(c for c in archive.checks if c.id == RELEASE)
    assert "TLS EOF" in failure.detail
    assert "HASH_MISMATCH" not in failure.detail


def test_active_scope_still_rejects_bad_blob_hashes() -> None:
    origin = FakeOrigin(publication())
    file = real_release().manifest.files[0]
    origin.objects[blob_key(file.sha256)] = b"corrupt"
    report = run(origin, release_scope="active", expect="published")
    assert report.failed and statuses(report)[RELEASE] is Status.FAIL
