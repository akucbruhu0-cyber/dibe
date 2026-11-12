package app.dibe.android.export

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
    private const val MARGIN = 60f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
    private const val BOTTOM = PAGE_HEIGHT - MARGIN
    // StaticLayout multiplies the font's own line height (Rubik's is ~1.19 em): 1.35 here is the desktop's 1.6 em.
    private const val LINE_HEIGHT = 1.35f

    private const val INK = 0xFF1A1C1F.toInt()
    private const val MUTED = 0xFF6E6E6E.toInt()
    private const val ACCENT = 0xFF26794A.toInt() // the app's green, as on the desktop PDF
    private const val LINE = 0xFFE3E6E3.toInt()
    private const val BUBBLE = 0xFFBFE9CB.toInt() // the app's light green, behind a follow-up question

    private val SUMMARY_HEADING_SIZES = floatArrayOf(14.5f, 12.5f, 11.5f)

    /** A task item's `[ ]` or `[x]`, which the Markdown reader leaves in the text. */
    private val taskMark = Regex("^\\[([ xX])\\]\\s+")

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
            val footer = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular; textSize = 8.5f; color = MUTED }
            for ((index, operations) in pages.pages.withIndex()) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create())
                operations.forEach { it(page.canvas) }
                // Left to right always, or the page's direction would print "31 / 7".
                val number = layout("${index + 1} / ${pages.pages.size}", footer, CONTENT_WIDTH, Layout.Alignment.ALIGN_CENTER, TextDirectionHeuristics.LTR, 1f)
                page.canvas.drawAt(number, MARGIN, PAGE_HEIGHT - 28f - number.height)
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

        fun typeset(text: CharSequence, paint: TextPaint, width: Float = CONTENT_WIDTH, spacing: Float = LINE_HEIGHT, center: Boolean = false, justify: Boolean = false) =
            layout(text, paint, width, if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL, direction, spacing, justify)

        /** Body text runs edge to edge, as in a printed book; a paragraph's last line keeps to its start side. */
        fun body(text: CharSequence, paint: TextPaint, width: Float = CONTENT_WIDTH) = typeset(text, paint, width, justify = true)

        /** A hairline across the content, [color] and [weight] points thick, at the current line. */
        fun hairline(color: Int, weight: Float, width: Float = CONTENT_WIDTH, left: Float = MARGIN) {
            val y = pages.y
            val paint = Paint().apply { this.color = color }
            pages.draw { it.drawRect(left, y, left + width, y + weight, paint) }
            pages.y += weight
        }

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
            // Transcript / Summary: the document's two parts, each opened by a hairline across the page.
            val heading = { text: String ->
                pages.gap(26f)
                val layout = typeset(plain(text), paint(15f, ACCENT, bold), spacing = 1.1f)
                pages.keep(layout.height + 6f + 40f)
                pages.text(layout, MARGIN)
                pages.gap(5f)
                hairline(LINE, 1f)
            }
            if (title.isNotEmpty()) {
                pages.text(typeset(plain(title), paint(22f, INK, bold), spacing = 1.1f, center = true), MARGIN)
                // A short green rule under the title, the document's one ornament.
                pages.gap(10f)
                hairline(ACCENT, 2f, 48f, MARGIN + (CONTENT_WIDTH - 48f) / 2)
                pages.gap(14f)
            }
            if (options.content != ExportContent.SUMMARY) {
                if (both) heading(options.transcriptLabel)
                val body = paint(11f, INK)
                val metadataPaint = paint(9f, MUTED, bold)
                for (segment in record.segments) {
                    val text = segment.text.jsTrim()
                    if (text.isEmpty()) continue
                    pages.gap(10f)
                    val bodyLayout = body(plain(text), body)
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
            val thread = exportedThread(record, options)
            if (thread.isNotEmpty()) {
                heading(options.threadLabel)
                for (entry in thread) {
                    bubble(entry.question.jsTrim())
                    summary(entry.answer.jsTrim())
                }
            }
        }

        /** A follow-up question in a chat bubble, on the side a chat puts what you sent. */
        private fun bubble(question: String) {
            val paint = paint(11f, INK)
            val natural = typeset(plain(question), paint, CONTENT_WIDTH * 0.85f - 28f, spacing = 1.2f)
            val width = (0 until natural.lineCount).maxOf { natural.getLineWidth(it) } + 2f
            val text = typeset(plain(question), paint, width, spacing = 1.2f)
            val w = width + 28f
            val h = text.height + 16f
            pages.gap(22f)
            // The question moves to the next page with the start of its answer.
            pages.keep(h + 40f)
            val left = if (rtl) MARGIN + CONTENT_WIDTH - w else MARGIN
            val top = pages.y
            pages.draw { canvas ->
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BUBBLE }
                canvas.drawRoundRect(RectF(left, top, left + w, top + h), 14f, 14f, fill)
                // The corner nearest the page edge stays tight, like a chat bubble's tail.
                val corner = if (rtl) left + w - 14f else left
                canvas.drawRoundRect(RectF(corner, top, corner + 14f, top + 14f), 4f, 4f, fill)
                canvas.drawAt(text, left + 14f, top + 8f)
            }
            pages.y += h
            pages.gap(2f)
        }

        private fun summary(summary: String) {
            val body = paint(11f, INK)
            val (marked, visuals) = extractVisuals(summary)
            for (block in parseMarkdown(marked)) {
                when (block) {
                    is Block.Heading -> {
                        pages.gap(18f)
                        val size = SUMMARY_HEADING_SIZES[minOf(block.rank, SUMMARY_HEADING_SIZES.lastIndex)]
                        val layout = typeset(runs(block.runs), paint(size, if (block.rank == 0) ACCENT else INK, bold), spacing = 1.1f)
                        // A heading never ends a page alone: it moves to the next page with what it introduces.
                        pages.keep(layout.height + 40f)
                        pages.text(layout, MARGIN)
                        pages.gap(2f)
                    }
                    Block.Rule -> {
                        pages.gap(14f)
                        hairline(LINE, 1f)
                        pages.gap(14f)
                    }
                    is Block.Quote -> {
                        pages.gap(12f)
                        val layout = body(runs(block.runs), paint(11f, MUTED), CONTENT_WIDTH - 14f)
                        // A green bar on the start side marks the quote.
                        pages.keep(layout.height.toFloat())
                        val barX = if (rtl) MARGIN + CONTENT_WIDTH - 2f else MARGIN
                        val barTop = pages.y
                        val height = minOf(layout.height.toFloat(), pages.space)
                        val bar = Paint().apply { color = ACCENT }
                        pages.draw { it.drawRect(barX, barTop, barX + 2f, barTop + height, bar) }
                        pages.text(layout, if (rtl) MARGIN else MARGIN + 14f)
                    }
                    is Block.Bullet, is Block.Number -> {
                        val (depth, marker, items) = when (block) {
                            is Block.Bullet -> Triple(block.depth, "•", block.runs)
                            is Block.Number -> Triple(block.depth, block.marker, block.runs)
                            }
                        pages.gap(5f)
                        val indent = depth * 14f
                        val markerWidth = 18f
                        val textWidth = CONTENT_WIDTH - indent - markerWidth
                        // `- [ ] task` draws as a box, ticked when done, rather than its brackets.
                        val task = if (block is Block.Bullet) taskMark.find(items.firstOrNull()?.text ?: "") else null
                        val text = if (task == null) items else listOf(items[0].copy(text = items[0].text.substring(task.value.length))) + items.drop(1)
                        val markerLayout = typeset(marker, paint(11f, ACCENT, bold), markerWidth)
                        val textLayout = body(runs(text), body, textWidth)
                        // The marker sits on the start side: the right edge in a right-to-left document.
                        val markerX = if (rtl) MARGIN + CONTENT_WIDTH - indent - markerWidth else MARGIN + indent
                        val textX = if (rtl) MARGIN else MARGIN + indent + markerWidth
                        pages.keep(maxOf(markerLayout.height, textLayout.height).toFloat())
                        val y = pages.y
                        if (task == null) {
                            pages.draw { it.drawAt(markerLayout, markerX, y) }
                        } else {
                            val done = task.groupValues[1] != " "
                            val boxX = if (rtl) markerX + markerWidth - 9f else markerX
                            val boxTop = y + (textLayout.getLineBottom(0) - 9f) / 2
                            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = ACCENT
                                strokeWidth = 1.2f
                                style = if (done) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
                            }
                            pages.draw { it.drawRoundRect(boxX, boxTop, boxX + 9f, boxTop + 9f, 2f, 2f, box) }
                        }
                        pages.text(textLayout, textX)
                    }
                    is Block.Paragraph -> {
                        val visual = visualAt(block.runs.joinToString("") { it.text }, visuals)
                        if (visual != null) {
                            pages.gap(16f)
                            val drawing = VisualDrawing(visual, CONTENT_WIDTH, regular, bold)
                            // ponytail: a visual taller than a page is clipped by the page edge; none has had 8 rows that tall.
                            pages.keep(drawing.height)
                            val y = pages.y
                            pages.draw { it.save(); it.translate(MARGIN, y); drawing.draw(it); it.restore() }
                            pages.y += drawing.height
                            continue
                        }
                        pages.gap(10f)
                        pages.text(body(runs(block.runs), body), MARGIN)
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
        justify: Boolean = false,
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
            .setAlignment(alignment)
            .setJustificationMode(if (justify) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
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
