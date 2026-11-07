package com.dualstream.uniter.codec

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import com.dualstream.uniter.core.CodecId
import com.dualstream.uniter.core.Logx
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus through the platform [MediaCodec]. An Opus *encoder* is only a required
 * component from Android 10, and several vendor builds ship a decoder but no
 * encoder, so every entry point here reports unavailability instead of
 * crashing and the factory then falls back to ADPCM.
 */
object OpusSupport {

    const val MIME = "audio/opus"

    fun encoderName(): String? = findCodec(encoder = true)

    fun decoderName(): String? = findCodec(encoder = false)

    fun hasEncoder(): Boolean = encoderName() != null

    private fun findCodec(encoder: Boolean): String? {
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { info ->
                    info.isEncoder == encoder &&
                        info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
                }?.name
        } catch (t: Throwable) {
            Logx.w("opus", "codec enumeration failed", t)
            null
        }
    }

    /**
     * Builds the three csd buffers an Android Opus decoder expects when the
     * encoder did not provide them: the `OpusHead` identification header, the
     * codec delay and the seek pre-roll, both in little-endian nanoseconds.
     */
    fun syntheticCsd(sampleRate: Int, channels: Int, preSkipSamples: Int = 3840): List<ByteArray> {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1                               // version
        head[9] = channels.toByte()
        head[10] = (preSkipSamples and 0xFF).toByte()
        head[11] = ((preSkipSamples shr 8) and 0xFF).toByte()
        head[12] = (sampleRate and 0xFF).toByte()
        head[13] = ((sampleRate shr 8) and 0xFF).toByte()
        head[14] = ((sampleRate shr 16) and 0xFF).toByte()
        head[15] = ((sampleRate shr 24) and 0xFF).toByte()
        head[16] = 0                              // output gain lo
        head[17] = 0                              // output gain hi
        head[18] = 0                              // channel mapping family

        val delayNs = preSkipSamples.toLong() * 1_000_000_000L / sampleRate
        return listOf(head, le64(delayNs), le64(delayNs))
    }

    private fun le64(v: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
}

/**
 * Synchronous-mode Opus encoder. The pipeline feeds whole frames and drains
 * whatever the codec has ready, so a codec that buffers a frame internally only
 * adds its own latency and never stalls the capture thread.
 */
class OpusEncoder(
    override val sampleRate: Int,
    override val frameSamples: Int,
    bitrate: Int
) : AudioEncoder {

    override val id = CodecId.OPUS

    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private val csd = ArrayList<ByteArray>(3)
    private var ptsUs = 0L
    private var released = false

    override val initData: List<ByteArray>
        get() = if (csd.isNotEmpty()) csd.toList()
        else OpusSupport.syntheticCsd(sampleRate, 1)

    init {
        val name = OpusSupport.encoderName()
            ?: throw CodecUnavailableException("no Opus encoder on this device")
        codec = try {
            MediaCodec.createByCodecName(name).also { mc ->
                val fmt = MediaFormat.createAudioFormat(OpusSupport.MIME, sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameSamples * 2 * 4)
                    // The frame duration is decided by feeding exactly one frame per
                    // input buffer; vendors disagree about the format hints for it.
                }
                mc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                mc.start()
            }
        } catch (t: Throwable) {
            throw CodecUnavailableException("Opus encoder could not start", t)
        }
        Logx.i("opus", "encoder $name ${sampleRate}Hz mono ${bitrate}bps frame=$frameSamples")
    }

    override fun encode(pcm: ShortArray, offset: Int, count: Int): List<ByteArray> {
        if (released) return emptyList()
        try {
            val inIndex = codec.dequeueInputBuffer(FEED_TIMEOUT_US)
            if (inIndex >= 0) {
                val buf = codec.getInputBuffer(inIndex)!!
                buf.clear()
                buf.order(ByteOrder.nativeOrder())
                val sh = buf.asShortBuffer()
                val n = minOf(count, sh.remaining())
                sh.put(pcm, offset, n)
                buf.position(0)
                buf.limit(n * 2)
                codec.queueInputBuffer(inIndex, 0, n * 2, ptsUs, 0)
                ptsUs += n.toLong() * 1_000_000L / sampleRate
            } else {
                Logx.d("opus", "no encoder input buffer, frame skipped")
            }
            return drain()
        } catch (t: Throwable) {
            Logx.e("opus", "encode failed", t)
            return emptyList()
        }
    }

    private fun drain(): List<ByteArray> {
        var out: MutableList<ByteArray>? = null
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 0)
            if (idx < 0) break
            val buf = codec.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(bytes)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    if (csd.size < 3) csd.add(bytes)
                } else {
                    (out ?: ArrayList<ByteArray>(2).also { out = it }).add(bytes)
                }
            }
            codec.releaseOutputBuffer(idx, false)
        }
        return out ?: emptyList()
    }

    /**
     * MediaCodec exposes no portable live bitrate control for audio encoders
     * (`PARAMETER_KEY_VIDEO_BITRATE` is silently ignored by audio components),
     * so this always declines. The caller then restarts the stream with the new
     * bitrate, which is the only way to actually change it. Returning true here
     * would keep the old bitrate while reporting success.
     */
    override fun setBitrate(bitsPerSecond: Int): Boolean {
        Logx.i("opus", "live bitrate change to $bitsPerSecond needs a stream restart")
        return false
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private companion object {
        const val FEED_TIMEOUT_US = 10_000L
    }
}

/** Synchronous-mode Opus decoder driven by the receiver's network thread. */
class OpusDecoder(
    override val sampleRate: Int,
    override val frameSamples: Int,
    csd: List<ByteArray>
) : AudioDecoder {

    override val id = CodecId.OPUS

    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L
    private var released = false

    init {
        val name = OpusSupport.decoderName()
            ?: throw CodecUnavailableException("no Opus decoder on this device")
        val buffers = csd.takeIf { it.size >= 3 }
            ?: OpusSupport.syntheticCsd(sampleRate, 1)
        codec = try {
            MediaCodec.createByCodecName(name).also { mc ->
                val fmt = MediaFormat.createAudioFormat(OpusSupport.MIME, sampleRate, 1).apply {
                    buffers.forEachIndexed { i, b ->
                        setByteBuffer("csd-$i", ByteBuffer.wrap(b))
                    }
                }
                mc.configure(fmt, null, null, 0)
                mc.start()
            }
        } catch (t: Throwable) {
            throw CodecUnavailableException("Opus decoder could not start", t)
        }
        Logx.i("opus", "decoder $name ${sampleRate}Hz mono csd=${buffers.size}")
    }

    override fun decode(packet: ByteArray, offset: Int, length: Int): ShortArray? {
        if (released) return null
        return try {
            val inIndex = codec.dequeueInputBuffer(FEED_TIMEOUT_US)
            if (inIndex >= 0) {
                val buf = codec.getInputBuffer(inIndex)!!
                buf.clear()
                buf.put(packet, offset, length)
                codec.queueInputBuffer(inIndex, 0, length, ptsUs, 0)
                ptsUs += frameSamples.toLong() * 1_000_000L / sampleRate
            } else {
                Logx.d("opus", "no decoder input buffer, packet skipped")
            }
            drain()
        } catch (t: Throwable) {
            Logx.e("opus", "decode failed", t)
            null
        }
    }

    private fun drain(): ShortArray? {
        var acc: ShortArray? = null
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (idx < 0) break
            val buf = codec.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val sh = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                val chunk = ShortArray(sh.remaining())
                sh.get(chunk)
                acc = if (acc == null) chunk else acc + chunk
            }
            codec.releaseOutputBuffer(idx, false)
        }
        return acc
    }

    override fun reset() {
        if (released) return
        runCatching {
            codec.flush()
            codec.start()
        }.onFailure { Logx.w("opus", "decoder flush failed", it) }
        ptsUs = 0
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private companion object {
        const val FEED_TIMEOUT_US = 10_000L
    }
}
