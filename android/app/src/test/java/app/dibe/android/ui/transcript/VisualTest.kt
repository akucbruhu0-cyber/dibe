package app.dibe.android.ui.transcript

import app.dibe.android.ai.VisualPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualTest {
    @Test
    fun chart_readsRowsColorsAndLooseNumbers() {
        val chart = parseVisual("chart", "type: pie\ntitle: \"גישות\"\nלוגית | 20 | #007AFF\nמקודדת | 30%\nפרגמטית | 50\nשבור | אין מספר") as Visual.Chart
        assertTrue(chart.pie)
        assertEquals("גישות", chart.title)
        assertEquals(listOf(20.0, 30.0, 50.0), chart.rows.map { it.value })
        assertEquals(0x007AFFL, chart.rows[0].color)
        assertNull(parseVisual("chart", "title: ריק\nאין | כלום"))
    }

    @Test
    fun flow_unifiesShortenedNamesAndLayersBranches() {
        // Pocket's own output: the model wrote "הבעת רגש (פחד/הפתעה)" then "הבעת רגש"; the two are one node.
        val flow = parseVisual(
            "flow",
            "title: אבולוציה\nתגובה פיזיולוגית -> הבעת רגש (פחד/הפתעה)\nהבעת רגש -> ביטוי כנות\nהבעת רגש -> סמן העצמה",
        ) as Visual.Flow
        assertEquals(listOf(listOf("תגובה פיזיולוגית"), listOf("הבעת רגש (פחד/הפתעה)"), listOf("ביטוי כנות", "סמן העצמה")), flow.layers)
        // A cycle still ends.
        assertEquals(2, (parseVisual("flow", "A -> B\nB -> A") as Visual.Flow).layers.flatten().size)
    }

    @Test
    fun tree_and_timeline_and_compare() {
        val tree = parseVisual("tree", "start::מהו המוקד?\n- תיאוריה => t\n- שטח => e\n\nt::סקירה\nהשוואת מודלים.\n\ne::ראיונות") as Visual.Tree
        assertEquals("start", tree.start)
        assertEquals(listOf("תיאוריה" to "t", "שטח" to "e"), tree.nodes.getValue("start").choices)
        assertEquals("השוואת מודלים.", tree.nodes.getValue("t").body)

        val timeline = parseVisual("timeline", "שבועיים | גיבוש | בחירת מאמרים\nסוף הסמסטר | הגשה") as Visual.Timeline
        assertEquals(Visual.Timeline.Event("סוף הסמסטר", "הגשה", ""), timeline.events[1])

        val compare = parseVisual("compare", "| גישה | רעיון |\n|---|---|\n| מינימליסטית | לוגיקה |\n| פרגמטית |") as Visual.Compare
        assertEquals(listOf("גישה", "רעיון"), compare.header)
        assertEquals(listOf("פרגמטית", ""), compare.rows[1])
    }

    @Test
    fun parseMarkdown_turnsVisualFencesIntoGraphicsAndDropsBrokenOnes() {
        val blocks = parseMarkdown("## א\n```dibe:chart\nX | 1\n```\n```dibe:chart\nbroken\n```\n```\ncode\n```")
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is MdBlock.Graphic)
        assertTrue(blocks[2] is MdBlock.Code)
    }

    @Test
    fun merge_placesEachVisualAtTheEndOfItsSection() {
        val summary = "פתיחה.\n\n### גישות מרכזיות\n\n- אחת\n\n### **תובנות**\n\n- שתיים"
        val answer = "Sure!\n```dibe:chart\nafter: גישות מרכזיות\ntitle: ה\nא | 1\n```\n```dibe:flow\nafter: תובנות\nא -> ב\n```\n```dibe:timeline\nafter: אין כזו\nמחר | משהו\n```"
        val merged = VisualPass.merge(summary, answer)
        val chart = merged.indexOf("```dibe:chart")
        assertTrue(chart > merged.indexOf("- אחת") && chart < merged.indexOf("### **תובנות**"))
        assertTrue(merged.indexOf("```dibe:flow") > merged.indexOf("- שתיים"))
        assertTrue(merged.trimEnd().endsWith("מחר | משהו\n```"))
        assertTrue("after:" !in merged)
        assertTrue("Sure!" !in merged)
    }

    @Test
    fun merge_readsBoldOnlyHeadings_andTreeReadsHebrewIds() {
        val merged = VisualPass.merge("**סקירה:**\n\nטקסט.\n\n**החלטות**\n\n- אחת", "```dibe:flow\nafter: סקירה\nא -> ב\n```")
        assertTrue(merged.indexOf("```dibe:flow") < merged.indexOf("**החלטות**"))
        val tree = parseVisual("tree", "התחלה::לבחור?\n- כן => אישור\n\nאישור::ממשיכים") as Visual.Tree
        assertEquals(listOf("כן" to "אישור"), tree.nodes.getValue("התחלה").choices)
    }

    @Test
    fun extractVisuals_leavesAMarkerParagraphTheExportsDrawIn() {
        val (marked, visuals) = extractVisuals("## א\nטקסט\n```dibe:chart\nX | 1\n```\n```dibe:chart\nbroken\n```\n## ב")
        assertEquals(1, visuals.size)
        val blocks = app.dibe.android.export.parseMarkdown(marked)
        // Heading, paragraph, the marker paragraph, heading: the broken block leaves nothing.
        assertEquals(4, blocks.size)
        val marker = (blocks[2] as app.dibe.android.export.Block.Paragraph).runs.joinToString("") { it.text }
        assertEquals(visuals[0], visualAt(marker, visuals))
        assertNull(visualAt("טקסט", visuals))
    }

    @Test
    fun visualsAsMarkdown_writesThemOutForExports() {
        val text = visualsAsMarkdown("לפני\n```dibe:chart\ntype: pie\ntitle: ח\nא | 1\nב | 3\n```\nאחרי")
        assertEquals("לפני\n**ח**\n- א: 1 (25%)\n- ב: 3 (75%)\nאחרי", text)
    }
}
