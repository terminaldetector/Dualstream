package com.dualstream.uniter

import com.dualstream.uniter.audio.RateMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateMeterTest {

    /** Reproduces the first field run: 32 kbit/s asked for, ~200 kbit/s sent. */
    @Test
    fun `the field run's over-budget stream is detected`() {
        val m = RateMeter(windowMs = 1_000)
        var now = 0L
        // 165 packets per second of 150 bytes, each covering 5 ms of audio.
        repeat(400) {
            now += 6
            m.record(now, packetBytes = 150, audioSamples = 240, sampleRate = 48_000)
        }
        assertTrue("measured ${m.bitsPerSecond}", m.bitsPerSecond > 150_000)
        assertTrue(m.isOverBudget(32_000))
        assertTrue("ratio ${m.realtimeRatio}", m.realtimeRatio > 0.7f)
    }

    @Test
    fun `a well behaved stream is within budget`() {
        val m = RateMeter(windowMs = 1_000)
        var now = 0L
        // 50 packets per second of 95 bytes, 20 ms each: 38 kbit/s on the wire.
        repeat(150) {
            now += 20
            m.record(now, packetBytes = 95, audioSamples = 960, sampleRate = 48_000)
        }
        assertEquals(38_000.0, m.bitsPerSecond.toDouble(), 2_000.0)
        assertEquals(50.0, m.packetsPerSecond.toDouble(), 3.0)
        assertFalse(m.isOverBudget(32_000))
    }

    @Test
    fun `realtime ratio is one when audio keeps pace with the clock`() {
        val m = RateMeter(windowMs = 1_000)
        var now = 0L
        repeat(150) {
            now += 20
            m.record(now, 80, 960, 48_000)
        }
        assertEquals(1.0f, m.realtimeRatio, 0.05f)
    }

    @Test
    fun `realtime ratio exceeds one when the encoder outruns the clock`() {
        val m = RateMeter(windowMs = 1_000)
        var now = 0L
        // A frame of audio emitted every 10 ms instead of every 20.
        repeat(300) {
            now += 10
            m.record(now, 80, 960, 48_000)
        }
        assertEquals(2.0f, m.realtimeRatio, 0.1f)
    }

    @Test
    fun `nothing is reported before a window closes`() {
        val m = RateMeter(windowMs = 1_000)
        assertFalse(m.record(0, 100, 960, 48_000))
        assertFalse(m.record(500, 100, 960, 48_000))
        assertEquals(0, m.bitsPerSecond)
        // Must not claim an overrun on no evidence.
        assertFalse(m.isOverBudget(1))
    }

    @Test
    fun `totals survive across windows and reset clears them`() {
        val m = RateMeter(windowMs = 100)
        var now = 0L
        repeat(50) {
            now += 20
            m.record(now, 100, 960, 48_000)
        }
        assertEquals(50, m.totalPackets)
        assertEquals(5_000, m.totalBytes)
        assertTrue(m.windowsCompleted > 0)
        m.reset()
        assertEquals(0, m.totalPackets)
        assertEquals(0, m.bitsPerSecond)
    }
}
