package app.dibe.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileTest {
    @get:Rule val tmp = TemporaryFolder()

    /** RIFF size and data size as written in the header on disk. */
    private fun headerSizes(file: File): Pair<Long, Long> {
        val header = ByteBuffer.wrap(file.readBytes(), 0, WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        return header.getInt(4).toLong() and 0xFFFF_FFFFL to (header.getInt(40).toLong() and 0xFFFF_FFFFL)
    }

    private fun ramp(n: Int) = ShortArray(n) { (it * 7 - 3000).toShort() }

    @Test
    fun headerIsAStandardMono16kPcm16Header() {
        val file = tmp.newFile("a.wav")
        WavWriter(file).use { it.write(ramp(10), 10) }
        val h = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(file.readBytes(), 0, 4))
        assertEquals("WAVEfmt ", String(file.readBytes(), 8, 8))
        assertEquals(16, h.getInt(16))
        assertEquals(1.toShort(), h.getShort(20))
        assertEquals(1.toShort(), h.getShort(22))
        assertEquals(SAMPLE_RATE, h.getInt(24))
        assertEquals(SAMPLE_RATE * 2, h.getInt(28))
        assertEquals(2.toShort(), h.getShort(32))
        assertEquals(16.toShort(), h.getShort(34))
        assertEquals("data", String(file.readBytes(), 36, 4))
        assertEquals(36L + 20 to 20L, headerSizes(file))
        assertEquals(WAV_HEADER_BYTES + 20L, file.length())
    }

    @Test
    fun checkpointMakesTheFileValidWhileStillWriting() {
        val file = tmp.newFile("b.wav")
        val writer = WavWriter(file)
        writer.write(ramp(1600), 1600)
        writer.checkpoint()
        assertEquals(36L + 3200 to 3200L, headerSizes(file))
        writer.write(ramp(800), 800)
        // Not checkpointed: the header still says what the last checkpoint wrote.
        assertEquals(3200L, headerSizes(file).second)
        writer.close()
        assertEquals(4800L, headerSizes(file).second)
        assertEquals(2400L, writer.samples)
    }

    @Test
    fun aKilledWriterIsRepairedToTheLastWholeSample() {
        val file = tmp.newFile("c.wav")
        val writer = WavWriter(file)
        val audio = ramp(5000)
        writer.write(audio, 3000)
        writer.checkpoint()
        writer.write(audio.copyOfRange(3000, 5000), 2000)
        // The process dies here; the next run finds a stale header and half a sample at the end.
        RandomAccessFile(file, "rw").use { it.seek(it.length()); it.write(0x55) }

        assertEquals(5000L, repairWav(file))
        assertEquals(36L + 10_000 to 10_000L, headerSizes(file))
        assertEquals(WAV_HEADER_BYTES + 10_000L, file.length())
        assertArrayEquals(pcm16ToFloat(audio), readWavPcm(file), 0f)
    }

    @Test
    fun repairingAHealthyFileChangesNothing() {
        val file = tmp.newFile("d.wav")
        WavWriter(file).use { it.write(ramp(100), 100) }
        val before = file.readBytes()
        assertEquals(100L, repairWav(file))
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun aFileWithoutAHeaderHoldsNoAudio() {
        val file = tmp.newFile("e.wav")
        file.writeBytes(ByteArray(10))
        assertEquals(0L, repairWav(file))
    }

    @Test
    fun somethingElseIsLeftUntouched() {
        val file = tmp.newFile("f.wav")
        val bytes = ByteArray(100) { it.toByte() }
        file.writeBytes(bytes)
        val error = runCatching { repairWav(file) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun readingCrossesBufferBoundaries() {
        val file = tmp.newFile("g.wav")
        // More than one 64 KiB read, and an odd split across reads.
        val audio = ShortArray(70_001) { (it % 65_536 - 32_768).toShort() }
        WavWriter(file).use { it.write(audio, audio.size) }
        assertArrayEquals(pcm16ToFloat(audio), readWavPcm(file), 0f)
    }

    @Test
    fun aRangeIsReadFromItsSampleOnAndStopsAtTheEnd() {
        val file = tmp.newFile("h.wav")
        val audio = ramp(70_001)
        WavWriter(file).use { it.write(audio, audio.size) }
        assertEquals(70_001L, wavSamples(file))
        assertArrayEquals(pcm16ToFloat(audio.copyOfRange(33_333, 66_666)), readWavPcm(file, 33_333, 33_333), 0f)
        assertArrayEquals(pcm16ToFloat(audio.copyOfRange(70_000, 70_001)), readWavPcm(file, 70_000, 10), 0f)
    }
}
