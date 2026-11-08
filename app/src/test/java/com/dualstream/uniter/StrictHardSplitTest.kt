package com.dualstream.uniter

import com.dualstream.uniter.audio.Mixer
import com.dualstream.uniter.audio.MixerControls
import com.dualstream.uniter.core.MixMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Strict Split contract: phone A is the left ear and nothing else, phone B
 * is the right ear and nothing else.
 *
 * These assert on the produced PCM rather than on the mode that was asked for,
 * because every way this has gone wrong so far has been a silent substitution
 * somewhere between the two.
 */
class StrictHardSplitTest {

    private val frame = 32
    private val strict = MixMode.STRICT_HARD_SPLIT

    private fun constant(v: Int) = ShortArray(frame) { v.toShort() }

    private fun mixer(gainA: Float = 1f, gainB: Float = 1f) = Mixer(frame).apply {
        localVolume = gainA
        remoteVolume = gainB
    }

    private fun left(out: ShortArray) = IntArray(frame) { out[it * 2].toInt() }
    private fun right(out: ShortArray) = IntArray(frame) { out[it * 2 + 1].toInt() }

    @Test
    fun `only A plays, so the right ear is silent and A is not copied into it`() {
        val out = mixer().mix(constant(5000), null, strict)
        assertTrue(left(out).all { it == 5000 })
        assertTrue(right(out).all { it == 0 })
    }

    @Test
    fun `only B plays, so the left ear is silent and B is not copied into it`() {
        val out = mixer().mix(null, constant(7000), strict)
        assertTrue(left(out).all { it == 0 })
        assertTrue(right(out).all { it == 7000 })
    }

    @Test
    fun `with both playing neither sample ever reaches the other ear`() {
        val out = mixer().mix(constant(1234), constant(-4321), strict)
        assertTrue(left(out).all { it == 1234 })
        assertTrue(right(out).all { it == -4321 })
        // No sum anywhere: a mix would show 1234 + (-4321) on at least one side.
        assertFalse(left(out).any { it == 1234 - 4321 })
        assertFalse(right(out).any { it == 1234 - 4321 })
    }

    @Test
    fun `each side has its own gain and one does not move the other`() {
        val out = mixer(gainA = 0.5f, gainB = 0.25f).mix(constant(1000), constant(1000), strict)
        assertTrue(left(out).all { it == 500 })
        assertTrue(right(out).all { it == 250 })
    }

    @Test
    fun `silencing one side leaves the other untouched`() {
        val m = mixer()
        val both = m.mix(constant(3000), constant(6000), strict)
        val onlyA = m.mix(constant(3000), null, strict)
        assertEquals(left(both).toList(), left(onlyA).toList())
        assertTrue(right(onlyA).all { it == 0 })
    }

    @Test
    fun `balance cannot move energy between the channels in strict mode`() {
        val m = mixer().apply { balance = -1f }   // hard left in the other modes
        val out = m.mix(constant(2000), constant(2000), strict)
        assertTrue("right must survive a hard-left balance", right(out).all { it == 2000 })
        assertTrue(left(out).all { it == 2000 })
    }

    @Test
    fun `a loud remote side never ducks the local one`() {
        val m = mixer().apply { duckDepth = 0.9f }
        repeat(50) { m.mix(constant(4000), constant(20000), strict) }
        assertEquals(1f, m.duckGain, 1e-6f)
        val out = m.mix(constant(4000), constant(20000), strict)
        assertTrue(left(out).all { it == 4000 })
    }

    @Test
    fun `a short source buffer is padded with its own silence, not the neighbour's audio`() {
        val shortLocal = ShortArray(frame / 2) { 900 }
        val out = mixer().mix(shortLocal, constant(-900), strict)
        val l = left(out)
        for (i in 0 until frame / 2) assertEquals(900, l[i])
        for (i in frame / 2 until frame) assertEquals("sample $i leaked", 0, l[i])
        assertTrue(right(out).all { it == -900 })
    }

    @Test
    fun `strict mode never falls back when a source is missing`() {
        for (has in listOf(true, false)) {
            assertEquals(strict, MixerControls.effectiveMode(strict, hasLocalSource = has))
            assertFalse(MixerControls.isFallingBack(strict, hasLocalSource = has))
        }
        // The friendlier mode still degrades; the two must not be confused.
        assertNotEquals(
            MixMode.HARD_SPLIT,
            MixerControls.effectiveMode(MixMode.HARD_SPLIT, hasLocalSource = false)
        )
    }

    @Test
    fun `strict mode does not demand a local source, because silence is correct`() {
        assertFalse(MixerControls.modeNeedsLocalSource(strict))
        // It still explains what the user will hear.
        assertTrue(
            MixerControls.explain(strict, hasLocalSource = false)!!.contains("silent")
        )
    }

    @Test
    fun `the other modes keep their behaviour`() {
        val m = mixer()
        val mixed = m.mix(constant(1000), constant(500), MixMode.MIXED_STEREO)
        assertTrue(left(mixed).all { it == 1500 } && right(mixed).all { it == 1500 })

        val remote = m.mix(constant(9000), constant(300), MixMode.REMOTE_ONLY)
        assertTrue(left(remote).all { it == 300 } && right(remote).all { it == 300 })

        val hard = m.mix(constant(100), constant(200), MixMode.HARD_SPLIT)
        assertTrue(left(hard).all { it == 100 } && right(hard).all { it == 200 })
    }

    @Test
    fun `saturation still applies per channel`() {
        val out = mixer(gainA = 4f, gainB = 4f).mix(constant(30000), constant(-30000), strict)
        assertTrue(left(out).all { it == 32767 })
        assertTrue(right(out).all { it == -32768 })
    }
}
