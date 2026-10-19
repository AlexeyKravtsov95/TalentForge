#!/usr/bin/env bash
# Google Play two-key signing self-test (M5.PLAY.1) with TWO TEMPORARY test keys.
#
# The app signing key signs the RuStore/AppGallery APK (:composeApp:assemblePublishableRelease);
# a separate upload key signs the Google Play bundle (:composeApp:bundlePublishablePlay). This test
# drives the real Gradle configuration, scripts/verify_release_apk.py and scripts/verify_play_bundle.py
# with two throwaway keys and no production secret:
#
#   1. two-keys       one build stages both: the APK carries the app-signing test certificate and
#                     the AAB the upload test certificate (read back with apksigner / keytool)
#   2. distinct       the two certificates differ
#   3. metadata       applicationId, versionCode, versionName, minSdk, targetSdk, ABIs and
#                     permissions are equal in the APK and AAB reports; no INTERNET (NET-1 = B)
#   4. wrong-cert     a wrong expected upload fingerprint: validation fails, nothing staged
#   5. same-cert      the upload fingerprint set to the app signing one: refused before building
#   6. same-key       the upload settings naming the release keystore and alias: refused
#   7. aab-app-key    an AAB signed with a COPY of the app signing key: validation fails
#   8. apk-upload-key an APK signed with the upload key: validation fails
#   9. play-none      no upload settings: bundlePublishablePlay fails before building
#  10. play-partial   two of four upload settings: every play build fails, naming the missing
#                     settings, printing no value
#  11. no-app-cert    no app signing fingerprint: bundlePublishablePlay fails before building
#  12. unsigned-aab   bundlePlay without settings: the unsigned bundle is rejected
#  13. release-aab    bundleRelease (signed with the app signing key): rejected for Google Play
#  14. debug-aab      bundleDebug: rejected (debug certificate, debuggable)
#  15. play-apk       an APK of the play build type is refused
#  16. stale          after a successful publishable bundle, a failing attempt leaves none staged
#  17. not-selftest   without TALENTFORGE_SIGNING_SELFTEST the test keys are refused by both
#                     publishable tasks: a test-key artifact is never a release artifact
#  18. play-apks-16kb APKs generated from the staged AAB by bundletool: 64-bit native libraries
#                     stored uncompressed at 16 KB boundaries (SKIP without bundletool)
#  19. no-secrets     no log, report or build file contains a password or a keystore path
#  20. cleanup        the keys, the test-signed outputs and the staged files are deleted
#
# Every staged file of a passing run is named *-SELFTEST-NOT-FOR-UPLOAD and its report says
# SELFTEST-PASS, never PASS. The keys are created in a fresh directory OUTSIDE the repository, each
# with its own random password that exists only in this process's environment. On exit (success,
# failure or interrupt) the key directory and every artifact signed with a test key are deleted;
# Gradle runs with --no-build-cache so no test-key output reaches the build cache.
#
# Usage: scripts/play_signing_selftest.sh [--evidence DIR]
#   --evidence DIR  also copy the logs (as .txt, home directory shown as ~) and both reports into
#                   DIR. Their certificate digests are public and belong to the deleted test keys.
# Exit: 0 = every scenario passed, 1 = a scenario failed, 2 = cannot run.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOBILE="$REPO/mobile"
AAB_VALIDATOR="$REPO/scripts/verify_play_bundle.py"
EVIDENCE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --evidence) EVIDENCE="${2:?--evidence needs a directory}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

die() { echo "play-selftest: $*" >&2; exit 2; }
command -v keytool >/dev/null || die "keytool not found (a JDK 17 on PATH is required)"
command -v jarsigner >/dev/null || die "jarsigner not found (a JDK 17 on PATH is required)"
command -v python3 >/dev/null || die "python3 not found"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f "$MOBILE/local.properties" ]; then
  SDK="$(sed -n 's/^sdk\.dir=//p' "$MOBILE/local.properties")"
fi
{ [ -n "$SDK" ] && [ -d "$SDK/build-tools" ]; } || die "Android SDK not found (ANDROID_HOME or mobile/local.properties)"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"
BUILD_TOOLS="${BUILD_TOOLS%/}"
if grep -qsE '^talentforge\.(release|play)\.' "${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"; then
  die "talentforge.release.* or talentforge.play.* is set in the Gradle user properties; run this self-test where no release key is configured"
fi
for name in STORE_FILE STORE_PASSWORD KEY_ALIAS KEY_PASSWORD CERT_SHA256; do
  unset "TALENTFORGE_RELEASE_$name" "TALENTFORGE_PLAY_UPLOAD_$name"
done
unset TALENTFORGE_SIGNING_SELFTEST

TMP="$(mktemp -d "${TMPDIR:-/tmp}/tf-play-selftest.XXXXXX")" || die "mktemp failed"
case "$TMP/" in "$REPO"/*) rm -rf "$TMP"; die "the temporary directory is inside the repository" ;; esac
OUTPUTS="$MOBILE/composeApp/build/outputs"
SIGNED_OUTPUTS=("$OUTPUTS/apk/release" "$OUTPUTS/bundle" "$OUTPUTS/publishable" "$OUTPUTS/publishable-google-play")
APK_DIR="$OUTPUTS/publishable"
AAB_DIR="$OUTPUTS/publishable-google-play"
cleanup() { rm -rf "$TMP" "${SIGNED_OUTPUTS[@]}"; }
trap cleanup EXIT
trap 'exit 2' INT TERM
LOGS="$TMP/logs"
mkdir -p "$LOGS"

# ------------------------------------------------------------------ the two temporary test keys
make_key() { # file alias secret-variable dn → prints the certificate SHA-256
  keytool -genkeypair -storetype PKCS12 -keystore "$1" -alias "$2" -keyalg RSA -keysize 4096 -validity 2 \
    -dname "$4" -storepass:env "$3" -keypass:env "$3" >>"$LOGS/00-keytool.log" 2>&1 || return 1
  keytool -list -v -keystore "$1" -alias "$2" -storepass:env "$3" \
    | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | tr -d ':' | tr 'A-F' 'a-f'
}
APP_KS="$TMP/app-signing-test.p12"
UPLOAD_KS="$TMP/upload-test.p12"
APP_ALIAS="appsigning"
UPLOAD_ALIAS="upload"
TF_APP_SECRET="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
TF_UPLOAD_SECRET="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
export TF_APP_SECRET TF_UPLOAD_SECRET
APP_CERT="$(make_key "$APP_KS" "$APP_ALIAS" TF_APP_SECRET "CN=TalentForge signing self-test - temporary - app signing test key")"
UPLOAD_CERT="$(make_key "$UPLOAD_KS" "$UPLOAD_ALIAS" TF_UPLOAD_SECRET "CN=TalentForge signing self-test - temporary - upload test key")"
{ [ ${#APP_CERT} -eq 64 ] && [ ${#UPLOAD_CERT} -eq 64 ]; } || { cat "$LOGS/00-keytool.log" >&2; die "could not create the test keys"; }
{
  echo "app signing test certificate SHA-256 (temporary key, deleted after the run): $APP_CERT"
  echo "upload test certificate SHA-256 (temporary key, deleted after the run):      $UPLOAD_CERT"
} >"$LOGS/00-test-certificates.txt"
WRONG="0000000000000000000000000000000000000000000000000000000000000000"

FAILED=0
result() { # scenario status detail
  printf '%-4s  %-15s %s\n' "$2" "$1" "$3" | tee -a "$LOGS/summary.txt"
  case "$2" in PASS | SKIP) ;; *) FAILED=1 ;; esac
}
gradle() { # log-name tasks... (signing environment set by the caller)
  local log="$LOGS/$1.log"
  shift
  (cd "$MOBILE" && ./gradlew "$@" --console=plain --no-build-cache) >"$log" 2>&1
}
with_app() { # runs "$@" with the app signing (release) settings and fingerprint
  TALENTFORGE_RELEASE_STORE_FILE="$APP_KS" TALENTFORGE_RELEASE_STORE_PASSWORD="$TF_APP_SECRET" \
    TALENTFORGE_RELEASE_KEY_ALIAS="$APP_ALIAS" TALENTFORGE_RELEASE_KEY_PASSWORD="$TF_APP_SECRET" \
    TALENTFORGE_RELEASE_CERT_SHA256="$APP_CERT" "$@"
}
with_upload() { # runs "$@" with the upload settings and fingerprint (overridable per scenario)
  TALENTFORGE_PLAY_UPLOAD_STORE_FILE="${UPLOAD_KS_OVERRIDE:-$UPLOAD_KS}" \
    TALENTFORGE_PLAY_UPLOAD_STORE_PASSWORD="${UPLOAD_SECRET_OVERRIDE:-$TF_UPLOAD_SECRET}" \
    TALENTFORGE_PLAY_UPLOAD_KEY_ALIAS="${UPLOAD_ALIAS_OVERRIDE:-$UPLOAD_ALIAS}" \
    TALENTFORGE_PLAY_UPLOAD_KEY_PASSWORD="${UPLOAD_SECRET_OVERRIDE:-$TF_UPLOAD_SECRET}" \
    TALENTFORGE_PLAY_UPLOAD_CERT_SHA256="${UPLOAD_CERT_OVERRIDE:-$UPLOAD_CERT}" "$@"
}
selftest() { TALENTFORGE_SIGNING_SELFTEST=true "$@"; }
validate_aab() { # log-name aab [extra validator arguments]
  local log="$LOGS/$1.log" aab="$2"
  shift 2
  python3 "$AAB_VALIDATOR" --aab "$aab" --expected-upload-cert-sha256 "$UPLOAD_CERT" \
    --app-signing-cert-sha256 "$APP_CERT" "$@" >"$log" 2>&1
}
nothing_in() { [ -z "$(ls -A "$1" 2>/dev/null)" ]; }
DEBUG_KS="${ANDROID_USER_HOME:-$HOME/.android}/debug.keystore"

# 1-3 — one build stages both artifacts, each with its own key
STAGED_APK=""
STAGED_AAB=""
if selftest with_app with_upload gradle 01-two-keys :composeApp:assemblePublishableRelease :composeApp:bundlePublishablePlay; then
  STAGED_APK="$(ls "$APK_DIR"/TalentForge-*-SELFTEST-NOT-FOR-UPLOAD.apk 2>/dev/null | head -1)"
  STAGED_AAB="$(ls "$AAB_DIR"/TalentForge-*-SELFTEST-NOT-FOR-UPLOAD.aab 2>/dev/null | head -1)"
fi
if [ -n "$STAGED_APK" ] && [ -n "$STAGED_AAB" ]; then
  "$BUILD_TOOLS/apksigner" verify --print-certs "$STAGED_APK" >"$LOGS/01-apksigner.log" 2>&1
  apk_cert="$(sed -n 's/.*certificate SHA-256 digest: //p' "$LOGS/01-apksigner.log" | sort -u)"
  keytool -printcert -jarfile "$STAGED_AAB" >"$LOGS/01-keytool-printcert.log" 2>&1
  aab_cert="$(sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' "$LOGS/01-keytool-printcert.log" | tr -d ':' | tr 'A-F' 'a-f' | sort -u)"
  if [ "$apk_cert" = "$APP_CERT" ] && [ "$aab_cert" = "$UPLOAD_CERT" ]; then
    result two-keys PASS "APK = app signing test key, AAB = upload test key (apksigner, keytool)"
  else
    result two-keys FAIL "APK certificate is the app key: $([ "$apk_cert" = "$APP_CERT" ] && echo yes || echo no); AAB certificate is the upload key: $([ "$aab_cert" = "$UPLOAD_CERT" ] && echo yes || echo no)"
  fi
  if [ "$APP_CERT" != "$UPLOAD_CERT" ] && [ "$apk_cert" != "$aab_cert" ]; then
    result distinct PASS "the two certificates differ"
  else
    result distinct FAIL "the APK and AAB certificates are the same"
  fi
  cp "$STAGED_APK.report.json" "$LOGS/01-apk.report.json"
  cp "$STAGED_AAB.report.json" "$LOGS/01-aab.report.json"
  if python3 - "$LOGS/01-apk.report.json" "$LOGS/01-aab.report.json" "$APP_CERT" "$UPLOAD_CERT" >"$LOGS/03-metadata.log" 2>&1 <<'PY'; then
import json
import sys
from pathlib import Path

apk, aab, app_cert, upload_cert = sys.argv[1:]
a = json.loads(Path(apk).read_text())
b = json.loads(Path(aab).read_text())
for report in (a, b):
    assert report["result"] == "SELFTEST-PASS", report["result"]
    assert report["purpose"] == "signing-selftest", report["purpose"]
assert a["signature"]["certificateSha256"] == [app_cert]
assert b["signature"]["certificateSha256"] == [upload_cert]
for key in ("applicationId", "versionCode", "versionName", "minSdk", "targetSdk", "permissions"):
    assert a["package"][key] == b["package"][key], (key, a["package"][key], b["package"][key])
assert sorted(a["package"]["nativeCode"]) == sorted(b["package"]["nativeCode"])
assert "android.permission.INTERNET" not in a["package"]["permissions"]
print({key: a["package"][key] for key in ("applicationId", "versionCode", "versionName", "minSdk", "targetSdk", "nativeCode", "permissions")})
PY
    result metadata PASS "release metadata, ABIs and permissions equal; no INTERNET; both SELFTEST-PASS"
  else
    result metadata FAIL "reports differ or are not SELFTEST-PASS (see 03-metadata.log)"
  fi
else
  result two-keys FAIL "the two-key build did not stage both artifacts (see 01-two-keys.log)"
  result distinct FAIL "not reached"
  result metadata FAIL "not reached"
fi

# 4 — a wrong expected upload fingerprint
if UPLOAD_CERT_OVERRIDE="$WRONG" selftest with_app with_upload gradle 04-wrong-cert :composeApp:bundlePublishablePlay; then
  result wrong-cert FAIL "bundlePublishablePlay accepted a wrong upload fingerprint"
elif grep -q '^FAIL  upload-certificate-sha256-matches' "$LOGS/04-wrong-cert.log" && nothing_in "$AAB_DIR"; then
  result wrong-cert PASS "validation fails on the fingerprint; nothing staged"
else
  result wrong-cert FAIL "unexpected outcome (see 04-wrong-cert.log)"
fi

# 5 — the upload fingerprint set to the app signing fingerprint
if UPLOAD_CERT_OVERRIDE="$APP_CERT" selftest with_app with_upload gradle 05-same-cert :composeApp:bundlePublishablePlay; then
  result same-cert FAIL "bundlePublishablePlay accepted one fingerprint for both roles"
elif grep -q 'Google Play needs a' "$LOGS/05-same-cert.log" && nothing_in "$AAB_DIR"; then
  result same-cert PASS "refused before building; nothing staged"
else
  result same-cert FAIL "unexpected outcome (see 05-same-cert.log)"
fi

# 6 — the upload settings naming the release keystore and alias
if UPLOAD_KS_OVERRIDE="$APP_KS" UPLOAD_ALIAS_OVERRIDE="$APP_ALIAS" UPLOAD_SECRET_OVERRIDE="$TF_APP_SECRET" \
  selftest with_app with_upload gradle 06-same-key :composeApp:bundlePlay; then
  result same-key FAIL "a play build accepted the release key as upload key"
elif grep -q 'upload key must not be the app signing key' "$LOGS/06-same-key.log" \
  && ! grep -qF -e "$TF_APP_SECRET" -e "$APP_KS" "$LOGS/06-same-key.log"; then
  result same-key PASS "refused, naming the settings without values"
else
  result same-key FAIL "unexpected outcome (see 06-same-key.log)"
fi

# 7 — an AAB signed with a copy of the app signing key (another file: only the certificate tells)
APP_COPY="$TMP/app-signing-copy.p12"
cp "$APP_KS" "$APP_COPY"
if UPLOAD_KS_OVERRIDE="$APP_COPY" UPLOAD_ALIAS_OVERRIDE="$APP_ALIAS" UPLOAD_SECRET_OVERRIDE="$TF_APP_SECRET" \
  selftest with_app with_upload gradle 07-aab-app-key :composeApp:bundlePublishablePlay; then
  result aab-app-key FAIL "bundlePublishablePlay accepted a bundle signed with the app signing key"
elif grep -q '^FAIL  certificate-differs-from-other-role' "$LOGS/07-aab-app-key.log" \
  && grep -q '^FAIL  upload-certificate-sha256-matches' "$LOGS/07-aab-app-key.log" && nothing_in "$AAB_DIR"; then
  result aab-app-key PASS "rejected: the certificate is the app signing key's; nothing staged"
else
  result aab-app-key FAIL "unexpected outcome (see 07-aab-app-key.log)"
fi

# 8 — an APK signed with the upload key (the owner mistakes the upload key for the release key)
if TALENTFORGE_RELEASE_STORE_FILE="$UPLOAD_KS" TALENTFORGE_RELEASE_STORE_PASSWORD="$TF_UPLOAD_SECRET" \
  TALENTFORGE_RELEASE_KEY_ALIAS="$UPLOAD_ALIAS" TALENTFORGE_RELEASE_KEY_PASSWORD="$TF_UPLOAD_SECRET" \
  TALENTFORGE_RELEASE_CERT_SHA256="$UPLOAD_CERT" TALENTFORGE_PLAY_UPLOAD_CERT_SHA256="$UPLOAD_CERT" \
  selftest gradle 08-apk-upload-key :composeApp:assemblePublishableRelease; then
  result apk-upload-key FAIL "assemblePublishableRelease accepted an APK signed with the upload key"
elif grep -q '^FAIL  certificate-differs-from-other-role' "$LOGS/08-apk-upload-key.log" && nothing_in "$APK_DIR"; then
  result apk-upload-key PASS "rejected: the certificate is the upload key's; nothing staged"
else
  result apk-upload-key FAIL "unexpected outcome (see 08-apk-upload-key.log)"
fi

# 9 — no upload settings
if selftest with_app gradle 09-play-none :composeApp:bundlePublishablePlay; then
  result play-none FAIL "bundlePublishablePlay succeeded without upload settings"
elif grep -q 'needs the upload signing configuration' "$LOGS/09-play-none.log" && nothing_in "$AAB_DIR"; then
  result play-none PASS "fails before building; nothing staged"
else
  result play-none FAIL "unexpected failure (see 09-play-none.log)"
fi

# 10 — partial upload settings
if TALENTFORGE_PLAY_UPLOAD_STORE_FILE="$UPLOAD_KS" TALENTFORGE_PLAY_UPLOAD_KEY_ALIAS="$UPLOAD_ALIAS" \
  gradle 10-play-partial :composeApp:bundlePlay; then
  result play-partial FAIL "bundlePlay succeeded with two of four settings"
elif grep -q 'Incomplete Google Play upload signing configuration' "$LOGS/10-play-partial.log" \
  && grep -q 'TALENTFORGE_PLAY_UPLOAD_STORE_PASSWORD' "$LOGS/10-play-partial.log" \
  && ! grep -qF "$UPLOAD_KS" "$LOGS/10-play-partial.log"; then
  result play-partial PASS "fails naming the missing settings, without values"
else
  result play-partial FAIL "unexpected output (see 10-play-partial.log)"
fi

# 11 — no app signing fingerprint
if selftest with_upload gradle 11-no-app-cert :composeApp:bundlePublishablePlay; then
  result no-app-cert FAIL "bundlePublishablePlay succeeded without the app signing fingerprint"
elif grep -q 'needs the app signing certificate fingerprint' "$LOGS/11-no-app-cert.log" && nothing_in "$AAB_DIR"; then
  result no-app-cert PASS "fails before building; nothing staged"
else
  result no-app-cert FAIL "unexpected failure (see 11-no-app-cert.log)"
fi

# 12 — the unsigned technical bundle
UNSIGNED_AAB="$OUTPUTS/bundle/play/composeApp-play.aab"
rm -rf "$OUTPUTS/bundle/play"
if gradle 12-bundlePlay-unsigned :composeApp:bundlePlay && [ -f "$UNSIGNED_AAB" ]; then
  validate_aab 12-unsigned-validator "$UNSIGNED_AAB" --selftest
  rc=$?
  if [ $rc -eq 1 ] && grep -q '^FAIL  signature-verifies' "$LOGS/12-unsigned-validator.log"; then
    result unsigned-aab PASS "unsigned bundle built; the validator rejects it"
  else
    result unsigned-aab FAIL "validator exit $rc on the unsigned bundle"
  fi
else
  result unsigned-aab FAIL "bundlePlay without settings did not produce the unsigned bundle"
fi

# 13 — bundleRelease is signed with the app signing key: never the Google Play upload
RELEASE_AAB="$OUTPUTS/bundle/release/composeApp-release.aab"
if with_app gradle 13-bundleRelease :composeApp:bundleRelease && [ -f "$RELEASE_AAB" ]; then
  validate_aab 13-release-validator "$RELEASE_AAB" --selftest
  rc=$?
  if [ $rc -eq 1 ] && grep -q '^FAIL  certificate-differs-from-other-role' "$LOGS/13-release-validator.log"; then
    result release-aab PASS "bundleRelease carries the app signing key; rejected for Google Play"
  else
    result release-aab FAIL "validator exit $rc on the release bundle"
  fi
else
  result release-aab FAIL "bundleRelease did not produce a bundle (see 13-bundleRelease.log)"
fi

# 14 — the debug bundle
DEBUG_AAB="$OUTPUTS/bundle/debug/composeApp-debug.aab"
if gradle 14-bundleDebug :composeApp:bundleDebug && [ -f "$DEBUG_AAB" ]; then
  validate_aab 14-debug-validator "$DEBUG_AAB" --debug-keystore "$DEBUG_KS"
  rc=$?
  if [ $rc -eq 1 ] && grep -q '^FAIL  not-debuggable' "$LOGS/14-debug-validator.log" \
    && grep -q '^FAIL  not-debug-certificate-dn' "$LOGS/14-debug-validator.log"; then
    result debug-aab PASS "rejected: debuggable, debug certificate"
  else
    result debug-aab FAIL "validator exit $rc on the debug bundle"
  fi
else
  result debug-aab FAIL "no debug bundle (see 14-bundleDebug.log)"
fi

# 15 — an APK of the play build type
if selftest with_upload gradle 15-play-apk :composeApp:assemblePlay; then
  result play-apk FAIL "assemblePlay produced an upload-key APK"
elif grep -q 'play build type builds only the Google Play bundle' "$LOGS/15-play-apk.log" \
  && [ -z "$(find "$OUTPUTS/apk/play" -name '*.apk' 2>/dev/null)" ]; then
  result play-apk PASS "refused; no play APK exists"
else
  result play-apk FAIL "unexpected outcome (see 15-play-apk.log)"
fi

# 16 — a failing attempt never leaves the previous bundle staged
if selftest with_app with_upload gradle 16a-stage :composeApp:bundlePublishablePlay && ! nothing_in "$AAB_DIR"; then
  if TALENTFORGE_PLAY_UPLOAD_STORE_FILE="$UPLOAD_KS" TALENTFORGE_PLAY_UPLOAD_KEY_ALIAS="$UPLOAD_ALIAS" \
    selftest with_app gradle 16-stale :composeApp:bundlePublishablePlay; then
    result stale FAIL "the attempt with partial settings succeeded"
  elif nothing_in "$AAB_DIR"; then
    result stale PASS "attempt failed in the signing checks; the previous bundle is gone"
  else
    result stale FAIL "the previous bundle is still staged: $(ls "$AAB_DIR")"
  fi
else
  result stale FAIL "could not stage a bundle first (see 16a-stage.log)"
fi

# 17 — test keys without the self-test switch: refused, nothing staged anywhere
if with_app with_upload gradle 17-not-selftest :composeApp:assemblePublishableRelease :composeApp:bundlePublishablePlay --continue; then
  result not-selftest FAIL "a test key passed as a release"
elif [ "$(grep -c '^FAIL  not-selftest-certificate' "$LOGS/17-not-selftest.log")" -eq 2 ] \
  && nothing_in "$APK_DIR" && nothing_in "$AAB_DIR"; then
  result not-selftest PASS "both validators refuse the test keys; nothing staged"
else
  result not-selftest FAIL "unexpected outcome (see 17-not-selftest.log)"
fi

# 18 — the APKs Google Play would generate: 16 KB-aligned native libraries (bundletool, local only)
if command -v bundletool >/dev/null; then
  if selftest with_app with_upload gradle 18a-stage :composeApp:bundlePublishablePlay; then
    AAB="$(ls "$AAB_DIR"/*.aab | head -1)"
    APKS="$TMP/play.apks"
    # signed with the upload test key, so bundletool does not fall back to the debug keystore; it reads
    # the password from a private file in $TMP (never "pass:", which would show in the process list)
    PASS_FILE="$TMP/upload.pass"
    (umask 077 && printf '%s' "$TF_UPLOAD_SECRET" >"$PASS_FILE")
    if bundletool build-apks --bundle="$AAB" --output="$APKS" --ks="$UPLOAD_KS" --ks-key-alias="$UPLOAD_ALIAS" \
      --ks-pass="file:$PASS_FILE" --key-pass="file:$PASS_FILE" >"$LOGS/18-bundletool.log" 2>&1; then
      mkdir -p "$TMP/apks" && (cd "$TMP/apks" && unzip -q "$APKS")
      ok=1
      checked=0
      for split in "$TMP"/apks/splits/*arm64_v8a*.apk "$TMP"/apks/splits/*x86_64*.apk "$TMP"/apks/standalones/*.apk; do
        [ -f "$split" ] || continue
        checked=$((checked + 1))
        echo "== ${split#"$TMP"/apks/}" >>"$LOGS/18-zipalign.log"
        "$BUILD_TOOLS/zipalign" -c -P 16 -v 4 "$split" >>"$LOGS/18-zipalign.log" 2>&1 || ok=0
        unzip -v "$split" 'lib/*.so' 2>/dev/null \
          | awk '/lib\/(arm64-v8a|x86_64)\// && $2 != "Stored" { bad = 1 } END { exit bad }' || ok=0
      done
      if [ $ok -eq 1 ] && [ $checked -gt 0 ]; then
        result play-apks-16kb PASS "zipalign -c -P 16 verifies $checked generated APKs; 64-bit libraries stored uncompressed"
      else
        result play-apks-16kb FAIL "a generated APK is not 16 KB aligned, or none was checked (see 18-zipalign.log)"
      fi
    else
      result play-apks-16kb FAIL "bundletool build-apks failed (see 18-bundletool.log)"
    fi
  else
    result play-apks-16kb FAIL "could not stage a bundle (see 18a-stage.log)"
  fi
else
  result play-apks-16kb SKIP "bundletool not on PATH (the bundle's own 16 KB checks still ran)"
fi

# 19 — no secret in the logs, the reports or the build directories
leaks="$(grep -rlF -e "$TF_APP_SECRET" -e "$TF_UPLOAD_SECRET" -e "$APP_KS" -e "$UPLOAD_KS" -e "$APP_COPY" \
  "$LOGS" "$MOBILE/composeApp/build" "$MOBILE/build" 2>/dev/null)"
if [ -z "$leaks" ]; then
  result no-secrets PASS "no password or keystore path in the logs, reports or build/"
else
  result no-secrets FAIL "secret material found in: ${leaks//$HOME/~}"
fi

# evidence is copied before the cleanup check deletes the logs
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

# 20 — the keys and every test-signed output are deleted
cleanup
left=""
for path in "$TMP" "${SIGNED_OUTPUTS[@]}"; do
  if [ -e "$path" ]; then left="$left ${path#"$REPO"/}"; fi
done
if [ -z "$left" ]; then
  line="$(printf '%-4s  %-15s %s' PASS cleanup "test keys, test-signed outputs and staged files deleted")"
else
  line="$(printf '%-4s  %-15s %s' FAIL cleanup "still present:$left")"
  FAILED=1
fi
echo "$line"
if [ -n "$EVIDENCE" ]; then echo "$line" >>"$EVIDENCE/summary.txt"; fi
echo "play-selftest: $([ $FAILED -eq 0 ] && echo PASS || echo FAIL) — the test keys and test-signed outputs are deleted"
exit $FAILED
