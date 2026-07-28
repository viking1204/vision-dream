package io.github.xororz.localdream.inference

import io.github.xororz.localdream.openai.BoundedSerialExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceDispatcherTest {
    @Test
    fun activeCancelledOwnerKeepsRuntimeLeaseUntilOperationReturns() {
        val dispatcher = InferenceDispatcher(waitingCapacity = 1)
        val started = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        val submission = dispatcher.submit(ownerId = "openai") {
            started.countDown()
            while (true) {
                try {
                    allowReturn.await()
                    break
                } catch (_: InterruptedException) {
                    // Native cancellation can interrupt but still requires unwind.
                }
            }
        }
        assertNotNull(submission)
        assertTrue(started.await(5, TimeUnit.SECONDS))

        dispatcher.cancelOwner("openai")
        val accepted = submission as BoundedSerialExecutor.Submission.Accepted
        assertTrue(accepted.future.isCompletedExceptionally)
        assertFalse(dispatcher.runtimeLeases.canStopBackend())

        allowReturn.countDown()
        accepted.executionFinished.get(5, TimeUnit.SECONDS)
        assertTrue(dispatcher.runtimeLeases.canStopBackend())
    }

    @Test
    fun uiAndTransportAdmissionsAreMutuallyExclusive() {
        val dispatcher = InferenceDispatcher(waitingCapacity = 1)
        assertTrue(dispatcher.tryAcquireForUi())
        assertTrue(dispatcher.submit(ownerId = "openai") { Unit } == null)
        dispatcher.releaseFromUi()

        val submission = dispatcher.submit(ownerId = "openai") { Unit }
        assertNotNull(submission)
        (submission as BoundedSerialExecutor.Submission.Accepted).future.get(5, TimeUnit.SECONDS)
    }
}
