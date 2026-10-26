#!/usr/bin/env bash
# Temporary iOS distribution signing for .github/workflows/store-delivery.yml (issue #90).
#
#   ios_delivery_signing.sh install   # keychain + App Store profile, checked; prints outputs
#   ios_delivery_signing.sh cleanup   # always run: deletes the keychain, the profile, the files
#
# install reads, from the environment (GitHub Environment "ios-signing"):
#   IOS_DISTRIBUTION_CERT_P12_BASE64  Apple Distribution certificate + private key (.p12), base64
#   IOS_DISTRIBUTION_CERT_PASSWORD    its export password
#   IOS_APPSTORE_PROFILE_BASE64       the App Store provisioning profile (.mobileprovision), base64
#   APPLE_TEAM_ID                     the 10-character Team ID
# and refuses anything that is not exactly an App Store profile of that team for the frozen
# bundle id with an Apple Distribution identity from the .p12. Files live under
# $RUNNER_TEMP/tf-ios-signing (outside the checkout, mode 0700); no value is printed. The
# keychain gets a random password and is removed by cleanup, which also runs after a failure.
set -euo pipefail

BUNDLE_ID="io.github.alexeykravtsov95.talentforge"
: "${RUNNER_TEMP:?RUNNER_TEMP is not set (GitHub Actions only)}"
DIR="$RUNNER_TEMP/tf-ios-signing"
KEYCHAIN="$DIR/tf-delivery.keychain-db"
PROFILE_DIRS=(
  "$HOME/Library/MobileDevice/Provisioning Profiles"
  "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
)

die() { echo "::error::$*"; exit 1; }

plist_value() { /usr/libexec/PlistBuddy -c "Print :$2" "$1" 2>/dev/null; }

install() {
  local missing=()
  for name in IOS_DISTRIBUTION_CERT_P12_BASE64 IOS_DISTRIBUTION_CERT_PASSWORD \
    IOS_APPSTORE_PROFILE_BASE64 APPLE_TEAM_ID; do
    [ -n "${!name:-}" ] || missing+=("$name")
  done
  [ "${#missing[@]}" -eq 0 ] || die "missing iOS signing configuration: ${missing[*]} (docs/release/ci-delivery.md §3)"
  [[ "$APPLE_TEAM_ID" =~ ^[A-Z0-9]{10}$ ]] || die "APPLE_TEAM_ID is not a 10-character Team ID"

  umask 077
  rm -rf "$DIR"
  mkdir -p "$DIR"
  printf '%s' "$IOS_DISTRIBUTION_CERT_P12_BASE64" | base64 --decode > "$DIR/distribution.p12" \
    || die "IOS_DISTRIBUTION_CERT_P12_BASE64 is not base64"
  printf '%s' "$IOS_APPSTORE_PROFILE_BASE64" | base64 --decode > "$DIR/profile.mobileprovision" \
    || die "IOS_APPSTORE_PROFILE_BASE64 is not base64"

  security cms -D -i "$DIR/profile.mobileprovision" > "$DIR/profile.plist" 2>/dev/null \
    || die "IOS_APPSTORE_PROFILE_BASE64 is not a signed provisioning profile"
  local uuid team app_id devices all_devices task_allow expires
  uuid="$(plist_value "$DIR/profile.plist" UUID)" || die "the profile has no UUID"
  team="$(plist_value "$DIR/profile.plist" TeamIdentifier:0)" || die "the profile has no team"
  app_id="$(plist_value "$DIR/profile.plist" Entitlements:application-identifier)" \
    || die "the profile has no application-identifier"
  devices="$(plist_value "$DIR/profile.plist" ProvisionedDevices || true)"
  all_devices="$(plist_value "$DIR/profile.plist" ProvisionsAllDevices || true)"
  task_allow="$(plist_value "$DIR/profile.plist" Entitlements:get-task-allow || true)"
  expires="$(plist_value "$DIR/profile.plist" ExpirationDate)" || die "the profile has no expiry"
  [[ "$uuid" =~ ^[0-9A-Fa-f-]{36}$ ]] || die "the profile UUID is malformed"
  [ "$team" = "$APPLE_TEAM_ID" ] || die "the profile belongs to another team than APPLE_TEAM_ID"
  [ "$app_id" = "$APPLE_TEAM_ID.$BUNDLE_ID" ] || die "the profile is not for <team>.$BUNDLE_ID"
  [ -z "$devices" ] || die "the profile lists devices (Ad Hoc / development), not an App Store profile"
  [ "$all_devices" != "true" ] || die "the profile is an enterprise profile"
  [ "$task_allow" != "true" ] || die "the profile allows get-task-allow (development profile)"
  echo "App Store profile $uuid for $BUNDLE_ID, expires $expires"

  local keychain_password
  keychain_password="$(openssl rand -hex 24)"
  security create-keychain -p "$keychain_password" "$KEYCHAIN"
  security set-keychain-settings -lut 7200 "$KEYCHAIN"
  security unlock-keychain -p "$keychain_password" "$KEYCHAIN"
  security import "$DIR/distribution.p12" -k "$KEYCHAIN" -f pkcs12 \
    -P "$IOS_DISTRIBUTION_CERT_PASSWORD" -T /usr/bin/codesign -T /usr/bin/security >/dev/null \
    || die "IOS_DISTRIBUTION_CERT_P12_BASE64 / IOS_DISTRIBUTION_CERT_PASSWORD: the .p12 could not be imported"
  security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$keychain_password" \
    "$KEYCHAIN" >/dev/null
  rm -f "$DIR/distribution.p12"
  # search the temporary keychain first, keep the user's keychains after it
  local existing=() line
  while IFS= read -r line; do
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%\"}"
    line="${line#\"}"
    if [ -n "$line" ]; then existing+=("$line"); fi
  done < <(security list-keychains -d user)
  security list-keychains -d user -s "$KEYCHAIN" "${existing[@]}"

  local identities
  identities="$(security find-identity -v -p codesigning "$KEYCHAIN" \
    | grep -c "\"Apple Distribution: .*($APPLE_TEAM_ID)\"" || true)"
  [ "$identities" = "1" ] \
    || die "the .p12 must hold exactly one Apple Distribution identity of APPLE_TEAM_ID (found $identities)"

  for dir in "${PROFILE_DIRS[@]}"; do
    mkdir -p "$dir"
    cp "$DIR/profile.mobileprovision" "$dir/$uuid.mobileprovision"
  done
  printf '%s\n' "$uuid" > "$DIR/profile-uuid"

  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
      echo "keychain=$KEYCHAIN"
      echo "profile_uuid=$uuid"
    } >> "$GITHUB_OUTPUT"
  fi
}

cleanup() {
  if [ -f "$DIR/profile-uuid" ]; then
    local uuid dir
    uuid="$(cat "$DIR/profile-uuid")"
    for dir in "${PROFILE_DIRS[@]}"; do
      rm -f "$dir/$uuid.mobileprovision"
    done
  fi
  if [ -f "$KEYCHAIN" ]; then
    security delete-keychain "$KEYCHAIN" || true
  fi
  rm -rf "$DIR"
  echo "iOS signing material removed"
}

case "${1:-}" in
  install) install ;;
  cleanup) cleanup ;;
  *)
    echo "usage: $0 install|cleanup" >&2
    exit 2
    ;;
esac
