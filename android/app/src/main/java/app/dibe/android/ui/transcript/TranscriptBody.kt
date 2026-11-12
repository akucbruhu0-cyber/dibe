@file:OptIn(ExperimentalFoundationApi::class)

package app.dibe.android.ui.transcript

import app.dibe.android.ui.components.bottomFade
import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dibe.android.R
import app.dibe.android.data.Segment
import app.dibe.android.data.TranscriptRecord
import app.dibe.android.ui.components.ClockFamily
import app.dibe.android.ui.components.LocalReducedMotion
import app.dibe.android.ui.components.formatClock
import app.dibe.android.ui.theme.DibeTheme
import app.dibe.android.ui.theme.Light
import java.time.Instant

/**
 * The transcript as a list of timed lines. A Hebrew transcript lays out right to left whatever the
 * UI language, so the times sit at the start of each line as they do on the desktop.
 */
@Composable
internal fun TranscriptBody(
    record: TranscriptRecord,
    rtl: Boolean,
    listState: LazyListState,
    matches: List<Match>,
    currentMatch: Int,
    player: AudioPlayer?,
    onTap: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Offered on an empty transcript that has audio (a recovered recording). */
    onTranscribe: (() -> Unit)? = null,
    /** 0..100 while that transcription runs. */
    transcribing: Int? = null,
) {
    val segments = record.segments
    val speakerLabel = stringResource(R.string.transcript_speaker)
    val playerState = player?.state?.collectAsStateWithLifecycle()
    // Only a change of line recomposes the list, not every position tick.
    val active by remember(segments, playerState) {
        derivedStateOf {
            val now = playerState?.value ?: return@derivedStateOf -1
            if (now.playing || now.positionMs > 0) activeSegment(segments, now.positionMs / 10) else -1
        }
    }
    val bySegment = remember(matches) { matches.withIndex().groupBy { it.value.segment } }
    val colors = MaterialTheme.colorScheme
    val matchStyle = SpanStyle(background = colors.tertiary.copy(alpha = 0.32f))
    val currentStyle = SpanStyle(background = colors.tertiary, color = Light.Foreground)
    val tapLabel = stringResource(if (player != null) R.string.transcript_play_from_here else R.string.transcript_edit_line)
    val editLabel = stringResource(R.string.transcript_edit_line)

    LaunchedEffect(active) {
        if (active < 0 || playerState?.value?.playing != true || listState.isScrollInProgress) return@LaunchedEffect
        val index = active + 1
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
        val visible = item != null && item.offset >= layout.viewportStartOffset && item.offset + item.size <= layout.viewportEndOffset
        if (!visible) listState.animateScrollToItem(index)
    }

    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxSize().bottomFade(),
                state = listState,
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 80.dp),
            ) {
                item(key = "meta", contentType = "meta") { MetaLine(record, audioMs = playerState?.value?.durationMs?.takeIf { it > 0 }) }
                if (segments.isEmpty()) {
                    item(key = "empty") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(if (onTranscribe != null) R.string.transcript_untranscribed else R.string.transcript_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                            )
                            when {
                                transcribing != null -> LinearProgressIndicator(
                                    progress = { transcribing / 100f },
                                    Modifier.fillMaxWidth(),
                                )
                                onTranscribe != null -> Button(onTranscribe) { Text(stringResource(R.string.transcript_transcribe_now)) }
                            }
                        }
                    }
                }
                itemsIndexed(segments, key = { index, _ -> index }, contentType = { _, _ -> "segment" }) { index, segment ->
                    SegmentRow(
                        segment = segment,
                        speaker = segment.speaker?.let { speakerName(it, speakerLabel, record.speakerNames) },
                        text = highlighted(segment.text.trim(), bySegment[index], currentMatch, matchStyle, currentStyle),
                        active = index == active,
                        tapLabel = tapLabel,
                        editLabel = editLabel,
                        onTap = { onTap(index) },
                        onEdit = { onEdit(index) },
                    )
                }
            }
        }
    }
}

private fun highlighted(
    text: String,
    matches: List<IndexedValue<Match>>?,
    current: Int,
    match: SpanStyle,
    currentMatch: SpanStyle,
): AnnotatedString {
    if (matches.isNullOrEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        matches.forEach { (index, found) -> addStyle(if (index == current) currentMatch else match, found.start, found.end) }
    }
}

@Composable
private fun MetaLine(record: TranscriptRecord, audioMs: Long?) {
    val context = LocalContext.current
    val created = remember(record.createdAt) { runCatching { Instant.parse(record.createdAt).toEpochMilli() }.getOrNull() }
    val parts = listOfNotNull(
        created?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME) },
        // The recording's own length when there is one; the last segment ends at the last speech, not the end.
        audioMs?.let(::formatClock) ?: record.segments.lastOrNull()?.let { formatClock(it.stop * 10) },
    )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SegmentRow(
    segment: Segment,
    speaker: String?,
    text: AnnotatedString,
    active: Boolean,
    tapLabel: String,
    editLabel: String,
    onTap: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (active) colors.primaryContainer else Color.Transparent,
        animationSpec = if (LocalReducedMotion.current) snap() else tween(200),
        label = "activeLine",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .drawBehind { drawRect(background) }
            .combinedClickable(onClick = onTap, onClickLabel = tapLabel, onLongClick = onEdit, onLongClickLabel = editLabel)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatClock(segment.start * 10),
                style = TextStyle(fontFamily = ClockFamily).merge(MaterialTheme.typography.labelMedium),
                color = colors.onSurfaceVariant,
            )
            if (speaker != null) {
                Text(speaker, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = colors.primary)
            }
        }
        Text(text, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
    }
}

@Preview(showBackground = true)
@Composable
private fun TranscriptBodyPreview() {
    DibeTheme {
        val record = TranscriptRecord(
            name = "פגישה",
            sourcePath = "",
            createdAt = "2026-11-09T08:00:00Z",
            segments = listOf(
                Segment(0, 420, "שלום לכולם, נתחיל בתקציב.", speaker = 0),
                Segment(420, 980, "הצעתי לחלק אותו לשלושה רבעונים.", speaker = 1),
                Segment(980, 1500, "מסכים, בוא נסגור את זה היום.", speaker = 0),
            ),
        )
        TranscriptBody(
            record,
            rtl = true,
            listState = rememberLazyListState(),
            matches = findMatches(record.segments.map { it.text }, "תקציב"),
            currentMatch = 0,
            player = null,
            onTap = {},
            onEdit = {},
        )
    }
}
