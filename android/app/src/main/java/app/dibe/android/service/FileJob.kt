package app.dibe.android.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.dibe.android.app
import app.dibe.android.data.TranscriptEntry
import app.dibe.android.data.TranscriptRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Copies of the files being transcribed; whatever is here when no job runs is a dead job's. */
internal const val FILE_JOBS_DIR = "file-jobs"

/**
 * One shared or picked file: a private copy first (the grant to read [uri] may not outlive the
 * UI), then decode it to a 16 kHz WAV beside it, transcribe that, and save the copy with the transcript.
 */
internal class FileJob(
    private val context: Context,
    private val uri: Uri,
    private val force: Boolean,
    /** Fill this saved transcript (its own audio is [uri]) instead of saving a new one. */
    private val targetId: String? = null,
) {
    private val app = context.app
    private val state = app.sessions.fileState

    suspend fun run(): TranscriptEntry? {
        var copy: File? = null
        val wav = File(context.filesDir, FILE_JOBS_DIR).resolve("decoded.wav")
        try {
            val model = withContext(Dispatchers.IO) { context.readyModel(force) }
            val settings = app.settings.settings.value
            // Asked here, off the main thread: a cloud provider may take seconds to answer.
            val name = withContext(Dispatchers.IO) { displayName() } ?: state.value?.name ?: uri.lastPathSegment ?: "audio"
            state.update { it?.copy(name = name) }
            copy = copySource(name)
            decodeToWav(copy, wav) { p -> state.update { it?.copy(progress = p) } }
            state.update { it?.copy(phase = FilePhase.TRANSCRIBING, progress = 0) }
            val segments = transcribeWav(
                app.engine,
                model,
                wav,
                settings.language,
                onProgress = { p -> state.update { it?.copy(progress = p) } },
                onSegment = { s -> state.update { it?.copy(segments = it.segments + s) } },
            )
            if (targetId != null) {
                val updated = try {
                    app.store.update(targetId) { it.copy(segments = segments, language = settings.language, modelPath = model.file.path) }
                } catch (e: IOException) {
                    throw e.toFailure(Problem.ENGINE_FAILED)
                }
                state.update { it?.copy(phase = FilePhase.DONE, progress = 100, segments = segments, savedId = targetId) }
                return app.store.entries.value.firstOrNull { it.id == targetId } ?: TranscriptEntry(targetId, updated.name, System.currentTimeMillis())
            }
            val record = TranscriptRecord(
                name = fileProjectName(name),
                sourcePath = uri.toString(),
                createdAt = isoNow(),
                language = settings.language,
                modelPath = model.file.path,
                segments = segments,
            )
            val entry = try {
                app.store.save(record, copy.takeIf { settings.keepAudio })
            } catch (e: IOException) {
                throw e.toFailure(Problem.ENGINE_FAILED)
            }
            if (settings.keepAudio) copy = null
            state.update { it?.copy(phase = FilePhase.DONE, progress = 100, segments = segments, savedId = entry.id) }
            return entry
        } catch (f: SessionFailure) {
            state.update { it?.copy(phase = FilePhase.FAILED, error = f.problem, errorDetail = f.detail) }
            return null
        } catch (e: CancellationException) {
            state.update { it?.copy(phase = FilePhase.CANCELLED) }
            throw e
        } finally {
            copy?.delete()
            wav.delete()
        }
    }

    private fun displayName(): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private suspend fun copySource(name: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, FILE_JOBS_DIR).apply { mkdirs() }
        val target = File(dir, "source.${extension(name)}")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw SessionFailure(Problem.CORRUPT_FILE, "the file could not be opened")
            input.use { src ->
                target.outputStream().use { dst ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = src.read(buf)
                        if (n < 0) break
                        dst.write(buf, 0, n)
                    }
                }
            }
            target
        } catch (e: SecurityException) {
            target.delete()
            throw SessionFailure(Problem.CORRUPT_FILE, "no permission to read the file anymore")
        } catch (e: FileNotFoundException) {
            target.delete()
            throw SessionFailure(Problem.CORRUPT_FILE, e.message)
        } catch (e: IOException) {
            target.delete()
            throw e.toFailure(Problem.CORRUPT_FILE)
        } catch (e: CancellationException) {
            target.delete()
            throw e
        }
    }

    /** The copy keeps a real extension: the saved project's `audio.<ext>` must stay playable. */
    private fun extension(name: String): String {
        val fromName = name.substringAfterLast('.', "").lowercase()
        if (fromName.length in 1..5 && fromName.all { it.isLetterOrDigit() }) return fromName
        return context.contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"
    }
}
