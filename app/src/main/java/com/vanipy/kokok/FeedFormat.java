package com.vanipy.kokok;

/** Shared wire-format constants for the signed, encrypted alarm feed. */
public final class FeedFormat {
    private FeedFormat() {}

    /** HKDF context info binding envelopes to this app + format version. */
    public static final byte[] CONTEXT_INFO = "kokok-feed-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    public static final int ENVELOPE_VERSION = 1;
    public static final int FEED_VERSION = 1;
}
