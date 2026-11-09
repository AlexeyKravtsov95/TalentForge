"""Every independent barrier rechecks each unique pinned object, without duplicate I/O."""

# ruff: noqa: F811 - imported pytest fixture is injected by name

from collections import Counter
from datetime import timedelta
from threading import Lock

import pytest

from talent_data.ota.catalog import catalog_entry, next_catalog
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import Channel
from talent_data.ota.release import serialize_manifest
from talent_data.ota.store import LocalDirectoryStore, blob_key, ensure_catalog_references
from talent_data.ota.verify import sha256_ref
from tests.unit.ota.publisher_testkit import NOW
from tests.unit.ota.test_ota_publisher_store import artifact  # noqa: F401


@pytest.fixture
def reference_world(tmp_path, artifact):
    class CountingStore(LocalDirectoryStore):
        def __init__(self):
            super().__init__(tmp_path / "store")
            self.counts = Counter()
            self.lock = Lock()

        def get(self, key):
            with self.lock:
                self.counts[key] += 1
            return super().get(key)

    store = CountingStore()
    for sha, raw in artifact.blobs.items():
        store.put(blob_key(sha), raw, cache_control="public")
    manifests = [
        artifact.manifest,
        artifact.manifest.model_copy(
            update={"release_id": artifact.manifest.release_id.rsplit("-r", 1)[0] + "-r2"}
        ),
    ]

    def catalog_for(values):
        entries = []
        for manifest in values:
            raw = serialize_manifest(manifest)
            store.put(blob_key(sha256_ref(raw)), raw, cache_control="public")
            entries.append(catalog_entry(raw, manifest, published_at=NOW))
        catalog = next_catalog(
            None,
            game="forever",
            channel=Channel.PRODUCTION,
            active_release_id=entries[0].release_id,
            append=entries[0],
            issued_at=NOW,
            validity=timedelta(days=45),
        ).catalog
        return catalog.model_copy(
            update={"releases": entries, "active_release_id": entries[-1].release_id}
        )

    return store, manifests, catalog_for


@pytest.mark.parametrize("workers", [1, 8])
def test_reference_pass_deduplicates_without_cross_pass_cache(reference_world, workers):
    store, manifests, catalog_for = reference_world
    catalog = catalog_for(manifests)
    ensure_catalog_references(store, catalog, read_workers=workers)
    # Two release manifests plus six shared files, not twelve file reads.
    assert sum(store.counts.values()) == 8
    assert set(store.counts.values()) == {1}
    ensure_catalog_references(store, catalog, read_workers=workers)
    assert sum(store.counts.values()) == 16
    file = manifests[0].files[0]
    store.put(blob_key(file.sha256), b"corruption", cache_control="public")
    with pytest.raises(OtaPublisherError, match="does not hash"):
        ensure_catalog_references(store, catalog, read_workers=workers)


def test_shared_hash_conflicting_lengths_rejected(reference_world):
    store, manifests, catalog_for = reference_world
    manifest = manifests[1]
    files = list(manifest.files)
    files[0] = files[0].model_copy(update={"byte_length": files[0].byte_length + 1})
    manifests[1] = manifest.model_copy(
        update={"files": files, "total_byte_length": manifest.total_byte_length + 1}
    )
    with pytest.raises(OtaPublisherError, match="length"):
        ensure_catalog_references(store, catalog_for(manifests))


def test_correct_hash_with_wrong_declared_length_fails(reference_world):
    store, manifests, catalog_for = reference_world
    manifest = manifests[0]
    files = [f.model_copy(update={"byte_length": f.byte_length + 1}) for f in manifest.files]
    changed = manifest.model_copy(
        update={"files": files, "total_byte_length": manifest.total_byte_length + len(files)}
    )
    with pytest.raises(OtaPublisherError, match="length"):
        ensure_catalog_references(store, catalog_for([changed]))


@pytest.mark.parametrize("workers", [1, 8])
def test_each_manifest_identity_checked(reference_world, workers):
    store, manifests, catalog_for = reference_world
    catalog = catalog_for(manifests)
    entries = list(catalog.releases)
    entries[-1] = entries[-1].model_copy(update={"client_build": "1.60.1.99999"})
    with pytest.raises(OtaPublisherError, match="clientBuild"):
        ensure_catalog_references(
            store, catalog.model_copy(update={"releases": entries}), read_workers=workers
        )


@pytest.mark.parametrize("workers", [1, 8])
def test_one_missing_unique_file_fails(reference_world, workers):
    store, manifests, catalog_for = reference_world
    catalog = catalog_for(manifests)
    store.delete(blob_key(manifests[0].files[-1].sha256))
    with pytest.raises(OtaPublisherError, match="not found"):
        ensure_catalog_references(store, catalog, read_workers=workers)
