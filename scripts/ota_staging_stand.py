"""Local OTA staging stand (M5.OTA.4a): the real publisher, a staging key, HTTPS.

Three commands:

``prepare <out>``
    Creates, **outside any git checkout**, everything a staging client needs and nothing a
    production client could use:

    * an ephemeral staging signing key ``tf-staging-local-<hex>`` (private PEM, mode 0600)
      and its public keyring - generated here, never committed, refused by production
      configurations (``KEY_NOT_FOR_CHANNEL``) and unrelated to the fixtures' ``test-*``;
    * an ephemeral TLS trust chain: a local CA (its private key is discarded once the
      server certificate is issued) and a server certificate for ``localhost``,
      ``127.0.0.1`` and ``10.0.2.2`` (the Android emulator's alias of the host);
    * the publications of the **real** publisher CLI (``talent-data ota ...``) over the
      committed data of a commit on ``origin/main``, into a local directory store whose
      object keys are exactly the static URL paths of contract §1 (the documented static
      export: the store directory *is* the origin):

      ================  ============================================================
      ``1-a-staged``    stage A (gates G1-G8, staging channel): A active
      ``2-b-staged``    stage B (append-only): A and B listed, B active
      ``3-rollback-a``  rollback to A (a new sequence) signed with the staging key
      ``4-level-2``     stage B-r2 with ``minDataLevel`` 2: active, needs a newer app
      ================  ============================================================

    * the G6 STOP attempt between ``3-rollback-a`` and ``4-level-2``: a B whose paladin
      tree carries an engine-unsupported node type (the wire-valid fixture content of
      ``release-open-union-node-type``); ``validate`` and ``stage`` must refuse it and
      the store must stay byte-identical;
    * ``report.json`` (what the Kotlin E2E test asserts against) and
      ``android-staging.env`` (the debug-build settings of ``docs/ota/android-device-smoke.md``).

``activate <out> <snapshot>``
    Atomically points ``<out>/live`` at ``stores/<snapshot>`` (what a publication does to
    a real origin: one catalog object changes, blobs are only ever added).

``serve <out>``
    Serves ``<out>/live`` over HTTPS with the stand's certificate:
    ``GET /v1/channels/staging/catalog.json`` and ``GET /v1/blobs/sha256/<64 hex>`` only,
    everything else 404, no redirects, the contract's cache headers. ``--blob-delay-ms``
    slows blob responses so a device check can be interrupted on purpose.

    python scripts/ota_staging_stand.py prepare ~/tf-ota-stand
    python scripts/ota_staging_stand.py activate ~/tf-ota-stand 1-a-staged
    python scripts/ota_staging_stand.py serve ~/tf-ota-stand --port 8443

Nothing here can sign for production: the publisher refuses to load any key id that is
not ``test-*`` / ``tf-staging-*`` (``talent_data.ota.signing``), this script only ever
generates ``tf-staging-local-*``, and it refuses an output directory inside a git
checkout so the private keys cannot be committed by accident.
"""

from __future__ import annotations

import argparse
import base64
import contextlib
import datetime as dt
import hashlib
import http.server
import io
import ipaddress
import json
import os
import re
import secrets
import shutil
import ssl
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from cryptography import x509  # noqa: E402
from cryptography.hazmat.primitives import hashes, serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID  # noqa: E402

from talent_data.cli.main import main as cli_main  # noqa: E402

GAME = "forever"
BUILD_A = "1.60.1.70374"
BUILD_B = "1.60.1.70450"
A = f"{BUILD_A}-r1"
B = f"{BUILD_B}-r1"
B_LEVEL_2 = f"{BUILD_B}-r2"
G6_REVISION = 3
CHANNEL = "staging"
CATALOG_KEY = f"v1/channels/{CHANNEL}/catalog.json"
BLOB_KEY = re.compile(r"v1/blobs/sha256/[0-9a-f]{64}")
SNAPSHOTS = ("1-a-staged", "2-b-staged", "3-rollback-a", "4-level-2")
TLS_NAMES = ("localhost",)
TLS_ADDRESSES = ("127.0.0.1", "10.0.2.2")
CERT_DAYS = 30
OPEN_UNION_PALADIN = ROOT / "docs/ota/fixtures/v1/blobs/release-open-union-node-type--paladin.json"


class StandError(Exception):
    """A precondition of the stand does not hold."""


# --------------------------------------------------------------------------- helpers


def utc(text: str | None) -> dt.datetime:
    if text is None:
        return dt.datetime.now(dt.UTC).replace(microsecond=0)
    parsed = dt.datetime.fromisoformat(text.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise StandError(f"--now needs a UTC instant, got {text!r}")
    return parsed.astimezone(dt.UTC).replace(microsecond=0)


def _stamp(instant: dt.datetime) -> str:
    return instant.strftime("%Y-%m-%dT%H:%M:%SZ")


def _git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=ROOT, check=True, capture_output=True, text=True
    ).stdout.strip()


def inside_git_checkout(path: Path) -> bool:
    probe = path
    while not probe.exists():
        probe = probe.parent
    result = subprocess.run(
        ["git", "rev-parse", "--is-inside-work-tree"], cwd=probe, capture_output=True, text=True
    )
    return result.returncode == 0 and result.stdout.strip() == "true"


def _write_private(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as handle:
        handle.write(data)


def tree_digest(root: Path) -> dict[str, str]:
    """Every file under ``root`` with the SHA-256 of its bytes (to prove "unchanged")."""
    if not root.exists():
        return {}
    return {
        path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


def _sha256_ref(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


# --------------------------------------------------------------------------- keys


def _pkcs8_pem(key: ec.EllipticCurvePrivateKey) -> bytes:
    return key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    )


def generate_staging_key(keys: Path) -> tuple[str, Path, Path]:
    """A fresh ``tf-staging-local-<hex>`` key: (key id, public keyring, private PEM)."""
    key_id = f"tf-staging-local-{secrets.token_hex(4)}"
    key = ec.generate_private_key(ec.SECP256R1())
    spki = key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    entry = {
        "keyId": key_id,
        "algorithm": "ecdsa-p256-sha256",
        "publicKeySpki": base64.b64encode(spki).decode("ascii"),
    }
    keys.mkdir(parents=True, exist_ok=True)
    keyring = keys / "staging-keyring.json"
    keyring.write_text(json.dumps({"keys": [entry]}, indent=2) + "\n", encoding="utf-8")
    pem = keys / f"{key_id}.pem"
    _write_private(pem, _pkcs8_pem(key))
    return key_id, keyring, pem


def generate_tls(tls: Path, now: dt.datetime) -> dict[str, str]:
    """A local CA and a server certificate signed by it; the CA key is never written.

    ``ca.pem`` is the trust anchor a test client pins explicitly, ``server.pem`` the server
    certificate followed by the CA certificate, ``server.key`` its PKCS#8 key (mode 0600)."""
    ca_key = ec.generate_private_key(ec.SECP256R1())
    ca_name = x509.Name(
        [x509.NameAttribute(NameOID.COMMON_NAME, "TalentForge OTA local staging CA (ephemeral)")]
    )
    not_before = now - dt.timedelta(hours=1)
    not_after = now + dt.timedelta(days=CERT_DAYS)
    ca_usage = x509.KeyUsage(
        digital_signature=False,
        content_commitment=False,
        key_encipherment=False,
        data_encipherment=False,
        key_agreement=False,
        key_cert_sign=True,
        crl_sign=True,
        encipher_only=False,
        decipher_only=False,
    )
    ca_cert = (
        x509.CertificateBuilder()
        .subject_name(ca_name)
        .issuer_name(ca_name)
        .public_key(ca_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(not_before)
        .not_valid_after(not_after)
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .add_extension(ca_usage, critical=True)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), False)
        .sign(ca_key, hashes.SHA256())
    )
    server_key = ec.generate_private_key(ec.SECP256R1())
    sans: list[x509.GeneralName] = [x509.DNSName(name) for name in TLS_NAMES]
    sans += [x509.IPAddress(ipaddress.ip_address(address)) for address in TLS_ADDRESSES]
    server_cert = (
        x509.CertificateBuilder()
        .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")]))
        .issuer_name(ca_name)
        .public_key(server_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(not_before)
        .not_valid_after(not_after)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.SubjectAlternativeName(sans), critical=False)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .add_extension(
            x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), False
        )
        .sign(ca_key, hashes.SHA256())
    )
    tls.mkdir(parents=True, exist_ok=True)
    ca_pem = ca_cert.public_bytes(serialization.Encoding.PEM)
    (tls / "ca.pem").write_bytes(ca_pem)
    (tls / "server.pem").write_bytes(server_cert.public_bytes(serialization.Encoding.PEM) + ca_pem)
    _write_private(tls / "server.key", _pkcs8_pem(server_key))
    return {
        "caSha256": hashlib.sha256(ca_cert.public_bytes(serialization.Encoding.DER)).hexdigest(),
        "names": ",".join(TLS_NAMES + TLS_ADDRESSES),
        "notAfter": _stamp(not_after),
    }


# --------------------------------------------------------------------------- publisher


class Publisher:
    """The real ``talent-data ota`` CLI against a local directory store, staging only."""

    def __init__(self, out: Path, *, commit: str, now: dt.datetime) -> None:
        self.out = out
        self.store = out / "work" / "store"
        self.artifacts = out / "work" / "artifacts"
        self.commit = commit
        self.now = _stamp(now)
        self.key_id, self.keyring, self.pem = generate_staging_key(out / "keys")
        self.log: list[dict[str, Any]] = []

    def cli(self, *args: str, expect: int = 0, repo: Path = ROOT) -> int:
        code: int = cli_main(["ota", *args, "--repo", str(repo)])
        self.log.append({"command": args[0], "exit": code})
        if code != expect:
            raise StandError(f"talent-data ota {args[0]} exited with {code}, expected {expect}")
        return code

    def common(self) -> list[str]:
        return ["--game", GAME, "--store", str(self.store), "--keyring", str(self.keyring)]

    def manifest_path(self, release_id: str) -> Path:
        return self.artifacts / release_id / "release.json"

    def build(self, build: str, *, revision: int = 1, min_data_level: int = 1) -> Path:
        self.cli(
            "build-release", "--game", GAME, "--build", build, "--revision", str(revision),
            "--min-data-level", str(min_data_level), "--commit", self.commit,
            "--created-at", self.now, "--out", str(self.artifacts),
        )  # fmt: skip
        return self.manifest_path(f"{build}-r{revision}")

    def validate(self, manifest: Path, *, expect: int = 0, repo: Path = ROOT) -> int:
        return self.cli(
            "validate", *self.common(), "--channel", CHANNEL, "--manifest", str(manifest),
            "--now", self.now, expect=expect, repo=repo,
        )  # fmt: skip

    def stage(self, manifest: Path, *, expect: int = 0, repo: Path = ROOT) -> int:
        return self.cli(
            "stage", *self.common(), "--manifest", str(manifest), "--key", str(self.pem),
            "--key-id", self.key_id, "--execute", "--now", self.now, expect=expect, repo=repo,
        )  # fmt: skip

    def rollback(self, release_id: str) -> None:
        self.cli(
            "rollback", *self.common(), "--channel", CHANNEL, "--active", release_id,
            "--out", str(self.artifacts), "--execute", "--now", self.now,
        )  # fmt: skip
        payload = self.artifacts / f"pending-{CHANNEL}.payload.json"
        signature = self.artifacts / f"pending-{CHANNEL}.signature.der"
        self.cli(
            "sign", "--key", str(self.pem), "--key-id", self.key_id,
            "--payload", str(payload), "--out", str(signature),
        )  # fmt: skip
        self.cli(
            "attach-signature", *self.common(), "--payload", str(payload),
            "--signature", str(signature), "--key-id", self.key_id, "--channel", CHANNEL,
            "--execute", "--now", self.now,
        )  # fmt: skip

    def verify_live(self) -> None:
        self.cli("verify-live", *self.common(), "--channel", CHANNEL, "--now", self.now)

    def snapshot(self, name: str) -> dict[str, Any]:
        target = self.out / "stores" / name
        shutil.copytree(self.store, target)
        catalog = (target / CATALOG_KEY).read_bytes()
        payload = json.loads(base64.b64decode(json.loads(catalog)["payload"]))
        return {
            "name": name,
            "sequence": payload["sequence"],
            "activeReleaseId": payload["activeReleaseId"],
            "releases": [r["releaseId"] for r in payload["releases"]],
            "manifests": {r["releaseId"]: r["manifestSha256"] for r in payload["releases"]},
            "issuedAt": payload["issuedAt"],
            "expiresAt": payload["expiresAt"],
            "catalogSha256": _sha256_ref(catalog),
            "blobCount": sum(1 for _ in (target / "v1/blobs/sha256").iterdir()),
        }

    def g6_attempt(self) -> dict[str, Any]:
        """A release whose only defect is an engine-unsupported mechanic must STOP on G6.

        Like ``test_attach_re_runs_g6_for_a_release_that_never_passed_promote``: a scratch
        clone commits the wire-valid fixture content of ``release-open-union-node-type``
        (paladin, an unknown node type) onto a local ``origin/main``, so the release is
        reproducible (G8), wire-valid (G3) and on main (G1). ``validate`` and ``stage``
        must refuse it with G6 STOP, and the staging store must stay byte-identical.
        G4 also reports that the talent data of an already bundled build changed."""
        repo = self.out / "work" / "g6-repo"
        subprocess.run(["git", "clone", "-q", "--no-checkout", str(ROOT), str(repo)], check=True)

        def git(*args: str) -> str:
            return subprocess.run(
                ["git", "-C", str(repo), *args], check=True, capture_output=True, text=True
            ).stdout.strip()

        git("checkout", "-q", self.commit)
        paladin = repo / "data" / "normalized" / GAME / BUILD_B / "paladin.json"
        paladin.write_bytes(OPEN_UNION_PALADIN.read_bytes())
        git("-c", "user.email=stand@invalid", "-c", "user.name=stand", "commit", "-qam", "G6")
        smuggled = git("rev-parse", "HEAD")
        git("update-ref", "refs/remotes/origin/main", smuggled)
        release_id = f"{BUILD_B}-r{G6_REVISION}"
        artifacts = self.out / "work" / "g6"
        self.cli(
            "build-release", "--game", GAME, "--build", BUILD_B, "--revision", str(G6_REVISION),
            "--commit", smuggled, "--created-at", self.now, "--out", str(artifacts), repo=repo,
        )  # fmt: skip
        manifest = artifacts / release_id / "release.json"
        before = tree_digest(self.store)
        captured = io.StringIO()
        with contextlib.redirect_stdout(captured):
            validate_exit = self.validate(manifest, expect=1, repo=repo)
            stage_exit = self.stage(manifest, expect=1, repo=repo)
        output = captured.getvalue()
        sys.stdout.write(output)
        if tree_digest(self.store) != before:
            raise StandError("G6: the refused stage changed the store")
        gates = dict(re.findall(r"^  (G[1-8]) (PASS|FLAG|STOP)", output, re.MULTILINE))
        if gates.get("G6") != "STOP" or "UNSUPPORTED_NODE_TYPE" not in output:
            raise StandError(f"G6 did not stop the unsupported mechanic: {gates}")
        return {
            "releaseId": release_id,
            "commit": smuggled,
            "manifestSha256": _sha256_ref(manifest.read_bytes()),
            "gates": gates,
            "validateExit": validate_exit,
            "stageExit": stage_exit,
            "storeUnchanged": True,
        }


def prepare(out: Path, *, commit: str | None, now: dt.datetime) -> dict[str, Any]:
    out = out.expanduser().resolve()
    if inside_git_checkout(out):
        raise StandError(f"{out} is inside a git checkout; the stand writes private keys")
    if out.exists() and any(out.iterdir()):
        raise StandError(f"{out} is not empty")
    commit = commit or _git("merge-base", "HEAD", "origin/main")
    out.mkdir(parents=True, exist_ok=True)
    # transport validity follows the wall clock, whatever publication instant --now names
    tls = generate_tls(out / "tls", utc(None))
    publisher = Publisher(out, commit=commit, now=now)
    snapshots = []

    for build, name in ((BUILD_A, SNAPSHOTS[0]), (BUILD_B, SNAPSHOTS[1])):
        manifest = publisher.build(build)
        publisher.validate(manifest)
        publisher.stage(manifest)
        publisher.verify_live()
        snapshots.append(publisher.snapshot(name))

    publisher.rollback(A)
    publisher.verify_live()
    snapshots.append(publisher.snapshot(SNAPSHOTS[2]))

    g6 = publisher.g6_attempt()

    level_2 = publisher.build(BUILD_B, revision=2, min_data_level=2)
    publisher.validate(level_2)
    publisher.stage(level_2)
    publisher.verify_live()
    snapshots.append(publisher.snapshot(SNAPSHOTS[3]))

    if any(g6["manifestSha256"] in s["manifests"].values() for s in snapshots):
        raise StandError("G6: the refused release is listed in a published catalog")
    activate(out, SNAPSHOTS[0])

    manifests = {
        release_id: {
            "path": publisher.manifest_path(release_id).relative_to(out).as_posix(),
            "sha256": _sha256_ref(publisher.manifest_path(release_id).read_bytes()),
        }
        for release_id in (A, B, B_LEVEL_2)
    }
    report: dict[str, Any] = {
        "format": "talentforge-ota-staging-stand",
        "formatVersion": 1,
        "game": GAME,
        "channel": CHANNEL,
        "now": publisher.now,
        "commit": commit,
        "keyId": publisher.key_id,
        "keyring": "keys/staging-keyring.json",
        "tls": {"ca": "tls/ca.pem", "chain": "tls/server.pem", "key": "tls/server.key", **tls},
        "releases": {"a": A, "b": B, "bLevel2": B_LEVEL_2},
        "manifests": manifests,
        "snapshots": snapshots,
        "g6": g6,
        "publisherLog": publisher.log,
    }
    (out / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (out / "android-staging.env").write_text(
        "# Debug builds only (docs/ota/android-device-smoke.md). Never set for a release build:\n"
        "# :composeApp refuses release tasks while any of these is set.\n"
        "export TALENTFORGE_OTA_ANDROID_STAGING_ENABLED=true\n"
        "export TALENTFORGE_OTA_ANDROID_STAGING_BASEURL=https://10.0.2.2:8443\n"
        f"export TALENTFORGE_OTA_ANDROID_STAGING_KEYRING={out / 'keys/staging-keyring.json'}\n"
        f"export TALENTFORGE_OTA_ANDROID_STAGING_TRUSTANCHOR={out / 'tls/ca.pem'}\n",
        encoding="utf-8",
    )
    return report


def activate(out: Path, snapshot: str) -> None:
    """Point ``<out>/live`` at ``stores/<snapshot>`` with one atomic rename."""
    out = out.expanduser().resolve()
    if snapshot not in SNAPSHOTS or not (out / "stores" / snapshot).is_dir():
        raise StandError(f"unknown snapshot {snapshot!r} (one of {', '.join(SNAPSHOTS)})")
    temp = out / ".live.tmp"
    if temp.is_symlink() or temp.exists():
        temp.unlink()
    temp.symlink_to(Path("stores") / snapshot)
    temp.replace(out / "live")


# --------------------------------------------------------------------------- server


def _handler(root: Path, blob_delay: float) -> type[http.server.BaseHTTPRequestHandler]:
    stores = root / "stores"

    class StaticOrigin(http.server.BaseHTTPRequestHandler):
        """The static origin of contract §1: two kinds of objects, nothing else."""

        server_version = "TalentForgeStagingStand"
        sys_version = ""

        def do_GET(self) -> None:
            key = self.path.removeprefix("/")
            if key != CATALOG_KEY and not BLOB_KEY.fullmatch(key):
                self._status(404)
                return
            file = (root / "live" / key).resolve()
            if stores not in file.parents or not file.is_file():
                self._status(404)
                return
            if key != CATALOG_KEY and blob_delay:
                time.sleep(blob_delay)
            body = file.read_bytes()
            cache = (
                "public, max-age=300"
                if key == CATALOG_KEY
                else "public, max-age=31536000, immutable"
            )
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Cache-Control", cache)
            self.end_headers()
            self.wfile.write(body)

        def do_HEAD(self) -> None:
            self._status(405)

        def do_POST(self) -> None:
            self._status(405)

        def _status(self, code: int) -> None:
            self.send_response(code)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, format: str, *args: Any) -> None:
            sys.stderr.write(f"{self.log_date_time_string()} {format % args}\n")

    return StaticOrigin


class _Server(http.server.ThreadingHTTPServer):
    """A client that goes away mid-response (killed app, cancelled check) is one log line."""

    daemon_threads = True

    def handle_error(self, request: Any, client_address: Any) -> None:
        error = sys.exc_info()[1]
        if isinstance(error, (ConnectionError, ssl.SSLError, TimeoutError)):
            sys.stderr.write(f"client {client_address[0]} went away: {type(error).__name__}\n")
            return
        super().handle_error(request, client_address)


def tls_context(out: Path) -> ssl.SSLContext:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(out / "tls/server.pem", out / "tls/server.key")
    return context


def make_server(
    out: Path, *, host: str, port: int, blob_delay_ms: int = 0
) -> http.server.ThreadingHTTPServer:
    out = out.expanduser().resolve()
    if not (out / "live").is_symlink():
        raise StandError(f"{out} has no live snapshot (run prepare)")
    server = _Server((host, port), _handler(out, blob_delay_ms / 1000))
    server.socket = tls_context(out).wrap_socket(server.socket, server_side=True)
    return server


# --------------------------------------------------------------------------- CLI


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare", help="keys, TLS and the publications (outside git)")
    prep.add_argument("out", type=Path)
    prep.add_argument("--commit", help="40-hex commit on origin/main (default: merge-base)")
    prep.add_argument("--now", help="publication instant YYYY-MM-DDTHH:MM:SSZ (default: now)")
    act = sub.add_parser("activate", help="switch the served snapshot")
    act.add_argument("out", type=Path)
    act.add_argument("snapshot", choices=SNAPSHOTS)
    serve = sub.add_parser("serve", help="HTTPS static origin of <out>/live")
    serve.add_argument("out", type=Path)
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8443)
    serve.add_argument("--blob-delay-ms", type=int, default=0)
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            report = prepare(args.out, commit=args.commit, now=utc(args.now))
            for snapshot in report["snapshots"]:
                print(
                    f"  {snapshot['name']}: sequence {snapshot['sequence']}, active "
                    f"{snapshot['activeReleaseId']}, listed {snapshot['releases']}"
                )
            g6 = report["g6"]
            stopped = sorted(gate for gate, status in g6["gates"].items() if status == "STOP")
            print(
                f"  G6 attempt {g6['releaseId']}: validate exit {g6['validateExit']}, stage exit "
                f"{g6['stageExit']} (STOP: {', '.join(stopped)}), store unchanged, never listed"
            )
            print(f"stand ready in {args.out} (key {report['keyId']}, live = {SNAPSHOTS[0]})")
        elif args.command == "activate":
            activate(args.out, args.snapshot)
            print(f"live -> {args.snapshot}")
        else:
            server = make_server(
                args.out, host=args.host, port=args.port, blob_delay_ms=args.blob_delay_ms
            )
            print(f"serving https://{args.host}:{args.port}/ from {args.out}/live", flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass
            finally:
                server.server_close()
    except StandError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
