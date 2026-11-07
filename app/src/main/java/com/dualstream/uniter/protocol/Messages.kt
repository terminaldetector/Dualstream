package com.dualstream.uniter.protocol

import com.dualstream.uniter.core.CaptureState
import com.dualstream.uniter.core.CodecId
import com.dualstream.uniter.core.Role
import com.dualstream.uniter.core.StreamConfig
import java.io.ByteArrayOutputStream

/**
 * Handshake description exchanged right after the RFCOMM socket opens.
 *
 * Wire layout (big endian):
 * ```
 * 0 role (1)      1 = receiver, 2 = transmitter
 * 1 codec (1)     CodecId.wire
 * 2 sampleRate(4) codec sample rate in Hz
 * 6 channels (1)
 * 7 frameMs (1)
 * 8 bitrate (4)
 * 12 nameLen (1)
 * 13 name (UTF-8)
 * ```
 */
data class Hello(
    val role: Role,
    val codec: CodecId,
    val sampleRate: Int,
    val channels: Int,
    val frameMs: Int,
    val bitrate: Int,
    val deviceName: String
) {
    fun encode(): ByteArray {
        val name = deviceName.take(60).toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream(13 + name.size)
        bos.write(if (role == Role.RECEIVER) 1 else 2)
        bos.write(codec.wire)
        bos.writeInt(sampleRate)
        bos.write(channels)
        bos.write(frameMs)
        bos.writeInt(bitrate)
        bos.write(name.size)
        bos.write(name)
        return bos.toByteArray()
    }

    val config: StreamConfig get() = StreamConfig(codec, bitrate, frameMs)

    companion object {
        fun decode(p: ByteArray): Hello? {
            if (p.size < 13) return null
            val role = when (p[0].toInt() and 0xFF) {
                1 -> Role.RECEIVER
                2 -> Role.TRANSMITTER
                else -> return null
            }
            val codec = CodecId.fromWire(p[1].toInt() and 0xFF) ?: return null
            val sampleRate = p.readInt(2)
            val channels = p[6].toInt() and 0xFF
            val frameMs = p[7].toInt() and 0xFF
            val bitrate = p.readInt(8)
            val nameLen = p[12].toInt() and 0xFF
            if (p.size < 13 + nameLen) return null
            val name = String(p, 13, nameLen, Charsets.UTF_8)
            if (sampleRate !in 8_000..48_000 || channels !in 1..2 || frameMs !in 1..60) return null
            return Hello(role, codec, sampleRate, channels, frameMs, bitrate, name)
        }
    }
}

/**
 * Mid-stream reconfiguration, sent by either side. A transmitter honours a
 * bitrate request from the receiver; a receiver accepts a codec change only
 * between streams.
 */
data class ConfigUpdate(val bitrate: Int, val frameMs: Int) {
    fun encode(): ByteArray {
        val bos = ByteArrayOutputStream(5)
        bos.writeInt(bitrate)
        bos.write(frameMs)
        return bos.toByteArray()
    }

    companion object {
        fun decode(p: ByteArray): ConfigUpdate? {
            if (p.size < 5) return null
            return ConfigUpdate(p.readInt(0), p[4].toInt() and 0xFF)
        }
    }
}

/** Capture-state notification, so phone A can display what phone B is doing. */
data class StateUpdate(val capture: CaptureState) {
    fun encode(): ByteArray = byteArrayOf(capture.ordinal.toByte())

    companion object {
        fun decode(p: ByteArray): StateUpdate? {
            if (p.isEmpty()) return null
            val idx = p[0].toInt() and 0xFF
            val values = CaptureState.entries
            if (idx >= values.size) return null
            return StateUpdate(values[idx])
        }
    }
}

/**
 * Audio frame payload header: 1 byte of flags plus a 4-byte capture timestamp
 * in milliseconds, followed by codec bytes.
 */
object AudioPayload {
    const val HEADER = 5
    const val FLAG_SILENCE = 0x01

    fun writeHeader(dst: ByteArray, flags: Int, timestampMs: Int) {
        dst[0] = flags.toByte()
        dst[1] = ((timestampMs ushr 24) and 0xFF).toByte()
        dst[2] = ((timestampMs ushr 16) and 0xFF).toByte()
        dst[3] = ((timestampMs ushr 8) and 0xFF).toByte()
        dst[4] = (timestampMs and 0xFF).toByte()
    }

    fun flags(p: ByteArray): Int = if (p.isEmpty()) 0 else p[0].toInt() and 0xFF

    fun timestampMs(p: ByteArray): Int = if (p.size < HEADER) 0 else p.readInt(1)

    fun isSilence(p: ByteArray): Boolean = flags(p) and FLAG_SILENCE != 0
}

internal fun ByteArrayOutputStream.writeInt(v: Int) {
    write((v ushr 24) and 0xFF)
    write((v ushr 16) and 0xFF)
    write((v ushr 8) and 0xFF)
    write(v and 0xFF)
}

internal fun ByteArray.readInt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

/**
 * Codec-specific initialisation data. For Opus through MediaCodec these are the
 * csd buffers produced by the encoder; the decoder cannot start without them.
 * Codecs that need no setup send an empty list.
 *
 * Wire layout: `count(1)` then `count` records of `len(2) | bytes`.
 */
data class CodecInit(val buffers: List<ByteArray>) {
    fun encode(): ByteArray {
        val bos = ByteArrayOutputStream(1 + buffers.sumOf { it.size + 2 })
        bos.write(buffers.size and 0xFF)
        for (b in buffers) {
            bos.write((b.size ushr 8) and 0xFF)
            bos.write(b.size and 0xFF)
            bos.write(b)
        }
        return bos.toByteArray()
    }

    companion object {
        fun decode(p: ByteArray): CodecInit? {
            if (p.isEmpty()) return null
            val count = p[0].toInt() and 0xFF
            var off = 1
            val out = ArrayList<ByteArray>(count)
            repeat(count) {
                if (off + 2 > p.size) return null
                val len = ((p[off].toInt() and 0xFF) shl 8) or (p[off + 1].toInt() and 0xFF)
                off += 2
                if (off + len > p.size) return null
                out.add(p.copyOfRange(off, off + len))
                off += len
            }
            return CodecInit(out)
        }
    }
}
