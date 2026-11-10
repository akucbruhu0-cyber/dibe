package app.dibe.android.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.dibe.android.R
import app.dibe.android.app
import app.dibe.android.service.FilePhase
import app.dibe.android.service.Problem
import app.dibe.android.service.RecordingPhase
import app.dibe.android.service.RecordingState
import app.dibe.android.ui.components.EaseOutStrong
import app.dibe.android.ui.components.LocalReducedMotion
import app.dibe.android.ui.components.message
import app.dibe.android.ui.components.prefersReducedMotion
import app.dibe.android.ui.home.HomeScreen
import app.dibe.android.ui.library.LibraryScreen
import app.dibe.android.ui.onboarding.OnboardingScreen
import app.dibe.android.ui.recording.RecordingScreen
import app.dibe.android.ui.settings.ModelsScreen
import app.dibe.android.ui.settings.SettingsScreen
import app.dibe.android.ui.transcript.TranscriptScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val RECORDING = "recording"
    const val MODELS = "models"
    const val TRANSCRIPT = "transcript/{id}"
    fun transcript(id: String) = "transcript/${Uri.encode(id)}"
}

/** What the activity's intents ask of the UI. */
sealed interface AppRequest {
    data class OpenTranscript(val id: String) : AppRequest
    /** A shared or opened file was queued: show its job. */
    data object ShowHome : AppRequest
    /** A shared file was refused: another one is still running. */
    data object FileBusy : AppRequest
}

/** Warnings that come with a saved recording that ended early. */
private val CUT_SHORT = setOf(Problem.CAPTURE_FAILED, Problem.NO_SPACE)

private enum class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME(Routes.HOME, R.string.nav_record, Icons.Outlined.Mic, Icons.Rounded.Mic),
    HISTORY(Routes.HISTORY, R.string.nav_history, Icons.Outlined.History, Icons.Rounded.History),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings, Icons.Rounded.Settings),
}

private fun NavHostController.openTab(route: String) = navigate(route) {
    // Home is always the root once onboarding is gone.
    popUpTo(Routes.HOME) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun NavHostController.openTranscript(id: String) = navigate(Routes.transcript(id)) {
    popUpTo(Routes.RECORDING) { inclusive = true }
}

/**
 * Pops only while [entry] is the resumed screen: a screen still animating out (or a double tap)
 * would otherwise pop the one that replaced it.
 */
private fun NavHostController.back(entry: NavBackStackEntry) {
    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) popBackStack()
}

@Composable
fun AppRoot(requests: Flow<AppRequest>) {
    val context = LocalContext.current
    val app = context.app
    val reduced = remember { context.prefersReducedMotion() }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val start = remember { if (app.settings.settings.value.onboardingDone) Routes.HOME else Routes.ONBOARDING }
    val fileBusy = stringResource(R.string.home_file_busy)

    LaunchedEffect(requests) {
        requests.collect { request ->
            val onboarding = nav.currentDestination?.route == Routes.ONBOARDING
            when (request) {
                is AppRequest.OpenTranscript -> if (!onboarding) nav.navigate(Routes.transcript(request.id))
                AppRequest.ShowHome -> if (!onboarding) nav.openTab(Routes.HOME)
                AppRequest.FileBusy -> snackbar.showSnackbar(fileBusy)
            }
        }
    }

    // Only the recording facts that move navigation; the level ticks stay out of this scope.
    val recordingFacts = remember { { state: RecordingState -> (state.phase != RecordingPhase.IDLE) to state.savedId } }
    // Starts from the current value: a stale `false` after rotation would read as a fresh start.
    val recording by remember { app.sessions.recording.map(recordingFacts).distinctUntilChanged() }
        .collectAsStateWithLifecycle(recordingFacts(app.sessions.recording.value))
    val (recordingActive, savedId) = recording
    // Saved across rotation, so only a real start opens the recording screen, not a recreation.
    var wasActive by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(recordingActive) {
        if (recordingActive && !wasActive && nav.currentDestination?.route != Routes.RECORDING) nav.navigate(Routes.RECORDING)
        wasActive = recordingActive
    }
    LaunchedEffect(savedId) {
        savedId ?: return@LaunchedEffect
        // Read before clearResults drops it: a recording cut short opens like any other, and says why.
        val cutShort = app.sessions.recording.value.warning?.takeIf { it in CUT_SHORT }
        nav.openTranscript(savedId)
        app.sessions.clearResults()
        if (cutShort != null) {
            snackbar.showSnackbar(context.getString(cutShort.message()), withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }

    val filePhase by remember { app.sessions.fileJob.map { it?.phase }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(app.sessions.fileJob.value?.phase)
    var fileWasRunning by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(filePhase) {
        val job = app.sessions.fileJob.value
        when {
            // Finished while the reader watched its card: open it. Elsewhere the bubble offers it.
            job?.phase == FilePhase.DONE && job.savedId != null && fileWasRunning && nav.currentDestination?.route == Routes.HOME -> {
                nav.openTranscript(job.savedId)
                app.sessions.clearResults()
            }
            job?.phase == FilePhase.CANCELLED -> app.sessions.clearResults()
        }
        fileWasRunning = filePhase == FilePhase.DECODING || filePhase == FilePhase.TRANSCRIBING
    }

    val tabs = Tab.entries.any { it.route == route }
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = { if (tabs) DibeNavigationBar(route) { nav.openTab(it) } },
                snackbarHost = { SnackbarHost(snackbar) },
                // Each screen owns its top inset; the bar (or the screen, when hidden) owns the bottom.
                contentWindowInsets = WindowInsets(0),
                containerColor = MaterialTheme.colorScheme.background,
            ) { padding ->
                NavHost(
                    navController = nav,
                    startDestination = start,
                    modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                    enterTransition = {
                        if (reduced) fadeIn(tween(150)) else fadeIn(tween(220, easing = EaseOutStrong)) + scaleIn(tween(220, easing = EaseOutStrong), initialScale = 0.98f)
                    },
                    exitTransition = { fadeOut(tween(120)) },
                    popEnterTransition = { if (reduced) fadeIn(tween(150)) else fadeIn(tween(220, easing = EaseOutStrong)) },
                    popExitTransition = {
                        if (reduced) fadeOut(tween(120)) else fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.98f)
                    },
                ) {
                    composable(Routes.ONBOARDING, enterTransition = { EnterTransition.None }) { entry ->
                        OnboardingScreen(
                            onFinished = { openModels ->
                                // Replayed from Settings: just go back there; first launch made Home the root.
                                when {
                                    nav.previousBackStackEntry == null -> {
                                        app.settings.update { it.copy(onboardingDone = true) }
                                        nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                                        if (openModels) nav.navigate(Routes.MODELS)
                                    }
                                    openModels -> nav.navigate(Routes.MODELS) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                                    else -> nav.back(entry)
                                }
                            },
                        )
                    }
                    composable(Routes.HOME) {
                        HomeScreen(
                            snackbar = snackbar,
                            onOpenModels = { nav.navigate(Routes.MODELS) },
                            onOpenRecording = { nav.navigate(Routes.RECORDING) },
                            onOpenTranscript = { nav.openTranscript(it) },
                        )
                    }
                    composable(Routes.HISTORY) { LibraryScreen(onOpen = { nav.navigate(Routes.transcript(it)) }) }
                    composable(Routes.SETTINGS) { SettingsScreen(onOpenModels = { nav.navigate(Routes.MODELS) }, onReplayIntro = { nav.navigate(Routes.ONBOARDING) }) }
                    composable(Routes.MODELS) { entry -> ModelsScreen(onBack = { nav.back(entry) }) }
                    composable(Routes.RECORDING) { entry ->
                        RecordingScreen(onBack = { nav.back(entry) }, onOpenModels = { nav.navigate(Routes.MODELS) })
                    }
                    composable(Routes.TRANSCRIPT) { entry ->
                        val id = entry.arguments?.getString("id").orEmpty()
                        TranscriptScreen(id = id, onBack = { nav.back(entry) }, onDeleted = { nav.back(entry) })
                    }
                }
            }
            ActivityBubble(
                route = route,
                // Clear of the navigation bar on tabs, and of the transcript's player bar (~130dp tall).
                bottomReserve = when {
                    tabs -> 81.dp
                    route == Routes.TRANSCRIPT -> 140.dp
                    else -> 0.dp
                },
                onOpenRecording = { nav.navigate(Routes.RECORDING) { launchSingleTop = true } },
                onOpenTranscript = { id ->
                    nav.openTranscript(id)
                    app.sessions.clearResults()
                },
                onOpenHome = { nav.openTab(Routes.HOME) },
            )
        }
    }
}

/** Hairline-topped, background-coloured: the desktop's quiet chrome, with a green active pill. */
@Composable
private fun DibeNavigationBar(route: String?, onSelect: (String) -> Unit) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            Tab.entries.forEach { tab ->
                val selected = route == tab.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { if (!selected) onSelect(tab.route) },
                    icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                    label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}
