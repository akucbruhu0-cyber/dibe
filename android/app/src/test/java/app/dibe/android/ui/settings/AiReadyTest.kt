package app.dibe.android.ui.settings

import app.dibe.android.ai.AiPlatform
import app.dibe.android.ai.AiSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReadyTest {
    @Test
    fun isReady_needsWhatTheChosenPlatformNeeds() {
        assertFalse(AiSettings().isReady())
        assertTrue(AiSettings(claudeApiKey = "sk").isReady())
        assertFalse(AiSettings(platform = AiPlatform.GEMINI, claudeApiKey = "sk").isReady())
        assertFalse(AiSettings(platform = AiPlatform.OPENAI).isReady())
        assertTrue(AiSettings(platform = AiPlatform.OPENAI, openaiBaseUrl = "http://192.168.1.4:8080/v1").isReady())
        assertFalse(AiSettings(platform = AiPlatform.OPENAI, openaiBaseUrl = " ").isReady())
        assertTrue(AiSettings(platform = AiPlatform.OLLAMA).isReady())
    }
}
