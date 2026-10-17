#!/usr/bin/env bash
# M5.GALLERY.1 — Android release-preparation checks for RuStore + Huawei AppGallery (macOS or Linux
# with JDK 17 and the Android SDK).
#
# What it does
#   * checks the commit under test (default: origin/main after `git fetch origin`) in a DETACHED git
#     worktree outside this checkout. No branch is created, moved or reset; this checkout's working
#     tree (including uncommitted local changes) is never touched.
#   * runs the four mobile drift checks, the read-only dataset freshness check (online,
#     informational), forced (`--rerun --no-build-cache`) :shared:jvmTest and
#     :composeApp:desktopTest, assembleDebug, assembleRelease, bundleRelease, lintRelease and the
#     release runtime dependency report;
#   * inspects the produced APKs/AAB with aapt2, apksigner and (optional) bundletool, then writes a
#     JSON/text summary with gallery1_inspect.py.
#
# What it never does
#   * create, read or require a signing key; sign anything for release; upload or publish anything;
#     install anything on a device; commit or push.
#
# Every step is PASS / FAIL / FAIL-EXPECTED / INFO / BLOCKED in <out>/00-summary.tsv, with the
# commit SHA. lintRelease is EXPECTED to fail on the pre-existing TF-RC1-LINT error. The freshness
# check is INFO: exit 3 (OUTDATED) is the known TF-RC1-DATA state, not a failure of this milestone.
set -u

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]
  --commit REF       commit to check (default: origin/main, after 'git fetch origin')
  --no-fetch         do not run 'git fetch origin' first
  --work-dir DIR     scratch root outside every checkout (default \${TMPDIR:-/tmp}/talentforge-gallery1)
  --out DIR          full logs (default <work-dir>/<sha12>/evidence)
  --curated DIR      small, committable outputs (default: <this dir>/mac)
  --python PATH      python3 for the drift checks (default <checkout>/.venv/bin/python, else python3)
  --bundletool PATH  bundletool-all-*.jar or a bundletool executable (default: "bundletool" on PATH
                     if present); without it the AAB split/size steps are BLOCKED
  --skip-freshness   skip the online freshness check
  -h, --help         this help
USAGE
}

COMMIT=""; FETCH=1; WORK=""; OUT=""; CURATED=""; PY=""; BUNDLETOOL=""; FRESH=1
while [ $# -gt 0 ]; do
  case "$1" in
    --commit) COMMIT="${2:?}"; shift 2 ;;
    --no-fetch) FETCH=0; shift ;;
    --work-dir) WORK="${2:?}"; shift 2 ;;
    --out) OUT="${2:?}"; shift 2 ;;
    --curated) CURATED="${2:?}"; shift 2 ;;
    --python) PY="${2:?}"; shift 2 ;;
    --bundletool) BUNDLETOOL="${2:?}"; shift 2 ;;
    --skip-freshness) FRESH=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

die() { echo "gallery1: error: $*" >&2; exit 2; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(git -C "$HERE" rev-parse --show-toplevel)" || die "not in a git checkout"
ROOT="$(cd "$ROOT" && pwd -P)"
[ "$HERE" = "$ROOT/docs/release/appgallery/evidence" ] || die "run the copy in docs/release/appgallery/evidence"
INSPECT="$HERE/gallery1_inspect.py"
[ -f "$INSPECT" ] || die "missing $INSPECT"

if [ "$FETCH" = 1 ]; then
  git -C "$ROOT" fetch origin || die "git fetch origin failed (remote-tracking refs only)"
fi
COMMIT="${COMMIT:-origin/main}"
SHA="$(git -C "$ROOT" rev-parse --verify "$COMMIT^{commit}")" || die "cannot resolve $COMMIT"
WORK="${WORK:-${TMPDIR:-/tmp}/talentforge-gallery1}"
mkdir -p "$WORK"; WORK="$(cd "$WORK" && pwd -P)"
case "$WORK/" in "$ROOT/"*) die "--work-dir must be outside the checkout" ;; esac
WT="$WORK/${SHA:0:12}/wt"
OUT="${OUT:-$WORK/${SHA:0:12}/evidence}"
CURATED="${CURATED:-$HERE/mac}"
mkdir -p "$OUT" "$CURATED"; OUT="$(cd "$OUT" && pwd -P)"; CURATED="$(cd "$CURATED" && pwd -P)"

# Detached worktree at exactly $SHA; reuse only if clean and at that SHA.
if [ -d "$WT" ]; then
  [ "$(git -C "$WT" rev-parse HEAD 2>/dev/null)" = "$SHA" ] || die "$WT exists at another commit; remove it"
  [ -z "$(git -C "$WT" status --porcelain --untracked-files=no)" ] || die "$WT has tracked changes; remove it"
else
  mkdir -p "$(dirname "$WT")"
  git -C "$ROOT" worktree add --detach "$WT" "$SHA" >/dev/null || die "git worktree add failed"
fi

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
[ -n "$SDK" ] && [ -d "$SDK" ] || die "Android SDK not found (ANDROID_HOME or mobile/local.properties)"
[ -f "$WT/mobile/local.properties" ] || printf 'sdk.dir=%s\n' "$SDK" > "$WT/mobile/local.properties"  # git-ignored
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
AAPT2="$BT/aapt2"; APKSIGNER="$BT/apksigner"
G='./gradlew --console=plain --no-configuration-cache'
SUMMARY="$OUT/00-summary.tsv"
printf 'id\tstatus\texit\tseconds\tcommit\tcommand\n' > "$SUMMARY"

step() { # step <id> <dir> <command...>; EXPECT=fail|info changes how a non-zero exit is recorded
  local id="$1" dir="$2"; shift 2
  local cmd="$*" log="$OUT/$id.log" start rc status
  [ "$(git -C "$WT" rev-parse HEAD)" = "$SHA" ] || die "worktree HEAD moved"
  echo "== $id: $cmd"
  start=$(date +%s)
  ( cd "$dir" && eval "$cmd" ) >"$log" 2>&1; rc=$?
  if [ $rc -eq 0 ]; then status=PASS
  elif [ "${EXPECT:-}" = fail ]; then status=FAIL-EXPECTED
  elif [ "${EXPECT:-}" = info ]; then status=INFO
  else status=FAIL; fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$id" "$status" "$rc" "$(( $(date +%s) - start ))" "$SHA" "$cmd" >> "$SUMMARY"
  echo "   -> $status (exit $rc)"
}
blocked() { printf '%s\tBLOCKED\t-\t0\t%s\t%s\n' "$1" "$SHA" "$2" >> "$SUMMARY"; echo "== $1: BLOCKED ($2)"; }

M="$WT/mobile"
APK_DEBUG="$M/composeApp/build/outputs/apk/debug/composeApp-debug.apk"
APK_REL="$M/composeApp/build/outputs/apk/release/composeApp-release-unsigned.apk"
AAB_REL="$M/composeApp/build/outputs/bundle/release/composeApp-release.aab"

step 01-env "$WT" "uname -srm; (sw_vers 2>/dev/null || true); java -version 2>&1; \"$PY\" --version; echo build-tools=\$(basename \"$BT\"); \"$AAPT2\" version; \"$APKSIGNER\" --version; git log -1 --format='%H %cI %s'"

for s in datasets visuals tooltips localization; do
  step "1${s:0:1}-sync-$s" "$WT" "\"$PY\" scripts/sync_mobile_$s.py --check"
done
if [ "$FRESH" = 1 ]; then
  EXPECT=info step 15-freshness "$WT" "\"$PY\" scripts/check_product_freshness.py"
else
  blocked 15-freshness "skipped (--skip-freshness)"
fi

step 20-shared-jvmTest "$M" "$G :shared:jvmTest --rerun --no-build-cache"
step 21-desktopTest "$M" "$G :composeApp:desktopTest --rerun --no-build-cache"
step 30-assembleDebug "$M" "$G :composeApp:assembleDebug"
step 31-assembleRelease "$M" "$G :composeApp:assembleRelease"
step 32-bundleRelease "$M" "$G :composeApp:bundleRelease"
EXPECT=fail step 33-lintRelease "$M" "$G :composeApp:lintRelease"
step 34-deps-release "$M" "$G :composeApp:dependencies --configuration releaseRuntimeClasspath"

for v in debug release; do
  apk="$APK_DEBUG"; [ $v = release ] && apk="$APK_REL"
  if [ -f "$apk" ]; then
    step "40-badging-$v" "$WT" "\"$AAPT2\" dump badging \"$apk\""
    step "41-manifest-$v" "$WT" "\"$AAPT2\" dump xmltree --file AndroidManifest.xml \"$apk\""
    # release: expected to fail (unsigned, TF-RC1-SIGNING); debug: shows the DEBUG certificate on purpose
    if [ $v = release ]; then
      EXPECT=fail step "42-apksigner-$v" "$WT" "\"$APKSIGNER\" verify --print-certs --verbose \"$apk\""
    else
      step "42-apksigner-$v" "$WT" "\"$APKSIGNER\" verify --print-certs --verbose \"$apk\""
    fi
  else
    blocked "40-badging-$v" "missing $apk"
  fi
done

[ -z "$BUNDLETOOL" ] && BUNDLETOOL="$(command -v bundletool || true)"
case "$BUNDLETOOL" in *.jar) BTOOL="java -jar \"$BUNDLETOOL\"" ;; *) BTOOL="\"$BUNDLETOOL\"" ;; esac
if [ -n "$BUNDLETOOL" ] && [ -f "$BUNDLETOOL" ] && [ -f "$AAB_REL" ]; then
  step 50-bundletool-manifest "$WT" "$BTOOL dump manifest --bundle=\"$AAB_REL\""
  # Split APKs for size estimates only; bundletool signs them with the local DEBUG keystore,
  # so these APKs are never distribution artifacts.
  rm -f "$OUT/release-splits.apks" "$OUT/release-universal.apks"
  step 51-bundletool-splits "$WT" "$BTOOL build-apks --bundle=\"$AAB_REL\" --output=\"$OUT/release-splits.apks\""
  step 52-bundletool-size "$WT" "$BTOOL get-size total --apks=\"$OUT/release-splits.apks\" --dimensions=ABI,SCREEN_DENSITY,LANGUAGE"
  step 53-bundletool-universal "$WT" "$BTOOL build-apks --bundle=\"$AAB_REL\" --mode=universal --output=\"$OUT/release-universal.apks\""
else
  blocked 50-bundletool "no bundletool (AAB split/size estimates not produced)"
fi

step 60-inspect "$WT" "\"$PY\" \"$INSPECT\" --wt \"$WT\" --out \"$OUT\" --curated \"$CURATED\" --sha \"$SHA\""
# Curated copies: home directory replaced by "~"; the debug-certificate log is NOT copied (its
# digest identifies this machine's debug keystore; the inspection keeps only the subject DN).
scrub() { sed -e "s#$WT#<worktree>#g" -e "s#$OUT#<logs>#g" -e "s#$HOME#~#g" "$1" > "$2"; }
scrub "$SUMMARY" "$CURATED/00-summary.tsv"
for f in 01-env 15-freshness 34-deps-release 40-badging-debug 40-badging-release 41-manifest-release 42-apksigner-release 50-bundletool-manifest 52-bundletool-size; do
  [ -f "$OUT/$f.log" ] && scrub "$OUT/$f.log" "$CURATED/$f.txt"
done
# Gradle task outcomes per step: shows which task outputs were restored from the build cache.
for f in 20-shared-jvmTest 21-desktopTest 30-assembleDebug 31-assembleRelease 32-bundleRelease 33-lintRelease; do
  [ -f "$OUT/$f.log" ] || continue
  printf '%s: executed=%s from-cache=%s up-to-date=%s | %s\n' "$f" \
    "$(grep -cE '^> Task :[^ ]+$' "$OUT/$f.log")" \
    "$(grep -cE '^> Task .* FROM-CACHE$' "$OUT/$f.log")" \
    "$(grep -cE '^> Task .* UP-TO-DATE$' "$OUT/$f.log")" \
    "$(grep -E '^BUILD (SUCCESSFUL|FAILED)' "$OUT/$f.log" | tail -1)"
done > "$CURATED/gradle-task-outcomes.txt"
[ -f "$OUT/33-lintRelease.log" ] && grep -E 'Error|error:|errors,|warnings|Lint found|FAILED|What went wrong' "$OUT/33-lintRelease.log" | head -20 | sed -e "s#$WT#<worktree>#g" -e "s#$HOME#~#g" > "$CURATED/33-lintRelease-excerpt.txt"
[ -f "$M/composeApp/build/reports/lint-results-release.txt" ] && scrub "$M/composeApp/build/reports/lint-results-release.txt" "$CURATED/lint-results-release.txt"

echo
echo "commit:   $SHA"
echo "logs:     $OUT"
echo "curated:  $CURATED"
column -t -s "$(printf '\t')" "$SUMMARY" | cut -c1-150
