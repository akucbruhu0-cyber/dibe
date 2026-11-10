@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.dibe.android.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dibe.android.R
import app.dibe.android.app
import app.dibe.android.engine.TranscriptionEngine
import app.dibe.android.models.DeviceInfo
import app.dibe.android.models.EngineKind
import app.dibe.android.models.Fit
import app.dibe.android.models.ModelProblem
import app.dibe.android.models.ModelRepository
import app.dibe.android.models.ModelSpec
import app.dibe.android.models.ModelStatus
import app.dibe.android.models.Tier
import app.dibe.android.models.spaceNeeded
import app.dibe.android.service.ACTIVE_FILE_PHASES
import app.dibe.android.service.RecordingPhase
import app.dibe.android.service.Sessions
import app.dibe.android.ui.components.formatSize
import app.dibe.android.ui.library.ConfirmDialog
import app.dibe.android.ui.library.Pill
import app.dibe.android.ui.library.SectionLabel
import app.dibe.android.ui.theme.DibeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

data class ModelsUiState(
    val catalog: List<ModelSpec> = emptyList(),
    val statuses: Map<String, ModelStatus> = emptyMap(),
    val activeId: String? = null,
    val recommendedId: String? = null,
    val fits: Map<String, Fit> = emptyMap(),
    val ramBytes: Long = 0,
    val soc: String = "",
    val freeBytes: Long? = null,
)

class ModelsViewModel(
    private val models: ModelRepository,
    private val device: DeviceInfo,
    private val engine: TranscriptionEngine,
    private val sessions: Sessions,
) : ViewModel() {
    private val fixed = ModelsUiState(
        catalog = models.catalog,
        fits = models.catalog.associate { it.id to models.fit(it) },
        recommendedId = models.recommended().id,
        ramBytes = device.totalRamBytes,
        soc = device.socName,
    )
    private val free = MutableStateFlow<Long?>(null)
    private val _errors = Channel<Int>(Channel.BUFFERED)
    val errors = _errors.receiveAsFlow()

    val uiState: StateFlow<ModelsUiState> = combine(models.statuses, models.activeModelId, free) { statuses, active, free ->
        fixed.copy(statuses = statuses, activeId = active, freeBytes = free)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), fixed)

    init {
        // Re-read free space when a model lands, leaves or fails — not on every progress tick.
        models.statuses
            .map { statuses -> statuses.mapValues { it.value::class } }
            .distinctUntilChanged()
            .onEach { free.value = withContext(Dispatchers.IO) { device.freeStorageBytes() } }
            .launchIn(viewModelScope)
    }

    fun download(id: String) = guarded { models.download(id) }
    fun cancel(id: String) = guarded { models.cancel(id) }
    /** The repository leaves unloading to its caller: a deleted model must not stay in RAM, nor vanish under running work. */
    fun delete(id: String) {
        val loaded = models.modelFile(id)?.path?.let { it == engine.loadedModel.value } == true
        val working = sessions.recording.value.phase != RecordingPhase.IDLE || sessions.fileJob.value?.phase in ACTIVE_FILE_PHASES
        if (working && (loaded || id == models.activeModelId.value)) {
            _errors.trySend(R.string.models_delete_busy)
            return
        }
        viewModelScope.launch {
            if (loaded) {
                try {
                    engine.unload()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _errors.send(R.string.models_action_failed)
                    return@launch
                }
            }
            guarded { models.delete(id) }
        }
    }
    fun use(id: String) = guarded { models.setActive(id) }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            _errors.trySend(R.string.models_action_failed)
        }
    }
}

/** Ranked model manager: download, switch, delete, fit warnings. Also opened from onboarding and home. */
@Composable
fun ModelsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.app
    val vm = viewModel { ModelsViewModel(app.models, app.device, app.engine, app.sessions) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.errors.collect { snackbar.showSnackbar(context.getString(it)) }
        }
    }
    ModelsContent(state, snackbar, onBack, vm::download, vm::cancel, vm::delete, vm::use)
}

@Composable
private fun ModelsContent(
    state: ModelsUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onUse: (String) -> Unit,
) {
    var confirmHeavy by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.models_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.lib_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "intro") {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.models_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DeviceFacts(state)
                }
            }
            Tier.entries.forEach { tier ->
                val specs = state.catalog.filter { it.tier == tier }
                if (specs.isEmpty()) return@forEach
                item(key = "tier-$tier") {
                    SectionLabel(
                        stringResource(tierLabel(tier)),
                        Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 4.dp, top = 12.dp),
                    )
                }
                items(specs, key = { it.id }) { spec ->
                    val fit = state.fits[spec.id] ?: Fit.GOOD
                    ModelCard(
                        spec = spec,
                        status = state.statuses[spec.id] ?: ModelStatus.NotInstalled,
                        active = spec.id == state.activeId,
                        recommended = spec.id == state.recommendedId,
                        fit = fit,
                        freeBytes = state.freeBytes,
                        onDownload = { if (fit == Fit.TOO_HEAVY) confirmHeavy = spec.id else onDownload(spec.id) },
                        onCancel = { onCancel(spec.id) },
                        onUse = { onUse(spec.id) },
                        onDelete = { confirmDelete = spec.id },
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    )
                }
            }
        }
    }

    state.catalog.find { it.id == confirmHeavy }?.let { spec ->
        ConfirmDialog(
            title = stringResource(R.string.models_heavy_title),
            text = stringResource(R.string.models_heavy_body, spec.name),
            confirm = stringResource(R.string.models_download_anyway),
            onConfirm = {
                onDownload(spec.id)
                confirmHeavy = null
            },
            onDismiss = { confirmHeavy = null },
        )
    }
    state.catalog.find { it.id == confirmDelete }?.let { spec ->
        ConfirmDialog(
            title = stringResource(R.string.models_delete_title, spec.name),
            text = stringResource(if (spec.id == state.activeId) R.string.models_delete_active_body else R.string.models_delete_body),
            confirm = stringResource(R.string.lib_delete),
            onConfirm = {
                onDelete(spec.id)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun DeviceFacts(state: ModelsUiState) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.ramBytes > 0) {
            val gb = ceil(state.ramBytes / (1024.0 * 1024 * 1024)).toInt()
            Pill(stringResource(R.string.models_ram, gb), icon = Icons.Outlined.Memory)
        }
        if (state.soc.isNotBlank()) Pill(state.soc, icon = Icons.Outlined.DeveloperBoard)
        state.freeBytes?.let { Pill(stringResource(R.string.models_free, formatSize(it)), icon = Icons.Outlined.SdStorage) }
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    status: ModelStatus,
    active: Boolean,
    recommended: Boolean,
    fit: Fit,
    freeBytes: Long?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier,
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
        border = if (active) BorderStroke(1.5.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(spec.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${formatSize(spec.sizeBytes)} · ${stringResource(languagesNote(spec))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (active) {
                    Pill(
                        stringResource(R.string.models_in_use),
                        icon = Icons.Outlined.CheckCircle,
                        container = colors.primaryContainer,
                        content = colors.onPrimaryContainer,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (recommended) {
                    Pill(stringResource(R.string.models_recommended), icon = Icons.Outlined.ThumbUp, container = colors.primaryContainer, content = colors.onPrimaryContainer)
                }
                if (spec.hebrew) Pill(stringResource(R.string.models_hebrew), icon = Icons.Outlined.Translate)
                Pill(
                    stringResource(if (spec.live) R.string.models_live else R.string.models_files_only),
                    icon = if (spec.live) Icons.Outlined.GraphicEq else Icons.AutoMirrored.Outlined.InsertDriveFile,
                )
                if (fit == Fit.TIGHT) {
                    Pill(stringResource(R.string.models_fit_tight), icon = Icons.Outlined.Speed, container = colors.tertiary.copy(alpha = 0.22f))
                }
            }
            if (fit == Fit.TOO_HEAVY) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = colors.error, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.models_fit_too_heavy), style = MaterialTheme.typography.bodySmall, color = colors.error)
                }
            }
            ModelStatusArea(spec, status, active, fit, freeBytes, onDownload, onCancel, onUse, onDelete)
        }
    }
}

private fun languagesNote(spec: ModelSpec): Int = when {
    spec.engine == EngineKind.WHISPER -> R.string.models_languages_whisper
    spec.hebrew -> R.string.models_languages_hebrew
    else -> R.string.models_languages_european
}

@Composable
private fun ModelStatusArea(
    spec: ModelSpec,
    status: ModelStatus,
    active: Boolean,
    fit: Fit,
    freeBytes: Long?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    when (status) {
        ModelStatus.NotInstalled -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val content: @Composable () -> Unit = {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.models_download))
            }
            if (fit == Fit.TOO_HEAVY) OutlinedButton(onClick = onDownload) { content() }
            else FilledTonalButton(onClick = onDownload) { content() }
        }
        is ModelStatus.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val progress = status.progress
            if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            ProgressLine(stringResource(R.string.models_progress, formatSize(status.downloadedBytes), formatSize(spec.sizeBytes)), onCancel)
        }
        is ModelStatus.Waiting -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(
                progress = { if (spec.sizeBytes > 0) status.downloadedBytes.toFloat() / spec.sizeBytes else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = colors.onSurfaceVariant,
            )
            ProgressLine(stringResource(R.string.models_waiting), onCancel, Icons.Outlined.WifiOff)
        }
        ModelStatus.Verifying -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.models_verifying), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        ModelStatus.Installed -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.lib_delete))
            }
            Spacer(Modifier.weight(1f))
            if (!active) Button(onClick = onUse) { Text(stringResource(R.string.models_use)) }
        }
        is ModelStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.error, modifier = Modifier.size(18.dp))
                Text(failureText(spec, status.reason, freeBytes), style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDownload) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.lib_retry))
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(text: String, onCancel: () -> Unit, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp).size(16.dp))
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onCancel) { Text(stringResource(R.string.lib_cancel)) }
    }
}

@Composable
private fun failureText(spec: ModelSpec, reason: ModelProblem, freeBytes: Long?): String = when (reason) {
    ModelProblem.NO_SPACE -> {
        val missing = (spaceNeeded(spec) - (freeBytes ?: 0)).coerceAtLeast(1_000_000)
        stringResource(R.string.models_failed_space, formatSize(missing))
    }
    ModelProblem.NETWORK -> stringResource(R.string.models_failed_network)
    ModelProblem.CORRUPT -> stringResource(R.string.models_failed_corrupt)
    ModelProblem.UNKNOWN -> stringResource(R.string.models_failed_unknown)
}

private val previewCatalog = listOf(
    ModelSpec("tiny", "Whisper Tiny", Tier.LIGHT, EngineKind.WHISPER, "", "", 77_700_000, null, 2, true),
    ModelSpec("ivrit", "Parakeet Hebrew", Tier.MEDIUM, EngineKind.PARAKEET, "", "", 640_000_000, null, 4, true, hebrew = true),
    ModelSpec("large", "Whisper Large v3 Turbo", Tier.HEAVY, EngineKind.WHISPER, "", "", 1_620_000_000, null, 8, false),
)

@Preview
@Composable
private fun ModelsPreview() {
    DibeTheme {
        ModelsContent(
            ModelsUiState(
                catalog = previewCatalog,
                statuses = mapOf(
                    "tiny" to ModelStatus.Installed,
                    "ivrit" to ModelStatus.Downloading(0.42f, 268_000_000),
                    "large" to ModelStatus.Failed(ModelProblem.NO_SPACE),
                ),
                activeId = "tiny",
                recommendedId = "ivrit",
                fits = mapOf("large" to Fit.TOO_HEAVY, "ivrit" to Fit.TIGHT),
                ramBytes = 7_600_000_000,
                soc = "Snapdragon 8 Gen 2",
                freeBytes = 900_000_000,
            ),
            SnackbarHostState(),
            {}, {}, {}, {}, {},
        )
    }
}
