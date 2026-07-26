package io.github.xororz.localdream.openai

import java.util.LinkedHashMap
import java.util.UUID

/**
 * Bounded in-memory registry for short-lived image download URLs.
 *
 * Download tokens are bearer capabilities: callers do not need to attach the
 * API key when an image widget follows a URL returned by the generation API.
 * Entries expire quickly and are also evicted by count and total byte size.
 */
internal class TemporaryImageStore(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val tokenFactory: () -> String = {
        UUID.randomUUID().toString().replace("-", "")
    },
) {
    data class Entry(
        val bytes: ByteArray,
        val mimeType: String,
        val requestId: String,
        val expiresAtMillis: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var storedBytes = 0L

    init {
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxBytes > 0L) { "maxBytes must be positive" }
    }

    @Synchronized
    fun register(
        image: GeneratedImage,
        requestId: String,
    ): String {
        require(image.bytes.isNotEmpty()) { "image bytes must not be empty" }
        require(image.bytes.size <= maxBytes) { "image exceeds temporary store capacity" }
        purgeExpired()
        while (entries.size >= maxEntries || storedBytes + image.bytes.size > maxBytes) {
            evictOldest()
        }

        val token = generateUniqueToken()
        val bytes = image.bytes.copyOf()
        entries[token] = Entry(
            bytes = bytes,
            mimeType = image.mimeType,
            requestId = requestId,
            expiresAtMillis = nowMillis() + ttlMillis,
        )
        storedBytes += bytes.size
        return token
    }

    @Synchronized
    fun get(token: String): Entry? {
        if (!TOKEN_PATTERN.matches(token)) return null
        purgeExpired()
        return entries[token]
    }

    @Synchronized
    fun clear() {
        entries.clear()
        storedBytes = 0L
    }

    private fun generateUniqueToken(): String {
        repeat(MAX_TOKEN_ATTEMPTS) {
            val token = tokenFactory()
            require(TOKEN_PATTERN.matches(token)) {
                "tokenFactory must return a 32-character lowercase hex token"
            }
            if (!entries.containsKey(token)) return token
        }
        error("Could not generate a unique image token")
    }

    private fun purgeExpired() {
        val now = nowMillis()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.expiresAtMillis <= now) {
                storedBytes -= entry.bytes.size
                iterator.remove()
            }
        }
    }

    private fun evictOldest() {
        val iterator = entries.iterator()
        check(iterator.hasNext()) { "temporary image store cannot evict an empty registry" }
        val entry = iterator.next().value
        storedBytes -= entry.bytes.size
        iterator.remove()
    }

    companion object {
        const val DOWNLOAD_PATH_PREFIX = "/v1/images/files/"
        const val DEFAULT_TTL_SECONDS = 10 * 60L

        private const val DEFAULT_TTL_MILLIS = DEFAULT_TTL_SECONDS * 1000L
        private const val DEFAULT_MAX_ENTRIES = 12
        private const val DEFAULT_MAX_BYTES = 64L * 1024L * 1024L
        private const val MAX_TOKEN_ATTEMPTS = 8
        private val TOKEN_PATTERN = Regex("[0-9a-f]{32}")
        private val HOST_PATTERN = Regex(
            """(?:\[[0-9A-Fa-f:.]+]|[A-Za-z0-9.-]+)(?::[0-9]{1,5})?""",
        )

        fun tokenFromPath(path: String): String? {
            if (!path.startsWith(DOWNLOAD_PATH_PREFIX)) return null
            return path.removePrefix(DOWNLOAD_PATH_PREFIX)
                .takeIf(TOKEN_PATTERN::matches)
        }

        fun downloadUrl(
            hostHeader: String?,
            token: String,
            fallbackPort: Int,
        ): String {
            require(TOKEN_PATTERN.matches(token)) { "invalid image token" }
            val fallback = "127.0.0.1:$fallbackPort"
            val host = hostHeader
                ?.trim()
                ?.takeIf(HOST_PATTERN::matches)
                ?: fallback
            return "http://$host$DOWNLOAD_PATH_PREFIX$token"
        }
    }
}
