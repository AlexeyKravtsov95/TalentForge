#!/bin/bash
# Step commands are single-quoted on purpose: they expand when rc1_step evaluates them.
# shellcheck disable=SC2016
# M5.RC.1 audit, pass 2 (runnable entry point): FORCED re-execution of the Gradle test
# suites, then device checks on the isolated `.devcheck` Android package and an iOS
# simulator launch smoke.
#
#   RC1_PHONE=<adb serial> RC1_EMULATOR=<adb serial> \
#     docs/release/rc1-evidence/run-rc1-rerun.sh --commit 5648941 [--work-dir DIR] [--dry-run]
#
# There is NO default device serial: without --phone/RC1_PHONE (or when that device is not
# attached) the physical-phone checks are recorded as BLOCKED, likewise the emulator.
# Only the `.devcheck` package is installed and it is uninstalled afterwards; the regular
# app and its saved builds are never touched. Simulators that were shut down are shut down
# again. The historical as-run copy of this script is as-run/run-rc1-rerun.sh (not runnable).
set -uo pipefail
# shellcheck source=SCRIPTDIR/rc1-lib.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/rc1-lib.sh"
rc1_begin rerun "$@"

WT="$RC1_WT"; PY="$RC1_PYTHON"; EV="$RC1_OUT_DIR"
export WT PY EV RC1_SDK RC1_ADB RC1_PHONE RC1_EMULATOR
G='./gradlew --console=plain --no-configuration-cache --no-build-cache'
DEVICE_CHECK="$RC1_SCRIPT_DIR/rc1_android_device_check.py"
export DEVICE_CHECK

# 1. forced test execution (never FROM-CACHE)
if [ -z "$RC1_SDK" ]; then
  rc1_blocked 10-gradle-tests "no Android SDK (set ANDROID_HOME or sdk.dir in mobile/local.properties)"
else
  rc1_step 10-shared-jvmTest-rerun "$WT/mobile" "$G :shared:jvmTest --rerun"
  rc1_step 11-desktopTest-rerun "$WT/mobile" "$G :composeApp:desktopTest --rerun"
fi
if command -v xcodebuild >/dev/null 2>&1; then
  rc1_step 12-iosSimulatorArm64Test-rerun "$WT/mobile" "$G :shared:iosSimulatorArm64Test --rerun"
else
  rc1_blocked 12-iosSimulatorArm64Test-rerun "xcodebuild not available"
fi

# 2. Android devices (isolated .devcheck build). The device-check script imports the
#    helpers and the APK of the WORKTREE (RC1_REPO), i.e. of the audited commit.
android_device() { # android_device <id-suffix> <serial-or-empty> <label> <how-to-set> [--screens]
  local suffix="$1" serial="$2" label="$3" how="$4"; shift 4
  if [ -z "$serial" ]; then
    rc1_blocked "21-section-check-$suffix" "no $label serial given ($how)"
    rc1_blocked "22-rc1-device-$suffix" "no $label serial given ($how)"
    return
  fi
  if ! rc1_device_connected "$serial"; then
    rc1_blocked "21-section-check-$suffix" "$label not attached/authorised in adb"
    rc1_blocked "22-rc1-device-$suffix" "$label not attached/authorised in adb"
    return
  fi
  export RC1_SERIAL="$serial"
  rc1_step "21-section-check-$suffix" "$WT" '"$PY" scripts/android_section_check.py --serial "$RC1_SERIAL" --runs 1 --no-build --out "$EV/section-check-'"$suffix"'"'
  rc1_step "22-rc1-device-$suffix" "$WT" 'RC1_REPO="$WT" "$PY" "$DEVICE_CHECK" --serial "$RC1_SERIAL" --out "$EV/rc1-device-'"$suffix"'" '"$*"
}
if [ -z "$RC1_SDK" ] || [ ! -x "$RC1_ADB" ]; then
  rc1_blocked 20-android-devices "no Android SDK/adb"
else
  rc1_step 20-build-devcheck "$WT/mobile" "$G :composeApp:assembleDebug -Ptalentforge.applicationIdSuffix=.devcheck"
  # NOTE: android_section_check.py --no-build installs the APK from the worktree
  android_device phone "$RC1_PHONE" "physical phone" "--phone / RC1_PHONE"
  android_device emulator "$RC1_EMULATOR" "emulator" "--emulator / RC1_EMULATOR" --screens
  rc1_step 24-restore-plain-debug "$WT/mobile" "$G :composeApp:assembleDebug"
fi

# 3. iOS simulator launch smoke (Release simulator build)
ios_smoke() { # ios_smoke <simulator name>
  local name="$1" udid app was
  udid=$(xcrun simctl list devices available | awk -v rt="-- $RC1_IOS_RUNTIME --" -v n="    $name (" '
    $0 == rt { inrt = 1; next } /^-- / { inrt = 0 } inrt && index($0, n) == 1 { print; exit }' \
    | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')
  [ -n "$udid" ] || { echo "simulator '$name' ($RC1_IOS_RUNTIME) not found"; return 3; }
  app=$(find "$EV/../xcode-sim-release/Build/Products/Release-iphonesimulator" -maxdepth 1 -name "*.app" 2>/dev/null | head -1)
  [ -n "$app" ] || { echo "no Release simulator build (run run-rc1-checks.sh first, same --work-dir)"; return 3; }
  was=$(xcrun simctl list devices | grep "$udid" | grep -o "(Booted)\|(Shutdown)")
  echo "simulator: $name $udid (was $was)"
  xcrun simctl boot "$udid" 2>/dev/null; xcrun simctl bootstatus "$udid" -b
  xcrun simctl uninstall "$udid" io.github.alexeykravtsov95.talentforge 2>/dev/null
  xcrun simctl install "$udid" "$app" || return 1
  xcrun simctl launch "$udid" io.github.alexeykravtsov95.talentforge || return 1
  sleep 10
  xcrun simctl io "$udid" screenshot "$EV/ios-$(echo "$name" | tr -c 'A-Za-z0-9\n' '_')-launch.png"
  local alive=1
  xcrun simctl spawn "$udid" launchctl list | grep -qi talentforge && alive=0
  xcrun simctl terminate "$udid" io.github.alexeykravtsov95.talentforge
  xcrun simctl uninstall "$udid" io.github.alexeykravtsov95.talentforge
  [ "$was" = "(Shutdown)" ] && xcrun simctl shutdown "$udid"
  [ "$alive" -eq 0 ] && echo "process alive after 10 s"
  return "$alive"
}
export -f ios_smoke
export RC1_IOS_RUNTIME
ios_status() { case "$1" in 0) echo PASS ;; 3) echo BLOCKED ;; *) echo FAIL ;; esac; }
if command -v xcrun >/dev/null 2>&1; then
  IFS=';' read -r -a sims <<< "$RC1_IOS_SIMS"
  i=0
  for sim in "${sims[@]}"; do
    i=$((i + 1))
    RC1_STATUS_OVERRIDE=ios_status rc1_step "3${i}-ios-launch-$(echo "$sim" | tr -c 'A-Za-z0-9\n' '-')" "$WT" "ios_smoke \"$sim\""
  done
else
  rc1_blocked 30-ios-launch "xcrun not available (macOS with Xcode required)"
fi

rc1_end
