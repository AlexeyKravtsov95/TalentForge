#!/usr/bin/env bash
# iOS simulator launch smoke (M5.IOS.1) for the built TalentForge .app.
#
# For each named simulator device: boot it and wait until it has fully booted, install the
# app, launch it, wait, check the process is still alive (a crash or a hang in the first
# seconds fails), and save a screenshot of the running app. It proves only that the build
# starts and draws on that device class; the interactive scenarios are the manual smoke in
# docs/release/apple-appstore/readiness.md.
#
# A device the installed runtimes do not offer is a FAILURE, never a skip: a screenshot of
# another device is not evidence for it. A failed check saves its evidence (the full
# `launchctl list` of the simulator, the app's recent unified log, new crash reports) under
# <screenshot-dir>/<device>-diagnostics/ and stays a FAILURE.
#
# The running check never pipes `launchctl list` into a reader that may stop early: with
# `pipefail`, `launchctl … | grep -q` reported a running app as missing whenever grep found
# the line and closed the pipe before launchctl finished writing (SIGPIPE, "Child process
# terminated with signal 13", mobile-ci run 39910279665). The whole output is captured
# first, `simctl spawn`'s own exit status is checked, and the app's line must carry a PID.
#
# Usage:
#   scripts/ios_simulator_smoke.sh <path/to/iosApp.app> <screenshot-dir> "<device name>" ...
# Example (mobile-ci, Xcode 27.1):
#   scripts/ios_simulator_smoke.sh build/Build/Products/Release-iphonesimulator/iosApp.app \
#       build/ios-smoke "iPhone 18 Pro" "iPhone 18 Pro Max" "iPad Pro 13-inch (M5)"
#
# Environment: TALENTFORGE_SMOKE_WAIT_SECONDS (default 15) before the first check;
# TALENTFORGE_SMOKE_CHECKS (default 3) checks, TALENTFORGE_SMOKE_CHECK_INTERVAL (default 2) s
# apart, retrying only a check that could not run (simctl failed), never a missing app.
set -euo pipefail

# Prints the PID of the running app [bundle_id] from a `launchctl list` [listing] (columns
# PID, status, label; an app's label is "UIKitApplication:<bundle id>[…]"). Returns 1 when
# no line of the app has a numeric PID (absent, or listed as "-": not running).
running_pid_in_listing() {
  local listing=$1 bundle_id=$2 pid label
  while IFS=$'\t' read -r pid _ label; do
    case "$label" in
      "UIKitApplication:${bundle_id}["*|"UIKitApplication:${bundle_id}")
        if [[ "$pid" =~ ^[0-9]+$ ]]; then
          printf '%s\n' "$pid"
          return 0
        fi
        ;;
    esac
  done <<<"$listing"
  return 1
}

# Checks once whether [bundle_id] runs on simulator [udid]. Exit status: 0 running (PID on
# stdout), 1 not running, 2 the check itself failed (simctl/launchctl error). The full
# listing is written to [listing_file] for diagnostics.
check_app_running() {
  local udid=$1 bundle_id=$2 listing_file=$3 listing rc=0
  listing=$(xcrun simctl spawn "$udid" launchctl list 2>"$listing_file.stderr") || rc=$?
  printf '%s\n' "$listing" >"$listing_file"
  if [ "$rc" -ne 0 ]; then
    echo "simctl spawn launchctl list exited with $rc: $(cat "$listing_file.stderr")" >&2
    return 2
  fi
  running_pid_in_listing "$listing" "$bundle_id"
}

# Polls check_app_running up to [checks] times, [interval] s apart. A check that cannot run
# (status 2) is retried; "not running" is final at once — a retry must never turn a crashed
# app into a pass.
wait_for_running_app() {
  local udid=$1 bundle_id=$2 listing_file=$3 checks=$4 interval=$5 attempt rc
  for ((attempt = 1; attempt <= checks; attempt++)); do
    rc=0
    check_app_running "$udid" "$bundle_id" "$listing_file" || rc=$?
    case "$rc" in
      0) return 0 ;;
      1) return 1 ;;
    esac
    [ "$attempt" -lt "$checks" ] && sleep "$interval"
  done
  return 2
}

# Saves what explains a failed launch: simulator log of the app and crash reports newer
# than [since] (simulator crashes are written to the host's DiagnosticReports).
save_diagnostics() {
  local udid=$1 diagnostics=$2 since=$3 executable=$4
  mkdir -p "$diagnostics"
  xcrun simctl spawn "$udid" log show --style compact --last 5m \
    --predicate "process == \"$executable\"" >"$diagnostics/app-log.txt" 2>&1 || true
  xcrun simctl listapps "$udid" >"$diagnostics/listapps.txt" 2>&1 || true
  local report
  for report in "$HOME"/Library/Logs/DiagnosticReports/"$executable"*.ips \
    "$HOME"/Library/Logs/DiagnosticReports/"$executable"*.crash; do
    [ -f "$report" ] && [ "$report" -nt "$since" ] && cp "$report" "$diagnostics/"
  done
  return 0
}

# The newest available iOS runtime's device of exactly [name]: "<udid> <runtime>", or empty.
find_device() {
  local devices
  devices=$(xcrun simctl list devices available -j)
  python3 -c '
import json, sys
name, devices = sys.argv[1], json.loads(sys.argv[2])["devices"]
found = sorted((runtime, d["udid"]) for runtime, ds in devices.items() if "SimRuntime.iOS" in runtime
               for d in ds if d["name"] == name)
if found:
    runtime, udid = found[-1]
    print(udid, runtime.rsplit(".", 1)[-1])
' "$1" "$devices"
}

current_udid=""
current_bundle=""
cleanup_device() {
  if [ -n "$current_udid" ]; then
    xcrun simctl terminate "$current_udid" "$current_bundle" >/dev/null 2>&1 || true
    xcrun simctl shutdown "$current_udid" >/dev/null 2>&1 || true
    current_udid=""
  fi
}

# Runs the smoke on one device; prints PASS/FAIL and returns 0/1.
smoke_device() {
  local app=$1 out=$2 device=$3 bundle_id=$4 executable=$5 found udid runtime slug marker pid rc
  found=$(find_device "$device")
  if [ -z "$found" ]; then
    echo "FAIL [$device]: no available simulator of this name" >&2
    return 1
  fi
  read -r udid runtime <<<"$found"
  slug=$(printf '%s' "$device" | tr -c 'A-Za-z0-9' '-' | tr -s '-' | sed 's/-$//')
  marker=$(mktemp)
  current_udid=$udid
  current_bundle=$bundle_id

  xcrun simctl boot "$udid" 2>/dev/null || true # already booted is not an error
  if ! xcrun simctl bootstatus "$udid" -b >/dev/null; then
    echo "FAIL [$device, $runtime]: the simulator did not finish booting" >&2
    cleanup_device
    return 1
  fi
  if ! xcrun simctl install "$udid" "$app"; then
    echo "FAIL [$device, $runtime]: install failed" >&2
    cleanup_device
    return 1
  fi
  touch "$marker"
  if ! xcrun simctl launch "$udid" "$bundle_id" >/dev/null; then
    echo "FAIL [$device, $runtime]: launch failed" >&2
    save_diagnostics "$udid" "$out/$slug-diagnostics" "$marker" "$executable"
    cleanup_device
    return 1
  fi
  sleep "${TALENTFORGE_SMOKE_WAIT_SECONDS:-15}"

  rc=0
  pid=$(wait_for_running_app "$udid" "$bundle_id" "$out/$slug-launchctl.txt" \
    "${TALENTFORGE_SMOKE_CHECKS:-3}" "${TALENTFORGE_SMOKE_CHECK_INTERVAL:-2}") || rc=$?
  if [ "$rc" -eq 0 ] && xcrun simctl io "$udid" screenshot "$out/$slug.png" >/dev/null 2>&1; then
    rm -f "$out/$slug-launchctl.txt" "$out/$slug-launchctl.txt.stderr"
    echo "PASS [$device, $runtime]: running (pid $pid) after ${TALENTFORGE_SMOKE_WAIT_SECONDS:-15}s, screenshot $out/$slug.png"
    cleanup_device
    return 0
  fi
  case "$rc" in
    0) echo "FAIL [$device, $runtime]: running (pid $pid) but the screenshot failed" >&2 ;;
    1) echo "FAIL [$device, $runtime]: $bundle_id is not running ${TALENTFORGE_SMOKE_WAIT_SECONDS:-15}s after launch" >&2 ;;
    *) echo "FAIL [$device, $runtime]: could not query the simulator's processes" >&2 ;;
  esac
  save_diagnostics "$udid" "$out/$slug-diagnostics" "$marker" "$executable"
  mv "$out/$slug-launchctl.txt"* "$out/$slug-diagnostics/" 2>/dev/null || true
  echo "  diagnostics: $out/$slug-diagnostics/" >&2
  cleanup_device
  return 1
}

main() {
  if [ "$#" -lt 3 ]; then
    echo "usage: $0 <app> <screenshot-dir> <device name>..." >&2
    exit 2
  fi
  local app=$1 out=$2 bundle_id executable failures=0 device
  shift 2
  bundle_id=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Info.plist")
  executable=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$app/Info.plist")
  mkdir -p "$out"
  trap cleanup_device EXIT
  for device in "$@"; do
    smoke_device "$app" "$out" "$device" "$bundle_id" "$executable" || failures=$((failures + 1))
  done
  if [ "$failures" -ne 0 ]; then
    echo "RESULT: FAIL ($failures device(s))" >&2
    exit 1
  fi
  echo "RESULT: PASS"
}

# Sourced (tests): define the functions only.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
