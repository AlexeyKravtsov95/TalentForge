#!/usr/bin/env bash
# M5.STORE.1: build the publishable APK through the real :composeApp:assemblePublishableRelease
# path with a TEMPORARY test key (NOT a production key), for the emulator network check only.
# The key directory is outside every checkout and is deleted on exit; the password exists only
# in this process's environment. The staged APK is copied to OUT (scratch) and must never be
# uploaded anywhere.
# Usage: publishable-tempkey.sh CHECKOUT OUT
set -uo pipefail
CHECKOUT="$1"; OUT="$2"
KEYDIR="$(mktemp -d "${TMPDIR:-/tmp}/tf-store1-tempkey.XXXXXX")"
cleanup() { rm -rf "$KEYDIR"; rm -rf "$CHECKOUT/mobile/composeApp/build/outputs/apk/release" "$CHECKOUT/mobile/composeApp/build/outputs/publishable"; }
trap cleanup EXIT INT TERM
mkdir -p "$OUT"
export TF_TMP_PASS="$(openssl rand -hex 24)"
keytool -genkeypair -keystore "$KEYDIR/temp.jks" -storetype PKCS12 -alias store1-temp \
  -keyalg RSA -keysize 4096 -validity 2 -dname "CN=TalentForge STORE1 temporary self-test key, O=NOT A PRODUCTION KEY" \
  -storepass:env TF_TMP_PASS -keypass:env TF_TMP_PASS >/dev/null 2>&1 || { echo "keytool failed"; exit 2; }
SHA=$(keytool -list -v -keystore "$KEYDIR/temp.jks" -storepass:env TF_TMP_PASS -alias store1-temp 2>/dev/null \
  | sed -n 's/^[[:space:]]*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f')
echo "temporary certificate SHA-256: $SHA" | tee "$OUT/temp-cert.txt"
export TALENTFORGE_RELEASE_STORE_FILE="$KEYDIR/temp.jks"
export TALENTFORGE_RELEASE_STORE_PASSWORD="$TF_TMP_PASS"
export TALENTFORGE_RELEASE_KEY_ALIAS=store1-temp
export TALENTFORGE_RELEASE_KEY_PASSWORD="$TF_TMP_PASS"
export TALENTFORGE_RELEASE_CERT_SHA256="$SHA"
( cd "$CHECKOUT/mobile" && ./gradlew --console=plain --no-build-cache --no-configuration-cache \
    :composeApp:assemblePublishableRelease ) > "$OUT/assemblePublishableRelease.txt" 2>&1
rc=$?
echo "assemblePublishableRelease exit=$rc" | tee -a "$OUT/temp-cert.txt"
[ $rc -eq 0 ] || exit 1
cp "$CHECKOUT"/mobile/composeApp/build/outputs/publishable/* "$OUT/"
gen="$CHECKOUT/mobile/composeApp/build/generated/ota/commonMain/kotlin/io/github/alexeykravtsov95/talentforge/ui/ota/GeneratedOtaSettings.kt"
cp "$gen" "$OUT/GeneratedOtaSettings.kt"
# secret scan: neither the password nor the key path may appear in the log, report or build tree
if grep -rqF "$TF_TMP_PASS" "$OUT" "$CHECKOUT/mobile/composeApp/build" 2>/dev/null || grep -rqF "$KEYDIR" "$OUT" 2>/dev/null; then
  echo "SECRET LEAK DETECTED" | tee -a "$OUT/temp-cert.txt"; exit 1
fi
echo "secret scan: clean" | tee -a "$OUT/temp-cert.txt"
