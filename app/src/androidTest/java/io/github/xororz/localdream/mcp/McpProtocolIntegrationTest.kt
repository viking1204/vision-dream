package io.github.xororz.localdream.mcp

import androidx.test.platform.app.InstrumentationRegistry
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 验证 MCP 独立 Streamable HTTP listener 的协议和网络边界。
 * 测试直接通过 TCP 访问真实 listener，而非绕过 transport 调用内部路由。
 */
class McpProtocolIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetCredentials() {
        context.deleteSharedPreferences(CREDENTIAL_PREFERENCES)
    }

    @After
    fun clearCredentials() {
        context.deleteSharedPreferences(CREDENTIAL_PREFERENCES)
    }

    @Test
    fun capabilityDiscoveryNeverAdvertisesOrDispatchesAnUnavailableGatewayTool() {
        val port = availableLoopbackPort()
        val credentials = McpClientCredentialStore(context)
        val grant = credentials.provision("capability-client", McpTransport.LOOPBACK, setOf("models.read"))
        val server = McpHttpServer(port, McpTransport.LOOPBACK, credentials)

        try {
            server.start()
            val sessionId = request(port, "POST", grant.token, body = initializeRequest(1)).headers.getValue("mcp-session-id")
            val listed = request(port, "POST", grant.token, sessionId, body = toolsListRequest(2))
            assertEquals(0, listed.json().getJSONObject("result").getJSONArray("tools").length())
            assertError(
                request(
                    port,
                    "POST",
                    grant.token,
                    sessionId,
                    body = toolCallRequest(3, "models.list", JSONObject()),
                ),
                200,
                "TOOL_NOT_ENABLED",
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun initializeSessionSseAndDeleteFollowStreamableHttpContract() {
        val port = availableLoopbackPort()
        val credentials = McpClientCredentialStore(context)
        val grant = credentials.provision("loopback-client", McpTransport.LOOPBACK, setOf("models.read"))
        val server = McpHttpServer(port, McpTransport.LOOPBACK, credentials)

        try {
            server.start()
            val uninitialized = request(port, "POST", grant.token, body = pingRequest(1))
            assertError(uninitialized, 200, "SESSION_EXPIRED")

            val initialized = request(port, "POST", grant.token, body = initializeRequest(2))
            assertEquals(200, initialized.status)
            assertEquals(McpProtocol.VERSION, initialized.json().getJSONObject("result").getString("protocolVersion"))
            val sessionId = initialized.headers.getValue("mcp-session-id")

            val sse = request(port, "GET", grant.token, sessionId = sessionId, stream = true)
            assertEquals(200, sse.status)
            assertTrue(sse.headers.getValue("content-type").startsWith("text/event-stream"))
            assertEquals("keep-alive", sse.headers.getValue("connection"))
            assertTrue(sse.body.contains("event: ready"))

            val ping = request(port, "POST", grant.token, sessionId, body = pingRequest(3))
            assertEquals(0, ping.json().getJSONObject("result").length())

            val deleted = request(port, "DELETE", grant.token, sessionId)
            assertEquals(204, deleted.status)
            assertTrue(deleted.body.isEmpty())
            assertError(request(port, "GET", grant.token, sessionId), 401, "SESSION_EXPIRED")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun listenerRejectsInvalidHostOriginAndPortConflictsAndKeepsTransportsIsolated() {
        val port = availableLoopbackPort()
        val credentials = McpClientCredentialStore(context)
        val loopbackGrant = credentials.provision("loopback-client", McpTransport.LOOPBACK, setOf("models.read"))
        val loopbackServer = McpHttpServer(port, McpTransport.LOOPBACK, credentials)

        try {
            loopbackServer.start()
            assertError(
                request(port, "POST", loopbackGrant.token, host = "untrusted.test", body = initializeRequest(1)),
                403,
                "ORIGIN_DENIED",
            )
            assertError(
                request(
                    port,
                    "POST",
                    loopbackGrant.token,
                    origin = "http://untrusted.test",
                    body = initializeRequest(2),
                ),
                403,
                "ORIGIN_DENIED",
            )

            val conflictingListener = McpHttpServer(port, McpTransport.LOOPBACK, credentials)
            try {
                conflictingListener.start()
                throw AssertionError("Expected second listener to fail binding the occupied port")
            } catch (_: BindException) {
                // The existing listener owns this port; MCP must not silently share it.
            } finally {
                conflictingListener.shutdown()
            }
        } finally {
            loopbackServer.shutdown()
        }

        val lanPort = availableLoopbackPort()
        val lanGrant = credentials.provision("lan-client", McpTransport.LAN, setOf("models.read"))
        val lanServer = McpHttpServer(
            port = lanPort,
            transport = McpTransport.LAN,
            credentialStore = credentials,
            allowedLanHosts = { setOf("trusted.test") },
        )
        try {
            lanServer.start()
            assertEquals(
                200,
                request(lanPort, "POST", lanGrant.token, host = "trusted.test", body = initializeRequest(3)).status,
            )
            assertError(
                request(lanPort, "POST", lanGrant.token, host = "127.0.0.1", body = initializeRequest(4)),
                403,
                "ORIGIN_DENIED",
            )
        } finally {
            lanServer.shutdown()
        }
    }

    @Test
    fun imageCapabilityDownloadsOnceAndNeverLeaksAcrossJobs() {
        val port = availableLoopbackPort()
        val credentials = McpClientCredentialStore(context)
        val grant = credentials.provision("image-client", McpTransport.LOOPBACK, setOf("jobs.read"))
        val capabilities = McpImageCapabilityStore()
        val capability = capabilities.create(
            clientId = grant.clientId,
            jobId = "job-1",
            transport = McpTransport.LOOPBACK,
            mimeType = "image/png",
            assetId = "asset-1",
        )
        val server = McpHttpServer(
            port = port,
            transport = McpTransport.LOOPBACK,
            credentialStore = credentials,
            imageCapabilities = capabilities,
            imageResolver = McpImageContentResolver { imageCapability ->
                if (imageCapability.assetId == "asset-1") {
                    McpImageContent("image-data".toByteArray(StandardCharsets.UTF_8), "image/png")
                } else {
                    null
                }
            },
        )

        try {
            server.start()
            assertError(
                request(
                    port = port,
                    method = "GET",
                    token = grant.token,
                    path = McpProtocol.imagePath("other-job", capability.token),
                ),
                404,
                "NOT_FOUND",
            )

            val firstRead = request(
                port = port,
                method = "GET",
                token = grant.token,
                path = McpProtocol.imagePath(capability.jobId, capability.token),
            )
            assertEquals(200, firstRead.status)
            assertEquals("image/png", firstRead.headers.getValue("content-type"))
            assertEquals("image-data", firstRead.body)

            assertError(
                request(
                    port = port,
                    method = "GET",
                    token = grant.token,
                    path = McpProtocol.imagePath(capability.jobId, capability.token),
                ),
                404,
                "NOT_FOUND",
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun authenticatedCallsArePersistedAsSanitizedAuditEvents() {
        val port = availableLoopbackPort()
        val credentials = McpClientCredentialStore(context)
        val grant = credentials.provision("audit-client", McpTransport.LOOPBACK, setOf("models.read"))
        val audit = RecordingMcpAuditSink()
        val server = McpHttpServer(
            port = port,
            transport = McpTransport.LOOPBACK,
            credentialStore = credentials,
            auditSink = audit,
        )

        try {
            server.start()
            val initialized = request(port, "POST", grant.token, body = initializeRequest(1))
            val sessionId = initialized.headers.getValue("mcp-session-id")
            assertEquals(200, request(port, "POST", grant.token, sessionId, body = pingRequest(2)).status)

            assertEquals(listOf("initialize", "ping"), audit.events.map(McpAuditEvent::method))
            assertTrue(audit.events.all { it.clientId == grant.clientId })
            assertTrue(audit.events.all { it.scopeSnapshot == "models.read" })
            assertTrue(audit.events.all { it.sessionHash?.contains(sessionId) != true })
            assertTrue(audit.events.all { it.outcomeCode == "OK" })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun toolsAreValidatedConfirmedAndAuditedBeforeReachingTheDomainGateway() {
        val port = availableLoopbackPort()
        val credentials = McpClientCredentialStore(context)
        val grant = credentials.provision("tool-client", McpTransport.LOOPBACK, setOf("jobs.write", "models.read"))
        val confirmations = McpConfirmationStore(idGenerator = { "confirmation-1" })
        val audit = RecordingMcpAuditSink()
        val calls = mutableListOf<McpToolInvocation>()
        val server = McpHttpServer(
            port = port,
            transport = McpTransport.LOOPBACK,
            credentialStore = credentials,
            confirmationStore = confirmations,
            auditSink = audit,
            toolGateway = McpToolGateway { _, invocation, _ ->
                calls += invocation
                McpToolGatewayResult.Completed(JSONObject().put("cancelled", true), jobId = "job-1")
            },
        )

        try {
            server.start()
            val sessionId = request(port, "POST", grant.token, body = initializeRequest(1)).headers.getValue("mcp-session-id")
            val listed = request(port, "POST", grant.token, sessionId, body = toolsListRequest(2))
            assertEquals(200, listed.status)
            assertTrue(listed.json().getJSONObject("result").getJSONArray("tools").length() > 0)

            assertError(
                request(port, "POST", grant.token, sessionId, body = toolCallRequest(3, "jobs.cancel", JSONObject().put("jobId", "job-1"))),
                200,
                "CONFIRMATION_REQUIRED",
            )
            assertTrue(calls.isEmpty())

            val pending = confirmations.uiRequests.value.single()
            assertEquals(grant.clientId, pending.clientId)
            assertEquals("jobs.cancel", pending.action)
            assertEquals(setOf("job-1"), pending.targetIds)
            assertEquals(
                "confirmation-1",
                confirmations.approveUiRequest(pending.id),
            )
            val completed = request(
                port,
                "POST",
                grant.token,
                sessionId,
                body = toolCallRequest(4, "jobs.cancel", JSONObject().put("jobId", "job-1"), "confirmation-1"),
            )
            assertEquals(true, completed.json().getJSONObject("result").getBoolean("cancelled"))
            assertEquals(listOf("jobs.cancel"), calls.map { it.definition.name })
            assertEquals("destructive", audit.events.last().risk)
            assertEquals("job-1", audit.events.last().jobId)
            assertTrue(audit.events.last().parameterDigest.isNotBlank())
        } finally {
            server.shutdown()
        }
    }

    private fun assertError(response: HttpResponse, expectedStatus: Int, expectedCode: String) {
        assertEquals(expectedStatus, response.status)
        assertEquals(expectedCode, response.json().getJSONObject("error").getJSONObject("data").getString("code"))
    }

    private fun request(
        port: Int,
        method: String,
        token: String? = null,
        sessionId: String? = null,
        origin: String? = null,
        host: String = LOOPBACK_ADDRESS,
        path: String = McpProtocol.PATH,
        body: String = "",
        stream: Boolean = false,
    ): HttpResponse = Socket().use { socket ->
        socket.connect(InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), port), SOCKET_TIMEOUT_MILLIS)
        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
        val request = buildString {
            append("$method $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            token?.let { append("Authorization: Bearer $it\r\n") }
            sessionId?.let { append("Mcp-Session-Id: $it\r\n") }
            origin?.let { append("Origin: $it\r\n") }
            if (body.isNotEmpty()) {
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
            }
            append("\r\n")
            append(body)
        }
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().flush()
        val input = socket.getInputStream()
        val raw = if (stream) {
            buildString {
                while (!contains("event: ready\n")) {
                    val next = input.read()
                    check(next >= 0) { "SSE closed before ready event" }
                    append(next.toChar())
                }
            }
        } else {
            input.readBytes().toString(StandardCharsets.UTF_8)
        }
        parseResponse(raw)
    }

    private fun parseResponse(raw: String): HttpResponse {
        val headerEnd = raw.indexOf("\r\n\r\n")
        check(headerEnd >= 0) { "Missing HTTP header terminator: $raw" }
        val headerLines = raw.substring(0, headerEnd).split("\r\n")
        val status = headerLines.first().split(' ')[1].toInt()
        val headers = headerLines.drop(1).associate { line ->
            val separator = line.indexOf(':')
            line.substring(0, separator).lowercase() to line.substring(separator + 1).trim()
        }
        return HttpResponse(status, headers, raw.substring(headerEnd + 4))
    }

    private fun initializeRequest(id: Int): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", "initialize")
        .put("params", JSONObject().put("protocolVersion", McpProtocol.VERSION))
        .toString()

    private fun pingRequest(id: Int): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", "ping")
        .toString()

    private fun toolsListRequest(id: Int): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", "tools/list")
        .toString()

    private fun toolCallRequest(
        id: Int,
        name: String,
        arguments: JSONObject,
        confirmationId: String? = null,
    ): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", "tools/call")
        .put(
            "params",
            JSONObject()
                .put("name", name)
                .put("arguments", arguments)
                .apply { confirmationId?.let { put("confirmationId", it) } },
        )
        .toString()

    private fun availableLoopbackPort(): Int = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_ADDRESS)).use {
        it.localPort
    }

    private data class HttpResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
    ) {
        fun json(): JSONObject = JSONObject(body)
    }

    private class RecordingMcpAuditSink : McpAuditSink {
        val events = mutableListOf<McpAuditEvent>()

        override fun append(event: McpAuditEvent) {
            events += event
        }
    }

    private companion object {
        const val CREDENTIAL_PREFERENCES = "mcp_credentials"
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val SOCKET_TIMEOUT_MILLIS = 2_000
    }
}
