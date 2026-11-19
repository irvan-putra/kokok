package com.vanipy.kokok;

import com.google.crypto.tink.CleartextKeysetHandle;
import com.google.crypto.tink.HybridDecrypt;
import com.google.crypto.tink.JsonKeysetReader;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.PublicKeyVerify;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import org.json.JSONObject;

/**
 * Opens the signed, encrypted feed envelope written by tools/feed_write.py.
 * Tink on both ends, identical key templates:
 * hybrid ECIES_P256_COMPRESSED_HKDF_HMAC_SHA256_AES128_GCM, signature ECDSA_P256.
 * Any signature/decrypt failure drops the whole envelope (fail closed).
 */
public final class FeedCrypto {
    private FeedCrypto() {}

    static {
        try {
            com.google.crypto.tink.hybrid.HybridConfig.register();
            com.google.crypto.tink.signature.SignatureConfig.register();
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static KeysetHandle readJsonKeyset(String json) throws GeneralSecurityException, java.io.IOException {
        return CleartextKeysetHandle.read(JsonKeysetReader.withString(json));
    }

    /** Returns the decrypted+verified feed JSON string, or throws. */
    public static String openEnvelope(String envelopeJson, String verifyKeysetJson, KeysetHandle appPrivate)
            throws GeneralSecurityException, java.io.IOException, org.json.JSONException {
        JSONObject env = new JSONObject(envelopeJson);
        if (env.getInt("v") != FeedFormat.ENVELOPE_VERSION) {
            throw new GeneralSecurityException("unsupported envelope version");
        }
        String encB64 = env.getString("enc");
        byte[] sig = Base64.getDecoder().decode(env.getString("sig"));
        byte[] enc = Base64.getDecoder().decode(encB64);

        PublicKeyVerify verifier = readJsonKeyset(verifyKeysetJson).getPrimitive(PublicKeyVerify.class);
        try {
            verifier.verify(sig, encB64.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new GeneralSecurityException("feed signature invalid", e);
        }

        HybridDecrypt decrypt = appPrivate.getPrimitive(HybridDecrypt.class);
        byte[] plain = decrypt.decrypt(enc, FeedFormat.CONTEXT_INFO);
        return new String(plain, StandardCharsets.UTF_8);
    }
}
