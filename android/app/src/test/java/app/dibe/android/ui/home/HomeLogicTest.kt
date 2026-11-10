package app.dibe.android.ui.home

import app.dibe.android.engine.EngineCapabilities
import app.dibe.android.ui.components.formatClock
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLogicTest {
    @Test
    fun languageOptions_alwaysPutsAutoThenPhoneLanguageThenEnglishThenByName() {
        Locale.setDefault(Locale.US)
        val caps = EngineCapabilities(engine = "whisper", languages = listOf("fr", "en", "he", "de"), languageDetection = true)
        // Android reports Hebrew as "iw"; models list it as "he".
        assertEquals(listOf("auto", "he", "en", "fr", "de"), languageOptions(caps, uiLanguage = "iw"))
        // "auto" stays selectable without detection: there it means the model's own language.
        assertEquals(listOf("auto", "en", "fr", "de", "he"), languageOptions(caps.copy(languageDetection = false), uiLanguage = "en"))
    }

    @Test
    fun formatClock_switchesToHoursAtAnHour() {
        assertEquals("00:00", formatClock(0))
        assertEquals("01:23", formatClock(83_999))
        assertEquals("1:00:05", formatClock(3_605_000))
    }
}
