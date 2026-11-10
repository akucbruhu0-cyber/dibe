package app.dibe.android.ui.recording

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import app.dibe.android.R
import app.dibe.android.app
import app.dibe.android.data.Segment
import app.dibe.android.service.Problem
import app.dibe.android.service.RecordingPhase
import app.dibe.android.service.RecordingState
import app.dibe.android.ui.components.ClockFamily
import app.dibe.android.ui.components.LevelMeter
import app.dibe.android.ui.components.NoticeBanner
import app.dibe.android.ui.components.ProblemCard
import app.dibe.android.ui.components.PulseDot
import app.dibe.android.ui.components.formatClock
import app.dibe.android.ui.components.message
import app.dibe.android.ui.theme.Brand
import app.dibe.android.ui.theme.DibeTheme

/**
 * The live recording. Leaving it keeps the recording going (the bubble follows the user around);
 * the saved transcript opens on its own once the recording is stopped (see AppRoot).
 */
@Composable
fun RecordingScreen(onBack: () -> Unit, onOpenModels: () -> Unit) {
    val sessions = LocalContext.current.app.sessions
    val state by sessions.recording.collectAsStateWithLifecycle()
    // Re-checked once resumed: `onBack` ignores a screen still animating in or out.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Nothing running and nothing to report (a stale route, a discarded take): nothing to show.
    LaunchedEffect(state.phase, state.error, state.savedId, lifecycleState) {
        if (state.phase == RecordingPhase.IDLE && state.error == null && state.savedId == null) onBack()
    }
    RecordingContent(
        state = state,
        onBack = onBack,
        onPauseResume = { if (state.phase == RecordingPhase.PAUSED) sessions.resume() else sessions.pause() },
        onStop = sessions::stop,
        onOpenModels = onOpenModels,
        onClose = {
            sessions.clearResults()
            onBack()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingContent(
    state: RecordingState,
    onBack: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onOpenModels: () -> Unit,
    onClose: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val paused = state.phase == RecordingPhase.PAUSED
    val finishing = state.phase == RecordingPhase.FINISHING
    val running = state.phase == RecordingPhase.RECORDING || paused
    val failed = state.phase == RecordingPhase.IDLE && state.error != null

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { StatusLine(state.phase) },
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.rec_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (!failed) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onPauseResume,
                        enabled = running,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (paused) R.string.rec_resume else R.string.rec_pause))
                    }
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onStop()
                        },
                        enabled = running,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        if (finishing) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Stop, null, Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (finishing) R.string.rec_finishing else R.string.rec_stop))
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
                    OutlinedButton(onClose, Modifier.fillMaxWidth().height(56.dp), shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(R.string.rec_close))
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp).alpha(if (paused) 0.5f else 1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LevelMeter(if (running && !paused) state.level else 0f, maxHeight = 26.dp)
                Spacer(Modifier.width(14.dp))
                Text(formatClock(state.elapsedMs), style = MaterialTheme.typography.displaySmall, fontFamily = ClockFamily)
            }
            val warning = state.warning
            AnimatedVisibility(warning != null && !failed) {
                if (warning != null) NoticeBanner(stringResource(warning.message()), Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp))
            }
            if (failed) {
                ProblemCard(state.error, onOpenModels, Modifier.padding(horizontal = 20.dp), detail = state.errorDetail)
            } else {
                val empty = stringResource(
                    when {
                        state.warning == Problem.MODEL_NOT_LIVE -> R.string.rec_empty_after_stop
                        state.phase == RecordingPhase.STARTING -> R.string.rec_empty_starting
                        else -> R.string.rec_empty_listening
                    },
                )
                LiveTranscript(state.finals, state.partial, empty, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** The screen's one live region: the state in words, beside a dot that never carries it alone. */
@Composable
private fun StatusLine(phase: RecordingPhase) {
    val text = stringResource(
        when (phase) {
            RecordingPhase.STARTING -> R.string.rec_status_starting
            RecordingPhase.PAUSED -> R.string.rec_status_paused
            RecordingPhase.FINISHING -> R.string.rec_status_finishing
            RecordingPhase.IDLE -> R.string.rec_status_stopped
            RecordingPhase.RECORDING -> R.string.rec_status_recording
        },
    )
    Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        PulseDot(
            if (phase == RecordingPhase.RECORDING) Brand.Green else MaterialTheme.colorScheme.onSurfaceVariant,
            pulsing = phase == RecordingPhase.RECORDING,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Preview(showBackground = true, heightDp = 760)
@Composable
private fun RecordingPreview() {
    DibeTheme {
        RecordingContent(
            RecordingState(
                phase = RecordingPhase.RECORDING,
                elapsedMs = 83_000,
                level = 0.5f,
                finals = listOf(Segment(0, 420, "Thanks everyone for joining today."), Segment(420, 910, "Let's start with the roadmap.")),
                partial = "The first thing on the list is",
                warning = Problem.CAPTURE_SILENT,
            ),
            {}, {}, {}, {}, {},
        )
    }
}
