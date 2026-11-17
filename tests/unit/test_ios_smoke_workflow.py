"""Keep required device coverage and failure isolation in the smoke workflow."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_smoke_jobs_are_isolated_and_always_upload_evidence() -> None:
    workflow = (ROOT / ".github/workflows/mobile-ci.yml").read_text()
    build, smoke = workflow.split("  ios-simulator-smoke:", 1)
    smoke = smoke.split("  ios-ota-e2e:", 1)[0]
    assert "Simulator launch smoke" not in build
    assert "needs: mobile-macos" in smoke
    assert "fail-fast: false" in smoke
    for device in ("iPhone 18 Pro", "iPhone 18 Pro Max", "iPad Pro 13-inch (M5)"):
        assert f"- device: {device}\n" in smoke
    assert "continue-on-error" not in smoke
    assert "if: always()" in smoke
    assert "path: ios-smoke/" in smoke
    assert "if-no-files-found: error" in smoke
    assert "timeout-minutes: 27" in smoke and "timeout-minutes: 35" in smoke
    assert "actions/download-artifact@v4" in smoke
    assert "name: ios-simulator-app" in build and "name: ios-simulator-app" in smoke
    assert "run-id:" not in smoke  # download is restricted to this run
    assert "tar -C" in build and "tar -xzf" in smoke
