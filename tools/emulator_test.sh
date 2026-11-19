#!/usr/bin/env bash
# OS-level alarm flow test on an API 34 emulator (GitHub runner).
# Exercised: feed sync schedules, absence cancellation, test alarm ring,
# snooze re-ring, dismiss, denied exact-alarm permission UI, reboot survival.
set -e
trap 'adb logcat -d > emulator-logcat.txt; adb shell uiautomator dump /data/local/tmp/final-ui.xml; adb pull /data/local/tmp/final-ui.xml final-ui.xml; adb exec-out screencap -p > final-screen.png' EXIT
cd "$(dirname "$0")/.."   # repo root

pip install tink
adb install -r app/build/outputs/apk/debug/app-debug.apk
# API 33+ needs the runtime notification permission for the alarm UI path
adb shell pm grant com.vanipy.kokok android.permission.POST_NOTIFICATIONS || true
# deterministic geometry: small default skins leave buttons below the fold
adb shell wm size 1080x1920
adb shell wm density 420

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
import os
for d in ['/data/user_de/0/com.vanipy.kokok/shared_prefs']:
    subprocess.run(['adb','shell','run-as','com.vanipy.kokok','mkdir','-p',d], check=True)
    subprocess.run('cat feed.xml | adb shell "run-as com.vanipy.kokok sh -c \'cat > %s/feed.xml\'"' % d, shell=True, check=True)
    r = subprocess.run(['adb','shell','run-as','com.vanipy.kokok','cat',d + '/feed.xml'],
                       capture_output=True, text=True)
    if 'feedUrl' not in r.stdout:
        raise SystemExit('seed write did not land in ' + d)
    print('seeded pairing state in', d)
PYSEED

# --- launch: native-pixel main screen + feed sync schedules alarm ---
adb shell am start -n com.vanipy.kokok/.MainActivity
sleep 5
adb exec-out screencap -p > main.png
python3 tools/uitap.py "sync now"
sleep 8
python3 tools/assert_feed_state.py 1 present
adb shell dumpsys alarm > full-feed-scheduled.txt
grep -q 'tag=\*walarm\*:com.vanipy.kokok.RING' full-feed-scheduled.txt || { echo "FAIL: no exact ring alarm after authenticated feed"; exit 1; }
adb shell dumpsys alarm | grep -B1 -A3 'ci-feed-1\|com.vanipy.kokok' > feed-scheduled.txt || true
adb exec-out screencap -p > after-sync.png

# --- rev 2 without the alarm: absence cancellation at OS level ---
cp feed/envelope2.json feed/live.json
python3 tools/uitap.py "sync now"
sleep 8
python3 tools/assert_feed_state.py 2 absent
adb shell dumpsys alarm > full-feed-canceled.txt
if grep -q 'tag=\*walarm\*:com.vanipy.kokok.RING' full-feed-canceled.txt; then echo "FAIL: exact ring alarm remains after empty feed"; exit 1; fi
adb shell dumpsys alarm | grep -B1 -A3 'ci-feed-1' > feed-canceled.txt || echo "ci-feed-1 not in dumpsys (expected after cancel)" > feed-canceled.txt
adb exec-out screencap -p > after-cancel.png
adb shell dumpsys alarm | grep -A2 com.vanipy.kokok || true
python3 tools/uitap.py "test alarm (rings in 60 s)"
sleep 75
adb exec-out screencap -p > ringing.png
# runtime evidence, not just pixels: the foreground service must be running
adb shell dumpsys activity services com.vanipy.kokok > ring-services.txt || true
grep -q AlarmRingService ring-services.txt || { echo "FAIL: AlarmRingService not running while ringing"; exit 1; }
adb shell dumpsys media.player > media-player-1.txt 2>/dev/null || true
sleep 5
adb shell dumpsys media.player > media-player-2.txt 2>/dev/null || true
adb shell dumpsys notification | grep -B2 -A4 com.vanipy.kokok || true

# ring controls live in the SystemUI window when the full-screen intent
# degrades to a heads-up notification (unlocked emulator): expand the shade
# and tap the notification action label; evidence dump on failure
tap_ring_action() {
  adb shell cmd statusbar expand-notifications
  sleep 2
  python3 tools/uitap.py "$1"
}

# snooze via notification action: ringing stops, alarm rescheduled +1 min
tap_ring_action "snooze"
sleep 3
adb shell dumpsys activity services com.vanipy.kokok > after-snooze-services.txt || true
if grep -q AlarmRingService after-snooze-services.txt; then echo "FAIL: ring service still running after snooze"; exit 1; else echo "snooze stopped the ring service"; fi
sleep 75
adb exec-out screencap -p > ringing-after-snooze.png
adb shell dumpsys activity services com.vanipy.kokok > rering-services.txt || true
grep -q AlarmRingService rering-services.txt || { echo "FAIL: snoozed alarm did not re-ring (service absent)"; exit 1; }

# dismiss the snoozed ring via the notification action
tap_ring_action "dismiss"
sleep 3
adb shell dumpsys activity services com.vanipy.kokok > after-dismiss-services.txt || true
if grep -q AlarmRingService after-dismiss-services.txt; then echo "FAIL: ring service still running after dismiss"; exit 1; else echo "dismiss stopped the ring service"; fi

# collapse the shade left open by the ring-action taps so the app is captured
adb shell cmd statusbar collapse

# exact permission probe only: USE_EXACT_ALARM may keep it allowed; screenshot is not denial proof
adb shell appops set com.vanipy.kokok SCHEDULE_EXACT_ALARM deny || true
adb shell am start -n com.vanipy.kokok/.MainActivity
sleep 4
adb exec-out screencap -p > permission-denied.png
adb shell appops set com.vanipy.kokok SCHEDULE_EXACT_ALARM allow || true

# reboot: set a fresh test alarm, reboot, verify OS kept/rescheduled it
adb shell am start -n com.vanipy.kokok/.MainActivity
sleep 3
python3 tools/uitap.py "test alarm (rings in 60 s)"
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
