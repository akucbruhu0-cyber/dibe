package app.dibe.android.export

import app.dibe.android.data.Segment
import app.dibe.android.data.TranscriptRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocxWriterTest {
    private val record = TranscriptRecord(
        name = "פגישה & <צוות>",
        sourcePath = "",
        createdAt = "2026-11-09T08:15:30.123Z",
        segments = listOf(
            Segment(0, 712, " שלום\tלכולם\nשורה ", 0),
            Segment(712, 1530, "   ", 1),
            Segment(361234, 372001, "control \u0001 char and 😀", null),
        ),
        speakerNames = mapOf("0" to "דנה"),
        summary = "## כותרת\n\n- **פריט** _נטוי_\n  1. פנימי\n\n> ציטוט\n\n---\n\nפסקה `code`",
    )

    private fun parts(options: ExportOptions): Map<String, String> {
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(record, options, it) }.toByteArray()
        val parts = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes().decodeToString()
            }
        }
        return parts
    }

    @Test
    fun everyPartIsWellFormedXml() {
        for (content in ExportContent.entries) {
            for (rtl in listOf(true, false)) {
                val parts = parts(ExportOptions(ExportFormat.DOCX, content = content, rtl = rtl))
                assertEquals(
                    setOf("[Content_Types].xml", "_rels/.rels", "docProps/core.xml", "word/_rels/document.xml.rels", "word/styles.xml", "word/document.xml"),
                    parts.keys,
                )
                val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                for ((name, xml) in parts) factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()), name)
            }
        }
    }

    @Test
    fun documentCarriesTheDesktopContent() {
        val document = parts(ExportOptions(ExportFormat.DOCX, content = ExportContent.BOTH, rtl = true, transcriptLabel = "תמלול", summaryLabel = "סיכום"))
            .getValue("word/document.xml")
        assertTrue(document.contains("פגישה &amp; &lt;צוות&gt;"))
        assertTrue(document.contains("<w:pStyle w:val=\"Heading1\"/>"))
        assertTrue(document.contains(">תמלול<") && document.contains(">סיכום<"))
        // Left-to-right times, right side of the page, bold muted, speaker name after three spaces.
        assertTrue(document.contains("<w:keepNext/><w:spacing w:before=\"200\" w:after=\"40\"/><w:jc w:val=\"right\"/>"))
        assertTrue(document.contains(">00:00 --&gt; 00:07   דנה<"))
        assertTrue(document.contains(">01:00:12 --&gt; 01:02:00<"))
        assertTrue(document.contains("שלום</w:t><w:tab/><w:t xml:space=\"preserve\">לכולם</w:t><w:br/>"))
        assertFalse("blank segments are skipped", document.contains("Speaker 2"))
        assertFalse("XML 1.0 cannot hold U+0001", document.contains("\u0001"))
        assertTrue(document.contains("😀"))
        assertTrue(document.contains("<w:bidi/>") && document.contains("<w:rtl/>"))
        assertTrue(document.contains("<w:pStyle w:val=\"Heading2\"/>"))
        assertTrue(document.contains("<w:ind w:start=\"720\" w:hanging=\"360\"/>"))
        assertTrue(document.contains("<w:pBdr>"))
        assertTrue(document.contains("Courier New"))
    }

    @Test
    fun transcriptOnlyLeavesTheSummaryOut() {
        val document = parts(ExportOptions(ExportFormat.DOCX, showTimestamps = false, showSpeakers = false)).getValue("word/document.xml")
        assertFalse(document.contains("כותרת"))
        assertFalse(document.contains("Heading1"))
        assertFalse(document.contains("--&gt;"))
        assertFalse(document.contains("<w:bidi/>"))
    }
}
