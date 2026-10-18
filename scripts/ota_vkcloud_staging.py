"""VK Cloud remote staging helpers (M5.OTA.4b) - everything around ``talent-data ota``.

The publication itself is the publisher CLI with ``--backend s3`` (see
``docs/ota/publisher-runbook.md`` §6). This script holds the operator steps the CLI has no
command for, each reproducible:

``keygen <dir>``
    The durable staging signing key ``tf-staging-<yyyy>`` (private PEM, mode 0600) and its
    public keyring, plus ``android-staging-vkcloud.env`` for a debug build. Refuses a
    directory inside a git checkout and never overwrites an existing key. The key is a
    *signing* key, unrelated to the S3 access key (which stays in the AWS CLI profile).

``probe-http``
    Anonymous, read-only HTTP checks of the public origin: status, ``Content-Type``,
    ``Cache-Control``, ``Content-Encoding`` with and without ``Accept-Encoding: gzip``
    (bytes on the wire vs. decoded), SHA-256 of the decoded body, redirects (never
    followed), plain ``http://``, and the paths that must stay private. Prints JSON.

``probe-conditional``
    Whether the bucket honours conditional puts (``If-None-Match: *``, ``If-Match``). Writes
    one small probe blob (a content-addressed key no release references; ``gc`` lists it)
    and re-puts the *identical* bytes conditionally - so even a provider that ignores the
    conditions changes nothing. Dry run unless ``--execute``.

``fault bad-signature`` / ``fault restore`` / ``fault expired``
    Fault injection on the staging catalog for the client E2E, through the adapter's guards:

    * ``bad-signature`` - the next catalog (live re-issued, sequence + 1) is put with a
      corrupted signature; the correctly signed envelope of the *same payload* is saved to
      ``--restore-file`` (outside git);
    * ``restore`` - replaces the live bad-signature catalog with that saved envelope, after
      proving both carry byte-identical payloads and the saved one verifies: the result is
      exactly what a normal re-sign would have published;
    * ``expired`` - a correctly signed re-issue whose validity is ``--validity-seconds``;
      once it lapses, the runbook recovery (``ota resign`` + ``sign`` + ``attach-signature``,
      all ``--channel staging``) replaces it.

Every mutating subcommand is a dry run without ``--execute``. Nothing here touches the
production catalog (the adapter refuses that key) or loads a production key (inline signing
accepts ``tf-staging-*`` / ``test-*`` only).
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import gzip
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

import httpx  # noqa: E402
from botocore.exceptions import ClientError  # noqa: E402
from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402

from talent_data.ota.catalog import resign_catalog  # noqa: E402
from talent_data.ota.errors import OtaPublisherError  # noqa: E402
from talent_data.ota.models import Channel, SignedEnvelope  # noqa: E402
from talent_data.ota.s3_store import S3BlobStore, S3Location, connect  # noqa: E402
from talent_data.ota.signing import (  # noqa: E402
    build_envelope,
    inline_sign_envelope,
    load_keyring_file,
    load_private_key,
    sign_pae_der,
)
from talent_data.ota.store import (  # noqa: E402
    BLOB_CACHE_CONTROL,
    CATALOG_CACHE_CONTROL,
    blob_key,
    catalog_key,
    read_live_catalog,
    write_catalog,
)
from talent_data.ota.verify import (  # noqa: E402
    OtaError,
    parse_strict,
    sha256_ref,
    strict_b64decode,
    verify_catalog,
)

GAME = "forever"
STAGING = catalog_key(Channel.STAGING)
PROBE_BYTES = b'{"probe": "talentforge M5.OTA.4b conditional-write probe"}\n'


class HelperError(Exception):
    """A precondition does not hold; nothing was written."""


def now_utc() -> dt.datetime:
    return dt.datetime.now(dt.UTC).replace(microsecond=0)


def inside_git_checkout(path: Path) -> bool:
    probe = path
    while not probe.exists():
        probe = probe.parent
    result = subprocess.run(
        ["git", "rev-parse", "--is-inside-work-tree"], cwd=probe, capture_output=True, text=True
    )
    return result.returncode == 0 and result.stdout.strip() == "true"


def write_private(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as handle:
        handle.write(data)


def outside_git(path: Path, what: str) -> Path:
    path = path.expanduser().resolve()
    if inside_git_checkout(path):
        raise HelperError(f"{what} {path} is inside a git checkout; choose a directory outside")
    return path


# --------------------------------------------------------------------------- keygen


def keygen(out: Path, key_id: str, origin: str) -> dict[str, str]:
    if not key_id.startswith("tf-staging-") or key_id.startswith("tf-staging-local-"):
        raise HelperError(f"a durable staging key id is tf-staging-<name>, got {key_id!r}")
    if not origin.startswith("https://"):
        raise HelperError("the staging origin is https:// only")
    out = outside_git(out, "key directory")
    pem = out / f"{key_id}.pem"
    keyring = out / "staging-keyring.json"
    env = out / "android-staging-vkcloud.env"
    for path in (pem, keyring, env):
        if path.exists():
            raise HelperError(f"{path} exists; a staging key is never overwritten")
    key = ec.generate_private_key(ec.SECP256R1())
    spki = key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    write_private(
        pem,
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ),
    )
    entry = {
        "keyId": key_id,
        "algorithm": "ecdsa-p256-sha256",
        "publicKeySpki": base64.b64encode(spki).decode("ascii"),
    }
    keyring.write_text(json.dumps({"keys": [entry]}, indent=2) + "\n", encoding="utf-8")
    # public PKI origin: no TRUSTANCHOR - the platform's roots verify the certificate
    env.write_text(
        "export TALENTFORGE_OTA_ANDROID_STAGING_ENABLED=true\n"
        f"export TALENTFORGE_OTA_ANDROID_STAGING_BASEURL={origin}\n"
        f"export TALENTFORGE_OTA_ANDROID_STAGING_KEYRING={keyring}\n",
        encoding="utf-8",
    )
    return {"keyId": key_id, "keyring": str(keyring), "pem": str(pem), "env": str(env)}


# --------------------------------------------------------------------------- probe-http


def _fetch(client: httpx.Client, url: str, *, gzip_ok: bool, method: str = "GET") -> dict[str, Any]:
    headers = {"Accept-Encoding": "gzip" if gzip_ok else "identity"}
    try:
        with client.stream(method, url, headers=headers) as response:
            wire = b"".join(response.iter_raw())
            result: dict[str, Any] = {
                "status": response.status_code,
                "httpVersion": response.http_version,
                "location": response.headers.get("location"),
                "contentType": response.headers.get("content-type"),
                "contentEncoding": response.headers.get("content-encoding"),
                "cacheControl": response.headers.get("cache-control"),
                "contentLength": response.headers.get("content-length"),
                "vary": response.headers.get("vary"),
                "wireBytes": len(wire),
            }
    except httpx.HTTPError as exc:
        return {"error": type(exc).__name__}
    if method == "GET" and result["status"] == 200:
        body = gzip.decompress(wire) if result["contentEncoding"] == "gzip" else wire
        result["decodedBytes"] = len(body)
        result["sha256"] = sha256_ref(body)
    return result


def probe_http(origin: str, json_blob: str | None, png_blob: str | None) -> dict[str, Any]:
    if not origin.startswith("https://"):
        raise HelperError("the origin is probed over https://")
    plain = "http://" + origin.removeprefix("https://")
    paths = {"catalog": STAGING}
    if json_blob:
        paths["jsonBlob"] = blob_key("sha256:" + json_blob)
    if png_blob:
        paths["pngBlob"] = blob_key("sha256:" + png_blob)
    report: dict[str, Any] = {"origin": origin, "at": now_utc().isoformat(), "objects": {}}
    # TLS verification is httpx's default and stays on; redirects are reported, not followed
    with httpx.Client(follow_redirects=False, timeout=30.0) as client:
        for name, path in paths.items():
            url = f"{origin}/{path}"
            report["objects"][name] = {
                "path": path,
                "head": _fetch(client, url, gzip_ok=False, method="HEAD"),
                "identity": _fetch(client, url, gzip_ok=False),
                "gzip": _fetch(client, url, gzip_ok=True),
                "plainHttp": _fetch(client, f"{plain}/{path}", gzip_ok=False),
            }
        report["private"] = {
            path: _fetch(client, f"{origin}/{path}", gzip_ok=False, method="HEAD").get("status")
            for path in (catalog_key(Channel.PRODUCTION), "v1/", "outside.json", "?list-type=2")
        }
    return report


# --------------------------------------------------------------------------- S3 helpers


def open_store(args: argparse.Namespace, *, writable: bool) -> S3BlobStore:
    location = S3Location(
        bucket=args.s3_bucket,
        endpoint_url=args.s3_endpoint,
        region=args.s3_region,
        profile=args.aws_profile,
    )
    return connect(location, writable=writable, allow_delete=False)


def probe_conditional(args: argparse.Namespace) -> dict[str, Any]:
    key = blob_key(sha256_ref(PROBE_BYTES))
    plan: dict[str, Any] = {"probeKey": key, "bytes": len(PROBE_BYTES)}
    if not args.execute:
        return {**plan, "dryRun": "nothing written (pass --execute)"}
    store = open_store(args, writable=True)
    created = not store.exists(key)
    if created:
        store.put(key, PROBE_BYTES, cache_control=BLOB_CACHE_CONTROL)  # the verified put
    md5 = base64.b64encode(hashlib.md5(PROBE_BYTES, usedforsecurity=False).digest()).decode()
    common = {
        "Bucket": args.s3_bucket,
        "Key": key,
        "Body": PROBE_BYTES,
        "ContentMD5": md5,
        "ContentType": "application/json",
        "CacheControl": BLOB_CACHE_CONTROL,
    }
    client: Any = store._client  # raw client: these probes bypass the adapter's HEAD guard
    results: dict[str, Any] = {**plan, "created": created}
    for name, condition in (
        ("ifNoneMatchStarOnExisting", {"IfNoneMatch": "*"}),
        ("ifMatchWrongEtag", {"IfMatch": '"00000000000000000000000000000000"'}),
    ):
        try:
            client.put_object(**common, **condition)
            results[name] = "IGNORED (200: the identical bytes were re-put)"
        except ClientError as exc:
            response = exc.response
            status = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
            code = response.get("Error", {}).get("Code") or type(exc).__name__
            verdict = "HONOURED" if status in (409, 412) else "ERROR"
            results[name] = f"{verdict} ({status} {code})"
    head = client.head_object(Bucket=args.s3_bucket, Key=key)
    results["afterHead"] = {
        "ContentType": head.get("ContentType"),
        "CacheControl": head.get("CacheControl"),
        "ContentEncoding": head.get("ContentEncoding"),
        "ETag": head.get("ETag"),
    }
    return results


# --------------------------------------------------------------------------- faults


def _verify(envelope: bytes, keyring: dict[str, Any], now: dt.datetime) -> Any:
    return verify_catalog(
        envelope, keyring=keyring, game=GAME, channel=Channel.STAGING, now=now, last_seen=None
    )


def fault_bad_signature(args: argparse.Namespace) -> dict[str, Any]:
    restore = outside_git(args.restore_file, "restore file")
    if restore.exists():
        raise HelperError(f"{restore} exists; restore or remove the previous fault first")
    now = now_utc()
    store = open_store(args, writable=args.execute)
    keyring = load_keyring_file(args.keyring.expanduser())
    live = read_live_catalog(store, Channel.STAGING, keyring=keyring, game=GAME, now=now)
    if live is None:
        raise HelperError("no live staging catalog to build on")
    update = resign_catalog(live, issued_at=now)
    good = inline_sign_envelope(update.payload, key_pem=args.key.expanduser(), key_id=args.key_id)
    der = bytearray(sign_pae_der(load_private_key(args.key.expanduser()), update.payload))
    der[-5] ^= 0x01  # inside the signature's s value: still DER, no longer valid
    bad = build_envelope(update.payload, {args.key_id: bytes(der)})
    _verify(good, keyring, now)
    try:
        _verify(bad, keyring, now)
    except OtaError as exc:
        verdict = exc.code.value
    else:
        raise HelperError("the corrupted envelope still verifies; refusing to inject it")
    plan = {
        "fault": "bad-signature",
        "liveSequence": live.sequence,
        "faultSequence": update.catalog.sequence,
        "publisherVerdict": verdict,
        "restoreFile": str(restore),
    }
    if not args.execute:
        return {**plan, "dryRun": "nothing written (pass --execute)"}
    write_private(restore, good)
    store.put(STAGING, bad, cache_control=CATALOG_CACHE_CONTROL)  # If-Match: the live ETag
    return {**plan, "written": True}


def fault_restore(args: argparse.Namespace) -> dict[str, Any]:
    restore = args.restore_file.expanduser().resolve()
    good = restore.read_bytes()
    now = now_utc()
    store = open_store(args, writable=args.execute)
    live_bytes = store.get(STAGING)  # observes the ETag of the faulty live catalog

    def payload_of(envelope: bytes) -> bytes:
        return strict_b64decode(parse_strict(SignedEnvelope, envelope).payload)

    if payload_of(live_bytes) != payload_of(good):
        raise HelperError("the live catalog's payload is not the saved one; refusing to restore")
    verified = _verify(good, load_keyring_file(args.keyring.expanduser()), now)
    plan = {
        "fault": "restore",
        "sequence": verified.catalog.sequence,
        "payloadSha256": verified.payload_sha256,
    }
    if not args.execute:
        return {**plan, "dryRun": "nothing written (pass --execute)"}
    store.put(STAGING, good, cache_control=CATALOG_CACHE_CONTROL)  # If-Match: the faulty ETag
    restore.unlink()
    return {**plan, "written": True}


def fault_expired(args: argparse.Namespace) -> dict[str, Any]:
    now = now_utc()
    store = open_store(args, writable=args.execute)
    keyring = load_keyring_file(args.keyring.expanduser())
    live = read_live_catalog(store, Channel.STAGING, keyring=keyring, game=GAME, now=now)
    if live is None:
        raise HelperError("no live staging catalog to build on")
    update = resign_catalog(
        live, issued_at=now, validity=dt.timedelta(seconds=args.validity_seconds)
    )
    plan = {
        "fault": "expired",
        "liveSequence": live.sequence,
        "faultSequence": update.catalog.sequence,
        "expiresAt": update.catalog.expires_at.isoformat(),
    }
    if not args.execute:
        return {**plan, "dryRun": "nothing written (pass --execute)"}
    envelope = inline_sign_envelope(
        update.payload, key_pem=args.key.expanduser(), key_id=args.key_id
    )
    write_catalog(
        store,
        Channel.STAGING,
        envelope,
        base_sequence=update.base_sequence,
        keyring=keyring,
        game=GAME,
        now=now,
    )
    return {**plan, "written": True}


# --------------------------------------------------------------------------- CLI


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="VK Cloud remote staging helpers (M5.OTA.4b)")
    sub = parser.add_subparsers(dest="command", required=True)

    def s3(p: argparse.ArgumentParser) -> None:
        p.add_argument("--s3-bucket", required=True)
        p.add_argument("--s3-endpoint", required=True)
        p.add_argument("--s3-region", required=True)
        p.add_argument("--aws-profile")
        p.add_argument("--execute", action="store_true", help="write (default: dry run)")

    def signer(p: argparse.ArgumentParser) -> None:
        p.add_argument("--keyring", type=Path, required=True)
        p.add_argument("--key", type=Path, required=True)
        p.add_argument("--key-id", required=True)

    kg = sub.add_parser("keygen", help="durable tf-staging-* signing key, outside git")
    kg.add_argument("out", type=Path)
    kg.add_argument("--key-id", default=f"tf-staging-{now_utc().year}")
    kg.add_argument("--origin", required=True, help="public https:// origin for the env file")

    ph = sub.add_parser("probe-http", help="anonymous read-only checks of the public origin")
    ph.add_argument("--origin", required=True)
    ph.add_argument("--json-blob", help="64-hex name of a JSON blob")
    ph.add_argument("--png-blob", help="64-hex name of a PNG blob")

    pc = sub.add_parser("probe-conditional", help="does the bucket honour conditional puts?")
    s3(pc)

    fault = sub.add_parser("fault", help="staging catalog fault injection for the client E2E")
    fault_sub = fault.add_subparsers(dest="fault", required=True)
    bad = fault_sub.add_parser("bad-signature")
    s3(bad)
    signer(bad)
    bad.add_argument("--restore-file", type=Path, required=True)
    restore = fault_sub.add_parser("restore")
    s3(restore)
    restore.add_argument("--keyring", type=Path, required=True)
    restore.add_argument("--restore-file", type=Path, required=True)
    expired = fault_sub.add_parser("expired")
    s3(expired)
    signer(expired)
    expired.add_argument("--validity-seconds", type=int, default=120)

    args = parser.parse_args(argv)
    try:
        if args.command == "keygen":
            result: dict[str, Any] = keygen(args.out, args.key_id, args.origin)
        elif args.command == "probe-http":
            result = probe_http(args.origin, args.json_blob, args.png_blob)
        elif args.command == "probe-conditional":
            result = probe_conditional(args)
        elif args.fault == "bad-signature":
            result = fault_bad_signature(args)
        elif args.fault == "restore":
            result = fault_restore(args)
        else:
            result = fault_expired(args)
    except (HelperError, OtaError, OtaPublisherError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, default=str))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
