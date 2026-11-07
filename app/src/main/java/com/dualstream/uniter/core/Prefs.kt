package com.dualstream.uniter.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent settings (section 8 "Настройки") plus the trusted-device record
 * needed for automatic reconnection (section 3, item 3-4).
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("dualstream", Context.MODE_PRIVATE)

    var role: Role
        get() = runCatching { Role.valueOf(sp.getString(K_ROLE, Role.RECEIVER.name)!!) }
            .getOrDefault(Role.RECEIVER)
        set(v) = sp.edit().putString(K_ROLE, v.name).apply()

    /** MAC address of the paired peer phone, remembered after a first success. */
    var trustedPeerAddress: String?
        get() = sp.getString(K_PEER_ADDR, null)
        set(v) = sp.edit().putString(K_PEER_ADDR, v).apply()

    var trustedPeerName: String?
        get() = sp.getString(K_PEER_NAME, null)
        set(v) = sp.edit().putString(K_PEER_NAME, v).apply()

    var mixMode: MixMode
        get() = runCatching { MixMode.valueOf(sp.getString(K_MODE, MixMode.MIXED_STEREO.name)!!) }
            .getOrDefault(MixMode.MIXED_STEREO)
        set(v) = sp.edit().putString(K_MODE, v.name).apply()

    /** 0f..1f */
    var localVolume: Float
        get() = sp.getFloat(K_VOL_LOCAL, 0.8f)
        set(v) = sp.edit().putFloat(K_VOL_LOCAL, v.coerceIn(0f, 1f)).apply()

    var remoteVolume: Float
        get() = sp.getFloat(K_VOL_REMOTE, 1.0f)
        set(v) = sp.edit().putFloat(K_VOL_REMOTE, v.coerceIn(0f, 1f)).apply()

    /** -1f = fully left, 0f = centre, +1f = fully right. */
    var balance: Float
        get() = sp.getFloat(K_BALANCE, 0f)
        set(v) = sp.edit().putFloat(K_BALANCE, v.coerceIn(-1f, 1f)).apply()

    /** Target encoder bitrate in bit/s. One of 24000/32000/48000/64000. */
    var bitrate: Int
        get() = sp.getInt(K_BITRATE, 32_000)
        set(v) = sp.edit().putInt(K_BITRATE, v).apply()

    /** Encoder frame size in milliseconds, 10 or 20. */
    var frameMs: Int
        get() = sp.getInt(K_FRAME_MS, 20)
        set(v) = sp.edit().putInt(K_FRAME_MS, if (v == 10) 10 else 20).apply()

    var preferredCodec: CodecId
        get() = runCatching { CodecId.valueOf(sp.getString(K_CODEC, CodecId.OPUS.name)!!) }
            .getOrDefault(CodecId.OPUS)
        set(v) = sp.edit().putString(K_CODEC, v.name).apply()

    /** How strongly PRIORITY mode ducks the local source, 0f..1f of attenuation. */
    var duckDepth: Float
        get() = sp.getFloat(K_DUCK, 0.75f)
        set(v) = sp.edit().putFloat(K_DUCK, v.coerceIn(0f, 1f)).apply()

    var autoReconnect: Boolean
        get() = sp.getBoolean(K_AUTO_RECONNECT, true)
        set(v) = sp.edit().putBoolean(K_AUTO_RECONNECT, v).apply()

    /**
     * Power-saving mode: lower bitrate, longer frames, bigger jitter target.
     * Trades latency for radio airtime and CPU wakeups.
     */
    var powerSave: Boolean
        get() = sp.getBoolean(K_POWER_SAVE, false)
        set(v) = sp.edit().putBoolean(K_POWER_SAVE, v).apply()

    var verboseLog: Boolean
        get() = sp.getBoolean(K_VERBOSE, false)
        set(v) {
            sp.edit().putBoolean(K_VERBOSE, v).apply()
            Logx.verbose = v
        }

    /** What phone B feeds into the encoder. */
    var inputSource: InputSource
        get() = runCatching {
            InputSource.valueOf(sp.getString(K_INPUT, InputSource.PLAYBACK_CAPTURE.name)!!)
        }.getOrDefault(InputSource.PLAYBACK_CAPTURE)
        set(v) = sp.edit().putString(K_INPUT, v.name).apply()

    /**
     * Use this phone's built-in microphone as the local source on phone A.
     * Together with phone B sending its own microphone, this is the
     * two-microphone setup: each phone records its own hardware.
     */
    var micAsLocalSource: Boolean
        get() = sp.getBoolean(K_MIC_LOCAL, false)
        set(v) = sp.edit().putBoolean(K_MIC_LOCAL, v).apply()

    /** Linear gain applied to microphone input, 0f..4f. */
    var micGain: Float
        get() = sp.getFloat(K_MIC_GAIN, 1.0f)
        set(v) = sp.edit().putFloat(K_MIC_GAIN, v.coerceIn(0f, 4f)).apply()

    /**
     * Attach the platform echo canceller and noise suppressor to microphone
     * input when the device offers them. Worth having when both phones run
     * their microphones into one headset, where leakage would otherwise loop.
     */
    var micProcessing: Boolean
        get() = sp.getBoolean(K_MIC_FX, true)
        set(v) = sp.edit().putBoolean(K_MIC_FX, v).apply()

    /** Optional content URI of the app-managed local source used by HARD_SPLIT. */
    var localSourceUri: String?
        get() = sp.getString(K_LOCAL_URI, null)
        set(v) = sp.edit().putString(K_LOCAL_URI, v).apply()

    fun snapshotStreamConfig(): StreamConfig {
        val ps = powerSave
        return StreamConfig(
            codec = preferredCodec,
            bitrate = if (ps) minOf(bitrate, 24_000) else bitrate,
            frameMs = if (ps) 20 else frameMs
        )
    }

    private companion object {
        const val K_ROLE = "role"
        const val K_PEER_ADDR = "peer_addr"
        const val K_PEER_NAME = "peer_name"
        const val K_MODE = "mix_mode"
        const val K_VOL_LOCAL = "vol_local"
        const val K_VOL_REMOTE = "vol_remote"
        const val K_BALANCE = "balance"
        const val K_BITRATE = "bitrate"
        const val K_FRAME_MS = "frame_ms"
        const val K_CODEC = "codec"
        const val K_DUCK = "duck"
        const val K_AUTO_RECONNECT = "auto_reconnect"
        const val K_POWER_SAVE = "power_save"
        const val K_VERBOSE = "verbose"
        const val K_LOCAL_URI = "local_uri"
        const val K_INPUT = "input_source"
        const val K_MIC_LOCAL = "mic_local"
        const val K_MIC_GAIN = "mic_gain"
        const val K_MIC_FX = "mic_fx"
    }
}
