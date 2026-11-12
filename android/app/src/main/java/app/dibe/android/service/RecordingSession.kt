package app.dibe.android.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.dibe.android.app
import app.dibe.android.audio.AudioCapture
import app.dibe.android.audio.SAMPLE_RATE
import app.dibe.android.audio.WavWriter
import app.dibe.android.data.Segment
import app.dibe.android.data.TranscriptEntry
import app.dibe.android.data.TranscriptRecord
import app.dibe.android.data.TranscriptStore
import app.dibe.android.engine.LiveEvent
import app.dibe.android.settings.AppSettings
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "RecordingSession"
/** 30 s of 100 ms chunks between the capture and live inference: how far inference may fall behind before audio is skipped. */
private const val LIVE_QUEUE_CHUNKS = 300
private const val ADVANCE_EVERY_MS = 500L
/** How long Stop waits for the live transcript before transcribing the recording instead. */
private const val LIVE_FINISH_TIMEOUT_MS = 30_000L
/** 2 s, so a disk stall at a checkpoint never overruns the input. */
private const val RECORD_BUFFER_BYTES = 2 * SAMPLE_RATE * 2

/**
 * One recording, from the first sample to the saved transcript. The WAV stays in
 * [IN_PROGRESS_DIR] until the transcript is saved, so a process killed at any point (finishing
 * included) leaves it for [recoverRecordings]. Live transcription only ever reads a copy of the
 * audio: whatever happens to it, the recording is transcribed from the WAV when the live
 * transcript is not known to be complete.
 */
internal class RecordingSession(
    private val context: Context,
    val source: AudioSource,
    private val projection: MediaProjection?,
    private val force: Boolean,
    /** False while a file job holds the engine: the recording is transcribed after it instead. */
    private val liveAllowed: Boolean,
) {
    private val app = context.app
    private val state = app.sessions.recordingState
    private val ended = CompletableDeferred<Unit>()
    private val startedAt = LocalDateTime.now()
    @Volatile private var capture: AudioCapture? = null
    @Volatile private var captureError: Throwable? = null
    @Volatile private var baseWarning: Problem? = null
    @Volatile private var systemSilent = false
    @Volatile private var micTaken = false
    private var micWatch: AudioManager.AudioRecordingCallback? = null

    fun pause() {
        capture?.paused = true
        state.update { if (it.phase == RecordingPhase.RECORDING) it.copy(phase = RecordingPhase.PAUSED, level = 0f) else it }
    }

    fun resume() {
        capture?.paused = false
        state.update { if (it.phase == RecordingPhase.PAUSED) it.copy(phase = RecordingPhase.RECORDING) else it }
    }

    fun stop() {
        ended.complete(Unit)
    }

    fun onProjectionStopped() {
        // BOTH keeps the mic; the system side turns to silence and CAPTURE_SILENT says so.
        if (source == AudioSource.SYSTEM) stop()
    }

    /** Closes the WAV now (blocking), for a service that is being destroyed. */
    fun abort() {
        capture?.stop()
    }

    /** Returns the saved transcript, or null when nothing was saved (the state says why). */
    suspend fun run(): TranscriptEntry? = try {
        record()
    } finally {
        projection?.stop()
    }

    private suspend fun record(): TranscriptEntry? {
        val settings = app.settings.settings.value
        val (model, live, opened) = try {
            withContext(Dispatchers.IO) {
                val model = context.readyModel(force)
                val liveModel = model.spec?.live == true
                if (!liveModel) baseWarning = Problem.MODEL_NOT_LIVE
                val live = if (liveModel && liveAllowed) Channel<FloatArray>(LIVE_QUEUE_CHUNKS) else null
                Triple(model, live, openCapture(live))
            }
        } catch (f: SessionFailure) {
            state.value = RecordingState(source = source, error = f.problem, errorDetail = f.detail)
            return null
        }
        val (file, capture) = opened
        this.capture = capture
        state.value = RecordingState(phase = RecordingPhase.RECORDING, source = source, warning = baseWarning)
        try {
            return coroutineScope {
                val liveJob = live?.let { async { runLive(model, it, settings) } }
                ended.await()
                val stoppedAt = SystemClock.elapsedRealtime()
                state.update { it.copy(phase = RecordingPhase.FINISHING, level = 0f) }
                withContext(Dispatchers.IO) { capture.stop() }
                val liveComplete = liveJob != null &&
                    (withTimeoutOrNull(LIVE_FINISH_TIMEOUT_MS) { liveJob.await() } ?: false.also { liveJob.cancel() }) &&
                    !capture.liveDropped
                // Timings of the FINISHING phase, to tell a slow finish from a capture that stopped early.
                Log.i(TAG, "stopped: ${capture.samples / SAMPLE_RATE}s audio, live complete=$liveComplete after ${SystemClock.elapsedRealtime() - stoppedAt}ms")
                finish(model, file, capture.samples, liveComplete, settings)
                    .also { Log.i(TAG, "saved ${SystemClock.elapsedRealtime() - stoppedAt}ms after stop") }
            }
        } finally {
            capture.stop()
        }
    }

    /** The recording's new WAV and the capture writing it. */
    @SuppressLint("MissingPermission") // checked first
    private fun openCapture(live: Channel<FloatArray>?): Pair<File, AudioCapture> {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SessionFailure(Problem.MIC_PERMISSION)
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val size = maxOf(
            RECORD_BUFFER_BYTES,
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
        )
        val records = mutableListOf<AudioRecord>()
        fun build(builder: AudioRecord.Builder, denied: Problem): AudioRecord {
            val record = try {
                builder.setAudioFormat(format).setBufferSizeInBytes(size).build()
            } catch (e: UnsupportedOperationException) {
                throw SessionFailure(Problem.ENGINE_FAILED, "the audio input could not be opened: ${e.message}")
            } catch (e: SecurityException) {
                throw SessionFailure(denied, e.message)
            }
            records += record
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw SessionFailure(Problem.ENGINE_FAILED, "the audio input could not be opened")
            }
            return record
        }
        try {
            // VOICE_RECOGNITION is the source the CDD tunes for speech recognizers: no AGC, no noise
            // suppression, flat response. MIC may get the phone's call-style processing, which pumps
            // levels and smears the quiet parts ASR models need.
            val mic = if (source == AudioSource.SYSTEM) null else build(
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION),
                Problem.MIC_PERMISSION,
            )
            val system = if (source == AudioSource.MIC) null else build(
                AudioRecord.Builder().setAudioPlaybackCaptureConfig(
                    AudioPlaybackCaptureConfiguration.Builder(checkNotNull(projection))
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build(),
                ),
                Problem.PROJECTION_DENIED,
            )
            var file: File? = null
            val wav = try {
                WavWriter(newRecordingFile(context, startedAt).also { file = it })
            } catch (e: IOException) {
                file?.delete()
                throw e.toFailure(Problem.ENGINE_FAILED)
            }
            mic?.let(::watchMic)
            return checkNotNull(file) to AudioCapture(
                mic = mic,
                system = system,
                wav = wav,
                live = live,
                onChunk = { samples, level ->
                    state.update { it.copy(elapsedMs = samples * 1000 / SAMPLE_RATE, level = level) }
                },
                onSystemSilence = { silent ->
                    systemSilent = silent
                    publishWarning()
                },
                onEnd = { error ->
                    unwatchMic()
                    if (error != null) {
                        Log.e(TAG, "the recording stopped on its own", error)
                        captureError = error
                    }
                    ended.complete(Unit)
                },
            )
        } catch (e: Exception) {
            records.forEach { it.release() }
            throw e
        }
    }

    /**
     * Android gives the microphone to one app at a time; when another app takes it (the camera
     * recording video, a call) this session is "silenced": it keeps running but reads zeros. There is
     * no way around that policy, so say so while it lasts and carry on when the mic comes back.
     */
    private fun watchMic(record: AudioRecord) {
        val audio = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId } ?: return
                if (mine.isClientSilenced != micTaken) {
                    micTaken = mine.isClientSilenced
                    Log.i(TAG, "microphone ${if (micTaken) "taken by another app" else "back"}")
                    publishWarning()
                }
            }
        }
        audio.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        micWatch = callback
    }

    private fun unwatchMic() {
        micWatch?.let { context.getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(it) }
        micWatch = null
    }

    private fun publishWarning() {
        val warning = when {
            micTaken -> Problem.MIC_IN_USE
            systemSilent -> Problem.CAPTURE_SILENT
            else -> baseWarning
        }
        state.update { if (it.phase == RecordingPhase.RECORDING || it.phase == RecordingPhase.PAUSED) it.copy(warning = warning) else it }
    }

    /** Live inference, off the capture thread. Returns whether its finals cover all the audio; never throws but to cancel. */
    private suspend fun runLive(model: ReadyModel, chunks: Channel<FloatArray>, settings: AppSettings): Boolean {
        val session = try {
            app.engine.startLive(model.file, model.vad, settings.language, settings.livePartials)
        } catch (e: CancellationException) {
            chunks.cancel()
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "live transcription did not start", e)
            chunks.cancel()
            return false
        }
        var complete = true
        var finished = false
        try {
            while (true) {
                val first = chunks.receiveCatching().getOrNull() ?: break
                session.push(first)
                while (true) session.push(chunks.tryReceive().getOrNull() ?: break)
                val began = SystemClock.elapsedRealtime()
                complete = apply(session.advance()) && complete
                delay(ADVANCE_EVERY_MS - (SystemClock.elapsedRealtime() - began))
            }
            finished = true
            return apply(session.finish()) && complete
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "live transcription failed; the recording will be transcribed after it stops", e)
            chunks.cancel()
            return false
        } finally {
            if (!finished) session.cancel()
        }
    }

    /** Publishes [events]; false when the engine warned (it skipped or failed on some audio). */
    private fun apply(events: List<LiveEvent>): Boolean {
        if (events.isEmpty()) return true
        state.update { s ->
            var finals = s.finals
            var partial = s.partial
            for (event in events) when (event) {
                is LiveEvent.Final -> {
                    finals = finals + Segment(centis(event.start), centis(event.end), event.text)
                    partial = null
                }
                is LiveEvent.Partial -> partial = event.text.ifEmpty { null }
                is LiveEvent.Warning -> Unit
            }
            s.copy(finals = finals, partial = partial)
        }
        val warnings = events.filterIsInstance<LiveEvent.Warning>()
        warnings.forEach { Log.w(TAG, "live: ${it.code}: ${it.message}") }
        return warnings.isEmpty()
    }

    private suspend fun finish(
        model: ReadyModel,
        file: File,
        samples: Long,
        liveComplete: Boolean,
        settings: AppSettings,
    ): TranscriptEntry? {
        val endedAt = System.currentTimeMillis()
        val captureError = captureError
        val captureProblem = captureError?.let { if (it.isNoSpace()) Problem.NO_SPACE else Problem.CAPTURE_FAILED }
        if (samples == 0L) {
            file.delete()
            state.value = RecordingState(source = source, error = captureProblem, errorDetail = captureError?.message)
            return null
        }
        var segments = state.value.finals
        var problem: Problem? = null
        var detail: String? = null
        if (!liveComplete) {
            try {
                segments = transcribeWav(
                    app.engine,
                    model,
                    file,
                    settings.language,
                    onProgress = { p -> state.update { it.copy(progress = p) } },
                    onSegment = {},
                )
            } catch (f: SessionFailure) {
                problem = f.problem
                detail = f.detail
            }
        }
        // Without a full transcript, or of a recording cut short, the audio is kept whatever keepAudio says.
        val keepAudio = settings.keepAudio || problem != null || captureProblem != null
        // Named after its calendar event here, so it is saved without the app's name and the AI title passes it by.
        val meeting = if (settings.calendarNames) {
            withContext(Dispatchers.IO) { context.meetingAt(startedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), endedAt) }
        } else null
        val record = TranscriptRecord(
            name = meeting?.let { TranscriptStore.toFileStem(it.title) } ?: recordingName(startedAt),
            sourcePath = file.path,
            createdAt = isoNow(),
            language = settings.language,
            modelPath = model.file.path,
            segments = segments,
            meeting = meeting,
        )
        val entry = try {
            app.store.save(record, file.takeIf { keepAudio })
        } catch (e: IOException) {
            // The WAV stays in recordings-in-progress and comes back on the next start.
            Log.e(TAG, "could not save the recording", e)
            val failure = e.toFailure(Problem.ENGINE_FAILED)
            state.value = RecordingState(source = source, finals = segments, error = failure.problem, errorDetail = failure.detail)
            return null
        }
        if (!keepAudio) file.delete()
        state.value = RecordingState(
            source = source,
            elapsedMs = samples * 1000 / SAMPLE_RATE,
            finals = segments,
            savedId = entry.id,
            error = problem,
            errorDetail = detail ?: captureError?.message,
            warning = captureProblem,
        )
        return entry
    }

    private fun centis(seconds: Double) = (seconds * 100).roundToLong()
}
