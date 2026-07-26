package io.github.xororz.localdream.openai

import java.io.IOException
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class PendingSocketScan(
    val ready: List<Socket>,
    val failed: List<Socket>,
)

/**
 * Holds preconnected sockets without assigning them to request workers.
 *
 * The pool is deliberately bounded. When full, the oldest idle connection is
 * returned for eviction so stale LAN clients cannot permanently block newer
 * callers.
 */
internal class PendingSocketPool(
    private val capacity: Int,
    private val hasAvailableInput: (Socket) -> Boolean = {
        it.getInputStream().available() > 0
    },
) {
    private val sockets = ArrayDeque<Socket>()

    init {
        require(capacity > 0) { "Pending socket capacity must be positive" }
    }

    @Synchronized
    fun add(socket: Socket): Socket? {
        val evicted = if (sockets.size >= capacity) sockets.removeFirst() else null
        sockets.addLast(socket)
        return evicted
    }

    @Synchronized
    fun drainReady(): PendingSocketScan {
        val ready = mutableListOf<Socket>()
        val failed = mutableListOf<Socket>()
        val iterator = sockets.iterator()
        while (iterator.hasNext()) {
            val socket = iterator.next()
            try {
                if (hasAvailableInput(socket)) {
                    iterator.remove()
                    ready += socket
                }
            } catch (_: IOException) {
                iterator.remove()
                failed += socket
            }
        }
        return PendingSocketScan(ready = ready, failed = failed)
    }

    @Synchronized
    fun removeAll(): List<Socket> = sockets.toList().also { sockets.clear() }
}

/**
 * Polls a bounded set of idle preconnections and dispatches a socket only
 * after its first HTTP byte arrives.
 */
internal class PreHeaderSocketDispatcher(
    capacity: Int,
    private val onReady: (Socket) -> Unit,
    private val onDiscarded: (socket: Socket, atCapacity: Boolean) -> Unit,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    private val pool = PendingSocketPool(capacity)
    private val signalLock = ReentrantLock()
    private val changed = signalLock.newCondition()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    init {
        require(pollIntervalMillis > 0) { "Poll interval must be positive" }
    }

    @Synchronized
    fun start() {
        check(!running && thread == null) { "Pre-header dispatcher is already started" }
        running = true
        thread = Thread(::dispatchLoop, "openai-http-preheader").also(Thread::start)
    }

    fun add(socket: Socket) {
        if (!running) {
            onDiscarded(socket, false)
            return
        }
        pool.add(socket)?.let { onDiscarded(it, true) }
        signalLock.withLock {
            changed.signalAll()
        }
    }

    @Synchronized
    fun shutdown() {
        running = false
        signalLock.withLock {
            changed.signalAll()
        }
        thread?.interrupt()
        try {
            thread?.join(STOP_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
        pool.removeAll().forEach { onDiscarded(it, false) }
    }

    private fun dispatchLoop() {
        while (running) {
            val scan = pool.drainReady()
            scan.failed.forEach { onDiscarded(it, false) }
            scan.ready.forEach(onReady)
            if (running && scan.ready.isEmpty()) {
                signalLock.withLock {
                    try {
                        changed.await(pollIntervalMillis, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 50L
        const val STOP_JOIN_TIMEOUT_MS = 1_000L
    }
}
