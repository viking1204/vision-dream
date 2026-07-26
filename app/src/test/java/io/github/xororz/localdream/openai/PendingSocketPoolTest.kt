package io.github.xororz.localdream.openai

import java.io.IOException
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSocketPoolTest {
    @Test
    fun idleSocketsStayPendingUntilInputArrives() {
        val readiness = mutableMapOf<Socket, Boolean>()
        val pool = PendingSocketPool(capacity = 2) { readiness[it] == true }
        val socket = Socket()
        readiness[socket] = false

        assertEquals(PendingSocketScan(emptyList(), emptyList()), pool.drainReady())

        pool.add(socket)
        assertEquals(PendingSocketScan(emptyList(), emptyList()), pool.drainReady())

        readiness[socket] = true
        assertEquals(listOf(socket), pool.drainReady().ready)
        assertTrue(pool.drainReady().ready.isEmpty())
    }

    @Test
    fun capacityEvictsOldestIdleSocket() {
        val pool = PendingSocketPool(capacity = 2) { false }
        val oldest = Socket()
        val second = Socket()
        val newest = Socket()

        pool.add(oldest)
        pool.add(second)

        assertSame(oldest, pool.add(newest))
    }

    @Test
    fun readinessFailureRemovesBrokenSocket() {
        val broken = Socket()
        val pool = PendingSocketPool(capacity = 1) {
            throw IOException("closed")
        }

        pool.add(broken)

        assertEquals(listOf(broken), pool.drainReady().failed)
        assertTrue(pool.drainReady().failed.isEmpty())
    }
}
