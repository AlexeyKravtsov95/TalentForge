"""The S3-compatible remote store adapter (M5.OTA.4b) - offline, against an in-memory S3.

`InMemoryS3` answers like an S3 endpoint (botocore `ClientError`s with real status codes,
MD5 ETags, `Content-MD5` checks, conditional `If-None-Match`/`If-Match` puts) so the
adapter's contract is proven without the network:

* scope: blobs and the staging catalog only; the production catalog is never written
  (the production store and channel isolation: ``test_ota_s3_production.py``);
* mode: read-only unless writable, delete only with allow_delete;
* 404 / 403 / network are distinct failures, and 403 is never "absent";
* immutable blobs: hash-checked, never overwritten, verified after the put;
* contract headers (Cache-Control, Content-Type, no Content-Encoding);
* the catalog put is conditional on the observed ETag: a concurrent publication fails
  closed and overwrites nothing;
* the whole publication protocol and the CLI's `--backend s3` run over it.

The live VK Cloud bucket is exercised by the M5.OTA.4b report, not by this suite.
"""

from __future__ import annotations

import base64
import hashlib
import io
from collections.abc import Callable
from datetime import timedelta
from pathlib import Path
from typing import Any

import pytest
from botocore.exceptions import ClientError, EndpointConnectionError

from talent_data.cli.main import main
from talent_data.ota import s3_store
from talent_data.ota.catalog import catalog_entry, next_catalog
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import Channel
from talent_data.ota.release import (
    ReleaseArtifact,
    build_manifest,
    release_files,
    serialize_manifest,
)
from talent_data.ota.s3_store import (
    JSON_CONTENT_TYPE,
    PNG_CONTENT_TYPE,
    S3BlobStore,
    S3Failure,
    S3Location,
    S3StoreError,
    content_type_of,
)
from talent_data.ota.signing import build_envelope, load_keyring_file, sign_pae_der
from talent_data.ota.store import (
    BLOB_CACHE_CONTROL,
    BLOB_PREFIX,
    CATALOG_CACHE_CONTROL,
    blob_key,
    catalog_key,
    ensure_catalog_references,
    garbage_blob_keys,
    read_live_catalog,
    upload_blobs,
    write_catalog,
)
from talent_data.ota.verify import sha256_ref
from tests.unit.ota.publisher_testkit import (
    BUILD_A,
    COMMIT,
    GAME,
    NOW,
    PRIMARY,
    ROOT,
    derive_test_key,
    write_test_keyring,
)

BUCKET = "talentforge-ota-staging"
ENDPOINT = "https://hb.ru-msk.vkcloud-storage.ru"
STAGING = catalog_key(Channel.STAGING)
PRODUCTION = catalog_key(Channel.PRODUCTION)
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 16
CONTENTS = {
    "dataset/forever/1.60.1.70374/manifest.json": b'{"dataset": true}\n',
    "dataset/forever/1.60.1.70374/mage.json": b'{"class": "MAGE"}\n',
    "visuals/forever/1.60.1.70374/icons.json": b'{"icons": []}\n',
    "visuals/forever/1.60.1.70374/mage.json": b'{"visuals": []}\n',
    "tooltips/forever/1.60.1.70374/mage.json": b'{"tooltips": []}\n',
    "localization/forever/1.60.1.70374/ruRU/mage.json": b'{"locale": "ruRU"}\n',
}


def _error(status: int, code: str, operation: str) -> ClientError:
    return ClientError(
        {"Error": {"Code": code, "Message": ""}, "ResponseMetadata": {"HTTPStatusCode": status}},
        operation,
    )


class InMemoryS3:
    """An S3 endpoint in a dict, with the status codes and conditional semantics of S3."""

    def __init__(
        self,
        *,
        stored_encoding: str | None = None,
        bucket: str = BUCKET,
        honour_conditions: bool = True,
    ) -> None:
        self.bucket = bucket
        #: ``False`` answers like VK Cloud: If-None-Match / If-Match are ignored (200)
        self.honour_conditions = honour_conditions
        self.objects: dict[str, dict[str, Any]] = {}
        self.calls: list[tuple[str, str]] = []
        self.denied: set[str] = set()
        self.offline = False
        self.stored_encoding = stored_encoding
        #: runs inside put_object before the write: simulates a concurrent publisher
        self.before_put: Callable[[str], None] | None = None
        #: runs inside put_object after the write: a rival landing right after this one
        self.after_put: Callable[[str], None] | None = None

    def _enter(self, operation: str, key: str, bucket: str) -> None:
        assert bucket == self.bucket
        self.calls.append((operation, key))
        if self.offline:
            raise EndpointConnectionError(endpoint_url=ENDPOINT)
        if key in self.denied:
            raise _error(403, "AccessDenied", operation)

    def raw_put(self, key: str, data: bytes, **meta: Any) -> None:
        self.objects[key] = {
            "data": data,
            "ETag": '"' + hashlib.md5(data, usedforsecurity=False).hexdigest() + '"',
            **meta,
        }

    def head_object(self, *, Bucket: str, Key: str) -> dict[str, Any]:
        self._enter("head_object", Key, Bucket)
        if Key not in self.objects:
            raise _error(404, "404", "HeadObject")
        obj = self.objects[Key]
        return {
            "ContentLength": len(obj["data"]),
            "ETag": obj["ETag"],
            "ContentType": obj.get("ContentType"),
            "CacheControl": obj.get("CacheControl"),
            "ContentEncoding": obj.get("ContentEncoding"),
        }

    def get_object(self, *, Bucket: str, Key: str) -> dict[str, Any]:
        self._enter("get_object", Key, Bucket)
        if Key not in self.objects:
            raise _error(404, "NoSuchKey", "GetObject")
        obj = self.objects[Key]
        return {
            "Body": io.BytesIO(obj["data"]),
            "ContentLength": len(obj["data"]),
            "ETag": obj["ETag"],
        }

    def put_object(
        self,
        *,
        Bucket: str,
        Key: str,
        Body: bytes,
        ContentLength: int,
        ContentMD5: str,
        ContentType: str,
        CacheControl: str,
        IfNoneMatch: str | None = None,
        IfMatch: str | None = None,
    ) -> dict[str, Any]:
        self._enter("put_object", Key, Bucket)
        if ContentLength != len(Body):
            raise _error(400, "IncompleteBody", "PutObject")
        if base64.b64decode(ContentMD5) != hashlib.md5(Body, usedforsecurity=False).digest():
            raise _error(400, "BadDigest", "PutObject")
        if self.before_put is not None:
            hook, self.before_put = self.before_put, None
            hook(Key)
        current = self.objects.get(Key)
        if self.honour_conditions:
            if IfNoneMatch == "*" and current is not None:
                raise _error(412, "PreconditionFailed", "PutObject")
            if IfMatch is not None and (current is None or current["ETag"] != IfMatch):
                raise _error(412, "PreconditionFailed", "PutObject")
        self.raw_put(
            Key,
            Body,
            ContentType=ContentType,
            CacheControl=CacheControl,
            ContentEncoding=self.stored_encoding,
        )
        etag = self.objects[Key]["ETag"]
        if self.after_put is not None:
            hook, self.after_put = self.after_put, None
            hook(Key)
        return {"ETag": etag}

    def delete_object(self, *, Bucket: str, Key: str) -> dict[str, Any]:
        self._enter("delete_object", Key, Bucket)
        self.objects.pop(Key, None)
        return {}

    def list_objects_v2(
        self, *, Bucket: str, Prefix: str, MaxKeys: int, ContinuationToken: str | None = None
    ) -> dict[str, Any]:
        self._enter("list_objects_v2", Prefix, Bucket)
        keys = sorted(key for key in self.objects if key.startswith(Prefix))
        start = int(ContinuationToken or 0)
        page = keys[start : start + MaxKeys]
        truncated = start + MaxKeys < len(keys)
        return {
            "Contents": [{"Key": key} for key in page],
            "IsTruncated": truncated,
            **({"NextContinuationToken": str(start + MaxKeys)} if truncated else {}),
        }

    def mutations(self) -> list[tuple[str, str]]:
        return [call for call in self.calls if call[0] in ("put_object", "delete_object")]


@pytest.fixture(autouse=True)
def no_environment_credentials(monkeypatch: pytest.MonkeyPatch) -> None:
    """Writes refuse AWS_* credentials in the environment; a test machine may have some."""
    for name in s3_store.ENV_CREDENTIALS:
        monkeypatch.delenv(name, raising=False)


@pytest.fixture()
def s3() -> InMemoryS3:
    return InMemoryS3()


def store_over(s3: InMemoryS3, *, writable: bool = True, allow_delete: bool = False) -> S3BlobStore:
    return S3BlobStore(s3, BUCKET, writable=writable, allow_delete=allow_delete)


def blob(data: bytes) -> tuple[str, bytes]:
    return blob_key(sha256_ref(data)), data


@pytest.fixture(scope="module")
def artifact() -> ReleaseArtifact:
    files = release_files(CONTENTS)
    manifest = build_manifest(
        game="forever",
        client_build="1.60.1.70374",
        revision=1,
        min_data_level=1,
        created_at=NOW,
        repository_commit="0123456789abcdef0123456789abcdef01234567",
        validation_note="s3 store test",
        files=files,
    )
    return ReleaseArtifact(
        manifest=manifest,
        manifest_bytes=serialize_manifest(manifest),
        snapshot=CONTENTS,
        blobs={f.sha256: CONTENTS[f.path] for f in files},
    )


@pytest.fixture(scope="module")
def keyring(tmp_path_factory: pytest.TempPathFactory) -> dict[str, object]:
    path, _ = write_test_keyring(tmp_path_factory.mktemp("keys"))
    return load_keyring_file(path)


def staging_update(artifact: ReleaseArtifact) -> tuple[Any, bytes]:
    entry = catalog_entry(artifact.manifest_bytes, artifact.manifest, published_at=NOW)
    update = next_catalog(
        None,
        game="forever",
        channel=Channel.STAGING,
        active_release_id=entry.release_id,
        append=entry,
        issued_at=NOW,
        validity=timedelta(days=45),
    )
    signature = sign_pae_der(derive_test_key(PRIMARY), update.payload)
    return update, build_envelope(update.payload, {PRIMARY: signature})


def upload_release(store: S3BlobStore, artifact: ReleaseArtifact) -> None:
    sha, data = artifact.manifest_blob
    upload_blobs(store, {sha: data, **artifact.blobs})


# --------------------------------------------------------------------------- scope and mode


def test_the_production_catalog_is_never_written_and_no_request_is_made(s3) -> None:
    store = store_over(s3)
    with pytest.raises(S3StoreError) as raised:
        store.put(PRODUCTION, b"{}", cache_control=CATALOG_CACHE_CONTROL)
    assert raised.value.kind is S3Failure.OUT_OF_SCOPE
    assert s3.calls == []
    # read-only access answers "absent" so gates and gc fail closed if one ever appears
    assert store.exists(PRODUCTION) is False


@pytest.mark.parametrize(
    "key",
    [
        "v1/channels/beta/catalog.json",
        "v1/blobs/sha256/ABC",
        "v1/blobs/sha256/" + "0" * 63,
        "v1/blobs/sha256/" + "0" * 64 + "/x",
        "v1/../outside",
        "outside/x",
        "",
    ],
)
def test_keys_outside_the_two_patterns_are_refused_for_every_operation(s3, key: str) -> None:
    store = store_over(s3, allow_delete=True)
    for operation in (
        lambda: store.get(key),
        lambda: store.exists(key),
        lambda: store.put(key, b"x", cache_control=BLOB_CACHE_CONTROL),
        lambda: store.delete(key),
    ):
        with pytest.raises(S3StoreError) as raised:
            operation()
        assert raised.value.kind is S3Failure.OUT_OF_SCOPE
    assert s3.calls == []


def test_the_catalog_is_never_deleted_and_only_the_blob_prefix_is_listed(s3) -> None:
    store = store_over(s3, allow_delete=True)
    with pytest.raises(S3StoreError, match="OUT_OF_SCOPE"):
        store.delete(STAGING)
    with pytest.raises(S3StoreError, match="OUT_OF_SCOPE"):
        store.list("v1/")
    assert s3.calls == []


def test_a_read_only_store_cannot_write_and_only_gc_mode_can_delete(s3) -> None:
    key, data = blob(b'{"a": 1}')
    with pytest.raises(S3StoreError) as raised:
        store_over(s3, writable=False).put(key, data, cache_control=BLOB_CACHE_CONTROL)
    assert raised.value.kind is S3Failure.READ_ONLY
    with pytest.raises(S3StoreError) as raised:
        store_over(s3, writable=True, allow_delete=False).delete(key)
    assert raised.value.kind is S3Failure.READ_ONLY
    assert s3.mutations() == []


def test_the_staging_bucket_is_never_the_production_store(s3) -> None:
    with pytest.raises(OtaPublisherError, match="not the production bucket"):
        S3BlobStore(s3, BUCKET, channel=Channel.PRODUCTION)
    with pytest.raises(OtaPublisherError, match="is the staging bucket; refusing it"):
        S3Location(BUCKET, ENDPOINT, "ru-msk", channel=Channel.PRODUCTION)
    with pytest.raises(OtaPublisherError, match="https://"):
        S3Location(BUCKET, "http://hb.ru-msk.vkcloud-storage.ru", "ru-msk")
    assert s3.calls == []


# --------------------------------------------------------------------------- failure kinds


def test_404_403_and_network_failures_are_distinct(s3) -> None:
    store = store_over(s3)
    key, data = blob(b'{"present": true}')
    s3.raw_put(key, data)

    missing = blob_key(sha256_ref(b"missing"))
    assert store.exists(missing) is False
    with pytest.raises(S3StoreError) as raised:
        store.get(missing)
    assert raised.value.kind is S3Failure.NOT_FOUND
    assert isinstance(raised.value, OtaPublisherError)  # existing callers keep working

    s3.denied.add(key)
    with pytest.raises(S3StoreError) as raised:
        store.exists(key)  # a 403 is never "absent"
    assert raised.value.kind is S3Failure.ACCESS_DENIED
    with pytest.raises(S3StoreError) as raised:
        store.get(key)
    assert raised.value.kind is S3Failure.ACCESS_DENIED

    s3.denied.clear()
    s3.offline = True
    with pytest.raises(S3StoreError) as raised:
        store.exists(key)
    assert raised.value.kind is S3Failure.NETWORK


def test_a_403_on_upload_never_lets_a_blob_be_written_blind(s3) -> None:
    key, data = blob(b'{"blind": true}')
    s3.denied.add(key)
    with pytest.raises(S3StoreError) as raised:
        store_over(s3).put(key, data, cache_control=BLOB_CACHE_CONTROL)
    assert raised.value.kind is S3Failure.ACCESS_DENIED
    assert s3.mutations() == []


# --------------------------------------------------------------------------- blobs


def test_blob_puts_carry_the_contract_headers(s3) -> None:
    store = store_over(s3)
    json_key, json_data = blob(b'{"talent": "data"}\n')
    png_key, png_data = blob(PNG)
    store.put(json_key, json_data, cache_control=BLOB_CACHE_CONTROL)
    store.put(png_key, png_data, cache_control=BLOB_CACHE_CONTROL)
    assert s3.objects[json_key]["ContentType"] == JSON_CONTENT_TYPE == "application/json"
    assert s3.objects[png_key]["ContentType"] == PNG_CONTENT_TYPE == "image/png"
    for key in (json_key, png_key):
        assert s3.objects[key]["CacheControl"] == "public, max-age=31536000, immutable"
        assert s3.objects[key]["ContentEncoding"] is None
    assert store.get(json_key) == json_data


def test_content_types() -> None:
    assert content_type_of(STAGING, b"{}") == "application/json"
    assert content_type_of(blob_key(sha256_ref(b"[]")), b"[]") == "application/json"
    assert content_type_of(blob_key(sha256_ref(PNG)), PNG) == "image/png"
    assert content_type_of(blob_key(sha256_ref(b"{no")), b"{no") == "application/octet-stream"


def test_an_existing_blob_is_never_overwritten(s3) -> None:
    store = store_over(s3)
    key, data = blob(b'{"once": true}')
    store.put(key, data, cache_control=BLOB_CACHE_CONTROL)
    with pytest.raises(S3StoreError) as raised:
        store.put(key, data, cache_control=BLOB_CACHE_CONTROL)
    assert raised.value.kind is S3Failure.IMMUTABLE
    assert s3.mutations() == [("put_object", key)]


def test_a_blob_created_concurrently_is_not_overwritten(s3) -> None:
    store = store_over(s3)
    key, data = blob(b'{"race": true}')
    s3.before_put = lambda k: s3.raw_put(k, data)  # another writer wins between HEAD and PUT
    with pytest.raises(S3StoreError) as raised:
        store.put(key, data, cache_control=BLOB_CACHE_CONTROL)
    assert raised.value.kind is S3Failure.PRECONDITION_FAILED


def test_a_blob_must_hash_to_its_key(s3) -> None:
    key, _ = blob(b'{"right": true}')
    with pytest.raises(S3StoreError) as raised:
        store_over(s3).put(key, b'{"wrong": true}', cache_control=BLOB_CACHE_CONTROL)
    assert raised.value.kind is S3Failure.IMMUTABLE
    assert s3.mutations() == []


def test_the_cache_control_must_be_the_contract_value(s3) -> None:
    key, data = blob(b'{"cache": true}')
    with pytest.raises(OtaPublisherError, match="cache-control"):
        store_over(s3).put(key, data, cache_control=CATALOG_CACHE_CONTROL)
    with pytest.raises(OtaPublisherError, match="cache-control"):
        store_over(s3).put(STAGING, b"{}", cache_control=BLOB_CACHE_CONTROL)


def test_a_stored_content_encoding_fails_the_put_verification() -> None:
    # what VK Cloud does to an aws-chunked upload: the object is served with
    # `Content-Encoding: aws-chunked` - not something an HTTP client may decode
    s3 = InMemoryS3(stored_encoding="aws-chunked")
    key, data = blob(b'{"chunked": true}')
    with pytest.raises(S3StoreError) as raised:
        store_over(s3).put(key, data, cache_control=BLOB_CACHE_CONTROL)
    assert raised.value.kind is S3Failure.VERIFY_FAILED
    assert "Content-Encoding" in str(raised.value)


# --------------------------------------------------------------------------- catalog


def test_a_catalog_write_needs_an_observation_and_is_conditional(s3) -> None:
    store = store_over(s3)
    with pytest.raises(S3StoreError) as raised:
        store.put(STAGING, b'{"c": 1}', cache_control=CATALOG_CACHE_CONTROL)
    assert raised.value.kind is S3Failure.PRECONDITION_FAILED
    assert s3.mutations() == []

    assert store.exists(STAGING) is False  # observed absent -> If-None-Match: *
    store.put(STAGING, b'{"c": 1}', cache_control=CATALOG_CACHE_CONTROL)
    assert s3.objects[STAGING]["ContentType"] == "application/json"
    assert s3.objects[STAGING]["CacheControl"] == "public, max-age=300"

    other = store_over(s3)
    assert other.get(STAGING) == b'{"c": 1}'  # observed ETag -> If-Match
    store.put(STAGING, b'{"c": 2}', cache_control=CATALOG_CACHE_CONTROL)  # store moves on
    with pytest.raises(S3StoreError) as raised:
        other.put(STAGING, b'{"c": 3}', cache_control=CATALOG_CACHE_CONTROL)
    assert raised.value.kind is S3Failure.PRECONDITION_FAILED
    assert s3.objects[STAGING]["data"] == b'{"c": 2}'  # nothing overwritten


def test_a_concurrent_publication_aborts_and_overwrites_nothing(s3, artifact, keyring) -> None:
    store = store_over(s3)
    upload_release(store, artifact)
    update, envelope = staging_update(artifact)
    rival = b'{"rival": "publisher"}\n'
    # the rival writes after this publisher's sequence re-read, right before its PUT
    s3.before_put = lambda key: s3.raw_put(key, rival)
    with pytest.raises(S3StoreError) as raised:
        write_catalog(
            store,
            Channel.STAGING,
            envelope,
            base_sequence=update.base_sequence,
            keyring=keyring,
            game="forever",
            now=NOW,
        )
    assert raised.value.kind is S3Failure.PRECONDITION_FAILED
    assert s3.objects[STAGING]["data"] == rival


# --------------------------------------------------------------------------- protocol


def test_the_publication_protocol_runs_over_the_s3_store(
    s3, artifact, keyring, monkeypatch
) -> None:
    monkeypatch.setattr(s3_store, "_LIST_PAGE", 2)  # force paginated listings
    store = store_over(s3)
    upload_release(store, artifact)
    update, envelope = staging_update(artifact)
    ensure_catalog_references(store, update.catalog)
    write_catalog(
        store,
        Channel.STAGING,
        envelope,
        base_sequence=update.base_sequence,
        keyring=keyring,
        game="forever",
        now=NOW,
    )

    puts = [key for operation, key in s3.mutations() if operation == "put_object"]
    assert puts[-1] == STAGING  # the catalog is the last write
    assert all(key.startswith(BLOB_PREFIX) for key in puts[:-1])
    live = read_live_catalog(
        store_over(s3), Channel.STAGING, keyring=keyring, game="forever", now=NOW
    )
    assert live is not None and live.sequence == 1

    stray_key, stray = blob(b'{"unreferenced": true}')
    s3.raw_put(stray_key, stray)
    referenced, garbage = garbage_blob_keys(store, {Channel.STAGING: live})
    assert garbage == [stray_key]
    assert len(referenced) == len(artifact.blobs) + 1
    store_over(s3, allow_delete=True).delete(stray_key)
    assert stray_key not in s3.objects


def test_a_stray_object_under_the_blob_prefix_stops_listing(s3) -> None:
    s3.raw_put(BLOB_PREFIX + "not-a-hash", b"x")
    with pytest.raises(S3StoreError, match="unexpected non-blob"):
        store_over(s3).list(BLOB_PREFIX)


# --------------------------------------------------------------------------- CLI

S3_FLAGS = [
    "--backend",
    "s3",
    "--s3-bucket",
    BUCKET,
    "--s3-endpoint",
    ENDPOINT,
    "--s3-region",
    "ru-msk",
    "--aws-profile",
    "talentforge-staging",
]
NOW_ARG = "2026-10-18T12:00:00Z"


@pytest.fixture()
def cli_s3(s3, monkeypatch) -> list[dict[str, Any]]:
    """`connect` over the in-memory S3; records how each command opened the store."""
    opened: list[dict[str, Any]] = []

    def fake_connect(
        location: S3Location,
        *,
        writable: bool,
        allow_delete: bool,
        catalog_guard: s3_store.ProductionCatalogGuard | None = None,
    ) -> S3BlobStore:
        opened.append(
            {
                "location": location,
                "writable": writable,
                "allow_delete": allow_delete,
                "guard": catalog_guard,
            }
        )
        return S3BlobStore(
            s3,
            location.bucket,
            channel=location.channel,
            writable=writable,
            allow_delete=allow_delete,
            catalog_guard=catalog_guard,
        )

    monkeypatch.setattr(s3_store, "connect", fake_connect)
    return opened


def test_cli_stage_over_s3_is_a_read_only_dry_run_until_execute(
    s3, cli_s3, tmp_path: Path, capsys
) -> None:
    keyring_path, pem = write_test_keyring(tmp_path / "keys")
    out = tmp_path / "artifacts"
    common = ["--repo", str(ROOT), "--game", GAME]
    build = ["ota", "build-release", *common, "--build", BUILD_A, "--commit", COMMIT]
    assert main([*build, "--created-at", "2026-10-17T23:00:00Z", "--out", str(out)]) == 0
    manifest = out / f"{BUILD_A}-r1" / "release.json"
    keys = ["--keyring", str(keyring_path)]
    stage = ["ota", "stage", *common, "--manifest", str(manifest), *S3_FLAGS, *keys]
    stage += ["--key", str(pem), "--key-id", PRIMARY, "--now", NOW_ARG]

    assert main(stage) == 0  # dry run
    assert cli_s3[-1]["writable"] is False
    assert s3.mutations() == []
    assert "dry run: nothing written" in capsys.readouterr().out

    assert main([*stage, "--execute"]) == 0
    assert cli_s3[-1]["writable"] is True
    assert cli_s3[-1]["allow_delete"] is False
    assert cli_s3[-1]["location"].profile == "talentforge-staging"
    assert s3.mutations()[-1] == ("put_object", STAGING)
    assert PRODUCTION not in s3.objects

    verify = ["ota", "verify-live", *common, *S3_FLAGS, *keys, "--channel", "staging"]
    assert main([*verify, "--now", NOW_ARG]) == 0
    assert cli_s3[-1]["writable"] is False

    gc = ["ota", "gc", *common, *S3_FLAGS, *keys, "--now", NOW_ARG]
    assert main([*gc, "--execute"]) == 0  # without --confirm: still no right to delete
    assert cli_s3[-1]["allow_delete"] is False
    assert main([*gc, "--execute", "--confirm"]) == 0
    assert cli_s3[-1]["allow_delete"] is True
    assert not [m for m in s3.mutations() if m[0] == "delete_object"]  # nothing unreferenced


@pytest.mark.parametrize(
    "argv",
    [
        ["verify-live", "--channel", "production"],
        ["rollback", "--channel", "production", "--active", "x"],
        ["resign"],  # defaults to production
        ["promote", "--manifest", "release.json"],
    ],
)
def test_cli_refuses_the_staging_bucket_for_production(
    cli_s3, tmp_path: Path, argv, capsys
) -> None:
    keyring_path, _ = write_test_keyring(tmp_path / "keys")
    assert main(["ota", *argv, *S3_FLAGS, "--keyring", str(keyring_path)]) == 1
    err = capsys.readouterr().err
    assert "is the staging bucket; refusing it for the production channel" in err
    assert cli_s3 == []  # refused before any connection


def test_cli_s3_backend_needs_every_location_flag(cli_s3, tmp_path: Path, capsys) -> None:
    keyring_path, _ = write_test_keyring(tmp_path / "keys")
    verify = ["ota", "verify-live", "--channel", "staging", "--keyring", str(keyring_path)]
    assert main([*verify, "--backend", "s3", "--s3-bucket", BUCKET]) == 1
    assert "--s3-endpoint, --s3-region" in capsys.readouterr().err
    assert main(verify) == 1
    assert "--store DIR is required" in capsys.readouterr().err
    assert cli_s3 == []
