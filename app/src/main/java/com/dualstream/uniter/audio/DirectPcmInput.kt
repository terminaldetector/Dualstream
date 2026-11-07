package com.dualstream.uniter.audio

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Direct PCM input (section 5, last paragraph): an in-process queue that an
 * integrated player inside this app — or a future plug-in — can push frames
 * into, bypassing MediaProjection entirely.
 *
 * Writers call [offer]; the transmitter pipeline reads it like any other
 * capture source.
 */
class DirectPcmInput(
    override val sampleRate: Int = 48_000,
    capacityFrames: Int = 32
) : CaptureSource {

    override val description = "direct PCM input"

    private val queue = ArrayBlockingQueue<ShortArray>(capacityFrames)
    private var head: ShortArray? = null
    private var headOffset = 0

    @Volatile
    private var running = false

    override fun start(): Boolean {
        running = true
        return true
    }

    /**
     * Pushes mono samples at [sampleRate]. Drops the oldest frame when full, so
     * a slow radio never blocks the producer.
     * @return true if the frame was queued without dropping anything
     */
    fun offer(frame: ShortArray): Boolean {
        if (!running) return false
        if (queue.offer(frame)) return true
        queue.poll()
        return queue.offer(frame)
    }

    override fun read(dst: ShortArray): Int {
        if (!running) return -1
        var written = 0
        while (written < dst.size) {
            var chunk = head
            if (chunk == null) {
                chunk = if (written == 0) {
                    queue.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } else {
                    queue.poll()
                }
                if (chunk == null) break
                head = chunk
                headOffset = 0
            }
            val avail = chunk.size - headOffset
            val n = minOf(avail, dst.size - written)
            chunk.copyInto(dst, written, headOffset, headOffset + n)
            written += n
            headOffset += n
            if (headOffset >= chunk.size) {
                head = null
                headOffset = 0
            }
        }
        return written
    }

    override fun stop() {
        running = false
        queue.clear()
        head = null
        headOffset = 0
    }

    private companion object {
        const val READ_TIMEOUT_MS = 100L
    }
}
