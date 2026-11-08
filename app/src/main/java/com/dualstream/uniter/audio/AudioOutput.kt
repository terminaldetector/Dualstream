package com.dualstream.uniter.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.dualstream.uniter.core.Logx

/**
 * Stereo [AudioTrack] sink on phone A. The attributes are plain media usage on
 * purpose: that is what the framework routes to a connected A2DP headset. The
 * specification forbids forcing Bluetooth profiles through hidden APIs, so the
 * routing decision is left to the platform.
 */
/**
 * Which Android stream the mixed result is played on.
 *
 * This is not cosmetic. Everything a normal app plays as media shares one
 * volume with every other app's media, so there is no way to silence other
 * apps in the headset without silencing this app too. Playing the mix on the
 * accessibility stream separates the two: the media volume can then be taken
 * to zero so other apps stop reaching the headset directly, while the mix —
 * which already contains their audio, captured — stays audible on its own
 * volume.
 *
 * Whether the capture survives that muting is a property of the device and has
 * to be measured; see docs/TESTING.md. Accessibility routing is also not
 * guaranteed on every build, which is why this stays a choice rather than the
 * default.
 */
enum class OutputStream(val label: String) {
    MEDIA("Media"),
    ACCESSIBILITY("Accessibility")
}

class AudioOutput(
    private val sampleRate: Int = Pcm.OUT_RATE,
    private val frameSamples: Int = 960,
    private val stream: OutputStream = OutputStream.MEDIA
) {
    private var track: AudioTrack? = null

    /** Size of the AudioTrack ring buffer in frames, for latency bookkeeping. */
    var bufferFrames: Int = 0
        private set

    fun start(): Boolean {
        if (track != null) return true
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) {
            Logx.e("out", "getMinBufferSize failed: $minBytes")
            return false
        }
        // Three mixer frames or the platform minimum, whichever is larger.
        val wanted = maxOf(minBytes, frameSamples * 2 * 2 * 3)
        return try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            when (stream) {
                                OutputStream.MEDIA -> AudioAttributes.USAGE_MEDIA
                                OutputStream.ACCESSIBILITY ->
                                    AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                            }
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(wanted)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            t.play()
            track = t
            bufferFrames = wanted / 4
            Logx.i(
                "out",
                "AudioTrack started ${sampleRate}Hz stereo buffer=${wanted}B on ${stream.label}"
            )
            true
        } catch (t: Throwable) {
            Logx.e("out", "AudioTrack could not start", t)
            false
        }
    }

    /** Blocking write of an interleaved stereo buffer. */
    fun write(interleaved: ShortArray, count: Int = interleaved.size): Int {
        val t = track ?: return -1
        return try {
            t.write(interleaved, 0, count, AudioTrack.WRITE_BLOCKING)
        } catch (e: Throwable) {
            Logx.w("out", "write failed", e)
            -1
        }
    }

    /** Estimated output latency in ms from the frames still queued in the track. */
    fun queuedMs(): Int {
        val t = track ?: return 0
        return try {
            val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val written = writtenFrames - head
            if (written <= 0) 0 else (written * 1000 / sampleRate).toInt()
        } catch (e: Throwable) {
            0
        }
    }

    private var writtenFrames: Long = 0

    fun noteWritten(stereoShorts: Int) {
        writtenFrames += stereoShorts / 2
    }

    /** Clears queued audio so a reconnect does not play stale frames. */
    fun flush() {
        val t = track ?: return
        runCatching {
            t.pause()
            t.flush()
            t.play()
        }.onFailure { Logx.w("out", "flush failed", it) }
        writtenFrames = 0
    }

    fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.stop() }
        runCatching { t.release() }
        writtenFrames = 0
        Logx.i("out", "AudioTrack stopped")
    }

    companion object {
        /** True when the framework currently reports an A2DP sink as active. */
        fun isA2dpActive(am: AudioManager): Boolean = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        } catch (t: Throwable) {
            false
        }
    }
}
