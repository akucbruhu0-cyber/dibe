package app.dibe.android.ui.transcript

import kotlin.math.roundToInt

/**
 * The visuals a summary carries as fenced blocks, ```` ```dibe:chart ```` and so on: a few `key: value`
 * lines (title, type), then one line per row. Line-based on purpose: a small local model writes it
 * reliably and a cut-off answer still parses. Anything that doesn't read as a visual returns null and
 * the block is simply not shown.
 */
sealed interface Visual {
    val title: String

    data class Chart(override val title: String, val pie: Boolean, val rows: List<Row>) : Visual {
        /** [color] is 0xRRGGBB, or null for the palette's next color. */
        data class Row(val label: String, val value: Double, val color: Long?)
    }

    /** `A -> B` lines, laid out top to bottom: each node sits one layer below its deepest parent. */
    data class Flow(override val title: String, val layers: List<List<String>>) : Visual

    data class Timeline(override val title: String, val events: List<Event>) : Visual {
        data class Event(val time: String, val title: String, val detail: String)
    }

    /** `id::Title`, an optional body, then `- Choice => id` lines. Starts at `start`, else the first node. */
    data class Tree(override val title: String, val start: String, val nodes: Map<String, Node>) : Visual {
        data class Node(val title: String, val body: String, val choices: List<Pair<String, String>>)
    }

    /** Pipe rows; the first is the header. Each later row is one item compared across the columns. */
    data class Compare(override val title: String, val header: List<String>, val rows: List<List<String>>) : Visual
}

/** `dibe:chart`, `dibe-chart` → `chart`. */
private val fenceInfo = Regex("""^\s*`{3,}\s*dibe[:-]([a-z]+)\s*$""", RegexOption.IGNORE_CASE)
private val metaLine = Regex("""^\s*(title|type|after)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
private val number = Regex("""-?\d+(?:[.,]\d+)?""")
private val hex = Regex("""#?([0-9a-fA-F]{6})""")

fun visualKind(fenceLine: String): String? = fenceInfo.matchEntire(fenceLine)?.groupValues?.get(1)?.lowercase()

fun parseVisual(kind: String, body: String): Visual? {
    val meta = mutableMapOf<String, String>()
    val rows = mutableListOf<String>()
    for (line in body.lines()) {
        if (line.isBlank()) continue
        val m = metaLine.matchEntire(line)
        if (m != null && rows.isEmpty()) meta[m.groupValues[1].lowercase()] = m.groupValues[2].trim()
        else rows += line
    }
    val title = meta["title"].orEmpty().trim('"', ' ')
    return when (kind) {
        "chart" -> chart(title, meta["type"].orEmpty().lowercase(), rows)
        "flow", "flowchart" -> flow(title, rows)
        "timeline" -> timeline(title, rows)
        "tree", "decision-tree" -> tree(title, rows)
        "compare", "table" -> compare(title, rows)
        else -> null
    }
}

private fun cells(line: String) = line.trim().trim('|').split('|').map { it.trim() }

private fun chart(title: String, type: String, lines: List<String>): Visual? {
    val rows = lines.mapNotNull { line ->
        val parts = cells(line)
        if (parts.size < 2 || parts[0].isEmpty()) return@mapNotNull null
        val value = number.find(parts[1])?.value?.replace(',', '.')?.toDoubleOrNull() ?: return@mapNotNull null
        val color = parts.getOrNull(2)?.let { hex.matchEntire(it)?.groupValues?.get(1)?.toLong(16) }
        Visual.Chart.Row(parts[0], value, color)
    }.filter { it.value >= 0 }.take(MAX_ROWS)
    if (rows.isEmpty() || rows.all { it.value == 0.0 }) return null
    return Visual.Chart(title, pie = type == "pie" || type == "donut", rows = rows)
}

/** A node's identity without a trailing "(...)": models shorten "Fear (panic)" to "Fear" on its next line. */
private fun nodeKey(name: String) = name.substringBefore(" (").trim().lowercase()

private fun flow(title: String, lines: List<String>): Visual? {
    val names = linkedMapOf<String, String>() // key -> longest spelling seen
    val edges = mutableListOf<Pair<String, String>>()
    for (line in lines) {
        val parts = line.split("->", "→").map { it.trim().trimStart('-', '•', ' ') }.filter { it.isNotEmpty() }
        parts.forEach { name -> names.merge(nodeKey(name), name) { a, b -> if (b.length > a.length) b else a } }
        parts.zipWithNext().forEach { (a, b) -> if (nodeKey(a) != nodeKey(b)) edges += nodeKey(a) to nodeKey(b) }
    }
    if (names.size < 2) return null
    // Longest-path layering; a cycle stops growing after names.size rounds.
    val layer = names.keys.associateWith { 0 }.toMutableMap()
    repeat(names.size) {
        edges.forEach { (a, b) -> if (layer.getValue(b) < layer.getValue(a) + 1) layer[b] = minOf(layer.getValue(a) + 1, names.size) }
    }
    val layers = names.keys.groupBy { layer.getValue(it) }.toSortedMap().values.map { keys -> keys.map { names.getValue(it) } }
    return Visual.Flow(title, layers)
}

private fun timeline(title: String, lines: List<String>): Visual? {
    val events = lines.mapNotNull { line ->
        val parts = cells(line)
        when {
            parts.size >= 3 -> Visual.Timeline.Event(parts[0], parts[1], parts.drop(2).joinToString(" | "))
            parts.size == 2 -> Visual.Timeline.Event(parts[0], parts[1], "")
            else -> null
        }
    }.take(MAX_ROWS * 2)
    return if (events.isEmpty()) null else Visual.Timeline(title, events)
}

private val nodeLine = Regex("""^\s*([\w-]+)\s*::\s*(.*)$""")
private val choiceLine = Regex("""^\s*[-*]\s*(.+?)\s*=>\s*([\w-]+)\s*$""")

private fun tree(title: String, lines: List<String>): Visual? {
    val nodes = linkedMapOf<String, Visual.Tree.Node>()
    var id: String? = null
    var heading = ""
    val body = mutableListOf<String>()
    val choices = mutableListOf<Pair<String, String>>()
    fun close() {
        id?.let { nodes[it] = Visual.Tree.Node(heading, body.joinToString(" ").trim(), choices.toList()) }
        body.clear(); choices.clear()
    }
    for (line in lines) {
        val node = nodeLine.matchEntire(line)
        val choice = choiceLine.matchEntire(line)
        when {
            node != null -> { close(); id = node.groupValues[1]; heading = node.groupValues[2].trim() }
            choice != null && id != null -> choices += choice.groupValues[1] to choice.groupValues[2]
            line.isNotBlank() && id != null -> body += line.trim()
        }
    }
    close()
    if (nodes.isEmpty() || nodes.values.none { it.choices.isNotEmpty() }) return null
    return Visual.Tree(title, if ("start" in nodes) "start" else nodes.keys.first(), nodes)
}

private val separatorRow = Regex("""^[\s|:-]+$""")

private fun compare(title: String, lines: List<String>): Visual? {
    val rows = lines.filterNot { separatorRow.matches(it) }.map(::cells).filter { it[0].isNotEmpty() }
    if (rows.size < 2 || rows[0].size < 2) return null
    val header = rows[0]
    return Visual.Compare(title, header, rows.drop(1).take(MAX_ROWS).map { row -> List(header.size) { row.getOrElse(it) { "" } } })
}

private const val MAX_ROWS = 8

private val fencedVisual = Regex("""(?m)^[ \t]*`{3,}[ \t]*dibe[:-]([a-zA-Z]+)[ \t]*\n([\s\S]*?)\n[ \t]*`{3,}[ \t]*$""")

/** [markdown] with every visual written out as plain Markdown, for files that can't draw it. */
fun visualsAsMarkdown(markdown: String): String = markdown.replace(fencedVisual) { match ->
    parseVisual(match.groupValues[1].lowercase(), match.groupValues[2])?.asMarkdown().orEmpty()
}

private fun Visual.asMarkdown(): String {
    val lines = mutableListOf<String>()
    if (title.isNotBlank()) lines += "**$title**"
    when (this) {
        is Visual.Chart -> {
            val total = rows.sumOf { it.value }
            rows.forEach { lines += "- ${it.label}: ${plainNumber(it.value)}" + if (pie) " (${(it.value / total * 100).roundToInt()}%)" else "" }
        }
        is Visual.Flow -> layers.forEachIndexed { i, names -> lines += "${i + 1}. ${names.joinToString(" · ")}" }
        is Visual.Timeline -> events.forEach { lines += "- **${it.time}** ${it.title}" + if (it.detail.isNotBlank()) ": ${it.detail}" else "" }
        is Visual.Tree -> nodes.values.forEach { node ->
            lines += "- **${node.title}**" + if (node.body.isNotBlank()) " ${node.body}" else ""
            node.choices.forEach { (label, target) -> lines += "  - $label → ${nodes[target]?.title ?: target}" }
        }
        is Visual.Compare -> rows.forEach { row ->
            lines += "- **${row[0]}**"
            row.drop(1).forEachIndexed { c, cell -> if (cell.isNotBlank()) lines += "  - ${header[c + 1]}: $cell" }
        }
    }
    return lines.joinToString("\n")
}

private fun plainNumber(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** The visual's own direction, from its first strong letter: a Hebrew chart in an English UI still reads right to left. */
fun Visual.rtl(): Boolean? {
    val text = title + when (this) {
        is Visual.Chart -> rows.joinToString { it.label }
        is Visual.Flow -> layers.flatten().joinToString()
        is Visual.Timeline -> events.joinToString { it.title }
        is Visual.Tree -> nodes.values.joinToString { it.title }
        is Visual.Compare -> header.joinToString()
    }
    for (c in text) when (Character.getDirectionality(c)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
        Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
    }
    return null
}

/** Private-use marks around an index: no Markdown meaning, never in real text. */
private const val MARK = '\uE000'

/**
 * [markdown] with each visual replaced by a paragraph of its own marker (see [visualAt]), and the
 * visuals in order. Files that draw them keep the summary's headings ranked as one document.
 */
fun extractVisuals(markdown: String): Pair<String, List<Visual>> {
    val visuals = mutableListOf<Visual>()
    val marked = markdown.replace(fencedVisual) { match ->
        val visual = parseVisual(match.groupValues[1].lowercase(), match.groupValues[2]) ?: return@replace ""
        visuals += visual
        "\n\n$MARK${visuals.lastIndex}$MARK\n\n"
    }
    return marked to visuals
}

/** The visual a paragraph of [text] stands for, if it is a marker from [extractVisuals]. */
fun visualAt(text: String, visuals: List<Visual>): Visual? =
    text.trim().takeIf { it.length > 2 && it.first() == MARK && it.last() == MARK }?.drop(1)?.dropLast(1)?.toIntOrNull()?.let(visuals::getOrNull)
