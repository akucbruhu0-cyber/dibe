package app.dibe.android.audio

import android.media.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmTest {
    @Test
    fun mixAverageNeverClips() {
        val a = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 1000, 0)
        val b = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, -1000, 3)
        val out = ShortArray(4)
        mixAverage(a, b, 4, out)
        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 0, 1), out)
    }

    @Test
    fun rmsOfFullScaleIsOneAndSilenceIsZero() {
        assertEquals(1f, rms(ShortArray(100) { Short.MIN_VALUE }, 100), 1e-6f)
        assertEquals(0f, rms(ShortArray(100), 100), 0f)
        assertEquals(0f, rms(ShortArray(0), 0), 0f)
    }

    @Test
    fun pcm16ToFloatScales() {
        assertArrayEquals(floatArrayOf(-1f, 0f, 0.5f), pcm16ToFloat(shortArrayOf(-32768, 0, 16384, 99), 3), 0f)
    }

    @Test
    fun fifoPadsWithSilenceWhenShortAndKeepsOrder() {
        val fifo = ShortFifo(8)
        fifo.push(shortArrayOf(1, 2, 3), 3)
        val out = ShortArray(5) { 9 }
        fifo.take(out, 5)
        assertArrayEquals(shortArrayOf(1, 2, 3, 0, 0), out)
        assertEquals(0, fifo.size)
        fifo.push(shortArrayOf(4, 5, 6, 7), 2)
        fifo.take(out, 1)
        assertEquals(4.toShort(), out[0])
        assertEquals(1, fifo.size)
    }

    @Test
    fun fifoDropsTheOldestWhenFull() {
        val fifo = ShortFifo(4)
        assertEquals(0, fifo.push(shortArrayOf(1, 2, 3), 3))
        assertEquals(2, fifo.push(shortArrayOf(4, 5, 6), 3))
        val out = ShortArray(4)
        fifo.take(out, 4)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), out)
        assertEquals(5, fifo.push(ShortArray(9) { it.toShort() }, 9))
        fifo.take(out, 4)
        assertArrayEquals(shortArrayOf(5, 6, 7, 8), out)
    }

    @Test
    fun silenceIsReportedAfterTheWindowAndClearedByAnySound() {
        val detector = SilenceDetector(thresholdRms = 1e-4f, windowSamples = 8L * SAMPLE_RATE)
        val chunk = SAMPLE_RATE / 10
        repeat(79) { assertFalse(detector.feed(0f, chunk)) }
        assertTrue("8 s of silence", detector.feed(0f, chunk))
        assertTrue(detector.silent)
        assertFalse(detector.feed(0f, chunk))
        assertTrue("sound clears it", detector.feed(0.01f, chunk))
        assertFalse(detector.silent)
        repeat(79) { detector.feed(0f, chunk) }
        assertFalse("the count restarts after sound", detector.silent)
    }

    private fun bytes(capacity: Int, fill: ByteBuffer.() -> Unit): ByteBuffer =
        ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN).apply(fill).also { it.flip() }

    @Test
    fun decodedStereo16BitIsDownmixed() {
        val buf = bytes(8) { putShort(16384); putShort(0); putShort(-32768); putShort(-32768) }
        assertArrayEquals(floatArrayOf(0.25f, -1f), decodedToMono(buf, AudioFormat.ENCODING_PCM_16BIT, 2), 1e-6f)
    }

    @Test
    fun decodedFloat8Bit24BitAnd32BitAreScaled() {
        val float = bytes(8) { putFloat(0.5f); putFloat(-0.25f) }
        assertArrayEquals(floatArrayOf(0.5f, -0.25f), decodedToMono(float, AudioFormat.ENCODING_PCM_FLOAT, 1), 0f)

        val eight = bytes(3) { put(0x80.toByte()); put(0xFF.toByte()); put(0) }
        assertArrayEquals(floatArrayOf(0f, 127 / 128f, -1f), decodedToMono(eight, AudioFormat.ENCODING_PCM_8BIT, 1), 1e-6f)

        // 0x400000 = +0.5, 0x800000 = -1 as little-endian packed 24-bit.
        val packed = bytes(6) { put(0); put(0); put(0x40); put(0); put(0); put(0x80.toByte()) }
        assertArrayEquals(floatArrayOf(0.5f, -1f), decodedToMono(packed, AudioFormat.ENCODING_PCM_24BIT_PACKED, 1), 1e-6f)

        val int32 = bytes(8) { putInt(Int.MIN_VALUE); putInt(1 shl 30) }
        assertArrayEquals(floatArrayOf(-1f, 0.5f), decodedToMono(int32, AudioFormat.ENCODING_PCM_32BIT, 1), 1e-6f)
    }

    @Test
    fun decodedRespectsPositionAndDropsAPartialFrame() {
        val buf = bytes(10) { putShort(1); putShort(16384); putShort(-16384); putShort(0); put(7); put(7) }
        buf.position(2)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f, 0f), decodedToMono(buf, AudioFormat.ENCODING_PCM_16BIT, 1).copyOf(3), 1e-6f)
        // 7 bytes of stereo PCM16 hold one whole frame.
        assertArrayEquals(floatArrayOf(0f), decodedToMono(buf.duplicate().also { it.limit(9) }, AudioFormat.ENCODING_PCM_16BIT, 2), 1e-6f)
        assertEquals("the caller's buffer is not moved", 2, buf.position())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownEncodingIsRefused() {
        decodedToMono(bytes(4) { putInt(0) }, AudioFormat.ENCODING_AC3, 1)
    }
}
