package app.dibe.android.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelChoiceTest {
    private val gib = 1L shl 30

    private fun ram(gib: Double) = (gib * this.gib).toLong()

    @Test fun fitRoundsReportedRamUpToTheNominalSize() {
        assertEquals(Fit.TIGHT, fitFor(ram(7.3), minRamGb = 8)) // an 8 GB phone
        assertEquals(Fit.GOOD, fitFor(ram(7.3), minRamGb = 6))
        assertEquals(Fit.TIGHT, fitFor(ram(5.4), minRamGb = 6))
        assertEquals(Fit.TOO_HEAVY, fitFor(ram(3.6), minRamGb = 6))
        assertEquals(Fit.GOOD, fitFor(ram(15.2), minRamGb = 8)) // Pixel 9 Pro
    }

    @Test fun recommendsByLanguageThenRam() {
        fun pick(hebrew: Boolean, ramGib: Double) = recommend(hebrew) { fitFor(ram(ramGib), it.minRamGb) }.id
        assertEquals(PARAKEET_HE, pick(hebrew = true, ramGib = 7.3))
        assertEquals(PARAKEET_MULTI, pick(hebrew = false, ramGib = 5.4))
        assertEquals(WHISPER_SMALL, pick(hebrew = true, ramGib = 3.6))
        assertEquals(WHISPER_SMALL, pick(hebrew = false, ramGib = 3.6))
        assertEquals(WHISPER_BASE, pick(hebrew = true, ramGib = 2.7))
        assertTrue(isHebrew("iw") && isHebrew("he"))
        assertFalse(isHebrew("en"))
    }

    @Test fun catalogIsOrderedLightToHeavy() {
        assertEquals(CATALOG.map { it.tier }, CATALOG.map { it.tier }.sorted())
        assertTrue(CATALOG.all { it.url.endsWith("/" + it.fileName) })
    }

    @Test fun fastCoresSkipTheLittleCluster() {
        val tensorG4 = List(4) { 1_950_000L } + List(3) { 2_600_000L } + 3_105_000L
        assertEquals(4, fastCoreCount(tensorG4))
        assertEquals(4, fastCoreCount(List(4) { 1_800_000L } + List(4) { 2_400_000L }))
        assertEquals(8, fastCoreCount(List(8) { 2_000_000L }))
        assertNull(fastCoreCount(emptyList()))
    }
}
