package io.github.xororz.localdream.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Encrypts native-inference evidence in app-private storage with a
 * non-exportable Android Keystore key. The GCM authenticated data binds each
 * record to its model id, so copying a sealed file to another model cannot
 * create target-runtime evidence for that model.
 */
object NativeRuntimeAttestationStore {
    private const val DIRECTORY = "runtime-attestations"
    private const val KEY_ALIAS = "vision_dream_runtime_attestation_v1"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun read(context: Context, modelId: String): NativeRuntimeAttestation? = runCatching {
        val encrypted = recordFile(context, modelId).takeIf(File::isFile)?.readText()?.trim()
            ?.takeIf(String::isNotEmpty) ?: return null
        decode(decrypt(encrypted, modelId))
    }.getOrNull()

    fun write(context: Context, modelId: String, attestation: NativeRuntimeAttestation) {
        val target = recordFile(context, modelId)
        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
            "Could not create runtime attestation directory"
        }
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(encrypt(encode(attestation), modelId))
        try {
            if (!temporary.renameTo(target)) {
                throw IllegalStateException("Could not atomically persist runtime attestation")
            }
        } finally {
            temporary.delete()
        }
    }

    internal fun recordFile(context: Context, modelId: String): File = File(
        File(context.applicationContext.filesDir, DIRECTORY),
        "${sha256(modelId)}.evidence",
    )

    private fun encode(value: NativeRuntimeAttestation): String = JSONObject().apply {
        put("deviceModel", value.deviceModel)
        put("soc", value.soc)
        put("qairtVersion", value.qairtVersion)
        put("abi", value.abi)
        put("htpTarget", value.htpTarget)
        put("contextFingerprint", value.contextFingerprint)
        put("observedAtEpochMillis", value.observedAtEpochMillis)
        put(
            "loadedLibraryFingerprints",
            JSONObject().apply {
                value.loadedLibraryFingerprints.toSortedMap().forEach { (name, digest) -> put(name, digest) }
            },
        )
    }.toString()

    private fun decode(raw: String): NativeRuntimeAttestation? {
        val json = JSONObject(raw)
        val fingerprints = json.optJSONObject("loadedLibraryFingerprints") ?: return null
        val values = buildMap {
            fingerprints.keys().forEach { name ->
                val digest = fingerprints.optString(name).lowercase()
                if (name.isNotBlank() && digest.matches(Regex("[0-9a-f]{64}"))) put(name, digest)
            }
        }
        val deviceModel = json.optString("deviceModel").trim()
        val soc = json.optString("soc").trim()
        val qairtVersion = json.optString("qairtVersion").trim()
        val abi = json.optString("abi").trim()
        val htpTarget = json.optString("htpTarget").trim()
        val contextFingerprint = json.optString("contextFingerprint").trim().lowercase()
        val observedAtEpochMillis = json.optLong("observedAtEpochMillis", -1L)
        if (
            listOf(deviceModel, soc, qairtVersion, abi, htpTarget).any(String::isEmpty) ||
            !contextFingerprint.matches(Regex("[0-9a-f]{64}")) || values.isEmpty() || observedAtEpochMillis <= 0L
        ) {
            return null
        }
        return NativeRuntimeAttestation(
            deviceModel,
            soc,
            qairtVersion,
            abi,
            htpTarget,
            contextFingerprint,
            values,
            observedAtEpochMillis,
        )
    }

    private fun encrypt(plainText: String, modelId: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(modelId.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String, modelId: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > GCM_IV_BYTES) { "Invalid runtime attestation" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES))
        cipher.updateAAD(modelId.toByteArray(StandardCharsets.UTF_8))
        return cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES).toString(StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
