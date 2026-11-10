package app.dibe.android.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The UI's handle on background work. Every call is forwarded to [DibeService] (a foreground
 * service), which owns the audio pipeline and the engine work and publishes into these flows,
 * so a recording or a file job survives the UI going away, the screen turning off, or another
 * app taking the foreground.
 */
class Sessions(private val context: Context) {
    internal val recordingState = MutableStateFlow(RecordingState())
    internal val fileState = MutableStateFlow<FileJobState?>(null)
    val recording: StateFlow<RecordingState> = recordingState.asStateFlow()
    val fileJob: StateFlow<FileJobState?> = fileState.asStateFlow()

    init {
        // The service's start saves what a killed recording left behind; this is the app-start trigger.
        if (leftoverRecordings(context).isNotEmpty()) send(DibeService.ACTION_RECOVER)
    }

    /**
     * [projectionData] is the MediaProjection consent result, required for SYSTEM and BOTH.
     * The caller has RECORD_AUDIO already (and POST_NOTIFICATIONS was asked).
     * [force] skips the free-memory check (the UI's "try anyway" after LOW_MEMORY).
     */
    fun startRecording(source: AudioSource, projectionData: Intent?, force: Boolean = false) {
        val current = recordingState.value
        if (current.phase != RecordingPhase.IDLE) return
        val problem = when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                Problem.MIC_PERMISSION
            source != AudioSource.MIC && projectionData == null -> Problem.PROJECTION_DENIED
            else -> null
        }
        if (problem != null) {
            recordingState.value = RecordingState(source = source, error = problem)
            return
        }
        if (!recordingState.compareAndSet(current, RecordingState(phase = RecordingPhase.STARTING, source = source))) return
        val intent = DibeService.intent(context, DibeService.ACTION_RECORD)
            .putExtra(DibeService.EXTRA_SOURCE, source.name)
            .putExtra(DibeService.EXTRA_PROJECTION, projectionData)
            .putExtra(DibeService.EXTRA_FORCE, force)
        startForeground(intent)?.let { recordingState.value = RecordingState(source = source, error = Problem.ENGINE_FAILED, errorDetail = it) }
    }

    fun pause() {
        if (recordingState.value.phase == RecordingPhase.RECORDING) send(DibeService.ACTION_PAUSE)
    }

    fun resume() {
        if (recordingState.value.phase == RecordingPhase.PAUSED) send(DibeService.ACTION_RESUME)
    }

    fun stop() {
        if (recordingState.value.phase in setOf(RecordingPhase.STARTING, RecordingPhase.RECORDING, RecordingPhase.PAUSED)) {
            send(DibeService.ACTION_STOP)
        }
    }

    /**
     * Queue is one deep: a new file while one runs is refused (returns false). Also refused while
     * a recording runs: its live transcription holds the engine. [force] as in [startRecording].
     */
    fun transcribeFile(uri: Uri, force: Boolean = false, targetId: String? = null): Boolean {
        val current = fileState.value
        if (current != null && current.phase in ACTIVE_FILE_PHASES) return false
        if (recordingState.value.phase != RecordingPhase.IDLE) return false
        // A placeholder: FileJob asks the provider for the real name off the main thread.
        val job = FileJobState(uri.lastPathSegment ?: "audio", FilePhase.DECODING, targetId = targetId)
        if (!fileState.compareAndSet(current, job)) return false
        // No persistable grant: the intent's grant lasts while the service copies the file, which is all it needs.
        val intent = DibeService.intent(context, DibeService.ACTION_FILE)
            .putExtra(DibeService.EXTRA_URI, uri)
            .putExtra(DibeService.EXTRA_FORCE, force)
            .putExtra(DibeService.EXTRA_TARGET, targetId)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startForeground(intent)?.let { fileState.value = job.copy(phase = FilePhase.FAILED, error = Problem.ENGINE_FAILED, errorDetail = it) }
        return true
    }

    fun cancelFile() {
        if (fileState.value?.phase in ACTIVE_FILE_PHASES) send(DibeService.ACTION_CANCEL_FILE)
    }

    /** Clears a finished/failed file job and a recording result from the flows. */
    fun clearResults() {
        fileState.update { if (it == null || it.phase in ACTIVE_FILE_PHASES) it else null }
        recordingState.update { if (it.phase == RecordingPhase.IDLE) RecordingState(source = it.source) else it }
    }

    /** Returns the failure message, null when started. */
    private fun startForeground(intent: Intent): String? = try {
        ContextCompat.startForegroundService(context, intent)
        null
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException: called while the app is in the background.
        Log.e(TAG, "could not start the service", e)
        e.message ?: "the service could not start"
    }

    private fun send(action: String) {
        try {
            context.startService(DibeService.intent(context, action))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "could not reach the service for $action", e)
        }
    }

    private companion object {
        const val TAG = "Sessions"
    }
}
