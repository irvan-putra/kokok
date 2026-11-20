# Feed refresh and local snooze

Source fix only, not in published0.2.0. A new feed revision previously rescheduled included nonterminal IDs from the feed timestamp, even if the person had snoozed locally. Adding unrelated backup records could therefore move that snooze back to the original time and ring early.

The fix persists a local snoozed flag in the existing alarm record, including the alarm-only direct-boot mirror. Snooze sets it; a newer feed leaves that included scheduled ID and its OS schedule alone. Explicit feed removal still cancels feed-managed alarms. Identical unsnoozed scheduled IDs are also left alone. New/changed unsnoozed alarms retain existing scheduling and late-ring behavior. No feed schema, pairing, key, sound, sleep, or original alarm-ID change.

Compatibility limit: records created before this fix have no snoozed flag. They remain readable but an already-active legacy snooze cannot be distinguished safely from a remote-time edit using that record alone. Do not deploy a feed update during a possible legacy snooze. A safe additive update needs a verified upgraded client and a confirmed quiet state with no active legacy snooze, or a separately designed migration using trustworthy prior feed state. No inference from unchanged feed JSON is enough. Never use critical wakeups as a test.

Focused FeedModel tests cover additive backup preservation, exact no-op, ordinary remote edit, explicit removal and flag persistence through original/mirror storage. Model tests do not prove Android delivery or physical-phone behavior. The published0.2.0 APK remains unchanged until a separate reviewed build/release path.
