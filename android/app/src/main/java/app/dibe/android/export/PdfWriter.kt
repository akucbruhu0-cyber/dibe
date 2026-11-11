package app.dibe.android.export

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.TypefaceSpan
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import app.dibe.android.R
import app.dibe.android.data.TranscriptRecord
import app.dibe.android.data.jsTrim
import app.dibe.android.ui.transcript.extractVisuals
import app.dibe.android.ui.transcript.visualAt
import java.io.OutputStream

/**
 * Port of desktop/src/lib/pdf/transcript-document.tsx on [PdfDocument]: A4, Rubik (Latin, Hebrew and
 * Cyrillic in one family), the same sizes, colours and gaps, and a "page / pages" footer.
 * StaticLayout runs the Unicode bidi algorithm, so Hebrew reads right to left with its English intact.
 */
internal object PdfWriter {
    private const val PAGE_WIDTH = 595 // A4 in points
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 52f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
    private const val BOTTOM = PAGE_HEIGHT - MARGIN
    private const val LINE_HEIGHT = 1.45f

    private const val INK = 0xFF1A1C1F.toInt()
    private const val MUTED = 0xFF6E6E6E.toInt()
    private const val ACCENT = 0xFF145A1E.toInt() // brand green; the desktop PDF uses its blue primary

    private val SUMMARY_HEADING_SIZES = floatArrayOf(14f, 12.5f, 11.5f)

    /**
     * Bidi isolates and marks have no glyph in the font and would draw as boxes; the layout resolves
     * each line's direction itself, so they can go.
     */
    private fun plain(text: String) = text.replace(Regex("[؜‎‏‪-‮⁦-⁩]"), "")

    fun write(context: Context, record: TranscriptRecord, options: ExportOptions, out: OutputStream) {
        val regular = font(context, R.font.rubik_regular, Typeface.NORMAL)
        val bold = font(context, R.font.rubik_bold, Typeface.BOLD)
        val pages = Pages()
        Draft(pages, regular, bold, options.rtl).compose(record, options)

        val document = PdfDocument()
        try {
            val footer = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular; textSize = 9f; color = MUTED }
            for ((index, operations) in pages.pages.withIndex()) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create())
                operations.forEach { it(page.canvas) }
                // Left to right always, or the page's direction would print "31 / 7".
                val number = layout("${index + 1} / ${pages.pages.size}", footer, CONTENT_WIDTH, Layout.Alignment.ALIGN_CENTER, TextDirectionHeuristics.LTR, 1f)
                page.canvas.drawAt(number, MARGIN, PAGE_HEIGHT - 24f - number.height)
                document.finishPage(page)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    private fun font(context: Context, id: Int, style: Int): Typeface =
        try {
            ResourcesCompat.getFont(context, id)
        } catch (e: Resources.NotFoundException) {
            Log.w("PdfWriter", "bundled font missing", e)
            null
        } ?: Typeface.create(Typeface.DEFAULT, style)

    /** Pages as lists of draw calls, so the footer can know the page count before anything is drawn. */
    private class Pages {
        val pages = mutableListOf(mutableListOf<(Canvas) -> Unit>())
        var y = MARGIN
        val atTop get() = y == MARGIN
        val space get() = BOTTOM - y

        fun newPage() {
            pages += mutableListOf<(Canvas) -> Unit>()
            y = MARGIN
        }

        /** Gaps collapse at the top of a page, where they would only push the text down. */
        fun gap(points: Float) {
            if (!atTop) y = minOf(y + points, BOTTOM)
        }

        fun draw(operation: (Canvas) -> Unit) {
            pages.last() += operation
        }

        /** Starts a new page unless [height] fits here, or cannot fit on any page. */
        fun keep(height: Float) {
            if (height > space && !atTop && height <= BOTTOM - MARGIN) newPage()
        }

        /** Draws [layout] at [x], breaking it between lines across as many pages as it needs. */
        fun text(layout: StaticLayout, x: Float) {
            val bottoms = IntArray(layout.lineCount) { layout.getLineBottom(it) }
            var from = 0
            while (from < bottoms.size) {
                var end = fitLines(bottoms, from, space)
                if (end == from) {
                    if (!atTop) {
                        newPage()
                        continue
                    }
                    end = from + 1 // a line taller than a page still has to go somewhere
                }
                val sliceTop = layout.getLineTop(from).toFloat()
                val sliceHeight = bottoms[end - 1] - sliceTop
                val top = y
                draw { canvas ->
                    canvas.save()
                    canvas.clipRect(x, top, x + layout.width, top + sliceHeight)
                    canvas.translate(x, top - sliceTop)
                    layout.draw(canvas)
                    canvas.restore()
                }
                y += sliceHeight
                from = end
                if (from < bottoms.size) newPage()
            }
        }
    }

    private class Draft(val pages: Pages, val regular: Typeface, val bold: Typeface, val rtl: Boolean) {
        val direction: TextDirectionHeuristic = if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR

        fun paint(size: Float, color: Int, face: Typeface = regular) =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size; this.color = color }

        fun typeset(text: CharSequence, paint: TextPaint, width: Float = CONTENT_WIDTH, spacing: Float = LINE_HEIGHT, center: Boolean = false) =
            layout(text, paint, width, if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL, direction, spacing)

        /** Bold runs in Rubik Bold; italics and code read as plain text, as on the desktop: the font has no italic face. */
        fun runs(runs: List<Run>): CharSequence {
            val text = SpannableStringBuilder()
            for (run in runs) {
                val start = text.length
                text.append(plain(run.text))
                if (run.bold) text.setSpan(TypefaceSpan(bold), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return text
        }

        fun compose(record: TranscriptRecord, options: ExportOptions) {
            val title = record.name.jsTrim()
            val both = options.content == ExportContent.BOTH
            val summary = record.summary?.jsTrim() ?: ""
            val heading = { text: String ->
                pages.gap(18f)
                pages.text(typeset(plain(text), paint(13f, INK, bold)), MARGIN)
                pages.gap(2f)
            }
            if (title.isNotEmpty()) {
                pages.text(typeset(plain(title), paint(20f, ACCENT, bold), spacing = 1f, center = true), MARGIN)
                pages.gap(10f)
            }
            if (options.content != ExportContent.SUMMARY) {
                if (both) heading(options.transcriptLabel)
                val body = paint(11f, INK)
                val metadataPaint = paint(9f, MUTED, bold)
                for (segment in record.segments) {
                    val text = segment.text.jsTrim()
                    if (text.isEmpty()) continue
                    pages.gap(12f)
                    val bodyLayout = typeset(plain(text), body)
                    val metadata = segmentMetadata(segment, record, options)
                    if (metadata.isNotEmpty()) {
                        // Times read left to right whatever the transcript does; only the side follows it.
                        val metadataLayout = layout(
                            plain(metadata), metadataPaint, CONTENT_WIDTH,
                            if (rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL, TextDirectionHeuristics.LTR, 1f,
                        )
                        // The label belongs to the line under it: they move to the next page together.
                        pages.keep(metadataLayout.height + 3f + bodyLayout.getLineBottom(0))
                        pages.text(metadataLayout, MARGIN)
                        pages.gap(3f)
                    }
                    pages.text(bodyLayout, MARGIN)
                }
            }
            if (options.content != ExportContent.TRANSCRIPT && summary.isNotEmpty()) {
                if (both) heading(options.summaryLabel)
                summary(summary)
            }
        }

        private fun summary(summary: String) {
            val body = paint(11f, INK)
            val (marked, visuals) = extractVisuals(summary)
            for (block in parseMarkdown(marked)) {
                when (block) {
                    is Block.Heading -> {
                        pages.gap(14f)
                        val size = SUMMARY_HEADING_SIZES[minOf(block.rank, SUMMARY_HEADING_SIZES.lastIndex)]
                        pages.text(typeset(runs(block.runs), paint(size, INK, bold), spacing = 1f), MARGIN)
                    }
                    Block.Rule -> {
                        pages.gap(12f)
                        val y = pages.y
                        val rule = Paint().apply { color = MUTED; alpha = (0.4f * 255).toInt(); strokeWidth = 1f }
                        pages.draw { it.drawLine(MARGIN, y, MARGIN + CONTENT_WIDTH, y, rule) }
                        pages.y += 1f
                        pages.gap(12f)
                    }
                    is Block.Quote -> {
                        pages.gap(10f)
                        pages.text(typeset(runs(block.runs), paint(11f, MUTED), CONTENT_WIDTH - 20f), MARGIN + 10f)
                    }
                    is Block.Bullet, is Block.Number -> {
                        val (depth, marker, items) = when (block) {
                            is Block.Bullet -> Triple(block.depth, "•", block.runs)
                            is Block.Number -> Triple(block.depth, block.marker, block.runs)
                            }
                        pages.gap(4f)
                        val indent = depth * 14f
                        val markerWidth = 16f
                        val textWidth = CONTENT_WIDTH - indent - markerWidth
                        val markerLayout = typeset(marker, paint(11f, MUTED), markerWidth)
                        val textLayout = typeset(runs(items), body, textWidth)
                        // The marker sits on the start side: the right edge in a right-to-left document.
                        val markerX = if (rtl) MARGIN + CONTENT_WIDTH - indent - markerWidth else MARGIN + indent
                        val textX = if (rtl) MARGIN else MARGIN + indent + markerWidth
                        pages.keep(maxOf(markerLayout.height, textLayout.height).toFloat())
                        val y = pages.y
                        pages.draw { it.drawAt(markerLayout, markerX, y) }
                        pages.text(textLayout, textX)
                    }
                    is Block.Paragraph -> {
                        val visual = visualAt(block.runs.joinToString("") { it.text }, visuals)
                        if (visual != null) {
                            pages.gap(14f)
                            val drawing = VisualDrawing(visual, CONTENT_WIDTH, regular, bold)
                            // ponytail: a visual taller than a page is clipped by the page edge; none has had 8 rows that tall.
                            pages.keep(drawing.height)
                            val y = pages.y
                            pages.draw { it.save(); it.translate(MARGIN, y); drawing.draw(it); it.restore() }
                            pages.y += drawing.height
                            continue
                        }
                        pages.gap(12f)
                        pages.text(typeset(runs(block.runs), body), MARGIN)
                    }
                }
            }
        }
    }

    private fun layout(
        text: CharSequence,
        paint: TextPaint,
        width: Float,
        alignment: Layout.Alignment,
        direction: TextDirectionHeuristic,
        spacing: Float,
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
            .setAlignment(alignment)
            .setTextDirection(direction)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .build()

    private fun Canvas.drawAt(layout: StaticLayout, x: Float, y: Float) {
        save()
        translate(x, y)
        layout.draw(this)
        restore()
    }
}

/**
 * Index after the last line, starting at [from], that fits in [space] points; [from] itself when
 * not even one does. [bottoms] are each line's bottom within its layout, where lines are contiguous.
 */
internal fun fitLines(bottoms: IntArray, from: Int, space: Float): Int {
    val top = if (from == 0) 0 else bottoms[from - 1]
    var end = from
    while (end < bottoms.size && bottoms[end] - top <= space) end++
    return end
}
