package app.dibe.android.export

import android.content.Context
import app.dibe.android.data.Segment
import app.dibe.android.data.TranscriptRecord
import app.dibe.android.data.jsTrim
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream

/** The desktop's nine formats (desktop/src/components/format-select.tsx). */
enum class ExportFormat(val extension: String, val mime: String) {
    TXT("txt", "text/plain"),
    SRT("srt", "application/x-subrip"),
    VTT("vtt", "text/vtt"),
    HTML("html", "text/html"),
    PDF("pdf", "application/pdf"),
    JSON("json", "application/json"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    CSV("csv", "text/csv"),
    MD("md", "text/markdown"),
}

enum class ExportContent { TRANSCRIPT, SUMMARY, BOTH }

/** Mirrors desktop `TranscriptExportOptions`. */
data class ExportOptions(
    val format: ExportFormat,
    val content: ExportContent = ExportContent.TRANSCRIPT,
    val showTimestamps: Boolean = true,
    val showSpeakers: Boolean = true,
    val speakerLabel: String = "Speaker",
    val rtl: Boolean = false,
    /** Section headings in DOCX and PDF when [content] is BOTH; the text formats always say "Transcript"/"Summary", as on the desktop. */
    val transcriptLabel: String = "Transcript",
    val summaryLabel: String = "Summary",
)

object Exporter {
    /** Writes [record] in [options].format to [out]; [context] is used only for PDF fonts. Blocking; [out] is left open. */
    fun export(context: Context, record: TranscriptRecord, options: ExportOptions, out: OutputStream) {
        when (options.format) {
            ExportFormat.PDF -> PdfWriter.write(context, record, options, out)
            ExportFormat.DOCX -> DocxWriter.write(record, options, out)
            else -> out.write(text(record, options).toByteArray())
        }
        out.flush()
    }

    /**
     * The text formats exactly as the desktop's `serializeTranscriptExport` writes them; for PDF and
     * DOCX, the plain preview the desktop shows for them.
     */
    fun text(record: TranscriptRecord, options: ExportOptions): String {
        val summary = record.summary?.jsTrim() ?: ""
        val segments = record.segments
        return when (options.format) {
            ExportFormat.MD -> markdown(segments, summary, record, options)
            ExportFormat.SRT -> srt(segments, summary, record, options)
            ExportFormat.VTT -> vtt(segments, summary, record, options)
            ExportFormat.HTML -> html(segments, summary, record, options)
            ExportFormat.JSON -> json(segments, summary, record, options)
            ExportFormat.CSV -> csv(segments, summary, record, options)
            ExportFormat.PDF, ExportFormat.DOCX ->
                listOf(record.name.jsTrim(), plainText(segments, summary, record, options)).filter { it.isNotEmpty() }.joinToString("\n\n")
            ExportFormat.TXT -> plainText(segments, summary, record, options)
        }
    }

    private fun plainText(segments: List<Segment>, summary: String, record: TranscriptRecord, options: ExportOptions): String {
        val transcript = segments.joinToString("\n\n") { segment ->
            val metadata = segmentMetadata(segment, record, options)
            "${if (metadata.isNotEmpty()) "$metadata\n" else ""}${segment.text.jsTrim()}"
        }
        // The AI writes summaries in Markdown; plain text gets its words, not its marks.
        return sectioned(transcript, markdownToText(summary), options) { it }
    }

    private fun markdown(segments: List<Segment>, summary: String, record: TranscriptRecord, options: ExportOptions): String {
        val transcript = segments.joinToString("\n\n") { segment ->
            val metadata = segmentMetadata(segment, record, options)
            "${if (metadata.isNotEmpty()) "**$metadata**\n\n" else ""}${segment.text.jsTrim()}"
        }
        val content = sectioned(transcript, summary, options) { "## $it" }
        val title = record.name.jsTrim()
        return if (title.isNotEmpty()) "# $title\n\n$content" else content
    }

    private fun srt(segments: List<Segment>, summary: String, record: TranscriptRecord, options: ExportOptions): String {
        val cues = if (options.content != ExportContent.SUMMARY) {
            segments.withIndex().joinToString("\n\n") { (index, segment) ->
                "${index + 1}\n${timestamp(segment.start, segment.stop, ",", true)}\n${subtitleText(segment, record, options)}"
            }
        } else ""
        val note = if (options.content != ExportContent.TRANSCRIPT) subtitleNote(summary) else ""
        return listOf(cues, note).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    private fun vtt(segments: List<Segment>, summary: String, record: TranscriptRecord, options: ExportOptions): String {
        val cues = if (options.content != ExportContent.SUMMARY) {
            segments.joinToString("\n\n") { "${timestamp(it.start, it.stop)}\n${subtitleText(it, record, options)}" }
        } else ""
        val note = if (options.content != ExportContent.TRANSCRIPT) subtitleNote(summary) else ""
        return "WEBVTT\n\n${listOf(cues, note).filter { it.isNotEmpty() }.joinToString("\n\n")}"
    }

    private fun html(segments: List<Segment>, summary: String, record: TranscriptRecord, options: ExportOptions): String {
        // ponytail: light palette only; the desktop's dark export theme has no Android setting yet.
        val background = "#ffffff"
        val foreground = "#1a1c1f"
        val muted = "#6e6e6e"
        val primary = "#145a1e" // brand green; the only intended difference from the desktop HTML
        val border = "#e6e6e6"
        val direction = if (options.rtl) "rtl" else "ltr"
        val both = options.content == ExportContent.BOTH
        val transcript = segments.joinToString("\n") { segment ->
            val metadata = segmentMetadata(segment, record, options)
            val metadataHtml = if (metadata.isNotEmpty()) "<div class=\"metadata\">${escapeHtml(metadata)}</div>" else ""
            "<article class=\"segment\">$metadataHtml<p>${escapeHtml(segment.text.jsTrim()).replace(Regex("\r?\n"), "<br>")}</p></article>"
        }
        val sections = buildList {
            if (options.content != ExportContent.SUMMARY) add("<section class=\"transcript\">${if (both) "<h2>Transcript</h2>" else ""}$transcript</section>")
            // Under the page's own "Summary" heading when there is one, so the levels stay in order.
            if (options.content != ExportContent.TRANSCRIPT) {
                add("<section class=\"summary\">${if (both) "<h2>Summary</h2>" else ""}${markdownToHtml(summary, if (both) 3 else 2)}</section>")
            }
        }
        val title = record.name.jsTrim()
        val style = ":root{color-scheme:light}html,body{min-height:100%;margin:0;background:$background;color:$foreground}" +
            ".html{box-sizing:border-box;min-height:100vh;font-family:Roboto,Arial,sans-serif;max-width:1000px;margin:auto;padding:22px;line-height:1.5;background:$background;color:$foreground}" +
            ".html h1{text-align:center;color:$primary}.html h2{border-bottom:1px solid $border;padding-bottom:.35rem}.html .segment{margin-top:18px}" +
            ".html .metadata{font-size:.8rem;font-weight:600;color:$muted}.html .segment p,.html .summary p{white-space:normal}" +
            ".html .summary hr{border:0;border-top:1px solid $border;margin:1.25rem 0}" +
            ".html .summary blockquote{margin:0;padding-inline-start:1rem;border-inline-start:3px solid $border;color:$muted}" +
            ".html .summary ul,.html .summary ol{padding-inline-start:1.5rem}"
        return listOf(
            "<!doctype html>",
            "<html dir=\"$direction\">",
            "<head>",
            "<meta charset=\"utf-8\">",
            "<meta name=\"color-scheme\" content=\"light\">",
            "<title>${escapeHtml(title)}</title>",
            "<style>$style</style>",
            "</head>",
            "<body>",
            "<main class=\"html printable\" dir=\"$direction\">",
            if (title.isNotEmpty()) "<h1>${escapeHtml(title)}</h1>" else "",
            *sections.toTypedArray(),
            "</main>",
            "</body>",
            "</html>",
        ).filter { it.isNotEmpty() }.joinToString("\n")
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private fun json(segments: List<Segment>, summary: String, record: TranscriptRecord, options: ExportOptions): String {
        val output = buildJsonObject {
            put("direction", if (options.rtl) "rtl" else "ltr")
            val title = record.name.jsTrim()
            if (title.isNotEmpty()) put("title", title)
            if (options.content != ExportContent.SUMMARY) {
                put("transcript", buildJsonArray {
                    for (segment in segments) {
                        add(buildJsonObject {
                            put("text", segment.text.jsTrim())
                            if (options.showTimestamps) {
                                put("start", seconds(segment.start))
                                put("stop", seconds(segment.stop))
                            }
                            if (options.showSpeakers && segment.speaker != null) {
                                put("speaker", segment.speaker + 1)
                                chosenSpeakerName(segment, record)?.let { put("speakerName", it) }
                            }
                        })
                    }
                })
            }
            if (options.content != ExportContent.TRANSCRIPT) put("summary", summary)
        }
        return prettyJson.encodeToString(JsonObject.serializer(), output)
    }

    /** Centiseconds as a JS number prints them: `1`, not `1.0`. */
    private fun seconds(centis: Long): JsonPrimitive = if (centis % 100 == 0L) JsonPrimitive(centis / 100) else JsonPrimitive(centis / 100.0)

    private fun csv(segments: List<Segment>, summary: String, record: TranscriptRecord, options: ExportOptions): String {
        val header = buildList {
            add("content")
            if (options.showTimestamps) addAll(listOf("start", "end"))
            if (options.showSpeakers) add("speaker")
            add("text")
        }
        val rows = buildList {
            if (options.content != ExportContent.SUMMARY) {
                for (segment in segments) {
                    add(buildList {
                        add("transcript")
                        if (options.showTimestamps) {
                            add(formatTimestamp(segment.start, true, "."))
                            add(formatTimestamp(segment.stop, true, "."))
                        }
                        if (options.showSpeakers) add(segment.speaker?.let { chosenSpeakerName(segment, record) ?: "${it + 1}" } ?: "")
                        add(segment.text.jsTrim())
                    })
                }
            }
            if (options.content != ExportContent.TRANSCRIPT) {
                add(buildList {
                    add("summary")
                    if (options.showTimestamps) addAll(listOf("", ""))
                    if (options.showSpeakers) add("")
                    add(summary)
                })
            }
        }
        return (listOf(header.joinToString(",")) + rows.map { row -> row.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" } })
            .joinToString("\n")
    }

    private fun sectioned(transcript: String, summary: String, options: ExportOptions, heading: (String) -> String) =
        when (options.content) {
            ExportContent.TRANSCRIPT -> transcript
            ExportContent.SUMMARY -> summary
            ExportContent.BOTH -> "${heading("Transcript")}\n\n$transcript\n\n${heading("Summary")}\n\n$summary"
        }

    /** A summary in a subtitle file: plain, and without the blank lines that would end its note. */
    private fun subtitleNote(summary: String) = "NOTE Summary\n${markdownToText(summary).replace(Regex("\n{2,}"), "\n")}"

    private fun subtitleText(segment: Segment, record: TranscriptRecord, options: ExportOptions): String {
        val prefix = speaker(segment, record, options)
        return "${if (prefix.isNotEmpty()) "[$prefix] " else ""}${segment.text.jsTrim().replace("-->", "->")}"
    }
}

/** Timestamps and speaker of one segment, joined with ` · `; empty when neither is shown. */
internal fun segmentMetadata(segment: Segment, record: TranscriptRecord, options: ExportOptions): String {
    val time = if (options.showTimestamps) timestamp(segment.start, segment.stop) else ""
    val directionalTime = if (time.isNotEmpty() && options.rtl) "⁦$time⁩" else time
    return listOf(directionalTime, speaker(segment, record, options)).filter { it.isNotEmpty() }.joinToString(" · ")
}

internal fun speaker(segment: Segment, record: TranscriptRecord, options: ExportOptions): String =
    if (options.showSpeakers && segment.speaker != null) speakerName(segment.speaker, options.speakerLabel, record.speakerNames) else ""

/** What a speaker is called on screen and in exports: the chosen name, else "Speaker N". */
internal fun speakerName(speaker: Int, label: String, names: Map<String, String>?): String =
    names?.get(speaker.toString())?.jsTrim()?.ifEmpty { null } ?: "$label ${speaker + 1}"

/** The name a user chose for a speaker, when they chose one — machine formats carry it beside the index. */
private fun chosenSpeakerName(segment: Segment, record: TranscriptRecord): String? =
    segment.speaker?.let { record.speakerNames?.get(it.toString())?.jsTrim() }?.ifEmpty { null }

private fun timestamp(start: Long, stop: Long, decimalMarker: String = ".", alwaysIncludeHours: Boolean = false) =
    "${formatTimestamp(start, alwaysIncludeHours, decimalMarker)} --> ${formatTimestamp(stop, alwaysIncludeHours, decimalMarker)}"

/** [centis] are the desktop segment unit. Negative times clamp to zero where the desktop would throw mid-export. */
internal fun formatTimestamp(centis: Long, alwaysIncludeHours: Boolean, decimalMarker: String, includeMilliseconds: Boolean = true): String {
    var milliseconds = maxOf(0L, centis) * 10
    val hours = milliseconds / 3_600_000
    milliseconds -= hours * 3_600_000
    val minutes = milliseconds / 60_000
    milliseconds -= minutes * 60_000
    val seconds = milliseconds / 1_000
    milliseconds -= seconds * 1_000
    val hoursMarker = if (alwaysIncludeHours || hours != 0L) "${hours.toString().padStart(2, '0')}:" else ""
    val result = "$hoursMarker${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    return if (includeMilliseconds) "$result$decimalMarker${milliseconds.toString().padStart(3, '0')}" else result
}
