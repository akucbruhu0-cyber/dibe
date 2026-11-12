package app.dibe.android.ai

import app.dibe.android.ai.AiClient.Companion.askPrompt
import app.dibe.android.ai.AiClient.Companion.chunkLines
import app.dibe.android.ai.AiClient.Companion.fillPrompt
import app.dibe.android.ai.AiClient.Companion.inputBudgetBytes
import app.dibe.android.ai.AiClient.Companion.outputTokens
import app.dibe.android.ai.AiClient.Companion.speakersOf
import app.dibe.android.ai.AiClient.Companion.transcriptText
import app.dibe.android.ai.AiClient.Companion.utf8Bytes
import app.dibe.android.data.AiThreadEntry
import app.dibe.android.data.Segment
import app.dibe.android.data.TranscriptRecord
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress

class AiClientTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val golden = Json.parseToJsonElement(javaClass.classLoader!!.getResource("ai/golden.json")!!.readText()).jsonObject

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.strings(key: String) = this[key]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun budgetsMatchTheDesktop() {
        for (row in golden["budgets"]!!.jsonArray.map { it.jsonObject }) {
            val context = row["contextTokens"]!!.jsonPrimitive.int
            assertEquals(row["outputTokens"]!!.jsonPrimitive.int, outputTokens(context))
            assertEquals(row["inputBudgetBytes"]!!.jsonPrimitive.int, inputBudgetBytes(context))
        }
        for (row in golden["utf8"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(row.string("value"), row["bytes"]!!.jsonPrimitive.int, utf8Bytes(row.string("value")))
        }
    }

    @Test
    fun promptsAndPresetsMatchTheDesktop() {
        for (row in golden["fills"]!!.jsonArray.map { it.jsonObject }) {
            val values = row["values"]!!.jsonObject
            fun value(key: String) = values[key]?.jsonPrimitive?.content
            assertEquals(
                row.string("result"),
                fillPrompt(row.string("template"), value("transcript"), value("text"), value("language"), value("speakers")),
            )
        }
        val presets = golden["presets"]!!.jsonObject
        assertEquals(presets.keys.toList(), AiPresets.summary.keys.toList())
        for ((id, prompt) in presets) assertEquals(id, prompt.jsonPrimitive.content, AiPresets.prompt(id))
        for ((platform, model) in golden["models"]!!.jsonObject) {
            assertEquals(model.jsonPrimitive.content, defaultModel(AiPlatform.valueOf(platform)))
        }
        assertEquals(AiPresets.prompt("summary"), AiSettings().summaryPrompt)
        assertEquals(AiPresets.prompt("tldr"), AiSettings(preset = "tldr").summaryPrompt)
        assertEquals("mine {transcript}", AiSettings(preset = "custom", prompt = "mine {transcript}").summaryPrompt)
        assertEquals(AiPresets.prompt("summary"), AiSettings(preset = "custom").summaryPrompt)
    }

    @Test
    fun transcriptTextAndChunksMatchTheDesktop() {
        val records = Json.parseToJsonElement(javaClass.classLoader!!.getResource("export/fixtures.json")!!.readText())
            .jsonObject["records"]!!.jsonObject
            .mapValues { json.decodeFromJsonElement(TranscriptRecord.serializer(), it.value) }
        for (row in golden["transcripts"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(row.string("text"), transcriptText(records.getValue(row.string("id")), row.string("label")))
        }
        var split = 0
        for (row in golden["chunks"]!!.jsonArray.map { it.jsonObject }) {
            val expected = row.strings("chunks")
            val actual = chunkLines(row.strings("lines"), row.string("template"), row["contextTokens"]!!.jsonPrimitive.int)
            assertEquals("${row.string("id")} @ ${row["contextTokens"]}", expected, actual)
            if (expected.size > 1) split++
        }
        assertTrue("the fixtures must exercise splitting", split >= 2)
        assertEquals("דנה,   ,  Avi ", speakersOf(records.getValue("hebrew")))
    }

    @Test
    fun askBuildsTheDesktopPrompt() {
        val record = TranscriptRecord(
            name = "n", sourcePath = "", createdAt = "",
            segments = listOf(Segment(0, 1, " hi ", 0), Segment(1, 2, "there")),
            speakerNames = mapOf("0" to "Dana"),
            summary = "the summary",
            thread = listOf(AiThreadEntry("q1", "a1"), AiThreadEntry("q2", "a2")),
        )
        assertEquals(
            "Answer the question about this transcript in Hebrew, using markdown. Be direct and specific; quote the transcript where it helps. If the transcript does not say, say so." +
                "\n\n" + VisualPass.ASK.replace("{language}", "Hebrew") +
                "\n\nTranscript:\n\"\"\"\n[Dana] hi\nthere\n\n\"\"\"" +
                "\n\nEarlier questions:\nQ: q1\nA: a1\n\nQ: q2\nA: a2" +
                "\n\nQuestion: why?",
            askPrompt(record, "  why? ", 65_536, "Hebrew", "Speaker"),
        )
        // Too long for the context: the summary stands in for it.
        val long = record.copy(segments = List(500) { Segment(0, 1, "x".repeat(40)) }, thread = null)
        assertEquals(
            "Answer the question about this transcript in English, using markdown. Be direct and specific; quote the transcript where it helps. If the transcript does not say, say so." +
                "\n\n" + VisualPass.ASK.replace("{language}", "English") +
                "\n\nTranscript (summary):\n\"\"\"\nthe summary\n\"\"\"\n\nQuestion: q",
            askPrompt(long, "q", 8_192, "English", "Speaker"),
        )
    }

    private var server: HttpServer? = null

    @After
    fun stop() {
        server?.stop(0)
    }

    /** A local OpenAI-compatible / Ollama endpoint answering [status] with [body]; records the request. */
    private fun serve(status: Int, body: String, requests: MutableList<Pair<Map<String, String>, String>>): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val headers = exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() } + ("path" to exchange.requestURI.path)
            requests += headers to exchange.requestBody.readBytes().decodeToString()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        this.server = server
        return "http://127.0.0.1:${server.address.port}/v1/"
    }

    @Test
    fun openAiCompatibleRequestAndAnswer() = runTest {
        val requests = mutableListOf<Pair<Map<String, String>, String>>()
        val base = serve(200, """{"choices":[{"message":{"content":"  ## סיכום  "}}]}""", requests)
        val settings = AiSettings(platform = AiPlatform.OPENAI, openaiBaseUrl = base, openaiApiKey = "k", contextTokens = 4096)
        val record = TranscriptRecord(name = "n", sourcePath = "", createdAt = "", segments = listOf(Segment(0, 1, "שלום")))
        assertEquals("## סיכום", AiClient().summarize(record, settings, "Hebrew"))
        val (headers, body) = requests.single()
        assertEquals("/v1/chat/completions", headers["path"])
        assertEquals("Bearer k", headers["authorization"])
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals("gpt-5.6-luna", sent.string("model"))
        assertEquals(1024, sent["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("false", sent["stream"]!!.jsonPrimitive.content)
        val prompt = (sent["messages"] as JsonArray)[0].jsonObject.string("content")
        assertEquals(fillPrompt(AiPresets.prompt("summary"), transcript = "שלום\n", language = "Hebrew", speakers = ""), prompt)
    }

    @Test
    fun ollamaRequestShape() = runTest {
        val requests = mutableListOf<Pair<Map<String, String>, String>>()
        val base = serve(200, """{"response":"OK"}""", requests).removeSuffix("v1/")
        AiClient().test(AiSettings(platform = AiPlatform.OLLAMA, ollamaBaseUrl = base, model = "llama3", contextTokens = 8192))
        val (headers, body) = requests.single()
        assertEquals("/api/generate", headers["path"])
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals("llama3", sent.string("model"))
        assertEquals("Reply with the single word OK.", sent.string("prompt"))
        assertEquals(8192, sent["options"]!!.jsonObject["num_ctx"]!!.jsonPrimitive.int)
        assertEquals(2048, sent["options"]!!.jsonObject["num_predict"]!!.jsonPrimitive.int)
    }

    private suspend fun kindOf(status: Int, body: String): AiException {
        val base = serve(status, body, mutableListOf())
        try {
            AiClient().test(AiSettings(platform = AiPlatform.OPENAI, openaiBaseUrl = base, openaiApiKey = "k"))
        } catch (e: AiException) {
            return e
        } finally {
            server?.stop(0)
            server = null
        }
        fail("expected AiException")
        error("unreachable")
    }

    @Test
    fun httpErrorsMapToKinds() = runTest {
        val auth = kindOf(401, """{"error":{"message":"bad key"}}""")
        assertEquals(AiException.Kind.AUTH, auth.kind)
        assertEquals("OpenAI: 401 Unauthorized · bad key", auth.message)
        assertEquals(AiException.Kind.AUTH, kindOf(403, "").kind)
        assertEquals(AiException.Kind.AUTH, kindOf(400, """{"error":{"message":"API key not valid"}}""").kind)
        assertEquals(AiException.Kind.RATE_LIMIT, kindOf(429, "slow down").kind)
        assertEquals(AiException.Kind.SERVER, kindOf(503, "").kind)
        assertEquals(AiException.Kind.BAD_RESPONSE, kindOf(404, "").kind)
        assertEquals(AiException.Kind.BAD_RESPONSE, kindOf(200, "<html>").kind)
    }

    @Test
    fun missingKeysAndUnreachableHosts() = runTest {
        suspend fun kind(settings: AiSettings) = try {
            AiClient().test(settings)
            null
        } catch (e: AiException) {
            e.kind
        }
        assertEquals(AiException.Kind.NO_KEY, kind(AiSettings(platform = AiPlatform.CLAUDE)))
        assertEquals(AiException.Kind.NO_KEY, kind(AiSettings(platform = AiPlatform.GEMINI)))
        assertEquals(AiException.Kind.NO_KEY, kind(AiSettings(platform = AiPlatform.OPENAI)))
        assertEquals(AiException.Kind.NETWORK, kind(AiSettings(platform = AiPlatform.OLLAMA, ollamaBaseUrl = "http://127.0.0.1:1")))
        assertEquals(AiException.Kind.NETWORK, kind(AiSettings(platform = AiPlatform.OLLAMA, ollamaBaseUrl = "not a url")))
        assertEquals(AiException.Kind.NETWORK, kind(AiSettings(platform = AiPlatform.OLLAMA, ollamaBaseUrl = "ftp://host")))
    }
}
