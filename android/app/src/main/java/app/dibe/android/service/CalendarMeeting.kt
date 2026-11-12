package app.dibe.android.service

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Instances
import android.util.Log
import app.dibe.android.data.Meeting
import app.dibe.android.data.isoTimestamp
import app.dibe.android.data.jsTrim

private const val TAG = "DibeCalendar"

internal class CalendarEvent(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val calendar: String?)

/**
 * The event a recording over [start, end) belongs to: the timed one it overlaps most, ties to the
 * earlier begin, then the lower id. Blank titles and all-day events never name a recording.
 */
internal fun pickEvent(events: List<CalendarEvent>, start: Long, end: Long): CalendarEvent? =
    events
        .filter { !it.allDay && it.title.jsTrim().isNotEmpty() }
        .map { it to minOf(it.end, end) - maxOf(it.begin, start) }
        .filter { (_, overlap) -> overlap > 0 }
        .minWithOrNull(compareBy({ -it.second }, { it.first.begin }, { it.first.id }))
        ?.first

/** The device calendar's event over the recording, or null: no permission, no event, or the provider failed (logged). Blocking. */
internal fun Context.meetingAt(start: Long, end: Long): Meeting? {
    if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
    return try {
        val event = pickEvent(events(start, end), start, end) ?: return null
        Meeting(
            title = event.title.jsTrim(),
            start = isoTimestamp(event.begin),
            end = isoTimestamp(event.end),
            calendar = event.calendar?.takeIf { it.jsTrim().isNotEmpty() },
            attendees = attendees(event.id).ifEmpty { null },
        )
    } catch (e: Exception) {
        // SecurityException included: the permission can go between the check and the query.
        Log.w(TAG, "calendar lookup failed", e)
        null
    }
}

private fun Context.events(start: Long, end: Long): List<CalendarEvent> {
    val uri = Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, start)
        ContentUris.appendId(it, end)
    }.build()
    val columns = arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.CALENDAR_DISPLAY_NAME)
    return contentResolver.query(uri, columns, null, null, null)?.use { c ->
        buildList {
            while (c.moveToNext()) {
                add(CalendarEvent(c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getLong(3), c.getInt(4) == 1, c.getString(5)))
            }
        }
    }.orEmpty()
}

/** Names, an email where the name is blank, in the calendar's order, each once. */
private fun Context.attendees(eventId: Long): List<String> =
    contentResolver.query(
        Attendees.CONTENT_URI,
        arrayOf(Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL),
        "${Attendees.EVENT_ID} = ?",
        arrayOf(eventId.toString()),
        Attendees._ID,
    )?.use { c ->
        buildList {
            while (c.moveToNext()) {
                val name = c.getString(0)?.jsTrim().orEmpty().ifEmpty { c.getString(1)?.jsTrim().orEmpty() }
                if (name.isNotEmpty()) add(name)
            }
        }.distinct()
    }.orEmpty()
