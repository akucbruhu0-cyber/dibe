package app.dibe.android.models

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

internal const val MODELS_DIR = "models"

private const val TAG = "DibeModels"
/** Headroom left on the volume after a model lands, for recordings and transcripts. */
private const val SPACE_MARGIN_BYTES = 200L * 1024 * 1024
private const val POLL_MS = 500L
private const val KEY_ACTIVE = "active"
private const val KEY_DOWNLOAD = "download."

/**
 * Built-in ranked catalog, downloads through the system DownloadManager (resumes across network
 * loss, shows its own notification), verification (size, GGML/GGUF magic, sha256 when known),
 * and the active-model choice. The VAD model is fetched with the first model.
 *
 * Download ids live in SharedPreferences, so a download that finished or failed while the process
 * was dead is picked up on the next start. Deleting the loaded model does not unload the engine:
 * the caller unloads it first.
 *
 * DownloadManager can only write to external storage, where on Android 10 another app with legacy
 * storage could swap a file. So a download is copied into internal storage, hashed there, and only
 * that copy is ever served; a model left in the external dir by an older build is moved the same way.
 */
class ModelRepository(private val context: Context, private val device: DeviceInfo) {
    /** Ordered light to heavy. */
    val catalog: List<ModelSpec> get() = CATALOG

    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)

    val statuses: StateFlow<Map<String, ModelStatus>>
        field = MutableStateFlow<Map<String, ModelStatus>>(CATALOG.associate { it.id to ModelStatus.NotInstalled })

    val activeModelId: StateFlow<String?>
        field = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))

    private val downloads = context.getSystemService(DownloadManager::class.java)
    /** DownloadManager's landing dir: parts, and finished models of older builds. */
    private val dir: File? by lazy { context.getExternalFilesDir(MODELS_DIR) }
    private val installDir = File(context.filesDir, MODELS_DIR)
    // Process-wide: downloads outlive every screen; nothing cancels it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards the download records, [verifying], [poller] and status transitions. */
    private val lock = Any()
    private val verifying = mutableSetOf<String>()
    private var poller: Job? = null
    @Volatile private var vadStatus: ModelStatus = ModelStatus.NotInstalled

    init {
        val present = mutableListOf<ModelSpec>()
        synchronized(lock) {
            // A copy the process died in the middle of; nothing stages before init ends.
            installDir.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
            val known = (CATALOG + VAD_SPEC).map { key(it) }.toSet()
            prefs.all.keys.filter { it.startsWith(KEY_DOWNLOAD) && it !in known }.forEach { stale ->
                removeDownload(prefs.getLong(stale, -1))
                prefs.edit { remove(stale) }
            }
            for (spec in CATALOG + VAD_SPEC) {
                val download = downloadId(spec)
                when {
                    finalFile(spec).exists() || legacyFile(spec)?.exists() == true -> {
                        // The process died between publishing the file and forgetting its download.
                        if (download != null) drop(spec, download, ModelStatus.Verifying)
                        setStatus(spec, ModelStatus.Verifying)
                        present += spec
                    }
                    download != null -> setStatus(spec, ModelStatus.Waiting(0))
                }
            }
        }
        scope.launch { checkInstalled(present) }
        startPolling()
    }

    /** Fails fast with [ModelProblem.NO_SPACE] when free storage is short. */
    fun download(id: String) {
        val spec = catalogSpec(id)
        enqueue(spec)
        if (statusOf(spec) !is ModelStatus.Failed) enqueue(VAD_SPEC)
    }

    /** Also dismisses a failed download. */
    fun cancel(id: String) {
        val spec = catalogSpec(id)
        synchronized(lock) {
            val download = downloadId(spec)
            when {
                download != null -> drop(spec, download, ModelStatus.NotInstalled)
                statusOf(spec) is ModelStatus.Failed -> setStatus(spec, ModelStatus.NotInstalled)
            }
        }
    }

    fun delete(id: String) {
        val spec = catalogSpec(id)
        synchronized(lock) {
            downloadId(spec)?.let { drop(spec, it, ModelStatus.NotInstalled) }
            finalFile(spec).delete()
            legacyFile(spec)?.delete()
            setStatus(spec, ModelStatus.NotInstalled)
            if (activeModelId.value == id) activate(null)
        }
    }

    /** Ignored unless [id] is installed. */
    fun setActive(id: String) {
        val spec = catalogSpec(id)
        synchronized(lock) { if (statusOf(spec) == ModelStatus.Installed) activate(id) }
    }

    fun fit(spec: ModelSpec): Fit = fitFor(device.totalRamBytes, spec.minRamGb)

    /** Catalog entry recommended for this phone and UI language. */
    fun recommended(): ModelSpec = recommend(isHebrew(context.resources.configuration.locales[0].language), ::fit)

    /** Null unless installed and verified. */
    fun modelFile(id: String): File? =
        CATALOG.find { it.id == id }?.takeIf { statusOf(it) == ModelStatus.Installed }?.let(::finalFile)

    fun vadFile(): File? = finalFile(VAD_SPEC).takeIf { vadStatus == ModelStatus.Installed }

    /**
     * An internal file gets size and magic only (no other app can write there): a truncated file shows
     * as corrupt instead of crashing the engine. One left in the external dir is hashed once, on its way in.
     */
    private fun checkInstalled(present: List<ModelSpec>) {
        for (spec in present) {
            val file = finalFile(spec)
            val legacy = legacyFile(spec)?.takeIf { it.exists() }
            val problem = when {
                file.exists() -> if (verifyModelFile(file, spec.sizeBytes, sha256 = null)) null else ModelProblem.CORRUPT
                legacy != null -> stage(spec, legacy)
                else -> ModelProblem.UNKNOWN // deleted meanwhile; the status check below drops it
            }
            synchronized(lock) {
                // A copy that failed for space or I/O keeps its source for the next start.
                if (problem == null || problem == ModelProblem.CORRUPT) legacy?.delete()
                if (statusOf(spec) != ModelStatus.Verifying) { // deleted meanwhile
                    stagedFile(spec).delete()
                    return@synchronized
                }
                val installed = problem == null && (file.exists() || commit(spec))
                if (!installed) {
                    Log.w(TAG, "${spec.fileName} is not usable (${problem ?: "could not move into place"}), deleting it")
                    file.delete()
                }
                setStatus(spec, if (installed) ModelStatus.Installed else ModelStatus.Failed(problem ?: ModelProblem.UNKNOWN))
            }
        }
        synchronized(lock) {
            val active = activeModelId.value
            if (active != null && modelFile(active) == null) activate(null)
        }
        if (CATALOG.any { statusOf(it) == ModelStatus.Installed }) enqueue(VAD_SPEC)
    }

    private fun enqueue(spec: ModelSpec) {
        synchronized(lock) {
            val status = statusOf(spec)
            if (status != ModelStatus.NotInstalled && status !is ModelStatus.Failed) return
            val dir = dir ?: return fail(spec, ModelProblem.UNKNOWN, "no external files dir")
            if (device.freeStorageBytes() < spaceNeeded(spec)) {
                return setStatus(spec, ModelStatus.Failed(ModelProblem.NO_SPACE))
            }
            // DownloadManager writes "<name>-1.part" beside a stale part instead of replacing it.
            File(dir, partName(spec)).delete()
            val download = try {
                downloads.enqueue(
                    DownloadManager.Request(Uri.parse(spec.url))
                        .setTitle(spec.name)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(true)
                        .setDestinationInExternalFilesDir(context, MODELS_DIR, partName(spec)),
                )
            } catch (e: IllegalArgumentException) { // the user disabled the Download Manager app
                return fail(spec, ModelProblem.UNKNOWN, "enqueue failed: $e")
            } catch (e: IllegalStateException) { // external storage unavailable
                return fail(spec, ModelProblem.UNKNOWN, "enqueue failed: $e")
            } catch (e: SecurityException) {
                return fail(spec, ModelProblem.UNKNOWN, "enqueue failed: $e")
            }
            prefs.edit { putLong(key(spec), download) }
            setStatus(spec, ModelStatus.Waiting(0))
        }
        startPolling()
    }

    /** Runs only while a download is pending; [enqueue] restarts it. */
    private fun startPolling() {
        synchronized(lock) {
            if (poller != null) return
            poller = scope.launch {
                while (true) {
                    val pending = synchronized(lock) { pending().also { if (it.isEmpty()) poller = null } }
                    if (pending.isEmpty()) return@launch
                    poll(pending)
                    delay(POLL_MS)
                }
            }
        }
    }

    private fun poll(pending: List<Pair<ModelSpec, Long>>) {
        val rows = query(pending.map { it.second }) ?: return
        synchronized(lock) {
            for ((spec, download) in pending) {
                if (downloadId(spec) != download) continue // cancelled since pending() was read
                val row = rows[download]
                when (row?.status) {
                    null -> drop(spec, download, ModelStatus.NotInstalled) // cancelled from the notification
                    DownloadManager.STATUS_SUCCESSFUL -> verify(spec, download, row.file)
                    DownloadManager.STATUS_FAILED -> {
                        Log.w(TAG, "${spec.fileName} download failed, reason ${row.reason}")
                        drop(spec, download, ModelStatus.Failed(problemFor(row.reason)))
                    }
                    DownloadManager.STATUS_RUNNING -> {
                        val total = row.total.takeIf { it > 0 } ?: spec.sizeBytes
                        val progress = (row.downloaded.toFloat() / total).coerceIn(0f, 1f)
                        setStatus(spec, ModelStatus.Downloading(progress, row.downloaded))
                    }
                    else -> setStatus(spec, ModelStatus.Waiting(row.downloaded)) // pending or paused
                }
            }
        }
    }

    /** Under [lock]. The hash of a large model takes seconds, so it runs off the poll loop. */
    private fun verify(spec: ModelSpec, download: Long, file: File?) {
        val part = file ?: dir?.let { File(it, partName(spec)) }
            ?: return drop(spec, download, ModelStatus.Failed(ModelProblem.UNKNOWN))
        if (!verifying.add(spec.id)) return
        setStatus(spec, ModelStatus.Verifying)
        scope.launch {
            try {
                publish(spec, download, part)
            } finally {
                synchronized(lock) { verifying.remove(spec.id) }
            }
        }
    }

    private fun publish(spec: ModelSpec, download: Long, part: File) {
        val problem = stage(spec, part)
        synchronized(lock) {
            part.delete()
            if (downloadId(spec) != download) { // cancelled while copying or hashing
                stagedFile(spec).delete()
                return
            }
            val published = problem == null && commit(spec)
            if (problem == null && !published) Log.w(TAG, "${spec.fileName}: could not move into place")
            drop(spec, download, if (published) ModelStatus.Installed else ModelStatus.Failed(problem ?: ModelProblem.UNKNOWN))
            if (published && spec !== VAD_SPEC && activeModelId.value == null) activate(spec.id)
        }
    }

    /**
     * Copies [source] to [stagedFile] and verifies the copy, so the bytes hashed are the bytes served.
     * Off [lock]: copying and hashing a large model take seconds. Null when the staged file is good.
     */
    private fun stage(spec: ModelSpec, source: File): ModelProblem? {
        val staged = stagedFile(spec)
        val problem = if (!verifyModelFile(source, spec.sizeBytes, sha256 = null)) {
            ModelProblem.CORRUPT
        } else {
            try {
                installDir.mkdirs()
                source.copyTo(staged, overwrite = true)
                if (verifyModelFile(staged, spec.sizeBytes, spec.sha256)) null else ModelProblem.CORRUPT
            } catch (e: IOException) {
                Log.w(TAG, "${spec.fileName}: could not copy into internal storage", e)
                staged.delete()
                if (device.freeStorageBytes() < spec.sizeBytes) ModelProblem.NO_SPACE else ModelProblem.UNKNOWN
            }
        }
        if (problem != null) {
            if (problem == ModelProblem.CORRUPT) Log.w(TAG, "${spec.fileName} failed verification")
            staged.delete()
        }
        return problem
    }

    /** Moves a good [stagedFile] into place; same directory, so the rename is atomic. Under [lock]. */
    private fun commit(spec: ModelSpec): Boolean {
        val staged = stagedFile(spec)
        val destination = finalFile(spec)
        val moved = (!destination.exists() || destination.delete()) && staged.renameTo(destination)
        if (!moved) staged.delete()
        return moved
    }

    private class Row(val status: Int, val reason: Int, val downloaded: Long, val total: Long, val file: File?)

    /** Null when the provider cannot answer right now; the next tick asks again. */
    private fun query(ids: List<Long>): Map<Long, Row>? = try {
        downloads.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val status = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val reason = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val downloaded = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val total = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val uri = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            buildMap {
                while (cursor.moveToNext()) {
                    val file = cursor.getString(uri)?.let { Uri.parse(it).path }?.let(::File)
                    put(
                        cursor.getLong(id),
                        Row(cursor.getInt(status), cursor.getInt(reason), cursor.getLong(downloaded), cursor.getLong(total), file),
                    )
                }
            }
        }
    } catch (e: RuntimeException) { // not a suspend call, so no CancellationException passes here
        Log.w(TAG, "DownloadManager query failed", e)
        null
    }

    private fun pending(): List<Pair<ModelSpec, Long>> =
        (CATALOG + VAD_SPEC).filter { it.id !in verifying }.mapNotNull { spec -> downloadId(spec)?.let { spec to it } }

    /** Forgets the download (DownloadManager deletes its partial file) and settles on [status]. Under [lock]. */
    private fun drop(spec: ModelSpec, download: Long, status: ModelStatus) {
        removeDownload(download)
        prefs.edit { remove(key(spec)) }
        dir?.let { File(it, partName(spec)).delete() }
        setStatus(spec, status)
    }

    private fun removeDownload(download: Long) {
        if (download < 0) return
        try {
            downloads.remove(download)
        } catch (e: RuntimeException) {
            Log.w(TAG, "DownloadManager remove failed", e)
        }
    }

    private fun fail(spec: ModelSpec, problem: ModelProblem, why: String) {
        Log.w(TAG, "${spec.fileName}: $why")
        setStatus(spec, ModelStatus.Failed(problem))
    }

    private fun activate(id: String?) {
        activeModelId.value = id
        prefs.edit { if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id) }
    }

    private fun statusOf(spec: ModelSpec): ModelStatus =
        if (spec === VAD_SPEC) vadStatus else statuses.value[spec.id] ?: ModelStatus.NotInstalled

    private fun setStatus(spec: ModelSpec, status: ModelStatus) {
        if (spec === VAD_SPEC) vadStatus = status else statuses.update { it + (spec.id to status) }
    }

    private fun downloadId(spec: ModelSpec): Long? = prefs.getLong(key(spec), -1).takeIf { it >= 0 }
    private fun key(spec: ModelSpec) = KEY_DOWNLOAD + spec.id
    private fun partName(spec: ModelSpec) = spec.fileName + ".part"
    private fun finalFile(spec: ModelSpec) = File(installDir, spec.fileName)
    private fun stagedFile(spec: ModelSpec) = File(installDir, spec.fileName + ".tmp")
    /** Where builds before internal storage kept the model. */
    private fun legacyFile(spec: ModelSpec): File? = dir?.let { File(it, spec.fileName) }
}

/**
 * The download and its internal copy exist side by side for a moment (on most phones on the same
 * partition), plus headroom for recordings and transcripts once the download is gone.
 */
internal fun spaceNeeded(spec: ModelSpec): Long = 2 * spec.sizeBytes + SPACE_MARGIN_BYTES

internal fun problemFor(reason: Int): ModelProblem = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> ModelProblem.NO_SPACE
    DownloadManager.ERROR_HTTP_DATA_ERROR,
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
    DownloadManager.ERROR_TOO_MANY_REDIRECTS,
    DownloadManager.ERROR_CANNOT_RESUME,
    in 100..599, // an HTTP status
    -> ModelProblem.NETWORK
    else -> ModelProblem.UNKNOWN
}
