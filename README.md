# kokok

Alarms set for you by your assistant that ring fully offline once applied. AGPL-3.0.

Install: signed APKs on the
[releases page](https://github.com/irvan-putra/kokok/releases), also listed
in the [kaji gallery](https://vanipy.com/kaji/apk/).

## How it works

1. Your assistant publishes an encrypted, signed feed document to a hosted file URL.
2. The app fetches it over HTTPS (redirects are not followed), verifies the signature,
   decrypts it with a key that never leaves the phone, and applies the result with
   `AlarmManager.setAlarmClock` — exact, survives Doze, and shows over the lockscreen.
3. Once applied, every alarm rings with no network at all.

**Publishing a feed is not proof an alarm is set.** The phone must fetch and apply it.
The app shows the last sync time and exactly what it applied. There is no automatic
phone-to-assistant acknowledgement in v1: confirmation is the on-screen alarm list,
plus an optional message you choose to send yourself.

## Feed format

- Envelope JSON: `{"v":1,"kid":...,"enc":"<base64>","sig":"<base64>"}`; the signature
  covers the UTF-8 bytes of the `enc` string.
- Crypto: Google Tink on both ends, identical key templates —
  hybrid `ECIES_P256_COMPRESSED_HKDF_HMAC_SHA256_AES128_GCM`, signature `ECDSA_P256`,
  HKDF context `kokok-feed-v1`. No homegrown primitives.
- Document: `{"v":1,"rev":N,"updated":...,"tz":...,"alarms":[{"id","label","atMillis","snooze"}]}`.
  `atMillis` is an absolute instant; the phone renders it in its current timezone.
- Writer: `tools/feed_write.py` (Python Tink), cross-verified against the Android
  reader in both directions by unit tests.

## Fail-closed semantics

- `rev` must be strictly increasing: replayed or rolled-back feeds are rejected whole.
- Any signature or decrypt failure drops the whole envelope.
- An alarm absent from the feed is canceled. On-device test alarms are never
  feed-managed.
- A fired or dismissed alarm never rings again, even if the feed still lists
  it. Repeating alarms use a fresh id per occurrence (id + date).
- A ringing alarm keeps ringing until you dismiss or snooze it; there is no
  auto-silence timeout.
- An alarm found late rings immediately, no matter how late. AOSP DeskClock instead
  posts a "missed alarm" notification without ringing; kokok deliberately rings.
- Reboot before first unlock: stored state lives in device-protected storage and the
  boot receiver handles both `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED`, so
  rescheduling works even on a still-locked phone. Time/timezone changes re-evaluate
  against the fresh clock.

## Permissions, and why

| permission | why |
|---|---|
| `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` | exact alarm timing |
| `RECEIVE_BOOT_COMPLETED` | reschedule after reboot |
| `POST_NOTIFICATIONS` | the ringing full-screen notification |
| `USE_FULL_SCREEN_INTENT` | show the ringing screen over the lockscreen |
| `WAKE_LOCK` / `VIBRATE` | wake and alert while ringing |
| `INTERNET` | feed sync + update check |
| `REQUEST_INSTALL_PACKAGES` | optional in-app update via the system installer |

The setup checklist on the main screen reads the actual runtime values on your phone
(Android release, exact-alarm grant, full-screen intent, notifications, battery
optimization) and only shows what needs attention. Battery-optimization exclusion is
optional and off by default.

## Building and releasing

- `gradle assembleDebug` / `gradle testDebugUnitTest` (JDK 17, Android SDK 34).
- CI (`.github/workflows/build.yml`): unit tests, rendered UI screenshots, an API-34
  emulator job that installs the APK, sets a test alarm, and captures the real
  ringing screen; and a manual signed-release job (`workflow_dispatch` with a version)
  that signs with the permanent repo-secrets key and publishes a direct
  `kokok-vX.Y.Z.apk` prerelease asset. The trusted checksum is GitHub's
  per-asset digest; earlier releases also carry SHA-256 sidecar files,
  kept but no longer published for new releases.

## Honest limits

- Sync is periodic (JobScheduler, ~30 min, OS may delay) plus manual "sync now".
  An alarm published seconds before its time may arrive late; the late-grace rule
  above decides what happens then.
- The feed URL is a secret-ish bearer URL but not baked into this repo; it is entered
  at pairing time.
- v1 has no automatic acknowledgement channel from the phone to the assistant.

## Pairing configuration (recovery branch, not a released feature)

The app can review pasted pairing JSON or text shared to kokok. Only the HTTPS
feed URL and publisher **public** verification keyset belong in this configuration.
The writer's `pair --url ... --verify publisher-public.json --out pairing.json`
command prepares that non-secret configuration. No operational endpoint or signing
key is provided by this code. Never use synthetic test keys for a real feed.

Import shows a trust review before changing anything. Replacement retains the
phone key, alarm records and monotonic revision floor. The next feed must exceed
the existing revision, even from a replacement publisher. Its full alarm list can
cancel omitted remote alarms and ring overdue alarms immediately. Local test
alarms are excluded from remote cancellation. Canceling the review changes nothing.
A successful configuration import is not an alarm receipt; inspect the applied
revision and on-device alarm list after sync. Publisher rotation needs explicit
review of the new public keyset and a higher revision, not a replay-state reset.

Known custody limit: the phone keyset is in app-private device-protected storage,
not hardware-backed Keystore storage. Backup is disabled. The example writer's
private-key JSON paths are an interface, not a safe production custody solution.
Protected publisher custody and endpoint approval remain separate operational
choices. Never publish keys, plaintext alarms, or personal pairing configuration.


## Optional estimated sleep (0.2.0 experiment)

Sleep monitoring is off by default. Enable it on the phone and grant Android's Physical activity permission. Initial support is SDK 29+ with available Google Play services; older Android 26-28 alarm support is unchanged. Actual provider registration and later valid events, not just installed services, determine availability. If registration fails or data is missing, Kokok says so rather than inventing sleep. No microphone, camera, fine-location or background-location permission is added. Sleep estimates never change alarm times.

The provider estimates sleep from phone signals. It cannot prove sleep, REM, stages, attention or medical health. A still phone in darkness is not proof its owner sleeps. Daily segments may arrive only after high-confidence awakening; classification intervals are not guaranteed. Power-saving, missing sensors and unavailable Play services can leave gaps. Existing alarms and dismissal work independently.

Kokok keeps at most 30 estimated windows and the last confidence observation in a separate credential-protected private store, unavailable before unlock. It does not add telemetry or upload sleep records. Copy private summary is explicit and puts the summary on the clipboard, where the user decides whether to share. Stop unregisters; if provider removal fails this is visible and local callbacks are still ignored. Clear records removes Kokok sleep data, not alarms or provider history. Backup is disabled in the app manifest. Provider on-device inference does not establish a blanket no-collection claim for Google Play services.

Sleep subscription restoration runs only after unlock/normal boot or app replacement if the user previously enabled it, separate from direct-boot alarm recovery. Previously detected segments returned at registration are deduplicated, not presented as newly measured nights.

Dependency source conflict: the Sleep guide currently names 25.0.0/minSdk29. Google's Maven repository provides 21.4.0 with library/transitive minSdk23 and Sleep API classes, while25.0.0 is absent. The21.4.0 artifact uses Kotlin 2.3 metadata, incompatible with the existing Kotlin 2.0.20 compiler; this experiment pins 21.3.0 rather than silently upgrading the compiler. This project keeps application minSdk26 and gates only the optional sleep feature at 29+, without a manifest minSdk override. Build and runtime tests must still qualify this experiment. The API reference documents legacy permissions on older APIs, but enabling sleep below 29 is not part of this initial version.

Sources:
- https://developers.google.com/location-context/sleep
- https://developers.google.com/android/reference/com/google/android/gms/location/ActivityRecognitionClient
- https://developers.google.com/android/reference/com/google/android/gms/location/SleepSegmentEvent
- https://developers.google.com/android/reference/com/google/android/gms/common/GoogleApiAvailability
- https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-location/maven-metadata.xml
- https://developers.google.com/android/guides/play-data-disclosure (covers only the named core SDKs, not a full Sleep SDK privacy guarantee)

Tests use synthetic timestamps and labels only. Host/render or emulator results are not real overnight sleep accuracy or physical-device support evidence.
