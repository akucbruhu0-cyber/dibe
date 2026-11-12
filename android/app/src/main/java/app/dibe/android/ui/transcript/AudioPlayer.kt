package app.dibe.android.ui.transcript

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.dibe.android.R
import app.dibe.android.ui.components.ClockFamily
import app.dibe.android.ui.components.formatClock
import app.dibe.android.ui.components.glass
import app.dibe.android.ui.components.pillIconButtonColors
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerState(
    val ready: Boolean = false,
    val failed: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)

/**
 * The project's audio, owned by the screen's ViewModel so it survives rotation. Position is
 * polled while playing, which is what drives the slider and the highlighted line.
 */
@Stable
class AudioPlayer(file: File, private val scope: CoroutineScope) {
    private val player = MediaPlayer()
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var ticker: Job? = null

    init {
        player.setOnPreparedListener { _state.update { it.copy(ready = true, durationMs = player.duration.toLong().coerceAtLeast(0)) } }
        player.setOnCompletionListener {
            ticker?.cancel()
            _state.update { it.copy(playing = false, positionMs = it.durationMs) }
        }
        player.setOnErrorListener { _, _, _ ->
            ticker?.cancel()
            _state.update { it.copy(failed = true, playing = false) }
            true
        }
        try {
            player.setDataSource(file.path)
            player.prepareAsync()
        } catch (_: Exception) {
            _state.update { it.copy(failed = true) }
        }
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun play() {
        val current = _state.value
        if (!current.ready || current.playing) return
        if (current.durationMs > 0 && current.positionMs >= current.durationMs) player.seekTo(0)
        player.start()
        // Setting a speed starts playback on its own, so it is applied only once started.
        if (current.speed != 1f) player.playbackParams = player.playbackParams.setSpeed(current.speed)
        _state.update { it.copy(playing = true) }
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && player.isPlaying) {
                _state.update { it.copy(positionMs = player.currentPosition.toLong()) }
                delay(100)
            }
        }
    }

    fun pause() {
        if (!_state.value.playing) return
        player.pause()
        ticker?.cancel()
        _state.update { it.copy(playing = false, positionMs = player.currentPosition.toLong()) }
    }

    fun seekTo(ms: Long) {
        if (!_state.value.ready) return
        val target = ms.coerceIn(0, _state.value.durationMs)
        player.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        _state.update { it.copy(positionMs = target) }
    }

    fun skip(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    fun cycleSpeed() {
        val next = Speeds[(Speeds.indexOf(_state.value.speed) + 1) % Speeds.size]
        if (_state.value.playing) player.playbackParams = player.playbackParams.setSpeed(next)
        _state.update { it.copy(speed = next) }
    }

    fun release() {
        ticker?.cancel()
        player.release()
    }

    private companion object {
        val Speeds = listOf(1f, 1.5f, 2f)
    }
}

private fun speedLabel(speed: Float) = if (speed % 1f == 0f) "${speed.toInt()}×" else "$speed×"

/** Media controls stay left-to-right in Hebrew too, like the timeline they describe (Material bidi rule). */
@Composable
internal fun PlayerBar(player: AudioPlayer, state: PlayerState, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    // A glass dock floating on the shell, under the transcript's sheet.
    Surface(modifier, color = Color.Transparent) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .glass(RoundedCornerShape(24.dp), strong = true)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val clock = TextStyle(fontFamily = ClockFamily).merge(MaterialTheme.typography.labelMedium)
                    val shown = dragging?.toLong() ?: state.positionMs
                    Row(Modifier.widthIn(max = 720.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatClock(shown), style = clock, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = shown.toFloat(),
                            onValueChange = { dragging = it },
                            onValueChangeFinished = {
                                dragging?.let { player.seekTo(it.toLong()) }
                                dragging = null
                            },
                            valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(),
                            enabled = state.ready,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics {
                                contentDescription = "${formatClock(shown)} / ${formatClock(state.durationMs)}"
                            },
                        )
                        Text(formatClock(state.durationMs), style = clock, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { player.skip(-10_000) }, enabled = state.ready) {
                                Icon(Icons.Outlined.Replay10, contentDescription = stringResource(R.string.player_back_10))
                            }
                            FilledIconButton(onClick = player::toggle, enabled = state.ready, modifier = Modifier.size(52.dp), colors = pillIconButtonColors()) {
                                Icon(
                                    if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    contentDescription = stringResource(if (state.playing) R.string.player_pause else R.string.player_play),
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                            IconButton(onClick = { player.skip(10_000) }, enabled = state.ready) {
                                Icon(Icons.Outlined.Forward10, contentDescription = stringResource(R.string.player_forward_10))
                            }
                        }
                        TextButton(onClick = player::cycleSpeed, modifier = Modifier.align(Alignment.CenterEnd)) {
                            val label = stringResource(R.string.player_speed, speedLabel(state.speed))
                            Text(speedLabel(state.speed), Modifier.semantics { contentDescription = label }, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}
