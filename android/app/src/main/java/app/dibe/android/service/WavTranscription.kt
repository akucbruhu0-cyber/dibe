package app.dibe.android.service

import app.dibe.android.audio.SAMPLE_RATE
import app.dibe.android.audio.readWavPcm
import app.dibe.android.audio.wavSamples
import app.dibe.android.data.Segment
import app.dibe.android.engine.TranscriptionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 10 min: ~38 MB of float in RAM at a time, whatever the length of the file. */
internal const val WINDOW_SAMPLES = 10 * 60 * SAMPLE_RATE
/** A window ends in the quietest stretch of its last 5 s, so no word is cut in two. */
internal const val CUT_SEARCH_SAMPLES = 5 * SAMPLE_RATE
internal const val CUT_FRAME_SAMPLES = SAMPLE_RATE / 5
private const val CUT_HOP_SAMPLES = SAMPLE_RATE / 100

/**
 * A 16 kHz mono PCM16 WAV (a [app.dibe.android.audio.WavWriter] file) through [engine] one window at
 * a time. [onProgress] gets 0..100 over the whole file; segments, also those to [onSegment], carry
 * their times in the file. Throws [SessionFailure].
 */
internal suspend fun transcribeWav(
    engine: TranscriptionEngine,
    model: ReadyModel,
    wav: File,
    language: String,
    onProgress: (Int) -> Unit,
    onSegment: (Segment) -> Unit,
): List<Segment> = transcribeWav(wav, onProgress, onSegment) { pcm, progress, segment ->
    engine.transcribe(model.file, model.vad, pcm, language, progress, segment)
}

/** [transcribe] is the engine's, on one window; the window size is the tests'. */
internal suspend fun transcribeWav(
    wav: File,
    onProgress: (Int) -> Unit,
    onSegment: (Segment) -> Unit,
    window: Int = WINDOW_SAMPLES,
    transcribe: suspend (pcm16k: FloatArray, onProgress: (Int) -> Unit, onSegment: (Segment) -> Unit) -> List<Segment>,
): List<Segment> {
    val bounds = readWav { windowBounds(wavSamples(wav), window) { start, count -> readWavPcm(wav, start, count) } }
    val total = bounds.last().coerceAtLeast(1)
    val segments = ArrayList<Segment>()
    for ((start, end) in bounds.zipWithNext()) {
        currentCoroutineContext().ensureActive()
        val pcm = readWav { readWavPcm(wav, start, (end - start).toInt()) }
        val offset = start * 100 / SAMPLE_RATE
        // ponytail: each window loads its model if it must, so a concurrent job on another model makes
        // both reload per window; hold one engine lock across all windows if that ever matters.
        segments += engineCall {
            transcribe(
                pcm,
                { p -> onProgress(((start + (end - start) * p / 100) * 100 / total).toInt()) },
                { onSegment(it.shifted(offset)) },
            )
        }.map { it.shifted(offset) }
    }
    return segments
}

/**
 * Where the windows of a [total]-sample file start, and [total] last. Each is at most [window]
 * long; all but the last end in the middle of the quietest [frame] of their last [search] samples,
 * which [read] (start, count) gives.
 */
internal fun windowBounds(
    total: Long,
    window: Int = WINDOW_SAMPLES,
    search: Int = CUT_SEARCH_SAMPLES,
    frame: Int = CUT_FRAME_SAMPLES,
    hop: Int = CUT_HOP_SAMPLES,
    read: (start: Long, count: Int) -> FloatArray,
): List<Long> {
    require(frame in 1..search && search < window && hop > 0)
    val bounds = mutableListOf(0L)
    var start = 0L
    while (total - start > window) {
        val from = start + window - search
        start = from + quietestPoint(read(from, search), frame, hop)
        bounds += start
    }
    if (total > 0) bounds += total
    return bounds
}

/** The middle of the [frame]-long stretch of [pcm] with the least energy, the earliest of equals. */
internal fun quietestPoint(pcm: FloatArray, frame: Int, hop: Int): Int {
    if (pcm.size < frame) return pcm.size / 2
    val energy = DoubleArray(pcm.size + 1)
    for (i in pcm.indices) energy[i + 1] = energy[i] + pcm[i].toDouble() * pcm[i]
    var best = 0
    var at = 0
    while (at + frame <= pcm.size) {
        if (energy[at + frame] - energy[at] < energy[best + frame] - energy[best]) best = at
        at += hop
    }
    return best + frame / 2
}

internal fun Segment.shifted(cs: Long) = copy(start = start + cs, stop = stop + cs)

private suspend fun <T> readWav(block: () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (e: IOException) {
        throw e.toFailure(Problem.ENGINE_FAILED)
    }
}
