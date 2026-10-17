#!/usr/bin/env bash
# Release-signing self-test (M5.RC.2b, TF-RC1-SIGNING) with a TEMPORARY test key.
#
# Exercises the real Gradle signing configuration, the fail-closed publishable path
# (`:composeApp:assemblePublishableRelease`) and scripts/verify_release_apk.py without any
# production secret:
#
#   1. unsigned       no signing settings: assembleRelease builds the unsigned technical APK,
#                     and the validator rejects it
#   2. publish-none   no signing settings: assemblePublishableRelease fails, stages nothing
#   3. partial        two of four settings: every release build fails, naming the missing
#                     settings, printing no value
#   4. debug-key      the SDK debug keystore as release key: the build fails (SKIP if the
#                     machine has no debug keystore)
#   5. in-repo-key    a keystore inside the checkout: the build fails
#   6. signed         all four settings: assembleRelease signs; apksigner shows the test
#                     certificate and the validator accepts it
#   7. publish-nocert all settings, no expected fingerprint: the publishable task fails
#   8. publish-wrong  a wrong expected fingerprint: validation fails, nothing stays staged
#   9. publish-ok     the correct fingerprint: the APK and its PASS report are staged
#  10. stale-wrong    after a successful publishable build, a build with a wrong fingerprint
#                     fails and the previous APK is no longer staged
#  11. stale-none     after a successful publishable build, a build without signing settings
#                     fails and the previous APK is no longer staged
#  12. stale-partial  after a successful publishable build, a build with partial settings
#                     (fails in checkReleaseSigning) leaves no previous APK staged
#  13. debug-apk      the debug APK: the validator rejects the debug certificate
#  14. no-secrets     no log, report or build file contains the password or the key path
#
# The test key is created in a fresh directory OUTSIDE the repository, with a random
# password that exists only in this process's environment (keytool reads it through
# -storepass:env). It is NOT a production key. On exit (success, failure or interrupt) the
# key's directory and every artifact signed with the test key (composeApp/build/outputs/
# apk/release and outputs/publishable) are deleted; Gradle runs with --no-build-cache so no
# test-key output reaches the build cache.
#
# Usage: scripts/release_signing_selftest.sh [--evidence DIR]
#   --evidence DIR  also copy the logs (as .txt, home directory shown as ~) and the staged
#                   APK's report into DIR. The certificate digest in them is public and belongs
#                   to the deleted test key.
# Exit: 0 = every scenario passed, 1 = a scenario failed, 2 = cannot run.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOBILE="$REPO/mobile"
VALIDATOR="$REPO/scripts/verify_release_apk.py"
EVIDENCE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --evidence) EVIDENCE="${2:?--evidence needs a directory}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

die() { echo "selftest: $*" >&2; exit 2; }
command -v keytool >/dev/null || die "keytool not found (a JDK 17 on PATH is required)"
command -v python3 >/dev/null || die "python3 not found"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f "$MOBILE/local.properties" ]; then
  SDK="$(sed -n 's/^sdk\.dir=//p' "$MOBILE/local.properties")"
fi
{ [ -n "$SDK" ] && [ -d "$SDK/build-tools" ]; } || die "Android SDK not found (ANDROID_HOME or mobile/local.properties)"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"
BUILD_TOOLS="${BUILD_TOOLS%/}"
APKSIGNER="$BUILD_TOOLS/apksigner"
if grep -qs '^talentforge\.release\.' "${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"; then
  die "talentforge.release.* is set in the Gradle user properties; run this self-test where no release key is configured"
fi
for name in STORE_FILE STORE_PASSWORD KEY_ALIAS KEY_PASSWORD CERT_SHA256; do
  unset "TALENTFORGE_RELEASE_$name"
done

TMP="$(mktemp -d "${TMPDIR:-/tmp}/tf-signing-selftest.XXXXXX")" || die "mktemp failed"
case "$TMP/" in "$REPO"/*) rm -rf "$TMP"; die "the temporary directory is inside the repository" ;; esac
IN_REPO_KEY="$MOBILE/build/tf-signing-selftest-keystore.p12"
APK_RELEASE_DIR="$MOBILE/composeApp/build/outputs/apk/release"
PUBLISHABLE_DIR="$MOBILE/composeApp/build/outputs/publishable"
cleanup() {
  rm -rf "$TMP" "$APK_RELEASE_DIR" "$PUBLISHABLE_DIR"
  rm -f "$IN_REPO_KEY"
}
trap cleanup EXIT
trap 'exit 2' INT TERM
LOGS="$TMP/logs"
mkdir -p "$LOGS"

# ------------------------------------------------------------------ the temporary test key
KEYSTORE="$TMP/selftest-release.p12"
ALIAS="selftest"
SECRET="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
export TF_SELFTEST_SECRET="$SECRET"
keytool -genkeypair -storetype PKCS12 -keystore "$KEYSTORE" -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 2 \
  -dname "CN=TalentForge signing self-test - temporary - not a release key" \
  -storepass:env TF_SELFTEST_SECRET -keypass:env TF_SELFTEST_SECRET >"$LOGS/00-keytool.log" 2>&1 \
  || { cat "$LOGS/00-keytool.log" >&2; die "keytool could not create the test key"; }
CERT_SHA="$(keytool -list -v -keystore "$KEYSTORE" -alias "$ALIAS" -storepass:env TF_SELFTEST_SECRET \
  | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | tr -d ':' | tr 'A-F' 'a-f')"
[ ${#CERT_SHA} -eq 64 ] || die "could not read the test certificate fingerprint"
echo "test certificate SHA-256 (temporary key, deleted after the run): $CERT_SHA" >"$LOGS/00-test-certificate.txt"

FAILED=0
result() { # scenario status detail
  printf '%-4s  %-14s %s\n' "$2" "$1" "$3" | tee -a "$LOGS/summary.txt"
  case "$2" in PASS | SKIP) ;; *) FAILED=1 ;; esac
}
gradle() { # log-name tasks... (signing environment set by the caller)
  local log="$LOGS/$1.log"
  shift
  (cd "$MOBILE" && ./gradlew "$@" --console=plain --no-build-cache) >"$log" 2>&1
}
with_signing() { # runs "$@" with the complete test signing settings
  TALENTFORGE_RELEASE_STORE_FILE="$KEYSTORE" TALENTFORGE_RELEASE_STORE_PASSWORD="$SECRET" \
    TALENTFORGE_RELEASE_KEY_ALIAS="$ALIAS" TALENTFORGE_RELEASE_KEY_PASSWORD="$SECRET" "$@"
}
validate() { # log-name apk expected-sha [extra validator arguments]
  local log="$LOGS/$1.log" apk="$2" sha="$3"
  shift 3
  python3 "$VALIDATOR" --apk "$apk" --expected-cert-sha256 "$sha" --build-tools "$BUILD_TOOLS" "$@" >"$log" 2>&1
}
DEBUG_KS="${ANDROID_USER_HOME:-$HOME/.android}/debug.keystore"

# 1 — unsigned technical release
rm -rf "$APK_RELEASE_DIR"
if gradle 01-unsigned-assembleRelease :composeApp:assembleRelease \
  && [ -f "$APK_RELEASE_DIR/composeApp-release-unsigned.apk" ] && [ ! -f "$APK_RELEASE_DIR/composeApp-release.apk" ]; then
  validate 01-unsigned-validator "$APK_RELEASE_DIR/composeApp-release-unsigned.apk" "$CERT_SHA"
  rc=$?
  if [ $rc -eq 1 ] && grep -q '^FAIL  signature-verifies' "$LOGS/01-unsigned-validator.log"; then
    result unsigned PASS "unsigned APK built; the validator rejects it (signature-verifies FAIL)"
  else
    result unsigned FAIL "validator exit $rc on the unsigned APK"
  fi
else
  result unsigned FAIL "assembleRelease without signing settings did not produce only the unsigned APK"
fi

# 2 — publishable path without signing settings
if gradle 02-publish-none :composeApp:assemblePublishableRelease; then
  result publish-none FAIL "assemblePublishableRelease succeeded without signing settings"
elif grep -q 'needs the release signing configuration' "$LOGS/02-publish-none.log" && [ ! -e "$PUBLISHABLE_DIR" ]; then
  result publish-none PASS "fails before building; nothing staged"
else
  result publish-none FAIL "unexpected failure (see 02-publish-none.log)"
fi

# 3 — partial settings
if TALENTFORGE_RELEASE_STORE_FILE="$KEYSTORE" TALENTFORGE_RELEASE_KEY_ALIAS="$ALIAS" \
  gradle 03-partial :composeApp:assembleRelease; then
  result partial FAIL "assembleRelease succeeded with two of four settings"
elif grep -q 'Incomplete release signing configuration' "$LOGS/03-partial.log" \
  && grep -q 'TALENTFORGE_RELEASE_STORE_PASSWORD' "$LOGS/03-partial.log" \
  && ! grep -qF "$KEYSTORE" "$LOGS/03-partial.log"; then
  result partial PASS "fails naming the missing settings, without values"
else
  result partial FAIL "unexpected output (see 03-partial.log)"
fi

# 4 — the SDK debug keystore as release key (its password "android" is the public SDK default)
if [ -f "$DEBUG_KS" ]; then
  if TALENTFORGE_RELEASE_STORE_FILE="$DEBUG_KS" TALENTFORGE_RELEASE_STORE_PASSWORD=android \
    TALENTFORGE_RELEASE_KEY_ALIAS=androiddebugkey TALENTFORGE_RELEASE_KEY_PASSWORD=android \
    gradle 04-debug-key :composeApp:assembleRelease; then
    result debug-key FAIL "assembleRelease accepted the debug keystore"
  elif grep -q 'must not use the Android debug keystore' "$LOGS/04-debug-key.log"; then
    result debug-key PASS "the debug keystore is refused"
  else
    result debug-key FAIL "unexpected failure (see 04-debug-key.log)"
  fi
else
  result debug-key SKIP "no debug keystore on this machine"
fi

# 5 — a keystore inside the checkout (mobile/build/ is git-ignored; removed again below and on exit)
mkdir -p "$(dirname "$IN_REPO_KEY")" && cp "$KEYSTORE" "$IN_REPO_KEY"
if TALENTFORGE_RELEASE_STORE_FILE="$IN_REPO_KEY" TALENTFORGE_RELEASE_STORE_PASSWORD="$SECRET" \
  TALENTFORGE_RELEASE_KEY_ALIAS="$ALIAS" TALENTFORGE_RELEASE_KEY_PASSWORD="$SECRET" \
  gradle 05-in-repo-key :composeApp:assembleRelease; then
  result in-repo-key FAIL "assembleRelease accepted a keystore inside the repository"
elif grep -q 'outside the repository checkout' "$LOGS/05-in-repo-key.log"; then
  result in-repo-key PASS "a keystore inside the checkout is refused"
else
  result in-repo-key FAIL "unexpected failure (see 05-in-repo-key.log)"
fi
rm -f "$IN_REPO_KEY"

# 6 — signed release
rm -rf "$APK_RELEASE_DIR"
SIGNED="$APK_RELEASE_DIR/composeApp-release.apk"
if with_signing gradle 06-signed-assembleRelease :composeApp:assembleRelease && [ -f "$SIGNED" ]; then
  "$APKSIGNER" verify --verbose --print-certs "$SIGNED" >"$LOGS/06-signed-apksigner.log" 2>&1
  arc=$?
  apk_cert="$(sed -n 's/.*certificate SHA-256 digest: //p' "$LOGS/06-signed-apksigner.log" | sort -u)"
  validate 06-signed-validator "$SIGNED" "$CERT_SHA" --debug-keystore "$DEBUG_KS"
  vrc=$?
  if [ $arc -eq 0 ] && [ "$apk_cert" = "$CERT_SHA" ] && [ $vrc -eq 0 ]; then
    result signed PASS "apksigner verifies; certificate = the test key; validator PASS"
  else
    result signed FAIL "apksigner exit $arc, validator exit $vrc, certificate match: $([ "$apk_cert" = "$CERT_SHA" ] && echo yes || echo no)"
  fi
else
  result signed FAIL "assembleRelease with complete settings did not produce composeApp-release.apk"
fi

# 7 — publishable without the expected fingerprint
if with_signing gradle 07-publish-nocert :composeApp:assemblePublishableRelease; then
  result publish-nocert FAIL "assemblePublishableRelease succeeded without an expected fingerprint"
elif grep -q 'needs the expected certificate fingerprint' "$LOGS/07-publish-nocert.log" && [ ! -e "$PUBLISHABLE_DIR" ]; then
  result publish-nocert PASS "fails; nothing staged"
else
  result publish-nocert FAIL "unexpected failure (see 07-publish-nocert.log)"
fi

# 8 — publishable with a wrong fingerprint
WRONG="0000000000000000000000000000000000000000000000000000000000000000"
if TALENTFORGE_RELEASE_CERT_SHA256="$WRONG" with_signing gradle 08-publish-wrong :composeApp:assemblePublishableRelease; then
  result publish-wrong FAIL "assemblePublishableRelease accepted a wrong fingerprint"
elif grep -q '^FAIL  certificate-sha256-matches' "$LOGS/08-publish-wrong.log" \
  && [ -z "$(ls -A "$PUBLISHABLE_DIR" 2>/dev/null)" ]; then
  result publish-wrong PASS "validation fails on the fingerprint; nothing staged"
else
  result publish-wrong FAIL "unexpected outcome (see 08-publish-wrong.log)"
fi

# 9 — publishable with the correct fingerprint
if TALENTFORGE_RELEASE_CERT_SHA256="$CERT_SHA" with_signing gradle 09-publish-ok :composeApp:assemblePublishableRelease; then
  STAGED="$(ls "$PUBLISHABLE_DIR"/TalentForge-*.apk 2>/dev/null | head -1)"
  REPORT="$STAGED.report.json"
  if [ -n "$STAGED" ] && [ -f "$REPORT" ] && python3 - "$STAGED" "$REPORT" "$CERT_SHA" <<'PY'; then
import hashlib
import json
import sys
from pathlib import Path

apk, report, cert = sys.argv[1:]
data = json.loads(Path(report).read_text())
assert data["result"] == "PASS", data["result"]
assert data["apk"]["sha256"] == hashlib.sha256(Path(apk).read_bytes()).hexdigest()
assert data["signature"]["certificateSha256"] == [cert]
assert data["package"]["applicationId"] == "io.github.alexeykravtsov95.talentforge"
assert "arm64-v8a" in data["package"]["nativeCode"]
PY
    cp "$REPORT" "$LOGS/09-publish-ok.report.json"
    result publish-ok PASS "staged $(basename "$STAGED") with a PASS report (APK SHA-256 recorded)"
  else
    result publish-ok FAIL "staged APK or report missing or inconsistent"
  fi
else
  result publish-ok FAIL "assemblePublishableRelease failed with the correct fingerprint (see 09-publish-ok.log)"
fi

# 10-12 — a failed publishable attempt never leaves the previous attempt's APK staged.
# Each scenario first stages a good APK (as in 9), then runs a failing attempt.
publish_good() { # log-name → 0 when a good APK is staged
  TALENTFORGE_RELEASE_CERT_SHA256="$CERT_SHA" with_signing gradle "$1" :composeApp:assemblePublishableRelease \
    && ls "$PUBLISHABLE_DIR"/TalentForge-*.apk >/dev/null 2>&1
}
nothing_staged() { [ -z "$(ls -A "$PUBLISHABLE_DIR" 2>/dev/null)" ]; }

if publish_good 10a-stage; then
  if TALENTFORGE_RELEASE_CERT_SHA256="$WRONG" with_signing gradle 10-stale-wrong :composeApp:assemblePublishableRelease; then
    result stale-wrong FAIL "the wrong-fingerprint attempt succeeded"
  elif nothing_staged; then
    result stale-wrong PASS "attempt failed; the previous APK is gone from outputs/publishable"
  else
    result stale-wrong FAIL "the previous APK is still staged: $(ls "$PUBLISHABLE_DIR")"
  fi
else
  result stale-wrong FAIL "could not stage a good APK first (see 10a-stage.log)"
fi

if publish_good 11a-stage; then
  if gradle 11-stale-none :composeApp:assemblePublishableRelease; then
    result stale-none FAIL "the attempt without signing settings succeeded"
  elif nothing_staged; then
    result stale-none PASS "attempt failed before building; the previous APK is gone"
  else
    result stale-none FAIL "the previous APK is still staged: $(ls "$PUBLISHABLE_DIR")"
  fi
else
  result stale-none FAIL "could not stage a good APK first (see 11a-stage.log)"
fi

if publish_good 12a-stage; then
  if TALENTFORGE_RELEASE_STORE_FILE="$KEYSTORE" TALENTFORGE_RELEASE_KEY_ALIAS="$ALIAS" \
    TALENTFORGE_RELEASE_CERT_SHA256="$CERT_SHA" gradle 12-stale-partial :composeApp:assemblePublishableRelease; then
    result stale-partial FAIL "the attempt with partial settings succeeded"
  elif nothing_staged; then
    result stale-partial PASS "attempt failed in the signing checks; the previous APK is gone"
  else
    result stale-partial FAIL "the previous APK is still staged: $(ls "$PUBLISHABLE_DIR")"
  fi
else
  result stale-partial FAIL "could not stage a good APK first (see 12a-stage.log)"
fi

# 13 — the debug APK is rejected
DEBUG_APK="$MOBILE/composeApp/build/outputs/apk/debug/composeApp-debug.apk"
if [ -f "$DEBUG_APK" ] || gradle 13-assembleDebug :composeApp:assembleDebug; then
  validate 13-debug-validator "$DEBUG_APK" "$CERT_SHA" --debug-keystore "$DEBUG_KS"
  rc=$?
  if [ $rc -eq 1 ] && grep -q '^FAIL  not-debug-certificate-dn' "$LOGS/13-debug-validator.log"; then
    result debug-apk PASS "the validator rejects the debug certificate"
  else
    result debug-apk FAIL "validator exit $rc on the debug APK"
  fi
else
  result debug-apk FAIL "no debug APK"
fi

# 14 — no secret in the logs, the reports or the build directories
leaks="$(grep -rlF -e "$SECRET" -e "$KEYSTORE" "$LOGS" "$MOBILE/composeApp/build" "$MOBILE/build" 2>/dev/null)"
if [ -z "$leaks" ]; then
  result no-secrets PASS "no password or keystore path in the logs, reports or build/"
else
  result no-secrets FAIL "secret material found in: ${leaks//$HOME/~}"
fi

if [ -n "$EVIDENCE" ]; then
  mkdir -p "$EVIDENCE"
  for f in "$LOGS"/*; do
    [ "$(basename "$f")" = 00-keytool.log ] && continue
    name="$(basename "$f")"
    # *.log is git-ignored repository-wide: evidence logs are written as .txt so they can be committed
    case "$name" in *.log) name="${name%.log}.txt" ;; esac
    sed -e "s|$TMP|<tmp>|g" -e "s|$HOME|~|g" "$f" >"$EVIDENCE/$name"
  done
fi
echo "selftest: $([ $FAILED -eq 0 ] && echo PASS || echo FAIL) — the test key and test-signed outputs are deleted on exit"
exit $FAILED
