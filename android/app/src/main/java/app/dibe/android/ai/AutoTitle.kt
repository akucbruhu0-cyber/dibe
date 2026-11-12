package app.dibe.android.ai

import app.dibe.android.data.JS_SPACE
import app.dibe.android.data.jsTrim

/** Desktop `TITLE_PROMPT` / `cleanTitle` (desktop/src/lib/ai/config.ts): the AI names a new recording. */
object AutoTitle {
    /** A title needs the gist, not the whole recording. */
    const val TRANSCRIPT_CHARS = 6000

    const val PROMPT = "Output only the requested content. No introductions, explanations, or commentary.\n\n" +
        "Write a short, specific title (3 to 7 words) for this recording, in the same language as the transcript. " +
        "No quotes, no trailing punctuation, no emoji, no date.\n\n\"\"\"\n{transcript}\n\"\"\""

    fun prompt(transcript: String): String = PROMPT.replace("{transcript}", transcript.take(TRANSCRIPT_CHARS))

    private val wrappingQuotes = Regex("^[\"'“”«»„]+|[\"'“”«»„]+$")

    /** The reply as a project name: first line, no markdown or quotes, at most 80 characters. Empty when nothing usable came back. */
    fun clean(reply: String): String {
        val line = reply.split('\n').firstOrNull { it.jsTrim().isNotEmpty() } ?: ""
        return line
            .replace(Regex("[#*]"), "")
            .replace(Regex("$JS_SPACE+"), " ")
            .jsTrim()
            .replace(Regex("^title:$JS_SPACE*", RegexOption.IGNORE_CASE), "")
            // Only wrapping quotes: an apostrophe inside a word is spelling (צ'יפס, don't).
            .replace(wrappingQuotes, "")
            .replace(Regex("[.:;,!]+$"), "")
            .take(80)
            .jsTrim()
    }
}
