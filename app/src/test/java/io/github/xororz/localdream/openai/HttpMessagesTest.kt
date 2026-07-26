package io.github.xororz.localdream.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpMessagesTest {
    @Test
    fun requestAndResponseHeadersAreCaseInsensitive() {
        val request = HttpRequest(
            method = "POST",
            path = "/v1/images/generations",
            headers = mapOf("Authorization" to "Bearer token"),
            body = "request".encodeToByteArray(),
        )
        val response = HttpResponse.json(
            statusCode = 200,
            json = """{"ok":true}""",
            headers = mapOf("X-Request-Id" to "request-1"),
        )

        assertEquals("Bearer token", request.header("authorization"))
        assertEquals("request", request.bodyAsUtf8())
        assertNull(request.header("content-type"))
        assertEquals("application/json; charset=utf-8", response.header("content-type"))
        assertEquals("request-1", response.header("x-request-id"))
        assertEquals("""{"ok":true}""", response.bodyAsUtf8())
    }

    @Test
    fun binaryResponsePreservesImageBytesAndContentType() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

        val response = HttpResponse.binary(
            statusCode = 200,
            body = bytes,
            contentType = "image/png",
            headers = mapOf("X-Request-Id" to "request-2"),
        )

        assertEquals("image/png", response.header("content-type"))
        assertEquals("no-store", response.header("cache-control"))
        assertEquals("inline", response.header("content-disposition"))
        assertEquals("request-2", response.header("x-request-id"))
        assertEquals(bytes.toList(), response.body.toList())
    }
}
