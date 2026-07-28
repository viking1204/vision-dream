package io.github.xororz.localdream.openai

import io.github.xororz.localdream.inference.InferenceDispatcher

/**
 * Atomically arbitrates the one native inference pipeline between the app UI
 * and the OpenAI-compatible gateway.
 *
 * API reservations include queued requests, so the UI cannot load a different
 * model while an accepted request is waiting. Multiple API callers may reserve
 * the pipeline because [BoundedSerialExecutor] still executes them one by one.
 */
class InferenceArbiter(private val dispatcher: InferenceDispatcher? = null) {
    private val monitor = Any()
    private var appActive = false
    private var apiReservations = 0

    fun tryAcquireForApp(): Boolean = synchronized(monitor) {
        if (appActive || apiReservations > 0 || dispatcher?.tryAcquireForUi() == false) {
            false
        } else {
            appActive = true
            true
        }
    }

    fun releaseFromApp() = synchronized(monitor) {
        check(appActive) { "App inference is not acquired" }
        appActive = false
        dispatcher?.releaseFromUi()
    }

    /**
     * Submits and reserves atomically with respect to [tryAcquireForApp].
     *
     * `null` means an app generation already owns the pipeline. Executor-level
     * rejection remains a normal [BoundedSerialExecutor.Submission.Rejected].
     */
    fun <T> submitForApi(
        executor: BoundedSerialExecutor,
        affinityKey: String? = null,
        operation: () -> T,
    ): BoundedSerialExecutor.Submission<T>? = synchronized(monitor) {
        if (appActive) return@synchronized null
        executor.submit(affinityKey = affinityKey, operation = operation).also { submission ->
            if (submission is BoundedSerialExecutor.Submission.Accepted) {
                apiReservations++
                // A result future is cancelled as soon as the API service stops,
                // but the native call can still be unwinding. Keep ownership
                // until the executor confirms the operation itself has exited.
                submission.executionFinished.whenComplete { _, _ -> releaseFromApi() }
            }
        }
    }

    fun hasApiReservations(): Boolean = synchronized(monitor) {
        apiReservations > 0
    }

    private fun releaseFromApi() = synchronized(monitor) {
        check(apiReservations > 0) { "API inference is not reserved" }
        apiReservations--
    }

    companion object {
        val process = InferenceArbiter(InferenceDispatcher.process)
    }
}
