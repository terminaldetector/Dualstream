package com.dualstream.uniter

import com.dualstream.uniter.audio.Mixer
import com.dualstream.uniter.core.MixMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MixerTest {

    private val frame = 16

    private fun constant(v: Int) = ShortArray(frame) { v.toShort() }

    private fun mixerAtUnityGain() = Mixer(frame).apply {
        localVolume = 1f
        remoteVolume = 1f
    }

    @Test
    fun `hard split sends local left and remote right`() {
        val m = mixerAtUnityGain()
        val out = m.mix(constant(1000), constant(-2000), MixMode.HARD_SPLIT)
        assertEquals(frame * 2, out.size)
        for (i in 0 until frame) {
            assertEquals(1000, out[i * 2].toInt())
            assertEquals(-2000, out[i * 2 + 1].toInt())
        }
    }

    @Test
    fun `mixed stereo sums both sources into both ears`() {
        val m = mixerAtUnityGain()
        val out = m.mix(constant(1000), constant(500), MixMode.MIXED_STEREO)
        for (i in 0 until frame) {
            assertEquals(1500, out[i * 2].toInt())
            assertEquals(1500, out[i * 2 + 1].toInt())
        }
    }

    @Test
    fun `remote only mutes the local source`() {
        val m = mixerAtUnityGain()
        val out = m.mix(constant(9000), constant(300), MixMode.REMOTE_ONLY)
        for (i in 0 until frame) {
            assertEquals(300, out[i * 2].toInt())
            assertEquals(300, out[i * 2 + 1].toInt())
        }
    }

    @Test
    fun `summing loud sources saturates instead of wrapping`() {
        val m = mixerAtUnityGain()
        val out = m.mix(constant(30000), constant(30000), MixMode.MIXED_STEREO)
        for (i in 0 until frame) {
            assertEquals(32767, out[i * 2].toInt())
        }
        val neg = m.mix(constant(-30000), constant(-30000), MixMode.MIXED_STEREO)
        for (i in 0 until frame) {
            assertEquals(-32768, neg[i * 2].toInt())
        }
    }

    @Test
    fun `priority mode ducks the local source while the remote is loud`() {
        val m = mixerAtUnityGain().apply { duckDepth = 0.8f }
        assertEquals(1f, m.duckGain, 1e-6f)

        // A loud remote frame should pull the gain down over a few frames.
        repeat(20) { m.mix(constant(5000), constant(8000), MixMode.PRIORITY) }
        assertTrue("duck gain was ${m.duckGain}", m.duckGain <= 0.21f)

        // Silence on the remote side releases it again.
        repeat(200) { m.mix(constant(5000), ShortArray(frame), MixMode.PRIORITY) }
        assertEquals(1f, m.duckGain, 0.01f)
    }

    @Test
    fun `priority mode leaves the local source alone when the remote is silent`() {
        val m = mixerAtUnityGain()
        repeat(10) { m.mix(constant(4000), ShortArray(frame), MixMode.PRIORITY) }
        assertEquals(1f, m.duckGain, 1e-6f)
    }

    @Test
    fun `other modes never duck`() {
        val m = mixerAtUnityGain()
        repeat(50) { m.mix(constant(4000), constant(20000), MixMode.MIXED_STEREO) }
        assertEquals(1f, m.duckGain, 1e-6f)
    }

    @Test
    fun `volumes scale each source independently`() {
        val m = Mixer(frame).apply {
            localVolume = 0.5f
            remoteVolume = 0.25f
        }
        val out = m.mix(constant(1000), constant(1000), MixMode.HARD_SPLIT)
        assertEquals(500, out[0].toInt())
        assertEquals(250, out[1].toInt())
    }

    @Test
    fun `balance attenuates the opposite channel`() {
        val m = mixerAtUnityGain().apply { balance = -1f }   // hard left
        val out = m.mix(constant(1000), constant(1000), MixMode.MIXED_STEREO)
        assertEquals(2000, out[0].toInt())
        assertEquals(0, out[1].toInt())
    }

    @Test
    fun `a null source is treated as silence`() {
        val m = mixerAtUnityGain()
        val out = m.mix(null, constant(777), MixMode.MIXED_STEREO)
        for (i in 0 until frame) assertTrue(abs(out[i * 2] - 777) < 2)
    }
}
