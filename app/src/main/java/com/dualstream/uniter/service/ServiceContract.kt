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

    /** When true the transmitter uses the direct PCM test tone instead of capture. */
    const val EXTRA_USE_TEST_TONE = "use_test_tone"
}
