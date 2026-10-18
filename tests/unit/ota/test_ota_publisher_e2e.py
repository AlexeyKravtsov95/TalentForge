"""The offline end-to-end publisher run (M5.OTA.2, test plan §5): one test, real CLI.

build 70374 -> validate -> stage -> publish the test catalog (promote with an
attached TEST-key signature) -> verify-live -> build 70450 -> validate -> promote ->
verify-live -> rollback -> re-sign, everything against a ``LocalDirectoryStore``
standing in for the bucket and the real repository as ``--repo``. Every resulting
catalog passes ``verify_catalog`` - including with the committed fixture keyring -
and every release passes ``verify_release_files``.

The tests below cover the negative scenarios: corruption, missing blobs, signature
failure, unsupported mechanics (the wire-valid open-union content), a sequence
conflict, a missing approval record, append-only violations, dry-run safety and gc's
confirm gate. Each negative test gets its own store: none of them may depend on (or
damage) another's state.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import pytest

from talent_data.cli.main import main
from talent_data.ota.models import Channel, OtaCatalog, ReleaseFile
from talent_data.ota.store import catalog_key
from talent_data.ota.verify import (
    load_keyring,
    verify_catalog,
    verify_release_files,
    verify_release_manifest,
)
from tests.unit.ota.publisher_testkit import (
    BUILD_A,
    BUILD_B,
    COMMIT,
    CREATED_AT,
    FIXTURES,
    GAME,
    NOW,
    PRIMARY,
    ROOT,
    VALIDATION_NOTE,
    derive_test_key,
    fixture_manifest_sha256,
    sign_der,
    write_approval_record,
    write_test_keyring,
)

A = f"{BUILD_A}-r1"
B = f"{BUILD_B}-r1"
NOW_ARG = "2026-10-18T12:00:00Z"
CREATED_ARG = "2026-10-17T23:00:00Z"


class Env:
    """One CLI-invoking publisher environment: store, keyring, approvals, artifacts."""

    def __init__(self, root: Path) -> None:
        self.root = root
        self.store = root / "store"
        self.artifacts = root / "artifacts"
        self.docs = root / "approvals"
        self.keyring_path, self.pem = write_test_keyring(root / "keys")
        write_approval_record(self.docs, A, manifest_sha256=fixture_manifest_sha256(A))
        write_approval_record(self.docs, B, manifest_sha256=fixture_manifest_sha256(B))

    def cli(self, *args: str) -> int:
        return main(["ota", *args, "--repo", str(ROOT)])

    # -- command shorthands ---------------------------------------------------

    def build(self, build: str) -> int:
        return self.cli(
            "build-release",
            "--game",
            GAME,
            "--build",
            build,
            "--revision",
            "1",
            "--commit",
            COMMIT,
            "--created-at",
            CREATED_ARG,
            "--out",
            str(self.artifacts),
        )

    def manifest(self, build: str) -> Path:
        return self.artifacts / f"{build}-r1" / "release.json"

    def validate(self, build: str, *, manifest: Path | None = None) -> int:
        return self.cli(
            "validate",
            "--game",
            GAME,
            "--manifest",
            str(manifest or self.manifest(build)),
            "--store",
            str(self.store),
            "--keyring",
            str(self.keyring_path),
            "--releases-docs",
            str(self.docs),
            "--now",
            NOW_ARG,
        )

    def stage(self, build: str) -> int:
        return self.cli(
            "stage",
            "--game",
            GAME,
            "--manifest",
            str(self.manifest(build)),
            "--store",
            str(self.store),
            "--keyring",
            str(self.keyring_path),
            "--key",
            str(self.pem),
            "--key-id",
            PRIMARY,
            "--execute",
            "--now",
            NOW_ARG,
        )

    def promote(self, build: str, *, manifest: Path | None = None) -> int:
        return self.cli(
            "promote",
            "--game",
            GAME,
            "--manifest",
            str(manifest or self.manifest(build)),
            "--store",
            str(self.store),
            "--keyring",
            str(self.keyring_path),
            "--releases-docs",
            str(self.docs),
            "--out",
            str(self.artifacts),
            "--execute",
            "--now",
            NOW_ARG,
        )

    def rollback(self, active: str = A, *, now: str = NOW_ARG) -> int:
        return self.cli(
            "rollback",
            "--game",
            GAME,
            "--active",
            active,
            "--store",
            str(self.store),
            "--keyring",
            str(self.keyring_path),
            "--out",
            str(self.artifacts),
            "--execute",
            "--now",
            now,
        )

    def resign(self, now: str = NOW_ARG) -> int:
        return self.cli(
            "resign",
            "--game",
            GAME,
            "--store",
            str(self.store),
            "--keyring",
            str(self.keyring_path),
            "--out",
            str(self.artifacts),
            "--execute",
            "--now",
            now,
        )

    def sign_pending(self, channel: str = "production") -> Path:
        payload = self.artifacts / f"pending-{channel}.payload.json"
        signature = self.artifacts / f"pending-{channel}.signature.der"
        assert (
            self.cli(
                "sign",
                "--key",
                str(self.pem),
                "--key-id",
                PRIMARY,
                "--payload",
                str(payload),
                "--out",
                str(signature),
            )
            == 0
        )
        return signature

    def attach(self, channel: str = "production", *, signature: Path | None = None) -> int:
        payload = self.artifacts / f"pending-{channel}.payload.json"
        signature = signature or self.sign_pending(channel)
        return self.cli(
            "attach-signature",
            "--game",
            GAME,
            "--payload",
            str(payload),
            "--signature",
            str(signature),
            "--key-id",
            PRIMARY,
            "--keyring",
            str(self.keyring_path),
            "--store",
            str(self.store),
            "--channel",
            channel,
            "--execute",
            "--now",
            NOW_ARG,
        )

    def verify_live(self, channel: str = "production", *, now: str = NOW_ARG) -> int:
        return self.cli(
            "verify-live",
            "--game",
            GAME,
            "--store",
            str(self.store),
            "--keyring",
            str(self.keyring_path),
            "--channel",
            channel,
            "--now",
            now,
        )

    def gc(self, *extra: str) -> int:
        return self.cli(
            "gc",
            "--game",
            GAME,
            "--store",
            str(self.store),
            "--keyring",
            str(self.keyring_path),
            "--now",
            NOW_ARG,
            *extra,
        )

    # -- assertions -----------------------------------------------------------

    def keyring(self) -> dict[str, object]:
        return load_keyring(json.loads(self.keyring_path.read_text())["keys"])

    def live(self, channel: str = "production") -> OtaCatalog:
        envelope = (self.store / catalog_key(Channel(channel))).read_bytes()
        verified = verify_catalog(
            envelope,
            keyring=self.keyring(),
            game=GAME,
            channel=Channel(channel),
            now=NOW,
            last_seen=None,
        )
        return verified.catalog

    def catalog_bytes(self, channel: str = "production") -> bytes:
        return (self.store / catalog_key(Channel(channel))).read_bytes()

    def blob_dir(self) -> Path:
        return self.store / "v1" / "blobs" / "sha256"

    def verify_every_release(self) -> None:
        for entry in self.live().releases:
            blob = self.blob_dir() / entry.manifest_sha256.split(":")[1]
            manifest = verify_release_manifest(
                blob.read_bytes(), entry, game=GAME, supported_data_level=entry.min_data_level
            )

            def read_file(file: ReleaseFile) -> bytes:
                return (self.blob_dir() / file.sha256.split(":")[1]).read_bytes()

            verify_release_files(manifest, read_file)


@pytest.fixture(scope="module")
def env(tmp_path_factory: pytest.TempPathFactory) -> Env:
    return Env(tmp_path_factory.mktemp("ota-e2e"))


@pytest.fixture()
def neg_env(tmp_path: Path) -> Env:
    """A fresh environment with release A published (the minimum live state)."""
    env = Env(tmp_path / "neg")
    assert env.build(BUILD_A) == 0
    assert env.validate(BUILD_A) == 0
    assert env.stage(BUILD_A) == 0
    assert env.promote(BUILD_A) == 0
    assert env.attach() == 0
    assert env.verify_live() == 0
    return env


def test_the_full_offline_pipeline(env: Env) -> None:
    # 1-2. build 70374 and validate it (G7 flags: the approval record covers it)
    assert env.build(BUILD_A) == 0
    assert (
        env.manifest(BUILD_A).read_bytes()
        == (FIXTURES / "releases" / f"{A}.release.json").read_bytes()
    )
    assert env.validate(BUILD_A) == 0

    # 3. stage it: blobs first, then a staging catalog signed with the TEST key
    assert env.stage(BUILD_A) == 0
    staging = env.live("staging")
    assert staging.sequence == 1 and staging.active_release_id == A
    assert env.verify_live("staging") == 0

    # 4. publish the test production catalog: promote + externally-style signature
    assert env.promote(BUILD_A) == 0
    assert env.attach() == 0
    production = env.live()
    assert production.sequence == 1 and production.active_release_id == A
    assert [r.release_id for r in production.releases] == [A]

    # 5. verify-live through the reference verifier, every blob of every release
    assert env.verify_live() == 0
    env.verify_every_release()

    # 6-9. build 70450, validate, promote (append-only), verify
    assert env.build(BUILD_B) == 0
    assert (
        env.manifest(BUILD_B).read_bytes()
        == (FIXTURES / "releases" / f"{B}.release.json").read_bytes()
    )
    assert env.validate(BUILD_B) == 0
    assert env.promote(BUILD_B) == 0
    assert env.attach() == 0
    production = env.live()
    assert production.sequence == 2 and production.active_release_id == B
    assert [r.release_id for r in production.releases] == [A, B]
    assert env.verify_live() == 0
    env.verify_every_release()
    blobs_before_rollback = set(env.blob_dir().iterdir())

    # 10. rollback to 70374: a NEW catalog with a higher sequence, nothing unlisted
    assert env.rollback(A) == 0
    assert env.attach() == 0
    rolled = env.live()
    assert rolled.sequence == 3 and rolled.active_release_id == A
    assert [r.release_id for r in rolled.releases] == [A, B]
    assert env.verify_live() == 0
    assert set(env.blob_dir().iterdir()) == blobs_before_rollback  # nothing deleted

    # 11. re-sign: same content, new sequence and validity window
    assert env.resign(now="2026-10-20T12:00:00Z") == 0
    assert env.attach() == 0
    resigned = env.live()
    assert resigned.sequence == 4 and resigned.active_release_id == A
    assert [r.release_id for r in resigned.releases] == [A, B]
    assert resigned.issued_at > rolled.issued_at

    # 12. the production catalog also passes the committed fixture keyring
    fixture_keyring = load_keyring(json.loads((FIXTURES / "keyring.json").read_text())["keys"])
    verify_catalog(
        env.catalog_bytes(),
        keyring=fixture_keyring,
        game=GAME,
        channel=Channel.PRODUCTION,
        now=NOW,
        last_seen=None,
    )
    assert env.verify_live("staging") == 0


# --------------------------------------------------------------------------- negative scenarios


def test_dry_runs_write_nothing(tmp_path: Path) -> None:
    env = Env(tmp_path / "dry")
    assert env.build(BUILD_A) == 0
    assert env.validate(BUILD_A) == 0

    assert (
        env.cli(
            "stage",
            "--game",
            GAME,
            "--manifest",
            str(env.manifest(BUILD_A)),
            "--store",
            str(env.store),
            "--keyring",
            str(env.keyring_path),
            "--key",
            str(env.pem),
            "--key-id",
            PRIMARY,
            "--now",
            NOW_ARG,
        )
        == 0
    )  # no --execute
    assert not (env.store / "v1").exists()

    assert (
        env.cli(
            "promote",
            "--game",
            GAME,
            "--manifest",
            str(env.manifest(BUILD_A)),
            "--store",
            str(env.store),
            "--keyring",
            str(env.keyring_path),
            "--releases-docs",
            str(env.docs),
            "--out",
            str(env.artifacts),
            "--now",
            NOW_ARG,
        )
        == 0
    )  # no --execute
    assert not (env.artifacts / "pending-production.payload.json").exists()
    assert not (env.store / "v1").exists()


def test_promote_refuses_a_flag_without_an_approval_record(tmp_path: Path) -> None:
    env = Env(tmp_path / "approval")
    (env.docs / f"{A}.md").unlink()  # no record for A
    assert env.build(BUILD_A) == 0
    assert env.validate(BUILD_A) == 0  # validate passes: a FLAG is not a STOP
    assert env.promote(BUILD_A) == 1  # promote refuses without the record
    assert not (env.artifacts / "pending-production.payload.json").exists()
    assert not (env.store / "v1" / "channels").exists()

    # a record without evidence does not cover G7: still refused
    write_approval_record(env.docs, A, accepted="G5", evidence=None)
    assert env.promote(BUILD_A) == 1
    assert not (env.artifacts / "pending-production.payload.json").exists()


def test_g6_blocks_validate_stage_and_promote_of_the_open_union_content(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The wire-valid fixture content of release-open-union-node-type must never be
    published - whatever approval record, environment variable or flag is around."""
    for variable in ("OTA_ALLOW_UNSUPPORTED_MECHANICS", "OTA_SKIP_G6", "TF_APPROVE_ALL"):
        monkeypatch.setenv(variable, "1")
    env = Env(tmp_path / "g6")

    from talent_data.ota.release import (
        build_manifest,
        build_release,
        release_files,
        serialize_manifest,
    )

    paladin = (FIXTURES / "blobs" / "release-open-union-node-type--paladin.json").read_bytes()
    normal = build_release(ROOT, COMMIT, GAME, BUILD_B, 1, CREATED_AT, VALIDATION_NOTE)
    snapshot = {**normal.snapshot, f"dataset/{GAME}/{BUILD_B}/paladin.json": paladin}
    files = release_files(snapshot)
    manifest = build_manifest(
        game=GAME,
        client_build=BUILD_B,
        revision=1,
        min_data_level=1,
        created_at=CREATED_AT,
        repository_commit=COMMIT,
        validation_note=VALIDATION_NOTE,
        files=files,
    )
    manifest_path = env.artifacts / B / "release.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_bytes(serialize_manifest(manifest))

    assert env.validate(BUILD_B, manifest=manifest_path) == 1
    assert (
        env.cli(
            "stage",
            "--game",
            GAME,
            "--manifest",
            str(manifest_path),
            "--store",
            str(env.store),
            "--keyring",
            str(env.keyring_path),
            "--key",
            str(env.pem),
            "--key-id",
            PRIMARY,
            "--execute",
            "--now",
            NOW_ARG,
        )
        == 1
    )
    assert env.promote(BUILD_B, manifest=manifest_path) == 1
    assert not (env.store / "v1" / "channels").exists()
    assert not (env.artifacts / "pending-production.payload.json").exists()


def test_verify_live_detects_a_missing_blob(neg_env: Env) -> None:
    blobs = sorted(neg_env.blob_dir().iterdir())
    blobs[-1].unlink()  # a blob of the only listed release disappears
    assert neg_env.verify_live() == 1


def test_verify_live_detects_a_corrupt_blob(neg_env: Env) -> None:
    blobs = sorted(neg_env.blob_dir().iterdir())
    corrupt = bytearray(blobs[-1].read_bytes())
    corrupt[-1] ^= 0xFF
    blobs[-1].write_bytes(bytes(corrupt))
    assert neg_env.verify_live() == 1


def test_verify_live_detects_an_expired_catalog(neg_env: Env) -> None:
    late = "2027-05-01T00:00:00Z"  # after expiresAt (issued + 45 days)
    assert neg_env.verify_live(now=late) == 1


def test_verify_live_reports_an_oversized_catalog(neg_env: Env) -> None:
    catalog = neg_env.store / catalog_key(Channel.PRODUCTION)
    catalog.write_bytes(catalog.read_bytes() + b" " * (1024 * 1024 + 1))
    assert neg_env.verify_live() == 1


def test_attach_refuses_a_wrong_signature(neg_env: Env) -> None:
    assert neg_env.resign() == 0  # a pending payload exists (A is already active)
    before = neg_env.catalog_bytes()
    payload = neg_env.artifacts / "pending-production.payload.json"
    wrong = neg_env.artifacts / "wrong.signature.der"
    # signed by the BACKUP key but handed in as PRIMARY: the signature must not verify
    wrong.write_bytes(sign_der(derive_test_key("test-backup"), payload.read_bytes()))
    assert neg_env.attach(signature=wrong) == 1
    assert neg_env.catalog_bytes() == before


def test_a_sequence_conflict_aborts_the_publication(neg_env: Env) -> None:
    assert neg_env.build(BUILD_B) == 0
    assert neg_env.promote(BUILD_B) == 0  # pending payload built on sequence 1

    # another publication wins the race (a re-sign of the live catalog)
    assert neg_env.resign(now="2026-10-19T09:00:00Z") == 0
    assert neg_env.attach() == 0  # live is now at sequence 2

    before = neg_env.catalog_bytes()
    assert neg_env.attach() == 1  # the stale promote payload aborts
    assert neg_env.catalog_bytes() == before
    assert neg_env.verify_live() == 0
    assert [r.release_id for r in neg_env.live().releases] == [A]  # B never landed


def test_a_failed_blob_upload_leaves_the_live_catalog_untouched(
    neg_env: Env, monkeypatch: pytest.MonkeyPatch
) -> None:
    from talent_data.ota import store as store_module
    from talent_data.ota.errors import OtaPublisherError

    before = neg_env.catalog_bytes()
    original_put = store_module.LocalDirectoryStore.put

    def failing_put(self, key: str, data: bytes, *, cache_control: str) -> None:
        if key.startswith("v1/blobs/"):
            raise OtaPublisherError(f"simulated blob upload failure for {key}")
        original_put(self, key, data, cache_control=cache_control)

    monkeypatch.setattr(store_module.LocalDirectoryStore, "put", failing_put)
    assert neg_env.build(BUILD_B) == 0
    assert neg_env.promote(BUILD_B) == 1  # the blob upload fails; the catalog survives
    monkeypatch.undo()
    assert neg_env.catalog_bytes() == before
    assert neg_env.verify_live() == 0


def test_gc_needs_execute_and_confirm_and_keeps_referenced_blobs(neg_env: Env) -> None:
    from talent_data.ota.verify import sha256_ref

    referenced = len(list(neg_env.blob_dir().iterdir()))
    junk = neg_env.blob_dir() / sha256_ref(b"abandoned").split(":")[1]
    junk.write_bytes(b"abandoned")

    assert neg_env.gc() == 0  # lists only
    assert junk.exists()
    assert neg_env.gc("--execute") == 0  # still not enough
    assert junk.exists()
    assert neg_env.gc("--execute", "--confirm") == 0
    assert not junk.exists()
    assert len(list(neg_env.blob_dir().iterdir())) == referenced
    assert neg_env.verify_live() == 0  # every referenced blob survived


def test_rollback_cannot_name_an_unlisted_release(neg_env: Env) -> None:
    assert neg_env.rollback(active=f"{BUILD_B}-r9") == 1
    assert neg_env.verify_live() == 0


# --------------------------------------------------------------------------- review fixes
# The blocks below pin the behaviours the PR #43 review demanded: stage can never
# touch production, attach-signature re-proves the whole publication from the signed
# bytes, gc fails closed on a manifest that does not match its pin, an expired
# catalog can be recovered, and an approval record pins the bytes it approves.


def _pending(env: Env, channel: str = "production") -> Path:
    return env.artifacts / f"pending-{channel}.payload.json"


def _rewrite_pending(payload_path: Path, mutate) -> None:
    """Local-tamperer helper: edit the pending payload AND its meta pin, so only the
    attach-time structural checks (not the pin) can catch what follows."""
    import json

    doc = json.loads(payload_path.read_text())
    mutate(doc)
    payload = json.dumps(doc, indent=2, ensure_ascii=False).encode("utf-8") + b"\n"
    payload_path.write_bytes(payload)
    import hashlib

    meta_path = payload_path.with_name(payload_path.name + ".meta.json")
    meta = json.loads(meta_path.read_text())
    meta["payloadSha256"] = "sha256:" + hashlib.sha256(payload).hexdigest()
    meta_path.write_text(json.dumps(meta, indent=2) + "\n")


def test_stage_cannot_target_the_production_channel(tmp_path: Path) -> None:
    env = Env(tmp_path / "stage-lock")
    assert env.build(BUILD_A) == 0
    with pytest.raises(SystemExit):  # argparse itself rejects --channel production
        env.cli(
            "stage",
            "--game",
            GAME,
            "--manifest",
            str(env.manifest(BUILD_A)),
            "--store",
            str(env.store),
            "--keyring",
            str(env.keyring_path),
            "--key",
            str(env.pem),
            "--key-id",
            PRIMARY,
            "--channel",
            "production",
            "--execute",
            "--now",
            NOW_ARG,
        )
    # and the handler refuses too, whatever a future parser change allows through
    from talent_data.cli import ota as ota_cli

    namespace = argparse.Namespace(
        game=GAME,
        channel="production",
        manifest=env.manifest(BUILD_A),
        store=env.store,
        keyring=env.keyring_path,
        key=env.pem,
        key_id=PRIMARY,
        execute=True,
        now=NOW_ARG,
        main_ref="origin/main",
        repo=None,
    )
    assert ota_cli._cmd_stage(namespace) == 1
    assert not (env.store / "v1").exists()


def test_attach_refuses_bytes_edited_after_promote(neg_env: Env) -> None:
    """The pending meta pins the payload promote produced: a payload file edited
    afterwards (even correctly re-signed) is refused - the signed bytes never reach
    the store."""
    assert neg_env.build(BUILD_B) == 0
    assert neg_env.promote(BUILD_B) == 0
    before = neg_env.catalog_bytes()

    payload = _pending(neg_env)
    doc = json.loads(payload.read_bytes())
    doc["sequence"] = 99  # an edit; the meta sidecar still pins promote's bytes
    payload.write_text(json.dumps(doc, indent=2) + "\n")
    signature = neg_env.sign_pending()
    assert neg_env.attach(signature=signature) == 1
    assert neg_env.catalog_bytes() == before


def test_attach_refuses_a_payload_of_the_wrong_channel(neg_env: Env) -> None:
    assert neg_env.build(BUILD_B) == 0
    assert neg_env.promote(BUILD_B) == 0
    before = neg_env.catalog_bytes()

    payload = _pending(neg_env)
    _rewrite_pending(payload, lambda doc: doc.update(channel="staging"))
    signature = neg_env.sign_pending()
    # attaching the staging payload with --channel production (the CLI default):
    assert neg_env.attach(signature=signature) == 1
    assert neg_env.catalog_bytes() == before


def test_attach_refuses_a_sequence_jump(neg_env: Env) -> None:
    assert neg_env.build(BUILD_B) == 0
    assert neg_env.promote(BUILD_B) == 0
    before = neg_env.catalog_bytes()

    payload = _pending(neg_env)
    _rewrite_pending(payload, lambda doc: doc.update(sequence=doc["sequence"] + 5))
    signature = neg_env.sign_pending()
    assert neg_env.attach(signature=signature) == 1
    assert neg_env.catalog_bytes() == before


def test_attach_refuses_an_append_only_violation(neg_env: Env) -> None:
    assert neg_env.build(BUILD_B) == 0
    assert neg_env.promote(BUILD_B) == 0  # pending: sequence 2, releases [A, B]
    before = neg_env.catalog_bytes()

    payload = _pending(neg_env)
    _rewrite_pending(payload, lambda doc: doc["releases"].pop(0))  # unlist A
    signature = neg_env.sign_pending()
    assert neg_env.attach(signature=signature) == 1
    assert neg_env.catalog_bytes() == before


def test_attach_refuses_when_a_referenced_blob_is_missing(neg_env: Env) -> None:
    assert neg_env.build(BUILD_B) == 0
    assert neg_env.promote(BUILD_B) == 0
    before = neg_env.catalog_bytes()
    pending = json.loads(_pending(neg_env).read_bytes())
    entry_b = next(r for r in pending["releases"] if r["releaseId"] == B)
    manifest_blob = neg_env.blob_dir() / entry_b["manifestSha256"].split(":")[1]
    first_file_sha = re.search(r"sha256:[0-9a-f]{64}", manifest_blob.read_text()).group(0)
    (neg_env.blob_dir() / first_file_sha.split(":")[1]).unlink()
    signature = neg_env.sign_pending()
    assert neg_env.attach(signature=signature) == 1
    assert neg_env.catalog_bytes() == before


def test_gc_aborts_when_a_manifest_does_not_match_its_pin(neg_env: Env) -> None:
    """A manifest corrupted into different-but-valid JSON must stop gc: the pin check
    (not parseability) is what may drive deletions."""
    from talent_data.ota.verify import sha256_ref

    entry = neg_env.live().releases[0]
    manifest_blob = neg_env.blob_dir() / entry.manifest_sha256.split(":")[1]
    doc = json.loads(manifest_blob.read_text())
    doc["files"] = doc["files"][:1]  # valid JSON, references almost nothing
    manifest_blob.write_text(json.dumps(doc, indent=2))

    junk = neg_env.blob_dir() / sha256_ref(b"junk").split(":")[1]
    junk.write_bytes(b"junk")
    assert neg_env.gc("--execute", "--confirm") == 1  # aborts before any deletion
    assert junk.exists()


def test_an_expired_catalog_is_recovered_by_resign(neg_env: Env) -> None:
    late = "2027-01-05T00:00:00Z"  # beyond issuedAt + 45 days
    assert neg_env.verify_live(now=late) == 1  # clients reject the lapsed catalog

    assert neg_env.resign(now=late) == 0  # recovery read tolerates the lapse
    assert neg_env.attach() == 0  # so does the write's live read
    recovered = neg_env.live()
    assert recovered.sequence == 2
    assert neg_env.verify_live(now=late) == 0  # the fresh window covers 'late'
    assert neg_env.verify_live() == 0  # and still works at the original now


def test_an_expired_catalog_can_be_rolled_back(neg_env: Env) -> None:
    late = "2027-01-05T00:00:00Z"
    assert neg_env.build(BUILD_B) == 0
    assert neg_env.promote(BUILD_B) == 0
    assert neg_env.attach() == 0
    assert neg_env.verify_live() == 0

    assert neg_env.verify_live(now=late) == 1  # lapsed
    assert neg_env.rollback(A, now=late) == 0  # recovery path on the lapsed catalog
    assert neg_env.attach() == 0
    assert neg_env.verify_live(now=late) == 0
    assert neg_env.live().active_release_id == A


def test_promote_refuses_an_approval_record_pinning_other_bytes(tmp_path: Path) -> None:
    env = Env(tmp_path / "approval-pin")
    write_approval_record(env.docs, A, manifest_sha256="sha256:" + "0" * 64)  # wrong pin
    assert env.build(BUILD_A) == 0
    assert env.promote(BUILD_A) == 1
    assert not (env.artifacts / "pending-production.payload.json").exists()
