package io.github.xororz.localdream.openai

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceArbiterTest {
    @Test
    fun appAndApiAdmissionsAreMutuallyExclusive() {
        val arbiter = InferenceArbiter()
        val executor = BoundedSerialExecutor(waitingCapacity = 1)

        assertTrue(arbiter.tryAcquireForApp())
        assertNull(arbiter.submitForApi(executor) { "blocked" })
        arbiter.releaseFromApp()

        val gate = CountDownLatch(1)
        val submission = arbiter.submitForApi(executor) {
            gate.await(5, TimeUnit.SECONDS)
            "done"
        }
        assertNotNull(submission)
        assertTrue(arbiter.hasApiReservations())
        assertFalse(arbiter.tryAcquireForApp())

        gate.countDown()
        val future = (submission as BoundedSerialExecutor.Submission.Accepted).future
        future.get(5, TimeUnit.SECONDS)
        awaitCondition { !arbiter.hasApiReservations() }
        assertTrue(arbiter.tryAcquireForApp())
        arbiter.releaseFromApp()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun queuedApiRequestsKeepAppBlockedUntilEveryRequestCompletes() {
        val arbiter = InferenceArbiter()
        val executor = BoundedSerialExecutor(waitingCapacity = 1)
        val firstGate = CountDownLatch(1)

        val first = arbiter.submitForApi(executor) {
            firstGate.await(5, TimeUnit.SECONDS)
        }
        val second = arbiter.submitForApi(executor) { Unit }
        assertNotNull(first)
        assertNotNull(second)
        assertFalse(arbiter.tryAcquireForApp())

        firstGate.countDown()
        (first as BoundedSerialExecutor.Submission.Accepted).future.get(5, TimeUnit.SECONDS)
        (second as BoundedSerialExecutor.Submission.Accepted).future.get(5, TimeUnit.SECONDS)
        awaitCondition { !arbiter.hasApiReservations() }
        assertTrue(arbiter.tryAcquireForApp())
        arbiter.releaseFromApp()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun shutdownKeepsAppBlockedUntilActiveApiOperationActuallyReturns() {
        val arbiter = InferenceArbiter()
        val executor = BoundedSerialExecutor(waitingCapacity = 0)
        val operationStarted = CountDownLatch(1)
        val allowOperationToReturn = CountDownLatch(1)

        try {
            val submission = arbiter.submitForApi(executor, affinityKey = "model-a") {
                operationStarted.countDown()
                while (true) {
                    try {
                        allowOperationToReturn.await()
                        break
                    } catch (_: InterruptedException) {
                        // Simulate native cancellation that has not unwound yet.
                    }
                }
            }
            assertNotNull(submission)
            assertTrue(operationStarted.await(5, TimeUnit.SECONDS))

            executor.shutdownNow()

            val future = (submission as BoundedSerialExecutor.Submission.Accepted).future
            assertTrue(future.isCompletedExceptionally)
            assertTrue(arbiter.hasApiReservations())
            assertFalse(arbiter.tryAcquireForApp())

            allowOperationToReturn.countDown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            awaitCondition { !arbiter.hasApiReservations() }
            assertTrue(arbiter.tryAcquireForApp())
            arbiter.releaseFromApp()
        } finally {
            allowOperationToReturn.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition was not met before timeout" }
            Thread.yield()
        }
    }
}
