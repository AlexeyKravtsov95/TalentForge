"""Parallel blob uploads never leave writes running or replay an uncertain catalog PUT."""

from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Event, Lock

import boto3
import pytest
from botocore.exceptions import ReadTimeoutError

from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import Channel
from talent_data.ota.s3_channels import PROFILES
from talent_data.ota.s3_store import S3Failure, S3Location, S3StoreError, connect
from talent_data.ota.store import (
    LocalDirectoryStore,
    blob_key,
    catalog_key,
    upload_blobs,
)
from talent_data.ota.verify import sha256_ref
from tests.unit.ota.test_ota_s3_store import InMemoryS3, store_over


def test_upload_limit_and_no_background_put_after_failure(tmp_path):
    barrier, release, failed = Barrier(4, timeout=5), Event(), Event()
    lock = Lock()
    active = peak = 0
    started = []
    blobs = {sha256_ref(str(n).encode()): str(n).encode() for n in range(20)}
    first = blob_key(min(blobs))

    class Store(LocalDirectoryStore):
        def put(self, key, data, *, cache_control):
            nonlocal active, peak
            with lock:
                active += 1
                peak = max(active, peak)
                started.append(key)
            try:
                barrier.wait()
                if key == first:
                    failed.set()
                    raise OtaPublisherError("upload failed")
                assert release.wait(5)
                super().put(key, data, cache_control=cache_control)
            finally:
                with lock:
                    active -= 1

    store = Store(tmp_path)
    with ThreadPoolExecutor(max_workers=1) as observer:
        result = observer.submit(upload_blobs, store, blobs, upload_workers=4)
        try:
            assert failed.wait(5)
            assert not result.done()
            assert len(started) == 4
        finally:
            release.set()
        with pytest.raises(OtaPublisherError, match="upload failed"):
            result.result(timeout=5)
    assert active == 0 and peak == 4


def test_unknown_blob_put_reads_exact_bytes_before_continuing():
    remote = InMemoryS3()
    store = store_over(remote)
    raw = b'{"immutable": true}'
    sha = sha256_ref(raw)

    def lose_response(key):
        raise ReadTimeoutError(endpoint_url="https://TEST.invalid")

    remote.after_put = lose_response
    assert upload_blobs(store, {sha: raw}) == [blob_key(sha)]
    assert store.get(blob_key(sha)) == raw
    assert remote.mutations() == [("put_object", blob_key(sha))]


def test_corrupt_existing_blob_never_overwritten():
    remote = InMemoryS3()
    raw = b'{"expected": true}'
    remote.raw_put(blob_key(sha256_ref(raw)), b"corrupt")
    with pytest.raises(OtaPublisherError, match="does not hash"):
        upload_blobs(store_over(remote), {sha256_ref(raw): raw})
    assert remote.mutations() == []


@pytest.mark.parametrize(("operation", "attempts"), [("put_object", 1), ("get_object", 3)])
def test_catalog_sdk_total_attempts_is_one(monkeypatch, operation, attempts):
    import botocore.endpoint

    from talent_data.ota import s3_store

    profile = PROFILES[Channel.STAGING]
    session = boto3.session.Session(
        aws_access_key_id="TEST-ACCESS",
        aws_secret_access_key="TEST-SECRET",
        region_name="us-east-1",
    )
    clients = []
    writes = []

    class Transport:
        def send(self, request):
            writes.append(request.method)
            raise ReadTimeoutError(endpoint_url="https://TEST.invalid")

    class Session:
        def client(self, *args, **kwargs):
            client = session.client(*args, **kwargs)
            client._endpoint.http_session = Transport()
            clients.append(client)
            return client

    monkeypatch.setattr(s3_store, "check_credentials", lambda *args, **kwargs: None)
    monkeypatch.setattr(boto3, "Session", lambda **kwargs: Session())
    monkeypatch.setattr(botocore.endpoint.time, "sleep", lambda seconds: None)
    store = connect(
        S3Location(profile.bucket, profile.endpoint_url, profile.region, profile.aws_profile),
        writable=True,
        allow_delete=False,
    )
    kwargs = {"Body": b"TEST-catalog"} if operation == "put_object" else {}
    with pytest.raises(S3StoreError) as error:
        store._call(operation, catalog_key(Channel.STAGING), **kwargs)
    assert error.value.kind is S3Failure.NETWORK
    assert len(writes) == attempts
    assert all(client.meta.config.connect_timeout == 5 for client in clients)
    assert all(client.meta.config.read_timeout == 15 for client in clients)


def test_upload_success_is_sorted_and_existing_exact_objects_are_not_rewritten(tmp_path):
    store = LocalDirectoryStore(tmp_path)
    blobs = {sha256_ref(raw): raw for raw in (b"z", b"a", b"k")}
    assert upload_blobs(store, blobs, upload_workers=4) == sorted(blob_key(sha) for sha in blobs)
    assert upload_blobs(store, blobs, upload_workers=1) == []
    assert all(store.get(blob_key(sha)) == raw for sha, raw in blobs.items())
