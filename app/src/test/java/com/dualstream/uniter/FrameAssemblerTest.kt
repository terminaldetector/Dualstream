package com.dualstream.uniter

import com.dualstream.uniter.audio.FrameAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAssemblerTest {

    @Test
    fun `odd sized input is regrouped onto the frame grid`() {
        val a = FrameAssembler(100)
        val frames = mutableListOf<ShortArray>()
        val src = ShortArray(250) { it.toShort() }
        a.submit(src, 0, 250) { frames.add(it) }
        assertEquals(2, frames.size)
        assertEquals(100, frames[0].size)
        assertEquals(0, frames[0][0].toInt())
        assertEquals(100, frames[1][0].toInt())
    }

    @Test
    fun `the remainder is carried into the next submission`() {
        val a = FrameAssembler(100)
        val frames = mutableListOf<ShortArray>()
        a.submit(ShortArray(60) { 1 }, 0, 60) { frames.add(it) }
        assertEquals(0, frames.size)
        a.submit(ShortArray(60) { 2 }, 0, 60) { frames.add(it) }
        assertEquals(1, frames.size)
        assertEquals(1, frames[0][0].toInt())
        assertEquals(2, frames[0][99].toInt())
    }

    @Test
    fun `no samples are lost across many submissions`() {
        val a = FrameAssembler(64)
        var total = 0
        repeat(100) { a.submit(ShortArray(37), 0, 37) { total += it.size } }
        assertTrue("emitted $total of 3700", total == 3700 / 64 * 64)
    }

    @Test
    fun `reset discards the partial frame`() {
        val a = FrameAssembler(50)
        val frames = mutableListOf<ShortArray>()
        a.submit(ShortArray(30), 0, 30) { frames.add(it) }
        a.reset()
        a.submit(ShortArray(30), 0, 30) { frames.add(it) }
        assertEquals(0, frames.size)
    }
}
