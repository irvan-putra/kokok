#!/usr/bin/env python3
"""Tap a UI element by its visible label on the emulator.

Fixes two harness defects found in run 44461932491 review:
- Android buttons render ALL CAPS while labels are set lowercase: match
  case-insensitively.
- Elements can sit below the small emulator viewport: scroll the app's
  ScrollView and re-dump, up to a few tries.
Exits 1 with a message if the label never appears.
"""
import re
import subprocess
import sys
import time

label = sys.argv[1]
pat = re.compile(
    r'text="(' + re.escape(label) + r')"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
    re.IGNORECASE)

for _attempt in range(4):
    subprocess.run(['adb', 'shell', 'uiautomator', 'dump', '/data/local/tmp/uitap.xml'],
                   check=True, capture_output=True)
    xml = subprocess.run(['adb', 'shell', 'cat', '/data/local/tmp/uitap.xml'],
                         capture_output=True, text=True, check=True).stdout
    m = pat.search(xml)
    if m:
        x = (int(m.group(2)) + int(m.group(4))) // 2
        y = (int(m.group(3)) + int(m.group(5))) // 2
        subprocess.run(['adb', 'shell', 'input', 'tap', str(x), str(y)], check=True)
        sys.exit(0)
    # not visible: scroll down inside the app and look again
    subprocess.run(['adb', 'shell', 'input', 'swipe', '540', '1500', '540', '500', '300'],
                   check=True)
    time.sleep(1)
subprocess.run(['adb', 'shell', 'cp', '/data/local/tmp/uitap.xml',
                '/data/local/tmp/uitap-fail.xml'], capture_output=True)
subprocess.run(['adb', 'pull', '/data/local/tmp/uitap-fail.xml'], capture_output=True)
with open('uitap-fail.png', 'wb') as f:
    subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=f)
print(f'label not found after scroll retries: {label}', file=sys.stderr)
sys.exit(1)
