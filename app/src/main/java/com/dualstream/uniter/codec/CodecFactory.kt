package com.dualstream.uniter.codec

import com.dualstream.uniter.core.CodecId
import com.dualstream.uniter.core.Logx
import com.dualstream.uniter.core.StreamConfig

/**
 * Chooses a concrete codec and the sample rate it runs at.
 *
 * Opus always runs at 48 kHz as required by section 4. ADPCM has a fixed 4 bits
 * per sample, so the only way it can honour a bitrate budget is by lowering the
 * sample rate; the pipeline resamples around it.
 */
object CodecFactory {

    const val OPUS_RATE = 48_000

    /** Resolves the sample rate a codec will actually use for [config]. */
    fun sampleRateFor(codec: CodecId, bitrate: Int): Int = when (codec) {
        CodecId.OPUS -> OPUS_RATE
        CodecId.ADPCM -> adpcmRateFor(bitrate)
        CodecId.PCM16 -> 16_000
    }

    /** ADPCM needs 4 bits per sample, so rate = bitrate / 4. */
    fun adpcmRateFor(bitrate: Int): Int = when {
        bitrate >= 64_000 -> 16_000
        bitrate >= 48_000 -> 12_000
        else -> 8_000
    }

    fun framesSamples(sampleRate: Int, frameMs: Int): Int = sampleRate * frameMs / 1000

    /**
     * Builds an encoder for [config], degrading to ADPCM when the requested
     * codec is not usable on this device. The returned encoder reports the
     * codec that was actually created.
     */
    fun createEncoder(config: StreamConfig): AudioEncoder {
        if (config.codec == CodecId.OPUS) {
            try {
                val rate = OPUS_RATE
                return OpusEncoder(rate, framesSamples(rate, config.frameMs), config.bitrate)
            } catch (e: CodecUnavailableException) {
                Logx.w("codec", "Opus unavailable, falling back to ADPCM: ${e.message}")
            }
        }
        if (config.codec == CodecId.PCM16) {
            val rate = sampleRateFor(CodecId.PCM16, config.bitrate)
            return Pcm16Encoder(rate, framesSamples(rate, config.frameMs))
        }
        val rate = adpcmRateFor(config.bitrate)
        return AdpcmEncoder(rate, framesSamples(rate, config.frameMs))
    }

    /**
     * Builds a decoder matching what the peer announced. [initData] carries the
     * Opus csd buffers received in the CODEC_INIT frame.
     */
    fun createDecoder(
        codec: CodecId,
        sampleRate: Int,
        frameMs: Int,
        initData: List<ByteArray>
    ): AudioDecoder {
        val frames = framesSamples(sampleRate, frameMs)
        return when (codec) {
            CodecId.OPUS -> OpusDecoder(sampleRate, frames, initData)
            CodecId.ADPCM -> AdpcmDecoder(sampleRate, frames)
            CodecId.PCM16 -> Pcm16Decoder(sampleRate, frames)
        }
    }

    /** True when the device can encode Opus; used to grey out the UI option. */
    fun opusEncodeSupported(): Boolean = OpusSupport.hasEncoder()
}
