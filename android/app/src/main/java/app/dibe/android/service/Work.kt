package app.dibe.android.service

import android.app.ActivityManager
import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import app.dibe.android.DibeApp
import app.dibe.android.app
import app.dibe.android.models.ModelSpec
import app.dibe.android.ui.settings.isReady
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val TAG = "DibeWork"

/** Room a loaded model takes beyond its file (KV cache, work buffers). */
private const val MODEL_RAM_FACTOR = 1.6

/** A pipeline step failed in a way the UI explains. */
internal class SessionFailure(val problem: Problem, val detail: String? = null) : Exception(detail ?: problem.name)

internal class ReadyModel(val spec: ModelSpec?, val file: File, val vad: File)

/** The active model, checked before any work so a missing model or a phone short of RAM fails fast. */
internal fun Context.readyModel(force: Boolean): ReadyModel {
    val models = app.models
    val id = models.activeModelId.value ?: throw SessionFailure(Problem.NO_MODEL)
    val file = models.modelFile(id)?.takeIf { it.isFile } ?: throw SessionFailure(Problem.NO_MODEL)
    val vad = models.vadFile()?.takeIf { it.isFile } ?: throw SessionFailure(Problem.NO_MODEL, "the VAD model is missing")
    val loaded = app.engine.loadedModel.value?.let { File(it).absolutePath == file.absolutePath } == true
    if (!force && !loaded) {
        val info = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val needed = (file.length() * MODEL_RAM_FACTOR).toLong()
        if (info.lowMemory || info.availMem < needed) {
            throw SessionFailure(Problem.LOW_MEMORY, "${info.availMem / MB} MB free, the model needs about ${needed / MB} MB")
        }
    }
    return ReadyModel(models.catalog.firstOrNull { it.id == id }, file, vad)
}

private const val MB = 1024 * 1024

/** Engine calls throw [RuntimeException] with the native error chain as the message. */
internal suspend fun <T> engineCall(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: OutOfMemoryError) {
    throw SessionFailure(Problem.LOW_MEMORY, e.message)
} catch (e: RuntimeException) {
    throw SessionFailure(Problem.ENGINE_FAILED, e.message)
}

internal fun Throwable.isNoSpace(): Boolean =
    generateSequence(this) { it.cause }.any { it is ErrnoException && it.errno == OsConstants.ENOSPC } ||
        message?.contains("ENOSPC") == true

internal fun IOException.toFailure(otherwise: Problem) = SessionFailure(if (isNoSpace()) Problem.NO_SPACE else otherwise, message)

internal const val IN_PROGRESS_DIR = "recordings-in-progress"
private const val FILE_STAMP_PATTERN = "yyyyMMdd-HHmmss"
private val FILE_STAMP = DateTimeFormatter.ofPattern(FILE_STAMP_PATTERN, Locale.ROOT)
private val NAME_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss", Locale.ROOT)

/** Created here, never reused: two recordings started in the same second get `-1`, `-2`, ... Throws [IOException]. */
internal fun newRecordingFile(context: Context, at: LocalDateTime): File {
    val dir = File(context.filesDir, IN_PROGRESS_DIR).apply { mkdirs() }
    val stamp = FILE_STAMP.format(at)
    return generateSequence(0) { it + 1 }
        .map { n -> dir.resolve(if (n == 0) "$stamp.wav" else "$stamp-$n.wav") }
        .first { it.createNewFile() }
}

internal fun leftoverRecordings(context: Context): List<File> =
    File(context.filesDir, IN_PROGRESS_DIR).listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()

internal fun recordingStartedAt(file: File): LocalDateTime? = try {
    LocalDateTime.parse(file.nameWithoutExtension.take(FILE_STAMP_PATTERN.length), FILE_STAMP)
} catch (e: java.time.format.DateTimeParseException) {
    null
}

/** The desktop's default names: `autoProjectName(..., 'record')` over its local-time stem. */
internal fun recordingName(at: LocalDateTime) = "Record-${NAME_STAMP.format(at)}"

internal fun fileProjectName(displayName: String) = "File-${displayName.substringBeforeLast('.').ifBlank { displayName }}"

internal fun isoNow(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

/** Never fails the work it follows: a summary is a bonus on top of a saved transcript. */
internal suspend fun DibeApp.autoSummarize(id: String) {
    val ai = settings.settings.value.ai
    // The settings screen's rule, so a keyless local OpenAI-compatible server summarizes too.
    if (!ai.autoSummarize || !ai.isReady()) return
    try {
        val record = store.read(id) ?: return
        val language = resources.configuration.locales[0].getDisplayLanguage(Locale.ENGLISH)
        val summary = this.ai.summarize(record, ai, language)
        store.update(id) { it.copy(summary = summary) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "auto summary of $id failed", e)
    }
}
