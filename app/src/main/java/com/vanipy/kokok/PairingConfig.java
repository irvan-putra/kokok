package com.vanipy.kokok;

import com.google.crypto.tink.PublicKeyVerify;
import java.net.URI;
import org.json.JSONArray;
import org.json.JSONObject;

/** Non-secret provisioning data. Parsing never modifies installed pairing or alarm state. */
public final class PairingConfig {
    public final String feedUrl;
    public final String verifyKeyset;
    public final String publisherKeyId;

    private PairingConfig(String url, String keyset, String kid) {
        feedUrl = url;
        verifyKeyset = keyset;
        publisherKeyId = kid;
    }

    public static String publicKeyId(String keysetJson) throws Exception {
        JSONObject keyset = new JSONObject(keysetJson);
        String primary = String.valueOf(keyset.getLong("primaryKeyId"));
        JSONArray keys = keyset.getJSONArray("key");
        boolean enabledPrimary = false;
        for (int i = 0; i < keys.length(); i++) {
            JSONObject key = keys.getJSONObject(i);
            if (!"ASYMMETRIC_PUBLIC".equals(key.getJSONObject("keyData").getString("keyMaterialType"))) {
                throw new IllegalArgumentException("Pairing requires a public verification keyset, never a private key");
            }
            if (primary.equals(String.valueOf(key.getLong("keyId"))) && "ENABLED".equals(key.getString("status"))) {
                enabledPrimary = true;
            }
        }
        if (!enabledPrimary) throw new IllegalArgumentException("No enabled primary verification key");
        FeedCrypto.readJsonKeyset(keysetJson).getPrimitive(PublicKeyVerify.class);
        return primary;
    }

    public static PairingConfig parse(String text) throws Exception {
        if (text.length() > 65536) throw new IllegalArgumentException("Pairing configuration is too large");
        JSONObject obj = new JSONObject(text);
        String url = obj.getString("feedUrl").trim();
        URI uri = new URI(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Feed URL must be HTTPS with a host, no user info or fragment");
        }
        String keyset = obj.getJSONObject("verifyKeyset").toString();
        return new PairingConfig(url, keyset, publicKeyId(keyset));
    }
}
