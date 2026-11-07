package com.dualstream.uniter.audio

import com.dualstream.uniter.core.MixMode

/**
 * Combines the app-managed local source with the remote phone into one
 * interleaved stereo buffer, implementing the four modes of section 6.
 *
 * The mixer is deliberately stateless apart from the ducking envelope, so it is
 * unit-testable without any Android dependency.
 */
class Mixer(private val frameSamples: Int) {

    /** Linear gain currently applied to the local source by PRIORITY mode. */
    var duckGain: Float = 1f
        private set

    var localVolume: Float = 0.8f
    var remoteVolume: Float = 1.0f

    /** -1f fully left .. +1f fully right. Applied after mode handling. */
    var balance: Float = 0f

    /** Attenuation applied to the local source while remote audio is present. */
    var duckDepth: Float = 0.75f

    /** RMS above which the remote stream counts as "active" for PRIORITY. */
    var duckThreshold: Float = 0.015f

    private val out = ShortArray(frameSamples * 2)

    /**
     * @param local mono frame from the app-managed local source, or null for silence
     * @param remote mono frame decoded from phone B, or null for silence
     * @return interleaved stereo frame of [frameSamples] frames; the array is
     * reused between calls, so copy it if you need to keep it
     */
    fun mix(local: ShortArray?, remote: ShortArray?, mode: MixMode): ShortArray {
        val remoteRms = if (remote != null) Pcm.rms(remote, 0, minOf(remote.size, frameSamples)) else 0f
        updateDuck(mode, remoteRms)

        val lGain = when (mode) {
            MixMode.REMOTE_ONLY -> 0f
            MixMode.PRIORITY -> localVolume * duckGain
            else -> localVolume
        }
        val rGain = remoteVolume

        val panL = if (balance > 0f) 1f - balance else 1f
        val panR = if (balance < 0f) 1f + balance else 1f

        for (i in 0 until frameSamples) {
            val lv = sampleAt(local, i, lGain)
            val rv = sampleAt(remote, i, rGain)

            var leftOut: Int
            var rightOut: Int
            when (mode) {
                MixMode.HARD_SPLIT -> {
                    // Left ear carries the local source, right ear the remote phone.
                    leftOut = lv
                    rightOut = rv
                }
                MixMode.MIXED_STEREO, MixMode.PRIORITY -> {
                    leftOut = lv + rv
                    rightOut = lv + rv
                }
                MixMode.REMOTE_ONLY -> {
                    leftOut = rv
                    rightOut = rv
                }
            }
            leftOut = (leftOut * panL).toInt()
            rightOut = (rightOut * panR).toInt()

            out[i * 2] = clamp(leftOut)
            out[i * 2 + 1] = clamp(rightOut)
        }
        return out
    }

    private fun sampleAt(buf: ShortArray?, i: Int, gain: Float): Int {
        if (buf == null || i >= buf.size || gain == 0f) return 0
        return (buf[i] * gain).toInt()
    }

    private fun updateDuck(mode: MixMode, remoteRms: Float) {
        if (mode != MixMode.PRIORITY) {
            duckGain = 1f
            return
        }
        val floor = (1f - duckDepth).coerceIn(0f, 1f)
        duckGain = if (remoteRms > duckThreshold) {
            // Fast attack so the local source gets out of the way immediately.
            maxOf(floor, duckGain - ATTACK)
        } else {
            // Slow release so short pauses in speech do not pump the volume.
            minOf(1f, duckGain + RELEASE)
        }
    }

    private fun clamp(v: Int): Short = when {
        v > 32767 -> 32767
        v < -32768 -> -32768
        else -> v
    }.toShort()

    fun reset() {
        duckGain = 1f
    }

    private companion object {
        /** Per-frame envelope steps; at 20 ms frames this is ~120 ms attack. */
        const val ATTACK = 0.18f
        const val RELEASE = 0.02f
    }
}
