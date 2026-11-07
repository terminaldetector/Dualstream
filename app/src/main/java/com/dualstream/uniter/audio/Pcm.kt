package com.dualstream.uniter.audio

import kotlin.math.abs
import kotlin.math.sqrt

/** Small shared PCM helpers. All of them are allocation-free on the hot path. */
object Pcm {

    /** Playback output format: everything downstream of the mixer uses this. */
    const val OUT_RATE = 48_000
    const val OUT_CHANNELS = 2

    /** Averages an interleaved stereo buffer into [dst], returning sample count. */
    fun downmixStereoToMono(src: ShortArray, srcSamples: Int, dst: ShortArray): Int {
        val frames = minOf(srcSamples / 2, dst.size)
        var i = 0
        for (f in 0 until frames) {
            val l = src[i].toInt()
            val r = src[i + 1].toInt()
            dst[f] = ((l + r) shr 1).toShort()
            i += 2
        }
        return frames
    }

    /** RMS of a mono buffer normalised to 0f..1f. */
    fun rms(buf: ShortArray, offset: Int = 0, count: Int = buf.size - offset): Float {
        if (count <= 0) return 0f
        var acc = 0.0
        for (i in offset until offset + count) {
            val v = buf[i].toDouble()
            acc += v * v
        }
        return (sqrt(acc / count) / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    /** True when every sample is zero, i.e. digital silence. */
    fun isSilent(buf: ShortArray, offset: Int = 0, count: Int = buf.size - offset): Boolean {
        for (i in offset until offset + count) if (buf[i] != 0.toShort()) return false
        return true
    }

    /** True when the buffer never exceeds [threshold], used for near-silence. */
    fun isBelow(buf: ShortArray, threshold: Int, offset: Int = 0, count: Int = buf.size - offset): Boolean {
        for (i in offset until offset + count) if (abs(buf[i].toInt()) > threshold) return false
        return true
    }

    /** Saturating 16-bit add. */
    fun mixSample(a: Int, b: Int): Short {
        val s = a + b
        return when {
            s > 32767 -> 32767
            s < -32768 -> -32768
            else -> s
        }.toShort()
    }

    /** Applies [gain] in place. */
    fun applyGain(buf: ShortArray, gain: Float, offset: Int = 0, count: Int = buf.size - offset) {
        if (gain == 1f) return
        for (i in offset until offset + count) {
            buf[i] = (buf[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * Linear fade-out, used to taper the last good frame instead of clicking.
     *
     * The ramp reaches exactly zero on the final sample. That matters: a tail
     * that stops at 1/count of full scale is still a step discontinuity, which
     * is the very click the concealment exists to avoid.
     */
    fun fadeOut(buf: ShortArray, count: Int = buf.size) {
        if (count <= 0) return
        for (i in 0 until count) {
            val g = 1f - (i + 1).toFloat() / count
            buf[i] = (buf[i] * g).toInt().toShort()
        }
    }
}
