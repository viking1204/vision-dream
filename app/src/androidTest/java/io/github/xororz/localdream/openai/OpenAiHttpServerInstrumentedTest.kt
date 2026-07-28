package io.github.xororz.localdream.openai

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiHttpServerInstrumentedTest {
    @Test
    fun serverAcceptsIpv4LoopbackConnections() {
        val port = availableIpv4Port()
        val server = OpenAiHttpServer(
            port = port,
            isAuthorized = { _, _, authorization -> authorization == "Bearer device-test" },
            handler = {
                HttpResponse.json(200, """{"status":"ok"}""")
            },
        )

        try {
            server.start()
            val response = Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(
                        InetAddress.getByName(IPV4_LOOPBACK_ADDRESS),
                        port,
                    ),
                    SOCKET_TIMEOUT_MS,
                )
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.getOutputStream().write(
                    (
                        "GET /health HTTP/1.1\r\n" +
                            "Host: 127.0.0.1\r\n" +
                            "Authorization: Bearer device-test\r\n" +
                            "\r\n"
                        ).toByteArray(StandardCharsets.ISO_8859_1),
                )
                socket.getInputStream().bufferedReader().readText()
            }

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("""{"status":"ok"}"""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun serverAcceptsBoundedChunkedRequestBodies() {
        val port = availableIpv4Port()
        val server = OpenAiHttpServer(
            port = port,
            isAuthorized = { _, _, authorization -> authorization == "Bearer device-test" },
            handler = { request ->
                HttpResponse.json(200, request.bodyAsUtf8())
            },
        )
        val chunks = listOf("""{"prompt":""", """"hello"}""")
        val request = buildString {
            append("POST /v1/images/generations HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            append("Authorization: Bearer device-test\r\n")
            append("Content-Type: application/json\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("\r\n")
            chunks.forEach { chunk ->
                append(chunk.toByteArray(StandardCharsets.UTF_8).size.toString(16))
                append("\r\n")
                append(chunk)
                append("\r\n")
            }
            append("0\r\n\r\n")
        }

        try {
            server.start()
            val response = Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(
                        InetAddress.getByName(IPV4_LOOPBACK_ADDRESS),
                        port,
                    ),
                    SOCKET_TIMEOUT_MS,
                )
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.getOutputStream().write(
                    request.toByteArray(StandardCharsets.ISO_8859_1),
                )
                socket.getInputStream().bufferedReader().readText()
            }

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("""{"prompt":"hello"}"""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun serverAcknowledgesExpectContinueBeforeReadingBody() {
        val port = availableIpv4Port()
        val receivedBody = AtomicReference<String>()
        val server = OpenAiHttpServer(
            port = port,
            isAuthorized = { _, _, authorization -> authorization == "Bearer device-test" },
            handler = { request ->
                receivedBody.set(request.bodyAsUtf8())
                HttpResponse.json(200, """{"status":"ok"}""")
            },
        )
        val body = """{"prompt":"hello"}"""
        val headers = buildString {
            append("POST /v1/images/generations HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            append("Authorization: Bearer device-test\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
            append("Expect: 100-continue\r\n")
            append("\r\n")
        }

        try {
            server.start()
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(
                        InetAddress.getByName(IPV4_LOOPBACK_ADDRESS),
                        port,
                    ),
                    SOCKET_TIMEOUT_MS,
                )
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val output = socket.getOutputStream()
                output.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
                output.flush()

                val input = socket.getInputStream()
                val interimResponse = readHeaderBlock(input)
                assertEquals("HTTP/1.1 100 Continue\r\n\r\n", interimResponse)

                output.write(body.toByteArray(StandardCharsets.UTF_8))
                output.flush()
                val finalResponse = input.readBytes().toString(StandardCharsets.UTF_8)
                assertTrue(finalResponse.startsWith("HTTP/1.1 200 OK"))
                assertEquals(body, receivedBody.get())
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun serverRejectsInvalidExpectContinueBeforeInterimResponse() {
        val port = availableIpv4Port()
        val handlerCalls = AtomicInteger()
        val server = OpenAiHttpServer(
            port = port,
            isAuthorized = { _, _, authorization -> authorization == "Bearer device-test" },
            handler = {
                handlerCalls.incrementAndGet()
                HttpResponse.json(200, """{"status":"unexpected"}""")
            },
        )
        val invalidHeaders = listOf(
            Pair(
                buildString {
                    append("POST /v1/images/generations HTTP/1.1\r\n")
                    append("Host: 127.0.0.1\r\n")
                    append("Authorization: Bearer device-test\r\n")
                    append("Content-Length: ${Long.MAX_VALUE}\r\n")
                    append("Expect: 100-continue\r\n")
                    append("\r\n")
                },
                "HTTP/1.1 413 Payload Too Large",
            ),
            Pair(
                buildString {
                    append("POST /v1/images/generations HTTP/1.1\r\n")
                    append("Host: 127.0.0.1\r\n")
                    append("Authorization: Bearer device-test\r\n")
                    append("Content-Length: 1\r\n")
                    append("Transfer-Encoding: chunked\r\n")
                    append("Expect: 100-continue\r\n")
                    append("\r\n")
                },
                "HTTP/1.1 400 Bad Request",
            ),
        )

        try {
            server.start()
            invalidHeaders.forEach { (headers, expectedStatus) ->
                val response = Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(
                            InetAddress.getByName(IPV4_LOOPBACK_ADDRESS),
                            port,
                        ),
                        SOCKET_TIMEOUT_MS,
                    )
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    socket.getOutputStream().write(
                        headers.toByteArray(StandardCharsets.ISO_8859_1),
                    )
                    socket.getInputStream().bufferedReader().readText()
                }
                assertTrue(response.startsWith(expectedStatus))
                assertFalse(response.startsWith("HTTP/1.1 100 Continue"))
            }
            assertEquals(0, handlerCalls.get())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun temporaryImageCapabilityPathCanBypassBearerHeader() {
        val port = availableIpv4Port()
        val token = "0123456789abcdef0123456789abcdef"
        val downloadPath = TemporaryImageStore.DOWNLOAD_PATH_PREFIX + token
        val server = OpenAiHttpServer(
            port = port,
            isAuthorized = { method, path, authorization ->
                authorization == "Bearer device-test" ||
                    (method == "GET" && TemporaryImageStore.tokenFromPath(path) != null)
            },
            handler = { request ->
                if (request.path == downloadPath) {
                    HttpResponse.binary(200, byteArrayOf(1, 2, 3), "image/png")
                } else {
                    HttpResponse.json(200, """{"status":"unexpected"}""")
                }
            },
        )

        try {
            server.start()
            val downloadResponse = Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(InetAddress.getByName(IPV4_LOOPBACK_ADDRESS), port),
                    SOCKET_TIMEOUT_MS,
                )
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.getOutputStream().write(
                    (
                        "GET $downloadPath HTTP/1.1\r\n" +
                            "Host: 127.0.0.1\r\n" +
                            "\r\n"
                        ).toByteArray(StandardCharsets.ISO_8859_1),
                )
                socket.getInputStream().readBytes()
            }
            assertTrue(
                downloadResponse.toString(StandardCharsets.ISO_8859_1)
                    .startsWith("HTTP/1.1 200 OK"),
            )

            val unauthorizedResponse = Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(InetAddress.getByName(IPV4_LOOPBACK_ADDRESS), port),
                    SOCKET_TIMEOUT_MS,
                )
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.getOutputStream().write(
                    (
                        "GET /health HTTP/1.1\r\n" +
                            "Host: 127.0.0.1\r\n" +
                            "\r\n"
                        ).toByteArray(StandardCharsets.ISO_8859_1),
                )
                socket.getInputStream().bufferedReader().readText()
            }
            assertTrue(unauthorizedResponse.startsWith("HTTP/1.1 401 Unauthorized"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun absoluteReadDeadlineCoversHeadersChunkBodyAndTrailers() {
        val port = availableIpv4Port()
        val handlerCalls = AtomicInteger()
        val server = OpenAiHttpServer(
            port = port,
            isAuthorized = { _, _, authorization -> authorization == "Bearer device-test" },
            handler = {
                handlerCalls.incrementAndGet()
                HttpResponse.json(200, """{"status":"unexpected"}""")
            },
            requestReadTimeoutMillis = REQUEST_DEADLINE_MS,
        )
        val partialRequests = listOf(
            buildString {
                append("GET /health HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Authorization: Bearer device-test\r\n")
                append("X-Pending: ")
            },
            buildString {
                append("POST /v1/images/generations HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Authorization: Bearer device-test\r\n")
                append("Transfer-Encoding: chunked\r\n")
                append("\r\n")
                append("20\r\n")
            },
            buildString {
                append("POST /v1/images/generations HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Authorization: Bearer device-test\r\n")
                append("Transfer-Encoding: chunked\r\n")
                append("\r\n")
                append("1\r\na\r\n0\r\nX-Pending: ")
            },
        )

        try {
            server.start()
            partialRequests.forEach { requestPrefix ->
                val response = readResponseWhileTrickling(port, requestPrefix)
                assertTrue(response.startsWith("HTTP/1.1 408 Request Timeout"))
                assertTrue(response.contains(""""code":"request_timeout""""))
                assertFalse(response.contains("device-test"))
            }
            assertEquals(0, handlerCalls.get())
        } finally {
            server.shutdown()
        }
    }

    private fun readResponseWhileTrickling(
        port: Int,
        requestPrefix: String,
    ): String = Socket().use { socket ->
        socket.connect(
            InetSocketAddress(
                InetAddress.getByName(IPV4_LOOPBACK_ADDRESS),
                port,
            ),
            SOCKET_TIMEOUT_MS,
        )
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val output = socket.getOutputStream()
        output.write(requestPrefix.toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()

        val successfulWrites = AtomicInteger()
        val trickleProof = CountDownLatch(REQUIRED_TRICKLE_WRITES)
        val trickleWriter = Thread(
            {
                try {
                    // Keep sending data before the deadline, then stop. On
                    // Android, continuing to write after the server closes an
                    // incomplete request can make TCP discard the otherwise
                    // valid 408 response with an RST. Three writes prove that
                    // the deadline is absolute without introducing that
                    // transport-level race into the assertion.
                    repeat(REQUIRED_TRICKLE_WRITES) {
                        output.write('a'.code)
                        output.flush()
                        successfulWrites.incrementAndGet()
                        trickleProof.countDown()
                        Thread.sleep(TRICKLE_INTERVAL_MS)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: Exception) {
                    // The expected deadline closes the peer while this writer
                    // is still trying to prolong the request.
                }
            },
            "openai-http-deadline-trickle",
        ).apply { isDaemon = true }

        trickleWriter.start()
        try {
            assertTrue(
                "The test must prove traffic continued before the absolute deadline",
                trickleProof.await(TRICKLE_PROOF_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            assertTrue(successfulWrites.get() >= REQUIRED_TRICKLE_WRITES)
            socket.getInputStream().bufferedReader().readText()
        } finally {
            trickleWriter.interrupt()
            trickleWriter.join(TRICKLE_JOIN_TIMEOUT_MS)
        }
    }

    private fun availableIpv4Port(): Int = ServerSocket(
        0,
        1,
        InetAddress.getByName(IPV4_LOOPBACK_ADDRESS),
    ).use { it.localPort }

    private fun readHeaderBlock(input: InputStream): String {
        val delimiter = "\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
        val bytes = ByteArrayOutputStream()
        var matched = 0
        while (matched < delimiter.size) {
            val value = input.read()
            check(value >= 0) { "Connection closed before the HTTP header completed" }
            bytes.write(value)
            matched = when {
                value.toByte() == delimiter[matched] -> matched + 1
                value.toByte() == delimiter[0] -> 1
                else -> 0
            }
            check(bytes.size() <= MAX_TEST_HEADER_BYTES) { "HTTP test header exceeded limit" }
        }
        return bytes.toString(StandardCharsets.ISO_8859_1.name())
    }

    private companion object {
        const val IPV4_LOOPBACK_ADDRESS = "127.0.0.1"
        const val SOCKET_TIMEOUT_MS = 2_000
        const val REQUEST_DEADLINE_MS = 800
        const val TRICKLE_INTERVAL_MS = 100L
        const val TRICKLE_JOIN_TIMEOUT_MS = 500L
        const val REQUIRED_TRICKLE_WRITES = 3
        const val TRICKLE_PROOF_TIMEOUT_MS = 600L
        const val MAX_TEST_HEADER_BYTES = 8 * 1024
    }
}
