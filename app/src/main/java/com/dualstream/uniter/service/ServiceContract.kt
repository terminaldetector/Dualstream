package com.dualstream.uniter.service

/** Intent actions and extras shared by the UI and the two services. */
object ServiceContract {
    const val ACTION_START = "com.dualstream.uniter.action.START"
    const val ACTION_STOP = "com.dualstream.uniter.action.STOP"
    const val ACTION_RECONNECT = "com.dualstream.uniter.action.RECONNECT"
    const val ACTION_APPLY_SETTINGS = "com.dualstream.uniter.action.APPLY_SETTINGS"

    /** MediaProjection consent result, transmitter only. */
    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_RESULT_DATA = "result_data"

    /** Peer MAC address, transmitter only. */
    const val EXTRA_PEER_ADDRESS = "peer_address"

    /**
     * Name of the [com.dualstream.uniter.core.InputSource] the transmitter
     * should use. Absent means "whatever is stored in the settings".
     */
    const val EXTRA_INPUT_SOURCE = "input_source"
}
