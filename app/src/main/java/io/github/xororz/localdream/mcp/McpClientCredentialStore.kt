package io.github.xororz.localdream.mcp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores only encrypted bearer material in preferences. The encryption key is
 * non-exportable Android Keystore state; callers can reveal a token only at
 * provisioning time and transport authorization compares it in memory.
 */
class McpClientCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    fun provision(
        clientId: String,
        transport: McpTransport,
        scopes: Set<String>,
    ): ProvisionedCredential {
        require(CLIENT_ID.matches(clientId)) { "Invalid MCP client id" }
        require(scopes.isNotEmpty()) { "MCP client must receive at least one scope" }
        val token = newToken()
        val generation = preferences.getLong(generationKey(clientId), 0L) + 1L
        val encrypted = encrypt(token)
        preferences.edit()
            .putString(tokenKey(clientId), encrypted)
            .putLong(generationKey(clientId), generation)
            .putString(transportKey(clientId), transport.name)
            .putString(scopesKey(clientId), scopes.sorted().joinToString(" "))
            .putStringSet(CLIENTS, storedClientIds() + clientId)
            .apply()
        return ProvisionedCredential(clientId, token, generation, scopes, transport)
    }

    /** Metadata only: tokens remain unreadable unless a new credential is issued. */
    fun grants(): List<GrantSummary> = storedClientIds().mapNotNull { clientId ->
        val transport = preferences.getString(transportKey(clientId), null)
            ?.let { value -> McpTransport.entries.firstOrNull { it.name == value } }
            ?: return@mapNotNull null
        val generation = preferences.getLong(generationKey(clientId), 0L)
        if (generation <= 0L || preferences.getString(tokenKey(clientId), null) == null) return@mapNotNull null
        GrantSummary(
            clientId = clientId,
            generation = generation,
            scopes = (preferences.getString(scopesKey(clientId), "") ?: "")
                .split(' ').filter(String::isNotBlank).toSet(),
            transport = transport,
        )
    }.sortedBy(GrantSummary::clientId)

    fun revoke(clientId: String) {
        if (!CLIENT_ID.matches(clientId)) return
        preferences.edit()
            .remove(tokenKey(clientId))
            .remove(transportKey(clientId))
            .remove(scopesKey(clientId))
            .putLong(generationKey(clientId), preferences.getLong(generationKey(clientId), 0L) + 1L)
            .putStringSet(CLIENTS, storedClientIds() - clientId)
            .apply()
    }

    fun authenticate(bearerToken: String?, transport: McpTransport): McpAuthenticatedClient? {
        val token = bearerToken?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (clientId in preferences.getStringSet(CLIENTS, emptySet()).orEmpty() + storedClientIds()) {
            val storedTransport = preferences.getString(transportKey(clientId), null) ?: continue
            if (storedTransport != transport.name) continue
            val encrypted = preferences.getString(tokenKey(clientId), null) ?: continue
            val expected = runCatching { decrypt(encrypted) }.getOrNull() ?: continue
            if (constantTimeEquals(expected, token)) {
                return McpAuthenticatedClient(
                    clientId = clientId,
                    tokenGeneration = preferences.getLong(generationKey(clientId), 0L),
                    scopes = (preferences.getString(scopesKey(clientId), "") ?: "")
                        .split(' ').filter(String::isNotBlank).toSet(),
                    transport = transport,
                )
            }
        }
        return null
    }

    private fun storedClientIds(): Set<String> {
        val ids = preferences.all.keys.filter { it.startsWith(TOKEN_PREFIX) }
            .map { it.removePrefix(TOKEN_PREFIX) }.toSet()
        preferences.edit().putStringSet(CLIENTS, ids).apply()
        return ids
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > GCM_IV_BYTES) { "Invalid encrypted credential" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES))
        return cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES)
            .toString(StandardCharsets.UTF_8)
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

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
        val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
        var difference = leftBytes.size xor rightBytes.size
        for (index in leftBytes.indices) difference = difference or (leftBytes[index].toInt() xor rightBytes.getOrElse(index) { 0 }.toInt())
        return difference == 0
    }

    private fun tokenKey(clientId: String) = "$TOKEN_PREFIX$clientId"
    private fun generationKey(clientId: String) = "generation.$clientId"
    private fun transportKey(clientId: String) = "transport.$clientId"
    private fun scopesKey(clientId: String) = "scopes.$clientId"

    data class ProvisionedCredential(val clientId: String, val token: String, val generation: Long, val scopes: Set<String>, val transport: McpTransport)

    data class GrantSummary(
        val clientId: String,
        val generation: Long,
        val scopes: Set<String>,
        val transport: McpTransport,
    )

    private companion object {
        const val PREFERENCES = "mcp_credentials"
        const val CLIENTS = "clients"
        const val TOKEN_PREFIX = "token."
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "vision_dream_mcp_credentials"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        val CLIENT_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
    }
}
