#!/usr/bin/env bash
# Executable path/worktree safety checks for run-gallery1-checks.sh (M5.GALLERY.1 review fixes).
#
# Builds a throw-away fixture under a fresh temporary directory: a primary checkout containing a
# copy of the runner and a tracked historical evidence file, a linked worktree of it, an unrelated
# git repository and a plain scratch directory. Every case runs the runner in --dry-run mode (the
# runner validates the commit and every path before creating anything) and asserts the exit status,
# the message and that nothing was created. Finally it asserts that the fixture checkouts are
# byte-for-byte unchanged. Needs only bash, git and coreutils; touches nothing outside the fixture.
#
# Usage: bash docs/release/appgallery/evidence/test-run-gallery1-safety.sh
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
RUNNER="$HERE/run-gallery1-checks.sh"
[ -f "$RUNNER" ] || { echo "missing $RUNNER" >&2; exit 2; }

T="$(mktemp -d "${TMPDIR:-/tmp}/gallery1-safety.XXXXXX")" || exit 2
T="$(cd "$T" && pwd -P)"
cleanup() { rm -rf "$T"; }
trap cleanup EXIT

export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
g() { git -c user.name=t -c user.email=t@example.invalid -c init.defaultBranch=main "$@"; }

REPO="$T/repo"; LINKED="$T/repo-linked"; OTHER="$T/other"; SCRATCH="$T/scratch"
EV=docs/release/appgallery/evidence
mkdir -p "$REPO/$EV/mac" "$OTHER" "$SCRATCH"
cp "$RUNNER" "$REPO/$EV/run-gallery1-checks.sh"
cp "$HERE/gallery1_inspect.py" "$REPO/$EV/gallery1_inspect.py"
printf 'historical\n' > "$REPO/$EV/mac/00-summary.tsv"
g -C "$REPO" init -q && g -C "$REPO" add -A && g -C "$REPO" commit -q -m base
SHA1="$(g -C "$REPO" rev-parse HEAD)"
printf 'second\n' > "$REPO/second.txt"; g -C "$REPO" add second.txt; g -C "$REPO" commit -q -m second
SHA2="$(g -C "$REPO" rev-parse HEAD)"
g -C "$REPO" worktree add -q -b side "$LINKED" "$SHA2"
g -C "$OTHER" init -q

snapshot() { (cd "$1" && find . -path ./.git -prune -o -type f -print0 | sort -z | xargs -0 cksum; g -C "$1" status --porcelain); }
BEFORE_REPO="$(snapshot "$REPO")"; BEFORE_LINKED="$(snapshot "$LINKED")"

PASS=0; FAIL=0
run() { # run <checkout> <args...>  -> sets RC and MSG
  local co="$1"; shift
  MSG="$(cd "$T" && bash "$co/$EV/run-gallery1-checks.sh" "$@" 2>&1)"; RC=$?
}
expect() { # expect <name> <rc> <message regex> <path that must NOT exist, or ->
  local name="$1" rc="$2" re="$3" absent="$4"
  if [ "$RC" = "$rc" ] && printf '%s' "$MSG" | grep -Eq -- "$re" && { [ "$absent" = - ] || [ ! -e "$absent" ]; }; then
    PASS=$((PASS + 1)); echo "ok   $name"
  else
    FAIL=$((FAIL + 1)); echo "FAIL $name (rc=$RC, wanted $rc /$re/)"; printf '%s\n' "$MSG" | sed 's/^/     | /' | tail -5
  fi
}

W="$SCRATCH/w"
run "$REPO" --dry-run --work-dir "$W";                                       expect "commit selection is mandatory" 2 "--commit is required" "$W"
run "$REPO" --commit HEAD --dry-run --work-dir "$W";                         expect "valid dry run records the full SHA, creates nothing" 0 "HEAD -> $SHA2" "$W"
run "$REPO" --commit "$SHA1" --expect-sha "$SHA2" --dry-run --work-dir "$W"; expect "expect-sha mismatch refused" 2 "not the expected" "$W"
run "$REPO" --commit HEAD --expect-sha "$SHA2" --dry-run --work-dir "$W";    expect "expect-sha match accepted" 0 "dry run: all checks passed" "$W"
run "$REPO" --commit no-such-ref --dry-run --work-dir "$W";                  expect "unknown ref refused" 2 "cannot resolve" "$W"
run "$REPO" --commit HEAD --dry-run --work-dir "$REPO/tmpw";                 expect "work dir inside the checkout refused" 2 "inside the git checkout" "$REPO/tmpw"
run "$LINKED" --commit HEAD --dry-run --work-dir "$REPO/tmpw";               expect "work dir inside the PRIMARY checkout (from a linked worktree) refused" 2 "inside the git checkout $REPO" "$REPO/tmpw"
run "$LINKED" --commit HEAD --dry-run --work-dir "$LINKED/tmpw";             expect "work dir inside the invoking linked worktree refused" 2 "inside the git checkout $LINKED" "$LINKED/tmpw"
run "$REPO" --commit HEAD --dry-run --work-dir "$LINKED/tmpw";               expect "work dir inside another registered worktree refused" 2 "inside the registered worktree" "$LINKED/tmpw"
run "$REPO" --commit HEAD --dry-run --work-dir "$T";                         expect "work dir containing the checkout refused" 2 "contains the git checkout" -
run "$REPO" --commit HEAD --dry-run --work-dir "$W" --out "$OTHER/o";        expect "output inside another git repository refused" 2 "inside a git repository" "$OTHER/o"
run "$REPO" --commit HEAD --dry-run --work-dir "$W" --curated "$REPO/$EV/mac"; expect "curated into tracked evidence refused" 2 "inside the git checkout" -
run "$REPO" --commit HEAD --dry-run --work-dir /;                            expect "/ refused" 2 "refuses /" -
mkdir -p "$T/home"
MSG="$(cd "$T" && HOME="$T/home" bash "$REPO/$EV/run-gallery1-checks.sh" --commit HEAD --dry-run --work-dir "$T/home" 2>&1)"; RC=$?
expect "home directory itself refused" 2 "refuses the home directory" -
run "$REPO" --commit HEAD --dry-run --work-dir "relative/w";                 expect "relative path refused" 2 "unsafe or ambiguous" "$T/relative"
run "$REPO" --commit HEAD --dry-run --work-dir "$SCRATCH/../escape";         expect "'..' refused" 2 "unsafe or ambiguous" "$T/escape"
run "$REPO" --commit HEAD --dry-run --work-dir "$SCRATCH/a#b";               expect "'#' refused" 2 "unsafe or ambiguous" "$SCRATCH/a#b"
touch "$SCRATCH/file"
run "$REPO" --commit HEAD --dry-run --work-dir "$SCRATCH/file/w";            expect "existing non-directory refused" 2 "unsafe or ambiguous" -
run "$REPO" --commit HEAD --dry-run --work-dir "$W" --out "$W/x" --curated "$W/x"; expect "out == curated refused" 2 "must differ" "$W"
run "$REPO" --commit HEAD --dry-run --work-dir "$W" --out "$W/${SHA2:0:12}/wt/logs"; expect "output inside the scratch worktree refused" 2 "inside the scratch worktree" "$W"
run "$REPO" --commit HEAD --dry-run --work-dir "$W" --publish-evidence mac;  expect "publish into the existing mac/ refused" 2 "already exists" -
run "$REPO" --commit HEAD --dry-run --work-dir "$W" --publish-evidence ../x; expect "publish with a path-like name refused" 2 "invalid directory name" "$REPO/$EV/x"
run "$REPO" --commit HEAD --dry-run --work-dir "$W" --publish-evidence run3 --curated "$SCRATCH/c"; expect "publish + curated refused" 2 "mutually exclusive" "$REPO/$EV/run3"
run "$REPO" --commit HEAD --dry-run --work-dir "$W" --publish-evidence run3; expect "publish into a new dir: dry run creates nothing" 0 "opt-in repository evidence" "$REPO/$EV/run3"
mkdir -p "$SCRATCH/tmpdir"
MSG="$(cd "$T" && TMPDIR="$SCRATCH/tmpdir/" bash "$REPO/$EV/run-gallery1-checks.sh" --commit HEAD --dry-run 2>&1)"; RC=$?
expect "default work dir with a trailing-slash TMPDIR (macOS) accepted" 0 "$SCRATCH/tmpdir/talentforge-gallery1/${SHA2:0:12}/wt \\(create\\)" "$SCRATCH/tmpdir/talentforge-gallery1"
run "$REPO" --commit HEAD --dry-run --work-dir "$SCRATCH//w";               expect "explicit '//' path refused" 2 "unsafe or ambiguous" "$SCRATCH/w"
run "$REPO" --commit HEAD --dry-run --fetch --work-dir "$W";                 expect "--fetch with --dry-run refused" 2 "cannot be combined" "$W"

# Scratch worktree reuse rules.
W2="$SCRATCH/w2"; mkdir -p "$W2/${SHA2:0:12}/wt"
run "$REPO" --commit HEAD --dry-run --work-dir "$W2";                        expect "unregistered existing directory refused" 2 "not a registered worktree" -
W3="$SCRATCH/w3"; g -C "$REPO" worktree add -q --detach "$W3/${SHA2:0:12}/wt" "$SHA1"
run "$REPO" --commit HEAD --dry-run --work-dir "$W3";                        expect "registered worktree at another commit refused" 2 "at another commit" -
W4="$SCRATCH/w4"; g -C "$REPO" worktree add -q --detach "$W4/${SHA2:0:12}/wt" "$SHA2"
run "$REPO" --commit HEAD --dry-run --work-dir "$W4";                        expect "clean detached worktree at the SHA is reused" 0 "\(reuse\)" -
printf 'dirty\n' >> "$W4/${SHA2:0:12}/wt/second.txt"
run "$REPO" --commit HEAD --dry-run --work-dir "$W4";                        expect "dirty reusable worktree refused" 2 "has local changes" -
W5="$SCRATCH/w5"; g -C "$REPO" worktree add -q -b onbranch "$W5/${SHA2:0:12}/wt" "$SHA2"
run "$REPO" --commit HEAD --dry-run --work-dir "$W5";                        expect "worktree on a branch refused" 2 "not detached" -
W6="$SCRATCH/w6"; mkdir -p "$W6/${SHA2:0:12}"; g -C "$OTHER" commit -q --allow-empty -m o
g -C "$OTHER" worktree add -q --detach "$W6/${SHA2:0:12}/wt" HEAD
run "$REPO" --commit HEAD --dry-run --work-dir "$W6";                        expect "worktree of another repository refused" 2 "not a registered worktree" -

# A real (non-dry-run) invocation with default output locations: it creates its scratch worktree,
# logs and curated directory below --work-dir, then stops at the missing Android SDK. Nothing may
# appear in the checkout.
R="$SCRATCH/real"
MSG="$(cd "$T" && ANDROID_HOME='' ANDROID_SDK_ROOT='' bash "$REPO/$EV/run-gallery1-checks.sh" --commit HEAD --work-dir "$R" 2>&1)"; RC=$?
expect "ordinary run stops at the missing SDK" 2 "Android SDK not found" -
if [ -d "$R/${SHA2:0:12}/curated" ] && [ -f "$R/${SHA2:0:12}/evidence/00-commit.txt" ] \
   && grep -q "^sha: $SHA2$" "$R/${SHA2:0:12}/evidence/00-commit.txt" \
   && [ "$(g -C "$R/${SHA2:0:12}/wt" rev-parse HEAD)" = "$SHA2" ]; then
  PASS=$((PASS + 1)); echo "ok   ordinary run wrote worktree, logs, curated dir and full SHA only under --work-dir"
else FAIL=$((FAIL + 1)); echo "FAIL ordinary run outputs not where expected"; fi
run "$REPO" --commit HEAD --dry-run --work-dir "$R";                         expect "worktree created by a previous run is reused" 0 "\(reuse\)" -

# The checkouts themselves must be unchanged (tracked historical evidence included).
if [ "$(snapshot "$REPO")" = "$BEFORE_REPO" ]; then PASS=$((PASS + 1)); echo "ok   primary checkout unchanged"
else FAIL=$((FAIL + 1)); echo "FAIL primary checkout changed"; fi
if [ "$(snapshot "$LINKED")" = "$BEFORE_LINKED" ]; then PASS=$((PASS + 1)); echo "ok   linked worktree unchanged"
else FAIL=$((FAIL + 1)); echo "FAIL linked worktree changed"; fi
if [ "$(cat "$REPO/$EV/mac/00-summary.tsv")" = historical ]; then PASS=$((PASS + 1)); echo "ok   historical evidence untouched"
else FAIL=$((FAIL + 1)); echo "FAIL historical evidence changed"; fi

echo "passed $PASS, failed $FAIL"
[ "$FAIL" = 0 ]
