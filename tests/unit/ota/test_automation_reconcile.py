"""Offline reconciliation, exact CDN convergence and renewal of existing catalogs."""

# ruff: noqa: F811

from collections import Counter
from datetime import timedelta

import httpx
import pytest

from talent_data.ota.automation.publisher import read_live, renew_catalog
from talent_data.ota.automation.reconcile import (
    confirm_publication,
    reconcile_requests,
    resign_due,
    select_requests,
    verify_after_write,
)
from talent_data.ota.automation.state import (
    FileJournal,
    JournalError,
    RenewalReceipt,
    RequestState,
)
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import SignedEnvelope
from talent_data.ota.store import LocalDirectoryStore, blob_key
from talent_data.ota.verify import OtaError, parse_strict, sha256_ref, strict_b64decode
from tests.unit.ota.publisher_testkit import GAME, NOW, derive_test_key
from tests.unit.ota.test_automation_prepare import prepared_inputs  # noqa: F401
from tests.unit.ota.test_automation_provenance import exact_package  # noqa: F401
from tests.unit.ota.test_automation_public_verify import catalog_of, resign
from tests.unit.ota.test_automation_publisher import env  # noqa: F401
from tests.unit.ota.test_ota_preflight import (
    CATALOG,
    KEYRING,
    ORIGIN,
    PROD_A,
    FakeOrigin,
    publication,
)
from tests.unit.ota.test_ota_production_release import world  # noqa: F401


class Clock:
    value = 0.0

    def time(self):
        return self.value

    def sleep(self, delay):
        assert 0 < delay <= 30
        self.value += delay


@pytest.fixture
def renewal(tmp_path):
    objects = publication()
    store = LocalDirectoryStore(tmp_path / "store")
    for key, raw in objects.items():
        store.put(key, raw, cache_control="public")
    journal = FileJournal(tmp_path / "renewal-journal.json")
    journal.initialize()

    class Signer:
        calls = 0

        def sign(self, data):
            from cryptography.hazmat.primitives import hashes
            from cryptography.hazmat.primitives.asymmetric import ec

            self.calls += 1
            return derive_test_key(PROD_A).sign(data, ec.ECDSA(hashes.SHA256()))

    signer = Signer()

    def run(at=NOW + timedelta(days=30)):
        return renew_catalog(
            store=store,
            keyring=KEYRING,
            game=GAME,
            now=at,
            journal=journal,
            signer=signer,
            run_id=20,
            attempt=1,
            key_id=PROD_A,
        )

    return store, journal, signer, run


def test_canceled_pending_run_is_reconciled_from_committed_request(env):
    request = env.receipt.request
    live = read_live(env.store, keyring=env.owner.keyring, game=GAME, now=NOW).catalog
    selected = reconcile_requests([request], journal=env.journal, live=live, run_id=11, attempt=1)
    assert selected == (request,)
    assert env.journal.load(env.receipt.request_digest).receipt.state is RequestState.PENDING
    # No webhook or artifact lookup participates in discovery/selection.
    assert select_requests(
        [request, request],
        {env.receipt.request_digest: env.journal.load(env.receipt.request_digest).receipt},
        live=live,
    ) == (request,)
    assert env.run().state is RequestState.PUBLISHED_NOT_VERIFIED
    assert reconcile_requests([request], journal=env.journal, live=live, run_id=12, attempt=1) == ()


def test_stale_committed_request_is_explicitly_recorded(env):
    request = env.receipt.request
    live = read_live(env.store, keyring=env.owner.keyring, game=GAME, now=NOW).catalog
    other = live.model_copy(update={"active_release_id": "different-r1"})
    assert (
        reconcile_requests([request], journal=env.journal, live=other, run_id=11, attempt=1) == ()
    )
    receipt = env.journal.load(env.receipt.request_digest).receipt
    assert (receipt.state, receipt.reason) == (RequestState.STALE, "LIVE_CHANGED")


def test_conflicting_committed_requests_are_blocked(env):
    request = env.receipt.request
    conflict = request.model_copy(update={"source_commit": "f" * 40})
    live = read_live(env.store, keyring=env.owner.keyring, game=GAME, now=NOW).catalog
    assert (
        reconcile_requests(
            [request, conflict], journal=env.journal, live=live, run_id=11, attempt=1
        )
        == ()
    )
    assert len(env.journal.read_all()) == 2
    assert all(x.receipt.state is RequestState.BLOCKED for x in env.journal.read_all().values())


def test_select_requests_refuses_corrupt_trusted_identity(env):
    live = read_live(env.store, keyring=env.owner.keyring, game=GAME, now=NOW).catalog
    with pytest.raises(JournalError):
        select_requests([env.receipt.request], {"wrong": env.receipt}, live=live)


def test_resign_at_15_days_preserves_all_release_members(renewal):
    store, journal, signer, run = renewal
    from talent_data.ota.catalog import catalog_entry
    from talent_data.ota.release import serialize_manifest
    from tests.unit.ota.test_ota_preflight import real_release

    base = read_live(store, keyring=KEYRING, game=GAME, now=NOW).catalog
    artifact = real_release()
    manifest = artifact.manifest.model_copy(
        update={
            "release_id": artifact.manifest.release_id.rsplit("-r", 1)[0] + "-r2",
        }
    )
    raw = serialize_manifest(manifest)
    store.put(blob_key(sha256_ref(raw)), raw, cache_control="public")
    second = catalog_entry(raw, manifest, published_at=NOW)
    objects = {}
    resign(
        objects,
        base.model_copy(
            update={"releases": [*base.releases, second], "active_release_id": second.release_id}
        ),
    )
    store.put(CATALOG, objects[CATALOG], cache_control="public")
    before = read_live(store, keyring=KEYRING, game=GAME, now=NOW)
    assert len(before.catalog.releases) == 2
    result = run()
    assert isinstance(result, RenewalReceipt)
    assert result.state is RequestState.PUBLISHED_NOT_VERIFIED
    after = read_live(store, keyring=KEYRING, game=GAME, now=NOW + timedelta(days=30))
    assert after.catalog.releases == before.catalog.releases
    assert after.catalog.active_release_id == before.catalog.active_release_id
    assert after.catalog.sequence == before.catalog.sequence + 1
    assert after.catalog.expires_at == after.catalog.issued_at + timedelta(days=45)
    assert journal.load(result.request_digest).prepared.payload == after.payload
    assert run() is None
    assert signer.calls == 1
    assert store.get(CATALOG) == after.envelope


@pytest.mark.parametrize("fault", ["expired", "signature", "reference"])
def test_untrusted_catalog_is_never_resigned(renewal, fault):
    store, journal, signer, run = renewal
    at = NOW + timedelta(days=30)
    if fault == "expired":
        at = NOW + timedelta(days=46)
    elif fault == "signature":
        store.put(CATALOG, b"{}", cache_control="public")
    else:
        before = read_live(store, keyring=KEYRING, game=GAME, now=NOW)

        store.put(
            blob_key(before.catalog.releases[0].manifest_sha256), b"bad", cache_control="public"
        )
    original = store.get(CATALOG)
    with pytest.raises((OtaPublisherError, OtaError, ValueError)):
        run(at)
    assert signer.calls == 0
    assert store.get(CATALOG) == original
    assert journal.read_all() == {}


def test_resign_due_boundary(renewal):
    store, _, _, _ = renewal
    catalog = read_live(store, keyring=KEYRING, game=GAME, now=NOW).catalog
    assert not resign_due(catalog, now=NOW + timedelta(days=30, microseconds=-1))
    assert resign_due(catalog, now=NOW + timedelta(days=30))
    assert not resign_due(catalog, now=catalog.expires_at)


def verify(objects, clock, handler=None, **options):
    origin = FakeOrigin(objects)
    with httpx.Client(transport=httpx.MockTransport(handler or origin.handler)) as client:
        return verify_after_write(
            client,
            origin=ORIGIN,
            keyring=KEYRING,
            game=GAME,
            now=NOW + timedelta(days=30),
            clock=clock.time,
            sleeper=clock.sleep,
            **options,
        )


def test_signed_wrong_expected_payload_does_not_download_old_blobs():
    objects = publication()
    origin, clock, counts = FakeOrigin(objects), Clock(), Counter()

    def handler(request):
        counts[request.url.path] += 1
        return origin.handler(request)

    result = verify(
        objects, clock, handler, expected_payload_sha256="sha256:" + "f" * 64, budget_seconds=60
    )
    assert not result.ok
    assert set(counts) == {"/" + CATALOG}
    assert clock.value == 60


def test_s3_success_cdn_old_then_converged_does_not_republish(renewal, monkeypatch):
    store, journal, _, run = renewal
    old = store.get(CATALOG)
    puts = []
    original = store.put

    def put(key, raw, *, cache_control):
        puts.append(key)
        original(key, raw, cache_control=cache_control)

    monkeypatch.setattr(store, "put", put)
    receipt = run()
    current = store.get(CATALOG)
    objects = publication()
    objects[CATALOG] = current
    origin, clock = FakeOrigin(objects), Clock()

    def handler(request):
        origin.objects[CATALOG] = old if clock.value < 30 else current
        return origin.handler(request)

    result = verify(
        objects, clock, handler, expected_payload_sha256=receipt.payload_sha256, budget_seconds=60
    )
    confirmed = confirm_publication(journal, receipt.request_digest, result)
    assert confirmed.state is RequestState.VERIFIED
    assert puts == [CATALOG]
    assert clock.value == 30


def test_cdn_budget_exhausted_is_published_not_verified(renewal):
    _, journal, _, run = renewal
    receipt = run()
    result = verify(
        publication(), Clock(), expected_payload_sha256=receipt.payload_sha256, budget_seconds=30
    )
    assert not result.ok
    assert confirm_publication(journal, receipt.request_digest, result) == receipt
    assert journal.load(receipt.request_digest).receipt.state is RequestState.PUBLISHED_NOT_VERIFIED


def test_catalog_snapshot_change_never_confirms_expected_payload():
    objects, clock = publication(), Clock()
    expected = sha256_ref(strict_b64decode(parse_strict(SignedEnvelope, objects[CATALOG]).payload))
    origin = FakeOrigin(objects)
    count = 0

    def handler(request):
        nonlocal count
        if request.url.path == "/" + CATALOG:
            count += 1
            if count == 2:
                resign(origin.objects, catalog_of(objects).model_copy(update={"sequence": 2}))
        return origin.handler(request)

    result = verify(objects, clock, handler, expected_payload_sha256=expected, budget_seconds=1)
    assert not result.ok and "SNAPSHOT_CHANGED" in result.problem


def test_renewal_durable_failure_blocks_put(renewal, monkeypatch):
    store, journal, _, run = renewal
    before = store.get(CATALOG)
    original = journal.save

    def fail(receipt, prepared=None):
        result = original(receipt, prepared)
        if receipt.state is RequestState.WRITE_STARTED:
            raise JournalError("TEST crash after durable barrier")
        return result

    monkeypatch.setattr(journal, "save", fail)
    with pytest.raises(JournalError):
        run()
    monkeypatch.setattr(journal, "save", original)
    assert store.get(CATALOG) == before
    assert run().state is RequestState.PREPARED
    assert run().state is RequestState.PUBLISHED_NOT_VERIFIED


def test_removed_pending_request_is_stale_but_terminal_index_survives(env):
    env.journal.save(env.receipt)
    live = read_live(env.store, keyring=env.owner.keyring, game=GAME, now=NOW).catalog
    assert reconcile_requests([], journal=env.journal, live=live, run_id=11, attempt=1) == ()
    retained = env.journal.load(env.receipt.request_digest)
    assert (retained.receipt.state, retained.receipt.reason) == (
        RequestState.STALE,
        "REQUEST_REMOVED",
    )
    assert (
        reconcile_requests(
            [env.receipt.request], journal=env.journal, live=live, run_id=12, attempt=1
        )
        == ()
    )


def test_renewal_and_release_share_unresolved_write_barrier(renewal, env, monkeypatch):
    _, journal, signer, run = renewal
    original = env.journal.save

    def interrupt(receipt, prepared=None):
        result = original(receipt, prepared)
        if receipt.state is RequestState.WRITE_STARTED:
            raise JournalError("TEST crash")
        return result

    monkeypatch.setattr(env.journal, "save", interrupt)
    with pytest.raises(JournalError):
        env.run()
    release = next(iter(env.journal.read_all().values()))
    journal.save(release.receipt, release.prepared)
    with pytest.raises(JournalError):
        run()
    assert signer.calls == 0
    before = release.receipt
    live = read_live(env.store, keyring=env.owner.keyring, game=GAME, now=NOW).catalog
    reconcile_requests([], journal=env.journal, live=live, run_id=12, attempt=1)
    assert env.journal.load(before.request_digest).receipt == before


def test_renewal_lost_response_and_ambiguous_retry_never_resign_again(renewal, monkeypatch):
    store, _, signer, run = renewal
    original_put, original_get = store.put, store.get
    writes = []

    def landed(key, raw, *, cache_control):
        original_put(key, raw, cache_control=cache_control)
        if key == CATALOG:
            writes.append(key)
            raise OtaPublisherError("TEST lost response")

    def unreadable(key):
        if key == CATALOG and writes:
            raise OtaPublisherError("TEST unavailable")
        return original_get(key)

    monkeypatch.setattr(store, "put", landed)
    monkeypatch.setattr(store, "get", unreadable)
    first = run()
    assert first.state is RequestState.AMBIGUOUS
    assert run() == first
    monkeypatch.setattr(store, "get", original_get)
    assert run().state is RequestState.PUBLISHED_NOT_VERIFIED
    assert signer.calls == 1 and writes == [CATALOG]


def test_verification_stops_new_files_at_deadline_and_advances_expiry():
    objects, clock = publication(), Clock()
    expected = sha256_ref(strict_b64decode(parse_strict(SignedEnvelope, objects[CATALOG]).payload))
    origin, counts = FakeOrigin(objects), Counter()

    def slow(request):
        counts[request.url.path] += 1
        clock.value += 2
        return origin.handler(request)

    result = verify(objects, clock, slow, expected_payload_sha256=expected, budget_seconds=1)
    assert not result.ok and set(counts) == {"/" + CATALOG}
    assert clock.value == 2


def test_verification_retry_uses_elapsed_time_for_catalog_expiry():
    objects = publication(validity=timedelta(seconds=20))
    expected = sha256_ref(strict_b64decode(parse_strict(SignedEnvelope, objects[CATALOG]).payload))
    origin, clock = FakeOrigin(objects), Clock()
    counts = Counter()

    def handler(request):
        counts[request.url.path] += 1
        # First attempt fails transiently; on next attempt the signature is expired.
        if request.url.path == "/" + CATALOG and clock.value == 0:
            from tests.unit.ota.test_ota_preflight import respond

            return respond(503)
        return origin.handler(request)

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = verify_after_write(
            client,
            origin=ORIGIN,
            keyring=KEYRING,
            game=GAME,
            now=NOW,
            expected_payload_sha256=expected,
            budget_seconds=31,
            sleeper=clock.sleep,
            clock=clock.time,
        )
    assert not result.ok and "CATALOG_EXPIRED" in result.problem
    assert set(counts) == {"/" + CATALOG}


def test_zero_budget_and_nonfinite_budget_perform_no_requests():
    clock = Clock()

    def forbidden(request):
        pytest.fail("no request allowed after exhausted deadline")

    assert not verify(
        {}, clock, forbidden, expected_payload_sha256="sha256:" + "f" * 64, budget_seconds=0
    ).ok
    with pytest.raises(ValueError):
        verify(
            {},
            clock,
            forbidden,
            expected_payload_sha256="sha256:" + "f" * 64,
            budget_seconds=float("inf"),
        )


def test_renewal_receipt_wire_is_closed_and_required(renewal):
    import json

    from talent_data.ota.automation.state import JournalIndex
    from talent_data.ota.verify import parse_strict

    _, journal, _, run = renewal
    receipt = run()
    raw = json.loads(journal.path.read_bytes())
    assert isinstance(
        parse_strict(JournalIndex, journal.path.read_bytes())
        .entries[receipt.request_digest]
        .receipt,
        RenewalReceipt,
    )
    operation = raw["entries"][receipt.request_digest]["receipt"]["operation"]
    for change in ({"kind": "release"}, {"basePayloadB64": 10}, {"unknown": True}):
        changed = {**operation, **change}
        raw["entries"][receipt.request_digest]["receipt"]["operation"] = changed
        with pytest.raises(ValueError):
            parse_strict(JournalIndex, json.dumps(raw).encode())
    raw["entries"][receipt.request_digest]["receipt"]["operation"] = {
        "basePayloadB64": operation["basePayloadB64"]
    }
    with pytest.raises(ValueError):
        parse_strict(JournalIndex, json.dumps(raw).encode())


def test_published_renewal_not_confirmed_by_other_fully_verified_payload(renewal):
    _, journal, _, run = renewal
    receipt = run()
    objects = publication()
    digest = sha256_ref(strict_b64decode(parse_strict(SignedEnvelope, objects[CATALOG]).payload))
    result = verify(objects, Clock(), expected_payload_sha256=digest, budget_seconds=30)
    assert result.ok
    assert confirm_publication(journal, receipt.request_digest, result) == receipt
