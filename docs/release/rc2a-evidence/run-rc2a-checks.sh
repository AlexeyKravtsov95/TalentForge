#!/usr/bin/env bash
# M5.RC.2a — application icon integration checks (macOS with Xcode + Android SDK).
#
# Runs against the checkout that contains this script, at its current HEAD (recorded in every
# row). Never commits, pushes, creates branches or edits tracked files. Device rules:
#   * Android devices get only the ISOLATED `.devcheck` debug build (installed next to, never over,
#     the real app; uninstalled at the end). No default serial: a missing device is BLOCKED.
#   * iOS simulator: the Debug simulator build is installed, launched once, screenshotted, and
#     uninstalled; the simulator's appearance is restored.
# Every step is PASS / FAIL / BLOCKED in <out>/00-summary.tsv. lintRelease is EXPECTED to exit
# non-zero because of the pre-existing TF-RC1-LINT error; 31-lint-icon judges the icon only.
set -u

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]
  --out DIR           evidence directory (default \${TMPDIR:-/tmp}/talentforge-rc2a/<sha12>)
  --python PATH       Python with Pillow (default <checkout>/.venv/bin/python, else python3)
  --emulator SERIAL   adb serial of an Android emulator (no default)
  --phone SERIAL      adb serial of a physical Android phone (no default)
  --ios-sim NAME      simulator name (default "iPhone 17e")
  --ios-runtime NAME  simulator runtime header (default "iOS 26.4")
  --skip-tests        skip the shared/desktop test baseline (icon steps still run)
USAGE
}

OUT=""; PY=""; EMU=""; PHONE=""; SIM="iPhone 17e"; RUNTIME="iOS 26.4"; SKIP_TESTS=0
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --python) PY="$2"; shift 2 ;;
    --emulator) EMU="$2"; shift 2 ;;
    --phone) PHONE="$2"; shift 2 ;;
    --ios-sim) SIM="$2"; shift 2 ;;
    --ios-runtime) RUNTIME="$2"; shift 2 ;;
    --skip-tests) SKIP_TESTS=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(git -C "$HERE" rev-parse --show-toplevel)" || { echo "not in a git checkout" >&2; exit 2; }
ROOT="$(cd "$ROOT" && pwd -P)"
[ "$HERE" = "$ROOT/docs/release/rc2a-evidence" ] || { echo "run the committed copy" >&2; exit 2; }
SHA="$(git -C "$ROOT" rev-parse HEAD)"
[ -z "$(git -C "$ROOT" status --porcelain --untracked-files=no)" ] \
  || { echo "tracked changes in $ROOT; refusing to audit a dirty tree" >&2; exit 2; }
OUT="${OUT:-${TMPDIR:-/tmp}/talentforge-rc2a/${SHA:0:12}}"
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd -P)"
COMMON="$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)"
PRIMARY="$(dirname "$COMMON")"
if [ -z "$PY" ]; then
  for p in "$ROOT/.venv/bin/python" "$PRIMARY/.venv/bin/python"; do
    [ -x "$p" ] && { PY="$p"; break; }
  done
  PY="${PY:-$(command -v python3)}"
fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for f in "$ROOT/mobile/local.properties" "$PRIMARY/mobile/local.properties"; do
    [ -f "$f" ] && SDK="$(sed -n 's/^sdk\.dir=//p' "$f" | head -1)" && [ -n "$SDK" ] && break
  done
fi
if [ ! -f "$ROOT/mobile/local.properties" ] && [ -n "$SDK" ]; then
  printf 'sdk.dir=%s\n' "$SDK" > "$ROOT/mobile/local.properties"   # git-ignored
fi
ADB="${SDK:+$SDK/platform-tools/adb}"
AAPT2="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)/aapt2"
CHECKS="$HERE/rc2a_checks.py"
RES="$ROOT/mobile/composeApp/src/androidMain/res"
IOS_ICON="$ROOT/mobile/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
PKG=io.github.alexeykravtsov95.talentforge
G='./gradlew --console=plain --no-configuration-cache'
SUMMARY="$OUT/00-summary.tsv"
printf 'id\tstatus\texit\tseconds\tcommit\tcommand\n' > "$SUMMARY"

step() { # step <id> <dir> <command...>; EXPECT_FAIL=1 marks a known pre-existing failure
  local id="$1" dir="$2"; shift 2
  local cmd="$*" log="$OUT/$id.log" start rc status
  start=$(date +%s); echo "== [$id] $cmd"
  { echo "# cmd: $cmd"; echo "# dir: $dir"; echo "# commit: $SHA"; echo "# start: $(date -u +%FT%TZ)"; } > "$log"
  ( cd "$dir" && eval "$cmd" ) >> "$log" 2>&1; rc=$?
  status=PASS; [ "$rc" -eq 0 ] || status=FAIL
  [ -n "${EXPECT_FAIL:-}" ] && [ "$rc" -ne 0 ] && status="FAIL-EXPECTED"
  echo "# exit: $rc status: $status" >> "$log"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$id" "$status" "$rc" "$(( $(date +%s) - start ))" "$SHA" "$cmd" >> "$SUMMARY"
  echo "   -> $status (exit $rc)"
}
blocked() { printf '%s\tBLOCKED\t-\t0\t%s\t%s\n' "$1" "$SHA" "$2" >> "$SUMMARY"; echo "== [$1] BLOCKED: $2"; }
connected() {
  [ -n "$1" ] && [ -x "$ADB" ] && "$ADB" devices | awk -v s="$1" '$1==s && $2=="device"{f=1} END{exit !f}'
}

export ROOT OUT SDK AAPT2 PY CHECKS RES IOS_ICON PKG ADB
echo "commit $SHA  evidence $OUT"

step 00-env "$ROOT" 'uname -a; sw_vers 2>/dev/null; java -version 2>&1; xcodebuild -version 2>/dev/null; "$PY" -c "import PIL,sys;print(sys.version, PIL.__version__)"; echo "aapt2: $AAPT2"; "$AAPT2" version; git rev-parse HEAD; shasum -a 256 "$IOS_ICON" "$RES"/drawable-nodpi/*.png "$RES"/mipmap-*/*'

# --- documented mobile baseline (the icon touches no Kotlin; run anyway) ---
if [ "$SKIP_TESTS" = 0 ]; then
  step 10-sync-datasets "$ROOT" '"$PY" scripts/sync_mobile_datasets.py --check'
  step 11-shared-jvmTest "$ROOT/mobile" "$G :shared:jvmTest"
  step 12-composeApp-desktopTest "$ROOT/mobile" "$G :composeApp:desktopTest"
fi

# --- Android ---
step 20-assembleDebug "$ROOT/mobile" "$G :composeApp:assembleDebug"
step 21-assembleRelease "$ROOT/mobile" "$G :composeApp:assembleRelease"
EXPECT_FAIL=1 step 30-lintRelease "$ROOT/mobile" "$G :composeApp:lintRelease"
cp "$ROOT"/mobile/composeApp/build/reports/lint-results-release.* "$OUT/" 2>/dev/null
if [ -f "$OUT/lint-results-release.xml" ]; then
  step 31-lint-icon "$ROOT" '"$PY" "$CHECKS" lint --report "$OUT/lint-results-release.xml" --out "$OUT"'
else
  blocked 31-lint-icon "no lint XML report under mobile/composeApp/build/reports"
fi
if [ -x "$AAPT2" ]; then
  for apk in "$ROOT"/mobile/composeApp/build/outputs/apk/debug/*.apk \
             "$ROOT"/mobile/composeApp/build/outputs/apk/release/*.apk; do
    [ -f "$apk" ] || continue
    cp "$apk" "$OUT/"
    n="$(basename "$apk" .apk)"
    step "40-aapt2-$n" "$ROOT" "\"\$AAPT2\" dump badging \"$apk\" | grep -E '^(package|application:|application-icon|launchable-activity)'"
    step "41-icon-$n" "$ROOT" "\"\$PY\" \"\$CHECKS\" apk --apk \"$apk\" --aapt2 \"\$AAPT2\" --res \"\$RES\" --out \"\$OUT\""
  done
else
  blocked 40-aapt2 "aapt2 not found under $SDK/build-tools"
fi

# --- iOS ---
if command -v xcodebuild >/dev/null 2>&1; then
  for cfg in Debug Release; do
    step "50-xcode-sim-$cfg" "$ROOT/mobile/iosApp" "xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration $cfg -destination 'generic/platform=iOS Simulator' -derivedDataPath \"\$OUT/dd-sim-$cfg\" CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build"
    step "51-actool-warnings-$cfg" "$ROOT" "! grep -n -i -E 'warning: .*(appicon|app icon|xcassets|actool|unassigned|icon)' \"\$OUT/50-xcode-sim-$cfg.log\""
    app="$(ls -d "$OUT/dd-sim-$cfg/Build/Products/$cfg-iphonesimulator/"*.app 2>/dev/null | head -1)"
    if [ -n "$app" ]; then
      step "52-ios-bundle-$cfg" "$ROOT" "\"\$PY\" \"\$CHECKS\" ios --app \"$app\" --source \"\$IOS_ICON\" --out \"\$OUT/ios-$cfg\""
    else
      blocked "52-ios-bundle-$cfg" "no built .app"
    fi
  done
  APP_DEBUG="$(ls -d "$OUT"/dd-sim-Debug/Build/Products/Debug-iphonesimulator/*.app 2>/dev/null | head -1)"
  UDID="$(xcrun simctl list devices available | awk -v rt="-- $RUNTIME --" -v n="    $SIM (" '
    index($0, "-- ")==1 {inrt = ($0 == rt)}
    inrt && index($0, n)==1 {match($0, /\(([0-9A-F-]{36})\)/); print substr($0, RSTART+1, 36); exit}')"
  if [ -n "$UDID" ] && [ -n "$APP_DEBUG" ]; then
    export UDID APP_DEBUG
    step 53-ios-sim-home "$ROOT" '
      was=$(xcrun simctl list devices | grep "$UDID" | grep -o "(Booted)\|(Shutdown)")
      xcrun simctl boot "$UDID" 2>/dev/null; xcrun simctl bootstatus "$UDID" -b
      ap=$(xcrun simctl ui "$UDID" appearance)
      xcrun simctl uninstall "$UDID" "$PKG" 2>/dev/null
      xcrun simctl install "$UDID" "$APP_DEBUG" || exit 1
      xcrun simctl launch "$UDID" "$PKG" || exit 1
      sleep 6; xcrun simctl terminate "$UDID" "$PKG"; sleep 3
      xcrun simctl ui "$UDID" appearance light; sleep 2
      xcrun simctl io "$UDID" screenshot "$OUT/ios-sim-home-light.png"
      xcrun simctl ui "$UDID" appearance dark; sleep 3
      xcrun simctl io "$UDID" screenshot "$OUT/ios-sim-home-dark.png"
      xcrun simctl ui "$UDID" appearance "$ap"
      xcrun simctl uninstall "$UDID" "$PKG"
      [ "$was" = "(Shutdown)" ] && xcrun simctl shutdown "$UDID"
      echo "appearance restored to $ap; the icon is visible only if it landed on the first Home page (review)"'
  else
    blocked 53-ios-sim-home "simulator '$SIM' ($RUNTIME) or Debug .app not found"
  fi
else
  blocked 50-ios "xcodebuild not available"
fi

# --- Android launcher (isolated .devcheck build only) ---
if [ -n "$EMU$PHONE" ]; then
  step 60-devcheck-apk "$ROOT/mobile" "$G :composeApp:assembleDebug -Ptalentforge.applicationIdSuffix=.devcheck"
  DEVAPK="$(ls "$ROOT"/mobile/composeApp/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)"
  export DEVAPK
fi
for pair in "emulator:$EMU" "phone:$PHONE"; do
  tag="${pair%%:*}"; serial="${pair#*:}"
  if [ -z "$serial" ]; then blocked "61-launcher-$tag" "no --$tag serial given"; continue; fi
  if ! connected "$serial"; then blocked "61-launcher-$tag" "device not connected"; continue; fi
  export SERIAL="$serial" TAG="$tag"
  step "61-launcher-$tag" "$ROOT" '
    "$ADB" -s "$SERIAL" install -r "$DEVAPK" || exit 1
    "$PY" "$CHECKS" launcher --adb "$ADB" --serial "$SERIAL" --package "$PKG.devcheck" --tag "$TAG" --out "$OUT/launcher-$TAG"; rc=$?
    "$ADB" -s "$SERIAL" uninstall "$PKG.devcheck"; exit $rc'
done

echo; echo "commit: $SHA"; echo "evidence: $OUT"
column -t -s $'\t' "$SUMMARY" | cut -c1-160
[ "$(git -C "$ROOT" rev-parse HEAD)" = "$SHA" ] || echo "WARNING: HEAD moved during the run"
