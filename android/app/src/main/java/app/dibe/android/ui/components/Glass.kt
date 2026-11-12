package app.dibe.android.ui.components

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.lerp
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dibe.android.ui.theme.LocalDibeColors

/** The mint to lime-cream shell: the one full-bleed colour, behind every screen. */
@Composable
fun ShellBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = LocalDibeColors.current
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.shellFrom, colors.shellTo))), content = content)
}

/** Pocket's layered hairline shadow, tinted green: a floating card's lift without a heavy drop. */
@Composable
fun Modifier.softShadow(shape: Shape, elevation: Dp = 6.dp): Modifier {
    val tint = LocalDibeColors.current.shadow
    return shadow(elevation, shape, clip = false, ambientColor = tint.copy(alpha = 0.5f), spotColor = tint.copy(alpha = 0.35f))
}

/** The 1px ring that goes with [softShadow]. */
@Composable
fun hairlineBorder(): BorderStroke = BorderStroke(1.dp, LocalDibeColors.current.hairline)

/**
 * Translucent white with a bright 1dp edge and a soft shadow, for what floats over content.
 * No backdrop blur: RenderEffect blurs a view's own pixels, not what is behind it, so the honest
 * fallback is translucency plus the edge. [strong] is the denser glass for busy backdrops.
 */
@Composable
fun Modifier.glass(shape: Shape, strong: Boolean = false): Modifier {
    val colors = LocalDibeColors.current
    return softShadow(shape, 8.dp)
        .background(if (strong) colors.glassStrong else colors.glass, shape)
        .border(1.dp, colors.glassBorder, shape)
}

/** A 0→1 phase that loops every [millis], or null (static) under reduced motion. */
@Composable
private fun loopingPhase(millis: Int, label: String): State<Float>? =
    if (LocalReducedMotion.current) null
    else rememberInfiniteTransition(label = label).animateFloat(0f, 1f, infiniteRepeatable(tween(millis, easing = LinearEasing)), label = label)

/** A horizontal gradient two widths long, slid by [phase] widths: seamless because [colors] repeat. */
private fun sliding(colors: List<Color>, width: Float, phase: Float): Brush {
    val start = -phase * width
    return Brush.linearGradient(colors, Offset(start, 0f), Offset(start + 2 * width, 0f), TileMode.Repeated)
}

/**
 * Pocket's glow: a blurred copy of the element's shape, filled with a slowly sliding green
 * gradient, behind [content]. Blur needs API 31; below it a soft radial gradient stands in.
 * Static under reduced motion. Reserved for the one live thing on a screen.
 */
@Composable
fun GlowBehind(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    active: Boolean = true,
    spread: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    val colors = LocalDibeColors.current
    val phase = if (active) loopingPhase(5000, "glow") else null
    Box(modifier) {
        if (active) {
            Box(
                Modifier
                    .matchParentSize()
                    // Grows the layer by [spread] on each side so the blur has room and is never cut.
                    .layout { measurable, constraints ->
                        val pad = spread.roundToPx()
                        val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth + 2 * pad, constraints.maxHeight + 2 * pad))
                        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-pad, -pad) }
                    }
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(spread * 0.8f, BlurredEdgeTreatment.Unbounded) else Modifier)
                    .graphicsLayer { alpha = colors.glowAlpha }
                    .drawBehind {
                        val pad = spread.toPx()
                        val inner = Size(size.width - 2 * pad, size.height - 2 * pad)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val outline = shape.createOutline(inner, layoutDirection, this)
                            translate(pad, pad) { drawOutline(outline, sliding(colors.glow, inner.width, phase?.value ?: 0f)) }
                        } else {
                            drawRect(
                                Brush.radialGradient(
                                    listOf(colors.glow[0], colors.glow[2].copy(alpha = 0.6f), Color.Transparent),
                                    center = center,
                                    radius = size.maxDimension / 2,
                                ),
                            )
                        }
                    },
            )
        }
        content()
    }
}

/** Paints the content (text) with the sliding shimmer gradient while [active]: AI as it writes. */
@Composable
fun Modifier.shimmer(active: Boolean = true): Modifier {
    if (!active) return this
    val colors = LocalDibeColors.current.shimmer
    val phase = loopingPhase(2400, "shimmer")
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(sliding(colors, size.width.coerceAtLeast(1f), phase?.value ?: 0f), blendMode = BlendMode.SrcIn)
        }
}

/**
 * Pocket's scroll edge: content scrolling down under the bottom of a list softens into it. On API 31+
 * the strip shows a blurred copy of the content (Pocket's 4px backdrop blur), faded in down the
 * strip; under it everything fades out. Older phones get the fade alone. Give the content at least
 * [height] of bottom padding so its last row can scroll clear of it.
 */
@Composable
fun Modifier.bottomFade(height: Dp = 72.dp): Modifier {
    val blurred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) rememberGraphicsLayer() else null
    return graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        val edge = height.toPx().coerceAtMost(size.height)
        val top = size.height - edge
        // Where the blurred copy is fully opaque, the sharp content is not drawn at all.
        val solid = top + edge * 0.55f
        if (blurred == null) {
            drawContent()
        } else {
            clipRect(bottom = solid) { this@drawWithContent.drawContent() }
            blurred.renderEffect = BlurEffect(4.dp.toPx(), 4.dp.toPx(), TileMode.Clamp)
            blurred.record { this@drawWithContent.drawContent() }
            drawContext.canvas.saveLayer(Rect(0f, top, size.width, size.height), Paint())
            drawLayer(blurred)
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = top, endY = solid),
                topLeft = Offset(0f, top),
                size = Size(size.width, edge),
                blendMode = BlendMode.DstIn,
            )
            drawContext.canvas.restore()
        }
        drawRect(
            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = top, endY = size.height),
            blendMode = BlendMode.DstIn,
        )
    }
}

/** Every other button: a white pill with a mint ring and dark-green label (9.1:1). */
@Composable
fun pastelButtonColors(): ButtonColors = MaterialTheme.colorScheme.let {
    ButtonDefaults.outlinedButtonColors(containerColor = it.surface, contentColor = it.onPrimaryContainer)
}

/** The pastel fill is only 1.16:1 against the white page, so a faint green edge keeps the pill's shape readable. */
@Composable
fun pastelBorder(): BorderStroke = BorderStroke(1.5.dp, LocalDibeColors.current.pillRing)

/** Switches a shade lighter than the strong green: the "on" track stays at 3.4:1 on white (UI components need 3:1). */
@Composable
fun dibeSwitchColors(): SwitchColors = MaterialTheme.colorScheme.let {
    val track = lerp(it.primary, it.primaryContainer, 0.25f)
    SwitchDefaults.colors(checkedTrackColor = track, checkedBorderColor = track)
}

/** Primary CTA: the dark pill (light in dark mode). */
@Composable
fun pillButtonColors(): ButtonColors = LocalDibeColors.current.let {
    ButtonDefaults.buttonColors(containerColor = it.pillDark, contentColor = it.onPillDark)
}

@Composable
fun pillIconButtonColors(): IconButtonColors = LocalDibeColors.current.let {
    IconButtonDefaults.filledIconButtonColors(containerColor = it.pillDark, contentColor = it.onPillDark)
}

/** Top bars sit on the shell; once content scrolls under them they take its top colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun shellBarColors(): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = LocalDibeColors.current.shellFrom)
