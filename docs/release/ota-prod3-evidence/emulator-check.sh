#!/usr/bin/env bash
# M5.OTA.PROD.3 emulator check: the OTA OFF release APK, then the OTA ON release APK installed over
# it (an app update), on a dedicated emulator whose DNS is blackholed (`emulator -dns-server
# 127.0.0.1`), so ota.mythweld.com cannot be resolved and no request can reach the live origin.
#
#   emulator-check.sh SERIAL OTA_OFF_APK OTA_ON_APK OUTDIR
#
# Both APKs are release builds signed with one throwaway local key (never the owner's). The script
# refuses to run if the app is already installed or if the emulator can resolve ota.mythweld.com.
set -uo pipefail
S="$1"; OFF="$2"; ON="$3"; OUT="$4"; PKG=io.github.alexeykravtsov95.talentforge
# a real share code of a bundled build (docs/build-sharing.md §3: Warrior, 13 of 51 points, 1.60.1.70374)
CODE=TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1K6WT0ENT1A994JKTJVM40HMNV0TH96601088G40820411008206N3QGF90ABEE1AA
mkdir -p "$OUT"; : > "$OUT/steps.txt"
a() { adb -s "$S" "$@"; }
log() { echo "[$(date -u +%H:%M:%SZ)] $*" | tee -a "$OUT/steps.txt"; }
FAILED=0
fail() { log "FAIL $*"; FAILED=1; }

ui() { a shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; a shell cat /data/local/tmp/ui.xml; }
# taps the first node whose text or content-desc equals $1 (contains it, with a leading "~")
tap() {
    local xy
    xy=$(ui | python3 -I -c '
import sys, re, xml.etree.ElementTree as ET
want = sys.argv[1]
part = want.startswith("~")
want = want.lstrip("~")
for n in ET.fromstring(sys.stdin.read()).iter("node"):
    labels = [n.get("text") or "", n.get("content-desc") or ""]
    if any((want in l) if part else (want == l) for l in labels):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2); break
' "$1")
    [ -n "$xy" ] || { fail "not on screen: $1"; return 1; }
    a shell input tap $xy; log "tapped '$1'"; sleep "${2:-2}"
}
# waits up to $2 s for a text (substring) on screen
expect() {
    local t=0
    until ui | grep -qF -- "$1"; do t=$((t + 2)); [ $t -ge "${2:-20}" ] && { fail "not shown within ${2:-20}s: $1"; return 1; }; sleep 2; done
    log "PASS shown: $1"
}
absent() { if ui | grep -qF -- "$1"; then fail "shown but must not be: $1"; else log "PASS absent: $1"; fi; }
shot() { a shell screencap -p /data/local/tmp/s.png; a pull /data/local/tmp/s.png "$OUT/$1.png" >/dev/null; }
perms() { a shell dumpsys package $PKG | grep -E "android.permission.INTERNET" | sed 's/^ *//' | sort -u | tr '\n' ' '; }

log "device: $(a shell getprop ro.build.fingerprint) (API $(a shell getprop ro.build.version.sdk))"
a shell pm list packages | grep -q "^package:$PKG\$" && { log "the app is already installed - refusing"; exit 2; }
resolved=$(a shell "ping -c1 -W3 ota.mythweld.com 2>&1")
if printf '%s' "$resolved" | grep -q "^PING "; then
    log "ota.mythweld.com resolves here ($resolved) - refusing (no live access allowed)"; exit 2
fi
log "PASS ota.mythweld.com does not resolve on this emulator (DNS blackholed): $(printf '%s' "$resolved" | head -1)"

log "== OTA OFF release APK"
a install "$OFF" > "$OUT/install-off.txt" 2>&1 || { log "install failed"; exit 2; }
p=$(perms); [ -z "$p" ] && log "PASS OTA OFF requests no INTERNET" || fail "OTA OFF requests: $p"
a shell am start -W -n $PKG/.ui.MainActivity > "$OUT/launch-off.txt"; log "launch: $(grep -E 'TotalTime|WaitTime' "$OUT/launch-off.txt" | tr '\n' ' ')"
expect "No builds yet" 30; shot 01-off-home
tap "Import build"; tap "Paste the code here" 1
a shell input text "$CODE"; sleep 1
tap "Continue" 4; expect "Import this build?"; tap "Import" 4
expect "Warrior" 20; shot 02-off-imported
a shell input keyevent KEYCODE_BACK; sleep 2
tap "About" 3; expect "TalentForge works offline"; absent "Check for updates"; shot 03-off-about
a shell input keyevent KEYCODE_BACK; sleep 2

log "== OTA ON release APK installed over it (app update)"
a install -r "$ON" > "$OUT/install-on.txt" 2>&1 || { log "update install failed"; cat "$OUT/install-on.txt"; exit 2; }
p=$(perms); case "$p" in *"android.permission.INTERNET: granted=true"*) log "PASS OTA ON: $p" ;; *) fail "OTA ON INTERNET: $p" ;; esac
a shell am start -W -n $PKG/.ui.MainActivity > "$OUT/launch-on.txt"; log "launch: $(grep -E 'TotalTime|WaitTime' "$OUT/launch-on.txt" | tr '\n' ' ')"
expect "Warrior" 30; log "PASS the imported build survived the update"; shot 04-on-home
tap "About" 3
expect "only talent data updates are downloaded from the internet"; absent "works offline"
expect "Included with the app"; shot 05-on-about
tap "Check for updates" 6
expect "Couldn't check for updates" 30; shot 06-on-check-offline
tap "Show details" 2; expect "ota.mythweld.com"; shot 07-on-privacy-details
a shell input keyevent KEYCODE_BACK; sleep 2
# the editor of the imported build: its specialization tabs (Arms is the Warrior's first) exist only there
tap "Continue editing" 5; expect "Arms" 60; absent "Continue editing"; absent "is not available"; shot 08-on-build-opened
log "app process: $(a shell pidof $PKG || echo none) (alive = no crash)"
a logcat -d -b crash > "$OUT/logcat-crash.txt" 2>&1
if grep -q "$PKG" "$OUT/logcat-crash.txt"; then fail "a crash of $PKG in logcat"; else log "PASS no crash of $PKG in the crash buffer"; fi

a uninstall $PKG >/dev/null 2>&1; log "uninstalled"
if [ $FAILED = 0 ]; then log "RESULT: PASS"; else log "RESULT: FAIL"; fi
exit $FAILED
