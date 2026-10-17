#!/bin/bash
# M5.RC.1 second pass: forced (non-cached) test execution + real-device / simulator checks.
#   bash ~/Desktop/workspace/TalentForge/.tf-transfer/rc1/run-rc1-rerun.sh
# Only the isolated `.devcheck` Android package is installed (and uninstalled after);
# iOS checks use a separate, freshly booted simulator that is shut down afterwards.
set -u
RC="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$RC/../.." && pwd)"
WT="$RC/wt"; EV="$RC/evidence2"; mkdir -p "$EV"
SUMMARY="$EV/00-summary.tsv"; : > "$SUMMARY"
PY="$REPO/.venv/bin/python"
SDK="$(grep '^sdk.dir=' "$REPO/mobile/local.properties" | cut -d= -f2-)"
export PATH="$SDK/platform-tools:$PATH"
PHONE="${RC1_PHONE:-144321556E002995}"
EMU="${RC1_EMU:-emulator-5554}"
step() { local id="$1" dir="$2"; shift 2; local log="$EV/$id.log" s=$(date +%s)
  echo "== [$id]"; { echo "# cmd: $*"; echo "# start: $(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$log"
  ( cd "$dir" && eval "$@" ) >> "$log" 2>&1; local rc=$?
  echo "# exit: $rc  duration_s: $(( $(date +%s)-s ))" >> "$log"
  printf "%s\t%s\t%s\n" "$id" "$rc" "$(( $(date +%s)-s ))" >> "$SUMMARY"; echo "   -> exit $rc"; }
G='./gradlew --console=plain --no-configuration-cache --no-build-cache'

# 1. Tests forced to EXECUTE (the first pass restored them FROM-CACHE)
step 10-shared-jvmTest-rerun "$WT/mobile" "$G :shared:jvmTest --rerun"
step 11-desktopTest-rerun "$WT/mobile" "$G :composeApp:desktopTest --rerun"
step 12-iosSimulatorArm64Test-rerun "$WT/mobile" "$G :shared:iosSimulatorArm64Test --rerun"
step 13-lint-report "$WT/mobile" 'cat composeApp/build/intermediates/lint_intermediate_text_report/release/lintReportRelease/lint-results-release.txt'

# 2. Android real device + emulator (isolated .devcheck build)
step 20-build-devcheck "$WT/mobile" "$G :composeApp:assembleDebug -Ptalentforge.applicationIdSuffix=.devcheck"
step 21-section-check-phone "$WT" '"$PY" scripts/android_section_check.py --serial "$PHONE" --runs 1 --no-build --out "$EV/section-check-phone"'
step 22-rc1-device-phone "$RC" '"$PY" rc1_android_device_check.py --serial "$PHONE" --out "$EV/rc1-device-phone"'
step 23-rc1-device-emulator "$RC" '"$PY" rc1_android_device_check.py --serial "$EMU" --out "$EV/rc1-device-emulator" --screens'
step 24-restore-plain-debug "$WT/mobile" "$G :composeApp:assembleDebug"

# 3. iOS simulator launch smoke (Release simulator build; separate simulators)
ios_smoke() { # ios_smoke <name> <runtime-substring>
  local name="$1" udid
  udid=$(xcrun simctl list devices available | grep -A40 -- "-- iOS 26.4 --" | grep "    $name (" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')
  [ -z "$udid" ] && { echo "no simulator $name"; return 2; }
  echo "simulator: $name $udid"
  local was=$(xcrun simctl list devices | grep "$udid" | grep -o "(Booted)\|(Shutdown)")
  xcrun simctl boot "$udid" 2>/dev/null; xcrun simctl bootstatus "$udid" -b
  local APP=$(find "$WT/mobile/iosApp/build-release/Build/Products/Release-iphonesimulator" -maxdepth 1 -name "*.app" | head -1)
  xcrun simctl uninstall "$udid" io.github.alexeykravtsov95.talentforge 2>/dev/null
  xcrun simctl install "$udid" "$APP" || return 1
  xcrun simctl launch "$udid" io.github.alexeykravtsov95.talentforge || return 1
  sleep 10
  xcrun simctl io "$udid" screenshot "$EV/ios-$(echo $name | tr ' ()' '___')-launch.png"
  xcrun simctl spawn "$udid" launchctl list | grep -i talentforge && echo "process alive after 10 s"
  ls -t ~/Library/Logs/DiagnosticReports 2>/dev/null | grep -i iosApp | head -3
  xcrun simctl terminate "$udid" io.github.alexeykravtsov95.talentforge
  xcrun simctl uninstall "$udid" io.github.alexeykravtsov95.talentforge
  [ "$was" = "(Shutdown)" ] && xcrun simctl shutdown "$udid"
  return 0
}
step 30-ios-iphone17e "$RC" 'ios_smoke "iPhone 17e"'
step 31-ios-ipad-air-11 "$RC" 'ios_smoke "iPad Air 11-inch (M4)"'

echo; column -t -s $'\t' "$SUMMARY"
