package com.dualstream.uniter.codec

/**
 * Picks the transmission bitrate from how the link is actually behaving
 * (section 4: switching between 24, 32, 48 and 64 kbit/s).
 *
 * The ladder only moves down quickly and up slowly. A Bluetooth link shared
 * with an A2DP headset degrades suddenly and recovers gradually, so reacting
 * fast to trouble and slowly to calm is what keeps it from oscillating between
 * two rates and sounding worse than either.
 *
 * Pure logic, no Android, so the policy is unit-tested rather than guessed at
 * from field reports.
 */
class BitrateController(
    /** The rate the user asked for; the controller never goes above it. */
    private val ceilingBps: Int = 32_000,
    startBps: Int = ceilingBps
) {
    var current: Int = nearestRung(startBps.coerceAtMost(ceilingBps))
        private set

    /** Consecutive healthy decisions, used to earn a step back up. */
    private var healthyRuns = 0

    /** Why the rate last changed, for the log and the UI. */
    var lastReason: String? = null
        private set

    /**
     * @param measuredBps what the encoder is really producing
     * @param underrunsDelta audio gaps on the receiver since the last call
     * @param rttMs round trip, or -1 when unknown
     * @param linkFailed a link drop happened since the last call
     * @return true when [current] changed
     */
    fun update(
        measuredBps: Int,
        underrunsDelta: Long,
        rttMs: Int,
        linkFailed: Boolean
    ): Boolean {
        val before = current

        val trouble = when {
            linkFailed -> "the link dropped"
            underrunsDelta > UNDERRUN_LIMIT -> "$underrunsDelta audio gaps"
            rttMs in (RTT_LIMIT_MS + 1)..10_000 -> "round trip ${rttMs}ms"
            // A codec exceeding its own budget is the one case where lowering
            // the request may not help, but sending less is still the only
            // lever this side has.
            measuredBps > current * OVER_BUDGET -> "encoder produced ${measuredBps / 1000}kbit/s"
            else -> null
        }

        if (trouble != null) {
            healthyRuns = 0
            stepDown()
            if (current != before) lastReason = "lowered: $trouble"
            return current != before
        }

        healthyRuns++
        if (healthyRuns >= HEALTHY_RUNS_TO_RAISE) {
            healthyRuns = 0
            stepUp()
            if (current != before) lastReason = "raised: link has been clean"
        }
        return current != before
    }

    /** Forces the ladder to its lowest rung, e.g. for power saving. */
    fun forceLowest() {
        current = RUNGS.first()
        healthyRuns = 0
        lastReason = "lowered: power saving"
    }

    fun reset(toBps: Int = ceilingBps) {
        current = nearestRung(toBps.coerceAtMost(ceilingBps))
        healthyRuns = 0
        lastReason = null
    }

    private fun stepDown() {
        val i = RUNGS.indexOf(current)
        if (i > 0) current = RUNGS[i - 1]
    }

    private fun stepUp() {
        val i = RUNGS.indexOf(current)
        if (i >= 0 && i < RUNGS.size - 1 && RUNGS[i + 1] <= ceilingBps) {
            current = RUNGS[i + 1]
        }
    }

    companion object {
        /** The rungs named by section 4, lowest first. */
        val RUNGS = intArrayOf(24_000, 32_000, 48_000, 64_000).toList()

        private const val UNDERRUN_LIMIT = 3L
        private const val RTT_LIMIT_MS = 400
        private const val OVER_BUDGET = 1.6f

        /** At one decision every few seconds, this is roughly half a minute. */
        private const val HEALTHY_RUNS_TO_RAISE = 10

        fun nearestRung(bps: Int): Int =
            RUNGS.minByOrNull { kotlin.math.abs(it - bps) } ?: 32_000
    }
}
