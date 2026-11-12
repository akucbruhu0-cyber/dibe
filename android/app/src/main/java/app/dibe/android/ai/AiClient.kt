package app.dibe.android.ai

import app.dibe.android.data.TranscriptRecord
import app.dibe.android.data.jsTrim
import app.dibe.android.export.speakerName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/** Port of desktop/src/lib/ai/client.ts: same presets, placeholders and context chunking. */
class AiClient {
    /**
     * Summary of [record] in [uiLanguage] (a language name, e.g. "Hebrew"). Throws [AiException].
     * A transcript too long for the context is summarized in parts and the parts summarized again.
     * [speakerLabel] names unnamed speakers in the text the model reads, as the desktop's localized label does.
     */
    suspend fun summarize(record: TranscriptRecord, settings: AiSettings, uiLanguage: String, speakerLabel: String = "Speaker"): String {
        if (record.segments.isEmpty()) throw AiException(AiException.Kind.BAD_RESPONSE, "The transcript is empty")
        val template = settings.summaryPrompt
        val speakers = speakersOf(record)
        fun fill(transcript: String) = fillPrompt(template, transcript = transcript, language = uiLanguage, speakers = speakers)
        val chunks = chunkLines(transcriptText(record, speakerLabel).split("\n"), fill(""), settings.contextTokens)
        var source = chunks[0]
        if (chunks.size > 1) {
            // The model sees the partial summaries as the transcript, so the user's prompt still applies.
            val parts = chunks.map { complete(settings, fill(it)).jsTrim() }
            source = parts.withIndex().joinToString("\n\n") { (index, part) -> "--- Part ${index + 1} of ${parts.size} ---\n$part" }
        }
        return complete(settings, fill(source)).jsTrim().ifEmpty {
            throw AiException(AiException.Kind.BAD_RESPONSE, "The model returned an empty summary")
        }
    }

    /**
     * [summary] with charts and diagrams added under its headings (see [VisualPass]). The transcript
     * goes along as far as it fits beside the summary; a long one is cut, the summary still carries it.
     * Throws [AiException].
     */
    suspend fun visualize(record: TranscriptRecord, summary: String, settings: AiSettings, uiLanguage: String, speakerLabel: String = "Speaker"): String {
        val template = VisualPass.PROMPT.replace("{summary}", summary)
        val transcript = chunkLines(transcriptText(record, speakerLabel).split("\n"), fillPrompt(template, transcript = "", language = uiLanguage), settings.contextTokens)[0]
        return VisualPass.merge(summary, complete(settings, fillPrompt(template, transcript = transcript, language = uiLanguage)))
    }

    /** Answer to a question about [record], with its earlier thread as context. Throws [AiException]. */
    suspend fun ask(record: TranscriptRecord, question: String, settings: AiSettings, uiLanguage: String, speakerLabel: String = "Speaker"): String {
        if (question.jsTrim().isEmpty()) throw AiException(AiException.Kind.BAD_RESPONSE, "The question is empty")
        val prompt = askPrompt(record, question, settings.contextTokens, uiLanguage, speakerLabel)
        return complete(settings, prompt).jsTrim().ifEmpty {
            throw AiException(AiException.Kind.BAD_RESPONSE, "The model returned an empty answer")
        }
    }

    /** A short title for [record] ([AutoTitle]); empty when the reply held none. Throws [AiException]. */
    suspend fun title(record: TranscriptRecord, settings: AiSettings, speakerLabel: String = "Speaker"): String =
        AutoTitle.clean(complete(settings, AutoTitle.prompt(transcriptText(record, speakerLabel))))

    /** Throws [AiException] when the connection or key is wrong. */
    suspend fun test(settings: AiSettings) {
        complete(settings, "Reply with the single word OK.")
    }

    /** The chosen platform's models, from the provider itself, badged and in picker order. Throws [AiException]. */
    suspend fun listModels(settings: AiSettings): List<AiModel> {
        val platform = settings.platform
        val (url, headers, label) = when (platform) {
            AiPlatform.OLLAMA -> Triple("${settings.ollamaBaseUrl.trimEnd('/')}/api/tags", emptyMap(), "Ollama")
            AiPlatform.CLAUDE -> {
                if (settings.claudeApiKey.isBlank()) throw AiException(AiException.Kind.NO_KEY, "Claude: no API key")
                Triple("$CLAUDE_MODELS_URL?limit=1000", claudeHeaders(settings), "Claude")
            }
            AiPlatform.OPENAI -> Triple("${openAiBase(settings).trimEnd('/')}/models", bearer(settings.openaiApiKey), "OpenAI")
            AiPlatform.GEMINI -> {
                if (settings.geminiApiKey.isBlank()) throw AiException(AiException.Kind.NO_KEY, "Gemini: no API key")
                // The key rides in a header, not ?key=, so it stays out of URLs that proxies and logs keep.
                Triple("$GEMINI_MODELS_URL?pageSize=1000", mapOf("x-goog-api-key" to settings.geminiApiKey.trim()), "Gemini")
            }
        }
        val official = isOfficialOpenAi(settings)
        val body = request(url, headers, null, label).toString()
        val models = AiModels.parse(platform, body, official)
            ?: throw AiException(AiException.Kind.BAD_RESPONSE, "$label: unreadable model list · ${body.take(300)}")
        return AiModels.ordered(platform, models, official)
    }

    private suspend fun complete(settings: AiSettings, prompt: String): String {
        val model = settings.model.ifBlank { defaultModel(settings.platform) }
        val maxTokens = outputTokens(settings.contextTokens)
        return when (settings.platform) {
            AiPlatform.OLLAMA -> {
                val body = buildJsonObject {
                    put("model", model)
                    put("prompt", prompt)
                    put("stream", false)
                    putJsonObject("options") {
                        put("num_ctx", settings.contextTokens)
                        put("num_predict", maxTokens)
                    }
                }
                val response = request("${settings.ollamaBaseUrl.trimEnd('/')}/api/generate", emptyMap(), body, "Ollama")
                response.field("response").string() ?: ""
            }
            AiPlatform.CLAUDE -> {
                if (settings.claudeApiKey.isBlank()) throw AiException(AiException.Kind.NO_KEY, "Claude: no API key")
                val response = request(CLAUDE_URL, claudeHeaders(settings), chatBody(model, maxTokens, prompt), "Claude")
                response.field("content").item(0).field("text").string() ?: ""
            }
            AiPlatform.OPENAI -> openAiCompatible(openAiBase(settings), settings.openaiApiKey, model, maxTokens, prompt, "OpenAI")
            AiPlatform.GEMINI -> {
                if (settings.geminiApiKey.isBlank()) throw AiException(AiException.Kind.NO_KEY, "Gemini: no API key")
                // Google serves Gemini behind an OpenAI-compatible API, so it shares that client.
                openAiCompatible(GEMINI_OPENAI_BASE_URL, settings.geminiApiKey, model, maxTokens, prompt, "Gemini")
            }
        }
    }

    private suspend fun openAiCompatible(baseUrl: String, apiKey: String, model: String, maxTokens: Int, prompt: String, label: String): String {
        val response = request("${baseUrl.trimEnd('/')}/chat/completions", bearer(apiKey), chatBody(model, maxTokens, prompt), label)
        return response.field("choices").item(0).field("message").field("content").string() ?: ""
    }

    private fun claudeHeaders(settings: AiSettings) = mapOf("x-api-key" to settings.claudeApiKey.trim(), "anthropic-version" to "2023-06-01")

    private fun bearer(apiKey: String) = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${apiKey.trim()}")

    /** The OpenAI base URL; [AiException] when it is OpenAI itself and there is no key. */
    private fun openAiBase(settings: AiSettings): String {
        // A custom base URL is often a local server that wants no key; OpenAI itself always does.
        if (settings.openaiApiKey.isBlank() && isOfficialOpenAi(settings)) throw AiException(AiException.Kind.NO_KEY, "OpenAI: no API key")
        return settings.openaiBaseUrl.ifBlank { OPENAI_BASE_URL }
    }

    private fun chatBody(model: String, maxTokens: Int, prompt: String) = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        put("stream", false)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", prompt)
            }
        }
    }

    /** POST [body] to [url], or GET it when [body] is null. */
    private suspend fun request(url: String, headers: Map<String, String>, body: JsonObject?, label: String): JsonElement = coroutineScope {
        val connection = try {
            val target = URL(url)
            if (target.protocol != "http" && target.protocol != "https") throw IOException("not an http address: $url")
            // Every header here is a key or rides with one; cleartext is allowed app-wide for LAN servers only.
            if (headers.isNotEmpty() && target.protocol == "http" && !isLocalHost(target.host)) {
                throw AiException(AiException.Kind.INSECURE, "$label: an API key is never sent over plain http to ${target.host}; use https")
            }
            target.openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw AiException(AiException.Kind.NETWORK, "$label: ${e.message}")
        }
        // A blocking read ignores cancellation; closing the connection is what ends it.
        val closer = launch(Dispatchers.IO) {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
        try {
            withContext(Dispatchers.IO) { exchange(connection, headers, body?.toString(), label) }
        } finally {
            closer.cancel()
        }
    }

    private fun exchange(connection: HttpURLConnection, headers: Map<String, String>, body: String?, label: String): JsonElement {
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            // A long summary is one response written in full before the first byte arrives.
            connection.readTimeout = READ_TIMEOUT_MS
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val ok = status in 200..299
            val text = (if (ok) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (!ok) throw failure(label, status, connection.responseMessage ?: "", text)
            // Every API here answers with an object; anything else is a proxy or portal page, not the model.
            val parsed = try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                null
            }
            return parsed as? JsonObject ?: throw AiException(AiException.Kind.BAD_RESPONSE, "$label: unreadable response · ${text.take(300)}")
        } catch (e: IOException) {
            throw AiException(AiException.Kind.NETWORK, "$label: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    internal companion object {
        const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
        const val CLAUDE_MODELS_URL = "https://api.anthropic.com/v1/models"
        const val GEMINI_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val GEMINI_OPENAI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 300_000

        /** Output ceiling; the input side of the context is what is left after it and a margin. */
        private const val MAX_OUTPUT_TOKENS = 8_192
        private const val SAFETY_TOKENS = 1_024
        private const val BYTES_PER_TOKEN = 4

        fun isOfficialOpenAi(settings: AiSettings) = settings.openaiBaseUrl.trim().trimEnd('/').ifEmpty { OPENAI_BASE_URL } == OPENAI_BASE_URL

        /** True when [apiKey] would travel to [url] over plain http off the local network: [request] refuses that. */
        fun exposesKey(url: String, apiKey: String): Boolean {
            if (apiKey.isBlank()) return false
            val target = try {
                URL(url.trim())
            } catch (e: MalformedURLException) {
                return false
            }
            return target.protocol == "http" && !isLocalHost(target.host)
        }

        /**
         * Loopback, private LAN (10/8, 172.16/12, 192.168/16), link-local, unique-local (fc00::/7),
         * `localhost` and `*.local`. Literals and names only, never a DNS lookup: what a name resolves to can change.
         */
        fun isLocalHost(host: String): Boolean {
            val name = host.lowercase().removePrefix("[").removeSuffix("]").substringBefore('%').trimEnd('.')
            if (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local")) return true
            if (':' in name) {
                if (name == "::1") return true
                val first = name.substringBefore(':').ifEmpty { return false }.toIntOrNull(16) ?: return false
                return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
            }
            // Canonical decimal only: "010.0.0.1" is 8.0.0.1 to some resolvers.
            val octets = name.split('.').map { part -> part.toIntOrNull()?.takeIf { it in 0..255 && it.toString() == part } ?: return false }
            if (octets.size != 4) return false
            val (a, b) = octets
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }

        fun outputTokens(contextTokens: Int) = maxOf(1, minOf(MAX_OUTPUT_TOKENS, Math.floorDiv(contextTokens, 4)))

        /** Bytes of prompt that fit beside the answer in this context. */
        fun inputBudgetBytes(contextTokens: Int) = maxOf(1, contextTokens - outputTokens(contextTokens) - SAFETY_TOKENS) * BYTES_PER_TOKEN

        fun utf8Bytes(value: String): Int =
            value.codePoints().map { code -> if (code <= 0x7f) 1 else if (code <= 0x7ff) 2 else if (code <= 0xffff) 3 else 4 }.sum()

        /** Fill `{transcript}`, `{text}`, `{language}`, `{speakers}`; `%s` still works for old prompts. */
        fun fillPrompt(template: String, transcript: String? = null, text: String? = null, language: String? = null, speakers: String? = null): String {
            val body = transcript ?: text ?: ""
            return template
                .replace("%s", body)
                .replace("{transcript}", transcript ?: body)
                .replace("{text}", text ?: body)
                .replace("{language}", language ?: "")
                .replace("{speakers}", speakers ?: "")
        }

        /** Split a transcript's lines into pieces that each fit the context beside the prompt, cutting only between lines. */
        fun chunkLines(lines: List<String>, template: String, contextTokens: Int): List<String> {
            val budget = inputBudgetBytes(contextTokens) - utf8Bytes(template)
            val chunks = mutableListOf<String>()
            val current = mutableListOf<String>()
            var size = 0
            for (line in lines) {
                val bytes = utf8Bytes(line) + 1
                if (size + bytes > budget && current.isNotEmpty()) {
                    chunks += current.joinToString("\n")
                    current.clear()
                    size = 0
                }
                current += line
                size += bytes
            }
            if (current.isNotEmpty()) chunks += current.joinToString("\n")
            return chunks.ifEmpty { listOf("") }
        }

        /** The desktop's `asText`: one line per segment, speakers in brackets. */
        fun transcriptText(record: TranscriptRecord, speakerLabel: String): String = record.segments.joinToString("") { segment ->
            val prefix = segment.speaker?.let { "[${speakerName(it, speakerLabel, record.speakerNames)}] " } ?: ""
            "$prefix${segment.text.jsTrim()}\n"
        }

        /** Named speakers in index order, as JS `Object.values` lists integer keys. */
        fun speakersOf(record: TranscriptRecord): String =
            record.speakerNames.orEmpty().entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.joinToString(", ") { it.value }

        /** The desktop's question prompt (use-summaries.ts `ask`). */
        fun askPrompt(record: TranscriptRecord, question: String, contextTokens: Int, uiLanguage: String, speakerLabel: String): String {
            // The whole transcript when it fits, otherwise the summary, which still answers most questions.
            val transcript = transcriptText(record, speakerLabel)
            val budget = inputBudgetBytes(contextTokens) - 4_000
            val fits = utf8Bytes(transcript) < budget
            val context = if (fits) transcript else record.summary ?: transcript.take(maxOf(0, budget))
            val history = record.thread.orEmpty().joinToString("\n\n") { "Q: ${it.question}\nA: ${it.answer}" }
            return listOf(
                "Answer the question about this transcript in $uiLanguage, using markdown. Be direct and specific; quote the transcript where it helps. If the transcript does not say, say so.",
                VisualPass.ASK.replace("{language}", uiLanguage),
                "Transcript${if (fits) "" else " (summary)"}:\n\"\"\"\n$context\n\"\"\"",
                if (history.isNotEmpty()) "Earlier questions:\n$history" else "",
                "Question: ${question.jsTrim()}",
            ).filter { it.isNotEmpty() }.joinToString("\n\n")
        }

        /** The desktop's error text (`Label: 401 Unauthorized · detail`), with a kind the UI can act on. */
        fun failure(label: String, status: Int, statusText: String, body: String): AiException {
            val detail = try {
                val error = (Json.parseToJsonElement(body) as? JsonObject)?.get("error")
                error.field("message").string() ?: (error as? JsonPrimitive)?.content ?: body
            } catch (e: SerializationException) {
                body
            }
            val kind = when {
                status == 401 || status == 403 -> AiException.Kind.AUTH
                // ponytail: Gemini answers a wrong key with 400; matching its wording is the only tell.
                status == 400 && detail.contains("API key", ignoreCase = true) -> AiException.Kind.AUTH
                status == 429 -> AiException.Kind.RATE_LIMIT
                status >= 500 -> AiException.Kind.SERVER
                else -> AiException.Kind.BAD_RESPONSE
            }
            val message = "$label: $status $statusText".trimEnd() + if (detail.isNotEmpty()) " · ${detail.take(300)}" else ""
            return AiException(kind, message)
        }

        private fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)

        private fun JsonElement?.item(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

        private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

class AiException(val kind: Kind, message: String) : Exception(message) {
    /** INSECURE: a key would go out over plain http to a host off the local network. */
    enum class Kind { NO_KEY, AUTH, NETWORK, RATE_LIMIT, SERVER, BAD_RESPONSE, INSECURE }
}
