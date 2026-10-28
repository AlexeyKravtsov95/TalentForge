"""The owner's production helper (M5.OTA.PROD.4) on a local store standing in for the bucket.

``scripts/ota_production_release.py`` drives the real publisher CLI against a
``LocalDirectoryStore`` whose live production catalog already carries ``BUILD_A`` (what clients
run), signed by production-like keys ``tf-prod-test-a``/``-b`` derived from public seeds - they
exist only inside this test run and never touch a real store. The package is built once by the
real CI script; every test works on its own copy of it and of the store.

Covered: the whole path (check -> prepare -> offline signature over the PAE file -> publish ->
verify) and its repetition without a new sequence; a damaged package; an approval record that
pins other bytes, is missing, unfilled or accepts a STOP gate; a signature by a key outside the
keyring, by another key, over other bytes; a payload made stale by another publication, and its
replacement; a publisher run holding the lock; a competing catalog written while attaching; an
interrupted blob upload resumed; a write that reports an error although it landed; a live
catalog that cannot be read back.
"""

from __future__ import annotations

import base64
import fcntl
import json
import shutil
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import ModuleType

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from talent_data.ota import release_package as rp
from talent_data.ota import signing
from talent_data.ota.catalog import catalog_entry, next_catalog, resign_catalog
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import Channel
from talent_data.ota.release import build_release
from talent_data.ota.signing import load_keyring_file
from talent_data.ota.store import (
    CATALOG_CACHE_CONTROL,
    LocalDirectoryStore,
    catalog_key,
    read_live_catalog,
    upload_blobs,
    write_catalog,
)
from tests.unit.ota.publisher_testkit import (
    BUILD_A,
    COMMIT,
    CREATED_AT,
    GAME,
    ROOT,
    VALIDATION_NOTE,
    derive_test_key,
    sign_der,
)
from tests.unit.ota.release_package_testkit import (
    RELEASE,
    build_package,
    filled_record,
    load_script,
)

KEY_A, KEY_B = "tf-prod-test-a", "tf-prod-test-b"
LIVE_ISSUED = datetime(2026, 10, 18, 12, 0, tzinfo=UTC)
NOW = "2026-10-19T12:00:00Z"
LATER = "2026-10-19T13:00:00Z"
CATALOG = catalog_key(Channel.PRODUCTION)
PAYLOAD = "pending-production.payload.json"


def _keyring_doc(*names: str) -> dict[str, object]:
    def entry(name: str) -> dict[str, str]:
        der = (
            derive_test_key(name)
            .public_key()
            .public_bytes(
                serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
            )
        )
        return {
            "keyId": name,
            "algorithm": "ecdsa-p256-sha256",
            "publicKeySpki": base64.b64encode(der).decode("ascii"),
        }

    return {"keys": [entry(name) for name in names]}


@pytest.fixture(scope="module")
def world(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """Built once: the package, the keyring and a store whose production runs BUILD_A."""
    root = tmp_path_factory.mktemp("world")
    assert build_package(root / "out", root / "scratch") == 0
    shutil.move(root / "out" / RELEASE, root / "package")
    (root / "keyring.json").write_text(json.dumps(_keyring_doc(KEY_A, KEY_B)), encoding="utf-8")
    keyring = load_keyring_file(root / "keyring.json")

    base = build_release(ROOT, COMMIT, GAME, BUILD_A, 1, CREATED_AT, VALIDATION_NOTE)
    store = LocalDirectoryStore(root / "store")
    sha, data = base.manifest_blob
    upload_blobs(store, {sha: data, **base.blobs})
    first = next_catalog(
        None,
        game=GAME,
        channel=Channel.PRODUCTION,
        active_release_id=base.release_id,
        append=catalog_entry(base.manifest_bytes, base.manifest, published_at=LIVE_ISSUED),
        issued_at=LIVE_ISSUED,
    )
    signature = sign_der(derive_test_key(KEY_A), first.payload)
    write_catalog(
        store,
        Channel.PRODUCTION,
        signing.build_envelope(first.payload, {KEY_A: signature}),
        base_sequence=None,
        keyring=keyring,
        game=GAME,
        now=LIVE_ISSUED,
    )
    template = (root / "package" / rp.APPROVAL_TEMPLATE_NAME).read_text(encoding="utf-8")
    (root / "approvals").mkdir()
    (root / "approvals" / f"{RELEASE}.md").write_text(filled_record(template), encoding="utf-8")
    return root


class Owner:
    """One owner machine: copies of the package, the store and the approvals; the helper."""

    def __init__(self, world: Path, root: Path) -> None:
        self.package = root / RELEASE
        self.store = root / "store"
        self.approvals = root / "approvals"
        shutil.copytree(world / "package", self.package)
        shutil.copytree(world / "store", self.store)
        shutil.copytree(world / "approvals", self.approvals)
        self.keyring_path = world / "keyring.json"
        self.keyring = load_keyring_file(self.keyring_path)
        self.helper: ModuleType = load_script("ota_production_release")

    def run(self, command: str, *extra: str, now: str = NOW) -> int:
        return int(
            self.helper.main(
                [
                    command,
                    str(self.package),
                    "--store",
                    str(self.store),
                    "--keyring",
                    str(self.keyring_path),
                    "--releases-docs",
                    str(self.approvals),
                    "--now",
                    now,
                    *extra,
                ]
            )
        )

    @property
    def work(self) -> Path:
        return self.package / rp.WORK_DIR

    def snapshot(self) -> dict[str, bytes]:
        return {
            p.relative_to(self.store).as_posix(): p.read_bytes()
            for p in sorted(self.store.rglob("*"))
            if p.is_file()
        }

    def live(self) -> tuple[int, str]:
        catalog = read_live_catalog(
            LocalDirectoryStore(self.store),
            Channel.PRODUCTION,
            keyring=self.keyring,
            game=GAME,
            now=datetime.fromisoformat(LATER),
        )
        assert catalog is not None
        return catalog.sequence, catalog.active_release_id

    def sign(self, key: str = KEY_A, *, name: str = "sig.der") -> Path:
        """The offline step: ECDSA P-256/SHA-256 over the PAE file's bytes (= openssl dgst)."""
        pae_bytes = (self.work / "pending-production.pae.bin").read_bytes()
        der = derive_test_key(key).sign(pae_bytes, ec.ECDSA(hashes.SHA256()))
        path = self.package.parent / name
        path.write_bytes(der)
        return path

    def publish(self, signature: Path, *extra: str, key: str = KEY_A, now: str = NOW) -> int:
        return self.run("publish", "--signature", str(signature), "--key-id", key, *extra, now=now)

    def journal(self) -> list[str]:
        lines = (self.work / "journal.jsonl").read_text(encoding="utf-8").splitlines()
        return [json.loads(line)["event"] for line in lines]

    def compete(self, *, now: datetime) -> None:
        """Another publisher re-signs the live catalog (tf-prod-test-b): the next sequence."""
        store = LocalDirectoryStore(self.store)
        live = read_live_catalog(
            store, Channel.PRODUCTION, keyring=self.keyring, game=GAME, now=now
        )
        assert live is not None
        update = resign_catalog(live, issued_at=now, validity=timedelta(days=45))
        signature = sign_der(derive_test_key(KEY_B), update.payload)
        envelope = signing.build_envelope(update.payload, {KEY_B: signature})
        store.put(CATALOG, envelope, cache_control=CATALOG_CACHE_CONTROL)


@pytest.fixture
def owner(world: Path, tmp_path: Path) -> Owner:
    return Owner(world, tmp_path)


def test_the_whole_publication_and_its_repetition(
    owner: Owner, capsys: pytest.CaptureFixture[str]
) -> None:
    before = owner.snapshot()
    assert owner.run("check") == 0
    assert owner.run("prepare") == 0  # dry run
    assert owner.snapshot() == before
    assert not (owner.work / PAYLOAD).exists()

    assert owner.run("prepare", "--execute") == 0
    after_prepare = owner.snapshot()
    assert after_prepare[CATALOG] == before[CATALOG]  # blobs only: nothing visible to clients
    added = set(after_prepare) - set(before)
    assert added and all(key.startswith("v1/blobs/sha256/") for key in added)
    assert owner.live() == (1, f"{BUILD_A}-r1")
    out = capsys.readouterr().out
    assert "openssl dgst -sha256 -sign tf-prod-2026a.key.pem" in out
    assert "PAE SHA-256: sha256:" in out

    signature = owner.sign()
    assert owner.publish(signature) == 0  # dry run
    assert owner.snapshot() == after_prepare
    assert owner.publish(signature, "--execute") == 0
    assert owner.live() == (2, RELEASE)
    published = owner.snapshot()[CATALOG]
    assert owner.run("verify") == 0

    # repetition: no extra sequence, no new payload, no write
    assert owner.publish(signature, "--execute") == 0
    assert owner.run("prepare", "--execute") == 0
    assert owner.run("check") == 0
    assert owner.snapshot()[CATALOG] == published
    assert owner.live() == (2, RELEASE)
    assert owner.journal() == ["start", "prepared", "write", "published"]


def test_a_damaged_package_is_refused_before_anything_is_written(owner: Owner) -> None:
    blob = sorted((owner.package / rp.BLOBS_DIR).iterdir())[0]
    blob.write_bytes(blob.read_bytes() + b"\n")
    before = owner.snapshot()
    assert owner.run("check") == 1
    assert owner.run("prepare", "--execute") == 1
    assert owner.snapshot() == before


@pytest.mark.parametrize("record", ["other pin", "unfilled", "missing", "a STOP gate accepted"])
def test_an_approval_record_that_does_not_approve_these_bytes_is_refused(
    owner: Owner, record: str
) -> None:
    path = owner.approvals / f"{RELEASE}.md"
    text = path.read_text(encoding="utf-8")
    index = rp.load_package(owner.package).index
    if record == "other pin":
        path.write_text(text.replace(index.manifest_sha256, "sha256:" + "1" * 64))
    elif record == "unfilled":
        path.write_text((owner.package / rp.APPROVAL_TEMPLATE_NAME).read_text())
    elif record == "missing":
        path.unlink()
    else:
        path.write_text(text.replace("- Accepted gates: G5, G7", "- Accepted gates: G6, G7"))
    before = owner.snapshot()
    assert owner.run("prepare", "--execute") == 1
    assert owner.snapshot() == before
    assert not (owner.work / PAYLOAD).exists()


def test_a_foreign_signature_is_refused(owner: Owner) -> None:
    assert owner.run("prepare", "--execute") == 0
    prepared = owner.snapshot()
    outsider = owner.sign("tf-prod-test-outsider", name="outsider.der")
    assert owner.publish(outsider, "--execute") == 1  # a key no app pins, claimed as A
    by_b = owner.sign(KEY_B, name="b.der")
    assert owner.publish(by_b, "--execute") == 1  # B's signature presented as A's
    assert owner.publish(by_b, "--execute", key="tf-prod-test-unknown") == 1
    other_bytes = owner.package.parent / "other.der"
    other_bytes.write_bytes(sign_der(derive_test_key(KEY_A), b'{"not": "the payload"}'))
    assert owner.publish(other_bytes, "--execute") == 1
    assert owner.snapshot() == prepared
    # B is in the keyring: its signature over these bytes publishes, as B
    assert owner.publish(by_b, "--execute", key=KEY_B) == 0
    assert owner.live() == (2, RELEASE)


def test_a_payload_made_stale_by_another_publication_is_refused_and_replaced(
    owner: Owner,
) -> None:
    assert owner.run("prepare", "--execute") == 0
    old_pae = (owner.work / "pending-production.pae.bin").read_bytes()
    old_signature = owner.sign()
    owner.compete(now=datetime(2026, 10, 19, 12, 30, tzinfo=UTC))  # live: sequence 2
    competing = owner.snapshot()

    assert owner.publish(old_signature, "--execute", now=LATER) == 1
    assert owner.snapshot() == competing
    assert owner.live() == (2, f"{BUILD_A}-r1")
    assert owner.run("prepare", "--execute", now=LATER) == 1  # stale: never replaced silently

    assert owner.run("prepare", "--replace", "--execute", now=LATER) == 0
    assert (owner.work / "pending-production.pae.bin").read_bytes() != old_pae
    assert list((owner.work / "superseded").iterdir())
    assert owner.publish(old_signature, "--execute", now=LATER) == 1  # void for the new bytes
    assert owner.publish(owner.sign(name="new.der"), "--execute", now=LATER) == 0
    assert owner.live() == (3, RELEASE)
    assert "stale" in owner.journal() and "superseded" in owner.journal()


def test_one_publisher_at_a_time(owner: Owner) -> None:
    assert owner.run("prepare", "--execute") == 0
    signature = owner.sign()
    with (owner.work / "publisher.lock").open("a+") as other_run:
        fcntl.flock(other_run, fcntl.LOCK_EX | fcntl.LOCK_NB)
        before = owner.snapshot()
        assert owner.publish(signature, "--execute") == 1
        assert owner.run("prepare", "--replace", "--execute") == 1
        assert owner.snapshot() == before
        fcntl.flock(other_run, fcntl.LOCK_UN)
    assert owner.publish(signature, "--execute") == 0


def test_a_catalog_written_by_another_publisher_while_attaching_is_ambiguous(
    owner: Owner, monkeypatch: pytest.MonkeyPatch
) -> None:
    assert owner.run("prepare", "--execute") == 0
    signature = owner.sign()
    original = signing.build_envelope
    raced = False

    def racing(payload: bytes, signatures: dict[str, bytes]) -> bytes:
        nonlocal raced
        if not raced:  # the other publisher's catalog lands first (its own envelope included)
            raced = True
            owner.compete(now=datetime(2026, 10, 19, 11, 0, tzinfo=UTC))
        return original(payload, signatures)

    monkeypatch.setattr(signing, "build_envelope", racing)
    assert owner.publish(signature, "--execute") == 3
    assert owner.live() == (2, f"{BUILD_A}-r1")  # ours was refused by the sequence re-read
    assert owner.journal()[-1] == "ambiguous"


@pytest.fixture
def counted_puts(monkeypatch: pytest.MonkeyPatch) -> Iterator[list[str]]:
    puts: list[str] = []
    original = LocalDirectoryStore.put

    def put(self: LocalDirectoryStore, key: str, data: bytes, *, cache_control: str) -> None:
        puts.append(key)
        original(self, key, data, cache_control=cache_control)

    monkeypatch.setattr(LocalDirectoryStore, "put", put)
    yield puts


def test_an_interrupted_upload_resumes_without_rewriting(
    owner: Owner, monkeypatch: pytest.MonkeyPatch, counted_puts: list[str]
) -> None:
    before = owner.snapshot()
    counting = LocalDirectoryStore.put

    def failing(self: LocalDirectoryStore, key: str, data: bytes, *, cache_control: str) -> None:
        if len(counted_puts) == 40:
            raise OtaPublisherError("connection reset (injected)")
        counting(self, key, data, cache_control=cache_control)

    monkeypatch.setattr(LocalDirectoryStore, "put", failing)
    assert owner.run("prepare", "--execute") == 1
    assert owner.snapshot()[CATALOG] == before[CATALOG]
    assert len(counted_puts) == 40
    assert not (owner.work / PAYLOAD).exists()

    monkeypatch.setattr(LocalDirectoryStore, "put", counting)
    first = list(counted_puts)
    assert owner.run("prepare", "--execute") == 0
    resumed = counted_puts[len(first) :]
    assert resumed and not set(resumed) & set(first)  # nothing uploaded twice
    assert CATALOG not in resumed
    assert owner.journal() == ["start", "failed", "start", "prepared"]
    assert owner.publish(owner.sign(), "--execute") == 0
    assert owner.live() == (2, RELEASE)


def test_a_write_reported_as_failed_that_landed_is_published(
    owner: Owner, monkeypatch: pytest.MonkeyPatch
) -> None:
    assert owner.run("prepare", "--execute") == 0
    signature = owner.sign()
    original = LocalDirectoryStore.put

    def landed(self: LocalDirectoryStore, key: str, data: bytes, *, cache_control: str) -> None:
        original(self, key, data, cache_control=cache_control)
        if key == CATALOG:
            raise OtaPublisherError("VERIFY_FAILED: read-back timed out (injected)")

    monkeypatch.setattr(LocalDirectoryStore, "put", landed)
    assert owner.publish(signature, "--execute") == 0
    assert owner.live() == (2, RELEASE)
    assert owner.journal()[-1] == "published"


def test_a_live_catalog_that_cannot_be_read_back_is_ambiguous(
    owner: Owner, monkeypatch: pytest.MonkeyPatch
) -> None:
    assert owner.run("prepare", "--execute") == 0
    signature = owner.sign()
    reads = 0
    original = owner.helper.read_live

    def flaky(target: object, game: str) -> object:
        nonlocal reads
        reads += 1
        if reads == 2:  # the read-back after the write
            raise OtaPublisherError("NETWORK: connection refused (injected)")
        return original(target, game)

    monkeypatch.setattr(owner.helper, "read_live", flaky)
    assert owner.publish(signature, "--execute") == 3
    assert owner.journal()[-1] == "ambiguous"


def test_rehearsal_options_are_refused_for_production(owner: Owner) -> None:
    for option in (["--keyring", "k.json"], ["--releases-docs", "d"], ["--now", NOW]):
        with pytest.raises(SystemExit) as raised:
            owner.helper.main(["check", str(owner.package), *option])
        assert raised.value.code == 2
