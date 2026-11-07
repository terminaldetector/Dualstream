package com.dualstream.uniter

import com.dualstream.uniter.codec.AdpcmDecoder
import com.dualstream.uniter.codec.AdpcmEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AdpcmCodecTest {

    private fun tone(n: Int, freq: Double, rate: Int, amp: Double = 0.5): ShortArray =
        ShortArray(n) { i -> (sin(2 * PI * freq * i / rate) * amp * 32767).toInt().toShort() }

    @Test
    fun `packet size is four to one plus the state header`() {
        val enc = AdpcmEncoder(16_000, 320)
        val packets = enc.encode(ShortArray(320), 0, 320)
        assertEquals(1, packets.size)
        assertEquals(3 + 160, packets[0].size)
    }

    @Test
    fun `round trip keeps the signal to noise ratio above 20 dB`() {
        val rate = 16_000
        val frame = 320
        val enc = AdpcmEncoder(rate, frame)
        val dec = AdpcmDecoder(rate, frame)

        val input = tone(frame * 20, 440.0, rate)
        var signal = 0.0
        var noise = 0.0

        // Skip the first frame: the predictor needs a moment to track the signal.
        for (f in 0 until 20) {
            val off = f * frame
            val packet = enc.encode(input, off, frame).single()
            val out = dec.decode(packet, 0, packet.size)!!
            assertEquals(frame, out.size)
            if (f == 0) continue
            for (i in 0 until frame) {
                val s = input[off + i].toDouble()
                val e = s - out[i].toDouble()
                signal += s * s
                noise += e * e
            }
        }
        val snrDb = 10 * kotlin.math.log10(signal / noise.coerceAtLeast(1e-9))
        assertTrue("SNR was $snrDb dB", snrDb > 20.0)
    }

    @Test
    fun `decoder recovers after a dropped packet because state travels in band`() {
        val rate = 16_000
        val frame = 320
        val enc = AdpcmEncoder(rate, frame)
        val dec = AdpcmDecoder(rate, frame)
        val input = tone(frame * 6, 300.0, rate)

        val packets = (0 until 6).map { enc.encode(input, it * frame, frame).single() }

        // Decode frames 0, then skip 1 and 2, then resume at 3.
        dec.decode(packets[0], 0, packets[0].size)
        val resumed = dec.decode(packets[3], 0, packets[3].size)!!

        var noise = 0.0
        var signal = 0.0
        for (i in 0 until frame) {
            val s = input[3 * frame + i].toDouble()
            val e = s - resumed[i].toDouble()
            signal += s * s
            noise += e * e
        }
        val rms = sqrt(noise / frame) / 32768.0
        assertTrue("error rms after a gap was $rms", rms < 0.1)
    }

    @Test
    fun `silence encodes and decodes to silence`() {
        val enc = AdpcmEncoder(8_000, 160)
        val dec = AdpcmDecoder(8_000, 160)
        val packet = enc.encode(ShortArray(160), 0, 160).single()
        val out = dec.decode(packet, 0, packet.size)!!
        assertTrue(out.all { kotlin.math.abs(it.toInt()) < 16 })
    }

    @Test
    fun `short packets are rejected instead of crashing`() {
        val dec = AdpcmDecoder(8_000, 160)
        org.junit.Assert.assertNull(dec.decode(ByteArray(2), 0, 2))
    }
}
