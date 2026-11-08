package com.dualstream.uniter

import com.dualstream.uniter.core.Role
import com.dualstream.uniter.transport.Blocker
import com.dualstream.uniter.transport.ConnectionReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionReadinessTest {

    private fun receiver(
        bluetoothOn: Boolean = true,
        hasPermissions: Boolean = true,
        discoverable: Boolean = true,
        peerSelected: Boolean = false,
        bondedToPeer: Boolean = false
    ) = ConnectionReadiness.evaluate(
        Role.RECEIVER, bluetoothOn, hasPermissions, discoverable, peerSelected, bondedToPeer
    )

    private fun transmitter(
        bluetoothOn: Boolean = true,
        hasPermissions: Boolean = true,
        discoverable: Boolean = false,
        peerSelected: Boolean = true,
        bondedToPeer: Boolean = true
    ) = ConnectionReadiness.evaluate(
        Role.TRANSMITTER, bluetoothOn, hasPermissions, discoverable, peerSelected, bondedToPeer
    )

    @Test
    fun `an unpaired receiver that is not visible is blocked on visibility`() {
        val r = receiver(discoverable = false)
        assertFalse(r.ready)
        assertEquals(Blocker.NOT_DISCOVERABLE, r.first)
    }

    @Test
    fun `a visible receiver with nothing paired is ready to be found`() {
        assertTrue(receiver(discoverable = true).ready)
    }

    @Test
    fun `once paired the receiver no longer needs to be visible`() {
        // This is the point of tracking it: demanding visibility forever would
        // nag about a step that has already done its job.
        val r = receiver(discoverable = false, peerSelected = true, bondedToPeer = true)
        assertTrue(r.ready)
        assertFalse(r.blockers.contains(Blocker.NOT_DISCOVERABLE))
    }

    @Test
    fun `a receiver does not need a peer selected`() {
        val r = receiver(peerSelected = false, discoverable = true)
        assertTrue(r.ready)
        assertFalse(r.blockers.contains(Blocker.NO_PEER_SELECTED))
    }

    @Test
    fun `a transmitter with no peer chosen is blocked on that`() {
        val r = transmitter(peerSelected = false, bondedToPeer = false)
        assertFalse(r.ready)
        assertEquals(Blocker.NO_PEER_SELECTED, r.first)
        // Reporting "not paired" as well would be noise: there is nothing to pair with yet.
        assertFalse(r.blockers.contains(Blocker.PEER_NOT_BONDED))
    }

    @Test
    fun `a transmitter with an unpaired peer is blocked on pairing`() {
        val r = transmitter(peerSelected = true, bondedToPeer = false)
        assertFalse(r.ready)
        assertEquals(Blocker.PEER_NOT_BONDED, r.first)
    }

    @Test
    fun `a transmitter never needs to be visible itself`() {
        val r = transmitter(discoverable = false)
        assertTrue(r.ready)
        assertFalse(r.blockers.contains(Blocker.NOT_DISCOVERABLE))
    }

    @Test
    fun `bluetooth off comes before everything else`() {
        for (r in listOf(
            receiver(bluetoothOn = false, discoverable = false),
            transmitter(bluetoothOn = false, peerSelected = false)
        )) {
            assertEquals(Blocker.BLUETOOTH_OFF, r.first)
        }
    }

    @Test
    fun `a missing permission is reported before role specific steps`() {
        val r = receiver(hasPermissions = false, discoverable = false)
        assertEquals(Blocker.NO_PERMISSION, r.first)
        assertTrue(r.blockers.contains(Blocker.NOT_DISCOVERABLE))
    }

    @Test
    fun `every blocker has advice for both roles`() {
        for (b in Blocker.entries) {
            for (role in Role.entries) {
                val advice = ConnectionReadiness.advice(b, role)
                assertTrue("$b/$role was empty", advice.length > 10)
            }
        }
    }

    @Test
    fun `the visibility advice explains that listening is not enough`() {
        val advice = ConnectionReadiness.advice(Blocker.NOT_DISCOVERABLE, Role.RECEIVER)
        assertTrue(advice.contains("visible"))
        assertTrue(advice.contains("socket"))
    }

    @Test
    fun `summary is null when ready and the first blocker otherwise`() {
        assertNull(ConnectionReadiness.summary(receiver(discoverable = true), Role.RECEIVER))
        assertNotNull(
            ConnectionReadiness.summary(receiver(discoverable = false), Role.RECEIVER)
        )
    }
}
