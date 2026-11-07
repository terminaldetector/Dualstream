package com.dualstream.uniter

import com.dualstream.uniter.audio.PcmRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRingTest {

    @Test
    fun `written samples come back in order`() {
        val r = PcmRing(16)
        r.write(shortArrayOf(1, 2, 3, 4))
        val out = ShortArray(4)
        assertEquals(4, r.read(out))
        assertTrue(shortArrayOf(1, 2, 3, 4).contentEquals(out))
        assertEquals(0, r.available)
    }

    @Test
    fun `reads never block and report what was available`() {
        val r = PcmRing(16)
        r.write(shortArrayOf(7, 8))
        val out = ShortArray(10)
        assertEquals(2, r.read(out))
        assertEquals(7, out[0].toInt())
        assertEquals(0, r.read(out))
    }

    @Test
    fun `the oldest samples are dropped when the reader falls behind`() {
        val r = PcmRing(8)
        r.write(ShortArray(8) { (it + 1).toShort() })
        r.write(shortArrayOf(100, 101))
        assertEquals(8, r.available)
        assertEquals(2, r.overflowed)
        val out = ShortArray(8)
        r.read(out)
        // 1 and 2 were pushed out; the newest samples survive.
        assertEquals(3, out[0].toInt())
        assertEquals(100, out[6].toInt())
        assertEquals(101, out[7].toInt())
    }

    @Test
    fun `a write larger than the ring keeps only its newest tail`() {
        val r = PcmRing(4)
        r.write(ShortArray(10) { (it + 1).toShort() })
        assertEquals(4, r.available)
        val out = ShortArray(4)
        r.read(out)
        assertTrue(shortArrayOf(7, 8, 9, 10).contentEquals(out))
        assertEquals(6, r.overflowed)
    }

    @Test
    fun `wrapping around the end of the buffer preserves order`() {
        val r = PcmRing(6)
        r.write(shortArrayOf(1, 2, 3, 4))
        val out = ShortArray(3)
        r.read(out)                              // head is now at 3
        r.write(shortArrayOf(5, 6, 7, 8))        // wraps
        val all = ShortArray(5)
        assertEquals(5, r.read(all))
        assertTrue(shortArrayOf(4, 5, 6, 7, 8).contentEquals(all))
    }

    @Test
    fun `clear empties without counting an overflow`() {
        val r = PcmRing(8)
        r.write(shortArrayOf(1, 2, 3))
        r.clear()
        assertEquals(0, r.available)
        assertEquals(0, r.overflowed)
    }

    @Test
    fun `interleaved writes and reads stay consistent over many cycles`() {
        val r = PcmRing(64)
        var next = 0
        var expected = 0
        val buf = ShortArray(20)
        repeat(500) {
            val chunk = ShortArray(20) { ((next + it) % 1000).toShort() }
            next += 20
            r.write(chunk)
            val n = r.read(buf)
            for (i in 0 until n) {
                assertEquals((expected % 1000).toShort(), buf[i])
                expected++
            }
        }
        // Capacity is larger than each chunk, so nothing should have been dropped.
        assertEquals(0, r.overflowed)
    }

    @Test
    fun `a concurrent producer and consumer never corrupt a sample`() {
        // Deliberately not asserting that nothing is dropped: an unthrottled
        // producer is *supposed* to overrun the ring, and the point of the ring
        // is that it discards old samples rather than blocking or tearing. So
        // the invariant under test is that whatever comes out is intact, and
        // that accounting adds up.
        val r = PcmRing(4096)
        val total = 200_000
        val producer = Thread {
            val chunk = ShortArray(100) { 5 }
            var sent = 0
            while (sent < total) {
                r.write(chunk)
                sent += chunk.size
            }
        }
        var received = 0
        var corrupted = 0
        producer.start()
        val buf = ShortArray(256)
        while (producer.isAlive || r.available > 0) {
            val n = r.read(buf)
            for (i in 0 until n) if (buf[i] != 5.toShort()) corrupted++
            received += n
        }
        producer.join(10_000)
        assertFalse("producer did not finish", producer.isAlive)
        assertEquals("a sample was torn", 0, corrupted)
        assertTrue("nothing was read at all", received > 0)
        assertEquals(
            "reads plus drops must account for every sample written",
            total.toLong(), received + r.overflowed
        )
    }
}
