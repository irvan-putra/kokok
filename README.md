# kokok

Alarms set for you by your assistant that ring fully offline once applied. AGPL-3.0.

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
- A fired, missed or dismissed alarm never rings again, even if the feed still lists
  it. Repeating alarms use a fresh id per occurrence (id + date).
- A ringing alarm keeps ringing until you dismiss or snooze it; there is no
  auto-silence timeout (user-chosen behavior, 2026-11-19).
- An alarm found late: if it is at most 10 minutes late it rings immediately;
  later than that it is marked `missed`. (AOSP DeskClock instead posts a "missed
  alarm" notification without ringing; the 10-minute immediate ring is a deliberate
  deviation and an open product choice — see `FeedFormat.LATE_GRACE_MILLIS`.)
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
  `kokok-vX.Y.Z.apk` prerelease asset with its SHA-256.

## Honest limits

- Sync is periodic (JobScheduler, ~30 min, OS may delay) plus manual "sync now".
  An alarm published seconds before its time may arrive late; the late-grace rule
  above decides what happens then.
- The feed URL is a secret-ish bearer URL but not baked into this repo; it is entered
  at pairing time.
- v1 has no automatic acknowledgement channel from the phone to the assistant.
