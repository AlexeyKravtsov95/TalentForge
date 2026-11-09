"""Read-only OTA benchmark. Fixture signing uses a public TEST scalar, never custody keys."""

from __future__ import annotations

import argparse
from collections.abc import Mapping
from contextlib import nullcontext
from datetime import UTC, datetime, timedelta
from pathlib import Path
from threading import Lock
from time import perf_counter, sleep
from typing import Literal

import httpx
from pydantic import Field, field_validator

from talent_data.ota import io_metrics
from talent_data.ota.automation.public_verify import verify_publication
from talent_data.ota.automation.requests import _Model
from talent_data.ota.catalog import catalog_entry, next_catalog
from talent_data.ota.models import Channel, FileRole
from talent_data.ota.preflight import production_keyring
from talent_data.ota.release import build_manifest, build_release, release_files, serialize_manifest
from talent_data.ota.s3_channels import PRODUCTION_PROFILE
from talent_data.ota.store import blob_key, ensure_blob, ensure_catalog_references
from talent_data.ota.verify import (
    TrustedKey,
    parse_strict,
    sha256_ref,
    verify_release_files,
    verify_release_manifest,
)

ROOT = Path(__file__).resolve().parents[1]


class Measurement(_Model):
    schema_version: Literal[1]
    kind: Literal["ota-read-benchmark"]
    mode: Literal["fixture", "public"]
    workers: Literal[1, 8]
    private_gets: int = Field(ge=0)
    public_gets: int = Field(ge=0)
    transferred_bytes: int = Field(ge=0)
    max_private_in_flight: int = Field(ge=0, le=8)
    file_references: int | None = Field(ge=0)
    unique_files: int | None = Field(ge=0)
    elapsed_seconds: float = Field(ge=0, allow_inf_nan=False)
    reference_seconds: float = Field(ge=0, allow_inf_nan=False)
    proof_seconds: float = Field(ge=0, allow_inf_nan=False)
    proof_verified: bool
    catalog_payload_sha256: str | None = Field(pattern=r"^sha256:[a-f0-9]{64}$")
    end_to_end_slo_proven: Literal[False]

    @field_validator("schema_version", mode="before")
    @classmethod
    def version_type(cls, value: object) -> object:
        if type(value) is not int:
            raise ValueError("integer schemaVersion required")
        return value


class Counter:
    def __init__(self):
        self.lock = Lock()
        self.gets = self.bytes = self.active = self.peak = 0

    def count(self, operation, *, bytes, retry=False, sha_ref=None):
        with self.lock:
            self.gets += operation == "GET"
            self.bytes += bytes

    def add_bytes(self, amount):
        with self.lock:
            self.bytes += amount

    def incomplete(self):
        pass

    def phase(self, name):
        return nullcontext()

    def record_failure(self, diagnostic, stage):
        pass


class ReadOnlyStore:
    def __init__(self, objects: Mapping[str, bytes], counter: Counter, delay: float):
        self.objects, self.counter, self.delay = objects, counter, delay

    def get(self, key: str) -> bytes:
        with self.counter.lock:
            self.counter.active += 1
            self.counter.peak = max(self.counter.peak, self.counter.active)
        try:
            if self.delay:
                sleep(self.delay)
            raw = self.objects[key]
            self.counter.count("GET", bytes=len(raw))
            return raw
        finally:
            with self.counter.lock:
                self.counter.active -= 1

    def exists(self, key):
        raise AssertionError("read-only benchmark never HEADs for upload")

    def put(self, *args, **kwargs):
        raise AssertionError("read-only benchmark cannot PUT")

    def delete(self, *args, **kwargs):
        raise AssertionError("read-only benchmark cannot delete")

    def list(self, *args, **kwargs):
        raise AssertionError("read-only benchmark cannot list a private bucket")


def fixture(now: datetime):
    """Five semantically valid TEST releases: 1,996 file references / 919 unique files."""
    from cryptography.hazmat.primitives.asymmetric import ec

    from talent_data.models.visuals import TalentIconRegistry, expected_content_hash
    from talent_data.ota.signing import build_envelope, sign_pae_der

    source = "c749ebc5fc31c43be742844cb89d012f100a2a0a"
    build = "1.60.1.70374"
    base = build_release(
        ROOT,
        source,
        "forever",
        build,
        1,
        datetime(2026, 10, 17, 23, tzinfo=UTC),
        "TEST benchmark input",
    )
    registry_path = f"visuals/forever/{build}/icons.json"
    registry = parse_strict(TalentIconRegistry, base.snapshot[registry_path])
    image_paths = {f.path for f in base.manifest.files if f.role is FileRole.ICON_IMAGE}
    stable = {p: b for p, b in base.snapshot.items() if p not in image_paths and p != registry_path}
    non_image_unique = len({sha256_ref(b) for b in stable.values()})
    unique_images = 919 - non_image_unique - 5  # one distinct registry per revision
    sample = base.snapshot[f"visuals/forever/{build}/" + registry.icons[0].file]
    objects, entries = {}, []
    for index in range(5):
        contents = dict(stable)
        image_count = (400 if index == 4 else 399) - len(stable) - 1
        ids = [icon.file_data_id for icon in registry.icons]
        ids += [2_000_000_000 + n for n in range(image_count - len(ids))]
        icons = []
        for position, file_id in enumerate(sorted(ids)):
            token = (index * (399 - len(stable) - 1) + position) % unique_images
            raw = sample + b"TEST-OTA-BENCHMARK-" + token.to_bytes(4, "big")
            icon = registry.icons[0].model_copy(
                update={
                    "file_data_id": file_id,
                    "file": f"icons/{file_id}.png",
                    "sha256": sha256_ref(raw),
                    "byte_length": len(raw),
                }
            )
            icons.append(icon)
            contents[f"visuals/forever/{build}/{icon.file}"] = raw
        changed = registry.model_copy(update={"icons": icons})
        changed = changed.model_copy(update={"content_hash": expected_content_hash(changed)})
        contents[registry_path] = changed.to_json().encode()
        manifest = build_manifest(
            game="forever",
            client_build=build,
            revision=index + 1,
            min_data_level=1,
            created_at=now - timedelta(seconds=1),
            repository_commit=source,
            validation_note="TEST benchmark only; no publication",
            files=release_files(contents),
        )
        raw_manifest = serialize_manifest(manifest)
        entry = catalog_entry(raw_manifest, manifest, published_at=now)
        verified = verify_release_manifest(
            raw_manifest, entry, game="forever", supported_data_level=1
        )
        verify_release_files(verified, lambda file, contents=contents: contents[file.path])
        entries.append(entry)
        objects[blob_key(sha256_ref(raw_manifest))] = raw_manifest
        for raw in contents.values():
            objects[blob_key(sha256_ref(raw))] = raw
    catalog = next_catalog(
        None,
        game="forever",
        channel=Channel.PRODUCTION,
        active_release_id=entries[0].release_id,
        append=entries[0],
        issued_at=now,
    ).catalog
    catalog = catalog.model_copy(
        update={"releases": entries, "active_release_id": entries[-1].release_id}
    )
    key = ec.derive_private_key(1, ec.SECP256R1())  # PUBLIC TEST scalar; never a production key.
    objects["v1/channels/production/catalog.json"] = build_envelope(
        catalog.model_dump_json(by_alias=True).encode(),
        {"test-benchmark": sign_pae_der(key, catalog.model_dump_json(by_alias=True).encode())},
    )
    return catalog, objects, {"test-benchmark": TrustedKey("test-benchmark", key.public_key())}


def measure(
    mode: Literal["fixture", "public"], workers: Literal[1, 8], *, latency_ms: float = 0
) -> Measurement:
    if (
        mode not in {"fixture", "public"}
        or type(workers) is not int
        or workers not in (1, 8)
        or not 0 <= latency_ms <= 50
    ):
        raise ValueError("bounded benchmark settings required")
    started = perf_counter()
    private, public = Counter(), Counter()
    refs = unique = None
    reference_seconds = 0.0
    now = datetime(2026, 11, 8, 12, tzinfo=UTC) if mode == "fixture" else datetime.now(UTC)
    origin = PRODUCTION_PROFILE.public_origin
    transport = None
    if mode == "fixture":
        catalog, objects, keys = fixture(now)
        store = ReadOnlyStore(objects, private, latency_ms / 1000)
        tick = perf_counter()
        for _ in range(3):
            if workers == 8:
                ensure_catalog_references(store, catalog, read_workers=8)
            else:
                # Original serial reference algorithm, with the same strict manifest verifier.
                for entry in catalog.releases:
                    manifest = verify_release_manifest(
                        ensure_blob(store, entry.manifest_sha256),
                        entry,
                        game="forever",
                        supported_data_level=1,
                    )
                    for file in manifest.files:
                        if len(ensure_blob(store, file.sha256)) != file.byte_length:
                            raise ValueError("reference length mismatch")
        reference_seconds = perf_counter() - tick
        manifests = [
            verify_release_manifest(
                objects[blob_key(e.manifest_sha256)], e, game="forever", supported_data_level=1
            )
            for e in catalog.releases
        ]
        refs = sum(len(m.files) for m in manifests)
        unique = len({f.sha256 for m in manifests for f in m.files})
        origin = "https://benchmark.invalid"

        def handler(request):
            raw = objects.get(request.url.path.lstrip("/"))
            return (
                httpx.Response(200, stream=httpx.ByteStream(raw))
                if raw is not None
                else httpx.Response(404)
            )

        transport = httpx.MockTransport(handler)
    else:
        keys = production_keyring(ROOT / "docs/ota/keys/production-keyring.json")
    tick = perf_counter()
    with (
        httpx.Client(transport=transport, follow_redirects=False, timeout=30) as client,
        io_metrics.observe(public),
    ):
        proof = verify_publication(
            client, origin=origin, keyring=keys, game="forever", now=now, read_workers=workers
        )
    return Measurement(
        schema_version=1,
        kind="ota-read-benchmark",
        mode=mode,
        workers=workers,
        private_gets=private.gets,
        public_gets=public.gets,
        transferred_bytes=private.bytes + public.bytes,
        max_private_in_flight=private.peak,
        file_references=refs,
        unique_files=unique,
        elapsed_seconds=perf_counter() - started,
        reference_seconds=reference_seconds,
        proof_seconds=perf_counter() - tick,
        proof_verified=proof.ok,
        catalog_payload_sha256=proof.catalog_payload_sha256,
        end_to_end_slo_proven=False,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("fixture", "public"), required=True)
    parser.add_argument("--read-workers", type=int, choices=(1, 8), required=True)
    parser.add_argument("--latency-ms", type=float, default=0)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    result = measure(args.mode, args.read_workers, latency_ms=args.latency_ms)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(result.model_dump_json(by_alias=True) + "\n")
    print(result.model_dump_json(by_alias=True))
    return 0 if result.proof_verified else 1


if __name__ == "__main__":
    raise SystemExit(main())
