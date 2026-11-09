"""Performance counts retain the real manifest parser and complete semantic proof."""

import pytest


def test_catalog_header_is_sixty_blobs_unchanged():
    from talent_data.ota.store import BLOB_CACHE_CONTROL, CATALOG_CACHE_CONTROL

    assert CATALOG_CACHE_CONTROL == "public, max-age=60"
    assert BLOB_CACHE_CONTROL == "public, max-age=31536000, immutable"


@pytest.mark.parametrize(("workers", "expected"), [(1, 6003), (8, 2772)])
def test_fixture_reference_counts_2001_to_924(workers, expected):
    from scripts.benchmark_ota_delivery import measure

    result = measure("fixture", workers, latency_ms=0)
    assert result.file_references == 1996 and result.unique_files == 919
    assert result.private_gets == expected
    assert result.proof_verified and result.catalog_payload_sha256.startswith("sha256:")
    assert result.elapsed_seconds >= result.reference_seconds >= 0


def test_benchmark_public_mode_cannot_create_writer(monkeypatch):
    from scripts import benchmark_ota_delivery as benchmark

    from talent_data.ota import s3_store
    from talent_data.ota.automation.public_verify import PublicVerification

    monkeypatch.setattr(s3_store, "connect", lambda *a, **kw: pytest.fail("no private S3"))
    monkeypatch.setattr(benchmark, "fixture", lambda *a, **kw: pytest.fail("no signer"))
    monkeypatch.setattr(
        benchmark,
        "verify_publication",
        lambda *a, **kw: PublicVerification(True, "sha256:" + "a" * 64, "1.60.1.70862-r1", 5, None),
    )
    result = benchmark.measure("public", 8)
    assert result.proof_verified and result.private_gets == 0


def test_latency_measurement_does_not_replace_full_correctness_proof(monkeypatch):
    from scripts import benchmark_ota_delivery as benchmark

    from talent_data.ota.automation.public_verify import PublicVerification

    monkeypatch.setattr(
        benchmark,
        "verify_publication",
        lambda *a, **kw: PublicVerification(False, None, None, None, "BLOB_HASH_MISMATCH"),
    )
    result = benchmark.measure("fixture", 8)
    assert result.private_gets == 2772 and not result.proof_verified
