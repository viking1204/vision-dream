package io.github.xororz.localdream.mcp

import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/** Fixed-window admission and bounded replay state kept before domain routing. */
class McpTransportGuards(
    private val clock: () -> Long = System::currentTimeMillis,
    private val rpcPerMinute: Int = 60,
    private val concurrentSse: Int = 2,
) {
    init {
        require(rpcPerMinute in 1..120)
        require(concurrentSse in 1..4)
    }

    private data class Window(var startedAt: Long, var used: Int)
    private val rpcWindows = ConcurrentHashMap<String, Window>()
    private val sseConnections = ConcurrentHashMap<String, Int>()

    fun takeRpc(clientId: String): Int? = synchronized(rpcWindows) {
        val now = clock()
        val window = rpcWindows[clientId]
            ?.takeIf { now - it.startedAt < WINDOW_MILLIS }
            ?: Window(now, 0).also { rpcWindows[clientId] = it }
        if (window.used >= rpcPerMinute) {
            (((WINDOW_MILLIS - (now - window.startedAt)).coerceAtLeast(1) + 999) / 1000).toInt()
        } else {
            window.used++
            null
        }
    }

    fun openSse(clientId: String): Int? = synchronized(sseConnections) {
        val count = sseConnections[clientId] ?: 0
        if (count >= concurrentSse) {
            1
        } else {
            sseConnections[clientId] = count + 1
            null
        }
    }

    fun closeSse(clientId: String) = synchronized(sseConnections) {
        val remaining = (sseConnections[clientId] ?: 1) - 1
        if (remaining <= 0) sseConnections.remove(clientId) else sseConnections[clientId] = remaining
    }

    private companion object {
        const val WINDOW_MILLIS = 60_000L
    }
}

/**
 * Serializes a client's mutation retries by idempotency key.
 *
 * Replay records are partitioned by client, credential generation, tool and
 * idempotency key. A completed result remains replayable for the whole safe
 * retry window. After that window its tombstone remains until expiry and
 * rejects the key rather than risking a duplicate write. The Android service
 * supplies a persistent private-store implementation so listener restarts keep
 * the same boundary; a crash after the pre-operation IN_FLIGHT record is also
 * rejected rather than repeated.
 */
class McpMutationReplayStore(
    private val persistence: McpMutationReplayPersistence? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = MAX_REPLAY_ENTRIES,
) {
    init {
        require(maxEntries > 0)
    }
    private data class Key(
        val clientId: String,
        val tokenGeneration: Long,
        val toolName: String,
        val idempotencyKey: String,
    )

    private data class Entry(
        val parameterDigest: String,
        val recordedAt: Long,
        val state: State,
        val result: McpToolGatewayResult? = null,
    )

    private enum class State { IN_FLIGHT, SETTLED }

    private val entries = LinkedHashMap<Key, Entry>()

    fun replay(
        client: McpAuthenticatedClient,
        invocation: McpToolInvocation,
        idempotencyKey: String,
    ): McpToolGatewayResult? = synchronized(entries) {
        pruneExpired()
        val key = Key(client.clientId, client.tokenGeneration, invocation.definition.name, idempotencyKey)
        replayExisting(key, invocation.parameterDigest)
    }

    fun execute(
        client: McpAuthenticatedClient,
        invocation: McpToolInvocation,
        idempotencyKey: String,
        operation: () -> McpToolGatewayResult,
    ): McpToolGatewayResult = synchronized(entries) {
        pruneExpired()
        val key = Key(client.clientId, client.tokenGeneration, invocation.definition.name, idempotencyKey)
        replayExisting(key, invocation.parameterDigest)?.let { return@synchronized it }

        val inFlight = Entry(invocation.parameterDigest, clock(), State.IN_FLIGHT)
        if (entries.size >= maxEntries) return@synchronized McpToolGatewayResult.Rejected("IDEMPOTENCY_LEDGER_FULL")
        if (!persist(key, inFlight)) return@synchronized McpToolGatewayResult.Rejected("IDEMPOTENCY_LEDGER_FULL")
        entries[key] = inFlight
        operation().also { result ->
            val settled = inFlight.copy(state = State.SETTLED, result = result.copyForReplay())
            // The original domain operation already happened. If its replay
            // result cannot be committed, retain IN_FLIGHT and reject future
            // retries instead of allowing a second side effect after restart.
            if (persist(key, settled)) entries[key] = settled
        }
    }

    private fun replayExisting(key: Key, parameterDigest: String): McpToolGatewayResult? {
        val existing = entry(key) ?: return null
        if (existing.parameterDigest != parameterDigest) return McpToolGatewayResult.Rejected("IDEMPOTENCY_KEY_CONFLICT")
        val elapsed = (clock() - existing.recordedAt).coerceAtLeast(0L)
        if (elapsed >= TOMBSTONE_RETENTION_MILLIS) {
            if (persistence?.remove(storageKey(key)) == false) return McpToolGatewayResult.Rejected("IDEMPOTENCY_RECORD_UNAVAILABLE")
            entries.remove(key)
            return null
        }
        if (elapsed >= SAFE_RETRY_WINDOW_MILLIS) return McpToolGatewayResult.Rejected("IDEMPOTENCY_RETRY_WINDOW_EXPIRED")
        return existing.result?.copyForReplay() ?: McpToolGatewayResult.Rejected("IDEMPOTENCY_OUTCOME_UNKNOWN")
    }

    private fun entry(key: Key): Entry? {
        entries[key]?.let { return it }
        val encoded = persistence?.read(storageKey(key)) ?: return null
        val restored = decode(encoded) ?: return Entry(
            parameterDigest = "",
            recordedAt = clock(),
            state = State.IN_FLIGHT,
        )
        entries[key] = restored
        return restored
    }

    private fun persist(key: Key, entry: Entry): Boolean = persistence?.writeWithinCapacity(
        storageKey(key),
        encode(entry),
        clock() - TOMBSTONE_RETENTION_MILLIS,
        maxEntries,
    ) ?: true

    private fun pruneExpired() {
        val cutoff = clock() - TOMBSTONE_RETENTION_MILLIS
        entries.entries.removeIf { (_, entry) -> entry.recordedAt < cutoff }
    }

    private fun encode(entry: Entry): String = JSONObject()
        .put("parameterDigest", entry.parameterDigest)
        .put("recordedAt", entry.recordedAt)
        .put("state", entry.state.name)
        .apply {
            when (val result = entry.result) {
                is McpToolGatewayResult.Completed -> {
                    put("result", result.result)
                    result.jobId?.let { put("jobId", it) }
                }

                is McpToolGatewayResult.Rejected -> put("rejectedCode", result.code)

                null -> Unit
            }
        }
        .toString()

    private fun decode(encoded: String): Entry? = runCatching {
        val value = JSONObject(encoded)
        val state = State.valueOf(value.getString("state"))
        val result = when {
            state == State.IN_FLIGHT -> null

            value.has("result") -> McpToolGatewayResult.Completed(
                JSONObject(value.getJSONObject("result").toString()),
                value.optString("jobId", "").takeIf(String::isNotBlank),
            )

            value.has("rejectedCode") -> McpToolGatewayResult.Rejected(value.getString("rejectedCode"))

            else -> return null
        }
        Entry(value.getString("parameterDigest"), value.getLong("recordedAt"), state, result)
    }.getOrNull()

    private fun storageKey(key: Key): String {
        val material = listOf(key.clientId, key.tokenGeneration, key.toolName, key.idempotencyKey).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun McpToolGatewayResult.copyForReplay(): McpToolGatewayResult = when (this) {
        is McpToolGatewayResult.Completed -> McpToolGatewayResult.Completed(JSONObject(result.toString()), jobId)
        is McpToolGatewayResult.Rejected -> this
    }

    private companion object {
        const val SAFE_RETRY_WINDOW_MILLIS = 15 * 60 * 1000L
        const val TOMBSTONE_RETENTION_MILLIS = 24 * 60 * 60 * 1000L
        const val MAX_REPLAY_ENTRIES = 256
    }
}

/** Per-session durable-in-memory SSE replay buffer with explicit reset semantics. */
class McpSseEventStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSessions: Int = MAX_REPLAY_SESSIONS,
    private val replayIdleMillis: Long = DEFAULT_REPLAY_IDLE_MILLIS,
) {
    init {
        require(maxSessions > 0) { "maxSessions must be positive" }
        require(replayIdleMillis > 0) { "replayIdleMillis must be positive" }
    }

    data class Event(val id: Long, val event: String, val data: String)

    class SessionEvents(var lastAccessAt: Long) {
        val events = ArrayDeque<Event>()
        val subscribers = linkedSetOf<ArrayBlockingQueue<Event>>()
    }

    private val nextId = AtomicLong(1)

    /** Access-ordered global state prevents abandoned session IDs from accumulating. */
    private val bySession = LinkedHashMap<String, SessionEvents>(16, 0.75f, true)

    fun publish(sessionId: String, event: String, data: String) {
        val value = Event(nextId.getAndIncrement(), event, data)
        val session = session(sessionId)
        synchronized(session) {
            session.events += value
            while (session.events.size > MAX_REPLAY_EVENTS) session.events.removeFirst()
            val overflowed = session.subscribers.filterNot { it.offer(value) }
            overflowed.forEach { subscriber ->
                // A slow stream must not retain an unbounded event backlog. Send
                // one reset explaining that it must reconnect from replay, then
                // detach it so future task events cannot accumulate.
                session.subscribers -= subscriber
                subscriber.clear()
                check(subscriber.offer(SUBSCRIBER_OVERFLOW_EVENT))
            }
        }
    }

    fun open(sessionId: String, lastEventId: Long?): Subscription {
        val session = session(sessionId)
        val queue = ArrayBlockingQueue<Event>(MAX_SUBSCRIBER_QUEUE_EVENTS)
        val initial = synchronized(session) {
            session.subscribers += queue
            val oldest = session.events.firstOrNull()?.id
            if (lastEventId != null && oldest != null && lastEventId < oldest - 1) {
                val latestTasks = linkedMapOf<String, JSONObject>()
                session.events.filter { it.event == "task" }.forEach { value ->
                    runCatching { JSONObject(value.data) }.getOrNull()?.let { task ->
                        task.optString("jobId").takeIf(String::isNotBlank)?.let { latestTasks[it] = task }
                    }
                }
                listOf(
                    Event(
                        nextId.getAndIncrement(),
                        "reset",
                        JSONObject().put("reason", "replay_unavailable").put("tasks", JSONArray(latestTasks.values)).toString(),
                    ),
                )
            } else {
                session.events.filter { lastEventId == null || it.id > lastEventId }
            }
        }
        return Subscription(session, queue, initial, clock)
    }

    /** Unblocks all streams of a deleted or revoked session immediately. */
    fun close(sessionId: String) {
        val session = synchronized(bySession) { bySession.remove(sessionId) } ?: return
        close(session)
    }

    /** Releases every replay buffer when the listener is shut down. */
    fun closeAll() {
        val sessions = synchronized(bySession) {
            bySession.values.toList().also { bySession.clear() }
        }
        sessions.forEach(::close)
    }

    /**
     * Lifecycle cleanup is explicit so the HTTP owner can prune an idle
     * session even when it no longer has an open SSE stream.
     */
    fun pruneExpired() {
        val expired = synchronized(bySession) { expiredSessionsLocked() }
        expired.forEach(::close)
    }

    private fun session(sessionId: String): SessionEvents = synchronized(bySession) {
        pruneExpiredLocked()
        val session = bySession[sessionId] ?: SessionEvents(clock()).also { bySession[sessionId] = it }
        session.lastAccessAt = clock()
        while (bySession.size > maxSessions) {
            val eldest = bySession.entries.iterator().next()
            bySession.remove(eldest.key)
            close(eldest.value)
        }
        session
    }

    private fun pruneExpiredLocked() {
        expiredSessionsLocked().forEach(::close)
    }

    private fun expiredSessionsLocked(): List<SessionEvents> {
        val cutoff = clock() - replayIdleMillis
        // An open stream may be quiet for longer than replay retention.  It is
        // still live and must receive the next task/progress event.
        val expired = bySession.entries.filter { (_, session) ->
            synchronized(session) { session.lastAccessAt < cutoff && session.subscribers.isEmpty() }
        }
        expired.forEach { (id, _) -> bySession.remove(id) }
        return expired.map { it.value }
    }

    private fun close(session: SessionEvents) {
        synchronized(session) {
            session.subscribers.forEach { subscriber ->
                subscriber.clear()
                check(subscriber.offer(CLOSED_EVENT))
            }
            session.subscribers.clear()
            session.events.clear()
        }
    }

    class Subscription(
        private val session: SessionEvents,
        private val queue: ArrayBlockingQueue<Event>,
        val initial: List<Event>,
        private val clock: () -> Long,
    ) : AutoCloseable {
        fun poll(timeoutMillis: Long): Event? = queue.poll(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS).also {
            synchronized(session) { session.lastAccessAt = clock() }
        }
        override fun close() = synchronized(session) { session.subscribers -= queue }
    }

    companion object {
        const val MAX_REPLAY_EVENTS = 128
        const val MAX_SUBSCRIBER_QUEUE_EVENTS = 64
        const val MAX_REPLAY_SESSIONS = 64
        const val DEFAULT_REPLAY_IDLE_MILLIS = 15 * 60 * 1000L
        internal val CLOSED_EVENT = Event(Long.MIN_VALUE, "closed", "")
        internal val SUBSCRIBER_OVERFLOW_EVENT = Event(
            Long.MIN_VALUE + 1,
            "reset",
            JSONObject().put("reason", "subscriber_overflow").toString(),
        )
    }
}
