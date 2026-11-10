package app.dibe.android.audio

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** The rate the engine takes, and the rate everything is recorded at. */
const val SAMPLE_RATE = 16_000

fun pcm16ToFloat(pcm: ShortArray, count: Int = pcm.size): FloatArray = FloatArray(count) { pcm[it] / 32768f }

/** Averaging (not summing) keeps any two full-scale inputs inside 16 bits. */
fun mixAverage(a: ShortArray, b: ShortArray, count: Int, out: ShortArray) {
    for (i in 0 until count) out[i] = ((a[i] + b[i]) / 2).toShort()
}

/** 0..1 */
fun rms(pcm: ShortArray, count: Int): Float {
    if (count == 0) return 0f
    var sum = 0.0
    for (i in 0 until count) sum += pcm[i].toDouble() * pcm[i]
    return (sqrt(sum / count) / 32768).toFloat()
}

/**
 * The playback-capture side of a BOTH recording. The mic sets the pace; this absorbs the other
 * stream's jitter so the two stay sample-aligned without either read ever waiting on the other.
 */
// ponytail: clock drift between the two is absorbed by dropping (full) or padding (empty), no resampling.
class ShortFifo(private val capacity: Int) {
    private val data = ShortArray(capacity)
    var size = 0
        private set

    /** Returns how many of the oldest samples were dropped to make room. */
    fun push(src: ShortArray, count: Int): Int {
        if (count >= capacity) {
            System.arraycopy(src, count - capacity, data, 0, capacity)
            val dropped = size + count - capacity
            size = capacity
            return dropped
        }
        val overflow = (size + count - capacity).coerceAtLeast(0)
        if (overflow > 0) {
            System.arraycopy(data, overflow, data, 0, size - overflow)
            size -= overflow
        }
        System.arraycopy(src, 0, data, size, count)
        size += count
        return overflow
    }

    /** Fills [out] with the oldest [count] samples, padded with silence when fewer are queued. */
    fun take(out: ShortArray, count: Int) {
        val n = minOf(count, size)
        System.arraycopy(data, 0, out, 0, n)
        out.fill(0, n, count)
        System.arraycopy(data, n, data, 0, size - n)
        size -= n
    }
}

/**
 * Playback capture of an app that forbids it (allowAudioPlaybackCapture=false, calls, DRM) is not
 * an error: it is digital silence. This turns a long run of it into a warning.
 */
class SilenceDetector(
    private val thresholdRms: Float = 1e-4f,
    private val windowSamples: Long = 8L * SAMPLE_RATE,
) {
    private var quietSamples = 0L
    var silent = false
        private set

    /** Returns true when [silent] changed. */
    fun feed(rms: Float, samples: Int): Boolean {
        val was = silent
        if (rms < thresholdRms) {
            quietSamples += samples
            if (quietSamples >= windowSamples) silent = true
        } else {
            quietSamples = 0
            silent = false
        }
        return silent != was
    }
}

/**
 * Interleaved decoder output (position..limit of [buf]) as mono float. Throws
 * [IllegalArgumentException] for an encoding it does not know.
 */
fun decodedToMono(buf: ByteBuffer, encoding: Int, channels: Int): FloatArray {
    val bytesPerSample = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> throw IllegalArgumentException("PCM encoding $encoding")
    }
    require(channels > 0) { "$channels channels" }
    val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val frames = b.remaining() / (bytesPerSample * channels)
    var at = b.position()
    return FloatArray(frames) {
        var sum = 0f
        repeat(channels) {
            sum += when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> ((b.get(at).toInt() and 0xFF) - 128) / 128f
                AudioFormat.ENCODING_PCM_16BIT -> b.getShort(at) / 32768f
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> ((b.get(at).toInt() and 0xFF) or
                    ((b.get(at + 1).toInt() and 0xFF) shl 8) or
                    (b.get(at + 2).toInt() shl 16)) / 8388608f
                AudioFormat.ENCODING_PCM_32BIT -> b.getInt(at) / 2147483648f
                else -> b.getFloat(at)
            }
            at += bytesPerSample
        }
        sum / channels
    }
}
