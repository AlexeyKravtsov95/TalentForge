"""Reference verifier of the OTA contract v1 (``docs/ota/ota-contract-v1.md``).

This is the executable form of the specification's verification algorithm, in the same role
the Python allocation engine plays for allocation: the committed fixtures
(``docs/ota/fixtures/v1``) record its verdicts, and the Kotlin client (M5.OTA.3) must reach
the same verdict - accept, or reject with the same :class:`OtaErrorCode` - for every case.

It performs no I/O: callers hand in bytes and a ``read_file`` callback. Every rejection is
an :class:`OtaError` carrying a closed code; the message is for humans only.

Order matters and is part of the contract - the cheapest, most fundamental check runs first,
so a corrupt or hostile object is never parsed further than needed:

1. catalog: envelope shape -> payload type -> signature -> payload JSON/schema -> identity
   (game, channel) -> freshness (expiry, sequence) -> internal consistency;
2. release manifest: bytes against the catalog pin -> JSON/schema -> identity against the
   catalog entry -> data level -> layout (paths, roles, sizes);
3. files: size and SHA-256 against the manifest -> strict content parsing -> identity and
   completeness across the snapshot's documents.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import json
import re
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import StrEnum
from typing import Any

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_der_public_key
from pydantic import BaseModel, ValidationError

from talent_data.models.localization import TalentLocalizationDocument
from talent_data.models.manifest import DatasetManifest
from talent_data.models.talents import TalentDataDocument
from talent_data.models.tooltips import TalentTooltipsDocument
from talent_data.models.visuals import TalentIconRegistry, TalentVisualsDocument
from talent_data.ota.models import (
    CATALOG_PAYLOAD_TYPE,
    OTA_SCHEMA_VERSION,
    CatalogRelease,
    Channel,
    FileRole,
    OtaCatalog,
    OtaReleaseManifest,
    ReleaseFile,
    SignedEnvelope,
)
from talent_data.versions import ClientVersion

# The data level this reference implementation (and the app at M5.OTA.3) understands:
# level 1 = the contracts listed in docs/ota/ota-contract-v1.md (section "Data levels").
SUPPORTED_DATA_LEVEL = 1

MAX_CATALOG_VALIDITY = timedelta(days=180)
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class OtaErrorCode(StrEnum):
    # catalog (signed envelope + payload)
    ENVELOPE_INVALID = "ENVELOPE_INVALID"
    PAYLOAD_TYPE_MISMATCH = "PAYLOAD_TYPE_MISMATCH"
    UNTRUSTED_SIGNER = "UNTRUSTED_SIGNER"
    SIGNATURE_INVALID = "SIGNATURE_INVALID"
    PAYLOAD_INVALID = "PAYLOAD_INVALID"
    UNSUPPORTED_SCHEMA_VERSION = "UNSUPPORTED_SCHEMA_VERSION"
    GAME_MISMATCH = "GAME_MISMATCH"
    CHANNEL_MISMATCH = "CHANNEL_MISMATCH"
    CATALOG_EXPIRED = "CATALOG_EXPIRED"
    SEQUENCE_REGRESSION = "SEQUENCE_REGRESSION"
    CATALOG_INCONSISTENT = "CATALOG_INCONSISTENT"
    # release manifest
    MANIFEST_HASH_MISMATCH = "MANIFEST_HASH_MISMATCH"
    MANIFEST_INVALID = "MANIFEST_INVALID"
    RELEASE_IDENTITY_MISMATCH = "RELEASE_IDENTITY_MISMATCH"
    INCOMPATIBLE_DATA_LEVEL = "INCOMPATIBLE_DATA_LEVEL"
    LAYOUT_INVALID = "LAYOUT_INVALID"
    # release files
    BLOB_SIZE_MISMATCH = "BLOB_SIZE_MISMATCH"
    BLOB_HASH_MISMATCH = "BLOB_HASH_MISMATCH"
    CONTENT_INVALID = "CONTENT_INVALID"
    CONTENT_IDENTITY_MISMATCH = "CONTENT_IDENTITY_MISMATCH"
    RELEASE_INCOMPLETE = "RELEASE_INCOMPLETE"
    BUILD_IDENTITY_CONFLICT = "BUILD_IDENTITY_CONFLICT"


class OtaError(Exception):
    def __init__(self, code: OtaErrorCode, detail: str) -> None:
        super().__init__(f"{code.value}: {detail}")
        self.code = code
        self.detail = detail


# --------------------------------------------------------------------------- primitives


def sha256_ref(data: bytes) -> str:
    """``sha256:<hex>`` - the form every OTA document uses (and the blob address)."""
    return "sha256:" + hashlib.sha256(data).hexdigest()


def pae(payload_type: str, payload: bytes) -> bytes:
    """DSSE v1 pre-authentication encoding - the exact message that is signed."""
    type_bytes = payload_type.encode("utf-8")
    return b"DSSEv1 %d %s %d %s" % (len(type_bytes), type_bytes, len(payload), payload)


def strict_b64decode(text: str) -> bytes:
    """Standard padded base64; any other spelling of the same bytes is rejected, so one
    payload has exactly one envelope encoding."""
    try:
        raw = base64.b64decode(text, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError(f"not base64: {exc}") from exc
    if base64.b64encode(raw).decode("ascii") != text:
        raise ValueError("non-canonical base64")
    return raw


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    keys = [key for key, _ in pairs]
    if len(keys) != len(set(keys)):
        raise ValueError(f"duplicate JSON object key in {sorted(keys)}")
    return dict(pairs)


def _reject_constant(name: str) -> Any:
    raise ValueError(f"non-standard JSON constant {name}")


def strict_json(data: bytes) -> Any:
    """UTF-8 (no BOM) JSON with unique object keys and no NaN/Infinity."""
    if data.startswith(b"\xef\xbb\xbf"):
        raise ValueError("UTF-8 byte order mark")
    text = data.decode("utf-8")
    return json.loads(text, object_pairs_hook=_reject_duplicates, parse_constant=_reject_constant)


def _unknown_keys(raw: Any, dumped: Any, where: str = "$") -> list[str]:
    """Keys present on the wire but absent from the model's own serialization - catches
    unknown fields even for models that do not forbid extras themselves."""
    if isinstance(raw, dict) and isinstance(dumped, dict):
        found = [f"{where}.{key}" for key in raw if key not in dumped]
        for key in sorted(raw.keys() & dumped.keys()):
            found += _unknown_keys(raw[key], dumped[key], f"{where}.{key}")
        return found
    if isinstance(raw, list) and isinstance(dumped, list):
        return [
            k
            for i, (r, d) in enumerate(zip(raw, dumped, strict=False))
            for k in _unknown_keys(r, d, f"{where}[{i}]")
        ]
    return []


def parse_strict[M: BaseModel](model: type[M], data: bytes) -> M:
    """Parse ``data`` as ``model`` the way the client must: strict JSON, strict wire types,
    no unknown fields. Raises ``ValueError`` (``ValidationError`` included)."""
    raw = strict_json(data)
    parsed = model.model_validate_json(data, strict=True)
    unknown = _unknown_keys(raw, parsed.model_dump(mode="json", by_alias=True))
    if unknown:
        raise ValueError(f"unknown fields {unknown}")
    return parsed


def _require_utc(value: datetime, name: str) -> None:
    if value.tzinfo is None or value.utcoffset() != timedelta(0):
        raise ValueError(f"{name} must be a UTC timestamp (...Z), got {value.isoformat()}")


def release_sort_key(release_id: str) -> tuple[ClientVersion, int]:
    build, revision = release_id.rsplit("-r", 1)
    return ClientVersion.parse(build), int(revision)


# --------------------------------------------------------------------------- keyring


@dataclass(frozen=True)
class TrustedKey:
    key_id: str
    public_key: ec.EllipticCurvePublicKey


def load_keyring(entries: Sequence[Mapping[str, str]]) -> dict[str, TrustedKey]:
    """``[{"keyId", "algorithm": "ecdsa-p256-sha256", "publicKeySpki": base64 DER}]``."""
    keyring: dict[str, TrustedKey] = {}
    for entry in entries:
        if entry["algorithm"] != "ecdsa-p256-sha256":
            raise ValueError(f"unsupported key algorithm {entry['algorithm']!r}")
        key = load_der_public_key(strict_b64decode(entry["publicKeySpki"]))
        if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(
            key.curve, ec.SECP256R1
        ):
            raise TypeError(f"{entry['keyId']}: not a P-256 public key")
        if entry["keyId"] in keyring:
            raise ValueError(f"duplicate keyId {entry['keyId']}")
        keyring[entry["keyId"]] = TrustedKey(entry["keyId"], key)
    return keyring


# --------------------------------------------------------------------------- catalog


@dataclass(frozen=True)
class SeenCatalog:
    """What the client persisted about the newest catalog it accepted."""

    sequence: int
    payload_sha256: str


@dataclass(frozen=True)
class VerifiedCatalog:
    catalog: OtaCatalog
    payload_sha256: str

    def release(self, release_id: str) -> CatalogRelease:
        return next(r for r in self.catalog.releases if r.release_id == release_id)


def verify_catalog(
    envelope_bytes: bytes,
    *,
    keyring: Mapping[str, TrustedKey],
    game: str,
    channel: Channel,
    now: datetime,
    last_seen: SeenCatalog | None,
) -> VerifiedCatalog:
    """Verify one downloaded ``catalog.json``. A rejected catalog changes nothing on the
    device: the caller keeps its current state and tries again later."""
    try:
        envelope = parse_strict(SignedEnvelope, envelope_bytes)
        payload = strict_b64decode(envelope.payload)
        for signature in envelope.signatures:
            strict_b64decode(signature.sig)
    except ValueError as exc:
        raise OtaError(OtaErrorCode.ENVELOPE_INVALID, str(exc)) from exc
    key_ids = [s.key_id for s in envelope.signatures]
    if len(key_ids) != len(set(key_ids)):
        raise OtaError(OtaErrorCode.ENVELOPE_INVALID, f"duplicate keyId in {key_ids}")
    if envelope.payload_type != CATALOG_PAYLOAD_TYPE:
        raise OtaError(
            OtaErrorCode.PAYLOAD_TYPE_MISMATCH,
            f"payloadType {envelope.payload_type!r}, expected {CATALOG_PAYLOAD_TYPE!r}",
        )

    message = pae(envelope.payload_type, payload)
    trusted = [s for s in envelope.signatures if s.key_id in keyring]
    if not trusted:
        raise OtaError(OtaErrorCode.UNTRUSTED_SIGNER, f"no pinned key among {key_ids}")
    if not any(_signature_ok(keyring[s.key_id], s.sig, message) for s in trusted):
        raise OtaError(
            OtaErrorCode.SIGNATURE_INVALID,
            f"no valid signature by {[s.key_id for s in trusted]}",
        )

    catalog = _parse_catalog_payload(payload)
    if catalog.game != game:
        raise OtaError(OtaErrorCode.GAME_MISMATCH, f"catalog game {catalog.game!r} != {game!r}")
    if catalog.channel != channel:
        raise OtaError(
            OtaErrorCode.CHANNEL_MISMATCH,
            f"catalog channel {catalog.channel.value!r} != {channel.value!r}",
        )
    if now >= catalog.expires_at:
        raise OtaError(
            OtaErrorCode.CATALOG_EXPIRED,
            f"expired at {catalog.expires_at.isoformat()} (now {now.isoformat()})",
        )
    payload_sha256 = sha256_ref(payload)
    if last_seen is not None:
        if catalog.sequence < last_seen.sequence:
            raise OtaError(
                OtaErrorCode.SEQUENCE_REGRESSION,
                f"sequence {catalog.sequence} < accepted {last_seen.sequence}",
            )
        if catalog.sequence == last_seen.sequence and payload_sha256 != last_seen.payload_sha256:
            raise OtaError(
                OtaErrorCode.SEQUENCE_REGRESSION,
                f"sequence {catalog.sequence} re-used with different content",
            )
    _check_catalog_consistency(catalog)
    return VerifiedCatalog(catalog, payload_sha256)


def _signature_ok(key: TrustedKey, sig_b64: str, message: bytes) -> bool:
    try:
        key.public_key.verify(strict_b64decode(sig_b64), message, ec.ECDSA(hashes.SHA256()))
    except InvalidSignature:
        return False
    return True


def _parse_catalog_payload(payload: bytes) -> OtaCatalog:
    try:
        raw = strict_json(payload)
    except ValueError as exc:
        raise OtaError(OtaErrorCode.PAYLOAD_INVALID, str(exc)) from exc
    version = raw.get("schemaVersion") if isinstance(raw, dict) else None
    if type(version) is int and version != OTA_SCHEMA_VERSION:
        raise OtaError(OtaErrorCode.UNSUPPORTED_SCHEMA_VERSION, f"schemaVersion {version}")
    try:
        catalog = parse_strict(OtaCatalog, payload)
        _require_utc(catalog.issued_at, "issuedAt")
        _require_utc(catalog.expires_at, "expiresAt")
        for release in catalog.releases:
            _require_utc(release.published_at, f"{release.release_id}.publishedAt")
    except ValueError as exc:
        raise OtaError(OtaErrorCode.PAYLOAD_INVALID, str(exc)) from exc
    return catalog


def _check_catalog_consistency(catalog: OtaCatalog) -> None:
    def fail(detail: str) -> OtaError:
        return OtaError(OtaErrorCode.CATALOG_INCONSISTENT, detail)

    if catalog.expires_at <= catalog.issued_at:
        raise fail("expiresAt is not after issuedAt")
    if catalog.expires_at - catalog.issued_at > MAX_CATALOG_VALIDITY:
        raise fail(f"validity exceeds {MAX_CATALOG_VALIDITY.days} days")
    ids = [r.release_id for r in catalog.releases]
    if len(ids) != len(set(ids)):
        raise fail("duplicate releaseId")
    for release in catalog.releases:
        if not release.release_id.startswith(release.client_build + "-r"):
            raise fail(f"{release.release_id} does not name clientBuild {release.client_build}")
    keys = [release_sort_key(i) for i in ids]
    if keys != sorted(keys):
        raise fail("releases are not ordered by (clientBuild, revision)")
    if catalog.active_release_id not in ids:
        raise fail(f"activeReleaseId {catalog.active_release_id} is not listed")


# --------------------------------------------------------------------------- activation


class ActivationDecision(StrEnum):
    ACTIVATE = "ACTIVATE"
    NO_CHANGE = "NO_CHANGE"
    KEEP_CURRENT_INCOMPATIBLE = "KEEP_CURRENT_INCOMPATIBLE"
    KEEP_BUNDLED_FLOOR = "KEEP_BUNDLED_FLOOR"


def decide_activation(
    verified: VerifiedCatalog,
    *,
    supported_data_level: int,
    bundled_product_build: str,
    current_release_id: str | None,
) -> tuple[ActivationDecision, CatalogRelease]:
    """What the client does with a verified catalog. ``current_release_id`` is the active
    OTA release on the device, ``None`` while the bundled product is in use.

    The catalog's ``activeReleaseId`` is the only release ever activated automatically -
    never "the newest compatible one". A release older than the app's bundled product is
    never activated (the bundled product is the floor of every rollback)."""
    active = verified.release(verified.catalog.active_release_id)
    if active.min_data_level > supported_data_level:
        return ActivationDecision.KEEP_CURRENT_INCOMPATIBLE, active
    if ClientVersion.parse(active.client_build) < ClientVersion.parse(bundled_product_build):
        return ActivationDecision.KEEP_BUNDLED_FLOOR, active
    if current_release_id == active.release_id:
        return ActivationDecision.NO_CHANGE, active
    return ActivationDecision.ACTIVATE, active


# --------------------------------------------------------------------------- release manifest


def role_path_pattern(role: FileRole, game: str, client_build: str) -> re.Pattern[str]:
    """The only paths a file of ``role`` may have in a release of (game, clientBuild) -
    exactly the app's bundled resource layout, so no path can leave the release root."""
    g, b = re.escape(game), re.escape(client_build)
    name = r"[a-z][a-z0-9_]{0,31}\.json"
    patterns = {
        FileRole.DATASET_MANIFEST: rf"dataset/{g}/{b}/manifest\.json",
        FileRole.TALENT_DATA: rf"dataset/{g}/{b}/(?!manifest\.json$){name}",
        FileRole.VISUALS: rf"visuals/{g}/{b}/(?!icons\.json$){name}",
        FileRole.ICON_REGISTRY: rf"visuals/{g}/{b}/icons\.json",
        FileRole.ICON_IMAGE: rf"visuals/{g}/{b}/icons/[1-9][0-9]{{0,9}}\.png",
        FileRole.TOOLTIPS: rf"tooltips/{g}/{b}/{name}",
        FileRole.LOCALIZATION: rf"localization/{g}/{b}/[a-z]{{2}}[A-Z]{{2}}/{name}",
    }
    return re.compile(rf"^{patterns[role]}$")


def verify_release_manifest(
    manifest_bytes: bytes,
    entry: CatalogRelease,
    *,
    game: str,
    supported_data_level: int,
) -> OtaReleaseManifest:
    """Verify a downloaded release manifest against the verified catalog entry pinning it."""
    if len(manifest_bytes) != entry.manifest_byte_length or (
        sha256_ref(manifest_bytes) != entry.manifest_sha256
    ):
        raise OtaError(
            OtaErrorCode.MANIFEST_HASH_MISMATCH,
            f"{len(manifest_bytes)} bytes {sha256_ref(manifest_bytes)}, catalog pins "
            f"{entry.manifest_byte_length} bytes {entry.manifest_sha256}",
        )
    try:
        raw = strict_json(manifest_bytes)
    except ValueError as exc:
        raise OtaError(OtaErrorCode.MANIFEST_INVALID, str(exc)) from exc
    version = raw.get("schemaVersion") if isinstance(raw, dict) else None
    if type(version) is int and version != OTA_SCHEMA_VERSION:
        raise OtaError(OtaErrorCode.UNSUPPORTED_SCHEMA_VERSION, f"schemaVersion {version}")
    try:
        manifest = parse_strict(OtaReleaseManifest, manifest_bytes)
        _require_utc(manifest.created_at, "createdAt")
    except ValueError as exc:
        raise OtaError(OtaErrorCode.MANIFEST_INVALID, str(exc)) from exc

    identity = {
        "game": (manifest.game, game),
        "clientBuild": (manifest.client_build, entry.client_build),
        "releaseId": (manifest.release_id, entry.release_id),
        "minDataLevel": (manifest.min_data_level, entry.min_data_level),
        "totalByteLength": (manifest.total_byte_length, entry.total_byte_length),
    }
    for name, (found, expected) in identity.items():
        if found != expected:
            raise OtaError(
                OtaErrorCode.RELEASE_IDENTITY_MISMATCH, f"{name} {found!r} != {expected!r}"
            )
    if manifest.min_data_level > supported_data_level:
        raise OtaError(
            OtaErrorCode.INCOMPATIBLE_DATA_LEVEL,
            f"needs data level {manifest.min_data_level}, app supports {supported_data_level}",
        )
    _check_layout(manifest)
    return manifest


def _check_layout(manifest: OtaReleaseManifest) -> None:
    def fail(detail: str) -> OtaError:
        return OtaError(OtaErrorCode.LAYOUT_INVALID, detail)

    paths = [f.path for f in manifest.files]
    if len(paths) != len(set(paths)):
        raise fail("duplicate path")
    if paths != sorted(paths, key=lambda p: p.encode("utf-8")):
        raise fail("files are not ordered by path")
    for file in manifest.files:
        if not role_path_pattern(file.role, manifest.game, manifest.client_build).match(file.path):
            raise fail(f"{file.path!r} is not a valid {file.role.value} path")
    if sum(f.byte_length for f in manifest.files) != manifest.total_byte_length:
        raise fail("totalByteLength is not the sum of the file sizes")
    for role in (FileRole.DATASET_MANIFEST, FileRole.ICON_REGISTRY):
        if sum(1 for f in manifest.files if f.role is role) != 1:
            raise fail(f"exactly one {role.value} file is required")
    for role in (FileRole.TALENT_DATA, FileRole.VISUALS, FileRole.TOOLTIPS, FileRole.LOCALIZATION):
        if not any(f.role is role for f in manifest.files):
            raise fail(f"no {role.value} file")


# --------------------------------------------------------------------------- release files


def verify_release_files(
    manifest: OtaReleaseManifest,
    read_file: Callable[[ReleaseFile], bytes],
    *,
    known_same_build_manifest: DatasetManifest | None = None,
) -> None:
    """Verify every file of a verified release manifest: bytes, strict content, and the
    identity/completeness relations between the snapshot's documents.

    ``known_same_build_manifest`` is the dataset manifest of the same (game, clientBuild)
    the device already trusts (bundled, or an installed release); talent data of one client
    build never changes between releases."""
    blobs: dict[str, bytes] = {}
    for file in manifest.files:
        data = read_file(file)
        if len(data) != file.byte_length:
            raise OtaError(
                OtaErrorCode.BLOB_SIZE_MISMATCH,
                f"{file.path}: {len(data)} bytes, manifest says {file.byte_length}",
            )
        if sha256_ref(data) != file.sha256:
            raise OtaError(OtaErrorCode.BLOB_HASH_MISMATCH, f"{file.path}: {sha256_ref(data)}")
        blobs[file.path] = data
    _SnapshotChecker(manifest, blobs).check(known_same_build_manifest)


class _SnapshotChecker:
    def __init__(self, manifest: OtaReleaseManifest, blobs: Mapping[str, bytes]) -> None:
        self.m = manifest
        self.blobs = blobs
        self.root = f"{manifest.game}/{manifest.client_build}"

    def files(self, role: FileRole) -> list[ReleaseFile]:
        return [f for f in self.m.files if f.role is role]

    def parse[M: BaseModel](self, model: type[M], path: str) -> M:
        try:
            return parse_strict(model, self.blobs[path])
        except (ValueError, ValidationError) as exc:
            raise OtaError(OtaErrorCode.CONTENT_INVALID, f"{path}: {exc}") from exc

    def identity(self, path: str, what: str, found: object, expected: object) -> None:
        if found != expected:
            raise OtaError(
                OtaErrorCode.CONTENT_IDENTITY_MISMATCH,
                f"{path}: {what} {found!r} != {expected!r}",
            )

    @staticmethod
    def incomplete(detail: str) -> OtaError:
        return OtaError(OtaErrorCode.RELEASE_INCOMPLETE, detail)

    def same_file_names(self, role: FileRole, prefix: str, expected: set[str]) -> None:
        names = {f.path.removeprefix(prefix) for f in self.files(role)}
        if names != expected:
            raise self.incomplete(
                f"{role.value} files {sorted(names)} != talent data files {sorted(expected)}"
            )

    def check(self, known: DatasetManifest | None) -> None:
        g, b = self.m.game, self.m.client_build
        # dataset manifest + talent data
        (manifest_file,) = self.files(FileRole.DATASET_MANIFEST)
        dataset = self.parse(DatasetManifest, manifest_file.path)
        self.identity(manifest_file.path, "game", dataset.game, g)
        self.identity(manifest_file.path, "clientBuild", dataset.client_build, b)
        for entry in dataset.classes:
            if entry.validation.errors != 0:
                raise OtaError(
                    OtaErrorCode.CONTENT_INVALID,
                    f"{manifest_file.path}: {entry.token} has validation errors",
                )
        dataset_prefix = f"dataset/{self.root}/"
        listed = {e.file for e in dataset.classes}
        if len(listed) != len(dataset.classes):
            raise OtaError(OtaErrorCode.CONTENT_INVALID, "dataset manifest repeats a file")
        self.same_file_names(FileRole.TALENT_DATA, dataset_prefix, listed)

        documents: dict[str, TalentDataDocument] = {}
        for entry in dataset.classes:
            path = dataset_prefix + entry.file
            doc = self.parse(TalentDataDocument, path)
            self.identity(path, "game", doc.game, g)
            self.identity(path, "clientBuild", doc.client_build, b)
            self.identity(path, "class", doc.class_.token, entry.token)
            self.identity(path, "contentHash", doc.content_hash, entry.content_hash)
            self.identity(path, "treeIds", sorted(t.id for t in doc.trees), sorted(entry.tree_ids))
            documents[entry.file] = doc
        if known is not None:
            _check_same_build_identity(dataset, known)

        # icon registry + images
        (registry_file,) = self.files(FileRole.ICON_REGISTRY)
        visuals_prefix = f"visuals/{self.root}/"
        registry = self.parse(TalentIconRegistry, registry_file.path)
        self.identity(registry_file.path, "game", registry.game, g)
        self.identity(registry_file.path, "clientBuild", registry.client_build, b)
        images = {f.path: f for f in self.files(FileRole.ICON_IMAGE)}
        registered = {visuals_prefix + icon.file: icon for icon in registry.icons}
        if set(images) != set(registered):
            raise self.incomplete("icon images and the icon registry list different files")
        for path, icon in registered.items():
            image = images[path]
            self.identity(path, "registry sha256", icon.sha256, image.sha256)
            self.identity(path, "registry byteLength", icon.byte_length, image.byte_length)
            if not self.blobs[path].startswith(PNG_SIGNATURE):
                raise OtaError(OtaErrorCode.CONTENT_INVALID, f"{path}: not a PNG file")
        icon_ids = {icon.file_data_id for icon in registry.icons}

        # visuals + tooltips sidecars
        self.same_file_names(FileRole.VISUALS, visuals_prefix, set(documents))
        tooltips_prefix = f"tooltips/{self.root}/"
        self.same_file_names(FileRole.TOOLTIPS, tooltips_prefix, set(documents))
        tooltip_hashes: dict[str, str] = {}
        for name, doc in documents.items():
            path = visuals_prefix + name
            visuals = self.parse(TalentVisualsDocument, path)
            self._sidecar_identity(path, visuals, doc)
            for visual in visuals.entries:
                if visual.icon_file_data_id not in icon_ids:
                    raise self.incomplete(f"{path}: icon {visual.icon_file_data_id} unregistered")
            path = tooltips_prefix + name
            tooltips = self.parse(TalentTooltipsDocument, path)
            self._sidecar_identity(path, tooltips, doc)
            tooltip_hashes[name] = tooltips.content_hash

        # localization overlays: each locale complete
        locales: dict[str, set[str]] = {}
        localization_prefix = f"localization/{self.root}/"
        for file in self.files(FileRole.LOCALIZATION):
            locale, name = file.path.removeprefix(localization_prefix).split("/")
            locales.setdefault(locale, set()).add(name)
            if name not in documents:
                raise self.incomplete(f"{file.path}: no talent data file {name}")
            overlay = self.parse(TalentLocalizationDocument, file.path)
            self._sidecar_identity(file.path, overlay, documents[name])
            self.identity(file.path, "locale", str(overlay.locale), locale)
            self.identity(
                file.path,
                "tooltipsContentHash",
                overlay.tooltips_content_hash,
                tooltip_hashes[name],
            )
        for locale, names in locales.items():
            if names != set(documents):
                raise self.incomplete(f"locale {locale} lacks {sorted(set(documents) - names)}")

    def _sidecar_identity(
        self,
        path: str,
        sidecar: TalentVisualsDocument | TalentTooltipsDocument | TalentLocalizationDocument,
        doc: TalentDataDocument,
    ) -> None:
        self.identity(path, "game", sidecar.game, self.m.game)
        self.identity(path, "clientBuild", sidecar.client_build, self.m.client_build)
        self.identity(path, "classToken", sidecar.class_token, doc.class_.token)
        self.identity(
            path, "talentDataContentHash", sidecar.talent_data_content_hash, doc.content_hash
        )


def _check_same_build_identity(new: DatasetManifest, known: DatasetManifest) -> None:
    """Two datasets of one client build must describe the same talent data - otherwise a
    saved build would silently be re-interpreted against different data."""

    def fingerprint(m: DatasetManifest) -> list[tuple[str, str, tuple[int, ...], str]]:
        return [(e.token, e.file, tuple(sorted(e.tree_ids)), e.content_hash) for e in m.classes]

    if (new.game, new.client_build) != (known.game, known.client_build):
        raise ValueError("known manifest is of another build")
    if fingerprint(new) != fingerprint(known):
        raise OtaError(
            OtaErrorCode.BUILD_IDENTITY_CONFLICT,
            f"{new.client_build}: talent data differs from the dataset already trusted for "
            "this client build",
        )
