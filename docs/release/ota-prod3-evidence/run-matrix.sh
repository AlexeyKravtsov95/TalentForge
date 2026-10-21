#!/usr/bin/env bash
# M5.OTA.PROD.3: the Android build matrix (release APK and Google Play bundle, OTA OFF and ON)
# and the negative guard scenarios. Local only: no network access, no signing key, no live origin.
# Every build is UNSIGNED (no TALENTFORGE_RELEASE_* / TALENTFORGE_PLAY_UPLOAD_* settings), so
# nothing it produces is publishable.
#
#   docs/release/ota-prod3-evidence/run-matrix.sh            (from the repository root)
#
# Everything is built in a temporary `git worktree` of HEAD, which the negative cases modify and
# which is removed on exit: the checkout the script is run from is never written to, except the
# evidence (<evidence>/matrix.txt and <evidence>/logs/*.txt, the tail of each Gradle run). The
# script refuses to run while mobile/, scripts/ or docs/ota/keys/ has local changes, so the
# results always describe the committed HEAD they name.
#
# Every artifact is checked by check_ota_artifact.py (dex string table, GeneratedOtaSettings, the
# committed keyring against the pinned SPKI SHA-256). Any FAIL line makes the exit code 1.
set -uo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
evidence="$root/docs/release/ota-prod3-evidence"
logs="$evidence/logs"
summary="$evidence/matrix.txt"
checker="$evidence/check_ota_artifact.py"   # committed (guarded below), so it is HEAD's checker
aapt2="${ANDROID_HOME:?ANDROID_HOME must name the Android SDK}/build-tools/36.0.0/aapt2"
origin="https://ota.mythweld.com"
# the pinned production keys: SHA-256 of each DER SPKI (docs/ota/production-deployment.md §4.4)
fingerprints=(
    --fingerprint tf-prod-2026a=1fac1b0d15f2a09fc3769099623fe1038e846f0033ec6e233f5111ff71239278
    --fingerprint tf-prod-2026b=6997b3d47d74ceeb6daec62b1d94f3f3e3bc476534eefe8cd140f67578532b26
)
on="-Ptalentforge.ota.android.production.enabled=true"

guarded=(mobile scripts docs/ota/keys docs/release/ota-prod3-evidence/run-matrix.sh docs/release/ota-prod3-evidence/check_ota_artifact.py)
if [ -n "$(git -C "$root" status --porcelain -- "${guarded[@]}")" ]; then
    echo "refusing: local changes in ${guarded[*]}; the matrix builds and checks the committed HEAD" >&2
    git -C "$root" status --short -- "${guarded[@]}" >&2
    exit 2
fi
head="$(git -C "$root" rev-parse --short HEAD)"

# a clean environment: no OTA or signing setting reaches Gradle except the ones each case passes
for var in $(env | sed -n 's/^\(TALENTFORGE_[A-Z0-9_]*\)=.*/\1/p'); do unset "$var"; done
scratch="$(mktemp -d)"
wt="$scratch/wt"
# ... nor from the user's ~/.gradle/gradle.properties (never read): an isolated Gradle user home
# that shares only the dependency caches and the wrapper distributions
shared_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
mkdir -p "$scratch/gradle-home"
ln -s "$shared_home/caches" "$scratch/gradle-home/caches"
ln -s "$shared_home/wrapper" "$scratch/gradle-home/wrapper"
export GRADLE_USER_HOME="$scratch/gradle-home"
cleanup() {
    [ -d "$wt/mobile" ] && (cd "$wt/mobile" && ./gradlew --stop >/dev/null 2>&1)
    git -C "$root" worktree remove --force "$wt" >/dev/null 2>&1
    rm -rf "$scratch"
}
trap cleanup EXIT
git -C "$root" worktree add --detach --quiet "$wt" HEAD || { echo "could not create the temporary worktree" >&2; exit 2; }
mobile="$wt/mobile"
generated="$mobile/composeApp/build/generated/ota/commonMain/kotlin/io/github/alexeykravtsov95/talentforge/ui/ota/GeneratedOtaSettings.kt"
apk="$mobile/composeApp/build/outputs/apk/release/composeApp-release-unsigned.apk"
aab="$mobile/composeApp/build/outputs/bundle/play/composeApp-play.aab"
kept="$scratch/artifacts"
mkdir -p "$logs" "$kept"
rm -f "$logs"/*.txt
: > "$summary"

say() { printf '%s\n' "$*" | tee -a "$summary"; }
fail() { say "FAIL  $*"; }

# local paths never reach the evidence
sanitize() { sed -e "s#$wt#<worktree>#g" -e "s#$scratch#<scratch>#g" -e "s#$root#<repo>#g" -e "s#$HOME#~#g"; }

gradle() { # <log name> <gradle args...>; returns the Gradle exit code
    local name="$1"; shift
    (cd "$mobile" && ./gradlew --console=plain "$@") > "$scratch/$name.full" 2>&1
    local status=$?
    tail -n 40 "$scratch/$name.full" | sanitize > "$logs/$name.txt"
    return $status
}

# restores a file the previous case modified (inside the temporary worktree only)
restore() { git -C "$wt" checkout --quiet -- "$@" && [ -z "$(git -C "$wt" status --porcelain)" ] || { fail "could not restore the temporary worktree"; exit 1; }; }

apk_permissions() { "$aapt2" dump permissions "$1" | sed -n "s/^uses-permission: name='\(.*\)'$/\1/p" | sort | tr '\n' ' '; }

aab_permissions() {
    python3 -I - "$wt/scripts" "$1" <<'PY'
import sys, zipfile
sys.path.insert(0, sys.argv[1])
import verify_play_bundle as v
with zipfile.ZipFile(sys.argv[2]) as z:
    m = v.parse_bundle_manifest(z.read("base/manifest/AndroidManifest.xml"))
print(" ".join(sorted(m.permissions)))
PY
}

check_artifact() { # <mode on|off> <artifact> <generated> [keyring]; prints the checker's lines, returns its exit code
    python3 -I "$checker" --mode "$1" --artifact "$2" --generated "$3" \
        --keyring "${4:-$wt/docs/ota/keys/production-keyring.json}" --origin "$origin" "${fingerprints[@]}"
}

case_build() { # <label> <task> <apk|aab> <on|off> [gradle args...]
    local label="$1" task="$2" kind="$3" mode="$4"; shift 4
    if ! gradle "$label" "$task" "$@"; then fail "$label: $task failed (logs/$label.txt)"; return; fi
    local artifact perms
    if [ "$kind" = apk ]; then artifact="$apk"; perms="$(apk_permissions "$artifact")"; else artifact="$aab"; perms="$(aab_permissions "$artifact")"; fi
    cp "$artifact" "$kept/$label.$kind"; cp "$generated" "$kept/$label.kt"
    local has=no expect=no; case " $perms " in *" android.permission.INTERNET "*) has=yes ;; esac
    [ "$mode" = on ] && expect=yes
    if [ "$has" = "$expect" ]; then say "PASS  $label: INTERNET=$has; permissions: $perms"; else fail "$label: INTERNET=$has, expected $expect; permissions: $perms"; fi
    local out status
    out="$(check_artifact "$mode" "$kept/$label.$kind" "$kept/$label.kt")"; status=$?
    printf '%s\n' "$out" > "$logs/$label-pins.txt"
    if [ $status = 0 ]; then
        say "PASS  $label: pins ($(grep -c '^PASS' <<<"$out") assertions: dex string table, GeneratedOtaSettings, keyring SPKI SHA-256)"
    else
        fail "$label: pins (exit $status)"; grep '^FAIL' <<<"$out" | sed 's/^/        /' | tee -a "$summary"
    fi
}

case_refused() { # <label> <expected message> <gradle args...>
    local label="$1" expected="$2"; shift 2
    if gradle "$label" "$@"; then fail "$label: the build was NOT refused"; return; fi
    if grep -qF "$expected" "$logs/$label.txt"; then say "PASS  $label: refused (\"$expected\")"; else fail "$label: failed without \"$expected\" (logs/$label.txt)"; fi
}

checker_bites() { # <label> <checker args...>: the checker must FAIL on this input
    local label="$1"; shift
    local out status
    out="$(check_artifact "$@")"; status=$?
    printf '%s\n' "$out" > "$logs/checker-$label.txt"
    if [ $status = 1 ] && grep -q '^FAIL' <<<"$out"; then
        say "PASS  checker-$label: refused ($(grep -c '^FAIL' <<<"$out") FAIL: $(grep -m1 '^FAIL' <<<"$out" | cut -c6-90)…)"
    else
        fail "checker-$label: the checker accepted it (exit $status)"
    fi
}

say "M5.OTA.PROD.3 build matrix — $(date -u +%Y-%m-%dT%H:%M:%SZ), commit $head (built in a temporary worktree of it)"
say ""
say "== self-tests"
if gradle self-tests :composeApp:verifyOtaSettingsRules :composeApp:verifyReleaseInternetRules; then say "PASS  verifyOtaSettingsRules + verifyReleaseInternetRules"; else fail "self-tests (logs/self-tests.txt)"; fi

say ""
say "== artifacts (unsigned), each checked by check_ota_artifact.py"
case_build release-ota-off :composeApp:assembleRelease apk off
case_build play-ota-off :composeApp:bundlePlay aab off
case_build release-ota-on :composeApp:assembleRelease apk on "$on"
case_build play-ota-on :composeApp:bundlePlay aab on "$on"
case_build release-ota-explicit-off :composeApp:assembleRelease apk off -Ptalentforge.ota.android.production.enabled=false

say ""
say "== the checker refuses what it must (it is not a rubber stamp)"
if [ -f "$kept/release-ota-on.apk" ] && [ -f "$kept/release-ota-off.apk" ]; then
    checker_bites off-artifact-as-on on "$kept/release-ota-off.apk" "$kept/release-ota-off.kt"
    checker_bites on-artifact-as-off off "$kept/release-ota-on.apk" "$kept/release-ota-on.kt"
    python3 -I -c 'import sys; t = open(sys.argv[1]).read(); k = "OtaKeySetting(\"tf-prod-2026b\""; open(sys.argv[2], "w").write(t.replace(k, "OtaKeySetting(\"tf-staging-x\", \"ecdsa-p256-sha256\", \"S\"),\n            " + k, 1))' "$kept/release-ota-on.kt" "$kept/staging-key-added.kt"
    checker_bites staging-key-in-generated on "$kept/release-ota-on.apk" "$kept/staging-key-added.kt"
    # the same key as the LAST entry of the list, and the backup key removed: a parser that reads
    # only part of the list would miss both
    python3 -I -c 'import sys; lines = open(sys.argv[1]).read().split("\n"); i = next(n for n, l in enumerate(lines) if "OtaKeySetting(\"tf-prod-2026b\"" in l); lines.insert(i + 1, lines[i].replace("tf-prod-2026b", "tf-staging-x")); open(sys.argv[2], "w").write("\n".join(lines))' "$kept/release-ota-on.kt" "$kept/staging-key-last.kt"
    checker_bites staging-key-last-in-generated on "$kept/release-ota-on.apk" "$kept/staging-key-last.kt"
    grep -v 'OtaKeySetting("tf-prod-2026b"' "$kept/release-ota-on.kt" > "$kept/backup-key-removed.kt"
    checker_bites backup-key-removed-in-generated on "$kept/release-ota-on.apk" "$kept/backup-key-removed.kt"
    sed 's#baseUrl = "https://ota.mythweld.com"#baseUrl = "https://ota.example.org"#' "$kept/release-ota-on.kt" > "$kept/other-origin.kt"
    checker_bites other-origin-in-generated on "$kept/release-ota-on.apk" "$kept/other-origin.kt"
    python3 -I - "$wt/docs/ota/keys/production-keyring.json" "$kept/tampered-keyring.json" <<'PY'
import json, sys
k = json.load(open(sys.argv[1])); k["keys"][1]["publicKeySpki"] = k["keys"][0]["publicKeySpki"]
json.dump(k, open(sys.argv[2], "w"))
PY
    checker_bites tampered-keyring on "$kept/release-ota-on.apk" "$kept/release-ota-on.kt" "$kept/tampered-keyring.json"
else
    fail "checker self-tests: the OTA ON/OFF APKs were not built"
fi

say ""
say "== refused configurations (built in the temporary worktree)"
case_refused origin-override "baseUrl is pinned to https://ota.mythweld.com" :composeApp:assembleRelease "$on" -Ptalentforge.ota.android.production.baseUrl=https://ota.example.org
case_refused http-origin "must be an https origin" :composeApp:assembleRelease "$on" -Ptalentforge.ota.android.production.baseUrl=http://ota.mythweld.com
case_refused fixture-keyring "contains a test- key" :composeApp:assembleRelease "$on" "-Ptalentforge.ota.android.production.keyring=$wt/docs/ota/fixtures/v1/keyring.json"
case_refused staging-in-release "Release builds never carry staging OTA settings" :composeApp:assembleRelease "$on" -Ptalentforge.ota.android.staging.enabled=true
case_refused staging-in-play "Release builds never carry staging OTA settings" :composeApp:bundlePlay "$on" -Ptalentforge.ota.android.staging.baseUrl=https://staging.example.org
# a setting the rules accept on its own (iOS staging, not enabled): only the release guard refuses it
case_refused ios-staging-in-release "Release builds never carry staging OTA settings; unset talentforge.ota.ios.staging.baseUrl" :composeApp:assembleRelease -Ptalentforge.ota.ios.staging.baseUrl=https://staging.example.org
case_refused not-boolean "enabled must be true or false" :composeApp:assembleRelease -Ptalentforge.ota.android.production.enabled=yes

# the merged-manifest guard on a broken overlay: OTA ON with the OTA overlay removing INTERNET
overlay="mobile/composeApp/src/androidReleaseOta/AndroidManifest.xml"
cp "$wt/mobile/composeApp/src/androidRelease/AndroidManifest.xml" "$wt/$overlay"
case_refused broken-overlay-release "so the production OTA client could not connect" :composeApp:assembleRelease "$on"
case_refused broken-overlay-play "so the production OTA client could not connect" :composeApp:bundlePlay "$on"
restore "$overlay"

# an OTA setting committed to the repository's gradle.properties
printf '\ntalentforge.ota.android.production.enabled=true\n' >> "$wt/mobile/gradle.properties"
case_refused committed-ota-setting "talentforge.ota.android.production.enabled must not be committed" :composeApp:assembleRelease
restore mobile/gradle.properties

# the committed public keyring changed (the backup key replaced by the primary's SPKI): every
# build stops, with OTA off as well as on
python3 -I - "$wt/docs/ota/keys/production-keyring.json" <<'PY'
import json, sys
k = json.load(open(sys.argv[1])); k["keys"][1]["publicKeySpki"] = k["keys"][0]["publicKeySpki"]
json.dump(k, open(sys.argv[1], "w"), indent=2)
PY
case_refused tampered-keyring-ota-off "the committed production keyring changed: tf-prod-2026b has SPKI SHA-256" :composeApp:assembleRelease
case_refused tampered-keyring-ota-on "the committed production keyring changed: tf-prod-2026b has SPKI SHA-256" :composeApp:assembleRelease "$on"
restore docs/ota/keys/production-keyring.json

# a pinned fingerprint changed in the build script: the rule self-test catches it
sed -i.bak 's/1fac1b0d15f2a09fc3769099623fe1038e846f0033ec6e233f5111ff71239278/0000000000000000000000000000000000000000000000000000000000000000/' "$wt/mobile/composeApp/build.gradle.kts" && rm -f "$wt/mobile/composeApp/build.gradle.kts.bak"
case_refused pinned-fingerprint-changed "the committed production keyring was refused" :composeApp:verifyOtaSettingsRules
restore mobile/composeApp/build.gradle.kts

say ""
if grep -q '^FAIL' "$summary"; then say "RESULT: FAIL"; exit 1; fi
say "RESULT: PASS"
