package io.github.xororz.localdream.mcp

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.github.xororz.localdream.openai.OpenAiApiPreferences
import org.json.JSONObject

/**
 * Separate, bounded Streamable HTTP listener for MCP. It deliberately does not
 * reuse the OpenAI listener, port, token preference or request routing.
 */
class McpHttpServer(
    private val port: Int,
    private val transport: McpTransport,
    private val credentialStore: McpClientCredentialStore,
    private val sessions: McpSessionRegistry = McpSessionRegistry(),
    private val allowedLanHosts: () -> Set<String> = { emptySet() },
    private val imageCapabilities: McpImageCapabilityStore = McpImageCapabilityStore(),
    private val imageResolver: McpImageContentResolver = McpImageContentResolver { null },
    private val auditSink: McpAuditSink = McpAuditSink.None,
    private val toolRegistry: McpToolRegistry = McpToolRegistry(),
    private val confirmationStore: McpConfirmationStore = McpConfirmationStore(),
    private val toolGateway: McpToolGateway = McpToolGateway.Unavailable,
    private val guards: McpTransportGuards = McpTransportGuards(),
    private val sseEvents: McpSseEventStore = McpSseEventStore(),
    private val bindAddress: String? = null,
) {
    @Volatile private var running = false

    @Volatile private var socket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(WORKER_COUNT) { runnable ->
        Thread(runnable, "mcp-http-worker")
    }
    private val sseWorkers = Executors.newFixedThreadPool(SSE_WORKER_COUNT) { runnable ->
        Thread(runnable, "mcp-sse-worker")
    }
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val taskEvents = McpTaskEventBus.subscribe { event ->
        sessions.sessionsFor(event.clientId, transport).forEach { session ->
            sseEvents.publish(session.id, "task", taskEvent(event))
        }
    }

    @Synchronized
    fun start() {
        check(!running) { "MCP listener is already running" }
        validatePort(port)
        val address = bindAddress ?: if (transport == McpTransport.LOOPBACK) LOOPBACK else WILDCARD
        val serverSocket = ServerSocket()
        try {
            serverSocket.reuseAddress = true
            serverSocket.bind(InetSocketAddress(InetAddress.getByName(address), port), BACKLOG)
            socket = serverSocket
            running = true
            Thread({ acceptLoop(serverSocket) }, "mcp-http-accept-$port").start()
        } catch (failure: Throwable) {
            serverSocket.close()
            socket = null
            throw failure
        }
    }

    @Synchronized
    fun shutdown() {
        running = false
        socket?.close()
        socket = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        workers.shutdownNow()
        sseWorkers.shutdownNow()
        taskEvents.close()
    }

    private fun acceptLoop(serverSocket: ServerSocket) {
        while (running) {
            val client = try {
                serverSocket.accept()
            } catch (error: Exception) {
                if (running) Log.w(TAG, "MCP accept failed", error)
                break
            }
            clients += client
            workers.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        var handedOffToSse = false
        try {
            client.soTimeout = READ_TIMEOUT_MILLIS
            val request = readRequest(BufferedInputStream(client.getInputStream()))
            val response = request?.let(::route) ?: HttpResponse.badRequest("INVALID_HTTP")
            response.sse?.let { stream ->
                handedOffToSse = true
                sseWorkers.execute {
                    client.use { connection -> writeSseResponse(BufferedOutputStream(connection.getOutputStream()), stream) }
                    clients -= client
                }
                return
            }
            client.use { connection -> writeResponse(BufferedOutputStream(connection.getOutputStream()), response) }
        } catch (_: Exception) {
            // Transport error is deliberately not reflected to unauthenticated clients.
        } finally {
            if (!handedOffToSse) clients -= client
        }
    }

    private fun route(request: HttpRequest): HttpResponse {
        if (!hostAllowed(request.headers["host"]) || !originAllowed(request.headers["origin"])) {
            return HttpResponse.forbidden("ORIGIN_DENIED")
        }
        val client = credentialStore.authenticate(request.bearerToken(), transport)
            ?: return HttpResponse.unauthorized()
        val startedAt = System.currentTimeMillis()
        val response = if (request.path.startsWith(McpProtocol.IMAGE_PATH_PREFIX)) {
            image(request, client)
        } else if (request.path != McpProtocol.PATH) {
            HttpResponse.notFound()
        } else {
            when (request.method) {
                "POST" -> {
                    val retryAfter = guards.takeRpc(client.clientId)
                    if (retryAfter == null) post(request, client) else HttpResponse.rateLimited(retryAfter)
                }
                "GET" -> get(request, client)
                "DELETE" -> delete(request, client)
                else -> HttpResponse.methodNotAllowed()
            }
        }
        return try {
            auditSink.append(auditEvent(request, client, response, startedAt))
            response
        } catch (failure: Exception) {
            Log.e(TAG, "MCP audit persistence failed", failure)
            HttpResponse.json(
                McpProtocol.error(null, -32603, "AUDIT_UNAVAILABLE", "MCP audit is unavailable").toString(),
                status = 500,
            )
        }
    }

    /**
     * This deliberately derives audit data from request metadata and our own
     * response only.  In particular, raw arguments may contain prompts,
     * confirmation ids or inline image data and must never be persisted here.
     */
    private fun auditEvent(
        request: HttpRequest,
        client: McpAuthenticatedClient,
        response: HttpResponse,
        startedAt: Long,
    ): McpAuditEvent = McpAuditEvent(
        timestamp = startedAt,
        clientId = client.clientId,
        transport = client.transport,
        sessionHash = request.headers[McpProtocol.SESSION_HEADER]?.let(::sha256),
        method = auditMethod(request),
        scopeSnapshot = client.scopes.sorted().joinToString(" "),
        risk = response.audit?.risk ?: "unknown",
        parameterDigest = response.audit?.parameterDigest.orEmpty(),
        jobId = response.audit?.jobId,
        outcomeCode = response.outcomeCode(),
        durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0),
    )

    private fun auditMethod(request: HttpRequest): String = when {
        request.path.startsWith(McpProtocol.IMAGE_PATH_PREFIX) -> "images.get"

        request.method == "GET" -> "sse.get"

        request.method == "DELETE" -> "session.delete"

        request.method != "POST" -> "http.unknown"

        else -> runCatching { JSONObject(request.body).optString("method") }
            .getOrNull()
            ?.takeIf { it in AUDITED_RPC_METHODS }
            ?: "rpc.unknown"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

    private fun HttpResponse.outcomeCode(): String = runCatching {
        JSONObject(body.toString(StandardCharsets.UTF_8))
            .optJSONObject("error")
            ?.optJSONObject("data")
            ?.optString("code")
            ?.takeIf(String::isNotBlank)
    }.getOrNull() ?: "OK"

    /**
     * A successful response consumes the capability before writing bytes, so a
     * second request cannot replay it. The resolver receives only the opaque
     * asset id held by the server-side capability, never a client file path.
     */
    private fun image(request: HttpRequest, client: McpAuthenticatedClient): HttpResponse {
        if (request.method != "GET") return HttpResponse.methodNotAllowed("GET")
        val pathParts = request.path.removePrefix(McpProtocol.IMAGE_PATH_PREFIX).split('/')
        if (pathParts.size != 2 || pathParts.any(String::isBlank)) return HttpResponse.notFound()
        val (jobId, token) = pathParts
        val candidate = imageCapabilities.peek(token, client.clientId, jobId, client.transport)
            ?: return HttpResponse.notFound()
        val content = imageResolver.resolve(candidate)
            ?.takeIf { it.mimeType == candidate.mimeType && it.bytes.isNotEmpty() }
            ?: return HttpResponse.notFound()
        val consumed = imageCapabilities.consume(token, client.clientId, jobId, client.transport)
            ?: return HttpResponse.notFound()
        return HttpResponse.bytes(200, consumed.mimeType, content.bytes)
    }

    private fun post(request: HttpRequest, client: McpAuthenticatedClient): HttpResponse {
        val json = runCatching { JSONObject(request.body) }.getOrElse {
            return jsonError(null, -32700, "PARSE_ERROR", "Invalid JSON-RPC body")
        }
        val id = json.opt("id")
        if (json.optString("jsonrpc") != "2.0") {
            return jsonError(id, -32600, "INVALID_REQUEST", "JSON-RPC 2.0 is required")
        }
        return if (json.optString("method") == "initialize") {
            val requestedVersion = json.optJSONObject("params")?.optString("protocolVersion")
            if (requestedVersion != McpProtocol.VERSION) {
                jsonError(id, -32602, "UNSUPPORTED_PROTOCOL_VERSION", "Unsupported MCP protocol version")
            } else {
                val session = sessions.create(client.clientId, client.tokenGeneration, client.transport, client.scopes)
                HttpResponse.json(
                    McpProtocol.result(id, McpProtocol.initializeResult()).toString(),
                    mapOf("Mcp-Session-Id" to session.id),
                )
            }
        } else {
            if (sessionFor(request, client) == null) {
                return jsonError(id, -32001, "SESSION_EXPIRED", "Session is missing or expired")
            }
            when (json.optString("method")) {
                // MCP ping succeeds with an empty result object.  Extra fields break
                // the protocol schema and must not become an undocumented contract.
                "ping" -> HttpResponse.json(McpProtocol.result(id, JSONObject()).toString())
                "tools/list" -> toolsList(id)
                "tools/call" -> toolsCall(id, json.optJSONObject("params"), client)
                else -> jsonError(id, -32601, "METHOD_NOT_FOUND", "Method is not enabled")
            }
        }
    }

    private fun toolsList(id: Any?): HttpResponse {
        val tools = org.json.JSONArray()
        McpToolRegistry.definitions.values
            .asSequence()
            .filter(toolGateway::supports)
            .sortedBy(McpToolDefinition::name)
            .forEach { definition ->
                tools.put(
                    JSONObject()
                        .put("name", definition.name)
                        .put("description", "Vision Dream ${definition.name}")
                        .put(
                            "inputSchema",
                            JSONObject()
                                .put("type", "object")
                                .put("required", org.json.JSONArray(definition.requiredArguments.toList().sorted()))
                                .put(
                                    "properties",
                                    JSONObject().apply {
                                        definition.allowedArguments.sorted().forEach { argument ->
                                            put(argument, JSONObject().put("type", "string"))
                                        }
                                    },
                                )
                                .put("additionalProperties", false),
                        ),
                )
            }
        return HttpResponse.json(McpProtocol.result(id, JSONObject().put("tools", tools)).toString())
    }

    /**
     * Confirmation id is deliberately outside Tool arguments.  It is transport
     * authorization metadata, not a domain parameter and therefore cannot
     * bypass the registry's no-extra-fields rule.
     */
    private fun toolsCall(id: Any?, params: JSONObject?, client: McpAuthenticatedClient): HttpResponse {
        if (params == null || !params.keys().asSequence().all(TOOL_CALL_PARAM_KEYS::contains)) {
            return jsonError(id, -32602, "INVALID_PARAMS", "Invalid tool call parameters")
        }
        val name = params.optString("name")
        val arguments = params.optJSONObject("arguments")
            ?: return jsonError(id, -32602, "INVALID_PARAMS", "Tool arguments must be an object")
        val validation = toolRegistry.validate(name, arguments, client.scopes)
        val invocation = (validation as? McpToolValidation.Allowed)?.invocation
            ?: return toolRejected(id, (validation as McpToolValidation.Rejected).code)
        if (!toolGateway.supports(invocation.definition)) {
            return toolRejected(id, "TOOL_NOT_ENABLED")
        }
        val audit = McpToolAudit(
            risk = invocation.definition.risk.name.lowercase(Locale.ROOT),
            parameterDigest = invocation.parameterDigest,
        )
        if (invocation.definition.risk == McpToolRisk.DESTRUCTIVE) {
            val confirmationId = params.optString("confirmationId")
            val confirmation = McpConfirmationRequest(
                clientId = client.clientId,
                tokenGeneration = client.tokenGeneration,
                action = invocation.definition.name,
                parameterDigest = invocation.parameterDigest,
                targetIds = invocation.targetIds,
                scopes = client.scopes,
            )
            if (confirmationId.isBlank()) {
                confirmationStore.requestUiConfirmation(confirmation)
                return toolRejected(id, "CONFIRMATION_REQUIRED", audit)
            }
            if (confirmationStore.consume(confirmationId, confirmation) != McpConfirmationResult.APPROVED) {
                return toolRejected(id, "CONFIRMATION_INVALID", audit)
            }
        }
        return when (val execution = toolGateway.execute(client, invocation, arguments)) {
            is McpToolGatewayResult.Completed -> HttpResponse.json(
                McpProtocol.result(id, execution.result).toString(),
                audit = audit.copy(jobId = execution.jobId),
            )

            is McpToolGatewayResult.Rejected -> toolRejected(id, execution.code, audit)
        }
    }

    private fun toolRejected(id: Any?, code: String, audit: McpToolAudit? = null): HttpResponse = HttpResponse.json(
        McpProtocol.error(id, -32602, code, "Tool call was rejected").toString(),
        audit = audit,
    )

    private fun get(request: HttpRequest, client: McpAuthenticatedClient): HttpResponse {
        val session = sessionFor(request, client)
            ?: return HttpResponse.json(McpProtocol.error(null, -32001, "SESSION_EXPIRED", "Session is missing or expired").toString(), status = 401)
        val retryAfter = guards.openSse(client.clientId)
            ?: return HttpResponse.sse(
                client.clientId,
                client.tokenGeneration,
                session.id,
                sseEvents.open(session.id, request.headers[LAST_EVENT_ID]?.toLongOrNull()),
            )
        return HttpResponse(
            status = 429,
            contentType = "application/json; charset=utf-8",
            body = McpProtocol.error(null, -32000, "RATE_LIMITED", "Too many SSE streams").toString().toByteArray(StandardCharsets.UTF_8),
            headers = mapOf("Retry-After" to retryAfter.toString()),
        )
    }

    private fun delete(request: HttpRequest, client: McpAuthenticatedClient): HttpResponse {
        val sessionId = request.headers[McpProtocol.SESSION_HEADER]
        if (sessionId.isNullOrBlank() || sessionFor(request, client) == null) {
            return HttpResponse.json(McpProtocol.error(null, -32001, "SESSION_EXPIRED", "Session is missing or expired").toString(), status = 401)
        }
        sessions.remove(sessionId)
        sseEvents.close(sessionId)
        return HttpResponse(status = 204, contentType = "application/json", body = ByteArray(0))
    }

    private fun sessionFor(
        request: HttpRequest,
        client: McpAuthenticatedClient,
    ): McpSession? = request.headers[McpProtocol.SESSION_HEADER]?.let { sessionId ->
        sessions.validate(sessionId, client.clientId, client.tokenGeneration, client.transport)
    }

    private fun jsonError(
        id: Any?,
        code: Int,
        stableCode: String,
        message: String,
    ): HttpResponse = HttpResponse.json(McpProtocol.error(id, code, stableCode, message).toString())

    private fun hostAllowed(hostHeader: String?): Boolean {
        val host = parseAuthorityHost(hostHeader) ?: return false
        return if (transport == McpTransport.LOOPBACK) host == "127.0.0.1" || host == "localhost" else host in allowedLanHosts()
    }

    private fun originAllowed(origin: String?): Boolean {
        if (origin == null) return true
        val host = runCatching { java.net.URI(origin).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return if (transport == McpTransport.LOOPBACK) host == "127.0.0.1" || host == "localhost" else host in allowedLanHosts()
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0 || headers.size >= MAX_HEADERS) return null
            headers[line.substring(0, colon).lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength !in 0..MAX_BODY_BYTES || headers["transfer-encoding"] != null) return null
        val bytes = ByteArray(contentLength)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) return null
            offset += count
        }
        return HttpRequest(parts[0].uppercase(Locale.ROOT), parts[1].substringBefore('?'), headers, String(bytes, StandardCharsets.UTF_8))
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= MAX_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\n'.code) return bytes.filter { it.toInt() != '\r'.code }.toByteArray().toString(StandardCharsets.US_ASCII)
            bytes += value.toByte()
        }
        return null
    }

    private fun writeResponse(output: BufferedOutputStream, response: HttpResponse) {
        val bytes = response.body
        val reason = when (response.status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            429 -> "Too Many Requests"
            else -> "Internal Server Error"
        }
        output.write("HTTP/1.1 ${response.status} $reason\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Type: ${response.contentType}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n".toByteArray(StandardCharsets.US_ASCII))
        response.headers.forEach { (name, value) ->
            output.write("$name: $value\r\n".toByteArray(StandardCharsets.US_ASCII))
        }
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun writeSseResponse(output: BufferedOutputStream, stream: SseStream) {
        try {
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\nX-Accel-Buffering: no\r\nConnection: keep-alive\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            writeSseEvent(output, McpSseEventStore.Event(0, "ready", McpProtocol.sseReadyJson()))
            stream.subscription.initial.forEach { writeSseEvent(output, it) }
            output.flush()
            while (running) {
                if (!sessions.isActive(stream.sessionId, stream.clientId, stream.tokenGeneration, transport)) return
                val event = try {
                    stream.subscription.poll(SSE_HEARTBEAT_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
                if (event === McpSseEventStore.CLOSED_EVENT) return
                if (event == null) output.write(": keepalive\n\n".toByteArray(StandardCharsets.US_ASCII)) else writeSseEvent(output, event)
                output.flush()
            }
        } finally {
            stream.subscription.close()
            guards.closeSse(stream.clientId)
        }
    }

    private fun writeSseEvent(output: BufferedOutputStream, event: McpSseEventStore.Event) {
        val id = if (event.id > 0) "id: ${event.id}\n" else ""
        output.write("${id}event: ${event.event}\ndata: ${event.data}\n\n".toByteArray(StandardCharsets.UTF_8))
    }

    private fun taskEvent(event: McpTaskEventBus.Event): String = JSONObject()
        .put("jobId", event.jobId)
        .put("task", McpTaskProjection.from(event.status).wireValue)
        .toString()

    private fun HttpRequest.bearerToken(): String? = headers["authorization"]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class HttpResponse(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
        val audit: McpToolAudit? = null,
        val sse: SseStream? = null,
    ) {
        companion object {
            fun json(
                body: String,
                headers: Map<String, String> = emptyMap(),
                status: Int = 200,
                audit: McpToolAudit? = null,
            ) = HttpResponse(status, "application/json; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8), headers, audit)
            fun bytes(status: Int, contentType: String, body: ByteArray) = HttpResponse(status, contentType, body)
            fun badRequest(code: String) = json(McpProtocol.error(null, -32600, code, "Invalid HTTP request").toString(), status = 400)
            fun unauthorized() = json(McpProtocol.error(null, -32000, "UNAUTHORIZED", "Valid bearer credentials are required").toString(), mapOf("WWW-Authenticate" to "Bearer"), 401)
            fun forbidden(code: String) = json(McpProtocol.error(null, -32000, code, "Request origin is not allowed").toString(), status = 403)
            fun notFound() = json(McpProtocol.error(null, -32601, "NOT_FOUND", "Resource not found").toString(), status = 404)
            fun methodNotAllowed(allowedMethods: String = "POST, GET, DELETE") = json(McpProtocol.error(null, -32600, "METHOD_NOT_ALLOWED", "HTTP method is not allowed").toString(), mapOf("Allow" to allowedMethods), 405)
            fun rateLimited(retryAfter: Int) = json(McpProtocol.error(null, -32000, "RATE_LIMITED", "RPC rate limit exceeded").toString(), mapOf("Retry-After" to retryAfter.toString()), 429)
            fun sse(
                clientId: String,
                tokenGeneration: Long,
                sessionId: String,
                subscription: McpSseEventStore.Subscription,
            ) = HttpResponse(
                200,
                "text/event-stream; charset=utf-8",
                ByteArray(0),
                sse = SseStream(clientId, tokenGeneration, sessionId, subscription),
            )
        }
    }

    private data class SseStream(
        val clientId: String,
        val tokenGeneration: Long,
        val sessionId: String,
        val subscription: McpSseEventStore.Subscription,
    )

    companion object {
        const val DEFAULT_PORT = 8810
        private const val LOOPBACK = "127.0.0.1"
        private const val WILDCARD = "0.0.0.0"
        private const val BACKLOG = 16
        private const val WORKER_COUNT = 8
        private const val SSE_WORKER_COUNT = 8
        private const val READ_TIMEOUT_MILLIS = 15_000
        private const val MAX_HEADERS = 32
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val LAST_EVENT_ID = "last-event-id"
        private const val SSE_HEARTBEAT_MILLIS = 15_000L
        private const val TAG = "McpHttpServer"
        private val AUDITED_RPC_METHODS = setOf("initialize", "ping", "tools/list", "tools/call")
        private val TOOL_CALL_PARAM_KEYS = setOf("name", "arguments", "confirmationId")

        /**
         * HTTP Host uses brackets around an IPv6 literal.  Splitting on the
         * first colon treats `[fd00::1]:8810` as `[fd00`, which rejects an
         * otherwise allowlisted LAN client.  Keep the bracketed literal in the
         * same canonical representation used by [McpLanHostAllowlist].
         */
        internal fun parseAuthorityHost(authority: String?): String? {
            val value = authority?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: return null
            if (value.startsWith('[')) {
                val closingBracket = value.indexOf(']')
                if (closingBracket <= 1) return null
                val suffix = value.substring(closingBracket + 1)
                if (suffix.isNotEmpty() && (suffix.first() != ':' || suffix.drop(1).toIntOrNull() !in 1..65535)) {
                    return null
                }
                return value.substring(0, closingBracket + 1)
            }
            val separator = value.indexOf(':')
            if (separator < 0) return value
            if (value.indexOf(':', separator + 1) >= 0 || value.substring(separator + 1).toIntOrNull() !in 1..65535) {
                return null
            }
            return value.substring(0, separator)
        }

        fun validatePort(port: Int) {
            require(port in 1024..65535) { "MCP port must be between 1024 and 65535" }
            require(port !in setOf(8081, 8808, OpenAiApiPreferences.PORT)) { "MCP port conflicts with a reserved listener" }
        }
    }

    private data class McpToolAudit(
        val risk: String,
        val parameterDigest: String,
        val jobId: String? = null,
    )
}
