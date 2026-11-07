package com.dualstream.uniter

import com.dualstream.uniter.core.CaptureState
import com.dualstream.uniter.core.CodecId
import com.dualstream.uniter.core.Role
import com.dualstream.uniter.protocol.AudioPayload
import com.dualstream.uniter.protocol.CodecInit
import com.dualstream.uniter.protocol.ConfigUpdate
import com.dualstream.uniter.protocol.Hello
import com.dualstream.uniter.protocol.StateUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {

    @Test
    fun `hello survives a round trip`() {
        val h = Hello(Role.TRANSMITTER, CodecId.OPUS, 48_000, 1, 20, 32_000, "Pixel 7a")
        assertEquals(h, Hello.decode(h.encode()))
    }

    @Test
    fun `hello rejects a truncated payload`() {
        assertNull(Hello.decode(ByteArray(5)))
    }

    @Test
    fun `hello rejects an impossible sample rate`() {
        val h = Hello(Role.TRANSMITTER, CodecId.ADPCM, 48_000, 1, 20, 32_000, "x")
        val bytes = h.encode()
        bytes[2] = 0x7F   // sample rate becomes absurd
        assertNull(Hello.decode(bytes))
    }

    @Test
    fun `config update round trip`() {
        val c = ConfigUpdate(48_000, 10)
        assertEquals(c, ConfigUpdate.decode(c.encode()))
    }

    @Test
    fun `state update round trip for every capture state`() {
        CaptureState.entries.forEach { s ->
            assertEquals(StateUpdate(s), StateUpdate.decode(StateUpdate(s).encode()))
        }
    }

    @Test
    fun `codec init carries several buffers`() {
        val init = CodecInit(listOf(ByteArray(19) { 1 }, ByteArray(8) { 2 }, ByteArray(8) { 3 }))
        val back = CodecInit.decode(init.encode())!!
        assertEquals(3, back.buffers.size)
        assertEquals(19, back.buffers[0].size)
        assertTrue(back.buffers[2].all { it == 3.toByte() })
    }

    @Test
    fun `codec init tolerates an empty list`() {
        assertEquals(0, CodecInit.decode(CodecInit(emptyList()).encode())!!.buffers.size)
    }

    @Test
    fun `audio payload header carries flags and timestamp`() {
        val p = ByteArray(AudioPayload.HEADER + 4)
        AudioPayload.writeHeader(p, AudioPayload.FLAG_SILENCE, 1_234_567)
        assertTrue(AudioPayload.isSilence(p))
        assertEquals(1_234_567, AudioPayload.timestampMs(p))
    }
}
