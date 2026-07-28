package io.github.xororz.localdream.openai

import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Runs one operation at a time while bounding only the operations waiting behind it.
 *
 * The active operation does not consume [waitingCapacity]. Shutdown is graceful:
 * accepted operations finish before termination, while later submissions are rejected.
 *
 * At each dequeue, the earliest task matching [preferredAffinityKey] may overtake
 * the FIFO head. Overtaking is bounded so a continuous stream for the loaded
 * model cannot starve an older request for another model.
 */
class BoundedSerialExecutor(
    val waitingCapacity: Int,
    private val preferredAffinityKey: () -> String? = { null },
    threadFactory: ThreadFactory = defaultThreadFactory(),
) : AutoCloseable {
    enum class RejectionReason {
        QUEUE_FULL,
        SHUTDOWN,
    }

    sealed class Submission<out T> {
        data class Accepted<T>(
            val future: CompletableFuture<T>,
            internal val executionFinished: CompletableFuture<Unit>,
        ) : Submission<T>()

        data class Rejected(val reason: RejectionReason) : Submission<Nothing>()
    }

    private val monitor = Object()
    private val waitingTasks = ArrayDeque<QueuedTask<*>>()
    private val worker: Thread

    private var activeTask: QueuedTask<*>? = null
    private var consecutiveAffinityOvertakes = 0
    private var shutdownRequested = false
    private var terminated = false

    init {
        require(waitingCapacity >= 0) { "waitingCapacity must not be negative" }
        worker = requireNotNull(threadFactory.newThread(::runWorker)) {
            "threadFactory returned null"
        }
        worker.start()
    }

    val queuedTaskCount: Int
        get() = synchronized(monitor) { waitingTasks.size }

    val hasActiveTask: Boolean
        get() = synchronized(monitor) { activeTask != null }

    val isShutdown: Boolean
        get() = synchronized(monitor) { shutdownRequested }

    val isTerminated: Boolean
        get() = synchronized(monitor) { terminated }

    fun <T> submit(
        affinityKey: String? = null,
        ownerId: String? = null,
        operation: () -> T,
    ): Submission<T> {
        val task = QueuedTask(affinityKey, ownerId, operation)
        synchronized(monitor) {
            if (shutdownRequested) {
                return Submission.Rejected(RejectionReason.SHUTDOWN)
            }
            if (activeTask == null) {
                activeTask = task
                monitor.notifyAll()
            } else {
                if (waitingTasks.size >= waitingCapacity) {
                    return Submission.Rejected(RejectionReason.QUEUE_FULL)
                }
                waitingTasks.addLast(task)
            }
        }
        return Submission.Accepted(task.future, task.executionFinished)
    }

    /**
     * Stops accepting submissions and lets all previously accepted operations finish.
     */
    fun shutdown() {
        synchronized(monitor) {
            if (shutdownRequested) return
            shutdownRequested = true
            monitor.notifyAll()
        }
    }

    /**
     * Rejects new work, cancels all accepted futures, and interrupts the worker.
     *
     * Waiting tasks finish their execution lifecycle immediately because they
     * can no longer run. The active task's result is cancelled immediately, but
     * its execution lifecycle finishes only after its operation actually
     * returns. Callers use that distinction to keep the native inference lease
     * held while cancellation is still unwinding.
     */
    fun shutdownNow() {
        val (activeToCancel, waitingToCancel) = synchronized(monitor) {
            if (terminated) return
            shutdownRequested = true
            val active = activeTask
            val waiting = waitingTasks.toList()
            waitingTasks.clear()
            monitor.notifyAll()
            active to waiting
        }
        activeToCancel?.cancelResult()
        waitingToCancel.forEach { it.cancelBeforeExecution() }
        worker.interrupt()
    }

    /**
     * Cancels only work owned by one transport. An active native operation is
     * allowed to unwind on the worker; its execution lifecycle remains open
     * until that operation returns.
     */
    fun cancelOwner(ownerId: String): List<CompletableFuture<Unit>> {
        val (activeToCancel, waitingToCancel) = synchronized(monitor) {
            val active = activeTask?.takeIf { it.ownerId == ownerId }
            val waiting = waitingTasks.filter { it.ownerId == ownerId }
            waitingTasks.removeAll(waiting.toSet())
            monitor.notifyAll()
            active to waiting
        }
        activeToCancel?.cancelResult()
        waitingToCancel.forEach { it.cancelBeforeExecution() }
        return buildList {
            activeToCancel?.let { add(it.executionFinished) }
            waitingToCancel.forEach { add(it.executionFinished) }
        }
    }

    fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        require(timeout >= 0L) { "timeout must not be negative" }
        var remainingNanos = unit.toNanos(timeout)
        val deadline = System.nanoTime() + remainingNanos
        synchronized(monitor) {
            while (!terminated) {
                if (remainingNanos <= 0L) return false
                val millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                val nanos = (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
                monitor.wait(millis, nanos)
                remainingNanos = deadline - System.nanoTime()
            }
            return true
        }
    }

    override fun close() {
        shutdown()
    }

    private fun runWorker() {
        while (true) {
            val task = awaitActiveTask() ?: return
            task.run()
            synchronized(monitor) {
                check(activeTask === task)
                activeTask = selectNextTask()
                if (activeTask == null && shutdownRequested) {
                    terminated = true
                    monitor.notifyAll()
                    return
                }
                monitor.notifyAll()
            }
        }
    }

    private fun awaitActiveTask(): QueuedTask<*>? = synchronized(monitor) {
        while (activeTask == null && !shutdownRequested) {
            try {
                monitor.wait()
            } catch (_: InterruptedException) {
                // Interruption is not a forceful shutdown signal; preserve accepted work.
            }
        }
        if (activeTask == null && shutdownRequested) {
            terminated = true
            monitor.notifyAll()
            null
        } else {
            activeTask
        }
    }

    /**
     * Selects dynamically because the loaded model can change after every task.
     *
     * A mutable-priority heap would retain its old ordering when the provider
     * changes. The queue is intentionally tiny, so a synchronized linear scan
     * gives correct ordering without an unbounded priority queue.
     */
    private fun selectNextTask(): QueuedTask<*>? {
        if (waitingTasks.isEmpty()) return null

        val preferred = runCatching(preferredAffinityKey).getOrNull()
        if (preferred == null || consecutiveAffinityOvertakes >= MAX_AFFINITY_OVERTAKES) {
            consecutiveAffinityOvertakes = 0
            return waitingTasks.removeFirst()
        }

        val iterator = waitingTasks.iterator()
        var index = 0
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.affinityKey == preferred) {
                iterator.remove()
                if (index == 0) {
                    consecutiveAffinityOvertakes = 0
                } else {
                    consecutiveAffinityOvertakes++
                }
                return candidate
            }
            index++
        }

        consecutiveAffinityOvertakes = 0
        return waitingTasks.removeFirst()
    }

    private class QueuedTask<T>(
        val affinityKey: String?,
        val ownerId: String?,
        private val operation: () -> T,
    ) {
        val future = CompletableFuture<T>()
        val executionFinished = CompletableFuture<Unit>()

        fun run() {
            try {
                if (!future.isDone) {
                    try {
                        future.complete(operation())
                    } catch (error: Throwable) {
                        future.completeExceptionally(error)
                    }
                }
            } finally {
                executionFinished.complete(Unit)
            }
        }

        fun cancelResult() {
            future.completeExceptionally(
                CancellationException("Image API service stopped"),
            )
        }

        fun cancelBeforeExecution() {
            cancelResult()
            executionFinished.complete(Unit)
        }
    }

    companion object {
        internal const val MAX_AFFINITY_OVERTAKES = 3

        private fun defaultThreadFactory(): ThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, "openai-image-serial-executor").apply {
                isDaemon = true
            }
        }
    }
}
