package com.vanipy.kokok;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Feed document model + fail-closed application semantics. */
public final class FeedModel {
    private FeedModel() {}

    public static final class Alarm {
        public final String id;
        public final String label;
        public final long atMillis;
        public final int snooze;

        public Alarm(String id, String label, long atMillis, int snooze) {
            this.id = id;
            this.label = label;
            this.atMillis = atMillis;
            this.snooze = snooze;
        }
    }

    public static final class Feed {
        public int rev;
        public final List<Alarm> alarms = new ArrayList<>();
    }

    public static Feed parse(String json) throws org.json.JSONException {
        JSONObject doc = new JSONObject(json);
        if (doc.getInt("v") != FeedFormat.FEED_VERSION) {
            throw new org.json.JSONException("unsupported feed version");
        }
        Feed f = new Feed();
        f.rev = doc.getInt("rev");
        JSONArray arr = doc.optJSONArray("alarms");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                f.alarms.add(new Alarm(
                        a.getString("id"),
                        a.optString("label", ""),
                        a.getLong("atMillis"),
                        a.optInt("snooze", 9)));
            }
        }
        return f;
    }

    /** What applying a feed would change. */
    public static final class Plan {
        public final List<Alarm> toSchedule = new ArrayList<>();
        public final List<Alarm> toRingNow = new ArrayList<>();
        public final List<String> toCancel = new ArrayList<>();
    }

    /**
     * Fail-closed semantics:
     * - rev must be strictly greater than lastAppliedRev (replay/rollback rejected)
     * - an alarm absent from the feed is canceled (unless locally created)
     * - an alarm already fired/missed/dismissed never rings again
     * - a past-due alarm rings immediately no matter how late
     */
    public static Plan apply(Feed feed, long lastAppliedRev, Map<String, FeedStore.StoredAlarm> stored, long nowMillis) {
        if (feed.rev <= lastAppliedRev) {
            throw new IllegalStateException("stale or replayed feed rev " + feed.rev + " <= " + lastAppliedRev);
        }
        Plan p = new Plan();
        for (Alarm a : feed.alarms) {
            FeedStore.StoredAlarm prev = stored.get(a.id);
            if (prev != null && ("fired".equals(prev.getStatus()) || "missed".equals(prev.getStatus()) || "dismissed".equals(prev.getStatus()))) {
                continue; // terminal state, never re-ring
            }
            if (a.atMillis > nowMillis) {
                p.toSchedule.add(a);
            } else {
                p.toRingNow.add(a); // late alarms ring, however late
            }
        }
        java.util.Set<String> inFeed = new java.util.HashSet<>();
        for (Alarm a : feed.alarms) inFeed.add(a.id);
        for (Map.Entry<String, FeedStore.StoredAlarm> e : stored.entrySet()) {
            if (!e.getValue().getLocal() && !inFeed.contains(e.getKey())
                    && "scheduled".equals(e.getValue().getStatus())) {
                p.toCancel.add(e.getKey());
            }
        }
        return p;
    }
}
