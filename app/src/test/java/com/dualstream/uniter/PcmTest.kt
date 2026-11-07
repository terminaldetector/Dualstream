package com.dualstream.uniter

import com.dualstream.uniter.audio.Pcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTest {

    @Test
    fun `stereo downmix averages the two channels`() {
        val src = shortArrayOf(1000, 2000, -1000, 1000)
        val dst = ShortArray(2)
        assertEquals(2, Pcm.downmixStereoToMono(src, 4, dst))
        assertEquals(1500, dst[0].toInt())
        assertEquals(0, dst[1].toInt())
    }

    @Test
    fun `rms of silence is zero and of full scale is close to one`() {
        assertEquals(0f, Pcm.rms(ShortArray(100)), 1e-6f)
        assertTrue(Pcm.rms(ShortArray(100) { 32767 }) > 0.99f)
    }

    @Test
    fun `digital silence is detected exactly`() {
        assertTrue(Pcm.isSilent(ShortArray(50)))
        val almost = ShortArray(50)
        almost[7] = 1
        assertFalse(Pcm.isSilent(almost))
        assertTrue(Pcm.isBelow(almost, 2))
    }

    @Test
    fun `mix saturates at both ends`() {
        assertEquals(32767, Pcm.mixSample(30000, 30000).toInt())
        assertEquals(-32768, Pcm.mixSample(-30000, -30000).toInt())
        assertEquals(0, Pcm.mixSample(100, -100).toInt())
    }

    @Test
    fun `gain is applied in place and clipped`() {
        val b = shortArrayOf(1000, -1000, 30000)
        Pcm.applyGain(b, 2f)
        assertEquals(2000, b[0].toInt())
        assertEquals(-2000, b[1].toInt())
        assertEquals(32767, b[2].toInt())
    }

    @Test
    fun `fade out ends at zero`() {
        val b = ShortArray(10) { 10000 }
        Pcm.fadeOut(b)
        assertEquals(9000, b[0].toInt())
        assertEquals(0, b[9].toInt())
        assertTrue(b[5] < b[2])
    }
}
