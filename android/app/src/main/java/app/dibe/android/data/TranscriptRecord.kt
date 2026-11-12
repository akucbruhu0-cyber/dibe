package app.dibe.android.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** `Date.toISOString()`'s form, the one the desktop writes into [TranscriptRecord.createdAt]. */
fun isoTimestamp(millis: Long = System.currentTimeMillis()): String = ISO.format(Instant.ofEpochMilli(millis))

private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** Desktop `Segment`: times in centiseconds. */
@Serializable
data class Segment(val start: Long, val stop: Long, val text: String, val speaker: Int? = null)

@Serializable
data class AiThreadEntry(val question: String, val answer: String)

/** The calendar event a recording was named after. [start]/[end] in [isoTimestamp] form; [attendees] are display names. */
@Serializable
data class Meeting(val title: String, val start: String, val end: String, val calendar: String? = null, val attendees: List<String>? = null)

/**
 * Byte-compatible with the desktop `transcript.vibe.json` (desktop/src/lib/transcripts-store.ts),
 * so a project folder can move between DIBE on the phone and on the computer.
 */
@Serializable
data class TranscriptRecord(
    val version: Int = 1,
    val name: String,
    val sourcePath: String,
    /** ISO 8601 */
    val createdAt: String,
    val language: String? = null,
    val modelPath: String? = null,
    val audioFile: String? = null,
    val segments: List<Segment>,
    /** JSON object keys are the zero-based speaker index as a string. */
    val speakerNames: Map<String, String>? = null,
    val summary: String? = null,
    val thread: List<AiThreadEntry>? = null,
    val meeting: Meeting? = null,
)

/** A saved project as listed, without reading its record. */
data class TranscriptEntry(
    /** Folder name, `<name>-<yyyyMMdd-HHmmss>`; stable id. */
    val id: String,
    val name: String,
    val createdAtMillis: Long,
)
