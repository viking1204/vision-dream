package io.github.xororz.localdream.openai

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Small bounded HTTP/1.1 transport for the OpenAI gateway.
 *
 * It intentionally supports one request per connection. Fixed-length and
 * chunked bodies share the same per-request and process-wide memory budgets,
 * keeping common mobile HTTP clients compatible without unbounded buffering.
 */
class OpenAiHttpServer(
    private val port: Int,
    private val isAuthorized: (method: String, path: String, authorization: String?) -> Boolean,
    private val handler: (HttpRequest) -> HttpResponse,
    private val requestReadTimeoutMillis: Int = DEFAULT_REQUEST_READ_TIMEOUT_MS,
) {
    private val bodyBudgetLock = Any()

    @Volatile
    private var running = false
    private var reservedBodyBytes = 0L
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var preHeaderDispatcher: PreHeaderSocketDispatcher? = null
    private var workers: ThreadPoolExecutor? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    init {
        require(requestReadTimeoutMillis > 0) { "Request read timeout must be positive" }
    }

    @Throws(IOException::class)
    @Synchronized
    fun start() {
        check(
            !running &&
                socket == null &&
                acceptThread == null &&
                preHeaderDispatcher == null &&
                workers == null,
        ) {
            "OpenAI HTTP server is already started"
        }

        var serverSocket: ServerSocket? = null
        var executor: ThreadPoolExecutor? = null
        var acceptingThread: Thread? = null
        var dispatcher: PreHeaderSocketDispatcher? = null
        try {
            // SO_REUSEADDR must be configured before bind so a service restart
            // can reclaim the same port after the previous listener closes.
            val boundSocket = ServerSocket()
            serverSocket = boundSocket
            boundSocket.reuseAddress = true
            // Android devices are not consistent about whether an unspecified
            // ServerSocket is dual-stack. Bind IPv4 explicitly so both
            // same-device 127.0.0.1 callers and LAN IPv4 clients can connect.
            // Android maps the overload without an explicit backlog to a
            // zero-length accept queue on some devices. A client that
            // pre-connects (as Tavo does) can then prevent the next real
            // request from completing its TCP handshake. Keep a bounded,
            // non-zero kernel queue ahead of the bounded worker queue.
            boundSocket.bind(
                InetSocketAddress(
                    InetAddress.getByName(IPV4_WILDCARD_ADDRESS),
                    port,
                ),
                ACCEPT_BACKLOG,
            )
            val workerExecutor = createWorkerExecutor()
            executor = workerExecutor
            val newDispatcher = PreHeaderSocketDispatcher(
                capacity = MAX_PENDING_PRECONNECTS,
                onReady = { submitReadyClient(workerExecutor, it) },
                onDiscarded = { discarded, atCapacity ->
                    clients -= discarded
                    closeQuietly(discarded)
                    if (atCapacity) {
                        Log.w(TAG, "Evicted oldest idle HTTP preconnection at capacity")
                    }
                },
            )
            dispatcher = newDispatcher
            val newAcceptThread = Thread(
                {
                    acceptLoop(boundSocket, newDispatcher)
                },
                "openai-http-accept",
            )
            acceptingThread = newAcceptThread

            socket = boundSocket
            workers = workerExecutor
            acceptThread = newAcceptThread
            preHeaderDispatcher = newDispatcher
            running = true
            newDispatcher.start()
            newAcceptThread.start()
            Log.i(TAG, "OpenAI gateway listening on $port")
        } catch (failure: Throwable) {
            running = false
            closeQuietly(serverSocket)
            dispatcher?.shutdown()
            closeClients()
            joinThread(acceptingThread)
            if (stopExecutor(executor)) {
                resetBodyBudget()
            }
            socket = null
            workers = null
            acceptThread = null
            preHeaderDispatcher = null
            throw failure
        }
    }

    @Synchronized
    fun shutdown() {
        running = false
        val serverSocket = socket
        val acceptingThread = acceptThread
        val dispatcher = preHeaderDispatcher
        val executor = workers

        closeQuietly(serverSocket)
        socket = null
        dispatcher?.shutdown()
        closeClients()

        // Closing the listener releases accept(); wait only for a bounded
        // interval so lifecycle teardown cannot block the main thread forever.
        joinThread(acceptingThread)
        closeClients()
        if (stopExecutor(executor)) {
            resetBodyBudget()
        }

        workers = null
        acceptThread = null
        preHeaderDispatcher = null
    }

    private fun createWorkerExecutor() = ThreadPoolExecutor(
        WORKER_COUNT,
        WORKER_COUNT,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(WORKER_BACKLOG),
    ) { runnable ->
        Thread(runnable, "openai-http-worker")
    }

    private fun closeClients() {
        clients.forEach(::closeQuietly)
        clients.clear()
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    private fun joinThread(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(ACCEPT_JOIN_TIMEOUT_MS)
            if (thread.isAlive) {
                Log.w(TAG, "${thread.name} did not stop within timeout")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun stopExecutor(executor: ThreadPoolExecutor?): Boolean {
        if (executor == null) return true
        executor.shutdownNow()
        return try {
            executor.awaitTermination(WORKER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS).also { terminated ->
                if (!terminated) {
                    Log.w(TAG, "HTTP workers did not stop within timeout")
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun resetBodyBudget() {
        synchronized(bodyBudgetLock) {
            reservedBodyBytes = 0L
        }
    }

    private fun acceptLoop(
        serverSocket: ServerSocket,
        dispatcher: PreHeaderSocketDispatcher,
    ) {
        while (running) {
            val client = try {
                serverSocket.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "Accept failed", e)
                break
            }
            clients += client
            dispatcher.add(client)
        }
    }

    private fun submitReadyClient(
        executor: ThreadPoolExecutor,
        client: Socket,
    ) {
        try {
            executor.execute { serve(client, System.nanoTime()) }
        } catch (_: RejectedExecutionException) {
            rejectBusyClient(client)
            clients -= client
        }
    }

    private fun serve(
        client: Socket,
        acceptedAtNanos: Long,
    ) {
        try {
            client.use { socket ->
                // The pre-header pool dispatches only after the first byte, so
                // an idle Tavo connection consumes no request worker and does
                // not age the absolute header/body read budgets.
                val deadlineInput = RequestDeadlineInputStream(
                    delegate = BufferedInputStream(socket.getInputStream()),
                    acceptedAtNanos = acceptedAtNanos,
                    timeoutMillis = requestReadTimeoutMillis,
                    configureSocketTimeout = { socket.soTimeout = it },
                )
                val parsed = try {
                    parseRequest(
                        input = deadlineInput,
                        sendContinue = { writeContinue(socket) },
                        onHeadersRead = {
                            deadlineInput.resetDeadline(requestReadTimeoutMillis)
                        },
                    )
                } catch (_: RequestReadDeadlineExceededException) {
                    ParseResult.Failure(
                        status = 408,
                        message = "Request read deadline exceeded",
                        code = "request_timeout",
                    )
                }
                try {
                    val response = when (parsed) {
                        is ParseResult.Success -> try {
                            handler(parsed.request)
                        } catch (e: Exception) {
                            Log.e(TAG, "Unhandled gateway request error", e)
                            errorResponse(
                                500,
                                "Internal server error",
                                "server_error",
                                "internal_error",
                            )
                        }

                        is ParseResult.Failure -> errorResponse(
                            status = parsed.status,
                            message = parsed.message,
                            type = parsed.type,
                            code = parsed.code,
                            headers = parsed.headers,
                        )

                        is ParseResult.Preflight -> parsed.response
                    }
                    when (parsed) {
                        is ParseResult.Success -> Log.i(
                            TAG,
                            "${parsed.request.method} ${parsed.request.path} -> " +
                                response.statusCode,
                        )

                        is ParseResult.Failure -> Log.w(
                            TAG,
                            "Rejected HTTP request -> ${response.statusCode} (${parsed.code})",
                        )

                        is ParseResult.Preflight -> Log.i(
                            TAG,
                            "OPTIONS ${parsed.path} -> ${response.statusCode} (cors preflight)",
                        )
                    }
                    writeResponse(socket, response)
                } finally {
                    if (parsed is ParseResult.Success) {
                        releaseBodyBytes(parsed.reservedBodyBytes)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request connection failed: ${e.message}")
        } finally {
            clients -= client
        }
    }

    private fun parseRequest(
        input: InputStream,
        sendContinue: () -> Unit,
        onHeadersRead: () -> Unit,
    ): ParseResult {
        val requestLine = readLine(input)
            ?: return ParseResult.Failure(400, "Missing request line", "invalid_http")
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) {
            return ParseResult.Failure(400, "Invalid request line", "invalid_http")
        }
        val method = parts[0].uppercase()
        val requestTarget = parts[1]
        val path = requestTarget.substringBefore('?')
        val query = requestTarget.substringAfter('?', "").takeIf(String::isNotEmpty)
        if (!path.startsWith('/')) {
            return ParseResult.Failure(400, "Invalid request target", "invalid_http")
        }

        val headers = linkedMapOf<String, String>()
        var headerBytes = requestLine.length
        while (true) {
            val line = readLine(input)
                ?: return ParseResult.Failure(400, "Truncated headers", "invalid_http")
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES) {
                return ParseResult.Failure(431, "Request headers are too large", "headers_too_large")
            }
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) {
                return ParseResult.Failure(400, "Invalid request header", "invalid_http")
            }
            val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim()
            if (!HEADER_NAME.matches(name)) {
                return ParseResult.Failure(400, "Invalid request header", "invalid_http")
            }
            if (name in SINGLETON_HEADERS && headers.containsKey(name)) {
                return ParseResult.Failure(400, "Duplicate $name header", "invalid_http")
            }
            headers[name] = headers[name]?.let { "$it,$value" } ?: value
        }

        // CORS preflight requests carry no credentials. Authenticating them
        // here (before routing) is what broke browser/WebView clients: the
        // browser aborts the real request when its preflight is rejected, so
        // it never sends the actual Authorization header. Answer preflight
        // with 204 and the CORS headers, without touching the auth budget.
        if (method == "OPTIONS") {
            return ParseResult.Preflight(preflightResponse(), path)
        }

        // Authenticate bearer requests or temporary image capability paths
        // before parsing Content-Length or allocating a request body. This
        // keeps unrelated unauthenticated LAN clients outside the memory
        // budget entirely.
        if (!isAuthorized(method, requestTarget, headers["authorization"])) {
            return ParseResult.Failure(
                status = 401,
                message = "Invalid or missing bearer token",
                code = "invalid_api_key",
                type = "authentication_error",
                headers = mapOf("WWW-Authenticate" to "Bearer"),
            )
        }

        val transferEncoding = headers["transfer-encoding"]
            ?.lowercase(Locale.ROOT)
            ?.split(',')
            ?.map(String::trim)
        val bodyMode = when (transferEncoding) {
            null, listOf("identity") -> if (headers.containsKey("content-length")) {
                "fixed"
            } else {
                "empty"
            }

            listOf("chunked") -> "chunked"

            else -> "unsupported"
        }
        val expectation = headers["expect"]?.lowercase(Locale.ROOT)?.trim()
        Log.i(
            TAG,
            "Headers $method $path body=$bodyMode expectContinue=${expectation == "100-continue"}",
        )
        // A completed header block proves this is a real request. Reset the
        // absolute budget so pre-connect idle time cannot consume body time.
        onHeadersRead()
        when (expectation) {
            null -> Unit

            "100-continue" -> Unit

            else -> {
                return ParseResult.Failure(
                    status = 417,
                    message = "Unsupported Expect header",
                    code = "unsupported_expectation",
                )
            }
        }
        val continueRequested = expectation == "100-continue"

        val bodyResult = when (transferEncoding) {
            null, listOf("identity") -> readFixedBody(
                input = input,
                contentLengthValue = headers["content-length"],
                beforeBodyRead = {
                    if (continueRequested) sendContinue()
                },
            )

            listOf("chunked") -> {
                if (headers.containsKey("content-length")) {
                    BodyReadResult.failure(
                        400,
                        "Content-Length cannot be combined with chunked encoding",
                        "invalid_http",
                    )
                } else {
                    if (continueRequested) sendContinue()
                    readChunkedBody(input)
                }
            }

            else -> BodyReadResult.failure(
                400,
                "Unsupported Transfer-Encoding",
                "unsupported_transfer_encoding",
            )
        }
        return when (bodyResult) {
            is BodyReadResult.Failure -> bodyResult.failure

            is BodyReadResult.Success -> ParseResult.Success(
                request = HttpRequest(method, path, headers, bodyResult.body, query),
                reservedBodyBytes = bodyResult.reservedBodyBytes,
            )
        }
    }

    private fun readFixedBody(
        input: InputStream,
        contentLengthValue: String?,
        beforeBodyRead: () -> Unit,
    ): BodyReadResult {
        val contentLength = if (contentLengthValue == null) {
            0L
        } else {
            contentLengthValue.toLongOrNull()
                ?: return BodyReadResult.failure(
                    400,
                    "Invalid Content-Length",
                    "invalid_http",
                )
        }
        if (contentLength < 0L) {
            return BodyReadResult.failure(400, "Invalid Content-Length", "invalid_http")
        }
        if (contentLength > MAX_BODY_BYTES) {
            return BodyReadResult.failure(413, "Request body is too large", "body_too_large")
        }
        if (!reserveBodyBytes(contentLength)) {
            return BodyReadResult.Failure(
                ParseResult.Failure(
                    status = 429,
                    message = "Too much request body data is already in flight",
                    code = "body_budget_exceeded",
                    headers = mapOf("Retry-After" to "5"),
                ),
            )
        }
        val body = try {
            ByteArray(contentLength.toInt())
        } catch (_: OutOfMemoryError) {
            releaseBodyBytes(contentLength)
            return BodyReadResult.failure(
                503,
                "Request body memory is unavailable",
                "server_busy",
                type = "server_error",
            )
        }
        var offset = 0
        try {
            if (body.isNotEmpty()) {
                beforeBodyRead()
            }
            while (offset < body.size) {
                val count = input.read(body, offset, body.size - offset)
                if (count < 0) {
                    releaseBodyBytes(contentLength)
                    return BodyReadResult.failure(
                        400,
                        "Truncated request body",
                        "invalid_http",
                    )
                }
                offset += count
            }
        } catch (e: Exception) {
            releaseBodyBytes(contentLength)
            throw e
        }
        return BodyReadResult.Success(body, contentLength)
    }

    private fun readChunkedBody(input: InputStream): BodyReadResult {
        var reservedBytes = 0L
        val output = try {
            ByteArrayOutputStream(INITIAL_CHUNKED_BODY_CAPACITY)
        } catch (_: OutOfMemoryError) {
            return BodyReadResult.failure(
                503,
                "Request body memory is unavailable",
                "server_busy",
                type = "server_error",
            )
        }
        val buffer = ByteArray(CHUNK_READ_BUFFER_BYTES)
        try {
            while (true) {
                val chunkLine = readLine(input)
                    ?: return chunkedFailure(
                        reservedBytes,
                        400,
                        "Truncated chunk header",
                        "invalid_http",
                    )
                val sizeToken = chunkLine.substringBefore(';').trim()
                val chunkSize = sizeToken.toLongOrNull(16)
                    ?.takeIf { it >= 0L }
                    ?: return chunkedFailure(
                        reservedBytes,
                        400,
                        "Invalid chunk size",
                        "invalid_http",
                    )
                if (chunkSize == 0L) {
                    var trailerBytes = 0
                    while (true) {
                        val trailer = readLine(input)
                            ?: return chunkedFailure(
                                reservedBytes,
                                400,
                                "Truncated chunk trailers",
                                "invalid_http",
                            )
                        trailerBytes += trailer.length
                        if (trailerBytes > MAX_HEADER_BYTES) {
                            return chunkedFailure(
                                reservedBytes,
                                431,
                                "Chunk trailers are too large",
                                "headers_too_large",
                            )
                        }
                        if (trailer.isEmpty()) break
                        if (trailer.indexOf(':') <= 0) {
                            return chunkedFailure(
                                reservedBytes,
                                400,
                                "Invalid chunk trailer",
                                "invalid_http",
                            )
                        }
                    }
                    return BodyReadResult.Success(output.toByteArray(), reservedBytes)
                }
                if (chunkSize > MAX_BODY_BYTES - reservedBytes) {
                    return chunkedFailure(
                        reservedBytes,
                        413,
                        "Request body is too large",
                        "body_too_large",
                    )
                }
                if (!reserveBodyBytes(chunkSize)) {
                    releaseBodyBytes(reservedBytes)
                    return BodyReadResult.Failure(
                        ParseResult.Failure(
                            status = 429,
                            message = "Too much request body data is already in flight",
                            code = "body_budget_exceeded",
                            headers = mapOf("Retry-After" to "5"),
                        ),
                    )
                }
                reservedBytes += chunkSize
                var remaining = chunkSize
                while (remaining > 0L) {
                    val count = input.read(
                        buffer,
                        0,
                        minOf(buffer.size.toLong(), remaining).toInt(),
                    )
                    if (count < 0) {
                        return chunkedFailure(
                            reservedBytes,
                            400,
                            "Truncated request body",
                            "invalid_http",
                        )
                    }
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                if (input.read() != '\r'.code || input.read() != '\n'.code) {
                    return chunkedFailure(
                        reservedBytes,
                        400,
                        "Invalid chunk terminator",
                        "invalid_http",
                    )
                }
            }
        } catch (_: OutOfMemoryError) {
            releaseBodyBytes(reservedBytes)
            return BodyReadResult.failure(
                503,
                "Request body memory is unavailable",
                "server_busy",
                type = "server_error",
            )
        } catch (e: Exception) {
            releaseBodyBytes(reservedBytes)
            throw e
        }
    }

    private fun chunkedFailure(
        reservedBytes: Long,
        status: Int,
        message: String,
        code: String,
    ): BodyReadResult.Failure {
        releaseBodyBytes(reservedBytes)
        return BodyReadResult.Failure(
            ParseResult.Failure(
                status = status,
                message = message,
                code = code,
            ),
        )
    }

    private fun reserveBodyBytes(bytes: Long): Boolean = synchronized(bodyBudgetLock) {
        if (reservedBodyBytes + bytes > MAX_IN_FLIGHT_BODY_BYTES) {
            false
        } else {
            reservedBodyBytes += bytes
            true
        }
    }

    private fun releaseBodyBytes(bytes: Long) {
        synchronized(bodyBudgetLock) {
            reservedBodyBytes = (reservedBodyBytes - bytes).coerceAtLeast(0L)
        }
    }

    private fun readLine(input: InputStream): String? {
        val result = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (result.isEmpty()) null else result.toString()
            if (byte == '\n'.code) return result.toString()
            if (byte != '\r'.code) result.append(byte.toChar())
            if (result.length > MAX_LINE_BYTES) return null
        }
    }

    private sealed class ParseResult {
        data class Success(
            val request: HttpRequest,
            val reservedBodyBytes: Long,
        ) : ParseResult()

        data class Failure(
            val status: Int,
            val message: String,
            val code: String,
            val type: String = "invalid_request_error",
            val headers: Map<String, String> = emptyMap(),
        ) : ParseResult()

        data class Preflight(
            val response: HttpResponse,
            val path: String,
        ) : ParseResult()
    }

    private fun preflightResponse(): HttpResponse = HttpResponse(
        statusCode = 204,
        headers = CORS_PREFLIGHT_HEADERS,
        body = byteArrayOf(),
    )

    private sealed class BodyReadResult {
        data class Success(
            val body: ByteArray,
            val reservedBodyBytes: Long,
        ) : BodyReadResult()

        data class Failure(val failure: ParseResult.Failure) : BodyReadResult()

        companion object {
            fun failure(
                status: Int,
                message: String,
                code: String,
                type: String = "invalid_request_error",
            ): Failure = Failure(
                ParseResult.Failure(
                    status = status,
                    message = message,
                    code = code,
                    type = type,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "OpenAiHttpServer"
        private const val WORKER_COUNT = 12
        private const val WORKER_BACKLOG = 12
        private const val ACCEPT_BACKLOG = 32
        private const val MAX_PENDING_PRECONNECTS = 32
        private const val ACCEPT_JOIN_TIMEOUT_MS = 1_000L
        private const val WORKER_SHUTDOWN_TIMEOUT_MS = 2_000L
        private const val IPV4_WILDCARD_ADDRESS = "0.0.0.0"
        private const val DEFAULT_REQUEST_READ_TIMEOUT_MS = 30 * 1000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_LINE_BYTES = 4 * 1024
        private const val MAX_BODY_BYTES = 20L * 1024 * 1024
        private const val MAX_IN_FLIGHT_BODY_BYTES = 40L * 1024 * 1024
        private const val INITIAL_CHUNKED_BODY_CAPACITY = 8 * 1024
        private const val CHUNK_READ_BUFFER_BYTES = 8 * 1024
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9a-z-]+")
        private val SINGLETON_HEADERS = setOf(
            "authorization",
            "content-length",
            "transfer-encoding",
        )
        private val CORS_PREFLIGHT_HEADERS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type, Authorization",
            "Access-Control-Max-Age" to "86400",
        )
    }
}

private fun writeContinue(socket: Socket) {
    val output = socket.getOutputStream()
    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    output.flush()
}

private fun rejectBusyClient(client: Socket) {
    try {
        client.use {
            writeResponse(
                it,
                errorResponse(
                    503,
                    "HTTP worker limit reached",
                    "server_error",
                    "server_busy",
                ),
            )
        }
    } catch (_: Exception) {
    }
}

private fun writeResponse(socket: Socket, response: HttpResponse) {
        val reason = when (response.statusCode) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
        404 -> "Not Found"
        408 -> "Request Timeout"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        417 -> "Expectation Failed"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Error"
    }
    val header = buildString {
        append("HTTP/1.1 ${response.statusCode} $reason\r\n")
        response.headers.forEach { (name, value) ->
            append("$name: $value\r\n")
        }
        append("X-Content-Type-Options: nosniff\r\n")
        append("Content-Length: ${response.body.size}\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }
    val output = socket.getOutputStream()
    output.write(header.toByteArray(StandardCharsets.ISO_8859_1))
    output.write(response.body)
    output.flush()
}

private fun errorResponse(
    status: Int,
    message: String,
    type: String = "invalid_request_error",
    code: String,
    headers: Map<String, String> = emptyMap(),
): HttpResponse = HttpResponse.json(
    status,
    OpenAiJson.error(
        OpenAiError(
            message = message,
            type = type,
            code = code,
        ),
    ),
    headers,
)

/**
 * Applies one monotonic request-read budget across every read operation.
 *
 * The wrapper sits outside [BufferedInputStream], ensuring already-buffered
 * headers, chunk data, and trailers still observe the same absolute deadline.
 */
internal class RequestDeadlineInputStream(
    private val delegate: InputStream,
    acceptedAtNanos: Long,
    timeoutMillis: Int,
    private val configureSocketTimeout: (Int) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) : InputStream() {
    private var deadlineStartedAtNanos = acceptedAtNanos
    private var timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
    private var configuredTimeoutMillis = -1

    fun resetDeadline(timeoutMillis: Int) {
        require(timeoutMillis > 0) { "Request read timeout must be positive" }
        deadlineStartedAtNanos = nanoTime()
        timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
        configuredTimeoutMillis = -1
    }

    override fun read(): Int = readBeforeDeadline { delegate.read() }

    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        return readBeforeDeadline { delegate.read(bytes, offset, length) }
    }

    private inline fun readBeforeDeadline(read: () -> Int): Int {
        configureRemainingTimeout()
        return try {
            read()
        } catch (failure: SocketTimeoutException) {
            throw RequestReadDeadlineExceededException(failure)
        }
    }

    private fun configureRemainingTimeout() {
        val elapsedNanos = nanoTime() - deadlineStartedAtNanos
        val remainingNanos = timeoutNanos - elapsedNanos
        if (remainingNanos <= 0L) {
            throw RequestReadDeadlineExceededException()
        }
        val wholeMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
        val roundedMillis = if (TimeUnit.MILLISECONDS.toNanos(wholeMillis) < remainingNanos) {
            wholeMillis + 1L
        } else {
            wholeMillis
        }
        val remainingMillis = roundedMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        if (remainingMillis != configuredTimeoutMillis) {
            configureSocketTimeout(remainingMillis)
            configuredTimeoutMillis = remainingMillis
        }
    }
}

internal class RequestReadDeadlineExceededException(
    cause: Throwable? = null,
) : IOException("Request read deadline exceeded", cause)
