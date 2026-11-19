package com.vanipy.kokok;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PairingConfigTest {
    private String fixture(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/test/resources/" + name)), StandardCharsets.UTF_8);
    }
    private String config(String url, String key) throws Exception {
        return new JSONObject().put("feedUrl", url).put("verifyKeyset", new JSONObject(key)).toString();
    }
    @Test public void acceptsPublicKeyAndHttps() throws Exception {
        String pub = fixture("sign-pub.json");
        PairingConfig c = PairingConfig.parse(config("https://example.org/feed.json", pub));
        assertEquals("https://example.org/feed.json", c.feedUrl);
        assertEquals(String.valueOf(new JSONObject(pub).getLong("primaryKeyId")), c.publisherKeyId);
    }
    @Test public void refusesUnsafeUrlsWithoutChangingState() throws Exception {
        String pub = fixture("sign-pub.json");
        for (String url : new String[]{"http://example.org/f", "https:///missing", "https://user:pass@example.org/f", "https://example.org/f#fragment", "file:///tmp/f"}) {
            try { PairingConfig.parse(config(url, pub)); fail(url); }
            catch (Exception expected) { assertNotNull(expected.getMessage()); }
        }
    }
    @Test public void rejectsPrivateOrWrongPrimitiveKeys() throws Exception {
        for (String file : new String[]{"app-priv.json", "app-pub.json"}) {
            try { PairingConfig.parse(config("https://example.org/f", fixture(file))); fail(file); }
            catch (Exception expected) { assertNotNull(expected); }
        }
    }
    @Test public void rejectsDisabledPrimary() throws Exception {
        JSONObject pub = new JSONObject(fixture("sign-pub.json"));
        pub.getJSONArray("key").getJSONObject(0).put("status", "DISABLED");
        try { PairingConfig.parse(config("https://example.org/f", pub.toString())); fail(); }
        catch (Exception expected) { assertTrue(expected.getMessage().contains("primary")); }
    }
}
