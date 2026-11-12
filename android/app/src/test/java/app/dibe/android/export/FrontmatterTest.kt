package app.dibe.android.export

import app.dibe.android.data.Meeting
import app.dibe.android.data.Segment
import app.dibe.android.data.TranscriptRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class FrontmatterTest {
    private val record = TranscriptRecord(name = "Standup", sourcePath = "", createdAt = "", segments = listOf(Segment(0, 100, "hi")))
    private val md = ExportOptions(format = ExportFormat.MD, content = ExportContent.TRANSCRIPT)

    @Test
    fun fullMeetingGoesFirst() {
        val meeting = Meeting(
            title = "Sync \"Q4\" — תכנון",
            start = "2026-11-12T09:00:00.000Z",
            end = "2026-11-12T09:30:00.000Z",
            calendar = "Work",
            attendees = listOf("Dana", "a@b.c"),
        )
        val expected = "---\ndate: 2026-11-12T09:00:00.000Z\nmeeting: \"Sync \\\"Q4\\\" — תכנון\"\ncalendar: \"Work\"\n" +
            "attendees:\n  - \"Dana\"\n  - \"a@b.c\"\n---\n\n"
        assertEquals(expected + Exporter.text(record, md), Exporter.text(record.copy(meeting = meeting), md))
    }

    @Test
    fun calendarAndAttendeesAreLeftOutWhenUnknown() {
        val meeting = Meeting("1:1", "2026-11-12T09:00:00.000Z", "2026-11-12T09:30:00.000Z")
        assertEquals("---\ndate: 2026-11-12T09:00:00.000Z\nmeeting: \"1:1\"\n---\n\n", Exporter.frontmatter(meeting))
        assertEquals(Exporter.frontmatter(meeting), Exporter.frontmatter(meeting.copy(attendees = emptyList())))
    }
}
