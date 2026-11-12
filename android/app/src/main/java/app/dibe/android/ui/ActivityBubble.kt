package app.dibe.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dibe.android.R
import app.dibe.android.app
import app.dibe.android.service.FileJobState
import app.dibe.android.service.FilePhase
import app.dibe.android.service.RecordingPhase
import app.dibe.android.service.RecordingState
import app.dibe.android.ui.components.ClockFamily
import app.dibe.android.ui.components.EaseOutStrong
import app.dibe.android.ui.components.LevelMeter
import app.dibe.android.ui.components.LocalReducedMotion
import app.dibe.android.ui.components.PulseDot
import app.dibe.android.ui.components.formatClock
import app.dibe.android.ui.components.GlowBehind
import app.dibe.android.ui.components.glass
import app.dibe.android.ui.components.pillIconButtonColors
import app.dibe.android.ui.theme.Brand
import app.dibe.android.ui.theme.DibeTheme
import kotlinx.coroutines.launch

private enum class BubbleKind { RECORDING, FILE }

/** Above this horizontal speed (px/s) a release flings to that side, wherever the bubble is. */
private const val FLING_VELOCITY = 800f

/**
 * In-app only: a live recording (or a running file job) stays one tap away on every other screen.
 * Drag it anywhere; it settles on the nearest side with a spring that keeps the fling's momentum.
 */
@Composable
fun ActivityBubble(
    route: String?,
    /** Space kept free at the bottom for the screen's own bars (navigation bar, player). */
    bottomReserve: Dp,
    onOpenRecording: () -> Unit,
    onOpenTranscript: (String) -> Unit,
    onOpenHome: () -> Unit,
) {
    val sessions = LocalContext.current.app.sessions
    val recording by sessions.recording.collectAsStateWithLifecycle()
    val job by sessions.fileJob.collectAsStateWithLifecycle()
    val hiddenHere = route == null || route == Routes.ONBOARDING
    val kind = when {
        hiddenHere -> null
        recording.phase != RecordingPhase.IDLE && route != Routes.RECORDING -> BubbleKind.RECORDING
        // Home shows the job's own card.
        job != null && job?.phase != FilePhase.CANCELLED && route != Routes.HOME -> BubbleKind.FILE
        else -> null
    }
    // Keeps the content while the bubble animates out. Not state: `kind` already drives recomposition.
    val last = remember { arrayOf(BubbleKind.RECORDING) }
    if (kind != null) last[0] = kind

    DraggableBubble(visible = kind != null, bottomReserve = bottomReserve) {
        when (kind ?: last[0]) {
            BubbleKind.RECORDING -> RecordingPill(recording, onOpen = onOpenRecording, onStop = sessions::stop)
            BubbleKind.FILE -> job?.let { current ->
                FilePill(current) {
                    val id = current.savedId
                    if (current.phase == FilePhase.DONE && id != null) onOpenTranscript(id) else onOpenHome()
                }
            }
        }
    }
}

@Composable
private fun DraggableBubble(visible: Boolean, bottomReserve: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val insets = WindowInsets.safeDrawing
    // Physical sides: starts on the reading end (right in English, left in Hebrew).
    val startRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    var atRight by rememberSaveable { mutableStateOf(startRight) }
    var yFraction by rememberSaveable { mutableFloatStateOf(1f) }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        val margin = with(density) { 12.dp.toPx() }
        val bar = with(density) { bottomReserve.toPx() }
        val width = constraints.maxWidth.toFloat()
        val top = insets.getTop(density) + margin
        val bottom = constraints.maxHeight - insets.getBottom(density) - bar - margin
        var size by remember { mutableStateOf(IntSize.Zero) }
        val pos = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        var placed by remember { mutableStateOf(false) }
        var dragging by remember { mutableStateOf(false) }

        fun maxY() = (bottom - size.height).coerceAtLeast(top)
        fun rest() = Offset(if (atRight) width - size.width - margin else margin, top + (maxY() - top) * yFraction)

        LaunchedEffect(size, width, top, bottom) {
            if (size == IntSize.Zero || dragging) return@LaunchedEffect
            if (placed && !reduced) pos.animateTo(rest(), spring(stiffness = Spring.StiffnessMediumLow)) else pos.snapTo(rest())
            placed = true
        }

        fun settle(velocity: Velocity) {
            dragging = false
            val here = pos.value
            atRight = when {
                velocity.x > FLING_VELOCITY -> true
                velocity.x < -FLING_VELOCITY -> false
                else -> here.x + size.width / 2 > width / 2
            }
            // A little of the vertical fling carries through, as if thrown.
            val y = (here.y + velocity.y * 0.08f).coerceIn(top, maxY())
            yFraction = if (maxY() > top) (y - top) / (maxY() - top) else 1f
            scope.launch {
                if (reduced) {
                    pos.snapTo(rest())
                } else {
                    pos.animateTo(rest(), spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), Offset(velocity.x, velocity.y))
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)) + scaleIn(tween(220, easing = EaseOutStrong), initialScale = 0.9f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.9f),
            modifier = Modifier
                .absoluteOffset { pos.value.round() }
                .onSizeChanged { if (it != IntSize.Zero) size = it }
                .pointerInput(width, top, bottom, size) {
                    val tracker = VelocityTracker()
                    var current = Offset.Zero
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            tracker.resetTracking()
                            current = pos.value
                        },
                        onDragEnd = { settle(tracker.calculateVelocity()) },
                        onDragCancel = { settle(Velocity.Zero) },
                    ) { change, amount ->
                        change.consume()
                        current = Offset(
                            (current.x + amount.x).coerceIn(0f, width - size.width),
                            (current.y + amount.y).coerceIn(top - margin, maxY() + margin),
                        )
                        // Tracked in screen space: the bubble moves with the finger, its local position doesn't.
                        tracker.addPosition(change.uptimeMillis, current)
                        scope.launch { pos.snapTo(current) }
                    }
                },
        ) { content() }
    }
}

@Composable
private fun BubbleSurface(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    // Floats over any screen's content: the denser glass, since nothing blurs what is behind it.
    Surface(
        onClick = onClick,
        modifier = Modifier.glass(CircleShape, strong = true).semantics { contentDescription = description },
        shape = CircleShape,
        color = Color.Transparent,
        content = content,
    )
}

@Composable
private fun RecordingPill(state: RecordingState, onOpen: () -> Unit, onStop: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val paused = state.phase == RecordingPhase.PAUSED
    val time = formatClock(state.elapsedMs)
    val description = stringResource(if (paused) R.string.bubble_paused_desc else R.string.bubble_recording_desc, time)
    GlowBehind(active = !paused) {
        BubbleSurface(description, onOpen) {
            Row(Modifier.height(56.dp).padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(if (paused) MaterialTheme.colorScheme.onSurfaceVariant else Brand.Green, pulsing = !paused, size = 10.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(time, fontFamily = ClockFamily, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(10.dp))
                    LevelMeter(if (paused) 0f else state.level, barWidth = 2.dp, gap = 2.dp, minHeight = 4.dp, maxHeight = 16.dp)
                }
                Spacer(Modifier.width(4.dp))
                if (state.phase == RecordingPhase.FINISHING) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onStop()
                        },
                        enabled = state.phase != RecordingPhase.STARTING,
                        colors = pillIconButtonColors(),
                    ) { Icon(Icons.Rounded.Stop, stringResource(R.string.rec_stop)) }
                }
            }
        }
    }
}

@Composable
private fun FilePill(job: FileJobState, onClick: () -> Unit) {
    val (label, description) = when (job.phase) {
        FilePhase.DONE -> stringResource(R.string.bubble_file_ready) to stringResource(R.string.bubble_file_ready_desc, job.name)
        FilePhase.FAILED -> stringResource(R.string.bubble_file_failed) to stringResource(R.string.bubble_file_failed_desc, job.name)
        else -> "${job.progress}%" to stringResource(R.string.bubble_file_running_desc, job.name, job.progress)
    }
    BubbleSurface(description, onClick) {
        Row(
            Modifier.height(56.dp).padding(start = 12.dp, end = 18.dp).clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                when (job.phase) {
                    FilePhase.DONE -> Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                    FilePhase.FAILED -> Icon(Icons.Rounded.PriorityHigh, null, tint = MaterialTheme.colorScheme.error)
                    FilePhase.DECODING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    else -> CircularProgressIndicator(
                        progress = { job.progress / 100f },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontFamily = if (job.phase == FilePhase.TRANSCRIBING) ClockFamily else null)
        }
    }
}

@Preview
@Composable
private fun BubblePreview() {
    DibeTheme {
        androidx.compose.foundation.layout.Column {
            RecordingPill(RecordingState(phase = RecordingPhase.RECORDING, elapsedMs = 192_000, level = 0.6f), {}, {})
            Spacer(Modifier.height(12.dp))
            FilePill(FileJobState(name = "Interview.m4a", phase = FilePhase.TRANSCRIBING, progress = 42)) {}
        }
    }
}
