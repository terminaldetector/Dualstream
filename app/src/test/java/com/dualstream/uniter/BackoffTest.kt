package com.dualstream.uniter

import com.dualstream.uniter.transport.Backoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {

    @Test
    fun `delays grow and then settle at the cap`() {
        val b = Backoff(firstDelayMs = 500, maxDelayMs = 8_000, jitterRatio = 0.0)
        assertEquals(500, b.nextDelayMs())
        assertEquals(1_000, b.nextDelayMs())
        assertEquals(2_000, b.nextDelayMs())
        assertEquals(4_000, b.nextDelayMs())
        assertEquals(8_000, b.nextDelayMs())
        repeat(10) { assertEquals(8_000, b.nextDelayMs()) }
    }

    @Test
    fun `reset starts over`() {
        val b = Backoff(jitterRatio = 0.0)
        repeat(4) { b.nextDelayMs() }
        b.reset()
        assertEquals(0, b.attempts)
        assertEquals(500, b.nextDelayMs())
    }

    @Test
    fun `jitter stays inside the configured band`() {
        val b = Backoff(firstDelayMs = 1_000, maxDelayMs = 1_000, jitterRatio = 0.25)
        repeat(200) {
            val d = b.nextDelayMs()
            assertTrue("delay $d", d in 750..1_250)
        }
    }
}
