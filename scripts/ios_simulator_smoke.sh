#!/usr/bin/env bash
# iOS simulator launch smoke (M5.IOS.1) for the built TalentForge .app.
#
# For each named simulator device: boot it, install the app, launch it, wait, check the
# process is still alive (a crash or a hang in the first seconds fails), and save a
# screenshot of the running app. It proves only that the build starts and draws on that
# device class; the interactive scenarios are the manual smoke in
# docs/release/apple-appstore/readiness.md.
#
# A device the installed runtimes do not offer is a FAILURE, never a skip: a screenshot of
# another device is not evidence for it.
#
# Usage:
#   scripts/ios_simulator_smoke.sh <path/to/iosApp.app> <screenshot-dir> "<device name>" ...
# Example (mobile-ci, Xcode 27.1):
#   scripts/ios_simulator_smoke.sh build/Build/Products/Release-iphonesimulator/iosApp.app \
#       build/ios-smoke "iPhone 18 Pro" "iPhone 18 Pro Max" "iPad Pro 13-inch (M5)"
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "usage: $0 <app> <screenshot-dir> <device name>..." >&2
  exit 2
fi
app=$1
out=$2
shift 2
bundle_id=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Info.plist")
wait_seconds=${TALENTFORGE_SMOKE_WAIT_SECONDS:-15}
mkdir -p "$out"

failures=0
for device in "$@"; do
  # the newest available runtime's device of exactly this name
  udid=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
name = sys.argv[1]
devices = json.load(sys.stdin)["devices"]
found = [(runtime, d["udid"]) for runtime, ds in devices.items() if "SimRuntime.iOS" in runtime
         for d in ds if d["name"] == name]
print(sorted(found)[-1][1] if found else "")
' "$device")
  if [ -z "$udid" ]; then
    echo "FAIL [$device]: no available simulator of this name" >&2
    failures=$((failures + 1))
    continue
  fi
  runtime=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
for runtime, ds in json.load(sys.stdin)["devices"].items():
    if any(d["udid"] == sys.argv[1] for d in ds):
        print(runtime.rsplit(".", 1)[-1])
' "$udid")
  xcrun simctl boot "$udid" 2>/dev/null || true
  xcrun simctl bootstatus "$udid" -b >/dev/null
  xcrun simctl install "$udid" "$app"
  xcrun simctl launch "$udid" "$bundle_id" >/dev/null
  sleep "$wait_seconds"
  slug=$(printf '%s' "$device" | tr -c 'A-Za-z0-9' '-' | tr -s '-' | sed 's/-$//')
  if xcrun simctl spawn "$udid" launchctl list | grep -q "UIKitApplication:$bundle_id"; then
    xcrun simctl io "$udid" screenshot "$out/$slug.png" >/dev/null 2>&1
    echo "PASS [$device, $runtime]: running after ${wait_seconds}s, screenshot $out/$slug.png"
  else
    echo "FAIL [$device, $runtime]: $bundle_id is not running ${wait_seconds}s after launch" >&2
    failures=$((failures + 1))
  fi
  xcrun simctl terminate "$udid" "$bundle_id" 2>/dev/null || true
  xcrun simctl shutdown "$udid" 2>/dev/null || true
done

if [ "$failures" -ne 0 ]; then
  echo "RESULT: FAIL ($failures device(s))" >&2
  exit 1
fi
echo "RESULT: PASS"
