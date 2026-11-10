package app.dibe.android.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dibe.android.R
import app.dibe.android.app
import app.dibe.android.models.Fit
import app.dibe.android.models.ModelSpec
import app.dibe.android.models.ModelStatus
import app.dibe.android.ui.components.Eyebrow
import app.dibe.android.ui.components.formatSize
import app.dibe.android.ui.components.label

/** The last onboarding step: what this phone has, and the model that fits it. */
@Composable
internal fun DevicePage(
    recommended: ModelSpec,
    fit: Fit,
    status: ModelStatus?,
    onChooseAnother: () -> Unit,
    onSkip: () -> Unit,
) {
    val device = LocalContext.current.app.device
    val (soc, ram, cores) = remember { Triple(device.socName, device.totalRamBytes, device.fastCores) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Spacer(Modifier.height(16.dp))
            Eyebrow(stringResource(R.string.onb_device_eyebrow))
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.onb_device_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.onb_device_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    Fact(Icons.Outlined.DeveloperBoard, stringResource(R.string.onb_device_processor), soc)
                    HorizontalDivider(Modifier.padding(start = 50.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Fact(Icons.Outlined.Memory, stringResource(R.string.onb_device_memory), formatSize(ram))
                    HorizontalDivider(Modifier.padding(start = 50.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Fact(Icons.Outlined.Speed, stringResource(R.string.onb_device_fast_cores), cores.toString())
                }
            }
            Spacer(Modifier.height(16.dp))
            RecommendedModel(recommended, fit, status)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onChooseAnother, Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.onb_choose_another), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onSkip, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.onb_skip_for_now), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Fact(icon: ImageVector, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecommendedModel(spec: ModelSpec, fit: Fit, status: ModelStatus?) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(20.dp).semantics(mergeDescendants = true) {}) {
            Eyebrow(stringResource(R.string.onb_recommended), color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(6.dp))
            Text(spec.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(stringResource(spec.tier.label()))
                Tag(formatSize(spec.sizeBytes))
                if (spec.hebrew) Tag(stringResource(R.string.ui_badge_hebrew))
                if (!spec.live) Tag(stringResource(R.string.ui_badge_files_only))
            }
            Spacer(Modifier.height(14.dp))
            val (icon, tint) = when (fit) {
                Fit.GOOD -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
                Fit.TIGHT -> Icons.Outlined.WarningAmber to MaterialTheme.colorScheme.tertiary
                Fit.TOO_HEAVY -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = tint)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(fit.label()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            val state = when (status) {
                ModelStatus.Installed -> stringResource(R.string.onb_model_installed)
                is ModelStatus.Downloading, is ModelStatus.Waiting, ModelStatus.Verifying -> stringResource(R.string.onb_model_downloading)
                else -> null
            }
            if (state != null) {
                Spacer(Modifier.height(6.dp))
                Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.background.copy(alpha = 0.7f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}
