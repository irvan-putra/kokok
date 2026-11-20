#!/usr/bin/env python3
"""Assert actual app-owned device-protected state after authenticated sync."""
import json,subprocess,sys,xml.etree.ElementTree as E
revision=int(sys.argv[1]); present=sys.argv[2]=='present'
r=subprocess.run(['adb','shell','run-as','com.vanipy.kokok','cat','/data/user/0/com.vanipy.kokok/shared_prefs/feed.xml'],capture_output=True,text=True,check=True)
root=E.fromstring(r.stdout);values={e.get('name'):e.text if e.tag=='string' else e.get('value') for e in root}
assert int(values.get('lastAppliedRev','0'))==revision, 'authenticated revision was not applied'
assert int(values.get('lastSyncAt','0'))>0, 'no successful sync timestamp'
alarms=json.loads(values.get('alarms','{}'))
assert ('ci-feed-1' in alarms)==present, 'remote alarm state mismatch'
if present:assert alarms['ci-feed-1']['status']=='scheduled', 'remote alarm not scheduled'
print('Verified app-owned applied revision',revision,'and remote alarm', 'present' if present else 'absent')
