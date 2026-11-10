package app.dibe.android.ui.transcript

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test
    fun parseMarkdown_readsTheBlocksSummariesUse() {
        val blocks = parseMarkdown(
            """
            ## Overview
            The team met
            on Monday.

            - First
              * Nested
            - [x] Booked
            - [ ] Pay
            3. Third
            > Quoted
            > on.
            ---
            ```
            code *raw*
            ```
            """.trimIndent(),
        )
        assertEquals(10, blocks.size)
        val heading = blocks[0] as MdBlock.Heading
        assertEquals(2, heading.level)
        assertEquals("Overview", heading.text.text)
        assertEquals("The team met on Monday.", (blocks[1] as MdBlock.Paragraph).text.text)
        assertEquals(MdBlock.Item(inlineMarkdown("First"), 0, "•"), blocks[2])
        assertEquals(1, (blocks[3] as MdBlock.Item).depth)
        assertEquals(true, (blocks[4] as MdBlock.Item).checked)
        assertEquals(false, (blocks[5] as MdBlock.Item).checked)
        assertEquals("3.", (blocks[6] as MdBlock.Item).marker)
        assertEquals("Quoted on.", (blocks[7] as MdBlock.Quote).text.text)
        assertEquals(MdBlock.Rule, blocks[8])
        assertEquals("code *raw*", (blocks[9] as MdBlock.Code).text)
    }

    @Test
    fun inlineMarkdown_stylesBoldItalicAndDropsLinkTargets() {
        val text = inlineMarkdown("A **bold** and *it **mixed** al* [site](https://x.y) `c` 2 * 3")
        assertEquals("A bold and it mixed al site c 2 * 3", text.text)
        val bold = text.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { text.text.substring(it.start, it.end) }
        val italic = text.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }.map { text.text.substring(it.start, it.end) }
        assertEquals(listOf("bold", "mixed"), bold)
        assertEquals(listOf("it mixed al"), italic)
    }

    @Test
    fun inlineMarkdown_leavesUnclosedMarkersAsText() {
        assertEquals("**open and `tick", inlineMarkdown("**open and `tick").text)
        assertTrue(inlineMarkdown("\\*literal\\*").spanStyles.isEmpty())
    }
}
