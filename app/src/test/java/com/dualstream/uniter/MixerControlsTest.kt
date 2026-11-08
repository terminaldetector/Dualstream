package com.dualstream.uniter

import com.dualstream.uniter.audio.MixerControls
import com.dualstream.uniter.core.MixMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerControlsTest {

    @Test
    fun `a local source is a picked track or the microphone`() {
        assertTrue(MixerControls.hasLocalSource("content://x", false))
        assertTrue(MixerControls.hasLocalSource(null, true))
        assertFalse(MixerControls.hasLocalSource(null, false))
        assertFalse(MixerControls.hasLocalSource("", false))
        assertFalse(MixerControls.hasLocalSource("   ", false))
    }

    @Test
    fun `the local slider does nothing without a local source`() {
        for (mode in MixMode.entries) {
            assertFalse(
                "$mode",
                MixerControls.localVolumeEffective(hasLocalSource = false, mode = mode)
            )
        }
    }

    @Test
    fun `the local slider does nothing in remote only, even with a source`() {
        assertFalse(MixerControls.localVolumeEffective(true, MixMode.REMOTE_ONLY))
    }

    @Test
    fun `the local slider works with a source in the mixing modes`() {
        for (mode in listOf(MixMode.HARD_SPLIT, MixMode.MIXED_STEREO, MixMode.PRIORITY)) {
            assertTrue("$mode", MixerControls.localVolumeEffective(true, mode))
        }
    }

    @Test
    fun `remote only is the one mode that needs no local source`() {
        assertFalse(MixerControls.modeNeedsLocalSource(MixMode.REMOTE_ONLY))
        assertTrue(MixerControls.modeNeedsLocalSource(MixMode.HARD_SPLIT))
        assertTrue(MixerControls.modeNeedsLocalSource(MixMode.MIXED_STEREO))
        assertTrue(MixerControls.modeNeedsLocalSource(MixMode.PRIORITY))
    }

    @Test
    fun `nothing is explained away when a local source exists`() {
        for (mode in MixMode.entries) {
            assertNull("$mode", MixerControls.explain(mode, hasLocalSource = true))
        }
    }

    @Test
    fun `remote only says plainly that no local source is needed`() {
        val text = MixerControls.explain(MixMode.REMOTE_ONLY, hasLocalSource = false)
        assertNotNull(text)
        assertFalse("must not imply a fault", text!!.contains("cannot"))
    }

    @Test
    fun `hard split without a source says what it degrades to and why`() {
        val text = MixerControls.explain(MixMode.HARD_SPLIT, hasLocalSource = false)!!
        assertTrue(text.contains("Remote Only"))
        // The user has to learn that other apps' audio is out of reach, not
        // assume the app is simply broken.
        assertTrue(text.contains("other apps"))
        assertTrue(text.contains("re-route"))
    }

    @Test
    fun `the mixing modes explain that other apps cannot be intercepted`() {
        for (mode in listOf(MixMode.MIXED_STEREO, MixMode.PRIORITY)) {
            val text = MixerControls.explain(mode, hasLocalSource = false)!!
            assertTrue("$mode", text.contains("other apps"))
            assertTrue("$mode", text.contains("intercept"))
        }
    }

    @Test
    fun `without a local source the mixing modes fall back to remote only`() {
        // Otherwise Hard Split puts silence in one ear and, with the link down,
        // in both — which is what reached the user as "it muted everything".
        for (mode in listOf(MixMode.HARD_SPLIT, MixMode.MIXED_STEREO, MixMode.PRIORITY)) {
            assertTrue("$mode", MixerControls.isFallingBack(mode, hasLocalSource = false))
            org.junit.Assert.assertEquals(
                MixMode.REMOTE_ONLY,
                MixerControls.effectiveMode(mode, hasLocalSource = false)
            )
        }
    }

    @Test
    fun `with a local source every mode is used as chosen`() {
        for (mode in MixMode.entries) {
            org.junit.Assert.assertEquals(
                mode, MixerControls.effectiveMode(mode, hasLocalSource = true)
            )
            assertFalse("$mode", MixerControls.isFallingBack(mode, hasLocalSource = true))
        }
    }

    @Test
    fun `remote only is never a fallback from itself`() {
        assertFalse(MixerControls.isFallingBack(MixMode.REMOTE_ONLY, hasLocalSource = false))
    }

    @Test
    fun `the system volume note says it moves both sides together`() {
        assertTrue(MixerControls.SYSTEM_VOLUME_NOTE.contains("other apps"))
        assertTrue(MixerControls.SYSTEM_VOLUME_NOTE.contains("own output"))
    }
}
