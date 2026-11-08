package com.dualstream.uniter

import com.dualstream.uniter.codec.BitrateController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateControllerTest {

    private fun healthy(c: BitrateController, times: Int) {
        repeat(times) { c.update(measuredBps = c.current, underrunsDelta = 0, rttMs = 40, linkFailed = false) }
    }

    @Test
    fun `starts at the requested rate`() {
        assertEquals(32_000, BitrateController(ceilingBps = 32_000).current)
        assertEquals(64_000, BitrateController(ceilingBps = 64_000).current)
    }

    @Test
    fun `a link drop lowers the rate immediately`() {
        val c = BitrateController(ceilingBps = 48_000)
        assertTrue(c.update(48_000, 0, 50, linkFailed = true))
        assertEquals(32_000, c.current)
        assertTrue(c.lastReason!!.contains("dropped"))
    }

    @Test
    fun `audio gaps lower the rate`() {
        val c = BitrateController(ceilingBps = 48_000)
        c.update(48_000, underrunsDelta = 10, rttMs = 50, linkFailed = false)
        assertEquals(32_000, c.current)
        assertTrue(c.lastReason!!.contains("gaps"))
    }

    @Test
    fun `a slow round trip lowers the rate`() {
        val c = BitrateController(ceilingBps = 32_000)
        c.update(32_000, 0, rttMs = 900, linkFailed = false)
        assertEquals(24_000, c.current)
    }

    @Test
    fun `an unknown round trip is not treated as trouble`() {
        val c = BitrateController(ceilingBps = 32_000)
        c.update(32_000, 0, rttMs = -1, linkFailed = false)
        assertEquals(32_000, c.current)
    }

    @Test
    fun `an encoder exceeding its budget lowers the rate`() {
        // The actual field failure: 32 kbit/s requested, ~200 produced.
        val c = BitrateController(ceilingBps = 32_000)
        assertTrue(c.update(measuredBps = 199_000, underrunsDelta = 0, rttMs = 40, linkFailed = false))
        assertEquals(24_000, c.current)
        assertTrue(c.lastReason!!.contains("encoder produced"))
    }

    @Test
    fun `it never goes below the lowest rung`() {
        val c = BitrateController(ceilingBps = 64_000)
        repeat(20) { c.update(0, 99, 50, linkFailed = true) }
        assertEquals(24_000, c.current)
    }

    @Test
    fun `it never goes above the ceiling the user asked for`() {
        val c = BitrateController(ceilingBps = 32_000)
        healthy(c, 500)
        assertEquals(32_000, c.current)
    }

    @Test
    fun `a clean link earns the rate back, but only slowly`() {
        val c = BitrateController(ceilingBps = 48_000)
        c.update(48_000, 0, 50, linkFailed = true)
        assertEquals(32_000, c.current)

        // One good decision must not undo the step down, or the rate oscillates.
        healthy(c, 3)
        assertEquals(32_000, c.current)

        healthy(c, 20)
        assertEquals(48_000, c.current)
        assertTrue(c.lastReason!!.contains("raised"))
    }

    @Test
    fun `trouble resets the progress towards a raise`() {
        val c = BitrateController(ceilingBps = 48_000)
        c.update(48_000, 0, 50, linkFailed = true)
        healthy(c, 8)
        c.update(32_000, underrunsDelta = 99, rttMs = 50, linkFailed = false)   // back to zero
        healthy(c, 8)
        assertEquals(24_000, c.current)   // the second drop happened, no raise yet
    }

    @Test
    fun `power saving forces the lowest rung`() {
        val c = BitrateController(ceilingBps = 64_000)
        c.forceLowest()
        assertEquals(24_000, c.current)
        assertNotNull(c.lastReason)
    }

    @Test
    fun `reset returns to the requested rate`() {
        val c = BitrateController(ceilingBps = 48_000)
        repeat(5) { c.update(0, 99, 50, linkFailed = true) }
        c.reset()
        assertEquals(48_000, c.current)
    }

    @Test
    fun `an arbitrary requested rate snaps to a rung`() {
        assertEquals(32_000, BitrateController.nearestRung(30_000))
        assertEquals(24_000, BitrateController.nearestRung(16_000))
        assertEquals(64_000, BitrateController.nearestRung(96_000))
    }
}
