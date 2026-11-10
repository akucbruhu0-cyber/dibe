package app.dibe.android.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.dibe.android.R
import app.dibe.android.data.Segment
import app.dibe.android.models.ModelStatus
import app.dibe.android.service.FileJobState
import app.dibe.android.service.FilePhase
import app.dibe.android.service.Problem
import app.dibe.android.ui.components.ProblemCard
import app.dibe.android.ui.components.label
import app.dibe.android.ui.theme.DibeTheme

/** Thin, pill-capped progress (the desktop's h-1.5 bar); indeterminate while [progress] is null. */
@Composable
private fun ThinProgress(progress: Float?, modifier: Modifier = Modifier) {
    val bar = modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
    val track = MaterialTheme.colorScheme.surfaceVariant
    if (progress == null) {
        LinearProgressIndicator(bar, trackColor = track, gapSize = 0.dp)
    } else {
        LinearProgressIndicator({ progress }, bar, trackColor = track, gapSize = 0.dp, drawStopIndicator = {})
    }
}

/** The active model: a quiet chip when ready, a call to action when there is none. */
@Composable
fun ModelCard(model: ModelState, onOpenModels: () -> Unit, modifier: Modifier = Modifier) {
    when (model) {
        is ModelState.Ready -> Surface(
            onClick = onOpenModels,
            modifier = modifier.heightIn(min = 48.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.GraphicEq, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(model.spec.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    " · " + stringResource(model.spec.tier.label()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is ModelState.Fetching -> Surface(
            onClick = onOpenModels,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                val status = model.status
                val (line, progress) = when (status) {
                    is ModelStatus.Downloading -> {
                        val percent = status.progress?.let { " · ${(it * 100).toInt()}%" }.orEmpty()
                        stringResource(R.string.home_model_downloading) + percent to status.progress
                    }
                    is ModelStatus.Waiting -> stringResource(R.string.home_model_waiting) to null
                    else -> stringResource(R.string.home_model_verifying) to null
                }
                Text(model.spec.name, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                ThinProgress(progress)
            }
        }
        is ModelState.Failed, ModelState.None -> Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(if (model is ModelState.Failed) R.string.home_model_failed_title else R.string.home_no_model_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(if (model is ModelState.Failed) R.string.home_model_failed_body else R.string.home_no_model_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(12.dp))
                Button(onOpenModels, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_action_choose_model)) }
            }
        }
    }
}

/** A file being transcribed: progress, the newest lines as they land, then done or what failed. */
@Composable
fun FileJobCard(
    job: FileJobState,
    onCancel: () -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
    onRunAnyway: (() -> Unit)? = null,
) {
    if (job.phase == FilePhase.FAILED) {
        ProblemCard(
            problem = job.error ?: Problem.ENGINE_FAILED,
            onOpenModels = onOpenModels,
            modifier = modifier,
            title = job.name,
            detail = job.errorDetail,
            onDismiss = onDismiss,
            onRunAnyway = onRunAnyway,
        )
        return
    }
    val done = job.phase == FilePhase.DONE
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)) {
            Row(Modifier.padding(end = 8.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {}
                    Icon(
                        if (done) Icons.Outlined.CheckCircle else Icons.Outlined.AudioFile,
                        null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = when (job.phase) {
                        FilePhase.DECODING -> stringResource(R.string.home_file_decoding)
                        FilePhase.DONE -> stringResource(R.string.home_file_done)
                        else -> stringResource(R.string.home_file_transcribing, job.progress)
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!done) {
                Spacer(Modifier.height(14.dp))
                ThinProgress(if (job.phase == FilePhase.DECODING) null else job.progress / 100f, Modifier.padding(end = 8.dp))
                val preview = job.segments.takeLast(2).joinToString(" ") { it.text.trim() }
                if (preview.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    AnimatedContent(preview, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "preview") {
                        Text(
                            it,
                            Modifier.padding(end = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (done) {
                    TextButton(onDismiss) { Text(stringResource(R.string.ui_action_dismiss)) }
                    job.savedId?.let { id -> Button({ onOpen(id) }) { Text(stringResource(R.string.home_file_open)) } }
                } else {
                    TextButton(onCancel) { Text(stringResource(R.string.home_file_cancel)) }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FileJobCardPreview() {
    DibeTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FileJobCard(
                FileJobState("Interview.m4a", FilePhase.TRANSCRIBING, 42, listOf(Segment(0, 300, "So the first thing we noticed was the silence."))),
                {}, {}, {}, {},
            )
            FileJobCard(FileJobState("Lecture.mp4", FilePhase.FAILED, error = Problem.LOW_MEMORY), {}, {}, {}, {})
            ModelCard(ModelState.None, {})
        }
    }
}
