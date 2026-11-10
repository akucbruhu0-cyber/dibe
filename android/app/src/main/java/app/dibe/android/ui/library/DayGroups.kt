package app.dibe.android.ui.library

import app.dibe.android.data.TranscriptEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DayGroup { TODAY, YESTERDAY, EARLIER }

/** A date in the future (clock changed since saving) still counts as today. */
fun dayGroup(millis: Long, today: LocalDate, zone: ZoneId): DayGroup {
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    return when {
        !day.isBefore(today) -> DayGroup.TODAY
        day == today.minusDays(1) -> DayGroup.YESTERDAY
        else -> DayGroup.EARLIER
    }
}

/** [entries] newest first; each group keeps that order. */
fun groupByDay(entries: List<TranscriptEntry>, today: LocalDate, zone: ZoneId): List<Pair<DayGroup, List<TranscriptEntry>>> =
    entries.groupBy { dayGroup(it.createdAtMillis, today, zone) }.toList().sortedBy { it.first }
