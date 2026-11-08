package com.dualstream.uniter.core

/** Which half of the bridge this phone acts as. */
enum class Role {
    /** Phone A. Owns the Bluetooth headset, runs the RFCOMM server, mixes and plays. */
    RECEIVER,

    /** Phone B. Captures its own playback and pushes encoded frames over RFCOMM. */
    TRANSMITTER
}

/** Output routing modes of section 6 of the specification. */
enum class MixMode(val label: String) {
    /** Left ear = local (app-managed) source, right ear = remote phone. */
    HARD_SPLIT("Hard Split"),

    /**
     * Left ear is phone A and nothing else; right ear is phone B and nothing
     * else. No cross-feed, no balance, no ducking, and above all no fallback:
     * a missing source silences its own channel and leaves the other running.
     *
     * Separate from [HARD_SPLIT] on purpose. Hard Split degrades to Remote Only
     * when there is no local source, which is friendlier but changes what the
     * mode means; here the contract is that each ear carries exactly one
     * device, and silence is the correct answer when that device has nothing.
     */
    STRICT_HARD_SPLIT("Strict Split"),

    /** Both sources summed into both ears. */
    MIXED_STEREO("Mixed Stereo"),

    /** Phone A behaves purely as a Bluetooth audio sink for phone B. */
    REMOTE_ONLY("Remote Only"),

    /** Local source is ducked automatically while the remote stream is active. */
    PRIORITY("Priority")
}

/** Transport-level link state, shared by both roles. */
enum class LinkState {
    IDLE,
    LISTENING,
    CONNECTING,
    HANDSHAKING,
    CONNECTED,
    RECOVERING,
    BLUETOOTH_OFF,
    NO_PERMISSION,
    FAILED
}

/** State of the capture side on phone B. */
enum class CaptureState {
    IDLE,

    /** MediaProjection consent not granted (or revoked by the system). */
    NEEDS_CONSENT,

    /** AudioRecord is running and delivering non-silent audio. */
    CAPTURING,

    /**
     * AudioRecord is running but every frame is digital silence. On Android this
     * normally means the apps that are playing did not allow playback capture.
     * The specification explicitly forbids reporting this as a live stream.
     */
    CAPTURING_SILENCE,

    /** Capture could not be started at all. */
    UNAVAILABLE,

    /** Operator chose the direct PCM input instead of system capture. */
    DIRECT_PCM,

    /** This phone's built-in microphone is the source. */
    MIC,

    /** Playback capture and the built-in microphone are being summed. */
    MIC_AND_PLAYBACK,

    /**
     * Android's audio policy silenced our recorder: another client took the
     * input, or the app lost its foreground claim on the microphone. The frames
     * keep arriving and are all zeros, which must never be shown as a stream.
     */
    SILENCED_BY_POLICY,

    /**
     * Capture is running, but the input was routed somewhere other than the
     * built-in microphone — typically the Bluetooth headset. That is the case
     * the two-microphone setup has to avoid: grabbing the headset's microphone
     * pulls it into the hands-free profile and takes A2DP down with it.
     */
    WRONG_INPUT_ROUTE
}

/**
 * Which source feeds phone A's own ear.
 *
 * An explicit choice, because deriving it from whichever capability happened
 * to be available made the microphone setting look ignored: a projection token
 * existing was enough to override it.
 */
enum class LocalInputMode(val label: String) {
    /** Whatever any app on this phone is playing, via MediaProjection. */
    APP_AUDIO("App audio"),

    /** This phone's built-in microphone, pinned to the built-in device. */
    BUILTIN_MIC("Microphone"),

    /** A track chosen in the app, or the built-in test tone. */
    FILE_OR_TONE("Track"),

    /** Nothing: this phone contributes silence to its ear. */
    NONE("None")
}

/**
 * What phone B feeds into the encoder, and what phone A can use as its local
 * source.
 *
 * The microphone options exist so that the two phones can contribute their own
 * built-in microphones independently. Each phone records its own hardware, so
 * the two recorders never compete for one microphone — which is exactly what
 * would happen if both tried to use the headset's microphone, and the system
 * would then cut the input on both.
 */
enum class InputSource(val label: String, val needsProjection: Boolean, val needsMic: Boolean) {
    /** MediaProjection + AudioPlaybackCapture: what other apps are playing. */
    PLAYBACK_CAPTURE("App audio", true, false),

    /** This phone's own built-in microphone, pinned to the built-in device. */
    BUILTIN_MIC("Built-in mic", false, true),

    /** Both of the above, summed before encoding. */
    PLAYBACK_AND_MIC("App audio + mic", true, true),

    /** Generated signal through the direct PCM input. Stage 1 bring-up. */
    TEST_TONE("Test tone", false, false)
}

/** State of the A2DP headset on phone A. */
enum class HeadsetState {
    UNKNOWN,
    DISCONNECTED,
    CONNECTED_A2DP,

    /**
     * Headset connected, but the device reports that running RFCOMM and A2DP at
     * the same time degrades the link (section 7).
     */
    CONNECTED_DEGRADED
}

/** Codecs the transport can negotiate. Numeric ids travel on the wire. */
enum class CodecId(val wire: Int, val label: String) {
    /** Opus through MediaCodec. Preferred, 48 kHz. */
    OPUS(1, "Opus"),

    /** IMA-ADPCM at a reduced sample rate. Pure Kotlin, always available. */
    ADPCM(2, "ADPCM"),

    /** Raw PCM16. Diagnostics only: it does not fit a real RFCOMM link. */
    PCM16(3, "PCM16 (raw)");

    companion object {
        fun fromWire(v: Int): CodecId? = entries.firstOrNull { it.wire == v }
    }
}
