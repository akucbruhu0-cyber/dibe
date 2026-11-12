package app.dibe.android.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.dibe.android.R

/** The small markdown subset the AI providers answer in: headings, lists, checklists, quotes, code, bold, italic. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock
    data class Paragraph(val text: AnnotatedString) : MdBlock
    /** [marker] is "•" or the number ("3."); [checked] is set for checklist items. */
    data class Item(val text: AnnotatedString, val depth: Int, val marker: String, val checked: Boolean? = null) : MdBlock
    data class Quote(val text: AnnotatedString) : MdBlock
    data class Code(val text: String) : MdBlock
    /** A ```` ```dibe:… ```` block that parsed; one that didn't is dropped rather than shown raw. */
    data class Graphic(val visual: Visual) : MdBlock
    data object Rule : MdBlock
}

private val headingLine = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
private val taskLine = Regex("""^(\s*)[-*+]\s+\[([ xX])]\s+(.*)$""")
private val bulletLine = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val orderedLine = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
private val ruleLine = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
private val quoteLine = Regex("""^\s{0,3}>\s?(.*)$""")

private val Bold = SpanStyle(fontWeight = FontWeight.Bold)
private val Italic = SpanStyle(fontStyle = FontStyle.Italic)
private val PlainCode = SpanStyle(fontFamily = FontFamily.Monospace)

private fun depthOf(indent: String) = indent.replace("\t", "    ").length / 2

fun parseMarkdown(source: String, code: SpanStyle = PlainCode): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()
    fun flush() {
        if (paragraph.isEmpty()) return
        blocks += MdBlock.Paragraph(inlineMarkdown(paragraph.joinToString(" "), code))
        paragraph.clear()
    }
    val lines = tablesAsVisuals(source.replace("\r\n", "\n")).lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i++]
        if (line.trimStart().startsWith("```")) {
            flush()
            val body = mutableListOf<String>()
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) body += lines[i++]
            i++
            val kind = visualKind(line)
            if (kind == null) blocks += MdBlock.Code(body.joinToString("\n"))
            else parseVisual(kind, body.joinToString("\n"))?.let { blocks += MdBlock.Graphic(it) }
            continue
        }
        if (line.isBlank()) { flush(); continue }
        if (ruleLine.matches(line)) { flush(); blocks += MdBlock.Rule; continue }
        val block: MdBlock? = headingLine.matchEntire(line)?.let {
            MdBlock.Heading(it.groupValues[1].length, inlineMarkdown(it.groupValues[2], code))
        } ?: taskLine.matchEntire(line)?.let {
            val (indent, mark, text) = it.destructured
            MdBlock.Item(inlineMarkdown(text, code), depthOf(indent), "•", checked = mark != " ")
        } ?: bulletLine.matchEntire(line)?.let {
            MdBlock.Item(inlineMarkdown(it.groupValues[2], code), depthOf(it.groupValues[1]), "•")
        } ?: orderedLine.matchEntire(line)?.let {
            val (indent, number, text) = it.destructured
            MdBlock.Item(inlineMarkdown(text, code), depthOf(indent), "$number.")
        }
        if (block != null) { flush(); blocks += block; continue }
        val quote = quoteLine.matchEntire(line)
        if (quote != null) {
            flush()
            val text = inlineMarkdown(quote.groupValues[1], code)
            val previous = blocks.lastOrNull()
            if (previous is MdBlock.Quote) blocks[blocks.lastIndex] = MdBlock.Quote(previous.text + AnnotatedString(" ") + text)
            else blocks += MdBlock.Quote(text)
            continue
        }
        paragraph += line.trim()
    }
    flush()
    return blocks
}

fun inlineMarkdown(source: String, code: SpanStyle = PlainCode): AnnotatedString =
    buildAnnotatedString { appendInline(source, code) }

private fun AnnotatedString.Builder.appendInline(s: String, code: SpanStyle) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if ((c == '*' || c == '_') && s.startsWith("$c$c", i)) {
            val end = s.indexOf("$c$c", i + 2)
            if (end > i + 2) {
                withStyle(Bold) { appendInline(s.substring(i + 2, end), code) }
                i = end + 2
                continue
            }
        }
        if (c == '*' && i + 1 < s.length && !s[i + 1].isWhitespace()) {
            val end = closingStar(s, i + 1)
            if (end > i + 1) {
                withStyle(Italic) { appendInline(s.substring(i + 1, end), code) }
                i = end + 1
                continue
            }
        }
        if (c == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(code) { append(s.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        if (c == '[') {
            val middle = s.indexOf("](", i + 1)
            val close = if (middle > 0) s.indexOf(')', middle + 2) else -1
            if (middle > i && close > middle) {
                appendInline(s.substring(i + 1, middle), code)
                i = close + 1
                continue
            }
        }
        if (c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_[]#+-.!>") {
            append(s[i + 1])
            i += 2
            continue
        }
        append(c)
        i++
    }
}

/** The `*` closing an italic run, stepping over `**bold**` inside it. */
private fun closingStar(s: String, from: Int): Int {
    var i = from
    while (i < s.length) {
        if (s[i] == '*') {
            if (!s.startsWith("**", i)) return i
            val end = s.indexOf("**", i + 2)
            if (end < 0) return -1
            i = end + 2
            continue
        }
        i++
    }
    return -1
}

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceContainer)
    val blocks = remember(markdown, codeStyle) { parseMarkdown(markdown, codeStyle) }
    val body = type.bodyLarge.copy(textDirection = TextDirection.Content, textAlign = TextAlign.Justify)
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            val previous = blocks.getOrNull(index - 1)
            val gap = when {
                index == 0 -> 0
                block is MdBlock.Heading -> 20
                block is MdBlock.Item && previous is MdBlock.Item -> 6
                else -> 12
            }
            val spaced = Modifier.padding(top = gap.dp)
            when (block) {
                is MdBlock.Heading -> Text(
                    block.text,
                    spaced.semantics { heading() },
                    style = when (block.level) {
                        1 -> type.titleLarge
                        2 -> type.titleMedium
                        else -> type.titleSmall
                    }.copy(textDirection = TextDirection.Content),
                )
                is MdBlock.Paragraph -> Text(block.text, spaced, style = body)
                is MdBlock.Item -> Row(spaced.padding(start = (block.depth * 18).dp)) {
                    if (block.checked != null) {
                        Icon(
                            if (block.checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            contentDescription = stringResource(if (block.checked) R.string.summary_item_done else R.string.summary_item_open),
                            modifier = Modifier.padding(top = 2.dp, end = 8.dp).size(20.dp),
                            tint = colors.onSurfaceVariant,
                        )
                    } else {
                        Text(block.marker, Modifier.widthIn(min = 22.dp), style = body, color = colors.onSurfaceVariant)
                    }
                    Text(block.text, style = body)
                }
                is MdBlock.Quote -> Row(spaced.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(MaterialTheme.shapes.extraSmall).background(colors.outline))
                    Text(block.text, Modifier.padding(start = 12.dp), style = body, color = colors.onSurfaceVariant)
                }
                is MdBlock.Code -> Text(
                    block.text,
                    spaced
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(colors.surfaceContainer)
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                    style = type.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                is MdBlock.Graphic -> VisualCard(block.visual, spaced.padding(vertical = 4.dp))
                MdBlock.Rule -> HorizontalDivider(spaced.padding(vertical = 4.dp), color = colors.outlineVariant)
            }
        }
    }
}
