package com.vanipy.kokok;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Recurring reminders: defined by the feed, fired on-device at a local
 * wall-clock time. A reminder occurrence is id + local date, the same rule
 * repeating alarms already use. "done" ends today's occurrence; "again"
 * re-fires it after the reminder's snooze minutes.
 */
public final class ReminderModel {
    private ReminderModel() {}

    public static final class Reminder {
        public final String id;
        public final String label;
        public final int hour;           // 0-23, phone-local
        public final int minute;         // 0-59
        public final Set<Integer> days;  // ISO weekday 1=Mon..7=Sun; empty = every day
        public final int snoozeMinutes;  // the "again" interval
        public final boolean ring;       // false: heads-up notification; true: full ringer

        public Reminder(String id, String label, int hour, int minute,
                        Set<Integer> days, int snoozeMinutes, boolean ring) {
            this.id = id;
            this.label = label;
            this.hour = hour;
            this.minute = minute;
            this.days = days;
            this.snoozeMinutes = snoozeMinutes;
            this.ring = ring;
        }

        public String timeString() {
            return String.format(Locale.US, "%02d:%02d", hour, minute);
        }

        public boolean sameAs(Reminder o) {
            return o != null && id.equals(o.id) && label.equals(o.label)
                    && hour == o.hour && minute == o.minute && days.equals(o.days)
                    && snoozeMinutes == o.snoozeMinutes && ring == o.ring;
        }
    }

    /** Per-reminder mutable on-device state. */
    public static final class State {
        public final Set<String> doneDates = new HashSet<>(); // yyyy-MM-dd, phone-local
        public String awaitingDate = "";   // occurrence posted, waiting for the user
        public long snoozeUntilMillis = 0; // pending "again" fire for awaitingDate
    }

    /** Parses one feed reminder; any invalid field rejects the whole feed. */
    public static Reminder parseReminder(JSONObject o) throws JSONException {
        String id = o.getString("id");
        if (id.isEmpty() || id.indexOf('@') >= 0) throw new JSONException("bad reminder id");
        String time = o.getString("time");
        if (!time.matches("([01]\\d|2[0-3]):[0-5]\\d")) {
            throw new JSONException("bad reminder time");
        }
        int hour = Integer.parseInt(time.substring(0, 2));
        int minute = Integer.parseInt(time.substring(3, 5));
        Set<Integer> days = new HashSet<>();
        JSONArray d = o.optJSONArray("days");
        if (d != null) {
            for (int i = 0; i < d.length(); i++) {
                int day = d.getInt(i);
                if (day < 1 || day > 7) throw new JSONException("bad reminder day");
                days.add(day);
            }
        }
        int snooze = o.optInt("snooze", 60);
        if (snooze < 1 || snooze > 24 * 60) throw new JSONException("bad reminder snooze");
        return new Reminder(id, o.optString("label", ""), hour, minute, days,
                snooze, o.optBoolean("ring", false));
    }

    public static JSONObject toJson(Reminder r) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", r.id);
            o.put("label", r.label);
            o.put("time", r.timeString());
            JSONArray d = new JSONArray();
            for (int day : r.days) d.put(day);
            o.put("days", d);
            o.put("snooze", r.snoozeMinutes);
            o.put("ring", r.ring);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return o;
    }

    public static String dateString(long millis, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(millis);
        return dateString(c);
    }

    private static String dateString(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /**
     * The next instant this reminder should fire, or -1 for none.
     * - recurrence by phone-local wall clock and optional weekday filter
     * - a done date never fires again
     * - an occurrence awaiting the user does not re-fire, except at its
     *   pending "again" time
     * - today's occurrence found late fires immediately, however late;
     *   occurrences from past days are skipped
     */
    public static long nextFireMillis(Reminder def, State state, long nowMillis, TimeZone tz) {
        Calendar cal = Calendar.getInstance(tz, Locale.US);
        for (int offset = 0; offset <= 8; offset++) {
            cal.setTimeInMillis(nowMillis);
            cal.add(Calendar.DAY_OF_YEAR, offset);
            String date = dateString(cal);
            int dow = cal.get(Calendar.DAY_OF_WEEK);
            int iso = dow == Calendar.SUNDAY ? 7 : dow - 1;
            if (!def.days.isEmpty() && !def.days.contains(iso)) continue;
            if (state.doneDates.contains(date)) continue;
            int year = cal.get(Calendar.YEAR);
            int month = cal.get(Calendar.MONTH);
            int dom = cal.get(Calendar.DAY_OF_MONTH);
            cal.set(year, month, dom, def.hour, def.minute, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long candidate = cal.getTimeInMillis();
            if (offset == 0) {
                if (date.equals(state.awaitingDate)) {
                    if (state.snoozeUntilMillis > nowMillis) return state.snoozeUntilMillis;
                    continue; // notification/ring already posted; waiting for the user
                }
                if (candidate > nowMillis) return candidate;
                return nowMillis + 1_000; // late today: fire immediately
            }
            if (candidate > nowMillis) return candidate;
        }
        return -1;
    }
}
