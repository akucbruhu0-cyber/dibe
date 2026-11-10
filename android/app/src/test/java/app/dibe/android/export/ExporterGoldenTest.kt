package app.dibe.android.export

import app.dibe.android.data.TranscriptRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected files come from running the desktop TS on the same fixtures (see the generator note in the report). */
class ExporterGoldenTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String) =
        requireNotNull(javaClass.classLoader!!.getResource(path)) { "missing resource $path" }.readText()

    @Test
    fun textFormatsMatchTheDesktop() {
        val fixtures = json.parseToJsonElement(resource("export/fixtures.json")).jsonObject
        val records = fixtures["records"]!!.jsonObject
        val formats = mapOf(
            "txt" to ExportFormat.TXT, "srt" to ExportFormat.SRT, "vtt" to ExportFormat.VTT, "html" to ExportFormat.HTML,
            "json" to ExportFormat.JSON, "csv" to ExportFormat.CSV, "md" to ExportFormat.MD, "docx.txt" to ExportFormat.DOCX,
        )
        var compared = 0
        for (variant in fixtures["variants"] as JsonArray) {
            val v = variant.jsonObject
            val id = v.string("id")
            val record = json.decodeFromJsonElement(TranscriptRecord.serializer(), records[v.string("record")]!!)
            for ((extension, format) in formats) {
                val options = ExportOptions(
                    format = format,
                    content = ExportContent.valueOf(v.string("content").uppercase()),
                    showTimestamps = v["showTimestamps"]!!.jsonPrimitive.boolean,
                    showSpeakers = v["showSpeakers"]!!.jsonPrimitive.boolean,
                    speakerLabel = v.string("speakerLabel"),
                    rtl = v["rtl"]!!.jsonPrimitive.boolean,
                )
                assertEquals("$id.$extension", resource("export/golden/$id.$extension"), Exporter.text(record, options))
                compared++
            }
        }
        assertEquals(7 * 8, compared)
    }

    @Test
    fun markdownMatchesTheDesktop() {
        val samples = json.parseToJsonElement(resource("export/markdown.json")) as JsonArray
        for (sample in samples) {
            val s = sample.jsonObject
            val markdown = s.string("markdown")
            assertEquals("text of <$markdown>", s.string("text"), markdownToText(markdown))
            assertEquals("html2 of <$markdown>", s.string("html2"), markdownToHtml(markdown, 2))
            assertEquals("html3 of <$markdown>", s.string("html3"), markdownToHtml(markdown, 3))
        }
    }

    @Test
    fun timestampsFormatLikeTheDesktop() {
        assertEquals("00:00.000", formatTimestamp(0, false, "."))
        assertEquals("01:00:12,340", formatTimestamp(361234, false, ","))
        assertEquals("00:00:07.120", formatTimestamp(712, true, "."))
        assertEquals("100:00:00", formatTimestamp(36_000_000, false, "", false))
        assertEquals("00:00.000", formatTimestamp(-5, false, "."))
    }

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content
}
