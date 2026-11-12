@file:OptIn(ExperimentalMaterial3Api::class)

package app.dibe.android.ui.settings

import app.dibe.android.ui.components.pastelBorder
import app.dibe.android.ui.components.pastelButtonColors
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dibe.android.R
import app.dibe.android.ai.AiClient
import app.dibe.android.ai.AiException
import app.dibe.android.ai.AiModel
import app.dibe.android.ai.AiModels
import app.dibe.android.ai.AiPlatform
import app.dibe.android.ai.AiPresets
import app.dibe.android.ai.AiSettings
import app.dibe.android.ai.defaultModel
import app.dibe.android.ai.summaryPrompt
import app.dibe.android.app
import app.dibe.android.settings.SettingsStore
import app.dibe.android.ui.components.PillSegmented
import app.dibe.android.ui.theme.DibeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Ready to summarize: the chosen platform has what it needs to be called. */
fun AiSettings.isReady(): Boolean = when (platform) {
    AiPlatform.CLAUDE -> claudeApiKey.isNotBlank()
    AiPlatform.GEMINI -> geminiApiKey.isNotBlank()
    // A local OpenAI-compatible server may take no key; the default URL always needs one.
    AiPlatform.OPENAI -> openaiApiKey.isNotBlank() || openaiBaseUrl.trim().trimEnd('/').ifEmpty { AiSettings().openaiBaseUrl } != AiSettings().openaiBaseUrl
    AiPlatform.OLLAMA -> ollamaBaseUrl.isNotBlank()
}

@StringRes
internal fun presetLabel(id: String): Int = when (id) {
    "summary" -> R.string.settings_ai_preset_summary
    "meeting-notes" -> R.string.settings_ai_preset_meeting_notes
    "tldr" -> R.string.settings_ai_preset_tldr
    "translate" -> R.string.settings_ai_preset_translate
    "article" -> R.string.settings_ai_preset_article
    else -> R.string.settings_ai_preset_custom
}

/** A friendly line for each failure; null is a failure the client did not classify. */
@StringRes
internal fun aiErrorText(kind: AiException.Kind?): Int = when (kind) {
    AiException.Kind.NO_KEY -> R.string.summary_error_no_key
    AiException.Kind.AUTH -> R.string.summary_error_auth
    AiException.Kind.NETWORK -> R.string.summary_error_network
    AiException.Kind.RATE_LIMIT -> R.string.summary_error_rate_limit
    AiException.Kind.SERVER -> R.string.summary_error_server
    AiException.Kind.BAD_RESPONSE -> R.string.summary_error_bad_response
    AiException.Kind.INSECURE -> R.string.summary_error_insecure
    null -> R.string.summary_error_unknown
}

private val Platforms = listOf(AiPlatform.CLAUDE, AiPlatform.OPENAI, AiPlatform.GEMINI, AiPlatform.OLLAMA)

@StringRes
private fun platformLabel(platform: AiPlatform): Int = when (platform) {
    AiPlatform.CLAUDE -> R.string.settings_ai_platform_claude
    AiPlatform.OPENAI -> R.string.settings_ai_platform_openai
    AiPlatform.GEMINI -> R.string.settings_ai_platform_gemini
    AiPlatform.OLLAMA -> R.string.settings_ai_platform_ollama
}

/** The picker's list for one platform: [live] came from the provider, [failed] means the fetch did and this is the fallback. */
data class AiModelList(val models: List<AiModel>, val live: Boolean = false, val loading: Boolean = false, val failed: Boolean = false)

/** What a platform's model list depends on; a change makes a fetched list stale. */
private fun AiSettings.endpoint(platform: AiPlatform): String = when (platform) {
    AiPlatform.CLAUDE -> claudeApiKey
    AiPlatform.GEMINI -> geminiApiKey
    AiPlatform.OPENAI -> "$openaiBaseUrl\n$openaiApiKey"
    AiPlatform.OLLAMA -> ollamaBaseUrl
}

internal fun builtInModels(ai: AiSettings) = AiModelList(AiModels.builtIn(ai.platform, AiClient.isOfficialOpenAi(ai)))

sealed interface AiTest {
    data object Running : AiTest
    data class Ok(val millis: Long) : AiTest
    data class Failed(val kind: AiException.Kind?) : AiTest
}

class AiSettingsViewModel(private val store: SettingsStore, private val ai: AiClient) : ViewModel() {
    val settings: StateFlow<AiSettings> =
        store.settings.map { it.ai }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), store.settings.value.ai)
    private val _test = MutableStateFlow<AiTest?>(null)
    val test: StateFlow<AiTest?> = _test.asStateFlow()
    private var testJob: Job? = null
    /** Per platform, in memory only: lists cost a request and change rarely. */
    private val _models = MutableStateFlow<Map<AiPlatform, AiModelList>>(emptyMap())
    val models: StateFlow<Map<AiPlatform, AiModelList>> = _models.asStateFlow()

    /** Any edit makes an earlier test result stale, and a new key or address the fetched model list. */
    fun update(change: (AiSettings) -> AiSettings) {
        testJob?.cancel()
        _test.value = null
        val before = store.settings.value.ai
        store.update { it.copy(ai = change(it.ai)) }
        val after = store.settings.value.ai
        _models.update { lists -> lists.filterKeys { before.endpoint(it) == after.endpoint(it) } }
    }

    /** Fetches the platform's live list unless one is cached or loading; [force] refetches a cached one. */
    fun loadModels(force: Boolean = false) {
        val snapshot = store.settings.value.ai
        val platform = snapshot.platform
        val current = _models.value[platform]
        if (current?.loading == true || (current?.live == true && !force)) return
        val fallback = builtInModels(snapshot)
        if (!snapshot.isReady()) {
            _models.update { it + (platform to fallback) }
            return
        }
        _models.update { it + (platform to (current ?: fallback).copy(loading = true, failed = false)) }
        viewModelScope.launch {
            val next = try {
                AiModelList(ai.listModels(snapshot).ifEmpty { fallback.models }, live = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fallback.copy(failed = true)
            }
            // A key edited mid-fetch already dropped this entry; this answer is for the old key.
            if (store.settings.value.ai.endpoint(platform) == snapshot.endpoint(platform)) _models.update { it + (platform to next) }
        }
    }

    fun runTest() {
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _test.value = AiTest.Running
            val started = SystemClock.elapsedRealtime()
            _test.value = try {
                ai.test(store.settings.value.ai)
                AiTest.Ok(SystemClock.elapsedRealtime() - started).also { loadModels() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                AiTest.Failed(e.kind)
            } catch (_: Exception) {
                AiTest.Failed(null)
            }
        }
    }
}

@Composable
internal fun aiSettingsViewModel(): AiSettingsViewModel {
    val app = LocalContext.current.app
    return viewModel { AiSettingsViewModel(app.settings, app.ai) }
}

/** The AI card's body, also shown in the transcript screen's setup sheet. */
@Composable
internal fun AiSettingsFields(
    ai: AiSettings,
    test: AiTest?,
    onChange: ((AiSettings) -> AiSettings) -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Settings and the transcript sheet share one view model per screen; previews have none.
    val vm = if (LocalInspectionMode.current) null else aiSettingsViewModel()
    val lists = vm?.models?.collectAsStateWithLifecycle()?.value.orEmpty()
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.settings_ai_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PillSegmented(
            options = Platforms,
            selected = ai.platform,
            onSelect = { platform -> onChange { it.copy(platform = platform, model = "") } },
            label = { stringResource(platformLabel(it)) },
        )
        key(ai.platform) {
            when (ai.platform) {
                AiPlatform.CLAUDE -> KeyField(ai.claudeApiKey, stringResource(R.string.settings_ai_key_where, "console.anthropic.com")) { key ->
                    onChange { it.copy(claudeApiKey = key) }
                }
                AiPlatform.GEMINI -> KeyField(ai.geminiApiKey, stringResource(R.string.settings_ai_key_where, "aistudio.google.com")) { key ->
                    onChange { it.copy(geminiApiKey = key) }
                }
                AiPlatform.OPENAI -> {
                    UrlField(
                        ai.openaiBaseUrl,
                        stringResource(R.string.settings_ai_openai_url_hint),
                        warning = stringResource(R.string.settings_ai_url_insecure).takeIf { AiClient.exposesKey(ai.openaiBaseUrl, ai.openaiApiKey) },
                    ) { url -> onChange { it.copy(openaiBaseUrl = url) } }
                    KeyField(ai.openaiApiKey, stringResource(R.string.settings_ai_key_optional)) { key -> onChange { it.copy(openaiApiKey = key) } }
                }
                AiPlatform.OLLAMA -> UrlField(ai.ollamaBaseUrl, stringResource(R.string.settings_ai_ollama_url_hint)) { url ->
                    onChange { it.copy(ollamaBaseUrl = url) }
                }
            }
            ModelPicker(
                ai = ai,
                list = lists[ai.platform] ?: builtInModels(ai),
                onLoad = { force -> vm?.loadModels(force) },
                onPick = { model -> onChange { it.copy(model = model.trim()) } },
            )
            // Right under the model: the two are what a test checks, and a result far below them goes unseen.
            TestConnection(test, onTest)
        }
        PromptEditor(ai, onChange)
    }
    // The settings screen puts the auto-summary switch right below, so the two automatic AI steps sit together.
    SwitchRow(
        title = stringResource(R.string.settings_ai_title),
        subtitle = stringResource(R.string.settings_ai_title_body),
        checked = ai.autoTitle,
        onCheckedChange = { on -> onChange { it.copy(autoTitle = on) } },
    )
}

/**
 * The model as a dropdown of [list], badged models first, with a way out to typing any id.
 * A typed id the list lacks still shows, as the selected "custom" entry.
 */
@Composable
private fun ModelPicker(ai: AiSettings, list: AiModelList, onLoad: (force: Boolean) -> Unit, onPick: (String) -> Unit) {
    var typing by rememberSaveable { mutableStateOf(false) }
    val default = defaultModel(ai.platform)
    if (typing) {
        SyncedField(
            value = ai.model,
            onValue = onPick,
            label = stringResource(R.string.settings_ai_model),
            placeholder = default,
            supporting = stringResource(R.string.settings_ai_model_hint),
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            trailing = {
                IconButton(onClick = { typing = false }) {
                    Icon(Icons.AutoMirrored.Outlined.List, contentDescription = stringResource(R.string.settings_ai_model_back_to_list))
                }
            },
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val selectedId = ai.model.ifBlank { default }
    val selected = list.models.firstOrNull { it.id == selectedId }
    val choices = if (selected == null) listOf(AiModel(selectedId)) + list.models else list.models
    Row(verticalAlignment = Alignment.Top) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { open ->
                expanded = open
                if (open) onLoad(false)
            },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selected?.name ?: selectedId,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.settings_ai_model)) },
                supportingText = {
                    Text(
                        when {
                            list.failed -> stringResource(R.string.settings_ai_model_offline)
                            selected == null -> stringResource(R.string.settings_ai_model_custom)
                            else -> selectedId
                        },
                    )
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { model ->
                    DropdownMenuItem(
                        text = { ModelRow(model, isCustom = model === choices.first() && selected == null) },
                        onClick = {
                            expanded = false
                            onPick(model.id)
                        },
                        leadingIcon = {
                            if (model.id == selectedId) Icon(Icons.Outlined.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
                if (list.loading) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_ai_model_loading)) },
                        onClick = {},
                        enabled = false,
                        leadingIcon = { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_ai_model_type)) },
                    onClick = {
                        expanded = false
                        typing = true
                    },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
        // Top-aligned with the field, not its supporting line; 56dp is the outlined field's height.
        Box(Modifier.padding(top = 4.dp).size(48.dp), contentAlignment = Alignment.Center) {
            if (list.loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { onLoad(true) }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.settings_ai_model_refresh))
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: AiModel, isCustom: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(model.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (model.name != model.id) {
                Text(model.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val badge = when {
            isCustom -> R.string.settings_ai_model_custom
            else -> when (model.badge) {
                AiModel.Badge.FREE_TIER -> R.string.settings_ai_model_free
                AiModel.Badge.LOCAL -> R.string.settings_ai_model_local
                AiModel.Badge.LOW_COST -> R.string.settings_ai_model_low_cost
                null -> null
            }
        }
        badge?.let { ModelTag(stringResource(it), highlight = !isCustom) }
    }
}

@Composable
private fun ModelTag(text: String, highlight: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun PromptEditor(ai: AiSettings, onChange: ((AiSettings) -> AiSettings) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // Picking a preset writes into the field directly; recreating it would race the settings flow.
    val prompt = rememberTextFieldState(ai.summaryPrompt)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource(presetLabel(ai.preset)),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.settings_ai_style)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AiPresets.summary.keys.forEach { id ->
                DropdownMenuItem(
                    text = { Text(stringResource(presetLabel(id))) },
                    onClick = {
                        expanded = false
                        onChange { it.copy(preset = id, prompt = AiPresets.prompt(id)) }
                        prompt.setTextAndPlaceCursorAtEnd(AiPresets.prompt(id))
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
    SyncedField(
        value = ai.summaryPrompt,
        state = prompt,
        onValue = { text ->
            onChange { it.copy(preset = if (text == AiPresets.prompt(it.preset)) it.preset else AiPresets.CUSTOM, prompt = text) }
        },
        label = stringResource(R.string.settings_ai_prompt),
        supporting = stringResource(R.string.settings_ai_prompt_hint),
        lines = TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 10),
    )
}

@Composable
private fun TestConnection(test: AiTest?, onTest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onTest, enabled = test != AiTest.Running, colors = pastelButtonColors(), border = pastelBorder()) { Text(stringResource(R.string.settings_ai_test)) }
        Row(
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (test) {
                null -> Unit
                AiTest.Running -> {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.settings_ai_testing), style = MaterialTheme.typography.bodySmall)
                }
                is AiTest.Ok -> {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.settings_ai_test_ok, test.millis), style = MaterialTheme.typography.bodySmall)
                }
                is AiTest.Failed -> {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(stringResource(aiErrorText(test.kind)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun KeyField(value: String, supporting: String, onValue: (String) -> Unit) {
    SyncedField(
        value = value,
        onValue = { onValue(it.trim()) },
        label = stringResource(R.string.settings_ai_key),
        supporting = supporting,
        secure = true,
    )
}

/** [warning], when set, replaces [supporting] and marks the field as an error. */
@Composable
private fun UrlField(value: String, supporting: String, warning: String? = null, onValue: (String) -> Unit) {
    SyncedField(
        value = value,
        onValue = { onValue(it.trim()) },
        label = stringResource(R.string.settings_ai_base_url),
        supporting = warning ?: supporting,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        error = warning != null,
    )
}

/**
 * Text lives in the field's own state and is pushed to the store as it changes, so a slow
 * round trip through the settings flow can never move the caret or drop a keystroke.
 */
@Composable
private fun SyncedField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    state: TextFieldState = rememberTextFieldState(value),
    placeholder: String? = null,
    supporting: String? = null,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    lines: TextFieldLineLimits = TextFieldLineLimits.SingleLine,
    secure: Boolean = false,
    error: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val latest by rememberUpdatedState(onValue)
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.drop(1).collect { latest(it) } }
    val modifier = Modifier.fillMaxWidth()
    val labelSlot: @Composable () -> Unit = { Text(label) }
    val placeholderSlot: (@Composable () -> Unit)? = placeholder?.let { { Text(it) } }
    val supportingSlot: (@Composable () -> Unit)? = supporting?.let { { Text(it) } }
    if (secure) {
        var visible by rememberSaveable { mutableStateOf(false) }
        OutlinedSecureTextField(
            state = state,
            modifier = modifier,
            label = { labelSlot() },
            supportingText = supportingSlot,
            textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(if (visible) R.string.settings_ai_key_hide else R.string.settings_ai_key_show),
                    )
                }
            },
        )
    } else {
        OutlinedTextField(
            state = state,
            modifier = modifier,
            label = { labelSlot() },
            placeholder = placeholderSlot,
            supportingText = supportingSlot,
            keyboardOptions = keyboard,
            lineLimits = lines,
            isError = error,
            trailingIcon = trailing,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
        )
    }
}

/** Set up AI without leaving the transcript: the same fields as Settings, in a sheet. */
@Composable
internal fun AiSetupSheet(onDismiss: () -> Unit) {
    val vm = aiSettingsViewModel()
    val ai by vm.settings.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.settings_ai_setup_title), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
            AiSettingsFields(ai, test, vm::update, vm::runTest)
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.lib_done))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AiSettingsFieldsPreview() {
    DibeTheme {
        AiSettingsFields(AiSettings(claudeApiKey = "sk-ant-123"), AiTest.Ok(412), {}, {})
    }
}
