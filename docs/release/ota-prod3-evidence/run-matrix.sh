#!/usr/bin/env bash
# M5.OTA.PROD.3: the Android build matrix (release APK and Google Play bundle, OTA OFF and ON)
# and the negative guard scenarios. Local only: no network access, no signing key, no live origin.
# Every build is UNSIGNED (no TALENTFORGE_RELEASE_* / TALENTFORGE_PLAY_UPLOAD_* settings), so
# nothing it produces is publishable.
#
#   docs/release/ota-prod3-evidence/run-matrix.sh            (from the repository root)
#
# Writes <evidence>/matrix.txt (summary) and <evidence>/logs/*.txt (the tail of each Gradle run).
set -uo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
evidence="$root/docs/release/ota-prod3-evidence"
mobile="$root/mobile"
logs="$evidence/logs"
summary="$evidence/matrix.txt"
aapt2="${ANDROID_HOME:?ANDROID_HOME must name the Android SDK}/build-tools/36.0.0/aapt2"
generated="$mobile/composeApp/build/generated/ota/commonMain/kotlin/io/github/alexeykravtsov95/talentforge/ui/ota/GeneratedOtaSettings.kt"
apk="$mobile/composeApp/build/outputs/apk/release/composeApp-release-unsigned.apk"
aab="$mobile/composeApp/build/outputs/bundle/play/composeApp-play.aab"
on="-Ptalentforge.ota.android.production.enabled=true"
mkdir -p "$logs"
: > "$summary"

# a clean environment: no OTA or signing setting reaches Gradle except the ones each case passes
for var in $(env | sed -n 's/^\(TALENTFORGE_[A-Z0-9_]*\)=.*/\1/p'); do unset "$var"; done
# ... nor from the user's ~/.gradle/gradle.properties (never read): an isolated Gradle user home
# that shares only the dependency caches and the wrapper distributions
shared_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
isolated_home="$(mktemp -d)"
ln -s "$shared_home/caches" "$isolated_home/caches"
ln -s "$shared_home/wrapper" "$isolated_home/wrapper"
export GRADLE_USER_HOME="$isolated_home"
cleanup_home() { (cd "$mobile" && ./gradlew --stop >/dev/null 2>&1); rm -rf "$isolated_home"; }
trap cleanup_home EXIT

say() { printf '%s\n' "$*" | tee -a "$summary"; }

gradle() { # <log name> <gradle args...>; returns the Gradle exit code
    local name="$1"; shift
    (cd "$mobile" && ./gradlew --console=plain "$@") > "$logs/$name.full" 2>&1
    local status=$?
    tail -n 40 "$logs/$name.full" > "$logs/$name.txt"
    rm -f "$logs/$name.full"
    return $status
}

apk_permissions() { "$aapt2" dump permissions "$apk" | sed -n "s/^uses-permission: name='\(.*\)'$/\1/p" | sort | tr '\n' ' '; }

aab_permissions() {
    python3 -I - "$root/scripts" "$aab" <<'PY'
import sys, zipfile
sys.path.insert(0, sys.argv[1])
import verify_play_bundle as v
with zipfile.ZipFile(sys.argv[2]) as z:
    m = v.parse_bundle_manifest(z.read("base/manifest/AndroidManifest.xml"))
print(" ".join(sorted(m.permissions)))
PY
}

# key ids and origins compiled into the binary (dex string pool)
pins() {
    local file="$1"
    unzip -p "$file" '*.dex' 2>/dev/null | LC_ALL=C strings -n 6 \
        | grep -oE 'tf-prod-[0-9a-z]+|tf-staging-[0-9a-z]+|"?test-[a-z0-9-]+|https://[a-z0-9.-]*(mythweld|staging|example|invalid)[a-z0-9./-]*' \
        | sort -u | tr '\n' ' '
}

generated_summary() { grep -E 'enabled =|baseUrl =|OtaKeySetting\(|TrustAnchorPem' "$generated" | sed -E 's/"MF[^"]*"/"<spki>"/; s/^ +//' | tr '\n' ';'; }

case_build() { # <label> <task> <artifact kind> <expect INTERNET yes|no> [gradle args...]
    local label="$1" task="$2" kind="$3" expect="$4"; shift 4
    if ! gradle "$label" "$task" "$@"; then say "FAIL  $label: $task failed (logs/$label.txt)"; return; fi
    local perms artifact
    if [ "$kind" = apk ]; then perms="$(apk_permissions)"; artifact="$apk"; else perms="$(aab_permissions)"; artifact="$aab"; fi
    local has=no; case " $perms " in *" android.permission.INTERNET "*) has=yes ;; esac
    local verdict=PASS; [ "$has" = "$expect" ] || verdict=FAIL
    say "$verdict  $label: INTERNET=$has (expected $expect); permissions: $perms"
    say "      pins in dex: $(pins "$artifact")"
    say "      generated: $(generated_summary)"
}

case_refused() { # <label> <expected message> <gradle args...>
    local label="$1" expected="$2"; shift 2
    if gradle "$label" "$@"; then say "FAIL  $label: the build was NOT refused"; return; fi
    if grep -qF "$expected" "$logs/$label.txt"; then say "PASS  $label: refused (\"$expected\")"; else say "FAIL  $label: failed without \"$expected\" (logs/$label.txt)"; fi
}

say "M5.OTA.PROD.3 build matrix — $(date -u +%Y-%m-%dT%H:%M:%SZ), commit $(git -C "$root" rev-parse --short HEAD)$(git -C "$root" diff --quiet HEAD -- mobile || echo '+uncommitted')"
say ""
say "== self-tests"
if gradle self-tests :composeApp:verifyOtaSettingsRules :composeApp:verifyReleaseInternetRules; then say "PASS  verifyOtaSettingsRules + verifyReleaseInternetRules"; else say "FAIL  self-tests (logs/self-tests.txt)"; fi

say ""
say "== artifacts (unsigned)"
case_build release-ota-off :composeApp:assembleRelease apk no
case_build play-ota-off :composeApp:bundlePlay aab no
case_build release-ota-on :composeApp:assembleRelease apk yes "$on"
case_build play-ota-on :composeApp:bundlePlay aab yes "$on"
case_build release-ota-explicit-off :composeApp:assembleRelease apk no -Ptalentforge.ota.android.production.enabled=false

say ""
say "== refused configurations"
case_refused origin-override "baseUrl is pinned to https://ota.mythweld.com" :composeApp:assembleRelease "$on" -Ptalentforge.ota.android.production.baseUrl=https://ota.example.org
case_refused http-origin "must be an https origin" :composeApp:assembleRelease "$on" -Ptalentforge.ota.android.production.baseUrl=http://ota.mythweld.com
case_refused fixture-keyring "contains a test- key" :composeApp:assembleRelease "$on" "-Ptalentforge.ota.android.production.keyring=$root/docs/ota/fixtures/v1/keyring.json"
case_refused staging-in-release "Release builds never carry staging OTA settings" :composeApp:assembleRelease "$on" -Ptalentforge.ota.android.staging.enabled=true
case_refused staging-in-play "Release builds never carry staging OTA settings" :composeApp:bundlePlay "$on" -Ptalentforge.ota.android.staging.baseUrl=https://staging.example.org
# a setting the rules accept on its own (iOS staging, not enabled): only the release guard refuses it
case_refused ios-staging-in-release "Release builds never carry staging OTA settings; unset talentforge.ota.ios.staging.baseUrl" :composeApp:assembleRelease -Ptalentforge.ota.ios.staging.baseUrl=https://staging.example.org
case_refused not-boolean "enabled must be true or false" :composeApp:assembleRelease -Ptalentforge.ota.android.production.enabled=yes

# the merged-manifest guard on a broken overlay: OTA ON with the OTA overlay temporarily
# removing INTERNET must fail checkReleaseInternetPermission and checkPlayInternetPermission
overlay="$mobile/composeApp/src/androidReleaseOta/AndroidManifest.xml"
cp "$overlay" "$evidence/.overlay.bak"
trap 'mv -f "$evidence/.overlay.bak" "$overlay"; cleanup_home' EXIT
cp "$mobile/composeApp/src/androidRelease/AndroidManifest.xml" "$overlay"
case_refused broken-overlay-release "so the production OTA client could not connect" :composeApp:assembleRelease "$on"
case_refused broken-overlay-play "so the production OTA client could not connect" :composeApp:bundlePlay "$on"
mv -f "$evidence/.overlay.bak" "$overlay"
trap cleanup_home EXIT

# an OTA setting committed to the repository's gradle.properties is refused (temporarily appended)
properties="$mobile/gradle.properties"
cp "$properties" "$evidence/.properties.bak"
trap 'mv -f "$evidence/.properties.bak" "$properties"; cleanup_home' EXIT
printf '\ntalentforge.ota.android.production.enabled=true\n' >> "$properties"
case_refused committed-ota-setting "talentforge.ota.android.production.enabled must not be committed" :composeApp:assembleRelease
mv -f "$evidence/.properties.bak" "$properties"
trap cleanup_home EXIT

say ""
if grep -q '^FAIL' "$summary"; then say "RESULT: FAIL"; exit 1; fi
say "RESULT: PASS"
