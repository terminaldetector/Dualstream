package com.dualstream.uniter

import com.dualstream.uniter.audio.JitterBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {

    private fun buffer() = JitterBuffer(
        frameSamples = 960, frameMs = 20, minFrames = 2, maxFrames = 10, initialTarget = 3
    )

    private fun frame(v: Int) = ShortArray(960) { v.toShort() }

    @Test
    fun `nothing is played until the target depth is reached`() {
        val b = buffer()
        b.push(frame(1))
        assertNull(b.pop())
        b.push(frame(2))
        assertNull(b.pop())
        b.push(frame(3))
        assertNotNull(b.pop())
    }

    @Test
    fun `frames come back in order`() {
        val b = buffer()
        repeat(3) { b.push(frame(it)) }
        assertEquals(0, b.pop()!![0].toInt())
        assertEquals(1, b.pop()!![0].toInt())
        assertEquals(2, b.pop()!![0].toInt())
    }

    @Test
    fun `the oldest frames are dropped above the hard limit`() {
        val b = buffer()
        repeat(40) { b.push(frame(it)) }
        assertTrue("size ${b.size}", b.size <= 10)
        assertTrue("dropped ${b.dropped}", b.dropped > 0)
        // What remains must be the newest audio, not the oldest.
        assertTrue(b.pop()!![0].toInt() > 20)
    }

    @Test
    fun `an underrun is counted and deepens the target`() {
        val b = buffer()
        repeat(3) { b.push(frame(it)) }
        repeat(3) { b.pop() }
        val before = b.targetFrames
        assertNull(b.pop())
        assertEquals(1, b.underruns)
        assertEquals(before + 1, b.targetFrames)
    }

    @Test
    fun `concealment returns a tapered copy only once`() {
        val b = buffer()
        repeat(3) { b.push(frame(10_000)) }
        b.pop()
        val tail = b.lastGoodTail()
        assertNotNull(tail)
        assertEquals(0, tail!!.last().toInt())
        assertTrue("tail started at ${tail[0]}", tail[0].toInt() > 9_000)
        assertTrue("tail is not monotonic", tail[100] > tail[500])
        assertNull(b.lastGoodTail())
    }

    @Test
    fun `flush empties the buffer and returns to priming`() {
        val b = buffer()
        repeat(5) { b.push(frame(it)) }
        b.flush()
        assertEquals(0, b.size)
        assertNull(b.pop())
    }

    @Test
    fun `buffered milliseconds follow the frame count`() {
        val b = buffer()
        repeat(4) { b.push(frame(it)) }
        assertEquals(80, b.bufferedMs)
    }

    @Test
    fun `a calm link lets the target shrink again`() {
        val b = buffer()
        // Drain to an underrun so the target deepens first.
        repeat(3) { b.push(frame(it)) }
        while (b.pop() != null) { /* drain */ }
        val deepened = b.targetFrames
        assertTrue(deepened > 2)

        // Refill above the new target, then report a long clean run.
        repeat(deepened + 2) { b.push(frame(0)) }
        b.maybeRelax(10_000)
        assertTrue("target ${b.targetFrames} was $deepened", b.targetFrames < deepened)
    }

    @Test
    fun `a short clean run does not shrink the target`() {
        val b = buffer()
        repeat(5) { b.push(frame(it)) }
        val before = b.targetFrames
        b.maybeRelax(3)
        assertEquals(before, b.targetFrames)
    }
}
