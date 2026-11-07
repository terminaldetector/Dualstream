package com.dualstream.uniter.audio

import com.dualstream.uniter.core.Logx

/**
 * Sums two capture sources into one mono stream, used for "app audio +
 * microphone" on phone B.
 *
 * The primary source paces the result: its blocking read is the clock. The
 * secondary runs on its own thread into a [PcmRing], because two recorders are
 * driven by independent clocks — reading them one after another on one thread
 * would make each one's jitter the other's problem, and a stall in either would
 * stop the stream.
 *
 * Each side's silence is tracked separately. That matters: when Android's audio
 * policy silences one of two concurrent recorders, the sum is still non-silent,
 * so a single "is there sound" check would hide the fact that one source died.
 */
class MixCaptureSource(
    private val primary: CaptureSource,
    private val secondary: CaptureSource,
    private val frameSamples: Int,
    var primaryGain: Float = 1f,
    var secondaryGain: Float = 1f
) : CaptureSource {

    override val sampleRate: Int = primary.sampleRate

    override val description: String =
        "${primary.description} + ${secondary.description}"

    private val ring = PcmRing(frameSamples * 8)
    private val secondaryBuf = ShortArray(frameSamples)
    private val mixBuf = ShortArray(frameSamples)

    private var feeder: Thread? = null

    @Volatile private var running = false

    /** True when the most recent frame from that side was digital silence. */
    @Volatile var primarySilent: Boolean = true
        private set

    @Volatile var secondarySilent: Boolean = true
        private set

    /** Set when the secondary source reported a fatal error and was given up on. */
    @Volatile var secondaryFailed: Boolean = false
        private set

    init {
        require(primary.sampleRate == secondary.sampleRate) {
            "both sources must run at the same sample rate, got " +
                "${primary.sampleRate} and ${secondary.sampleRate}"
        }
    }

    override fun start(): Boolean {
        if (!primary.start()) return false
        running = true
        if (!secondary.start()) {
            // The mix degrades to the primary source alone rather than failing:
            // losing the microphone should not take the app audio down with it.
            secondaryFailed = true
            return true
        }
        feeder = Thread({ feed() }, "dualstream-mix-secondary").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun feed() {
        val buf = ShortArray(frameSamples)
        try {
            while (running) {
                val n = secondary.read(buf)
                if (n < 0) {
                    secondaryFailed = true
                    return
                }
                if (n > 0) {
                    ring.write(buf, 0, n)
                } else {
                    // A source that returns nothing without blocking would turn
                    // this into a hot loop, so back off rather than burn a core.
                    Thread.sleep(IDLE_BACKOFF_MS)
                }
            }
        } catch (e: InterruptedException) {
            // stop() interrupts this thread; a read blocked in the middle of a
            // frame surfaces here. Shutting down quietly is the whole intent.
            Logx.d("mix", "secondary feeder interrupted")
        } catch (t: Throwable) {
            secondaryFailed = true
            Logx.w("mix", "secondary source failed", t)
        }
    }

    override fun read(dst: ShortArray): Int {
        val n = primary.read(dst)
        if (n <= 0) return n
        primarySilent = Pcm.isSilent(dst, 0, n)

        val want = minOf(n, secondaryBuf.size)
        val got = ring.read(secondaryBuf, 0, want)
        if (got > 0) {
            secondarySilent = Pcm.isSilent(secondaryBuf, 0, got)
        } else if (secondaryFailed) {
            secondarySilent = true
        }

        for (i in 0 until n) {
            val a = (dst[i] * primaryGain).toInt()
            val b = if (i < got) (secondaryBuf[i] * secondaryGain).toInt() else 0
            mixBuf[i] = Pcm.mixSample(a, b)
        }
        mixBuf.copyInto(dst, 0, 0, n)
        return n
    }

    override fun stop() {
        running = false
        feeder?.interrupt()
        feeder = null
        runCatching { secondary.stop() }
        runCatching { primary.stop() }
        ring.clear()
    }

    private companion object {
        const val IDLE_BACKOFF_MS = 2L
    }
}
