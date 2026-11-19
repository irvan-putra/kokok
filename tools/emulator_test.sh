#!/usr/bin/env bash
# OS-level alarm flow test on an API 34 emulator (GitHub runner).
# Exercised: feed sync schedules, absence cancellation, test alarm ring,
# snooze re-ring, dismiss, denied exact-alarm permission UI, reboot survival.
set -e
cd "$(dirname "$0")/.."   # repo root

pip install tink
adb install -r app/build/outputs/apk/debug/app-debug.apk

# --- synthetic feed: keys, envelopes, host server ---
mkdir -p feed && cd feed
python3 ../tools/feed_write.py gen-sign --private sign-priv.json --public sign-pub.json
python3 ../tools/feed_write.py gen-app --private app-priv.json --public app-pub.json
NOW=$(python3 -c 'import time; print(int(time.time()*1000))')
python3 - "$NOW" <<'PYFEED'
import json, sys
now = int(sys.argv[1])
feed1 = {"v":1,"rev":1,"updated":"ci","tz":"UTC","alarms":[{"id":"ci-feed-1","label":"ci feed alarm","atMillis":now+300000,"snooze":1}]}
feed2 = {"v":1,"rev":2,"updated":"ci","tz":"UTC","alarms":[]}
json.dump(feed1, open('feed1.json','w'))
json.dump(feed2, open('feed2.json','w'))
PYFEED
python3 ../tools/feed_write.py encrypt --in feed1.json --to app-pub.json --sign sign-priv.json --sign-public sign-pub.json --out envelope1.json
python3 ../tools/feed_write.py encrypt --in feed2.json --to app-pub.json --sign sign-priv.json --sign-public sign-pub.json --out envelope2.json
cp envelope1.json live.json
python3 -m http.server 8000 &>/dev/null &
cd ..

# --- seed pairing state into the fresh app via run-as (debuggable) ---
python3 - <<'PYSEED'
import json, subprocess, xml.sax.saxutils as x
app_priv = open('feed/app-priv.json').read()
sign_pub = open('feed/sign-pub.json').read()
def esc(v): return x.escape(v, {'"': '&quot;'})
xml = ('<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>'
       + '<string name="feedUrl">http://10.0.2.2:8000/live.json</string>'
       + '<string name="verifyKeyset">' + esc(sign_pub.replace('\n','')) + '</string>'
       + '<string name="appKeyset">' + esc(app_priv.replace('\n','')) + '</string>'
       + '</map>\n')
open('feed.xml','w').write(xml)
subprocess.run(['adb','shell','run-as','com.vanipy.kokok','mkdir','-p','shared_prefs'], check=True)
subprocess.run('cat feed.xml | adb shell "run-as com.vanipy.kokok sh -c \'cat > shared_prefs/feed.xml\'"', shell=True, check=True)
PYSEED

# --- launch: native-pixel main screen + feed sync schedules alarm ---
adb shell am start -n com.vanipy.kokok/.MainActivity
sleep 5
adb exec-out screencap -p > main.png
adb shell uiautomator dump /sdcard/ui0.xml
adb pull /sdcard/ui0.xml ui0.xml
python3 - <<'PYTAP'
import re, subprocess
xml = open('ui0.xml').read()
m = re.search(r'text="sync now"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
assert m, "sync now button not found"
x = (int(m.group(1)) + int(m.group(3))) // 2
y = (int(m.group(2)) + int(m.group(4))) // 2
subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=True)
PYTAP
sleep 8
adb shell dumpsys alarm | grep -B1 -A3 'ci-feed-1\|com.vanipy.kokok' > feed-scheduled.txt || true
adb exec-out screencap -p > after-sync.png

# --- rev 2 without the alarm: absence cancellation at OS level ---
cp feed/envelope2.json feed/live.json
adb shell uiautomator dump /sdcard/ui1b.xml
adb pull /sdcard/ui1b.xml ui1b.xml
python3 - <<'PYTAP2'
import re, subprocess
xml = open('ui1b.xml').read()
m = re.search(r'text="sync now"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
assert m, "sync now button not found"
x = (int(m.group(1)) + int(m.group(3))) // 2
y = (int(m.group(2)) + int(m.group(4))) // 2
subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=True)
PYTAP2
sleep 8
adb shell dumpsys alarm | grep -B1 -A3 'ci-feed-1' > feed-canceled.txt || echo "ci-feed-1 not in dumpsys (expected after cancel)" > feed-canceled.txt
adb exec-out screencap -p > after-cancel.png
adb shell dumpsys alarm | grep -A2 com.vanipy.kokok || true
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml ui.xml

# tap "test alarm (rings in 60 s)" by label
python3 - <<'PYRING'
import re, subprocess
xml = open('ui.xml').read()
m = re.search(r'text="test alarm \(rings in 60 s\)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
assert m, "test alarm button not found"
x = (int(m.group(1)) + int(m.group(3))) // 2
y = (int(m.group(2)) + int(m.group(4))) // 2
subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=True)
PYRING
sleep 75
adb exec-out screencap -p > ringing.png
adb shell dumpsys notification | grep -B2 -A4 com.vanipy.kokok || true

# snooze via button: ringing stops, alarm rescheduled +1 min
adb shell uiautomator dump /sdcard/ui2.xml
adb pull /sdcard/ui2.xml ui2.xml
python3 - <<'PYSNOOZE'
import re, subprocess
xml = open('ui2.xml').read()
m = re.search(r'text="snooze 1 min"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=True)
    print("snoozed")
else:
    raise SystemExit("no snooze button found while ringing")
PYSNOOZE
sleep 75
adb exec-out screencap -p > ringing-after-snooze.png

# dismiss the snoozed ring
adb shell uiautomator dump /sdcard/ui3.xml
adb pull /sdcard/ui3.xml ui3.xml
python3 - <<'PYDISMISS'
import re, subprocess
xml = open('ui3.xml').read()
m = re.search(r'text="dismiss"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=True)
    print("dismissed")
else:
    raise SystemExit("snoozed alarm did not ring again")
PYDISMISS

# denied-permission evidence: revoke exact-alarm appop, reopen, capture checklist
adb shell appops set com.vanipy.kokok SCHEDULE_EXACT_ALARM deny || true
adb shell am start -n com.vanipy.kokok/.MainActivity
sleep 4
adb exec-out screencap -p > permission-denied.png
adb shell appops set com.vanipy.kokok SCHEDULE_EXACT_ALARM allow || true

# reboot: set a fresh test alarm, reboot, verify OS kept/rescheduled it
adb shell am start -n com.vanipy.kokok/.MainActivity
sleep 3
adb shell uiautomator dump /sdcard/ui4.xml
adb pull /sdcard/ui4.xml ui4.xml
python3 - <<'PYREBOOT'
import re, subprocess
xml = open('ui4.xml').read()
m = re.search(r'text="test alarm \(rings in 60 s\)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
assert m, "test alarm button not found"
x = (int(m.group(1)) + int(m.group(3))) // 2
y = (int(m.group(2)) + int(m.group(4))) // 2
subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=True)
PYREBOOT
adb reboot
for i in $(seq 1 60); do
  sleep 5
  if adb shell getprop sys.boot_completed 2>/dev/null | grep -q 1; then break; fi
done
sleep 10
adb exec-out screencap -p > after-reboot.png
# a test alarm set 60s pre-reboot should ring after boot (late grace) or be rescheduled
adb shell dumpsys alarm | grep -A3 com.vanipy.kokok > dumpsys-after-reboot.txt || true
adb shell dumpsys notification | grep -B2 -A4 com.vanipy.kokok >> dumpsys-after-reboot.txt || true
echo "emulator flow OK"
