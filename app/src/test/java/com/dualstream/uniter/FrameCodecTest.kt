package com.dualstream.uniter

import com.dualstream.uniter.protocol.Crc16
import com.dualstream.uniter.protocol.FrameFormatException
import com.dualstream.uniter.protocol.FrameReader
import com.dualstream.uniter.protocol.FrameWriter
import com.dualstream.uniter.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class FrameCodecTest {

    @Test
    fun `round trip preserves type seq and payload`() {
        val bos = ByteArrayOutputStream()
        val w = FrameWriter(bos)
        val payload = ByteArray(300) { (it * 7).toByte() }
        w.write(Protocol.Type.AUDIO, 1234, payload)

        val r = FrameReader(ByteArrayInputStream(bos.toByteArray()))
        val f = r.read()
        assertEquals(Protocol.Type.AUDIO, f.type)
        assertEquals(1234, f.seq)
        assertTrue(payload.contentEquals(f.payload))
    }

    @Test
    fun `several frames stream back in order`() {
        val bos = ByteArrayOutputStream()
        val w = FrameWriter(bos)
        for (i in 0 until 50) {
            w.write(Protocol.Type.AUDIO, i, ByteArray(10) { i.toByte() })
        }
        val r = FrameReader(ByteArrayInputStream(bos.toByteArray()))
        for (i in 0 until 50) {
            val f = r.read()
            assertEquals(i, f.seq)
            assertEquals(i.toByte(), f.payload[0])
        }
    }

    @Test
    fun `empty payload is legal`() {
        val bos = ByteArrayOutputStream()
        FrameWriter(bos).write(Protocol.Type.BYE, 0, ByteArray(0))
        val f = FrameReader(ByteArrayInputStream(bos.toByteArray())).read()
        assertEquals(Protocol.Type.BYE, f.type)
        assertEquals(0, f.payload.size)
    }

    @Test
    fun `reader resynchronises after leading garbage`() {
        val bos = ByteArrayOutputStream()
        bos.write(byteArrayOf(0x00, 0x11, 0xD5.toByte(), 0x22, 0x7F))
        FrameWriter(bos).write(Protocol.Type.PING, 9, byteArrayOf(1, 2, 3))

        val r = FrameReader(ByteArrayInputStream(bos.toByteArray()))
        val f = r.read()
        assertEquals(Protocol.Type.PING, f.type)
        assertEquals(9, f.seq)
        assertTrue(r.resyncBytes > 0)
    }

    @Test
    fun `corrupted payload is rejected by the checksum`() {
        val bos = ByteArrayOutputStream()
        FrameWriter(bos).write(Protocol.Type.AUDIO, 1, ByteArray(32) { 5 })
        val bytes = bos.toByteArray()
        bytes[Protocol.HEADER_SIZE + 4] = 99      // flip a payload byte

        try {
            FrameReader(ByteArrayInputStream(bytes)).read()
            fail("expected a checksum failure")
        } catch (e: FrameFormatException) {
            assertTrue(e.message!!.contains("crc"))
        }
    }

    @Test
    fun `truncated stream reports end of file`() {
        val bos = ByteArrayOutputStream()
        FrameWriter(bos).write(Protocol.Type.AUDIO, 1, ByteArray(64))
        val cut = bos.toByteArray().copyOf(Protocol.HEADER_SIZE + 10)
        try {
            FrameReader(ByteArrayInputStream(cut)).read()
            fail("expected EOF")
        } catch (e: EOFException) {
            // expected
        }
    }

    @Test
    fun `crc16 matches the ccitt false reference vector`() {
        assertEquals(0x29B1, Crc16.compute("123456789".toByteArray()))
    }
}
