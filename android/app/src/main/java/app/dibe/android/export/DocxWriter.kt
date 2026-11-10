package app.dibe.android.export

import app.dibe.android.data.TranscriptRecord
import app.dibe.android.data.jsTrim
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Port of desktop/src/lib/docx.ts as hand-written OOXML: the same paragraphs, sizes, colours and
 * spacing the desktop's `docx` package produces, set for paper (light page, Arial everywhere).
 */
internal object DocxWriter {
    private const val FONT = "Arial"

    // Sizes are half-points, spacing twips: Word's own units.
    private const val TITLE_SIZE = 44
    private const val HEADING_SIZE = 26
    private const val BODY_SIZE = 22
    private const val METADATA_SIZE = 18
    private const val LIST_INDENT = 360
    private const val LINE = 276
    private const val PARAGRAPH_GAP = 200
    private const val SECTION_GAP = 360

    private const val INK = "1A1C1F"
    private const val MUTED = "6E6E6E"
    private const val ACCENT = "1D4ED8"

    /** The summary's own headings, largest first, all smaller than the section heading above them. */
    private val SUMMARY_HEADINGS = listOf("Heading2" to 26, "Heading3" to 24, "Heading4" to 22)

    private class Text(
        val text: String,
        val size: Int = BODY_SIZE,
        val color: String = INK,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val rtl: Boolean = false,
    )

    fun write(record: TranscriptRecord, options: ExportOptions, out: OutputStream) {
        val title = record.name
        val zip = ZipOutputStream(out)
        for ((name, content) in listOf(
            "[Content_Types].xml" to CONTENT_TYPES,
            "_rels/.rels" to ROOT_RELS,
            "docProps/core.xml" to core(title),
            "word/_rels/document.xml.rels" to DOCUMENT_RELS,
            "word/styles.xml" to STYLES,
            "word/document.xml" to document(record, options),
        )) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
        // finish, not close: the caller owns [out].
        zip.finish()
    }

    fun document(record: TranscriptRecord, options: ExportOptions): String {
        val rtl = options.rtl
        val body = StringBuilder()
        // Bidi paragraphs carry no w:jc: their default (start) is the right edge in every reader, while
        // w:jc="right" means a different side in Word than in LibreOffice once w:bidi is set.
        val align = if (rtl) null else "left"
        body.paragraph(
            align = "center", bidi = rtl, after = SECTION_GAP,
            runs = listOf(Text(record.name, TITLE_SIZE, ACCENT, bold = true, rtl = rtl)),
        )
        val heading = { text: String ->
            body.paragraph(
                style = "Heading1", align = align, bidi = rtl, before = SECTION_GAP, after = 120,
                runs = listOf(Text(text, HEADING_SIZE, bold = true, rtl = rtl)),
            )
        }
        val both = options.content == ExportContent.BOTH
        if (options.content != ExportContent.SUMMARY) {
            if (both) heading(options.transcriptLabel)
            for (segment in record.segments) {
                val text = segment.text.jsTrim()
                if (text.isEmpty()) continue
                // Always the left-to-right form: an embedding would turn "00:00 --> 00:07" into "00:07 <-- 00:00".
                val metadata = listOf(
                    if (options.showTimestamps) "${formatTimestamp(segment.start, false, "", false)} --> ${formatTimestamp(segment.stop, false, "", false)}" else "",
                    speaker(segment, record, options),
                ).filter { it.isNotEmpty() }.joinToString("   ")
                if (metadata.isNotEmpty()) {
                    // Times read left to right whatever the transcript does; only the side of the page follows it.
                    body.paragraph(
                        align = if (rtl) "right" else "left", keepNext = true, before = PARAGRAPH_GAP, after = 40,
                        runs = listOf(Text(metadata, METADATA_SIZE, MUTED, bold = true)),
                    )
                }
                body.paragraph(align = align, bidi = rtl, line = LINE, after = PARAGRAPH_GAP, runs = listOf(Text(text, rtl = rtl)))
            }
        }
        val summary = record.summary ?: ""
        if (options.content != ExportContent.TRANSCRIPT && summary.jsTrim().isNotEmpty()) {
            if (both) heading(options.summaryLabel)
            summary(body, summary, rtl, align)
        }
        return "$XML_HEADER<w:document xmlns:w=\"$W\"><w:body>$body" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            "</w:sectPr></w:body></w:document>"
    }

    /** The AI summary's Markdown as real headings, bold and italic runs, lists, quotes and rules. */
    private fun summary(body: StringBuilder, summary: String, rtl: Boolean, align: String?) {
        fun runs(items: List<Run>, size: Int = BODY_SIZE, bold: Boolean = false, italic: Boolean = false, color: String = INK) =
            items.map { Text(it.text, size, color, bold || it.bold, italic || it.italic, it.code, rtl) }
        for (block in parseMarkdown(summary)) {
            when (block) {
                is Block.Heading -> {
                    val (style, size) = SUMMARY_HEADINGS[minOf(block.rank, SUMMARY_HEADINGS.lastIndex)]
                    body.paragraph(
                        style = style, align = align, bidi = rtl, keepNext = true, before = 280, after = 100,
                        runs = runs(block.runs, size, bold = true),
                    )
                }
                Block.Rule -> body.paragraph(align = align, bidi = rtl, after = PARAGRAPH_GAP, rule = true, runs = emptyList())
                is Block.Quote -> body.paragraph(
                    align = align, bidi = rtl, line = LINE, after = PARAGRAPH_GAP, indent = LIST_INDENT,
                    runs = runs(block.runs, italic = true, color = MUTED),
                )
                is Block.Bullet, is Block.Number -> {
                    // The marker hangs in the indent, so wrapped lines line up under the text.
                    val (depth, marker, items) = when (block) {
                        is Block.Bullet -> Triple(block.depth, "•", block.runs)
                        is Block.Number -> Triple(block.depth, block.marker, block.runs)
                    }
                    body.paragraph(
                        align = align, bidi = rtl, line = LINE, after = 80, indent = LIST_INDENT * (depth + 1), hanging = LIST_INDENT,
                        runs = listOf(Text("$marker\t", rtl = rtl)) + runs(items),
                    )
                }
                is Block.Paragraph -> body.paragraph(align = align, bidi = rtl, line = LINE, after = PARAGRAPH_GAP, runs = runs(block.runs))
            }
        }
    }

    /** Children in the order the schema requires (CT_PPr, CT_RPr); Word rejects files that reorder them. */
    private fun StringBuilder.paragraph(
        runs: List<Text>,
        style: String? = null,
        align: String? = null,
        bidi: Boolean = false,
        keepNext: Boolean = false,
        before: Int? = null,
        after: Int? = null,
        line: Int? = null,
        indent: Int? = null,
        hanging: Int? = null,
        rule: Boolean = false,
    ) {
        append("<w:p><w:pPr>")
        if (style != null) append("<w:pStyle w:val=\"$style\"/>")
        if (keepNext) append("<w:keepNext/>")
        if (rule) append("<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"C8C8C8\"/></w:pBdr>")
        if (bidi) append("<w:bidi/>")
        if (before != null || after != null || line != null) {
            append("<w:spacing")
            before?.let { append(" w:before=\"$it\"") }
            after?.let { append(" w:after=\"$it\"") }
            line?.let { append(" w:line=\"$it\" w:lineRule=\"auto\"") }
            append("/>")
        }
        if (indent != null) append("<w:ind w:start=\"$indent\"${hanging?.let { " w:hanging=\"$it\"" } ?: ""}/>")
        if (align != null) append("<w:jc w:val=\"$align\"/>")
        append("</w:pPr>")
        for (run in runs) {
            append("<w:r><w:rPr>")
            if (run.code) append("<w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\" w:cs=\"Courier New\"/>")
            if (run.bold) append("<w:b/><w:bCs/>")
            if (run.italic) append("<w:i/><w:iCs/>")
            append("<w:color w:val=\"${run.color}\"/><w:sz w:val=\"${run.size}\"/><w:szCs w:val=\"${run.size}\"/>")
            if (run.rtl) append("<w:rtl/>")
            append("</w:rPr>")
            // Tabs and line breaks are elements in OOXML; inside w:t Word would show them as spaces.
            val pieces = xmlText(run.text).split(Regex("(?=[\t\n])|(?<=[\t\n])"))
            for (piece in pieces) {
                when (piece) {
                    "" -> Unit
                    "\t" -> append("<w:tab/>")
                    "\n" -> append("<w:br/>")
                    else -> append("<w:t xml:space=\"preserve\">").append(piece).append("</w:t>")
                }
            }
            append("</w:r>")
        }
        append("</w:p>")
    }

    /** Escaped, and without the characters XML 1.0 cannot hold at all (one would make Word call the file corrupt). */
    fun xmlText(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val code = value.codePointAt(index)
            index += Character.charCount(code)
            val allowed = code == 0x9 || code == 0xA || code == 0xD || code in 0x20..0xD7FF || code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF
            when {
                !allowed -> Unit
                code == '&'.code -> out.append("&amp;")
                code == '<'.code -> out.append("&lt;")
                code == '>'.code -> out.append("&gt;")
                code == '"'.code -> out.append("&quot;")
                code == 0xD -> Unit
                else -> out.appendCodePoint(code)
            }
        }
        return out.toString()
    }

    private fun core(title: String) =
        "$XML_HEADER<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${xmlText(title)}</dc:title><dc:description>Transcript exported from DIBE</dc:description></cp:coreProperties>"

    private const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private const val CONTENT_TYPES = XML_HEADER +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
        "</Types>"

    private const val ROOT_RELS = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
        "</Relationships>"

    private const val DOCUMENT_RELS = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    /** Fonts on the document default, so every paragraph and anything typed later is Arial, not Word's own default. */
    private val STYLES = XML_HEADER +
        "<w:styles xmlns:w=\"$W\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"$FONT\" w:hAnsi=\"$FONT\" w:cs=\"$FONT\"/>" +
        "<w:color w:val=\"$INK\"/><w:sz w:val=\"$BODY_SIZE\"/><w:szCs w:val=\"$BODY_SIZE\"/></w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:line=\"$LINE\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        (1..4).joinToString("") { level ->
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$level\"><w:name w:val=\"heading $level\"/>" +
                "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:outlineLvl w:val=\"${level - 1}\"/></w:pPr></w:style>"
        } +
        "</w:styles>"
}
