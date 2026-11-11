package app.dibe.android.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import app.dibe.android.ui.transcript.Visual
import app.dibe.android.ui.transcript.rtl
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * A summary's visual drawn for a file, in points, on any [Canvas]: a PDF page, or a bitmap for Word
 * and HTML. Every label is a [StaticLayout], so Hebrew wraps and reads right to left with its numbers
 * intact. The layout runs once in the constructor; [height] is known before anything is drawn.
 */
internal class VisualDrawing(
    private val visual: Visual,
    private val width: Float,
    private val regular: Typeface = Typeface.DEFAULT,
    private val bold: Typeface = Typeface.DEFAULT_BOLD,
) {
    private val rtl = visual.rtl() ?: false
    private val ops = mutableListOf<(Canvas) -> Unit>()
    private val inner = width - 2 * PAD
    val height: Float

    init {
        var y = PAD
        if (visual.title.isNotBlank()) y += text(visual.title, 11f, INK, bold, 0f, y, inner).height + 8f
        y = when (visual) {
            is Visual.Chart -> if (visual.pie) donut(visual, y) else bars(visual, y)
            is Visual.Flow -> flow(visual, y)
            is Visual.Timeline -> timeline(visual, y)
            is Visual.Tree -> tree(visual, y)
            is Visual.Compare -> compare(visual, y)
        }
        height = y + PAD
    }

    fun draw(canvas: Canvas) {
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = LINE }
        canvas.drawRoundRect(RectF(0.5f, 0.5f, width - 0.5f, height - 0.5f), 10f, 10f, border)
        ops.forEach { it(canvas) }
    }

    /** PNG at [scale] pixels per point, on white: Word and browsers show it as is. */
    fun png(scale: Float): ByteArray {
        val bitmap = Bitmap.createBitmap((width * scale).roundToInt(), (height * scale).roundToInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        draw(canvas)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bitmap.recycle() }
    }

    /** Left edge of a box [w] wide that starts [start] points from the content's start side. */
    private fun x(start: Float, w: Float) = PAD + if (rtl) inner - start - w else start

    private fun paint(size: Float, color: Int, face: Typeface) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size; this.color = color }

    private fun layout(text: String, size: Float, color: Int, face: Typeface, w: Float, center: Boolean = false): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint(size, color, face), maxOf(1, w.toInt()))
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
            .setLineSpacing(0f, 1.25f)
            .setIncludePad(false)
            .build()

    /** Lays [text] out [w] wide at [start] from the start side, [top] down, and queues it. */
    private fun text(text: String, size: Float, color: Int, face: Typeface, start: Float, top: Float, w: Float, center: Boolean = false): StaticLayout {
        val l = layout(text, size, color, face, w, center)
        val left = x(start, w)
        ops += { it.save(); it.translate(left, top); l.draw(it); it.restore() }
        return l
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun bars(chart: Visual.Chart, top: Float): Float {
        val max = chart.rows.maxOf { it.value }
        var y = top
        chart.rows.forEachIndexed { i, row ->
            val value = layout(number(row.value), 10f, INK, bold, inner)
            val valueWidth = value.getLineWidth(0) + 2f
            val label = text(row.label, 10f, INK, regular, 0f, y, inner - valueWidth - 8f)
            text(number(row.value), 10f, INK, bold, inner - valueWidth, y, valueWidth)
            y += maxOf(label.height, value.height) + 3f
            val barTop = y
            val w = (row.value / max).toFloat() * inner
            val left = x(0f, w)
            val color = colorOf(i, row.color)
            ops += { it.drawRoundRect(RectF(left, barTop, left + w, barTop + 6f), 3f, 3f, fill(color)) }
            y += 6f + if (i < chart.rows.lastIndex) 8f else 0f
        }
        return y
    }

    private fun donut(chart: Visual.Chart, top: Float): Float {
        val size = 96f
        val stroke = 14f
        val total = chart.rows.sumOf { it.value }
        val ringLeft = x(0f, size)
        val r = (size - stroke) / 2
        // Round ends reach half a stroke past each arc, so slices sit a stroke (plus a hairline) apart.
        val gap = if (chart.rows.size > 1) Math.toDegrees(((stroke + 3f) / r).toDouble()).toFloat() else 0f
        var start = -90f
        val arcs = chart.rows.mapIndexed { i, row ->
            val sweep = (row.value / total * 360).toFloat()
            // Clockwise from the top in left-to-right pages, counter-clockwise in right-to-left ones.
            val from = if (rtl) 180f - start - sweep else start
            Triple(from + gap / 2, maxOf(sweep - gap, 0.5f), colorOf(i, row.color)).also { start += sweep }
        }
        ops += { canvas ->
            val oval = RectF(ringLeft + stroke / 2, top + stroke / 2, ringLeft + size - stroke / 2, top + size - stroke / 2)
            arcs.forEach { (from, sweep, color) ->
                val paint = fill(color).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = if (arcs.size > 1) Paint.Cap.ROUND else Paint.Cap.BUTT }
                canvas.drawArc(oval, from, sweep, false, paint)
            }
        }
        val legendStart = size + 16f
        val legendWidth = inner - legendStart
        var y = top
        val rows = chart.rows.mapIndexed { i, row ->
            val percent = "${(row.value / total * 100).roundToInt()}%"
            val value = layout(percent, 10f, INK, bold, legendWidth)
            val valueWidth = value.getLineWidth(0) + 2f
            val label = layout(row.label, 10f, INK, regular, legendWidth - 14f - valueWidth - 6f)
            Triple(i, label, value to valueWidth).also { y += maxOf(label.height, value.height) + 6f }
        }
        // The legend sits beside the ring, centred on it when it is the shorter of the two.
        var ly = top + maxOf(0f, (size - (y - top - 6f)) / 2)
        rows.forEach { (i, label, value) ->
            val rowTop = ly
            val dot = x(legendStart, 8f)
            val color = colorOf(i, chart.rows[i].color)
            ops += { it.drawCircle(dot + 4f, rowTop + 6.5f, 4f, fill(color)) }
            text(chart.rows[i].label, 10f, INK, regular, legendStart + 14f, rowTop, label.width.toFloat())
            text(value.first.text.toString(), 10f, INK, bold, inner - value.second, rowTop, value.second)
            ly += maxOf(label.height, value.first.height) + 6f
        }
        return top + maxOf(size, ly - top - 6f)
    }

    private fun flow(flow: Visual.Flow, top: Float): Float {
        var y = top
        flow.layers.forEachIndexed { level, names ->
            if (level > 0) {
                val arrowTop = y
                val cx = PAD + inner / 2
                val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; strokeWidth = 1.2f; style = Paint.Style.STROKE }
                ops += { it.drawPath(Path().apply { moveTo(cx, arrowTop + 2f); lineTo(cx, arrowTop + 14f); moveTo(cx - 4f, arrowTop + 10f); lineTo(cx, arrowTop + 14f); lineTo(cx + 4f, arrowTop + 10f) }, ink) }
                y += 18f
            }
            // Boxes in rows, each row centred.
            val boxes = names.map { name ->
                val l = layout(name, 10f, INK, regular, minOf(inner - 16f, layout(name, 10f, INK, regular, inner).let { lay -> (0 until lay.lineCount).maxOf { lay.getLineWidth(it) } } + 2f), center = true)
                l to l.width + 16f
            }
            var i = 0
            while (i < boxes.size) {
                var rowWidth = boxes[i].second
                var end = i + 1
                while (end < boxes.size && rowWidth + 6f + boxes[end].second <= inner) rowWidth += 6f + boxes[end++].second
                val rowHeight = boxes.subList(i, end).maxOf { it.first.height + 10f }
                var start = (inner - rowWidth) / 2
                for ((l, w) in boxes.subList(i, end)) {
                    val left = x(start, w)
                    val boxTop = y
                    val color = Palette[level % Palette.size]
                    ops += { canvas ->
                        val rect = RectF(left, boxTop, left + w, boxTop + rowHeight)
                        canvas.drawRoundRect(rect, 7f, 7f, fill(color).apply { alpha = 26 })
                        canvas.drawRoundRect(rect, 7f, 7f, fill(color).apply { style = Paint.Style.STROKE; strokeWidth = 1f; alpha = 150 })
                        canvas.save(); canvas.translate(left + 8f, boxTop + (rowHeight - l.height) / 2); l.draw(canvas); canvas.restore()
                    }
                    start += w + 6f
                }
                y += rowHeight + 6f
                i = end
            }
            y -= 6f
        }
        return y
    }

    private fun timeline(timeline: Visual.Timeline, top: Float): Float {
        var y = top
        val textStart = 20f
        val textWidth = inner - textStart
        timeline.events.forEachIndexed { i, event ->
            val rowTop = y
            y += text(event.time, 8.5f, ACCENT, regular, textStart, y, textWidth).height + 1f
            y += text(event.title, 10f, INK, bold, textStart, y, textWidth).height
            if (event.detail.isNotBlank()) y += 1f + text(event.detail, 10f, MUTED, regular, textStart, y, textWidth).height
            val dot = x(2f, 8f)
            val color = colorOf(i, null)
            val last = i == timeline.events.lastIndex
            val lineBottom = y + 12f
            ops += { canvas ->
                if (!last) canvas.drawRect(dot + 3.25f, rowTop + 10f, dot + 4.75f, lineBottom, fill(LINE))
                canvas.drawCircle(dot + 4f, rowTop + 5f, 4f, fill(color))
            }
            if (!last) y += 12f
        }
        return y
    }

    /** Paper can't click: every node, and where each of its choices leads. */
    private fun tree(tree: Visual.Tree, top: Float): Float {
        var y = top
        tree.nodes.values.forEachIndexed { i, node ->
            if (i > 0) y += 8f
            y += text(node.title, 10f, INK, bold, 0f, y, inner).height
            if (node.body.isNotBlank()) y += 1f + text(node.body, 8.5f, MUTED, regular, 0f, y, inner).height
            node.choices.forEach { (label, target) ->
                y += 2f + text("$label – ${tree.nodes[target]?.title ?: target}", 10f, INK, regular, 10f, y, inner - 10f).height
            }
        }
        return y
    }

    private fun compare(compare: Visual.Compare, top: Float): Float {
        var y = top
        compare.rows.forEachIndexed { i, row ->
            if (i > 0) y += 8f
            val cardTop = y
            val color = colorOf(i, null)
            y += text(row[0], 10f, color, bold, 9f, y, inner - 9f).height
            row.drop(1).forEachIndexed { c, cell ->
                if (cell.isBlank()) return@forEachIndexed
                y += 2f + text(compare.header[c + 1], 8.5f, MUTED, regular, 9f, y, inner - 9f).height
                y += text(cell, 10f, INK, regular, 9f, y, inner - 9f).height
            }
            val edge = x(0f, 3f)
            val cardBottom = y
            ops += { it.drawRoundRect(RectF(edge, cardTop, edge + 3f, cardBottom), 1.5f, 1.5f, fill(color)) }
        }
        return y
    }

    private companion object {
        const val PAD = 12f
        const val INK = 0xFF1A1C1F.toInt()
        const val MUTED = 0xFF6E6E6E.toInt()
        const val LINE = 0xFFDDDDDD.toInt()
        const val ACCENT = 0xFF007AFF.toInt()

        /** The screen's palette (ui/transcript/VisualViews.kt), so the file matches the app. */
        val Palette = intArrayOf(0xFF007AFF.toInt(), 0xFFFF3B30.toInt(), 0xFF34C759.toInt(), 0xFFFF9500.toInt(), 0xFFAF52DE.toInt(), 0xFF5AC8FA.toInt(), 0xFFFF2D55.toInt(), 0xFFFFCC00.toInt())

        fun colorOf(index: Int, own: Long?) = own?.let { (0xFF000000 or it).toInt() } ?: Palette[index % Palette.size]

        fun number(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}
