#!/usr/bin/env python3
"""Generate the OTA contract v1 fixtures (``docs/ota/fixtures/v1``) - M5.OTA.1.

The fixtures are the executable examples of ``docs/ota/ota-contract-v1.md``: signed
catalogs, release manifests built from the repository's committed data of two real builds
(A = 1.60.1.70374, B = 1.60.1.70450), corrupted variants of both, and ``cases.json`` - the
verdict the reference verifier (``talent_data.ota.verify``) reaches for each case, which
the Kotlin client (M5.OTA.3) must reproduce.

Deterministic: the TEST keys are derived from fixed seeds, signatures use RFC 6979
deterministic ECDSA and every timestamp is fixed, so a regeneration is byte-identical
(``tests/unit/ota/test_ota_contract.py`` checks it). The TEST keys are public by
construction - their key ids start with ``test-`` and no app keyring may ever contain them.

Offline; reads only ``data/``. Usage:

    python scripts/generate_ota_fixtures.py            # rewrite docs/ota/fixtures/v1
    python scripts/generate_ota_fixtures.py --check    # exit 1 when the committed copy drifted
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import shutil
import sys
import tempfile
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from talent_data.ota.models import (
    CATALOG_PAYLOAD_TYPE,
    CatalogRelease,
    FileRole,
    OtaReleaseManifest,
    ReleaseFile,
    ReleaseProvenance,
)
from talent_data.ota.verify import pae, sha256_ref

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
OUT = ROOT / "docs" / "ota" / "fixtures" / "v1"

GAME = "forever"
BUILD_A = "1.60.1.70374"
BUILD_B = "1.60.1.70450"
# The commit whose committed data the fixture releases describe (main after PR #41).
COMMIT = "c749ebc5fc31c43be742844cb89d012f100a2a0a"
ISSUED = datetime(2026, 10, 18, 0, 0, tzinfo=UTC)
NOW = ISSUED + timedelta(hours=12)
VALIDITY = timedelta(days=45)
VALIDATION_NOTE = "talent-data validate + manifest + visuals/tooltips/localization sync checks"

# Release paths -> repository directories. The release layout is the app's bundled layout.
ROOT_DIRS = {
    "dataset": DATA / "normalized",
    "visuals": DATA / "visuals",
    "tooltips": DATA / "tooltips",
    "localization": DATA / "localization",
}

_P256_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551


def repository_source(path: str) -> Path:
    """The committed file a release path of the fixtures was built from."""
    head, rest = path.split("/", 1)
    return ROOT_DIRS[head] / rest


# --------------------------------------------------------------------------- keys


def test_key(name: str) -> ec.EllipticCurvePrivateKey:
    seed = hashlib.sha256(f"talentforge-ota-v1-test-key:{name}".encode()).digest()
    return ec.derive_private_key(int.from_bytes(seed) % (_P256_ORDER - 1) + 1, ec.SECP256R1())


PRIMARY, BACKUP, UNTRUSTED = "test-primary", "test-backup", "test-untrusted"
KEYS = {name: test_key(name) for name in (PRIMARY, BACKUP, UNTRUSTED)}


def spki_b64(name: str) -> str:
    der = (
        KEYS[name]
        .public_key()
        .public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    )
    return base64.b64encode(der).decode("ascii")


def sign(name: str, payload_type: str, payload: bytes) -> str:
    sig = KEYS[name].sign(
        pae(payload_type, payload), ec.ECDSA(hashes.SHA256(), deterministic_signing=True)
    )
    return base64.b64encode(sig).decode("ascii")


# --------------------------------------------------------------------------- serialization


def dumps(obj: Any) -> bytes:
    return (json.dumps(obj, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def ts(value: datetime) -> str:
    return value.strftime("%Y-%m-%dT%H:%M:%SZ")


def envelope(
    payload: bytes,
    signers: list[str],
    *,
    payload_type: str = CATALOG_PAYLOAD_TYPE,
    signed_payload: bytes | None = None,
    key_ids: list[str] | None = None,
) -> bytes:
    """``signed_payload`` signs other bytes than the ones carried (tampering);
    ``key_ids`` relabels the signatures (a signature made by another key)."""
    to_sign = payload if signed_payload is None else signed_payload
    signatures = [
        {"keyId": key_id, "sig": sign(signer, payload_type, to_sign)}
        for signer, key_id in zip(signers, key_ids or signers, strict=True)
    ]
    return dumps(
        {
            "payloadType": payload_type,
            "payload": base64.b64encode(payload).decode("ascii"),
            "signatures": signatures,
        }
    )


# --------------------------------------------------------------------------- releases


def _role(path: str) -> FileRole:
    head, rest = path.split("/", 1)
    name = rest.split("/", 2)[2]
    if head == "dataset":
        return FileRole.DATASET_MANIFEST if name == "manifest.json" else FileRole.TALENT_DATA
    if head == "visuals":
        if name == "icons.json":
            return FileRole.ICON_REGISTRY
        return FileRole.ICON_IMAGE if name.startswith("icons/") else FileRole.VISUALS
    return FileRole.TOOLTIPS if head == "tooltips" else FileRole.LOCALIZATION


def snapshot(build: str) -> dict[str, bytes]:
    """Release path -> bytes of every committed file of ``build``."""
    files: dict[str, bytes] = {}
    for head, base in ROOT_DIRS.items():
        build_dir = base / GAME / build
        for source in sorted(p for p in build_dir.rglob("*") if p.is_file()):
            relative = source.relative_to(build_dir).as_posix()
            files[f"{head}/{GAME}/{build}/{relative}"] = source.read_bytes()
    return files


def release_files(contents: dict[str, bytes]) -> list[ReleaseFile]:
    return [
        ReleaseFile(path=p, role=_role(p), sha256=sha256_ref(d), byte_length=len(d))
        for p, d in sorted(contents.items(), key=lambda item: item[0].encode("utf-8"))
    ]


def release_manifest(
    build: str, revision: int, files: list[ReleaseFile], *, min_level: int = 1
) -> OtaReleaseManifest:
    return OtaReleaseManifest(
        schema_version=1,
        game=GAME,
        client_build=build,
        release_id=f"{build}-r{revision}",
        min_data_level=min_level,
        created_at=ISSUED - timedelta(hours=1),
        provenance=ReleaseProvenance(repository_commit=COMMIT, validation=VALIDATION_NOTE),
        files=files,
        total_byte_length=sum(f.byte_length for f in files),
    )


def manifest_bytes(manifest: OtaReleaseManifest | dict[str, Any]) -> bytes:
    """Indented JSON with one line per file entry. Any byte form is valid on the wire (the
    catalog pins the bytes, nothing is canonicalized); this one keeps diffs readable."""
    doc = (
        manifest.model_dump(mode="json", by_alias=True)
        if isinstance(manifest, OtaReleaseManifest)
        else manifest
    )
    members = []
    for key, value in doc.items():
        if key == "files" and isinstance(value, list) and value:
            rows = ",\n".join(f"    {json.dumps(f, ensure_ascii=False)}" for f in value)
            members.append(f'  "files": [\n{rows}\n  ]')
        else:
            text = json.dumps(value, indent=2, ensure_ascii=False).replace("\n", "\n  ")
            members.append(f"  {json.dumps(key)}: {text}")
    return ("{\n" + ",\n".join(members) + "\n}\n").encode("utf-8")


def entry_for(
    data: bytes, release_id: str, client_build: str, min_level: int, total: int
) -> CatalogRelease:
    return CatalogRelease(
        release_id=release_id,
        client_build=client_build,
        min_data_level=min_level,
        manifest_sha256=sha256_ref(data),
        manifest_byte_length=len(data),
        total_byte_length=total,
        published_at=ISSUED - timedelta(minutes=30),
    )


def entry_json(entry: CatalogRelease) -> dict[str, Any]:
    return entry.model_dump(mode="json", by_alias=True)


# --------------------------------------------------------------------------- generation


class Writer:
    def __init__(self, out: Path) -> None:
        self.out = out
        self.cases: list[dict[str, Any]] = []

    def file(self, relative: str, data: bytes) -> str:
        path = self.out / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return relative

    def case(self, case_id: str, description: str, **fields: Any) -> None:
        self.cases.append({"id": case_id, "description": description, **fields})


def catalog_context(
    *,
    last_seen: dict[str, Any] | None = None,
    bundled: str = BUILD_A,
    current: str | None = None,
    channel: str = "production",
    now: datetime = NOW,
) -> dict[str, Any]:
    return {
        "game": GAME,
        "channel": channel,
        "now": ts(now),
        "lastSeen": last_seen,
        "supportedDataLevel": 1,
        "bundledProductBuild": bundled,
        "currentReleaseId": current,
    }


def accept(decision: str, release_id: str) -> dict[str, Any]:
    return {"result": "ACCEPT", "decision": decision, "releaseId": release_id}


def reject(code: str) -> dict[str, Any]:
    return {"result": "REJECT", "code": code}


def generate(out: Path) -> None:
    if out.exists():
        shutil.rmtree(out)
    w = Writer(out)
    _keyring(w)
    contents_a, contents_b = snapshot(BUILD_A), snapshot(BUILD_B)

    # ---- the real releases A and B, and B-r2 that needs data level 2
    rel_a = release_manifest(BUILD_A, 1, release_files(contents_a))
    rel_b = release_manifest(BUILD_B, 1, release_files(contents_b))
    rel_b2 = release_manifest(BUILD_B, 2, release_files(contents_b), min_level=2)
    releases: dict[str, tuple[str, bytes, CatalogRelease]] = {}
    for rel in (rel_a, rel_b, rel_b2):
        data = manifest_bytes(rel)
        name = w.file(f"releases/{rel.release_id}.release.json", data)
        entry = entry_for(
            data, rel.release_id, rel.client_build, rel.min_data_level, rel.total_byte_length
        )
        releases[rel.release_id] = (name, data, entry)

    _catalog_cases(w, releases, rel_a.release_id, rel_b.release_id, rel_b2.release_id)
    _release_cases(w, releases, rel_a, rel_b, rel_b2, contents_a, contents_b)
    w.file(
        "cases.json",
        dumps(
            {
                "schemaVersion": 1,
                "kind": "talentforge-ota-fixtures",
                "specification": "docs/ota/ota-contract-v1.md",
                "generator": "scripts/generate_ota_fixtures.py",
                "cases": w.cases,
            }
        ),
    )


def _keyring(w: Writer) -> None:
    def key(name: str) -> dict[str, str]:
        return {"keyId": name, "algorithm": "ecdsa-p256-sha256", "publicKeySpki": spki_b64(name)}

    w.file(
        "keyring.json",
        dumps(
            {
                "comment": "TEST keys only (derived from public seeds in "
                "scripts/generate_ota_fixtures.py). Never pin a test- key in an app.",
                "keys": [key(PRIMARY), key(BACKUP)],
                "untrustedKeys": [key(UNTRUSTED)],
            }
        ),
    )


def _catalog_cases(
    w: Writer,
    releases: dict[str, tuple[str, bytes, CatalogRelease]],
    id_a: str,
    id_b: str,
    id_b2: str,
) -> None:
    def catalog_payload(
        sequence: int,
        active: str,
        listed: list[str],
        *,
        mutate: Callable[[dict[str, Any]], None] | None = None,
        **overrides: Any,
    ) -> bytes:
        payload: dict[str, Any] = {
            "schemaVersion": 1,
            "game": GAME,
            "channel": "production",
            "sequence": sequence,
            "issuedAt": ts(ISSUED),
            "expiresAt": ts(ISSUED + VALIDITY),
            "activeReleaseId": active,
            "releases": [entry_json(releases[r][2]) for r in listed],
        }
        payload.update(overrides)
        if mutate is not None:
            mutate(payload)
        return dumps(payload)

    def case(
        case_id: str,
        description: str,
        envelope_bytes: bytes,
        context: dict[str, Any],
        expect: dict[str, Any],
    ) -> None:
        name = w.file(f"catalogs/{case_id}.json", envelope_bytes)
        w.case(case_id, description, kind="catalog", file=name, context=context, expect=expect)

    def signed(
        case_id: str,
        description: str,
        payload: bytes,
        expect: dict[str, Any],
        context: dict[str, Any] | None = None,
    ) -> None:
        case(
            case_id, description, envelope(payload, [PRIMARY]), context or catalog_context(), expect
        )

    p1 = catalog_payload(1, id_a, [id_a])
    p2 = catalog_payload(2, id_b, [id_a, id_b])
    p3 = catalog_payload(3, id_a, [id_a, id_b])
    p4 = catalog_payload(4, id_b2, [id_a, id_b, id_b2])
    seen1 = {"sequence": 1, "payloadSha256": sha256_ref(p1)}
    seen2 = {"sequence": 2, "payloadSha256": sha256_ref(p2)}

    # ---- accepted catalogs
    case(
        "catalog-a-first-publication",
        "First catalog: A active. A device on its bundled product A installs release A-r1 "
        "(files equal to bundled ones are copied from the bundle, not downloaded).",
        envelope(p1, [PRIMARY]),
        catalog_context(),
        accept("ACTIVATE", id_a),
    )
    case(
        "catalog-b-active",
        "A -> B without an app update: B becomes active.",
        envelope(p2, [PRIMARY]),
        catalog_context(last_seen=seen1, current=id_a),
        accept("ACTIVATE", id_b),
    )
    case(
        "catalog-b-active-refetched",
        "The same catalog fetched again while B is active: nothing is downloaded again.",
        envelope(p2, [PRIMARY]),
        catalog_context(last_seen=seen2, current=id_b),
        accept("NO_CHANGE", id_b),
    )
    case(
        "catalog-b-active-backup-key",
        "Signed by the backup key only (primary key lost): accepted.",
        envelope(p2, [BACKUP]),
        catalog_context(current=id_a),
        accept("ACTIVATE", id_b),
    )
    case(
        "catalog-b-active-extra-unknown-signer",
        "An additional signature by a key the app does not pin is ignored.",
        envelope(p2, [UNTRUSTED, PRIMARY]),
        catalog_context(current=id_a),
        accept("ACTIVATE", id_b),
    )
    case(
        "catalog-rollback-to-a",
        "Rollback: a newer catalog (sequence 3) makes A active again; B stays listed.",
        envelope(p3, [PRIMARY]),
        catalog_context(last_seen=seen2, current=id_b),
        accept("ACTIVATE", id_a),
    )
    case(
        "catalog-rollback-below-bundled-floor",
        "The rollback to A on an app whose bundled product is B: the bundled product is the "
        "floor, the device keeps what it has.",
        envelope(p3, [PRIMARY]),
        catalog_context(last_seen=seen2, bundled=BUILD_B),
        accept("KEEP_BUNDLED_FLOOR", id_a),
    )
    case(
        "catalog-active-needs-newer-app",
        "The active release needs data level 2 (a new mechanic): an app at level 1 keeps "
        "its current dataset and downloads nothing.",
        envelope(p4, [PRIMARY]),
        catalog_context(last_seen=seen2, current=id_b),
        accept("KEEP_CURRENT_INCOMPATIBLE", id_b2),
    )

    # ---- rejected envelopes
    case(
        "catalog-tampered-payload",
        "Payload changed after signing.",
        envelope(p2.replace(b'"sequence": 2', b'"sequence": 9'), [PRIMARY], signed_payload=p2),
        catalog_context(),
        reject("SIGNATURE_INVALID"),
    )
    case(
        "catalog-signature-by-wrong-key",
        "Labelled with the primary key id but made by another key.",
        envelope(p2, [UNTRUSTED], key_ids=[PRIMARY]),
        catalog_context(),
        reject("SIGNATURE_INVALID"),
    )
    case(
        "catalog-untrusted-signer",
        "Signed only by a key the app does not pin.",
        envelope(p2, [UNTRUSTED]),
        catalog_context(),
        reject("UNTRUSTED_SIGNER"),
    )
    case(
        "catalog-wrong-payload-type",
        "A correctly signed document of another type is not a catalog.",
        envelope(p2, [PRIMARY], payload_type="application/vnd.talentforge.ota-release.v1+json"),
        catalog_context(),
        reject("PAYLOAD_TYPE_MISMATCH"),
    )
    good = envelope(p2, [PRIMARY])
    valid = json.loads(good)
    wrapped = valid["payload"][:40] + "\n" + valid["payload"][40:]
    case(
        "catalog-envelope-unknown-field",
        "Unknown envelope field.",
        dumps({**valid, "cdnHint": "x"}),
        catalog_context(),
        reject("ENVELOPE_INVALID"),
    )
    case(
        "catalog-envelope-noncanonical-base64",
        "The payload base64 is line-wrapped; only canonical base64 is accepted.",
        dumps({**valid, "payload": wrapped}),
        catalog_context(),
        reject("ENVELOPE_INVALID"),
    )
    case(
        "catalog-envelope-duplicate-key-id",
        "Two signatures with the same key id.",
        dumps({**valid, "signatures": valid["signatures"] * 2}),
        catalog_context(),
        reject("ENVELOPE_INVALID"),
    )
    case(
        "catalog-truncated-download",
        "A partially downloaded catalog.json.",
        good[: len(good) // 2],
        catalog_context(),
        reject("ENVELOPE_INVALID"),
    )

    # ---- rejected payloads (correctly signed)
    signed(
        "catalog-unknown-field",
        "Signed payload with an unknown field: rejected, never ignored.",
        catalog_payload(2, id_b, [id_a, id_b], mirrors=["https://example.invalid"]),
        reject("PAYLOAD_INVALID"),
    )
    signed(
        "catalog-quoted-number",
        "sequence as a JSON string.",
        catalog_payload(2, id_b, [id_a, id_b], mutate=lambda p: p.update(sequence="2")),
        reject("PAYLOAD_INVALID"),
    )
    signed(
        "catalog-duplicate-json-key",
        "A JSON object with the activeReleaseId key twice.",
        p2.replace(
            b'  "activeReleaseId"',
            b'  "activeReleaseId": "' + id_a.encode() + b'",\n  "activeReleaseId"',
            1,
        ),
        reject("PAYLOAD_INVALID"),
    )
    signed(
        "catalog-unknown-channel",
        "A channel value outside the closed set.",
        catalog_payload(2, id_b, [id_a, id_b], channel="beta"),
        reject("PAYLOAD_INVALID"),
    )
    signed(
        "catalog-naive-timestamp",
        "A timestamp without a UTC designator.",
        catalog_payload(2, id_b, [id_a, id_b], expiresAt="2026-12-02T00:00:00"),
        reject("PAYLOAD_INVALID"),
    )
    signed(
        "catalog-byte-order-mark",
        "Payload bytes start with a UTF-8 BOM.",
        b"\xef\xbb\xbf" + p2,
        reject("PAYLOAD_INVALID"),
    )
    signed(
        "catalog-schema-version-2",
        "A future catalog schema.",
        catalog_payload(2, id_b, [id_a, id_b], schemaVersion=2),
        reject("UNSUPPORTED_SCHEMA_VERSION"),
    )
    signed(
        "catalog-other-game",
        "A catalog of another game.",
        catalog_payload(2, id_b, [id_a, id_b], game="classic"),
        reject("GAME_MISMATCH"),
    )
    signed(
        "catalog-staging-on-production",
        "A staging catalog served at the production URL.",
        catalog_payload(2, id_b, [id_a, id_b], channel="staging"),
        reject("CHANNEL_MISMATCH"),
    )
    signed(
        "catalog-expired",
        "Read after its expiry (freeze attack, or a stale CDN copy).",
        p2,
        reject("CATALOG_EXPIRED"),
        catalog_context(now=ISSUED + VALIDITY),
    )
    signed(
        "catalog-sequence-regression",
        "An older catalog (sequence 1) after sequence 2 was accepted (replay).",
        p1,
        reject("SEQUENCE_REGRESSION"),
        catalog_context(last_seen=seen2),
    )
    signed(
        "catalog-sequence-reused",
        "Sequence 2 again, with different content.",
        catalog_payload(2, id_a, [id_a, id_b]),
        reject("SEQUENCE_REGRESSION"),
        catalog_context(last_seen=seen2),
    )
    signed(
        "catalog-active-not-listed",
        "activeReleaseId names a release the catalog does not list.",
        catalog_payload(2, id_b, [id_a]),
        reject("CATALOG_INCONSISTENT"),
    )
    signed(
        "catalog-duplicate-release",
        "The same release listed twice.",
        catalog_payload(2, id_b, [id_a, id_b, id_b]),
        reject("CATALOG_INCONSISTENT"),
    )
    signed(
        "catalog-unordered-releases",
        "Releases not ordered by (clientBuild, revision).",
        catalog_payload(2, id_b, [id_b, id_a]),
        reject("CATALOG_INCONSISTENT"),
    )

    def mismatched_build(payload: dict[str, Any]) -> None:
        payload["releases"][1]["clientBuild"] = BUILD_A

    signed(
        "catalog-release-id-build-mismatch",
        "A releaseId that does not name its clientBuild.",
        catalog_payload(2, id_b, [id_a, id_b], mutate=mismatched_build),
        reject("CATALOG_INCONSISTENT"),
    )
    signed(
        "catalog-validity-too-long",
        "expiresAt more than 180 days after issuedAt.",
        catalog_payload(2, id_b, [id_a, id_b], expiresAt=ts(ISSUED + timedelta(days=181))),
        reject("CATALOG_INCONSISTENT"),
    )


def _release_cases(
    w: Writer,
    releases: dict[str, tuple[str, bytes, CatalogRelease]],
    rel_a: OtaReleaseManifest,
    rel_b: OtaReleaseManifest,
    rel_b2: OtaReleaseManifest,
    contents_a: dict[str, bytes],
    contents_b: dict[str, bytes],
) -> None:
    def case(
        case_id: str,
        description: str,
        file: str,
        entry: CatalogRelease,
        expect: dict[str, Any],
        *,
        overrides: dict[str, str] | None = None,
        known_same_build: bool = False,
    ) -> None:
        w.case(
            case_id,
            description,
            kind="release",
            file=file,
            catalogEntry=entry_json(entry),
            context={
                "game": GAME,
                "supportedDataLevel": 1,
                "knownSameBuildManifest": "repository" if known_same_build else None,
            },
            files={"source": "repository", "overrides": overrides or {}},
            expect=expect,
        )

    ok = {"result": "ACCEPT"}
    name_a, _, entry_a = releases[rel_a.release_id]
    name_b, data_b, entry_b = releases[rel_b.release_id]
    name_b2, _, entry_b2 = releases[rel_b2.release_id]
    case(
        "release-a-valid",
        "Release A: every file is the committed 70374 data.",
        name_a,
        entry_a,
        ok,
        known_same_build=True,
    )
    case(
        "release-b-valid",
        "Release B: every file is the committed 70450 data.",
        name_b,
        entry_b,
        ok,
        known_same_build=True,
    )
    case(
        "release-needs-data-level-2",
        "A release for a newer app (new mechanic), offered to an app at data level 1.",
        name_b2,
        entry_b2,
        reject("INCOMPATIBLE_DATA_LEVEL"),
    )
    case(
        "release-manifest-hash-mismatch",
        "The catalog pins other bytes (a manifest replaced on the server).",
        name_b,
        entry_b.model_copy(update={"manifest_sha256": entry_a.manifest_sha256}),
        reject("MANIFEST_HASH_MISMATCH"),
    )
    truncated = w.file("releases/invalid/truncated.release.json", data_b[: len(data_b) // 3])
    case(
        "release-manifest-truncated",
        "A partially downloaded release manifest.",
        truncated,
        entry_b,
        reject("MANIFEST_HASH_MISMATCH"),
    )

    # Manifest-level failures use a small manifest: one file of each role of B.
    def small_files() -> list[dict[str, Any]]:
        picked: dict[FileRole, ReleaseFile] = {}
        for f in rel_b.files:
            picked.setdefault(f.role, f)
        chosen = sorted(picked.values(), key=lambda f: f.path.encode("utf-8"))
        return [f.model_dump(mode="json", by_alias=True) for f in chosen]

    def resort(doc: dict[str, Any]) -> None:
        doc["files"].sort(key=lambda f: f["path"].encode("utf-8"))
        doc["totalByteLength"] = sum(f["byteLength"] for f in doc["files"])

    def invalid_manifest(
        case_id: str,
        description: str,
        code: str,
        mutate: Callable[[dict[str, Any]], object],
        *,
        entry_update: dict[str, Any] | None = None,
    ) -> None:
        doc = rel_b.model_dump(mode="json", by_alias=True)
        doc["files"] = small_files()
        doc["totalByteLength"] = sum(f["byteLength"] for f in doc["files"])
        mutate(doc)
        data = manifest_bytes(doc)
        name = w.file(f"releases/invalid/{case_id}.release.json", data)
        total = doc["totalByteLength"]
        entry = entry_for(data, rel_b.release_id, BUILD_B, 1, total)
        if entry_update:
            entry = entry.model_copy(update=entry_update)
        case(case_id, description, name, entry, reject(code))

    def set_talent_path(path: str) -> Callable[[dict[str, Any]], None]:
        def mutate(doc: dict[str, Any]) -> None:
            for f in doc["files"]:
                if f["role"] == "TALENT_DATA":
                    f["path"] = path
            resort(doc)

        return mutate

    def duplicate_first(doc: dict[str, Any]) -> None:
        doc["files"].insert(0, dict(doc["files"][0]))
        resort(doc)

    def drop_dataset_manifest(doc: dict[str, Any]) -> None:
        doc["files"] = [f for f in doc["files"] if f["role"] != "DATASET_MANIFEST"]
        resort(doc)

    invalid_manifest(
        "release-schema-version-2",
        "A future release manifest schema.",
        "UNSUPPORTED_SCHEMA_VERSION",
        lambda d: d.update(schemaVersion=2),
    )
    invalid_manifest(
        "release-unknown-field",
        "Unknown release manifest field.",
        "MANIFEST_INVALID",
        lambda d: d.update(postInstall="x"),
    )
    invalid_manifest(
        "release-unknown-role",
        "A file role outside the closed set (there is no executable role).",
        "MANIFEST_INVALID",
        lambda d: d["files"][0].update(role="SCRIPT"),
    )
    invalid_manifest(
        "release-identity-mismatch",
        "The manifest is B-r1; the catalog entry pins its bytes as B-r2.",
        "RELEASE_IDENTITY_MISMATCH",
        lambda d: None,
        entry_update={"release_id": f"{BUILD_B}-r2"},
    )
    invalid_manifest(
        "release-path-traversal",
        "A talent data path that leaves the release root.",
        "LAYOUT_INVALID",
        set_talent_path(f"dataset/{GAME}/{BUILD_B}/../../../../evil.json"),
    )
    invalid_manifest(
        "release-path-of-other-build",
        "A file placed under another build's directory.",
        "LAYOUT_INVALID",
        set_talent_path(f"dataset/{GAME}/{BUILD_A}/mage.json"),
    )
    invalid_manifest(
        "release-role-path-mismatch",
        "A talent data file at an icon path.",
        "LAYOUT_INVALID",
        set_talent_path(f"visuals/{GAME}/{BUILD_B}/icons/1.png"),
    )
    invalid_manifest(
        "release-duplicate-path",
        "The same path listed twice.",
        "LAYOUT_INVALID",
        duplicate_first,
    )
    invalid_manifest(
        "release-unordered-files",
        "Files not ordered by path.",
        "LAYOUT_INVALID",
        lambda d: d["files"].reverse(),
    )
    invalid_manifest(
        "release-total-mismatch",
        "totalByteLength is not the sum of the file sizes.",
        "LAYOUT_INVALID",
        lambda d: d.update(totalByteLength=d["totalByteLength"] + 1),
    )
    invalid_manifest(
        "release-missing-dataset-manifest",
        "No DATASET_MANIFEST file.",
        "LAYOUT_INVALID",
        drop_dataset_manifest,
    )

    # File-level failures: B's own manifest with a corrupt download...
    png = f"visuals/{GAME}/{BUILD_B}/icons/132089.png"
    flipped = bytearray(contents_b[png])
    flipped[-20] ^= 0x01
    case(
        "release-blob-hash-mismatch",
        "An icon whose bytes differ from the manifest's SHA-256.",
        name_b,
        entry_b,
        reject("BLOB_HASH_MISMATCH"),
        overrides={png: w.file("blobs/icon-132089-bitflip.png", bytes(flipped))},
    )
    case(
        "release-blob-truncated",
        "A partially downloaded icon.",
        name_b,
        entry_b,
        reject("BLOB_SIZE_MISMATCH"),
        overrides={png: w.file("blobs/icon-132089-truncated.png", contents_b[png][:1000])},
    )

    # ... and full manifests that pin bad content.
    def full_variant(
        case_id: str,
        description: str,
        code: str,
        replaced: dict[str, bytes],
        *,
        drop: tuple[str, ...] = (),
        revision: int = 1,
        known_same_build: bool = False,
    ) -> None:
        contents = {p: d for p, d in {**contents_b, **replaced}.items() if p not in drop}
        rel = release_manifest(BUILD_B, revision, release_files(contents))
        data = manifest_bytes(rel)
        name = w.file(f"releases/invalid/{case_id}.release.json", data)
        overrides: dict[str, str] = {}
        for path, content in replaced.items():
            source = next((p for p, d in contents_a.items() if d == content), None)
            if source is not None:
                overrides[path] = f"repository:{source}"
            else:
                blob = f"blobs/{case_id}--{path.rsplit('/', 1)[1]}"
                overrides[path] = w.file(blob, content)
        entry = entry_for(data, rel.release_id, BUILD_B, 1, rel.total_byte_length)
        case(
            case_id,
            description,
            name,
            entry,
            reject(code),
            overrides=overrides,
            known_same_build=known_same_build,
        )

    paladin = f"dataset/{GAME}/{BUILD_B}/paladin.json"
    full_variant(
        "release-new-mechanic",
        "Paladin talent data with a prerequisite kind outside the closed talent-data.v1 set: "
        "the strict reader rejects it and nothing of the release is used.",
        "CONTENT_INVALID",
        {paladin: contents_b[paladin].replace(b'"kind": "sufficient"', b'"kind": "exclusive"', 1)},
    )
    # talent-data.v1 keeps node types an open union (raw values are preserved and the
    # allocation engine reports such nodes as unsupported), so this release is VALID on the
    # wire: an unknown node type is caught by the publisher gate and minDataLevel, not here.
    contents = {
        **contents_b,
        paladin: contents_b[paladin].replace(b'"type": "single"', b'"type": "hybrid"', 1),
    }
    rel = release_manifest(BUILD_B, 3, release_files(contents))
    data = manifest_bytes(rel)
    case(
        "release-open-union-node-type",
        "A node type the app does not know is preserved, not rejected (open union of "
        "talent-data.v1); the engine treats the node as unsupported. Protection against new "
        "mechanics is the publisher gate plus minDataLevel, see the contract.",
        w.file("releases/edge/release-open-union-node-type.release.json", data),
        entry_for(data, rel.release_id, BUILD_B, 1, rel.total_byte_length),
        ok,
        overrides={
            paladin: w.file("blobs/release-open-union-node-type--paladin.json", contents[paladin])
        },
    )
    full_variant(
        "release-mixed-builds",
        "B's Mage tooltips replaced by A's (valid JSON, wrong client build).",
        "CONTENT_IDENTITY_MISMATCH",
        {
            f"tooltips/{GAME}/{BUILD_B}/mage.json": contents_a[
                f"tooltips/{GAME}/{BUILD_A}/mage.json"
            ]
        },
    )
    full_variant(
        "release-missing-class",
        "The Warrior talent data file is missing although the dataset manifest lists it.",
        "RELEASE_INCOMPLETE",
        {},
        drop=(f"dataset/{GAME}/{BUILD_B}/warrior.json",),
    )
    manifest_path = f"dataset/{GAME}/{BUILD_B}/manifest.json"
    old_hash = json.loads(contents_b[paladin])["contentHash"].encode()
    new_hash = sha256_ref(b"talentforge-ota-fixture: different talent data").encode()
    full_variant(
        "release-same-build-conflict",
        "B-r2 claims different Paladin talent data for the same client build (consistent "
        "within itself): rejected against the dataset the device already trusts for B.",
        "BUILD_IDENTITY_CONFLICT",
        {
            paladin: contents_b[paladin].replace(old_hash, new_hash),
            manifest_path: contents_b[manifest_path].replace(old_hash, new_hash),
        },
        revision=2,
        known_same_build=True,
    )


# --------------------------------------------------------------------------- CLI


def drifted(fresh: Path, committed: Path) -> list[str]:
    def listing(base: Path) -> dict[str, bytes]:
        if not base.exists():
            return {}
        return {
            p.relative_to(base).as_posix(): p.read_bytes() for p in base.rglob("*") if p.is_file()
        }

    a, b = listing(fresh), listing(committed)
    return [
        f"{name}: {'missing' if name not in b else 'unexpected' if name not in a else 'differs'}"
        for name in sorted(a.keys() | b.keys())
        if a.get(name) != b.get(name)
    ]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="compare with the committed copy")
    args = parser.parse_args(argv)
    if not args.check:
        generate(OUT)
        print(f"wrote {OUT.relative_to(ROOT)}")
        return 0
    with tempfile.TemporaryDirectory() as tmp:
        fresh = Path(tmp) / "v1"
        generate(fresh)
        drift = drifted(fresh, OUT)
    for line in drift:
        print(f"drift: {line}", file=sys.stderr)
    return 1 if drift else 0


if __name__ == "__main__":
    raise SystemExit(main())
