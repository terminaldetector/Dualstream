package com.dualstream.uniter.audio

/**
 * Decides which audio input this app is willing to record from.
 *
 * This is the rule that keeps the two-microphone setup working. If the app
 * simply asks for "the microphone" while a Bluetooth headset is connected,
 * Android is free to route the input to the headset's microphone. Doing so
 * pulls the headset into the hands-free profile, which takes the A2DP stream
 * down with it — the headset stops being a stereo sink and the whole bridge
 * collapses. On top of that, two phones cannot both record one headset
 * microphone: the system hands it to one of them and silences the other.
 *
 * So the policy is narrow on purpose: record the phone's own built-in
 * microphone, nothing else. Each phone then records its own hardware and the
 * two never compete.
 *
 * The constants mirror `android.media.AudioDeviceInfo`. They are duplicated as
 * plain integers so this logic stays testable without the Android framework;
 * [MicCaptureSource] asserts at runtime that they still agree with the
 * platform.
 */
object InputRoutePolicy {

    const val TYPE_UNKNOWN = 0
    const val TYPE_WIRED_HEADSET = 3
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_USB_DEVICE = 11
    const val TYPE_BUILTIN_MIC = 15
    const val TYPE_TELEPHONY = 18
    const val TYPE_USB_HEADSET = 22
    const val TYPE_REMOTE_SUBMIX = 25
    const val TYPE_BLE_HEADSET = 26

    /** The only route this app records from. */
    fun isAcceptable(deviceType: Int): Boolean = deviceType == TYPE_BUILTIN_MIC

    /** True for inputs that would drag a Bluetooth headset into hands-free mode. */
    fun isBluetoothInput(deviceType: Int): Boolean =
        deviceType == TYPE_BLUETOOTH_SCO || deviceType == TYPE_BLE_HEADSET

    /**
     * Picks the input to pin to, out of the device types the platform reports.
     * @return the chosen type, or null when the phone exposes no built-in microphone
     */
    fun choose(availableTypes: List<Int>): Int? =
        if (availableTypes.contains(TYPE_BUILTIN_MIC)) TYPE_BUILTIN_MIC else null

    fun describe(deviceType: Int): String = when (deviceType) {
        TYPE_BUILTIN_MIC -> "built-in microphone"
        TYPE_BLUETOOTH_SCO -> "Bluetooth hands-free microphone"
        TYPE_BLE_HEADSET -> "Bluetooth LE headset microphone"
        TYPE_WIRED_HEADSET -> "wired headset microphone"
        TYPE_USB_HEADSET -> "USB headset microphone"
        TYPE_USB_DEVICE -> "USB audio device"
        TYPE_TELEPHONY -> "telephony input"
        TYPE_REMOTE_SUBMIX -> "remote submix"
        TYPE_UNKNOWN -> "unknown input"
        else -> "input type $deviceType"
    }

    /**
     * Why a route was refused, for the UI. Null when the route is fine.
     */
    fun rejectionReason(deviceType: Int): String? = when {
        isAcceptable(deviceType) -> null
        isBluetoothInput(deviceType) ->
            "Android routed recording to the ${describe(deviceType)}. That switches the headset " +
                "to the hands-free profile and stops the A2DP stream, and two phones cannot share " +
                "one headset microphone. Only the built-in microphone is used."
        else ->
            "Android routed recording to the ${describe(deviceType)} instead of the built-in " +
                "microphone. Disconnect that input, or switch the source to app audio."
    }
}
