package com.dualstream.uniter.protocol

import java.util.UUID

/**
 * DualStream RFCOMM framing.
 *
 * RFCOMM is a reliable, ordered byte stream, so the frame header only has to
 * provide message boundaries, a type tag, a sequence number for loss accounting
 * and a checksum used to resynchronise if the stream is ever cut mid-frame.
 *
 * ```
 * offset size field
 * 0      2    magic   0xD5 0x71
 * 2      1    version
 * 3      1    type
 * 4      2    seq      (uint16, big endian, wraps)
 * 6      2    length   (uint16, big endian, payload bytes)
 * 8      2    crc16    (CCITT-FALSE over payload)
 * 10     n    payload
 * ```
 */
object Protocol {
    const val MAGIC_0: Byte = 0xD5.toByte()
    const val MAGIC_1: Byte = 0x71.toByte()
    const val VERSION: Int = 1
    const val HEADER_SIZE = 10
    const val MAX_PAYLOAD = 4096

    /**
     * Service record UUID for the audio bridge. Constant on purpose: the
     * receiver advertises it, the transmitter connects to it.
     */
    val SERVICE_UUID: UUID = UUID.fromString("7f3b1c90-2d41-4e8b-9a6d-0c5f21ab7d10")
    const val SERVICE_NAME = "DualStreamUniter"

    object Type {
        const val HELLO: Int = 1
        const val HELLO_ACK: Int = 2
        const val AUDIO: Int = 3
        const val PING: Int = 4
        const val PONG: Int = 5
        const val CONFIG: Int = 6

        /** Receiver asks the transmitter to restart its stream cleanly. */
        const val FLUSH: Int = 7
        const val BYE: Int = 8

        /** Transmitter publishes its capture state so phone A can show it. */
        const val STATE: Int = 9

        /**
         * Codec-specific initialisation data (Opus csd buffers). Sent by the
         * transmitter before the first audio frame of every stream.
         */
        const val CODEC_INIT: Int = 10
    }
}

/** One decoded protocol frame. [payload] is owned by the caller. */
data class Frame(
    val type: Int,
    val seq: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return type == other.type && seq == other.seq && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int =
        (type * 31 + seq) * 31 + payload.contentHashCode()

    override fun toString(): String = "Frame(type=$type, seq=$seq, len=${payload.size})"
}

/** CRC-16/CCITT-FALSE. */
object Crc16 {
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}
