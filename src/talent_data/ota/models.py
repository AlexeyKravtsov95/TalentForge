"""OTA dataset delivery - the publisher/app wire contracts (schema v1, M5.OTA.1).

Three public, versioned, closed documents (specification: ``docs/ota/ota-contract-v1.md``):

* ``ota-envelope.v1`` - the signed wrapper. Its ``payload`` is the exact bytes of one
  signed document (base64); its signatures are ECDSA P-256 / SHA-256 over the DSSE
  pre-authentication encoding of ``payloadType`` and those bytes. Nothing is canonicalized:
  the signed bytes are the parsed bytes.
* ``ota-catalog.v1`` - the payload of the only mutable object of a channel
  (``v1/channels/<channel>/catalog.json``): which releases exist, which one is active, and
  the SHA-256 of every release manifest. It is the root of every integrity chain.
* ``ota-release.v1`` - one immutable release manifest per published dataset snapshot: every
  file of the snapshot with its role, SHA-256 and size. It is not signed; it is
  authenticated by the ``manifestSha256`` pinned in a verified catalog.

Every downloadable object other than the catalog is a content-addressed blob
(``v1/blobs/sha256/<hex>``), so the store needs no server logic and no object is ever
rewritten.

JSON uses camelCase; Python uses snake_case. Parsing is strict: unknown fields, missing
fields and wrong wire types are rejected. No field has a default - not even the constant
``schemaVersion`` - so the generated JSON Schema requires every property, as the client does.
The models check the shape of one document; the relations between documents (catalog entry
vs. release manifest, manifest vs. files) are checked by ``talent_data.ota.verify``.
"""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

OTA_SCHEMA_VERSION: Literal[1] = 1

CATALOG_PAYLOAD_TYPE = "application/vnd.talentforge.ota-catalog.v1+json"

SHA256_PATTERN = r"^sha256:[0-9a-f]{64}$"
BUILD_PATTERN = r"^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$"
# "<clientBuild>-r<revision>", revision >= 1 without leading zeros.
RELEASE_ID_PATTERN = r"^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+-r[1-9][0-9]{0,5}$"
KEY_ID_PATTERN = r"^[a-z0-9][a-z0-9-]{2,63}$"
GAME_PATTERN = r"^[a-z][a-z0-9-]{0,31}$"
# Standard base64 alphabet with padding; the verifier also rejects non-canonical encodings.
BASE64_PATTERN = r"^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$"
COMMIT_PATTERN = r"^[0-9a-f]{40}$"

# Hard limits of v1. A document outside them is rejected, never truncated.
# catalog.json as received (envelope bytes), enforced while downloading and before any
# parsing. A maximal valid catalog (512 releases, 4 signatures) is ~0.23 MiB (contract §2).
MAX_CATALOG_BYTES = 1024 * 1024
MAX_SIGNATURES = 4
MAX_CATALOG_RELEASES = 512
MAX_RELEASE_FILES = 4096
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_RELEASE_BYTES = 256 * 1024 * 1024


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        frozen=True,
        extra="forbid",
        strict=True,
    )


# --------------------------------------------------------------------------- ota-envelope.v1


class EnvelopeSignature(_Model):
    key_id: str = Field(
        pattern=KEY_ID_PATTERN,
        description="Identifier of the signing key in the app's pinned keyring",
    )
    sig: str = Field(
        min_length=4,
        max_length=256,
        pattern=BASE64_PATTERN,
        description="base64 of the ASN.1 DER ECDSA P-256 / SHA-256 signature over "
        "PAE(payloadType, payload bytes)",
    )


class SignedEnvelope(_Model):
    payload_type: str = Field(
        min_length=1,
        max_length=128,
        description="Media type of the signed document; part of the signed message",
    )
    payload: str = Field(
        min_length=4,
        pattern=BASE64_PATTERN,
        description="base64 of the exact UTF-8 JSON bytes of the signed document",
    )
    signatures: list[EnvelopeSignature] = Field(
        min_length=1,
        max_length=MAX_SIGNATURES,
        description="Valid when at least one signature of a pinned key verifies",
    )


# --------------------------------------------------------------------------- ota-catalog.v1


class Channel(StrEnum):
    PRODUCTION = "production"
    STAGING = "staging"


class CatalogRelease(_Model):
    release_id: str = Field(
        pattern=RELEASE_ID_PATTERN,
        description="'<clientBuild>-r<revision>'; unique within the catalog",
    )
    client_build: str = Field(pattern=BUILD_PATTERN)
    min_data_level: int = Field(
        ge=1,
        description="Lowest app data level that can read and interpret the release",
    )
    manifest_sha256: str = Field(
        pattern=SHA256_PATTERN,
        description="SHA-256 of the release manifest bytes; also its blob address",
    )
    manifest_byte_length: int = Field(gt=0, le=MAX_FILE_BYTES)
    total_byte_length: int = Field(
        gt=0,
        le=MAX_RELEASE_BYTES,
        description="Sum of the release's file sizes, copied from the release manifest",
    )
    published_at: datetime


class OtaCatalog(_Model):
    schema_version: Literal[1]
    game: str = Field(pattern=GAME_PATTERN)
    channel: Channel
    sequence: int = Field(
        ge=1,
        description="Strictly increases with every catalog published to the channel",
    )
    issued_at: datetime
    expires_at: datetime = Field(
        description="After this instant the catalog is ignored (never trusted again)"
    )
    active_release_id: str = Field(
        pattern=RELEASE_ID_PATTERN,
        description="The release the publisher wants new builds to be created on",
    )
    releases: list[CatalogRelease] = Field(
        min_length=1,
        max_length=MAX_CATALOG_RELEASES,
        description="Every retained release, ordered by (clientBuild, revision); entries "
        "are only ever added, so saved builds can always find their dataset",
    )


# --------------------------------------------------------------------------- ota-release.v1


class FileRole(StrEnum):
    DATASET_MANIFEST = "DATASET_MANIFEST"
    TALENT_DATA = "TALENT_DATA"
    VISUALS = "VISUALS"
    ICON_REGISTRY = "ICON_REGISTRY"
    ICON_IMAGE = "ICON_IMAGE"
    TOOLTIPS = "TOOLTIPS"
    LOCALIZATION = "LOCALIZATION"


class ReleaseFile(_Model):
    path: str = Field(
        min_length=1,
        max_length=160,
        description="Path inside the snapshot, identical to the app's bundled resource "
        "path (e.g. dataset/forever/1.60.1.70450/mage.json); the role fixes its form",
    )
    role: FileRole
    sha256: str = Field(pattern=SHA256_PATTERN, description="Also the file's blob address")
    byte_length: int = Field(gt=0, le=MAX_FILE_BYTES)


class ReleaseProvenance(_Model):
    repository_commit: str = Field(
        pattern=COMMIT_PATTERN,
        description="Commit of the TalentForge repository whose committed data was published",
    )
    validation: str = Field(
        min_length=1,
        max_length=200,
        description="Human-readable record of the validation the publisher ran",
    )


class OtaReleaseManifest(_Model):
    schema_version: Literal[1]
    game: str = Field(pattern=GAME_PATTERN)
    client_build: str = Field(pattern=BUILD_PATTERN)
    release_id: str = Field(pattern=RELEASE_ID_PATTERN)
    min_data_level: int = Field(ge=1)
    created_at: datetime
    provenance: ReleaseProvenance
    files: list[ReleaseFile] = Field(
        min_length=1,
        max_length=MAX_RELEASE_FILES,
        description="Ordered by path (byte order), each path once",
    )
    total_byte_length: int = Field(gt=0, le=MAX_RELEASE_BYTES)
