package app.dibe.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarMeetingTest {
    private fun event(id: Long, begin: Long, end: Long, title: String = "e$id", allDay: Boolean = false) =
        CalendarEvent(id, title, begin, end, allDay, null)

    private fun pick(vararg events: CalendarEvent) = pickEvent(events.toList(), 100, 200)?.id

    @Test
    fun largestOverlapWins() = assertEquals(2L, pick(event(1, 0, 120), event(2, 150, 400)))

    @Test
    fun tiesGoToTheEarlierBeginThenTheLowerId() {
        assertEquals(2L, pick(event(1, 150, 250), event(2, 50, 150)))
        assertEquals(3L, pick(event(4, 0, 300), event(3, 0, 300)))
    }

    @Test
    fun touchingAllDayAndUntitledEventsAreSkipped() {
        assertNull(pick(event(1, 0, 100), event(2, 200, 300)))
        assertNull(pick(event(1, 0, 300, allDay = true)))
        assertNull(pick(event(1, 0, 300, title = "  ")))
        assertEquals(2L, pick(event(1, 0, 300, allDay = true), event(2, 190, 300)))
    }
}
