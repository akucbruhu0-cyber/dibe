package app.dibe.android.ai

import app.dibe.android.ai.AiModel.Badge
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class AiModelsTest {
    private fun ids(models: List<AiModel>?) = models!!.map { it.id }

    @Test
    fun geminiKeepsTextModelsAndPutsFreeFlashFirst() {
        val body = """{"models":[
            {"name":"models/gemini-4-argon","displayName":"Gemini 4 Argon","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/gemini-embedding-2","supportedGenerationMethods":["embedContent"]},
            {"name":"models/gemini-3.1-flash-image","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3.8-flash-tts","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3.5-flash-lite","displayName":"Gemini 3.5 Flash-Lite","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3.8-flash","displayName":"Gemini 3.8 Flash","supportedGenerationMethods":["generateContent"]}
        ],"nextPageToken":""}"""
        val parsed = AiModels.parse(AiPlatform.GEMINI, body)!!
        assertEquals(listOf("gemini-4-argon", "gemini-2.5-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash"), ids(parsed))
        assertEquals("Gemini 3.8 Flash", parsed.last().name)
        val ordered = AiModels.ordered(AiPlatform.GEMINI, parsed)
        // Curated free models in curated order, other free ones after, paid last.
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash", "gemini-4-argon"), ids(ordered))
        assertEquals(listOf(Badge.FREE_TIER, Badge.FREE_TIER, Badge.FREE_TIER, null), ordered.map { it.badge })
    }

    @Test
    fun openAiFiltersOnlyOpenAiItself() {
        val body = """{"object":"list","data":[{"id":"gpt-6-astra"},{"id":"whisper-1"},{"id":"gpt-image-2"},{"id":"gpt-6-luna"},
            {"id":"text-embedding-3-small"},{"id":"o3"},{"id":"gpt-realtime"},{"id":"gpt-5-nano"}]}"""
        val official = AiModels.ordered(AiPlatform.OPENAI, AiModels.parse(AiPlatform.OPENAI, body)!!)
        assertEquals(listOf("gpt-6-luna", "gpt-5-nano", "gpt-6-astra", "o3"), ids(official))
        assertEquals(Badge.LOW_COST, official[1].badge)
        // A custom server's ids are its own: nothing dropped, nothing badged, its order kept.
        val custom = AiModels.ordered(AiPlatform.OPENAI, AiModels.parse(AiPlatform.OPENAI, body, officialOpenAi = false)!!, officialOpenAi = false)
        assertEquals(8, custom.size)
        assertTrue(custom.all { it.badge == null })
        assertEquals("gpt-6-astra", custom.first().id)
    }

    @Test
    fun claudeUsesDisplayNamesAndOnlyCurrentHaikuIsLowCost() {
        val body = """{"data":[{"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5"},
            {"type":"model","id":"claude-haiku-5-5","display_name":"Claude Haiku 5.5"},
            {"type":"model","id":"claude-haiku-4-5","display_name":"Claude Haiku 4.5"}],"has_more":false}"""
        val ordered = AiModels.ordered(AiPlatform.CLAUDE, AiModels.parse(AiPlatform.CLAUDE, body)!!)
        assertEquals(listOf("claude-haiku-5-5", "claude-opus-5-5", "claude-haiku-4-5"), ids(ordered))
        assertEquals(listOf(Badge.LOW_COST, null, null), ordered.map { it.badge })
        assertEquals("Claude Opus 5.5", ordered[1].name)
    }

    @Test
    fun ollamaModelsAreAllLocal() {
        val body = """{"models":[{"name":"llama3:8b","model":"llama3:8b"},{"name":"gemma4:e2b","model":"gemma4:e2b"}]}"""
        val ordered = AiModels.ordered(AiPlatform.OLLAMA, AiModels.parse(AiPlatform.OLLAMA, body)!!)
        assertEquals(listOf("gemma4:e2b", "llama3:8b"), ids(ordered))
        assertTrue(ordered.all { it.badge == Badge.LOCAL })
    }

    @Test
    fun wrongShapesAreNull() {
        assertNull(AiModels.parse(AiPlatform.GEMINI, "<html>portal</html>"))
        assertNull(AiModels.parse(AiPlatform.CLAUDE, """{"models":[]}"""))
        assertNull(AiModels.parse(AiPlatform.OPENAI, "[]"))
    }

    @Test
    fun builtInListsLeadWithRecommendedAndHoldTheDefault() {
        for (platform in AiPlatform.entries) {
            val models = AiModels.builtIn(platform)
            assertTrue(platform.name, models.any { it.id == defaultModel(platform) })
            assertTrue(platform.name, models.first().badge != null)
        }
        assertEquals("gemini-3.8-flash", AiModels.builtIn(AiPlatform.GEMINI).first().id)
        assertEquals("claude-haiku-5-5", AiModels.builtIn(AiPlatform.CLAUDE).first().id)
        assertEquals("gpt-6-luna", AiModels.builtIn(AiPlatform.OPENAI).first().id)
    }

    private var server: HttpServer? = null

    @After
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun listModelsGetsTheProviderList() = runTest {
        val seen = mutableListOf<Triple<String, String, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server = it }
        server.createContext("/") { exchange ->
            seen += Triple(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"))
            val bytes = when (exchange.requestURI.path) {
                "/api/tags" -> """{"models":[{"name":"qwen3:4b","model":"qwen3:4b"}]}"""
                else -> """{"data":[{"id":"local-model"}]}"""
            }.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val openAi = AiClient().listModels(AiSettings(platform = AiPlatform.OPENAI, openaiBaseUrl = "$base/v1/", openaiApiKey = "k"))
        assertEquals(listOf("local-model"), ids(openAi))
        val ollama = AiClient().listModels(AiSettings(platform = AiPlatform.OLLAMA, ollamaBaseUrl = base))
        assertEquals(listOf(AiModel("qwen3:4b", badge = Badge.LOCAL)), ollama)
        assertEquals(listOf(Triple("GET", "/v1/models", "Bearer k"), Triple("GET", "/api/tags", null)), seen)
    }
}
