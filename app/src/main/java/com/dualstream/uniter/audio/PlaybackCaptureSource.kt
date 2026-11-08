package com.dualstream.uniter.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import com.dualstream.uniter.core.Logx

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
    private val frameSamples: Int = 960,
    /**
     * Leave this phone's own DualStream output out of the capture.
     *
     * On phone A the mixer both captures and plays, so without this the output
     * is fed straight back into its own input and the loop runs away within
     * seconds.
     */
    private val excludeOwnOutput: Boolean = false
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
                // Everything the platform allows a normal app to capture. These
                // three usages are the whole permitted set; the rest are
                // reserved for the system, so "capture every app" means this.
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .apply {
                    if (excludeOwnOutput) {
                        excludeUid(android.os.Process.myUid())
                        Logx.i("capture", "excluding our own output from the capture")
                    }
                }
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
