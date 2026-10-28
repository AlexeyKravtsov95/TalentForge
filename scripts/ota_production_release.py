"""Publish a prepared OTA release package to production - the owner's helper (M5.OTA.PROD.4).

Wraps the existing publisher (`talent-data ota promote / attach-signature / verify-live`) for
one package built by the "OTA production release package" workflow
(scripts/ota_release_package.py). Every check of the publisher runs unchanged - gates G1-G8,
the approval record and its pin, the production keyring and catalog guard, append-only and
sequence succession, blob integrity, the signature, the read-back - and the helper adds its own
around them:

  check    read-only: the package is intact and is what build-release produces at its commit;
           its staging binding re-proves from the copies inside it; the approval record merged
           on origin/main pins this manifest; staging started from the build live clients run;
           `ota promote` (dry run) and `ota report` against live production
  prepare  the same, then with --execute `ota promote --execute`: uploads the immutable blobs
           (verified by read-back; invisible to clients - no catalog references them yet) and
           writes the unsigned payload + the exact PAE bytes to sign offline into publish/
  publish  with --signature <der> --key-id <id>: the DER signature is verified against the
           committed public keyring, the payload must still succeed the live catalog, then with
           --execute `ota attach-signature --execute` writes catalog.json - the activation, the
           only step that makes the release visible to clients - and the outcome is re-read
  verify   `ota verify-live` and the read-only production preflight (published, keyring)

Dry run is the default: `prepare` and `publish` mutate nothing without --execute. The private
signing key is never read: signing is the owner's offline step (openssl, printed by `prepare`).
Credentials are the AWS CLI profile talentforge-production only.

Safe repetition:
  * an interrupted `prepare --execute` is re-run as is: present blobs are skipped, the catalog
    was not touched;
  * a prepared payload is never replaced silently: `prepare` keeps it while it still succeeds
    the live catalog, and refuses a stale one unless --replace (which needs a new signature);
  * `publish` after a successful publication writes nothing (the live payload is this one);
  * a payload whose base is no longer live is refused before any write: the catalog changed,
    prepare a new payload and sign it again;
  * after the write the live catalog is re-read: this payload (PUBLISHED), unchanged (NOT
    WRITTEN: the same signature may be retried), or anything else (exit 3, AMBIGUOUS: another
    publisher raced or the outcome is unknown - stop and follow the runbook).

One publisher at a time: `prepare --execute` and `publish` hold an exclusive lock on the
package's publish/ directory; across machines the rule is the runbook's (VK Cloud ignores
conditional writes; the sequence re-read and the read-back are the remaining guards).

    python scripts/ota_production_release.py check   <package>
    python scripts/ota_production_release.py prepare <package> [--execute] [--replace]
    python scripts/ota_production_release.py publish <package> --signature <der> \\
        --key-id tf-prod-2026a [--execute]
    python scripts/ota_production_release.py verify  <package>

Exit codes: 0 done / ready / already published, 1 refused (nothing written by this step),
2 usage, 3 AMBIGUOUS (a write's outcome needs the owner's attention). Owner runbook:
docs/ota/production-release.md.
"""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import fcntl
import json
import shutil
import sys
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from talent_data.cli.main import main as talent_data  # noqa: E402
from talent_data.ota import release_package as rp  # noqa: E402
from talent_data.ota.errors import OtaPublisherError  # noqa: E402
from talent_data.ota.gates import load_approval_record  # noqa: E402
from talent_data.ota.models import (  # noqa: E402
    CATALOG_PAYLOAD_TYPE,
    Channel,
    OtaCatalog,
    SignedEnvelope,
)
from talent_data.ota.s3_channels import PRODUCTION_PROFILE  # noqa: E402
from talent_data.ota.signing import (  # noqa: E402
    load_keyring_file,
    pae_sha256,
    verify_der_signature,
)
from talent_data.ota.store import BlobStore, catalog_key, verify_live_envelope  # noqa: E402
from talent_data.ota.verify import (  # noqa: E402
    TrustedKey,
    pae,
    parse_strict,
    sha256_ref,
    strict_b64decode,
)

EXIT_OK, EXIT_REFUSED, EXIT_USAGE, EXIT_AMBIGUOUS = 0, 1, 2, 3
REPOSITORY = "AlexeyKravtsov95/TalentForge"
MAIN_REF = "origin/main"
PRODUCTION_KEYRING = ROOT / "docs/ota/keys/production-keyring.json"
PRODUCTION_KEY_PREFIX = "tf-prod-"
PAYLOAD = "pending-production.payload.json"
META = PAYLOAD + ".meta.json"
PAE = "pending-production.pae.bin"
SIGNATURE = "pending-production.sig.der"
LOCK = "publisher.lock"
JOURNAL = "journal.jsonl"


class Refused(Exception):
    """This step must not go on; nothing was written by it."""


class Ambiguous(Exception):
    """A write happened or may have happened, and the live catalog is not this publication."""


@dataclass(frozen=True)
class Target:
    """Where the helper publishes: production (S3, the committed profile), or a local store
    standing in for it (rehearsals and tests: ``--store DIR``)."""

    store: Path | None
    keyring_path: Path
    now: str | None

    @property
    def production(self) -> bool:
        return self.store is None

    def flags(self) -> list[str]:
        if self.store is not None:
            return ["--store", str(self.store)]
        p = PRODUCTION_PROFILE
        return [
            "--backend",
            "s3",
            "--s3-bucket",
            p.bucket,
            "--s3-endpoint",
            p.endpoint_url,
            "--s3-region",
            p.region,
            "--aws-profile",
            p.aws_profile,
        ]

    def clock(self) -> list[str]:
        return [] if self.now is None else ["--now", self.now]

    def instant(self) -> dt.datetime:
        if self.now is None:
            return dt.datetime.now(dt.UTC)
        return dt.datetime.fromisoformat(self.now.replace("Z", "+00:00"))

    def keyring(self) -> dict[str, TrustedKey]:
        return load_keyring_file(self.keyring_path)

    def open_read(self) -> BlobStore:
        """The store, read-only: the publisher opens its own writable one under --execute."""
        if self.store is not None:
            from talent_data.ota.store import LocalDirectoryStore

            return LocalDirectoryStore(self.store)
        from talent_data.ota.s3_store import S3Location, check_credentials, connect

        p = PRODUCTION_PROFILE
        location = S3Location(
            bucket=p.bucket,
            endpoint_url=p.endpoint_url,
            region=p.region,
            profile=p.aws_profile,
            channel=Channel.PRODUCTION,
        )
        check_credentials(location, writable=False)
        return connect(location, writable=False, allow_delete=False)


@dataclass(frozen=True)
class Live:
    """The live production catalog as published: its bytes and its verified content."""

    envelope: bytes | None
    catalog: OtaCatalog | None
    payload: bytes | None

    @property
    def sequence(self) -> int | None:
        return None if self.catalog is None else self.catalog.sequence

    def describe(self) -> str:
        if self.catalog is None:
            return "no live production catalog"
        return (
            f"live production catalog: sequence {self.catalog.sequence}, active "
            f"{self.catalog.active_release_id}, {len(self.catalog.releases)} release(s)"
        )


def read_live(target: Target, game: str) -> Live:
    store = target.open_read()
    key = catalog_key(Channel.PRODUCTION)
    if not store.exists(key):
        return Live(None, None, None)
    envelope = store.get(key)
    catalog = verify_live_envelope(
        envelope,
        Channel.PRODUCTION,
        keyring=target.keyring(),
        game=game,
        now=target.instant(),
        allow_expired=True,  # a lapsed catalog is still a verified state to build on
    )
    payload = strict_b64decode(parse_strict(SignedEnvelope, envelope).payload)
    return Live(envelope, catalog, payload)


def published_state(package: rp.LoadedPackage, live: Live) -> str | None:
    """``active`` / ``listed`` when the live catalog already carries this exact release."""
    if live.catalog is None:
        return None
    index = package.index
    entry = next((r for r in live.catalog.releases if r.release_id == index.release_id), None)
    if entry is None:
        return None
    if entry.manifest_sha256 != index.manifest_sha256:
        raise Refused(
            f"production already lists {entry.release_id} with manifest {entry.manifest_sha256}, "
            f"not this package's {index.manifest_sha256}: a release id is never reused"
        )
    return "active" if live.catalog.active_release_id == entry.release_id else "listed"


# --------------------------------------------------------------------------- local state


def journal(package: rp.LoadedPackage, command: str, event: str, detail: str = "") -> None:
    """Append what a mutating step did to ``publish/journal.jsonl`` (the owner's record)."""
    package.work.mkdir(exist_ok=True)
    entry = {
        "at": dt.datetime.now(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "command": command,
        "event": event,
        "detail": detail,
    }
    with (package.work / JOURNAL).open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry, ensure_ascii=False) + "\n")


@contextlib.contextmanager
def publisher_lock(package: rp.LoadedPackage) -> Iterator[None]:
    """One publisher run at a time on this package (the OS releases it if the run dies)."""
    package.work.mkdir(exist_ok=True)
    with (package.work / LOCK).open("a+") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise Refused(
                f"another publisher run holds {package.work / LOCK}; one publisher at a time"
            ) from exc
        try:
            yield
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


@dataclass(frozen=True)
class Pending:
    payload: bytes
    base_sequence: int | None
    catalog: OtaCatalog

    def fresh(self, live: Live) -> bool:
        return self.base_sequence == live.sequence


def read_pending(package: rp.LoadedPackage) -> Pending | None:
    """The prepared payload, proven unaltered and still describing this package."""
    payload_path = package.work / PAYLOAD
    if not payload_path.exists():
        return None
    payload = payload_path.read_bytes()
    try:
        meta = json.loads((package.work / META).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise Refused(f"{package.work / META} is unreadable: {exc}") from exc
    if meta.get("payloadSha256") != sha256_ref(payload) or meta.get("channel") != "production":
        raise Refused(
            f"{payload_path} is not the payload its meta pins (altered after prepare); run "
            "prepare --replace --execute and sign the new PAE"
        )
    pae_path = package.work / PAE
    if not pae_path.exists() or pae_path.read_bytes() != pae(CATALOG_PAYLOAD_TYPE, payload):
        raise Refused(f"{pae_path} is not the PAE of the prepared payload")
    catalog = parse_strict(OtaCatalog, payload)
    index = package.index
    entry = next((r for r in catalog.releases if r.release_id == index.release_id), None)
    if (
        catalog.active_release_id != index.release_id
        or entry is None
        or entry.manifest_sha256 != index.manifest_sha256
    ):
        raise Refused(f"{payload_path} does not activate this package's release and manifest")
    base = meta.get("baseSequence")
    return Pending(payload, base if isinstance(base, int) else None, catalog)


def supersede(package: rp.LoadedPackage) -> Path:
    stamp = dt.datetime.now(dt.UTC).strftime("%Y%m%dT%H%M%S%fZ")
    target = package.work / "superseded" / stamp
    target.mkdir(parents=True)
    for name in (PAYLOAD, META, PAE):
        if (package.work / name).exists():
            shutil.move(package.work / name, target / name)
    return target


def sign_instructions(package: rp.LoadedPackage, pending: Pending) -> str:
    digest = pae_sha256(pending.payload)
    sig = package.work / SIGNATURE
    helper = f"python scripts/ota_production_release.py publish {package.root}"
    return "\n".join(
        [
            f"prepared payload {sha256_ref(pending.payload)}: sequence {pending.catalog.sequence} "
            f"(built on {pending.base_sequence}), active {pending.catalog.active_release_id}, "
            f"valid until {pending.catalog.expires_at:%Y-%m-%dT%H:%M:%SZ}",
            f"PAE to sign offline: {package.work / PAE}",
            f"  PAE SHA-256: {digest}",
            "On the offline signer (the private key never leaves it):",
            f"  shasum -a 256 {PAE}    # must print {digest.removeprefix('sha256:')}",
            f"  openssl dgst -sha256 -sign tf-prod-2026a.key.pem -out {SIGNATURE} {PAE}",
            f"Bring {SIGNATURE} back to {sig} and run:",
            f"  {helper} --signature {sig} --key-id tf-prod-2026a             # dry run",
            f"  {helper} --signature {sig} --key-id tf-prod-2026a --execute   # activation",
        ]
    )


# --------------------------------------------------------------------------- checks


def approvals_dir(args: argparse.Namespace, package: rp.LoadedPackage) -> Path:
    """The approval record - production: as merged on origin/main - proven to pin exactly this
    package, copied into ``publish/approvals/`` for the publisher."""
    release_id = package.index.release_id
    if args.releases_docs is not None:
        path = args.releases_docs.expanduser() / f"{release_id}.md"
        record = path.read_bytes() if path.is_file() else None
        source = str(path)
    else:
        record = rp.approval_record_at(args.repo, MAIN_REF, release_id)
        source = f"{MAIN_REF}:{rp.APPROVALS_DIR}/{release_id}.md"
    if record is None:
        raise Refused(
            f"no approval record {source}: fill in {package.root / rp.APPROVAL_TEMPLATE_NAME}, "
            "merge it into main by PR, then `git fetch origin`"
        )
    index = package.index
    problems = rp.approval_record_problems(
        record.decode("utf-8", "replace"),
        release_id=index.release_id,
        manifest_sha256=index.manifest_sha256,
        source_commit=index.source_commit,
    )
    if problems:
        raise Refused(
            f"the approval record {source} does not approve this package: " + "; ".join(problems)
        )
    directory = package.work / "approvals"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"{release_id}.md").write_bytes(record)
    load_approval_record(release_id, directory)  # the publisher's own parser
    print(f"approval record {source} pins {package.index.manifest_sha256}")
    return directory


def assess(args: argparse.Namespace, target: Target) -> tuple[rp.LoadedPackage, Live, Path]:
    """Everything check and prepare require before the publisher runs."""
    package = rp.load_package(args.package)
    index = package.index
    print(
        f"package {index.release_id}: manifest {index.manifest_sha256}, source "
        f"{index.source_commit}, createdAt {index.created_at}, {index.file_count} files - intact"
    )
    problems = rp.rebuild_problems(args.repo, package)
    if problems:
        raise Refused("the package is not what the committed data produces: " + "; ".join(problems))
    print(f"  identical to build-release at {index.source_commit[:12]} (manifest, every blob)")

    staging = rp.rebind_staging(package, REPOSITORY)
    if staging.status != "PASS":
        raise Refused(
            f"no staging evidence for this release ({staging.status}): "
            + "; ".join(staging.problems)
        )
    if staging != index.staging:
        raise Refused("the staging binding re-proven from the package differs from its index")
    print(f"  staging: {staging.run_url} PASS ({staging.mode}), base {staging.staging_base}")

    directory = approvals_dir(args, package)
    live = read_live(target, index.game)
    print(live.describe())
    if live.catalog is not None:
        active = next(
            r for r in live.catalog.releases if r.release_id == live.catalog.active_release_id
        )
        if active.client_build != staging.staging_base and published_state(package, live) is None:
            raise Refused(
                f"staging verified the update from {staging.staging_base}, but production "
                f"clients run {active.client_build} ({active.release_id})"
            )
    return package, live, directory


def publisher(*argv: str) -> int:
    print(f"$ talent-data ota {' '.join(argv)}", flush=True)
    code = talent_data(["ota", *argv])
    sys.stdout.flush()
    return code


def promote(
    args: argparse.Namespace,
    target: Target,
    package: rp.LoadedPackage,
    approvals: Path,
    *,
    execute: bool,
) -> int:
    return publisher(
        "promote",
        "--game",
        package.index.game,
        "--repo",
        str(args.repo),
        "--manifest",
        str(package.root / rp.MANIFEST_NAME),
        *target.flags(),
        "--keyring",
        str(target.keyring_path),
        "--releases-docs",
        str(approvals),
        "--out",
        str(package.work),
        *target.clock(),
        *(["--execute"] if execute else []),
    )


def verify_published(args: argparse.Namespace, target: Target, package: rp.LoadedPackage) -> int:
    code = publisher(
        "verify-live",
        "--game",
        package.index.game,
        "--repo",
        str(args.repo),
        *target.flags(),
        "--keyring",
        str(target.keyring_path),
        "--channel",
        "production",
        *target.clock(),
    )
    return EXIT_OK if code == EXIT_OK else EXIT_REFUSED


# --------------------------------------------------------------------------- commands


def cmd_check(args: argparse.Namespace, target: Target) -> int:
    package, live, approvals = assess(args, target)
    state = published_state(package, live)
    if state is not None:
        print(f"already published: {package.index.release_id} is {state} in production")
        return EXIT_OK if state == "active" else EXIT_REFUSED
    pending = read_pending(package)
    if pending is not None:
        fresh = pending.fresh(live)
        print(f"prepared payload: {'still succeeds the live catalog' if fresh else 'STALE'}")
    if promote(args, target, package, approvals, execute=False) != EXIT_OK:
        return EXIT_REFUSED
    report = publisher(
        "report",
        "--game",
        package.index.game,
        "--repo",
        str(args.repo),
        "--manifest",
        str(package.root / rp.MANIFEST_NAME),
        *target.flags(),
        "--keyring",
        str(target.keyring_path),
        *target.clock(),
    )
    if report != EXIT_OK:
        return EXIT_REFUSED
    print("check: ready to prepare (nothing was written)")
    return EXIT_OK


def cmd_prepare(args: argparse.Namespace, target: Target) -> int:
    package, live, approvals = assess(args, target)
    with publisher_lock(package) if args.execute else contextlib.nullcontext():
        state = published_state(package, live)
        if state == "active":
            print(
                f"already published: {package.index.release_id} is active (sequence "
                f"{live.sequence}); nothing to prepare"
            )
            return EXIT_OK
        if state == "listed":
            raise Refused(
                f"{package.index.release_id} is listed but not active: that is a rollback "
                "(talent-data ota rollback), not a publication"
            )
        pending = read_pending(package)
        if pending is not None and not args.replace:
            if pending.fresh(live):
                print("a prepared payload already succeeds the live catalog; nothing was written")
                print(sign_instructions(package, pending))
                return EXIT_OK
            raise Refused(
                f"the prepared payload was built on sequence {pending.base_sequence}, live is "
                f"{live.sequence}: it can no longer be published. `prepare --replace --execute` "
                "prepares a new one (and needs a new offline signature)"
            )
        if not args.execute:
            if pending is not None:
                print(f"would supersede the prepared payload {sha256_ref(pending.payload)}")
            code = promote(args, target, package, approvals, execute=False)
            print("dry run: nothing written (prepare --execute uploads blobs, writes the payload)")
            return EXIT_OK if code == EXIT_OK else EXIT_REFUSED
        if pending is not None:
            moved = supersede(package)
            journal(package, "prepare", "superseded", str(moved))
            print(f"superseded the prepared payload -> {moved} (its signature is void)")
        journal(package, "prepare", "start", live.describe())
        code = promote(args, target, package, approvals, execute=True)
        if code != EXIT_OK:
            journal(package, "prepare", "failed", f"exit {code}")
            print(
                "prepare did not finish: the catalog was not touched, no client sees anything. "
                "Uploaded blobs are immutable and kept; re-run prepare --execute to finish",
                file=sys.stderr,
            )
            return EXIT_REFUSED
        prepared = read_pending(package)
        if prepared is None:
            raise Refused("promote reported success but wrote no payload")
        journal(package, "prepare", "prepared", sha256_ref(prepared.payload))
        print("blobs uploaded and verified; the catalog is unchanged: nothing is visible yet")
        print(sign_instructions(package, prepared))
        return EXIT_OK


def cmd_publish(args: argparse.Namespace, target: Target) -> int:
    package = rp.load_package(args.package)
    with publisher_lock(package):
        pending = read_pending(package)
        if pending is None:
            raise Refused("nothing is prepared: run prepare --execute first")
        keyring = target.keyring()
        if target.production and not args.key_id.startswith(PRODUCTION_KEY_PREFIX):
            raise Refused(f"production is signed by {PRODUCTION_KEY_PREFIX}* keys only")
        if args.key_id not in keyring:
            raise Refused(f"key id {args.key_id!r} is not in {target.keyring_path}")
        der = args.signature.expanduser().read_bytes()
        if not verify_der_signature(keyring[args.key_id].public_key, der, pending.payload):
            raise Refused(
                f"{args.signature} is not a signature by {args.key_id} over {package.work / PAE} "
                "(another key, or other bytes)"
            )
        print(f"signature by {args.key_id} verifies over {sha256_ref(pending.payload)}")

        live = read_live(target, package.index.game)
        print(live.describe())
        if live.payload == pending.payload:
            print("already published: the live catalog is exactly this payload; nothing written")
            return verify_published(args, target, package)
        if published_state(package, live) == "active":
            print(f"already published by sequence {live.sequence} (another payload); no write")
            return verify_published(args, target, package)
        if not pending.fresh(live):
            journal(package, "publish", "stale", live.describe())
            raise Refused(
                f"the prepared payload was built on sequence {pending.base_sequence}, live is "
                f"{live.sequence}: the live catalog changed. Run prepare --replace --execute "
                "and sign the new PAE (this signature cannot be reused)"
            )

        attach = [
            "attach-signature",
            "--game",
            package.index.game,
            "--repo",
            str(args.repo),
            "--payload",
            str(package.work / PAYLOAD),
            "--signature",
            str(args.signature.expanduser()),
            "--key-id",
            args.key_id,
            *target.flags(),
            "--keyring",
            str(target.keyring_path),
            "--channel",
            "production",
            "--releases-docs",
            str(package.work / "approvals"),
            *target.clock(),
        ]
        if not args.execute:
            code = publisher(*attach)
            print("dry run: catalog.json not written (publish --execute activates the release)")
            return EXIT_OK if code == EXIT_OK else EXIT_REFUSED

        journal(package, "publish", "write", f"{live.describe()}; {sha256_ref(pending.payload)}")
        code = publisher(*attach, "--execute")
        try:
            after = read_live(target, package.index.game)
        except (OtaPublisherError, OSError) as exc:
            journal(package, "publish", "ambiguous", f"exit {code}; live unreadable: {exc}")
            raise Ambiguous(
                f"attach-signature exited {code} and the live catalog cannot be read back "
                f"({exc}): the outcome is unknown"
            ) from exc
        if after.payload == pending.payload:
            journal(package, "publish", "published", after.describe())
            if code != EXIT_OK:
                print(f"attach-signature exited {code}, yet the live catalog is this payload")
            print(f"published: {after.describe()}")
            return verify_published(args, target, package)
        if after.envelope == live.envelope:
            journal(package, "publish", "not-written", f"exit {code}")
            print(
                "not written: the live catalog is unchanged; the same signature may be retried "
                "once the cause above is fixed",
                file=sys.stderr,
            )
            return EXIT_REFUSED
        journal(package, "publish", "ambiguous", f"exit {code}; {after.describe()}")
        raise Ambiguous(
            f"attach-signature exited {code} and the live catalog is now another one "
            f"({after.describe()}): another publisher raced"
        )


def cmd_verify(args: argparse.Namespace, target: Target) -> int:
    package = rp.load_package(args.package)
    live = read_live(target, package.index.game)
    print(live.describe())
    if published_state(package, live) != "active":
        print(f"{package.index.release_id} is not the active production release", file=sys.stderr)
        return EXIT_REFUSED
    pending = read_pending(package)
    if pending is not None:
        same = "exactly" if live.payload == pending.payload else "NOT"
        print(f"the live catalog is {same} the payload prepared in {package.work}")
    code = verify_published(args, target, package)
    if not target.production:
        print("preflight: not run (a local store has no public origin)")
        return code
    from talent_data.ota.preflight import main as preflight

    report = package.work / "preflight.json"
    argv = ["--expect", "published", "--keyring", str(target.keyring_path), "--json", str(report)]
    print(f"$ python scripts/ota_production_preflight.py {' '.join(argv)}", flush=True)
    checked = preflight(argv)
    return EXIT_OK if code == EXIT_OK and checked == 0 else EXIT_REFUSED


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    def common(p: argparse.ArgumentParser) -> None:
        p.add_argument("package", type=Path, help="the downloaded release package directory")
        p.add_argument("--repo", type=Path, default=ROOT, help=argparse.SUPPRESS)
        p.add_argument(
            "--store", type=Path, help="rehearsal: a local directory standing in for the bucket"
        )
        # a rehearsal store's keyring, approval records and clock; never production's
        p.add_argument("--keyring", type=Path, help=argparse.SUPPRESS)
        p.add_argument("--releases-docs", type=Path, help=argparse.SUPPRESS)
        p.add_argument("--now", help=argparse.SUPPRESS)

    check = sub.add_parser("check", help="verify the package and the readiness (read-only)")
    common(check)
    check.set_defaults(handler=cmd_check)
    prepare = sub.add_parser("prepare", help="upload the blobs, write the payload and PAE")
    common(prepare)
    prepare.add_argument("--execute", action="store_true", help="write (default: dry run)")
    prepare.add_argument(
        "--replace", action="store_true", help="supersede a prepared payload (new signature)"
    )
    prepare.set_defaults(handler=cmd_prepare)
    publish = sub.add_parser("publish", help="verify the DER signature, write the catalog")
    common(publish)
    publish.add_argument("--signature", type=Path, required=True, help="the offline DER file")
    publish.add_argument("--key-id", required=True, help="e.g. tf-prod-2026a")
    publish.add_argument("--execute", action="store_true", help="write (default: dry run)")
    publish.set_defaults(handler=cmd_publish)
    verify = sub.add_parser("verify", help="verify-live and the production preflight")
    common(verify)
    verify.set_defaults(handler=cmd_verify)

    args = parser.parse_args(argv)
    rehearsal = [f for f in ("keyring", "releases_docs", "now") if getattr(args, f) is not None]
    if rehearsal and args.store is None:
        parser.error(
            "--keyring, --releases-docs and --now belong to a local rehearsal store (--store); "
            "production uses the committed keyring, origin/main and the wall clock"
        )
    args.repo = args.repo.expanduser().resolve()
    target = Target(
        store=None if args.store is None else args.store.expanduser().resolve(),
        keyring_path=(args.keyring or PRODUCTION_KEYRING).expanduser().resolve(),
        now=args.now,
    )
    try:
        code: int = args.handler(args, target)
    except Ambiguous as exc:
        print(
            f"AMBIGUOUS: {exc}. Stop: do not prepare or sign anything new. Run `verify`, then "
            "follow docs/ota/production-release.md §7 (recovery)",
            file=sys.stderr,
        )
        return EXIT_AMBIGUOUS
    except (Refused, OtaPublisherError, OSError) as exc:
        print(f"refused: {exc}", file=sys.stderr)
        return EXIT_REFUSED
    return code


if __name__ == "__main__":
    raise SystemExit(main())
