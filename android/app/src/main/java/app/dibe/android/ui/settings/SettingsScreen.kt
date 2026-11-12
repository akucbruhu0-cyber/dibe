@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package app.dibe.android.ui.settings

import app.dibe.android.ui.components.bottomFade
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dibe.android.DibeApp
import app.dibe.android.R
import app.dibe.android.ai.AiSettings
import app.dibe.android.app
import app.dibe.android.engine.EngineCapabilities
import app.dibe.android.models.EngineKind
import app.dibe.android.models.ModelSpec
import app.dibe.android.models.Tier
import app.dibe.android.settings.AppLanguage
import app.dibe.android.settings.AppSettings
import app.dibe.android.settings.ThemeMode
import app.dibe.android.settings.appLanguage
import app.dibe.android.settings.setAppLanguage
import app.dibe.android.ui.components.PillSegmented
import app.dibe.android.ui.components.formatSize
import app.dibe.android.ui.components.languageName
import app.dibe.android.ui.components.normalizeLanguage
import app.dibe.android.ui.components.shellBarColors
import app.dibe.android.ui.theme.DibeTheme
import java.text.Collator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val activeModel: ModelSpec? = null,
    /** Null until read, or when no model is installed. */
    val capabilities: EngineCapabilities? = null,
)

class SettingsViewModel(private val app: DibeApp) : ViewModel() {
    private val capabilities = app.models.activeModelId.mapLatest { id ->
        val file = id?.let(app.models::modelFile) ?: return@mapLatest null
        try {
            app.engine.metadata(file)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    val uiState: StateFlow<SettingsUiState> =
        combine(app.settings.settings, app.models.activeModelId, capabilities) { settings, id, capabilities ->
            SettingsUiState(settings, app.models.catalog.find { it.id == id }, capabilities)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(app.settings.settings.value))

    fun update(change: (AppSettings) -> AppSettings) = app.settings.update(change)
}

/** Settings tab root: models, transcription, AI summary, general. */
@Composable
fun SettingsScreen(onOpenModels: () -> Unit, onReplayIntro: () -> Unit) {
    val app = LocalContext.current.app
    val vm = viewModel { SettingsViewModel(app) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ai = aiSettingsViewModel()
    val aiSettings by ai.settings.collectAsStateWithLifecycle()
    val test by ai.test.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    // Re-read on each composition: changing it recreates the activity, and so does the system's own picker.
    val appLanguage = activity?.appLanguage() ?: AppLanguage.SYSTEM
    SettingsContent(state, aiSettings, test, appLanguage, onOpenModels, onReplayIntro, vm::update, ai::update, ai::runTest) {
        activity?.setAppLanguage(it)
    }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    ai: AiSettings,
    test: AiTest?,
    appLanguage: AppLanguage,
    onOpenModels: () -> Unit,
    onReplayIntro: () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onAiChange: ((AiSettings) -> AiSettings) -> Unit,
    onTest: () -> Unit,
    onAppLanguage: (AppLanguage) -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var picking by rememberSaveable { mutableStateOf(false) }
    val settings = state.settings
    val caps = state.capabilities
    // "auto" on a one-language model that can't detect is simply that language.
    val autoLabel = stringResource(
        if (caps != null && !caps.languageDetection && caps.languages.size <= 1) R.string.settings_language_model_default
        else R.string.settings_language_auto,
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.settings_title)) }, colors = shellBarColors(), scrollBehavior = scroll) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().bottomFade().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 720.dp).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                SettingsCard(stringResource(R.string.settings_section_models)) {
                    val model = state.activeModel
                    SettingRow(
                        title = model?.name ?: stringResource(R.string.settings_no_model),
                        subtitle = model?.let { "${stringResource(tierLabel(it.tier))} · ${formatSize(it.sizeBytes)}" }
                            ?: stringResource(R.string.settings_no_model_body),
                        icon = Icons.Outlined.Memory,
                    )
                    RowDivider()
                    SettingRow(
                        title = stringResource(R.string.settings_manage_models),
                        onClick = onOpenModels,
                        trailing = { Chevron() },
                    )
                }

                SettingsCard(stringResource(R.string.settings_section_transcription)) {
                    SettingRow(
                        title = stringResource(R.string.settings_language),
                        subtitle = if (state.activeModel == null) stringResource(R.string.settings_language_no_model)
                        else languageName(normalizeLanguage(settings.language), autoLabel),
                        icon = Icons.Outlined.Translate,
                        enabled = caps != null,
                        onClick = { picking = true },
                        trailing = { Chevron() },
                    )
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_live_partials),
                        subtitle = stringResource(R.string.settings_live_partials_body),
                        checked = settings.livePartials,
                        onCheckedChange = { on -> onChange { it.copy(livePartials = on) } },
                        icon = Icons.Outlined.GraphicEq,
                    )
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_keep_audio),
                        subtitle = stringResource(R.string.settings_keep_audio_body),
                        checked = settings.keepAudio,
                        onCheckedChange = { on -> onChange { it.copy(keepAudio = on) } },
                        icon = Icons.Outlined.Save,
                    )
                }

                SettingsCard(stringResource(R.string.settings_section_ai)) {
                    AiSettingsFields(ai, test, onAiChange, onTest)
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_ai_auto),
                        subtitle = stringResource(R.string.settings_ai_auto_body),
                        checked = ai.autoSummarize,
                        onCheckedChange = { on -> onAiChange { it.copy(autoSummarize = on) } },
                    )
                    RowDivider()
                    CalendarNamesRow(settings.calendarNames) { on -> onChange { it.copy(calendarNames = on) } }
                }

                SettingsCard(stringResource(R.string.settings_section_general)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
                        PillSegmented(
                            options = ThemeMode.entries,
                            selected = settings.theme,
                            onSelect = { mode -> onChange { it.copy(theme = mode) } },
                            label = { stringResource(themeLabel(it)) },
                        )
                    }
                    RowDivider()
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.settings_app_language), style = MaterialTheme.typography.bodyLarge)
                        PillSegmented(
                            options = AppLanguage.entries,
                            selected = appLanguage,
                            onSelect = { if (it != appLanguage) onAppLanguage(it) },
                            label = { stringResource(appLanguageLabel(it)) },
                        )
                    }
                    RowDivider()
                    SettingRow(
                        title = stringResource(R.string.settings_replay_intro),
                        icon = Icons.Outlined.School,
                        onClick = onReplayIntro,
                        trailing = { Chevron() },
                    )
                    RowDivider()
                    SettingRow(
                        title = stringResource(R.string.settings_privacy),
                        subtitle = stringResource(R.string.settings_privacy_body),
                        icon = Icons.Outlined.PrivacyTip,
                    )
                    RowDivider()
                    val uriHandler = LocalUriHandler.current
                    SettingRow(
                        title = stringResource(R.string.settings_support),
                        subtitle = stringResource(R.string.settings_support_body),
                        icon = ImageVector.vectorResource(R.drawable.ic_buymeacoffee),
                        // No browser installed would throw; the row then just does nothing.
                        onClick = { runCatching { uriHandler.openUri(SUPPORT_URL) } },
                        trailing = { Chevron() },
                    )
                    RowDivider()
                    SettingRow(
                        title = stringResource(R.string.settings_version),
                        subtitle = appVersion(),
                        icon = Icons.Outlined.Info,
                    )
                }
            }
        }
    }

    if (picking && caps != null) {
        LanguageSheet(
            languages = caps.languages,
            auto = autoLabel,
            selected = normalizeLanguage(settings.language),
            onSelect = { code ->
                onChange { it.copy(language = code) }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/** Buy Me a Coffee; same page as the desktop's `supportVibeURL` (`desktop/src/lib/config.ts`). */
private const val SUPPORT_URL = "https://buymeacoffee.com/dibe"

@Composable
private fun Chevron() {
    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
}

internal fun tierLabel(tier: Tier): Int = when (tier) {
    Tier.LIGHT -> R.string.models_tier_light
    Tier.MEDIUM -> R.string.models_tier_medium
    Tier.HEAVY -> R.string.models_tier_heavy
}

private fun appLanguageLabel(language: AppLanguage): Int = when (language) {
    AppLanguage.SYSTEM -> R.string.settings_app_language_system
    AppLanguage.ENGLISH -> R.string.settings_app_language_en
    AppLanguage.HEBREW -> R.string.settings_app_language_he
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

/**
 * Searchable, since Whisper alone lists 99 languages. Names are in the UI language. "auto" is always
 * first: the engines take it on every model (detection, or the model's own language).
 */
@Composable
private fun LanguageSheet(
    languages: List<String>,
    auto: String,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = remember(languages, auto) {
        val collator = Collator.getInstance()
        listOf("auto") + languages.map(::normalizeLanguage).distinct().sortedWith(compareBy(collator) { languageName(it, auto) })
    }
    val query = rememberTextFieldState()
    val needle = query.text.toString().trim()
    val shown = if (needle.isEmpty()) options
    else options.filter { languageName(it, auto).contains(needle, ignoreCase = true) || it.equals(needle, ignoreCase = true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(stringResource(R.string.settings_language), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
        if (options.size > 8) {
            TextField(
                state = query,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                placeholder = { Text(stringResource(R.string.settings_language_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                lineLimits = TextFieldLineLimits.SingleLine,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
        LazyColumn(Modifier.selectableGroup()) {
            items(shown, key = { it }) { code ->
                val active = code == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .selectable(selected = active, role = Role.RadioButton) { onSelect(code) }
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        languageName(code, auto),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (active) Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Not an AI step, but it names recordings too, so it sits by the AI title. On only once READ_CALENDAR is granted. */
@Composable
private fun CalendarNamesRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) onCheckedChange(true) }
    SwitchRow(
        title = stringResource(R.string.settings_calendar_names),
        subtitle = stringResource(R.string.settings_calendar_names_body),
        checked = checked,
        onCheckedChange = { on ->
            when {
                !on -> onCheckedChange(false)
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED ->
                    onCheckedChange(true)
                else -> ask.launch(Manifest.permission.READ_CALENDAR)
            }
        },
    )
}

@Preview
@Composable
private fun SettingsPreview() {
    DibeTheme {
        SettingsContent(
            SettingsUiState(
                activeModel = ModelSpec("w", "Whisper Small", Tier.MEDIUM, EngineKind.WHISPER, "", "", 488_000_000, null, 4, true),
            ),
            AiSettings(),
            null,
            AppLanguage.SYSTEM,
            {}, {}, {}, {}, {}, {},
        )
    }
}
