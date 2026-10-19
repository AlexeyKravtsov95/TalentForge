#!/usr/bin/env bash
# M5.STORE.1 network-behaviour check of a release APK with OTA OFF on a rooted emulator.
# Usage: netcheck.sh SERIAL APK OUTDIR
# Measures, for the app's UID: iptables/ip6tables owner-match packet counters (every socket
# the app opens), netstats per-UID bytes, live sockets in /proc/net, and a full tcpdump of the
# emulator whose DNS queries are listed. Nothing is uploaded; the emulator is read-only.
set -uo pipefail
S="$1"; APK="$2"; OUT="$3"; PKG=io.github.alexeykravtsov95.talentforge
mkdir -p "$OUT"
a() { adb -s "$S" "$@"; }
log() { echo "[$(date -u +%H:%M:%SZ)] $*" | tee -a "$OUT/steps.txt"; }

a root >/dev/null; sleep 2
log "device: $(a shell getprop ro.build.fingerprint)"
log "network: $(a shell 'ping -c1 -W3 8.8.8.8 >/dev/null 2>&1 && echo online || echo offline')"
a shell pm list packages | grep -q "$PKG" && { log "package already installed - refusing"; exit 2; }
a install "$APK" >"$OUT/install.txt" 2>&1 || { log "install failed"; cat "$OUT/install.txt"; exit 2; }
UID_=$(a shell cmd package list packages -U $PKG | sed -n 's/.*uid:\([0-9][0-9]*\).*/\1/p' | head -1)
[ -n "$UID_" ] || { log "could not resolve the app uid"; exit 2; }
log "installed; uid=$UID_"
a shell dumpsys package $PKG | grep -E "versionCode|versionName|pkgFlags|flags=\[|requested permissions|install permissions|android.permission" > "$OUT/package.txt"

for t in iptables ip6tables; do
  a shell "$t -I OUTPUT 1 -m owner --uid-owner $UID_ -j RETURN"
done
a shell 'rm -f /data/local/tmp/tf.pcap'
a shell 'nohup tcpdump -i any -n -s 0 -w /data/local/tmp/tf.pcap >/dev/null 2>&1 &'
sleep 2
a shell dumpsys netstats --poll >/dev/null 2>&1 || true
a shell dumpsys netstats detail | grep -A3 "uid=$UID_ " > "$OUT/netstats-before.txt" || true

sockets() { a shell "cat /proc/net/tcp /proc/net/tcp6 /proc/net/udp /proc/net/udp6" | awk -v u="$UID_" '$8==u' ; }

log "launch (cold)"
a shell am start -W -n $PKG/.ui.MainActivity > "$OUT/launch1.txt"; sleep 8
sockets >> "$OUT/sockets.txt"
a shell screencap -p /data/local/tmp/s1.png; a pull /data/local/tmp/s1.png "$OUT/01-launch.png" >/dev/null
log "monkey 1500 events, package-restricted, seed 4242"
a shell monkey -p $PKG -s 4242 --throttle 120 --pct-syskeys 0 --pct-appswitch 0 -v 1500 > "$OUT/monkey1.txt" 2>&1
sockets >> "$OUT/sockets.txt"
log "background 10 s, foreground"
a shell input keyevent KEYCODE_HOME; sleep 10
a shell am start -n $PKG/.ui.MainActivity >/dev/null; sleep 6
log "force-stop, relaunch, monkey 1500 seed 777"
a shell am force-stop $PKG; sleep 1
a shell am start -W -n $PKG/.ui.MainActivity > "$OUT/launch2.txt"; sleep 6
a shell monkey -p $PKG -s 777 --throttle 120 --pct-syskeys 0 --pct-appswitch 0 -v 1500 > "$OUT/monkey2.txt" 2>&1
sockets >> "$OUT/sockets.txt"
a shell screencap -p /data/local/tmp/s2.png; a pull /data/local/tmp/s2.png "$OUT/02-after-monkey.png" >/dev/null
log "idle 30 s with the app in front"
a shell am start -n $PKG/.ui.MainActivity >/dev/null; sleep 30
sockets >> "$OUT/sockets.txt"

log "positive control: the same owner-match counter for uid 2000 (shell) while it pings 3 times"
a shell "iptables -I OUTPUT 1 -m owner --uid-owner 2000 -j RETURN"
a shell "su 2000 ping -c 3 -W 3 8.8.8.8" > "$OUT/control-ping.txt" 2>&1
a shell "iptables -L OUTPUT -v -n -x" | grep "owner UID match 2000" > "$OUT/control-counter.txt"
a shell "iptables -D OUTPUT -m owner --uid-owner 2000 -j RETURN"

a shell 'pkill -INT tcpdump'; sleep 2
a shell dumpsys netstats --poll >/dev/null 2>&1 || true
a shell dumpsys netstats detail | grep -A3 "uid=$UID_ " > "$OUT/netstats-after.txt" || true
for t in iptables ip6tables; do
  a shell "$t -L OUTPUT -v -n -x" | grep -E "Chain|pkts|owner" > "$OUT/$t-output.txt"
done
a pull /data/local/tmp/tf.pcap "$OUT/emulator-all.pcap" >/dev/null
a shell "pidof $PKG" > "$OUT/pid.txt" || true
log "done; socket rows owned by the app: $(wc -l < "$OUT/sockets.txt" | tr -d ' ')"
