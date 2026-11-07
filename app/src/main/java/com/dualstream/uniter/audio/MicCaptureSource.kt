package com.dualstream.uniter.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.dualstream.uniter.core.Logx

/**
 * Records this phone's **built-in** microphone.
 *
 * Three deliberate decisions, all of them about not losing the microphone or
 * the headset:
 *
 *  1. The input device is pinned with `setPreferredDevice` to the built-in
 *     microphone, and the actual route is verified after recording starts. If
 *     Android had been left to choose, it could route to a connected Bluetooth
 *     headset's microphone, which switches that headset to the hands-free
 *     profile and takes the A2DP stream down. Two phones also cannot share one
 *     headset microphone: the system gives it to one and silences the other.
 *     Pinning to the built-in device is what lets both phones record at once.
 *  2. The audio source is `VOICE_RECOGNITION`, not `VOICE_COMMUNICATION`.
 *     The communication source makes the platform treat the session as a call,
 *     which is the other way to end up on the hands-free profile. This class
 *     never touches `setMode` or `startBluetoothSco` for the same reason.
 *  3. `setPrivacySensitive(false)` is explicit. Marking the session privacy
 *     sensitive would make Android silence *other* recorders — including the
 *     playback capture running next to it in this same app.
 */
class MicCaptureSource(
    private val audioManager: AudioManager?,
    override val sampleRate: Int = 48_000,
    private val frameSamples: Int = 960,
    private val enableProcessing: Boolean = true,
    @Volatile var gain: Float = 1f
) : CaptureSource {

    override val description = "built-in microphone"

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    /** The device type the platform actually routed us to, or null if unknown. */
    @Volatile
    var routedType: Int? = null
        private set

    /** Null while the route is acceptable; otherwise a message for the UI. */
    @Volatile
    var routeProblem: String? = null
        private set

    @Volatile
    var lastFrameWasSilent: Boolean = true
        private set

    /** Audio session id, needed to watch for the policy silencing us. */
    val sessionId: Int get() = record?.audioSessionId ?: AudioManager.AUDIO_SESSION_ID_GENERATE

    @SuppressLint("MissingPermission")  // RECORD_AUDIO is checked before the service starts
    override fun start(): Boolean {
        if (record != null) return true
        check(InputRoutePolicy.TYPE_BUILTIN_MIC == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            "InputRoutePolicy no longer matches AudioDeviceInfo"
        }

        val builtIn = findBuiltInMic()
        if (builtIn == null) {
            Logx.w("mic", "no built-in microphone reported by the platform")
        }

        return try {
            val minBytes = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBytes <= 0) {
                Logx.e("mic", "getMinBufferSize failed: $minBytes")
                return false
            }
            val bufferBytes = maxOf(minBytes, frameSamples * 2 * 4)

            val r = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // Do not let this session silence the playback capture
                        // that may be running alongside it.
                        setPrivacySensitive(false)
                    }
                }
                .build()

            if (r.state != AudioRecord.STATE_INITIALIZED) {
                Logx.e("mic", "AudioRecord not initialized, state=${r.state}")
                r.release()
                return false
            }

            // Pin the input before recording starts, so the first frame is already
            // on the right device.
            if (builtIn != null && !r.setPreferredDevice(builtIn)) {
                Logx.w("mic", "setPreferredDevice(built-in) was refused")
            }

            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Logx.e("mic", "AudioRecord did not enter recording state")
                r.release()
                return false
            }
            record = r
            attachEffects(r.audioSessionId)
            verifyRoute(r)
            Logx.i(
                "mic",
                "microphone started ${sampleRate}Hz mono buffer=${bufferBytes}B " +
                    "route=${routedType?.let { InputRoutePolicy.describe(it) } ?: "unknown"}"
            )
            true
        } catch (t: Throwable) {
            Logx.e("mic", "microphone could not start", t)
            false
        }
    }

    private fun findBuiltInMic(): AudioDeviceInfo? {
        val am = audioManager ?: return null
        return try {
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val types = inputs.map { it.type }
            Logx.d("mic", "inputs: " + types.joinToString { InputRoutePolicy.describe(it) })
            if (InputRoutePolicy.choose(types) == null) return null
            inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        } catch (t: Throwable) {
            Logx.w("mic", "could not enumerate inputs", t)
            null
        }
    }

    /**
     * Confirms where the platform actually put us. `setPreferredDevice` is a
     * request, not a guarantee, so the result has to be checked rather than
     * assumed.
     */
    private fun verifyRoute(r: AudioRecord) {
        val type = try {
            r.routedDevice?.type
        } catch (t: Throwable) {
            null
        }
        routedType = type
        routeProblem = type?.let { InputRoutePolicy.rejectionReason(it) }
        routeProblem?.let { Logx.w("mic", it) }
    }

    /** Re-checks the route; the platform can move us while recording. */
    fun recheckRoute() {
        record?.let { verifyRoute(it) }
    }

    private fun attachEffects(session: Int) {
        if (!enableProcessing) return
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(session)?.also { it.enabled = true }
            }
        }.onFailure { Logx.w("mic", "echo canceller unavailable", it) }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(session)?.also { it.enabled = true }
            }
        }.onFailure { Logx.w("mic", "noise suppressor unavailable", it) }
        runCatching {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(session)?.also { it.enabled = true }
            }
        }.onFailure { Logx.w("mic", "automatic gain control unavailable", it) }
        Logx.i(
            "mic",
            "effects aec=${aec != null} ns=${ns != null} agc=${agc != null}"
        )
    }

    override fun read(dst: ShortArray): Int {
        val r = record ?: return -1
        val want = minOf(dst.size, frameSamples)
        var got = 0
        while (got < want) {
            val n = r.read(dst, got, want - got, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) {
                    Logx.e("mic", "AudioRecord.read error $n")
                    return -1
                }
                break
            }
            got += n
        }
        if (got <= 0) return 0
        val g = gain
        if (g != 1f) Pcm.applyGain(dst, g, 0, got)
        lastFrameWasSilent = Pcm.isSilent(dst, 0, got)
        return got
    }

    override fun stop() {
        val r = record ?: return
        record = null
        listOfNotNull(aec, ns, agc).forEach { fx -> runCatching { fx.release() } }
        aec = null
        ns = null
        agc = null
        runCatching { r.stop() }
        runCatching { r.release() }
        Logx.i("mic", "microphone stopped")
    }
}
