package app.dibe.android.ui.transcript

import app.dibe.android.data.Segment
import app.dibe.android.data.TranscriptRecord

data class Match(val segment: Int, val start: Int, val end: Int)

/** Every case-insensitive occurrence of [query] (trimmed) in [texts], in reading order. */
fun findMatches(texts: List<String>, query: String): List<Match> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val matches = mutableListOf<Match>()
    texts.forEachIndexed { index, text ->
        var from = text.indexOf(needle, ignoreCase = true)
        while (from >= 0) {
            matches += Match(index, from, from + needle.length)
            from = text.indexOf(needle, from + needle.length, ignoreCase = true)
        }
    }
    return matches
}

/**
 * The segment being heard at [positionCs]: the last one that started by then, so a pause between
 * lines keeps the previous line lit instead of flickering off. -1 before the first line.
 */
fun activeSegment(segments: List<Segment>, positionCs: Long): Int {
    val found = segments.binarySearch { it.start.compareTo(positionCs) }
    return if (found >= 0) found else -(found + 1) - 1
}

/** True when the first letter with a strong direction is right-to-left (Hebrew, Arabic…). */
fun isRtlText(text: CharSequence): Boolean {
    for (char in text) {
        when (Character.getDirectionality(char)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
    }
    return false
}

fun transcriptIsRtl(record: TranscriptRecord): Boolean =
    isRtlText(record.segments.asSequence().take(20).joinToString(" ") { it.text })

/** Desktop `speakerName`: the chosen name, else "Speaker N". */
fun speakerName(speaker: Int, label: String, names: Map<String, String>?): String =
    names?.get(speaker.toString())?.trim()?.takeIf { it.isNotEmpty() } ?: "$label ${speaker + 1}"

/** What "Copy all" puts on the clipboard: one line per segment, speaker first when known. */
fun plainText(record: TranscriptRecord, speakerLabel: String): String =
    record.segments.joinToString("\n") { segment ->
        val text = segment.text.trim()
        segment.speaker?.let { "${speakerName(it, speakerLabel, record.speakerNames)}: $text" } ?: text
    }
