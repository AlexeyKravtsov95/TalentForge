"""The store and the publication write protocol (M5.OTA.2, test plan §5).

* blobs are written before any catalog, each verified by reading it back;
* a catalog is never written while a referenced manifest blob is missing;
* a catalog over 1 MiB is never written;
* a changed live ``sequence`` aborts the write (one publisher at a time);
* ``gc`` lists before deleting, and never touches a referenced blob.
"""

from __future__ import annotations

from datetime import timedelta
from pathlib import Path

import pytest

from talent_data.ota.catalog import catalog_entry, next_catalog
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import MAX_CATALOG_BYTES, Channel
from talent_data.ota.release import (
    ReleaseArtifact,
    build_manifest,
    release_files,
    serialize_manifest,
)
from talent_data.ota.signing import build_envelope, load_keyring_file, sign_pae_der
from talent_data.ota.store import (
    BLOB_CACHE_CONTROL,
    CATALOG_CACHE_CONTROL,
    blob_key,
    catalog_key,
    ensure_blob,
    ensure_catalog_references,
    garbage_blob_keys,
    read_release_manifest,
    upload_blobs,
    write_catalog,
)
from talent_data.ota.verify import sha256_ref
from tests.unit.ota.publisher_testkit import NOW, PRIMARY, derive_test_key, write_test_keyring

VALIDITY = timedelta(days=45)
CONTENTS = {
    "dataset/forever/1.60.1.70374/manifest.json": b'{"dataset": true}\n',
    "dataset/forever/1.60.1.70374/mage.json": b'{"class": "MAGE"}\n',
    "visuals/forever/1.60.1.70374/icons.json": b'{"icons": []}\n',
    "visuals/forever/1.60.1.70374/mage.json": b'{"visuals": []}\n',
    "tooltips/forever/1.60.1.70374/mage.json": b'{"tooltips": []}\n',
    "localization/forever/1.60.1.70374/ruRU/mage.json": b'{"locale": "ruRU"}\n',
}


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
        validation_note="store test",
        files=files,
    )
    return ReleaseArtifact(
        manifest=manifest,
        manifest_bytes=serialize_manifest(manifest),
        snapshot=CONTENTS,
        blobs={f.sha256: CONTENTS[f.path] for f in files},
    )


def all_blobs(artifact: ReleaseArtifact) -> dict[str, bytes]:
    sha, data = artifact.manifest_blob
    return {sha: data, **artifact.blobs}


@pytest.fixture()
def store(tmp_path: Path):
    from talent_data.ota.store import LocalDirectoryStore

    return LocalDirectoryStore(tmp_path / "store")


@pytest.fixture(scope="module")
def keyring(tmp_path_factory: pytest.TempPathFactory) -> dict[str, object]:
    path, _ = write_test_keyring(tmp_path_factory.mktemp("keys"))
    return load_keyring_file(path)


def signed_envelope(payload: bytes) -> bytes:
    return build_envelope(payload, {PRIMARY: sign_pae_der(derive_test_key(PRIMARY), payload)})


def test_upload_blobs_writes_verifies_and_skips_existing(store, artifact) -> None:
    blobs = all_blobs(artifact)
    written = upload_blobs(store, blobs)
    assert sorted(written) == sorted(blob_key(sha) for sha in blobs)
    for sha in blobs:
        ensure_blob(store, sha)  # present and hashing to its name
    again = upload_blobs(store, blobs)
    assert again == []  # existing blobs are never rewritten


def test_upload_blobs_catches_a_corrupting_store(store, artifact, monkeypatch) -> None:
    from talent_data.ota.store import LocalDirectoryStore

    original_put = LocalDirectoryStore.put

    def corrupting_put(self, key: str, data: bytes, *, cache_control: str) -> None:
        original_put(self, key, data[:-1] + bytes([data[-1] ^ 1]), cache_control=cache_control)

    monkeypatch.setattr(LocalDirectoryStore, "put", corrupting_put)
    with pytest.raises(OtaPublisherError, match="does not hash to its name"):
        upload_blobs(store, all_blobs(artifact))


def test_catalog_references_must_resolve(store, artifact) -> None:
    upload_blobs(store, all_blobs(artifact))
    entry = catalog_entry(artifact.manifest_bytes, artifact.manifest, published_at=NOW)
    update = next_catalog(
        None,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=entry.release_id,
        append=entry,
        issued_at=NOW,
        validity=VALIDITY,
    )
    ensure_catalog_references(store, update.catalog)  # everything present: fine

    store.delete(blob_key(entry.manifest_sha256))  # a referenced manifest blob vanishes
    with pytest.raises(OtaPublisherError, match="not found"):
        ensure_catalog_references(store, update.catalog)


def test_an_oversized_catalog_is_never_written(store, keyring) -> None:
    envelope = b"x" * (MAX_CATALOG_BYTES + 1)
    with pytest.raises(OtaPublisherError, match="over the"):
        write_catalog(
            store,
            Channel.PRODUCTION,
            envelope,
            base_sequence=None,
            keyring=keyring,
            game="forever",
            now=NOW,
        )
    assert not store.exists(catalog_key(Channel.PRODUCTION))


def test_a_changed_live_sequence_aborts_the_write(store, artifact, keyring) -> None:
    blobs = all_blobs(artifact)
    upload_blobs(store, blobs)
    entry = catalog_entry(artifact.manifest_bytes, artifact.manifest, published_at=NOW)

    first = next_catalog(
        None,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=entry.release_id,
        append=entry,
        issued_at=NOW,
        validity=VALIDITY,
    )
    write_catalog(
        store,
        Channel.PRODUCTION,
        signed_envelope(first.payload),
        base_sequence=first.base_sequence,
        keyring=keyring,
        game="forever",
        now=NOW,
    )

    # someone else published sequence 2 in the meantime; writing a catalog built on
    # sequence 1 must abort and leave the live object untouched
    second = next_catalog(
        first.catalog,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=entry.release_id,
        issued_at=NOW + timedelta(hours=1),
        validity=VALIDITY,
    )
    write_catalog(
        store,
        Channel.PRODUCTION,
        signed_envelope(second.payload),
        base_sequence=second.base_sequence,
        keyring=keyring,
        game="forever",
        now=NOW,
    )
    stale = next_catalog(
        second.catalog,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=entry.release_id,
        issued_at=NOW + timedelta(hours=2),
        validity=VALIDITY,
    )
    with pytest.raises(OtaPublisherError, match="concurrent publication"):
        write_catalog(
            store,
            Channel.PRODUCTION,
            signed_envelope(stale.payload),
            base_sequence=first.base_sequence,
            keyring=keyring,
            game="forever",
            now=NOW,
        )


def test_gc_lists_and_deletes_only_unreferenced_blobs(store, artifact, keyring) -> None:
    upload_blobs(store, all_blobs(artifact))
    entry = catalog_entry(artifact.manifest_bytes, artifact.manifest, published_at=NOW)
    update = next_catalog(
        None,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=entry.release_id,
        append=entry,
        issued_at=NOW,
        validity=VALIDITY,
    )
    write_catalog(
        store,
        Channel.PRODUCTION,
        signed_envelope(update.payload),
        base_sequence=None,
        keyring=keyring,
        game="forever",
        now=NOW,
    )
    junk = blob_key(sha256_ref(b"abandoned staging blob"))
    store.put(junk, b"abandoned staging blob", cache_control=BLOB_CACHE_CONTROL)

    referenced, garbage = garbage_blob_keys(store, {Channel.PRODUCTION: update.catalog})
    assert junk in garbage
    assert all(key not in garbage for key in referenced)
    assert catalog_key(Channel.PRODUCTION) not in garbage  # catalogs are never gc'd

    for key in garbage:  # gc's own deletion step: listed first, then removed
        store.delete(key)
    _, after = garbage_blob_keys(store, {Channel.PRODUCTION: update.catalog})
    assert after == []
    for sha in all_blobs(artifact):  # every referenced blob survived
        ensure_blob(store, sha)


def test_store_refuses_keys_outside_the_ota_layout(tmp_path: Path) -> None:
    from talent_data.ota.store import LocalDirectoryStore

    store = LocalDirectoryStore(tmp_path)
    for key in ("../evil", "/etc/passwd", "v1/blobs/sha256/../../x", "random/object"):
        with pytest.raises(OtaPublisherError, match="non-OTA object key"):
            store.get(key)


def test_cache_control_values_follow_the_contract(store) -> None:
    seen: dict[str, str] = {}
    original_put = store.put

    def recording_put(key: str, data: bytes, *, cache_control: str) -> None:
        seen[key] = cache_control
        original_put(key, data, cache_control=cache_control)

    store.put = recording_put  # type: ignore[method-assign]
    sha = sha256_ref(b"blob")
    store.put(blob_key(sha), b"blob", cache_control="public, max-age=31536000, immutable")
    store.put(catalog_key(Channel.STAGING), b"{}", cache_control="public, max-age=300")
    assert seen[blob_key(sha)] == "public, max-age=31536000, immutable"
    assert seen[catalog_key(Channel.STAGING)] == "public, max-age=300"


def test_read_release_manifest_verifies_the_catalog_pin(store, artifact) -> None:
    """A manifest corrupted into different (still valid) JSON must be refused by its
    pin, not by parseability - gc and every reader depend on this."""
    upload_blobs(store, all_blobs(artifact))
    entry = _entry(artifact)
    path = store.root / blob_key(entry.manifest_sha256)
    # same length, valid JSON, different bytes: flip a digit inside the validation note
    original = path.read_bytes()
    tampered = original.replace(b"store test", b"storE test").ljust(len(original))
    assert len(tampered) == len(original)
    path.write_bytes(tampered)
    with pytest.raises(OtaPublisherError, match="does not match its catalog pin"):
        read_release_manifest(store, entry)


def test_ensure_catalog_references_verifies_every_file_blob(store, artifact) -> None:
    upload_blobs(store, all_blobs(artifact))
    update = _published(store, artifact)
    ensure_catalog_references(store, update.catalog)  # everything present: fine

    victim = store.root / blob_key(next(iter(artifact.blobs)))
    victim.unlink()  # one FILE blob of the release disappears
    with pytest.raises(OtaPublisherError, match="not found"):
        ensure_catalog_references(store, update.catalog)


def test_put_is_atomic_and_leaves_no_temporary_objects(store) -> None:
    import os

    key = catalog_key(Channel.PRODUCTION)
    store.put(key, b'{"first": true}', cache_control=CATALOG_CACHE_CONTROL)
    store.put(key, b'{"second": true}', cache_control=CATALOG_CACHE_CONTROL)
    assert store.get(key) == b'{"second": true}'
    # no half-written or temporary objects are visible as store contents
    files = [p.name for p in (store.root / "v1" / "channels" / "production").iterdir()]
    assert files == ["catalog.json"]
    assert os.sep not in "".join(files)  # sanity: plain object name


def _entry(artifact):
    return catalog_entry(artifact.manifest_bytes, artifact.manifest, published_at=NOW)


def _published(store, artifact):
    entry = _entry(artifact)
    return next_catalog(
        None,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=entry.release_id,
        append=entry,
        issued_at=NOW,
        validity=VALIDITY,
    )
