"""The local OTA staging stand (M5.OTA.4a, ``scripts/ota_staging_stand.py``).

The stand runs the real publisher CLI with an ephemeral ``tf-staging-local-*`` key and
serves the result over HTTPS. These tests prove the Python half: what was published,
that it verifies only as a staging catalog under the staging key, that G6 alone stopped
the unsupported mechanic, and that the HTTPS origin serves the two contract paths only.
The Kotlin half (the real client installing these exact bytes over HTTPS) is
``OtaStagingE2ETest`` in ``mobile/composeApp``.
"""

from __future__ import annotations

import base64
import importlib.util
import json
import socket
import ssl
import stat
import sys
import threading
import urllib.error
import urllib.request
from collections.abc import Iterator
from datetime import timedelta
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

from talent_data.ota.models import Channel
from talent_data.ota.verify import (
    OtaError,
    OtaErrorCode,
    TrustedKey,
    load_keyring,
    verify_catalog,
)

ROOT = Path(__file__).resolve().parents[3]
FIXTURE_KEYRING = ROOT / "docs/ota/fixtures/v1/keyring.json"
NOW = "2026-10-18T12:00:00Z"


def _stand() -> ModuleType:
    name = "ota_staging_stand"
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


stand = _stand()


@pytest.fixture(scope="module")
def out(tmp_path_factory: pytest.TempPathFactory) -> Path:
    directory: Path = tmp_path_factory.mktemp("stand") / "out"
    stand.prepare(directory, commit=None, now=stand.utc(NOW))
    return directory


@pytest.fixture(scope="module")
def report(out: Path) -> dict[str, Any]:
    loaded: dict[str, Any] = json.loads((out / "report.json").read_text(encoding="utf-8"))
    return loaded


def _keyring(path: Path) -> dict[str, TrustedKey]:
    return load_keyring(json.loads(path.read_text(encoding="utf-8"))["keys"])


def _catalog(out: Path, snapshot: str) -> bytes:
    data: bytes = (out / "stores" / snapshot / stand.CATALOG_KEY).read_bytes()
    return data


def test_the_publications_of_the_real_publisher(report: dict[str, Any]) -> None:
    summary = [(s["name"], s["sequence"], s["activeReleaseId"]) for s in report["snapshots"]]
    assert summary == [
        ("1-a-staged", 1, stand.A),
        ("2-b-staged", 2, stand.B),
        ("3-rollback-a", 3, stand.A),
        ("4-level-2", 4, stand.B_LEVEL_2),
    ]
    listed = [s["releases"] for s in report["snapshots"]]
    assert listed[1] == listed[2] == [stand.A, stand.B]  # a rollback unlists nothing
    assert listed[3] == [stand.A, stand.B, stand.B_LEVEL_2]  # append-only
    for snapshot in report["snapshots"]:
        for release_id, sha in snapshot["manifests"].items():
            assert report["manifests"][release_id]["sha256"] == sha  # the built bytes
    commands = {entry["command"] for entry in report["publisherLog"]}
    published = {"build-release", "validate", "stage", "rollback", "sign", "attach-signature"}
    assert published <= commands
    assert "promote" not in commands  # nothing is ever published to production


def test_every_catalog_verifies_only_as_staging_under_the_staging_key(
    out: Path, report: dict[str, Any]
) -> None:
    keyring = _keyring(out / report["keyring"])
    assert list(keyring) == [report["keyId"]]
    assert report["keyId"].startswith("tf-staging-local-")
    now = stand.utc(report["now"])
    for snapshot in report["snapshots"]:
        envelope = _catalog(out, snapshot["name"])
        verified = verify_catalog(
            envelope, keyring=keyring, game="forever", channel=Channel.STAGING, now=now,
            last_seen=None,
        )  # fmt: skip
        assert verified.catalog.sequence == snapshot["sequence"]
        assert [s["keyId"] for s in json.loads(envelope)["signatures"]] == [report["keyId"]]
        with pytest.raises(OtaError) as production:
            verify_catalog(
                envelope, keyring=keyring, game="forever", channel=Channel.PRODUCTION,
                now=now, last_seen=None,
            )  # fmt: skip
        assert production.value.code is OtaErrorCode.CHANNEL_MISMATCH
        with pytest.raises(OtaError) as fixtures:
            verify_catalog(
                envelope, keyring=_keyring(FIXTURE_KEYRING), game="forever",
                channel=Channel.STAGING, now=now, last_seen=None,
            )  # fmt: skip
        assert fixtures.value.code is OtaErrorCode.UNTRUSTED_SIGNER
        validity = stand.utc(snapshot["expiresAt"]) - stand.utc(snapshot["issuedAt"])
        assert validity == timedelta(days=45)  # OD-13


def test_g6_alone_stops_the_unsupported_mechanic_and_nothing_is_published(
    report: dict[str, Any],
) -> None:
    g6 = report["g6"]
    assert g6["validateExit"] == 1 and g6["stageExit"] == 1 and g6["storeUnchanged"]
    # legitimate in every other respect (on main, reproducible, wire-valid): G6 refuses it
    assert {gate for gate, status in g6["gates"].items() if status == "STOP"} == {"G6"}
    for snapshot in report["snapshots"]:
        assert g6["releaseId"] not in snapshot["releases"]
        assert g6["manifestSha256"] not in snapshot["manifests"].values()


def test_keys_stay_outside_git_and_private(out: Path, report: dict[str, Any]) -> None:
    assert not stand.inside_git_checkout(out)
    for private in (out / "keys" / f"{report['keyId']}.pem", out / report["tls"]["key"]):
        assert stat.S_IMODE(private.stat().st_mode) == 0o600
        assert b"PRIVATE KEY" in private.read_bytes()
    for public in (out / report["keyring"], out / report["tls"]["ca"], out / "report.json"):
        assert b"PRIVATE KEY" not in public.read_bytes()
    assert not list((out / "tls").glob("ca.key*"))  # the CA key is never written


def test_prepare_refuses_a_directory_inside_a_git_checkout() -> None:
    target = ROOT / "artifacts" / "ota-stand-refused"
    with pytest.raises(stand.StandError, match="inside a git checkout"):
        stand.prepare(target, commit=None, now=stand.utc(NOW))
    assert not target.exists()


# --------------------------------------------------------------------------- HTTPS origin


@pytest.fixture(scope="module")
def origin(out: Path) -> Iterator[str]:
    server = stand.make_server(out, host="127.0.0.1", port=0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"https://localhost:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()


def _trusting(out: Path) -> ssl.SSLContext:
    """Explicit trust in the stand's CA only; certificate and hostname checks stay on."""
    context = ssl.create_default_context(cafile=str(out / "tls" / "ca.pem"))
    assert context.verify_mode is ssl.CERT_REQUIRED and context.check_hostname
    return context


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args: Any, **kwargs: Any) -> None:
        return None


def _get(url: str, context: ssl.SSLContext, method: str = "GET") -> tuple[int, bytes, Any]:
    opener = urllib.request.build_opener(
        urllib.request.HTTPSHandler(context=context), _NoRedirect()
    )
    try:
        with opener.open(urllib.request.Request(url, method=method), timeout=10) as response:
            return response.status, response.read(), response.headers
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.headers


def test_the_origin_serves_the_live_catalog_and_blobs(
    out: Path, origin: str, report: dict[str, Any]
) -> None:
    context = _trusting(out)
    stand.activate(out, "2-b-staged")
    try:
        status, body, headers = _get(f"{origin}/{stand.CATALOG_KEY}", context)
        assert status == 200 and body == _catalog(out, "2-b-staged")
        assert headers["Cache-Control"] == "public, max-age=300"
        payload = json.loads(base64.b64decode(json.loads(body)["payload"]))
        manifest_hex = payload["releases"][-1]["manifestSha256"].removeprefix("sha256:")
        status, body, headers = _get(f"{origin}/v1/blobs/sha256/{manifest_hex}", context)
        assert status == 200
        assert body == (out / report["manifests"][stand.B]["path"]).read_bytes()
        assert headers["Cache-Control"] == "public, max-age=31536000, immutable"
        stand.activate(out, "3-rollback-a")  # one atomic switch, like a catalog PUT
        assert _get(f"{origin}/{stand.CATALOG_KEY}", context)[1] == _catalog(out, "3-rollback-a")
    finally:
        stand.activate(out, "1-a-staged")


@pytest.mark.parametrize(
    "path",
    [
        "/v1/channels/production/catalog.json",
        "/v1/blobs/sha256/" + "0" * 64,
        "/v1/blobs/sha256/../../channels/staging/catalog.json",
        "/v1/blobs/sha256/%2e%2e/catalog.json",
        "/report.json",
        "/keys/staging-keyring.json",
        "/",
    ],
)
def test_the_origin_serves_nothing_else_and_never_redirects(
    out: Path, origin: str, path: str
) -> None:
    status, _, headers = _get(origin + path, _trusting(out))
    assert status == 404
    assert "Location" not in headers


def test_the_origin_accepts_only_get(out: Path, origin: str) -> None:
    assert _get(f"{origin}/{stand.CATALOG_KEY}", _trusting(out), method="HEAD")[0] == 405
    assert _get(f"{origin}/{stand.CATALOG_KEY}", _trusting(out), method="POST")[0] == 405


def test_tls_is_verified_and_plaintext_is_refused(out: Path, origin: str) -> None:
    # without the stand's CA the certificate does not verify: nothing is weakened
    with pytest.raises(urllib.error.URLError) as untrusted:
        _get(f"{origin}/{stand.CATALOG_KEY}", ssl.create_default_context())
    assert isinstance(untrusted.value.reason, ssl.SSLCertVerificationError)
    # a name the certificate does not carry fails hostname verification
    port = int(origin.rsplit(":", 1)[1])
    with (
        socket.create_connection(("127.0.0.1", port), timeout=10) as raw,
        pytest.raises(ssl.SSLCertVerificationError, match=r"match"),
    ):
        _trusting(out).wrap_socket(raw, server_hostname="ota.invalid")
    # plain HTTP on the TLS port never gets an HTTP response
    with socket.create_connection(("127.0.0.1", port), timeout=10) as plain:
        plain.sendall(b"GET /" + stand.CATALOG_KEY.encode() + b" HTTP/1.1\r\nHost: x\r\n\r\n")
        try:
            answer = plain.recv(1024)
        except ConnectionResetError:
            answer = b""
        assert not answer.startswith(b"HTTP/")


# ------------------------------------------------------------------------ any pair (M5.OTA.AUTO.4)


def test_the_default_pair_is_the_historical_replay(report: dict[str, Any]) -> None:
    assert report["builds"] == {"a": stand.BUILD_A, "b": stand.BUILD_B}
    assert report["releases"] == {"a": stand.A, "b": stand.B, "bLevel2": stand.B_LEVEL_2}


def test_the_stand_publishes_the_pair_it_is_given(tmp_path: Path) -> None:
    """``--base`` / ``--candidate``: the same four publications, for another committed pair."""
    base, candidate = "1.60.1.70450", "1.60.1.70532"
    report = stand.prepare(
        tmp_path / "pair", commit=None, now=stand.utc(NOW), base=base, candidate=candidate
    )
    a, b = f"{base}-r1", f"{candidate}-r1"
    assert report["builds"] == {"a": base, "b": candidate}
    assert report["releases"] == {"a": a, "b": b, "bLevel2": f"{candidate}-r2"}
    listed = {s["name"]: (s["activeReleaseId"], s["releases"]) for s in report["snapshots"]}
    assert listed == {
        "1-a-staged": (a, [a]),
        "2-b-staged": (b, [a, b]),
        "3-rollback-a": (a, [a, b]),
        "4-level-2": (f"{candidate}-r2", [a, b, f"{candidate}-r2"]),
    }
    assert report["g6"]["releaseId"] == f"{candidate}-r3" and report["g6"]["gates"]["G6"] == "STOP"
    manifest = json.loads((tmp_path / "pair" / report["manifests"][b]["path"]).read_bytes())
    assert manifest["clientBuild"] == candidate


def test_the_stand_refuses_one_build_as_both_halves(tmp_path: Path) -> None:
    with pytest.raises(stand.StandError, match="the same build"):
        stand.prepare(
            tmp_path / "same",
            commit=None,
            now=stand.utc(NOW),
            base=stand.BUILD_B,
            candidate=stand.BUILD_B,
        )
    assert not (tmp_path / "same").exists()
