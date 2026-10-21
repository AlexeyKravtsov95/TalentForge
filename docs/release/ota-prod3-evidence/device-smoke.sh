#!/usr/bin/env bash
# M5.OTA.PROD.3: the OTA device smoke (docs/ota/android-device-smoke.md §3, steps 1-10) on a
# physical phone, driven over adb, with a Russian UI. The app under test is the DEBUG build
# `.otastaging` (its own package and data: the installed TalentForge and its saved builds are never
# touched), configured for the local staging stand only (https://localhost:8443 through
# `adb reverse`, the stand's ephemeral tf-staging-local-* key and its own CA). Its production
# channel is disabled, so it cannot reach https://ota.mythweld.com.
#
#   device-smoke.sh SERIAL OTASTAGING_APK STAND_DIR OUTDIR PYTHON
#
# The stand must be served (`ota_staging_stand.py serve STAND_DIR --port 8443`).
set -uo pipefail
S="$1"; APK="$2"; STAND="$3"; OUT="$4"; PY="$5"
PKG=io.github.alexeykravtsov95.talentforge.otastaging
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
CODE=TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1K6WT0ENT1A994JKTJVM40HMNV0TH96601088G40820411008206N3QGF90ABEE1AA
mkdir -p "$OUT"; : > "$OUT/steps.txt"
a() { adb -s "$S" "$@"; }
log() { echo "[$(date -u +%H:%M:%SZ)] $*" | tee -a "$OUT/steps.txt"; }
FAILED=0
fail() { log "FAIL $*"; FAILED=1; }
activate() { PYTHONPATH="$ROOT/src" "$PY" -I "$ROOT/scripts/ota_staging_stand.py" activate "$STAND" "$1" | tail -1 | tee -a "$OUT/steps.txt"; }
state() { a shell run-as $PKG cat no_backup/ota/v1/state.json 2>/dev/null | tr -d '\n' | cut -c1-400; }
library() { a shell run-as $PKG cat shared_prefs/talentforge_build_library.xml 2>/dev/null | shasum -a 256 | cut -c1-16; }

ui() { a shell input keyevent KEYCODE_WAKEUP; a shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; a shell cat /data/local/tmp/ui.xml; }
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
expect() {
    local t=0
    until ui | grep -qF -- "$1"; do t=$((t + 2)); [ $t -ge "${2:-30}" ] && { fail "not shown within ${2:-30}s: $1"; return 1; }; sleep 2; done
    log "PASS shown: $1"
}
absent() { if ui | grep -qF -- "$1"; then fail "shown but must not be: $1"; else log "PASS absent: $1"; fi; }
shot() { a shell screencap -p /data/local/tmp/s.png; a pull /data/local/tmp/s.png "$OUT/$1.png" >/dev/null; }
launch() { a shell am start -W -n $PKG/io.github.alexeykravtsov95.talentforge.ui.MainActivity > "$OUT/launch-$1.txt"; log "launch $1: $(grep -E 'TotalTime' "$OUT/launch-$1.txt" | tr -d '\r')"; }
about() { tap "О приложении" 3; }
back() { a shell input keyevent KEYCODE_BACK; sleep 2; }
check() { tap "Проверить обновления" 2; }
outcome() { expect "$1" "${2:-60}"; }

log "device: $(a shell getprop ro.product.model) / Android $(a shell getprop ro.build.version.release) (API $(a shell getprop ro.build.version.sdk))"
a shell pm list packages | grep -q "^package:$PKG\$" && { log "$PKG is already installed - refusing"; exit 2; }
a reverse tcp:8443 tcp:8443 >/dev/null && log "adb reverse tcp:8443 -> the local stand"

log "== 1. install and launch (stand: 1-a-staged)"
activate 1-a-staged
a install "$APK" > "$OUT/install.txt" 2>&1 || { log "install failed"; cat "$OUT/install.txt"; exit 2; }
launch cold-1
expect "Пока нет билдов"; shot 01-home
about; expect "Сборка игры 1.60.1.70450"; expect "Встроены в приложение"; shot 01-about
log "state: $(state)"

log "== 2. a saved build (imported code: Warrior 13/51, 1.60.1.70374)"
back
tap "Импортировать билд"; tap "Вставьте код сюда" 1; a shell input text "$CODE"; sleep 1
tap "Продолжить" 4; expect "Импортировать этот билд?"; tap "Импортировать" 4
expect "Воин" 30; shot 02-home-build
LIB=$(library); log "library sha256/16: $LIB"
# an import opens the build's editor: back to Home
back; expect "О приложении"

log "== 3. activate 2-b-staged; check"
activate 2-b-staged
about; check
outcome "Данные талантов обновлены до"; expect "Загруженное обновление 1.60.1.70450-r1"; shot 03-updated
log "state: $(state)"

log "== 4. check again"
check; outcome "Данные талантов актуальны."; shot 04-up-to-date

log "== 5. background -> foreground"
a shell input keyevent KEYCODE_HOME; sleep 3; launch warm-5
if [ -n "$(a shell pidof $PKG)" ]; then log "PASS alive after foreground"; else fail "process gone"; fi

log "== 6. process death"
a shell am force-stop $PKG; sleep 1; launch cold-6
about; expect "Загруженное обновление 1.60.1.70450-r1" 30; shot 06-after-process-death

log "== 7. no connection (adb reverse removed: the origin is unreachable)"
a reverse --remove tcp:8443
check; outcome "Не удалось проверить обновления: нет соединения."; expect "Загруженное обновление 1.60.1.70450-r1"; shot 07-offline
a reverse tcp:8443 tcp:8443 >/dev/null

log "== 8. rollback to A (below the bundled floor)"
activate 3-rollback-a
check; outcome "Данные талантов актуальны."; expect "Загруженное обновление 1.60.1.70450-r1"
log "state: $(state)"

log "== 9. minDataLevel 2"
activate 4-level-2
check; outcome "Для новых данных талантов нужно обновить приложение."; shot 09-level-2
log "state: $(state)"

log "== 10. replay of an older catalog"
activate 3-rollback-a
check; outcome "SEQUENCE_REGRESSION"; shot 10-replay
log "state: $(state)"

log "== the saved-build library and the build itself"
if [ "$(library)" = "$LIB" ]; then log "PASS library unchanged by every check ($LIB)"; else fail "library changed: $(library) vs $LIB"; fi
back; tap "Продолжить" 5; expect "Оружие" 60; absent "недоступн"; shot 11-build-opened
a logcat -d -b crash > "$OUT/logcat-crash.txt" 2>&1
if grep -q "$PKG" "$OUT/logcat-crash.txt"; then fail "a crash of $PKG in logcat"; else log "PASS no crash of $PKG in the crash buffer"; fi

a uninstall $PKG >/dev/null 2>&1 && log "uninstalled $PKG"
a reverse --remove tcp:8443 >/dev/null 2>&1
if [ $FAILED = 0 ]; then log "RESULT: PASS"; else log "RESULT: FAIL"; fi
exit $FAILED
