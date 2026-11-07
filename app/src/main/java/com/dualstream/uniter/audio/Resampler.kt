package com.dualstream.uniter.audio

/**
 * Linear-interpolating mono resampler with carry-over state, so successive
 * calls join seamlessly.
 *
 * Linear interpolation is chosen deliberately: it costs almost nothing on the
 * audio thread and the codec rates in use here (8/12/16/48 kHz) are band
 * limited by the codec itself. If measurements in Stage 4 show audible
 * aliasing, replace this with a polyphase FIR without touching the callers.
 */
class Resampler(
    private val inRate: Int,
    private val outRate: Int
) {
    val bypass: Boolean get() = inRate == outRate

    private var phase = 0.0
    private var last: Short = 0
    private var primed = false

    /** Worst-case output length for [inputSamples] input samples. */
    fun maxOut(inputSamples: Int): Int =
        (inputSamples.toLong() * outRate / inRate).toInt() + 2

    /**
     * Resamples [count] samples from [src] into [dst].
     * @return number of samples written to [dst].
     */
    fun process(src: ShortArray, offset: Int, count: Int, dst: ShortArray): Int {
        if (bypass) {
            val n = minOf(count, dst.size)
            src.copyInto(dst, 0, offset, offset + n)
            return n
        }
        if (count <= 0) return 0
        val step = inRate.toDouble() / outRate.toDouble()
        var w = 0
        if (!primed) {
            last = src[offset]
            primed = true
        }
        while (w < dst.size) {
            val idx = phase.toInt()
            if (idx >= count) break
            val frac = phase - idx
            val a = if (idx == 0) last.toInt() else src[offset + idx - 1].toInt()
            val b = src[offset + idx].toInt()
            dst[w++] = (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
            phase += step
        }
        last = src[offset + count - 1]
        phase -= count
        if (phase < 0) phase = 0.0
        return w
    }

    fun reset() {
        phase = 0.0
        last = 0
        primed = false
    }
}
