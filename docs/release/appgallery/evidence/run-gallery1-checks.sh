#!/usr/bin/env bash
# M5.GALLERY.1 — Android release-preparation checks for RuStore + Huawei AppGallery (macOS or Linux
# with JDK 17 and the Android SDK).
#
# What it does
#   * checks ONE explicitly selected commit (--commit REF, required; the full SHA is recorded) in a
#     DETACHED git worktree under a scratch directory outside every git checkout;
#   * runs the four mobile drift checks, the read-only dataset freshness check (online,
#     informational), forced (`--rerun --no-build-cache`) :shared:jvmTest and
#     :composeApp:desktopTest, assembleDebug, assembleRelease, bundleRelease, lintRelease and the
#     release runtime dependency report;
#   * inspects the produced APKs/AAB with aapt2, apksigner and (optional) bundletool, then writes a
#     JSON/text summary with gallery1_inspect.py.
#
# Checkout safety (enforced before anything is created; see test-run-gallery1-safety.sh)
#   * every generated file (worktree, logs, curated results) goes below --work-dir / --out /
#     --curated, which must be absolute, unambiguous, outside the invoking checkout, outside its
#     primary checkout, outside every registered worktree and outside any other git repository;
#   * the committed historical evidence (e.g. ./mac/) is never written. Writing curated results into
#     the repository is opt-in only (--publish-evidence NAME) and only into a NEW directory;
#   * an existing scratch worktree is reused only if it is a registered worktree of this repository,
#     detached, at exactly the selected SHA and clean;
#   * no branch is created, moved or reset; `git fetch` runs only with --fetch.
#
# What it never does
#   * create, read or require a signing key; sign anything for release; upload or publish anything;
#     install anything on a device; commit or push.
#
# Every step is PASS / FAIL / FAIL-EXPECTED / INFO / BLOCKED in <out>/00-summary.tsv, with the full
# commit SHA. lintRelease is EXPECTED to fail on the pre-existing TF-RC1-LINT error. The freshness
# check is INFO: exit 3 (OUTDATED) is the known TF-RC1-DATA state, not a failure of this milestone.
set -u
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_COMMON_DIR

usage() {
  cat <<USAGE
Usage: $(basename "$0") --commit REF [options]

Required:
  --commit REF       the commit to check, resolved to a full SHA and recorded. Choose explicitly:
                       PR head as checked out here:   --commit HEAD
                       a pushed branch:               --commit origin/<branch>   (add --fetch)
                       current main:                  --commit origin/main       (add --fetch)
Options:
  --expect-sha SHA   refuse to run unless REF resolves to exactly this 40-hex SHA
  --fetch            run 'git fetch origin' first (updates remote-tracking refs only)
  --work-dir DIR     scratch root (default \${TMPDIR:-/tmp}/talentforge-gallery1)
  --out DIR          full logs (default <work-dir>/<sha12>/evidence)
  --curated DIR      small, curated results (default <work-dir>/<sha12>/curated)
  --publish-evidence NAME
                     opt-in: write the curated results into the NEW repository directory
                     docs/release/appgallery/evidence/NAME (refused if it exists; never mac/)
  --python PATH      python3 for the drift checks (default <checkout>/.venv/bin/python, else python3)
  --bundletool PATH  bundletool-all-*.jar or a bundletool executable (default: "bundletool" on PATH
                     if present); without it the AAB split/size steps are BLOCKED
  --skip-freshness   skip the online freshness check
  --dry-run          validate the commit and every path, print the plan, create and run nothing
  -h, --help         this help

All directories must be absolute paths without '.', '..', '//', '#' or newlines, outside every git
checkout or worktree, and must not contain this checkout or its primary checkout.
USAGE
}

die() { echo "gallery1: error: $*" >&2; exit 2; }

COMMIT=""; EXPECT_SHA=""; FETCH=0; WORK=""; OUT=""; CURATED=""; PUBLISH=""; PY=""; BUNDLETOOL=""
FRESH=1; DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --commit) COMMIT="${2:?--commit needs a value}"; shift 2 ;;
    --expect-sha) EXPECT_SHA="${2:?--expect-sha needs a value}"; shift 2 ;;
    --fetch) FETCH=1; shift ;;
    --work-dir) WORK="${2:?--work-dir needs a value}"; shift 2 ;;
    --out) OUT="${2:?--out needs a value}"; shift 2 ;;
    --curated) CURATED="${2:?--curated needs a value}"; shift 2 ;;
    --publish-evidence) PUBLISH="${2:?--publish-evidence needs a value}"; shift 2 ;;
    --python) PY="${2:?--python needs a value}"; shift 2 ;;
    --bundletool) BUNDLETOOL="${2:?--bundletool needs a value}"; shift 2 ;;
    --skip-freshness) FRESH=0; shift ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
done
[ -n "$COMMIT" ] || { usage >&2; die "--commit is required (e.g. --commit HEAD for the checked-out PR head)"; }
[ -z "$PUBLISH" ] || [ -z "$CURATED" ] || die "--publish-evidence and --curated are mutually exclusive"

# ---------------------------------------------------------------- repository and protected roots
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(git -C "$HERE" rev-parse --show-toplevel 2>/dev/null)" || die "not in a git checkout"
ROOT="$(cd "$ROOT" && pwd -P)"
[ "$HERE" = "$ROOT/docs/release/appgallery/evidence" ] || die "run the copy in docs/release/appgallery/evidence"
INSPECT="$HERE/gallery1_inspect.py"
[ -f "$INSPECT" ] || die "missing $INSPECT"
COMMON="$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)" || die "no git common dir"
COMMON="$(cd "$COMMON" && pwd -P)"
PRIMARY="$(cd "$(dirname "$COMMON")" && pwd -P)"   # the primary checkout (== ROOT unless ROOT is a linked worktree)
HOME_R="$(cd "${HOME:-/}" 2>/dev/null && pwd -P || echo /)"

WORKTREES=()   # every registered worktree of this repository (symlinks resolved)
while IFS= read -r line; do
  case "$line" in worktree\ *) p="${line#worktree }"; [ -d "$p" ] && WORKTREES+=("$(cd "$p" && pwd -P)") ;; esac
done < <(git -C "$ROOT" worktree list --porcelain)

is_within() { [ "$1" = "$2" ] || case "$1/" in "$2"/*) return 0 ;; *) return 1 ;; esac; }

# Absolute, symlink-resolved form of $1 without creating anything; fails on ambiguous input.
resolve_path() {
  local p="$1" base rest=""
  case "$p" in /*) ;; *) return 1 ;; esac
  case "$p" in *$'\n'*|*'#'*|*//*) return 1 ;; esac
  case "$p/" in */./*|*/../*) return 1 ;; esac
  p="${p%/}"; [ -n "$p" ] || { echo /; return 0; }
  base="$p"
  while [ ! -d "$base" ]; do
    if [ -e "$base" ] || [ -L "$base" ]; then return 1; fi   # exists (or dangling link) but is not a directory
    rest="/${base##*/}$rest"; base="${base%/*}"; [ -n "$base" ] || base=/
  done
  base="$(cd "$base" && pwd -P)" || return 1
  [ "$base" = / ] && base=""
  printf '%s%s\n' "$base" "$rest"
}
existing_ancestor() { local d="$1"; while [ ! -d "$d" ]; do d="${d%/*}"; [ -n "$d" ] || d=/; done; printf '%s\n' "$d"; }

# Prints the validated path; exits 2 (from the subshell) with a message otherwise.
check_dir() {
  local label="$1" r p ea
  r="$(resolve_path "$2")" || die "$label: unsafe or ambiguous path '$2' (absolute path required; no '.', '..', '//', '#' or newline; no existing non-directory)"
  [ "$r" != / ] || die "$label: refuses /"
  [ "$r" != "$HOME_R" ] || die "$label: refuses the home directory itself"
  for p in "$ROOT" "$PRIMARY"; do
    is_within "$r" "$p" && die "$label: $r is inside the git checkout $p"
    is_within "$p" "$r" && die "$label: $r contains the git checkout $p"
  done
  for p in "${WORKTREES[@]}"; do
    is_within "$r" "$p" && die "$label: $r is inside the registered worktree $p"
  done
  ea="$(existing_ancestor "$r")"
  if git -C "$ea" rev-parse --git-dir >/dev/null 2>&1; then
    die "$label: $r is inside a git repository ($(git -C "$ea" rev-parse --show-toplevel 2>/dev/null || echo "$ea"))"
  fi
  printf '%s\n' "$r"
}

# ---------------------------------------------------------------- commit under test
if [ "$FETCH" = 1 ]; then
  [ "$DRY" = 0 ] || die "--fetch cannot be combined with --dry-run"
  git -C "$ROOT" fetch origin || die "git fetch origin failed"
fi
SHA="$(git -C "$ROOT" rev-parse --verify --quiet "$COMMIT^{commit}")" || die "cannot resolve --commit $COMMIT"
case "$SHA" in *[!0-9a-f]*|'') die "unexpected SHA '$SHA'" ;; esac
[ "${#SHA}" = 40 ] || die "expected a 40-hex SHA, got '$SHA'"
if [ -n "$EXPECT_SHA" ]; then
  [ "$EXPECT_SHA" = "$SHA" ] || die "--commit $COMMIT resolves to $SHA, not the expected $EXPECT_SHA"
fi

# ---------------------------------------------------------------- output locations (validated before creation)
# Default scratch root. TMPDIR often ends with "/" (macOS), so trailing slashes are stripped here;
# an explicitly passed path containing "//" is still refused as ambiguous.
TMPROOT="${TMPDIR:-/tmp}"
while [ "$TMPROOT" != / ] && [ "${TMPROOT%/}" != "$TMPROOT" ]; do TMPROOT="${TMPROOT%/}"; done
[ -n "$TMPROOT" ] || TMPROOT=/
WORK="$(check_dir --work-dir "${WORK:-${TMPROOT%/}/talentforge-gallery1}")" || exit 2
WT="$WORK/${SHA:0:12}/wt"
OUT="$(check_dir --out "${OUT:-$WORK/${SHA:0:12}/evidence}")" || exit 2
if [ -n "$PUBLISH" ]; then
  if ! printf '%s' "$PUBLISH" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' || case "$PUBLISH" in *..*) true ;; *) false ;; esac; then
    die "--publish-evidence: invalid directory name '$PUBLISH'"
  fi
  CURATED="$HERE/$PUBLISH"
  if [ -e "$CURATED" ] || [ -L "$CURATED" ]; then
    die "--publish-evidence: $CURATED already exists; historical evidence is never overwritten"
  fi
  [ -z "$(git -C "$ROOT" ls-files -- "docs/release/appgallery/evidence/$PUBLISH")" ] || die "--publish-evidence: $PUBLISH is tracked"
else
  CURATED="$(check_dir --curated "${CURATED:-$WORK/${SHA:0:12}/curated}")" || exit 2
fi
for a in "$OUT" "$CURATED"; do
  is_within "$a" "$WT" && die "output $a is inside the scratch worktree $WT"
  is_within "$WT" "$a" && die "output $a contains the scratch worktree $WT"
done
[ "$OUT" != "$CURATED" ] || die "--out and the curated directory must differ"

# ---------------------------------------------------------------- scratch worktree: reuse only if provably safe
WT_ACTION=create
if [ -e "$WT" ] || [ -L "$WT" ]; then
  { [ -d "$WT" ] && [ ! -L "$WT" ]; } || die "$WT exists and is not a directory"
  wtr="$(cd "$WT" && pwd -P)"
  registered=0
  for p in "${WORKTREES[@]}"; do [ "$p" = "$wtr" ] && registered=1; done
  [ "$registered" = 1 ] || die "$WT exists but is not a registered worktree of this repository; remove it"
  [ "$(cd "$(git -C "$WT" rev-parse --path-format=absolute --git-common-dir)" && pwd -P)" = "$COMMON" ] \
    || die "$WT belongs to another repository"
  git -C "$WT" symbolic-ref -q HEAD >/dev/null && die "$WT is on a branch, not detached; remove it"
  [ "$(git -C "$WT" rev-parse HEAD)" = "$SHA" ] || die "$WT exists at another commit; remove it"
  [ -z "$(git -C "$WT" status --porcelain)" ] || die "$WT has local changes; remove it"
  WT_ACTION=reuse
fi

echo "commit under test: $COMMIT -> $SHA"
echo "checkout:          $ROOT (primary: $PRIMARY)"
echo "scratch worktree:  $WT ($WT_ACTION)"
echo "logs:              $OUT"
echo "curated:           $CURATED${PUBLISH:+ (opt-in repository evidence)}"
if [ "$DRY" = 1 ]; then
  echo "dry run: all checks passed; nothing created, nothing run"
  exit 0
fi

# ---------------------------------------------------------------- create (only after every check passed)
if [ "$WT_ACTION" = create ]; then
  mkdir -p "$(dirname "$WT")"
  git -C "$ROOT" worktree add --detach "$WT" "$SHA" >/dev/null || die "git worktree add failed"
fi
mkdir -p "$OUT" "$CURATED"
[ "$(git -C "$WT" rev-parse HEAD)" = "$SHA" ] || die "worktree HEAD is not $SHA"
{ echo "requested: $COMMIT"; echo "sha: $SHA"; git -C "$WT" log -1 --format='commit-date: %cI%nsubject: %s'; } > "$OUT/00-commit.txt"

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
{ [ -n "$SDK" ] && [ -d "$SDK" ]; } || die "Android SDK not found (ANDROID_HOME or mobile/local.properties)"
[ -f "$WT/mobile/local.properties" ] || printf 'sdk.dir=%s\n' "$SDK" > "$WT/mobile/local.properties"  # git-ignored, scratch worktree only
BT="$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)"
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
scrub "$OUT/00-commit.txt" "$CURATED/00-commit.txt"
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
echo "commit:   $COMMIT -> $SHA"
echo "logs:     $OUT"
echo "curated:  $CURATED"
column -t -s "$(printf '\t')" "$SUMMARY" | cut -c1-150
