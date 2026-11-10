package app.dibe.android.export

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPaginationTest {
    private val bottoms = intArrayOf(10, 20, 30, 40)

    @Test
    fun takesTheLinesThatFit() {
        assertEquals(2, fitLines(bottoms, 0, 25f))
        assertEquals(4, fitLines(bottoms, 0, 40f))
        assertEquals(4, fitLines(bottoms, 2, 20f)) // from line 2 the slice starts at 20
        assertEquals(3, fitLines(bottoms, 2, 19f))
    }

    @Test
    fun noneWhenTheFirstLineDoesNotFit() {
        assertEquals(1, fitLines(bottoms, 1, 9f))
        assertEquals(0, fitLines(bottoms, 0, 9.9f))
    }
}
