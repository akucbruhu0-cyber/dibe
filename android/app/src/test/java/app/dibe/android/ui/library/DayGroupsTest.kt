package app.dibe.android.ui.library

import app.dibe.android.data.TranscriptEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DayGroupsTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    private val today = LocalDate.of(2026, 11, 9)
    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun dayGroup_splitsAtLocalMidnight() {
        assertEquals(DayGroup.TODAY, dayGroup(at(today, 0), today, zone))
        assertEquals(DayGroup.YESTERDAY, dayGroup(at(today.minusDays(1), 23), today, zone))
        assertEquals(DayGroup.EARLIER, dayGroup(at(today.minusDays(2), 23), today, zone))
        assertEquals(DayGroup.TODAY, dayGroup(at(today.plusDays(1), 1), today, zone))
    }

    @Test
    fun groupByDay_keepsNewestFirstWithinEachGroup() {
        val entries = listOf(
            TranscriptEntry("a", "a", at(today, 15)),
            TranscriptEntry("b", "b", at(today, 9)),
            TranscriptEntry("c", "c", at(today.minusDays(5), 9)),
        )
        val groups = groupByDay(entries, today, zone)
        assertEquals(listOf(DayGroup.TODAY, DayGroup.EARLIER), groups.map { it.first })
        assertEquals(listOf("a", "b"), groups[0].second.map { it.id })
    }
}
