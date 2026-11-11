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

You add visuals to the summary of a recording below. Pick the 1 to 3 visuals that best help a reader grasp it at a glance, and write each as a fenced block in exactly one of these formats. All visible text is in {language}; labels are short (at most 6 words).

A chart, for amounts, proportions, counts or scores. type is bar or pie. One row per item: label | number | optional #hex color. When exact numbers were not said, estimate them from what was mentioned and how much weight each item got.
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

A decision tree, for a choice the listener faces. Nodes are `id::Title`, an optional line of explanation, then `- Choice => id`. The first node is start.
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

Prefer the kinds that match the content; do not repeat a kind unless the content clearly calls for it. Only blocks, nothing between them.

<summary>
{summary}
</summary>

<transcript>
{transcript}
</transcript>"""

    private val block = Regex("""```[ \t]*dibe[:-]([a-z]+)[ \t]*\n(.*?)\n[ \t]*```""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val afterLine = Regex("""^\s*after\s*:\s*(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val heading = Regex("""^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$""")

    private fun plain(text: String) = text.replace(Regex("""[*_`#]"""), "").trim().lowercase()

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
            heading.matchEntire(line)?.groupValues?.get(1)?.let(::plain)?.let { it.isNotEmpty() && (it == after || it.contains(after) || after.contains(it)) } == true
        }
        if (start < 0) return null
        var end = start + 1
        while (end < lines.size && heading.matchEntire(lines[end]) == null) end++
        return end
    }
}
