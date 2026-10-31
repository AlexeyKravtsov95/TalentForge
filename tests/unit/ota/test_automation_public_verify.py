"""Full public verification against TEST-signed real release snapshots, no network."""

import json
from collections import Counter
from datetime import timedelta

import httpx
import pytest

from talent_data.ota.automation.public_verify import verify_publication
from talent_data.ota.catalog import catalog_entry, next_catalog
from talent_data.ota.models import Channel, FileRole, OtaCatalog, SignedEnvelope
from talent_data.ota.release import build_release, serialize_manifest
from talent_data.ota.signing import build_envelope, sign_pae_der
from talent_data.ota.store import blob_key
from talent_data.ota.verify import parse_strict, sha256_ref, strict_b64decode
from tests.unit.ota.publisher_testkit import (
    BUILD_B,
    COMMIT,
    CREATED_AT,
    GAME,
    NOW,
    ROOT,
    VALIDATION_NOTE,
    derive_test_key,
)
from tests.unit.ota.test_ota_preflight import (
    CATALOG,
    KEYRING,
    ORIGIN,
    PROD_A,
    FakeOrigin,
    publication,
    real_release,
)


def check(origin):
    with httpx.Client(transport=httpx.MockTransport(origin.handler)) as client:
        return verify_publication(client, origin=ORIGIN, keyring=KEYRING, game=GAME, now=NOW)


def resign(objects, catalog):
    payload = catalog.model_dump_json(by_alias=True).encode()
    objects[CATALOG] = build_envelope(
        payload, {PROD_A: sign_pae_der(derive_test_key(PROD_A), payload)}
    )


def catalog_of(objects):
    return parse_strict(
        OtaCatalog, strict_b64decode(parse_strict(SignedEnvelope, objects[CATALOG]).payload)
    )


def alter_file(objects, transform):
    artifact = real_release()
    file = next(f for f in artifact.manifest.files if f.role == FileRole.TALENT_DATA)
    changed = transform(artifact.blobs[file.sha256])
    files = [
        f.model_copy(update={"sha256": sha256_ref(changed), "byte_length": len(changed)})
        if f == file
        else f
        for f in artifact.manifest.files
    ]
    manifest = artifact.manifest.model_copy(
        update={"files": files, "total_byte_length": sum(f.byte_length for f in files)}
    )
    raw = serialize_manifest(manifest)
    objects[blob_key(sha256_ref(changed))] = changed
    objects[blob_key(sha256_ref(raw))] = raw
    catalog = catalog_of(objects)
    entry = catalog_entry(raw, manifest, published_at=NOW)
    resign(objects, catalog.model_copy(update={"releases": [entry]}))


def test_full_publication_positive_control_and_shared_blobs_fetch_once():
    objects = publication()
    catalog = catalog_of(objects)
    # Same-build revision, same files: exercises cache and reference identity.
    artifact = real_release()
    manifest = artifact.manifest.model_copy(
        update={"release_id": artifact.manifest.release_id.rsplit("-r", 1)[0] + "-r2"}
    )
    raw = serialize_manifest(manifest)
    objects[blob_key(sha256_ref(raw))] = raw
    entry = catalog_entry(raw, manifest, published_at=NOW)
    resign(
        objects,
        catalog.model_copy(
            update={"releases": [*catalog.releases, entry], "active_release_id": entry.release_id}
        ),
    )
    origin = FakeOrigin(objects)
    counts = Counter()

    def handler(request):
        counts[request.url.path] += 1
        return origin.handler(request)

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = verify_publication(client, origin=ORIGIN, keyring=KEYRING, game=GAME, now=NOW)
    assert result.ok and result.active_release_id == entry.release_id
    assert result.sequence == 1 and result.catalog_payload_sha256
    assert counts["/" + CATALOG] == 2
    assert all(counts["/" + blob_key(f.sha256)] == 1 for f in manifest.files)


@pytest.mark.parametrize("fault", ["corrupt", "missing", "oversize", "redirect", "encoded"])
def test_valid_manifest_with_corrupt_blob_never_verifies_success(fault):
    objects = publication()
    file = real_release().manifest.files[0]
    key = blob_key(file.sha256)
    origin = FakeOrigin(objects)
    if fault == "corrupt":
        origin.objects[key] = b"x" * file.byte_length
    elif fault == "missing":
        del origin.objects[key]
    elif fault == "oversize":
        origin.edge_overrides[key] = (200, {}, b"x" * (file.byte_length + 1))
    elif fault == "redirect":
        origin.edge_overrides[key] = (302, {"location": "https://evil.invalid/steal"}, b"")
    else:
        origin.edge_overrides[key] = (200, {"content-encoding": "gzip"}, b"bad")
    result = check(origin)
    assert not result.ok and result.problem


@pytest.mark.parametrize("fault", ["identity", "duplicate"])
def test_content_verifier_rejects_valid_hashes_of_wrong_documents(fault):
    objects = publication()

    def transform(raw):
        if fault == "duplicate":
            return raw.replace(b'"schemaVersion": 1', b'"schemaVersion": 2, "schemaVersion": 1', 1)
        doc = json.loads(raw)
        doc["game"] = "other"
        return json.dumps(doc).encode()

    alter_file(objects, transform)
    result = check(FakeOrigin(objects))
    assert not result.ok
    assert (
        "CONTENT_IDENTITY_MISMATCH" if fault == "identity" else "CONTENT_INVALID"
    ) in result.problem


def test_retained_release_is_verified_even_when_active_is_healthy():
    objects = publication()
    old = catalog_of(objects)
    artifact = build_release(ROOT, COMMIT, GAME, BUILD_B, 1, CREATED_AT, VALIDATION_NOTE)
    update = next_catalog(
        old,
        game=GAME,
        channel=Channel.PRODUCTION,
        active_release_id=artifact.manifest.release_id,
        append=catalog_entry(artifact.manifest_bytes, artifact.manifest, published_at=NOW),
        issued_at=NOW,
        validity=timedelta(days=45),
    )
    objects.update({blob_key(s): b for s, b in artifact.blobs.items()})
    objects[blob_key(sha256_ref(artifact.manifest_bytes))] = artifact.manifest_bytes
    objects[CATALOG] = build_envelope(
        update.payload, {PROD_A: sign_pae_der(derive_test_key(PROD_A), update.payload)}
    )
    assert check(FakeOrigin(objects)).ok
    del objects[blob_key(real_release().manifest.files[0].sha256)]
    assert not check(FakeOrigin(objects)).ok


def test_catalog_change_during_blob_verification_rejects_snapshot():
    objects = publication()
    origin = FakeOrigin(objects)
    catalog = catalog_of(objects)
    requests = 0

    def handler(request):
        nonlocal requests
        if request.url.path == "/" + CATALOG:
            requests += 1
            if requests == 2:
                resign(origin.objects, catalog.model_copy(update={"sequence": 2}))
        return origin.handler(request)

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = verify_publication(client, origin=ORIGIN, keyring=KEYRING, game=GAME, now=NOW)
    assert not result.ok and "SNAPSHOT_CHANGED" in result.problem


def test_fetch_stops_reading_oversized_body_and_strips_client_credentials():
    class TooLarge(httpx.SyncByteStream):
        def __iter__(self):
            yield b"x" * (1024 * 1024 + 1)
            raise AssertionError("must stop after the catalog limit")

    def handler(request):
        assert request.method == "GET"
        assert "authorization" not in request.headers and "cookie" not in request.headers
        assert request.headers["accept-encoding"] == "identity"
        return httpx.Response(200, stream=TooLarge())

    with httpx.Client(
        transport=httpx.MockTransport(handler),
        auth=("user", "secret"),
        cookies={"session": "secret"},
    ) as client:
        result = verify_publication(client, origin=ORIGIN, keyring=KEYRING, game=GAME, now=NOW)
    assert not result.ok and "SIZE" in result.problem


def test_invalid_or_duplicate_catalog_is_rejected():
    objects = publication()
    objects[CATALOG] = objects[CATALOG].replace(
        b'"payloadType":', b'"payloadType": "wrong", "payloadType":', 1
    )
    assert not check(FakeOrigin(objects)).ok


def test_public_fetch_does_not_inherit_default_client_params_or_headers():
    origin = FakeOrigin(publication())

    def handler(request):
        assert request.url.query == b""
        assert "x-api-key" not in request.headers
        assert "cookie" not in request.headers and "authorization" not in request.headers
        return origin.handler(request)

    with httpx.Client(
        transport=httpx.MockTransport(handler),
        params={"token": "synthetic"},
        headers={"X-API-Key": "synthetic"},
        cookies={"session": "synthetic"},
        auth=("user", "synthetic"),
    ) as client:
        result = verify_publication(client, origin=ORIGIN, keyring=KEYRING, game=GAME, now=NOW)
    assert result.ok


def test_excessive_json_nesting_is_a_failure_for_full_and_lightweight_watch():
    from talent_data.notify.production_watch import observe_production
    from talent_data.prepare.production import ProductionStatus

    origin = FakeOrigin({CATALOG: b"[" * 16000 + b"]" * 16000})
    assert not check(origin).ok
    with httpx.Client(transport=httpx.MockTransport(origin.handler)) as client:
        result = observe_production(client, origin=ORIGIN, keyring=KEYRING, game=GAME, now=NOW)
    assert result.status is ProductionStatus.UNKNOWN
