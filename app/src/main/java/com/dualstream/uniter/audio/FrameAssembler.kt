package com.dualstream.uniter.audio

/**
 * Regroups a stream of arbitrary-length mono buffers into fixed-size frames.
 *
 * Needed because a codec's output length is not guaranteed to match the
 * playback frame grid: Opus may hand back a different number of samples than
 * were fed in, and the resampler rounds. Everything downstream of this class
 * sees exactly [frameSamples] per frame.
 */
class FrameAssembler(val frameSamples: Int) {

    private var acc = ShortArray(frameSamples * 4)
    private var filled = 0

    /** Feeds samples and emits every complete frame through [onFrame]. */
    fun submit(src: ShortArray, offset: Int, count: Int, onFrame: (ShortArray) -> Unit) {
        var read = 0
        while (read < count) {
            val need = frameSamples - filled
            val take = minOf(need, count - read)
            if (acc.size < frameSamples) acc = ShortArray(frameSamples)
            src.copyInto(acc, filled, offset + read, offset + read + take)
            filled += take
            read += take
            if (filled == frameSamples) {
                onFrame(acc.copyOf(frameSamples))
                filled = 0
            }
        }
    }

    fun reset() {
        filled = 0
    }
}
