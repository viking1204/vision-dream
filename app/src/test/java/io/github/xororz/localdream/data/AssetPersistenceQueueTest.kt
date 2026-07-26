package io.github.xororz.localdream.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class AssetPersistenceQueueTest {
    @Test
    fun `write survives cancellation of a screen awaiter`() = runBlocking {
        val queue = AssetPersistenceQueue(Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        try {
            val pendingWrite = queue.submit {
                started.complete(Unit)
                releaseWrite.await()
                "saved"
            }
            val screenAwaiter = launch {
                pendingWrite.await()
            }

            started.await()
            screenAwaiter.cancelAndJoin()
            releaseWrite.complete(Unit)

            assertEquals("saved", withTimeout(1_000) { pendingWrite.await() })
        } finally {
            queue.cancel()
        }
    }
}
