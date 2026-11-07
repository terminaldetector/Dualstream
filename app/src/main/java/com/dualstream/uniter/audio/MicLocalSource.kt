package com.dualstream.uniter.audio

import android.media.AudioManager
import com.dualstream.uniter.core.Logx

/**
 * Phone A's own built-in microphone as the local mixer source.
 *
 * With phone B sending its microphone at the same time, this is the
 * two-microphone arrangement: each phone records its own hardware, so the
 * recorders never contend for one device, and in Hard Split mode one
 * microphone lands in each ear.
 *
 * A feeder thread stands between the recorder and the playback loop. The
 * recorder is paced by the capture clock and the mixer by the output clock;
 * those drift, and if [read] blocked on the recorder a late frame would stall
 * playback of *both* sources. So reads are non-blocking and a short gap simply
 * becomes silence in the local channel.
 */
class MicLocalSource(
    audioManager: AudioManager?,
    private val frameSamples: Int,
    gain: Float = 1f,
    processing: Boolean = true
) : LocalAudioSource {

    private val mic = MicCaptureSource(
        audioManager = audioManager,
        sampleRate = Pcm.OUT_RATE,
        frameSamples = frameSamples,
        enableProcessing = processing,
        gain = gain
    )

    /** Roughly 200 ms of slack, enough to ride out scheduling hiccups. */
    private val ring = PcmRing(frameSamples * 10)

    private var feeder: Thread? = null

    @Volatile private var running = false

    override val description: String
        get() = "built-in microphone" + (mic.routeProblem?.let { " (route problem)" } ?: "")

    /** Null while the input is on the built-in microphone. */
    val routeProblem: String? get() = mic.routeProblem

    val sessionId: Int get() = mic.sessionId

    var gain: Float
        get() = mic.gain
        set(v) { mic.gain = v }

    /** True when the last frame the recorder produced was digital silence. */
    val silent: Boolean get() = mic.lastFrameWasSilent

    /** Samples dropped because the mixer could not keep up. */
    val overflowed: Long get() = ring.overflowed

    fun start(): Boolean {
        if (running) return true
        if (!mic.start()) return false
        running = true
        feeder = Thread({ feed() }, "dualstream-local-mic").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun feed() {
        val buf = ShortArray(frameSamples)
        try {
            while (running) {
                val n = mic.read(buf)
                if (n < 0) {
                    Logx.w("mic", "local microphone failed, stopping feeder")
                    running = false
                    return
                }
                if (n > 0) ring.write(buf, 0, n) else Thread.sleep(IDLE_BACKOFF_MS)
            }
        } catch (e: InterruptedException) {
            Logx.d("mic", "local microphone feeder interrupted")
        } catch (t: Throwable) {
            Logx.w("mic", "local microphone feeder failed", t)
            running = false
        }
    }

    override fun read(dst: ShortArray): Int {
        if (!running) return 0
        val n = ring.read(dst)
        if (n == 0) return 0
        if (n < dst.size) java.util.Arrays.fill(dst, n, dst.size, 0)
        return dst.size
    }

    override fun release() {
        running = false
        feeder?.interrupt()
        feeder = null
        mic.stop()
        ring.clear()
    }

    private companion object {
        const val IDLE_BACKOFF_MS = 2L
    }
}
