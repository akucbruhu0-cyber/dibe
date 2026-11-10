package app.dibe.android.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenTextTest {
    @Test
    fun punctuationOnlyIsNotSpoken() {
        listOf(".", " . ", "?!", "", "  ", "…", "-").forEach { assertFalse(it, it.isSpoken()) }
    }

    @Test
    fun wordsAndNumbersAreSpoken() {
        listOf("שלום.", "ok", " 3 ", "רגע, על").forEach { assertTrue(it, it.isSpoken()) }
    }
}
