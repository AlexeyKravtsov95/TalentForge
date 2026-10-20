"""OTA publisher commands (``talent-data ota …``) - M5.OTA.2.

build-release   build a release manifest from the committed data of a git commit
validate        run the publisher gates G1-G8 over a built release
report          the human review diff vs. the active production release
stage           upload blobs + publish a staging catalog (inline staging/test key)
promote         gates -> next production catalog; payload + PAE hash for offline signing
attach-signature  verify an external DER signature and write catalog.json (last)
rollback        new production catalog with an older active release
resign          new production catalog, same content, fresh validity window
verify-live     fetch and verify the live catalog and every listed release
sign            convenience DER signer (staging/test key ids only)
gc              list (and with --execute --confirm, delete) unreferenced blobs

The store is a local directory (``--store DIR``, the default backend) or, with
``--backend s3``, a channel's remote bucket (VK Cloud Object Storage) given by
``--s3-bucket``/``--s3-endpoint``/``--s3-region`` and the credentials of ``--aws-profile``
(or the standard AWS credential chain). The bucket names the channel - the committed
profiles of :mod:`talent_data.ota.s3_channels` (M5.OTA.4b staging, M5.OTA.PROD.1
production): a command aimed at the other channel is refused before connecting, so the
staging bucket is never used as production and neither channel's publisher writes the
other's catalog. The store is opened read-only unless the command writes under
``--execute`` (``gc``: ``--execute --confirm``, and never on production). Production
writes also refuse the ``--now`` rehearsal clock, and the production store re-proves every
catalog itself before the put (``ProductionCatalogGuard``).

Every mutating command is a dry run by default: nothing is written without
``--execute``, and ``gc`` additionally needs ``--confirm`` and prints what it would
delete first. Production signing is external by design: ``promote``/``rollback``/
``resign`` write the unsigned payload and print the SHA-256 of the PAE to sign; the
owner signs offline and ``attach-signature`` verifies the DER signature against the
committed public keyring before the catalog object is replaced - the last write of
the whole pipeline, after blobs are uploaded and verified. ``attach-signature`` never
trusts the earlier commands alone: it re-proves the payload against its pending-meta
SHA-256 pin, the target channel/game, the live succession (sequence + 1, append-only)
and every referenced object before writing. ``stage`` publishes to the staging channel
only; a staging/test-signed catalog can never replace the production one.

The imports that need the ``ota`` extra (``cryptography``, via ``talent_data.ota``)
live inside the handlers, so importing this module - and every other command of the
CLI - keeps working without the extra installed.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import TYPE_CHECKING

from talent_data.games import GAMES
from talent_data.ota.errors import OtaPublisherError

if TYPE_CHECKING:  # only for annotations; runtime imports stay lazy (handlers)
    from talent_data.ota.gates import GateResult, ReleaseUnderTest
    from talent_data.ota.models import Channel, OtaCatalog
    from talent_data.ota.store import BlobStore
    from talent_data.ota.verify import TrustedKey

EXIT_OK = 0
EXIT_FAILURE = 1
DEFAULT_RELEASES_DOCS = Path("docs/ota/releases")
DEFAULT_VALIDITY_DAYS = 45  # OD-13
STANDARD_VALIDATION_NOTE = (
    "talent-data validate + manifest + visuals/tooltips/localization sync checks"
)
BLOB_KEY_PREFIX = "v1/blobs/sha256/"
_TS_FORMAT = "%Y-%m-%dT%H:%M:%SZ"
_TS_HELP = _TS_FORMAT.replace("%", "%%")  # argparse %-formats help strings


def register(sub: argparse._SubParsersAction[argparse.ArgumentParser]) -> None:
    ota = sub.add_parser(
        "ota", help="OTA dataset delivery: build, validate, sign, publish, verify (M5.OTA.2)"
    )
    ota_sub = ota.add_subparsers(dest="ota_command", required=True)

    def game(p: argparse.ArgumentParser) -> None:
        p.add_argument("--game", default="forever", choices=sorted(GAMES))

    def repo(p: argparse.ArgumentParser) -> None:
        p.add_argument("--repo", type=Path, help="repository root (default: cwd)")

    def store_arg(p: argparse.ArgumentParser, mutation: str | None = None) -> None:
        p.add_argument(
            "--backend",
            choices=["local", "s3"],
            default="local",
            help="object store: a local directory (default) or a channel's remote bucket",
        )
        p.add_argument(
            "--store",
            type=Path,
            help="local backend: root directory standing in for the bucket",
        )
        p.add_argument("--s3-bucket", help="s3 backend: bucket name")
        p.add_argument("--s3-endpoint", help="s3 backend: https:// S3 API endpoint")
        p.add_argument("--s3-region", help="s3 backend: region, e.g. ru-msk")
        p.add_argument(
            "--aws-profile",
            help="s3 backend: AWS CLI profile (default: the standard credential chain)",
        )
        # what the command may do to the store, and only under --execute (gc: + --confirm)
        p.set_defaults(store_mutation=mutation)

    def keyring(p: argparse.ArgumentParser) -> None:
        p.add_argument(
            "--keyring",
            type=Path,
            required=True,
            help="public keyring JSON ({'keys': [{keyId, algorithm, publicKeySpki}]})",
        )

    def now_arg(p: argparse.ArgumentParser) -> None:
        p.add_argument("--now", help=f"override the clock, {_TS_HELP} (tests, rehearsals)")

    def channel_arg(p: argparse.ArgumentParser, default: str = "production") -> None:
        p.add_argument("--channel", choices=["production", "staging"], default=default)

    build = ota_sub.add_parser(
        "build-release", help="build a release manifest from committed data at a commit"
    )
    game(build)
    repo(build)
    build.add_argument("--build", required=True, help="client build, e.g. 1.60.1.70450")
    build.add_argument("--revision", type=int, default=1)
    build.add_argument("--commit", required=True, help="40-hex commit whose data/ is published")
    build.add_argument(
        "--created-at",
        help=f"release createdAt, {_TS_HELP} (default: now; explicit for reproducibility)",
    )
    build.add_argument("--validation-note", default=STANDARD_VALIDATION_NOTE)
    build.add_argument("--min-data-level", type=int, default=1)
    build.add_argument("--out", type=Path, default=Path("artifacts/ota"))
    build.set_defaults(handler=_cmd_build_release)

    validate = ota_sub.add_parser("validate", help="run gates G1-G8 over a built release")
    game(validate)
    repo(validate)
    channel_arg(validate)
    store_arg(validate)
    keyring(validate)
    _releases_docs(validate)
    validate.add_argument("--manifest", type=Path, required=True)
    validate.add_argument("--main-ref", default="origin/main", help=argparse.SUPPRESS)
    now_arg(validate)
    validate.set_defaults(handler=_cmd_validate)

    report = ota_sub.add_parser(
        "report", help="human review: what this release changes vs. the active one"
    )
    game(report)
    repo(report)
    store_arg(report)
    keyring(report)
    report.add_argument("--manifest", type=Path, required=True)
    now_arg(report)
    report.set_defaults(handler=_cmd_report)

    stage = ota_sub.add_parser(
        "stage", help="upload blobs and publish a staging catalog (staging/test key)"
    )
    game(stage)
    repo(stage)
    # `stage` is the staging channel, full stop: a staging/test-signed catalog must
    # never be able to replace the production one, so production is not even a choice.
    stage.add_argument("--channel", choices=["staging"], default="staging")
    store_arg(stage, mutation="write")
    keyring(stage)
    stage.add_argument("--manifest", type=Path, required=True)
    stage.add_argument(
        "--key", type=Path, required=True, help="private key PEM (test-/tf-staging-)"
    )
    stage.add_argument("--key-id", required=True)
    stage.add_argument("--execute", action="store_true", help="write (default: dry run)")
    stage.add_argument("--main-ref", default="origin/main", help=argparse.SUPPRESS)
    now_arg(stage)
    stage.set_defaults(handler=_cmd_stage)

    promote = ota_sub.add_parser(
        "promote", help="gates + approval -> next production catalog payload for offline signing"
    )
    game(promote)
    repo(promote)
    store_arg(promote, mutation="write")  # uploads the blobs (the catalog comes later)
    keyring(promote)
    _releases_docs(promote)
    promote.add_argument("--manifest", type=Path, required=True)
    promote.add_argument("--out", type=Path, default=Path("artifacts/ota"))
    promote.add_argument("--execute", action="store_true", help="write (default: dry run)")
    promote.add_argument("--main-ref", default="origin/main", help=argparse.SUPPRESS)
    now_arg(promote)
    promote.set_defaults(handler=_cmd_promote)

    attach = ota_sub.add_parser(
        "attach-signature", help="verify an external DER signature and write catalog.json"
    )
    game(attach)
    repo(attach)
    attach.add_argument("--payload", type=Path, required=True, help="from promote/rollback/resign")
    attach.add_argument("--signature", type=Path, required=True, help="DER signature file")
    attach.add_argument("--key-id", required=True)
    store_arg(attach, mutation="write")
    keyring(attach)
    channel_arg(attach)
    _releases_docs(attach)
    attach.add_argument("--main-ref", default="origin/main", help=argparse.SUPPRESS)
    attach.add_argument("--execute", action="store_true", help="write (default: dry run)")
    now_arg(attach)
    attach.set_defaults(handler=_cmd_attach_signature)

    rollback = ota_sub.add_parser(
        "rollback", help="new production catalog whose active release is an older one"
    )
    game(rollback)
    repo(rollback)
    store_arg(rollback)
    keyring(rollback)
    channel_arg(rollback)
    rollback.add_argument(
        "--active", required=True, help="releaseId to make active (must be listed)"
    )
    rollback.add_argument("--out", type=Path, default=Path("artifacts/ota"))
    rollback.add_argument("--execute", action="store_true", help="write (default: dry run)")
    now_arg(rollback)
    rollback.set_defaults(handler=_cmd_rollback)

    resign = ota_sub.add_parser(
        "resign", help="re-issue the live catalog before expiry (same content, new sequence)"
    )
    game(resign)
    repo(resign)
    store_arg(resign)
    keyring(resign)
    channel_arg(resign)
    resign.add_argument("--validity-days", type=int, default=DEFAULT_VALIDITY_DAYS)
    resign.add_argument("--out", type=Path, default=Path("artifacts/ota"))
    resign.add_argument("--execute", action="store_true", help="write (default: dry run)")
    now_arg(resign)
    resign.set_defaults(handler=_cmd_resign)

    verify = ota_sub.add_parser(
        "verify-live", help="verify the live catalog and every release it lists"
    )
    game(verify)
    repo(verify)
    store_arg(verify)
    keyring(verify)
    channel_arg(verify)
    now_arg(verify)
    verify.set_defaults(handler=_cmd_verify_live)

    sign = ota_sub.add_parser(
        "sign", help="convenience DER signer over the payload PAE (staging/test key ids only)"
    )
    repo(sign)
    sign.add_argument("--key", type=Path, required=True, help="private key PEM")
    sign.add_argument("--key-id", required=True)
    sign.add_argument("--payload", type=Path, required=True)
    sign.add_argument("--out", type=Path, required=True, help="DER signature file to write")
    sign.set_defaults(handler=_cmd_sign)

    gc = ota_sub.add_parser("gc", help="list (and delete) blobs no listed release references")
    game(gc)
    repo(gc)
    store_arg(gc, mutation="delete")
    keyring(gc)
    gc.add_argument("--execute", action="store_true", help="delete (default: dry run)")
    gc.add_argument("--confirm", action="store_true", help="required together with --execute")
    now_arg(gc)
    gc.set_defaults(handler=_cmd_gc)


def _releases_docs(p: argparse.ArgumentParser) -> None:
    p.add_argument(
        "--releases-docs",
        type=Path,
        default=DEFAULT_RELEASES_DOCS,
        help="approval records directory (OD-12; default: docs/ota/releases)",
    )


# --------------------------------------------------------------------------- helpers


def _instant(text: str | None) -> datetime:
    if text is None:
        return datetime.now(UTC)
    parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise OtaPublisherError(f"--now needs a UTC instant ({_TS_FORMAT}), got {text!r}")
    return parsed.astimezone(UTC)


def _repo_root(args: argparse.Namespace) -> Path:
    from talent_data import paths

    return args.repo.expanduser().resolve() if args.repo else paths.project_root()


def _store(args: argparse.Namespace) -> BlobStore:
    """The store the command works on, opened no more writable than it needs.

    The remote (s3) store is staging-only and read-only unless this command mutates it
    under ``--execute`` (``write``) or ``--execute --confirm`` (``delete``, gc only).
    """
    if args.backend == "local":
        if args.store is None:
            raise OtaPublisherError("--store DIR is required with the local backend")
        from talent_data.ota.store import LocalDirectoryStore

        return LocalDirectoryStore(args.store.expanduser().resolve())

    missing = [
        flag
        for flag, value in (
            ("--s3-bucket", args.s3_bucket),
            ("--s3-endpoint", args.s3_endpoint),
            ("--s3-region", args.s3_region),
        )
        if not value
    ]
    if missing:
        raise OtaPublisherError(f"the s3 backend needs {', '.join(missing)}")
    if args.store is not None:
        raise OtaPublisherError("--store is for the local backend; the s3 backend takes --s3-*")
    from talent_data.ota.models import Channel
    from talent_data.ota.s3_channels import profile_of_bucket

    # the bucket names the channel (committed profiles): a command aimed at the other
    # channel is refused before any connection - the production publisher never writes
    # the staging catalog, and the staging bucket is never used as production
    profile = profile_of_bucket(args.s3_bucket)
    target = _target_channel(args)
    if target is not None and target != profile.channel.value:
        raise OtaPublisherError(
            f"--s3-bucket {args.s3_bucket} is the {profile.channel.value} bucket; refusing it "
            f"for the {target} channel"
        )
    execute = bool(getattr(args, "execute", False))
    mutation = args.store_mutation
    writable = execute and mutation in ("write", "delete")
    allow_delete = execute and mutation == "delete" and bool(getattr(args, "confirm", False))
    if profile.channel is Channel.PRODUCTION:
        if allow_delete:
            raise OtaPublisherError(
                "production blobs are never deleted by this tool: gc lists them only"
            )
        writable = execute and mutation == "write"  # gc never gets a writable production store
        if writable and args.now is not None:
            raise OtaPublisherError(
                "--now is a rehearsal clock; production writes run on the wall clock only"
            )
    from talent_data.ota.s3_store import (
        ProductionCatalogGuard,
        S3Location,
        check_credentials,
        connect,
    )

    location = S3Location(
        bucket=args.s3_bucket,
        endpoint_url=args.s3_endpoint,
        region=args.s3_region,
        profile=args.aws_profile,
        channel=profile.channel,
    )
    # the channel's own AWS CLI profile, and only it, before anything else happens
    check_credentials(location, writable=writable)
    guard = None
    if writable and profile.channel is Channel.PRODUCTION:
        guard = ProductionCatalogGuard(_keyring(args), game=args.game)
    return connect(
        location,
        writable=writable,
        allow_delete=allow_delete,
        catalog_guard=guard,
    )


def _target_channel(args: argparse.Namespace) -> str | None:
    """The channel a command reads or writes; ``None`` for the channel-neutral ones.

    ``report`` (a review diff, read-only) and ``gc`` (both catalogs; never deletes on
    production) run against either bucket.
    """
    if args.ota_command == "stage":
        return "staging"
    if args.ota_command == "promote":
        return "production"
    channel: str | None = getattr(args, "channel", None)
    return channel


def _store_flags(args: argparse.Namespace) -> str:
    """The store flags of this invocation, for the follow-up commands a step prints."""
    if args.backend == "local":
        return f"--store {args.store}"
    profile = f" --aws-profile {args.aws_profile}" if args.aws_profile else ""
    return (
        f"--backend s3 --s3-bucket {args.s3_bucket} --s3-endpoint {args.s3_endpoint} "
        f"--s3-region {args.s3_region}{profile}"
    )


def _keyring(args: argparse.Namespace) -> dict[str, TrustedKey]:
    from talent_data.ota.signing import load_keyring_file

    return load_keyring_file(args.keyring.expanduser())


def _live(
    args: argparse.Namespace,
    store: BlobStore,
    channel: str,
    now: datetime,
    *,
    allow_expired: bool = False,
) -> OtaCatalog | None:
    from talent_data.ota.models import Channel
    from talent_data.ota.store import read_live_catalog

    return read_live_catalog(
        store,
        Channel(channel),
        keyring=_keyring(args),
        game=args.game,
        now=now,
        allow_expired=allow_expired,
    )


def _blob_key(sha_ref: str) -> str:
    return BLOB_KEY_PREFIX + sha_ref.removeprefix("sha256:")


def _print_gates(results: list[GateResult]) -> tuple[list[GateResult], list[GateResult]]:
    from talent_data.ota.gates import GateStatus

    stopped = [r for r in results if r.status is GateStatus.STOP]
    flagged = [r for r in results if r.status is GateStatus.FLAG]
    for r in results:
        print(f"  {r.gate.value} {r.status.value:<5} {r.detail}")
    if stopped:
        print(
            f"STOP by {', '.join(r.gate.value for r in stopped)}: no approval, flag or "
            "configuration can pass a STOP gate",
            file=sys.stderr,
        )
    elif flagged:
        print(
            f"FLAG by {', '.join(r.gate.value for r in flagged)}: promote needs an approval "
            f"record covering them ({DEFAULT_RELEASES_DOCS}/<releaseId>.md, OD-12)"
        )
    return stopped, flagged


def _pending_paths(out: Path, channel: str) -> tuple[Path, Path]:
    payload = out / f"pending-{channel}.payload.json"
    return payload, payload.with_name(payload.name + ".meta.json")


def _pae_path(payload_path: Path) -> Path:
    """The exact PAE bytes an offline signer signs (``openssl dgst -sha256 -sign``)."""
    return payload_path.with_name(payload_path.name.removesuffix(".payload.json") + ".pae.bin")


@dataclass(frozen=True)
class PendingMeta:
    """What ``promote``/``rollback``/``resign`` produced, pinned for attach time."""

    base_sequence: int | None
    payload_sha256: str


def _write_pending(out: Path, channel: str, payload: bytes, base_sequence: int | None) -> Path:
    import json

    from talent_data.ota.models import CATALOG_PAYLOAD_TYPE
    from talent_data.ota.verify import pae, sha256_ref

    payload_path, meta_path = _pending_paths(out, channel)
    payload_path.parent.mkdir(parents=True, exist_ok=True)
    payload_path.write_bytes(payload)
    # what the offline signer signs; its SHA-256 is the printed "PAE SHA-256"
    _pae_path(payload_path).write_bytes(pae(CATALOG_PAYLOAD_TYPE, payload))
    meta_path.write_text(
        json.dumps(
            {
                "baseSequence": base_sequence,
                "payloadSha256": sha256_ref(payload),
                "channel": channel,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return payload_path


def _pending_meta(payload_path: Path) -> PendingMeta:
    import json

    meta_path = payload_path.with_name(payload_path.name + ".meta.json")
    try:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise OtaPublisherError(
            f"{payload_path} has no readable pending meta (produced by promote/rollback/"
            f"resign); cannot attach a signature to unknown bytes: {exc}"
        ) from exc
    return PendingMeta(
        base_sequence=meta.get("baseSequence"), payload_sha256=meta.get("payloadSha256", "")
    )


def _all_upload_blobs(under_test: ReleaseUnderTest) -> dict[str, bytes]:
    sha, data = under_test.rebuild.manifest_blob
    return {sha: data, **under_test.rebuild.blobs}


def _missing_blob_count(store: BlobStore, blobs: dict[str, bytes]) -> int:
    return sum(1 for sha in blobs if not store.exists(_blob_key(sha)))


# --------------------------------------------------------------------------- commands


def _cmd_build_release(args: argparse.Namespace) -> int:
    from talent_data.ota.release import build_release
    from talent_data.ota.verify import sha256_ref

    artifact = build_release(
        _repo_root(args),
        args.commit,
        args.game,
        args.build,
        args.revision,
        _instant(args.created_at),
        args.validation_note,
        min_data_level=args.min_data_level,
    )
    out = args.out / artifact.release_id / "release.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(artifact.manifest_bytes)
    print(f"release {artifact.release_id} built from commit {args.commit}")
    print(
        f"  manifest: {out} ({len(artifact.manifest_bytes)} bytes, "
        f"{sha256_ref(artifact.manifest_bytes)})"
    )
    print(f"  files: {len(artifact.manifest.files)}, distinct blobs: {len(artifact.blobs)}")
    print(f"  total download: {artifact.manifest.total_byte_length:,} bytes")
    print("  deterministic: rebuilding with the same arguments reproduces these bytes (G8)")
    return EXIT_OK


def _cmd_validate(args: argparse.Namespace) -> int:
    from talent_data.ota.gates import load_approval_record, load_under_test, validate_release
    from talent_data.ota.models import Channel

    now = _instant(args.now)
    store = _store(args)
    under_test = load_under_test(args.manifest.expanduser(), _repo_root(args))
    approval = load_approval_record(under_test.manifest.release_id, args.releases_docs.expanduser())
    results = validate_release(
        under_test,
        repo_root=_repo_root(args),
        store=store,
        live=_live(args, store, "production", now),
        channel=Channel(args.channel),
        now=now,
        approval=approval,
        main_ref=args.main_ref,
    )
    print(f"gates for {under_test.manifest.release_id} (channel {args.channel}):")
    stopped, _ = _print_gates(results)
    return EXIT_FAILURE if stopped else EXIT_OK


def _cmd_report(args: argparse.Namespace) -> int:
    from talent_data.ota.gates import load_under_test
    from talent_data.ota.report import render_review_report

    now = _instant(args.now)
    store = _store(args)
    under_test = load_under_test(args.manifest.expanduser(), _repo_root(args))
    sys.stdout.write(render_review_report(under_test, _live(args, store, "production", now), store))
    return EXIT_OK


def _cmd_stage(args: argparse.Namespace) -> int:
    from talent_data.ota.catalog import catalog_entry, next_catalog
    from talent_data.ota.gates import load_under_test, validate_release
    from talent_data.ota.models import Channel
    from talent_data.ota.signing import inline_sign_envelope
    from talent_data.ota.store import ensure_catalog_references, upload_blobs, write_catalog

    if args.channel != "staging":  # parser allows only staging; guarded again here
        print(
            "error: stage publishes to the staging channel only; production catalogs are "
            "created by promote + attach-signature with a production key",
            file=sys.stderr,
        )
        return EXIT_FAILURE
    now = _instant(args.now)
    store = _store(args)
    under_test = load_under_test(args.manifest.expanduser(), _repo_root(args))
    results = validate_release(
        under_test,
        repo_root=_repo_root(args),
        store=store,
        live=_live(args, store, "production", now),
        channel=Channel("staging"),
        now=now,
        main_ref=args.main_ref,
    )
    print(f"gates for {under_test.manifest.release_id} (staging):")
    stopped, flagged = _print_gates(results)
    if stopped:
        return EXIT_FAILURE
    if flagged:
        print("  (staging may carry flags; only promote requires an approval record)")

    channel = Channel.STAGING
    live_staging = _live(args, store, "staging", now)
    entry = catalog_entry(under_test.rebuild.manifest_bytes, under_test.manifest, published_at=now)
    update = next_catalog(
        live_staging,
        game=args.game,
        channel=channel,
        active_release_id=under_test.manifest.release_id,
        append=entry,
        issued_at=now,
    )
    blobs = _all_upload_blobs(under_test)
    print(
        f"staging catalog: sequence "
        f"{0 if live_staging is None else live_staging.sequence} -> {update.catalog.sequence}, "
        f"active {update.catalog.active_release_id}, {len(update.catalog.releases)} release(s)"
    )
    print(f"  blobs to upload: {_missing_blob_count(store, blobs)} of {len(blobs)}")
    if not args.execute:
        print("dry run: nothing written (pass --execute)")
        return EXIT_OK
    written = upload_blobs(store, blobs)
    ensure_catalog_references(store, update.catalog)
    envelope = inline_sign_envelope(
        update.payload, key_pem=args.key.expanduser(), key_id=args.key_id
    )
    write_catalog(
        store,
        channel,
        envelope,
        base_sequence=update.base_sequence,
        keyring=_keyring(args),
        game=args.game,
        now=now,
    )
    print(f"  uploaded {len(written)} blob(s); staging catalog written (signed by {args.key_id})")
    return EXIT_OK


def _require_approval(
    flagged: list[GateResult], release_id: str, releases_docs: Path
) -> int | None:
    from talent_data.ota.gates import load_approval_record

    if not flagged:
        return None
    record = load_approval_record(release_id, releases_docs.expanduser())
    if record is None:
        print(
            f"error: publication refused - gates "
            f"{', '.join(g.gate.value for g in flagged)} flagged and no approval "
            f"record exists at {releases_docs}/{release_id}.md",
            file=sys.stderr,
        )
        return EXIT_FAILURE
    for result in flagged:
        if not record.accepts(result.gate):
            print(
                f"error: publication refused - the approval record does not accept "
                f"{result.gate.value}",
                file=sys.stderr,
            )
            return EXIT_FAILURE
    print(f"  approval record accepted: {', '.join(g.gate.value for g in flagged)}")
    return None


def _enforce_gates_and_approval(
    args: argparse.Namespace,
    under_test: ReleaseUnderTest,
    store: BlobStore,
    live: OtaCatalog | None,
    now: datetime,
    *,
    channel: Channel,
) -> int | None:
    """G1-G8 + approval records for one release about to be published.

    The single enforcement path shared by ``promote`` (before the payload is even
    produced) and ``attach-signature`` (re-proved from the store before the write, so
    no editable local file can smuggle a release past the gates). Returns
    ``EXIT_FAILURE`` when the release must not be published, ``None`` when it may.
    """
    from talent_data.ota.gates import load_approval_record, validate_release
    from talent_data.ota.verify import sha256_ref

    release_id = under_test.manifest.release_id
    approval = load_approval_record(release_id, args.releases_docs.expanduser())
    manifest_sha = sha256_ref(under_test.manifest_bytes)
    if (
        approval is not None
        and approval.manifest_sha256 is not None
        and approval.manifest_sha256 != manifest_sha
    ):
        print(
            "error: publication refused - the approval record pins "
            f"{approval.manifest_sha256} but this release's manifest is {manifest_sha}",
            file=sys.stderr,
        )
        return EXIT_FAILURE
    results = validate_release(
        under_test,
        repo_root=_repo_root(args),
        store=store,
        live=live,
        channel=channel,
        now=now,
        approval=approval,
        main_ref=args.main_ref,
    )
    print(f"gates for {release_id} ({channel.value}):")
    stopped, flagged = _print_gates(results)
    if stopped:
        return EXIT_FAILURE
    return _require_approval(flagged, release_id, args.releases_docs)


def _cmd_promote(args: argparse.Namespace) -> int:
    from talent_data.ota.catalog import catalog_entry, next_catalog
    from talent_data.ota.gates import load_under_test
    from talent_data.ota.models import Channel
    from talent_data.ota.signing import pae_sha256
    from talent_data.ota.store import ensure_catalog_references, upload_blobs

    now = _instant(args.now)
    store = _store(args)
    under_test = load_under_test(args.manifest.expanduser(), _repo_root(args))
    live = _live(args, store, "production", now)
    refused = _enforce_gates_and_approval(
        args, under_test, store, live, now, channel=Channel("production")
    )
    if refused is not None:
        return refused

    entry = catalog_entry(under_test.rebuild.manifest_bytes, under_test.manifest, published_at=now)
    update = next_catalog(
        live,
        game=args.game,
        channel=Channel("production"),
        active_release_id=under_test.manifest.release_id,
        append=entry,
        issued_at=now,
    )
    blobs = _all_upload_blobs(under_test)
    print(
        f"production catalog: sequence "
        f"{0 if live is None else live.sequence} -> {update.catalog.sequence}, "
        f"active {update.catalog.active_release_id}, {len(update.catalog.releases)} release(s)"
    )
    print(f"  blobs to upload: {_missing_blob_count(store, blobs)} of {len(blobs)}")
    print(f"  PAE SHA-256 to sign offline: {pae_sha256(update.payload)}")
    if not args.execute:
        print("dry run: nothing written (pass --execute)")
        return EXIT_OK
    written = upload_blobs(store, blobs)
    ensure_catalog_references(store, update.catalog)
    payload_path = _write_pending(args.out, "production", update.payload, update.base_sequence)
    print(f"  uploaded {len(written)} blob(s); unsigned payload: {payload_path}")
    print(
        f"  sign {_pae_path(payload_path)} offline with the production key, then run: "
        "talent-data ota attach-signature "
        f"--payload {payload_path} --signature <der> --key-id <id> "
        f"{_store_flags(args)} --keyring {args.keyring} --execute"
    )
    return EXIT_OK


def _cmd_attach_signature(args: argparse.Namespace) -> int:
    """Verify an external signature and write ``catalog.json`` - the last write.

    The signature alone is not enough to write. Before the catalog object is replaced,
    the whole publication is re-proved from the signed bytes themselves, without
    trusting that ``promote``/``rollback``/``resign`` ran or that their checks still
    hold:

    * the DER signature verifies over the PAE with a keyring key (identity of issuer);
    * the payload is a catalog of the target ``--channel`` and ``--game`` (a staging
      payload can never land on the production path, or vice versa) and is not already
      expired;
    * it is a *valid successor* of the live catalog: sequence is exactly live + 1 and
      every live release stays listed with identical members (append-only);
    * every object it references exists in the store and hashes to its pin
      (:func:`talent_data.ota.store.ensure_catalog_references`), and each *newly
      appended* release additionally passes the content contracts
      (``verify_release_manifest`` + ``verify_release_files`` over the store blobs -
      what clients will actually download);
    * every newly appended release re-runs gates G1-G8 with its approval records,
      rebuilt from the store manifest and the provenance commit - the pending meta is
      a local, editable file, so a catalog whose payload and meta were both rewritten
      around a release that never passed ``promote`` is still stopped here, G6 above
      all. A pure rollback/re-sign adds no release and needs no gate re-run: signature,
      integrity and succession are its whole proof;
    * the sequence guard in :func:`write_catalog` then re-reads the live catalog one
      final time before the single atomic put.
    """
    from talent_data.ota.catalog import ensure_valid_succession
    from talent_data.ota.gates import under_test_from_bytes
    from talent_data.ota.models import Channel, OtaCatalog, ReleaseFile
    from talent_data.ota.signing import build_envelope, verify_der_signature
    from talent_data.ota.store import (
        blob_key,
        ensure_catalog_references,
        read_release_manifest,
        write_catalog,
    )
    from talent_data.ota.verify import (
        parse_strict,
        sha256_ref,
        verify_release_files,
        verify_release_manifest,
    )

    now = _instant(args.now)
    store = _store(args)
    payload_path = args.payload.expanduser()
    payload = payload_path.read_bytes()
    der = args.signature.expanduser().read_bytes()
    keyring = _keyring(args)
    channel = Channel(args.channel)

    def refuse(detail: str) -> int:
        print(f"error: {detail}; nothing was written", file=sys.stderr)
        return EXIT_FAILURE

    if args.key_id not in keyring:
        return refuse(f"key id {args.key_id!r} is not in {args.keyring}")
    if not verify_der_signature(keyring[args.key_id].public_key, der, payload):
        return refuse(f"signature does not verify over PAE(payloadType, payload of {payload_path})")
    meta = _pending_meta(payload_path)
    if meta.payload_sha256 != sha256_ref(payload):
        return refuse(
            f"{payload_path} is not the payload its pending meta pins "
            f"({sha256_ref(payload)} != {meta.payload_sha256}); re-run the pending command"
        )
    try:
        catalog = parse_strict(OtaCatalog, payload)
    except ValueError as exc:
        return refuse(f"{payload_path} does not parse as a catalog: {exc}")
    if catalog.channel is not channel:
        return refuse(
            f"the signed payload is a {catalog.channel.value} catalog; refusing to write it "
            f"to the {channel.value} channel"
        )
    if catalog.game != args.game:
        return refuse(f"the signed payload is for game {catalog.game!r}, not {args.game!r}")
    if catalog.expires_at <= now:
        return refuse(
            f"the signed payload is already expired at {catalog.expires_at.isoformat()}; "
            "re-issue it (promote/rollback/resign) and sign again"
        )
    live = _live(args, store, args.channel, now, allow_expired=True)
    try:
        ensure_valid_succession(live, catalog)
        ensure_catalog_references(store, catalog)
    except OtaPublisherError as exc:
        return refuse(str(exc))

    # ---- releases the catalog appends: prove them again, from the store + the repo.
    # The pending meta is editable local state; the gates are not advisory.
    live_ids = set() if live is None else {entry.release_id for entry in live.releases}
    appended = [entry for entry in catalog.releases if entry.release_id not in live_ids]
    for entry in appended:
        try:
            manifest_bytes, manifest = read_release_manifest(store, entry)  # pin-verified
            verify_release_manifest(
                manifest_bytes,
                entry,
                game=catalog.game,
                supported_data_level=entry.min_data_level,
            )

            def read_file(file: ReleaseFile) -> bytes:
                return store.get(blob_key(file.sha256))

            verify_release_files(manifest, read_file)
            under_test = under_test_from_bytes(
                manifest_bytes, _repo_root(args), origin=Path(blob_key(entry.manifest_sha256))
            )
        except (OtaPublisherError, ValueError) as exc:
            return refuse(f"appended release {entry.release_id} failed verification: {exc}")
        print(
            f"  appended release {entry.release_id}: content re-verified from the store; "
            "re-running gates G1-G8"
        )
        refused = _enforce_gates_and_approval(args, under_test, store, live, now, channel=channel)
        if refused is not None:
            return refused

    envelope = build_envelope(payload, {args.key_id: der})
    print(
        f"signature by {args.key_id} verified: {args.channel} catalog sequence "
        f"{catalog.sequence}, active {catalog.active_release_id}, "
        f"{len(catalog.releases)} release(s), envelope {len(envelope)} bytes; "
        "succession, channel and every referenced object re-checked"
        + (
            f"; {len(appended)} appended release(s) re-passed G1-G8"
            if appended
            else "; no release appended (rollback/re-sign)"
        )
    )
    if not args.execute:
        print("dry run: catalog.json not written (pass --execute)")
        return EXIT_OK
    write_catalog(
        store,
        channel,
        envelope,
        base_sequence=meta.base_sequence,
        keyring=keyring,
        game=catalog.game,
        now=now,
        allow_expired_live=True,  # recovery: a lapsed live catalog may be re-issued
    )
    print(f"  catalog.json written to {store!r} (channel {args.channel})")
    return EXIT_OK


def _cmd_rollback(args: argparse.Namespace) -> int:
    from talent_data.ota.catalog import find_release, rollback_catalog
    from talent_data.ota.signing import pae_sha256
    from talent_data.ota.store import ensure_catalog_references

    now = _instant(args.now)
    store = _store(args)
    # recovery: a lapsed catalog can still be rolled back (its signature, structure
    # and identity are verified; only the expired window is forgiven for the issuer)
    live = _live(args, store, args.channel, now, allow_expired=True)
    if live is None:
        print(f"error: no live {args.channel} catalog to roll back", file=sys.stderr)
        return EXIT_FAILURE
    if find_release(live, args.active) is None:
        print(
            f"error: {args.active!r} is not listed in the live catalog "
            f"({[r.release_id for r in live.releases]}); rollback cannot unlist releases",
            file=sys.stderr,
        )
        return EXIT_FAILURE
    try:
        update = rollback_catalog(live, active_release_id=args.active, issued_at=now)
    except OtaPublisherError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_FAILURE
    print(
        f"rollback: sequence {live.sequence} -> {update.catalog.sequence}, "
        f"active {live.active_release_id} -> {args.active}, "
        f"{len(update.catalog.releases)} release(s) stay listed"
    )
    print(f"  PAE SHA-256 to sign offline: {pae_sha256(update.payload)}")
    if not args.execute:
        print("dry run: nothing written (pass --execute)")
        return EXIT_OK
    ensure_catalog_references(store, update.catalog)
    payload_path = _write_pending(args.out, args.channel, update.payload, update.base_sequence)
    print(f"  unsigned payload: {payload_path}")
    print(
        f"  sign {_pae_path(payload_path)} offline with the {args.channel} key, then run: "
        "talent-data ota attach-signature "
        f"--payload {payload_path} --signature <der> --key-id <id> "
        f"{_store_flags(args)} --keyring {args.keyring} --channel {args.channel} --execute"
    )
    return EXIT_OK


def _cmd_resign(args: argparse.Namespace) -> int:
    from datetime import timedelta

    from talent_data.ota.catalog import resign_catalog
    from talent_data.ota.signing import pae_sha256
    from talent_data.ota.store import ensure_catalog_references

    now = _instant(args.now)
    store = _store(args)
    # recovery: this is the command that revives a catalog whose expiresAt lapsed -
    # the live one is still signature-verified, only the expired window is forgiven
    live = _live(args, store, args.channel, now, allow_expired=True)
    if live is None:
        print(f"error: no live {args.channel} catalog to re-sign", file=sys.stderr)
        return EXIT_FAILURE
    try:
        update = resign_catalog(live, issued_at=now, validity=timedelta(days=args.validity_days))
    except OtaPublisherError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_FAILURE
    print(
        f"re-sign: sequence {live.sequence} -> {update.catalog.sequence}, active "
        f"{update.catalog.active_release_id} unchanged, {len(update.catalog.releases)} "
        "release(s) listed, "
        f"valid until {update.catalog.expires_at:%Y-%m-%dT%H:%M:%SZ}"
    )
    print(f"  PAE SHA-256 to sign offline: {pae_sha256(update.payload)}")
    if not args.execute:
        print("dry run: nothing written (pass --execute)")
        return EXIT_OK
    ensure_catalog_references(store, update.catalog)
    payload_path = _write_pending(args.out, args.channel, update.payload, update.base_sequence)
    print(f"  unsigned payload: {payload_path}")
    print(
        f"  sign {_pae_path(payload_path)} offline with the {args.channel} key, then run: "
        "talent-data ota attach-signature "
        f"--payload {payload_path} --signature <der> --key-id <id> "
        f"{_store_flags(args)} --keyring {args.keyring} --channel {args.channel} --execute"
    )
    return EXIT_OK


def _cmd_verify_live(args: argparse.Namespace) -> int:
    from talent_data.ota.models import MAX_CATALOG_BYTES, Channel, ReleaseFile
    from talent_data.ota.store import catalog_key
    from talent_data.ota.verify import (
        OtaError,
        verify_catalog,
        verify_release_files,
        verify_release_manifest,
    )

    now = _instant(args.now)
    store = _store(args)
    channel = Channel(args.channel)
    key = catalog_key(channel)
    try:
        envelope = store.get(key)
    except OtaPublisherError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_FAILURE
    if len(envelope) > MAX_CATALOG_BYTES:
        print(
            f"FAIL {key}: {len(envelope)} bytes exceed the 1 MiB catalog limit",
            file=sys.stderr,
        )
        return EXIT_FAILURE
    try:
        verified = verify_catalog(
            envelope,
            keyring=_keyring(args),
            game=args.game,
            channel=channel,
            now=now,
            last_seen=None,
        )
    except OtaError as exc:
        print(f"FAIL {key}: {exc.code.value}: {exc.detail}", file=sys.stderr)
        return EXIT_FAILURE
    catalog = verified.catalog
    print(
        f"OK   {key}: sequence {catalog.sequence}, active {catalog.active_release_id}, "
        f"{len(catalog.releases)} release(s), payload {verified.payload_sha256}"
    )
    failures = 0
    for entry in catalog.releases:
        try:
            manifest_blob = store.get(_blob_key(entry.manifest_sha256))
            manifest = verify_release_manifest(
                manifest_blob, entry, game=args.game, supported_data_level=entry.min_data_level
            )

            def read_file(file: ReleaseFile) -> bytes:
                return store.get(_blob_key(file.sha256))

            verify_release_files(manifest, read_file)
        except (OtaError, OtaPublisherError) as exc:
            detail = f"{exc.code.value}: {exc.detail}" if isinstance(exc, OtaError) else str(exc)
            print(f"FAIL {entry.release_id}: {detail}", file=sys.stderr)
            failures += 1
            continue
        print(
            f"OK   {entry.release_id}: {len(manifest.files)} files, "
            f"{manifest.total_byte_length:,} bytes verified"
        )
    print(f"{len(catalog.releases) - failures}/{len(catalog.releases)} listed release(s) verified")
    return EXIT_FAILURE if failures else EXIT_OK


def _cmd_sign(args: argparse.Namespace) -> int:
    from talent_data.ota.signing import INLINE_SIGNING_PREFIXES, load_private_key, sign_pae_der

    if not args.key_id.startswith(INLINE_SIGNING_PREFIXES):
        print(
            f"error: key id {args.key_id!r} may not be loaded by this tool; inline signing is "
            "for test-/tf-staging- keys only (production signs externally)",
            file=sys.stderr,
        )
        return EXIT_FAILURE
    der = sign_pae_der(
        load_private_key(args.key.expanduser()), args.payload.expanduser().read_bytes()
    )
    out = args.out.expanduser()
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(der)
    print(f"wrote DER signature {out} ({len(der)} bytes, key {args.key_id})")
    return EXIT_OK


def _cmd_gc(args: argparse.Namespace) -> int:
    from talent_data.ota.models import Channel
    from talent_data.ota.store import catalog_key, garbage_blob_keys, read_live_catalog

    now = _instant(args.now)
    store = _store(args)
    catalogs = {}
    for channel in (Channel.PRODUCTION, Channel.STAGING):
        live = read_live_catalog(store, channel, keyring=_keyring(args), game=args.game, now=now)
        if live is not None:
            catalogs[channel] = live
    referenced, garbage = garbage_blob_keys(store, catalogs)
    print(f"referenced blobs (all listed releases of both channels): {len(referenced)}")
    print(f"unreferenced blobs: {len(garbage)}")
    for key in garbage:
        print(f"  would delete: {key}")
    if not (args.execute and args.confirm):
        print("dry run: nothing deleted (gc needs --execute AND --confirm)")
        return EXIT_OK
    for key in garbage:
        store.delete(key)
    print(
        f"deleted {len(garbage)} unreferenced blob(s); {catalog_key(Channel.PRODUCTION)} untouched"
    )
    return EXIT_OK
