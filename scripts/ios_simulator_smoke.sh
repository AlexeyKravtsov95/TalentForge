#!/usr/bin/env bash
# iOS simulator launch smoke (M5.IOS.1) for the built TalentForge .app.
#
# For each named simulator device: boot it and wait until it has fully booted, install the
# app, launch it, wait, check the process is still alive (a crash in the first
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
# Every simctl operation has a deadline, a heartbeat every 10s, partial stdout/stderr logs,
# and a timings.jsonl record. Defaults: discovery/process 30s, boot 600s, install 300s,
# launch 120s, screenshot 90s; diagnostic calls 10-20s; cleanup 20s per call. The CI matrix
# runs one device per fresh runner, preserving all three required device checks.
# Environment: TALENTFORGE_SMOKE_WAIT_SECONDS (default 15) before the first check;
# TALENTFORGE_SMOKE_CHECKS (default 3) checks, TALENTFORGE_SMOKE_CHECK_INTERVAL (default 2) s
# apart, retrying only a check that could not run (simctl failed), never a missing app.
set -euo pipefail

smoke_script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
current_evidence=""

# All simctl calls, including diagnostics and cleanup, pass through this watchdog. Child
# output is retained on disk; progress goes to stderr so JSON/PID stdout stays parseable.
bounded_command() {
  local stage=$1 timeout=$2
  shift 2
  python3 "$smoke_script_dir/ios_smoke_command.py" --stage "$stage" --timeout "$timeout" \
    --output "$current_evidence" -- "$@"
}

simctl_stage() {
  local stage=$1 timeout=$2
  shift 2
  bounded_command "$stage" "$timeout" xcrun simctl "$@"
}

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
  local previous_evidence=$current_evidence
  current_evidence=${current_evidence:-"${listing_file%.txt}-diagnostics"}
  local stage="processes-${attempt:-1}"
  simctl_stage "$stage" "${TALENTFORGE_SMOKE_QUERY_TIMEOUT:-30}" \
    spawn "$udid" launchctl list || rc=$?
  cp "$current_evidence/$stage.stdout.log" "$listing_file"
  cp "$current_evidence/$stage.stderr.log" "$listing_file.stderr"
  listing=$(cat "$listing_file")
  current_evidence=$previous_evidence
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
  simctl_stage app-log 20 spawn "$udid" log show --style compact --last 5m \
    --predicate "process == \"$executable\"" || true
  simctl_stage listapps 20 listapps "$udid" || true
  simctl_stage devices 20 list devices -j || true
  simctl_stage runtimes 20 list runtimes -j || true
  simctl_stage runtime-images 20 runtime list -j || true
  bounded_command core-simulator-file 10 cat \
    "$HOME/Library/Logs/CoreSimulator/CoreSimulator.log" || true
  # Host evidence remains useful when CoreSimulator itself cannot respond.
  bounded_command host-processes 10 ps -axo pid,ppid,state,etime,command || true
  bounded_command host-disk 10 df -h || true
  bounded_command simulator-log 20 /usr/bin/log show --style compact --last 5m \
    --predicate 'process == "CoreSimulatorService" OR process == "simdiskimaged" OR process == "cryptexd" OR process BEGINSWITH "SimLaunchHost"' || true
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
  simctl_stage discovery "${TALENTFORGE_SMOKE_QUERY_TIMEOUT:-30}" list devices available -j || return 1
  devices=$(cat "$current_evidence/discovery.stdout.log")
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
    simctl_stage terminate 20 terminate "$current_udid" "$current_bundle" || true
    simctl_stage shutdown 20 shutdown "$current_udid" || true
    current_udid=""
  fi
}

# Runs the smoke on one device. Failures retain every stage's output, including boot
# and install (before the app has any process/logs). No command error becomes a pass.
smoke_device() {
  local app=$1 out=$2 device=$3 bundle_id=$4 executable=$5 found udid runtime slug marker pid rc
  slug=$(printf '%s' "$device" | tr -c 'A-Za-z0-9' '-' | tr -s '-' | sed 's/-$//')
  current_evidence="$out/$slug-diagnostics"
  mkdir -p "$current_evidence"
  marker="$current_evidence/started"
  touch "$marker"
  # Record runtime registrations before boot, including duplicate/unusable disk images.
  # This is read-only diagnostics: no runtime is imported, removed or remounted.
  simctl_stage runtime-inventory 30 runtime list -j || true
  echo "DEVICE [$device]: discovering simulator" >&2
  if ! found=$(find_device "$device") || [ -z "$found" ]; then
    echo "FAIL [$device]: no available simulator of this name, or discovery failed" >&2
    save_diagnostics "" "$current_evidence" "$marker" "$executable"
    return 1
  fi
  read -r udid runtime <<<"$found"
  current_udid=$udid
  current_bundle=$bundle_id
  printf 'device=%s\nudid=%s\nruntime=%s\n' "$device" "$udid" "$runtime" >"$current_evidence/device.txt"

  # bootstatus -b boots a shutdown device too; it reports boot progress in its saved log.
  rc=0
  simctl_stage bootstatus "${TALENTFORGE_SMOKE_BOOT_TIMEOUT:-600}" bootstatus "$udid" -b || rc=$?
  if [ "$rc" -eq 0 ]; then
    simctl_stage install "${TALENTFORGE_SMOKE_INSTALL_TIMEOUT:-300}" install "$udid" "$app" || rc=$?
  fi
  if [ "$rc" -eq 0 ]; then
    simctl_stage launch "${TALENTFORGE_SMOKE_LAUNCH_TIMEOUT:-120}" launch "$udid" "$bundle_id" || rc=$?
  fi
  if [ "$rc" -eq 0 ]; then
    bounded_command settle 30 sleep "${TALENTFORGE_SMOKE_WAIT_SECONDS:-15}" || rc=$?
  fi
  if [ "$rc" -eq 0 ]; then
    pid=$(wait_for_running_app "$udid" "$bundle_id" "$out/$slug-launchctl.txt" \
      "${TALENTFORGE_SMOKE_CHECKS:-3}" "${TALENTFORGE_SMOKE_CHECK_INTERVAL:-2}") || rc=$?
  fi
  if [ "$rc" -eq 0 ]; then
    rm -f "$out/$slug.png"
    simctl_stage screenshot "${TALENTFORGE_SMOKE_SCREENSHOT_TIMEOUT:-90}" \
      io "$udid" screenshot "$out/$slug.png" || rc=$?
    # A successful command without a screenshot is not smoke evidence.
    [ -s "$out/$slug.png" ] || rc=1
  fi
  if [ "$rc" -eq 0 ]; then
    echo "PASS [$device, $runtime]: running (pid $pid), screenshot $out/$slug.png"
    cleanup_device
    return 0
  fi
  echo "FAIL [$device, $runtime]: rc=$rc (see stage timings and partial logs in $current_evidence)" >&2
  save_diagnostics "$udid" "$current_evidence" "$marker" "$executable"
  cp "$out/$slug-launchctl.txt"* "$current_evidence/" 2>/dev/null || true
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
