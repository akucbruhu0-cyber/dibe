package app.dibe.android.ai

import kotlinx.serialization.Serializable

/** Desktop `AiPlatform` / `AiConnection` / summary `AiTask` (desktop/src/lib/ai/config.ts). */
@Serializable
enum class AiPlatform { OLLAMA, CLAUDE, OPENAI, GEMINI }

@Serializable
data class AiSettings(
    val platform: AiPlatform = AiPlatform.CLAUDE,
    val model: String = "",
    val contextTokens: Int = 65_536,
    val claudeApiKey: String = "",
    val openaiApiKey: String = "",
    val geminiApiKey: String = "",
    val ollamaBaseUrl: String = "http://localhost:11434",
    val openaiBaseUrl: String = "https://api.openai.com/v1",
    /** A preset id (summary, meeting-notes, tldr, translate, article) or "custom". */
    val preset: String = "summary",
    val prompt: String = "",
    val autoSummarize: Boolean = false,
    /** After a recording is transcribed, the AI renames it while it still has its default name. */
    val autoTitle: Boolean = false,
)

/** The default model for a platform, so switching platforms never leaves a Claude model on Ollama. */
fun defaultModel(platform: AiPlatform): String = when (platform) {
    AiPlatform.OLLAMA -> "gemma4:e2b"
    AiPlatform.OPENAI -> "gpt-5.6-luna"
    AiPlatform.CLAUDE -> "claude-sonnet-5"
    AiPlatform.GEMINI -> "gemini-3.8-flash"
}

/** The desktop's summary-task presets, word for word. Picking one fills the prompt; editing it makes it [CUSTOM]. */
object AiPresets {
    const val CUSTOM = "custom"
    private const val RULE = "Output only the requested content. No introductions, explanations, or commentary."

    /** Id to prompt, in the desktop's order. */
    val summary: Map<String, String> = linkedMapOf(
        "summary" to "$RULE\n\nWrite a concise summary of this transcript in {language} using markdown. Include:\n- A short overview paragraph\n- 3-5 key takeaways as bullet points\n- Action items as a checklist if there are any\n\n\"\"\"\n{transcript}\n\"\"\"",
        "meeting-notes" to "$RULE\n\nTurn this transcript into structured meeting notes in {language} using markdown. Include:\n- **Topics discussed** as headings\n- Key points under each topic\n- **Decisions** highlighted in bold\n- **Action items** as a checklist at the end\n\n\"\"\"\n{transcript}\n\"\"\"",
        "tldr" to "$RULE\n\nWrite a TL;DR of this transcript in {language} using markdown. Start with a one-paragraph overview, then list the 3-5 most important takeaways as bold bullet points.\n\n\"\"\"\n{transcript}\n\"\"\"",
        "translate" to "$RULE\n\nTranslate this transcript into {language} (the current app language). Preserve the original structure and formatting. Use markdown headings and paragraphs. Do not wrap the output in fenced code blocks.\n\n\"\"\"\n{transcript}\n\"\"\"",
        "article" to "$RULE\n\nRewrite this transcript as a clean, well-structured article in {language}. Use markdown headings and paragraphs.\n\n\"\"\"\n{transcript}\n\"\"\"",
    )

    fun prompt(id: String): String = summary[id] ?: ""
}

/** The prompt a summary runs: the edited one, else its preset's, else the default preset's. */
val AiSettings.summaryPrompt: String
    get() = prompt.takeIf { it.isNotBlank() } ?: AiPresets.prompt(preset).ifEmpty { AiPresets.prompt("summary") }
