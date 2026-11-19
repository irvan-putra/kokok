package com.vanipy.kokok;

/** Shared wire-format constants for the signed, encrypted alarm feed. */
public final class FeedFormat {
    private FeedFormat() {}

    /** HKDF context info binding envelopes to this app + format version. */
    public static final byte[] CONTEXT_INFO = "kokok-feed-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** An alarm that fired while the phone was unreachable rings immediately if it is
     *  at most this late, otherwise it is marked missed. Deliberate deviation from
     *  AOSP DeskClock (notification only); product decision pending. */
    public static final long LATE_GRACE_MILLIS = 10 * 60 * 1000L;

    public static final int ENVELOPE_VERSION = 1;
    public static final int FEED_VERSION = 1;
}
