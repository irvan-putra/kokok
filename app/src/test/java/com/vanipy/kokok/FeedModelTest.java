package com.vanipy.kokok;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FeedModelTest {
    private static FeedModel.Alarm alarm(String id, long at) {
        return new FeedModel.Alarm(id, "label-" + id, at, 9);
    }

    private static FeedModel.Feed feed(int rev, FeedModel.Alarm... alarms) throws Exception {
        StringBuilder sb = new StringBuilder("{\"v\":1,\"rev\":").append(rev).append(",\"alarms\":[");
        for (int i = 0; i < alarms.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(alarms[i].id).append("\",\"atMillis\":").append(alarms[i].atMillis).append('}');
        }
        sb.append("]}");
        return FeedModel.parse(sb.toString());
    }

    @Test
    public void schedulesFutureAlarms() throws Exception {
        long now = 1_000_000L;
        Map<String, FeedStore.StoredAlarm> stored = new HashMap<>();
        FeedModel.Plan p = FeedModel.apply(feed(1, alarm("a", now + 60_000)), 0, stored, now);
        assertEquals(1, p.toSchedule.size());
        assertEquals(0, p.toRingNow.size());
    }

    @Test
    public void ringsImmediatelyWithinTenMinutes() throws Exception {
        long now = 1_000_000L;
        Map<String, FeedStore.StoredAlarm> stored = new HashMap<>();
        FeedModel.Plan p = FeedModel.apply(feed(1, alarm("a", now - 5 * 60_000)), 0, stored, now);
        assertEquals(1, p.toRingNow.size());
    }

    @Test
    public void ringsImmediatelyNoMatterHowLate() throws Exception {
        long now = 1_000_000L;
        Map<String, FeedStore.StoredAlarm> stored = new HashMap<>();
        FeedModel.Plan p = FeedModel.apply(feed(1, alarm("a", now - 61 * 60_000)), 0, stored, now);
        assertEquals(1, p.toRingNow.size()); // late alarms ring, however late
    }

    @Test
    public void rejectsStaleRev() throws Exception {
        long now = 1_000_000L;
        Map<String, FeedStore.StoredAlarm> stored = new HashMap<>();
        try {
            FeedModel.apply(feed(3, alarm("a", now + 60_000)), 3, stored, now);
            fail("replayed rev must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("rev"));
        }
    }

    @Test
    public void firedAlarmsNeverReringAndAbsentIsCanceled() throws Exception {
        long now = 1_000_000L;
        Map<String, FeedStore.StoredAlarm> stored = new HashMap<>();
        stored.put("f", new FeedStore.StoredAlarm("f", "old", now + 60_000, 9, "fired", false));
        stored.put("g", new FeedStore.StoredAlarm("g", "gone", now + 60_000, 9, "scheduled", false));
        stored.put("l", new FeedStore.StoredAlarm("l", "local", now + 60_000, 9, "scheduled", true));
        FeedModel.Plan p = FeedModel.apply(feed(2, alarm("f", now + 60_000)), 1, stored, now);
        assertEquals(0, p.toSchedule.size());       // fired alarm in feed does not re-ring
        assertEquals(1, p.toCancel.size());          // "g" absent from feed
        assertEquals("g", p.toCancel.get(0));
        assertTrue(!p.toCancel.contains("l"));       // local alarms are not feed-managed
    }
    @Test
    public void feedRefreshPreservesSnoozedAlarmAndSchedulesOnlyBackup() {
        long now = 1_000_000L;
        Map<String, FeedStore.StoredAlarm> stored = new HashMap<>();
        FeedStore.StoredAlarm original = new FeedStore.StoredAlarm("original", "wake", now + 540000, 9, "scheduled", false, true);
        stored.put("original", original);
        FeedModel.Feed f = new FeedModel.Feed(); f.rev = 4;
        f.alarms.add(new FeedModel.Alarm("original", "wake", now - 60000, 9));
        f.alarms.add(new FeedModel.Alarm("backup", "backup", now + 60000, 9));
        FeedModel.Plan p = FeedModel.apply(f, 3, stored, now);
        assertEquals(1, p.toSchedule.size());
        assertEquals("backup", p.toSchedule.get(0).id);
        assertTrue(p.toRingNow.isEmpty());
        assertTrue(p.toCancel.isEmpty());
        assertTrue(stored.get("original") == original);
    }

    @Test
    public void snoozeFlagRoundTripsThroughBothAlarmStores() {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        Map<String, FeedStore.StoredAlarm> stored = new HashMap<>();
        stored.put("s", new FeedStore.StoredAlarm("s", "wake", 1540000, 9, "scheduled", false, true));
        FeedStore.INSTANCE.saveAlarms(c, stored);
        assertTrue(FeedStore.INSTANCE.alarms(c).get("s").getSnoozed());
        String mirror = c.createDeviceProtectedStorageContext().getSharedPreferences("alarm-boot-mirror", 0).getString("alarms", "");
        assertTrue(mirror.contains("\"snoozed\":true"));
    }
    @Test
    public void identicalScheduledIdIsNotRewrittenButUnsnoozedEditStillApplies() {
        Map<String, FeedStore.StoredAlarm> stored = new HashMap<>();
        stored.put("s", new FeedStore.StoredAlarm("s", "wake", 9000, 9, "scheduled", false));
        FeedModel.Feed f = new FeedModel.Feed(); f.rev = 4;
        f.alarms.add(new FeedModel.Alarm("s", "wake", 9000, 9));
        assertTrue(FeedModel.apply(f, 3, stored, 1000).toSchedule.isEmpty());
        f.alarms.clear(); f.alarms.add(new FeedModel.Alarm("s", "wake", 8000, 9));
        assertEquals(8000, FeedModel.apply(f, 3, stored, 1000).toSchedule.get(0).atMillis);
    }

    @Test
    public void explicitFeedRemovalStillCancelsSnoozedFeedAlarm() {
        Map<String, FeedStore.StoredAlarm> stored = new HashMap<>();
        stored.put("s", new FeedStore.StoredAlarm("s", "wake", 9000, 9, "scheduled", false, true));
        FeedModel.Feed f = new FeedModel.Feed(); f.rev = 4;
        assertEquals("s", FeedModel.apply(f, 3, stored, 1000).toCancel.get(0));
    }
}
