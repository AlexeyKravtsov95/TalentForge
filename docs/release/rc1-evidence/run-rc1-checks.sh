#!/bin/bash
# Step commands are single-quoted on purpose: they expand when rc1_step evaluates them.
# shellcheck disable=SC2016
# M5.RC.1 audit, pass 1 (runnable entry point): static/Python checks, data drift, dataset
# freshness, FORCED Gradle test execution, Android/iOS builds, lint, artifact inspection.
#
#   docs/release/rc1-evidence/run-rc1-checks.sh --commit 5648941 [--work-dir DIR] [--dry-run]
#
# Runs in a detached worktree of the given commit outside every checkout (see rc1-lib.sh);
# never touches a branch or your working tree. Prerequisites: README.md "Prerequisites".
# The historical as-run copy of this script is as-run/run-rc1-checks.sh (not runnable).
set -uo pipefail
# shellcheck source=SCRIPTDIR/rc1-lib.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/rc1-lib.sh"
rc1_begin checks "$@"

WT="$RC1_WT"; PY="$RC1_PYTHON"; EV="$RC1_OUT_DIR"
export WT PY EV RC1_SDK
G='./gradlew --console=plain --no-configuration-cache --no-build-cache'

freshness_status() { case "$1" in 0) echo CURRENT ;; 3) echo OUTDATED ;; 4) echo UNKNOWN ;; *) echo FAIL ;; esac; }

rc1_step 01-env "$WT" 'uname -a; sw_vers 2>/dev/null; java -version; xcodebuild -version 2>/dev/null || echo "xcodebuild: not available"; "$PY" --version; git --version; echo "commit: $(git rev-parse HEAD)"'
rc1_step 02-python-under-test "$WT" '"$PY" -c "import talent_data, sys; p = talent_data.__file__; print(p); sys.exit(0 if p.startswith(\"$WT/src/\") else 1)"'

rc1_step 10-ruff-check "$WT" '"$PY" -m ruff --version && "$PY" -m ruff check .'
rc1_step 11-ruff-format "$WT" '"$PY" -m ruff format --check .'
rc1_step 12-mypy "$WT" '"$PY" -m mypy --version && "$PY" -m mypy'
rc1_step 13-pytest "$WT" '"$PY" -m pytest -q -p no:cacheprovider'

for s in datasets visuals tooltips localization; do
  rc1_step "2${s:0:1}-sync-$s" "$WT" "\"\$PY\" scripts/sync_mobile_$s.py --check"
done

# freshness: exit 0/3/4 are results (CURRENT/OUTDATED/UNKNOWN), not tool failures
RC1_STATUS_OVERRIDE=freshness_status rc1_step 30-freshness "$WT" 'date -u +%Y-%m-%dT%H:%M:%SZ; "$PY" scripts/check_product_freshness.py --output "$EV/release-freshness.json"'

if [ -z "$RC1_SDK" ]; then
  rc1_blocked 40-gradle "no Android SDK (set ANDROID_HOME or sdk.dir in mobile/local.properties)"
else
  rc1_step 40-gradle-version "$WT/mobile" "$G --version"
  rc1_step 41-shared-jvmTest "$WT/mobile" "$G :shared:jvmTest --rerun"
  rc1_step 42-composeApp-desktopTest "$WT/mobile" "$G :composeApp:desktopTest --rerun"
  rc1_step 43-assembleDebug "$WT/mobile" "$G :composeApp:assembleDebug"
  rc1_step 44-assembleRelease "$WT/mobile" "$G :composeApp:assembleRelease"
  rc1_step 45-bundleRelease "$WT/mobile" "$G :composeApp:bundleRelease"
  rc1_step 49-lintRelease "$WT/mobile" "$G :composeApp:lintRelease"
fi

if ! command -v xcodebuild >/dev/null 2>&1; then
  rc1_blocked 46-ios "xcodebuild not available (macOS with Xcode required)"
else
  rc1_step 46-ios-shared-simTest "$WT/mobile" "$G :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 && $G :shared:iosSimulatorArm64Test --rerun"
  rc1_step 47-ios-link-debug "$WT/mobile" "$G :composeApp:linkDebugFrameworkIosArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64"
  rc1_step 48-ios-link-release "$WT/mobile" "$G :composeApp:linkReleaseFrameworkIosArm64"
  rc1_step 50-xcode-settings "$WT/mobile/iosApp" 'xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release -showBuildSettings 2>/dev/null | grep -E " (PRODUCT_BUNDLE_IDENTIFIER|MARKETING_VERSION|CURRENT_PROJECT_VERSION|IPHONEOS_DEPLOYMENT_TARGET|TARGETED_DEVICE_FAMILY|ASSETCATALOG_COMPILER_APPICON_NAME|CODE_SIGN_STYLE|PRODUCT_NAME) ="'
  rc1_step 51-xcode-sim-debug "$WT/mobile/iosApp" "xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath \"\$EV/../xcode-sim-debug\" CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build"
  rc1_step 52-xcode-sim-release "$WT/mobile/iosApp" "xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS Simulator' -derivedDataPath \"\$EV/../xcode-sim-release\" CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build"
  rc1_step 53-xcode-device-release-unsigned "$WT/mobile/iosApp" "xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS' -derivedDataPath \"\$EV/../xcode-device-release\" CODE_SIGNING_ALLOWED=NO build"
fi

# artifact inspection: the unsigned release APK is EXPECTED to fail apksigner verification
if [ -n "$RC1_SDK" ]; then
  rc1_step 60-android-artifacts "$WT/mobile" 'BT=$(ls -d "$RC1_SDK"/build-tools/* | sort -V | tail -1); find composeApp/build/outputs \( -name "*.apk" -o -name "*.aab" \) -exec ls -l {} \;; for a in composeApp/build/outputs/apk/*/*.apk; do echo "=== $a"; "$BT/aapt2" dump badging "$a" | grep -E "^(package|targetSdkVersion|application:|launchable-activity|uses-permission|native-code)"; "$BT/apksigner" verify "$a" 2>&1 | head -2 || true; done'
fi
if command -v plutil >/dev/null 2>&1; then
  rc1_step 61-ios-artifacts "$WT" 'for d in "$EV/../xcode-sim-debug" "$EV/../xcode-sim-release" "$EV/../xcode-device-release"; do find "$d/Build/Products" -maxdepth 2 -name "*.app" -exec du -sk {} \; 2>/dev/null; done; APP=$(find "$EV/../xcode-sim-release/Build/Products" -maxdepth 2 -name "*.app" | head -1); [ -n "$APP" ] && plutil -p "$APP/Info.plist" && ls "$APP"'
fi

rc1_end
