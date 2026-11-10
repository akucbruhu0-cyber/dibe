package app.dibe.android.ui.transcript

import app.dibe.android.data.Segment
import app.dibe.android.data.TranscriptRecord
import app.dibe.android.ui.components.formatClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTextTest {
    private fun record(vararg segments: Segment) =
        TranscriptRecord(name = "t", sourcePath = "", createdAt = "2026-11-09T08:00:00Z", segments = segments.toList())

    @Test
    fun segmentTimes_areCentiseconds() {
        assertEquals("01:05", formatClock(6_512L * 10))
        assertEquals("1:00:00", formatClock(360_000L * 10))
    }

    @Test
    fun findMatches_isCaseInsensitiveAndFindsEveryOccurrence() {
        val matches = findMatches(listOf("Budget talk", "no", "budget, BUDGET"), " budget ")
        assertEquals(listOf(Match(0, 0, 6), Match(2, 0, 6), Match(2, 8, 14)), matches)
        assertEquals(emptyList<Match>(), findMatches(listOf("anything"), "  "))
        assertEquals(listOf(Match(0, 6, 11)), findMatches(listOf("שלום, תקציב"), "תקציב"))
    }

    @Test
    fun activeSegment_keepsThePreviousLineLitThroughAPause() {
        val segments = listOf(Segment(0, 100, "a"), Segment(300, 400, "b"), Segment(500, 600, "c"))
        assertEquals(0, activeSegment(segments, 0))
        assertEquals(0, activeSegment(segments, 250))
        assertEquals(1, activeSegment(segments, 300))
        assertEquals(2, activeSegment(segments, 9_999))
        assertEquals(-1, activeSegment(listOf(Segment(50, 100, "late")), 10))
        assertEquals(-1, activeSegment(emptyList(), 10))
    }

    @Test
    fun isRtlText_followsTheFirstStrongLetter() {
        assertTrue(isRtlText("12:00 שלום world"))
        assertFalse(isRtlText("Hello שלום"))
        assertFalse(isRtlText("123 — !"))
        assertTrue(transcriptIsRtl(record(Segment(0, 1, " "), Segment(1, 2, "ברוכים הבאים"))))
    }

    @Test
    fun plainText_prefixesChosenOrDefaultSpeakerNames() {
        val r = record(Segment(0, 1, " Hi", speaker = 0), Segment(1, 2, "Yo ", speaker = 1), Segment(2, 3, "no one"))
            .copy(speakerNames = mapOf("0" to "Dana", "1" to " "))
        assertEquals("Dana: Hi\nSpeaker 2: Yo\nno one", plainText(r, "Speaker"))
    }
}
