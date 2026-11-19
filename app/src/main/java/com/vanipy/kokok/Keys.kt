package com.vanipy.kokok

import android.content.Context
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HybridKeyTemplates
import com.google.crypto.tink.proto.AesGcmKeyFormat
import com.google.crypto.tink.proto.EllipticCurveType
import com.google.crypto.tink.proto.EcPointFormat
import com.google.crypto.tink.proto.HashType
import com.google.crypto.tink.proto.KeyTemplate
import com.google.crypto.tink.proto.OutputPrefixType
import com.vanipy.kokok.FeedStore.appKeyset
import java.io.ByteArrayOutputStream

/**
 * The phone's feed-decryption keypair, generated on pairing.
 * Private keyset lives only on this device (device-protected storage);
 * only the public keyset JSON is shown for the pairing step.
 */
object Keys {

    /** ECIES_P256_COMPRESSED_HKDF_HMAC_SHA256_AES128_GCM with feed context info.
     *  Must stay byte-identical with tools/feed_write.py (cross-verified by tests). */
    fun hybridTemplate(): KeyTemplate {
        val dem = KeyTemplate.newBuilder()
            .setTypeUrl("type.googleapis.com/google.crypto.tink.AesGcmKey")
            .setValue(AesGcmKeyFormat.newBuilder().setKeySize(16).build().toByteString())
            .setOutputPrefixType(OutputPrefixType.TINK)
            .build()
        return HybridKeyTemplates.createEciesAeadHkdfKeyTemplate(
            EllipticCurveType.NIST_P256,
            HashType.SHA256,
            EcPointFormat.COMPRESSED,
            dem,
            OutputPrefixType.TINK,
            ByteArray(0),
        )
    }

    @Synchronized
    fun handle(context: Context): KeysetHandle {
        // Key export can be the first crypto action in a fresh app process.
        // Do not depend on FeedCrypto's unrelated static initializer.
        com.google.crypto.tink.hybrid.HybridConfig.register()
        val stored = context.appKeyset
        if (stored != null) {
            return CleartextKeysetHandle.read(JsonKeysetReader.withString(stored))
        }
        val fresh = KeysetHandle.generateNew(hybridTemplate())
        val out = ByteArrayOutputStream()
        CleartextKeysetHandle.write(fresh, JsonKeysetWriter.withOutputStream(out))
        context.appKeyset = out.toString("UTF-8")
        return fresh
    }

    fun publicKeysetJson(context: Context): String {
        val pub = handle(context).publicKeysetHandle
        val out = ByteArrayOutputStream()
        CleartextKeysetHandle.write(pub, JsonKeysetWriter.withOutputStream(out))
        return out.toString("UTF-8")
    }
}
