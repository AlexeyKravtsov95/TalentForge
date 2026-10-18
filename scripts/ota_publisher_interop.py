"""Publisher -> client interop stores (M5.OTA.3 integration check, offline, TEST keys only).

Runs the real M5.OTA.2 publisher CLI (``talent-data ota ...``) against a local directory
standing in for the bucket, exactly like the offline rehearsal of
``docs/ota/publisher-runbook.md`` §0, and keeps a copy of the store after every production
publication. The Kotlin client then installs from those copies over HTTP
(``OtaPublisherInteropTest``, opt-in), so the two independently written components are
checked against each other, not only against the shared fixtures.

    python scripts/ota_publisher_interop.py <out>
    cd mobile && ./gradlew :composeApp:desktopTest --tests "*OtaPublisherInteropTest*" \\
        -Dtalentforge.ota.interop=<out>

``<out>`` receives ``keys/keyring.json`` (test-primary + test-backup public keys),
``artifacts/<releaseId>/release.json`` (the built manifests) and ``stores/<n>-<step>/``:

    1-a-active      staging catalog: A (``stage``); production catalog: A
    2-b-active      production: A, B listed, B active (append-only promote)
    3-rollback-a    production: rollback to A (new sequence, nothing unlisted)

Signing uses only the public-seed TEST keys; nothing here can sign for an app.
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "src"))

from tests.unit.ota.publisher_testkit import (  # noqa: E402
    BUILD_A,
    BUILD_B,
    COMMIT,
    GAME,
    PRIMARY,
    fixture_manifest_sha256,
    write_approval_record,
    write_test_keyring,
)

from talent_data.cli.main import main as cli_main  # noqa: E402

NOW = "2026-10-18T12:00:00Z"
CREATED_AT = "2026-10-17T23:00:00Z"
A = f"{BUILD_A}-r1"
B = f"{BUILD_B}-r1"


class Publisher:
    def __init__(self, out: Path) -> None:
        self.out = out
        self.store = out / "store"
        self.artifacts = out / "artifacts"
        self.docs = out / "approvals"
        self.keyring, self.pem = write_test_keyring(out / "keys")
        for release in (A, B):
            write_approval_record(
                self.docs, release, manifest_sha256=fixture_manifest_sha256(release)
            )

    def cli(self, *args: str) -> None:
        code = cli_main(["ota", *args, "--repo", str(ROOT)])
        if code != 0:
            raise SystemExit(f"talent-data ota {args[0]} exited with {code}")

    def common(self) -> list[str]:
        return ["--game", GAME, "--store", str(self.store), "--keyring", str(self.keyring)]

    def manifest(self, build: str) -> str:
        return str(self.artifacts / f"{build}-r1" / "release.json")

    def build(self, build: str) -> None:
        self.cli(
            "build-release", "--game", GAME, "--build", build, "--revision", "1",
            "--commit", COMMIT, "--created-at", CREATED_AT, "--out", str(self.artifacts),
        )  # fmt: skip
        self.cli(
            "validate", *self.common(), "--manifest", self.manifest(build),
            "--releases-docs", str(self.docs), "--now", NOW,
        )  # fmt: skip

    def stage(self, build: str) -> None:
        self.cli(
            "stage", *self.common(), "--manifest", self.manifest(build),
            "--key", str(self.pem), "--key-id", PRIMARY, "--execute", "--now", NOW,
        )  # fmt: skip

    def promote(self, build: str) -> None:
        self.cli(
            "promote", *self.common(), "--manifest", self.manifest(build),
            "--releases-docs", str(self.docs), "--out", str(self.artifacts),
            "--execute", "--now", NOW,
        )  # fmt: skip
        self.attach()

    def rollback(self, release: str) -> None:
        self.cli(
            "rollback", *self.common(), "--active", release, "--out", str(self.artifacts),
            "--execute", "--now", NOW,
        )  # fmt: skip
        self.attach()

    def attach(self) -> None:
        payload = self.artifacts / "pending-production.payload.json"
        signature = self.artifacts / "pending-production.signature.der"
        self.cli(
            "sign", "--key", str(self.pem), "--key-id", PRIMARY,
            "--payload", str(payload), "--out", str(signature),
        )  # fmt: skip
        self.cli(
            "attach-signature", *self.common(), "--payload", str(payload),
            "--signature", str(signature), "--key-id", PRIMARY,
            "--releases-docs", str(self.docs), "--channel", "production",
            "--execute", "--now", NOW,
        )  # fmt: skip
        self.verify_live("production")

    def verify_live(self, channel: str) -> None:
        self.cli("verify-live", *self.common(), "--channel", channel, "--now", NOW)

    def snapshot(self, name: str) -> None:
        shutil.copytree(self.store, self.out / "stores" / name)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("out", type=Path, help="an empty or absent directory")
    out: Path = parser.parse_args(argv).out
    if out.exists() and any(out.iterdir()):
        parser.error(f"{out} is not empty")
    publisher = Publisher(out)
    publisher.build(BUILD_A)
    publisher.stage(BUILD_A)
    publisher.verify_live("staging")
    publisher.promote(BUILD_A)
    publisher.snapshot("1-a-active")
    publisher.build(BUILD_B)
    publisher.promote(BUILD_B)
    publisher.snapshot("2-b-active")
    publisher.rollback(A)
    publisher.snapshot("3-rollback-a")
    print(f"interop stores written to {out / 'stores'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
