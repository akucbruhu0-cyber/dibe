package app.dibe.android.audio

import android.media.AudioRecord
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.channels.SendChannel
import java.io.IOException

/** 100 ms */
private const val CHUNK = SAMPLE_RATE / 10
private const val CHECKPOINT_EVERY_MS = 1_000L
/** Written where a pause starts: it marks the pause in the file, and it is the quiet live transcription needs to close the sentence being spoken. */
private const val PAUSE_GAP = SAMPLE_RATE

/**
 * Reads 16 kHz mono PCM16 from [mic], [system] (playback capture) or both on its own thread and
 * writes every sample to [wav]. A copy of each chunk goes to [live] with trySend, so a slow
 * transcriber never holds up the recording: a full queue drops the chunk and sets [liveDropped].
 * Owns the records and the writer from construction on (the thread starts at once): both are
 * released and closed when it ends.
 */
class AudioCapture(
    private val mic: AudioRecord?,
    @Volatile private var system: AudioRecord?,
    private val wav: WavWriter,
    private val live: SendChannel<FloatArray>?,
    private val onChunk: (recordedSamples: Long, level: Float) -> Unit,
    private val onSystemSilence: (silent: Boolean) -> Unit,
    /** Always called once, from the capture thread, after the WAV is closed. */
    private val onEnd: (error: Throwable?) -> Unit,
) {
    @Volatile var paused = false
    @Volatile var liveDropped = false
        private set
    val samples: Long get() = wav.samples

    @Volatile private var running = true
    private val thread = Thread(::loop, "dibe-capture")

    init {
        require(mic != null || system != null)
        thread.start()
    }

    /** Blocks until the WAV is closed. Safe to call more than once and from any thread. */
    fun stop() {
        running = false
        thread.join(1_000)
        if (thread.isAlive) {
            // A blocking read that never returns (no data at all): stopping the record unblocks it.
            listOfNotNull(mic, system).forEach { stopQuietly(it) }
            thread.join(2_000)
            // Still stuck: give up rather than hang the caller; repairWav fixes the header later.
            if (thread.isAlive) Log.e(TAG, "the capture thread did not stop")
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var error: Throwable? = null
        try {
            record()
        } catch (e: Throwable) {
            error = e
        } finally {
            listOfNotNull(mic, system).forEach {
                stopQuietly(it)
                it.release()
            }
            try {
                wav.close()
            } catch (e: IOException) {
                if (error == null) error = e
            }
            live?.close()
            onEnd(error)
        }
    }

    private fun record() {
        listOfNotNull(mic, system).forEach {
            it.startRecording()
            if (it.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("the audio input did not start")
        }
        val master = mic ?: system!!
        val both = mic != null && system != null
        val main = ShortArray(CHUNK)
        val side = ShortArray(CHUNK)
        val sideChunk = ShortArray(CHUNK)
        val mixed = ShortArray(CHUNK)
        val fifo = ShortFifo(SAMPLE_RATE)
        val silence = SilenceDetector()
        var wasPaused = false
        var lastCheckpoint = SystemClock.elapsedRealtime()

        while (running) {
            val n = master.read(main, 0, CHUNK)
            if (n < 0) throw IOException("audio read failed ($n)")
            if (n == 0) continue

            val out: ShortArray
            val systemAudio: ShortArray?
            if (both) {
                drainSystem(side, fifo)
                // Once the system side is gone this keeps padding silence, which the detector reports.
                fifo.take(sideChunk, n)
                mixAverage(main, sideChunk, n, mixed)
                out = mixed
                systemAudio = sideChunk
            } else {
                out = main
                systemAudio = if (mic == null) main else null
            }

            val pausing = paused
            if (pausing && wasPaused) {
                onChunk(wav.samples, 0f)
                continue
            }
            // The first chunk of a pause still counts: it was captured before the tap.
            emit(out, n)
            if (pausing) emit(ShortArray(PAUSE_GAP), PAUSE_GAP)
            wasPaused = pausing

            if (systemAudio != null && silence.feed(rms(systemAudio, n), n)) onSystemSilence(silence.silent)
            onChunk(wav.samples, rms(out, n))
            val now = SystemClock.elapsedRealtime()
            if (now - lastCheckpoint >= CHECKPOINT_EVERY_MS) {
                wav.checkpoint()
                lastCheckpoint = now
            }
        }
    }

    private fun drainSystem(buf: ShortArray, fifo: ShortFifo) {
        val record = system ?: return
        while (true) {
            val got = record.read(buf, 0, buf.size, AudioRecord.READ_NON_BLOCKING)
            if (got < 0) {
                Log.w(TAG, "system audio capture ended ($got); recording the mic only")
                stopQuietly(record)
                record.release()
                system = null
                return
            }
            if (got == 0) return
            fifo.push(buf, got)
        }
    }

    private fun emit(pcm: ShortArray, count: Int) {
        wav.write(pcm, count)
        val result = live?.trySend(pcm16ToFloat(pcm, count)) ?: return
        if (result.isFailure && !result.isClosed) liveDropped = true
    }

    private fun stopQuietly(record: AudioRecord) {
        try {
            record.stop()
        } catch (e: IllegalStateException) {
            // Never started, or already released.
        }
    }

    private companion object {
        const val TAG = "AudioCapture"
    }
}
