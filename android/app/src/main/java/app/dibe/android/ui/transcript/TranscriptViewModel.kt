package app.dibe.android.ui.transcript

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibe.android.DibeApp
import app.dibe.android.R
import app.dibe.android.ai.AiException
import app.dibe.android.ai.VisualPass
import app.dibe.android.data.AiThreadEntry
import app.dibe.android.data.TranscriptRecord
import app.dibe.android.export.ExportContent
import app.dibe.android.export.ExportFormat
import app.dibe.android.export.ExportOptions
import app.dibe.android.export.Exporter
import app.dibe.android.service.ACTIVE_FILE_PHASES
import app.dibe.android.service.FilePhase
import app.dibe.android.service.Sessions
import app.dibe.android.service.autoTitle
import app.dibe.android.ui.settings.aiErrorText
import app.dibe.android.ui.settings.isReady
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

data class TranscriptUiState(
    val loading: Boolean = true,
    val record: TranscriptRecord? = null,
    val player: AudioPlayer? = null,
    val aiReady: Boolean = false,
    val aiPreset: String = "summary",
    val summarizing: Boolean = false,
    /** The summary is shown; its charts are still being drawn. */
    val visualizing: Boolean = false,
    @StringRes val summaryError: Int? = null,
    /** The question whose answer is on its way. */
    val asking: String? = null,
    @StringRes val askError: Int? = null,
    val failedQuestion: String? = null,
    val exporting: Boolean = false,
    /** 0..100 while the project's own audio is being transcribed into it. */
    val transcribing: Int? = null,
) {
    val missing: Boolean get() = !loading && record == null
    val rtl: Boolean get() = record?.let(::transcriptIsRtl) ?: false
    val hasSpeakers: Boolean get() = record?.segments?.any { it.speaker != null } == true
}

sealed interface TranscriptEffect {
    data object Deleted : TranscriptEffect
    data class Message(@StringRes val text: Int) : TranscriptEffect
    data class Share(val uri: Uri, val mime: String) : TranscriptEffect
}

class TranscriptViewModel(private val app: DibeApp, private val handle: SavedStateHandle, private val initialId: String) :
    ViewModel(), SummaryActions {
    private val store = app.store

    /** Renaming moves the project folder, so the id changes; kept here to survive process death. */
    private var id: String
        get() = handle[ID] ?: initialId
        set(value) {
            handle[ID] = value
        }

    private val _state = MutableStateFlow(TranscriptUiState())
    val state: StateFlow<TranscriptUiState> = _state.asStateFlow()
    private val _effects = Channel<TranscriptEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()
    private var summaryJob: Job? = null
    /** Format and toggles chosen in the sheet, waiting for the system "save as" picker, which can outlive the process. */
    private var pendingSave: ExportOptions?
        get() = handle.get<Bundle>(PENDING_SAVE)?.toExportOptions()
        set(value) {
            handle[PENDING_SAVE] = value?.toBundle()
        }

    init {
        viewModelScope.launch {
            val record = attempt(null) { store.read(id) }
            val audio = if (record != null) attempt(null) { store.audioFile(id)?.takeIf { it.isFile } } else null
            _state.update { it.copy(loading = false, record = record, player = audio?.let { file -> AudioPlayer(file, viewModelScope) }) }
        }
        app.sessions.fileJob
            .onEach { job ->
                val mine = job?.targetId == id
                _state.update { it.copy(transcribing = job?.takeIf { mine && it.phase in ACTIVE_FILE_PHASES }?.progress) }
                if (mine && job?.phase == FilePhase.DONE) {
                    store.read(id)?.let { record -> _state.update { it.copy(record = record) } }
                    app.sessions.clearResults()
                }
            }
            .launchIn(viewModelScope)
        app.settings.settings
            .onEach { settings -> _state.update { it.copy(aiReady = settings.ai.isReady(), aiPreset = settings.ai.preset) } }
            .launchIn(viewModelScope)
        // Renamed elsewhere (the AI titling a new recording while it is open): follow the folder and show the new name.
        store.entries
            .onEach {
                val now = store.currentId(id)
                if (now != null && now != id) {
                    id = now
                    store.read(now)?.let { record -> _state.update { it.copy(record = record) } }
                }
            }
            .launchIn(viewModelScope)
    }

    fun transcribeAudio() {
        val audio = store.audioFile(id)?.takeIf { it.isFile } ?: return
        if (!app.sessions.transcribeFile(Uri.fromFile(audio), targetId = id)) {
            viewModelScope.launch { _effects.send(TranscriptEffect.Message(R.string.home_file_busy)) }
        }
    }

    /** False, with the reason shown, while this project's own audio is transcribed into it: the job saves into this id. */
    fun canModify(): Boolean {
        if (!app.sessions.isFilling(id)) return true
        _effects.trySend(TranscriptEffect.Message(R.string.transcript_busy_transcribing))
        return false
    }

    fun rename(name: String) {
        if (!canModify()) return
        viewModelScope.launch {
            attempt(R.string.history_rename_failed) {
                val newId = store.rename(id, name)
                id = newId
                store.read(newId)?.let { record -> _state.update { it.copy(record = record) } }
            }
        }
    }

    fun delete() {
        if (!canModify()) return
        viewModelScope.launch {
            _state.value.player?.pause()
            attempt(R.string.history_delete_failed) {
                store.delete(id)
                _effects.send(TranscriptEffect.Deleted)
            }
        }
    }

    fun editSegment(index: Int, text: String) = change(R.string.transcript_save_failed) { record ->
        record.copy(segments = record.segments.mapIndexed { i, segment -> if (i == index) segment.copy(text = text) else segment })
    }

    override fun removeQuestion(index: Int) = change(R.string.transcript_save_failed) { record ->
        record.copy(thread = record.thread.orEmpty().filterIndexed { i, _ -> i != index })
    }

    /** [preset] differs from the saved one only for this run; a custom prompt is kept for its own preset. */
    override fun summarize(preset: String) {
        val record = _state.value.record ?: return
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            _state.update { it.copy(summarizing = true, summaryError = null) }
            try {
                val saved = app.settings.settings.value.ai
                val settings = if (preset == saved.preset) saved else saved.copy(preset = preset, prompt = "")
                val summary = app.ai.summarize(record, settings, uiLanguage())
                val updated = store.update(id) { it.copy(summary = summary) }
                _state.update { it.copy(record = updated) }
                // A (re)summary also names a project that still has the app's name; the store watcher above shows it.
                viewModelScope.launch { app.autoTitle(id) }
                if (VisualPass.appliesTo(preset)) {
                    _state.update { it.copy(summarizing = false, visualizing = true) }
                    // Visuals are extra: if this pass fails, the summary above already stands.
                    val drawn = runCatching { app.ai.visualize(record, summary, settings, uiLanguage()) }
                        .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "visuals failed", it) }
                        .getOrNull()
                    if (drawn != null && drawn != summary) {
                        val withVisuals = store.update(id) { it.copy(summary = drawn) }
                        _state.update { it.copy(record = withVisuals) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _state.update { it.copy(summaryError = aiErrorText(e.kind)) }
            } catch (e: IOException) {
                _effects.send(TranscriptEffect.Message(R.string.transcript_save_failed))
            } catch (e: Exception) {
                _state.update { it.copy(summaryError = aiErrorText(null)) }
            } finally {
                _state.update { it.copy(summarizing = false, visualizing = false) }
            }
        }
    }

    override fun cancelSummary() {
        summaryJob?.cancel()
    }

    override fun ask(question: String) {
        val text = question.trim()
        val record = _state.value.record ?: return
        if (text.isEmpty() || _state.value.asking != null) return
        viewModelScope.launch {
            _state.update { it.copy(asking = text, askError = null, failedQuestion = null) }
            try {
                val answer = app.ai.ask(record, text, app.settings.settings.value.ai, uiLanguage())
                val updated = store.update(id) { it.copy(thread = it.thread.orEmpty() + AiThreadEntry(text, answer)) }
                _state.update { it.copy(record = updated) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is AiException) aiErrorText(e.kind) else if (e is IOException) R.string.transcript_save_failed else aiErrorText(null)
                _state.update { it.copy(askError = message, failedQuestion = text) }
            } finally {
                _state.update { it.copy(asking = null) }
            }
        }
    }

    fun share(options: ExportOptions) {
        val record = _state.value.record ?: return
        viewModelScope.launch {
            val uri = exporting {
                withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "exports").apply { mkdirs() }
                    val file = File(dir, "${fileName(record.name)}.${options.format.extension}")
                    file.outputStream().use { Exporter.export(app, record, options, it) }
                    FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                }
            } ?: return@launch
            _effects.send(TranscriptEffect.Share(uri, options.format.mime))
        }
    }

    /** File name and MIME type of the project's own recording, for the "save as" picker; null without audio. */
    fun audioDocument(): Pair<String, String>? {
        val record = _state.value.record ?: return null
        val audio = store.audioFile(id)?.takeIf { it.isFile } ?: return null
        return "${fileName(record.name)}.${audio.extension}" to audioMime(audio)
    }

    fun shareAudio() {
        val (name, mime) = audioDocument() ?: return
        val audio = store.audioFile(id) ?: return
        viewModelScope.launch {
            val uri = exporting {
                withContext(Dispatchers.IO) {
                    // FileProvider only serves cache/exports, so the share goes through a copy there.
                    val copy = File(File(app.cacheDir, "exports").apply { mkdirs() }, name)
                    audio.copyTo(copy, overwrite = true)
                    FileProvider.getUriForFile(app, "${app.packageName}.files", copy)
                }
            } ?: return@launch
            _effects.send(TranscriptEffect.Share(uri, mime))
        }
    }

    fun saveAudioTo(uri: Uri) {
        val audio = store.audioFile(id)?.takeIf { it.isFile }
        viewModelScope.launch {
            if (audio == null) return@launch discard(uri)
            exporting {
                withContext(Dispatchers.IO) {
                    val out = app.contentResolver.openOutputStream(uri) ?: throw IOException("no stream for $uri")
                    out.use { sink -> audio.inputStream().use { it.copyTo(sink) } }
                }
            } ?: return@launch discard(uri)
            _effects.send(TranscriptEffect.Message(R.string.recording_saved))
        }
    }

    fun prepareSave(options: ExportOptions) {
        pendingSave = options
    }

    fun saveTo(uri: Uri) {
        val options = pendingSave
        pendingSave = null
        viewModelScope.launch {
            // After process death the picker answers while the record is still loading.
            val record = _state.first { !it.loading }.record
            if (options == null || record == null) {
                discard(uri)
                _effects.send(TranscriptEffect.Message(R.string.export_interrupted))
                return@launch
            }
            exporting {
                withContext(Dispatchers.IO) {
                    val out = app.contentResolver.openOutputStream(uri) ?: throw IOException("no stream for $uri")
                    out.use { Exporter.export(app, record, options, it) }
                }
            } ?: return@launch discard(uri)
            _effects.send(TranscriptEffect.Message(R.string.export_saved))
        }
    }

    /** The picker already created the document; without this it stays behind empty or half written. */
    private suspend fun discard(uri: Uri): Unit = withContext(Dispatchers.IO) {
        try {
            DocumentsContract.deleteDocument(app.contentResolver, uri)
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "could not delete the unwritten export", e)
        } catch (e: RuntimeException) { // the provider refuses deletes, or a stale grant
            Log.w(TAG, "could not delete the unwritten export", e)
        }
    }

    override fun onCleared() {
        _state.value.player?.release()
    }

    private suspend fun <T> exporting(block: suspend () -> T): T? {
        _state.update { it.copy(exporting = true) }
        return try {
            attempt(R.string.export_failed, block)
        } finally {
            _state.update { it.copy(exporting = false) }
        }
    }

    private fun change(@StringRes error: Int, transform: (TranscriptRecord) -> TranscriptRecord) {
        viewModelScope.launch {
            attempt(error) {
                val updated = store.update(id, transform)
                _state.update { it.copy(record = updated) }
            }
        }
    }

    /** Runs [block]; on failure shows [error] (when given) and returns null. */
    private suspend fun <T> attempt(@StringRes error: Int?, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        if (error != null) _effects.send(TranscriptEffect.Message(error))
        null
    }

    private companion object {
        const val TAG = "TranscriptViewModel"
        const val ID = "id"
        const val PENDING_SAVE = "pendingSave"

        /** The language name the AI should answer in, e.g. "Hebrew". */
        fun uiLanguage(): String = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)

        fun fileName(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(80).ifEmpty { "transcript" }
    }
}

/** A job is writing this transcript's own audio back into it, and saves into [id] when it ends. */
internal fun Sessions.isFilling(id: String): Boolean = fileJob.value?.let { it.targetId == id && it.phase in ACTIVE_FILE_PHASES } == true

private fun ExportOptions.toBundle() = Bundle().apply {
    putString("format", format.name)
    putString("content", content.name)
    putBoolean("timestamps", showTimestamps)
    putBoolean("speakers", showSpeakers)
    putString("speakerLabel", speakerLabel)
    putBoolean("rtl", rtl)
    putString("transcriptLabel", transcriptLabel)
    putString("summaryLabel", summaryLabel)
}

private fun Bundle.toExportOptions(): ExportOptions? = try {
    ExportOptions(
        format = ExportFormat.valueOf(getString("format") ?: return null),
        content = ExportContent.valueOf(getString("content") ?: return null),
        showTimestamps = getBoolean("timestamps"),
        showSpeakers = getBoolean("speakers"),
        speakerLabel = getString("speakerLabel") ?: return null,
        rtl = getBoolean("rtl"),
        transcriptLabel = getString("transcriptLabel") ?: return null,
        summaryLabel = getString("summaryLabel") ?: return null,
    )
} catch (e: IllegalArgumentException) { // an enum renamed since the state was saved
    null
}

private fun audioMime(file: File): String =
    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "audio/*"
