@file:OptIn(ExperimentalMaterial3Api::class)

package app.dibe.android.ui.transcript

import android.content.ClipData
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dibe.android.R
import app.dibe.android.app
import app.dibe.android.ui.components.PillSegmented
import app.dibe.android.ui.components.formatClock
import app.dibe.android.ui.library.ConfirmDialog
import app.dibe.android.ui.library.EmptyState
import app.dibe.android.ui.library.TextInputDialog
import app.dibe.android.ui.settings.AiSetupSheet
import kotlinx.coroutines.launch

private enum class Pane { TRANSCRIPT, SUMMARY }

/** One saved transcript: text, player, summary + Q&A, export. */
@Composable
fun TranscriptScreen(id: String, onBack: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val vm = viewModel { TranscriptViewModel(app, createSavedStateHandle(), id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val deleted by rememberUpdatedState(onDeleted)

    LaunchedEffect(vm) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.effects.collect { effect ->
                when (effect) {
                    TranscriptEffect.Deleted -> deleted()
                    is TranscriptEffect.Message -> snackbar.showSnackbar(context.getString(effect.text))
                    is TranscriptEffect.Share -> {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType(effect.mime)
                            .putExtra(Intent.EXTRA_STREAM, effect.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, null))
                    }
                }
            }
        }
    }
    // A recording keeps playing only while someone is looking at it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { state.player?.pause() }

    val saveLauncher = rememberLauncherForActivityResult(CreateExportDocument()) { uri -> uri?.let(vm::saveTo) }
    val saveAudioLauncher = rememberLauncherForActivityResult(CreateExportDocument()) { uri -> uri?.let(vm::saveAudioTo) }

    var pane by rememberSaveable { mutableStateOf(Pane.TRANSCRIPT) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val query = rememberTextFieldState()
    var currentMatch by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var aiSetup by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val record = state.record
    val texts = remember(record?.segments) { record?.segments.orEmpty().map { it.text.trim() } }
    val needle = query.text.toString()
    val matches = remember(texts, needle) { findMatches(texts, needle) }
    LaunchedEffect(matches) { currentMatch = 0 }
    LaunchedEffect(currentMatch, matches) {
        matches.getOrNull(currentMatch)?.let { listState.animateScrollToItem(it.segment + 1) }
    }
    val closeSearch = {
        searching = false
        query.clearText()
    }
    BackHandler(enabled = searching, onBack = closeSearch)

    val speakerLabel = stringResource(R.string.transcript_speaker)
    val copiedLabel = stringResource(R.string.lib_copied)
    fun copy(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(record?.name.orEmpty(), text)))
            // Android 13+ confirms a copy itself.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbar.showSnackbar(copiedLabel)
        }
    }
    fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, record?.name)
        context.startActivity(Intent.createChooser(send, null))
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = if (searching) closeSearch else onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(if (searching) R.string.transcript_close_search else R.string.lib_back),
                            )
                        }
                    },
                    title = {
                        if (searching) SearchInput(query, onNext = { if (matches.isNotEmpty()) currentMatch = (currentMatch + 1) % matches.size })
                        else if (record != null) {
                            Text(
                                record.name,
                                Modifier.clickable(onClickLabel = stringResource(R.string.lib_rename)) { if (vm.canModify()) renaming = true },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                            )
                        }
                    },
                    actions = {
                        if (searching) {
                            MatchCounter(matches.size, currentMatch, needle.isNotBlank())
                            IconButton(onClick = { currentMatch = (currentMatch - 1 + matches.size) % matches.size }, enabled = matches.isNotEmpty()) {
                                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.transcript_previous_match))
                            }
                            IconButton(onClick = { currentMatch = (currentMatch + 1) % matches.size }, enabled = matches.isNotEmpty()) {
                                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.transcript_next_match))
                            }
                        } else if (record != null) {
                            if (pane == Pane.TRANSCRIPT && record.segments.isNotEmpty()) {
                                IconButton(onClick = { searching = true }) {
                                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.transcript_search))
                                }
                            }
                            IconButton(onClick = { exportOpen = true }) {
                                Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.export_title))
                            }
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.lib_more_actions))
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.medium) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.lib_rename)) },
                                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                        onClick = { menu = false; if (vm.canModify()) renaming = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.transcript_copy_all)) },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                        enabled = record.segments.isNotEmpty(),
                                        onClick = { menu = false; copy(plainText(record, speakerLabel)) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.recording_share)) },
                                        leadingIcon = { Icon(Icons.Outlined.AudioFile, contentDescription = null) },
                                        enabled = state.player != null,
                                        onClick = { menu = false; vm.shareAudio() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.recording_save)) },
                                        leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                                        enabled = state.player != null,
                                        onClick = { menu = false; vm.audioDocument()?.let(saveAudioLauncher::launch) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.lib_delete)) },
                                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                        colors = MenuDefaults.itemColors(
                                            textColor = MaterialTheme.colorScheme.error,
                                            leadingIconColor = MaterialTheme.colorScheme.error,
                                        ),
                                        onClick = { menu = false; if (vm.canModify()) deleting = true },
                                    )
                                }
                            }
                        }
                    },
                )
                if (record != null && !searching) {
                    PillSegmented(
                        options = Pane.entries,
                        selected = pane,
                        onSelect = { pane = it },
                        label = { stringResource(if (it == Pane.TRANSCRIPT) R.string.transcript_tab else R.string.summary_tab) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (state.exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            val player = state.player
            when {
                record == null -> Unit
                pane == Pane.TRANSCRIPT && player != null -> {
                    val playerState by player.state.collectAsStateWithLifecycle()
                    if (!playerState.failed) PlayerBar(player, playerState)
                }
                pane == Pane.SUMMARY && state.aiReady && record.segments.isNotEmpty() -> AskBar(state.asking != null, vm::ask)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val body = Modifier.padding(padding)
        when {
            state.loading -> Box(body.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            record == null -> EmptyState(
                Icons.Outlined.SearchOff,
                R.string.transcript_missing_title,
                stringResource(R.string.transcript_missing_body),
                body,
            )
            pane == Pane.TRANSCRIPT -> TranscriptBody(
                record = record,
                rtl = state.rtl,
                listState = listState,
                matches = if (searching) matches else emptyList(),
                currentMatch = currentMatch,
                player = state.player,
                onTap = { index ->
                    val player = state.player?.takeUnless { it.state.value.failed }
                    if (player == null) editing = index
                    else {
                        player.seekTo(record.segments[index].start * 10)
                        player.play()
                    }
                },
                onEdit = { editing = it },
                modifier = body,
                // Only a recording recovered after the app died has no model yet; an empty result from a
                // model just means no speech, which "transcribe it now" would not change.
                onTranscribe = vm::transcribeAudio.takeIf { state.player != null && record.modelPath == null },
                transcribing = state.transcribing,
            )
            else -> SummaryPane(
                state = state,
                actions = vm,
                onSetUpAi = { aiSetup = true },
                onCopy = ::copy,
                onShare = ::shareText,
                modifier = body,
            )
        }
    }

    if (record != null) {
        if (renaming) {
            TextInputDialog(
                title = stringResource(R.string.lib_rename),
                label = stringResource(R.string.lib_name_label),
                initial = record.name,
                onConfirm = { name ->
                    if (name != record.name) vm.rename(name)
                    renaming = false
                },
                onDismiss = { renaming = false },
            )
        }
        if (deleting) {
            ConfirmDialog(
                title = stringResource(R.string.lib_delete_title),
                text = stringResource(R.string.lib_delete_body, record.name),
                confirm = stringResource(R.string.lib_delete),
                onConfirm = {
                    deleting = false
                    vm.delete()
                },
                onDismiss = { deleting = false },
            )
        }
        editing?.let { index ->
            record.segments.getOrNull(index)?.let { segment ->
                TextInputDialog(
                    title = stringResource(R.string.transcript_edit_line),
                    label = formatClock(segment.start * 10),
                    initial = segment.text.trim(),
                    singleLine = false,
                    onConfirm = { text ->
                        if (text != segment.text.trim()) vm.editSegment(index, text)
                        editing = null
                    },
                    onDismiss = { editing = null },
                )
            }
        }
        if (exportOpen) {
            ExportSheet(
                hasSummary = record.summary != null,
                hasSpeakers = state.hasSpeakers,
                rtl = state.rtl,
                onShare = vm::share,
                onSave = { options ->
                    vm.prepareSave(options)
                    saveLauncher.launch("${record.name}.${options.format.extension}" to options.format.mime)
                },
                onDismiss = { exportOpen = false },
            )
        }
    }
    if (aiSetup) AiSetupSheet(onDismiss = { aiSetup = false })
}

@Composable
private fun SearchInput(state: TextFieldState, onNext: () -> Unit) {
    val focus = remember { FocusRequester() }
    val placeholder = stringResource(R.string.transcript_search_placeholder)
    BasicTextField(
        state = state,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = placeholder },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, textDirection = TextDirection.Content),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = { onNext() },
        decorator = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (state.text.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                field()
            }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun MatchCounter(count: Int, current: Int, active: Boolean) {
    if (!active) return
    val label = if (count == 0) stringResource(R.string.transcript_no_matches) else stringResource(R.string.transcript_match_of, current + 1, count)
    Text(
        if (count == 0) "0/0" else "${current + 1}/$count",
        Modifier.semantics { contentDescription = label },
        style = MaterialTheme.typography.labelMedium,
        color = if (count == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
