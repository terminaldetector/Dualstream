package com.dualstream.uniter

import com.dualstream.uniter.audio.CaptureSource
import com.dualstream.uniter.audio.MixCaptureSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** A capture source that hands out a constant value, pacing itself a little. */
private class FakeSource(
    private val value: Int,
    override val sampleRate: Int = 48_000,
    override val description: String = "fake($value)",
    private val failOnRead: Boolean = false,
    private val startOk: Boolean = true,
    private val delayMs: Long = 0
) : CaptureSource {
    val started = AtomicBoolean(false)
    val stopped = AtomicBoolean(false)

    override fun start(): Boolean {
        started.set(startOk)
        return startOk
    }

    override fun read(dst: ShortArray): Int {
        if (failOnRead) return -1
        if (delayMs > 0) Thread.sleep(delayMs)
        java.util.Arrays.fill(dst, value.toShort())
        return dst.size
    }

    override fun stop() {
        stopped.set(true)
    }
}

class MixCaptureSourceTest {

    private val frame = 64

    private fun waitForSecondary(mix: MixCaptureSource, dst: ShortArray): Int {
        // The secondary runs on its own thread, so the first frames can arrive
        // before it has produced anything.
        var n = 0
        repeat(200) {
            n = mix.read(dst)
            if (!mix.secondarySilent) return n
            Thread.sleep(2)
        }
        return n
    }

    @Test
    fun `mismatched sample rates are refused up front`() {
        try {
            MixCaptureSource(FakeSource(1), FakeSource(1, sampleRate = 16_000), frame)
            org.junit.Assert.fail("expected a rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("same sample rate"))
        }
    }

    @Test
    fun `both sources are summed`() {
        val mix = MixCaptureSource(FakeSource(1000), FakeSource(500), frame)
        assertTrue(mix.start())
        val dst = ShortArray(frame)
        waitForSecondary(mix, dst)
        val n = mix.read(dst)
        assertEquals(frame, n)
        assertEquals(1500, dst[0].toInt())
        mix.stop()
    }

    @Test
    fun `gains scale each side independently`() {
        val mix = MixCaptureSource(FakeSource(1000), FakeSource(1000), frame,
            primaryGain = 0.5f, secondaryGain = 0.25f)
        mix.start()
        val dst = ShortArray(frame)
        waitForSecondary(mix, dst)
        mix.read(dst)
        assertEquals(750, dst[0].toInt())
        mix.stop()
    }

    @Test
    fun `the sum saturates instead of wrapping`() {
        val mix = MixCaptureSource(FakeSource(30000), FakeSource(30000), frame)
        mix.start()
        val dst = ShortArray(frame)
        waitForSecondary(mix, dst)
        mix.read(dst)
        assertEquals(32767, dst[0].toInt())
        mix.stop()
    }

    @Test
    fun `each side's silence is tracked separately`() {
        val mix = MixCaptureSource(FakeSource(1000), FakeSource(0), frame)
        mix.start()
        val dst = ShortArray(frame)
        repeat(50) { mix.read(dst); Thread.sleep(1) }
        assertFalse("primary should be audible", mix.primarySilent)
        assertTrue("secondary is silent and must be reported as such", mix.secondarySilent)
        // The sum is still audible, which is exactly why one combined check
        // would have hidden the dead secondary.
        assertEquals(1000, dst[0].toInt())
        mix.stop()
    }

    @Test
    fun `a secondary that cannot start degrades to the primary alone`() {
        val mix = MixCaptureSource(FakeSource(1000), FakeSource(500, startOk = false), frame)
        assertTrue("the mix must still start", mix.start())
        assertTrue(mix.secondaryFailed)
        val dst = ShortArray(frame)
        assertEquals(frame, mix.read(dst))
        assertEquals(1000, dst[0].toInt())
        mix.stop()
    }

    @Test
    fun `a primary that cannot start fails the mix`() {
        val mix = MixCaptureSource(FakeSource(1000, startOk = false), FakeSource(500), frame)
        assertFalse(mix.start())
    }

    @Test
    fun `a secondary that dies mid-stream is reported and the primary survives`() {
        val mix = MixCaptureSource(FakeSource(1000), FakeSource(500, failOnRead = true), frame)
        mix.start()
        val dst = ShortArray(frame)
        repeat(100) {
            mix.read(dst)
            if (mix.secondaryFailed) return@repeat
            Thread.sleep(2)
        }
        assertTrue(mix.secondaryFailed)
        assertEquals(frame, mix.read(dst))
        assertEquals(1000, dst[0].toInt())
        mix.stop()
    }

    @Test
    fun `a primary read error propagates`() {
        val mix = MixCaptureSource(FakeSource(1000, failOnRead = true), FakeSource(500), frame)
        mix.start()
        assertEquals(-1, mix.read(ShortArray(frame)))
        mix.stop()
    }

    @Test
    fun `a slow secondary never stalls the primary clock`() {
        val mix = MixCaptureSource(FakeSource(1000), FakeSource(500, delayMs = 50), frame)
        mix.start()
        val dst = ShortArray(frame)
        val start = System.currentTimeMillis()
        repeat(20) { mix.read(dst) }
        val elapsed = System.currentTimeMillis() - start
        // 20 primary frames would take a second if the 50 ms secondary were read
        // inline on this thread.
        assertTrue("20 frames took ${elapsed}ms", elapsed < 400)
        mix.stop()
    }

    @Test
    fun `stop shuts both sources down`() {
        val a = FakeSource(1)
        val b = FakeSource(2)
        val mix = MixCaptureSource(a, b, frame)
        mix.start()
        mix.read(ShortArray(frame))
        mix.stop()
        assertTrue(a.stopped.get())
        assertTrue(b.stopped.get())
    }

    @Test
    fun `the description names both halves`() {
        val mix = MixCaptureSource(FakeSource(1), FakeSource(2), frame)
        assertTrue(mix.description.contains("fake(1)"))
        assertTrue(mix.description.contains("fake(2)"))
    }
}
