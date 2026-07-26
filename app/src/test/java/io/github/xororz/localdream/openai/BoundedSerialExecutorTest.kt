package io.github.xororz.localdream.openai

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedSerialExecutorTest {
    @Test
    fun loadedModelTasksOvertakeInAffinityFifoOrder() {
        val loadedModel = AtomicReference("model-a")
        val executor = BoundedSerialExecutor(
            waitingCapacity = 3,
            preferredAffinityKey = loadedModel::get,
        )
        val activeStarted = CountDownLatch(1)
        val releaseActive = CountDownLatch(1)
        val executionOrder = CopyOnWriteArrayList<String>()

        try {
            val active = accepted(
                executor.submit("model-a") {
                    activeStarted.countDown()
                    assertTrue(releaseActive.await(2, TimeUnit.SECONDS))
                    executionOrder += "active-a"
                },
            )
            assertTrue(activeStarted.await(2, TimeUnit.SECONDS))

            val waitingB = accepted(
                executor.submit("model-b") { executionOrder += "waiting-b" },
            )
            val waitingA1 = accepted(
                executor.submit("model-a") { executionOrder += "waiting-a-1" },
            )
            val waitingA2 = accepted(
                executor.submit("model-a") { executionOrder += "waiting-a-2" },
            )

            releaseActive.countDown()
            active.get(2, TimeUnit.SECONDS)
            waitingB.get(2, TimeUnit.SECONDS)
            waitingA1.get(2, TimeUnit.SECONDS)
            waitingA2.get(2, TimeUnit.SECONDS)

            assertEquals(
                listOf("active-a", "waiting-a-1", "waiting-a-2", "waiting-b"),
                executionOrder,
            )
        } finally {
            releaseActive.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun olderDifferentModelRunsAfterThreeAffinityOvertakes() {
        val executor = BoundedSerialExecutor(
            waitingCapacity = 5,
            preferredAffinityKey = { "model-a" },
        )
        val activeStarted = CountDownLatch(1)
        val releaseActive = CountDownLatch(1)
        val executionOrder = CopyOnWriteArrayList<String>()

        try {
            val active = accepted(
                executor.submit("model-a") {
                    activeStarted.countDown()
                    assertTrue(releaseActive.await(2, TimeUnit.SECONDS))
                    executionOrder += "active-a"
                },
            )
            assertTrue(activeStarted.await(2, TimeUnit.SECONDS))

            val futures = listOf(
                accepted(executor.submit("model-b") { executionOrder += "waiting-b" }),
                accepted(executor.submit("model-a") { executionOrder += "waiting-a-1" }),
                accepted(executor.submit("model-a") { executionOrder += "waiting-a-2" }),
                accepted(executor.submit("model-a") { executionOrder += "waiting-a-3" }),
                accepted(executor.submit("model-a") { executionOrder += "waiting-a-4" }),
            )

            releaseActive.countDown()
            active.get(2, TimeUnit.SECONDS)
            futures.forEach { it.get(2, TimeUnit.SECONDS) }

            assertEquals(
                listOf(
                    "active-a",
                    "waiting-a-1",
                    "waiting-a-2",
                    "waiting-a-3",
                    "waiting-b",
                    "waiting-a-4",
                ),
                executionOrder,
            )
        } finally {
            releaseActive.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun preferredModelIsReadAgainWhenEachTaskIsDequeued() {
        val loadedModel = AtomicReference("model-a")
        val executor = BoundedSerialExecutor(
            waitingCapacity = 3,
            preferredAffinityKey = loadedModel::get,
        )
        val activeStarted = CountDownLatch(1)
        val releaseActive = CountDownLatch(1)
        val executionOrder = CopyOnWriteArrayList<String>()

        try {
            val active = accepted(
                executor.submit("model-c") {
                    activeStarted.countDown()
                    assertTrue(releaseActive.await(2, TimeUnit.SECONDS))
                    executionOrder += "active-c"
                },
            )
            assertTrue(activeStarted.await(2, TimeUnit.SECONDS))

            val waitingA = accepted(
                executor.submit("model-a") { executionOrder += "waiting-a" },
            )
            val waitingB1 = accepted(
                executor.submit("model-b") { executionOrder += "waiting-b-1" },
            )
            val waitingB2 = accepted(
                executor.submit("model-b") { executionOrder += "waiting-b-2" },
            )
            loadedModel.set("model-b")

            releaseActive.countDown()
            active.get(2, TimeUnit.SECONDS)
            waitingA.get(2, TimeUnit.SECONDS)
            waitingB1.get(2, TimeUnit.SECONDS)
            waitingB2.get(2, TimeUnit.SECONDS)

            assertEquals(
                listOf("active-c", "waiting-b-1", "waiting-b-2", "waiting-a"),
                executionOrder,
            )
        } finally {
            releaseActive.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun waitingCapacityExcludesActiveTaskAndPreservesFifoOrder() {
        val executor = BoundedSerialExecutor(waitingCapacity = 2)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executionOrder = CopyOnWriteArrayList<Int>()

        try {
            val first = accepted(
                executor.submit {
                    firstStarted.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                    executionOrder += 1
                    1
                },
            )
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

            val second = accepted(executor.submit { executionOrder += 2 })
            val third = accepted(executor.submit { executionOrder += 3 })
            val overflow = executor.submit { executionOrder += 4 }

            assertEquals(2, executor.queuedTaskCount)
            assertTrue(executor.hasActiveTask)
            assertRejected(overflow, BoundedSerialExecutor.RejectionReason.QUEUE_FULL)

            releaseFirst.countDown()
            assertEquals(1, first.get(2, TimeUnit.SECONDS))
            second.get(2, TimeUnit.SECONDS)
            third.get(2, TimeUnit.SECONDS)
            assertEquals(listOf(1, 2, 3), executionOrder)
        } finally {
            releaseFirst.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun zeroWaitingCapacityStillAllowsOneActiveTask() {
        val executor = BoundedSerialExecutor(waitingCapacity = 0)
        val activeStarted = CountDownLatch(1)
        val releaseActive = CountDownLatch(1)

        try {
            val active = accepted(
                executor.submit {
                    activeStarted.countDown()
                    assertTrue(releaseActive.await(2, TimeUnit.SECONDS))
                    "done"
                },
            )
            assertTrue(activeStarted.await(2, TimeUnit.SECONDS))
            assertRejected(
                executor.submit { "overflow" },
                BoundedSerialExecutor.RejectionReason.QUEUE_FULL,
            )

            releaseActive.countDown()
            assertEquals("done", active.get(2, TimeUnit.SECONDS))
            waitUntilIdle(executor)
            assertEquals("next", accepted(executor.submit { "next" }).get(2, TimeUnit.SECONDS))
        } finally {
            releaseActive.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun failedTaskDoesNotStopFollowingTask() {
        val executor = BoundedSerialExecutor(waitingCapacity = 1)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)

        try {
            val failed = accepted<Unit>(
                executor.submit {
                    firstStarted.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                    error("expected")
                },
            )
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            val following = accepted(executor.submit { 42 })
            releaseFirst.countDown()

            try {
                failed.get(2, TimeUnit.SECONDS)
                fail("Expected task failure")
            } catch (error: ExecutionException) {
                assertEquals("expected", error.cause?.message)
            }
            assertEquals(42, following.get(2, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun shutdownDrainsAcceptedTasksAndRejectsNewOnes() {
        val executor = BoundedSerialExecutor(waitingCapacity = 1)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)

        val first = accepted(
            executor.submit {
                firstStarted.countDown()
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                1
            },
        )
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        val second = accepted(executor.submit { 2 })

        executor.shutdown()
        assertTrue(executor.isShutdown)
        assertFalse(executor.isTerminated)
        assertRejected(
            executor.submit { 3 },
            BoundedSerialExecutor.RejectionReason.SHUTDOWN,
        )

        releaseFirst.countDown()
        assertEquals(1, first.get(2, TimeUnit.SECONDS))
        assertEquals(2, second.get(2, TimeUnit.SECONDS))
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        assertTrue(executor.isTerminated)
    }

    @Test
    fun emptyExecutorTerminatesAfterShutdown() {
        val executor = BoundedSerialExecutor(waitingCapacity = 1)

        executor.close()

        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        assertTrue(executor.isTerminated)
    }

    @Test
    fun shutdownNowCancelsActiveAndWaitingTasks() {
        val executor = BoundedSerialExecutor(waitingCapacity = 1)
        val activeStarted = CountDownLatch(1)
        val releaseActive = CountDownLatch(1)
        val active = accepted(
            executor.submit {
                activeStarted.countDown()
                releaseActive.await()
            },
        )
        assertTrue(activeStarted.await(2, TimeUnit.SECONDS))
        val waiting = accepted(executor.submit { "must not run" })

        executor.shutdownNow()
        releaseActive.countDown()

        assertCancelled(active)
        assertCancelled(waiting)
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    private fun waitUntilIdle(executor: BoundedSerialExecutor) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (executor.hasActiveTask && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertFalse("Executor did not become idle", executor.hasActiveTask)
    }

    private fun <T> accepted(submission: BoundedSerialExecutor.Submission<T>): CompletableFuture<T> = when (submission) {
        is BoundedSerialExecutor.Submission.Accepted -> submission.future

        is BoundedSerialExecutor.Submission.Rejected -> {
            fail("Expected accepted task, but was rejected with ${submission.reason}")
            error("unreachable")
        }
    }

    private fun assertRejected(
        submission: BoundedSerialExecutor.Submission<*>,
        reason: BoundedSerialExecutor.RejectionReason,
    ) {
        when (submission) {
            is BoundedSerialExecutor.Submission.Accepted -> fail("Expected rejected task")
            is BoundedSerialExecutor.Submission.Rejected -> assertEquals(reason, submission.reason)
        }
    }

    private fun assertCancelled(future: CompletableFuture<*>) {
        try {
            future.get(2, TimeUnit.SECONDS)
            fail("Expected cancelled task")
        } catch (_: CancellationException) {
        } catch (error: ExecutionException) {
            assertTrue(error.cause is CancellationException)
        }
    }
}
