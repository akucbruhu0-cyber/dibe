package app.dibe.android.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dibe.android.R
import app.dibe.android.app
import app.dibe.android.models.EngineKind
import app.dibe.android.models.ModelSpec
import app.dibe.android.models.Tier
import app.dibe.android.service.AudioSource
import app.dibe.android.ui.components.Eyebrow
import app.dibe.android.ui.components.LocalReducedMotion
import app.dibe.android.ui.components.PillSegmented
import app.dibe.android.ui.components.ProblemCard
import app.dibe.android.ui.components.languageName
import app.dibe.android.ui.theme.DibeTheme
import kotlinx.coroutines.launch

@Stable
interface HomeActions {
    fun record()
    fun selectSource(picked: AudioSource)
    fun pickFile()
    fun selectLanguage(code: String)
    fun openModels()
    fun cancelFile()
    fun openTranscript(id: String)
    fun dismissResults()
    fun runRecordingAnyway()
    fun runFileAnyway()
}

@Composable
fun HomeScreen(
    snackbar: SnackbarHostState,
    onOpenModels: () -> Unit,
    onOpenRecording: () -> Unit,
    onOpenTranscript: (String) -> Unit,
) {
    val app = LocalContext.current.app
    val vm = viewModel { HomeViewModel(app) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val busy = stringResource(R.string.home_file_busy)
    val projectionDenied = stringResource(R.string.ui_problem_projection_denied)

    LaunchedEffect(vm) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.effects.collect { effect ->
                when (effect) {
                    HomeEffect.FileBusy -> snackbar.showSnackbar(busy)
                }
            }
        }
    }

    var source by rememberSaveable { mutableStateOf(AudioSource.MIC) }
    val startRecording = rememberRecordStarter(
        onStart = vm::startRecording,
        onProjectionDenied = { scope.launch { snackbar.showSnackbar(projectionDenied) } },
    )
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::transcribe) }
    // The actions object lives across recompositions; it calls the latest of everything.
    val latestState by rememberUpdatedState(state)
    val latestStart by rememberUpdatedState(startRecording)
    val toModels by rememberUpdatedState(onOpenModels)
    val toRecording by rememberUpdatedState(onOpenRecording)
    val toTranscript by rememberUpdatedState(onOpenTranscript)
    val actions = remember(vm) {
        object : HomeActions {
            override fun record() = if (latestState.recordingActive) toRecording() else latestStart(source)
            override fun selectSource(picked: AudioSource) {
                source = picked
            }
            override fun pickFile() = pickFile.launch(arrayOf("audio/*", "video/*"))
            override fun selectLanguage(code: String) = vm.setLanguage(code)
            override fun openModels() = toModels()
            override fun cancelFile() = vm.cancelFile()
            override fun openTranscript(id: String) {
                toTranscript(id)
                vm.clearResults()
            }
            override fun dismissResults() = vm.clearResults()
            override fun runRecordingAnyway() {
                vm.armRunAnyway()
                latestStart(source)
            }
            override fun runFileAnyway() = vm.runFileAnyway()
        }
    }
    HomeContent(state, source, actions)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeContent(state: HomeUiState, source: AudioSource, actions: HomeActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Fills the screen when short, so the hero can sit in the optical centre.
                .heightIn(min = maxHeight)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.dibe_logo), contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Eyebrow(stringResource(R.string.home_eyebrow))
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(if (state.recordingActive) R.string.home_title_recording else R.string.home_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(16.dp))
                if (state.model is ModelState.Ready) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ModelCard(state.model, actions::openModels)
                        // A choice only when the model lists two languages or more, beside "auto".
                        if (state.languages.size > 2) LanguageChip(state.languages, state.language, actions::selectLanguage)
                    }
                } else {
                    ModelCard(state.model, actions::openModels)
                }

                Spacer(Modifier.height(28.dp))
                RecordButton(active = state.recordingActive, enabled = state.canStart || state.recordingActive, onClick = actions::record)
                Text(
                    stringResource(
                        when {
                            state.recordingActive -> R.string.home_record_open
                            state.canStart -> R.string.home_record_hint
                            else -> R.string.home_record_needs_model
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                PillSegmented(
                    options = AudioSource.entries,
                    selected = source,
                    onSelect = actions::selectSource,
                    label = { stringResource(it.label()) },
                    enabled = !state.recordingActive,
                )
                AnimatedVisibility(source != AudioSource.MIC && !state.recordingActive, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Text(
                        stringResource(R.string.home_source_system_hint),
                        Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                state.recordingError?.takeIf { !state.recordingActive }?.let { problem ->
                    Spacer(Modifier.height(16.dp))
                    ProblemCard(
                        problem,
                        actions::openModels,
                        detail = state.recordingErrorDetail,
                        onDismiss = actions::dismissResults,
                        onRunAnyway = actions::runRecordingAnyway,
                    )
                }

                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        stringResource(R.string.home_or),
                        Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                }
                Spacer(Modifier.height(16.dp))
                // The button becomes the job's card in the same place, and returns when it clears.
                AnimatedContent(
                    targetState = state.fileJob,
                    contentKey = { it != null },
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                    label = "fileSlot",
                ) { job ->
                    if (job != null) {
                        FileJobCard(job, actions::cancelFile, actions::openTranscript, actions::dismissResults, actions::openModels, onRunAnyway = actions::runFileAnyway)
                    } else {
                        OutlinedButton(
                            onClick = actions::pickFile,
                            enabled = state.canStart,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Icon(Icons.Outlined.UploadFile, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.home_pick_file), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun AudioSource.label() = when (this) {
    AudioSource.MIC -> R.string.home_source_mic
    AudioSource.SYSTEM -> R.string.home_source_system
    AudioSource.BOTH -> R.string.home_source_both
}

/** The hero: a big green key. Presses in, and while a recording runs, a ring breathes out of it. */
@Composable
private fun RecordButton(active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(140), label = "recordPress")
    val ring = if (active && !LocalReducedMotion.current) {
        rememberInfiniteTransition(label = "ring").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearOutSlowInEasing)), label = "ringProgress")
    } else {
        null
    }
    val primary = MaterialTheme.colorScheme.primary
    val halo = MaterialTheme.colorScheme.primaryContainer
    val label = stringResource(if (active) R.string.home_record_open else R.string.home_record_start)

    Box(
        Modifier
            .size(168.dp)
            .drawBehind {
                val radius = size.minDimension / 2
                drawCircle(halo, radius * 0.81f)
                ring?.let { drawCircle(primary.copy(alpha = 0.28f * (1 - it.value)), radius * (0.67f + 0.33f * it.value)) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(112.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(if (enabled) 14.dp else 0.dp, CircleShape, ambientColor = primary, spotColor = primary)
                .clip(CircleShape)
                .background(if (enabled) primary else primary.copy(alpha = 0.38f))
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(color = MaterialTheme.colorScheme.onPrimary),
                    enabled = enabled,
                    onClickLabel = label,
                    role = Role.Button,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (active) Icons.Rounded.GraphicEq else Icons.Rounded.Mic, label, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun LanguageChip(languages: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val auto = stringResource(R.string.home_language_auto)
    val description = stringResource(R.string.home_language_desc, languageName(selected, auto))
    Box {
        Surface(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { this.contentDescription = description },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Translate, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(languageName(selected, auto), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.medium) {
            languages.forEach { code ->
                DropdownMenuItem(
                    text = { Text(languageName(code, auto)) },
                    onClick = {
                        open = false
                        onSelect(code)
                    },
                    trailingIcon = if (code == selected) {
                        { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private val PreviewModel = ModelSpec(
    id = "whisper-small", name = "Whisper Small", tier = Tier.MEDIUM, engine = EngineKind.WHISPER,
    url = "", fileName = "", sizeBytes = 487_000_000, sha256 = null, minRamGb = 4, live = true,
)

private object NoActions : HomeActions {
    override fun record() = Unit
    override fun selectSource(picked: AudioSource) = Unit
    override fun pickFile() = Unit
    override fun selectLanguage(code: String) = Unit
    override fun openModels() = Unit
    override fun cancelFile() = Unit
    override fun openTranscript(id: String) = Unit
    override fun dismissResults() = Unit
    override fun runRecordingAnyway() = Unit
    override fun runFileAnyway() = Unit
}

@Preview(showBackground = true, heightDp = 860)
@Composable
private fun HomeReadyPreview() {
    DibeTheme {
        HomeContent(HomeUiState(model = ModelState.Ready(PreviewModel), languages = listOf("auto", "he", "en")), AudioSource.MIC, NoActions)
    }
}

@Preview(showBackground = true, heightDp = 860, locale = "iw")
@Composable
private fun HomeNoModelPreview() {
    DibeTheme(dark = true) { HomeContent(HomeUiState(), AudioSource.SYSTEM, NoActions) }
}
