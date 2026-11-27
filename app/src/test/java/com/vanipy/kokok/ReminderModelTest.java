package com.vanipy.kokok;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Calendar;
import java.util.TimeZone;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ReminderModelTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static long at(int y, int m, int d, int hh, int mm) {
        Calendar c = Calendar.getInstance(UTC);
        c.set(y, m - 1, d, hh, mm, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static ReminderModel.Reminder daily(int hh, int mm) throws Exception {
        return ReminderModel.parseReminder(new org.json.JSONObject(
                "{\"id\":\"r\",\"label\":\"label\",\"time\":\"" + String.format("%02d:%02d", hh, mm) + "\"}"));
    }

    // 2026-11-27 is a Friday (ISO weekday 5)

    @Test
    public void parsesRemindersFromFeed() throws Exception {
        String json = "{\"v\":1,\"rev\":1,\"reminders\":[{\"id\":\"li\",\"label\":\"LinkedIn puzzles\","
                + "\"time\":\"13:05\",\"days\":[1,2,3,4,5],\"snooze\":60,\"ring\":true}]}";
        FeedModel.Feed f = FeedModel.parse(json);
        assertEquals(1, f.reminders.size());
        ReminderModel.Reminder r = f.reminders.get(0);
        assertEquals("li", r.id);
        assertEquals("LinkedIn puzzles", r.label);
        assertEquals(13, r.hour);
        assertEquals(5, r.minute);
        assertEquals(5, r.days.size());
        assertTrue(r.days.contains(1));
        assertFalse(r.days.contains(6));
        assertEquals(60, r.snoozeMinutes);
        assertTrue(r.ring);
    }

    @Test
    public void feedWithoutRemindersParsesEmpty() throws Exception {
        FeedModel.Feed f = FeedModel.parse("{\"v\":1,\"rev\":1,\"alarms\":[]}");
        assertTrue(f.reminders.isEmpty());
    }

    @Test
    public void invalidReminderRejectsWholeFeed() throws Exception {
        try {
            FeedModel.parse("{\"v\":1,\"rev\":1,\"reminders\":[{\"id\":\"bad\",\"time\":\"25:00\"}]}");
            fail("invalid reminder time must reject the feed");
        } catch (org.json.JSONException expected) {
        }
    }

    @Test
    public void defaultsAreDailyWithSixtyMinuteAgain() throws Exception {
        ReminderModel.Reminder r = daily(13, 5);
        assertTrue(r.days.isEmpty());
        assertEquals(60, r.snoozeMinutes);
        assertFalse(r.ring);
    }

    @Test
    public void firesLaterToday() throws Exception {
        long now = at(2026, 11, 27, 10, 0);
        long next = ReminderModel.nextFireMillis(daily(13, 5), new ReminderModel.State(), now, UTC);
        assertEquals(at(2026, 11, 27, 13, 5), next);
    }

    @Test
    public void lateTodayFiresImmediately() throws Exception {
        long now = at(2026, 11, 27, 14, 0);
        long next = ReminderModel.nextFireMillis(daily(13, 5), new ReminderModel.State(), now, UTC);
        assertTrue("late same-day occurrence fires at once", next > now && next <= now + 5_000);
    }

    @Test
    public void doneTodaySkipsToTomorrow() throws Exception {
        ReminderModel.State s = new ReminderModel.State();
        s.doneDates.add("2026-11-27");
        long now = at(2026, 11, 27, 10, 0);
        long next = ReminderModel.nextFireMillis(daily(13, 5), s, now, UTC);
        assertEquals(at(2026, 11, 28, 13, 5), next);
    }

    @Test
    public void awaitingWithoutSnoozeWaitsForUser() throws Exception {
        ReminderModel.State s = new ReminderModel.State();
        s.awaitingDate = "2026-11-27";
        long now = at(2026, 11, 27, 14, 0);
        long next = ReminderModel.nextFireMillis(daily(13, 5), s, now, UTC);
        assertEquals(at(2026, 11, 28, 13, 5), next);
    }

    @Test
    public void awaitingWithSnoozeFiresAtSnoozeTime() throws Exception {
        ReminderModel.State s = new ReminderModel.State();
        s.awaitingDate = "2026-11-27";
        long now = at(2026, 11, 27, 14, 0);
        s.snoozeUntilMillis = now + 30 * 60_000L;
        long next = ReminderModel.nextFireMillis(daily(13, 5), s, now, UTC);
        assertEquals(now + 30 * 60_000L, next);
    }

    @Test
    public void weekdayFilterSkipsOtherDays() throws Exception {
        ReminderModel.Reminder monday = ReminderModel.parseReminder(new org.json.JSONObject(
                "{\"id\":\"m\",\"time\":\"13:05\",\"days\":[1]}"));
        long now = at(2026, 11, 27, 10, 0); // Friday
        long next = ReminderModel.nextFireMillis(monday, new ReminderModel.State(), now, UTC);
        assertEquals(at(2026, 11, 30, 13, 5), next); // Monday
    }

    @Test
    public void yesterdayAwaitingDoesNotBlockToday() throws Exception {
        ReminderModel.State s = new ReminderModel.State();
        s.awaitingDate = "2026-11-26";
        long now = at(2026, 11, 27, 10, 0);
        long next = ReminderModel.nextFireMillis(daily(13, 5), s, now, UTC);
        assertEquals(at(2026, 11, 27, 13, 5), next);
    }

    @Test
    public void sameAsDetectsChanges() throws Exception {
        ReminderModel.Reminder a = daily(13, 5);
        ReminderModel.Reminder b = daily(13, 5);
        assertTrue(a.sameAs(b));
        ReminderModel.Reminder c = ReminderModel.parseReminder(new org.json.JSONObject(
                "{\"id\":\"r\",\"label\":\"changed\",\"time\":\"13:05\"}"));
        assertFalse(a.sameAs(c));
    }
}
