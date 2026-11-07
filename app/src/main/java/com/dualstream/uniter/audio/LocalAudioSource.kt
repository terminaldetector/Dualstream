package com.dualstream.uniter.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.dualstream.uniter.core.Logx
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The "local managed source" of section 6.
 *
 * Section 6 is explicit that without system privileges the app cannot grab
 * arbitrary audio produced by phone A and force it into one ear. So HARD_SPLIT
 * and PRIORITY operate on a source the app itself owns and decodes. Anything
 * else would be a promise the platform does not allow us to keep.
 */
interface LocalAudioSource {
    /** Human-readable description for the UI. */
    val description: String

    /**
     * Fills [dst] with mono 16-bit samples at [Pcm.OUT_RATE].
     * @return samples written; 0 means "nothing to play right now".
     */
    fun read(dst: ShortArray): Int

    fun release()
}

/** Silent source: used when no local content is selected. */
object SilentSource : LocalAudioSource {
    override val description = "none"
    override fun read(dst: ShortArray): Int = 0
    override fun release() = Unit
}

/**
 * Continuous sine generator. This is the Stage 1 test signal: it lets the pair
 * be verified end to end before any capture permission exists.
 */
class ToneSource(
    private val frequency: Double = 440.0,
    private val amplitude: Double = 0.25,
    private val sampleRate: Int = Pcm.OUT_RATE
) : LocalAudioSource {

    override val description = "test tone ${frequency.toInt()} Hz"

    private var phase = 0.0
    private val step = 2.0 * PI * frequency / sampleRate

    override fun read(dst: ShortArray): Int {
        for (i in dst.indices) {
            dst[i] = (sin(phase) * amplitude * 32767.0).toInt().toShort()
            phase += step
            if (phase > 2 * PI) phase -= 2 * PI
        }
        return dst.size
    }

    override fun release() = Unit
}

/**
 * Decodes a local media file (content URI) to mono 48 kHz PCM with
 * MediaExtractor + MediaCodec, looping when it reaches the end.
 *
 * This is the app-managed source that makes HARD_SPLIT honest: the app owns the
 * decode, so it can legitimately place it in one ear.
 */
class FileLocalSource(
    context: Context,
    uri: Uri,
    private val loop: Boolean = true
) : LocalAudioSource {

    override val description: String = uri.lastPathSegment ?: uri.toString()

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var resampler: Resampler? = null
    private var srcChannels = 1
    private var eosQueued = false
    private var finished = false

    /** Decoded-but-not-yet-consumed mono samples at the output rate. */
    private val pending = ArrayDeque<ShortArray>()
    private var pendingHeadOffset = 0

    private var monoScratch = ShortArray(4096)
    private var resampleScratch = ShortArray(8192)

    init {
        extractor.setDataSource(context, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("no audio track in $uri")

        extractor.selectTrack(trackIndex)
        val fmt = extractor.getTrackFormat(trackIndex)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        srcChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceIn(1, 2)
        val srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE, Pcm.OUT_RATE)
        resampler = Resampler(srcRate, Pcm.OUT_RATE)

        codec = MediaCodec.createDecoderByType(mime).apply {
            configure(fmt, null, null, 0)
            start()
        }
        Logx.i("local", "decoding $description $mime ${srcRate}Hz ch=$srcChannels")
    }

    override fun read(dst: ShortArray): Int {
        var written = 0
        while (written < dst.size) {
            val chunk = pending.firstOrNull()
            if (chunk == null) {
                if (!pump()) break
                continue
            }
            val avail = chunk.size - pendingHeadOffset
            val n = minOf(avail, dst.size - written)
            chunk.copyInto(dst, written, pendingHeadOffset, pendingHeadOffset + n)
            written += n
            pendingHeadOffset += n
            if (pendingHeadOffset >= chunk.size) {
                pending.removeFirst()
                pendingHeadOffset = 0
            }
        }
        // Pad with silence rather than returning a short buffer: the mixer runs
        // on a fixed frame grid.
        if (written in 1 until dst.size) {
            java.util.Arrays.fill(dst, written, dst.size, 0)
            written = dst.size
        }
        return written
    }

    /** Moves the decoder forward one step. @return false when nothing more will come. */
    private fun pump(): Boolean {
        val mc = codec ?: return false
        if (finished) return false

        if (!eosQueued) {
            val inIndex = mc.dequeueInputBuffer(2_000)
            if (inIndex >= 0) {
                val buf = mc.getInputBuffer(inIndex)!!
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) {
                    if (loop) {
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        mc.queueInputBuffer(inIndex, 0, 0, 0, 0)
                    } else {
                        mc.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    }
                } else {
                    mc.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }

        val outIndex = mc.dequeueOutputBuffer(info, 2_000)
        when {
            outIndex >= 0 -> {
                val buf = mc.getOutputBuffer(outIndex)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val sh = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val raw = ShortArray(sh.remaining())
                    sh.get(raw)
                    pending.addLast(toMonoAtOutputRate(raw))
                }
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                mc.releaseOutputBuffer(outIndex, false)
                if (eos) finished = true
                return true
            }
            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val fmt = mc.outputFormat
                srcChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT, srcChannels).coerceIn(1, 2)
                val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE, Pcm.OUT_RATE)
                resampler = Resampler(rate, Pcm.OUT_RATE)
                return true
            }
            else -> return !eosQueued
        }
    }

    private fun toMonoAtOutputRate(raw: ShortArray): ShortArray {
        val monoCount: Int
        val mono: ShortArray
        if (srcChannels == 2) {
            if (monoScratch.size < raw.size / 2) monoScratch = ShortArray(raw.size / 2)
            monoCount = Pcm.downmixStereoToMono(raw, raw.size, monoScratch)
            mono = monoScratch
        } else {
            monoCount = raw.size
            mono = raw
        }
        val rs = resampler ?: return mono.copyOf(monoCount)
        if (rs.bypass) return mono.copyOf(monoCount)
        val need = rs.maxOut(monoCount)
        if (resampleScratch.size < need) resampleScratch = ShortArray(need)
        val n = rs.process(mono, 0, monoCount, resampleScratch)
        return resampleScratch.copyOf(n)
    }

    override fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
        codec = null
        pending.clear()
    }
}
