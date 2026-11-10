package app.dibe.android.settings

import android.content.Context
import app.dibe.android.ai.AiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class AppSettings(
    val onboardingDone: Boolean = false,
    /** "auto" or a language code the active model lists. */
    val language: String = "auto",
    val livePartials: Boolean = true,
    val keepAudio: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Read on Android 10–12 only: Android 13+ stores the app's language itself. */
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val ai: AiSettings = AiSettings(),
)

/** SharedPreferences-backed; API keys are encrypted with an Android Keystore AES key. */
class SettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())

    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Keystore encryption is an IPC per key: it runs here, not on the main thread of a key field's keystrokes. */
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "dibe-settings") }
    /** What the disk holds; touched on [writer] only. */
    private var written = _settings.value

    /** The new value is visible at once; the disk write follows in the background. */
    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val old = _settings.value
        val new = change(old)
        if (new == old) return
        _settings.value = new
        writer.execute(::persist)
    }

    /** Writes the latest value, so a burst of keystrokes queued behind one write costs one more write, not one each. */
    private fun persist() {
        val new = _settings.value
        if (new == written) return
        val editor = prefs.edit().putString(SETTINGS, json.encodeToString(AppSettings.serializer(), new.withoutKeys()))
        for ((name, key) in SECRETS) {
            if (key(new.ai) == key(written.ai)) continue
            val sealed = KeyCipher.encrypt(key(new.ai))
            if (sealed != null) editor.putString(name, sealed) else editor.remove(name)
        }
        editor.apply()
        written = new
    }

    private fun load(): AppSettings {
        val stored = prefs.getString(SETTINGS, null)?.let {
            try {
                json.decodeFromString(AppSettings.serializer(), it)
            } catch (e: IllegalArgumentException) {
                null // SerializationException is one; a corrupt file means defaults, not a crash
            }
        } ?: AppSettings()
        val secret = { name: String -> KeyCipher.decrypt(prefs.getString(name, null) ?: "") }
        return stored.copy(
            ai = stored.ai.copy(
                claudeApiKey = secret(CLAUDE_KEY),
                openaiApiKey = secret(OPENAI_KEY),
                geminiApiKey = secret(GEMINI_KEY),
            ),
        )
    }

    private fun AppSettings.withoutKeys() = copy(ai = ai.copy(claudeApiKey = "", openaiApiKey = "", geminiApiKey = ""))

    private companion object {
        const val SETTINGS = "settings"
        const val CLAUDE_KEY = "key.claude"
        const val OPENAI_KEY = "key.openai"
        const val GEMINI_KEY = "key.gemini"

        val SECRETS = listOf<Pair<String, (AiSettings) -> String>>(
            CLAUDE_KEY to { it.claudeApiKey },
            OPENAI_KEY to { it.openaiApiKey },
            GEMINI_KEY to { it.geminiApiKey },
        )

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }
    }
}
