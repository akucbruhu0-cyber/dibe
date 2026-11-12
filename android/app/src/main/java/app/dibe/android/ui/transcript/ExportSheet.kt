@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.dibe.android.ui.transcript

import app.dibe.android.ui.components.dibeSwitchColors
import app.dibe.android.ui.components.pastelBorder
import app.dibe.android.ui.components.pastelButtonColors
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.dibe.android.R
import app.dibe.android.export.ExportContent
import app.dibe.android.export.ExportFormat
import app.dibe.android.export.ExportOptions
import app.dibe.android.ui.components.pillButtonColors
import app.dibe.android.ui.library.SectionLabel
import app.dibe.android.ui.theme.LocalDibeColors
import kotlinx.coroutines.launch

/** `CreateDocument` with the MIME type chosen at launch time rather than when the launcher is made. */
internal class CreateExportDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

@StringRes
private fun formatDescription(format: ExportFormat): Int = when (format) {
    ExportFormat.TXT -> R.string.export_format_txt
    ExportFormat.SRT -> R.string.export_format_srt
    ExportFormat.VTT -> R.string.export_format_vtt
    ExportFormat.HTML -> R.string.export_format_html
    ExportFormat.PDF -> R.string.export_format_pdf
    ExportFormat.JSON -> R.string.export_format_json
    ExportFormat.DOCX -> R.string.export_format_docx
    ExportFormat.CSV -> R.string.export_format_csv
    ExportFormat.MD -> R.string.export_format_md
}

@StringRes
private fun contentLabel(content: ExportContent): Int = when (content) {
    ExportContent.TRANSCRIPT -> R.string.export_content_transcript
    ExportContent.SUMMARY -> R.string.export_content_summary
    ExportContent.BOTH -> R.string.export_content_both
}

/** Format, content and toggles, then Share or Save. The sheet closes before the export runs. */
@Composable
internal fun ExportSheet(
    hasSummary: Boolean,
    hasSpeakers: Boolean,
    /** The transcript has follow-up questions; their switch shows only then, and only with the summary. */
    hasThread: Boolean,
    rtl: Boolean,
    onShare: (ExportOptions) -> Unit,
    onSave: (ExportOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var format by rememberSaveable { mutableStateOf(ExportFormat.TXT) }
    var content by rememberSaveable { mutableStateOf(ExportContent.TRANSCRIPT) }
    var timestamps by rememberSaveable { mutableStateOf(true) }
    var speakers by rememberSaveable { mutableStateOf(true) }
    var thread by rememberSaveable { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val speakerLabel = stringResource(R.string.transcript_speaker)
    // The two parts' headings in a file that holds both, in the app's language.
    val transcriptLabel = stringResource(R.string.export_content_transcript)
    val summaryLabel = stringResource(R.string.export_content_summary)
    val threadLabel = stringResource(R.string.export_followups)
    val chosen = if (hasSummary) content else ExportContent.TRANSCRIPT
    val subtitles = format == ExportFormat.SRT || format == ExportFormat.VTT

    fun finish(action: (ExportOptions) -> Unit) {
        val options = ExportOptions(
            format, chosen, timestamps || subtitles, speakers, speakerLabel, rtl, transcriptLabel, summaryLabel,
            includeThread = hasThread && thread, threadLabel = threadLabel,
        )
        scope.launch { sheet.hide() }.invokeOnCompletion {
            onDismiss()
            action(options)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleLarge)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.export_format))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.entries.forEach { option ->
                        FilterChip(selected = option == format, onClick = { format = option }, label = { Text(option.name) }, shape = CircleShape, colors = darkPillChip())
                    }
                }
                Text(stringResource(formatDescription(format)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.export_content))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportContent.entries.forEach { option ->
                        FilterChip(
                            selected = option == chosen,
                            onClick = { content = option },
                            enabled = hasSummary || option == ExportContent.TRANSCRIPT,
                            label = { Text(stringResource(contentLabel(option))) },
                            shape = CircleShape,
                            colors = darkPillChip(),
                        )
                    }
                }
                if (!hasSummary) {
                    Text(stringResource(R.string.export_no_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column {
                val transcriptShown = chosen != ExportContent.SUMMARY
                ToggleRow(stringResource(R.string.export_timestamps), timestamps || subtitles, transcriptShown && !subtitles) { timestamps = it }
                if (hasSpeakers) ToggleRow(stringResource(R.string.export_speakers), speakers, transcriptShown) { speakers = it }
                if (hasThread && chosen != ExportContent.TRANSCRIPT) ToggleRow(stringResource(R.string.export_include_followups), thread, true) { thread = it }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { finish(onShare) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = pillButtonColors()) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.lib_share))
                }
                OutlinedButton(onClick = { finish(onSave) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = pastelButtonColors(), border = pastelBorder()) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.export_save))
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = dibeSwitchColors())
    }
}

/** Pocket's active filter: the dark pill. */
@Composable
private fun darkPillChip(): SelectableChipColors = LocalDibeColors.current.let {
    FilterChipDefaults.filterChipColors(selectedContainerColor = it.pillDark, selectedLabelColor = it.onPillDark)
}
