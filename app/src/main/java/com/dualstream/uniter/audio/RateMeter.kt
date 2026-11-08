package com.dualstream.uniter.audio

/**
 * Measures what the encoder is actually putting on the wire, against wall
 * clock and against the audio it represents.
 *
 * This exists because the first field run showed a stream requested at
 * 32 kbit/s arriving at roughly 200 kbit/s: the platform Opus encoder emitted
 * far more, far smaller packets than the frame size it was fed. Nothing in the
 * app noticed, and the symptom reached the user as "the link dies after a
 * while" — RFCOMM sharing a radio with A2DP cannot carry that. A codec that
 * ignores its bitrate has to be caught by measurement, not assumed away.
 *
 * All times are supplied by the caller so this is testable without a clock.
 */
class RateMeter(private val windowMs: Long = 3_000) {

    private var windowStart = 0L
    private var bytes = 0L
    private var packets = 0L
    private var samples = 0L

    /** Totals since the last [reset], for the lifetime figures. */
    var totalBytes = 0L
        private set
    var totalPackets = 0L
        private set

    /** Last completed window, or null until one has elapsed. */
    var bitsPerSecond = 0
        private set
    var packetsPerSecond = 0
        private set

    /**
     * Audio seconds produced per wall-clock second. 1.0 is real time; above 1
     * means the encoder is emitting faster than the audio it covers, which is
     * how a backlog — and therefore growing latency — builds up.
     */
    var realtimeRatio = 0f
        private set

    var windowsCompleted = 0L
        private set

    /**
     * @param packetBytes bytes handed to the transport, headers included
     * @param audioSamples samples of audio those bytes represent
     * @return true when a window just closed and the readings were updated
     */
    fun record(nowMs: Long, packetBytes: Int, audioSamples: Int, sampleRate: Int): Boolean {
        if (windowStart == 0L) windowStart = nowMs
        bytes += packetBytes
        packets++
        samples += audioSamples
        totalBytes += packetBytes
        totalPackets++

        val elapsed = nowMs - windowStart
        if (elapsed < windowMs) return false

        bitsPerSecond = ((bytes * 8 * 1000) / elapsed).toInt()
        packetsPerSecond = ((packets * 1000) / elapsed).toInt()
        realtimeRatio =
            if (sampleRate > 0) (samples * 1000.0 / sampleRate / elapsed).toFloat() else 0f
        windowsCompleted++

        windowStart = nowMs
        bytes = 0
        packets = 0
        samples = 0
        return true
    }

    /** True when the measured rate exceeds [targetBps] by more than [margin]. */
    fun isOverBudget(targetBps: Int, margin: Float = 1.5f): Boolean =
        windowsCompleted > 0 && bitsPerSecond > targetBps * margin

    fun reset() {
        windowStart = 0
        bytes = 0
        packets = 0
        samples = 0
        totalBytes = 0
        totalPackets = 0
        bitsPerSecond = 0
        packetsPerSecond = 0
        realtimeRatio = 0f
        windowsCompleted = 0
    }
}
