#!/usr/bin/env python3
"""Disposable emulator only. Upgrade real signed 0.1.4 without clearing its data.

Synthetic pairing/key/sound/alarm fixture seeded with adb root because released
APKs aren't debuggable. Nothing here connects to a user's phone or real feed.
"""
import hashlib,json,os,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
P='com.vanipy.kokok'
OUT=Path('sleep-upgrade-evidence');OUT.mkdir(exist_ok=True)
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
def cmd(*a):return subprocess.check_output(a,text=True,stderr=subprocess.STDOUT).strip()
def adb(*a):return cmd('adb',*a)
def wait(fn,seconds=120):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  try:
   if fn():return
  except (subprocess.CalledProcessError,AssertionError):pass
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
old=Path('kokok-old-0.1.4.apk')
assert hashlib.sha256(old.read_bytes()).hexdigest()=='1d2e94f5f1be0ee2365cc2a87061faac874c75cd08ceffc40ec4f62ce0164f26'
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
sound_root=ET.Element('map');ET.SubElement(sound_root,'string',name='uri').text=chosen_uri
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
# Set a secure lock before upgrade so subsequent boot really remains pre-unlock.
adb('shell','locksettings','set-pin','2468')
# Let the ACTUAL old app create and schedule an alarm through its normal UI.
# The new build must retain both its record and AlarmManager delivery.
tap('test alarm (rings in 60 s)')
# SharedPreferences.apply persists asynchronously after the UI returns.
wait(lambda: len(json.loads(parse(getprefs()).get('alarms','{}')))==1,20)
before=parse(getprefs());assert before['lastAppliedRev']=='72'
old_alarms=json.loads(before['alarms']);assert len(old_alarms)==1
alarm_id=next(iter(old_alarms));assert old_alarms[alarm_id]['status']=='scheduled'
assert 'com.vanipy.kokok.RING' in adb('shell','dumpsys','alarm')
# PackageManager enforces same signing identity; NEVER use -d or uninstall.
adb('install','-r','kokok-sleep-candidate.apk')
adb('shell','am','start','-n',P+'/.MainActivity');time.sleep(4)
after=parse(getprefs())
for k in ['appKeyset','verifyKeyset','feedUrl','lastAppliedRev','alarms']:assert after[k]==before[k],k
assert adb('shell','cat',dp+'/alarm_sound.xml')==sound_before
# Cache must contain the exact selected sound, not a bundled fallback.
source_audio=subprocess.check_output(['adb','exec-out','content','read','--uri',chosen_uri])
cached_audio=subprocess.check_output(['adb','exec-out','cat',f'/data/user_de/0/{P}/files/alarm-tone.bin'])
assert source_audio and hashlib.sha256(source_audio).digest()==hashlib.sha256(cached_audio).digest()
(OUT/'chosen-tone-cache-proof.txt').write_text('Exact selected provider bytes match private direct-boot cache SHA256='+hashlib.sha256(cached_audio).hexdigest()+'; no playback preview before reboot.\n')
screenshot('upgraded-signed-ui.png')
adb('logcat','-c')
# New app's BootReceiver must recover a pre-existing scheduled alarm before unlock.
reboot()
state=adb('shell','am','get-started-user-state','0');assert 'RUNNING_LOCKED' in state,state
(OUT/'preunlock-user-state.txt').write_text(state)
wait(service,180)
assert 'RUNNING_LOCKED' in adb('shell','am','get-started-user-state','0')
(OUT/'preunlock-ring-services.txt').write_text(adb('shell','dumpsys','activity','services',P))
screenshot('preunlock-ringing.png')
(OUT/'preunlock-media-player.txt').write_text(adb('shell','dumpsys','media.player'))
locked=parse(adb('shell','cat',f'/data/user_de/0/{P}/shared_prefs/alarm-boot-mirror.xml'))
assert json.loads(locked['alarms'])[alarm_id]['status']=='fired'
assert not any('keyset' in k.lower() or k=='feedUrl' for k in locked)
assert 'playback started; looping=true' in adb('logcat','-d','-s','KokokAlarmAudio:I','*:S')
# Dismiss via the actual pre-unlock full-screen action, then bring up the
# secure PIN UI. Typing immediately into the alarm window is not an unlock.
tap('dismiss');wait(lambda:not service(),30)
adb('shell','input','keyevent','224')
adb('shell','input','swipe','540','1700','540','400','400');time.sleep(2)
screenshot('before-pin-entry.png')
adb('shell','uiautomator','dump','/data/local/tmp/pin-ui.xml')
(OUT/'before-pin-entry.xml').write_text(adb('shell','cat','/data/local/tmp/pin-ui.xml'))
adb('shell','input','text','2468');adb('shell','input','keyevent','66')
wait(lambda:'RUNNING_UNLOCKED' in adb('shell','am','get-started-user-state','0'),60)
adb('shell','am','start','-n',P+'/.MainActivity');time.sleep(2)
after_unlock=parse(getprefs())
assert json.loads(after_unlock['alarms'])[alarm_id]['status']=='dismissed'
for k in ['appKeyset','verifyKeyset','feedUrl','lastAppliedRev']:assert after_unlock[k]==before[k],k
assert adb('shell','cat',dp+'/alarm_sound.xml')==sound_before
# Android blocks lowering versionCode for signed release apps. This isolated
# artifact is old0.1.4 source with only versionCode advanced, not a new release.
adb('install','-r','kokok-code-rollback.apk')
adb('shell','am','start','-n',P+'/.MainActivity');time.sleep(3)
rolled=parse(getprefs())
for k in ['appKeyset','verifyKeyset','feedUrl','lastAppliedRev','alarms']:assert rolled[k]==after_unlock[k],k
assert adb('shell','cat',dp+'/alarm_sound.xml')==sound_before
screenshot('code-rollback-old-ui.png')
(OUT/'rollback-scope.txt').write_text('Old0.1.4 code with versionCode7 only for Android anti-downgrade compatibility; original exact signed0.1.4 was used for initial upgrade. Not published. Original credential preferences readable and unchanged after code rollback. Direct installation of a lower versionCode is not supported.\n')
(OUT/'result.txt').write_text('PASS actual signed0.1.4 -> same-signer candidate in-place upgrade; synthetic key/pairing/revision/alarms/sound retained; API34 secure-lock reboot reached RUNNING_LOCKED and existing alarm fired before unlock; dismiss stopped service. No real sleep provider or physical-phone accuracy proof.\n')
print((OUT/'result.txt').read_text())
