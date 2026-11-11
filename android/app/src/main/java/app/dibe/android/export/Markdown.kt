package app.dibe.android.export

import app.dibe.android.data.JS_NON_SPACE
import app.dibe.android.data.JS_SPACE
import app.dibe.android.data.JS_SPACES
import app.dibe.android.data.jsTrim
import app.dibe.android.ui.transcript.visualsAsMarkdown

/**
 * Port of desktop/src/lib/markdown-blocks.ts: the Markdown subset AI summaries use, read into blocks
 * that each export draws in its own terms. Output must stay identical to the TS (golden tests).
 */
// ponytail: no tables, fenced code or backslash escapes; the desktop renders none of them either.

internal data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false) {
    fun sameMarks(other: Run) = bold == other.bold && italic == other.italic && code == other.code
}

internal sealed interface Block {
    /** [rank] 0 is the largest heading the text uses, whatever its number of `#`. */
    data class Heading(val rank: Int, val runs: List<Run>) : Block
    data class Paragraph(val runs: List<Run>) : Block
    data class Bullet(val depth: Int, val runs: List<Run>) : Block
    data class Number(val depth: Int, val marker: String, val runs: List<Run>) : Block
    data class Quote(val runs: List<Run>) : Block
    data object Rule : Block
}

private val HEADING = Regex("^$JS_SPACE{0,3}(#{1,6})$JS_SPACE+(.*?)(?:$JS_SPACE+#+)?$JS_SPACE*$")
private val RULE = Regex("^$JS_SPACE{0,3}([-*_])(?:$JS_SPACE*\\1){2,}$JS_SPACE*$")
private val BULLET = Regex("^($JS_SPACE*)[-*+]$JS_SPACE+(.*)$")
private val NUMBER = Regex("^($JS_SPACE*)(\\d{1,9}[.)])$JS_SPACE+(.*)$")
private val QUOTE = Regex("^$JS_SPACE{0,3}>$JS_SPACE?(.*)$")

/** Bold, italic (an underscore only between non-letters, so snake_case survives), code, links. */
private val INLINE = Regex(
    "\\*\\*(.+?)\\*\\*|__(.+?)__|\\*(?=$JS_NON_SPACE)(.+?)(?<=$JS_NON_SPACE)\\*" +
        "|(?<![\\p{L}\\p{N}])_(?=$JS_NON_SPACE)(.+?)(?<=$JS_NON_SPACE)_(?![\\p{L}\\p{N}])" +
        "|`([^`]+)`|\\[([^\\]]+)\\]\\([^)$JS_SPACES]*\\)",
)

private fun depthOf(indent: String) = indent.replace("\t", "  ").length / 2

/** A block before its text is read into runs, and before headings are ranked. */
private class Raw(val type: Char, var text: String = "", val level: Int = 0, val marker: String = "")

internal fun parseMarkdown(markdown: String): List<Block> {
    val raw = mutableListOf<Raw>()
    // The block a following line continues; a blank line or any other block closes it.
    var open: Raw? = null
    for (line in visualsAsMarkdown(markdown.replace(Regex("\r\n?"), "\n")).split("\n")) {
        if (line.jsTrim().isEmpty()) {
            open = null
            continue
        }
        val next = HEADING.find(line)?.let { Raw('h', it.groupValues[2], level = it.groupValues[1].length) }
            ?: (if (RULE.containsMatchIn(line)) Raw('r') else null)
            ?: BULLET.find(line)?.let { Raw('b', it.groupValues[2], level = depthOf(it.groupValues[1])) }
            ?: NUMBER.find(line)?.let { Raw('n', it.groupValues[3], level = depthOf(it.groupValues[1]), marker = it.groupValues[2]) }
            ?: run {
                val quote = QUOTE.find(line)
                when {
                    quote != null && open?.type == 'q' -> {
                        open.text += " ${quote.groupValues[1].jsTrim()}"
                        null
                    }
                    quote != null -> Raw('q', quote.groupValues[1])
                    open != null -> {
                        // A line under a paragraph, quote or list item belongs to it.
                        open.text += " ${line.jsTrim()}"
                        null
                    }
                    else -> Raw('p', line.jsTrim())
                }
            }
            ?: continue
        raw += next
        open = if (next.type == 'r' || next.type == 'h') null else next
    }

    val top = raw.filter { it.type == 'h' }.minOfOrNull { it.level } ?: 0
    return raw.map { block ->
        when (block.type) {
            'r' -> Block.Rule
            'h' -> Block.Heading(block.level - top, parseInline(block.text))
            'b' -> Block.Bullet(block.level, parseInline(block.text.jsTrim()))
            'n' -> Block.Number(block.level, block.marker, parseInline(block.text.jsTrim()))
            'q' -> Block.Quote(parseInline(block.text.jsTrim()))
            else -> Block.Paragraph(parseInline(block.text.jsTrim()))
        }
    }
}

private fun parseInline(text: String, marks: Run = Run("")): List<Run> {
    val runs = mutableListOf<Run>()
    var last = 0
    for (match in INLINE.findAll(text)) {
        val index = match.range.first
        if (index > last) runs += marks.copy(text = text.substring(last, index))
        val groups = match.groups
        val bold = groups[1] ?: groups[2]
        val italic = groups[3] ?: groups[4]
        when {
            bold != null -> runs += parseInline(bold.value, marks.copy(bold = true))
            italic != null -> runs += parseInline(italic.value, marks.copy(italic = true))
            groups[5] != null -> runs += marks.copy(text = groups[5]!!.value, code = true)
            groups[6] != null -> runs += parseInline(groups[6]!!.value, marks)
        }
        last = match.range.last + 1
    }
    if (last < text.length) runs += marks.copy(text = text.substring(last))
    // Neighbours with the same marks are one run: Word and PDF then break lines as in one word.
    return runs.fold(mutableListOf()) { merged, run ->
        val previous = merged.lastOrNull()
        if (previous != null && previous.sameMarks(run)) merged[merged.lastIndex] = previous.copy(text = previous.text + run.text)
        else if (run.text.isNotEmpty()) merged += run
        merged
    }
}

internal fun List<Run>.text() = joinToString("") { it.text }

/** Plain text: the words, with lists as `•` and numbers, for the formats that have no styling. */
internal fun markdownToText(markdown: String): String {
    val out = StringBuilder()
    var previous: Block? = null
    for (block in parseMarkdown(markdown)) {
        if (block == Block.Rule) continue
        val list = block is Block.Bullet || block is Block.Number
        val line = when (block) {
            is Block.Bullet -> "${"  ".repeat(block.depth)}• ${block.runs.text()}"
            is Block.Number -> "${"  ".repeat(block.depth)}${block.marker} ${block.runs.text()}"
            is Block.Heading -> block.runs.text()
            is Block.Paragraph -> block.runs.text()
            is Block.Quote -> block.runs.text()
            Block.Rule -> ""
        }
        // The items of one list stay together; everything else is a paragraph of its own.
        if (out.isNotEmpty()) out.append(if (list && previous != null && previous::class == block::class) "\n" else "\n\n")
        out.append(line)
        previous = block
    }
    return out.toString()
}

internal fun escapeHtml(value: String) =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

private fun runsHtml(runs: List<Run>) = runs.joinToString("") { run ->
    var html = escapeHtml(run.text)
    if (run.code) html = "<code>$html</code>"
    if (run.italic) html = "<em>$html</em>"
    if (run.bold) html = "<strong>$html</strong>"
    html
}

/** Nested `<ul>`/`<ol>` for a run of consecutive list items. */
private fun listHtml(items: List<Block>): String {
    val html = StringBuilder()
    val stack = ArrayDeque<Pair<String, Int>>()
    fun close() = "</li></${stack.removeLast().first}>"
    for (item in items) {
        val (tag, depth, runs) = when (item) {
            is Block.Bullet -> Triple("ul", item.depth, item.runs)
            is Block.Number -> Triple("ol", item.depth, item.runs)
            else -> error("not a list item")
        }
        while (stack.isNotEmpty() && stack.last().second > depth) html.append(close())
        val top = stack.lastOrNull()
        if (top != null && top.second == depth && top.first == tag) html.append("</li>")
        else {
            if (top != null && top.second == depth) html.append(close())
            html.append("<$tag>")
            stack.addLast(tag to depth)
        }
        html.append("<li>").append(runsHtml(runs))
    }
    while (stack.isNotEmpty()) html.append(close())
    return html.toString()
}

/** HTML elements; the largest heading becomes `<h{topLevel}>`, under the page's own headings. */
internal fun markdownToHtml(markdown: String, topLevel: Int): String {
    val out = mutableListOf<String>()
    val blocks = parseMarkdown(markdown)
    var index = 0
    while (index < blocks.size) {
        when (val block = blocks[index]) {
            is Block.Bullet, is Block.Number -> {
                val end = (index until blocks.size).firstOrNull { blocks[it] !is Block.Bullet && blocks[it] !is Block.Number } ?: blocks.size
                out += listHtml(blocks.subList(index, end))
                index = end
                continue
            }
            is Block.Heading -> {
                val level = minOf(topLevel + block.rank, 6)
                out += "<h$level>${runsHtml(block.runs)}</h$level>"
            }
            Block.Rule -> out += "<hr>"
            is Block.Quote -> out += "<blockquote>${runsHtml(block.runs)}</blockquote>"
            is Block.Paragraph -> out += "<p>${runsHtml(block.runs)}</p>"
        }
        index++
    }
    return out.joinToString("\n")
}
