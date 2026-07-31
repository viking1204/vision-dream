package io.github.xororz.localdream.inference

import io.github.xororz.localdream.openai.BoundedSerialExecutor
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadFactory

/**
 * Process-wide admission point for native inference. Transport shutdown may
 * cancel only its own work; it never shuts down this dispatcher because other
 * services can still own accepted jobs.
 */
class InferenceDispatcher(
    waitingCapacity: Int = DEFAULT_WAITING_CAPACITY,
    preferredAffinityKey: () -> String? = { null },
    private val leases: BackendRuntimeLeaseManager = BackendRuntimeLeaseManager(),
) {
    private val monitor = Any()
    private val preferredAffinityKey = preferredAffinityKey
    private val leaseCompletionBarriers =
        IdentityHashMap<CompletableFuture<Unit>, CompletableFuture<Unit>>()
    private var executor = newExecutor(waitingCapacity)

    /**
     * Applies a transport's queue preference only while the shared pipeline is
     * idle. Once another owner has accepted work, its fairness guarantees take
     * precedence over a live queue resize.
     */
    fun configureWaitingCapacity(waitingCapacity: Int): Boolean = synchronized(monitor) {
        if (uiActive || executor.hasActiveTask || executor.queuedTaskCount > 0) return@synchronized false
        executor.shutdown()
        executor = newExecutor(waitingCapacity)
        true
    }

    private fun newExecutor(waitingCapacity: Int) = BoundedSerialExecutor(
        waitingCapacity = waitingCapacity,
        preferredAffinityKey = preferredAffinityKey,
        threadFactory = ThreadFactory { runnable ->
            Thread(runnable, "inference-dispatcher").apply { isDaemon = true }
        },
    )
    private var uiActive = false
    private var runtimeTransitionActive = false

    val runtimeLeases: BackendRuntimeLeaseManager
        get() = leases

    val queuedTaskCount: Int
        get() = executor.queuedTaskCount

    val hasActiveTask: Boolean
        get() = executor.hasActiveTask

    fun acquireServiceLease(
        ownerId: String,
    ): BackendRuntimeLeaseManager.Lease = leases.acquire(
        ownerId,
        BackendRuntimeLeaseManager.Kind.SERVICE,
    )

    fun tryAcquireForUi(): Boolean = synchronized(monitor) {
        if (uiActive || executor.hasActiveTask || executor.queuedTaskCount > 0) {
            false
        } else {
            uiActive = true
            true
        }
    }

    fun releaseFromUi() = synchronized(monitor) {
        check(uiActive) { "UI inference is not acquired" }
        uiActive = false
    }

    /**
     * Admits a short runtime lifecycle transition only when the shared native
     * pipeline is idle. The operation must only submit the lifecycle command;
     * it must not block while the backend stops.
     */
    fun <T> tryRunRuntimeTransition(operation: () -> T): T? = synchronized(monitor) {
        if (uiActive || runtimeTransitionActive || executor.hasActiveTask || executor.queuedTaskCount > 0) {
            return@synchronized null
        }
        runtimeTransitionActive = true
        try {
            operation()
        } finally {
            runtimeTransitionActive = false
        }
    }

    /** Returns null when a UI generation owns the pipeline. */
    fun <T> submit(
        ownerId: String,
        affinityKey: String? = null,
        operation: () -> T,
    ): BoundedSerialExecutor.Submission<T>? = synchronized(monitor) {
        if (uiActive || runtimeTransitionActive) return@synchronized null
        executor.submit(affinityKey = affinityKey, ownerId = ownerId, operation = operation).also {
            if (it is BoundedSerialExecutor.Submission.Accepted) {
                val jobLease = leases.acquire(ownerId, BackendRuntimeLeaseManager.Kind.JOB)
                val leaseReleased = CompletableFuture<Unit>()
                leaseCompletionBarriers[it.executionFinished] = leaseReleased
                it.executionFinished.whenComplete { _, error ->
                    jobLease.close()
                    synchronized(monitor) {
                        leaseCompletionBarriers.remove(it.executionFinished)
                    }
                    if (error == null) {
                        leaseReleased.complete(Unit)
                    } else {
                        leaseReleased.completeExceptionally(error)
                    }
                }
                return@synchronized BoundedSerialExecutor.Submission.Accepted(
                    future = it.future,
                    executionFinished = leaseReleased,
                )
            }
        }
    }

    /**
     * Cancels one owner and returns its real execution-completion barriers.
     * A barrier completes only after both the native operation and its runtime
     * lease have finished; a cancelled result future proves neither.
     */
    fun cancelOwner(ownerId: String): List<CompletableFuture<Unit>> = synchronized(monitor) {
        executor.cancelOwner(ownerId).map { executionFinished ->
            leaseCompletionBarriers[executionFinished] ?: executionFinished
        }
    }

    companion object {
        private const val DEFAULT_WAITING_CAPACITY = 3
        val process = InferenceDispatcher()
    }
}
