"""Simulator runtime choice of the iOS OTA E2E harness (M5.IOS.2).

``scripts/ios_ota_e2e.py`` creates its simulator from ``xcrun simctl list -j runtimes``. The
runtime is chosen per device type — the newest *available* one that supports it — never "the
newest runtime first": the xcode-27 runner carries iOS 27.1 and 27.0, and only 27.0 supports
iPhone 18 Pro (mobile-ci run 39939931594: "iPhone 18 Pro is not supported by iOS 27.1"). A
requested device type is never replaced by another one.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[2]


def load_harness() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "ios_ota_e2e", ROOT / "scripts" / "ios_ota_e2e.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


h = load_harness()


def device(name: str) -> dict[str, str]:
    return {
        "name": name,
        "identifier": "com.apple.CoreSimulator.SimDeviceType." + name.replace(" ", "-"),
        "productFamily": "iPad" if name.startswith("iPad") else "iPhone",
    }


def runtime(
    version: str, build: str, devices: list[str], *, available: bool = True
) -> dict[str, Any]:
    value: dict[str, Any] = {
        "name": f"iOS {version}",
        "version": version,
        "buildversion": build,
        "identifier": "com.apple.CoreSimulator.SimRuntime.iOS-" + version.replace(".", "-"),
        "platform": "iOS",
        "isAvailable": available,
        "supportedDeviceTypes": [device(name) for name in devices],
    }
    if not available:
        value["availabilityError"] = "runtime bundle missing"
    return value


def listing(*runtimes: dict[str, Any]) -> dict[str, Any]:
    return {"runtimes": list(runtimes)}


IPHONE_18_PRO = "iPhone 18 Pro"


def test_only_the_older_runtime_supports_the_device() -> None:
    # the xcode-27 runner: iOS 27.1 lacks iPhone 18 Pro, iOS 27.0 has it
    choice = h.choose_runtime(
        listing(
            runtime("27.1", "24A94425", ["iPhone 17 Pro", "iPad Pro 13-inch (M5)"]),
            runtime("27.0", "24A434", [IPHONE_18_PRO, "iPhone 18 Pro Max", "iPhone 17 Pro"]),
        ),
        IPHONE_18_PRO,
    )
    assert (choice.device, choice.version, choice.build) == (IPHONE_18_PRO, "27.0", "24A434")
    assert choice.runtime == "com.apple.CoreSimulator.SimRuntime.iOS-27-0"
    assert choice.device_type == "com.apple.CoreSimulator.SimDeviceType.iPhone-18-Pro"


def test_both_runtimes_support_the_device_and_the_newest_wins() -> None:
    choice = h.choose_runtime(
        listing(
            runtime("27.0", "24A434", [IPHONE_18_PRO]),
            runtime("27.1", "24A94425", [IPHONE_18_PRO]),
            runtime("26.4", "23E254a", [IPHONE_18_PRO]),
        ),
        IPHONE_18_PRO,
    )
    assert (choice.version, choice.build) == ("27.1", "24A94425")


def test_a_requested_device_no_runtime_supports_is_never_replaced() -> None:
    runtimes = listing(
        runtime("27.1", "24A94425", ["iPhone 17 Pro"]),
        runtime("27.0", "24A434", ["iPhone 17", "iPad Pro 13-inch (M5)"]),
    )
    with pytest.raises(h.HarnessError) as raised:
        h.choose_runtime(runtimes, IPHONE_18_PRO)
    message = str(raised.value)
    assert "iPhone 18 Pro is not supported by any available iOS simulator runtime" in message
    assert "never replaced" in message
    # the diagnosis lists every runtime and its devices
    assert "iOS 27.1 (24A94425) com.apple.CoreSimulator.SimRuntime.iOS-27-1: available" in message
    assert "devices: iPad Pro 13-inch (M5), iPhone 17" in message


def test_an_unavailable_runtime_is_never_chosen() -> None:
    runtimes = listing(
        runtime("27.1", "24A94425", [IPHONE_18_PRO], available=False),
        runtime("27.0", "24A434", [IPHONE_18_PRO]),
    )
    assert h.choose_runtime(runtimes, IPHONE_18_PRO).version == "27.0"
    only_unavailable = listing(runtime("27.1", "24A94425", [IPHONE_18_PRO], available=False))
    with pytest.raises(h.HarnessError) as raised:
        h.choose_runtime(only_unavailable, IPHONE_18_PRO)
    message = str(raised.value)
    assert message.startswith("no available iOS simulator runtime")
    assert "unavailable (runtime bundle missing)" in message


def test_no_ios_runtime_at_all_is_diagnosed() -> None:
    other = {"name": "watchOS 27.0", "version": "27.0", "platform": "watchOS", "isAvailable": True}
    with pytest.raises(h.HarnessError, match="simctl lists no iOS runtime"):
        h.choose_runtime({"runtimes": [other]}, IPHONE_18_PRO)


def test_without_a_request_the_first_default_device_any_runtime_supports_is_used() -> None:
    runtimes = listing(
        runtime("27.1", "24A94425", ["iPhone 17 Pro"]),
        runtime("27.0", "24A434", [IPHONE_18_PRO]),
    )
    assert h.DEFAULT_DEVICE_TYPES[0] == IPHONE_18_PRO
    choice = h.choose_runtime(runtimes, None)
    assert (choice.device, choice.version) == (IPHONE_18_PRO, "27.0")
    local = listing(runtime("26.4", "23E254a", ["iPhone 17 Pro"]))
    assert h.choose_runtime(local, None).device == "iPhone 17 Pro"


def test_versions_compare_numerically() -> None:
    choice = h.choose_runtime(
        listing(runtime("27.9", "B9", [IPHONE_18_PRO]), runtime("27.10", "B10", [IPHONE_18_PRO])),
        IPHONE_18_PRO,
    )
    assert choice.version == "27.10"
