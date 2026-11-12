@file:OptIn(ExperimentalLayoutApi::class)

package app.dibe.android.ui.transcript

import app.dibe.android.ui.components.pastelBorder
import app.dibe.android.ui.components.pastelButtonColors
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.dibe.android.R
import java.text.NumberFormat
import kotlin.math.roundToInt

/** Distinct in light and dark; a row's own `#hex` wins. */
private val Palette = listOf(0x007AFF, 0xFF3B30, 0x34C759, 0xFF9500, 0xAF52DE, 0x5AC8FA, 0xFF2D55, 0xFFCC00).map { Color(0xFF000000 or it.toLong()) }

private fun colorOf(index: Int, own: Long?) = own?.let { Color(0xFF000000 or it) } ?: Palette[index % Palette.size]

/**
 * 0 → 1 once, when [seen] first turns true (the card scrolled into view), not when it is composed:
 * visuals sit below the fold and would finish animating before anyone got there.
 */
@Composable
private fun rememberReveal(key: Any, seen: Boolean): Float {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key, seen) { if (seen) progress.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    return progress.value
}

/** Item [index] of [count]'s own 0 → 1, starting a little after the one before it. */
private fun stagger(progress: Float, index: Int, count: Int): Float {
    val step = 0.5f / maxOf(count, 1)
    return ((progress - index * step) / (1f - (count - 1) * step).coerceAtLeast(0.3f)).coerceIn(0f, 1f)
}

private fun Modifier.rise(p: Float) = graphicsLayer { alpha = p; translationY = (1f - p) * 24f }

@Composable
fun VisualCard(visual: Visual, modifier: Modifier = Modifier) {
    val direction = remember(visual) { visual.rtl()?.let { if (it) LayoutDirection.Rtl else LayoutDirection.Ltr } } ?: LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides direction) { VisualCardBody(visual, modifier) }
}

@Composable
private fun VisualCardBody(visual: Visual, modifier: Modifier) {
    var seen by remember(visual) { mutableStateOf(false) }
    val progress = rememberReveal(visual, seen)
    Surface(
        modifier
            .fillMaxWidth()
            .onVisibilityChanged(minFractionVisible = 0.3f) { if (it) seen = true }
            .graphicsLayer {
                // The card itself settles in first, then its parts follow.
                val p = (progress * 4f).coerceAtMost(1f)
                alpha = p
                scaleX = 0.96f + 0.04f * p
                scaleY = scaleX
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (visual.title.isNotBlank()) {
                Text(visual.title, style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content))
            }
            when (visual) {
                is Visual.Chart -> if (visual.pie) Donut(visual, progress) else Bars(visual, progress)
                is Visual.Flow -> Flow(visual, progress)
                is Visual.Timeline -> Timeline(visual, progress)
                is Visual.Tree -> Tree(visual)
                is Visual.Compare -> Compare(visual, progress)
            }
        }
    }
}

private val numbers: NumberFormat = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }

@Composable
private fun Bars(chart: Visual.Chart, progress: Float) {
    val max = chart.rows.maxOf { it.value }
    val body = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        chart.rows.forEachIndexed { i, row ->
            val p = stagger(progress, i, chart.rows.size)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label, Modifier.weight(1f), style = body)
                    Text(numbers.format(row.value * p), style = body.copy(fontWeight = FontWeight.SemiBold))
                }
                Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Box(Modifier.fillMaxWidth((row.value / max).toFloat() * p).fillMaxHeight().clip(CircleShape).background(colorOf(i, row.color)))
                }
            }
        }
    }
}

@Composable
private fun Donut(chart: Visual.Chart, progress: Float) {
    val total = chart.rows.sumOf { it.value }
    val colors = chart.rows.mapIndexed { i, row -> colorOf(i, row.color) }
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val summary = chart.rows.joinToString { "${it.label} ${(it.value / total * 100).roundToInt()}%" }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.size(168.dp).semantics { contentDescription = summary }) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            // Round ends reach half a stroke past each arc, so slices sit a stroke (plus a hairline) apart.
            val gap = if (chart.rows.size > 1) Math.toDegrees(((stroke + 3.dp.toPx()) / (arc.width / 2)).toDouble()).toFloat() else 0f
            var start = -90f
            val shown = 360f * progress
            chart.rows.forEachIndexed { i, row ->
                val sweep = (row.value / total * 360).toFloat()
                val visible = (minOf(start + sweep, -90f + shown) - start - gap).coerceAtLeast(0f)
                if (visible > 0f) drawArc(colors[i], start + gap / 2, visible, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                start += sweep
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chart.rows.forEachIndexed { i, row ->
                Row(Modifier.rise(stagger(progress, i, chart.rows.size)), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(colors[i]))
                    Text(row.label, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                    Text("${(row.value / total * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
private fun Flow(flow: Visual.Flow, progress: Float) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        flow.layers.forEachIndexed { level, names ->
            val p = stagger(progress, level, flow.layers.size)
            if (level > 0) Icon(Icons.Outlined.ArrowDownward, null, Modifier.padding(vertical = 4.dp).size(18.dp).rise(p), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                Modifier.fillMaxWidth().rise(p),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val color = Palette[level % Palette.size]
                names.forEach { name ->
                    Text(
                        name,
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(color.copy(alpha = 0.10f))
                            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun Timeline(timeline: Visual.Timeline, progress: Float) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column {
        timeline.events.forEachIndexed { i, event ->
            val last = i == timeline.events.lastIndex
            Row(Modifier.height(IntrinsicSize.Min).rise(stagger(progress, i, timeline.events.size))) {
                Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.padding(top = 4.dp).size(12.dp).clip(CircleShape).background(colorOf(i, null)))
                    if (!last) Box(Modifier.width(2.dp).weight(1f).background(line))
                }
                Column(Modifier.padding(start = 12.dp, bottom = if (last) 0.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(event.time, style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content), color = MaterialTheme.colorScheme.primary)
                    Text(event.title, style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content))
                    if (event.detail.isNotBlank()) {
                        Text(event.detail, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Interactive: one node at a time, its choices as buttons; a choice to a missing node is not offered. */
@Composable
private fun Tree(tree: Visual.Tree) {
    var path by rememberSaveable(tree) { mutableStateOf(listOf(tree.start)) }
    AnimatedContent(path, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "tree") { trail ->
        val node = tree.nodes.getValue(trail.last())
        val choices = node.choices.filter { it.second in tree.nodes }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(node.title, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content))
            if (node.body.isNotBlank()) {
                Text(node.body, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            choices.forEach { (label, target) ->
                OutlinedButton(onClick = { path = trail + target }, modifier = Modifier.fillMaxWidth(), colors = pastelButtonColors(), border = pastelBorder()) {
                    Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), textAlign = TextAlign.Start)
                }
            }
            if (trail.size > 1) {
                Row {
                    TextButton(onClick = { path = trail.dropLast(1) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.visual_back), Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = { path = listOf(tree.start) }) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.visual_start_over), Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

/** One card per compared item: a phone is too narrow for a wide table. */
@Composable
private fun Compare(compare: Visual.Compare, progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        compare.rows.forEachIndexed { i, row ->
            val color = colorOf(i, null)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .rise(stagger(progress, i, compare.rows.size))
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(color))
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row[0], style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content), color = color)
                    row.drop(1).forEachIndexed { c, cell ->
                        if (cell.isBlank()) return@forEachIndexed
                        Column {
                            Text(compare.header[c + 1], style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(cell, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                        }
                    }
                }
            }
        }
    }
}

/** Shown while the visuals pass runs: the shape of a chart, breathing. */
@Composable
fun VisualSkeleton(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        0.35f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse",
    )
    val shade = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.visual_drawing), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(0.9f, 0.6f, 0.75f, 0.4f).forEach { width ->
                Box(Modifier.fillMaxWidth(width).height(10.dp).clip(CircleShape).graphicsLayer { alpha = pulse }.background(shade))
            }
        }
    }
}
