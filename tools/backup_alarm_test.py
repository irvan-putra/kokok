#!/usr/bin/env python3
"""Disposable-emulator qualification of independently scheduled backup alarms.
Uses the actual published APK. Only synthetic alarm records are seeded, no user
feed, keys, dates or labels. Timings compressed; never touches a real phone.
"""
import hashlib,json,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
P='com.vanipy.kokok';OUT=Path('backup-evidence');OUT.mkdir(exist_ok=True)
def adb(*a):return subprocess.check_output(['adb',*a],text=True,stderr=subprocess.STDOUT).strip()
def wait(fn,seconds=150):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  try:
   if fn():return
  except (subprocess.CalledProcessError,AssertionError):pass
  time.sleep(1)
 raise AssertionError('condition timed out')
def shot(n):
 with (OUT/n).open('wb') as f:subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True)
def state():
 xml=adb('shell','cat',f'/data/user/0/{P}/shared_prefs/feed.xml')
 return json.loads(next(e.text for e in ET.fromstring(xml) if e.attrib['name']=='alarms'))
def service():return 'AlarmRingService' in adb('shell','dumpsys','activity','services',P)
def action(name):
 adb('shell','cmd','statusbar','expand-notifications');time.sleep(1)
 subprocess.run(['python3','tools/uitap.py',name],check=True)
 adb('shell','cmd','statusbar','collapse')
apk=Path('published.apk');assert hashlib.sha256(apk.read_bytes()).hexdigest()=='8550ba9651f5ee2bbd5ed08c893d8121a4331f33bf196648b1c879d631a60ce5'
adb('install','-r',str(apk));adb('shell','pm','grant',P,'android.permission.POST_NOTIFICATIONS');adb('root');adb('wait-for-device')
adb('shell','wm','size','1080x1920');adb('shell','wm','density','420');adb('shell','am','start','-n',P+'/.MainActivity');time.sleep(3);adb('shell','am','force-stop',P)
# FeedStore's normal original schema, not a production feed update.
now=int(time.time()*1000)
alarms={}
for id,offset,snooze in [('backup-30',90000,9),('backup-15',120000,1),('original',150000,9)]:
 alarms[id]={'id':id,'label':'synthetic '+id,'atMillis':now+offset,'snooze':snooze,'status':'scheduled','local':False}
root=ET.Element('map');ET.SubElement(root,'string',name='alarms').text=json.dumps(alarms)
Path('/tmp/backup-feed.xml').write_bytes(ET.tostring(root));adb('push','/tmp/backup-feed.xml','/data/local/tmp/backup-feed.xml')
uid=adb('shell','stat','-c','%u',f'/data/user/0/{P}');assert uid.isdigit() and int(uid)>=10000
path=f'/data/user/0/{P}/shared_prefs/feed.xml';adb('shell','cp','/data/local/tmp/backup-feed.xml',path);adb('shell','chown',uid+':'+uid,path);adb('shell','restorecon','-RF',f'/data/user/0/{P}')
# MainActivity prepares only the alarm mirror, then a real reboot reschedules.
adb('shell','am','start','-n',P+'/.MainActivity');time.sleep(3);adb('reboot');wait(lambda:adb('shell','getprop','sys.boot_completed')=='1',180);adb('root');adb('wait-for-device');adb('shell','input','keyevent','82')
wait(lambda:state()['backup-30']['status']=='fired');assert service();shot('first-backup-ring.png')
action('dismiss');wait(lambda:state()['backup-30']['status']=='dismissed');assert state()['original']==alarms['original'];assert state()['backup-15']==alarms['backup-15'];assert not service()
wait(lambda:state()['backup-15']['status']=='fired');assert service();shot('second-backup-ring.png')
action('snooze');wait(lambda:state()['backup-15']['status']=='scheduled');assert state()['original']==alarms['original'];snoozed=state()['backup-15'];assert snoozed['atMillis']>alarms['original']['atMillis'];assert not service()
wait(lambda:state()['original']['status']=='fired');assert service();assert state()['backup-30']['status']=='dismissed';assert state()['backup-15']['status']=='scheduled';shot('original-ring.png')
action('dismiss');wait(lambda:state()['original']['status']=='dismissed');assert state()['backup-15']==snoozed
wait(lambda:state()['backup-15']['status']=='fired');assert service();shot('backup-snooze-rering.png');action('dismiss');wait(lambda:state()['backup-15']['status']=='dismissed');assert state()['original']['status']=='dismissed'
(OUT/'final-alarm-records.json').write_text(json.dumps(state(),indent=2));(OUT/'result.txt').write_text('PASS published0.2.0 synthetic three-alarm chain. First backup dismissal preserved other records; second backup snoozed without changing original; original rang and dismissed while backup remained scheduled; snoozed backup re-rang. Compressed offsets, no physical-phone proof and no real feed mutation.\n');print((OUT/'result.txt').read_text())
