package com.dualstream.uniter.audio

import android.media.projection.MediaProjection
import com.dualstream.uniter.core.Logx

/**
 * Phone A's own app audio as the mixer's local channel: whatever any app on
 * this phone is playing, not a track chosen inside this app.
 *
 * This is what makes "phone A in one ear, phone B in the other" work for any
 * app rather than only for content this app owns. It needs three things to
 * line up, and the third is the one that decides whether it works at all:
 *
 *  1. MediaProjection consent on phone A as well as phone B.
 *  2. The capture must exclude this app's own output, or the mixer's result is
 *     fed back into its own input and runs away.
 *  3. The captured apps keep playing to the headset directly — capture taps,
 *     it does not redirect — so their direct path has to be silenced, which
 *     means taking the media volume to zero and playing the mix on another
 *     stream. Whether the capture still carries audio once the media volume is
 *     zero depends on where the platform puts the capture tap relative to that
 *     attenuation, and that is not reliably documented. It is measured on the
 *     device, not assumed here.
 *
 * Like the microphone source, a feeder thread stands between capture and the
 * playback loop, because the two run on different clocks and a late frame must
 * never stall playback of both sources.
 */
class AppAudioLocalSource(
    projection: MediaProjection,
    private val frameSamples: Int
) : LocalAudioSource {

    private val capture = PlaybackCaptureSource(
        projection = projection,
        sampleRate = Pcm.OUT_RATE,
        frameSamples = frameSamples,
        excludeOwnOutput = true
    )

    private val ring = PcmRing(frameSamples * 10)
    private var feeder: Thread? = null

    @Volatile private var running = false

    override val description: String = "this phone's app audio"

    /** True when the capture is delivering nothing but digital silence. */
    val silent: Boolean get() = capture.lastFrameWasSilent

    fun start(): Boolean {
        if (running) return true
        if (!capture.start()) return false
        running = true
        feeder = Thread({ feed() }, "dualstream-local-appaudio").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun feed() {
        val buf = ShortArray(frameSamples)
        try {
            while (running) {
                val n = capture.read(buf)
                if (n < 0) {
                    Logx.w("local", "app-audio capture failed, stopping feeder")
                    running = false
                    return
                }
                if (n > 0) ring.write(buf, 0, n) else Thread.sleep(IDLE_BACKOFF_MS)
            }
        } catch (e: InterruptedException) {
            Logx.d("local", "app-audio feeder interrupted")
        } catch (t: Throwable) {
            Logx.w("local", "app-audio feeder failed", t)
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
        capture.stop()
        ring.clear()
    }

    private companion object {
        const val IDLE_BACKOFF_MS = 2L
    }
}
