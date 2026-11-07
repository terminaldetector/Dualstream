package com.dualstream.uniter.codec

import com.dualstream.uniter.core.CodecId
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Raw little-endian PCM16. Kept for bring-up and for Stage 1 of the plan, where
 * the point is to prove the RFCOMM path rather than to fit a real bitrate
 * budget. 16 kHz mono already needs 256 kbit/s, so it is not a production
 * choice.
 */
class Pcm16Encoder(
    override val sampleRate: Int,
    override val frameSamples: Int
) : AudioEncoder {
    override val id = CodecId.PCM16
    override val initData: List<ByteArray> = emptyList()

    override fun encode(pcm: ShortArray, offset: Int, count: Int): List<ByteArray> {
        val out = ByteArray(count * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bb.putShort(pcm[offset + i])
        return listOf(out)
    }

    override fun release() = Unit
}

class Pcm16Decoder(
    override val sampleRate: Int,
    override val frameSamples: Int
) : AudioDecoder {
    override val id = CodecId.PCM16

    override fun decode(packet: ByteArray, offset: Int, length: Int): ShortArray? {
        if (length < 2) return null
        val n = length / 2
        val out = ShortArray(n)
        val bb = ByteBuffer.wrap(packet, offset, n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) out[i] = bb.short
        return out
    }

    override fun reset() = Unit
    override fun release() = Unit
}
