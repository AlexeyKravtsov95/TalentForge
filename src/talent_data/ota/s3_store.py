"""S3-compatible remote adapter of the OTA ``BlobStore`` port (M5.OTA.4b, M5.OTA.PROD.1).

OD-1 is decided: the origin is VK Cloud Object Storage (``ru-msk``). Each channel has its
own bucket, fixed by the committed profiles of :mod:`talent_data.ota.s3_channels`
(``talentforge-ota-staging``, ``talentforge-ota-production``). This module is the
publisher's view of one of them; every ordering rule (blobs first, read-back verification,
catalog last behind the sequence guard) stays in :mod:`talent_data.ota.store` and is not
repeated here. What the adapter adds is what only a remote store can get wrong:

* **Channel isolation.** A store serves exactly one channel, on exactly that channel's
  bucket: the staging bucket can never be opened as production, nor the reverse, and no
  other bucket, endpoint or region is accepted. Writes go to ``v1/blobs/sha256/<64 hex>``
  and the store's own ``v1/channels/<channel>/catalog.json`` only; the *other* channel's
  catalog key is refused before any request is made (it may be *read*, so gates and ``gc``
  fail closed if one ever appears in the wrong bucket). Every other key is refused.
* **Mode.** A store is read-only unless constructed ``writable`` (the CLI does that only
  under ``--execute``), and cannot delete unless constructed ``allow_delete`` (``gc``
  under ``--execute --confirm`` only, and never on production). A dry run physically
  cannot mutate the bucket.
* **Production proves every catalog to the adapter itself.** A writable production store
  needs a :class:`ProductionCatalogGuard` (the production keyring - ``tf-prod-*`` keys
  only - and the game). Right before the put, the envelope must verify against that
  keyring on the wall clock, be signed by ``tf-prod-*`` keys only, keep at least
  :data:`PRODUCTION_MIN_VALIDITY` of validity, not be issued in the future, and be the
  exact successor of the live catalog (sequence + 1, append-only). So no raw put - a bad
  signature, a short-lived "expired" catalog, a test-signed or replayed one - can land on
  the production path, whatever called it: production has no fault injection and no
  rehearsal clock. Production blobs are never deleted by this tool.
* **Immutability.** A blob must hash to its key, is never overwritten (``HEAD`` first,
  then a conditional ``If-None-Match: *`` put - which VK Cloud ignores, so the ``HEAD``
  is the guard there; a racing writer can only write the same bytes under a content
  address), and is verified after the put
  (``HEAD``: length, ``ETag`` = MD5 of the bytes, contract headers, no
  ``Content-Encoding``).
* **Concurrency.** The catalog put is conditional on the ``ETag`` this process observed
  when it last read the live catalog (``If-Match``), or ``If-None-Match: *`` when it
  observed none; a catalog put without a prior observation is refused. **VK Cloud ignores
  both conditions** (measured 2026-10-18, ``scripts/ota_vkcloud_staging.py
  probe-conditional``), so the adapter also re-reads the live catalog's ``ETag``
  immediately before the put and aborts when it moved (state check before the write),
  and reads the catalog back after it (state check after the write). Between those two
  requests only the single-publisher procedure of the runbook protects the catalog.
* **Failure kinds.** 404, 403, 412, network and credential failures are distinct
  :class:`S3Failure` values; a 403 is never read as "absent".
* **HTTP metadata.** ``Cache-Control`` as contract §1 recommends, ``Content-Type``
  ``application/json`` for JSON and the catalog, ``image/png`` for PNG icons. Bytes are
  stored exactly as signed - no ``Content-Encoding``, ever (ADR-0002 O2).

Credentials come from the AWS CLI profile or the standard credential chain; they are
never read, printed or logged here. TLS verification is botocore's default and is never
disabled; the endpoint must be ``https://``.
"""

from __future__ import annotations

import base64
import hashlib
import json
import re
from collections.abc import Iterator, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import Any, Protocol

from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import MAX_CATALOG_BYTES, Channel, OtaCatalog
from talent_data.ota.s3_channels import PROFILES, require_profile
from talent_data.ota.store import (
    BLOB_CACHE_CONTROL,
    BLOB_PREFIX,
    CATALOG_CACHE_CONTROL,
    catalog_key,
)
from talent_data.ota.verify import TrustedKey

BLOB_KEY = re.compile(r"v1/blobs/sha256/[0-9a-f]{64}")
JSON_CONTENT_TYPE = "application/json"
PNG_CONTENT_TYPE = "image/png"
OCTET_CONTENT_TYPE = "application/octet-stream"
_PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
_LIST_PAGE = 1000
#: a production catalog must stay valid at least this long after it is written: a
#: short-lived catalog is the "expired" fault of staging, never a production publication
PRODUCTION_MIN_VALIDITY = timedelta(days=7)
#: how far a production catalog's ``issuedAt`` may lie ahead of the publisher's clock
PRODUCTION_CLOCK_SKEW = timedelta(minutes=5)


def _wall_clock() -> datetime:
    """The production guard's clock - the real one; the CLI's ``--now`` never reaches it."""
    return datetime.now(UTC)


class S3Failure(StrEnum):
    """Why a remote store operation failed - typed, so no caller parses messages."""

    NOT_FOUND = "NOT_FOUND"  # 404 NoSuchKey / NotFound
    ACCESS_DENIED = "ACCESS_DENIED"  # 403 - never treated as "absent"
    PRECONDITION_FAILED = "PRECONDITION_FAILED"  # 412/409: a concurrent writer won
    NETWORK = "NETWORK"  # DNS, connect, TLS, timeout, connection reset
    CREDENTIALS = "CREDENTIALS"  # no/partial credentials, unknown profile
    SERVICE = "SERVICE"  # any other S3 error response
    OUT_OF_SCOPE = "OUT_OF_SCOPE"  # a key this store must not touch
    READ_ONLY = "READ_ONLY"  # a mutation without --execute (or delete without --confirm)
    IMMUTABLE = "IMMUTABLE"  # overwriting a blob, or a blob that does not hash to its key
    VERIFY_FAILED = "VERIFY_FAILED"  # the object read back differs from what was written
    CATALOG_REFUSED = "CATALOG_REFUSED"  # a production catalog that does not prove itself


class S3StoreError(OtaPublisherError):
    """A remote store failure; :attr:`kind` says which."""

    def __init__(self, kind: S3Failure, key: str, detail: str) -> None:
        super().__init__(f"s3 {kind.value} {key}: {detail}")
        self.kind = kind
        self.key = key


class S3Client(Protocol):
    """The subset of a boto3 S3 client the adapter uses (keyword arguments only)."""

    def head_object(self, **kwargs: Any) -> dict[str, Any]: ...

    def get_object(self, **kwargs: Any) -> dict[str, Any]: ...

    def put_object(self, **kwargs: Any) -> dict[str, Any]: ...

    def delete_object(self, **kwargs: Any) -> dict[str, Any]: ...

    def list_objects_v2(self, **kwargs: Any) -> dict[str, Any]: ...


@dataclass(frozen=True)
class S3Location:
    """Where the remote store is: everything but credentials, which stay in the profile."""

    bucket: str
    endpoint_url: str
    region: str
    profile: str | None = None
    channel: Channel = Channel.STAGING

    def __post_init__(self) -> None:
        if not self.endpoint_url.startswith("https://"):
            raise OtaPublisherError(
                f"S3 endpoint must be https:// (TLS is never optional), got {self.endpoint_url!r}"
            )
        if not self.bucket or "/" in self.bucket:
            raise OtaPublisherError(f"invalid bucket name {self.bucket!r}")
        # the channel's committed profile, exactly: a staging bucket is never production
        require_profile(
            self.channel, bucket=self.bucket, endpoint_url=self.endpoint_url, region=self.region
        )


def content_type_of(key: str, data: bytes) -> str:
    """The ``Content-Type`` of an object: the catalog and JSON blobs are JSON, icons PNG."""
    if not BLOB_KEY.fullmatch(key):
        return JSON_CONTENT_TYPE  # the catalog envelope
    if data.startswith(_PNG_MAGIC):
        return PNG_CONTENT_TYPE
    if data[:1] in (b"{", b"["):
        try:
            json.loads(data)
        except ValueError:
            return OCTET_CONTENT_TYPE
        return JSON_CONTENT_TYPE
    return OCTET_CONTENT_TYPE


def connect(
    location: S3Location,
    *,
    writable: bool,
    allow_delete: bool,
    catalog_guard: ProductionCatalogGuard | None = None,
) -> S3BlobStore:
    """A boto3-backed store for ``location`` (needs the ``s3`` extra).

    A writable production store needs ``catalog_guard``; one is refused for staging.

    Checksums are computed only when the operation requires them: botocore's default
    streaming checksum (``aws-chunked`` uploads) makes VK Cloud store and serve
    ``Content-Encoding: aws-chunked`` on the object, which is not a valid HTTP content
    coding for clients; instead every put carries ``Content-MD5`` and is verified by ETag.
    """
    try:
        import boto3
        from botocore.config import Config
        from botocore.exceptions import BotoCoreError
    except ImportError as exc:  # pragma: no cover - depends on the environment
        raise OtaPublisherError(
            "the s3 store needs boto3: pip install -e '.[s3]' (or '.[dev]')"
        ) from exc
    config = Config(
        request_checksum_calculation="when_required",
        response_checksum_validation="when_required",
        s3={"addressing_style": "virtual"},
        retries={"mode": "standard", "max_attempts": 5},
        connect_timeout=10,
        read_timeout=60,
        user_agent_extra="talentforge-ota-publisher",
    )
    try:
        session = boto3.Session(profile_name=location.profile, region_name=location.region)
        client = session.client("s3", endpoint_url=location.endpoint_url, config=config)
    except BotoCoreError as exc:
        raise S3StoreError(S3Failure.CREDENTIALS, location.bucket, type(exc).__name__) from exc
    return S3BlobStore(
        client,
        location.bucket,
        channel=location.channel,
        writable=writable,
        allow_delete=allow_delete,
        catalog_guard=catalog_guard,
    )


def _classify(exc: Exception) -> tuple[S3Failure, str]:
    """Map a botocore exception to a failure kind and a secret-free detail."""
    from botocore.exceptions import (
        ClientError,
        ConnectionError,
        HTTPClientError,
        NoCredentialsError,
        PartialCredentialsError,
        ProfileNotFound,
    )

    if isinstance(exc, ClientError):
        meta = exc.response.get("ResponseMetadata", {})
        status = meta.get("HTTPStatusCode")
        code = str(exc.response.get("Error", {}).get("Code", ""))
        detail = f"HTTP {status} {code}".strip()
        if status == 404 or code in ("NoSuchKey", "NotFound", "404"):
            return S3Failure.NOT_FOUND, detail
        if status == 403 or code in ("AccessDenied", "Forbidden", "403"):
            return S3Failure.ACCESS_DENIED, detail
        if status in (409, 412) or code in ("PreconditionFailed", "ConditionalRequestConflict"):
            return S3Failure.PRECONDITION_FAILED, detail
        return S3Failure.SERVICE, detail
    if isinstance(exc, NoCredentialsError | PartialCredentialsError | ProfileNotFound):
        return S3Failure.CREDENTIALS, type(exc).__name__
    if isinstance(exc, ConnectionError | HTTPClientError):
        return S3Failure.NETWORK, type(exc).__name__
    return S3Failure.SERVICE, type(exc).__name__


class ProductionCatalogGuard:
    """What a production catalog must prove to the adapter before it is put.

    The CLI's ``attach-signature`` already re-proves a publication; this is the same
    proof one layer lower, so that *no* caller of a writable production store - a script,
    a test helper, a future command - can put a catalog the production clients would not
    accept as the next one. It needs only public material: the production keyring.
    """

    def __init__(self, keyring: Mapping[str, TrustedKey], *, game: str) -> None:
        from talent_data.ota.signing import PRODUCTION_KEY_PREFIX

        foreign = sorted(
            key_id for key_id in keyring if not key_id.startswith(PRODUCTION_KEY_PREFIX)
        )
        if not keyring or foreign:
            raise OtaPublisherError(
                f"a production store needs a production keyring ({PRODUCTION_KEY_PREFIX}* keys "
                f"only); refusing keyring with {foreign or 'no keys'}"
            )
        self.keyring = dict(keyring)
        self.game = game

    def check(self, envelope: bytes, live_envelope: bytes | None) -> OtaCatalog:
        """The verified catalog ``envelope`` carries, if it may replace ``live_envelope``."""
        from talent_data.ota.catalog import ensure_valid_succession
        from talent_data.ota.models import SignedEnvelope
        from talent_data.ota.signing import PRODUCTION_KEY_PREFIX
        from talent_data.ota.store import verify_live_envelope
        from talent_data.ota.verify import OtaError, parse_strict, verify_catalog

        key = catalog_key(Channel.PRODUCTION)

        def refuse(detail: str) -> S3StoreError:
            return S3StoreError(S3Failure.CATALOG_REFUSED, key, detail)

        now = _wall_clock()
        if len(envelope) > MAX_CATALOG_BYTES:
            raise refuse(f"{len(envelope)} bytes exceed the {MAX_CATALOG_BYTES}-byte limit")
        try:
            signers = [s.key_id for s in parse_strict(SignedEnvelope, envelope).signatures]
        except ValueError as exc:
            raise refuse(f"not a signed envelope: {exc}") from exc
        foreign = [key_id for key_id in signers if not key_id.startswith(PRODUCTION_KEY_PREFIX)]
        if foreign:
            raise refuse(f"signed by non-production key ids {foreign}")
        try:
            catalog = verify_catalog(
                envelope,
                keyring=self.keyring,
                game=self.game,
                channel=Channel.PRODUCTION,
                now=now,
                last_seen=None,
            ).catalog
        except OtaError as exc:
            raise refuse(f"does not verify: {exc.code.value}: {exc.detail}") from exc
        if catalog.issued_at > now + PRODUCTION_CLOCK_SKEW:
            raise refuse(f"issued in the future ({catalog.issued_at.isoformat()}, now {now})")
        if catalog.expires_at - now < PRODUCTION_MIN_VALIDITY:
            raise refuse(
                f"valid only until {catalog.expires_at.isoformat()}; a production catalog keeps "
                f"at least {PRODUCTION_MIN_VALIDITY.days} days of validity"
            )
        try:
            live = (
                None
                if live_envelope is None
                else verify_live_envelope(
                    live_envelope,
                    Channel.PRODUCTION,
                    keyring=self.keyring,
                    game=self.game,
                    now=now,
                    allow_expired=True,  # recovery: a lapsed live catalog may be succeeded
                )
            )
            ensure_valid_succession(live, catalog)
        except OtaPublisherError as exc:
            raise refuse(f"not the successor of the live catalog: {exc}") from exc
        return catalog


class S3BlobStore:
    """One channel's bucket as a :class:`~talent_data.ota.store.BlobStore`."""

    def __init__(
        self,
        client: S3Client,
        bucket: str,
        *,
        channel: Channel = Channel.STAGING,
        writable: bool = False,
        allow_delete: bool = False,
        catalog_guard: ProductionCatalogGuard | None = None,
    ) -> None:
        if bucket != PROFILES[channel].bucket:
            raise OtaPublisherError(
                f"bucket {bucket!r} is not the {channel.value} bucket {PROFILES[channel].bucket!r}"
            )
        if channel is Channel.PRODUCTION:
            if allow_delete:
                raise OtaPublisherError("production blobs are never deleted by this tool")
            if writable and catalog_guard is None:
                raise OtaPublisherError(
                    "a writable production store needs the production catalog guard"
                )
        elif catalog_guard is not None:
            raise OtaPublisherError("the production catalog guard is for the production store")
        self._client = client
        self.bucket = bucket
        self.channel = channel
        self.writable = writable
        self.allow_delete = allow_delete
        self._guard = catalog_guard
        self._catalog = catalog_key(channel)
        other = Channel.STAGING if channel is Channel.PRODUCTION else Channel.PRODUCTION
        self._other = other
        self._readonly_catalog = catalog_key(other)
        #: catalog key -> ETag last observed (``None``: observed absent)
        self._observed: dict[str, str | None] = {}

    def __repr__(self) -> str:
        return f"s3://{self.bucket}"

    # ------------------------------------------------------------------ scope

    def _readable(self, key: str) -> None:
        if BLOB_KEY.fullmatch(key) or key in (self._catalog, self._readonly_catalog):
            return
        raise S3StoreError(S3Failure.OUT_OF_SCOPE, key, "not an OTA object key of this store")

    def _writable(self, key: str) -> None:
        if key == self._readonly_catalog:
            raise S3StoreError(
                S3Failure.OUT_OF_SCOPE,
                key,
                f"the {self.channel.value} store never writes the {self._other.value} catalog",
            )
        if not (BLOB_KEY.fullmatch(key) or key == self._catalog):
            raise S3StoreError(S3Failure.OUT_OF_SCOPE, key, "not an OTA object key of this store")
        if not self.writable:
            raise S3StoreError(
                S3Failure.READ_ONLY, key, "store opened read-only (dry run: pass --execute)"
            )

    def _call(self, operation: str, key: str, **kwargs: Any) -> dict[str, Any]:
        method = getattr(self._client, operation)
        try:
            result: dict[str, Any] = method(Bucket=self.bucket, Key=key, **kwargs)
        except Exception as exc:
            kind, detail = _classify(exc)
            raise S3StoreError(kind, key, f"{operation}: {detail}") from exc
        return result

    # ------------------------------------------------------------------ BlobStore

    def exists(self, key: str) -> bool:
        self._readable(key)
        etag = self._head_etag(key)
        self._observe(key, etag)
        return etag is not None

    def get(self, key: str) -> bytes:
        self._readable(key)
        data, etag = self._fetch(key)
        self._observe(key, etag)
        return data

    def put(self, key: str, data: bytes, *, cache_control: str) -> None:
        self._writable(key)
        is_blob = BLOB_KEY.fullmatch(key) is not None
        expected_cache = BLOB_CACHE_CONTROL if is_blob else CATALOG_CACHE_CONTROL
        if cache_control != expected_cache:
            raise OtaPublisherError(
                f"object {key}: cache-control must be {expected_cache!r}, got {cache_control!r}"
            )
        content_type = content_type_of(key, data)
        md5 = hashlib.md5(data, usedforsecurity=False)
        args: dict[str, Any] = {
            "Body": data,
            "ContentLength": len(data),
            "ContentMD5": base64.b64encode(md5.digest()).decode("ascii"),
            "ContentType": content_type,
            "CacheControl": cache_control,
        }
        if is_blob:
            if hashlib.sha256(data).hexdigest() != key.removeprefix(BLOB_PREFIX):
                raise S3StoreError(S3Failure.IMMUTABLE, key, "bytes do not hash to the key")
            if self.exists(key):
                raise S3StoreError(
                    S3Failure.IMMUTABLE, key, "blob exists; immutable blobs are never rewritten"
                )
            args["IfNoneMatch"] = "*"
        else:
            if key not in self._observed:
                raise S3StoreError(
                    S3Failure.PRECONDITION_FAILED,
                    key,
                    "refusing an unconditional catalog write: read the live catalog first",
                )
            etag = self._observed[key]
            live = self._live_before_put(key, etag)
            if self._guard is not None:
                self._guard.check(data, live)
            if etag is None:
                args["IfNoneMatch"] = "*"
            else:
                args["IfMatch"] = etag
        response = self._call("put_object", key, **args)
        self._verify_put(key, data, md5.hexdigest(), content_type, cache_control)
        if not is_blob:
            self._observe(key, response.get("ETag"))
            if self.get(key) != data:
                raise S3StoreError(
                    S3Failure.VERIFY_FAILED, key, "catalog read back differs from the bytes put"
                )

    def list(self, prefix: str) -> list[str]:
        if prefix != BLOB_PREFIX:
            raise S3StoreError(S3Failure.OUT_OF_SCOPE, prefix, f"only {BLOB_PREFIX} is listed")
        keys = sorted(self._list_keys(prefix))
        strays = [key for key in keys if not BLOB_KEY.fullmatch(key)]
        if strays:
            raise S3StoreError(
                S3Failure.OUT_OF_SCOPE,
                prefix,
                f"unexpected non-blob objects under the blob prefix: {strays[:5]}",
            )
        return keys

    def delete(self, key: str) -> None:
        if not BLOB_KEY.fullmatch(key):
            raise S3StoreError(S3Failure.OUT_OF_SCOPE, key, "only unreferenced blobs are deleted")
        if self.channel is Channel.PRODUCTION:
            raise S3StoreError(
                S3Failure.READ_ONLY, key, "production blobs are never deleted by this tool"
            )
        if not (self.writable and self.allow_delete):
            raise S3StoreError(S3Failure.READ_ONLY, key, "deletion needs gc --execute --confirm")
        self._call("delete_object", key)

    # ------------------------------------------------------------------ internals

    def _observe(self, key: str, etag: str | None) -> None:
        if key == self._catalog:
            self._observed[key] = etag

    def _head_etag(self, key: str) -> str | None:
        """The object's ``ETag``; ``None`` when it is absent (404 only - a 403 raises)."""
        try:
            head = self._call("head_object", key)
        except S3StoreError as exc:
            if exc.kind is S3Failure.NOT_FOUND:
                return None
            raise
        etag = head.get("ETag")
        if not etag:  # fail closed: the state checks compare ETags
            raise S3StoreError(S3Failure.VERIFY_FAILED, key, "HEAD answered without an ETag")
        return str(etag)

    def _fetch(self, key: str) -> tuple[bytes, str | None]:
        response = self._call("get_object", key)
        body = response["Body"]
        try:
            data: bytes = body.read()
        except Exception as exc:
            kind, detail = _classify(exc)
            raise S3StoreError(kind, key, f"get_object body: {detail}") from exc
        finally:
            body.close()
        expected = response.get("ContentLength")
        if expected is not None and expected != len(data):
            raise S3StoreError(
                S3Failure.NETWORK, key, f"short read: {len(data)} of {expected} bytes"
            )
        etag = response.get("ETag")
        return data, None if etag is None else str(etag)

    def _live_before_put(self, key: str, observed: str | None) -> bytes | None:
        """The state check right before a catalog put - the ``If-Match`` VK ignores.

        The live catalog must still be the object this publisher observed; a production
        store also gets its bytes, which the guard verifies the new catalog against.
        """

        def moved(now: str | None) -> S3StoreError:
            return S3StoreError(
                S3Failure.PRECONDITION_FAILED,
                key,
                f"the live catalog changed since it was read (ETag {observed} -> {now}): a "
                "concurrent publication - aborting, nothing was overwritten",
            )

        current = self._head_etag(key)
        if current != observed:
            raise moved(current)
        if current is None or self._guard is None:
            return None
        data, etag = self._fetch(key)
        if etag != observed:
            raise moved(etag)
        return data

    def _list_keys(self, prefix: str) -> Iterator[str]:
        token: str | None = None
        while True:
            kwargs: dict[str, Any] = {
                "Bucket": self.bucket,
                "Prefix": prefix,
                "MaxKeys": _LIST_PAGE,
            }
            if token is not None:
                kwargs["ContinuationToken"] = token
            try:
                page = self._client.list_objects_v2(**kwargs)
            except Exception as exc:
                kind, detail = _classify(exc)
                raise S3StoreError(kind, prefix, f"list_objects_v2: {detail}") from exc
            for item in page.get("Contents", []):
                yield str(item["Key"])
            if not page.get("IsTruncated"):
                return
            token = page.get("NextContinuationToken")
            if not token:
                raise S3StoreError(
                    S3Failure.SERVICE, prefix, "truncated listing without a continuation token"
                )

    def _verify_put(
        self, key: str, data: bytes, md5_hex: str, content_type: str, cache_control: str
    ) -> None:
        head = self._call("head_object", key)
        problems = []
        if head.get("ContentLength") != len(data):
            problems.append(f"length {head.get('ContentLength')} != {len(data)}")
        if str(head.get("ETag", "")).strip('"') != md5_hex:
            problems.append(f"ETag {head.get('ETag')} != MD5 {md5_hex}")
        if head.get("ContentType") != content_type:
            problems.append(f"Content-Type {head.get('ContentType')!r} != {content_type!r}")
        if head.get("CacheControl") != cache_control:
            problems.append(f"Cache-Control {head.get('CacheControl')!r} != {cache_control!r}")
        if head.get("ContentEncoding"):
            problems.append(f"unexpected Content-Encoding {head.get('ContentEncoding')!r}")
        if problems:
            raise S3StoreError(S3Failure.VERIFY_FAILED, key, "; ".join(problems))
