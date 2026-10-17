# shellcheck shell=bash
# Shared helpers for the runnable M5.RC.1 audit entry points (run-rc1-checks.sh, run-rc1-rerun.sh).
# Sourced, never executed. Safety rules enforced here:
#   * the commit under test is explicit (--commit / RC1_COMMIT) and resolved to a full SHA;
#   * checks run in a DETACHED git worktree outside every checkout - no branch is created,
#     moved, reset or force-updated, and no checkout's working tree is modified;
#   * an existing worktree is reused only if it is clean and at exactly that SHA;
#   * every summary row records the SHA, and HEAD is re-verified before and after the run.

rc1_die() { echo "rc1: error: $*" >&2; exit 2; }

rc1_usage() {
  cat <<USAGE
Usage: $(basename "$0") --commit <sha|ref> [options]

Required:
  --commit REF        commit to audit (or RC1_COMMIT). The M5.RC.1 audit used 5648941.
Options:
  --work-dir DIR      scratch root (or RC1_WORK_DIR; default \${TMPDIR:-/tmp}/talentforge-rc1/<sha12>).
                      Must be outside every TalentForge checkout.
  --out DIR           evidence/log directory (or RC1_OUT_DIR; default <work-dir>/evidence-<script>)
  --python PATH       Python with the repo's dev dependencies (or RC1_PYTHON;
                      default <checkout>/.venv/bin/python, else python3)
  --fetch             'git fetch origin' first (updates remote-tracking refs only)
  --phone SERIAL      adb serial of a physical phone (or RC1_PHONE). No default:
                      without it the physical-phone checks are recorded as BLOCKED.
  --emulator SERIAL   adb serial of an emulator (or RC1_EMULATOR). No default.
  --ios-runtime NAME  simulator runtime header (or RC1_IOS_RUNTIME; default "iOS 26.4")
  --ios-sims LIST     ';'-separated simulator names (or RC1_IOS_SIMS;
                      default "iPhone 17e;iPad Air 11-inch (M4)")
  --dry-run           resolve and print every path/commit, create nothing, run nothing
  --setup-only        create/verify the detached worktree, then stop
  -h, --help          this help
USAGE
}

rc1_parse_args() {
  RC1_COMMIT="${RC1_COMMIT:-}"; RC1_WORK_DIR="${RC1_WORK_DIR:-}"; RC1_OUT_DIR="${RC1_OUT_DIR:-}"
  RC1_PYTHON="${RC1_PYTHON:-}"; RC1_PHONE="${RC1_PHONE:-}"; RC1_EMULATOR="${RC1_EMULATOR:-}"
  RC1_IOS_RUNTIME="${RC1_IOS_RUNTIME:-iOS 26.4}"
  RC1_IOS_SIMS="${RC1_IOS_SIMS:-iPhone 17e;iPad Air 11-inch (M4)}"
  RC1_FETCH=0; RC1_DRY_RUN=0; RC1_SETUP_ONLY=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --commit) RC1_COMMIT="${2:?--commit needs a value}"; shift 2 ;;
      --work-dir) RC1_WORK_DIR="${2:?--work-dir needs a value}"; shift 2 ;;
      --out) RC1_OUT_DIR="${2:?--out needs a value}"; shift 2 ;;
      --python) RC1_PYTHON="${2:?--python needs a value}"; shift 2 ;;
      --phone) RC1_PHONE="${2:?--phone needs a value}"; shift 2 ;;
      --emulator) RC1_EMULATOR="${2:?--emulator needs a value}"; shift 2 ;;
      --ios-runtime) RC1_IOS_RUNTIME="${2:?--ios-runtime needs a value}"; shift 2 ;;
      --ios-sims) RC1_IOS_SIMS="${2:?--ios-sims needs a value}"; shift 2 ;;
      --fetch) RC1_FETCH=1; shift ;;
      --dry-run) RC1_DRY_RUN=1; shift ;;
      --setup-only) RC1_SETUP_ONLY=1; shift ;;
      -h|--help) rc1_usage; exit 0 ;;
      *) rc1_usage >&2; rc1_die "unknown argument: $1" ;;
    esac
  done
}

# Resolves the checkout that contains these scripts, the commit, and every path.
rc1_resolve() {
  local script_name="$1" common
  RC1_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
  RC1_SOURCE_ROOT="$(git -C "$RC1_SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null)" \
    || rc1_die "$RC1_SCRIPT_DIR is not inside a git checkout"
  RC1_SOURCE_ROOT="$(cd "$RC1_SOURCE_ROOT" && pwd -P)"
  [ -f "$RC1_SOURCE_ROOT/scripts/mobile_bundles.py" ] && [ -d "$RC1_SOURCE_ROOT/mobile" ] \
    || rc1_die "$RC1_SOURCE_ROOT does not look like a TalentForge checkout"
  [ "$RC1_SCRIPT_DIR" = "$RC1_SOURCE_ROOT/docs/release/rc1-evidence" ] \
    || rc1_die "run the committed copy in <checkout>/docs/release/rc1-evidence (found $RC1_SCRIPT_DIR)"
  common="$(git -C "$RC1_SOURCE_ROOT" rev-parse --path-format=absolute --git-common-dir)"
  if [ "$(basename "$common")" = ".git" ]; then
    RC1_PRIMARY_ROOT="$(cd "$(dirname "$common")" && pwd -P)"
  else
    RC1_PRIMARY_ROOT=""
  fi

  [ -n "$RC1_COMMIT" ] || { rc1_usage >&2; rc1_die "--commit is required (the M5.RC.1 audit used 5648941)"; }
  if [ "$RC1_FETCH" = 1 ] && [ "$RC1_DRY_RUN" = 0 ]; then
    git -C "$RC1_SOURCE_ROOT" fetch --no-tags origin || rc1_die "git fetch failed"
  fi
  RC1_SHA="$(git -C "$RC1_SOURCE_ROOT" rev-parse --verify --quiet "${RC1_COMMIT}^{commit}")" \
    || rc1_die "commit '$RC1_COMMIT' is not known locally (use --fetch?)"

  RC1_WORK_DIR="${RC1_WORK_DIR:-${TMPDIR:-/tmp}/talentforge-rc1/${RC1_SHA:0:12}}"
  case "$RC1_WORK_DIR" in /*) ;; *) RC1_WORK_DIR="$PWD/$RC1_WORK_DIR" ;; esac
  # normalise symlinks (e.g. macOS /tmp -> /private/tmp) without creating anything
  if [ -d "$(dirname "$RC1_WORK_DIR")" ]; then
    RC1_WORK_DIR="$(cd "$(dirname "$RC1_WORK_DIR")" && pwd -P)/$(basename "$RC1_WORK_DIR")"
  fi
  local root
  for root in "$RC1_SOURCE_ROOT" "$RC1_PRIMARY_ROOT"; do
    [ -n "$root" ] || continue
    case "$RC1_WORK_DIR/" in "$root"/*) rc1_die "--work-dir must be outside the checkout $root";; esac
  done
  RC1_WT="$RC1_WORK_DIR/worktree"
  RC1_OUT_DIR="${RC1_OUT_DIR:-$RC1_WORK_DIR/evidence-$script_name}"

  if [ -z "$RC1_PYTHON" ]; then
    if [ -x "$RC1_SOURCE_ROOT/.venv/bin/python" ]; then RC1_PYTHON="$RC1_SOURCE_ROOT/.venv/bin/python"
    elif [ -n "$RC1_PRIMARY_ROOT" ] && [ -x "$RC1_PRIMARY_ROOT/.venv/bin/python" ]; then RC1_PYTHON="$RC1_PRIMARY_ROOT/.venv/bin/python"
    else RC1_PYTHON="$(command -v python3 || true)"; fi
  fi

  RC1_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$RC1_SDK" ]; then
    local props
    for props in "$RC1_SOURCE_ROOT/mobile/local.properties" "${RC1_PRIMARY_ROOT:+$RC1_PRIMARY_ROOT/mobile/local.properties}"; do
      [ -n "$props" ] && [ -f "$props" ] || continue
      RC1_SDK="$(sed -n 's/^sdk\.dir=//p' "$props" | head -1)"
      [ -n "$RC1_SDK" ] && break
    done
  fi
  RC1_ADB="${RC1_SDK:+$RC1_SDK/platform-tools/adb}"
}

rc1_print_plan() {
  cat <<PLAN
script dir     : $RC1_SCRIPT_DIR
source checkout: $RC1_SOURCE_ROOT
primary root   : ${RC1_PRIMARY_ROOT:-<none>}
commit (input) : $RC1_COMMIT
commit (SHA)   : $RC1_SHA
work dir       : $RC1_WORK_DIR
worktree       : $RC1_WT (detached at the SHA; $( [ -e "$RC1_WT" ] && echo exists || echo 'would be created'))
evidence dir   : $RC1_OUT_DIR
python         : ${RC1_PYTHON:-<not found>}
android sdk    : ${RC1_SDK:-<not found>}
phone serial   : ${RC1_PHONE:-<not set -> physical-phone checks BLOCKED>}
emulator serial: ${RC1_EMULATOR:-<not set -> emulator checks BLOCKED>}
ios simulators : $RC1_IOS_SIMS ($RC1_IOS_RUNTIME)
PLAN
}

# Creates, or verifies and reuses, the detached worktree at exactly RC1_SHA.
rc1_prepare_worktree() {
  if [ -e "$RC1_WT" ]; then
    local top head
    top="$(git -C "$RC1_WT" rev-parse --show-toplevel 2>/dev/null)" || rc1_die "$RC1_WT exists but is not a git worktree"
    [ "$(cd "$top" && pwd -P)" = "$RC1_WT" ] || rc1_die "$RC1_WT is not the top of a worktree"
    head="$(git -C "$RC1_WT" rev-parse HEAD)"
    [ "$head" = "$RC1_SHA" ] || rc1_die "$RC1_WT is at $head, not $RC1_SHA; remove it ('git worktree remove $RC1_WT') or use another --work-dir"
    [ -z "$(git -C "$RC1_WT" status --porcelain --untracked-files=no)" ] || rc1_die "$RC1_WT has tracked changes; refusing to use it"
  else
    mkdir -p "$(dirname "$RC1_WT")"
    git -C "$RC1_SOURCE_ROOT" worktree add --detach "$RC1_WT" "$RC1_SHA" || rc1_die "git worktree add failed"
  fi
  if [ ! -f "$RC1_WT/mobile/local.properties" ] && [ -n "$RC1_SDK" ]; then
    printf 'sdk.dir=%s\n' "$RC1_SDK" > "$RC1_WT/mobile/local.properties"   # git-ignored file
  fi
  rc1_assert_head
}

rc1_assert_head() {
  local head
  head="$(git -C "$RC1_WT" rev-parse HEAD)"
  [ "$head" = "$RC1_SHA" ] || rc1_die "worktree HEAD $head != $RC1_SHA - aborting rather than checking another commit"
}

# Common start: parse, resolve, dry-run/setup-only handling, evidence dir, python import check.
rc1_begin() {
  local script_name="$1"; shift
  rc1_parse_args "$@"
  rc1_resolve "$script_name"
  rc1_print_plan
  if [ "$RC1_DRY_RUN" = 1 ]; then echo "dry run: nothing created, nothing executed"; exit 0; fi
  rc1_prepare_worktree
  if [ "$RC1_SETUP_ONLY" = 1 ]; then echo "setup only: worktree ready at $RC1_WT ($RC1_SHA)"; exit 0; fi
  mkdir -p "$RC1_OUT_DIR"
  RC1_SUMMARY="$RC1_OUT_DIR/00-summary.tsv"
  printf 'id\tstatus\texit\tseconds\tcommit\tcommand\n' > "$RC1_SUMMARY"
  rc1_print_plan > "$RC1_OUT_DIR/00-plan.txt"
  # the target commit's own Python package must be the one under test
  export PYTHONPATH="$RC1_WT/src"
}

# rc1_step <id> <dir> <command...> -> PASS (exit 0) / FAIL (non-zero)
rc1_step() {
  local id="$1" dir="$2"; shift 2
  local cmd="$*" log="$RC1_OUT_DIR/$id.log" start rc status
  start=$(date +%s)
  echo "== [$id] $cmd"
  { echo "# cmd: $cmd"; echo "# dir: $dir"; echo "# commit: $RC1_SHA"; echo "# start: $(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$log"
  ( cd "$dir" && eval "$cmd" ) >> "$log" 2>&1
  rc=$?
  status=PASS; [ "$rc" -eq 0 ] || status=FAIL
  [ -n "${RC1_STATUS_OVERRIDE:-}" ] && status="$($RC1_STATUS_OVERRIDE "$rc")"
  echo "# exit: $rc  status: $status  duration_s: $(( $(date +%s) - start ))" >> "$log"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$id" "$status" "$rc" "$(( $(date +%s) - start ))" "$RC1_SHA" "$cmd" >> "$RC1_SUMMARY"
  echo "   -> $status (exit $rc)"
}

# rc1_blocked <id> <reason>: records a check that could not run (never PASS).
rc1_blocked() {
  printf '%s\tBLOCKED\t-\t0\t%s\t%s\n' "$1" "$RC1_SHA" "$2" >> "$RC1_SUMMARY"
  echo "== [$1] BLOCKED: $2"
}

rc1_device_connected() { # rc1_device_connected <serial>
  [ -n "$1" ] && [ -n "$RC1_ADB" ] && [ -x "$RC1_ADB" ] \
    && "$RC1_ADB" devices | awk -v s="$1" '$1 == s && $2 == "device" { found = 1 } END { exit !found }'
}

rc1_end() {
  rc1_assert_head
  echo; echo "commit audited: $RC1_SHA"; echo "evidence: $RC1_OUT_DIR"
  column -t -s $'\t' "$RC1_SUMMARY" 2>/dev/null | cut -c1-150 || cat "$RC1_SUMMARY"
}
