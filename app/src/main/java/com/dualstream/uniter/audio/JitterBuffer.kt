package com.dualstream.uniter.audio

import com.dualstream.uniter.core.Logx

/**
 * Adaptive jitter buffer for decoded remote frames (section 4: "Буфер —
 * адаптивный", section 9: "предотвращение накопления задержки").
 *
 * All frames stored here are mono PCM at the playback rate, one [frameSamples]
 * chunk each. The buffer does three jobs:
 *
 *  1. absorbs RFCOMM burstiness by holding a small target depth;
 *  2. grows that target when underruns happen and shrinks it again when the
 *     link is calm, so a bad radio minute does not leave permanent latency;
 *  3. hard-drops the oldest frames above [maxFrames]. Latency that accumulates
 *     cannot be recovered any other way, and dropping old audio is always
 *     better than drifting further behind.
 */
class JitterBuffer(
    val frameSamples: Int,
    val frameMs: Int,
    private val minFrames: Int = 2,
    private val maxFrames: Int = 25,
    initialTarget: Int = 3
) {
    private val queue = ArrayDeque<ShortArray>()
    private val lock = Any()

    private var target = initialTarget.coerceIn(minFrames, maxFrames)
    private var priming = true
    private var lastFrame: ShortArray? = null

    var underruns: Long = 0
        private set
    var dropped: Long = 0
        private set

    val size: Int get() = synchronized(lock) { queue.size }
    val targetFrames: Int get() = synchronized(lock) { target }
    val bufferedMs: Int get() = synchronized(lock) { queue.size * frameMs }

    fun push(frame: ShortArray) {
        synchronized(lock) {
            queue.addLast(frame)
            while (queue.size > maxFrames) {
                queue.removeFirst()
                dropped++
            }
            // Trim back towards the target once we are comfortably above it, so a
            // burst of frames does not become permanent extra delay.
            if (queue.size > target + TRIM_SLACK) {
                val excess = queue.size - (target + TRIM_SLACK)
                repeat(excess) {
                    if (queue.size > minFrames) {
                        queue.removeFirst()
                        dropped++
                    }
                }
            }
            if (priming && queue.size >= target) priming = false
        }
    }

    /**
     * Pops the next frame for playback.
     *
     * @return a frame, or null while priming or on an underrun. The caller
     * substitutes silence; [lastGoodTail] offers a tapered copy of the previous
     * frame for a softer gap.
     */
    fun pop(): ShortArray? {
        synchronized(lock) {
            if (priming) return null
            val f = queue.removeFirstOrNull()
            if (f == null) {
                underruns++
                priming = true
                // Underrun means the producer is behind: allow a deeper buffer.
                if (target < maxFrames - 1) {
                    target++
                    Logx.d("jitter", "underrun, target -> $target frames (${target * frameMs}ms)")
                }
                return null
            }
            lastFrame = f
            return f
        }
    }

    /** A fade-to-zero copy of the last played frame, for concealment. */
    fun lastGoodTail(): ShortArray? {
        val src = synchronized(lock) { lastFrame } ?: return null
        val copy = src.copyOf()
        Pcm.fadeOut(copy)
        synchronized(lock) { lastFrame = null }   // only conceal once
        return copy
    }

    /**
     * Called periodically by the audio thread. When the link has been clean for
     * a while the target shrinks again, recovering latency.
     */
    fun maybeRelax(cleanFrames: Int) {
        if (cleanFrames < RELAX_AFTER_FRAMES) return
        synchronized(lock) {
            if (target > minFrames && queue.size >= target) {
                target--
                Logx.d("jitter", "link calm, target -> $target frames (${target * frameMs}ms)")
            }
        }
    }

    /** Drops everything. Used after a link break (section 9). */
    fun flush() {
        synchronized(lock) {
            queue.clear()
            lastFrame = null
            priming = true
        }
    }

    fun resetCounters() {
        synchronized(lock) {
            underruns = 0
            dropped = 0
        }
    }

    private companion object {
        const val TRIM_SLACK = 4
        const val RELAX_AFTER_FRAMES = 500      // ~10 s at 20 ms frames
    }
}
