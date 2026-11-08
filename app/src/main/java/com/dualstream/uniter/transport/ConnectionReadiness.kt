package com.dualstream.uniter.transport

import com.dualstream.uniter.core.Role

/**
 * What is still missing before the two phones can connect.
 *
 * Pulled out as plain logic with no Android dependency, because getting this
 * wrong is what makes the product look broken: the user presses Start, nothing
 * happens, and the app says only "waiting". Each blocker below names the one
 * action that actually unblocks it.
 */
enum class Blocker {
    BLUETOOTH_OFF,
    NO_PERMISSION,

    /**
     * Phone A is not discoverable and the peer is not paired yet, so phone B
     * cannot find it at all. This is the step that is easy to miss: an RFCOMM
     * server socket does not make the adapter visible, and without visibility
     * no amount of scanning on phone B will ever show phone A.
     */
    NOT_DISCOVERABLE,

    NO_PEER_SELECTED,
    PEER_NOT_BONDED
}

data class Readiness(
    val ready: Boolean,
    val blockers: List<Blocker>
) {
    val first: Blocker? get() = blockers.firstOrNull()
}

object ConnectionReadiness {

    /**
     * @param bondedToPeer whether the chosen peer is already paired with this phone
     * @param discoverable whether this phone is currently visible to other devices
     * @param peerSelected whether a trusted peer address has been chosen
     */
    fun evaluate(
        role: Role,
        bluetoothOn: Boolean,
        hasPermissions: Boolean,
        discoverable: Boolean,
        peerSelected: Boolean,
        bondedToPeer: Boolean
    ): Readiness {
        val blockers = ArrayList<Blocker>(3)
        if (!bluetoothOn) blockers.add(Blocker.BLUETOOTH_OFF)
        if (!hasPermissions) blockers.add(Blocker.NO_PERMISSION)

        when (role) {
            Role.RECEIVER ->
                // Visibility only matters until the pair is bonded. Afterwards
                // phone B connects straight to the stored address, so demanding
                // discoverability forever would be noise.
                if (!bondedToPeer && !discoverable) blockers.add(Blocker.NOT_DISCOVERABLE)

            Role.TRANSMITTER -> {
                if (!peerSelected) {
                    blockers.add(Blocker.NO_PEER_SELECTED)
                } else if (!bondedToPeer) {
                    blockers.add(Blocker.PEER_NOT_BONDED)
                }
            }
        }
        return Readiness(blockers.isEmpty(), blockers)
    }

    /** The single next action for a blocker, phrased for the role that sees it. */
    fun advice(blocker: Blocker, role: Role): String = when (blocker) {
        Blocker.BLUETOOTH_OFF ->
            "Turn Bluetooth on."

        Blocker.NO_PERMISSION ->
            "Grant the Nearby devices permission."

        Blocker.NOT_DISCOVERABLE ->
            "Make this phone visible, then search for it on phone B and pair. " +
                "A listening socket is not enough: until this phone is discoverable, " +
                "phone B cannot see it at all. Once the two are paired this is no " +
                "longer needed."

        Blocker.NO_PEER_SELECTED ->
            if (role == Role.TRANSMITTER)
                "Open Devices and pick phone A — the phone with the headset."
            else
                "Open Devices and pick the other phone."

        Blocker.PEER_NOT_BONDED ->
            "Pair with phone A first. Make it visible on phone A, then press Pair here. " +
                "The audio channel is a secure RFCOMM socket, so it needs a system bond."
    }

    /** One line for the main screen; null when nothing is blocking. */
    fun summary(readiness: Readiness, role: Role): String? =
        readiness.first?.let { advice(it, role) }
}
