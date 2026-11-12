package app.dibe.android.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dibe.android.R
import app.dibe.android.service.Problem

@StringRes
fun Problem.message(): Int = when (this) {
    Problem.MIC_PERMISSION -> R.string.ui_problem_mic_permission
    Problem.NOTIFICATION_PERMISSION -> R.string.ui_problem_notification_permission
    Problem.PROJECTION_DENIED -> R.string.ui_problem_projection_denied
    Problem.CAPTURE_SILENT -> R.string.ui_problem_capture_silent
    Problem.NO_MODEL -> R.string.ui_problem_no_model
    Problem.LOW_MEMORY -> R.string.ui_problem_low_memory
    Problem.MODEL_NOT_LIVE -> R.string.ui_problem_model_not_live
    Problem.UNSUPPORTED_FORMAT -> R.string.ui_problem_unsupported_format
    Problem.CORRUPT_FILE -> R.string.ui_problem_corrupt_file
    Problem.ENGINE_FAILED -> R.string.ui_problem_engine_failed
    Problem.NO_SPACE -> R.string.ui_problem_no_space
    Problem.CAPTURE_FAILED -> R.string.ui_problem_capture_failed
    Problem.MIC_IN_USE -> R.string.ui_problem_mic_in_use
}

/** The one thing that fixes the problem, when there is one. */
private enum class Fix(@StringRes val label: Int) {
    MODELS(R.string.ui_action_choose_model),
    LIGHTER_MODEL(R.string.ui_action_choose_lighter_model),
    APP_SETTINGS(R.string.ui_action_open_settings),
    STORAGE(R.string.ui_action_free_space),
}

private fun Problem.fix(): Fix? = when (this) {
    Problem.NO_MODEL -> Fix.MODELS
    Problem.LOW_MEMORY -> Fix.LIGHTER_MODEL
    Problem.MIC_PERMISSION, Problem.NOTIFICATION_PERMISSION -> Fix.APP_SETTINGS
    Problem.NO_SPACE -> Fix.STORAGE
    else -> null
}

fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

private fun Context.openStorageSettings() {
    try {
        startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

/**
 * A failure explained in plain words, with the action that fixes it (models, app settings,
 * storage) and a dismiss. [title] names what failed, e.g. the file.
 */
@Composable
fun ProblemCard(
    problem: Problem,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    detail: String? = null,
    onDismiss: (() -> Unit)? = null,
    /** Offered for LOW_MEMORY only: retry without the free-memory check. */
    onRunAnyway: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val fix = problem.fix()
    Surface(
        modifier.fillMaxWidth().softShadow(MaterialTheme.shapes.large).semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        // A white card with a faint red wash: it floats like the others, and still reads as a failure.
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.05f).compositeOver(MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.22f)),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)) {
            Row(Modifier.padding(end = 8.dp)) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(problem.message()), style = MaterialTheme.typography.bodyMedium)
                    if (!detail.isNullOrBlank()) {
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (onDismiss != null) TextButton(onDismiss) { Text(stringResource(R.string.ui_action_dismiss)) }
                if (problem == Problem.LOW_MEMORY && onRunAnyway != null) {
                    TextButton(onRunAnyway) { Text(stringResource(R.string.ui_action_run_anyway)) }
                }
                if (fix != null) {
                    Spacer(Modifier.width(4.dp))
                    Button(
                        colors = pillButtonColors(),
                        onClick = {
                            when (fix) {
                                Fix.MODELS, Fix.LIGHTER_MODEL -> onOpenModels()
                                Fix.APP_SETTINGS -> context.openAppSettings()
                                Fix.STORAGE -> context.openStorageSettings()
                            }
                        },
                    ) { Text(stringResource(fix.label)) }
                }
            }
        }
    }
}

/** Not an alert: something to know while the work goes on (desktop PausedBanner). */
@Composable
fun NoticeBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
