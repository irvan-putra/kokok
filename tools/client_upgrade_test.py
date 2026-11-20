#!/usr/bin/env python3
"""Disposable emulator only. Upgrade real signed 0.1.4 without clearing its data.

Synthetic pairing/key/sound/alarm fixture seeded with adb root because released
APKs aren't debuggable. Nothing here connects to a user's phone or real feed.
"""
import hashlib,json,os,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
P='com.vanipy.kokok'
OUT=Path('client-upgrade-evidence');OUT.mkdir(exist_ok=True)
import atexit
def diagnostics():
 for name,args in [('logcat.txt',['logcat','-d']),('last-user-state.txt',['shell','am','get-started-user-state','0']),('alarm-dump.txt',['shell','dumpsys','alarm'])]:
  try:(OUT/name).write_bytes(subprocess.check_output(['adb',*args],stderr=subprocess.STDOUT,timeout=20))
  except Exception:pass
def storage_diagnostics():
 # Test keys only; retain structure and fingerprints rather than key contents.
 report={}
 for zone in ['user','user_de']:
  directory=f'/data/{zone}/0/{P}/shared_prefs'
  try:
   report[zone]={'listing':adb('shell','ls','-la',directory)}
   for name in ['feed.xml','alarm_sound.xml']:
    try:
     values=parse(adb('shell','cat',directory+'/'+name))
     report[zone][name]={k:(hashlib.sha256(v.encode()).hexdigest() if 'keyset' in k.lower() else v) for k,v in values.items()}
    except Exception as e:report[zone][name]=type(e).__name__
  except Exception as e:report[zone]={'error':type(e).__name__}
 (OUT/'storage-diagnostic.json').write_text(json.dumps(report,indent=2))
atexit.register(diagnostics)
atexit.register(storage_diagnostics)
def cmd(*a):return subprocess.check_output(a,text=True,stderr=subprocess.STDOUT,timeout=25).strip()
def adb(*a):return cmd('adb',*a)
def wait(fn,seconds=120):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  try:
   if fn():return
  except (subprocess.CalledProcessError,subprocess.TimeoutExpired,AssertionError):pass
  time.sleep(3)
 raise AssertionError('condition timed out')
def screenshot(name):
 with (OUT/name).open('wb') as f:subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True)
def getprefs():
 return adb('shell','cat',f'/data/user/0/{P}/shared_prefs/feed.xml')
def parse(xml):
 return {e.attrib['name']:e.text if e.tag=='string' else e.attrib.get('value') for e in ET.fromstring(xml)}
def reboot():
 adb('reboot');wait(lambda:adb('shell','getprop','sys.boot_completed')=='1',180)
 adb('root');adb('wait-for-device')
def tap(text):subprocess.run(['python3','tools/uitap.py',text],check=True)
def service():return 'AlarmRingService' in adb('shell','dumpsys','activity','services',P)
old=Path('kokok-old-0.2.0.apk')
assert hashlib.sha256(old.read_bytes()).hexdigest()=='8550ba9651f5ee2bbd5ed08c893d8121a4331f33bf196648b1c879d631a60ce5'
adb('install','-r',str(old));adb('shell','pm','grant',P,'android.permission.POST_NOTIFICATIONS')
adb('root');adb('wait-for-device')
adb('shell','wm','size','1080x1920');adb('shell','wm','density','420')
adb('shell','am','start','-n',P+'/.MainActivity');time.sleep(4)
# Generate public-test fixture keys with the project's tested writer. Never log keys.
subprocess.run(['python3','tools/feed_write.py','gen-app','--private','/tmp/sleep-fixture-key.json','--public','/tmp/sleep-fixture-pub.json'],check=True)
subprocess.run(['python3','tools/feed_write.py','gen-sign','--private','/tmp/sleep-fixture-sign.json','--public','/tmp/sleep-fixture-verify.json'],check=True)
adb('shell','am','force-stop',P)
uid=adb('shell','stat','-c','%u',f'/data/user/0/{P}').strip()
assert uid.isdigit() and int(uid)>=10000, 'unexpected package directory owner'
root=ET.Element('map')
fixtures={'feedUrl':'https://example.invalid/synthetic-alarm-feed','verifyKeyset':Path('/tmp/sleep-fixture-verify.json').read_text(),'appKeyset':Path('/tmp/sleep-fixture-key.json').read_text(),'alarms':'{}'}
for k,v in fixtures.items():ET.SubElement(root,'string',name=k).text=v
ET.SubElement(root,'long',name='lastAppliedRev',value='72')
Path('/tmp/sleep-upgrade-feed.xml').write_bytes(ET.tostring(root))
# A newly booted test image can still have no default and an empty media
# provider. Wait for an actual selected system tone; do not warm it by preview
# or turn a null symbolic default into product audio evidence.
wait(lambda:adb('shell','settings','get','system','alarm_alert').startswith('content://'),180)
chosen_uri=adb('shell','settings','get','system','alarm_alert')
(OUT/'chosen-tone-uri.txt').write_text(chosen_uri)
sound_root=ET.Element('map')
if os.environ.get('BOOT_SOUND_MODE','chosen') == 'chosen':
 ET.SubElement(sound_root,'string',name='uri').text=chosen_uri
(OUT/'sound-mode.txt').write_text(os.environ.get('BOOT_SOUND_MODE','chosen'))
sound=ET.tostring(sound_root,encoding='unicode')
Path('/tmp/sleep-upgrade-sound.xml').write_text(sound)
dp=f'/data/user/0/{P}/shared_prefs'
for src,name in [('/tmp/sleep-upgrade-feed.xml','feed.xml'),('/tmp/sleep-upgrade-sound.xml','alarm_sound.xml')]:
 adb('push',src,'/data/local/tmp/'+name)
 adb('shell','mkdir','-p',dp)
 adb('shell','cp','/data/local/tmp/'+name,dp+'/'+name)
 adb('shell','chown',f'{uid}:{uid}',dp+'/'+name)
 adb('shell','chmod','600',dp+'/'+name)
adb('shell','restorecon','-RF',f'/data/user/0/{P}')
adb('shell','am','start','-n',P+'/.MainActivity');time.sleep(3)
# Actual old app reads the fixture and public key without repair/reset.
assert parse(getprefs())['appKeyset']==fixtures['appKeyset']
sound_before=adb('shell','cat',dp+'/alarm_sound.xml')
screenshot('old-signed-ui.png')
# Establish fixture validity independently of the locked-boot path. Keep original
# settings/provider diagnostics so an unset emulator default cannot masquerade
# as a product regression. These contain only synthetic emulator state.
(OUT/'unlocked-default-setting.txt').write_text(adb('shell','settings','get','system','alarm_alert'))
(OUT/'unlocked-ringtone-provider.txt').write_text(adb('shell','content','query','--uri','content://media/internal/audio/media','--projection','_id:title:is_alarm'))
# No preview here: selected-tone playback must work cold after reboot.
# Actual signed old client creates the scheduled OS alarm through normal UI.
tap('test alarm (rings in 60 s)')
wait(lambda:len(json.loads(parse(getprefs()).get('alarms','{}')))==1,20)
before=parse(getprefs());alarms=json.loads(before['alarms']);alarm_id=next(iter(alarms))
assert 'com.vanipy.kokok.RING' in adb('shell','dumpsys','alarm')
(OUT/'before-os-alarms.txt').write_text(adb('shell','dumpsys','alarm'))
screenshot('old-scheduled.png')
adb('install','-r','kokok-client-candidate.apk')
adb('shell','am','start','-n',P+'/.MainActivity');time.sleep(2)
after=parse(getprefs())
for k in ['appKeyset','verifyKeyset','feedUrl','lastAppliedRev','alarms']:assert after[k]==before[k],k
assert adb('shell','cat',dp+'/alarm_sound.xml')==sound_before
assert 'com.vanipy.kokok.RING' in adb('shell','dumpsys','alarm')
screenshot('upgraded-scheduled.png')
wait(service,90)
screenshot('preserved-alarm-ring.png')
adb('shell','cmd','statusbar','expand-notifications');time.sleep(1);tap('snooze');adb('shell','cmd','statusbar','collapse')
wait(lambda:not service(),20)
record=json.loads(parse(getprefs())['alarms'])[alarm_id]
assert record['status']=='scheduled' and record['snoozed'] is True
mirror=parse(adb('shell','cat',f'/data/user_de/0/{P}/shared_prefs/alarm-boot-mirror.xml'))
assert json.loads(mirror['alarms'])[alarm_id]==record
# Reopen confirms marker survives actual application lifecycle, not only model IO.
adb('shell','am','start','-n',P+'/.MainActivity');time.sleep(1)
assert json.loads(parse(getprefs())['alarms'])[alarm_id]==record
screenshot('snoozed-preserved.png')
(OUT/'result.txt').write_text('PASS actual signed0.2.0 in-place update to same-signer0.2.1 candidate. Synthetic pairing/key/revision, selected sound and scheduled alarm record unchanged; existing OS alarm rang after update; real snooze action set marker in original/mirror and survived reopen. No real phone/feed or critical wakeup. No cold-boot or provider-accuracy claim.\n')
print((OUT/'result.txt').read_text())
