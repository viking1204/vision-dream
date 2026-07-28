package io.github.xororz.localdream.mcp

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
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
        if (window.used >= rpcPerMinute) (((WINDOW_MILLIS - (now - window.startedAt)).coerceAtLeast(1) + 999) / 1000).toInt()
        else {
            window.used++
            null
        }
    }

    fun openSse(clientId: String): Int? = synchronized(sseConnections) {
        val count = sseConnections[clientId] ?: 0
        if (count >= concurrentSse) 1 else {
            sseConnections[clientId] = count + 1
            null
        }
    }

    fun closeSse(clientId: String) = synchronized(sseConnections) {
        val remaining = (sseConnections[clientId] ?: 1) - 1
        if (remaining <= 0) sseConnections.remove(clientId) else sseConnections[clientId] = remaining
    }

    private companion object { const val WINDOW_MILLIS = 60_000L }
}

/** Per-session durable-in-memory SSE replay buffer with explicit reset semantics. */
class McpSseEventStore {
    data class Event(val id: Long, val event: String, val data: String)

    class SessionEvents {
        val events = ArrayDeque<Event>()
        val subscribers = linkedSetOf<LinkedBlockingQueue<Event>>()
    }

    private val nextId = AtomicLong(1)
    private val bySession = ConcurrentHashMap<String, SessionEvents>()

    fun publish(sessionId: String, event: String, data: String) {
        val value = Event(nextId.getAndIncrement(), event, data)
        val session = bySession.computeIfAbsent(sessionId) { SessionEvents() }
        synchronized(session) {
            session.events += value
            while (session.events.size > MAX_REPLAY_EVENTS) session.events.removeFirst()
            session.subscribers.forEach { it.offer(value) }
        }
    }

    fun open(sessionId: String, lastEventId: Long?): Subscription {
        val session = bySession.computeIfAbsent(sessionId) { SessionEvents() }
        val queue = LinkedBlockingQueue<Event>()
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
        val session = bySession.remove(sessionId) ?: return
        synchronized(session) {
            session.subscribers.forEach { it.offer(CLOSED_EVENT) }
            session.subscribers.clear()
            session.events.clear()
        }
    }

    class Subscription(
        private val session: SessionEvents,
        private val queue: LinkedBlockingQueue<Event>,
        val initial: List<Event>,
    ) : AutoCloseable {
        fun poll(timeoutMillis: Long): Event? = queue.poll(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        override fun close() = synchronized(session) { session.subscribers -= queue }
    }

    companion object {
        const val MAX_REPLAY_EVENTS = 128
        internal val CLOSED_EVENT = Event(Long.MIN_VALUE, "closed", "")
    }
}
