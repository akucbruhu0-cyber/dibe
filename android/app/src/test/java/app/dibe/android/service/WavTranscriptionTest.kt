package app.dibe.android.service

import app.dibe.android.audio.SAMPLE_RATE
import app.dibe.android.audio.WavWriter
import app.dibe.android.audio.pcm16ToFloat
import app.dibe.android.data.Segment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavTranscriptionTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A loud square wave with 200 ms of silence at each of [gapsAt] (samples). */
    private fun speech(samples: Int, vararg gapsAt: Int) = ShortArray(samples) { i ->
        if (gapsAt.any { i in it until it + CUT_FRAME_SAMPLES }) 0 else if (i / 20 % 2 == 0) 8_000 else -8_000
    }

    @Test
    fun windowsEndInTheQuietestStretchOfTheirLastSeconds() {
        // 8 s windows over 20 s: the gaps at 6 s and 13 s lie in the searched last 5 s of the first two.
        val audio = speech(20 * SAMPLE_RATE, 6 * SAMPLE_RATE, 13 * SAMPLE_RATE)
        val bounds = windowBounds(audio.size.toLong(), window = 8 * SAMPLE_RATE) { start, count ->
            pcm16ToFloat(audio.copyOfRange(start.toInt(), start.toInt() + count))
        }
        assertEquals(listOf(0L, 97_600L, 209_600L, 320_000L), bounds)
    }

    @Test
    fun shortFilesAreOneWindow() {
        assertEquals(listOf(0L, 100L), windowBounds(100) { _, _ -> error("no cut to look for") })
        assertEquals(listOf(0L), windowBounds(0) { _, _ -> error("no cut to look for") })
        assertEquals(listOf(0L, WINDOW_SAMPLES.toLong()), windowBounds(WINDOW_SAMPLES.toLong()) { _, _ -> error("no cut to look for") })
    }

    @Test
    fun evenSilenceIsCutAtItsStart() {
        assertEquals(CUT_FRAME_SAMPLES / 2, quietestPoint(FloatArray(CUT_SEARCH_SAMPLES), CUT_FRAME_SAMPLES, 160))
    }

    @Test
    fun eachWindowGoesToTheEngineWithItsSegmentsMovedToFileTime() {
        val audio = speech(20 * SAMPLE_RATE, 6 * SAMPLE_RATE, 13 * SAMPLE_RATE)
        val wav = tmp.newFile("a.wav")
        WavWriter(wav).use { it.write(audio, audio.size) }
        val windows = mutableListOf<FloatArray>()
        val streamed = mutableListOf<Segment>()
        val progress = mutableListOf<Int>()

        val segments = runBlocking {
            transcribeWav(wav, onProgress = { progress += it }, onSegment = { streamed += it }, window = 8 * SAMPLE_RATE) { pcm, onProgress, onSegment ->
                windows += pcm
                val segment = Segment(10, pcm.size * 100L / SAMPLE_RATE, "w${windows.size}")
                onProgress(50)
                onSegment(segment)
                listOf(segment)
            }
        }

        val expected = listOf(Segment(10, 610, "w1"), Segment(620, 1310, "w2"), Segment(1320, 2000, "w3"))
        assertEquals(expected, segments)
        assertEquals(expected, streamed)
        // Halfway through each window, as a share of the whole file.
        assertEquals(listOf(15, 48, 82), progress)
        assertEquals(listOf(97_600, 112_000, 110_400), windows.map { it.size })
        assertArrayEquals(pcm16ToFloat(audio.copyOfRange(97_600, 209_600)), windows[1], 0f)
    }
}
