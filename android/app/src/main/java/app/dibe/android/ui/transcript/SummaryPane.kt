@file:OptIn(ExperimentalLayoutApi::class)

package app.dibe.android.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.dibe.android.R
import app.dibe.android.ai.AiPresets
import app.dibe.android.data.AiThreadEntry
import app.dibe.android.data.Segment
import app.dibe.android.data.TranscriptRecord
import app.dibe.android.ui.library.SectionLabel
import app.dibe.android.ui.settings.presetLabel
import app.dibe.android.ui.theme.DibeTheme

@Stable
interface SummaryActions {
    fun summarize(preset: String) {}
    fun cancelSummary() {}
    fun ask(question: String) {}
    fun removeQuestion(index: Int) {}
}

/** The Summary tab: the summary, then the questions asked about this transcript with their answers. */
@Composable
internal fun SummaryPane(
    state: TranscriptUiState,
    actions: SummaryActions,
    onSetUpAi: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val record = state.record ?: return
    val scroll = rememberScrollState()
    var preset by rememberSaveable(state.aiPreset) { mutableStateOf(state.aiPreset) }
    val thread = record.thread.orEmpty()
    val canSummarize = state.aiReady && record.segments.isNotEmpty()

    // Follow the conversation the way a chat does: the newest answer comes into view.
    LaunchedEffect(thread.size, state.asking) {
        if (state.asking != null || thread.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }

    Box(modifier.fillMaxSize().verticalScroll(scroll), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!state.aiReady) SetupCard(onSetUpAi)

            when {
                state.summarizing -> Row(
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.summary_loading), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = actions::cancelSummary) { Text(stringResource(R.string.lib_cancel)) }
                }
                state.summaryError != null -> ErrorLine(stringResource(state.summaryError)) { actions.summarize(preset) }
            }

            val summary = record.summary
            if (summary != null) {
                SectionLabel(stringResource(R.string.summary_title))
                SelectionContainer { MarkdownText(summary) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onCopy(summary) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.lib_copy))
                    }
                    IconButton(onClick = { onShare(summary) }) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.lib_share))
                    }
                    if (canSummarize && !state.summarizing) {
                        PresetPicker(preset, state.aiPreset == AiPresets.CUSTOM) { preset = it }
                        TextButton(onClick = { actions.summarize(preset) }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.summary_again))
                        }
                    }
                }
            } else if (canSummarize && !state.summarizing) {
                SummaryIntro(preset, state.aiPreset == AiPresets.CUSTOM, onPreset = { preset = it }) { actions.summarize(preset) }
            } else if (state.aiReady && record.segments.isEmpty()) {
                Text(stringResource(R.string.summary_nothing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (thread.isNotEmpty() || state.asking != null || state.askError != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SectionLabel(stringResource(R.string.summary_questions))
                thread.forEachIndexed { index, entry ->
                    QuestionBubble(entry.question) { actions.removeQuestion(index) }
                    SelectionContainer { MarkdownText(entry.answer, Modifier.padding(start = 4.dp)) }
                }
                state.asking?.let { question ->
                    QuestionBubble(question, onRemove = null)
                    Row(
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.summary_thinking), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.askError?.let { error ->
                    state.failedQuestion?.let { QuestionBubble(it, onRemove = null) }
                    ErrorLine(stringResource(error)) { state.failedQuestion?.let(actions::ask) }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(onSetUpAi: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(stringResource(R.string.summary_setup_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(stringResource(R.string.summary_setup_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onSetUpAi, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.summary_setup_action)) }
        }
    }
}

@Composable
private fun SummaryIntro(preset: String, hasCustom: Boolean, onPreset: (String) -> Unit, onSummarize: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(stringResource(R.string.summary_intro_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.summary_intro_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            PresetPicker(preset, hasCustom, onPreset)
            Button(onClick = onSummarize) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.summary_summarize))
            }
        }
    }
}

@Composable
private fun PresetPicker(selected: String, hasCustom: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = if (hasCustom) listOf(AiPresets.CUSTOM) + AiPresets.summary.keys else AiPresets.summary.keys.toList()
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(stringResource(presetLabel(selected))) },
            leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.settings_ai_style), modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.medium) {
            options.forEach { id ->
                DropdownMenuItem(
                    text = { Text(stringResource(presetLabel(id))) },
                    trailingIcon = if (id == selected) ({ Icon(Icons.Outlined.Check, contentDescription = null) }) else null,
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun QuestionBubble(question: String, onRemove: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            question,
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
        )
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.summary_remove_question),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ErrorLine(message: String, onRetry: () -> Unit) {
    Row(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.lib_retry)) }
    }
}

/** Floats under the Summary tab like a chat box, above the keyboard. */
@Composable
internal fun AskBar(asking: Boolean, onAsk: (String) -> Unit, modifier: Modifier = Modifier) {
    val question = rememberTextFieldState()
    val canSend = question.text.isNotBlank() && !asking
    val send = {
        if (canSend) {
            onAsk(question.text.toString())
            question.clearText()
        }
    }
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextField(
                        state = question,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.summary_ask_placeholder)) },
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        onKeyboardAction = { send() },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    FilledIconButton(onClick = send, enabled = canSend, modifier = Modifier.padding(bottom = 4.dp).size(48.dp)) {
                        if (asking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.summary_ask_send))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SummaryPanePreview() {
    DibeTheme {
        SummaryPane(
            TranscriptUiState(
                loading = false,
                aiReady = true,
                record = TranscriptRecord(
                    name = "Weekly sync",
                    sourcePath = "",
                    createdAt = "2026-11-09T08:00:00Z",
                    segments = listOf(Segment(0, 300, "Hello")),
                    summary = "## Overview\nThe team agreed on **the budget**.\n\n- Ship v1\n- [ ] Book the venue",
                    thread = listOf(AiThreadEntry("What was decided?", "To ship v1 on *Monday*.")),
                ),
            ),
            object : SummaryActions {},
            {}, {}, {},
        )
    }
}
