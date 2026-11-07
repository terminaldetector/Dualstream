package com.dualstream.uniter.service

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import com.dualstream.uniter.core.Logx

/**
 * Foreground-service type bookkeeping.
 *
 * The manifest has to declare every type a service might ever use, but a
 * service must only *claim* the ones it is actually using right now. That is
 * not cosmetic: on Android 14 a service that claims `mediaProjection` without a
 * live projection is refused, so a microphone-only or test-tone session would
 * fail to start if it inherited the union of the manifest types. Hence the
 * explicit per-start bitmask.
 */
object ForegroundTypes {

    private val MICROPHONE: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

    const val NONE = 0

    fun connectedDevice(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    fun mediaPlayback(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

    fun mediaProjection(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

    fun microphone(): Int = MICROPHONE

    /**
     * Starts or updates the foreground state with exactly [types].
     * @return true if the platform accepted the claim
     */
    fun promote(service: Service, id: Int, notification: Notification, types: Int): Boolean =
        try {
            ServiceCompat.startForeground(service, id, notification, types)
            true
        } catch (t: Throwable) {
            // Android 14+ throws when a claimed type is not permitted, e.g.
            // mediaProjection without an active projection.
            Logx.e("fgs", "foreground claim $types refused", t)
            false
        }
}
