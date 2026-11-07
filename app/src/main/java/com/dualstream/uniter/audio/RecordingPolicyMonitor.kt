package com.dualstream.uniter.audio

import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import com.dualstream.uniter.core.Logx

/**
 * Watches whether Android's audio policy has silenced our recorder.
 *
 * This is the failure the two-microphone setup has to be able to explain. When
 * another app takes the input, or this app loses its claim on the microphone,
 * `AudioRecord` keeps returning frames on schedule — they are just all zeros.
 * Without this callback the app would see a healthy recorder producing silence
 * and have no way to tell that apart from a quiet room, which is exactly the
 * kind of thing the specification forbids reporting as a working stream.
 *
 * It also reports the route, because the platform can move a recording to
 * another device after it has started.
 */
class RecordingPolicyMonitor(
    private val audioManager: AudioManager?,
    private val onChange: (Report) -> Unit
) {
    /**
     * @param silenced the policy muted our recorder
     * @param routedType device type the recording sits on, or null if unknown
     */
    data class Report(val silenced: Boolean, val routedType: Int?)

    private var callback: AudioManager.AudioRecordingCallback? = null
    private var watchedSession: Int = -1

    fun start(sessionId: Int) {
        val am = audioManager ?: return
        stop()
        watchedSession = sessionId
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                val mine = configs?.firstOrNull { it.clientAudioSessionId == watchedSession }
                if (mine == null) {
                    // Our configuration disappeared from the list: the recorder is
                    // no longer active as far as the policy is concerned.
                    Logx.d("micpolicy", "no recording configuration for session $watchedSession")
                    return
                }
                val silenced = try {
                    mine.isClientSilenced
                } catch (t: Throwable) {
                    false
                }
                val routed = try {
                    mine.audioDevice?.type
                } catch (t: Throwable) {
                    null
                }
                if (silenced) {
                    Logx.w("micpolicy", "the audio policy has silenced our recorder")
                }
                onChange(Report(silenced, routed))
            }
        }
        runCatching {
            am.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper()))
            callback = cb
            Logx.i("micpolicy", "watching recording policy for session $sessionId")
        }.onFailure { Logx.w("micpolicy", "could not register the recording callback", it) }
    }

    fun stop() {
        val am = audioManager ?: return
        callback?.let { runCatching { am.unregisterAudioRecordingCallback(it) } }
        callback = null
        watchedSession = -1
    }
}
