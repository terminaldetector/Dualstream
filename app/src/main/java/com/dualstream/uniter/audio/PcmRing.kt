package com.dualstream.uniter.audio

/**
 * Fixed-capacity mono sample ring that decouples two threads running on
 * different clocks.
 *
 * Both microphone paths need this. A recorder is paced by the audio HAL and a
 * player by its own output clock; the two drift, so a reader must never block
 * on the writer. [read] therefore returns whatever is available and leaves the
 * caller to pad with silence, and [write] drops the oldest samples instead of
 * blocking when the reader falls behind. Dropping old audio is correct here:
 * live microphone input that has aged past the buffer is of no use.
 */
class PcmRing(val capacity: Int) {

    private val buf = ShortArray(capacity)
    private var head = 0          // next sample to read
    private var count = 0         // samples currently stored
    private val lock = Any()

    /** Samples discarded because the reader could not keep up. */
    var overflowed: Long = 0
        private set

    val available: Int get() = synchronized(lock) { count }

    fun write(src: ShortArray, offset: Int = 0, length: Int = src.size) {
        if (length <= 0) return
        synchronized(lock) {
            // A write larger than the ring can only keep its newest tail.
            var srcOffset = offset
            var srcLength = length
            if (srcLength > capacity) {
                val skip = srcLength - capacity
                srcOffset += skip
                srcLength = capacity
                overflowed += skip
            }
            val free = capacity - count
            if (srcLength > free) {
                val drop = srcLength - free
                head = (head + drop) % capacity
                count -= drop
                overflowed += drop
            }
            var tail = (head + count) % capacity
            var written = 0
            while (written < srcLength) {
                val chunk = minOf(srcLength - written, capacity - tail)
                src.copyInto(buf, tail, srcOffset + written, srcOffset + written + chunk)
                written += chunk
                tail = (tail + chunk) % capacity
            }
            count += srcLength
        }
    }

    /** @return samples written into [dst]; never blocks. */
    fun read(dst: ShortArray, offset: Int = 0, length: Int = dst.size - offset): Int {
        if (length <= 0) return 0
        synchronized(lock) {
            val n = minOf(length, count)
            var read = 0
            while (read < n) {
                val chunk = minOf(n - read, capacity - head)
                buf.copyInto(dst, offset + read, head, head + chunk)
                read += chunk
                head = (head + chunk) % capacity
            }
            count -= n
            return n
        }
    }

    fun clear() {
        synchronized(lock) {
            head = 0
            count = 0
        }
    }

    fun resetCounters() {
        synchronized(lock) { overflowed = 0 }
    }
}
