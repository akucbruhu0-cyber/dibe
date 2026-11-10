package app.dibe.android.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.dibe.android.R
import app.dibe.android.app
import app.dibe.android.audio.repairWav
import app.dibe.android.data.TranscriptRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private const val TAG = "DibeService"
/** A cap the lock never reaches in practice; it is released as soon as the work ends. */
private const val WAKE_LOCK_MAX_MS = 24 * 60 * 60 * 1000L
private const val NOTIFY_EVERY_MS = 500L

/**
 * The foreground service behind [Sessions]: it runs the recording and the file job, keeps the CPU
 * awake while either runs, and stops itself when both are done. Everything is driven by intents,
 * from Sessions and from the notification's actions.
 */
class DibeService : LifecycleService() {
    private val sessions get() = app.sessions
    private lateinit var notifications: Notifications
    private lateinit var wakeLock: PowerManager.WakeLock
    private var recording: RecordingSession? = null
    private var fileJob: Job? = null
    /** Recovery and summaries: short work that keeps the service alive but has no state of its own. */
    private val chores = mutableSetOf<Job>()
    private var lastStartId = 0
    private var foreground = false
    /** Set by [onTimeout]: the cancelled file job reports why instead of vanishing as CANCELLED. */
    private var fileTimedOut = false

    override fun onCreate() {
        super.onCreate()
        notifications = Notifications(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dibe:work")
            .apply { setReferenceCounted(false) }
        // Listed before any new recording can create its file, so only a dead session's files are taken.
        val leftovers = leftoverRecordings(this)
        File(filesDir, FILE_JOBS_DIR).deleteRecursively()
        if (leftovers.isNotEmpty()) chore { recoverRecordings(leftovers) }
        lifecycleScope.launch {
            combine(sessions.recording, sessions.fileJob) { r, f ->
                listOf(r.phase, r.progress, r.warning, f?.name, f?.phase, f?.progress)
            }.distinctUntilChanged().conflate().collect {
                if (foreground) notifications.update(notifications.ongoing(sessions.recording.value, sessions.fileJob.value))
                delay(NOTIFY_EVERY_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        when (intent?.action) {
            ACTION_RECORD -> startRecording(intent)
            ACTION_FILE -> startFile(intent)
            ACTION_PAUSE -> recording?.pause()
            ACTION_RESUME -> recording?.resume()
            ACTION_STOP -> recording?.stop()
            ACTION_CANCEL_FILE -> fileJob?.cancel()
            // ACTION_RECOVER: onCreate already did it.
        }
        stopIfIdle()
        // A killed recording cannot resume (the projection token is single use); recovery saves its audio.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+: dataSync and mediaProcessing have a daily budget; past it the service must drop
        // that type within seconds, or the system crashes the app.
        Log.w(TAG, "foreground time limit reached for type $fgsType")
        fileJob?.let {
            fileTimedOut = true
            it.cancel()
        }
        fileJob = null
        chores.toList().forEach(Job::cancel)
        // A recording keeps only its own types; without one nothing may stay in the foreground.
        if (recording != null && promote()) return
        stop(startId = null)
    }

    override fun onDestroy() {
        recording?.abort()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        if (recording != null) {
            promote()
            return
        }
        val source = intent.getStringExtra(EXTRA_SOURCE)?.let(AudioSource::valueOf) ?: AudioSource.MIC
        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        val types = recordingTypes(source) or (if (fileJob != null) fileType() else 0)
        // Android 14+: the mediaProjection type must be in place before getMediaProjection.
        if (!promote(types, exact = true)) {
            sessions.recordingState.value = RecordingState(
                source = source,
                error = if (source == AudioSource.MIC) Problem.MIC_PERMISSION else Problem.PROJECTION_DENIED,
                errorDetail = "the foreground service could not start",
            )
            return
        }
        var session: RecordingSession? = null
        val projection = if (source == AudioSource.MIC) null else {
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_PROJECTION, Intent::class.java)
            val projection = data?.let { mediaProjection(it) }
            if (projection == null) {
                sessions.recordingState.value = RecordingState(source = source, error = Problem.PROJECTION_DENIED)
                return
            }
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        session?.onProjectionStopped()
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            projection
        }
        session = RecordingSession(this, source, projection, force, liveAllowed = fileJob == null)
        recording = session
        holdWakeLock()
        lifecycleScope.launch {
            try {
                session.run()?.let { entry ->
                    notifications.ready(entry)
                    chore { app.autoSummarize(entry.id) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "the recording failed", e)
                sessions.recordingState.value = RecordingState(source = source, error = Problem.ENGINE_FAILED, errorDetail = e.message)
            } finally {
                // Cancelled with the service: the WAV stays in progress and is recovered on the next start.
                sessions.recordingState.update { if (it.phase != RecordingPhase.IDLE) RecordingState(source = source) else it }
                recording = null
                stopIfIdle()
            }
        }
    }

    private fun mediaProjection(data: Intent): MediaProjection? = try {
        getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data)
    } catch (e: SecurityException) {
        Log.w(TAG, "the projection consent was not accepted", e)
        null
    } catch (e: IllegalStateException) {
        Log.w(TAG, "the projection consent was already used", e)
        null
    }

    private fun startFile(intent: Intent) {
        if (fileJob != null) {
            promote()
            return
        }
        val uri = IntentCompat.getParcelableExtra(intent, EXTRA_URI, Uri::class.java) ?: return
        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        fileTimedOut = false
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                FileJob(this@DibeService, uri, force, intent.getStringExtra(EXTRA_TARGET)).run()?.let { entry ->
                    notifications.ready(entry)
                    chore { app.autoSummarize(entry.id) }
                }
            } catch (e: CancellationException) {
                // Same main-thread turn as FileJob's CANCELLED, so no collector sees that one and clears the job.
                if (fileTimedOut) {
                    val detail = getString(R.string.service_file_time_limit)
                    sessions.fileState.update { it?.copy(phase = FilePhase.FAILED, error = Problem.ENGINE_FAILED, errorDetail = detail) }
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "the file job failed", e)
                sessions.fileState.update { it?.copy(phase = FilePhase.FAILED, error = Problem.ENGINE_FAILED, errorDetail = e.message) }
            } finally {
                if (fileJob == coroutineContext[Job]) fileJob = null
                stopIfIdle()
            }
        }
        fileJob = job
        if (!promote()) {
            fileJob = null
            sessions.fileState.update {
                it?.copy(phase = FilePhase.FAILED, error = Problem.ENGINE_FAILED, errorDetail = "the foreground service could not start")
            }
            return
        }
        holdWakeLock()
        job.start()
    }

    /** Saves recordings a dead process left behind as transcripts without text, audio kept. */
    private suspend fun recoverRecordings(files: List<File>) = withContext(Dispatchers.IO) {
        for (file in files) {
            try {
                if (repairWav(file) == 0L) {
                    Log.i(TAG, "${file.name} holds no audio; removed")
                    file.delete()
                    continue
                }
                val endedAt = Instant.ofEpochMilli(file.lastModified())
                val startedAt = recordingStartedAt(file) ?: LocalDateTime.ofInstant(endedAt, ZoneId.systemDefault())
                val record = TranscriptRecord(
                    name = recordingName(startedAt),
                    sourcePath = file.path,
                    createdAt = endedAt.toString(),
                    segments = emptyList(),
                )
                app.store.save(record, file)
                Log.i(TAG, "recovered ${file.name}")
            } catch (e: IOException) {
                Log.e(TAG, "could not recover ${file.name}; it stays for the next start", e)
            }
        }
    }

    private fun chore(block: suspend () -> Unit) {
        holdWakeLock()
        // Lazy: Main.immediate would otherwise run a block that never suspends to its end before it is tracked.
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "background chore failed", e)
            }
        }
        chores += job
        job.invokeOnCompletion {
            chores -= job
            stopIfIdle()
        }
        job.start()
    }

    private fun currentTypes(): Int {
        val recording = recording
        return when {
            recording != null -> recordingTypes(recording.source) or (if (fileJob != null) fileType() else 0)
            fileJob != null -> fileType()
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC // chores: recovery, summaries
        }
    }

    /** Android 15 gives media processing its own time budget, apart from dataSync's. */
    private fun fileType(): Int =
        if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    private fun recordingTypes(source: AudioSource): Int {
        // While-in-use rules mute the mic (and playback capture) of a background app without this type.
        val mic = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        return if (source == AudioSource.MIC) mic else mic or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    }

    /**
     * startForeground, also on every repeated start command: each startForegroundService needs one.
     * Falls back to dataSync alone, because a start command that never reaches startForeground
     * crashes the app.
     */
    /**
     * Puts the service in the foreground with [types], falling back to dataSync when the platform
     * refuses them (Android 17 refused mediaProcessing as "type none" on a Pixel 9 Pro). [exact]
     * callers (a recording) need their own types: without the microphone type a background mic is muted.
     */
    private fun promote(types: Int = currentTypes(), exact: Boolean = recording != null): Boolean {
        val notification = notifications.ongoing(sessions.recording.value, sessions.fileJob.value)
        for (type in listOf(types, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC).distinct()) {
            try {
                ServiceCompat.startForeground(this, ONGOING_ID, notification, type)
                foreground = true
                return !exact || type == types
            } catch (e: SecurityException) {
                Log.e(TAG, "startForeground($type) refused", e)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: the app went to the background first.
                Log.e(TAG, "startForeground($type) not allowed", e)
            }
        }
        return false
    }

    private fun holdWakeLock() = wakeLock.acquire(WAKE_LOCK_MAX_MS)

    private fun stopIfIdle() {
        if (recording != null || fileJob != null || chores.isNotEmpty()) return
        // Only if no start command arrived since: a newer one keeps the service.
        stop(lastStartId)
    }

    /** [startId] null stops the service whatever start commands came since. */
    private fun stop(startId: Int?) {
        if (wakeLock.isHeld) wakeLock.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    companion object {
        internal const val ACTION_RECORD = "app.dibe.android.action.RECORD"
        internal const val ACTION_PAUSE = "app.dibe.android.action.PAUSE"
        internal const val ACTION_RESUME = "app.dibe.android.action.RESUME"
        internal const val ACTION_STOP = "app.dibe.android.action.STOP"
        internal const val ACTION_FILE = "app.dibe.android.action.FILE"
        internal const val ACTION_CANCEL_FILE = "app.dibe.android.action.CANCEL_FILE"
        internal const val ACTION_RECOVER = "app.dibe.android.action.RECOVER"
        internal const val EXTRA_SOURCE = "source"
        internal const val EXTRA_PROJECTION = "projection"
        internal const val EXTRA_URI = "uri"
        internal const val EXTRA_FORCE = "force"
        internal const val EXTRA_TARGET = "target"

        internal fun intent(context: Context, action: String) = Intent(context, DibeService::class.java).setAction(action)
    }
}
