package app.dibe.android.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A model the picker offers. [name] is the provider's display name, else the id. */
data class AiModel(val id: String, val name: String = id, val badge: Badge? = null) {
    /** FREE_TIER: Google's free tier covers it. LOCAL: runs on the user's own server. LOW_COST: no free tier, but the cheapest. */
    enum class Badge { FREE_TIER, LOCAL, LOW_COST }
}

/** Model lists for the picker: a curated fallback, the providers' list responses, and the order both are shown in. */
object AiModels {
    /**
     * Checked 2026-11-09 against Google's models and pricing pages (aistudio.google.com/docs), OpenAI's
     * models and pricing pages (developers.openai.com/api/docs) and Anthropic's model table. Recommended first.
     */
    private val curated: Map<AiPlatform, List<AiModel>> = mapOf(
        AiPlatform.GEMINI to listOf(
            AiModel("gemini-3.8-flash", "Gemini 3.8 Flash"),
            AiModel("gemini-3.6-flash", "Gemini 3.6 Flash"),
            AiModel("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"),
            AiModel("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite"),
            AiModel("gemini-4-argon", "Gemini 4 Argon"),
            AiModel("gemini-3.1-pro-preview", "Gemini 3.1 Pro Preview"),
        ),
        AiPlatform.OPENAI to listOf(
            AiModel("gpt-6-luna", "GPT-6 Luna"),
            AiModel("gpt-5.6-luna", "GPT-5.6 Luna"),
            AiModel("gpt-6.1-terra", "GPT-6.1 Terra"),
            AiModel("gpt-6.1-sol", "GPT-6.1 Sol"),
            AiModel("gpt-6-astra", "GPT-6 Astra"),
        ),
        AiPlatform.CLAUDE to listOf(
            AiModel("claude-haiku-5-5", "Claude Haiku 5.5"),
            AiModel("claude-sonnet-5-5", "Claude Sonnet 5.5"),
            AiModel("claude-sonnet-5", "Claude Sonnet 5"),
            AiModel("claude-opus-5-5", "Claude Opus 5.5"),
        ),
        AiPlatform.OLLAMA to listOf(AiModel(defaultModel(AiPlatform.OLLAMA))),
    )

    /** The offline list: always includes the platform's default. */
    fun builtIn(platform: AiPlatform, officialOpenAi: Boolean = true): List<AiModel> {
        val models = curated.getValue(platform)
        val withDefault = if (models.any { it.id == defaultModel(platform) }) models else models + AiModel(defaultModel(platform))
        return ordered(platform, withDefault, officialOpenAi)
    }

    /** Badges [models] and sorts them: badged before plain, curated order first within each, the provider's order after. A custom OpenAI-compatible server keeps its own order. */
    fun ordered(platform: AiPlatform, models: List<AiModel>, officialOpenAi: Boolean = true): List<AiModel> {
        val known = if (platform == AiPlatform.OPENAI && !officialOpenAi) emptyList() else curated.getValue(platform)
        val rank = known.withIndex().associate { (index, model) -> model.id to index }
        return models.distinctBy { it.id }
            .map { it.copy(badge = badge(platform, it.id, officialOpenAi)) }
            .sortedWith(compareBy({ it.badge == null }, { rank[it.id] ?: Int.MAX_VALUE }))
    }

    // ponytail: name rules, not prices; a provider renaming its cheap line needs a new rule here.
    private fun badge(platform: AiPlatform, id: String, officialOpenAi: Boolean): AiModel.Badge? = when (platform) {
        AiPlatform.OLLAMA -> AiModel.Badge.LOCAL
        // Every Flash and Flash-Lite text model is on Gemini's free tier; Pro and Argon are not.
        AiPlatform.GEMINI -> AiModel.Badge.FREE_TIER.takeIf { "flash" in id || id.startsWith("gemma-") }
        // luna and nano are OpenAI's ≤ $0.20/M input lines; another server's ids mean nothing here.
        AiPlatform.OPENAI -> AiModel.Badge.LOW_COST.takeIf { officialOpenAi && ("-luna" in id || "-nano" in id) }
        // Haiku 5.x is 10x cheaper than Haiku 4.5, so only the current line is "lowest cost".
        AiPlatform.CLAUDE -> AiModel.Badge.LOW_COST.takeIf { id.startsWith("claude-haiku-5") }
    }

    /** Ids on Google's list that answer generateContent but are not text chat models. */
    private val geminiNonText = listOf("image", "tts", "live", "embedding", "robotics", "transcribe", "audio", "banana", "omni", "customtools", "lyria", "computer-use", "antigravity", "deep-research")

    /** OpenAI's own list mixes in audio, image and embedding models; a custom server's list is taken as is. */
    private val openAiNonText = listOf("audio", "realtime", "tts", "transcribe", "image", "search", "embedding", "instruct", "codex", "moderation")

    /** The models in a provider's list response, unsorted; null when [body] is not that provider's shape. */
    fun parse(platform: AiPlatform, body: String, officialOpenAi: Boolean = true): List<AiModel>? {
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        return when (platform) {
            // GET /v1beta/models: {"models":[{"name":"models/gemini-…","displayName":…,"supportedGenerationMethods":[…]}]}
            AiPlatform.GEMINI -> root.array("models")?.mapNotNull { model ->
                val id = model.text("name")?.removePrefix("models/") ?: return@mapNotNull null
                val methods = (model.field("supportedGenerationMethods") as? JsonArray)?.mapNotNull { it.string() }.orEmpty()
                if ("generateContent" !in methods || geminiNonText.any { it in id }) return@mapNotNull null
                AiModel(id, model.text("displayName") ?: id)
            }
            // GET /models: {"data":[{"id":"gpt-…"}]}
            AiPlatform.OPENAI -> root.array("data")?.mapNotNull { model ->
                val id = model.text("id") ?: return@mapNotNull null
                val text = !officialOpenAi || ((id.startsWith("gpt-") || Regex("^o\\d").containsMatchIn(id)) && openAiNonText.none { it in id })
                AiModel(id).takeIf { text }
            }
            // GET /v1/models: {"data":[{"id":"claude-…","display_name":…}]}, newest first.
            AiPlatform.CLAUDE -> root.array("data")?.mapNotNull { model ->
                val id = model.text("id") ?: return@mapNotNull null
                AiModel(id, model.text("display_name") ?: id)
            }
            // GET /api/tags: {"models":[{"name":"gemma4:e2b","model":"gemma4:e2b"}]}
            AiPlatform.OLLAMA -> root.array("models")?.mapNotNull { model ->
                (model.text("model") ?: model.text("name"))?.let { AiModel(it) }
            }
        }
    }

    private fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)

    private fun JsonElement?.array(key: String): JsonArray? = field(key) as? JsonArray

    private fun JsonElement?.text(key: String): String? = field(key).string()?.takeIf { it.isNotBlank() }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
