"""The production S3 store and channel isolation (M5.OTA.PROD.1) - offline, in-memory S3.

No VK Cloud account, credential or production key is involved: the bucket is the
`InMemoryS3` of `test_ota_s3_store.py` (here answering like VK Cloud - conditional writes
ignored), and the "production" keys are TEST keys derived from public seeds under
`tf-prod-*` ids, which exist only inside this test run. What is proven:

* channel isolation: each channel opens only its committed bucket/endpoint/region; the
  staging bucket is never production and neither store writes the other's catalog;
* production refuses deletion, writes without its guard, and the guard refuses any
  keyring that is not production-only;
* the guard: every production catalog put must verify on the wall clock, be signed by
  `tf-prod-*` keys only, keep >= 7 days of validity, not be future-dated, and be the exact
  successor of the live catalog - so bad-signature / expired / test-key / replay /
  channel-swap "faults" cannot reach the production path, and nothing is written;
* concurrency on a store that ignores conditional writes: the state check right before
  the put and the read-back right after it;
* the CLI: the whole production publication over S3 (promote -> offline-style signature
  over the written PAE file -> attach-signature -> verify-live -> resign), dry runs that
  write nothing, `--now` refused for production writes, gc never deleting on production,
  and the staging fault-injection helper refusing the production bucket.
"""

from __future__ import annotations

import base64
import hashlib
import importlib.util
import json
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from talent_data.cli.main import main
from talent_data.ota import s3_store
from talent_data.ota.catalog import CatalogUpdate, catalog_entry, next_catalog, resign_catalog
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import CATALOG_PAYLOAD_TYPE, Channel, OtaCatalog
from talent_data.ota.release import (
    ReleaseArtifact,
    build_manifest,
    release_files,
    serialize_manifest,
)
from talent_data.ota.s3_channels import (
    PRODUCTION_PROFILE,
    PROFILES,
    STAGING_PROFILE,
    profile_of_bucket,
    require_profile,
)
from talent_data.ota.s3_store import (
    ProductionCatalogGuard,
    S3BlobStore,
    S3Failure,
    S3Location,
    S3StoreError,
)
from talent_data.ota.signing import build_envelope, pae_sha256, sign_pae_der
from talent_data.ota.store import (
    BLOB_CACHE_CONTROL,
    CATALOG_CACHE_CONTROL,
    blob_key,
    catalog_key,
    read_live_catalog,
    write_catalog,
)
from talent_data.ota.verify import TrustedKey, load_keyring, pae, sha256_ref
from tests.unit.ota.publisher_testkit import (
    BUILD_A,
    COMMIT,
    GAME,
    NOW,
    ROOT,
    derive_test_key,
    fixture_manifest_sha256,
    write_approval_record,
    write_test_keyring,
)
from tests.unit.ota.test_ota_s3_store import CONTENTS, ENDPOINT, InMemoryS3, upload_release

PROD_BUCKET = PRODUCTION_PROFILE.bucket
STAGING_BUCKET = STAGING_PROFILE.bucket
PRODUCTION = catalog_key(Channel.PRODUCTION)
STAGING = catalog_key(Channel.STAGING)
PROD_A, PROD_B = "tf-prod-2026a", "tf-prod-2026b"


def keyring_entry(name: str) -> dict[str, str]:
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


def production_keyring() -> dict[str, TrustedKey]:
    return load_keyring([keyring_entry(PROD_A), keyring_entry(PROD_B)])


def write_production_keyring(path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    doc = {"keys": [keyring_entry(PROD_A), keyring_entry(PROD_B)]}
    path.write_text(json.dumps(doc, indent=2) + "\n")
    return path


def sign(payload: bytes, key_id: str = PROD_A, *, signer: str | None = None) -> bytes:
    """An envelope signed by ``signer``'s key (default: ``key_id``'s) under ``key_id``."""
    der = sign_pae_der(derive_test_key(signer or key_id), payload)
    return build_envelope(payload, {key_id: der})


@pytest.fixture(scope="module")
def release() -> ReleaseArtifact:
    """A small, valid release (the contents of `test_ota_s3_store.py`)."""
    files = release_files(CONTENTS)
    manifest = build_manifest(
        game=GAME,
        client_build="1.60.1.70374",
        revision=1,
        min_data_level=1,
        created_at=NOW,
        repository_commit="0123456789abcdef0123456789abcdef01234567",
        validation_note="s3 production store test",
        files=files,
    )
    return ReleaseArtifact(
        manifest=manifest,
        manifest_bytes=serialize_manifest(manifest),
        snapshot=CONTENTS,
        blobs={f.sha256: CONTENTS[f.path] for f in files},
    )


@pytest.fixture()
def clock(monkeypatch: pytest.MonkeyPatch) -> list[datetime]:
    """The production guard's wall clock, pinned to the test instant."""
    now = [NOW]
    monkeypatch.setattr(s3_store, "_wall_clock", lambda: now[0])
    return now


@pytest.fixture()
def vk() -> InMemoryS3:
    """The production bucket, answering like VK Cloud: conditional writes are ignored."""
    return InMemoryS3(bucket=PROD_BUCKET, honour_conditions=False)


def prod_store(s3: InMemoryS3, *, writable: bool = True) -> S3BlobStore:
    guard = ProductionCatalogGuard(production_keyring(), game=GAME) if writable else None
    return S3BlobStore(
        s3, PROD_BUCKET, channel=Channel.PRODUCTION, writable=writable, catalog_guard=guard
    )


def first_update(release: ReleaseArtifact) -> CatalogUpdate:
    entry = catalog_entry(release.manifest_bytes, release.manifest, published_at=NOW)
    return next_catalog(
        None,
        game=GAME,
        channel=Channel.PRODUCTION,
        active_release_id=entry.release_id,
        append=entry,
        issued_at=NOW,
    )


def publish(store: S3BlobStore, update: CatalogUpdate, envelope: bytes, now: datetime) -> None:
    write_catalog(
        store,
        Channel.PRODUCTION,
        envelope,
        base_sequence=update.base_sequence,
        keyring=production_keyring(),
        game=GAME,
        now=now,
        allow_expired_live=True,
    )


def published(vk: InMemoryS3, release: ReleaseArtifact) -> OtaCatalog:
    """A production bucket with release A live at sequence 1."""
    store = prod_store(vk)
    upload_release(store, release)
    update = first_update(release)
    publish(store, update, sign(update.payload), NOW)
    return update.catalog


def edited(payload: bytes, **changes: Any) -> bytes:
    """``payload`` (catalog JSON) with top-level members replaced."""
    doc = json.loads(payload)
    doc.update(changes)
    return (json.dumps(doc, indent=2) + "\n").encode()


# --------------------------------------------------------------------------- isolation


def test_the_committed_profiles_keep_the_channels_apart() -> None:
    assert set(PROFILES) == {Channel.STAGING, Channel.PRODUCTION}
    assert PRODUCTION_PROFILE.bucket != STAGING_PROFILE.bucket
    assert PRODUCTION_PROFILE.aws_profile != STAGING_PROFILE.aws_profile
    assert PRODUCTION_PROFILE.public_origin != STAGING_PROFILE.public_origin
    assert "staging" not in PRODUCTION_PROFILE.bucket
    assert "prod" not in STAGING_PROFILE.bucket
    assert PRODUCTION_PROFILE.public_origin == "https://ota.mythweld.com"
    assert STAGING_PROFILE.bucket_origin == STAGING_PROFILE.public_origin
    for profile in PROFILES.values():
        assert profile.endpoint_url.startswith("https://")
        assert profile.public_origin.startswith("https://")
        assert profile_of_bucket(profile.bucket) is profile
    with pytest.raises(OtaPublisherError, match="unknown OTA bucket"):
        profile_of_bucket("talentforge-ota")


@pytest.mark.parametrize(
    ("channel", "bucket", "endpoint", "region", "message"),
    [
        (Channel.PRODUCTION, STAGING_BUCKET, ENDPOINT, "ru-msk", "is the staging bucket"),
        (Channel.STAGING, PROD_BUCKET, ENDPOINT, "ru-msk", "is the production bucket"),
        (Channel.PRODUCTION, "talentforge-ota-prod", ENDPOINT, "ru-msk", "is not the production"),
        (Channel.PRODUCTION, PROD_BUCKET, "https://s3.example.com", "ru-msk", "lives at"),
        (Channel.PRODUCTION, PROD_BUCKET, ENDPOINT, "kz-ast", "lives at"),
    ],
)
def test_a_location_must_be_exactly_its_channels_profile(
    channel: Channel, bucket: str, endpoint: str, region: str, message: str
) -> None:
    with pytest.raises(OtaPublisherError, match=message):
        S3Location(bucket, endpoint, region, channel=channel)
    with pytest.raises(OtaPublisherError, match=message):
        require_profile(channel, bucket=bucket, endpoint_url=endpoint, region=region)


def test_a_store_must_be_opened_on_its_own_bucket() -> None:
    with pytest.raises(OtaPublisherError, match="not the production bucket"):
        S3BlobStore(InMemoryS3(), STAGING_BUCKET, channel=Channel.PRODUCTION)
    with pytest.raises(OtaPublisherError, match="not the staging bucket"):
        S3BlobStore(InMemoryS3(bucket=PROD_BUCKET), PROD_BUCKET)


def test_the_production_store_never_writes_the_staging_catalog(vk: InMemoryS3) -> None:
    store = prod_store(vk)
    with pytest.raises(S3StoreError) as raised:
        store.put(STAGING, b"{}", cache_control=CATALOG_CACHE_CONTROL)
    assert raised.value.kind is S3Failure.OUT_OF_SCOPE
    assert "production store never writes the staging catalog" in str(raised.value)
    assert vk.calls == []
    assert store.exists(STAGING) is False  # readable, so gates/gc fail closed if one appears


def test_the_staging_store_never_writes_the_production_catalog() -> None:
    s3 = InMemoryS3()
    store = S3BlobStore(s3, STAGING_BUCKET, writable=True)
    with pytest.raises(S3StoreError, match="staging store never writes the production catalog"):
        store.put(PRODUCTION, b"{}", cache_control=CATALOG_CACHE_CONTROL)
    assert s3.calls == []


# --------------------------------------------------------------------------- production mode


def test_production_never_deletes_and_never_writes_without_its_guard(vk: InMemoryS3) -> None:
    with pytest.raises(OtaPublisherError, match="never deleted"):
        S3BlobStore(vk, PROD_BUCKET, channel=Channel.PRODUCTION, allow_delete=True)
    with pytest.raises(OtaPublisherError, match="needs the production catalog guard"):
        S3BlobStore(vk, PROD_BUCKET, channel=Channel.PRODUCTION, writable=True)
    with pytest.raises(OtaPublisherError, match="guard is for the production store"):
        S3BlobStore(
            InMemoryS3(),
            STAGING_BUCKET,
            writable=True,
            catalog_guard=ProductionCatalogGuard(production_keyring(), game=GAME),
        )
    key = blob_key(sha256_ref(b'{"keep": true}'))
    vk.raw_put(key, b'{"keep": true}')
    with pytest.raises(S3StoreError, match="production blobs are never deleted"):
        prod_store(vk).delete(key)
    assert key in vk.objects
    assert vk.mutations() == []


@pytest.mark.parametrize(
    "names",
    [("test-primary",), (PROD_A, "test-backup"), (PROD_A, "tf-staging-2026"), ()],
)
def test_the_guard_takes_a_production_only_keyring(names: tuple[str, ...]) -> None:
    keyring = load_keyring([keyring_entry(name) for name in names])
    with pytest.raises(OtaPublisherError, match="production keyring"):
        ProductionCatalogGuard(keyring, game=GAME)


def test_a_valid_first_production_catalog_is_written_last_and_read_back(
    vk: InMemoryS3, release: ReleaseArtifact, clock: list[datetime]
) -> None:
    catalog = published(vk, release)
    puts = [key for operation, key in vk.mutations() if operation == "put_object"]
    assert puts[-1] == PRODUCTION and puts.count(PRODUCTION) == 1
    assert all(key.startswith("v1/blobs/sha256/") for key in puts[:-1])
    assert vk.objects[PRODUCTION]["CacheControl"] == "public, max-age=300"
    assert vk.objects[PRODUCTION]["ContentType"] == "application/json"
    assert catalog.sequence == 1
    # the state was re-read right before the put (HEAD) and the put was read back
    put_at = vk.calls.index(("put_object", PRODUCTION))
    assert vk.calls[put_at - 1] == ("head_object", PRODUCTION)
    assert vk.calls[put_at + 1 :] == [("head_object", PRODUCTION), ("get_object", PRODUCTION)]


# --------------------------------------------------------------------------- guard refusals


def _faults(live: OtaCatalog) -> dict[str, bytes]:
    good = resign_catalog(live, issued_at=NOW).payload
    der = bytearray(sign_pae_der(derive_test_key(PROD_A), good))
    der[-5] ^= 0x01  # still DER, no longer a valid signature (the staging fault)
    short = resign_catalog(live, issued_at=NOW, validity=timedelta(seconds=120)).payload
    stale = resign_catalog(live, issued_at=NOW - timedelta(days=46)).payload
    future = resign_catalog(live, issued_at=NOW + timedelta(hours=1)).payload
    return {
        "bad signature": build_envelope(good, {PROD_A: bytes(der)}),
        "signed by a staging key id": sign(good, "tf-staging-2026"),
        "signed by a test key id": sign(good, "test-primary"),
        "a tf-prod id with another key": sign(good, PROD_A, signer="someone-else"),
        "short-lived (the expired fault)": sign(short),
        "already expired": sign(stale),
        "issued in the future": sign(future),
        "a staging-channel payload": sign(edited(good, channel="staging")),
        "another game": sign(edited(good, game="retail")),
        "a sequence jump": sign(edited(good, sequence=live.sequence + 2)),
        "a replayed sequence": sign(edited(good, sequence=live.sequence)),
        "unlisting a release": sign(edited(good, releases=[])),
        "not an envelope": b'{"payload": "x"}\n',
        "oversized": b" " * (1024 * 1024 + 1),
    }


FAULTS = [
    "bad signature",
    "signed by a staging key id",
    "signed by a test key id",
    "a tf-prod id with another key",
    "short-lived (the expired fault)",
    "already expired",
    "issued in the future",
    "a staging-channel payload",
    "another game",
    "a sequence jump",
    "a replayed sequence",
    "unlisting a release",
    "not an envelope",
    "oversized",
]


@pytest.mark.parametrize("fault", FAULTS)
def test_the_guard_refuses_every_catalog_production_clients_would_not_take_next(
    vk: InMemoryS3, release: ReleaseArtifact, clock: list[datetime], fault: str
) -> None:
    live = published(vk, release)
    before = vk.objects[PRODUCTION]["data"]
    mutations = len(vk.mutations())
    store = prod_store(vk)
    assert store.exists(PRODUCTION)  # the observation every catalog put needs
    with pytest.raises(S3StoreError) as raised:
        store.put(PRODUCTION, _faults(live)[fault], cache_control=CATALOG_CACHE_CONTROL)
    assert raised.value.kind is S3Failure.CATALOG_REFUSED
    assert vk.objects[PRODUCTION]["data"] == before
    assert len(vk.mutations()) == mutations  # refused before any put request


def test_the_fault_list_is_exhaustive(vk: InMemoryS3, release: ReleaseArtifact, clock) -> None:
    assert sorted(_faults(published(vk, release))) == sorted(FAULTS)


def test_a_first_catalog_must_start_the_sequence(
    vk: InMemoryS3, release: ReleaseArtifact, clock: list[datetime]
) -> None:
    store = prod_store(vk)
    upload_release(store, release)
    assert store.exists(PRODUCTION) is False
    with pytest.raises(S3StoreError) as raised:
        store.put(
            PRODUCTION,
            sign(edited(first_update(release).payload, sequence=7)),
            cache_control=CATALOG_CACHE_CONTROL,
        )
    assert raised.value.kind is S3Failure.CATALOG_REFUSED
    assert PRODUCTION not in vk.objects


def test_a_lapsed_live_catalog_can_be_succeeded(
    vk: InMemoryS3, release: ReleaseArtifact, clock: list[datetime]
) -> None:
    """Recovery (runbook): the guard forgives only the live catalog's lapsed window."""
    live = published(vk, release)
    clock[0] = NOW + timedelta(days=50)  # the live catalog expired 5 days ago
    update = resign_catalog(live, issued_at=clock[0])
    publish(prod_store(vk), update, sign(update.payload), clock[0])
    assert vk.objects[PRODUCTION]["data"] == sign(update.payload)


# --------------------------------------------------------------------------- concurrency


def test_a_rival_publication_before_the_put_is_caught_without_conditional_writes(
    vk: InMemoryS3, release: ReleaseArtifact, clock: list[datetime]
) -> None:
    live = published(vk, release)
    store = prod_store(vk)
    assert store.exists(PRODUCTION)  # this publisher's observation
    rival = sign(resign_catalog(live, issued_at=NOW).payload, PROD_B)
    vk.raw_put(PRODUCTION, rival)  # another publisher lands meanwhile
    ours = sign(resign_catalog(live, issued_at=NOW + timedelta(seconds=1)).payload)
    with pytest.raises(S3StoreError) as raised:
        store.put(PRODUCTION, ours, cache_control=CATALOG_CACHE_CONTROL)
    assert raised.value.kind is S3Failure.PRECONDITION_FAILED
    assert "concurrent publication" in str(raised.value)
    assert vk.objects[PRODUCTION]["data"] == rival  # nothing overwritten


def test_a_rival_landing_right_after_the_put_fails_the_read_back(
    vk: InMemoryS3, release: ReleaseArtifact, clock: list[datetime]
) -> None:
    live = published(vk, release)
    vk.after_put = lambda key: vk.raw_put(key, b'{"rival": "publisher"}\n')
    update = resign_catalog(live, issued_at=NOW)
    with pytest.raises(S3StoreError) as raised:
        publish(prod_store(vk), update, sign(update.payload), NOW)
    assert raised.value.kind is S3Failure.VERIFY_FAILED


def test_the_staging_store_also_checks_the_live_state_before_its_put() -> None:
    s3 = InMemoryS3(honour_conditions=False)  # like VK Cloud
    store = S3BlobStore(s3, STAGING_BUCKET, writable=True)
    assert store.exists(STAGING) is False
    s3.raw_put(STAGING, b'{"rival": 1}')
    with pytest.raises(S3StoreError) as raised:
        store.put(STAGING, b'{"ours": 1}', cache_control=CATALOG_CACHE_CONTROL)
    assert raised.value.kind is S3Failure.PRECONDITION_FAILED
    assert s3.objects[STAGING]["data"] == b'{"rival": 1}'


def test_production_blobs_stay_immutable_without_conditional_writes(vk: InMemoryS3) -> None:
    store = prod_store(vk)
    data = b'{"blob": true}'
    key = blob_key(sha256_ref(data))
    store.put(key, data, cache_control=BLOB_CACHE_CONTROL)
    for attempt in (data, b'{"other": true}'):
        with pytest.raises(S3StoreError) as raised:
            store.put(key, attempt, cache_control=BLOB_CACHE_CONTROL)
        assert raised.value.kind is S3Failure.IMMUTABLE
    assert vk.mutations() == [("put_object", key)]


# --------------------------------------------------------------------------- CLI

PROD_FLAGS = [
    "--backend",
    "s3",
    "--s3-bucket",
    PROD_BUCKET,
    "--s3-endpoint",
    ENDPOINT,
    "--s3-region",
    "ru-msk",
    "--aws-profile",
    PRODUCTION_PROFILE.aws_profile,
]


@pytest.fixture()
def cli_vk(vk: InMemoryS3, monkeypatch: pytest.MonkeyPatch) -> list[dict[str, Any]]:
    """`connect` over the VK-like in-memory production bucket; records each opening."""
    opened: list[dict[str, Any]] = []

    def fake_connect(
        location: S3Location,
        *,
        writable: bool,
        allow_delete: bool,
        catalog_guard: ProductionCatalogGuard | None = None,
    ) -> S3BlobStore:
        opened.append({"location": location, "writable": writable, "guard": catalog_guard})
        return S3BlobStore(
            vk,
            location.bucket,
            channel=location.channel,
            writable=writable,
            allow_delete=allow_delete,
            catalog_guard=catalog_guard,
        )

    monkeypatch.setattr(s3_store, "connect", fake_connect)
    return opened


class ProdCli:
    """The production publisher CLI over the in-memory production bucket (wall clock)."""

    def __init__(self, root: Path) -> None:
        self.out = root / "artifacts"
        self.docs = root / "approvals"
        self.keyring = write_production_keyring(root / "keys" / "production-keyring.json")
        self.release_id = f"{BUILD_A}-r1"
        write_approval_record(
            self.docs, self.release_id, manifest_sha256=fixture_manifest_sha256(self.release_id)
        )

    def run(self, *argv: str) -> int:
        return main(["ota", *argv, "--repo", str(ROOT), "--game", GAME])

    def build(self) -> Path:
        build = ["build-release", "--build", BUILD_A, "--commit", COMMIT]
        build += ["--created-at", "2026-10-17T23:00:00Z", "--out", str(self.out)]
        assert self.run(*build) == 0
        return self.out / self.release_id / "release.json"

    def store_args(self) -> list[str]:
        return [*PROD_FLAGS, "--keyring", str(self.keyring)]

    def offline_sign(self, key_id: str) -> Path:
        """The owner's offline step: sign the written PAE file.

        ``openssl dgst -sha256 -sign key.pem -out sig.der pending-production.pae.bin``
        produces a randomized (not RFC 6979) DER ECDSA signature - and so does this.
        """
        pae_file = self.out / "pending-production.pae.bin"
        der = derive_test_key(key_id).sign(pae_file.read_bytes(), ec.ECDSA(hashes.SHA256()))
        signature = self.out / "pending-production.sig.der"
        signature.write_bytes(der)
        return signature

    def attach(self, *extra: str, key_id: str = PROD_A) -> int:
        return self.run(
            "attach-signature",
            "--payload",
            str(self.out / "pending-production.payload.json"),
            "--signature",
            str(self.offline_sign(key_id)),
            "--key-id",
            key_id,
            "--releases-docs",
            str(self.docs),
            *self.store_args(),
            *extra,
        )


def test_cli_publishes_production_over_s3_end_to_end(
    vk: InMemoryS3, cli_vk: list[dict[str, Any]], tmp_path: Path, capsys
) -> None:
    cli = ProdCli(tmp_path)
    manifest = cli.build()
    promote = ["promote", "--manifest", str(manifest), "--releases-docs", str(cli.docs)]
    promote += [*cli.store_args(), "--out", str(cli.out)]

    assert cli.run(*promote) == 0  # dry run: read-only, nothing written
    assert cli_vk[-1]["writable"] is False and cli_vk[-1]["guard"] is None
    assert cli_vk[-1]["location"].channel is Channel.PRODUCTION
    assert vk.mutations() == []

    assert cli.run(*promote, "--execute") == 0
    assert cli_vk[-1]["writable"] is True and cli_vk[-1]["guard"] is not None
    assert PRODUCTION not in vk.objects  # promote uploads blobs only
    payload = (cli.out / "pending-production.payload.json").read_bytes()
    pae_file = cli.out / "pending-production.pae.bin"
    assert pae_file.read_bytes() == pae(CATALOG_PAYLOAD_TYPE, payload)
    assert f"PAE SHA-256 to sign offline: {pae_sha256(payload)}" in capsys.readouterr().out
    assert pae_sha256(payload) == "sha256:" + hashlib.sha256(pae_file.read_bytes()).hexdigest()

    assert cli.attach() == 0  # dry run
    assert PRODUCTION not in vk.objects
    assert cli.attach("--execute") == 0
    puts = [key for operation, key in vk.mutations() if operation == "put_object"]
    assert puts[-1] == PRODUCTION and puts.count(PRODUCTION) == 1

    verify = ["verify-live", "--channel", "production", *cli.store_args()]
    assert cli.run(*verify) == 0
    assert cli_vk[-1]["writable"] is False

    # re-sign: a new sequence, same content, signed with the backup key
    assert cli.run("resign", *cli.store_args(), "--out", str(cli.out), "--execute") == 0
    assert cli.attach("--execute", key_id=PROD_B) == 0
    assert cli.run(*verify) == 0
    catalog = read_live_catalog(
        S3BlobStore(vk, PROD_BUCKET, channel=Channel.PRODUCTION),
        Channel.PRODUCTION,
        keyring=production_keyring(),
        game=GAME,
        now=datetime.now(UTC),
    )
    assert catalog is not None and catalog.sequence == 2
    assert STAGING not in vk.objects


def test_cli_production_refusals(
    vk: InMemoryS3, cli_vk: list[dict[str, Any]], tmp_path: Path, capsys
) -> None:
    cli = ProdCli(tmp_path)
    manifest = cli.build()
    keys = ["--keyring", str(cli.keyring)]

    # the production bucket is never used for the staging channel
    signer = ["--key", str(tmp_path / "unused.pem"), "--key-id", "tf-staging-2026"]
    for argv in (
        ["stage", "--manifest", str(manifest), *PROD_FLAGS, *keys, *signer],
        ["verify-live", "--channel", "staging", *PROD_FLAGS, *keys],
        ["resign", "--channel", "staging", *PROD_FLAGS, *keys],
    ):
        assert cli.run(*argv) == 1
        err = capsys.readouterr().err
        assert "is the production bucket; refusing it for the staging channel" in err

    # --now is a rehearsal clock: never for a production write (a dry run may use it)
    promote = ["promote", "--manifest", str(manifest), "--releases-docs", str(cli.docs)]
    promote += [*PROD_FLAGS, *keys, "--out", str(cli.out), "--now", "2026-10-18T12:00:00Z"]
    assert cli.run(*promote) == 0
    assert cli.run(*promote, "--execute") == 1
    assert "production writes run on the wall clock only" in capsys.readouterr().err

    # gc lists only: production blobs are never deleted
    assert cli.run("gc", *PROD_FLAGS, *keys) == 0
    assert cli.run("gc", *PROD_FLAGS, *keys, "--execute", "--confirm") == 1
    assert "production blobs are never deleted" in capsys.readouterr().err

    # a writable production store refuses a keyring with test/staging keys
    test_keyring, _ = write_test_keyring(tmp_path / "test-keys")
    test_promote = ["promote", "--manifest", str(manifest), "--releases-docs", str(cli.docs)]
    test_promote += [*PROD_FLAGS, "--keyring", str(test_keyring), "--out", str(cli.out)]
    assert cli.run(*test_promote, "--execute") == 1
    assert "production keyring" in capsys.readouterr().err
    assert vk.mutations() == []


# --------------------------------------------------------------------------- staging helper


def _staging_helper() -> ModuleType:
    name = "ota_vkcloud_staging"
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize(
    "argv",
    [
        ["fault", "expired", "--keyring", "k.json", "--key", "k.pem", "--key-id", "tf-staging-x"],
        ["fault", "restore", "--keyring", "k.json", "--restore-file", "r.json"],
        ["probe-conditional"],
    ],
)
def test_the_staging_fault_helper_refuses_the_production_bucket(
    argv: list[str], monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys
) -> None:
    helper = _staging_helper()
    connections: list[object] = []
    monkeypatch.setattr(helper, "connect", lambda *a, **k: connections.append(a))
    restore = tmp_path / "r.json"
    restore.write_bytes(b"{}")
    argv = [str(restore) if arg == "r.json" else arg for arg in argv]
    flags = ["--s3-bucket", PROD_BUCKET, "--s3-endpoint", ENDPOINT, "--s3-region", "ru-msk"]
    assert helper.main([*argv, *flags, "--execute"]) == 1
    err = capsys.readouterr().err
    assert "is the production bucket; refusing it for the staging channel" in err
    assert connections == []
