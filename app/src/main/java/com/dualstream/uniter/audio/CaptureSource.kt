package com.dualstream.uniter.audio

/**
 * A producer of mono 16-bit PCM at [sampleRate] on phone B.
 */
interface CaptureSource {
    val sampleRate: Int
    val description: String

    fun start(): Boolean

    /**
     * Blocking read of up to `dst.size` mono samples.
     * @return samples written, 0 on timeout, negative on a fatal error
     */
    fun read(dst: ShortArray): Int

    fun stop()
}
