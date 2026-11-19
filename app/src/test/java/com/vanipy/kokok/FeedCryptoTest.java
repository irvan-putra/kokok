package com.vanipy.kokok;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Cross-implementation vector: the envelope fixture was produced by tools/feed_write.py (Python Tink). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FeedCryptoTest {
    private static String fixture(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/test/resources/" + name)), StandardCharsets.UTF_8);
    }

    @Test
    public void opensPythonProducedEnvelope() throws Exception {
        String out = FeedCrypto.openEnvelope(fixture("envelope.json"), fixture("sign-pub.json"),
                FeedCrypto.readJsonKeyset(fixture("app-priv.json")));
        FeedModel.Feed f = FeedModel.parse(out);
        assertEquals(1, f.rev);
        assertEquals(1, f.alarms.size());
        assertEquals("t-0001", f.alarms.get(0).id);
        assertEquals(1763598600000L, f.alarms.get(0).atMillis);
    }

    @Test
    public void rejectsTamperedCiphertext() throws Exception {
        String env = fixture("envelope.json");
        // flip a byte inside the base64 ciphertext payload
        int i = env.indexOf("\"enc\":\"") + 7;
        char[] chars = env.toCharArray();
        chars[i + 10] = chars[i + 10] == 'A' ? 'B' : 'A';
        String bad = new String(chars);
        try {
            FeedCrypto.openEnvelope(bad, fixture("sign-pub.json"),
                    FeedCrypto.readJsonKeyset(fixture("app-priv.json")));
            fail("tampered envelope must not open");
        } catch (GeneralSecurityException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void rejectsBadSignature() throws Exception {
        String env = fixture("envelope.json");
        int i = env.indexOf("\"sig\":\"") + 7;
        char[] chars = env.toCharArray();
        chars[i + 20] = chars[i + 20] == 'A' ? 'C' : 'A';
        String bad = new String(chars);
        try {
            FeedCrypto.openEnvelope(bad, fixture("sign-pub.json"),
                    FeedCrypto.readJsonKeyset(fixture("app-priv.json")));
            fail("bad signature must not open");
        } catch (GeneralSecurityException expected) {
            assertTrue(true);
        }
    }
}
