package com.dualstream.uniter.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Thrown when the stream is damaged beyond the resynchronisation window. */
class FrameFormatException(msg: String) : IOException(msg)

/**
 * Blocking frame writer. One instance per socket; [write] is synchronised so the
 * audio thread and the keep-alive thread can share a socket safely.
 */
class FrameWriter(private val out: OutputStream) {

    private val header = ByteArray(Protocol.HEADER_SIZE)
    private val lock = Any()

    @Throws(IOException::class)
    fun write(type: Int, seq: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
        require(length in 0..Protocol.MAX_PAYLOAD) { "payload too large: $length" }
        synchronized(lock) {
            header[0] = Protocol.MAGIC_0
            header[1] = Protocol.MAGIC_1
            header[2] = Protocol.VERSION.toByte()
            header[3] = type.toByte()
            header[4] = ((seq ushr 8) and 0xFF).toByte()
            header[5] = (seq and 0xFF).toByte()
            header[6] = ((length ushr 8) and 0xFF).toByte()
            header[7] = (length and 0xFF).toByte()
            val crc = Crc16.compute(payload, offset, length)
            header[8] = ((crc ushr 8) and 0xFF).toByte()
            header[9] = (crc and 0xFF).toByte()
            out.write(header)
            if (length > 0) out.write(payload, offset, length)
            out.flush()
        }
    }
}

/**
 * Blocking frame reader with magic-word resynchronisation. A single reader
 * thread owns one instance.
 */
class FrameReader(private val input: InputStream) {

    private val header = ByteArray(Protocol.HEADER_SIZE)

    /** Number of bytes skipped while hunting for a magic word; useful for diagnostics. */
    var resyncBytes: Long = 0
        private set

    /**
     * Reads the next frame, blocking until one is available.
     * @throws EOFException when the peer closed the socket.
     */
    @Throws(IOException::class)
    fun read(): Frame {
        syncToMagic()
        readFully(header, 2, Protocol.HEADER_SIZE - 2)

        val version = header[2].toInt() and 0xFF
        if (version != Protocol.VERSION) {
            throw FrameFormatException("unsupported protocol version $version")
        }
        val type = header[3].toInt() and 0xFF
        val seq = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
        val len = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
        val crc = ((header[8].toInt() and 0xFF) shl 8) or (header[9].toInt() and 0xFF)
        if (len > Protocol.MAX_PAYLOAD) {
            throw FrameFormatException("payload length $len exceeds ${Protocol.MAX_PAYLOAD}")
        }
        val payload = ByteArray(len)
        if (len > 0) readFully(payload, 0, len)
        if (Crc16.compute(payload) != crc) {
            throw FrameFormatException("crc mismatch on type=$type seq=$seq len=$len")
        }
        return Frame(type, seq, payload)
    }

    private fun syncToMagic() {
        var first = readByte()
        while (true) {
            if (first == Protocol.MAGIC_0) {
                val second = readByte()
                if (second == Protocol.MAGIC_1) {
                    header[0] = Protocol.MAGIC_0
                    header[1] = Protocol.MAGIC_1
                    return
                }
                resyncBytes++
                first = second
            } else {
                resyncBytes++
                first = readByte()
            }
        }
    }

    private fun readByte(): Byte {
        val v = input.read()
        if (v < 0) throw EOFException("peer closed the RFCOMM stream")
        return v.toByte()
    }

    private fun readFully(buf: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val n = input.read(buf, offset + done, length - done)
            if (n < 0) throw EOFException("peer closed the RFCOMM stream")
            done += n
        }
    }
}
