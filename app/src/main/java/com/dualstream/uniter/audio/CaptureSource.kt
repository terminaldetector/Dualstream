package com.dualstream.uniter.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import com.dualstream.uniter.core.Logx
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A producer of mono 16-bit PCM at [sampleRate] on phone B.
 */
interface CaptureSource {
    val sampleRate: Int
    val description: String

    fun start(): Boolean

    /**
     * Blocking read of up to `dst.size` mono samples.
     * @return samples written, 0 on timeout, negative on a fatal error
     */
    fun read(dst: ShortArray): Int

    fun stop()
}

/**
 * System playback capture: `MediaProjection` + `AudioPlaybackCaptureConfiguration`
 * + `AudioRecord` (section 5).
 *
 * Android only hands over audio from players whose capture policy allows it
 * (`ALLOW_CAPTURE_BY_ALL`, or media-usage players of apps that did not opt out).
 * When nothing is capturable the record object still runs and returns digital
 * silence; the caller must detect that and surface it, which is why
 * [lastFrameWasSilent] exists. Reporting silence as a live stream is explicitly
 * forbidden by the specification.
 */
class PlaybackCaptureSource(
    private val projection: MediaProjection,
    override val sampleRate: Int = 48_000,
    private val frameSamples: Int = 960
) : CaptureSource {

    override val description = "system playback capture"

    private var record: AudioRecord? = null
    private var stereoScratch = ShortArray(frameSamples * 2)

    /** True when the most recent read contained nothing but zeros. */
    @Volatile
    var lastFrameWasSilent: Boolean = true
        private set

    @SuppressLint("MissingPermission")  // RECORD_AUDIO is checked before the service starts
    override fun start(): Boolean {
        if (record != null) return true
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBytes = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferBytes = maxOf(minBytes, frameSamples * 2 * 2 * 4)

            val r = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (r.state != AudioRecord.STATE_INITIALIZED) {
                Logx.e("capture", "AudioRecord not initialized, state=${r.state}")
                r.release()
                return false
            }
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Logx.e("capture", "AudioRecord did not enter recording state")
                r.release()
                return false
            }
            record = r
            Logx.i("capture", "playback capture started ${sampleRate}Hz stereo buffer=${bufferBytes}B")
            true
        } catch (t: Throwable) {
            Logx.e("capture", "playback capture could not start", t)
            false
        }
    }

    override fun read(dst: ShortArray): Int {
        val r = record ?: return -1
        val wantFrames = minOf(dst.size, frameSamples)
        val needStereo = wantFrames * 2
        if (stereoScratch.size < needStereo) stereoScratch = ShortArray(needStereo)

        var got = 0
        while (got < needStereo) {
            val n = r.read(stereoScratch, got, needStereo - got, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) {
                    Logx.e("capture", "AudioRecord.read error $n")
                    return -1
                }
                break
            }
            got += n
        }
        if (got <= 0) return 0
        val frames = Pcm.downmixStereoToMono(stereoScratch, got, dst)
        lastFrameWasSilent = Pcm.isSilent(dst, 0, frames)
        return frames
    }

    override fun stop() {
        val r = record ?: return
        record = null
        runCatching { r.stop() }
        runCatching { r.release() }
        Logx.i("capture", "playback capture stopped")
    }
}

/**
 * Direct PCM input (section 5, last paragraph): an in-process queue that an
 * integrated player inside this app — or a future plug-in — can push frames
 * into, bypassing MediaProjection entirely.
 *
 * Writers call [offer]; the transmitter pipeline reads it like any other
 * capture source.
 */
class DirectPcmInput(
    override val sampleRate: Int = 48_000,
    capacityFrames: Int = 32
) : CaptureSource {

    override val description = "direct PCM input"

    private val queue = ArrayBlockingQueue<ShortArray>(capacityFrames)
    private var head: ShortArray? = null
    private var headOffset = 0

    @Volatile
    private var running = false

    override fun start(): Boolean {
        running = true
        return true
    }

    /**
     * Pushes mono samples at [sampleRate]. Drops the oldest frame when full, so
     * a slow radio never blocks the producer.
     * @return true if the frame was queued without dropping anything
     */
    fun offer(frame: ShortArray): Boolean {
        if (!running) return false
        if (queue.offer(frame)) return true
        queue.poll()
        return queue.offer(frame)
    }

    override fun read(dst: ShortArray): Int {
        if (!running) return -1
        var written = 0
        while (written < dst.size) {
            var chunk = head
            if (chunk == null) {
                chunk = if (written == 0) {
                    queue.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } else {
                    queue.poll()
                }
                if (chunk == null) break
                head = chunk
                headOffset = 0
            }
            val avail = chunk.size - headOffset
            val n = minOf(avail, dst.size - written)
            chunk.copyInto(dst, written, headOffset, headOffset + n)
            written += n
            headOffset += n
            if (headOffset >= chunk.size) {
                head = null
                headOffset = 0
            }
        }
        return written
    }

    override fun stop() {
        running = false
        queue.clear()
        head = null
        headOffset = 0
    }

    private companion object {
        const val READ_TIMEOUT_MS = 100L
    }
}
