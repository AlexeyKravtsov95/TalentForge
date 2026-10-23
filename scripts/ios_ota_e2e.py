#!/usr/bin/env python3
"""iOS Simulator OTA staging E2E (M5.IOS.2): the real iOS client against a real HTTPS origin.

``IosOtaStagingE2ETest`` (``mobile/composeApp/src/iosTest``) runs inside the Kotlin/Native test
executable **on an iOS Simulator** with the production iOS adapters: ``NSURLSession`` (App
Transport Security defaults, the system trust store, certificate and host-name verification on),
Security.framework ECDSA, the Application Support file store, ``NSUserDefaults`` for the saved
builds and the app's Compose resources as the bundle. This script provides everything around it:

* the publications of the **real** publisher (``scripts/ota_staging_stand.py prepare``: 70374 = A,
  70450 = B, rollback, data level 2) with an ephemeral ``tf-staging-local-*`` key;
* HTTPS origins on ``127.0.0.1`` with certificates of an ephemeral CA that is installed **as a
  root into the test simulator's trust store** (``simctl keychain add-root-cert``) — no
  verification is disabled anywhere — plus three origins the client must refuse: a certificate of
  an untrusted CA, an expired certificate and one for another host name;
* fault injection chosen by the test per origin key over ``/_control/<key>`` (HTTP status codes,
  redirects, dropped connections, truncated or bit-flipped files, a catalog re-signed by a foreign
  or a ``test-`` key, slow responses);
* a dedicated simulator created for the run (deleted afterwards), and the test phases spawned in it
  one process at a time, so a phase can end by ``SIGKILL`` and the next starts cold on the same
  OTA directory, exactly like an iOS termination and relaunch.

Nothing here touches production: the client is configured for the staging channel only, the stand
never holds a production key, and the CA's private key is never written to disk.

    python3 scripts/ios_ota_e2e.py run <work-dir-outside-git> [--device-type "iPhone 18 Pro"]

Exit status: 0 = every phase passed, 1 = a phase failed, 2 = the run could not be set up.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import http.server
import ipaddress
import json
import os
import re
import socket
import ssl
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlsplit

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from talent_data.ota.signing import build_envelope, sign_pae_der  # noqa: E402

TEST_BINARY = ROOT / "mobile/composeApp/build/bin/iosSimulatorArm64/debugTest/test.kexe"
TEST_CLASS = "io.github.alexeykravtsov95.talentforge.ui.ota.IosOtaStagingE2ETest"
CATALOG = "v1/channels/staging/catalog.json"
BLOB = re.compile(r"v1/blobs/sha256/([0-9a-f]{64})")
EXIT_PASS, EXIT_FAIL, EXIT_ERROR = 0, 1, 2
DEFAULT_DEVICE_TYPES = ("iPhone 18 Pro", "iPhone 17 Pro")

# (phase, test method, expectation): "pass", or "killed" (the phase ends itself by SIGKILL).
PHASES: tuple[tuple[str, str, str], ...] = (
    ("key-classes", "theAppConfigurationRefusesProductionTestAndPlaintextSettings", "pass"),
    ("update-70374-to-70450", "aToBOverHttpsKeepsTheOpenEditorAndTheSavedBuilds", "pass"),
    ("relaunch-1-install", "relaunchPhase1InstallsB", "pass"),
    ("relaunch-2-offline-cold-start", "relaunchPhase2ColdStartsOfflineOnB", "pass"),
    ("terminate-1-mid-download", "terminatePhase1KilledMidDownload", "killed"),
    ("terminate-2-cold-start-resume", "terminatePhase2ColdStartResumes", "pass"),
    ("suspension", "backgroundCancelsTheAttemptAndForegroundResumesIt", "pass"),
    ("transport", "transportFailuresChangeNothing", "pass"),
    ("tls", "rejectedCertificatesChangeNothing", "pass"),
    ("integrity", "signatureManifestAndFileFailuresChangeNothing", "pass"),
    ("disk-space", "insufficientStorageChangesNothing", "pass"),
    ("rollback-and-data-level", "rollbackAndAnIncompatibleDataLevel", "pass"),
    ("corrupted-store", "aCorruptedStoreRecovers", "pass"),
    ("historical-on-demand", "aSavedBuildOfAnUnbundledBuildDownloadsItsData", "pass"),
)


class HarnessError(Exception):
    """The run could not be set up."""


# ----------------------------------------------------------------------------- TLS


@dataclass
class Tls:
    """An ephemeral trusted CA (key kept in memory only) and the certificate of each origin."""

    ca_pem: bytes
    files: dict[str, tuple[Path, Path]]  # origin kind -> (chain, key)


def _name(common: str) -> x509.Name:
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, common)])


def _ca(common: str, now: dt.datetime) -> tuple[ec.EllipticCurvePrivateKey, x509.Certificate]:
    key = ec.generate_private_key(ec.SECP256R1())
    usage = x509.KeyUsage(
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
    cert = (
        x509.CertificateBuilder()
        .subject_name(_name(common))
        .issuer_name(_name(common))
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(days=1))
        .not_valid_after(now + dt.timedelta(days=7))
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .add_extension(usage, critical=True)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), False)
        .sign(key, hashes.SHA256())
    )
    return key, cert


def _leaf(
    ca_key: ec.EllipticCurvePrivateKey,
    ca: x509.Certificate,
    *,
    dns: list[str],
    ips: list[str],
    not_before: dt.datetime,
    not_after: dt.datetime,
) -> tuple[ec.EllipticCurvePrivateKey, x509.Certificate]:
    key = ec.generate_private_key(ec.SECP256R1())
    sans: list[x509.GeneralName] = [x509.DNSName(name) for name in dns]
    sans += [x509.IPAddress(ipaddress.ip_address(ip)) for ip in ips]
    authority = x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key())
    cert = (
        x509.CertificateBuilder()
        .subject_name(_name(dns[0]))
        .issuer_name(ca.subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(not_before)
        .not_valid_after(not_after)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.SubjectAlternativeName(sans), critical=False)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .add_extension(authority, False)
        .sign(ca_key, hashes.SHA256())
    )
    return key, cert


def make_tls(directory: Path) -> Tls:
    """``trusted`` (the CA installed in the simulator), ``untrusted`` (another CA), ``expired``
    and ``wrong-host`` (both from the trusted CA). Only public certificates and the leaf keys
    are written; neither CA key ever is."""
    now = dt.datetime.now(dt.UTC)
    directory.mkdir(parents=True, exist_ok=True)
    ca_key, ca = _ca("TalentForge iOS OTA E2E CA (ephemeral)", now)
    other_key, other = _ca("TalentForge iOS OTA E2E untrusted CA (ephemeral)", now)
    soon, later = now - dt.timedelta(hours=1), now + dt.timedelta(days=7)
    localhost = {"dns": ["localhost"], "ips": ["127.0.0.1"]}
    leaves = {
        "trusted": (ca, _leaf(ca_key, ca, **localhost, not_before=soon, not_after=later)),
        "untrusted": (
            other,
            _leaf(other_key, other, **localhost, not_before=soon, not_after=later),
        ),
        "expired": (
            ca,
            _leaf(
                ca_key,
                ca,
                **localhost,
                not_before=now - dt.timedelta(days=10),
                not_after=now - dt.timedelta(days=1),
            ),
        ),
        "wrong-host": (
            ca,
            _leaf(ca_key, ca, dns=["wrong-host.invalid"], ips=[], not_before=soon, not_after=later),
        ),
    }
    files: dict[str, tuple[Path, Path]] = {}
    for kind, (issuer, (key, cert)) in leaves.items():
        chain = directory / f"{kind}.pem"
        key_path = directory / f"{kind}.key"
        chain.write_bytes(
            cert.public_bytes(serialization.Encoding.PEM)
            + issuer.public_bytes(serialization.Encoding.PEM)
        )
        key_path.write_bytes(
            key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
        )
        key_path.chmod(0o600)
        files[kind] = (chain, key_path)
    ca_pem = ca.public_bytes(serialization.Encoding.PEM)
    (directory / "ca.pem").write_bytes(ca_pem)
    return Tls(ca_pem, files)


# ----------------------------------------------------------------------------- the origin


def fixture_test_key(name: str) -> ec.EllipticCurvePrivateKey:
    """The fixtures' public-by-construction ``test-*`` key (scripts/generate_ota_fixtures.py)."""
    seed = hashlib.sha256(f"talentforge-ota-v1-test-key:{name}".encode()).digest()
    order = int("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    return ec.derive_private_key(int.from_bytes(seed, "big") % (order - 1) + 1, ec.SECP256R1())


class Stand:
    """The publisher's stores, and the faulty variants of each published catalog."""

    def __init__(self, directory: Path) -> None:
        self.dir = directory
        self.report: dict[str, Any] = json.loads(
            (directory / "report.json").read_text(encoding="utf-8")
        )
        foreign = ec.generate_private_key(ec.SECP256R1())
        test_primary = fixture_test_key("primary")
        self.catalogs: dict[tuple[str, str], bytes] = {}
        for snapshot in (s["name"] for s in self.report["snapshots"]):
            envelope = json.loads((self.store(snapshot) / CATALOG).read_bytes())
            payload = base64.b64decode(envelope["payload"])
            signature = envelope["signatures"][0]
            der = bytearray(base64.b64decode(signature["sig"]))
            der[-1] ^= 0x01
            bad = dict(signature, sig=base64.b64encode(bytes(der)).decode())
            tampered = bytearray(payload)
            tampered[len(tampered) // 2] ^= 0x01
            variants = {
                "bad-signature": json.dumps(dict(envelope, signatures=[bad]), indent=2).encode(),
                "payload-flip": json.dumps(
                    dict(envelope, payload=base64.b64encode(bytes(tampered)).decode()), indent=2
                ).encode(),
                "foreign-key": build_envelope(
                    payload, {"tf-staging-foreign": sign_pae_der(foreign, payload)}
                ),
                "test-key": build_envelope(
                    payload, {"test-primary": sign_pae_der(test_primary, payload)}
                ),
            }
            for fault, body in variants.items():
                self.catalogs[(snapshot, fault)] = body

    def store(self, snapshot: str) -> Path:
        path = self.dir / "stores" / snapshot
        if not path.is_dir():
            raise HarnessError(f"unknown snapshot {snapshot}")
        return path

    def manifest_hex(self, snapshot: str) -> str:
        snap = next(s for s in self.report["snapshots"] if s["name"] == snapshot)
        return str(self.report["manifests"][snap["activeReleaseId"]]["sha256"]).removeprefix(
            "sha256:"
        )


@dataclass
class Route:
    """What one origin key serves: a publication and a fault (``name`` or ``name:argument``)."""

    snapshot: str = "1-a-staged"
    fault: str = "ok"
    blobs_served: int = 0


@dataclass
class Origin:
    stand: Stand
    routes: dict[str, Route] = field(default_factory=dict)
    log: list[dict[str, Any]] = field(default_factory=list)
    lock: threading.Lock = field(default_factory=threading.Lock)

    def record(self, entry: dict[str, Any]) -> None:
        with self.lock:
            self.log.append(entry)


def handler(origin: Origin, kind: str) -> type[http.server.BaseHTTPRequestHandler]:
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "TalentForgeE2E"
        sys_version = ""

        def log_message(self, format: str, *args: Any) -> None:
            pass

        def send(
            self, status: int, body: bytes = b"", headers: dict[str, str] | None = None
        ) -> int:
            self.send_response(status)
            for name, value in (headers or {}).items():
                self.send_header(name, value)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if body:
                self.wfile.write(body)
            return status

        def drop(self) -> int:
            """Close the TCP connection without an answer (a lost network)."""
            self.close_connection = True
            self.wfile.flush()
            self.connection.shutdown(socket.SHUT_RDWR)
            return 0

        def do_GET(self) -> None:
            url = urlsplit(self.path)
            parts = url.path.lstrip("/").split("/", 2)
            if parts[0] == "_control" and len(parts) == 2:
                query = parse_qs(url.query)
                with origin.lock:
                    route = origin.routes.setdefault(parts[1], Route())
                    route.snapshot = query.get("snapshot", [route.snapshot])[0]
                    route.fault = query.get("fault", ["ok"])[0]
                    route.blobs_served = 0
                self.send(200, b"ok")
                return
            if parts[0] == "r" and len(parts) == 3:
                # a redirect's target: answers normally, so a followed redirect would succeed
                status = self.serve(parts[1], parts[2], "ok", "")
                origin.record(
                    {"origin": kind, "key": "redirect-target", "path": parts[2], "status": status}
                )
                return
            if parts[0] != "s" or len(parts) != 3:
                self.send(404)
                return
            key, path = parts[1], parts[2]
            with origin.lock:
                route = origin.routes.setdefault(key, Route())
                if BLOB.fullmatch(path):
                    route.blobs_served += 1
                snapshot, fault, served = route.snapshot, route.fault, route.blobs_served
            status = self.apply(snapshot, fault, path, served)
            headers = dict(self.headers.items())
            origin.record(
                {
                    "origin": kind,
                    "key": key,
                    "path": path,
                    "fault": fault,
                    "status": status,
                    "headers": headers,
                }
            )

        def apply(self, snapshot: str, fault_spec: str, path: str, served: int) -> int:
            fault, _, argument = fault_spec.partition(":")
            blob = BLOB.fullmatch(path)
            target = blob.group(1) if blob else None
            if fault == "drop":
                return self.drop()
            if fault == "slow" and blob:
                time.sleep(int(argument) / 1000)
            if path == CATALOG:
                if fault == "catalog-status":
                    return self.send(int(argument))
                if fault == "catalog-drop":
                    return self.drop()
                if fault == "catalog-redirect":
                    return self.send(302, headers={"Location": f"/r/{snapshot}/{CATALOG}"})
                if (snapshot, fault) in origin.stand.catalogs:
                    body = origin.stand.catalogs[(snapshot, fault)]
                    return self.send(200, body, {"Content-Type": "application/json"})
            if fault == "manifest-status" and target == origin.stand.manifest_hex(snapshot):
                return self.send(int(argument))
            if fault == "blob-404" and target == argument:
                return self.send(404)
            if fault == "blob-drop-after" and blob and served > int(argument):
                # announces the whole file, sends 100 bytes, then the connection is lost
                data = (origin.stand.store(snapshot) / path).read_bytes()
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data[: min(100, len(data) - 1)])
                return self.drop()
            return self.serve(snapshot, path, fault, argument)

        def serve(self, snapshot: str, path: str, fault: str, argument: str) -> int:
            if path != CATALOG and BLOB.fullmatch(path) is None:
                return self.send(404)
            file = origin.stand.store(snapshot) / path
            if not file.is_file():
                return self.send(404)
            body = bytearray(file.read_bytes())
            target = path.rsplit("/", 1)[-1]
            if (fault == "manifest-flip" and target == origin.stand.manifest_hex(snapshot)) or (
                fault == "blob-flip" and target == argument
            ):
                body[min(100, len(body) - 1)] ^= 0x20
            if fault == "blob-short" and target == argument:
                del body[-10:]
            cache = (
                "public, max-age=300" if path == CATALOG else "public, max-age=31536000, immutable"
            )
            headers = {"Content-Type": "application/octet-stream", "Cache-Control": cache}
            return self.send(200, bytes(body), headers)

    return Handler


class QuietServer(http.server.ThreadingHTTPServer):
    """Connections the client cancels or a fault drops end in broken pipes: expected, not errors."""

    def handle_error(self, request: Any, client_address: Any) -> None:
        if not isinstance(sys.exc_info()[1], (ConnectionError, ssl.SSLError, TimeoutError)):
            super().handle_error(request, client_address)


class Server:
    def __init__(self, origin: Origin, kind: str, chain: Path, key: Path, port: int = 0) -> None:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(chain, key)
        self.httpd = QuietServer(("127.0.0.1", port), handler(origin, kind))
        self.httpd.daemon_threads = True
        self.httpd.socket = context.wrap_socket(self.httpd.socket, server_side=True)
        self.port = int(self.httpd.server_address[1])
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()


def closed_port() -> int:
    """A loopback port nothing listens on (connection refused: the origin is down)."""
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


# ----------------------------------------------------------------------------- simulator


def run_command(*args: str, check: bool = True, **kwargs: Any) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(args, capture_output=True, text=True, **kwargs)
    if check and result.returncode != 0:
        raise HarnessError(
            f"{' '.join(args)} failed ({result.returncode}): {result.stderr.strip()[-2000:]}"
        )
    return result


def pick_device(requested: str | None) -> tuple[str, str, str, str]:
    """(device type identifier, its name, the newest iOS runtime identifier, its version)."""
    listing = json.loads(run_command("xcrun", "simctl", "list", "-j", "runtimes").stdout)
    runtimes = [
        r for r in listing["runtimes"] if r.get("isAvailable") and r.get("platform") == "iOS"
    ]
    if not runtimes:
        raise HarnessError("no available iOS simulator runtime")
    runtime = max(runtimes, key=lambda r: [int(p) for p in r["version"].split(".")])
    supported = {t["name"]: t["identifier"] for t in runtime.get("supportedDeviceTypes", [])}
    for name in [requested] if requested else DEFAULT_DEVICE_TYPES:
        if name in supported:
            return supported[name], name, runtime["identifier"], runtime["version"]
    wanted = requested or " / ".join(DEFAULT_DEVICE_TYPES)
    raise HarnessError(f"{wanted} is not supported by {runtime['name']}")


@dataclass
class Simulator:
    udid: str
    device: str
    runtime: str

    @classmethod
    def create(cls, requested: str | None, ca: Path) -> Simulator:
        type_id, name, runtime_id, version = pick_device(requested)
        created = run_command(
            "xcrun", "simctl", "create", f"TF OTA E2E {os.getpid()}", type_id, runtime_id
        )
        simulator = cls(created.stdout.strip(), name, f"iOS {version}")
        run_command("xcrun", "simctl", "bootstatus", simulator.udid, "-b", timeout=900)
        # the run's CA becomes a trusted root of this simulator only: TLS is verified, not skipped
        run_command("xcrun", "simctl", "keychain", simulator.udid, "add-root-cert", str(ca))
        return simulator

    def delete(self) -> None:
        run_command("xcrun", "simctl", "shutdown", self.udid, check=False)
        run_command("xcrun", "simctl", "delete", self.udid, check=False)


# ----------------------------------------------------------------------------- phases


def run_phase(
    simulator: Simulator, config: Path, method: str, expect: str, logs: Path
) -> dict[str, Any]:
    env = dict(os.environ, SIMCTL_CHILD_TF_IOS_OTA_E2E=str(config))
    marker = config.parent / "killed" / method
    started = time.monotonic()
    pattern = f"--ktest_regex_filter={re.escape(TEST_CLASS)}\\.{method}"
    result = subprocess.run(
        ["xcrun", "simctl", "spawn", simulator.udid, str(TEST_BINARY), pattern],
        capture_output=True,
        text=True,
        env=env,
        timeout=1800,
    )
    seconds = round(time.monotonic() - started, 1)
    (logs / f"{method}.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    ran = f"[ RUN      ] {TEST_CLASS}.{method}" in result.stdout
    ok = f"[       OK ] {TEST_CLASS}.{method}" in result.stdout
    if expect == "killed":
        # the phase writes its marker right before kill(getpid(), SIGKILL): no result line follows
        passed = ran and marker.is_file() and not ok and result.returncode != 0
        found = "present" if marker.is_file() else "missing"
        detail = f"exit {result.returncode} (killed), marker {found}"
    else:
        passed = ran and ok and result.returncode == 0
        detail = f"exit {result.returncode}"
    return {
        "method": method,
        "expect": expect,
        "passed": passed,
        "detail": detail,
        "seconds": seconds,
    }


def inside_git_checkout(path: Path) -> bool:
    probe = path
    while not probe.exists():
        probe = probe.parent
    git = subprocess.run(["git", "-C", str(probe), "rev-parse"], capture_output=True)
    return git.returncode == 0


def run(args: argparse.Namespace) -> int:
    work = args.work.expanduser().resolve()
    if inside_git_checkout(work):
        raise HarnessError(f"{work} is inside a git checkout; the run writes TLS keys")
    if work.exists() and any(work.iterdir()):
        raise HarnessError(f"{work} is not empty")
    work.mkdir(parents=True, exist_ok=True)
    stand_dir = args.stand.expanduser().resolve() if args.stand else work / "stand"
    if not args.stand:
        run_command(
            sys.executable,
            str(ROOT / "scripts/ota_staging_stand.py"),
            "prepare",
            str(stand_dir),
            timeout=900,
        )
    if not args.skip_build:
        run_command(
            "./gradlew",
            ":composeApp:linkDebugTestIosSimulatorArm64",
            "--console=plain",
            cwd=ROOT / "mobile",
            timeout=3600,
        )
    if not TEST_BINARY.is_file():
        raise HarnessError(f"{TEST_BINARY} is missing (:composeApp:linkDebugTestIosSimulatorArm64)")
    stand = Stand(stand_dir)
    tls = make_tls(work / "tls")
    origin = Origin(stand)
    servers = {kind: Server(origin, kind, *paths) for kind, paths in tls.files.items()}
    simulator: Simulator | None = None
    results: list[dict[str, Any]] = []
    try:
        simulator = Simulator.create(args.device_type, work / "tls/ca.pem")
        config = work / "e2e-config.json"
        for directory in ("killed", "results", "logs"):
            (work / directory).mkdir()
        settings = {
            "stand": str(stand_dir),
            "work": str(work),
            "origin": f"https://localhost:{servers['trusted'].port}",
            "untrustedOrigin": f"https://localhost:{servers['untrusted'].port}",
            "expiredOrigin": f"https://localhost:{servers['expired'].port}",
            "wrongHostOrigin": f"https://localhost:{servers['wrong-host'].port}",
            "closedOrigin": f"https://localhost:{closed_port()}",
            "unresolvableOrigin": "https://tf-ota-e2e.invalid",
        }
        config.write_text(json.dumps(settings, indent=2), encoding="utf-8")
        for name, method, expect in PHASES:
            if args.only and name not in args.only:
                continue
            result = {"phase": name, **run_phase(simulator, config, method, expect, work / "logs")}
            results.append(result)
            verdict = "PASS" if result["passed"] else "FAIL"
            print(f"{verdict}  {name:32} {result['detail']} ({result['seconds']} s)", flush=True)
    finally:
        for server in servers.values():
            server.close()
        if simulator and not args.keep_device:
            simulator.delete()
    lines: list[str] = []
    for file in sorted((work / "results").glob("*.txt")):
        lines += file.read_text(encoding="utf-8").splitlines()
    report = {
        "format": "talentforge-ios-ota-e2e",
        "at": dt.datetime.now(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "xcode": " ".join(run_command("xcodebuild", "-version").stdout.split()),
        "simulator": {
            "device": simulator.device if simulator else None,
            "runtime": simulator.runtime if simulator else None,
        },
        "stand": {
            "keyId": stand.report["keyId"],
            "commit": stand.report["commit"],
            "releases": stand.report["releases"],
        },
        "phases": results,
        "results": lines,
        "requests": len(origin.log),
        "requestsOnRefusedTlsOrigins": sum(1 for e in origin.log if e["origin"] != "trusted"),
        "redirectTargetRequests": sum(1 for e in origin.log if e["key"] == "redirect-target"),
        "result": "PASS" if results and all(r["passed"] for r in results) else "FAIL",
    }
    (work / "ios-ota-e2e-report.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8"
    )
    print(
        f"requests served: {report['requests']}; on refused TLS origins: "
        f"{report['requestsOnRefusedTlsOrigins']}; "
        f"redirect target: {report['redirectTargetRequests']}"
    )
    print(f"RESULT: {report['result']} ({work / 'ios-ota-e2e-report.json'})")
    return EXIT_PASS if report["result"] == "PASS" else EXIT_FAIL


# ----------------------------------------------------------------------------- the app itself

BUNDLE_ID = "io.github.alexeykravtsov95.talentforge"
APP_KEY = "app"
OTA_STATE = "Library/Application Support/TalentForge/ota/v1/state.json"


def app_env(stand: Path, port: int) -> dict[str, str]:
    """The Debug build settings of an app that reads this run's staging origin (never a release)."""
    return {
        "TALENTFORGE_OTA_IOS_STAGING_ENABLED": "true",
        "TALENTFORGE_OTA_IOS_STAGING_BASEURL": f"https://localhost:{port}/s/{APP_KEY}",
        "TALENTFORGE_OTA_IOS_STAGING_KEYRING": str(stand.resolve() / "keys/staging-keyring.json"),
    }


@dataclass
class AppRun:
    simulator: Simulator
    origin: Origin
    work: Path
    checks: list[dict[str, Any]] = field(default_factory=list)

    def check(self, check_id: str, passed: bool, detail: str) -> None:
        self.checks.append({"id": check_id, "passed": passed, "detail": detail})
        print(f"{'PASS' if passed else 'FAIL'}  {check_id:34} {detail}", flush=True)

    def serve(self, snapshot: str, fault: str = "ok") -> None:
        with self.origin.lock:
            self.origin.routes[APP_KEY] = Route(snapshot=snapshot, fault=fault)

    def requests(self) -> list[dict[str, Any]]:
        with self.origin.lock:
            return [e for e in self.origin.log if e["key"] == APP_KEY]

    def launch(self) -> int:
        out = run_command("xcrun", "simctl", "launch", self.simulator.udid, BUNDLE_ID).stdout
        return int(out.strip().rsplit(":", 1)[1])

    def terminate(self) -> None:
        run_command("xcrun", "simctl", "terminate", self.simulator.udid, BUNDLE_ID, check=False)

    def running(self) -> bool:
        listing = run_command(
            "xcrun", "simctl", "spawn", self.simulator.udid, "launchctl", "list"
        ).stdout
        return any(
            f"UIKitApplication:{BUNDLE_ID}" in line and line.split()[0].isdigit()
            for line in listing.splitlines()
        )

    def state(self) -> dict[str, Any] | None:
        container = run_command(
            "xcrun", "simctl", "get_app_container", self.simulator.udid, BUNDLE_ID, "data"
        ).stdout.strip()
        path = Path(container) / OTA_STATE
        return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None

    def wait(self, what: str, condition: Any, seconds: float = 90) -> bool:
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if condition():
                return True
            time.sleep(0.25)
        print(f"       timed out waiting for {what}", flush=True)
        return False

    def screenshot(self, name: str) -> None:
        run_command(
            "xcrun",
            "simctl",
            "io",
            self.simulator.udid,
            "screenshot",
            str(self.work / f"{name}.png"),
        )

    def install(self, app: Path) -> None:
        run_command("xcrun", "simctl", "uninstall", self.simulator.udid, BUNDLE_ID, check=False)
        run_command("xcrun", "simctl", "install", self.simulator.udid, str(app))


def active(run: AppRun) -> Any:
    """The release ``state.json`` names active, or None."""
    return (run.state() or {}).get("active")


def inet_sockets(pid: int) -> list[str]:
    """The Internet sockets the process ``pid`` has open (``lsof``, run on the host)."""
    listing = subprocess.run(
        ["lsof", "-nP", "-a", "-p", str(pid), "-i"], capture_output=True, text=True
    )
    return listing.stdout.splitlines()[1:]


def app_scenarios(run: AppRun, ota_app: Path, off_app: Path, stand: Stand) -> None:
    b_id = stand.report["releases"]["b"]
    manifest_b = stand.report["manifests"][b_id]["sha256"].removeprefix("sha256:")

    # 1. first launch: bundled data at once, the automatic check after the first frame activates B
    run.install(ota_app)
    run.serve("2-b-staged")
    run.launch()
    activated = run.wait(
        "B active in state.json", lambda: (run.state() or {}).get("active") == b_id
    )
    requests = run.requests()
    paths = sorted({e["path"].rsplit("/", 1)[-1] for e in requests})
    agents = sorted({e["headers"].get("User-Agent", "") for e in requests})
    headers = sorted({name for e in requests for name in e["headers"]})
    languages = sorted({e["headers"].get("Accept-Language", "") for e in requests})
    run.check(
        "app-first-launch-activates",
        activated and run.running() and len(requests) >= 2,
        f"{len(requests)} HTTPS requests by the app (catalog, the manifest {manifest_b[:12]}…; "
        f"files from the app's bundle), state.json active={active(run)}, running={run.running()}",
    )
    run.check(
        "app-request-headers",
        all(a.startswith("TalentForge/") for a in agents)
        and "Cookie" not in headers
        and languages == ["*"],
        f"User-Agent {agents}; Accept-Language {languages} (no device language); "
        f"header names {headers}; no Cookie; paths {paths[:3]}…",
    )
    run.screenshot("app-1-after-activation")

    # 2. relaunch without network: B stays active, the app runs
    run.terminate()
    run.serve("2-b-staged", "drop")
    before = (run.state() or {}).get("active")
    run.launch()
    time.sleep(8)
    run.check(
        "app-relaunch-offline-keeps-b",
        run.running() and (run.state() or {}).get("active") == b_id == before,
        f"relaunched with every connection dropped: running={run.running()}, active={active(run)}",
    )
    run.terminate()

    # 3. termination during the install, then a cold start: no half install, B activated
    run.install(ota_app)
    run.serve("2-b-staged", "slow:4000")  # the manifest (a blob) answers after 4 s
    start = len(run.requests())
    run.launch()
    reached = run.wait("the catalog request", lambda: len(run.requests()) > start, 60)
    time.sleep(1)
    run.terminate()
    mid = run.state()
    run.serve("2-b-staged")
    run.launch()
    resumed = run.wait(
        "B active after the relaunch", lambda: (run.state() or {}).get("active") == b_id
    )
    run.check(
        "app-terminated-mid-install-recovers",
        reached and (mid is None or mid.get("active") != b_id) and resumed and run.running(),
        f"terminated with the manifest in flight (then active={(mid or {}).get('active')}); "
        f"after the cold start: active={active(run)}, running={run.running()}",
    )
    run.terminate()

    # 4. background during the check cancels it; foreground resumes it
    run.install(ota_app)
    run.serve("2-b-staged", "slow:4000")
    start = len(run.requests())
    run.launch()
    run.wait("the catalog request", lambda: len(run.requests()) > start, 60)
    run_command("xcrun", "simctl", "launch", run.simulator.udid, "com.apple.Preferences")
    time.sleep(6)
    background_state = (run.state() or {}).get("active")
    run.serve("2-b-staged")
    run.launch()  # brings the app back to the foreground
    resumed = run.wait(
        "B active after the foreground", lambda: (run.state() or {}).get("active") == b_id
    )
    run.check(
        "app-background-then-foreground",
        background_state != b_id and resumed,
        f"in the background for 6 s: active={background_state}; "
        f"back in the foreground: active={active(run)}",
    )
    run.screenshot("app-2-after-foreground")
    run.terminate()

    # 5. OTA OFF (the shipped kill switch): no Internet socket and no request at all
    run.install(off_app)
    run.serve("2-b-staged")
    start = len(run.origin.log)
    pid = run.launch()
    sockets: list[str] = []
    for _ in range(15):
        sockets += inet_sockets(pid)
        time.sleep(1)
    run.check(
        "app-ota-off-no-network",
        run.running() and not sockets and len(run.origin.log) == start and run.state() is None,
        f"15 s after launch: running={run.running()}, Internet sockets {sockets or 'none'} (lsof), "
        f"requests {len(run.origin.log) - start}, "
        f"OTA directory {'absent' if run.state() is None else 'present'}",
    )
    run.screenshot("app-3-ota-off")
    run.terminate()


def app(args: argparse.Namespace) -> int:
    work = args.work.expanduser().resolve()
    if inside_git_checkout(work):
        raise HarnessError(f"{work} is inside a git checkout; the run writes TLS keys")
    if work.exists() and any(work.iterdir()):
        raise HarnessError(f"{work} is not empty")
    work.mkdir(parents=True)
    stand = Stand(args.stand.expanduser().resolve())
    tls = make_tls(work / "tls")
    origin = Origin(stand)
    server = Server(origin, "trusted", *tls.files["trusted"], port=args.port)
    simulator: Simulator | None = None
    try:
        simulator = Simulator.create(args.device_type, work / "tls/ca.pem")
        run = AppRun(simulator, origin, work)
        app_scenarios(run, args.ota_app.resolve(), args.off_app.resolve(), stand)
    finally:
        server.close()
        if simulator and not args.keep_device:
            simulator.delete()
    report = {
        "format": "talentforge-ios-ota-app-e2e",
        "at": dt.datetime.now(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "xcode": " ".join(run_command("xcodebuild", "-version").stdout.split()),
        "simulator": {
            "device": simulator.device if simulator else None,
            "runtime": simulator.runtime if simulator else None,
        },
        "checks": run.checks,
        "requests": [{k: e[k] for k in ("path", "fault", "status", "headers")} for e in origin.log],
        "result": "PASS" if run.checks and all(c["passed"] for c in run.checks) else "FAIL",
    }
    (work / "ios-ota-app-report.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8"
    )
    print(f"RESULT: {report['result']} ({work / 'ios-ota-app-report.json'})")
    return EXIT_PASS if report["result"] == "PASS" else EXIT_FAIL


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0] if __doc__ else None)
    sub = parser.add_subparsers(dest="command", required=True)
    env_parser = sub.add_parser("app-env", help="print the Debug build settings for `app`")
    env_parser.add_argument("stand", type=Path)
    env_parser.add_argument("--port", type=int, default=8443)
    app_parser = sub.add_parser("app", help="drive the real app (Debug staging and OTA OFF builds)")
    app_parser.add_argument("work", type=Path, help="an empty directory outside any git checkout")
    app_parser.add_argument("--stand", type=Path, required=True)
    app_parser.add_argument(
        "--ota-app", type=Path, required=True, help="Debug .app built with app-env"
    )
    app_parser.add_argument(
        "--off-app", type=Path, required=True, help="a .app built without OTA settings"
    )
    app_parser.add_argument("--port", type=int, default=8443)
    app_parser.add_argument("--device-type")
    app_parser.add_argument("--keep-device", action="store_true")
    run_parser = sub.add_parser("run", help="run every phase on a new simulator")
    run_parser.add_argument("work", type=Path, help="an empty directory outside any git checkout")
    run_parser.add_argument("--stand", type=Path, help="an existing stand (default: prepare one)")
    run_parser.add_argument(
        "--device-type",
        help=f"simulator device type (default: {' or '.join(DEFAULT_DEVICE_TYPES)})",
    )
    run_parser.add_argument(
        "--skip-build", action="store_true", help="use the linked test binary as it is"
    )
    run_parser.add_argument("--keep-device", action="store_true", help="keep the simulator")
    run_parser.add_argument("--only", nargs="*", help="run only these phases (names above)")
    args = parser.parse_args(argv)
    if args.command == "app-env":
        for name, value in app_env(args.stand, args.port).items():
            print(f"export {name}={value}")
        return EXIT_PASS
    try:
        return app(args) if args.command == "app" else run(args)
    except (HarnessError, subprocess.TimeoutExpired) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return EXIT_ERROR


if __name__ == "__main__":
    sys.exit(main())
