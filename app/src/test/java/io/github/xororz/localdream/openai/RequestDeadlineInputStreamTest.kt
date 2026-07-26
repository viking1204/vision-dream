package io.github.xororz.localdream.openai

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RequestDeadlineInputStreamTest {
    @Test
    fun repeatedReadsConsumeOneAbsoluteTimeBudget() {
        val acceptedAtNanos = 1_000_000_000L
        var nowNanos = acceptedAtNanos
        val configuredTimeouts = mutableListOf<Int>()
        val input = RequestDeadlineInputStream(
            delegate = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            acceptedAtNanos = acceptedAtNanos,
            timeoutMillis = 100,
            configureSocketTimeout = configuredTimeouts::add,
            nanoTime = { nowNanos },
        )

        assertEquals(1, input.read())
        assertEquals(100, configuredTimeouts.last())

        nowNanos += TimeUnit.MILLISECONDS.toNanos(40)
        assertEquals(2, input.read())
        assertEquals(60, configuredTimeouts.last())

        nowNanos += TimeUnit.MILLISECONDS.toNanos(60)
        assertThrows(RequestReadDeadlineExceededException::class.java) {
            input.read()
        }
    }

    @Test
    fun resetStartsFreshDeadlineForRequestBody() {
        val acceptedAtNanos = 1_000_000_000L
        var nowNanos = acceptedAtNanos
        val configuredTimeouts = mutableListOf<Int>()
        val input = RequestDeadlineInputStream(
            delegate = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            acceptedAtNanos = acceptedAtNanos,
            timeoutMillis = 300_000,
            configureSocketTimeout = configuredTimeouts::add,
            nanoTime = { nowNanos },
        )

        nowNanos += TimeUnit.SECONDS.toNanos(45)
        assertEquals(1, input.read())
        assertEquals(255_000, configuredTimeouts.last())

        input.resetDeadline(30_000)
        nowNanos += TimeUnit.SECONDS.toNanos(29)
        assertEquals(2, input.read())
        assertEquals(1_000, configuredTimeouts.last())

        nowNanos += TimeUnit.SECONDS.toNanos(2)
        assertThrows(RequestReadDeadlineExceededException::class.java) {
            input.read()
        }
    }
}
