package app.dibe.android.ui.onboarding

import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.dibe.android.ui.components.LocalReducedMotion
import app.dibe.android.ui.theme.Brand

private val Corner = 24.dp

/**
 * A silent, looping clip with its poster in front until the first frame renders. The poster alone
 * stands in under reduced motion, when the clip fails (e.g. an empty placeholder), off-screen,
 * and in previews. Decorative: hidden from accessibility.
 */
@Composable
fun LoopingVideo(@RawRes video: Int, @DrawableRes poster: Int, playing: Boolean, modifier: Modifier = Modifier) {
    var failed by remember { mutableStateOf(false) }
    var rendering by remember(playing) { mutableStateOf(false) }
    val play = playing && !failed && !LocalReducedMotion.current && !LocalInspectionMode.current
    val background = MaterialTheme.colorScheme.background

    Box(
        modifier
            .clearAndSetSemantics {}
            // A VideoView is a SurfaceView, which ignores Compose clipping: paint the page colour
            // over its corners instead.
            .drawWithCache {
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(Corner.toPx())))
                }
                onDrawWithContent {
                    drawContent()
                    drawPath(mask, background)
                }
            },
    ) {
        if (play) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        // Never take audio focus: the user's music keeps playing.
                        setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            player.setVolume(0f, 0f)
                            start()
                        }
                        setOnInfoListener { _, what, _ ->
                            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendering = true
                            false
                        }
                        setOnErrorListener { _, _, _ ->
                            failed = true
                            true
                        }
                        setVideoURI(Uri.parse("android.resource://${context.packageName}/$video"))
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.matchParentSize(),
            )
        }
        if (!play || !rendering) {
            Image(
                painterResource(poster),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(RoundedCornerShape(Corner)).background(Brand.Night),
            )
        }
    }
}
