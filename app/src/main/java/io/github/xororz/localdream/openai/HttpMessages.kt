package io.github.xororz.localdream.openai

import java.nio.charset.StandardCharsets

/**
 * Transport-neutral HTTP request data used by the OpenAI-compatible gateway.
 */
data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = byteArrayOf(),
) {
    init {
        require(method.isNotBlank()) { "method must not be blank" }
        require(path.startsWith('/')) { "path must start with '/'" }
    }

    fun header(name: String): String? = headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

    fun bodyAsUtf8(): String = body.toString(StandardCharsets.UTF_8)
}

/**
 * Transport-neutral HTTP response data used by the OpenAI-compatible gateway.
 */
data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = byteArrayOf(),
) {
    init {
        require(statusCode in 100..599) { "statusCode must be a valid HTTP status" }
    }

    fun header(name: String): String? = headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

    fun bodyAsUtf8(): String = body.toString(StandardCharsets.UTF_8)

    companion object {
        fun json(
            statusCode: Int,
            json: String,
            headers: Map<String, String> = emptyMap(),
        ): HttpResponse = HttpResponse(
            statusCode = statusCode,
            headers = headers + ("Content-Type" to "application/json; charset=utf-8"),
            body = json.toByteArray(StandardCharsets.UTF_8),
        )

        fun binary(
            statusCode: Int,
            body: ByteArray,
            contentType: String,
            headers: Map<String, String> = emptyMap(),
        ): HttpResponse = HttpResponse(
            statusCode = statusCode,
            headers = headers + mapOf(
                "Content-Type" to contentType,
                "Cache-Control" to "no-store",
                "Content-Disposition" to "inline",
            ),
            body = body,
        )
    }
}
