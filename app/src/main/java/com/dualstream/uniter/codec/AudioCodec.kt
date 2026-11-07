package com.dualstream.uniter.codec

import com.dualstream.uniter.core.CodecId

/**
 * Encoder contract. The pipeline always hands over exactly [frameSamples] mono
 * 16-bit samples, so a frame-based codec never has to buffer internally.
 *
 * [encode] may return zero packets (the codec is still filling its own
 * pipeline) or more than one; callers must handle both.
 */
interface AudioEncoder {
    val id: CodecId

    /** Codec sample rate. May be lower than the capture rate. */
    val sampleRate: Int

    /** Samples per frame at [sampleRate], mono. */
    val frameSamples: Int

    /**
     * Data the decoder needs before the first packet. Only valid after the
     * first successful [encode] call for codecs that derive it from the stream.
     */
    val initData: List<ByteArray>

    fun encode(pcm: ShortArray, offset: Int, count: Int): List<ByteArray>

    /** Requests a new target bitrate. Returns true if it was applied. */
    fun setBitrate(bitsPerSecond: Int): Boolean = false

    fun release()
}

/** Decoder contract. Returns mono 16-bit PCM at [sampleRate]. */
interface AudioDecoder {
    val id: CodecId
    val sampleRate: Int
    val frameSamples: Int

    /** @return decoded samples, or null when the codec produced nothing yet. */
    fun decode(packet: ByteArray, offset: Int, length: Int): ShortArray?

    /** Drops internal state after a link break so stale audio is not played. */
    fun reset()

    fun release()
}

/** Raised when a codec cannot be created on this device. */
class CodecUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
