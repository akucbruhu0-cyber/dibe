package app.dibe.android.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dibe.android.R
import app.dibe.android.models.CATALOG
import app.dibe.android.app
import app.dibe.android.models.ModelStatus
import app.dibe.android.ui.components.Eyebrow
import app.dibe.android.ui.components.formatSize
import app.dibe.android.ui.theme.DibeTheme
import kotlinx.coroutines.launch

private class Intro(
    @RawRes val video: Int,
    @DrawableRes val poster: Int,
    @StringRes val eyebrow: Int?,
    @StringRes val title: Int,
    @StringRes val body: Int,
)

private val Intros = listOf(
    Intro(R.raw.onboarding_welcome, R.drawable.onboarding_welcome_poster, null, R.string.onb_welcome_title, R.string.onb_welcome_body),
    Intro(R.raw.onboarding_power, R.drawable.onboarding_power_poster, R.string.onb_power_eyebrow, R.string.onb_power_title, R.string.onb_power_body),
    Intro(R.raw.onboarding_hardware, R.drawable.onboarding_hardware_poster, R.string.onb_hardware_eyebrow, R.string.onb_hardware_title, R.string.onb_hardware_body),
    Intro(R.raw.onboarding_fit, R.drawable.onboarding_fit_poster, R.string.onb_fit_eyebrow, R.string.onb_fit_title, R.string.onb_fit_body),
)

/**
 * First launch: a welcome, three tips about on-device AI, then this phone's model recommendation.
 * [onFinished] marks onboarding done; `true` asks for the model list next.
 */
@Composable
fun OnboardingScreen(onFinished: (openModels: Boolean) -> Unit) {
    val models = LocalContext.current.app.models
    val pager = rememberPagerState { Intros.size + 1 }
    val scope = rememberCoroutineScope()
    val onDevicePage = pager.currentPage == Intros.size
    // A replay from Settings shows the model in use, so it never offers a second download.
    val recommended = remember { models.activeModelId.value?.let { id -> CATALOG.find { it.id == id } } ?: models.recommended() }
    val statuses by models.statuses.collectAsStateWithLifecycle()
    val status = statuses[recommended.id]
    // Already there or on its way: nothing left to start.
    val started = status != null && status !is ModelStatus.NotInstalled && status !is ModelStatus.Failed

    BackHandler(enabled = pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // The welcome page is already the logo and the name; the bar's copy fades in from page 2.
            val brand by animateFloatAsState(if (pager.currentPage == 0) 0f else 1f, label = "brand")
            Image(painterResource(R.drawable.dibe_logo), contentDescription = null, modifier = Modifier.size(28.dp).alpha(brand))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).alpha(brand))
            if (!onDevicePage) {
                TextButton({ scope.launch { pager.animateScrollToPage(Intros.size) } }) {
                    Text(stringResource(R.string.onb_skip_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            if (page < Intros.size) {
                IntroPage(Intros[page], playing = pager.settledPage == page)
            } else {
                DevicePage(
                    recommended = recommended,
                    fit = remember { models.fit(recommended) },
                    status = status,
                    onChooseAnother = { onFinished(true) },
                    onSkip = { onFinished(false) },
                )
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PageDots(count = Intros.size + 1, current = pager.currentPage)
            Spacer(Modifier.height(20.dp))
            val primary = when {
                pager.currentPage == 0 -> stringResource(R.string.onb_lets_start)
                !onDevicePage -> stringResource(R.string.onb_next)
                started -> stringResource(R.string.onb_continue)
                else -> stringResource(R.string.onb_download, formatSize(recommended.sizeBytes))
            }
            Button(
                onClick = {
                    when {
                        !onDevicePage -> scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                        started -> onFinished(false)
                        else -> {
                            models.download(recommended.id)
                            onFinished(false)
                        }
                    }
                },
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                AnimatedContent(primary, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "primary") {
                    Text(it, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun IntroPage(intro: Intro, playing: Boolean) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        LoopingVideo(intro.video, intro.poster, playing, Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(32.dp))
        // The welcome page has no eyebrow and a larger title.
        if (intro.eyebrow != null) {
            Eyebrow(stringResource(intro.eyebrow))
            Spacer(Modifier.height(10.dp))
        }
        Text(
            stringResource(intro.title),
            style = if (intro.eyebrow == null) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(intro.body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    val description = stringResource(R.string.onb_page_desc, current + 1, count)
    Row(Modifier.semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(if (selected) 20.dp else 6.dp, spring(dampingRatio = 0.8f), label = "dotWidth")
            val color by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                label = "dotColor",
            )
            Box(Modifier.height(6.dp).width(width).clip(CircleShape).background(color))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun IntroPagePreview() {
    DibeTheme { IntroPage(Intros[0], playing = false) }
}
