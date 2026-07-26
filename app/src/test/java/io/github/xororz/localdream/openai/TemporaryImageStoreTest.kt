package io.github.xororz.localdream.openai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TemporaryImageStoreTest {
    @Test
    fun registeredImageCanBeReadUntilItExpires() {
        var now = 1_000L
        val store = TemporaryImageStore(
            ttlMillis = 500L,
            maxEntries = 2,
            maxBytes = 10L,
            nowMillis = { now },
            tokenFactory = { "0123456789abcdef0123456789abcdef" },
        )

        val token = store.register(
            GeneratedImage(byteArrayOf(1, 2, 3), "image/png", seed = null),
            requestId = "request-1",
        )

        val stored = requireNotNull(store.get(token))
        assertArrayEquals(byteArrayOf(1, 2, 3), stored.bytes)
        assertEquals("image/png", stored.mimeType)
        assertEquals("request-1", stored.requestId)

        now += 500L
        assertNull(store.get(token))
    }

    @Test
    fun oldestImageIsEvictedWhenCapacityIsReached() {
        val tokens = ArrayDeque(
            listOf(
                "00000000000000000000000000000000",
                "11111111111111111111111111111111",
                "22222222222222222222222222222222",
            ),
        )
        val store = TemporaryImageStore(
            maxEntries = 2,
            maxBytes = 10L,
            tokenFactory = { tokens.removeFirst() },
        )

        val first = store.register(
            GeneratedImage(byteArrayOf(1), "image/png", seed = null),
            requestId = "request-1",
        )
        val second = store.register(
            GeneratedImage(byteArrayOf(2), "image/png", seed = null),
            requestId = "request-2",
        )
        val third = store.register(
            GeneratedImage(byteArrayOf(3), "image/png", seed = null),
            requestId = "request-3",
        )

        assertNull(store.get(first))
        assertEquals(2, requireNotNull(store.get(second)).bytes.single().toInt())
        assertEquals(3, requireNotNull(store.get(third)).bytes.single().toInt())
    }

    @Test
    fun downloadPathAndHostAreValidated() {
        val token = "0123456789abcdef0123456789abcdef"
        val path = TemporaryImageStore.DOWNLOAD_PATH_PREFIX + token

        assertEquals(token, TemporaryImageStore.tokenFromPath(path))
        assertNull(TemporaryImageStore.tokenFromPath("$path/extra"))
        assertEquals(
            "http://192.168.1.8:8809$path",
            TemporaryImageStore.downloadUrl("192.168.1.8:8809", token, 8809),
        )
        assertEquals(
            "http://127.0.0.1:8809$path",
            TemporaryImageStore.downloadUrl("bad/host", token, 8809),
        )
    }
}
