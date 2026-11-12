package app.dibe.android.export

import app.dibe.android.data.AiThreadEntry
import app.dibe.android.data.Segment
import app.dibe.android.data.TranscriptRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Follow-up questions go after the summary, as the desktop writes them, and never without the summary. */
class ThreadExportTest {
    private val record = TranscriptRecord(
        name = "Talk", sourcePath = "", createdAt = "", segments = listOf(Segment(0, 100, "hi")),
        summary = "A short summary.", thread = listOf(AiThreadEntry("Why?", "Because **so**.")),
    )
    private val options = ExportOptions(format = ExportFormat.MD, content = ExportContent.SUMMARY, includeThread = true, threadLabel = "Follow-ups")

    @Test
    fun markdownCarriesThemAfterTheSummary() {
        assertTrue(Exporter.text(record, options).contains("A short summary.\n\n## Follow-ups\n\n> **Why?**\n\nBecause **so**."))
        assertFalse(Exporter.text(record, options.copy(content = ExportContent.TRANSCRIPT)).contains("Why?"))
        assertFalse(Exporter.text(record, options.copy(includeThread = false)).contains("Why?"))
    }

    @Test
    fun htmlDrawsTheQuestionInABubble() {
        val html = Exporter.text(record, options.copy(format = ExportFormat.HTML))
        assertTrue(html.contains("<div class=\"question\">Why?</div>"))
        assertFalse(html.contains("&gt; **Why?**"))
    }
}
