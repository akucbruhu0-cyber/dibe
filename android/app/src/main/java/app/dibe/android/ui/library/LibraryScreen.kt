@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package app.dibe.android.ui.library

import app.dibe.android.ui.components.bottomFade
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
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
import app.dibe.android.data.TranscriptEntry
import app.dibe.android.data.TranscriptStore
import app.dibe.android.service.Sessions
import app.dibe.android.ui.components.hairlineBorder
import app.dibe.android.ui.components.shellBarColors
import app.dibe.android.ui.components.softShadow
import app.dibe.android.ui.theme.DibeTheme
import app.dibe.android.ui.theme.LocalDibeColors
import app.dibe.android.ui.transcript.isFilling
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val loaded: Boolean = false,
    val total: Int = 0,
    val groups: List<Pair<DayGroup, List<TranscriptEntry>>> = emptyList(),
    val refreshing: Boolean = false,
)

class LibraryViewModel(private val store: TranscriptStore, private val sessions: Sessions) : ViewModel() {
    val query = TextFieldState()
    private val refreshing = MutableStateFlow(false)
    private val loaded = MutableStateFlow(false)
    private val _errors = Channel<Int>(Channel.BUFFERED)
    val errors = _errors.receiveAsFlow()

    val uiState: StateFlow<LibraryUiState> = combine(
        store.entries,
        snapshotFlow { query.text.toString().trim() },
        refreshing,
        loaded,
    ) { entries, needle, refreshing, loaded ->
        val zone = ZoneId.systemDefault()
        val shown = if (needle.isEmpty()) entries else entries.filter { it.name.contains(needle, ignoreCase = true) }
        LibraryUiState(loaded, entries.size, groupByDay(shown, LocalDate.now(zone), zone), refreshing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        viewModelScope.launch {
            attempt(R.string.history_refresh_failed) { store.refresh() }
            loaded.value = true
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            attempt(R.string.history_refresh_failed) { store.refresh() }
            refreshing.value = false
        }
    }

    /** False, with the reason shown, while [id]'s own audio is transcribed into it: the job saves into that id. */
    fun canModify(id: String): Boolean {
        if (!sessions.isFilling(id)) return true
        _errors.trySend(R.string.transcript_busy_transcribing)
        return false
    }

    fun rename(id: String, name: String) {
        if (!canModify(id)) return
        viewModelScope.launch { attempt(R.string.history_rename_failed) { store.rename(id, name) } }
    }

    fun delete(id: String) {
        if (!canModify(id)) return
        viewModelScope.launch { attempt(R.string.history_delete_failed) { store.delete(id) } }
    }

    private suspend fun attempt(@StringRes error: Int, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _errors.send(error)
        }
    }
}

/** History tab: saved transcripts, newest first, with search, rename and delete. */
@Composable
fun LibraryScreen(onOpen: (id: String) -> Unit) {
    val app = LocalContext.current.app
    val vm = viewModel { LibraryViewModel(app.store, app.sessions) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.errors.collect { snackbar.showSnackbar(context.getString(it)) }
        }
    }
    LibraryContent(state, vm.query, snackbar, onOpen, vm::canModify, vm::rename, vm::delete, vm::refresh)
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    query: TextFieldState,
    snackbar: SnackbarHostState,
    onOpen: (String) -> Unit,
    canModify: (String) -> Boolean,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val all = state.groups.flatMap { it.second }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.history_title)) }, colors = shellBarColors(), scrollBehavior = scroll) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.total > 0) SearchField(query, Modifier.widthIn(max = 720.dp).padding(horizontal = 16.dp, vertical = 4.dp))
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                when {
                    !state.loaded -> Unit
                    state.total == 0 -> EmptyState(Icons.Outlined.History, R.string.history_empty_title, stringResource(R.string.history_empty_body))
                    all.isEmpty() -> EmptyState(
                        Icons.Outlined.SearchOff,
                        R.string.history_no_matches_title,
                        stringResource(R.string.history_no_matches_body, query.text.toString().trim()),
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxSize().align(Alignment.TopCenter).widthIn(max = 720.dp).bottomFade(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.groups.forEach { (group, entries) ->
                            item(key = "group-$group", contentType = "header") {
                                SectionLabel(
                                    stringResource(group.label),
                                    Modifier.animateItem().padding(start = 8.dp, end = 8.dp, top = 12.dp),
                                )
                            }
                            items(entries, key = { it.id }, contentType = { "entry" }) { entry ->
                                EntryRow(
                                    entry,
                                    group,
                                    onOpen = { onOpen(entry.id) },
                                    onRename = { if (canModify(entry.id)) renaming = entry.id },
                                    onDelete = { if (canModify(entry.id)) deleting = entry.id },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    all.find { it.id == renaming }?.let { entry ->
        TextInputDialog(
            title = stringResource(R.string.lib_rename),
            label = stringResource(R.string.lib_name_label),
            initial = entry.name,
            onConfirm = { name ->
                if (name != entry.name) onRename(entry.id, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    all.find { it.id == deleting }?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.lib_delete_title),
            text = stringResource(R.string.lib_delete_body, entry.name),
            confirm = stringResource(R.string.lib_delete),
            onConfirm = {
                onDelete(entry.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

private val DayGroup.label: Int
    get() = when (this) {
        DayGroup.TODAY -> R.string.history_today
        DayGroup.YESTERDAY -> R.string.history_yesterday
        DayGroup.EARLIER -> R.string.history_earlier
    }

@Composable
private fun SearchField(state: TextFieldState, modifier: Modifier = Modifier) {
    TextField(
        state = state,
        modifier = modifier.fillMaxWidth().softShadow(CircleShape, 3.dp),
        placeholder = { Text(stringResource(R.string.history_search)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (state.text.isNotEmpty()) {
                IconButton(onClick = { state.clearText() }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.lib_clear_search))
                }
            }
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun EntryRow(
    entry: TranscriptEntry,
    group: DayGroup,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .softShadow(MaterialTheme.shapes.medium, 3.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surface)
                .border(hairlineBorder(), MaterialTheme.shapes.medium)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menu = true },
                    onLongClickLabel = stringResource(R.string.lib_more_actions),
                )
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(thumbBrush(entry.id)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = thumbIconTint(), modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
                )
                Text(
                    entryDate(entry.createdAtMillis, group),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = { menu = true }) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.lib_more_actions_for, entry.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.medium) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_rename)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = { menu = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_delete)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                colors = MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error,
                ),
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

/**
 * Pocket's per-recording thumb: three glow hues, picked and angled by the id, the same every time.
 * The hues are washed 75% into the card so the icon on top keeps >= 6:1 in both themes (the raw glow
 * hues are too saturated to carry text or an icon).
 */
@Composable
private fun thumbBrush(id: String): Brush {
    val card = MaterialTheme.colorScheme.surface
    val hues = LocalDibeColors.current.glow.take(4).map { lerp(it, card, 0.75f) }
    val h = id.hashCode()
    val a = Math.floorMod(h, 4)
    val b = (a + 1 + Math.floorMod(h shr 2, 3)) % 4
    val c = (b + 1 + Math.floorMod(h shr 4, 2)) % 4
    val flip = (h shr 6) and 1 == 1
    return Brush.linearGradient(
        listOf(hues[a], hues[b], hues[c]),
        start = if (flip) Offset(Float.POSITIVE_INFINITY, 0f) else Offset.Zero,
        end = if (flip) Offset(0f, Float.POSITIVE_INFINITY) else Offset.Infinite,
    )
}

/** Dark green on the washed thumb in light mode, the light foreground on it in dark mode. */
@Composable
private fun thumbIconTint(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

/** Today: "12 minutes ago"; yesterday: the time; earlier: date and time, in the phone's own format. */
@Composable
private fun entryDate(millis: Long, group: DayGroup): String {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    return when (group) {
        DayGroup.TODAY ->
            if (now - millis < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.history_just_now)
            else DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()
        DayGroup.YESTERDAY -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
        DayGroup.EARLIER -> DateUtils.formatDateTime(
            context,
            millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
        )
    }
}

@Composable
internal fun EmptyState(icon: ImageVector, @StringRes title: Int, body: String, modifier: Modifier = Modifier) {
    // Scrollable so pull-to-refresh still works on an empty list.
    LazyColumn(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        item {
            Column(Modifier.widthIn(max = 360.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                }
                Text(
                    stringResource(title),
                    Modifier.padding(top = 20.dp),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    body,
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val previewEntries = System.currentTimeMillis().let { now ->
    listOf(
        TranscriptEntry("a", "Weekly sync", now - 25 * 60_000),
        TranscriptEntry("b", "שיחה עם דנה", now - 3 * 3_600_000),
        TranscriptEntry("c", "Lecture 4 — Thermodynamics", now - 26 * 3_600_000),
        TranscriptEntry("d", "Podcast notes", now - 9 * 86_400_000),
    )
}

@Preview
@Composable
private fun LibraryPreview() {
    DibeTheme {
        val zone = ZoneId.systemDefault()
        LibraryContent(
            LibraryUiState(true, 4, groupByDay(previewEntries, LocalDate.now(zone), zone)),
            TextFieldState(),
            SnackbarHostState(),
            {}, { true }, { _, _ -> }, {}, {},
        )
    }
}

@Preview
@Composable
private fun LibraryEmptyPreview() {
    DibeTheme(dark = true) {
        LibraryContent(LibraryUiState(loaded = true), TextFieldState(), SnackbarHostState(), {}, { true }, { _, _ -> }, {}, {})
    }
}
