package com.dualstream.uniter.codec

import com.dualstream.uniter.core.CodecId

/**
 * IMA-ADPCM, 4 bits per sample. Pure Kotlin, no platform dependency, so it is
 * the guaranteed fallback when the device has no usable Opus encoder.
 *
 * At 16 kHz mono it needs 64 kbit/s, at 12 kHz 48 kbit/s and at 8 kHz
 * 32 kbit/s. The factory picks the rate from the requested bitrate, which is
 * why ADPCM trades bandwidth for sample rate rather than for quantisation.
 */
object Ima {
    val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
        253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
        1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
        3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493,
        10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
        27086, 29794, 32767
    )
    val INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
}

class AdpcmEncoder(
    override val sampleRate: Int,
    override val frameSamples: Int
) : AudioEncoder {

    override val id = CodecId.ADPCM
    override val initData: List<ByteArray> = emptyList()

    private var predictor = 0
    private var index = 0

    override fun encode(pcm: ShortArray, offset: Int, count: Int): List<ByteArray> {
        // 2 bytes of predictor state per frame keeps the decoder in step even if
        // a frame is dropped by the jitter buffer.
        val out = ByteArray(3 + (count + 1) / 2)
        out[0] = ((predictor shr 8) and 0xFF).toByte()
        out[1] = (predictor and 0xFF).toByte()
        out[2] = index.toByte()

        var w = 3
        var pending = -1
        for (i in 0 until count) {
            val nibble = encodeSample(pcm[offset + i].toInt())
            if (pending < 0) {
                pending = nibble
            } else {
                out[w++] = ((nibble shl 4) or pending).toByte()
                pending = -1
            }
        }
        if (pending >= 0) out[w++] = pending.toByte()
        return listOf(if (w == out.size) out else out.copyOf(w))
    }

    private fun encodeSample(sample: Int): Int {
        val step = Ima.STEP_TABLE[index]
        var diff = sample - predictor
        var code = 0
        if (diff < 0) {
            code = 8
            diff = -diff
        }
        var tmp = step
        if (diff >= tmp) { code = code or 4; diff -= tmp }
        tmp = tmp shr 1
        if (diff >= tmp) { code = code or 2; diff -= tmp }
        tmp = tmp shr 1
        if (diff >= tmp) { code = code or 1 }

        var delta = step shr 3
        if (code and 4 != 0) delta += step
        if (code and 2 != 0) delta += step shr 1
        if (code and 1 != 0) delta += step shr 2
        predictor = if (code and 8 != 0) predictor - delta else predictor + delta
        predictor = predictor.coerceIn(-32768, 32767)

        index = (index + Ima.INDEX_TABLE[code]).coerceIn(0, Ima.STEP_TABLE.size - 1)
        return code
    }

    override fun release() = Unit
}

class AdpcmDecoder(
    override val sampleRate: Int,
    override val frameSamples: Int
) : AudioDecoder {

    override val id = CodecId.ADPCM

    override fun decode(packet: ByteArray, offset: Int, length: Int): ShortArray? {
        if (length < 3) return null
        var predictor = (((packet[offset].toInt() and 0xFF) shl 8) or
            (packet[offset + 1].toInt() and 0xFF)).toShort().toInt()
        var index = (packet[offset + 2].toInt() and 0xFF).coerceIn(0, Ima.STEP_TABLE.size - 1)

        val dataBytes = length - 3
        val out = ShortArray(dataBytes * 2)
        var w = 0
        for (i in 0 until dataBytes) {
            val b = packet[offset + 3 + i].toInt() and 0xFF
            for (nibble in intArrayOf(b and 0x0F, (b shr 4) and 0x0F)) {
                val step = Ima.STEP_TABLE[index]
                var delta = step shr 3
                if (nibble and 4 != 0) delta += step
                if (nibble and 2 != 0) delta += step shr 1
                if (nibble and 1 != 0) delta += step shr 2
                predictor = if (nibble and 8 != 0) predictor - delta else predictor + delta
                predictor = predictor.coerceIn(-32768, 32767)
                index = (index + Ima.INDEX_TABLE[nibble]).coerceIn(0, Ima.STEP_TABLE.size - 1)
                out[w++] = predictor.toShort()
            }
        }
        return if (w == out.size) out else out.copyOf(w)
    }

    override fun reset() = Unit
    override fun release() = Unit
}
