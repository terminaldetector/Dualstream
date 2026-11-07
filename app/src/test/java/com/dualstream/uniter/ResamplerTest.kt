package com.dualstream.uniter

import com.dualstream.uniter.audio.Resampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ResamplerTest {

    @Test
    fun `equal rates bypass and copy`() {
        val r = Resampler(48_000, 48_000)
        assertTrue(r.bypass)
        val src = ShortArray(100) { it.toShort() }
        val dst = ShortArray(100)
        assertEquals(100, r.process(src, 0, 100, dst))
        assertTrue(src.contentEquals(dst))
    }

    @Test
    fun `downsampling three to one produces a third of the samples`() {
        val r = Resampler(48_000, 16_000)
        val src = ShortArray(960)
        val dst = ShortArray(r.maxOut(960))
        val n = r.process(src, 0, 960, dst)
        assertTrue("got $n", n in 318..322)
    }

    @Test
    fun `upsampling one to three produces three times the samples`() {
        val r = Resampler(16_000, 48_000)
        val src = ShortArray(320)
        val dst = ShortArray(r.maxOut(320))
        val n = r.process(src, 0, 320, dst)
        assertTrue("got $n", n in 958..962)
    }

    @Test
    fun `successive calls do not drift in total sample count`() {
        val r = Resampler(48_000, 16_000)
        val src = ShortArray(960)
        val dst = ShortArray(r.maxOut(960))
        var total = 0
        repeat(100) { total += r.process(src, 0, 960, dst) }
        // 100 frames of 20 ms at 16 kHz is 32000 samples; allow one frame of slack.
        assertTrue("total $total", abs(total - 32_000) < 320)
    }

    @Test
    fun `a sine wave stays a sine wave after a down-up round trip`() {
        val down = Resampler(48_000, 16_000)
        val up = Resampler(16_000, 48_000)
        val n = 4_800
        val src = ShortArray(n) { i -> (sin(2 * PI * 300.0 * i / 48_000) * 0.5 * 32767).toInt().toShort() }

        val mid = ShortArray(down.maxOut(n))
        val m = down.process(src, 0, n, mid)
        val out = ShortArray(up.maxOut(m))
        val k = up.process(mid, 0, m, out)

        // Compare energy rather than samples: resampling shifts phase.
        fun energy(b: ShortArray, len: Int): Double {
            var acc = 0.0
            for (i in 0 until len) acc += b[i].toDouble() * b[i]
            return acc / len
        }
        val ratio = energy(out, k) / energy(src, n)
        assertTrue("energy ratio $ratio", ratio in 0.7..1.3)
    }
}
