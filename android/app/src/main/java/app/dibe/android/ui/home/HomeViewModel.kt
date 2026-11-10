package app.dibe.android.ui.home

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibe.android.DibeApp
import app.dibe.android.engine.EngineCapabilities
import app.dibe.android.models.ModelSpec
import app.dibe.android.models.ModelStatus
import app.dibe.android.service.AudioSource
import app.dibe.android.service.FileJobState
import app.dibe.android.service.Problem
import app.dibe.android.service.RecordingPhase
import app.dibe.android.ui.components.languageName
import app.dibe.android.ui.components.normalizeLanguage
import java.io.File
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

/** The active model as the home screen shows it. */
sealed interface ModelState {
    data class Ready(val spec: ModelSpec) : ModelState
    /** Downloading, waiting for network or verifying. */
    data class Fetching(val spec: ModelSpec, val status: ModelStatus) : ModelState
    data class Failed(val spec: ModelSpec) : ModelState
    data object None : ModelState
}

data class HomeUiState(
    val model: ModelState = ModelState.None,
    /** "auto" then the language codes the model takes; empty when unknown. */
    val languages: List<String> = emptyList(),
    val savedLanguage: String = "auto",
    val fileJob: FileJobState? = null,
    val recordingActive: Boolean = false,
    val recordingError: Problem? = null,
    val recordingErrorDetail: String? = null,
) {
    val canStart: Boolean get() = model is ModelState.Ready
    /** The saved choice when the model still offers it, else what the model starts with. */
    val language: String get() = savedLanguage.takeIf { it in languages } ?: languages.firstOrNull() ?: savedLanguage
}

sealed interface HomeEffect {
    /** A file was refused: one is still being transcribed. */
    data object FileBusy : HomeEffect
}

private data class RecordingSlice(val active: Boolean, val error: Problem?, val detail: String?)

private data class LanguageSource(val file: File?, val capabilities: EngineCapabilities?)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val app: DibeApp) : ViewModel() {
    private val models = app.models
    private val sessions = app.sessions

    private val modelState = combine(models.activeModelId, models.statuses) { id, statuses -> modelState(id, statuses) }

    private val languages = combine(models.activeModelId, models.statuses, app.engine.loadedModel, app.engine.capabilities) { id, statuses, loaded, caps ->
        val file = id?.takeIf { statuses[it] == ModelStatus.Installed }?.let(models::modelFile)
        LanguageSource(file, caps.takeIf { file != null && loaded == file.absolutePath })
    }.distinctUntilChanged().mapLatest { source ->
        val caps = source.capabilities ?: source.file?.let { file ->
            try {
                app.engine.metadata(file)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        caps?.let { languageOptions(it) }.orEmpty()
    }.onStart { emit(emptyList()) }

    private val recording = sessions.recording
        .map { RecordingSlice(it.phase != RecordingPhase.IDLE, it.error, it.errorDetail) }
        .distinctUntilChanged()

    val uiState: StateFlow<HomeUiState> = combine(modelState, languages, app.settings.settings, recording, sessions.fileJob) { model, languages, settings, rec, job ->
        HomeUiState(model, languages, settings.language, job, rec.active, rec.error, rec.detail)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // Built from the current values so the first frame doesn't flash "no model".
        HomeUiState(
            model = modelState(models.activeModelId.value, models.statuses.value),
            savedLanguage = app.settings.settings.value.language,
            fileJob = sessions.fileJob.value,
            recordingActive = sessions.recording.value.phase != RecordingPhase.IDLE,
        ),
    )

    private val _effects = Channel<HomeEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun setLanguage(code: String) = app.settings.update { it.copy(language = code) }

    // One-shot: the next start skips the free-memory check (after the user chose "Run anyway").
    private var forceNext = false
    private var lastFile: Uri? = null

    fun startRecording(source: AudioSource, projection: Intent?) {
        sessions.startRecording(source, projection, forceNext)
        forceNext = false
    }

    fun transcribe(uri: Uri) {
        lastFile = uri
        if (!sessions.transcribeFile(uri, forceNext)) _effects.trySend(HomeEffect.FileBusy)
        forceNext = false
    }

    /** A recording retried this way still goes through the permission/consent starter. */
    fun armRunAnyway() {
        forceNext = true
        sessions.clearResults()
    }

    fun runFileAnyway() {
        val uri = lastFile ?: return
        armRunAnyway()
        transcribe(uri)
    }

    fun cancelFile() = sessions.cancelFile()

    fun clearResults() = sessions.clearResults()

    private fun modelState(activeId: String?, statuses: Map<String, ModelStatus>): ModelState {
        val catalog = models.catalog
        catalog.firstOrNull { it.id == activeId }?.let { active ->
            when (val status = statuses[active.id]) {
                ModelStatus.Installed -> return ModelState.Ready(active)
                is ModelStatus.Failed -> return ModelState.Failed(active)
                null, ModelStatus.NotInstalled -> Unit
                else -> return ModelState.Fetching(active, status)
            }
        }
        // No usable active model: a download on its way (e.g. from onboarding) or failed still shows.
        catalog.firstNotNullOfOrNull { spec -> statuses[spec.id]?.takeIf { it.isFetching() }?.let { ModelState.Fetching(spec, it) } }
            ?.let { return it }
        return catalog.firstOrNull { statuses[it.id] is ModelStatus.Failed }?.let { ModelState.Failed(it) } ?: ModelState.None
    }

    private fun ModelStatus.isFetching() = this is ModelStatus.Downloading || this is ModelStatus.Waiting || this == ModelStatus.Verifying
}

/**
 * "auto" first (every engine takes it: Whisper detects, Parakeet uses the model's language), then the
 * phone's language and English, then the rest by name in the UI language.
 */
internal fun languageOptions(caps: EngineCapabilities, uiLanguage: String = Locale.getDefault().language): List<String> {
    val codes = caps.languages.distinct()
    val preferred = listOf(normalizeLanguage(uiLanguage), "en").distinct().filter { it in codes }
    val collator = Collator.getInstance()
    val rest = (codes - preferred.toSet()).sortedWith(compareBy(collator) { languageName(it, it) })
    return listOf("auto") + preferred + rest
}
