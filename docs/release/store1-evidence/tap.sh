# tap.sh SERIAL TEXT  — taps the centre of the first node whose text or content-desc equals TEXT
S="$1"; T="$2"
adb -s "$S" shell uiautomator dump /data/local/tmp/ui.xml >/dev/null
b=$(adb -s "$S" shell cat /data/local/tmp/ui.xml | tr '>' '\n' | grep -E "(text|content-desc)=\"$T\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+')
[ -n "$b" ] || { echo "not found: $T"; exit 1; }
set -- $b; x=$(( ($1+$3)/2 )); y=$(( ($2+$4)/2 ))
adb -s "$S" shell input tap $x $y; echo "tapped '$T' at $x,$y"; sleep 2
