package io.github.xororz.localdream.mcp

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
 * Serializes a client's mutation retries by idempotency key. A reused key may
 * replay only the exact same domain request; a changed payload is rejected so
 * reconnecting clients cannot accidentally turn one approval into two writes.
 */
class McpMutationReplayStore {
    private data class Key(
        val clientId: String,
        val tokenGeneration: Long,
        val toolName: String,
        val idempotencyKey: String,
    )

    private data class Entry(
        val parameterDigest: String,
        val result: McpToolGatewayResult,
    )

    private val entries = LinkedHashMap<Key, Entry>()

    fun replay(
        client: McpAuthenticatedClient,
        invocation: McpToolInvocation,
        idempotencyKey: String,
    ): McpToolGatewayResult? = synchronized(entries) {
        val key = Key(client.clientId, client.tokenGeneration, invocation.definition.name, idempotencyKey)
        entries[key]?.let { existing ->
            if (existing.parameterDigest == invocation.parameterDigest) {
                existing.result.copyForReplay()
            } else {
                McpToolGatewayResult.Rejected("IDEMPOTENCY_KEY_CONFLICT")
            }
        }
    }

    fun execute(
        client: McpAuthenticatedClient,
        invocation: McpToolInvocation,
        idempotencyKey: String,
        operation: () -> McpToolGatewayResult,
    ): McpToolGatewayResult = synchronized(entries) {
        val key = Key(client.clientId, client.tokenGeneration, invocation.definition.name, idempotencyKey)
        replay(client, invocation, idempotencyKey)?.let { return@synchronized it }
        operation().also {
            entries[key] = Entry(invocation.parameterDigest, it.copyForReplay())
            while (entries.size > MAX_REPLAYED_MUTATIONS) entries.remove(entries.entries.first().key)
        }
    }

    private fun McpToolGatewayResult.copyForReplay(): McpToolGatewayResult = when (this) {
        is McpToolGatewayResult.Completed -> McpToolGatewayResult.Completed(JSONObject(result.toString()), jobId)
        is McpToolGatewayResult.Rejected -> this
    }

    private companion object {
        const val MAX_REPLAYED_MUTATIONS = 256
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
        return Subscription(session, queue, initial)
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
        val expired = bySession.entries.filter { (_, session) -> session.lastAccessAt < cutoff }
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
    ) : AutoCloseable {
        fun poll(timeoutMillis: Long): Event? = queue.poll(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
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
