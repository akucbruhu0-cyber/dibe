package app.dibe.android.audio

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val WAV_HEADER_BYTES = 44

/** RIFF sizes are 32-bit: ~37 h of 16 kHz mono PCM16. */
private const val MAX_DATA_BYTES = 0xFFFF_FFFFL - (WAV_HEADER_BYTES - 8)

/**
 * 16 kHz mono PCM16 WAV. Its header is rewritten at every [checkpoint], so a process killed
 * mid-recording leaves a file that plays up to the last checkpoint, and [repairWav] recovers the
 * rest.
 */
class WavWriter(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private val channel = raf.channel
    private var bytes = ByteBuffer.allocate(0)
    var samples = 0L
        private set

    init {
        try {
            raf.setLength(0)
            channel.write(wavHeader(0), 0)
            channel.position(WAV_HEADER_BYTES.toLong())
        } catch (e: IOException) {
            raf.close()
            throw e
        }
    }

    fun write(pcm: ShortArray, count: Int) {
        if ((samples + count) * 2 > MAX_DATA_BYTES) throw IOException("the recording reached the 4 GiB limit of a WAV file")
        if (bytes.capacity() < count * 2) bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.clear()
        bytes.asShortBuffer().put(pcm, 0, count)
        bytes.limit(count * 2)
        while (bytes.hasRemaining()) channel.write(bytes)
        samples += count
    }

    /** force() so a power cut, not only a killed process, keeps the audio up to here. */
    fun checkpoint() {
        channel.write(wavHeader(samples * 2), 0)
        channel.force(false)
    }

    override fun close() {
        try {
            checkpoint()
        } finally {
            raf.close()
        }
    }
}

fun wavHeader(dataBytes: Long): ByteBuffer = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
    put("RIFF".toByteArray())
    putInt((dataBytes + WAV_HEADER_BYTES - 8).toInt())
    put("WAVE".toByteArray())
    put("fmt ".toByteArray())
    putInt(16)
    putShort(1) // PCM
    putShort(1) // mono
    putInt(SAMPLE_RATE)
    putInt(SAMPLE_RATE * 2)
    putShort(2)
    putShort(16)
    put("data".toByteArray())
    putInt(dataBytes.toInt())
    flip()
}

/**
 * Puts the real sizes into the header of a [WavWriter] file whose process died between
 * checkpoints (everything after the header is audio; a half sample is cut off). Returns the sample
 * count. Throws [IOException] and leaves the file untouched if it is not such a file.
 */
fun repairWav(file: File): Long = RandomAccessFile(file, "rw").use { raf ->
    if (raf.length() < WAV_HEADER_BYTES) return 0
    val magic = ByteArray(4)
    raf.readFully(magic)
    if (String(magic) != "RIFF") throw IOException("${file.name} is not a WAV file")
    val data = ((raf.length() - WAV_HEADER_BYTES) and 1L.inv()).coerceAtMost(MAX_DATA_BYTES and 1L.inv())
    if (WAV_HEADER_BYTES + data != raf.length()) raf.setLength(WAV_HEADER_BYTES + data)
    raf.channel.write(wavHeader(data), 0)
    data / 2
}

/** Samples in a [WavWriter] file, by its length (its header may be stale). */
fun wavSamples(file: File): Long = ((file.length() - WAV_HEADER_BYTES) / 2).coerceAtLeast(0)

/** [count] samples of a [WavWriter] file from sample [start] on, as float; fewer where the file ends. */
fun readWavPcm(file: File, start: Long = 0, count: Int = (wavSamples(file) - start).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()): FloatArray {
    require(start >= 0 && count >= 0) { "start $start, count $count" }
    val out = FloatArray(count)
    var i = 0
    FileInputStream(file).channel.use { ch ->
        ch.position(WAV_HEADER_BYTES + start * 2)
        val buf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        while (i < out.size && ch.read(buf) >= 0) {
            buf.flip()
            while (buf.remaining() >= 2 && i < out.size) out[i++] = buf.getShort() / 32768f
            buf.compact()
        }
    }
    return if (i == out.size) out else out.copyOf(i)
}
