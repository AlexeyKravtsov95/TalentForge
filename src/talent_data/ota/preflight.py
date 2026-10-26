"""Read-only preflight of the production OTA origin (M5.OTA.PROD.1).

``python scripts/ota_production_preflight.py`` (or ``python -m talent_data.ota.preflight``)
checks, from the outside and anonymously, what a production client will meet at
``https://ota.mythweld.com`` - and the bucket's own host behind it:

* **DNS** - the hostname resolves (optionally through an expected CNAME target);
* **TLS** - a certificate chained to a public root (Mozilla's, via ``certifi``) that names
  the host, TLS 1.2+, days left; whether TLS 1.0/1.1 are still accepted;
* **HTTP** - the catalog and every object it references: status, no redirects,
  ``Content-Type``, ``Cache-Control`` (contract §1), no ``Content-Encoding`` on an identity
  request, the same bytes with ``Accept-Encoding: gzip``; plain ``http://`` behaviour;
* **publication** (once one exists) - the catalog verifies against the *production*
  keyring (``tf-prod-*`` keys only) on the wall clock, its validity window, and every
  listed release's manifest and files re-hash to their pins (``verify_release_manifest``,
  ``verify_release_files`` - the client's own checks);
* **privacy** - the staging catalog, listings, ACL/policy/versioning sub-resources and
  keys outside the contract paths are not served.

It sends ``GET`` and ``HEAD`` only, without credentials, follows no redirect and changes
nothing anywhere. Whether anonymous *writes* are closed cannot be proven without trying
one, so that line is ``MANUAL`` (the bucket policy review in the deployment runbook).

Fail closed: only 403/404 confirm a private path is denied (network errors, timeouts, 429
and 5xx fail the check); a served catalog is never ``OK`` unless its signature verified
against the keyring and every listed release's content was re-hashed. Before the first
publication the catalog is simply absent (``PENDING``); ``--expect published`` turns that
into a failure and requires ``--keyring``. The exit status is 1 when any check FAILs.
"""

from __future__ import annotations

import argparse
import gzip
import json
import socket
import ssl
import sys
from collections.abc import Callable, Mapping
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from pathlib import Path
from typing import TYPE_CHECKING, Any
from urllib.parse import urlsplit

import httpx

from talent_data.ota.models import MAX_CATALOG_BYTES, Channel
from talent_data.ota.s3_channels import PRODUCTION_PROFILE, STAGING_PROFILE
from talent_data.ota.store import BLOB_CACHE_CONTROL, CATALOG_CACHE_CONTROL, blob_key, catalog_key

if TYPE_CHECKING:  # the verifier needs the `ota` extra; it is imported where used
    from talent_data.ota.models import CatalogRelease, OtaCatalog
    from talent_data.ota.verify import TrustedKey

GAME = "forever"
CATALOG = catalog_key(Channel.PRODUCTION)
#: re-sign when less than this is left (OD-13: 45-day window, re-sign at ~30 days)
RESIGN_WARNING = timedelta(days=15)
#: a production catalog never has less left than this (the publisher guard's minimum)
VALIDITY_FLOOR = timedelta(days=7)
#: Let's Encrypt renews 30 days ahead; less than this means renewal is not happening
CERTIFICATE_WARNING = timedelta(days=20)
#: paths the origin must not serve: other channel, listings, sub-resources, stray keys
PRIVATE_PATHS = (
    catalog_key(Channel.STAGING),
    "",
    "?list-type=2",
    "?acl",
    "?policy",
    "?versioning",
    "?uploads",
    "?cors",
    "v1/",
    "v1/channels/production/",
    "v1/blobs/sha256/",
    "v1/blobs/sha256/" + "0" * 64,
    "preflight-outside-v1.json",
)
_SAFE_METHODS = ("GET", "HEAD")


class Status(StrEnum):
    PASS = "PASS"
    FAIL = "FAIL"
    WARN = "WARN"  # works, but should be fixed before the release (or soon)
    PENDING = "PENDING"  # nothing published yet - expected before the first publication
    MANUAL = "MANUAL"  # cannot be proven without a mutating request; the runbook covers it
    SKIP = "SKIP"  # not applicable in this run


@dataclass(frozen=True)
class Check:
    id: str
    status: Status
    detail: str


@dataclass
class Report:
    origin: str
    at: str
    checks: list[Check] = field(default_factory=list)

    def add(self, check_id: str, status: Status, detail: str) -> None:
        self.checks.append(Check(check_id, status, detail))

    def status_of(self, check_id: str) -> Status | None:
        return next((c.status for c in self.checks if c.id == check_id), None)

    @property
    def failed(self) -> bool:
        return any(c.status is Status.FAIL for c in self.checks)

    def to_json(self) -> str:
        doc = {
            "origin": self.origin,
            "at": self.at,
            "result": "FAIL" if self.failed else "OK",
            "checks": [asdict(c) for c in self.checks],
        }
        return json.dumps(doc, indent=2) + "\n"


# --------------------------------------------------------------------------- DNS and TLS

Resolver = Callable[[str], tuple[str, list[str], list[str]]]


@dataclass(frozen=True)
class TlsInfo:
    version: str
    not_after: datetime
    subject_alt_names: list[str]
    issuer: str


class TlsRefused(Exception):
    """The handshake failed; ``local`` when this machine could not even offer it."""

    def __init__(self, detail: str, *, local: bool = False) -> None:
        super().__init__(detail)
        self.local = local


#: ``(host, legacy) -> TlsInfo``; ``legacy`` offers TLS 1.0-1.1 only
Handshake = Callable[[str, bool], TlsInfo]


def _trust_context() -> ssl.SSLContext:
    try:
        import certifi

        return ssl.create_default_context(cafile=certifi.where())
    except ImportError:  # pragma: no cover - certifi ships with httpx
        return ssl.create_default_context()


def tls_handshake(host: str, legacy: bool) -> TlsInfo:
    """A verifying TLS handshake with ``host:443`` (hostname checked by ``ssl``)."""
    context = _trust_context()
    if legacy:
        try:
            context.minimum_version = ssl.TLSVersion.TLSv1
            context.maximum_version = ssl.TLSVersion.TLSv1_1
            context.set_ciphers("DEFAULT:@SECLEVEL=0")
        except (ValueError, ssl.SSLError) as exc:
            raise TlsRefused(f"local OpenSSL cannot offer TLS < 1.2: {exc}", local=True) from exc
    else:
        context.minimum_version = ssl.TLSVersion.TLSv1_2
    try:
        with (
            socket.create_connection((host, 443), timeout=15) as raw,
            context.wrap_socket(raw, server_hostname=host) as tls,
        ):
            cert: Any = tls.getpeercert()
            version = tls.version() or "?"
    except ssl.SSLCertVerificationError as exc:
        raise TlsRefused(f"certificate not trusted: {exc.verify_message}") from exc
    except ssl.SSLError as exc:
        local = "NO_PROTOCOLS_AVAILABLE" in str(exc) or "NO_CIPHERS_AVAILABLE" in str(exc)
        raise TlsRefused(f"{exc.reason or exc}", local=local) from exc
    except OSError as exc:
        raise TlsRefused(f"{type(exc).__name__}: {exc}") from exc
    not_after = datetime.fromtimestamp(ssl.cert_time_to_seconds(cert["notAfter"]), UTC)
    names = [value for kind, value in cert.get("subjectAltName", ()) if kind == "DNS"]
    issuer = ", ".join(f"{k}={v}" for rdn in cert.get("issuer", ()) for k, v in rdn)
    return TlsInfo(version, not_after, names, issuer)


def check_dns(
    report: Report, host: str, resolve: Resolver, expect_cname_suffix: str | None
) -> bool:
    try:
        canonical, aliases, addresses = resolve(host)
    except OSError as exc:
        report.add("dns", Status.FAIL, f"{host} does not resolve: {exc}")
        return False
    report.add("dns", Status.PASS, f"{host} -> {canonical} {addresses} (aliases {aliases})")
    if expect_cname_suffix is not None:
        suffix = expect_cname_suffix.rstrip(".")
        names = [canonical, *aliases]
        if any(name.rstrip(".").endswith(suffix) for name in names):
            report.add("dns.cname", Status.PASS, f"resolves through *{suffix}")
        else:
            report.add("dns.cname", Status.FAIL, f"no name in {names} ends with {suffix}")
    return True


def check_tls(report: Report, host: str, handshake: Handshake, now: datetime) -> bool:
    try:
        info = handshake(host, False)
    except TlsRefused as exc:
        report.add("tls", Status.FAIL, f"{host}: {exc}")
        return False
    left = info.not_after - now
    report.add(
        "tls",
        Status.PASS,
        f"{info.version}, certificate for {info.subject_alt_names} (hostname verified), "
        f"issuer {info.issuer}",
    )
    report.add(
        "tls.expiry",
        Status.PASS if left >= CERTIFICATE_WARNING else Status.WARN,
        f"certificate valid until {info.not_after:%Y-%m-%d} ({left.days} days)",
    )
    try:
        legacy = handshake(host, True)
    except TlsRefused as exc:
        status = Status.SKIP if exc.local else Status.PASS
        report.add("tls.legacy", status, f"TLS 1.0/1.1 handshake refused: {exc}")
    else:
        report.add(
            "tls.legacy",
            Status.WARN,
            f"TLS 1.0/1.1 still accepted ({legacy.version}): allow TLS 1.2+ only",
        )
    return True


# --------------------------------------------------------------------------- HTTP


@dataclass(frozen=True)
class Fetched:
    status: int  # 0: no HTTP response at all (see ``error``)
    headers: Mapping[str, str]  # lower-cased names
    body: bytes  # decoded (gzip undone); empty for HEAD
    error: str | None = None

    def header(self, name: str) -> str | None:
        return self.headers.get(name.lower())


def fetch(
    client: httpx.Client, url: str, *, method: str = "GET", accept_gzip: bool = False
) -> Fetched:
    if method not in _SAFE_METHODS:  # the preflight never mutates anything
        raise ValueError(f"the preflight sends GET/HEAD only, not {method}")
    headers = {"Accept-Encoding": "gzip" if accept_gzip else "identity"}
    try:
        with client.stream(method, url, headers=headers) as response:
            wire = b"".join(response.iter_raw())
            lowered = {k.lower(): v for k, v in response.headers.items()}
            status = response.status_code
    except httpx.HTTPError as exc:
        return Fetched(0, {}, b"", error=f"{type(exc).__name__}: {exc}")
    body = wire
    if method == "GET" and lowered.get("content-encoding") == "gzip":
        try:
            body = gzip.decompress(wire)
        except (OSError, EOFError) as exc:
            return Fetched(status, lowered, b"", error=f"bad gzip body: {exc}")
    return Fetched(status, lowered, body)


def _media_type(value: str | None) -> str | None:
    return None if value is None else value.split(";")[0].strip().lower()


def object_problems(
    response: Fetched, *, cache_control: str, content_type: str | None
) -> list[str]:
    """What is wrong with an identity ``GET`` of one contract object."""
    if 300 <= response.status < 400:
        return [f"redirect {response.status} -> {response.header('location')}"]
    if response.status != 200:
        return [f"HTTP {response.status or response.error}"]
    problems = []
    if response.header("cache-control") != cache_control:
        problems.append(f"Cache-Control {response.header('cache-control')!r} != {cache_control!r}")
    if response.header("content-encoding"):
        problems.append(f"Content-Encoding {response.header('content-encoding')!r} on identity")
    if content_type is not None and _media_type(response.header("content-type")) != content_type:
        problems.append(f"Content-Type {response.header('content-type')!r} != {content_type!r}")
    return problems


#: the only answers that *confirm* a path is not public (anonymous S3 denies with 403)
_DENIED = (403, 404)


def check_private(report: Report, client: httpx.Client, origin: str, label: str) -> None:
    """Every private path must be *confirmed* denied (403/404) - fail closed otherwise.

    A network error, a timeout, 429, a 5xx or any other unexpected answer proves nothing
    about public access, so it fails the check instead of passing it.
    """
    served: list[str] = []
    unconfirmed: list[str] = []
    for path in PRIVATE_PATHS:
        response = fetch(client, f"{origin}/{path}")
        if response.status in _DENIED:
            continue
        if response.status == 200 or 300 <= response.status < 400:
            served.append(f"/{path} -> {response.status}")
        else:
            unconfirmed.append(f"/{path} -> {response.status or response.error}")
    if served:
        report.add(f"private.{label}", Status.FAIL, f"served: {served}; {unconfirmed or ''}")
    elif unconfirmed:
        report.add(
            f"private.{label}",
            Status.FAIL,
            f"could not confirm that {len(unconfirmed)} private path(s) are denied "
            f"(only 403/404 confirm it): {unconfirmed[:5]}",
        )
    else:
        report.add(
            f"private.{label}",
            Status.PASS,
            f"{len(PRIVATE_PATHS)} private paths answered 403/404 (staging catalog, listings, "
            "acl/policy/versioning, keys outside the contract)",
        )


def check_plain_http(report: Report, client: httpx.Client, origin: str) -> None:
    plain = "http://" + origin.removeprefix("https://")
    response = fetch(client, f"{plain}/{CATALOG}", method="HEAD")
    location = response.header("location") or ""
    if response.status == 0:
        report.add("http.plain", Status.PASS, f"plain HTTP not served ({response.error})")
    elif 300 <= response.status < 400 and location.startswith("https://"):
        report.add("http.plain", Status.PASS, f"plain HTTP redirects to {location}")
    elif response.status == 200:
        report.add(
            "http.plain",
            Status.WARN,
            "plain HTTP is served (200); clients refuse http:// themselves - prefer an "
            "HTTPS redirect for http:// at the edge",
        )
    else:
        report.add("http.plain", Status.PASS, f"plain HTTP answers {response.status}")


# --------------------------------------------------------------------------- publication


def production_keyring(path: Path) -> dict[str, TrustedKey]:
    """A keyring file holding production keys only (never a test or staging key)."""
    from talent_data.ota.signing import PRODUCTION_KEY_PREFIX, load_keyring_file

    keyring = load_keyring_file(path)
    foreign = sorted(k for k in keyring if not k.startswith(PRODUCTION_KEY_PREFIX))
    if not keyring or foreign:
        raise ValueError(
            f"{path} is not a production keyring ({PRODUCTION_KEY_PREFIX}* only): {foreign}"
        )
    return keyring


def check_catalog(
    report: Report,
    client: httpx.Client,
    origin: str,
    *,
    keyring: Mapping[str, TrustedKey] | None,
    expect: str,
    now: datetime,
    min_validity: timedelta | None = None,
) -> tuple[OtaCatalog, bytes] | None:
    from talent_data.ota.verify import OtaError, verify_catalog

    url = f"{origin}/{CATALOG}"
    identity = fetch(client, url)
    if identity.status in (403, 404):  # anonymous S3 answers 403 for a missing key
        status = Status.FAIL if expect == "published" else Status.PENDING
        report.add("catalog", status, f"no production catalog yet (HTTP {identity.status})")
        return None
    problems = object_problems(
        identity, cache_control=CATALOG_CACHE_CONTROL, content_type="application/json"
    )
    if problems:
        report.add("catalog.http", Status.FAIL, "; ".join(problems))
        return None
    if expect == "empty":
        report.add("catalog", Status.FAIL, "a production catalog is published (expected none)")
    if len(identity.body) > MAX_CATALOG_BYTES:
        report.add("catalog.size", Status.FAIL, f"{len(identity.body)} bytes exceed 1 MiB")
        return None
    report.add("catalog.http", Status.PASS, f"200, contract §1 headers, {len(identity.body)} B")
    compressed = fetch(client, url, accept_gzip=True)
    if compressed.body != identity.body:
        report.add("catalog.gzip", Status.FAIL, "gzip-negotiated bytes differ from identity")
    else:
        encoding = compressed.header("content-encoding") or "identity"
        report.add(
            "catalog.gzip", Status.PASS, f"same bytes with Accept-Encoding: gzip ({encoding})"
        )
    if keyring is None:  # a published catalog is never "OK" without its signature checked
        report.add(
            "catalog.signature",
            Status.FAIL,
            "a production catalog is published but was not verified: pass --keyring",
        )
        return None
    try:
        verified = verify_catalog(
            identity.body,
            keyring=keyring,
            game=GAME,
            channel=Channel.PRODUCTION,
            now=now,
            last_seen=None,
        )
    except OtaError as exc:
        report.add("catalog.signature", Status.FAIL, f"{exc.code.value}: {exc.detail}")
        return None
    catalog = verified.catalog
    report.add(
        "catalog.signature",
        Status.PASS,
        f"verified: sequence {catalog.sequence}, active {catalog.active_release_id}, "
        f"{len(catalog.releases)} release(s), payload {verified.payload_sha256}",
    )
    left = catalog.expires_at - now
    if left < VALIDITY_FLOOR:
        validity = Status.FAIL
    elif left < RESIGN_WARNING:
        validity = Status.WARN
    else:
        validity = Status.PASS
    report.add(
        "catalog.validity",
        validity,
        f"expires {catalog.expires_at:%Y-%m-%dT%H:%M:%SZ} ({left.days} days left; "
        f"re-sign below {RESIGN_WARNING.days})",
    )
    if min_validity is not None:  # a store upload needs a longer window (issue #90)
        report.add(
            "catalog.upload-window",
            Status.PASS if left >= min_validity else Status.FAIL,
            f"{left.days} days left, at least {min_validity.days} required before a store "
            "upload; re-sign the catalog first"
            if left < min_validity
            else f"{left.days} days left, at least {min_validity.days} required before a store "
            "upload",
        )
    return catalog, identity.body


def check_releases(report: Report, client: httpx.Client, origin: str, catalog: OtaCatalog) -> None:
    """Every object of every listed release, as a client fetches it - always all of them."""
    for entry in catalog.releases:
        _check_release(report, client, origin, entry)
    ids = [f"release.{entry.release_id}" for entry in catalog.releases]
    verified = [check_id for check_id in ids if report.status_of(check_id) is Status.PASS]
    if len(verified) == len(ids):
        report.add("releases", Status.PASS, f"{len(ids)}/{len(ids)} listed release(s) verified")
    else:
        report.add(
            "releases",
            Status.FAIL,
            f"{len(verified)}/{len(ids)} listed release(s) verified; the rest failed or "
            "were not checked",
        )


def _check_release(
    report: Report, client: httpx.Client, origin: str, entry: CatalogRelease
) -> None:
    from talent_data.ota.errors import OtaPublisherError
    from talent_data.ota.models import ReleaseFile
    from talent_data.ota.s3_store import content_type_of
    from talent_data.ota.verify import OtaError, verify_release_files, verify_release_manifest

    problems: list[str] = []
    bodies: dict[str, bytes] = {}

    def get_blob(sha_ref: str) -> bytes:
        if sha_ref not in bodies:
            key = blob_key(sha_ref)
            response = fetch(client, f"{origin}/{key}")
            found = object_problems(
                response,
                cache_control=BLOB_CACHE_CONTROL,
                content_type=content_type_of(key, response.body) if response.body else None,
            )
            problems.extend(f"{key}: {p}" for p in found)
            bodies[sha_ref] = response.body
        return bodies[sha_ref]

    def read_file(file: ReleaseFile) -> bytes:
        return get_blob(file.sha256)

    check_id = f"release.{entry.release_id}"
    try:
        manifest = verify_release_manifest(
            get_blob(entry.manifest_sha256),
            entry,
            game=GAME,
            supported_data_level=entry.min_data_level,
        )
        verify_release_files(manifest, read_file)
    except (OtaError, OtaPublisherError) as exc:
        problems.append(
            f"{exc.code.value}: {exc.detail}" if isinstance(exc, OtaError) else str(exc)
        )
        report.add(check_id, Status.FAIL, "; ".join(problems[:5]))
        return
    if problems:
        report.add(check_id, Status.FAIL, "; ".join(problems[:5]))
        return
    report.add(
        check_id,
        Status.PASS,
        f"{len(manifest.files)} files, {manifest.total_byte_length:,} bytes re-hashed; "
        f"{len(bodies)} objects with contract headers",
    )


# --------------------------------------------------------------------------- run


def run(
    origin: str,
    *,
    client: httpx.Client,
    resolve: Resolver,
    handshake: Handshake,
    now: datetime,
    keyring: Mapping[str, TrustedKey] | None = None,
    bucket_origin: str | None = None,
    expect: str = "any",
    expect_cname_suffix: str | None = None,
    min_validity: timedelta | None = None,
) -> Report:
    parts = urlsplit(origin)
    if parts.scheme != "https" or not parts.hostname or parts.path not in ("", "/"):
        raise ValueError(f"the origin is an https:// host without a path, got {origin!r}")
    origin = origin.rstrip("/")
    if STAGING_PROFILE.public_origin in (origin, bucket_origin):
        raise ValueError("this is the production preflight; the staging origin is not production")
    if expect == "published" and keyring is None:
        raise ValueError("--expect published needs --keyring: a publication is verified, not seen")
    host = parts.hostname
    report = Report(origin=origin, at=now.strftime("%Y-%m-%dT%H:%M:%SZ"))

    if check_dns(report, host, resolve, expect_cname_suffix) and check_tls(
        report, host, handshake, now
    ):
        check_plain_http(report, client, origin)
        found = check_catalog(
            report,
            client,
            origin,
            keyring=keyring,
            expect=expect,
            now=now,
            min_validity=min_validity,
        )
        if found is None:
            if report.status_of("catalog") is Status.PENDING:  # nothing published (403/404)
                report.add("releases", Status.SKIP, "no production catalog to follow")
            else:  # something is served, but it could not be verified: never OK
                report.add(
                    "releases",
                    Status.FAIL,
                    "the served catalog was not verified, so no release content was checked",
                )
        else:
            catalog, catalog_bytes = found
            check_releases(report, client, origin, catalog)
            if bucket_origin is not None:
                behind = fetch(client, f"{bucket_origin}/{CATALOG}")
                if behind.body == catalog_bytes:
                    report.add("catalog.bucket", Status.PASS, "the bucket host serves the same")
                else:
                    report.add(
                        "catalog.bucket",
                        Status.WARN,
                        f"the bucket host differs (HTTP {behind.status}); the edge cache may "
                        "lag up to its max-age - re-run in 5 minutes",
                    )
        check_private(report, client, origin, "origin")
    if bucket_origin is not None:
        check_private(report, client, bucket_origin, "bucket")
    report.add(
        "write.anonymous",
        Status.MANUAL,
        "anonymous writes are not attempted (a probe would itself be a write): confirm the "
        "bucket ACL is private and the policy grants s3:GetObject only "
        "(docs/ota/production-deployment.md §2)",
    )
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Read-only preflight of the production OTA origin (M5.OTA.PROD.1)"
    )
    parser.add_argument("--origin", default=PRODUCTION_PROFILE.public_origin)
    parser.add_argument(
        "--bucket-origin",
        default=PRODUCTION_PROFILE.bucket_origin,
        help="the bucket's own host, checked for privacy too ('' skips it)",
    )
    parser.add_argument("--keyring", type=Path, help="the production public keyring (tf-prod-*)")
    parser.add_argument("--expect", choices=["any", "empty", "published"], default="any")
    parser.add_argument("--expect-cname-suffix", help="e.g. the CDN host VK Cloud shows")
    parser.add_argument("--json", type=Path, help="also write the report as JSON here")
    parser.add_argument(
        "--min-validity-days",
        type=int,
        help="fail unless the catalog stays valid at least this long (store uploads: 30)",
    )
    args = parser.parse_args(argv)

    try:
        keyring = None if args.keyring is None else production_keyring(args.keyring)
        with httpx.Client(follow_redirects=False, timeout=30.0) as client:
            report = run(
                args.origin,
                client=client,
                resolve=socket.gethostbyname_ex,
                handshake=tls_handshake,
                now=datetime.now(UTC).replace(microsecond=0),
                keyring=keyring,
                bucket_origin=args.bucket_origin or None,
                expect=args.expect,
                expect_cname_suffix=args.expect_cname_suffix,
                min_validity=None
                if args.min_validity_days is None
                else timedelta(days=args.min_validity_days),
            )
    except (ValueError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    for check in report.checks:
        print(f"{check.status.value:<7} {check.id:<24} {check.detail}")
    print(f"result: {'FAIL' if report.failed else 'OK'} ({report.origin}, {report.at})")
    if args.json is not None:
        args.json.write_text(report.to_json(), encoding="utf-8")
    return 1 if report.failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
