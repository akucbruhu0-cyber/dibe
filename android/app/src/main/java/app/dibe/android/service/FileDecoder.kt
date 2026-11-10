package app.dibe.android.service

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.dibe.android.audio.WavWriter
import app.dibe.android.audio.decodedToMono
import app.dibe.android.engine.Resampler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

private const val TIMEOUT_US = 10_000L
/** Dequeue attempts without output after the end of input before the decoder counts as stuck (~2 s). */
private const val STUCK_AFTER = 200

/**
 * The first audio track of [file] into [wav] as 16 kHz mono PCM16, so no length of audio has to fit
 * in RAM. [onProgress] gets 0..100 by presentation time. Throws [SessionFailure] with
 * UNSUPPORTED_FORMAT (no audio track, unknown container or codec), CORRUPT_FILE (the decoder failed
 * on the data), NO_SPACE or ENGINE_FAILED (writing [wav], resampling).
 */
internal suspend fun decodeToWav(file: File, wav: File, onProgress: (Int) -> Unit): Unit =
    withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(file.path)
            } catch (e: IOException) {
                throw SessionFailure(Problem.UNSUPPORTED_FORMAT, e.message)
            } catch (e: IllegalArgumentException) {
                throw SessionFailure(Problem.UNSUPPORTED_FORMAT, e.message)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw SessionFailure(Problem.UNSUPPORTED_FORMAT, "no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: IOException) {
                throw SessionFailure(Problem.UNSUPPORTED_FORMAT, "no decoder for $mime")
            } catch (e: IllegalArgumentException) {
                throw SessionFailure(Problem.UNSUPPORTED_FORMAT, "no decoder for $mime")
            }
            try {
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    throw SessionFailure(Problem.UNSUPPORTED_FORMAT, "$mime: ${e.message}")
                } catch (e: IllegalArgumentException) {
                    throw SessionFailure(Problem.UNSUPPORTED_FORMAT, "$mime: ${e.message}")
                }
                Pcm16kWav(wav).use { out ->
                    try {
                        decodeLoop(extractor, codec, durationUs, out, onProgress)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IllegalStateException) {
                        throw SessionFailure(Problem.CORRUPT_FILE, e.message)
                    } catch (e: IllegalArgumentException) {
                        throw SessionFailure(Problem.CORRUPT_FILE, e.message)
                    }
                    out.finish()
                }
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

private suspend fun decodeLoop(
    extractor: MediaExtractor,
    codec: MediaCodec,
    durationUs: Long,
    out: Pcm16kWav,
    onProgress: (Int) -> Unit,
) {
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var idle = 0
    var rate = 0
    var channels = 0
    var encoding = AudioFormat.ENCODING_PCM_16BIT
    var progress = -1
    fun readFormat(f: MediaFormat) {
        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
    }
    while (true) {
        currentCoroutineContext().ensureActive()
        if (!inputDone) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                if (size < 0) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        when {
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val f = codec.outputFormat
                readFormat(f)
            }
            index >= 0 -> {
                idle = 0
                if (rate == 0) readFormat(codec.outputFormat)
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset).limit(info.offset + info.size)
                    val mono = try {
                        decodedToMono(buf, encoding, channels)
                    } catch (e: IllegalArgumentException) {
                        throw SessionFailure(Problem.UNSUPPORTED_FORMAT, e.message)
                    }
                    out.add(mono, rate)
                }
                codec.releaseOutputBuffer(index, false)
                if (durationUs > 0) {
                    val p = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                    if (p != progress) {
                        progress = p
                        onProgress(p)
                    }
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            inputDone && ++idle > STUCK_AFTER -> break
        }
    }
}

/** The decoded audio resampled to 16 kHz as it comes, into a WAV. */
private class Pcm16kWav(file: File) : AutoCloseable {
    private val wav = try {
        WavWriter(file)
    } catch (e: IOException) {
        throw e.toFailure(Problem.ENGINE_FAILED)
    }
    private var resampler: Resampler? = null
    private var rate = 0
    private var shorts = ShortArray(0)
    private var closed = false

    fun add(mono: FloatArray, rate: Int) {
        if (rate != this.rate) {
            // A stream that changes rate restarts the filter there: the one seam it cannot avoid.
            endResampler()
            resampler = native { Resampler(rate) }
            this.rate = rate
        }
        write(native { resampler!!.push(mono) })
    }

    /** Throws CORRUPT_FILE when no audio came. */
    fun finish() {
        endResampler()
        if (wav.samples == 0L) throw SessionFailure(Problem.CORRUPT_FILE, "no audio could be decoded")
        closed = true
        try {
            wav.close()
        } catch (e: IOException) {
            throw e.toFailure(Problem.ENGINE_FAILED)
        }
    }

    /** After a failure: the file is the caller's to delete. */
    override fun close() {
        resampler?.close()
        if (!closed) {
            closed = true
            wav.close()
        }
    }

    private fun endResampler() {
        val current = resampler ?: return
        resampler = null
        write(native { current.finish() })
    }

    private fun write(pcm: FloatArray) {
        if (shorts.size < pcm.size) shorts = ShortArray(pcm.size)
        for (i in pcm.indices) shorts[i] = (pcm[i] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
        try {
            wav.write(shorts, pcm.size)
        } catch (e: IOException) {
            throw e.toFailure(Problem.ENGINE_FAILED)
        }
    }

    /** The engine's resampler throws [RuntimeException] with the native error, or [UNSUPPORTED_CPU][app.dibe.android.engine.UNSUPPORTED_CPU]. */
    private fun <T> native(block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        throw SessionFailure(Problem.ENGINE_FAILED, e.message)
    }
}
