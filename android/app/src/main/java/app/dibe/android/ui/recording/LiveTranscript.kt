package app.dibe.android.ui.recording

import androidx.compose.animation.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.dibe.android.ui.transcript.isRtlText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dibe.android.R
import app.dibe.android.data.Segment
import app.dibe.android.ui.components.ClockFamily
import app.dibe.android.ui.components.EaseOutStrong
import app.dibe.android.ui.components.LocalReducedMotion
import app.dibe.android.ui.components.formatClock
import kotlinx.coroutines.launch

private const val PARTIAL_KEY = "partial"

/**
 * The transcript as it is spoken: settled lines in the text colour, the line still forming in grey,
 * which settles into colour when it becomes final (desktop `live-settle`).
 *
 * Laid out in reverse, newest at index 0: while the reader is at the live edge the list stays
 * pinned to it for free; once they scroll up, the keyed lines hold still and a pill offers the way
 * back.
 */
@Composable
fun LiveTranscript(finals: List<Segment>, partial: String?, emptyText: String, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    // Lines already there when the screen opened don't replay the settle.
    val firstNew = remember { finals.size }
    val forming = partial?.takeIf { it.isNotBlank() }
    // The list keeps its place by the key of the first visible line, so a new line at index 0 would
    // land out of view: while the reader is at the live edge, follow it explicitly.
    var pinned by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (!scrolling) pinned = !listState.canScrollBackward }
    }
    LaunchedEffect(finals.size, forming) { if (pinned) listState.scrollToItem(0) }

    Box(modifier) {
        if (finals.isEmpty() && forming == null) {
            Text(
                emptyText,
                Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true,
            // Short transcripts start at the top like a page, not at the bottom like a chat.
            verticalArrangement = Arrangement.Top,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 72.dp),
        ) {
            if (forming != null) {
                item(key = PARTIAL_KEY) { Line(null, forming, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(count = finals.size, key = { finals.size - 1 - it }) { position ->
                val index = finals.size - 1 - position
                val settle = !reduced && index >= firstNew && index == finals.lastIndex
                FinalLine(finals[index], settle)
            }
        }

        AnimatedVisibility(
            visible = !pinned,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            enter = fadeIn(tween(160)) + slideInVertically(tween(200, easing = EaseOutStrong)) { it / 2 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 2 },
        ) {
            Surface(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.minimumInteractiveComponentSize(),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 4.dp,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.rec_jump_to_live), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun FinalLine(segment: Segment, settle: Boolean) {
    val target = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val color = remember { Animatable(if (settle) muted else target) }
    LaunchedEffect(target) { color.animateTo(target, tween(200, easing = EaseOutStrong)) }
    Line(formatClock(segment.start * 10), segment.text.trim(), color.value)
}

/**
 * A timed line: the time hangs at the start, wrapped text aligns under the words. The line takes the
 * direction of its own words, so Hebrew reads right to left (time on the right) even in an English UI.
 */
@Composable
private fun Line(time: String?, text: String, color: Color) {
    val direction = if (isRtlText(text)) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) { TimedRow(time, text, color) }
}

@Composable
private fun TimedRow(time: String?, text: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            time.orEmpty(),
            Modifier.width(52.dp).padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = ClockFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
