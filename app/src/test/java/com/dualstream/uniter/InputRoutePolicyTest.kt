package com.dualstream.uniter

import com.dualstream.uniter.audio.InputRoutePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputRoutePolicyTest {

    @Test
    fun `only the built-in microphone is acceptable`() {
        assertTrue(InputRoutePolicy.isAcceptable(InputRoutePolicy.TYPE_BUILTIN_MIC))
        listOf(
            InputRoutePolicy.TYPE_BLUETOOTH_SCO,
            InputRoutePolicy.TYPE_BLE_HEADSET,
            InputRoutePolicy.TYPE_WIRED_HEADSET,
            InputRoutePolicy.TYPE_USB_HEADSET,
            InputRoutePolicy.TYPE_TELEPHONY,
            InputRoutePolicy.TYPE_UNKNOWN
        ).forEach { assertFalse(InputRoutePolicy.describe(it), InputRoutePolicy.isAcceptable(it)) }
    }

    @Test
    fun `bluetooth inputs are recognised because they break A2DP`() {
        assertTrue(InputRoutePolicy.isBluetoothInput(InputRoutePolicy.TYPE_BLUETOOTH_SCO))
        assertTrue(InputRoutePolicy.isBluetoothInput(InputRoutePolicy.TYPE_BLE_HEADSET))
        assertFalse(InputRoutePolicy.isBluetoothInput(InputRoutePolicy.TYPE_BUILTIN_MIC))
        assertFalse(InputRoutePolicy.isBluetoothInput(InputRoutePolicy.TYPE_WIRED_HEADSET))
    }

    @Test
    fun `the built-in microphone is chosen even when a headset is present`() {
        val available = listOf(
            InputRoutePolicy.TYPE_BLUETOOTH_SCO,
            InputRoutePolicy.TYPE_BUILTIN_MIC,
            InputRoutePolicy.TYPE_WIRED_HEADSET
        )
        assertEquals(InputRoutePolicy.TYPE_BUILTIN_MIC, InputRoutePolicy.choose(available))
    }

    @Test
    fun `no built-in microphone means no choice rather than a fallback`() {
        val available = listOf(
            InputRoutePolicy.TYPE_BLUETOOTH_SCO,
            InputRoutePolicy.TYPE_WIRED_HEADSET
        )
        assertNull(InputRoutePolicy.choose(available))
        assertNull(InputRoutePolicy.choose(emptyList()))
    }

    @Test
    fun `an acceptable route has no rejection reason`() {
        assertNull(InputRoutePolicy.rejectionReason(InputRoutePolicy.TYPE_BUILTIN_MIC))
    }

    @Test
    fun `a bluetooth route is rejected and the reason names the real consequence`() {
        val reason = InputRoutePolicy.rejectionReason(InputRoutePolicy.TYPE_BLUETOOTH_SCO)
        assertNotNull(reason)
        assertTrue(reason!!.contains("A2DP"))
        assertTrue(reason.contains("hands-free"))
    }

    @Test
    fun `other wrong routes are rejected with a usable message`() {
        val reason = InputRoutePolicy.rejectionReason(InputRoutePolicy.TYPE_USB_HEADSET)
        assertNotNull(reason)
        assertTrue(reason!!.contains("built-in"))
    }

    @Test
    fun `every known type has a readable description`() {
        listOf(
            InputRoutePolicy.TYPE_BUILTIN_MIC,
            InputRoutePolicy.TYPE_BLUETOOTH_SCO,
            InputRoutePolicy.TYPE_BLE_HEADSET,
            InputRoutePolicy.TYPE_WIRED_HEADSET,
            InputRoutePolicy.TYPE_USB_HEADSET,
            InputRoutePolicy.TYPE_USB_DEVICE,
            InputRoutePolicy.TYPE_TELEPHONY,
            InputRoutePolicy.TYPE_REMOTE_SUBMIX,
            InputRoutePolicy.TYPE_UNKNOWN
        ).forEach { assertFalse(InputRoutePolicy.describe(it).startsWith("input type")) }
        assertTrue(InputRoutePolicy.describe(999).contains("999"))
    }
}
