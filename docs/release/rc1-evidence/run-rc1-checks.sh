#!/bin/bash
# M5.RC.1 evidence collector. Run on the Mac from anywhere:
#   bash ~/Desktop/workspace/TalentForge/.tf-transfer/rc1/run-rc1-checks.sh
# Read-only towards the user's checkout: it fetches origin, creates a separate git
# worktree of origin/main (branch chore/m5-rc1-release-readiness-audit) and runs every
# check there. Nothing is pushed, signed, published or merged. Logs: .tf-transfer/rc1/evidence/
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
RC="$REPO/.tf-transfer/rc1"
EV="$RC/evidence"
WT="$RC/wt"
BRANCH="chore/m5-rc1-release-readiness-audit"
mkdir -p "$EV"
SUMMARY="$EV/00-summary.tsv"
: > "$SUMMARY"
step() { # step <id> <workdir> <command...>
  local id="$1" dir="$2"; shift 2
  local log="$EV/$id.log" start end rc
  start=$(date +%s)
  echo "== [$id] $* (in $dir)"
  { echo "# cmd: $*"; echo "# dir: $dir"; echo "# start: $(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$log"
  ( cd "$dir" && eval "$@" ) >> "$log" 2>&1
  rc=$?
  end=$(date +%s)
  echo "# exit: $rc  duration_s: $((end-start))" >> "$log"
  printf "%s\t%s\t%s\t%s\n" "$id" "$rc" "$((end-start))" "$*" >> "$SUMMARY"
  echo "   -> exit $rc in $((end-start))s"
}

# 0. Environment
step 01-env "$REPO" 'sw_vers; uname -m; sysctl -n machdep.cpu.brand_string; sysctl -n hw.memsize; java -version; /usr/libexec/java_home -V; xcodebuild -version; xcrun --show-sdk-version --sdk iphonesimulator; .venv/bin/python --version; git --version; (command -v gh && gh --version) || echo "gh: not installed"; (command -v gh >/dev/null && gh auth status >/dev/null 2>&1 && echo "gh: authenticated") || echo "gh: not authenticated"'

# 1. Fresh main + isolated worktree
step 02-git "$REPO" 'git fetch origin --prune && git log --oneline -12 origin/main && git rev-parse origin/main && (git worktree list | grep -q "$WT" || git worktree add -B "$BRANCH" "$WT" origin/main) && git -C "$WT" status --short --branch && git -C "$WT" log --oneline -1'
cp -n "$REPO/mobile/local.properties" "$WT/mobile/local.properties" 2>/dev/null
SDK="$(grep '^sdk.dir=' "$REPO/mobile/local.properties" | cut -d= -f2-)"
PY="$REPO/.venv/bin/python"
export PYTHONPATH="$WT/src"

# 2. GitHub / CI state (read-only)
step 03-github "$WT" 'command -v gh >/dev/null || { echo "gh not installed"; exit 2; }; gh pr list --state all --limit 12; echo; gh run list --limit 40 --json databaseId,workflowName,headBranch,event,status,conclusion,createdAt,url; echo; for id in $(gh run list --limit 8 --json databaseId -q ".[].databaseId"); do echo "--- run $id"; gh run view $id --json jobs -q ".jobs[] | [.name,.conclusion,(.steps|length)] | @tsv"; done'

# 3. Python baseline
step 10-ruff-check "$WT" '"$PY" -m ruff --version; "$PY" -m ruff check .'
step 11-ruff-format "$WT" '"$PY" -m ruff format --check .'
step 12-mypy "$WT" '"$PY" -m mypy --version; "$PY" -m mypy'
step 13-pytest "$WT" '"$PY" -c "import talent_data,sys;print(talent_data.__file__)"; "$PY" -m pytest -q -p no:cacheprovider'

# 4. Data consistency
step 20-sync-datasets "$WT" '"$PY" scripts/sync_mobile_datasets.py --check'
step 21-sync-visuals "$WT" '"$PY" scripts/sync_mobile_visuals.py --check'
step 22-sync-tooltips "$WT" '"$PY" scripts/sync_mobile_tooltips.py --check'
step 23-sync-localization "$WT" '"$PY" scripts/sync_mobile_localization.py --check'

# 5. Dataset freshness (network: wago.tools)
step 30-freshness "$WT" 'date -u +%Y-%m-%dT%H:%M:%SZ; "$PY" scripts/check_product_freshness.py --output "$EV/release-freshness.json"'

# 6. Kotlin Multiplatform
G='./gradlew --console=plain --no-configuration-cache'
step 40-gradle-version "$WT/mobile" "$G --version"
step 41-shared-jvmTest "$WT/mobile" "$G :shared:jvmTest"
step 42-composeApp-desktopTest "$WT/mobile" "$G :composeApp:desktopTest"
step 43-assembleDebug "$WT/mobile" "$G :composeApp:assembleDebug"
step 44-assembleRelease "$WT/mobile" "$G :composeApp:assembleRelease"
step 45-bundleRelease "$WT/mobile" "$G :composeApp:bundleRelease"
step 46-ios-shared-simTest "$WT/mobile" "$G :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test"
step 47-ios-link-debug "$WT/mobile" "$G :composeApp:linkDebugFrameworkIosArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64"
step 48-ios-link-release "$WT/mobile" "$G :composeApp:linkReleaseFrameworkIosArm64"
step 49-android-lint "$WT/mobile" "$G :composeApp:lintRelease"

# 7. Xcode
step 50-xcode-settings "$WT/mobile/iosApp" 'xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release -showBuildSettings 2>/dev/null | grep -E " (PRODUCT_BUNDLE_IDENTIFIER|MARKETING_VERSION|CURRENT_PROJECT_VERSION|IPHONEOS_DEPLOYMENT_TARGET|TARGETED_DEVICE_FAMILY|ASSETCATALOG_COMPILER_APPICON_NAME|DEVELOPMENT_TEAM|CODE_SIGN_STYLE|CODE_SIGN_IDENTITY|INFOPLIST_FILE|PRODUCT_NAME|SUPPORTED_PLATFORMS|ENABLE_BITCODE|SWIFT_VERSION|GENERATE_INFOPLIST_FILE) ="'
step 51-xcode-sim-debug "$WT/mobile/iosApp" "xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath build CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build"
step 52-xcode-sim-release "$WT/mobile/iosApp" "xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS Simulator' -derivedDataPath build-release CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build"
step 53-xcode-device-release-unsigned "$WT/mobile/iosApp" "xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS' -derivedDataPath build-device CODE_SIGNING_ALLOWED=NO build"

# 8. Artifacts: sizes, badging, signature state
step 60-artifacts "$WT/mobile" 'find composeApp/build/outputs -name "*.apk" -o -name "*.aab" | xargs ls -l; BT=$(ls -d "'"$SDK"'"/build-tools/* | sort -V | tail -1); echo "build-tools: $BT"; for a in composeApp/build/outputs/apk/*/*.apk; do echo "=== $a"; "$BT/aapt2" dump badging "$a" | grep -E "^(package|sdkVersion|targetSdkVersion|application-label|application-icon|application:|launchable-activity|uses-permission|locales|densities|native-code)"; "$BT/apksigner" verify --print-certs "$a" 2>&1 | grep -v -i "sha-256\|sha-1\|md5" | head -5; done; for d in iosApp/build iosApp/build-release iosApp/build-device; do find $d/Build/Products -maxdepth 2 -name "*.app" -exec du -sk {} \; 2>/dev/null; done; APP=$(find iosApp/build-release/Build/Products -maxdepth 2 -name "*.app" | head -1); [ -n "$APP" ] && { plutil -p "$APP/Info.plist"; ls -la "$APP"; ls "$APP"/*.lproj 2>/dev/null; }'

# 9. Runtime availability + optional on-device smoke (Android)
step 70-devices "$WT" '"'"$SDK"'/platform-tools/adb" devices -l; "'"$SDK"'/emulator/emulator" -list-avds; xcrun simctl list devices available | head -40'
if "$SDK/platform-tools/adb" devices | grep -q -w device; then
  step 71-android-smoke "$WT/mobile" 'ADB="'"$SDK"'/platform-tools/adb"; PKG=io.github.alexeykravtsov95.talentforge.devcheck; ACT=io.github.alexeykravtsov95.talentforge.ui.MainActivity; ./gradlew --console=plain --no-configuration-cache :composeApp:assembleDebug -Ptalentforge.applicationIdSuffix=.devcheck || exit 1; $ADB shell getprop ro.product.model; $ADB shell getprop ro.build.version.release; $ADB shell getprop persist.sys.locale; $ADB install -r -t composeApp/build/outputs/apk/debug/composeApp-debug.apk; $ADB shell pm clear $PKG; $ADB logcat -c; for i in 1 2 3 4 5; do $ADB shell am force-stop $PKG; sleep 2; $ADB shell am start -W -n $PKG/$ACT | grep -E "Status|LaunchState|TotalTime|WaitTime"; sleep 4; done; $ADB exec-out screencap -p > "'"$EV"'/71-android-first-launch.png"; $ADB shell dumpsys meminfo $PKG | sed -n 1,45p; echo "--- crash buffer"; $ADB logcat -d -b crash | tail -40; echo "--- AndroidRuntime"; $ADB logcat -d | grep -E "AndroidRuntime|FATAL" | tail -20; $ADB uninstall $PKG; echo "isolated .devcheck package uninstalled; the regular app was not touched"'
  # rebuild the plain debug APK so the artifact step reflects the real applicationId
  step 72-assembleDebug-restore "$WT/mobile" "$G :composeApp:assembleDebug"
fi
echo; echo "Done. Summary:"; column -t -s $'\t' "$SUMMARY" | cut -c1-140
