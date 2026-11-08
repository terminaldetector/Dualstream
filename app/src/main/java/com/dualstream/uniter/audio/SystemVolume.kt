package com.dualstream.uniter.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import com.dualstream.uniter.core.Logx

/**
 * Android's media volume, exposed in the app because it is the only control
 * that reaches everything in the headset.
 *
 * The app's own sliders set the balance between the two sources inside its
 * mix. They cannot touch audio that other apps on this phone are playing,
 * because that goes to the A2DP sink without passing through this process and
 * no public API lets an app attenuate another app's output. The media volume
 * does reach it — at the cost of moving this app's own output with it, since
 * both use the music stream.
 */
class SystemVolume(private val audioManager: AudioManager?) {

    private val stream = AudioManager.STREAM_MUSIC

    val max: Int
        get() = try {
            audioManager?.getStreamMaxVolume(stream) ?: 0
        } catch (t: Throwable) {
            0
        }

    val current: Int
        get() = try {
            audioManager?.getStreamVolume(stream) ?: 0
        } catch (t: Throwable) {
            0
        }

    /** 0f..1f, for a slider. Returns 0 when the volume cannot be read. */
    val fraction: Float
        get() {
            val m = max
            return if (m <= 0) 0f else current.toFloat() / m
        }

    /** @return true if the level was applied */
    fun setFraction(value: Float): Boolean {
        val am = audioManager ?: return false
        val m = max
        if (m <= 0) return false
        val level = (value.coerceIn(0f, 1f) * m).toInt().coerceIn(0, m)
        return try {
            am.setStreamVolume(stream, level, 0)
            true
        } catch (t: Throwable) {
            // Muting the music stream can be refused while Do Not Disturb owns
            // it, and some builds restrict volume changes from the background.
            Logx.w("volume", "could not set the media volume to $level", t)
            false
        }
    }

    /** True when the device reports no media stream at all. */
    val unavailable: Boolean get() = max <= 0

    companion object {
        /**
         * Whether this app may mute a stream.
         *
         * Taking a stream to zero is a mute, and Android routes mutes through
         * Do Not Disturb policy. Without this access `setStreamVolume(0)` is
         * refused — silently on some builds — so the one lever that keeps other
         * apps out of the headset does nothing. The user grants it in system
         * settings; there is no way to request it in a dialog.
         */
        fun canMute(context: Context): Boolean = try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.isNotificationPolicyAccessGranted == true
        } catch (t: Throwable) {
            Logx.w("volume", "could not read notification policy access", t)
            false
        }

        /** The settings screen where the user grants it. */
        fun policyAccessIntent(): android.content.Intent =
            android.content.Intent(
                android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            )
    }
}
