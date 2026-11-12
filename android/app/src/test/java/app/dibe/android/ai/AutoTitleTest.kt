package app.dibe.android.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** The desktop's `cleanTitle` cases (desktop/src/lib/ai/ai.test.ts). */
class AutoTitleTest {
    @Test
    fun cleansTheReplyLikeTheDesktop() {
        assertEquals("Weekly sync: launch plan", AutoTitle.clean("\n## \"Weekly sync: launch plan.\"\nMore text"))
        assertEquals("Budget review", AutoTitle.clean("Title: Budget review!"))
        assertEquals("שיחה על צ'יפס ובירה", AutoTitle.clean("«שיחה על צ'יפס ובירה»"))
        assertEquals("Bold title", AutoTitle.clean("**Bold   title**"))
        assertEquals(80, AutoTitle.clean("x".repeat(200)).length)
        assertEquals("", AutoTitle.clean("  \n "))
    }
}
