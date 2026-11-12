package app.dibe.android.ai

/**
 * The second pass after a summary: the model picks a few visuals and says, for each, which heading
 * of the summary it belongs under. The summary stays exactly as written; the blocks are inserted
 * at the end of their section (or at the end), so every export and the desktop read one Markdown.
 */
object VisualPass {
    /** Presets whose output is not a summary get no visuals. */
    fun appliesTo(preset: String) = preset != "translate"

    const val PROMPT = """Output only the requested content. No introductions, explanations, or commentary.

You may add visuals to the summary of a recording below: 0 to 3 of them, each only where it shows something the text alone conveys poorly. When nothing fits, output nothing at all. Write each as a fenced block in exactly one of these formats. All visible text is in {language}; labels are short (at most 6 words).

Choose the kind by what the content holds:
- numbers said in the recording (amounts, shares, counts, scores): chart
- a process, or causes and their effects: flow
- dates, deadlines, phases or an order of events: timeline
- a choice the listener faces, with its outcomes: tree
- two or more options, approaches or people side by side: compare

Put each visual under the heading whose section it explains.

A chart, for amounts, proportions, counts or scores. type is bar or pie. One row per item: label | number | optional #hex color. Use only numbers said in the recording; never estimate or invent them.
```dibe:chart
after: <a heading of the summary, copied exactly>
type: pie
title: <title>
Label one | 50
Label two | 30
Label three | 20
```

A flow, for a process, a cause and its effects, or how one thing led to another. One arrow per line; write each step with the same words every time.
```dibe:flow
after: <heading>
title: <title>
Step one -> Step two
Step two -> Step three
```

A timeline, for dates, deadlines, phases or the order things happen. One row per event: when | what | optional detail.
```dibe:timeline
after: <heading>
title: <title>
Week 1 | Kickoff | Define the scope
```

A decision tree, for a choice the listener faces. Nodes are `id::Title` (id is one short word), an optional line of explanation, then `- Choice => id`. The first node is start.
```dibe:tree
after: <heading>
title: <title>
start::Question?
- Option A => a
- Option B => b

a::Outcome A
What it means.

b::Outcome B
What it means.
```

A comparison, for two or more approaches, options, positions or people set side by side. The first row names the columns; each later row is one item.
```dibe:compare
after: <heading>
title: <title>
Approach | Main idea | Weakness
First | ... | ...
Second | ... | ...
```

Do not repeat a kind unless the content clearly calls for it. Only blocks, nothing between them.

<summary>
{summary}
</summary>

<transcript>
{transcript}
</transcript>"""

    /** For a question's answer: the same formats, without `after:`, since an answer has no sections. */
    val ASK = "When a visual would make the answer clearer (numbers said in the recording, a process, an order of events, a choice, options side by side), " +
        "add it as a fenced block in one of these formats, with its text in {language}. Use a comparison block instead of a Markdown table.\n\n" +
        PROMPT.substring(PROMPT.indexOf("A chart,"), PROMPT.indexOf("Do not repeat")).replace(Regex("(?m)^after: .*\n"), "").trim()

    private val block = Regex("""```[ \t]*dibe[:-]([a-z]+)[ \t]*\n(.*?)\n[ \t]*```""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val afterLine = Regex("""^\s*after\s*:\s*(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    /** A `#` heading, or a line that is only bold (`**Key points:**`), which models often write instead. */
    private val heading = Regex("""^\s{0,3}(?:#{1,6}\s+(.*?)\s*#*|\*\*([^*]+?)\*\*\s*:?|__([^_]+?)__\s*:?)\s*$""")

    private fun plain(text: String) = text.replace(Regex("""[*_`#]"""), "").trim().removeSuffix(":").trim().lowercase()

    /** [summary] with each block of [answer] at the end of the section its `after:` names; others go last. */
    fun merge(summary: String, answer: String): String {
        val lines = summary.trimEnd().lines().toMutableList()
        val tail = mutableListOf<String>()
        for (match in block.findAll(answer)) {
            val (kind, body) = match.destructured
            val after = afterLine.find(body)?.groupValues?.get(1)?.let(::plain).orEmpty()
            val clean = body.replace(afterLine, "").lines().filter { it.isNotBlank() || kind == "tree" }.joinToString("\n").trim()
            val fenced = listOf("", "```dibe:${kind.lowercase()}", clean, "```", "")
            val at = sectionEnd(lines, after)
            if (at == null) tail += fenced else lines.addAll(at, fenced)
        }
        return (lines + tail).joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** The line index just before the heading after the one matching [after], or null. */
    private fun sectionEnd(lines: List<String>, after: String): Int? {
        if (after.isEmpty()) return null
        val start = lines.indexOfFirst { line ->
            heading.matchEntire(line)?.groupValues?.drop(1)?.joinToString("")?.let(::plain)?.let { it.isNotEmpty() && (it == after || it.contains(after) || after.contains(it)) } == true
        }
        if (start < 0) return null
        var end = start + 1
        while (end < lines.size && heading.matchEntire(lines[end]) == null) end++
        return end
    }
}
